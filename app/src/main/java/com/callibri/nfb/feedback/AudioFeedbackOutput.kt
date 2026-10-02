package com.callibri.nfb.feedback

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin

/**
 * Gapless loop of a soft two-tone drone. Playback uses this track's own gain.
 * It does not call AudioManager and does not change the phone's media volume.
 * Pitch, tempo, and the waveform stay fixed; only [applyGain] moves.
 */
class AudioFeedbackOutput : FeedbackOutput {
    private val lock = Any()
    private var track: AudioTrack? = null
    private var playingFlag = false
    private var gain = 0.0
    private var error: String? = null

    override val isPlaying: Boolean
        get() = synchronized(lock) { playingFlag }

    override val appliedGain: Double
        get() = synchronized(lock) { gain }

    override val failure: String?
        get() = synchronized(lock) { error }

    override fun start() {
        synchronized(lock) {
            val player = track ?: buildTrack().also { track = it }
            if (player == null) {
                playingFlag = false
                return
            }
            try {
                if (player.playState != AudioTrack.PLAYSTATE_PLAYING) player.play()
                playingFlag = player.playState == AudioTrack.PLAYSTATE_PLAYING
                if (!playingFlag) error = "Test audio did not start."
            } catch (thrown: Exception) {
                playingFlag = false
                error = thrown.message ?: "Test audio did not start."
            }
        }
    }

    override fun stop() {
        synchronized(lock) {
            playingFlag = false
            gain = 0.0
            val player = track ?: return
            try {
                player.pause()
                player.stop()
            } catch (_: Exception) {
                // Already stopped, or the track was released.
            }
        }
    }

    override fun applyGain(linearGain: Double) {
        synchronized(lock) {
            val player = track
            if (!playingFlag || player == null) return
            val next = linearGain.coerceIn(0.0, 1.0)
            try {
                player.setVolume(next.toFloat())
                gain = next
                error = null
            } catch (thrown: Exception) {
                error = thrown.message ?: "Could not set test-audio volume."
            }
        }
    }

    override fun release() {
        val player = synchronized(lock) {
            playingFlag = false
            gain = 0.0
            track.also { track = null }
        }
        if (player == null) return
        try {
            player.pause()
            player.stop()
        } catch (_: Exception) {
            // Ignore a track that is already stopped.
        }
        try {
            player.release()
        } catch (_: Exception) {
            // Ignore a double release on teardown.
        }
    }

    private fun buildTrack(): AudioTrack? {
        return try {
            val pcm = TestTone.pcm()
            val player = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(TestTone.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
            val written = player.write(pcm, 0, pcm.size)
            if (written != pcm.size) {
                player.release()
                error = "Test audio buffer was not loaded."
                return null
            }
            player.setLoopPoints(0, pcm.size, -1)
            player.setVolume(0f)
            error = null
            player
        } catch (thrown: Exception) {
            error = thrown.message ?: "Test audio could not be created."
            null
        }
    }
}

internal object TestTone {
    const val SAMPLE_RATE = 44_100

    /** One second. 196 Hz and 294 Hz both complete an integer number of cycles, so the loop has no click. */
    fun pcm(): ShortArray {
        val frames = SAMPLE_RATE
        val pcm = ShortArray(frames)
        val scale = Short.MAX_VALUE.toDouble()
        for (index in 0 until frames) {
            val time = index.toDouble() / SAMPLE_RATE
            val sample = 0.20 * sin(TWO_PI * 196.0 * time) + 0.08 * sin(TWO_PI * 294.0 * time)
            pcm[index] = (sample * scale).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return pcm
    }

    private const val TWO_PI = 2.0 * PI
}
