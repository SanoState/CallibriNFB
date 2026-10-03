package com.callibri.nfb.session

import com.callibri.nfb.callibri.SessionPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionBackgroundTest {
    @Test
    fun sessionStaysActiveWhenTheUiObserverLeaves() {
        val lifetime = SessionLifetime()
        lifetime.arm()
        lifetime.setStreaming(true)
        lifetime.onUiObserverStarted()
        lifetime.onUiObserverStopped()

        assertEquals(0, lifetime.uiObserverCount())
        assertTrue(lifetime.stillRunningWithoutUi())
        assertTrue(lifetime.streaming.value)
        assertTrue(lifetime.commanded.value)
    }

    @Test
    fun overlayUsesTheExistingSmoothedReward() {
        val model = SessionPolicy.overlayModel(
            connected = true,
            streaming = true,
            smoothedReward = 67.4,
            volumePercent = 67.4,
            elapsedMillis = 12_400L,
            electrode = "Contact good",
            manualOverride = false,
        )

        assertEquals(67.4, model.displayedReward)
        assertEquals("67%", model.collapsedReward)
        assertEquals(0.0, model.barFraction, 1e-9)
        assertEquals(RewardMeter.fraction(67.4), model.barFraction, 1e-9)
        assertEquals("67%", model.smoothedLabel)
        assertEquals("Running", model.eegLabel)
    }

    @Test
    fun rewardMeterFillUsesTheActiveRange() {
        assertEquals(0.0, RewardMeter.fraction(20.0), 1e-9)
        assertEquals(0.0, RewardMeter.fraction(60.0), 1e-9)
        assertEquals(0.0, RewardMeter.fraction(70.0), 1e-9)
        assertEquals(0.25, RewardMeter.fraction(75.0), 1e-9)
        assertEquals(0.50, RewardMeter.fraction(80.0), 1e-9)
        assertEquals(0.75, RewardMeter.fraction(85.0), 1e-9)
        assertEquals(1.0, RewardMeter.fraction(90.0), 1e-9)
        assertEquals(1.0, RewardMeter.fraction(95.0), 1e-9)
    }

    @Test
    fun meterIgnoresAudioAndVisualSwitches() {
        fun model(audio: Boolean, visual: Boolean) = SessionPolicy.overlayModel(
            connected = true,
            streaming = true,
            smoothedReward = 60.0,
            volumePercent = 10.0,
            elapsedMillis = 1_000L,
            electrode = null,
            manualOverride = false,
            audioOn = audio,
            visualOn = visual,
        )
        val audioOnly = model(audio = true, visual = false)
        val visualOnly = model(audio = false, visual = true)
        val both = model(audio = true, visual = true)
        val neither = model(audio = false, visual = false)
        assertEquals(0.0, audioOnly.barFraction, 1e-9)
        assertEquals(audioOnly.barFraction, visualOnly.barFraction, 0.0)
        assertEquals(audioOnly.barFraction, both.barFraction, 0.0)
        assertEquals(audioOnly.barFraction, neither.barFraction, 0.0)
        assertEquals("60%", neither.collapsedReward)
    }

    @Test
    fun rewardMeterStaysAboveTheDimmingLayer() {
        assertFalse(
            SessionPolicy.shouldRaiseRewardMeter(
                dimmingAttached = false,
                dimmingGeneration = 1,
                alreadyRaisedGeneration = -1,
            ),
        )
        assertTrue(
            SessionPolicy.shouldRaiseRewardMeter(
                dimmingAttached = true,
                dimmingGeneration = 1,
                alreadyRaisedGeneration = -1,
            ),
        )
        assertFalse(
            SessionPolicy.shouldRaiseRewardMeter(
                dimmingAttached = true,
                dimmingGeneration = 1,
                alreadyRaisedGeneration = 1,
            ),
        )
    }

    @Test
    fun stopCommandMakesTheServiceRetireOnceStreamingEnds() {
        val lifetime = SessionLifetime()
        lifetime.arm()
        lifetime.setStreaming(true)
        lifetime.disarm()
        lifetime.setStreaming(false)

        assertFalse(lifetime.commanded.value)
        assertFalse(lifetime.stillRunningWithoutUi())
        val (_, retire) = SessionPolicy.onServiceTick(
            watch = ServiceWatch(keepService = lifetime.commanded.value, sawStreaming = true),
            connected = true,
            streaming = false,
            startFailed = false,
        )
        assertTrue(retire)
        assertNull(SessionPolicy.overlayReward(connected = true, streaming = false, smoothedReward = 80.0))
        val stopped = SessionPolicy.overlayModel(
            connected = true,
            streaming = false,
            smoothedReward = 80.0,
            volumePercent = 80.0,
            elapsedMillis = 1_000L,
            electrode = null,
            manualOverride = false,
        )
        assertNull(stopped.displayedReward)
        assertEquals("--%", stopped.collapsedReward)
        assertEquals(0.0, stopped.barFraction, 0.0)
    }

    @Test
    fun disconnectedStateCannotPresentAnActiveReward() {
        assertFalse(SessionPolicy.presentActiveReward(connected = false, streaming = true))
        assertNull(SessionPolicy.overlayReward(connected = false, streaming = true, smoothedReward = 88.0))
        val model = SessionPolicy.overlayModel(
            connected = false,
            streaming = false,
            smoothedReward = 88.0,
            volumePercent = 40.0,
            elapsedMillis = 1_000L,
            electrode = null,
            manualOverride = false,
        )
        assertNull(model.displayedReward)
        assertEquals("--%", model.collapsedReward)
        assertEquals(0.0, model.barFraction, 0.0)
        assertEquals(0.0, RewardMeter.displayedFraction(live = false, smoothedReward = 88.0), 0.0)
        assertEquals("Disconnected", model.callibriLabel)
        val copy = SessionPolicy.notificationCopy(connected = false, streaming = false, smoothedReward = 88.0)
        assertEquals("Callibri disconnected", copy.text)
    }

    @Test
    fun liveStreamKeepsTheServiceUpWithoutAUiObserver() {
        val lifetime = SessionLifetime()
        lifetime.arm()
        lifetime.setStreaming(true)
        lifetime.onUiObserverStarted()
        lifetime.onUiObserverStopped()

        val (_, retire) = SessionPolicy.onServiceTick(
            watch = ServiceWatch(keepService = lifetime.commanded.value, sawStreaming = true),
            connected = true,
            streaming = lifetime.streaming.value,
            startFailed = false,
        )
        assertFalse(retire)
        val copy = SessionPolicy.notificationCopy(connected = true, streaming = true, smoothedReward = 72.2)
        assertEquals("Neurofeedback session active", copy.text)
        assertEquals("Connected • Reward 72%", copy.detail)
    }

    @Test
    fun portalCreatesOneSessionForTheActivityAndTheService() {
        val portal = SessionPortal { Any() }
        val activity = portal.get()
        val service = portal.get()

        assertSame(activity, service)
        assertEquals(1, portal.creations())
    }

    @Test
    fun manualDiagnosticModeDoesNotStartTheForegroundService() {
        assertFalse(SessionPolicy.shouldStartForegroundService(explicitEegStart = false))
        assertTrue(SessionPolicy.shouldStartForegroundService(explicitEegStart = true))
    }

    @Test
    fun failedEegStartIsRecognizedWithoutTreatingOtherErrorsAsFailure() {
        assertTrue(
            SessionPolicy.eegStartFailed(
                phase = SessionPhase.Connected,
                streaming = false,
                message = "Couldn't start EEG: timeout",
                messageIsError = true,
            ),
        )
        assertFalse(
            SessionPolicy.eegStartFailed(
                phase = SessionPhase.Connected,
                streaming = true,
                message = "Couldn't start EEG: timeout",
                messageIsError = true,
            ),
        )
        assertFalse(
            SessionPolicy.eegStartFailed(
                phase = SessionPhase.Connected,
                streaming = false,
                message = "ADC input did not stick",
                messageIsError = true,
            ),
        )
    }
}
