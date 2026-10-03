package com.callibri.nfb.feedback

import kotlin.math.roundToInt

object ExternalEngagement {
    /** External media starts from the live EEG session or the manual slider. The test tone is not required. */
    fun shouldStart(
        externalSelected: Boolean,
        volumeFixed: Boolean,
        hasCapturedMax: Boolean,
        alreadyControlling: Boolean,
        manual: Boolean,
        eegStreaming: Boolean,
    ): Boolean = externalSelected &&
        !volumeFixed &&
        hasCapturedMax &&
        !alreadyControlling &&
        (manual || eegStreaming)
}

/**
 * Maps the existing smoothed reward onto a discrete STREAM_MUSIC index.
 *
 * 100% reward is the user's captured ceiling, not the phone's absolute maximum.
 * 20% reward is about 20% of that ceiling, and never step 0 when the ceiling is above 0.
 *
 *     normalized = clamp((reward - 20) / 80, 0, 1)
 *     index = round(minimumIndex + normalized * (maxIndex - minimumIndex))
 */
object MediaVolumeMapping {
    const val MIN_WRITE_INTERVAL_MS = 100L

    fun minimumIndex(maxIndex: Int): Int {
        if (maxIndex <= 0) return 0
        return (maxIndex * VolumeMapping.FLOOR_PERCENT / 100.0).roundToInt().coerceIn(1, maxIndex)
    }

    /**
     * Legacy 20–100 percent map. Kept so existing index assertions stay put.
     * Live feedback does not call this with the raw reward. It converts
     * [FeedbackIntensity.audioGain] back into a percent, which lands on the
     * same index as [desiredIndexForIntensity].
     */
    fun desiredIndex(rewardPercent: Double, maxIndex: Int): Int {
        if (maxIndex <= 0) return 0
        val normalized = (VolumeMapping.clampedPercent(rewardPercent) - VolumeMapping.FLOOR_PERCENT) /
            (VolumeMapping.CEILING_PERCENT - VolumeMapping.FLOOR_PERCENT)
        return desiredIndexForIntensity(normalized, maxIndex)
    }

    /** Index for a feedback intensity. 0 is [minimumIndex], 1 is [maxIndex]. */
    fun desiredIndexForIntensity(intensity: Double, maxIndex: Int): Int {
        if (maxIndex <= 0) return 0
        val minimum = minimumIndex(maxIndex)
        val unit = if (intensity.isFinite()) intensity.coerceIn(0.0, 1.0) else 0.0
        val raw = minimum + unit * (maxIndex - minimum)
        return raw.roundToInt().coerceIn(minimum, maxIndex)
    }

    fun stepCount(maxIndex: Int): Int {
        if (maxIndex <= 0) return 0
        return maxIndex - minimumIndex(maxIndex) + 1
    }

    fun percentOf(index: Int, span: Int): Int {
        if (span <= 0) return 0
        return ((index.coerceAtLeast(0) * 100.0) / span).roundToInt().coerceIn(0, 100)
    }
}

enum class MediaRestore {
    NA,
    NO,
    YES,
}

data class MediaVolumeStatus(
    val fixed: Boolean,
    val currentIndex: Int,
    val deviceMaxIndex: Int,
    val capturedMaxIndex: Int?,
    val minimumIndex: Int?,
    val desiredIndex: Int?,
    val lastAppliedIndex: Int?,
    val stepCount: Int?,
    val controlling: Boolean,
    val restore: MediaRestore,
    val currentPercent: Int,
    val levelPercent: Int?,
)

/**
 * Owns STREAM_MUSIC while external feedback is engaged.
 * Writes only when the calculated step differs from the stream, and at most 10 times a second.
 */
