package com.p2pchat.messenger.ble

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Runtime Bluetooth permission model.
 *
 *  - API 31+ (Android 12+): [Manifest.permission.BLUETOOTH_SCAN],
 *    [Manifest.permission.BLUETOOTH_CONNECT] and
 *    [Manifest.permission.BLUETOOTH_ADVERTISE] are runtime (dangerous)
 *    permissions and must be requested from the user.
 *  - API 26–30: BLUETOOTH / BLUETOOTH_ADMIN are install-time (normal)
 *    permissions, but BLE **scanning** results are gated behind
 *    [Manifest.permission.ACCESS_FINE_LOCATION]. Location is requested ONLY
 *    on those versions and ONLY because the platform requires it for scans;
 *    the app never reads GPS location.
 *
 * This object only *reports* what is missing. Requesting permissions (and
 * showing the rationale UI) is the app layer's job.
 */
object BlePermissions {

    /** All runtime permissions this app needs for full BLE operation on [sdk]. */
    fun requiredPermissions(sdk: Int = Build.VERSION.SDK_INT): List<String> =
        if (sdk >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
            )
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    /** Permissions needed just to scan, on [sdk]. */
    fun scanPermissions(sdk: Int = Build.VERSION.SDK_INT): List<String> =
        if (sdk >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun connectPermissions(sdk: Int = Build.VERSION_CODES.S): List<String> =
        if (sdk >= Build.VERSION_CODES.S) listOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyList()

    fun advertisePermissions(sdk: Int = Build.VERSION_CODES.S): List<String> =
        if (sdk >= Build.VERSION_CODES.S) listOf(Manifest.permission.BLUETOOTH_ADVERTISE) else emptyList()

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** Permissions from [requiredPermissions] that the user has not granted yet. */
    fun missingPermissions(context: Context): List<String> =
        requiredPermissions().filterNot { granted(context, it) }

    fun hasAll(context: Context): Boolean = missingPermissions(context).isEmpty()

    fun hasScan(context: Context): Boolean =
        scanPermissions().all { granted(context, it) }

    fun hasConnect(context: Context): Boolean =
        connectPermissions().all { granted(context, it) }

    fun hasAdvertise(context: Context): Boolean =
        advertisePermissions().all { granted(context, it) }
}
