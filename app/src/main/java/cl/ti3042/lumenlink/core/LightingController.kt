package cl.ti3042.lumenlink.core

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