class MediaVolumeGate(
    private val port: MediaStreamPort,
) {
    private val lock = Any()
    var capturedMaxIndex: Int? = null
        private set
    private var controlling = false
    private var preFeedbackIndex: Int? = null
    private var lastAppliedIndex: Int? = null
    private var lastWriteMs = Long.MIN_VALUE
    private var pendingIndex: Int? = null
    private var restore = MediaRestore.NA

    val isControlling: Boolean get() = synchronized(lock) { controlling }
    val isFixed: Boolean get() = port.isFixed

    fun captureCurrentAsMaximum(): Int? = synchronized(lock) {
        if (port.isFixed) return null
        val deviceMax = port.maxIndex().coerceAtLeast(0)
        val captured = port.currentIndex().coerceIn(0, deviceMax)
        capturedMaxIndex = captured
        captured
    }

    /** Records the current index and takes ownership. Does not write a new volume. */
    fun start(): String? = synchronized(lock) {
        if (port.isFixed) return "External media feedback unavailable on this device."
        if (capturedMaxIndex == null) return "Set the current volume as the maximum first."
        if (controlling) return null
        preFeedbackIndex = port.currentIndex().coerceIn(0, port.maxIndex().coerceAtLeast(0))
        controlling = true
        lastAppliedIndex = null
        restore = MediaRestore.NO
        null
    }

    /**
     * Restores the index from when control began. A second call does nothing.
     */
    fun stop() = synchronized(lock) {
        if (!controlling) return
        val restoreTo = preFeedbackIndex
        controlling = false
        lastAppliedIndex = null
        pendingIndex = null
        preFeedbackIndex = null
        if (restoreTo != null && !port.isFixed && port.currentIndex() != restoreTo) {
            port.setIndex(restoreTo)
        }
        restore = MediaRestore.YES
    }

    /** @return true when the write was held back and [flush] should run later. */
    fun apply(rewardPercent: Double, manual: Boolean, rewardReady: Boolean, nowMs: Long): Boolean = synchronized(lock) {
        if (!controlling || port.isFixed) return false
        val maxIndex = capturedMaxIndex ?: return false
        if (!manual && !rewardReady) return false
        val desired = MediaVolumeMapping.desiredIndex(rewardPercent, maxIndex)
        if (desired > maxIndex) return false
        val current = port.currentIndex()
        if (desired == lastAppliedIndex && current == desired) {
            pendingIndex = null
            return false
        }
        val limited = !manual && lastAppliedIndex != null && nowMs - lastWriteMs < MediaVolumeMapping.MIN_WRITE_INTERVAL_MS
        if (limited) {
            pendingIndex = desired
            return true
        }
        write(desired, current, nowMs)
        false
    }

    fun flush(nowMs: Long): Boolean = synchronized(lock) {
        val desired = pendingIndex ?: return false
        if (!controlling || port.isFixed) return false
        if (nowMs - lastWriteMs < MediaVolumeMapping.MIN_WRITE_INTERVAL_MS) return true
        write(desired, port.currentIndex(), nowMs)
        false
    }

    private fun write(desired: Int, current: Int, nowMs: Long) {
        pendingIndex = null
        if (current == desired) {
            lastAppliedIndex = desired
            return
        }
        port.setIndex(desired)
        lastAppliedIndex = desired
        lastWriteMs = nowMs
    }

    fun status(
        commandPercent: Double,
        lowerBound: Double = FeedbackIntensity.DEFAULT_LOWER,
        upperBound: Double = FeedbackIntensity.DEFAULT_UPPER,
    ): MediaVolumeStatus = synchronized(lock) {
        val deviceMax = if (port.isFixed) port.maxIndex().coerceAtLeast(0) else port.maxIndex().coerceAtLeast(0)
        val current = port.currentIndex().coerceAtLeast(0)
        val captured = capturedMaxIndex
        val minimum = captured?.let { MediaVolumeMapping.minimumIndex(it) }
        val intensity = FeedbackIntensity.intensity(commandPercent, lowerBound, upperBound)
        val desired = captured?.let { MediaVolumeMapping.desiredIndexForIntensity(intensity, it) }
        val steps = captured?.let { MediaVolumeMapping.stepCount(it) }
        val levelSpan = captured ?: deviceMax
        MediaVolumeStatus(
            fixed = port.isFixed,
            currentIndex = current,
            deviceMaxIndex = deviceMax,
            capturedMaxIndex = captured,
            minimumIndex = minimum,
            desiredIndex = desired,
            lastAppliedIndex = lastAppliedIndex,
            stepCount = steps,
            controlling = controlling,
            restore = restore,
            currentPercent = MediaVolumeMapping.percentOf(current, deviceMax),
            levelPercent = if (levelSpan > 0) MediaVolumeMapping.percentOf(current, levelSpan) else null,
        )
    }
}

interface MediaStreamPort {
    val isFixed: Boolean
    fun maxIndex(): Int
    fun currentIndex(): Int
    fun setIndex(index: Int)
}
