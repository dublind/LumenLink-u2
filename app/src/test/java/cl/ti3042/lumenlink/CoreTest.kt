package cl.ti3042.lumenlink

/**
 * CORETEST.KT: las 22 pruebas JVM están reunidas aquí, en cuatro clases.
 * Se ejecutan en el computador con testDebugUnitTest; no necesitan un emulador.
 * @Test identifica una prueba. Assert.assertTrue exige verdadero y assertFalse exige falso.
 * Assert.assertEquals compara resultados; assertThrows comprueba que una operación sea rechazada.
 * @BeforeClass prepara el certificado una sola vez antes de las pruebas de su clase.
 */

// Nuestro proyecto: reglas, lecturas y formato de la conexión.
import cl.ti3042.lumenlink.core.EnergyCalculator
import cl.ti3042.lumenlink.core.Frames
import cl.ti3042.lumenlink.core.HistoryFilter
import cl.ti3042.lumenlink.core.LightingController
import cl.ti3042.lumenlink.core.Pairing
import cl.ti3042.lumenlink.core.PasswordHasher
import cl.ti3042.lumenlink.core.PinnedTrust
import cl.ti3042.lumenlink.core.Reading
import cl.ti3042.lumenlink.core.ReadingStats
import cl.ti3042.lumenlink.core.fingerprint

// JSON: convierte los mensajes y las lecturas a un formato intercambiable.
import org.json.JSONObject

// JUnit: marca las pruebas y comprueba sus resultados con Assert.
import org.junit.Assert
import org.junit.BeforeClass
import org.junit.Test

// Java: flujos de bytes, red, fechas, certificados y trabajo en otros hilos.
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// Criptografía y TLS: cifrado, contraseñas y canal de comunicación seguro.
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager

// BLOQUE 1: CUENTAS Y FORMATO DE DATOS. CoreTest verifica las contraseñas y los mensajes.

class CoreTest {
    // Una contraseña válida coincide con su derivado; una incorrecta debe rechazarse.
    @Test fun passwordCanBeVerifiedAndWrongPasswordIsRejected() {
        val salt = PasswordHasher.salt()
        val hash = PasswordHasher.hash("Clave larga de prueba 2026".toCharArray(), salt)
        Assert.assertEquals(32, hash.size)
        Assert.assertTrue(PasswordHasher.matches("Clave larga de prueba 2026".toCharArray(), salt, hash))
        Assert.assertFalse(PasswordHasher.matches("Contraseña incorrecta".toCharArray(), salt, hash))
    }
    // La misma contraseña produce derivados diferentes al usar sales distintas.
    @Test fun saltsPreventEqualPasswordHashes() {
        val pass = "Contraseña para prueba".toCharArray()
        Assert.assertFalse(PasswordHasher.hash(pass, PasswordHasher.salt()).contentEquals(
            PasswordHasher.hash(pass, PasswordHasher.salt())))
    }
    // Comprueba el formato del usuario y los límites de la contraseña.
    @Test fun accountInputHasBounds() {
        Assert.assertFalse(PasswordHasher.validUser("' OR 1=1 --"))
        Assert.assertFalse(PasswordHasher.validUser("ab"))
        Assert.assertTrue(PasswordHasher.validUser("estudiante_01"))
        Assert.assertFalse(PasswordHasher.validPassword("corta"))
        Assert.assertTrue(PasswordHasher.validPassword("seis66"))
        Assert.assertFalse(PasswordHasher.validPassword("a".repeat(129)))
    }
    // Dos mensajes seguidos, incluso con tildes, deben recuperarse por separado.
    @Test fun framingHandlesUnicodeAndMultipleMessages() {
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        Frames.write(out, JSONObject().put("type", "lectura").put("label", "Distancia ultrasónica cm"))
        Frames.write(out, JSONObject().put("type", "command").put("action", "light_on"))
        val input = DataInputStream(ByteArrayInputStream(bytes.toByteArray()))
        Assert.assertEquals("Distancia ultrasónica cm", Frames.read(input).getString("label"))
        Assert.assertEquals("light_on", Frames.read(input).getString("action"))
    }
    // Rechaza longitudes negativas, cero y superiores al límite permitido.
    @Test fun framingRejectsNegativeAndExcessiveLengths() {
        for (size in listOf(-1, 0, Frames.MAX_BYTES + 1)) {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).writeInt(size)
            Assert.assertThrows(IllegalArgumentException::class.java) {
                Frames.read(DataInputStream(ByteArrayInputStream(bytes.toByteArray())))
            }
        }
    }
    // Rechaza un mensaje que anuncia más bytes de los que realmente contiene.
    @Test fun framingRejectsTruncatedFrames() {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).apply { writeInt(100); write(byteArrayOf(1, 2)) }
        Assert.assertThrows(EOFException::class.java) { Frames.read(DataInputStream(ByteArrayInputStream(bytes.toByteArray()))) }
    }
    private val pair = Pairing("192.168.1.20", 8842, "a".repeat(64), "b".repeat(32))
    // La configuración copiada debe conservar todos sus datos al volver a interpretarse.
    @Test fun pairingRoundTrip() { Assert.assertEquals(pair, Pairing.parse(pair.encode())) }
    // Rechaza una IP, un puerto, una huella o un token con formato incorrecto.
    @Test fun pairingRejectsBadIpPinTokenAndPort() {
        for (value in listOf(pair.copy(host = "999.1.1.1"), pair.copy(port = 80),
            pair.copy(fingerprint = "not-a-pin"), pair.copy(token = "1234"))) {
            Assert.assertThrows(IllegalArgumentException::class.java) { Pairing.parse(value.encode()) }
        }
    }
    // Convertir una lectura a JSON y recuperarla debe conservar sus campos.
    @Test fun readingRoundTripPreservesIdentityAndControlState() {
        val r = Reading("sample-001", 1234, 27.5, 80.0, true, false, 30.0, 30.0)
        Assert.assertEquals(r, Reading.parse(r.json()))
    }
    // Rechaza lecturas fuera de rango o sin identidad y fecha válidas.
    @Test fun readingsRejectUnsafeNumericValues() {
        val r = Reading("id", 1234, 100.0, 80.0, false, true, 30.0, 30.0)
        for (invalid in listOf(r.copy(distanceCm = 1000.0), r.copy(distanceCm = -1.0),
            r.copy(thresholdCm = 201.0), r.copy(ldrPercent = -1.0), r.copy(ldrPercent = 101.0),
            r.copy(ldrThresholdPercent = -1.0), r.copy(id = ""), r.copy(at = -1))) {
            Assert.assertThrows(IllegalArgumentException::class.java) { Reading.parse(invalid.json()) }
        }
    }
}

