package cl.ti3042.lumenlink.core

import org.json.JSONObject

/** Lectura compartida por red e historial. La LDR usa porcentaje relativo, no lux. */
data class Reading(val id: String, val at: Long, val distanceCm: Double, val ldrPercent: Double,
    val lightOn: Boolean, val automatic: Boolean, val thresholdCm: Double, val ldrThresholdPercent: Double) {
    fun json(): JSONObject = JSONObject().put("id", id).put("at", at)
        .put("distanceCm", distanceCm).put("ldrPercent", ldrPercent).put("lightOn", lightOn)
        .put("automatic", automatic).put("thresholdCm", thresholdCm).put("ldrThresholdPercent", ldrThresholdPercent)
    companion object {
        fun parse(o: JSONObject): Reading {
            val r = Reading(o.getString("id"), o.getLong("at"), o.getDouble("distanceCm"),
                o.getDouble("ldrPercent"), o.getBoolean("lightOn"), o.getBoolean("automatic"), o.getDouble("thresholdCm"), o.getDouble("ldrThresholdPercent"))
            require(r.id.length in 1..80 && r.at > 0 && r.distanceCm.isFinite() && r.distanceCm in 0.0..500.0 &&
                r.thresholdCm.isFinite() && r.thresholdCm in 5.0..200.0 &&
                r.ldrPercent.isFinite() && r.ldrPercent in 0.0..100.0 &&
                r.ldrThresholdPercent.isFinite() && r.ldrThresholdPercent in 0.0..100.0) { "Lectura fuera de rango" }
            return r
        }
    }
}
