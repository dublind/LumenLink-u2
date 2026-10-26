package cl.ti3042.lumenlink

import cl.ti3042.lumenlink.core.LightingController
import org.junit.Assert
import org.junit.Test

class LightingControllerTest {
    @Test fun automaticRuleCoversEveryCombinationOfSensors() {
        val model = LightingController()
        for ((distance, illumination, expected) in listOf(
            Triple(100.0, 80.0, false), Triple(10.0, 80.0, true),
            Triple(100.0, 10.0, true), Triple(10.0, 10.0, true))) {
            model.updateSensors(distance, illumination)
            Assert.assertEquals("distance=$distance illumination=$illumination", expected, model.lightOn)
        }
    }
    @Test fun thresholdIncludesItsBoundaryAndReevaluatesImmediately() {
        val model = LightingController()
        model.updateSensors(30.0, 80.0)
        Assert.assertTrue(model.lightOn)
        model.updateSensors(30.1, 80.0)
        Assert.assertFalse(model.lightOn)
        model.setThreshold(40.0)
        Assert.assertTrue(model.lightOn)
    }
    @Test fun manualOverridePersistsUntilAutomaticIsEnabled() {
        val model = LightingController()
        model.manualLight(false)
        model.updateSensors(5.0, 10.0)
        Assert.assertFalse(model.lightOn)
        Assert.assertFalse(model.automatic)
        model.enableAutomatic()
        Assert.assertTrue(model.lightOn)
        model.updateSensors(100.0, 80.0)
        Assert.assertFalse(model.lightOn)
        model.manualLight(true)
        Assert.assertTrue(model.lightOn)
    }
    @Test fun invalidSensorValuesDoNotChangeTheLastValidState() {
        val model = LightingController()
        model.updateSensors(10.0, 80.0)
        for (bad in listOf(-1.0, 501.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            Assert.assertThrows(IllegalArgumentException::class.java) { model.updateSensors(bad, 80.0) }
            Assert.assertEquals(10.0, model.distanceCm, 0.0)
            Assert.assertTrue(model.lightOn)
        }
        for (bad in listOf(4.0, 201.0, Double.NaN)) {
            Assert.assertThrows(IllegalArgumentException::class.java) { model.setThreshold(bad) }
            Assert.assertEquals(30.0, model.thresholdCm, 0.0)
        }
    }
    @Test fun ldrBoundaryAndThresholdChangesUseTheCorrectPolarity() {
        val model = LightingController()
        model.updateSensors(100.0, 30.0)
        Assert.assertTrue(model.lightOn)
        model.updateSensors(100.0, 30.1)
        Assert.assertFalse(model.lightOn)
        model.setLdrThreshold(40.0)
        Assert.assertTrue(model.lightOn)
        model.updateSensors(100.0, 100.0)
        Assert.assertFalse(model.lightOn)
        model.updateSensors(100.0, 0.0)
        Assert.assertTrue(model.lightOn)
    }
    @Test fun invalidLdrInputIsRejectedAtomically() {
        val model = LightingController()
        for (bad in listOf(-1.0, 101.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            Assert.assertThrows(IllegalArgumentException::class.java) { model.updateSensors(5.0, bad) }
            Assert.assertEquals(100.0, model.distanceCm, 0.0)
            Assert.assertEquals(80.0, model.ldrPercent, 0.0)
            Assert.assertThrows(IllegalArgumentException::class.java) { model.setLdrThreshold(bad) }
            Assert.assertEquals(30.0, model.ldrThresholdPercent, 0.0)
        }
    }
}
