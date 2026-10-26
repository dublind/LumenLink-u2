package cl.ti3042.lumenlink

/**
 * MAINACTIVITY.KT: toda la parte que depende de Android, reunida en cinco bloques.
 * Busca "BLOQUE 1" para pantallas; 2 para gráfico; 3 para red; 4 para SQLite; 5 para claves.
 * Las clases conservan sus nombres y responsabilidades aunque compartan este archivo.
 * Las reglas y los datos comunes están en core/Logica.kt.
 * Guía de Kotlin: val no se reasigna; var sí; private limita el acceso; fun declara una función.
 * apply configura un objeto; use lo cierra al terminar; ? admite null; ?: proporciona una alternativa.
 * Los import permiten usar clases existentes por su nombre corto; no instalan librerías.
 */

// Android: pantallas, gráficos, almacenamiento y claves del dispositivo.
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.text.InputFilter
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

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
import org.json.JSONArray
import org.json.JSONObject

// Java: flujos de bytes, red, fechas, certificados y trabajo en otros hilos.
import java.io.DataInputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

// Criptografía y TLS: cifrado, contraseñas y canal de comunicación seguro.
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509KeyManager
import javax.security.auth.x500.X500Principal

// BLOQUE 1: PANTALLAS Y COORDINACIÓN. MainActivity recibe las acciones del usuario y coordina las clases de los siguientes bloques.

class MainActivity : Activity() {
    // Apariencia y tareas: handler vuelve al hilo de pantalla; io trabaja con datos en otra cola.
    private val navy = Color.rgb(16, 44, 64)
    private val teal = Color.rgb(8, 127, 140)
    private val muted = Color.rgb(82, 104, 118)
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var security: DeviceSecurity
    // lateinit indica que estos servicios se preparan al iniciar, antes de utilizarlos.
    private lateinit var db: AppDatabase
    private lateinit var net: NetworkEngine
    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout
    private var user: String? = null
    // Estado de la sesión: rol del teléfono, conexión, token y última lectura confirmada.
    private var tab = "Panel"
    private var nodeMode = false
    private var nodeRunning = false
    private var status = "Desconectado"
    private var token = freshToken()
    private var latest: Reading? = null
    private val lighting = LightingController()
    private var syncRunning = false
    // La sincronización recorre una copia estable del historial en páginas de 100 lecturas.
    private var syncId = ""
    private var snapshot: List<Reading> = emptyList() // Solo se consulta desde la cola de trabajo de datos.
    // Opción de consulta elegida en Historial y resumen de todas las lecturas para la calculadora.
    private var historyFilter = HistoryFilter.ALL
    private var allStats: ReadingStats? = null
    // Secciones plegables: los ajustes y la calculadora se abren solo si el usuario los pide.
    private var showSettings = false
    private var showCalculator = false
    private var pairText = ""
    private var chosenIp = ""
    private var statusView: TextView? = null
    // Referencias a los controles visibles; pueden ser null al cambiar de pantalla.
    private var distanceView: TextView? = null
    private var detailView: TextView? = null
    private var lightOnView: TextView? = null
    private var trendView: TrendView? = null
    private var ldrView: TextView? = null
    private var ldrStateView: TextView? = null
    private var ldrBar: LdrBar? = null
    private var ldrTrendView: TrendView? = null
    private val trend = ArrayDeque<Float>()
    private val ldrTrend = ArrayDeque<Float>()
    private val time = SimpleDateFormat("dd/MM HH:mm:ss", Locale("es", "CL"))

