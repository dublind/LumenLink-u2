package cl.ti3042.lumenlink.core

/**
 * LOGICA.KT: reglas y formatos compartidos, reunidos en tres bloques.
 * No utiliza pantallas ni Android Keystore: estas clases se pueden probar en la JVM.
 * class crea objetos con estado; object declara una única instancia; data class representa datos.
 * require rechaza entradas inválidas. En la regla, || significa O y <= incluye el umbral.
 * companion object agrupa funciones que se llaman desde el nombre de la clase, como Reading.parse().
 * Los import de Java y javax permiten usar las herramientas estándar de red y criptografía.
 */

// JSON: convierte los mensajes y las lecturas a un formato intercambiable.
import org.json.JSONObject

// Java: flujos de bytes, red, fechas, certificados y trabajo en otros hilos.
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate

// Criptografía y TLS: cifrado, contraseñas y canal de comunicación seguro.
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.net.ssl.X509TrustManager

// ============================================================================
// BLOQUE 1: REGLA DE LA LUZ
// LightingController decide el encendido a partir de distancia, iluminación y modo manual.
// ============================================================================

/** Controla la luz con distancia en cm e iluminación relativa de la LDR. */
class LightingController {
    // private set permite consultar estos valores desde fuera, pero cambiarlos solo mediante las funciones.
    var distanceCm = 100.0
        private set
    var ldrPercent = 80.0
        private set
    var lightOn = false
        private set
    var automatic = true
        private set
    var thresholdCm = 30.0
        private set
    var ldrThresholdPercent = 30.0
        private set

    // Comprueba los dos sensores antes de cambiar valores y vuelve a evaluar la luz.
    fun updateSensors(distanceCm: Double, ldrPercent: Double) {
        require(distanceCm.isFinite() && distanceCm in 0.0..500.0) { "Distancia inválida" }
        require(ldrPercent.isFinite() && ldrPercent in 0.0..100.0) { "Nivel LDR inválido" }
        this.distanceCm = distanceCm
        this.ldrPercent = ldrPercent
        evaluate()
    }
    // Cambia el umbral de cercanía si está entre 5 y 200 cm.
    fun setThreshold(value: Double) {
        require(value.isFinite() && value in 5.0..200.0) { "Umbral inválido" }
        thresholdCm = value
        evaluate()
    }
    // Cambia el umbral de oscuridad si está entre 0 y 100 %.
    fun setLdrThreshold(value: Double) {
        require(value.isFinite() && value in 0.0..100.0) { "Umbral LDR inválido" }
        ldrThresholdPercent = value
        evaluate()
    }
    // La orden manual se conserva hasta volver al modo automático.
    fun manualLight(enabled: Boolean) { automatic = false; lightOn = enabled }
    // Devuelve el control a los sensores y recalcula inmediatamente la luz.
    fun enableAutomatic() { automatic = true; evaluate() }
    private fun evaluate() {
        // Basta con cercanía O oscuridad. El valor exacto del umbral también activa la luz.
        if (automatic) lightOn = distanceCm <= thresholdCm || ldrPercent <= ldrThresholdPercent
    }
    // Crea una lectura con una copia del estado actual para enviarla o guardarla.
    fun reading(id: String, at: Long) = Reading(id, at, distanceCm, ldrPercent, lightOn, automatic, thresholdCm, ldrThresholdPercent)
}

// ============================================================================
// BLOQUE 2: MENSAJES, EMPAREJAMIENTO Y LECTURAS
// Frames delimita mensajes; Pairing empareja; PinnedTrust verifica el certificado; Reading representa sensores.
// ============================================================================

// Cada mensaje lleva su longitud porque TCP puede dividir o juntar los datos.
object Frames {
    const val MAX_BYTES = 256 * 1024
    // Escribe primero la longitud del JSON y después sus bytes en UTF-8.
    fun write(out: DataOutputStream, message: JSONObject) {
        val bytes = message.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..MAX_BYTES) { "Mensaje demasiado grande" }
        out.writeInt(bytes.size)
        out.write(bytes)
        out.flush()
    }
    // Valida la longitud y espera los bytes completos antes de interpretar el JSON.
    fun read(input: DataInputStream): JSONObject {
        val size = input.readInt()
        // Rechaza tamaños inválidos antes de reservar memoria.
        require(size in 1..MAX_BYTES) { "Longitud de mensaje inválida" }
        return JSONObject(String(ByteArray(size).also { input.readFully(it) }, Charsets.UTF_8))
    }
}

