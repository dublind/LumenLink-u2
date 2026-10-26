package cl.ti3042.lumenlink

import cl.ti3042.lumenlink.core.EnergyCalculator
import cl.ti3042.lumenlink.core.HistoryFilter
import cl.ti3042.lumenlink.core.Reading
import cl.ti3042.lumenlink.core.ReadingStats
import org.junit.Assert
import org.junit.Test

class HistoryCalculationTest {
    private fun r(distance: Double, ldr: Double, on: Boolean, auto: Boolean) =
        Reading("id-$distance-$ldr-$on-$auto", 1L, distance, ldr, on, auto, 30.0, 30.0)
    private val rows = listOf(r(10.0, 80.0, true, true), r(100.0, 80.0, false, true),
        r(50.0, 20.0, true, false), r(200.0, 60.0, false, false))

    @Test fun filtersSelectMatchingReadings() {
        Assert.assertEquals(4, rows.count(HistoryFilter.ALL::matches))
        Assert.assertEquals(2, rows.count(HistoryFilter.LIGHT_ON::matches))
        Assert.assertEquals(2, rows.count(HistoryFilter.LIGHT_OFF::matches))
        Assert.assertEquals(2, rows.count(HistoryFilter.AUTOMATIC::matches))
        Assert.assertEquals(2, rows.count(HistoryFilter.MANUAL::matches))
    }
    @Test fun statsSummarizeReadings() {
        val s = ReadingStats.of(rows)!!
        Assert.assertEquals(4, s.count)
        Assert.assertEquals(90.0, s.avgDistanceCm, 0.001)
        Assert.assertEquals(10.0, s.minDistanceCm, 0.001)
        Assert.assertEquals(200.0, s.maxDistanceCm, 0.001)
        Assert.assertEquals(60.0, s.avgLdrPercent, 0.001)
        Assert.assertEquals(50.0, s.lightOnPercent, 0.001)
        Assert.assertEquals(50.0, s.automaticPercent, 0.001)
        Assert.assertNull(ReadingStats.of(emptyList()))
    }
    // 10 W, 12 h al día y 50 % encendida: 6 h × 10 W × 30 días = 1,8 kWh; a 200 CLP son 360 CLP.
    @Test fun energyEstimateMatchesHandCalculation() {
        val e = EnergyCalculator.estimate(50.0, 10.0, 12.0, 200.0)
        Assert.assertEquals(6.0, e.hoursOnPerDay, 0.001)
        Assert.assertEquals(1.8, e.kwhPerMonth, 0.001)
        Assert.assertEquals(360.0, e.costPerMonthClp, 0.001)
        Assert.assertEquals(1.8, e.savedKwhPerMonth, 0.001)
        Assert.assertEquals(360.0, e.savedClpPerMonth, 0.001)
    }
    @Test fun energyFormRejectsInvalidInput() {
        Assert.assertNull(EnergyCalculator.validate(10.0, 12.0, 200.0))
        Assert.assertTrue(EnergyCalculator.validate(null, 12.0, 200.0)!!.startsWith("Potencia"))
        Assert.assertTrue(EnergyCalculator.validate(10.0, 25.0, 200.0)!!.startsWith("Horas"))
        Assert.assertTrue(EnergyCalculator.validate(10.0, 12.0, 0.0)!!.startsWith("Tarifa"))
        Assert.assertThrows(IllegalArgumentException::class.java) { EnergyCalculator.estimate(50.0, 0.0, 12.0, 200.0) }
    }
}
