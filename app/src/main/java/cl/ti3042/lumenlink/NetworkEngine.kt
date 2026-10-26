package cl.ti3042.lumenlink

import cl.ti3042.lumenlink.core.Frames
import cl.ti3042.lumenlink.core.Pairing
import cl.ti3042.lumenlink.core.fingerprint
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

class NetworkEngine(private val security: DeviceSecurity,
    private val state: (String, Boolean) -> Unit, private val message: (JSONObject) -> Unit) {
    // state recibe cambios del enlace; message recibe JSON. Los callbacks llegan desde hilos de red.
    // Al reiniciar la conexión, descarta el trabajo pendiente de la sesión anterior.
    private val generation = AtomicLong()
    private val writer = Executors.newSingleThreadExecutor()
    private val writeLock = Any()
    @Volatile private var socket: SSLSocket? = null
    @Volatile private var listener: ServerSocket? = null
    @Volatile private var output: DataOutputStream? = null
    @Volatile var connected = false
        private set
    private fun announce(g: Long, text: String, ready: Boolean = false) {
        if (generation.get() == g) { connected = ready; state(text, ready) }
    }
    fun startNode(port: Int, token: String) {
        stop()
        val g = generation.get()
        Thread({
            var server: SSLServerSocket? = null
            try {
                server = security.serverContext().serverSocketFactory.createServerSocket() as SSLServerSocket
                server.reuseAddress = true
                server.enabledProtocols = arrayOf("TLSv1.2")
                server.bind(InetSocketAddress(port))
                if (generation.get() != g) return@Thread
                listener = server
                announce(g, "Sensor listo · esperando al otro teléfono")
                while (generation.get() == g) {
                    val peer = server.accept() as SSLSocket
                    if (generation.get() != g) { peer.close(); break }
                    socket = peer
                    try {
                        peer.soTimeout = 8_000
                        peer.enabledProtocols = arrayOf("TLSv1.2")
                        peer.startHandshake()
                        val input = DataInputStream(peer.inputStream)
                        val out = DataOutputStream(peer.outputStream)
                        // El token se recibe dentro de TLS y se valida antes de aceptar comandos.
                        val hello = Frames.read(input)
                        require(hello.optString("type") == "hello" && MessageDigest.isEqual(
                            hello.optString("token").toByteArray(), token.toByteArray())) { "Emparejamiento rechazado" }
                        Frames.write(out, JSONObject().put("type", "welcome").put("version", 1))
                        synchronized(writeLock) { output = out }
                        peer.soTimeout = 15_000
                        announce(g, "✅ Conectado con el otro teléfono", true)
                        readLoop(g, input)
                    } catch (e: Exception) {
                        android.util.Log.w("LumenLink", "Sesión del nodo rechazada", e)
                        announce(g, "El otro teléfono se desconectó")
                    } finally {
                        runCatching { peer.close() }
                        synchronized(writeLock) { if (socket === peer) { output = null; socket = null } }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("LumenLink", "No se pudo iniciar el nodo", e)
                announce(g, "No se pudo encender el sensor. Inténtalo de nuevo.")
            } finally { runCatching { server?.close() } }
        }, "lumenlink-server").start()
    }
    fun startController(pair: Pairing) {
        stop()
        val g = generation.get()
        Thread({
            while (generation.get() == g) {
                var peer: SSLSocket? = null
                try {
                    announce(g, "Conectando…")
                    peer = security.clientContext(pair.fingerprint).socketFactory.createSocket() as SSLSocket
                    if (generation.get() != g) break
                    socket = peer
                    peer.connect(InetSocketAddress(pair.host, pair.port), 5_000)
                    peer.soTimeout = 8_000
                    peer.enabledProtocols = arrayOf("TLSv1.2")
                    peer.startHandshake()
                    val input = DataInputStream(peer.inputStream)
                    val out = DataOutputStream(peer.outputStream)
                    Frames.write(out, JSONObject().put("type", "hello").put("token", pair.token))
                    require(Frames.read(input).optString("type") == "welcome")
                    synchronized(writeLock) { output = out }
                    peer.soTimeout = 15_000
                    announce(g, "✅ Conectado con el sensor", true)
                    readLoop(g, input)
                } catch (e: Exception) {
                    android.util.Log.w("LumenLink", "No se pudo conectar el panel", e)
                    announce(g, "Sin conexión. Reintentando… Revisa que los dos teléfonos estén en el mismo Wi-Fi.")
                } finally {
                    runCatching { peer?.close() }
                    synchronized(writeLock) { if (socket === peer) { output = null; socket = null } }
                }
                if (generation.get() == g) try { Thread.sleep(3_000) } catch (_: InterruptedException) { break }
            }
        }, "lumenlink-client").start()
    }
    private fun readLoop(g: Long, input: DataInputStream) {
        while (generation.get() == g) {
            val value = Frames.read(input)
            if (generation.get() == g) message(value)
        }
    }
    fun send(value: JSONObject) {
        val g = generation.get()
        val target = socket ?: return
        writer.execute {
            if (generation.get() != g || socket !== target) return@execute
            try { synchronized(writeLock) { output?.let { Frames.write(it, value) } } }
            catch (_: Exception) { runCatching { target.close() } }
        }
    }
    fun stop() {
        generation.incrementAndGet()
        connected = false
        // Cierra primero el socket para liberar una escritura que pueda estar bloqueada.
        runCatching { socket?.close() }
        runCatching { listener?.close() }
        synchronized(writeLock) { output = null }
        socket = null; listener = null
        state("Desconectado", false)
    }
    fun dispose() { stop(); writer.shutdownNow() }
}
