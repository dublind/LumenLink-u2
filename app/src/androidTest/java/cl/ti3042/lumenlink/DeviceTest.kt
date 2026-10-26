package cl.ti3042.lumenlink

/**
 * DEVICETEST.KT: las siete pruebas Android están reunidas en dos clases.
 * Requieren un dispositivo o emulador conectado: connectedDebugAndroidTest.
 * BLOQUE 1: cifrado, cuentas y red. BLOQUE 2: recorrido de las pantallas.
 * CountDownLatch es una señal de espera; TimeUnit expresa segundos.
 * AtomicBoolean y AtomicReference comparten datos con los hilos que reciben mensajes de red.
 * Los import solo permiten usar por nombre corto las clases de Android, JUnit y Java.
 */

// Android: pantallas, gráficos, almacenamiento y claves del dispositivo.
import android.graphics.Bitmap
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

// AndroidX Test: acceso a la aplicación y ejecución de pruebas en Android.
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry

// Nuestro proyecto: reglas, lecturas y formato de la conexión.
import cl.ti3042.lumenlink.core.Pairing
import cl.ti3042.lumenlink.core.Reading

// JSON: convierte los mensajes y las lecturas a un formato intercambiable.
import org.json.JSONObject

// JUnit: marca las pruebas y comprueba sus resultados con Assert.
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith

// Java: flujos de bytes, red, fechas, certificados y trabajo en otros hilos.
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

// BLOQUE 1: SEGURIDAD, BASE DE DATOS Y RED. DeviceTest contiene las seis pruebas de servicios del dispositivo.

/**
 * Comprueba cifrado, cuentas y conexión en un dispositivo o emulador Android.
 * No construye pantallas. Las pruebas de red conectan un panel y un nodo en el mismo Android.
 * DeviceSecurity, AppDatabase y NetworkEngine están en este mismo paquete: no necesitan import.
 */
@RunWith(AndroidJUnit4::class)
class DeviceTest {
    // El contexto permite abrir la base de datos de la aplicación que estamos probando.
    private val contexto get() = InstrumentationRegistry.getInstrumentation().targetContext

    // El puerto 0 pide uno libre al sistema. use cierra este socket al terminar.
    private fun puertoDisponible(): Int = ServerSocket(0).use { socket -> socket.localPort }

    // countDown() da la señal; await() espera hasta recibirla o agotar el tiempo.
    // Así evitamos comprobar un resultado antes de que termine la operación de red.
    private fun esperar(senal: CountDownLatch, mensaje: String, segundos: Long = 15) {
        Assert.assertTrue(mensaje, senal.await(segundos, TimeUnit.SECONDS))
    }

    /** El texto se puede recuperar, cada cifrado cambia y los datos alterados se rechazan. */
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

    /** Verifica acceso, aislamiento de cuentas, duplicados y almacenamiento cifrado. */
    @Test
    fun baseDeDatosSeparaUsuariosYEvitaDuplicados() {
        val db = AppDatabase(contexto, DeviceSecurity())
        val usuario = "test_${System.nanoTime()}" // Nombre diferente en cada ejecución.
        val clave = "Una contraseña de prueba larga"

        try {
            Assert.assertEquals("OK", db.register(usuario, clave))
            Assert.assertTrue(db.login(usuario, clave))
            Assert.assertFalse(db.login(usuario, "incorrecta"))

            // Los argumentos con nombre muestran qué significa cada valor de la lectura.
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
            // El ? recibe el usuario como parámetro; use cierra el cursor de la consulta.
            db.readableDatabase.rawQuery(
                "SELECT payload FROM readings WHERE owner=?", arrayOf(usuario)
            ).use { cursor ->
                Assert.assertTrue(cursor.moveToFirst())
                Assert.assertFalse(cursor.getString(0).contains("distanceCm"))
            }
        } finally {
            // finally limpia los datos de prueba aunque una comprobación falle.
            db.writableDatabase.delete("readings", "owner=?", arrayOf(usuario))
            db.writableDatabase.delete("users", "name=?", arrayOf(usuario))
            db.close()
        }
    }

    /** Tras cinco intentos incorrectos, la clave correcta tampoco permite entrar de inmediato. */
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

