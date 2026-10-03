package com.callibri.nfb.feedback

import com.callibri.nfb.session.RewardMeter
import com.callibri.nfb.session.SessionPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class FeedbackIntensityTest {
    @Test
    fun defaultRangeMapsTheTableIncludingOutsideTheSpan() {
        assertEquals(0.0, FeedbackIntensity.intensity(60.0), 1e-9)
        assertEquals(0.0, FeedbackIntensity.intensity(70.0), 1e-9)
        assertEquals(0.25, FeedbackIntensity.intensity(75.0), 1e-9)
        assertEquals(0.50, FeedbackIntensity.intensity(80.0), 1e-9)
        assertEquals(0.75, FeedbackIntensity.intensity(85.0), 1e-9)
        assertEquals(1.0, FeedbackIntensity.intensity(90.0), 1e-9)
        assertEquals(1.0, FeedbackIntensity.intensity(95.0), 1e-9)
    }

    @Test
    fun customRangeOf60To100() {
        assertEquals(0.0, FeedbackIntensity.intensity(60.0, 60.0, 100.0), 1e-9)
        assertEquals(0.5, FeedbackIntensity.intensity(80.0, 60.0, 100.0), 1e-9)
        assertEquals(1.0, FeedbackIntensity.intensity(100.0, 60.0, 100.0), 1e-9)
    }

    @Test
    fun invalidRangeDoesNotThrowAndIsNotUsable() {
        var lower = 70.0
        var upper = 90.0
        fun apply(nextLower: Double, nextUpper: Double) {
            if (FeedbackIntensity.isValid(nextLower, nextUpper)) {
                lower = nextLower
                upper = nextUpper
            }
        }
        apply(90.0, 70.0)
        apply(80.0, 80.0)
        apply(Double.NaN, 90.0)
        apply(70.0, Double.NaN)
        apply(Double.POSITIVE_INFINITY, 90.0)
        assertEquals(70.0, lower, 0.0)
        assertEquals(90.0, upper, 0.0)
        assertEquals(0.0, FeedbackIntensity.intensity(80.0, 90.0, 70.0), 0.0)
        assertEquals(0.0, FeedbackIntensity.intensity(80.0, 80.0, 80.0), 0.0)
        assertEquals(0.0, FeedbackIntensity.intensity(80.0, Double.NaN, 90.0), 0.0)
        assertEquals(0.0, FeedbackIntensity.intensity(Double.NaN, 70.0, 90.0), 0.0)
        assertEquals(0.20, FeedbackIntensity.audioGain(Double.NaN), 1e-9)
        assertEquals(0.50, VisualDimming.alpha(80.0, 0.50, 90.0, 10.0), 1e-9)
        assertEquals(0.0, RewardMeter.fraction(80.0, 5.0, 5.0), 0.0)
    }

    @Test
    fun audioVisualAndBarShareOneIntensity() {
        val reward = 76.0
        val lower = 70.0
        val upper = 90.0
        val intensity = FeedbackIntensity.intensity(reward, lower, upper)
        assertEquals(0.30, intensity, 1e-9)
        assertEquals(0.44, FeedbackIntensity.audioGain(intensity), 1e-9)
        assertEquals(0.75 * (1.0 - intensity), VisualDimming.alpha(reward, 0.75, lower, upper), 1e-9)
        assertEquals(intensity, RewardMeter.fraction(reward, lower, upper), 1e-9)
        assertEquals(
            VisualDimming.alphaForIntensity(intensity, 0.75),
            VisualDimming.alpha(reward, 0.75, lower, upper),
            1e-9,
        )
    }

    @Test
    fun displayedRewardStaysThePercentNotTheIntensity() {
        val mid = SessionPolicy.overlayModel(
            connected = true,
            streaming = true,
            smoothedReward = 80.0,
            volumePercent = 80.0,
            elapsedMillis = 1_000L,
            electrode = null,
            manualOverride = false,
        )
        assertEquals(80.0, mid.displayedReward)
        assertEquals("80%", mid.collapsedReward)
        assertEquals(0.5, mid.barFraction, 1e-9)

        val above = SessionPolicy.overlayModel(
            connected = true,
            streaming = true,
            smoothedReward = 94.0,
            volumePercent = 94.0,
            elapsedMillis = 1_000L,
            electrode = null,
            manualOverride = false,
        )
        assertEquals("94%", above.collapsedReward)
        assertEquals(1.0, above.barFraction, 1e-9)

        val manual = SessionPolicy.overlayModel(
            connected = true,
            streaming = true,
            smoothedReward = 55.0,
            volumePercent = 55.0,
            elapsedMillis = 1_000L,
            electrode = null,
            manualOverride = true,
        )
        assertEquals("55%", manual.collapsedReward)
        assertEquals(0.0, manual.barFraction, 1e-9)
    }

    @Test
    fun audioGainFloorAndCeiling() {
        assertEquals(0.20, FeedbackIntensity.audioGain(FeedbackIntensity.intensity(70.0)), 1e-9)
        assertEquals(0.60, FeedbackIntensity.audioGain(FeedbackIntensity.intensity(80.0)), 1e-9)
        assertEquals(1.0, FeedbackIntensity.audioGain(FeedbackIntensity.intensity(90.0)), 1e-9)
    }
}
