package cl.ti3042.lumenlink

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cl.ti3042.lumenlink.core.Pairing
import cl.ti3042.lumenlink.core.Reading
import org.json.JSONObject
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class DeviceTest {
    private val contexto get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun puertoDisponible(): Int = ServerSocket(0).use { socket -> socket.localPort }

    private fun esperar(senal: CountDownLatch, mensaje: String, segundos: Long = 15) {
        Assert.assertTrue(mensaje, senal.await(segundos, TimeUnit.SECONDS))
    }

    @Test
    fun cifradoProtegeLecturasYDetectaAlteraciones() {
        val seguridad = DeviceSecurity()
        val texto = "Lectura confidencial 10.0 cm"
        val cifrado = seguridad.encrypt(texto)

        Assert.assertEquals(texto, seguridad.decrypt(cifrado))
        // El IV es un valor nuevo en cada cifrado; el mismo texto no debe dar el mismo resultado.
        Assert.assertNotEquals(cifrado, seguridad.encrypt(texto))

        val bytes = Base64.decode(cifrado, Base64.NO_WRAP)
        // Cambia un solo bit del último byte para simular una manipulación del mensaje.
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        val cifradoAlterado = Base64.encodeToString(bytes, Base64.NO_WRAP)

        Assert.assertThrows(Exception::class.java) {
            seguridad.decrypt(cifradoAlterado)
        }
    }

    @Test
    fun baseDeDatosSeparaUsuariosYEvitaDuplicados() {
        val db = AppDatabase(contexto, DeviceSecurity())
        val usuario = "test_${System.nanoTime()}" // Nombre diferente en cada ejecución.
        val clave = "Una contraseña de prueba larga"

        try {
            Assert.assertEquals("OK", db.register(usuario, clave))
            Assert.assertTrue(db.login(usuario, clave))
            Assert.assertFalse(db.login(usuario, "incorrecta"))

            val lectura = Reading(
                id = "unique-id",
                at = System.currentTimeMillis(),
                distanceCm = 100.0,
                ldrPercent = 80.0,
                lightOn = false,
                automatic = true,
                thresholdCm = 30.0,
                ldrThresholdPercent = 30.0
            )
            db.save(usuario, lectura, "remote")
            db.save(usuario, lectura, "remote")
            Assert.assertEquals(1, db.count(usuario))
            Assert.assertEquals(lectura, db.list(usuario).single())
            Assert.assertTrue(db.list("another_account").isEmpty())

            // Inspecciona el contenido guardado sin pasar por el descifrado de db.list().
            db.readableDatabase.rawQuery(
                "SELECT payload FROM readings WHERE owner=?", arrayOf(usuario)
            ).use { cursor ->
                Assert.assertTrue(cursor.moveToFirst())
                Assert.assertFalse(cursor.getString(0).contains("distanceCm"))
            }
        } finally {
            db.writableDatabase.delete("readings", "owner=?", arrayOf(usuario))
            db.writableDatabase.delete("users", "name=?", arrayOf(usuario))
            db.close()
        }
    }

    @Test
    fun cincoClavesIncorrectasBloqueanLaCuenta() {
        val db = AppDatabase(contexto, DeviceSecurity())
        val usuario = "lock_${System.nanoTime()}"
        val clave = "Clave muy larga de prueba"

        try {
            Assert.assertEquals("OK", db.register(usuario, clave))
            repeat(5) {
                Assert.assertFalse(db.login(usuario, "incorrecta"))
            }
            Assert.assertFalse(db.login(usuario, clave))
        } finally {
            db.writableDatabase.delete("users", "name=?", arrayOf(usuario))
            db.close()
        }
    }

    @Test
    fun conexionTlsPermiteEnviarComandos() {
        val seguridad = DeviceSecurity()
        val puerto = puertoDisponible()
        val nodoListo = CountDownLatch(1)
        val panelConectado = CountDownLatch(1)
        val respuestaRecibida = CountDownLatch(1)
        val respuesta = AtomicReference<JSONObject>()

        lateinit var nodo: NetworkEngine
        nodo = NetworkEngine(
            seguridad,
            state = { estado, _ ->
                if (estado.contains("Sensor listo")) nodoListo.countDown()
            },
            message = { mensaje ->
                if (mensaje.optString("action") == "light_on") {
                    val confirmacion = JSONObject().put("type", "result").put("lightOn", true)
                    nodo.send(confirmacion)
                }
            }
        )
        val panel = NetworkEngine(
            seguridad,
            state = { _, conectado ->
                if (conectado) panelConectado.countDown()
            },
            message = { mensaje ->
                respuesta.set(mensaje)
                respuestaRecibida.countDown()
            }
        )

        try {
            val token = "a".repeat(32) // Token fijo solo para esta prueba.
            nodo.startNode(puerto, token)
            esperar(nodoListo, "El nodo no pudo abrir el puerto")

            val conexion = Pairing("127.0.0.1", puerto, seguridad.pin, token)
            panel.startController(conexion)
            esperar(panelConectado, "El panel no pudo establecer TLS", 20)

            val comando = JSONObject().put("type", "command").put("action", "light_on")
            panel.send(comando)
            esperar(respuestaRecibida, "No llegó la respuesta del nodo", 10)
            Assert.assertEquals("result", respuesta.get().optString("type"))
            Assert.assertTrue(respuesta.get().optBoolean("lightOn"))
        } finally {
            panel.dispose()
            nodo.dispose()
        }
    }

    @Test
    fun conexionRechazaTokenOHuellaIncorrectos() {
        val seguridad = DeviceSecurity()
        val puerto = puertoDisponible()
        val token = "a".repeat(32)
        val nodoListo = CountDownLatch(1)
        val rechazo = AtomicReference(CountDownLatch(1))
        val nodo = NetworkEngine(
            seguridad,
            state = { estado, _ ->
                if (estado.contains("Sensor listo")) nodoListo.countDown()
                if (estado.contains("El otro teléfono se desconectó")) rechazo.get().countDown()
            },
            message = {} // En esta prueba no necesitamos recibir mensajes.
        )

        try {
            nodo.startNode(puerto, token)
            esperar(nodoListo, "El nodo no pudo iniciar")

            val conexionesInvalidas = listOf(
                Pairing("127.0.0.1", puerto, seguridad.pin, "b".repeat(32)), // Token incorrecto.
                Pairing("127.0.0.1", puerto, "0".repeat(64), token) // Huella incorrecta.
            )
            for (conexion in conexionesInvalidas) {
                rechazo.set(CountDownLatch(1))
                val autorizado = AtomicBoolean(false)
                val panel = NetworkEngine(
                    seguridad,
                    state = { _, conectado ->
                        if (conectado) autorizado.set(true)
                    },
                    message = {}
                )
                try {
                    panel.startController(conexion)
                    esperar(rechazo.get(), "El intento de conexión no terminó")
                    Assert.assertFalse(autorizado.get())
                } finally {
                    panel.dispose()
                }
            }
        } finally {
            nodo.dispose()
        }
    }

    @Test
    fun panelSeReconectaCuandoRegresaElNodo() {
        val seguridad = DeviceSecurity()
        val puerto = puertoDisponible()
        val token = "a".repeat(32)
        val nodoListo = AtomicReference(CountDownLatch(1))
        val panelConectado = AtomicReference(CountDownLatch(1))
        val nodo = NetworkEngine(
            seguridad,
            state = { estado, _ ->
                if (estado.contains("Sensor listo")) nodoListo.get().countDown()
            },
            message = {}
        )
        val panel = NetworkEngine(
            seguridad,
            state = { _, conectado ->
                if (conectado) panelConectado.get().countDown()
            },
            message = {}
        )

        try {
            nodo.startNode(puerto, token)
            esperar(nodoListo.get(), "El nodo no pudo iniciar")
            panel.startController(Pairing("127.0.0.1", puerto, seguridad.pin, token))
            esperar(panelConectado.get(), "El panel no pudo conectar", 20)

            nodo.stop()
            nodoListo.set(CountDownLatch(1))
            panelConectado.set(CountDownLatch(1))
            nodo.startNode(puerto, token)
            esperar(nodoListo.get(), "El nodo no pudo reiniciar")
            // No llamamos otra vez a startController: el propio panel debe reintentar.
            esperar(panelConectado.get(), "El panel no se reconectó", 20)
        } finally {
            panel.dispose()
            nodo.dispose()
        }
    }
}