// BLOQUE 2: REGLA AUTOMÁTICA Y MODO MANUAL. LightingControllerTest comprueba los sensores y sus límites.

class LightingControllerTest {
    // Comprueba las cuatro combinaciones de cerca/lejos y oscuro/iluminado.
    @Test fun automaticRuleCoversEveryCombinationOfSensors() {
        val model = LightingController()
        for ((distance, illumination, expected) in listOf(
            Triple(100.0, 80.0, false), Triple(10.0, 80.0, true),
            Triple(100.0, 10.0, true), Triple(10.0, 10.0, true))) {
            model.updateSensors(distance, illumination)
            Assert.assertEquals("distance=$distance illumination=$illumination", expected, model.lightOn)
        }
    }
    // El valor exacto del umbral activa la luz; cambiarlo recalcula el resultado.
    @Test fun thresholdIncludesItsBoundaryAndReevaluatesImmediately() {
        val model = LightingController()
        model.updateSensors(30.0, 80.0)
        Assert.assertTrue(model.lightOn)
        model.updateSensors(30.1, 80.0)
        Assert.assertFalse(model.lightOn)
        model.setThreshold(40.0)
        Assert.assertTrue(model.lightOn)
    }
    // Los sensores no sustituyen una orden manual hasta volver a automático.
    @Test fun manualOverridePersistsUntilAutomaticIsEnabled() {
        val model = LightingController()
        model.manualLight(false)
        model.updateSensors(5.0, 10.0)
        Assert.assertFalse(model.lightOn)
        Assert.assertFalse(model.automatic)
        model.enableAutomatic()
        Assert.assertTrue(model.lightOn)
        model.updateSensors(100.0, 80.0)
        Assert.assertFalse(model.lightOn)
        model.manualLight(true)
        Assert.assertTrue(model.lightOn)
    }
    // Un sensor o umbral inválido debe dejar intacto el último estado válido.
    @Test fun invalidSensorValuesDoNotChangeTheLastValidState() {
        val model = LightingController()
        model.updateSensors(10.0, 80.0)
        for (bad in listOf(-1.0, 501.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            Assert.assertThrows(IllegalArgumentException::class.java) { model.updateSensors(bad, 80.0) }
            Assert.assertEquals(10.0, model.distanceCm, 0.0)
            Assert.assertTrue(model.lightOn)
        }
        for (bad in listOf(4.0, 201.0, Double.NaN)) {
            Assert.assertThrows(IllegalArgumentException::class.java) { model.setThreshold(bad) }
            Assert.assertEquals(30.0, model.thresholdCm, 0.0)
        }
    }
    // Una LDR menor significa oscuridad; comprueba el límite y el cambio de umbral.
    @Test fun ldrBoundaryAndThresholdChangesUseTheCorrectPolarity() {
        val model = LightingController()
        model.updateSensors(100.0, 30.0)
        Assert.assertTrue(model.lightOn)
        model.updateSensors(100.0, 30.1)
        Assert.assertFalse(model.lightOn)
        model.setLdrThreshold(40.0)
        Assert.assertTrue(model.lightOn)
        model.updateSensors(100.0, 100.0)
        Assert.assertFalse(model.lightOn)
        model.updateSensors(100.0, 0.0)
        Assert.assertTrue(model.lightOn)
    }
    // Si la LDR es inválida, no se modifica ninguno de los dos sensores.
    @Test fun invalidLdrInputIsRejectedAtomically() {
        val model = LightingController()
        for (bad in listOf(-1.0, 101.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            Assert.assertThrows(IllegalArgumentException::class.java) { model.updateSensors(5.0, bad) }
            Assert.assertEquals(100.0, model.distanceCm, 0.0)
            Assert.assertEquals(80.0, model.ldrPercent, 0.0)
            Assert.assertThrows(IllegalArgumentException::class.java) { model.setLdrThreshold(bad) }
            Assert.assertEquals(30.0, model.ldrThresholdPercent, 0.0)
        }
    }
}

// BLOQUE 3: TRANSPORTE TLS REAL. TlsIntegrationTest crea un certificado temporal e intercambia mensajes.

/** Intercambia mensajes TLS reales en la misma máquina para verificar el transporte. */
class TlsIntegrationTest {
    companion object {
        lateinit var store: KeyStore
        lateinit var cert: X509Certificate
        // Genera un certificado desechable con keytool antes de las dos pruebas TLS.
        @JvmStatic @BeforeClass fun certificate() {
            val dir = Files.createTempDirectory("lumenlink-tls-test").toFile()
            val file = File(dir, "test.p12")
            val executable = File(System.getProperty("java.home"), "bin/keytool" + if (System.getProperty("os.name").orEmpty().startsWith("Windows")) ".exe" else "")
            val process = ProcessBuilder(executable.path, "-genkeypair", "-alias", "test", "-keyalg", "RSA",
                "-keysize", "2048", "-storetype", "PKCS12", "-keystore", file.path, "-storepass", "test-only-password",
                "-keypass", "test-only-password", "-dname", "CN=LumenLink test", "-validity", "2", "-noprompt")
                .redirectErrorStream(true).start()
            val log = process.inputStream.bufferedReader().readText()
            check(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) { log }
            store = KeyStore.getInstance("PKCS12").apply { file.inputStream().use { load(it, "test-only-password".toCharArray()) } }
            cert = store.getCertificate("test") as X509Certificate
            file.delete(); dir.delete()
        }
    }
    // El certificado conocido se acepta; una huella distinta debe rechazarse.
    @Test fun certificatePinRejectsImpersonation() {
        PinnedTrust(fingerprint(cert)).checkServerTrusted(arrayOf(cert), "RSA")
        Assert.assertThrows(CertificateException::class.java) {
            PinnedTrust("0".repeat(64)).checkServerTrusted(arrayOf(cert), "RSA")
        }
    }
    // Intercambia un comando y una lectura sobre una conexión TLS real en la misma máquina.
    @Test fun encryptedControlAndTelemetryRoundTrip() {
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(store, "test-only-password".toCharArray())
        }
        val serverContext = SSLContext.getInstance("TLSv1.2").apply { init(managers.keyManagers, null, null) }
        val clientContext = SSLContext.getInstance("TLSv1.2").apply {
            init(null, arrayOf<TrustManager>(PinnedTrust(fingerprint(cert))), null)
        }
        val server = serverContext.serverSocketFactory.createServerSocket(0, 1, InetAddress.getLoopbackAddress()) as SSLServerSocket
        server.soTimeout = 10_000
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<String> {
                (server.accept() as SSLSocket).use { socket ->
                    socket.soTimeout = 10_000
                    val command = Frames.read(DataInputStream(socket.inputStream))
                    require(command.getString("action") == "light_on")
                    Frames.write(DataOutputStream(socket.outputStream), JSONObject().put("type", "reading")
                        .put("data", Reading("tls-test", 1234, 10.0, 80.0, true, false, 30.0, 30.0).json()))
                    socket.session.protocol
                }
            }
            (clientContext.socketFactory.createSocket(InetAddress.getLoopbackAddress(), server.localPort) as SSLSocket).use { socket ->
                socket.soTimeout = 10_000
                socket.enabledProtocols = arrayOf("TLSv1.2")
                Frames.write(DataOutputStream(socket.outputStream), JSONObject().put("type", "command").put("action", "light_on"))
                val result = Reading.parse(Frames.read(DataInputStream(socket.inputStream)).getJSONObject("data"))
                Assert.assertTrue(result.lightOn)
                Assert.assertEquals(10.0, result.distanceCm, 0.001)
                Assert.assertEquals("TLSv1.2", socket.session.protocol)
            }
            Assert.assertEquals("TLSv1.2", future.get(15, TimeUnit.SECONDS))
        } finally { server.close(); executor.shutdownNow() }
    }
}

