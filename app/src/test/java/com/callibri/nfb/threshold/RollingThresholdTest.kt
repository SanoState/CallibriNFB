package com.callibri.nfb.threshold

import com.callibri.nfb.protocol.BandGoal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RollingThresholdTest {
    @Test
    fun inhibitPercentilePutsTargetFractionBelowThreshold() {
        val threshold = rolling(BandGoal.InhibitBelow, target = 0.80)
        for (value in 1..10) {
            assertTrue(threshold.accept(value.toLong(), value.toDouble(), contactAcceptable = true))
        }
        assertEquals(8.2, threshold.threshold(10)!!, 1e-6)
        assertEquals(0.80, threshold.windowSuccess(10)!!, 1e-9)

        val spiked = rolling(BandGoal.InhibitBelow, target = 0.80)
        repeat(40) { index ->
            spiked.accept(index.toLong(), 10.0, contactAcceptable = true)
        }
        spiked.accept(41, 200.0, contactAcceptable = true)
        assertEquals(10.0, spiked.threshold(41)!!, 1e-6)
        assertFalse(spiked.accept(42, 10_000.0, contactAcceptable = true))
        assertEquals(10.0, spiked.threshold(42)!!, 1e-6)
    }

    @Test
    fun rewardPercentilePutsTargetFractionAboveThreshold() {
        val threshold = rolling(BandGoal.RewardAbove, target = 0.70)
        for (value in 1..10) {
            threshold.accept(value.toLong(), value.toDouble(), contactAcceptable = true)
        }
        assertEquals(3.7, threshold.threshold(10)!!, 1e-6)
        assertEquals(0.70, threshold.windowSuccess(10)!!, 1e-9)
    }

    @Test
    fun rollingWindowDropsSamplesOlderThanTheWindow() {
        val threshold = rolling(BandGoal.InhibitBelow, target = 0.80, windowMillis = 30_000)
        assertTrue(threshold.accept(0, 4.0, contactAcceptable = true))
        assertTrue(threshold.accept(1_000, 4.0, contactAcceptable = true))
        assertEquals(2, threshold.windowSize(30_000))
        assertEquals(1, threshold.windowSize(30_001))

        assertTrue(threshold.accept(40_000, 12.0, contactAcceptable = true))
        assertEquals(1, threshold.windowSize(40_000))
        assertEquals(12.0, threshold.threshold(40_000)!!, 1e-9)
    }

    @Test
    fun invalidSamplesNeverEnterTheWindow() {
        val threshold = rolling(BandGoal.InhibitBelow, target = 0.80)
        assertFalse(threshold.accept(1, Double.NaN, contactAcceptable = true))
        assertFalse(threshold.accept(2, Double.POSITIVE_INFINITY, contactAcceptable = true))
        assertFalse(threshold.accept(3, Double.NEGATIVE_INFINITY, contactAcceptable = true))
        assertFalse(threshold.accept(4, -5.0, contactAcceptable = true))
        assertFalse(threshold.accept(5, 250.0, contactAcceptable = true))
        assertFalse(threshold.accept(6, 10.0, contactAcceptable = false))
        assertTrue(threshold.accept(7, 10.0, contactAcceptable = true))
        assertTrue(threshold.accept(8, 0.0, contactAcceptable = true))
        assertEquals(2, threshold.acceptedCount)
        assertEquals(6, threshold.rejectedCount)
        assertEquals(2, threshold.windowSize(8))
    }

    @Test
    fun thresholdStaysNullUntilTheMinimumCount() {
        val threshold = rolling(BandGoal.RewardAbove, target = 0.70, minimumCount = 8)
        repeat(7) { index ->
            threshold.accept(index.toLong(), 12.0, contactAcceptable = true)
        }
        assertNull(threshold.threshold(6))
        threshold.accept(7, 12.0, contactAcceptable = true)
        assertEquals(12.0, threshold.threshold(7)!!, 1e-9)
    }

    @Test
    fun autoThresholdAdaptsWhenDistributionShifts() {
        val controller = AutoThresholdController(
            ThresholdConfig(
                windowSeconds = 30.0,
                minimumValidObservations = 8,
                bands = listOf(
                    BandThresholdSpec(
                        bandId = "inhibit1",
                        goal = BandGoal.InhibitBelow,
                        targetSuccess = 0.80,
                        weight = 1.0,
                    ),
                ),
            ),
        )
        repeat(30) { index ->
            controller.observe(
                index * 100L,
                listOf(AmplitudeReading("inhibit1", 8.0)),
                contactAcceptable = true,
            )
        }
        val early = controller.observe(2_900L, emptyList(), contactAcceptable = true).single().thresholdUv
        assertEquals(8.0, early!!, 0.01)

        repeat(30) { index ->
            controller.observe(
                60_000L + index * 100L,
                listOf(AmplitudeReading("inhibit1", 30.0)),
                contactAcceptable = true,
            )
        }
        val later = controller.observe(62_900L, emptyList(), contactAcceptable = true).single().thresholdUv
        assertEquals(30.0, later!!, 0.01)
        assertTrue(later > early + 15.0)
    }

    private fun rolling(
        goal: BandGoal,
        target: Double,
        windowMillis: Long = 60_000,
        minimumCount: Int = 1,
    ) = RollingThreshold(
        windowMillis = windowMillis,
        goal = goal,
        targetFraction = target,
        minimumCount = minimumCount,
        maxMicrovolts = 200.0,
    )
}
