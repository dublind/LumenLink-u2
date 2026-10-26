package cl.ti3042.lumenlink

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

import org.json.JSONObject

import org.junit.Assert
import org.junit.BeforeClass
import org.junit.Test

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

import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager

// BLOQUE 1: CUENTAS Y FORMATO DE DATOS

class CoreTest {
    @Test fun passwordCanBeVerifiedAndWrongPasswordIsRejected() {
        val salt = PasswordHasher.salt()
        val hash = PasswordHasher.hash("Clave larga de prueba 2026".toCharArray(), salt)
        Assert.assertEquals(32, hash.size)
        Assert.assertTrue(PasswordHasher.matches("Clave larga de prueba 2026".toCharArray(), salt, hash))
        Assert.assertFalse(PasswordHasher.matches("Contraseña incorrecta".toCharArray(), salt, hash))
    }
    @Test fun saltsPreventEqualPasswordHashes() {
        val pass = "Contraseña para prueba".toCharArray()
        Assert.assertFalse(PasswordHasher.hash(pass, PasswordHasher.salt()).contentEquals(
            PasswordHasher.hash(pass, PasswordHasher.salt())))
    }
    @Test fun accountInputHasBounds() {
        Assert.assertFalse(PasswordHasher.validUser("' OR 1=1 --"))
        Assert.assertFalse(PasswordHasher.validUser("ab"))
        Assert.assertTrue(PasswordHasher.validUser("estudiante_01"))
        Assert.assertFalse(PasswordHasher.validPassword("corta"))
        Assert.assertTrue(PasswordHasher.validPassword("seis66"))
        Assert.assertFalse(PasswordHasher.validPassword("a".repeat(129)))
    }
    @Test fun framingHandlesUnicodeAndMultipleMessages() {
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        Frames.write(out, JSONObject().put("type", "lectura").put("label", "Distancia ultrasónica cm"))
        Frames.write(out, JSONObject().put("type", "command").put("action", "light_on"))
        val input = DataInputStream(ByteArrayInputStream(bytes.toByteArray()))
        Assert.assertEquals("Distancia ultrasónica cm", Frames.read(input).getString("label"))
        Assert.assertEquals("light_on", Frames.read(input).getString("action"))
    }
    @Test fun framingRejectsNegativeAndExcessiveLengths() {
        for (size in listOf(-1, 0, Frames.MAX_BYTES + 1)) {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).writeInt(size)
            Assert.assertThrows(IllegalArgumentException::class.java) {
                Frames.read(DataInputStream(ByteArrayInputStream(bytes.toByteArray())))
            }
        }
    }
    @Test fun framingRejectsTruncatedFrames() {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).apply { writeInt(100); write(byteArrayOf(1, 2)) }
        Assert.assertThrows(EOFException::class.java) { Frames.read(DataInputStream(ByteArrayInputStream(bytes.toByteArray()))) }
    }
    private val pair = Pairing("192.168.1.20", 8842, "a".repeat(64), "b".repeat(32))
    @Test fun pairingRoundTrip() { Assert.assertEquals(pair, Pairing.parse(pair.encode())) }
    @Test fun pairingRejectsBadIpPinTokenAndPort() {
        for (value in listOf(pair.copy(host = "999.1.1.1"), pair.copy(port = 80),
            pair.copy(fingerprint = "not-a-pin"), pair.copy(token = "1234"))) {
            Assert.assertThrows(IllegalArgumentException::class.java) { Pairing.parse(value.encode()) }
        }
    }
    @Test fun readingRoundTripPreservesIdentityAndControlState() {
        val r = Reading("sample-001", 1234, 27.5, 80.0, true, false, 30.0, 30.0)
        Assert.assertEquals(r, Reading.parse(r.json()))
    }
    @Test fun readingsRejectUnsafeNumericValues() {
        val r = Reading("id", 1234, 100.0, 80.0, false, true, 30.0, 30.0)
        for (invalid in listOf(r.copy(distanceCm = 1000.0), r.copy(distanceCm = -1.0),
            r.copy(thresholdCm = 201.0), r.copy(ldrPercent = -1.0), r.copy(ldrPercent = 101.0),
            r.copy(ldrThresholdPercent = -1.0), r.copy(id = ""), r.copy(at = -1))) {
            Assert.assertThrows(IllegalArgumentException::class.java) { Reading.parse(invalid.json()) }
        }
    }
}

// BLOQUE 2: REGLA AUTOMÁTICA Y MODO MANUAL

class LightingControllerTest {
    @Test fun automaticRuleCoversEveryCombinationOfSensors() {
        val model = LightingController()
        for ((distance, illumination, expected) in listOf(
            Triple(100.0, 80.0, false), Triple(10.0, 80.0, true),
            Triple(100.0, 10.0, true), Triple(10.0, 10.0, true))) {
            model.updateSensors(distance, illumination)
            Assert.assertEquals("distance=$distance illumination=$illumination", expected, model.lightOn)
        }
    }
    @Test fun thresholdIncludesItsBoundaryAndReevaluatesImmediately() {
        val model = LightingController()
        model.updateSensors(30.0, 80.0)
        Assert.assertTrue(model.lightOn)
        model.updateSensors(30.1, 80.0)
        Assert.assertFalse(model.lightOn)
        model.setThreshold(40.0)
        Assert.assertTrue(model.lightOn)
    }
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

// BLOQUE 3: TRANSPORTE TLS REAL

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
    @Test fun certificatePinRejectsImpersonation() {
        PinnedTrust(fingerprint(cert)).checkServerTrusted(arrayOf(cert), "RSA")
        Assert.assertThrows(CertificateException::class.java) {
            PinnedTrust("0".repeat(64)).checkServerTrusted(arrayOf(cert), "RSA")
        }
    }
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

// BLOQUE 4: HISTORIAL Y CÁLCULOS

class HistoryCalculationTest {
    private fun r(distance: Double, ldr: Double, on: Boolean, auto: Boolean) =
        Reading("id-$distance-$ldr-$on-$auto", 1L, distance, ldr, on, auto, 30.0, 30.0)
    private val rows = listOf(r(10.0, 80.0, true, true), r(100.0, 80.0, false, true),
        r(50.0, 20.0, true, false), r(200.0, 60.0, false, false))

    @Test fun filtersSelectMatchingReadings() {
        Assert.assertEquals(4, rows.count(HistoryFilter.ALL::matches))
        Assert.assertEquals(2, rows.count(HistoryFilter.LIGHT_ON::matches))
        Assert.assertEquals(2, rows.count(HistoryFilter.LIGHT_OFF::matches))
        Assert.assertEquals(2, rows.count(HistoryFilter.AUTOMATIC::matches))
        Assert.assertEquals(2, rows.count(HistoryFilter.MANUAL::matches))
    }
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
    @Test fun energyFormRejectsInvalidInput() {
        Assert.assertNull(EnergyCalculator.validate(10.0, 12.0, 200.0))
        Assert.assertTrue(EnergyCalculator.validate(null, 12.0, 200.0)!!.startsWith("Potencia"))
        Assert.assertTrue(EnergyCalculator.validate(10.0, 25.0, 200.0)!!.startsWith("Horas"))
        Assert.assertTrue(EnergyCalculator.validate(10.0, 12.0, 0.0)!!.startsWith("Tarifa"))
        Assert.assertThrows(IllegalArgumentException::class.java) { EnergyCalculator.estimate(50.0, 0.0, 12.0, 200.0) }
    }
}
