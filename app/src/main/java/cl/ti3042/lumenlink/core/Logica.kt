package cl.ti3042.lumenlink.core

import org.json.JSONObject

import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate

import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.net.ssl.X509TrustManager

// BLOQUE 1: REGLA DE LA LUZ

class LightingController {
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

    fun updateSensors(distanceCm: Double, ldrPercent: Double) {
        require(distanceCm.isFinite() && distanceCm in 0.0..500.0) { "Distancia inválida" }
        require(ldrPercent.isFinite() && ldrPercent in 0.0..100.0) { "Nivel LDR inválido" }
        this.distanceCm = distanceCm
        this.ldrPercent = ldrPercent
        evaluate()
    }
    fun setThreshold(value: Double) {
        require(value.isFinite() && value in 5.0..200.0) { "Umbral inválido" }
        thresholdCm = value
        evaluate()
    }
    fun setLdrThreshold(value: Double) {
        require(value.isFinite() && value in 0.0..100.0) { "Umbral LDR inválido" }
        ldrThresholdPercent = value
        evaluate()
    }
    // La orden manual se conserva hasta volver al modo automático.
    fun manualLight(enabled: Boolean) { automatic = false; lightOn = enabled }
    fun enableAutomatic() { automatic = true; evaluate() }
    private fun evaluate() {
        // Basta con cercanía O oscuridad. El valor exacto del umbral también activa la luz.
        if (automatic) lightOn = distanceCm <= thresholdCm || ldrPercent <= ldrThresholdPercent
    }
    fun reading(id: String, at: Long) = Reading(id, at, distanceCm, ldrPercent, lightOn, automatic, thresholdCm, ldrThresholdPercent)
}

// BLOQUE 2: MENSAJES, EMPAREJAMIENTO Y LECTURAS

// Cada mensaje lleva su longitud porque TCP puede dividir o juntar los datos.
object Frames {
    const val MAX_BYTES = 256 * 1024
    fun write(out: DataOutputStream, message: JSONObject) {
        val bytes = message.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..MAX_BYTES) { "Mensaje demasiado grande" }
        out.writeInt(bytes.size)
        out.write(bytes)
        out.flush()
    }
    fun read(input: DataInputStream): JSONObject {
        val size = input.readInt()
        // Rechaza tamaños inválidos antes de reservar memoria.
        require(size in 1..MAX_BYTES) { "Longitud de mensaje inválida" }
        return JSONObject(String(ByteArray(size).also { input.readFully(it) }, Charsets.UTF_8))
    }
}

data class Pairing(val host: String, val port: Int, val fingerprint: String, val token: String) {
    fun encode(): String = "LUMEN1|$host|$port|$fingerprint|$token"
    companion object {
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

fun fingerprint(cert: X509Certificate): String =
    MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }

class PinnedTrust(private val pin: String) : X509TrustManager {
    // La identidad se comprueba con la huella transferida al emparejar, no con una lista de emisores.
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    // El panel usa un token: este protocolo no autentica al cliente con un certificado propio.
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        throw java.security.cert.CertificateException("No se utiliza autenticación de certificado de cliente")
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
    fun json(): JSONObject = JSONObject().put("id", id).put("at", at)
        .put("distanceCm", distanceCm).put("ldrPercent", ldrPercent).put("lightOn", lightOn)
        .put("automatic", automatic).put("thresholdCm", thresholdCm).put("ldrThresholdPercent", ldrThresholdPercent)
    companion object {
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

// BLOQUE 3: CONTRASEÑAS

object PasswordHasher {
    const val ITERATIONS = 210_000
    // Una sal distinta evita que dos cuentas tengan el mismo hash por usar la misma clave.
    fun salt(): ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }
    fun hash(password: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }
    // Compara los derivados sin detenerse en el primer byte diferente.
    fun matches(password: CharArray, salt: ByteArray, expected: ByteArray): Boolean =
        MessageDigest.isEqual(hash(password, salt), expected)
    fun validUser(value: String): Boolean = value.matches(Regex("[a-zA-Z0-9._-]{3,32}"))
    fun validPassword(value: String): Boolean = value.length in 6..128
}

// BLOQUE 4: HISTORIAL Y CÁLCULOS

enum class HistoryFilter(val label: String) {
    ALL("Todas"), LIGHT_ON("Luz ON"), LIGHT_OFF("Luz OFF"), AUTOMATIC("Auto"), MANUAL("Manual");
    fun matches(r: Reading): Boolean = when (this) {
        ALL -> true
        LIGHT_ON -> r.lightOn
        LIGHT_OFF -> !r.lightOn
        AUTOMATIC -> r.automatic
        MANUAL -> !r.automatic
    }
}

data class ReadingStats(val count: Int, val avgDistanceCm: Double, val minDistanceCm: Double, val maxDistanceCm: Double,
    val avgLdrPercent: Double, val lightOnPercent: Double, val automaticPercent: Double) {
    companion object {
        fun of(rows: List<Reading>): ReadingStats? {
            if (rows.isEmpty()) return null
            val n = rows.size.toDouble()
            return ReadingStats(rows.size, rows.sumOf { it.distanceCm } / n, rows.minOf { it.distanceCm },
                rows.maxOf { it.distanceCm }, rows.sumOf { it.ldrPercent } / n,
                rows.count { it.lightOn } * 100.0 / n, rows.count { it.automatic } * 100.0 / n)
        }
    }
}

object EnergyCalculator {
    const val DAYS_PER_MONTH = 30
    data class Estimate(val hoursOnPerDay: Double, val kwhPerMonth: Double, val costPerMonthClp: Double,
        val savedKwhPerMonth: Double, val savedClpPerMonth: Double)
    fun validate(watts: Double?, hoursPerDay: Double?, tariffClp: Double?): String? = when {
        watts == null || !watts.isFinite() || watts !in 1.0..500.0 -> "Potencia: ingresa entre 1 y 500 W."
        hoursPerDay == null || !hoursPerDay.isFinite() || hoursPerDay !in 1.0..24.0 -> "Horas de uso: ingresa entre 1 y 24 horas al día."
        tariffClp == null || !tariffClp.isFinite() || tariffClp !in 1.0..2000.0 -> "Tarifa: ingresa entre 1 y 2.000 CLP por kWh."
        else -> null
    }
    // kWh = W × horas encendida × 30 días / 1000; el costo multiplica esa energía por la tarifa.
    fun estimate(lightOnPercent: Double, watts: Double, hoursPerDay: Double, tariffClp: Double): Estimate {
        require(lightOnPercent.isFinite() && lightOnPercent in 0.0..100.0) { "Porcentaje encendida inválido" }
        validate(watts, hoursPerDay, tariffClp)?.let { throw IllegalArgumentException(it) }
        val hoursOn = hoursPerDay * lightOnPercent / 100.0
        val kwh = watts * hoursOn * DAYS_PER_MONTH / 1000.0
        val alwaysOnKwh = watts * hoursPerDay * DAYS_PER_MONTH / 1000.0
        return Estimate(hoursOn, kwh, kwh * tariffClp, alwaysOnKwh - kwh, (alwaysOnKwh - kwh) * tariffClp)
    }
}
