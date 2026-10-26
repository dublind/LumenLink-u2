package cl.ti3042.lumenlink

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import cl.ti3042.lumenlink.core.EnergyCalculator
import cl.ti3042.lumenlink.core.HistoryFilter
import cl.ti3042.lumenlink.core.LightingController
import cl.ti3042.lumenlink.core.Pairing
import cl.ti3042.lumenlink.core.PasswordHasher
import cl.ti3042.lumenlink.core.Reading
import cl.ti3042.lumenlink.core.ReadingStats
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val navy = Color.rgb(16, 44, 64)
    private val teal = Color.rgb(8, 127, 140)
    private val muted = Color.rgb(82, 104, 118)
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var security: DeviceSecurity
    private lateinit var db: AppDatabase
    private lateinit var net: NetworkEngine
    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout
    private var user: String? = null
    private var tab = "Panel"
    private var nodeMode = false
    private var nodeRunning = false
    private var status = "Desconectado"
    private var token = freshToken()
    private var latest: Reading? = null
    private val lighting = LightingController()
    private var syncRunning = false
    private var syncId = ""
    private var snapshot: List<Reading> = emptyList() // Solo se consulta desde la cola de trabajo de datos.
    private var historyFilter = HistoryFilter.ALL
    private var allStats: ReadingStats? = null
    private var showSettings = false
    private var showCalculator = false
    private var pairText = ""
    private var chosenIp = ""
    private var statusView: TextView? = null
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        val loading = TextView(this).apply { text = "Preparando almacenamiento seguro…"; gravity = Gravity.CENTER }
        setContentView(loading)
        io.execute {
            try {
                security = DeviceSecurity()
                db = AppDatabase(applicationContext, security)
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
    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).toInt()
    private fun background(color: Int, radius: Int = 16) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }
    private fun text(value: String, size: Float = 16f, color: Int = navy, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(5), 0, dp(5))
    }
    private fun column(parent: LinearLayout, card: Boolean = false): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(16))
        if (card) background = background(Color.WHITE)
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
    }
    private fun button(parent: LinearLayout, label: String, action: () -> Unit): Button = Button(this).apply {
        text = label; isAllCaps = false; textSize = 15f
        setTextColor(Color.WHITE); backgroundTintList = android.content.res.ColorStateList.valueOf(teal)
        minHeight = dp(48)
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        setOnClickListener { action() }
    }
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
    private fun field(parent: LinearLayout, hintText: String, secret: Boolean = false, initial: String = ""): EditText = EditText(this).apply {
        parent.addView(this@MainActivity.text(hintText, 14f, muted, true).apply { setPadding(0, dp(10), 0, 0) })
        hint = hintText; textSize = 18f; setText(initial); setTextColor(navy)
        inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        filters = arrayOf(InputFilter.LengthFilter(if (secret) 128 else 256))
        setPadding(dp(8), dp(10), dp(8), dp(10))
        parent.addView(this, LinearLayout.LayoutParams(-1, -2))
    }
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
    private fun logout() {
        stopConnection(); user = null; latest = null; pairText = ""; historyFilter = HistoryFilter.ALL; allStats = null
        trend.clear(); ldrTrend.clear(); showSettings = false; showCalculator = false; loginScreen()
    }
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
    private fun dashboard() {
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
        val values = column(content, true)
        values.addView(text("Distancia", 16f, muted, true))
        distanceView = text("— cm", 40f, bold = true).also { values.addView(it) }
        trendView = TrendView(this).also { values.addView(it, LinearLayout.LayoutParams(-1, dp(80))) }
        detailView = text("Conéctate en la pestaña Conectar para ver datos.", 14f, muted).also { values.addView(it) }
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
    private fun history() {
        val card = column(content, true)
        card.addView(text("Historial", 22f, bold = true))
        val totalView = text("Cargando…", 15f, muted).also { card.addView(it) }
        if (!nodeMode) button(card, "Traer datos del sensor") { requestSync() }
        button(card, "Actualizar") { showTab("Historial") }
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
        val statsCard = column(content, true)
        statsCard.addView(text("Resumen · ${historyFilter.label}", 18f, bold = true))
        val statsView = text("Calculando…", 16f).also { statsCard.addView(it) }
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
    private fun fmt(value: Double, decimals: Int): String = "%.${decimals}f".format(Locale("es", "CL"), value)
    private fun command(action: String, value: Double? = null) {
        // En el teléfono sensor la orden se aplica directo, aunque no haya otro teléfono conectado.
        if (nodeMode) { applyCommand(action, value); return }
        if (!net.connected) { toast("Primero conéctate con el sensor en la pestaña Conectar."); return }
        net.send(JSONObject().put("type", "command").put("action", action).apply { if (value != null) put("value", value) })
        toast("Comando enviado. Esperando estado confirmado del nodo.")
    }
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
    private fun requestSync() {
        if (!net.connected || nodeMode) { toast("Conecta el panel al nodo para sincronizar."); return }
        if (syncRunning) { toast("La sincronización ya está en curso."); return }
        syncId = UUID.randomUUID().toString(); syncRunning = true
        net.send(JSONObject().put("type", "history_request").put("request", syncId).put("offset", 0))
    }
    private val ticker = object : Runnable {
        override fun run() {
            if (user != null && nodeMode && nodeRunning) sample()
            else if (user != null && !nodeMode && ::net.isInitialized && net.connected)
                net.send(JSONObject().put("type", "ping"))
            handler.postDelayed(this, 2_000)
        }
    }
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
    private fun addTrend(r: Reading) {
        trend.addLast(r.distanceCm.toFloat())
        while (trend.size > 30) trend.removeFirst()
        ldrTrend.addLast(r.ldrPercent.toFloat())
        while (ldrTrend.size > 30) ldrTrend.removeFirst()
    }
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
    private fun toast(value: String) { Toast.makeText(this, value, Toast.LENGTH_LONG).show() }
    private fun localAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>()
            .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }.mapNotNull { it.hostAddress }.distinct()
    }.getOrDefault(emptyList())
    private fun freshToken(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
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
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::net.isInitialized) net.dispose()
        io.execute { if (::db.isInitialized) db.close() }
        io.shutdown()
        super.onDestroy()
    }
    @Deprecated("Activity back navigation")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (user != null && tab != "Panel") showTab("Panel")
        else if (user != null) AlertDialog.Builder(this).setTitle("Salir de LumenLink")
            .setMessage("Se detendrá la conexión con el otro teléfono.")
            .setPositiveButton("Salir") { _, _ -> finish() }.setNegativeButton("Volver", null).show()
        else super.onBackPressed()
    }
}