    // Android llama aquí al abrir la app: prepara las claves, la base de datos y la red.

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        val loading = TextView(this).apply { text = "Preparando almacenamiento seguro…"; gravity = Gravity.CENTER }
        setContentView(loading)
        io.execute {
            try {
                security = DeviceSecurity()
                db = AppDatabase(applicationContext, security)
                // Prepara la base antes de habilitar las pantallas.
                db.writableDatabase
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    net = NetworkEngine(security, { text, ready ->
                        runOnUiThread {
                            status = text
                            statusView?.text = text
                            if (!ready) {
                                syncRunning = false
                                if (nodeMode && nodeRunning) lighting.enableAutomatic()
                            } else if (!nodeMode && user != null) requestSync()
                        }
                    }, { value -> runOnUiThread { if (user != null) receive(value) } })
                    loginScreen()
                    handler.post(ticker)
                }
            } catch (_: Exception) {
                runOnUiThread { loading.text = "No se pudo iniciar el almacenamiento seguro. Reinicia la aplicación. Si persiste, revisa Android Keystore en este equipo." }
            }
        }
    }
    // Convierte medidas de diseño a píxeles según la densidad de la pantalla.
    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).toInt()
    // Crea un fondo de color con esquinas redondeadas.
    private fun background(color: Int, radius: Int = 16) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }
    // Crea un texto reutilizable para las distintas pantallas.
    private fun text(value: String, size: Float = 16f, color: Int = navy, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(5), 0, dp(5))
    }
    // Agrupa controles verticalmente, opcionalmente dentro de una tarjeta blanca.
    private fun column(parent: LinearLayout, card: Boolean = false): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(16))
        if (card) background = background(Color.WHITE)
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
    }
    // Crea un botón y asocia la acción que se ejecutará al pulsarlo.
    private fun button(parent: LinearLayout, label: String, action: () -> Unit): Button = Button(this).apply {
        text = label; isAllCaps = false; textSize = 15f
        setTextColor(Color.WHITE); backgroundTintList = android.content.res.ColorStateList.valueOf(teal)
        minHeight = dp(48)
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        setOnClickListener { action() }
    }
    // Pone botones lado a lado en una fila; cada par es (texto, acción).
    private fun buttonRow(parent: LinearLayout, vararg items: Pair<String, () -> Unit>) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        items.forEachIndexed { i, (label, action) ->
            row.addView(Button(this).apply {
                text = label; isAllCaps = false; textSize = 15f; setTextColor(Color.WHITE); minHeight = dp(52)
                backgroundTintList = android.content.res.ColorStateList.valueOf(teal)
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = dp(8) })
        }
        parent.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
    }
    // Crea un campo de entrada con su etiqueta siempre visible; secret oculta los caracteres de la contraseña.
    private fun field(parent: LinearLayout, hintText: String, secret: Boolean = false, initial: String = ""): EditText = EditText(this).apply {
        parent.addView(this@MainActivity.text(hintText, 14f, muted, true).apply { setPadding(0, dp(10), 0, 0) })
        hint = hintText; textSize = 18f; setText(initial); setTextColor(navy)
        inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        filters = arrayOf(InputFilter.LengthFilter(if (secret) 128 else 256))
        setPadding(dp(8), dp(10), dp(8), dp(10))
        parent.addView(this, LinearLayout.LayoutParams(-1, -2))
    }
    // Construye el encabezado y el contenido desplazable compartidos por las pantallas.
    private fun shell(subtitle: String) {
        statusView = null; distanceView = null; detailView = null; lightOnView = null; trendView = null
        ldrView = null; ldrStateView = null; ldrBar = null; ldrTrendView = null
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(242, 246, 248)) }
        // Deja espacio para las barras del sistema, también en Android 15.
        root.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(navy); setPadding(dp(22), dp(14), dp(14), dp(12))
        }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(text("LUMENLINK", 25f, Color.WHITE, true))
        titles.addView(text(subtitle, 14f, Color.rgb(164, 220, 222)))
        header.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        // Con sesión iniciada, el botón Salir queda siempre a la vista en el encabezado.
        if (user != null) header.addView(Button(this).apply {
            text = "Salir"; isAllCaps = false; textSize = 15f; setTextColor(navy)
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(164, 220, 222))
            setOnClickListener { logout() }
        }, LinearLayout.LayoutParams(-2, dp(48)))
        root.addView(header)
        val scroll = ScrollView(this).apply { isFillViewport = true }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(18), dp(14), dp(18)) }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        root.requestApplyInsets()
    }
    // Pantalla de acceso: registra cuentas locales o comprueba las credenciales.
    private fun loginScreen() {
        shell("Luz inteligente")
        val card = column(content, true)
        card.addView(text("Bienvenido", 25f, bold = true))
        val name = field(card, "Usuario")
        val pass = field(card, "Contraseña", true)
        val confirm = field(card, "Repetir contraseña (solo para crear cuenta)", true)
        val feedback = text("La contraseña debe tener al menos 6 caracteres.", 15f, muted)
        card.addView(feedback)
        val actions = mutableListOf<Button>()
        // Valida cada campo antes de consultar la base y marca el campo con el error.
        fun invalid(register: Boolean, n: String, p: String): String? {
            name.error = null; pass.error = null; confirm.error = null
            val (target, message) = when {
                n.isEmpty() -> name to "Ingresa tu usuario."
                !PasswordHasher.validUser(n) -> name to "Usuario: usa entre 3 y 32 letras, números, punto, guion o guion bajo."
                p.isEmpty() -> pass to "Ingresa tu contraseña."
                register && !PasswordHasher.validPassword(p) -> pass to "La contraseña debe tener entre 6 y 128 caracteres."
                register && confirm.text.toString() != p -> confirm to "Las contraseñas no coinciden."
                else -> return null
            }
            target.error = message; target.requestFocus()
            return message
        }
        // Consulta la base en la cola de trabajo y actualiza después la pantalla.
        fun authenticate(register: Boolean) {
            val n = name.text.toString().trim().lowercase(Locale.ROOT)
            val p = pass.text.toString()
            invalid(register, n, p)?.let { feedback.text = it; feedback.setTextColor(Color.rgb(176, 32, 32)); return }
            feedback.setTextColor(muted)
            actions.forEach { it.isEnabled = false }
            feedback.text = "Validando…"
            io.execute {
                val result = runCatching {
                    if (register) db.register(n, p)
                    else if (db.login(n, p)) "LOGIN" else "Credenciales inválidas o cuenta bloqueada. Tras 5 fallos, espera 30 segundos."
                }.getOrDefault("No se pudo acceder a la cuenta.")
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    actions.forEach { it.isEnabled = true }
                    if (result == "LOGIN") { user = n; pass.text.clear(); confirm.text.clear(); toast("Bienvenido, $n."); showTab("Panel") }
                    else {
                        feedback.text = if (result == "OK") "Cuenta creada. Ahora inicia sesión." else result
                        feedback.setTextColor(if (result == "OK") teal else Color.rgb(176, 32, 32))
                        if (result == "OK") { pass.text.clear(); confirm.text.clear() }
                    }
                }
            }
        }
        actions += button(card, "Iniciar sesión") { authenticate(false) }
        actions += button(card, "Crear cuenta local") { authenticate(true) }
    }
    // Cierra la sesión, corta la conexión y vuelve a la pantalla de acceso.
    private fun logout() {
        stopConnection(); user = null; latest = null; pairText = ""; historyFilter = HistoryFilter.ALL; allStats = null
        trend.clear(); ldrTrend.clear(); showSettings = false; showCalculator = false; loginScreen()
    }
    // Abre la sección elegida y dibuja la barra inferior de navegación.
    private fun showTab(name: String) {
        if (user == null) return
        tab = name
        shell("${if (nodeMode) "Sensor" else "Control"} · ${user}")
        when (name) {
            "Conectar" -> connection()
            "Historial" -> history()
            else -> dashboard()
        }
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Color.WHITE) }
        for (label in listOf("Panel", "Conectar", "Historial")) {
            val b = Button(this).apply {
                text = label; isAllCaps = false; textSize = 16f; minimumWidth = 0; minWidth = 0
                if (label == name) typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 0, 0, 0); setTextColor(if (label == name) teal else muted)
                backgroundTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                setOnClickListener { showTab(label) }
            }
            nav.addView(b, LinearLayout.LayoutParams(0, dp(60), 1f))
        }
        root.addView(nav)
    }
    // Panel: primero el estado de la luz y sus botones; luego luminosidad y distancia. Los ajustes van plegados.
    private fun dashboard() {
        // Estado de la luz en grande, con fondo ámbar cuando está encendida.
        lightOnView = text("Sin datos todavía", 20f, muted, true).also {
            it.setPadding(dp(16), dp(14), dp(16), dp(14)); it.background = background(Color.rgb(232, 238, 241), 14)
            content.addView(it, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        }
        statusView = text(status, 14f, muted).also { content.addView(it, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }) }
        val controls = column(content, true)
        buttonRow(controls, "Encender" to { command("light_on") }, "Apagar" to { command("light_off") })
        button(controls, "Automático") { command("auto") }
        controls.addView(text("En automático se enciende si hay alguien cerca o está oscuro.", 14f, muted))
        if (nodeMode) {
            // Simulador para la demostración: dos deslizadores imitan la LDR y el ultrasónico.
            // Funciona también sin conectar el otro teléfono; cada cambio genera una lectura nueva.
            val simulation = column(content, true)
            simulation.addView(text("Simular sensores", 19f, bold = true))
            slider(simulation, "☀️ Luz del ambiente", "%", 100, lighting.ldrPercent) {
                lighting.updateSensors(lighting.distanceCm, it); sample()
            }
            slider(simulation, "📏 Distancia de la persona", "cm", 300, lighting.distanceCm) {
                lighting.updateSensors(it, lighting.ldrPercent); sample()
            }
        }
        // Luminosidad: porcentaje grande, barra con el umbral marcado y gráfico de las últimas lecturas.
        val light = column(content, true)
        light.addView(text("Luz del ambiente", 16f, muted, true))
        ldrView = text("— %", 46f, bold = true).also { light.addView(it) }
        ldrStateView = text("Esperando lecturas", 17f, muted, true).also { light.addView(it) }
        ldrBar = LdrBar(this).also { light.addView(it, LinearLayout.LayoutParams(-1, dp(34)).apply { topMargin = dp(6) }) }
        val scale = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        scale.addView(text("Oscuro", 14f, muted), LinearLayout.LayoutParams(0, -2, 1f))
        scale.addView(text("Mucha luz", 14f, muted).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(0, -2, 1f))
        light.addView(scale)
        ldrTrendView = TrendView(this).apply { lineColor = LdrBar.amber; fixedRange = 0f..100f }
            .also { light.addView(it, LinearLayout.LayoutParams(-1, dp(80))) }
        // Distancia a la persona u objeto más cercano.
        val values = column(content, true)
        values.addView(text("Distancia", 16f, muted, true))
        distanceView = text("— cm", 40f, bold = true).also { values.addView(it) }
        trendView = TrendView(this).also { values.addView(it, LinearLayout.LayoutParams(-1, dp(80))) }
        detailView = text("Conéctate en la pestaña Conectar para ver datos.", 14f, muted).also { values.addView(it) }
        // Ajustes plegados: umbrales y valores exactos del simulador.
        val settings = column(content, true)
        button(settings, if (showSettings) "Ocultar ajustes" else "Ajustes") { showSettings = !showSettings; showTab("Panel") }
        if (showSettings) {
            val limit = field(settings, "Encender si alguien está a menos de (cm, 5 a 200)", initial = (latest?.thresholdCm ?: lighting.thresholdCm).toString())
            limit.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            button(settings, "Guardar distancia") {
                val value = limit.text.toString().replace(',', '.').toDoubleOrNull()
                if (value == null || !value.isFinite() || value !in 5.0..200.0) toast("Ingresa un valor entre 5 y 200 cm.")
                else command("set_threshold", value)
            }
            val ldrLimit = field(settings, "Encender si la luz baja de (%, 0 a 100)", initial = (latest?.ldrThresholdPercent ?: lighting.ldrThresholdPercent).toString())
            ldrLimit.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            button(settings, "Guardar nivel de luz") {
                val value = ldrLimit.text.toString().replace(',', '.').toDoubleOrNull()
                if (value == null || !value.isFinite() || value !in 0.0..100.0) toast("Ingresa un valor entre 0 y 100 %.")
                else command("set_ldr_threshold", value)
            }
        }
        renderReading()
    }
    // Deslizador con su valor siempre visible; onChange recibe el valor elegido.
    private fun slider(parent: LinearLayout, label: String, unit: String, max: Int, initial: Double, onChange: (Double) -> Unit) {
        val title = text("$label: ${initial.toInt()} $unit", 17f, navy, true).apply { setPadding(0, dp(12), 0, 0) }
        parent.addView(title)
        parent.addView(android.widget.SeekBar(this).apply {
            this.max = max; progress = initial.toInt().coerceIn(0, max); contentDescription = label
            minimumHeight = dp(48); setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                private var dragging = false
                // Con el dedo, la lectura se genera al soltar para no llenar el historial con cada píxel;
                // con teclado o accesibilidad no hay "soltar", así que se aplica al momento.
                override fun onProgressChanged(bar: android.widget.SeekBar, value: Int, fromUser: Boolean) {
                    title.text = "$label: $value $unit"
                    if (!dragging) onChange(value.toDouble())
                }
                override fun onStartTrackingTouch(bar: android.widget.SeekBar) { dragging = true }
                override fun onStopTrackingTouch(bar: android.widget.SeekBar) { dragging = false; onChange(bar.progress.toDouble()) }
            })
        }, LinearLayout.LayoutParams(-1, -2))
    }
    // Permite elegir el rol e iniciar el nodo o emparejar el panel con su configuración.
    private fun connection() {
        val modes = column(content, true)
        modes.addView(text("¿Qué hará este teléfono?", 20f, bold = true))
        modes.addView(text("Ahora: ${if (nodeMode) "es el sensor" else "controla la luz"}", 15f, teal, true))
        button(modes, "Controlar la luz") { changeMode(false) }
        button(modes, "Ser el sensor") { changeMode(true) }
        modes.addView(text("Los dos teléfonos deben estar en el mismo Wi-Fi.", 14f, muted))
        val card = column(content, true)
        statusView = text(status, 16f, bold = true).also { card.addView(it) }
        if (nodeMode) {
            card.addView(text("1. Encender el sensor", 20f, bold = true))
            val addresses = localAddresses()
            val ip = field(card, "Dirección de este teléfono en el Wi-Fi", initial = chosenIp.ifBlank { addresses.firstOrNull().orEmpty() })
            button(card, "Encender sensor") {
                try {
                    chosenIp = ip.text.toString().trim()
                    Pairing.parse(Pairing(chosenIp, 8842, security.pin, token).encode())
                    nodeRunning = true
                    net.startNode(8842, token)
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    showTab("Conectar")
                } catch (_: Exception) { toast("Revisa la dirección: este teléfono debe estar conectado al Wi-Fi.") }
            }
            if (nodeRunning) {
                card.addView(text("2. Copia el código y pégalo en el otro teléfono", 18f, bold = true))
                val config = Pairing(chosenIp, 8842, security.pin, token).encode()
                button(card, "Copiar código") {
                    val clip = ClipData.newPlainText("LumenLink · configuración privada", config)
                    if (android.os.Build.VERSION.SDK_INT >= 33) {
                        clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                    }
                    (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
                    toast("Código copiado. Pégalo en el otro teléfono.")
                }
                card.addView(text("El código es privado: compártelo solo con tu otro teléfono.", 14f, muted))
            }
        } else {
            card.addView(text("Conectar con el sensor", 20f, bold = true))
            val config = field(card, "Pega aquí el código del sensor", initial = pairText)
            config.setSingleLine(false)
            button(card, "Conectar") {
                try {
                    val pair = Pairing.parse(config.text.toString())
                    pairText = config.text.toString().trim()
                    latest = null; trend.clear(); ldrTrend.clear()
                    net.startController(pair)
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } catch (_: Exception) { toast("El código no es válido. Cópialo de nuevo desde el sensor.") }
            }
        }
        button(card, "Desconectar") { stopConnection(); showTab("Conectar") }
        card.addView(text("Deja la app abierta en los dos teléfonos mientras los usas.", 14f, muted))
    }
    // Al cambiar de rol, termina la sesión de red y restablece la simulación.
    private fun changeMode(node: Boolean) {
        if (nodeMode != node) {
            stopConnection(); nodeMode = node; latest = null; trend.clear(); ldrTrend.clear(); lighting.updateSensors(100.0, 80.0); lighting.enableAutomatic()
        }
        showTab("Conectar")
    }
    // Detiene el enlace y, salvo al salir temporalmente, renueva el token secreto.
    private fun stopConnection(invalidateToken: Boolean = true) {
        nodeRunning = false; syncRunning = false
        if (::net.isInitialized) net.stop()
        if (invalidateToken) token = freshToken()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    // Consulta las últimas 100 lecturas, las filtra según la opción elegida y muestra un resumen simple.
    private fun history() {
        val card = column(content, true)
        card.addView(text("Historial", 22f, bold = true))
        val totalView = text("Cargando…", 15f, muted).also { card.addView(it) }
        if (!nodeMode) button(card, "Traer datos del sensor") { requestSync() }
        button(card, "Actualizar") { showTab("Historial") }
        // Opciones de consulta: la activa se resalta en verde azulado.
        val filters = column(content, true)
        filters.addView(text("Mostrar", 16f, muted, true))
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (option in HistoryFilter.values()) {
            chips.addView(Button(this).apply {
                text = option.label; isAllCaps = false; textSize = 13f; minimumWidth = 0; minWidth = 0; setPadding(0, 0, 0, 0)
                setTextColor(if (option == historyFilter) Color.WHITE else navy)
                backgroundTintList = android.content.res.ColorStateList.valueOf(if (option == historyFilter) teal else Color.rgb(226, 234, 238))
                setOnClickListener { historyFilter = option; showTab("Historial") }
            }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(3) })
        }
        filters.addView(chips)
        // Resumen de las lecturas seleccionadas, en pocas líneas.
        val statsCard = column(content, true)
        statsCard.addView(text("Resumen · ${historyFilter.label}", 18f, bold = true))
        val statsView = text("Calculando…", 16f).also { statsCard.addView(it) }
        // Calculadora plegada: estima el consumo mensual a partir del % de tiempo con luz encendida.
        val calc = column(content, true)
        button(calc, if (showCalculator) "Ocultar calculadora" else "Calcular gasto de luz") { showCalculator = !showCalculator; showTab("Historial") }
        if (showCalculator) {
            val watts = field(calc, "Potencia de la ampolleta (W)", initial = "9")
            val hours = field(calc, "Horas de uso al día", initial = "12")
            val tariff = field(calc, "Precio del kWh (CLP)", initial = "200")
            listOf(watts, hours, tariff).forEach { it.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }
            val calcResult = text("", 16f, navy)
            button(calc, "Calcular") {
                val w = watts.text.toString().replace(',', '.').toDoubleOrNull()
                val h = hours.text.toString().replace(',', '.').toDoubleOrNull()
                val t = tariff.text.toString().replace(',', '.').toDoubleOrNull()
                watts.error = null; hours.error = null; tariff.error = null
                val error = EnergyCalculator.validate(w, h, t)
                val onPercent = allStats?.lightOnPercent
                when {
                    error != null -> {
                        val target = when { error.startsWith("Potencia") -> watts; error.startsWith("Horas") -> hours; else -> tariff }
                        target.error = error; target.requestFocus(); toast(error)
                    }
                    onPercent == null -> toast("Aún no hay datos para calcular. Conéctate primero.")
                    else -> {
                        val e = EnergyCalculator.estimate(onPercent, w!!, h!!, t!!)
                        calcResult.text = "Gasto al mes: \$${fmt(e.costPerMonthClp, 0)} (${fmt(e.kwhPerMonth, 2)} kWh)\n" +
                            "Ahorras: \$${fmt(e.savedClpPerMonth, 0)} al mes frente a dejarla siempre encendida"
                    }
                }
            }
            calc.addView(calcResult)
        }
        val listCard = column(content, true)
        listCard.addView(text("Lecturas · ${historyFilter.label}", 18f, bold = true))
        val result = text("Cargando…", 15f, muted).also { listCard.addView(it) }
        val owner = user ?: return
        val filter = historyFilter
        io.execute {
            val loaded = runCatching {
                val rows = db.list(owner)
                Triple(db.count(owner), rows, rows.filter(filter::matches))
            }
            runOnUiThread {
                if (user != owner || tab != "Historial" || !result.isAttachedToWindow) return@runOnUiThread
                val (total, rows, selected) = loaded.getOrElse {
                    result.text = "No se pudo leer el historial."; statsView.text = "Sin datos."; return@runOnUiThread
                }
                totalView.text = "$total registros guardados"
                allStats = ReadingStats.of(rows)
                val s = ReadingStats.of(selected)
                statsView.text = if (s == null) "No hay lecturas para esta opción." else
                    "Lecturas: ${s.count}\n" +
                    "Luz encendida: ${fmt(s.lightOnPercent, 0)} % del tiempo\n" +
                    "Luz del ambiente promedio: ${fmt(s.avgLdrPercent, 0)} %\n" +
                    "Distancia promedio: ${fmt(s.avgDistanceCm, 0)} cm"
                result.text = if (rows.isEmpty()) "Aún no hay lecturas. Conéctate en la pestaña Conectar."
                else if (selected.isEmpty()) "Ninguna lectura coincide con «${filter.label}»."
                else selected.joinToString("\n\n") { "${timeFor(it.at)}\n${if (it.lightOn) "💡 Encendida" else "Apagada"} · luz ${fmt(it.ldrPercent, 0)} % · ${fmt(it.distanceCm, 0)} cm" }
            }
        }
    }
    // Formatea un número con la cantidad de decimales indicada.
    private fun fmt(value: Double, decimals: Int): String = "%.${decimals}f".format(Locale("es", "CL"), value)
    // El panel envía la orden; el estado de la luz se confirma con la respuesta del nodo.
    private fun command(action: String, value: Double? = null) {
        // En el teléfono sensor la orden se aplica directo, aunque no haya otro teléfono conectado.
        if (nodeMode) { applyCommand(action, value); return }
        if (!net.connected) { toast("Primero conéctate con el sensor en la pestaña Conectar."); return }
        net.send(JSONObject().put("type", "command").put("action", action).apply { if (value != null) put("value", value) })
        toast("Comando enviado. Esperando estado confirmado del nodo.")
    }
    // El nodo aplica la orden recibida y publica su estado actualizado.
    private fun applyCommand(action: String, value: Double?) {
        when (action) {
            "light_on" -> lighting.manualLight(true)
            "light_off" -> lighting.manualLight(false)
            "auto" -> lighting.enableAutomatic()
            "set_threshold" -> { require(value != null); lighting.setThreshold(value) }
            "set_ldr_threshold" -> { require(value != null); lighting.setLdrThreshold(value) }
            else -> error("Comando desconocido")
        }
        sample()
    }
    // Identifica cada mensaje: comando, lectura, continuidad o páginas del historial.
    private fun receive(value: JSONObject) {
        try {
            when (value.getString("type")) {
                "ping" -> if (nodeMode) net.send(JSONObject().put("type", "pong"))
                "pong" -> Unit
                "command" -> if (nodeMode && nodeRunning) {
                    applyCommand(value.getString("action"), if (value.has("value")) value.getDouble("value") else null)
                }
                "reading" -> if (!nodeMode) {
                    val r = Reading.parse(value.getJSONObject("data"))
                    latest = r; addTrend(r); renderReading()
                    val owner = user ?: return
                    io.execute { runCatching { db.save(owner, r, "remote") } }
                }
                "history_request" -> if (nodeMode && nodeRunning) {
                    val offset = value.getInt("offset")
                    val request = value.getString("request")
                    require(offset in 0..2000 && request.length in 1..80)
                    val owner = user ?: return
                    io.execute {
                        runCatching {
                            // Fija una copia del historial para que las páginas no cambien durante el envío.
                            if (offset == 0) snapshot = buildList {
                                for (page in 0 until 20) {
                                    val batch = db.list(owner, "node", 100, page * 100)
                                    addAll(batch)
                                    if (batch.size < 100) break
                                }
                            }
                            val rows = snapshot.drop(offset).take(100)
                            net.send(JSONObject().put("type", "history").put("request", request)
                                .put("offset", offset).put("total", snapshot.size)
                                .put("data", JSONArray().apply { rows.forEach { put(it.json()) } }))
                        }
                    }
                }
                "history" -> if (!nodeMode && syncRunning && value.getString("request") == syncId) {
                    val data = value.getJSONArray("data")
                    val offset = value.getInt("offset")
                    val total = value.getInt("total")
                    require(data.length() in 0..100 && total in 0..2000 && offset in 0..total)
                    val rows = (0 until data.length()).map { Reading.parse(data.getJSONObject(it)) }
                    val owner = user ?: return
                    val request = syncId
                    io.execute {
                        val saved = runCatching { rows.forEach { db.save(owner, it, "remote") } }.isSuccess
                        runOnUiThread {
                            if (user != owner || syncId != request || !syncRunning) return@runOnUiThread
                            val next = offset + rows.size
                            if (saved && next < total && rows.isNotEmpty()) {
                                net.send(JSONObject().put("type", "history_request").put("request", request).put("offset", next))
                            } else {
                                syncRunning = false
                                toast(if (saved) "Historial sincronizado: $next registros revisados." else "No se pudo guardar el historial.")
                                if (tab == "Historial") showTab("Historial")
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) { toast("Mensaje rechazado: formato o valores inválidos.") }
    }
    // Inicia una sincronización con un identificador nuevo para distinguirla de las anteriores.
    private fun requestSync() {
        if (!net.connected || nodeMode) { toast("Conecta el panel al nodo para sincronizar."); return }
        if (syncRunning) { toast("La sincronización ya está en curso."); return }
        syncId = UUID.randomUUID().toString(); syncRunning = true
        net.send(JSONObject().put("type", "history_request").put("request", syncId).put("offset", 0))
    }
    // Cada dos segundos: el nodo toma una muestra y el panel comprueba que sigue conectado.
    private val ticker = object : Runnable {
        override fun run() {
            if (user != null && nodeMode && nodeRunning) sample()
            else if (user != null && !nodeMode && ::net.isInitialized && net.connected)
                net.send(JSONObject().put("type", "ping"))
            handler.postDelayed(this, 2_000)
        }
    }
    // Publica el estado del simulador y guarda la misma lectura en el historial.
    private fun sample() {
        val r = lighting.reading(UUID.randomUUID().toString(), System.currentTimeMillis())
        latest = r; addTrend(r); renderReading()
        val owner = user ?: return
        io.execute {
            if (runCatching { db.save(owner, r, "node") }.isFailure)
                runOnUiThread { toast("No se pudo guardar una lectura. Revisa el almacenamiento.") }
        }
        if (net.connected) net.send(JSONObject().put("type", "reading").put("data", r.json()))
    }
    // Conserva solamente las últimas 30 distancias y luminosidades para los gráficos.
    private fun addTrend(r: Reading) {
        trend.addLast(r.distanceCm.toFloat())
        while (trend.size > 30) trend.removeFirst()
        ldrTrend.addLast(r.ldrPercent.toFloat())
        while (ldrTrend.size > 30) ldrTrend.removeFirst()
    }
    // Actualiza los textos y el gráfico con el estado confirmado del nodo.
    private fun renderReading() {
        val r = latest ?: return
        distanceView?.text = "${"%.1f".format(Locale.ROOT, r.distanceCm)} cm"
        detailView?.text = "Última lectura: ${time.format(Date(r.at))}"
        val near = r.distanceCm <= r.thresholdCm
        val dark = r.ldrPercent <= r.ldrThresholdPercent
        ldrView?.text = "${"%.0f".format(Locale.ROOT, r.ldrPercent)} %"
        ldrView?.setTextColor(if (dark) navy else LdrBar.amberDark)
        ldrStateView?.text = when {
            dark -> "🌙 Está oscuro"
            r.ldrPercent < 60 -> "⛅ Luz media"
            else -> "☀️ Mucha luz"
        }
        ldrStateView?.setTextColor(if (dark) navy else LdrBar.amberDark)
        ldrBar?.apply { percent = r.ldrPercent.toFloat(); threshold = r.ldrThresholdPercent.toFloat(); invalidate() }
        ldrTrendView?.apply { values = ldrTrend.toList(); marker = r.ldrThresholdPercent.toFloat(); invalidate() }
        val cause = when {
            !r.automatic -> "Controlada a mano"
            near && dark -> "Hay alguien cerca y está oscuro"
            near -> "Hay alguien cerca"
            dark -> "Está oscuro"
            else -> "Nadie cerca y hay luz"
        }
        lightOnView?.text = "${if (r.lightOn) "💡 LUZ ENCENDIDA" else "LUZ APAGADA"} · ${if (r.automatic) "Automático" else "Manual"}\n$cause"
        lightOnView?.setTextColor(if (r.lightOn) navy else muted)
        lightOnView?.background = background(if (r.lightOn) Color.rgb(255, 236, 179) else Color.rgb(232, 238, 241), 14)
        trendView?.values = trend.toList()
        trendView?.invalidate()
    }
    // Muestra un aviso breve en la pantalla.
    private fun toast(value: String) { Toast.makeText(this, value, Toast.LENGTH_LONG).show() }
    // Busca direcciones IPv4 de interfaces activas para conectar desde el otro teléfono.
    private fun localAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>()
            .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }.mapNotNull { it.hostAddress }.distinct()
    }.getOrDefault(emptyList())
    // Genera 16 bytes aleatorios y los representa con 32 caracteres hexadecimales.
    private fun freshToken(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    // Convierte la fecha numérica de una lectura al formato que muestra el historial.
    private fun timeFor(at: Long): String = SimpleDateFormat("dd/MM HH:mm:ss", Locale("es", "CL")).format(Date(at))
    // Esta versión funciona en primer plano; salir de la app detiene el enlace.
    override fun onStop() {
        super.onStop()
        if (::net.isInitialized) {
            stopConnection(invalidateToken = false)
            status = "Desconectado al salir de la app. Vuelve a conectar en Conectar."
            statusView?.text = status
        }
    }
    // Libera las tareas, sockets y base de datos cuando Android destruye esta pantalla.
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::net.isInitialized) net.dispose()
        io.execute { if (::db.isInitialized) db.close() }
        io.shutdown()
        super.onDestroy()
    }
    @Deprecated("Activity back navigation")
    @Suppress("DEPRECATION")
    // El botón Atrás vuelve al panel o pide confirmar la salida si hay una sesión abierta.
    override fun onBackPressed() {
        if (user != null && tab != "Panel") showTab("Panel")
        else if (user != null) AlertDialog.Builder(this).setTitle("Salir de LumenLink")
            .setMessage("Se detendrá la conexión con el otro teléfono.")
            .setPositiveButton("Salir") { _, _ -> finish() }.setNegativeButton("Volver", null).show()
        else super.onBackPressed()
    }
}

// BLOQUE 2: GRÁFICO DE DISTANCIA. TrendView dibuja las últimas muestras; no modifica las reglas de iluminación.

class TrendView(context: Context) : View(context) {
    var values: List<Float> = emptyList()
    var lineColor: Int = Color.rgb(8, 127, 140)
    // fixedRange fija la escala (por ejemplo 0..100 %); marker dibuja una línea horizontal de referencia.
    var fixedRange: ClosedFloatingPointRange<Float>? = null
    var marker: Float? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    // Dibuja una cuadrícula y une los valores con una línea.
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        paint.color = Color.rgb(224, 233, 237); paint.strokeWidth = density
        for (i in 1..3) canvas.drawLine(0f, height * i / 4f, width.toFloat(), height * i / 4f, paint)
        val low = fixedRange?.start ?: ((values.minOrNull() ?: 0f) - 1f)
        val high = fixedRange?.endInclusive ?: ((values.maxOrNull() ?: 0f) + 1f)
        fun y(value: Float) = height - (value - low) / (high - low) * (height - 16 * density) - 8 * density
        marker?.let { m ->
            paint.color = Color.rgb(16, 44, 64); paint.strokeWidth = 2 * density
            canvas.drawLine(0f, y(m), width.toFloat(), y(m), paint)
        }
        if (values.size < 2) return
        val path = Path()
        values.forEachIndexed { index, value ->
            val x = index.toFloat() * width / (values.size - 1)
            if (index == 0) path.moveTo(x, y(value)) else path.lineTo(x, y(value))
        }
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 3 * density; paint.color = lineColor
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL
    }
}

/** Barra horizontal de luminosidad: se llena según el % de la LDR y marca el umbral de oscuridad. */
class LdrBar(context: Context) : View(context) {
    companion object {
        val amber: Int = Color.rgb(245, 166, 35)
        val amberDark: Int = Color.rgb(176, 104, 0)
    }
    var percent: Float = 0f
    var threshold: Float = 30f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = android.graphics.RectF()
    // Dibuja el fondo, el relleno degradado (oscuro → luz) y la marca del umbral.
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val radius = height / 2f
        box.set(0f, 0f, width.toFloat(), height.toFloat())
        paint.shader = null; paint.color = Color.rgb(224, 233, 237)
        canvas.drawRoundRect(box, radius, radius, paint)
        val fill = width * percent.coerceIn(0f, 100f) / 100f
        if (fill > 0f) {
            paint.shader = android.graphics.LinearGradient(0f, 0f, width.toFloat(), 0f,
                Color.rgb(16, 44, 64), Color.rgb(255, 214, 10), android.graphics.Shader.TileMode.CLAMP)
            box.set(0f, 0f, fill.coerceAtLeast(height.toFloat()), height.toFloat())
            canvas.drawRoundRect(box, radius, radius, paint)
            paint.shader = null
        }
        val x = width * threshold.coerceIn(0f, 100f) / 100f
        paint.color = Color.WHITE; paint.strokeWidth = 5 * density
        canvas.drawLine(x, 0f, x, height.toFloat(), paint)
        paint.color = Color.rgb(16, 44, 64); paint.strokeWidth = 2 * density
        canvas.drawLine(x, 0f, x, height.toFloat(), paint)
    }
}

