package cl.ti3042.lumenlink

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class UiFlowTest {
    private fun views(v: View): List<View> = listOf(v) + if (v is ViewGroup)
        (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun all(a: MainActivity): List<View> = views(a.window.decorView)
    private fun click(a: MainActivity, label: String) {
        all(a).filterIsInstance<Button>().first { it.text.toString() == label }.performClick()
    }
    // Mueve un deslizador del simulador: 0 es la luz del ambiente y 1 la distancia.
    private fun slide(a: MainActivity, index: Int, value: Int) {
        all(a).filterIsInstance<android.widget.SeekBar>()[index].progress = value
    }
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
    @Test fun registrationLoginNodeControlAndHistory() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val account = "ui_${System.nanoTime()}"
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitFor(scenario, "Crear cuenta local")
            capture(scenario, "01-acceso.png")
            scenario.onActivity { a ->
                val fields = all(a).filterIsInstance<EditText>()
                fields[0].setText(account); fields[1].setText("Una clave segura de prueba"); fields[2].setText("Una clave segura de prueba")
                click(a, "Crear cuenta local")
            }
            waitFor(scenario, "Cuenta creada")
            scenario.onActivity { a ->
                all(a).filterIsInstance<EditText>()[1].setText("Una clave segura de prueba")
                click(a, "Iniciar sesión")
            }
            waitFor(scenario, "Luz del ambiente")
            scenario.onActivity { click(it, "Conectar") }
            scenario.onActivity { click(it, "Ser el sensor") }
            scenario.onActivity { a ->
                all(a).filterIsInstance<EditText>().first().setText("127.0.0.1")
                click(a, "Encender sensor")
            }
            waitFor(scenario, "Sensor listo")
            scenario.onActivity { click(it, "Panel") }
            waitFor(scenario, "Última lectura:")
            waitFor(scenario, "LUZ APAGADA · Automático")
            scenario.onActivity { slide(it, 0, 10) }
            waitFor(scenario, "LUZ ENCENDIDA · Automático")
            scenario.onActivity { slide(it, 0, 80) }
            waitFor(scenario, "LUZ APAGADA · Automático")
            scenario.onActivity { slide(it, 1, 10) }
            waitFor(scenario, "LUZ ENCENDIDA · Automático")
            scenario.onActivity { slide(it, 1, 100) }
            waitFor(scenario, "LUZ APAGADA · Automático")
            scenario.onActivity { click(it, "Encender") }
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
                click(a, "Salir")
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
