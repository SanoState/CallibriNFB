package com.callibri.nfb.feedback

import android.content.Context
import android.hardware.input.InputManager
import android.os.Build
import android.view.WindowManager
import kotlin.math.min

/**
 * Maps the existing smoothed reward onto a black-overlay alpha.
 * This is not a second reward smoother. Android display brightness is not involved.
 *
 * normalized = clamp((reward - 20) / 80, 0, 1)
 * alpha = selectedMaxDimAlpha * (1 - normalized)
 */
object VisualDimming {
    const val DEFAULT_MAX_ALPHA = 0.75
    const val MIN_SETTING = 0.10

    fun normalized(rewardPercent: Double): Double {
        val clamped = VolumeMapping.clampedPercent(rewardPercent)
        val span = VolumeMapping.CEILING_PERCENT - VolumeMapping.FLOOR_PERCENT
        return ((clamped - VolumeMapping.FLOOR_PERCENT) / span).coerceIn(0.0, 1.0)
    }

    /** Keeps the saved or slider value inside 10% and the device-safe ceiling. */
    fun clampSetting(maxDimAlpha: Double, safeMaximum: Double): Double {
        val ceiling = safeMaximum.coerceIn(MIN_SETTING, TouchObscuringLimit.REQUESTED_CAP)
        return maxDimAlpha.coerceIn(MIN_SETTING, ceiling)
    }

    fun alpha(rewardPercent: Double, selectedMaxDimAlpha: Double): Double {
        val maxDim = selectedMaxDimAlpha.coerceIn(0.0, TouchObscuringLimit.REQUESTED_CAP)
        return (maxDim * (1.0 - normalized(rewardPercent))).coerceIn(0.0, maxDim)
    }
}

/**
 * How dark the overlay is allowed to get without blocking touches to apps underneath.
 *
 * On API 31+ the ceiling is [InputManager.getMaximumObscuringOpacityForTouch] minus a
 * small margin, and never above 0.80. Older releases, or a missing reading, use 0.78.
 */
object TouchObscuringLimit {
    const val REQUESTED_CAP = 0.80
    const val MARGIN = 0.02
    const val FALLBACK_SAFE_MAX = 0.78

    fun safeMaximum(systemMaximumObscuringOpacity: Float?): Double {
        val system = systemMaximumObscuringOpacity?.toDouble()
        if (system == null || !system.isFinite() || system <= MARGIN) return FALLBACK_SAFE_MAX
        val safe = min(REQUESTED_CAP, system - MARGIN)
        if (!safe.isFinite() || safe < VisualDimming.MIN_SETTING) return FALLBACK_SAFE_MAX
        return safe
    }

    /** Null when this Android version has no obscuring-opacity API, or the read fails. */
    fun systemMaximum(context: Context): Float? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return try {
            val manager = context.getSystemService(InputManager::class.java) ?: return null
            val reported = manager.getMaximumObscuringOpacityForTouch()
            if (reported.isFinite() && reported > 0f) reported else null
        } catch (_: RuntimeException) {
            null
        }
    }
}

/** The dim layer's window type and flags. Touches and focus pass through. */
object DimmingWindowSpec {
    const val TYPE = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    const val FLAGS =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

    fun passesTouchesThrough(): Boolean {
        val notTouchable = (FLAGS and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0
        val notFocusable = (FLAGS and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0
        return TYPE == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY && notTouchable && notFocusable
    }
}

/**
 * Audio and visual are separate switches. Both read the same reward percent
 * when they are on. Neither switch changes EEG.
 */
object FeedbackModes {
    fun drivesAudio(audioEnabled: Boolean): Boolean = audioEnabled

    fun visualAlpha(
        visualEnabled: Boolean,
        manual: Boolean,
        rewardReady: Boolean,
        rewardPercent: Double,
        maxDimAlpha: Double,
    ): Double {
        if (!visualEnabled) return 0.0
        if (!manual && !rewardReady) return 0.0
        return VisualDimming.alpha(rewardPercent, maxDimAlpha)
    }
}