data class Pairing(val host: String, val port: Int, val fingerprint: String, val token: String) {
    // Convierte la configuración de conexión al texto que se copia al otro teléfono.
    fun encode(): String = "LUMEN1|$host|$port|$fingerprint|$token"
    companion object {
        // Separa y valida IPv4, puerto, huella del certificado y token.
        fun parse(text: String): Pairing {
            val p = text.trim().split('|')
            require(p.size == 5 && p[0] == "LUMEN1") { "Configuración de conexión inválida" }
            val octets = p[1].split('.')
            require(octets.size == 4 && octets.all { it.matches(Regex("[0-9]{1,3}")) && (it.toIntOrNull() ?: -1) in 0..255 }) { "Dirección IPv4 inválida" }
            val port = p[2].toIntOrNull() ?: error("Puerto inválido")
            require(port in 1024..65535) { "Puerto fuera de rango" }
            require(p[3].matches(Regex("[a-fA-F0-9]{64}"))) { "Huella de certificado inválida" }
            require(p[4].matches(Regex("[a-fA-F0-9]{32}"))) { "Token inválido" }
            return Pairing(p[1], port, p[3].lowercase(), p[4].lowercase())
        }
    }
}

// Calcula la huella SHA-256 que identifica el certificado del nodo.

fun fingerprint(cert: X509Certificate): String =
    MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }

// Solo acepta el certificado cuya huella se recibió al emparejar el nodo.
class PinnedTrust(private val pin: String) : X509TrustManager {
    // La identidad se comprueba con la huella transferida al emparejar, no con una lista de emisores.
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    // El panel usa un token: este protocolo no autentica al cliente con un certificado propio.
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        throw java.security.cert.CertificateException("No se utiliza autenticación de certificado de cliente")
    // Rechaza certificados vencidos o cuya huella no sea la del nodo autorizado.
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val certificate = chain?.firstOrNull() ?: throw java.security.cert.CertificateException("Sin certificado")
        certificate.checkValidity()
        if (!MessageDigest.isEqual(fingerprint(certificate).toByteArray(), pin.toByteArray())) {
            throw java.security.cert.CertificateException("La huella no coincide con el dispositivo autorizado")
        }
    }
}

/** Lectura compartida por red e historial. La LDR usa porcentaje relativo, no lux. */
data class Reading(val id: String, val at: Long, val distanceCm: Double, val ldrPercent: Double,
    val lightOn: Boolean, val automatic: Boolean, val thresholdCm: Double, val ldrThresholdPercent: Double) {
    // Convierte la lectura en campos JSON que comparten la red y el historial.
    fun json(): JSONObject = JSONObject().put("id", id).put("at", at)
        .put("distanceCm", distanceCm).put("ldrPercent", ldrPercent).put("lightOn", lightOn)
        .put("automatic", automatic).put("thresholdCm", thresholdCm).put("ldrThresholdPercent", ldrThresholdPercent)
    companion object {
        // Recupera todos los campos del JSON y rechaza valores fuera de los rangos del simulador.
        fun parse(o: JSONObject): Reading {
            val r = Reading(o.getString("id"), o.getLong("at"), o.getDouble("distanceCm"),
                o.getDouble("ldrPercent"), o.getBoolean("lightOn"), o.getBoolean("automatic"), o.getDouble("thresholdCm"), o.getDouble("ldrThresholdPercent"))
            require(r.id.length in 1..80 && r.at > 0 && r.distanceCm.isFinite() && r.distanceCm in 0.0..500.0 &&
                r.thresholdCm.isFinite() && r.thresholdCm in 5.0..200.0 &&
                r.ldrPercent.isFinite() && r.ldrPercent in 0.0..100.0 &&
                r.ldrThresholdPercent.isFinite() && r.ldrThresholdPercent in 0.0..100.0) { "Lectura fuera de rango" }
            return r
        }
    }
}

// ============================================================================
// BLOQUE 3: PROTECCIÓN DE CONTRASEÑAS
// PasswordHasher deriva contraseñas con PBKDF2 y valida las entradas de las cuentas.
// ============================================================================

/** Guarda derivados de contraseña; no guarda la contraseña original. */
object PasswordHasher {
    const val ITERATIONS = 210_000
    // Una sal distinta evita que dos cuentas tengan el mismo hash por usar la misma clave.
    fun salt(): ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }
    // Deriva 256 bits con PBKDF2; al terminar borra la copia temporal de la contraseña.
    fun hash(password: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }
    // Compara los derivados sin detenerse en el primer byte diferente.
    fun matches(password: CharArray, salt: ByteArray, expected: ByteArray): Boolean =
        MessageDigest.isEqual(hash(password, salt), expected)
    // Acepta entre 3 y 32 letras, números, puntos, guiones o guiones bajos.
    fun validUser(value: String): Boolean = value.matches(Regex("[a-zA-Z0-9._-]{3,32}"))
    // Acepta contraseñas de 12 a 128 caracteres.
    fun validPassword(value: String): Boolean = value.length in 12..128
}
