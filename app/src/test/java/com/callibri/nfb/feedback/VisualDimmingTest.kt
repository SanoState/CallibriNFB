package com.callibri.nfb.feedback

import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualDimmingTest {
    @Test
    fun defaultMaximumDarknessFollowsTheLinearMap() {
        assertEquals(0.75, VisualDimming.DEFAULT_MAX_ALPHA, 0.0)
        assertEquals(0.75, VisualDimming.alpha(20.0, 0.75), 1e-9)
        assertEquals(0.75, VisualDimming.alpha(40.0, 0.75), 1e-9)
        assertEquals(0.75, VisualDimming.alpha(60.0, 0.75), 1e-9)
        assertEquals(0.75, VisualDimming.alpha(70.0, 0.75), 1e-9)
        assertEquals(0.5625, VisualDimming.alpha(75.0, 0.75), 1e-9)
        assertEquals(0.375, VisualDimming.alpha(80.0, 0.75), 1e-9)
        assertEquals(0.1875, VisualDimming.alpha(85.0, 0.75), 1e-9)
        assertEquals(0.0, VisualDimming.alpha(90.0, 0.75), 1e-9)
        assertEquals(0.0, VisualDimming.alpha(100.0, 0.75), 1e-9)
    }

    @Test
    fun deviceSafeMaximumStaysUnderTheObscuringLimit() {
        assertEquals(0.78, TouchObscuringLimit.safeMaximum(0.80f), 0.0001)
        assertEquals(0.78, TouchObscuringLimit.safeMaximum(null), 0.0)
        assertEquals(0.78, TouchObscuringLimit.safeMaximum(Float.NaN), 0.0)
        assertEquals(0.80, TouchObscuringLimit.safeMaximum(1.0f), 0.0001)
        assertEquals(0.48, TouchObscuringLimit.safeMaximum(0.50f), 0.0001)
        assertEquals(0.78, VisualDimming.clampSetting(0.90, 0.78), 1e-9)
        assertEquals(0.75, VisualDimming.clampSetting(0.75, 0.78), 1e-9)
        assertEquals(0.10, VisualDimming.clampSetting(0.01, 0.78), 1e-9)
        assertEquals(0.10, VisualDimming.MIN_SETTING, 0.0)
    }

    @Test
    fun rewardOutsideTheRangeClamps() {
        assertEquals(0.50, VisualDimming.alpha(0.0, 0.50), 1e-9)
        assertEquals(0.50, VisualDimming.alpha(20.0, 0.50), 1e-9)
        assertEquals(0.0, VisualDimming.alpha(100.0, 0.50), 1e-9)
        assertEquals(0.0, VisualDimming.alpha(140.0, 0.50), 1e-9)
    }

    @Test
    fun maxDimSettingIsRespectedAndCapped() {
        assertEquals(0.30, VisualDimming.alpha(20.0, 0.30), 1e-9)
        assertEquals(0.30, VisualDimming.alpha(60.0, 0.30), 1e-9)
        assertEquals(0.15, VisualDimming.alpha(80.0, 0.30), 1e-9)
        assertEquals(0.0, VisualDimming.alpha(90.0, 0.30), 1e-9)
        assertEquals(0.0, VisualDimming.alpha(100.0, 0.30), 1e-9)
        assertEquals(0.75, VisualDimming.alpha(20.0, 0.75), 1e-9)
        assertEquals(0.0, VisualDimming.alpha(100.0, 0.75), 1e-9)
        assertEquals(0.80, VisualDimming.alpha(20.0, 1.0), 1e-9)
    }

    @Test
    fun overlayFlagsLetTouchesThrough() {
        assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, DimmingWindowSpec.TYPE)
        assertTrue((DimmingWindowSpec.FLAGS and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0)
        assertTrue((DimmingWindowSpec.FLAGS and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0)
        assertTrue(DimmingWindowSpec.passesTouchesThrough())
    }

    @Test
    fun visualOffStopAndDisconnectAreClear() {
        val dark = FeedbackModes.visualAlpha(
            visualEnabled = true,
            manual = false,
            rewardReady = true,
            rewardPercent = 20.0,
            maxDimAlpha = 0.50,
        )
        assertEquals(0.50, dark, 1e-9)
        assertEquals(
            0.0,
            FeedbackModes.visualAlpha(false, manual = false, rewardReady = true, 20.0, 0.50),
            0.0,
        )
        assertEquals(
            0.0,
            FeedbackModes.visualAlpha(false, manual = true, rewardReady = true, 20.0, 0.50),
            0.0,
        )
    }

    @Test
    fun manualModeUsesTheSameMapping() {
        val manual = FeedbackModes.visualAlpha(
            visualEnabled = true,
            manual = true,
            rewardReady = false,
            rewardPercent = 80.0,
            maxDimAlpha = 0.50,
        )
        val live = FeedbackModes.visualAlpha(
            visualEnabled = true,
            manual = false,
            rewardReady = true,
            rewardPercent = 80.0,
            maxDimAlpha = 0.50,
        )
        assertEquals(0.25, manual, 1e-9)
        assertEquals(live, manual, 1e-9)
    }

    @Test
    fun turningManualOffUsesTheLiveReward() {
        val slider = FeedbackModes.visualAlpha(true, manual = true, rewardReady = true, 20.0, 0.50)
        val live = FeedbackModes.visualAlpha(true, manual = false, rewardReady = true, 100.0, 0.50)
        assertEquals(0.50, slider, 1e-9)
        assertEquals(0.0, live, 1e-9)
    }

    @Test
    fun noRewardYetStaysClear() {
        assertEquals(
            0.0,
            FeedbackModes.visualAlpha(true, manual = false, rewardReady = false, 20.0, 0.50),
            0.0,
        )
    }

    @Test
    fun audioOnlyLeavesTheScreenClear() {
        val mode = ModeProbe(audioEnabled = true, visualEnabled = false, eegStreaming = true, reward = 20.0)
        assertEquals(FeedbackIntensity.audioGain(FeedbackIntensity.intensity(20.0)), mode.audioGain())
        assertEquals(0.0, mode.visualAlpha(), 0.0)
        assertTrue(mode.eegStreaming)
    }

    @Test
    fun visualOnlyDoesNotDriveAudio() {
        val mode = ModeProbe(audioEnabled = false, visualEnabled = true, eegStreaming = true, reward = 20.0)
        assertNull(mode.audioGain())
        assertEquals(0.50, mode.visualAlpha(), 1e-9)
        assertTrue(mode.eegStreaming)
    }

    @Test
    fun combinedModeUsesOneRewardForBoth() {
        val reward = 76.0
        val mode = ModeProbe(audioEnabled = true, visualEnabled = true, eegStreaming = true, reward = reward)
        val intensity = FeedbackIntensity.intensity(reward)
        assertEquals(0.30, intensity, 1e-9)
        assertEquals(FeedbackIntensity.audioGain(intensity), mode.audioGain())
        assertEquals(VisualDimming.alphaForIntensity(intensity, mode.maxDim), mode.visualAlpha(), 1e-9)
        assertTrue(mode.eegStreaming)
    }

    @Test
    fun disablingAudioLeavesVisualRunning() {
        val both = ModeProbe(audioEnabled = true, visualEnabled = true, eegStreaming = true, reward = 20.0)
        val audioOff = both.copy(audioEnabled = false)
        assertNull(audioOff.audioGain())
        assertEquals(both.visualAlpha(), audioOff.visualAlpha(), 0.0)
        assertTrue(audioOff.eegStreaming)
    }

    @Test
    fun disablingVisualLeavesAudioRunning() {
        val both = ModeProbe(audioEnabled = true, visualEnabled = true, eegStreaming = true, reward = 20.0)
        val visualOff = both.copy(visualEnabled = false)
        assertEquals(both.audioGain(), visualOff.audioGain())
        assertEquals(0.0, visualOff.visualAlpha(), 0.0)
        assertTrue(visualOff.eegStreaming)
    }

    @Test
    fun disablingBothLeavesEegWithoutFeedback() {
        val off = ModeProbe(audioEnabled = false, visualEnabled = false, eegStreaming = true, reward = 20.0)
        assertNull(off.audioGain())
        assertEquals(0.0, off.visualAlpha(), 0.0)
        assertTrue(off.eegStreaming)
    }
}

private data class ModeProbe(
    val audioEnabled: Boolean,
    val visualEnabled: Boolean,
    val eegStreaming: Boolean,
    val reward: Double,
    val maxDim: Double = 0.50,
    val rewardReady: Boolean = true,
    val manual: Boolean = false,
    val lowerBound: Double = FeedbackIntensity.DEFAULT_LOWER,
    val upperBound: Double = FeedbackIntensity.DEFAULT_UPPER,
) {
    fun audioGain(): Double? =
        if (FeedbackModes.drivesAudio(audioEnabled)) {
            FeedbackIntensity.audioGain(FeedbackIntensity.intensity(reward, lowerBound, upperBound))
        } else {
            null
        }

    fun visualAlpha(): Double = FeedbackModes.visualAlpha(
        visualEnabled = visualEnabled,
        manual = manual,
        rewardReady = rewardReady,
        rewardPercent = reward,
        maxDimAlpha = maxDim,
        lowerBound = lowerBound,
        upperBound = upperBound,
    )
}
