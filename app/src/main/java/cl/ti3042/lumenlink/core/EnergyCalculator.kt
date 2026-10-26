package cl.ti3042.lumenlink.core

object EnergyCalculator {
    const val DAYS_PER_MONTH = 30
    data class Estimate(val hoursOnPerDay: Double, val kwhPerMonth: Double, val costPerMonthClp: Double,
        val savedKwhPerMonth: Double, val savedClpPerMonth: Double)
    fun validate(watts: Double?, hoursPerDay: Double?, tariffClp: Double?): String? = when {
        watts == null || !watts.isFinite() || watts !in 1.0..500.0 -> "Potencia: ingresa entre 1 y 500 W."
        hoursPerDay == null || !hoursPerDay.isFinite() || hoursPerDay !in 1.0..24.0 -> "Horas de uso: ingresa entre 1 y 24 horas al día."
        tariffClp == null || !tariffClp.isFinite() || tariffClp !in 1.0..2000.0 -> "Tarifa: ingresa entre 1 y 2.000 CLP por kWh."
        else -> null
    }
    // kWh = W × horas encendida × 30 días / 1000; el costo multiplica esa energía por la tarifa.
    fun estimate(lightOnPercent: Double, watts: Double, hoursPerDay: Double, tariffClp: Double): Estimate {
        require(lightOnPercent.isFinite() && lightOnPercent in 0.0..100.0) { "Porcentaje encendida inválido" }
        validate(watts, hoursPerDay, tariffClp)?.let { throw IllegalArgumentException(it) }
        val hoursOn = hoursPerDay * lightOnPercent / 100.0
        val kwh = watts * hoursOn * DAYS_PER_MONTH / 1000.0
        val alwaysOnKwh = watts * hoursPerDay * DAYS_PER_MONTH / 1000.0
        return Estimate(hoursOn, kwh, kwh * tariffClp, alwaysOnKwh - kwh, (alwaysOnKwh - kwh) * tariffClp)
    }
}
