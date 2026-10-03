package com.callibri.nfb.feedback

import android.content.Context

/** Remembers the darkest overlay the user allowed. The on/off switches are not saved. */
class VisualDimPreference(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun load(): Double = VisualDimming.clampSetting(
        prefs.getFloat(KEY, VisualDimming.DEFAULT_MAX_ALPHA.toFloat()).toDouble(),
    )

    fun save(maxDimAlpha: Double) {
        prefs.edit().putFloat(KEY, VisualDimming.clampSetting(maxDimAlpha).toFloat()).apply()
    }

    private companion object {
        const val NAME = "callibri_nfb_visual"
        const val KEY = "max_dim_alpha"
    }
}
