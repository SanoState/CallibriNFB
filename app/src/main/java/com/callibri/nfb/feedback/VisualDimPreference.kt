package com.callibri.nfb.feedback

import android.content.Context

/**
 * Remembers the darkest overlay the user allowed, and the feedback range.
 * The on/off switches are not saved. An invalid stored range falls back to 70–90.
 */
class VisualDimPreference(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun load(safeMaximum: Double): Double = VisualDimming.clampSetting(
        prefs.getFloat(KEY, VisualDimming.DEFAULT_MAX_ALPHA.toFloat()).toDouble(),
        safeMaximum,
    )

    fun save(maxDimAlpha: Double, safeMaximum: Double) {
        prefs.edit().putFloat(KEY, VisualDimming.clampSetting(maxDimAlpha, safeMaximum).toFloat()).apply()
    }

    fun loadRange(): Pair<Double, Double> {
        val lower = prefs.getFloat(KEY_LOWER, FeedbackIntensity.DEFAULT_LOWER.toFloat()).toDouble()
        val upper = prefs.getFloat(KEY_UPPER, FeedbackIntensity.DEFAULT_UPPER.toFloat()).toDouble()
        return if (FeedbackIntensity.isValid(lower, upper)) {
            lower to upper
        } else {
            FeedbackIntensity.DEFAULT_LOWER to FeedbackIntensity.DEFAULT_UPPER
        }
    }

    fun saveRange(lowerBound: Double, upperBound: Double) {
        if (!FeedbackIntensity.isValid(lowerBound, upperBound)) return
        prefs.edit()
            .putFloat(KEY_LOWER, lowerBound.toFloat())
            .putFloat(KEY_UPPER, upperBound.toFloat())
            .apply()
    }

    private companion object {
        const val NAME = "callibri_nfb_visual"
        const val KEY = "max_dim_alpha"
        const val KEY_LOWER = "feedback_lower_bound"
        const val KEY_UPPER = "feedback_upper_bound"
    }
}