// BLOQUE 3: CONEXIÓN ENTRE PANEL Y NODO. NetworkEngine transporta mensajes JSON sobre TLS; la lógica de las órdenes está en MainActivity.

/** Conecta un panel y un nodo mediante TLS y un token de emparejamiento. */
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
    // Notifica el estado solo si la tarea todavía pertenece a la sesión actual.
    private fun announce(g: Long, text: String, ready: Boolean = false) {
        if (generation.get() == g) { connected = ready; state(text, ready) }
    }
    // Abre un servidor TLS y comprueba el token antes de aceptar mensajes del panel.
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
    // Conecta el panel con la huella esperada y reintenta cada 3 segundos si se pierde el enlace.
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
                // Reintenta solo si el usuario no ha detenido la conexión.
                if (generation.get() == g) try { Thread.sleep(3_000) } catch (_: InterruptedException) { break }
            }
        }, "lumenlink-client").start()
    }
    // Lee mensajes completos hasta que termine la conexión o se cambie de sesión.
    private fun readLoop(g: Long, input: DataInputStream) {
        while (generation.get() == g) {
            val value = Frames.read(input)
            if (generation.get() == g) message(value)
        }
    }
    // Envía en una cola de trabajo para mantener el orden y no bloquear la pantalla.
    fun send(value: JSONObject) {
        val g = generation.get()
        val target = socket ?: return
        writer.execute {
            if (generation.get() != g || socket !== target) return@execute
            try { synchronized(writeLock) { output?.let { Frames.write(it, value) } } }
            catch (_: Exception) { runCatching { target.close() } }
        }
    }
    // Invalida las tareas antiguas y cierra el servidor y la conexión actual.
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
    // Cierra la conexión y termina también la cola de envío.
    fun dispose() { stop(); writer.shutdownNow() }
}