// BLOQUE 4: CONSULTA Y CÁLCULOS DEL HISTORIAL. HistoryCalculationTest comprueba filtros, promedios y la estimación de consumo.

class HistoryCalculationTest {
    // Crea una lectura de prueba con los valores que interesan a cada caso.
    private fun r(distance: Double, ldr: Double, on: Boolean, auto: Boolean) =
        Reading("id-$distance-$ldr-$on-$auto", 1L, distance, ldr, on, auto, 30.0, 30.0)
    private val rows = listOf(r(10.0, 80.0, true, true), r(100.0, 80.0, false, true),
        r(50.0, 20.0, true, false), r(200.0, 60.0, false, false))

    // Cada opción de consulta devuelve solo las lecturas que le corresponden.
    @Test fun filtersSelectMatchingReadings() {
        Assert.assertEquals(4, rows.count(HistoryFilter.ALL::matches))
        Assert.assertEquals(2, rows.count(HistoryFilter.LIGHT_ON::matches))
        Assert.assertEquals(2, rows.count(HistoryFilter.LIGHT_OFF::matches))
        Assert.assertEquals(2, rows.count(HistoryFilter.AUTOMATIC::matches))
        Assert.assertEquals(2, rows.count(HistoryFilter.MANUAL::matches))
    }
    // Promedios, extremos y porcentajes calculados sobre las cuatro lecturas.
    @Test fun statsSummarizeReadings() {
        val s = ReadingStats.of(rows)!!
        Assert.assertEquals(4, s.count)
        Assert.assertEquals(90.0, s.avgDistanceCm, 0.001)
        Assert.assertEquals(10.0, s.minDistanceCm, 0.001)
        Assert.assertEquals(200.0, s.maxDistanceCm, 0.001)
        Assert.assertEquals(60.0, s.avgLdrPercent, 0.001)
        Assert.assertEquals(50.0, s.lightOnPercent, 0.001)
        Assert.assertEquals(50.0, s.automaticPercent, 0.001)
        Assert.assertNull(ReadingStats.of(emptyList()))
    }
    // 10 W, 12 h al día y 50 % encendida: 6 h × 10 W × 30 días = 1,8 kWh; a 200 CLP son 360 CLP.
    @Test fun energyEstimateMatchesHandCalculation() {
        val e = EnergyCalculator.estimate(50.0, 10.0, 12.0, 200.0)
        Assert.assertEquals(6.0, e.hoursOnPerDay, 0.001)
        Assert.assertEquals(1.8, e.kwhPerMonth, 0.001)
        Assert.assertEquals(360.0, e.costPerMonthClp, 0.001)
        Assert.assertEquals(1.8, e.savedKwhPerMonth, 0.001)
        Assert.assertEquals(360.0, e.savedClpPerMonth, 0.001)
    }
    // El formulario rechaza valores vacíos o fuera de rango con un mensaje para el usuario.
    @Test fun energyFormRejectsInvalidInput() {
        Assert.assertNull(EnergyCalculator.validate(10.0, 12.0, 200.0))
        Assert.assertTrue(EnergyCalculator.validate(null, 12.0, 200.0)!!.startsWith("Potencia"))
        Assert.assertTrue(EnergyCalculator.validate(10.0, 25.0, 200.0)!!.startsWith("Horas"))
        Assert.assertTrue(EnergyCalculator.validate(10.0, 12.0, 0.0)!!.startsWith("Tarifa"))
        Assert.assertThrows(IllegalArgumentException::class.java) { EnergyCalculator.estimate(50.0, 0.0, 12.0, 200.0) }
    }
}
