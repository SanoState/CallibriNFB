package com.callibri.nfb.callibri

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Runtime permissions NeuroSDK needs for a BLE scan.
 *
 * API 31+: BLUETOOTH_SCAN and BLUETOOTH_CONNECT. The manifest marks scan as
 * neverForLocation, matching the AAR, so location is not requested.
 * API 26–30: ACCESS_FINE_LOCATION and ACCESS_COARSE_LOCATION. BLUETOOTH and
 * BLUETOOTH_ADMIN are install-time on those releases and are only declared.
 */
object CallibriPermissions {
    fun runtimePermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        }

    fun hasRuntimePermissions(context: Context): Boolean =
        runtimePermissions().all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
}
