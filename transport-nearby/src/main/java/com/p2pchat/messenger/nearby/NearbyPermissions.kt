package com.p2pchat.messenger.nearby

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Runtime permissions Google Nearby Connections needs, per API level:
 *  - API 33+: NEARBY_WIFI_DEVICES (neverForLocation) + Bluetooth runtime
 *    permissions; location is NOT required on 33+.
 *  - API 31–32: BLUETOOTH_SCAN / ADVERTISE / CONNECT + ACCESS_FINE_LOCATION
 *    (Nearby discovery on those versions is location-gated).
 *  - API <= 30: ACCESS_FINE_LOCATION only (Bluetooth permissions are
 *    install-time there).
 * ACCESS_WIFI_STATE / CHANGE_WIFI_STATE are normal permissions and are
 * never requested at runtime.
 */
object NearbyPermissions {

    fun requiredPermissions(): Array<String> {
        val perms = mutableListOf<String>()
        when {
            Build.VERSION.SDK_INT >= 33 -> {
                perms += Manifest.permission.NEARBY_WIFI_DEVICES
                perms += Manifest.permission.BLUETOOTH_SCAN
                perms += Manifest.permission.BLUETOOTH_ADVERTISE
                perms += Manifest.permission.BLUETOOTH_CONNECT
            }
            Build.VERSION.SDK_INT >= 31 -> {
                perms += Manifest.permission.BLUETOOTH_SCAN
                perms += Manifest.permission.BLUETOOTH_ADVERTISE
                perms += Manifest.permission.BLUETOOTH_CONNECT
                perms += Manifest.permission.ACCESS_FINE_LOCATION
            }
            else -> {
                perms += Manifest.permission.ACCESS_FINE_LOCATION
            }
        }
        return perms.toTypedArray()
    }

    fun missingPermissions(context: Context): List<String> =
        requiredPermissions().filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }

    fun hasAll(context: Context): Boolean = missingPermissions(context).isEmpty()
}
