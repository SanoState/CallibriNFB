package com.callibri.nfb.session

import com.callibri.nfb.callibri.SessionPhase
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide gate so the Activity and the foreground service share one session object.
 * The factory runs once.
 */
class SessionPortal<T>(private val factory: () -> T) {
    @Volatile
    private var instance: T? = null
    private var creations = 0

    fun get(): T {
        val existing = instance
        if (existing != null) return existing
        return synchronized(this) {
            val again = instance
            if (again != null) {
                again
            } else {
                factory().also {
                    instance = it
                    creations += 1
                }
            }
        }
    }

    fun creations(): Int = synchronized(this) { creations }
}

/**
 * EEG keeps running when the screen's collector goes away.
 * [commanded] is the user's Start EEG request. [streaming] follows the sensor.
 */
class SessionLifetime {
    private val _commanded = MutableStateFlow(false)
    val commanded: StateFlow<Boolean> = _commanded.asStateFlow()

    private val _streaming = MutableStateFlow(false)
    val streaming: StateFlow<Boolean> = _streaming.asStateFlow()

    private var uiObservers = 0

    fun arm() {
        _commanded.value = true
    }

    fun disarm() {
        _commanded.value = false
    }

    fun setStreaming(active: Boolean) {
        _streaming.value = active
    }

    fun onUiObserverStarted() {
        uiObservers += 1
    }

    fun onUiObserverStopped() {
        uiObservers = (uiObservers - 1).coerceAtLeast(0)
    }

    fun uiObserverCount(): Int = uiObservers

    /** Detaching the Activity does not clear the stream or the start request. */
    fun stillRunningWithoutUi(): Boolean = _commanded.value && _streaming.value
}

data class ServiceWatch(
    val keepService: Boolean,
    val sawStreaming: Boolean,
)

data class NotificationCopy(
    val title: String,
    val text: String,
    val detail: String,
)

data class OverlayModel(
    val collapsedReward: String,
    val callibriLabel: String,
    val eegLabel: String,
    val smoothedLabel: String,
    val volumeLabel: String,
    val elapsedLabel: String,
    val electrodeLabel: String?,
    val manualOverride: Boolean,
    val dotArgb: Int,
    /** Null when the session must not present a live reward. */
    val displayedReward: Double?,
    val mediaOn: Boolean = false,
    val mediaLevelLabel: String = "—",
    val audioOn: Boolean = false,
    val visualOn: Boolean = false,
    val visualDimLabel: String = "—",
)

object SessionPolicy {
    /** Foreground service starts only from an explicit EEG start, never from the manual slider. */
    fun shouldStartForegroundService(explicitEegStart: Boolean): Boolean = explicitEegStart

    fun presentActiveReward(connected: Boolean, streaming: Boolean): Boolean = connected && streaming

    /** The overlay prints the existing smoothed reward, and only while feedback is live. */
    fun overlayReward(connected: Boolean, streaming: Boolean, smoothedReward: Double): Double? =
        if (presentActiveReward(connected, streaming)) smoothedReward else null

    fun eegStartFailed(
        phase: SessionPhase,
        streaming: Boolean,
        message: String?,
        messageIsError: Boolean,
    ): Boolean =
        phase == SessionPhase.Connected &&
            !streaming &&
            messageIsError &&
            message?.startsWith("Couldn't start EEG") == true

    /**
     * Stay up while samples are flowing. Leave once the user stops, the sensor drops,
     * or a start attempt fails after the service has already seen a newer state.
     */
    fun onServiceTick(
        watch: ServiceWatch,
        connected: Boolean,
        streaming: Boolean,
        startFailed: Boolean,
    ): Pair<ServiceWatch, Boolean> {
        val next = watch.copy(sawStreaming = watch.sawStreaming || streaming)
        val retire = when {
            streaming -> false
            !watch.keepService -> true
            startFailed -> true
            !connected -> true
            next.sawStreaming -> true
            else -> false
        }
        return next to retire
    }

    fun notificationCopy(connected: Boolean, streaming: Boolean, smoothedReward: Double): NotificationCopy {
        if (!connected) {
            return NotificationCopy(
                title = "Callibri NFB",
                text = "Callibri disconnected",
                detail = "Disconnected",
            )
        }
        if (!streaming) {
            return NotificationCopy(
                title = "Callibri NFB",
                text = "Starting neurofeedback…",
                detail = "Waiting for EEG",
            )
        }
        val percent = smoothedReward.roundToInt().coerceIn(0, 100)
        return NotificationCopy(
            title = "Callibri NFB",
            text = "Neurofeedback session active",
            detail = "Connected • Reward $percent%",
        )
    }

    fun overlayModel(
        connected: Boolean,
        streaming: Boolean,
        smoothedReward: Double,
        volumePercent: Double,
        elapsedMillis: Long,
        electrode: String?,
        manualOverride: Boolean,
        mediaOn: Boolean = false,
        mediaLevelPercent: Int? = null,
        audioOn: Boolean = false,
        visualOn: Boolean = false,
        visualDimPercent: Int? = null,
    ): OverlayModel {
        val shown = overlayReward(connected, streaming, smoothedReward)
        val collapsed = if (shown == null) "—" else "${shown.roundToInt().coerceIn(0, 100)}%"
        return OverlayModel(
            collapsedReward = collapsed,
            callibriLabel = if (connected) "Connected" else "Disconnected",
            eegLabel = if (streaming) "Running" else "Stopped",
            smoothedLabel = if (shown == null) "—" else String.format(Locale.US, "%.0f%%", shown),
            volumeLabel = String.format(Locale.US, "%.0f%%", volumePercent.coerceIn(0.0, 100.0)),
            elapsedLabel = String.format(Locale.US, "%.1f s", elapsedMillis.coerceAtLeast(0L) / 1000.0),
            electrodeLabel = electrode,
            manualOverride = manualOverride,
            mediaOn = mediaOn,
            mediaLevelLabel = mediaLevelPercent?.let { "$it%" } ?: "—",
            audioOn = audioOn,
            visualOn = visualOn,
            visualDimLabel = visualDimPercent?.let { "$it%" } ?: "—",
            dotArgb = when {
                connected && streaming -> 0xFF3DDC97.toInt()
                connected -> 0xFFE0B15A.toInt()
                else -> 0xFFE06A6A.toInt()
            },
            displayedReward = shown,
        )
    }
}
