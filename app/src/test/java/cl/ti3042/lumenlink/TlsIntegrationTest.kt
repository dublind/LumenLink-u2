package cl.ti3042.lumenlink

import cl.ti3042.lumenlink.core.Frames
import cl.ti3042.lumenlink.core.PinnedTrust
import cl.ti3042.lumenlink.core.Reading
import cl.ti3042.lumenlink.core.fingerprint
import org.json.JSONObject
import org.junit.Assert
import org.junit.BeforeClass
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
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