// BLOQUE 4: CUENTAS E HISTORIAL EN SQLITE. AppDatabase filtra por usuario y cifra las lecturas antes de guardarlas.

/** Guarda cuentas y lecturas; cada consulta de historial se limita a su usuario. */
class AppDatabase(context: Context, private val security: DeviceSecurity) : SQLiteOpenHelper(context, "lumenlink.db", null, 1) {
    // @Synchronized impide que dos llamadas modifiquen simultáneamente estos datos.
    // Crea las tablas de cuentas y lecturas cuando la base se abre por primera vez.
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE users(name TEXT PRIMARY KEY, salt BLOB NOT NULL, hash BLOB NOT NULL, failures INTEGER NOT NULL DEFAULT 0, locked_until INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE readings(owner TEXT NOT NULL, id TEXT NOT NULL, at INTEGER NOT NULL, source TEXT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(owner,id,source))")
        db.execSQL("CREATE INDEX readings_owner_time ON readings(owner,at DESC)")
    }
    // La base sigue en su versión 1; aquí se añadirían futuras migraciones.
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    // Valida los datos, deriva la contraseña con una sal y rechaza usuarios repetidos.
    @Synchronized fun register(user: String, password: String): String {
        if (!PasswordHasher.validUser(user)) return "Usuario: usa entre 3 y 32 letras, números, punto, guion o guion bajo."
        if (!PasswordHasher.validPassword(password)) return "La contraseña debe tener entre 6 y 128 caracteres."
        val salt = PasswordHasher.salt()
        val chars = password.toCharArray()
        val hash = try { PasswordHasher.hash(chars, salt) } finally { chars.fill('\u0000') }
        val values = ContentValues().apply { put("name", user); put("salt", salt); put("hash", hash) }
        return if (writableDatabase.insertWithOnConflict("users", null, values, SQLiteDatabase.CONFLICT_IGNORE) == -1L)
            "El usuario ya existe." else "OK"
    }
    // Comprueba la contraseña y aplica el bloqueo temporal después de cinco fallos.
    @Synchronized fun login(user: String, password: String): Boolean {
        readableDatabase.query("users", null, "name=?", arrayOf(user), null, null, null).use { c ->
            if (!c.moveToFirst()) {
                // También deriva una clave si el usuario no existe para reducir diferencias de tiempo.
                val chars = password.take(128).toCharArray()
                try { PasswordHasher.hash(chars, ByteArray(16)) } finally { chars.fill('\u0000') }
                return false
            }
            val now = System.currentTimeMillis()
            if (c.getLong(c.getColumnIndexOrThrow("locked_until")) > now) return false
            val chars = password.take(128).toCharArray()
            val valid = try { password.length <= 128 && PasswordHasher.matches(chars,
                c.getBlob(c.getColumnIndexOrThrow("salt")), c.getBlob(c.getColumnIndexOrThrow("hash"))) }
                finally { chars.fill('\u0000') }
            // Cinco fallos bloquean la cuenta durante 30 segundos.
            val failures = if (valid) 0 else c.getInt(c.getColumnIndexOrThrow("failures")) + 1
            val values = ContentValues().apply {
                put("failures", if (failures >= 5) 0 else failures)
                put("locked_until", if (!valid && failures >= 5) now + 30_000 else 0)
            }
            writableDatabase.update("users", values, "name=?", arrayOf(user))
            return valid
        }
    }
    // Cifra la lectura y evita duplicarla por usuario, identificador y origen.
    @Synchronized fun save(user: String, r: Reading, source: String) {
        val values = ContentValues().apply {
            put("owner", user); put("id", r.id); put("at", r.at); put("source", source)
            put("payload", security.encrypt(r.json().toString()))
        }
        // Conserva el ID original para que una sincronización repetida no duplique la lectura.
        writableDatabase.insertWithOnConflict("readings", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        // Conserva las 2.000 lecturas más recientes de cada usuario.
        writableDatabase.execSQL("DELETE FROM readings WHERE owner=? AND rowid NOT IN (SELECT rowid FROM readings WHERE owner=? ORDER BY at DESC LIMIT 2000)", arrayOf(user, user))
    }
    // Consulta una página del historial y descifra cada lectura antes de devolverla.
    @Synchronized fun list(user: String, source: String? = null, limit: Int = 100, offset: Int = 0): List<Reading> {
        require(limit in 1..100 && offset in 0..2000)
        val where = if (source == null) "owner=?" else "owner=? AND source=?"
        val args = if (source == null) arrayOf(user) else arrayOf(user, source)
        return readableDatabase.query("readings", arrayOf("payload"), where, args, null, null,
            "at DESC, id ASC", "$offset,$limit").use { c ->
            buildList { while (c.moveToNext()) add(Reading.parse(JSONObject(security.decrypt(c.getString(0))))) }
        }
    }
    // Cuenta los registros que pertenecen al usuario indicado.
    @Synchronized fun count(user: String): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM readings WHERE owner=?", arrayOf(user)).use {
        it.moveToFirst(); it.getInt(0)
    }
}

