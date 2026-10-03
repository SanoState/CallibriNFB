package com.callibri.nfb.feedback

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * STREAM_MUSIC feedback. Full feedback intensity is the captured user ceiling.
 * [applyGain] receives [FeedbackIntensity.audioGain], so
 * `clamp(gain * 100)` lands on the existing 20–100 index map.
 * [flags] are always 0, so Android does not show the volume panel or play a click.
 */
class SystemMediaVolumeFeedbackOutput(
    private val gate: MediaVolumeGate,
) : FeedbackOutput {
    private var playingFlag = false
    private var gain = 0.0
    private var error: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private var flushPosted = false
    private var manual = false
    private var rewardReady = false

    fun prepare(manual: Boolean, rewardReady: Boolean) {
        this.manual = manual
        this.rewardReady = rewardReady
    }

    override val isPlaying: Boolean
        get() = playingFlag

    override val appliedGain: Double
        get() = gain

    override val failure: String?
        get() = error

    override fun start() {
        error = gate.start()
        playingFlag = error == null && gate.isControlling
        if (!playingFlag) gain = 0.0
    }

    override fun stop() {
        handler.removeCallbacksAndMessages(null)
        flushPosted = false
        gate.stop()
        playingFlag = false
        gain = 0.0
    }

    override fun applyGain(linearGain: Double) {
        if (!playingFlag) return
        val percent = VolumeMapping.clampedPercent(linearGain * 100.0)
        val deferred = gate.apply(
            rewardPercent = percent,
            manual = manual,
            rewardReady = rewardReady,
            nowMs = SystemClock.elapsedRealtime(),
        )
        gain = linearGain
        error = null
        if (deferred) scheduleFlush()
    }

    private fun scheduleFlush() {
        if (flushPosted) return
        flushPosted = true
        handler.postDelayed({
            flushPosted = false
            if (!playingFlag) return@postDelayed
            val again = gate.flush(SystemClock.elapsedRealtime())
            if (again) scheduleFlush()
        }, MediaVolumeMapping.MIN_WRITE_INTERVAL_MS)
    }
}

class AndroidMediaStream(context: Context) : MediaStreamPort {
    private val audio = context.applicationContext.getSystemService(AudioManager::class.java)

    override val isFixed: Boolean
        get() = try {
            audio?.isVolumeFixed == true
        } catch (_: Exception) {
            true
        }

    override fun maxIndex(): Int = try {
        audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0
    } catch (_: Exception) {
        0
    }

    override fun currentIndex(): Int = try {
        audio?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
    } catch (_: Exception) {
        0
    }

    override fun setIndex(index: Int) {
        val manager = audio ?: return
        if (isFixed) return
        val ceiling = maxIndex()
        manager.setStreamVolume(AudioManager.STREAM_MUSIC, index.coerceIn(0, ceiling), 0)
    }
}

enum class FeedbackDestination {
    BuiltIn,
    ExternalMedia,
}

/**
 * One [FeedbackController] talks to this. Only the selected output receives start, stop, and gain.
 */
class SelectingFeedbackOutput(
    private val builtIn: AudioFeedbackOutput,
    private val media: SystemMediaVolumeFeedbackOutput,
) : FeedbackOutput {
    @Volatile
    var destination: FeedbackDestination = FeedbackDestination.BuiltIn
        private set

    fun select(next: FeedbackDestination) {
        destination = next
    }

    private fun active(): FeedbackOutput =
        if (destination == FeedbackDestination.BuiltIn) builtIn else media

    override val isPlaying: Boolean
        get() = active().isPlaying

    override val appliedGain: Double
        get() = active().appliedGain

    override val failure: String?
        get() = active().failure

    override fun start() = active().start()

    override fun stop() = active().stop()

    override fun applyGain(linearGain: Double) = active().applyGain(linearGain)

    override fun release() {
        builtIn.release()
        media.stop()
    }
}
