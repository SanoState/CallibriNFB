package com.callibri.nfb.feedback

import android.content.Context

/** Remembers which training bands participate in the combined reward. Missing keys stay enabled. */
class BandEnablePreference(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun isEnabled(bandId: String): Boolean = prefs.getBoolean(key(bandId), true)

    fun save(bandId: String, enabled: Boolean) {
        prefs.edit().putBoolean(key(bandId), enabled).apply()
    }

    private fun key(bandId: String) = "enabled_$bandId"

    private companion object {
        const val NAME = "callibri_nfb_bands"
    }
}