// BLOQUE 5: CLAVES Y CIFRADO DEL DISPOSITIVO. DeviceSecurity mantiene las claves en Android Keystore y prepara los certificados TLS.

/** Mantiene las claves en Android Keystore y cifra el contenido de las lecturas. */
class DeviceSecurity {
    private val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    // Solo crea las claves si no existen: conserva el acceso a las lecturas guardadas anteriormente.
    init {
        if (!store.containsAlias("lumenlink-storage")) {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder("lumenlink-storage", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).build())
            }.generateKey()
        }
        if (!store.containsAlias("lumenlink-node-tls-v1")) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder("lumenlink-node-tls-v1", KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    // Estos permisos permiten que Android firme el intercambio TLS.
                    // Los mensajes de la aplicación se cifran con TLS, no con RSA directo.
                    .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE, KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, KeyProperties.SIGNATURE_PADDING_RSA_PSS)
                    .setKeySize(2048).setCertificateSubject(X500Principal("CN=LumenLink educational node"))
                    .setCertificateSerialNumber(BigInteger.ONE)
                    .setCertificateNotBefore(Date(1_577_836_800_000L))
                    .setCertificateNotAfter(Date(2_208_988_800_000L)).build())
            }.generateKeyPair()
        }
    }
    // Cifra el texto con AES-GCM y devuelve IV más contenido cifrado en Base64.
    fun encrypt(text: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, store.getKey("lumenlink-storage", null) as SecretKey)
        // Guarda el IV junto al texto cifrado para poder descifrar esa lectura después.
        return Base64.encodeToString(cipher.iv + cipher.doFinal(text.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }
    // Separa el IV, comprueba la integridad y recupera el texto original.
    fun decrypt(text: String): String {
        val data = Base64.decode(text, Base64.NO_WRAP)
        require(data.size >= 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, store.getKey("lumenlink-storage", null) as SecretKey,
            GCMParameterSpec(128, data.copyOfRange(0, 12)))
        return String(cipher.doFinal(data.copyOfRange(12, data.size)), Charsets.UTF_8)
    }
    // La huella identifica el certificado público; no revela la clave privada.
    val pin: String get() = fingerprint(store.getCertificate("lumenlink-node-tls-v1") as X509Certificate)
    // Prepara TLS con el certificado y la clave privada del nodo.
    fun serverContext(): SSLContext {
        val keyManager = object : X509KeyManager {
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
            override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? = null
            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? =
                if (keyType == "RSA") arrayOf("lumenlink-node-tls-v1") else null
            override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? =
                if (keyType == "RSA") "lumenlink-node-tls-v1" else null
            override fun getCertificateChain(alias: String?): Array<X509Certificate> =
                arrayOf(store.getCertificate("lumenlink-node-tls-v1") as X509Certificate)
            override fun getPrivateKey(alias: String?): PrivateKey = store.getKey("lumenlink-node-tls-v1", null) as PrivateKey
        }
        return SSLContext.getInstance("TLSv1.2").apply { init(arrayOf<KeyManager>(keyManager), null, null) }
    }
    // El panel verifica la huella del nodo antes de enviar su token secreto.
    // Prepara TLS para aceptar solamente el certificado cuya huella recibió el panel.
    fun clientContext(pin: String): SSLContext = SSLContext.getInstance("TLSv1.2").apply {
        init(null, arrayOf<TrustManager>(PinnedTrust(pin)), null)
    }
}