    /** Usa el certificado real de Android Keystore y comprueba un comando de ida y vuelta. */
    @Test
    fun conexionTlsPermiteEnviarComandos() {
        val seguridad = DeviceSecurity()
        val puerto = puertoDisponible()
        val nodoListo = CountDownLatch(1)
        val panelConectado = CountDownLatch(1)
        val respuestaRecibida = CountDownLatch(1)
        val respuesta = AtomicReference<JSONObject>()

        // Cada motor recibe dos funciones: una informa del estado y otra recibe mensajes.
        // lateinit permite que la función del nodo utilice ese mismo nodo para responder.
        lateinit var nodo: NetworkEngine
        nodo = NetworkEngine(
            seguridad,
            state = { estado, _ ->
                if (estado.contains("Nodo listo")) nodoListo.countDown()
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

            // 127.0.0.1 es este mismo dispositivo; pin es la huella del certificado esperado.
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

    /** Un token incorrecto o una huella distinta deben impedir la autorización. */
    @Test
    fun conexionRechazaTokenOHuellaIncorrectos() {
        val seguridad = DeviceSecurity()
        val puerto = puertoDisponible()
        val token = "a".repeat(32)
        val nodoListo = CountDownLatch(1)
        // Se cambia la señal para esperar por separado el rechazo de cada intento.
        val rechazo = AtomicReference(CountDownLatch(1))
        val nodo = NetworkEngine(
            seguridad,
            state = { estado, _ ->
                if (estado.contains("Nodo listo")) nodoListo.countDown()
                if (estado.contains("Panel desconectado")) rechazo.get().countDown()
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

    /** El panel debe reconectarse automáticamente cuando vuelve el mismo nodo autorizado. */
    @Test
    fun panelSeReconectaCuandoRegresaElNodo() {
        val seguridad = DeviceSecurity()
        val puerto = puertoDisponible()
        val token = "a".repeat(32)
        // AtomicReference permite sustituir las señales al iniciar la segunda conexión.
        val nodoListo = AtomicReference(CountDownLatch(1))
        val panelConectado = AtomicReference(CountDownLatch(1))
        val nodo = NetworkEngine(
            seguridad,
            state = { estado, _ ->
                if (estado.contains("Nodo listo")) nodoListo.get().countDown()
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

// BLOQUE 2: RECORRIDO DE PANTALLAS. UiFlowTest prueba la aplicación visible con una cuenta desechable.

/** Prueba las pantallas reales con una cuenta desechable. */
@RunWith(AndroidJUnit4::class)
class UiFlowTest {
    // Recorre los controles de una vista y sus hijos para poder encontrar textos y botones.
    private fun views(v: View): List<View> = listOf(v) + if (v is ViewGroup)
        (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    // Comienza la búsqueda desde la raíz de la pantalla de la aplicación.
    private fun all(a: MainActivity): List<View> = views(a.window.decorView)
    // Busca un botón por el texto que ve el usuario y ejecuta su pulsación.
    private fun click(a: MainActivity, label: String) {
        all(a).filterIsInstance<Button>().first { it.text.toString() == label }.performClick()
    }
    // Espera un texto de la interfaz con un límite de tiempo para no avanzar demasiado pronto.
    private fun waitFor(scenario: ActivityScenario<MainActivity>, label: String, timeout: Long = 20_000) {
        val end = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < end) {
            var found = false
            scenario.onActivity { a -> found = all(a).filterIsInstance<TextView>().any { it.text.toString().contains(label) } }
            if (found) return
            Thread.sleep(150)
        }
        Assert.fail("No se encontró el texto esperado: $label")
    }
    // Guarda una captura de prueba con una cuenta desechable para la evidencia visual.
    private fun capture(scenario: ActivityScenario<MainActivity>, name: String) {
        // Permite capturas solo durante esta prueba; la APK mantiene su protección.
        scenario.onActivity { it.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        Thread.sleep(250)
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "qa").apply { mkdirs() }
        File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    // Recorre registro, acceso, nodo, sensores, control manual, historial y cierre de sesión.
    @Test fun registrationLoginNodeControlAndHistory() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val account = "ui_${System.nanoTime()}"
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor(scenario, "Crear cuenta local")
            capture(scenario, "01-acceso.png")
            scenario.onActivity { a ->
                val fields = all(a).filterIsInstance<EditText>()
                fields[0].setText(account); fields[1].setText("Una clave segura de prueba")
                click(a, "Crear cuenta local")
            }
            waitFor(scenario, "Cuenta creada")
            scenario.onActivity { a ->
                all(a).filterIsInstance<EditText>()[1].setText("Una clave segura de prueba")
                click(a, "Iniciar sesión")
            }
            waitFor(scenario, "DISTANCIA ULTRASÓNICA SIMULADA")
            scenario.onActivity { click(it, "Conectar") }
            scenario.onActivity { click(it, "Usar como nodo IoT simulado") }
            scenario.onActivity { a ->
                all(a).filterIsInstance<EditText>().first().setText("127.0.0.1")
                click(a, "Iniciar nodo y generar configuración")
            }
            waitFor(scenario, "Nodo listo")
            scenario.onActivity { click(it, "Panel") }
            waitFor(scenario, "Última lectura:")
            waitFor(scenario, "LUZ APAGADA · Automático")
            scenario.onActivity { click(it, "LDR: simular sombra (10 %)") }
            waitFor(scenario, "LUZ ENCENDIDA · Automático")
            scenario.onActivity { click(it, "LDR: simular luz (80 %)") }
            waitFor(scenario, "LUZ APAGADA · Automático")
            scenario.onActivity { click(it, "Ultrasónico: objeto cerca (10 cm)") }
            waitFor(scenario, "LUZ ENCENDIDA · Automático")
            scenario.onActivity { click(it, "Ultrasónico: objeto lejos (100 cm)") }
            waitFor(scenario, "LUZ APAGADA · Automático")
            scenario.onActivity { click(it, "Encender luz · manual") }
            waitFor(scenario, "LUZ ENCENDIDA · Manual")
            capture(scenario, "02-panel.png")
            scenario.onActivity { a ->
                val scroll = all(a).filterIsInstance<android.widget.ScrollView>().first()
                scroll.scrollTo(0, 390)
            }
            capture(scenario, "04-simulacion.png")
            scenario.onActivity { click(it, "Historial") }
            waitFor(scenario, "registros guardados")
            capture(scenario, "03-historial.png")
            scenario.onActivity { a ->
                click(a, "Ayuda"); click(a, "Cerrar sesión")
            }
            waitFor(scenario, "Crear cuenta local")
        }
        val db = AppDatabase(context, DeviceSecurity())
        try {
            db.writableDatabase.delete("readings", "owner=?", arrayOf(account))
            db.writableDatabase.delete("users", "name=?", arrayOf(account))
        } finally { db.close() }
    }
}
