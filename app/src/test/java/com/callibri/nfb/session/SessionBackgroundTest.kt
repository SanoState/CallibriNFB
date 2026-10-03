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
        assertEquals("67%", model.smoothedLabel)
        assertEquals("Running", model.eegLabel)
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
        assertEquals("—", model.collapsedReward)
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
