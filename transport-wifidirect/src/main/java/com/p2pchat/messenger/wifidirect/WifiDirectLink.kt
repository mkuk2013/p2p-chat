package com.p2pchat.messenger.wifidirect

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import android.util.Log
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * The two halves of a Wi-Fi Direct call link. Identity never travels on
 * this layer — the CALLER and CALLEE already know each other through the
 * app's verified mesh identity; this layer only moves bytes fast.
 *
 * Topology (chosen because Android gives no silent way to pair two
 * WifiP2pManager peers, and discovery names cannot be trusted):
 *  - The CALLER creates a Wi-Fi Direct group with a fresh, per-call
 *    SSID + passphrase and shares them inside the end-to-end encrypted
 *    mesh invite. The caller is the group owner (GO).
 *  - The CALLEE joins that group as a Wi-Fi client through
 *    WifiNetworkSpecifier (Android shows its own consent dialog — user
 *    consent is a feature, not a bug) and then reaches the GO at the
 *    address carried in the invite (Android groups use 192.168.49.1).
 *
 * Nothing here touches mobile data or any internet path: the joined
 * network explicitly removes NET_CAPABILITY_INTERNET, and while joined
 * the process is bound to it so the WebRTC sockets cannot wander onto
 * another interface mid-call.
 */
data class WifiGroupInfo(
    val ssid: String,
    val passphrase: String,
    val ownerIp: String,
)

class WifiDirectGroupOwner(context: Context) {
    private val appContext = context.applicationContext
    private val manager =
        appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val channel: WifiP2pManager.Channel? =
        manager?.initialize(appContext, Looper.getMainLooper(), null)

    val supported: Boolean get() = manager != null && channel != null

    /**
     * Create a fresh group and wait until it is actually formed.
     * @return the group coordinates, or null with [lastError] set.
     */
    @Volatile
    var lastError: String? = null
        private set

    @SuppressLint("MissingPermission") // gated by the UI permission flow
    suspend fun createGroup(): WifiGroupInfo? {
        val mgr = manager ?: run { lastError = "Wi-Fi Direct is not available on this phone"; return null }
        val ch = channel ?: run { lastError = "Wi-Fi Direct is not available on this phone"; return null }
        lastError = null
        val suffix = UUID.randomUUID().toString().replace("-", "").take(8)
        val ssid = "DIRECT-P2-$suffix"
        val passphrase = UUID.randomUUID().toString().replace("-", "").take(20)
        val created = suspendCancellableCoroutine { cont ->
            val listener = object : WifiP2pManager.ActionListener {
                override fun onSuccess() { if (cont.isActive) cont.resume(true) }
                override fun onFailure(reason: Int) { if (cont.isActive) cont.resume(false) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val config = WifiP2pConfig.Builder()
                    .setNetworkName(ssid)
                    .setPassphrase(passphrase)
                    .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ)
                    .build()
                mgr.createGroup(ch, config, listener)
            } else {
                @Suppress("DEPRECATION")
                mgr.createGroup(ch, listener)
            }
        }
        if (!created) {
            lastError = "This phone couldn't start a Wi-Fi Direct group"
            return null
        }
        // Group formation is asynchronous: poll connection + group info
        // until both agree the group is up (or give up honestly).
        repeat(30) {
            val info = requestConnectionInfo()
            val group = requestGroupInfo()
            if (info?.groupFormed == true && group != null) {
                val ownerIp = info.groupOwnerAddress?.hostAddress ?: "192.168.49.1"
                return WifiGroupInfo(
                    ssid = group.networkName,
                    passphrase = group.passphrase ?: passphrase,
                    ownerIp = ownerIp,
                )
            }
            delay(500)
        }
        lastError = "The Wi-Fi Direct group didn't start in time"
        removeGroup()
        return null
    }

    private suspend fun requestConnectionInfo() = suspendCancellableCoroutine { cont ->
        val mgr = manager; val ch = channel
        if (mgr == null || ch == null) { cont.resume(null); return@suspendCancellableCoroutine }
        mgr.requestConnectionInfo(ch) { info -> if (cont.isActive) cont.resume(info) }
    }

    private suspend fun requestGroupInfo() = suspendCancellableCoroutine { cont ->
        val mgr = manager; val ch = channel
        if (mgr == null || ch == null) { cont.resume(null); return@suspendCancellableCoroutine }
        mgr.requestGroupInfo(ch) { group -> if (cont.isActive) cont.resume(group) }
    }

    fun removeGroup() {
        val mgr = manager ?: return
        val ch = channel ?: return
        runCatching {
            mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {}
                override fun onFailure(reason: Int) {}
            })
        }
    }

    companion object {
        private const val TAG = "WifiDirectOwner"
    }
}

/**
 * Callee side: join the caller's group as a plain Wi-Fi client.
 * [join] completes through exactly one of its callbacks, once.
 */
class WifiDirectJoiner(context: Context) {
    private val appContext = context.applicationContext
    private val connectivity =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiManager =
        appContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private var callback: ConnectivityManager.NetworkCallback? = null
    private var legacyNetworkId: Int = -1

    fun join(
        ssid: String,
        passphrase: String,
        onReady: (Network?) -> Unit,
        onFailure: (String) -> Unit,
    ) {
        leave()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            joinViaSpecifier(ssid, passphrase, onReady, onFailure)
        } else {
            joinLegacy(ssid, passphrase, onReady, onFailure)
        }
    }

    @SuppressLint("MissingPermission")
    private fun joinViaSpecifier(
        ssid: String,
        passphrase: String,
        onReady: (Network?) -> Unit,
        onFailure: (String) -> Unit,
    ) {
        val done = AtomicBoolean(false)
        val specifier = android.net.wifi.WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(passphrase)
            .build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (done.compareAndSet(false, true)) onReady(network)
            }

            override fun onUnavailable() {
                if (done.compareAndSet(false, true)) {
                    onFailure("The other phone's Wi-Fi group wasn't joined — " +
                        "the system request was declined or timed out")
                }
            }
        }
        callback = cb
        try {
            connectivity.requestNetwork(request, cb, 60_000)
        } catch (e: Exception) {
            Log.w(TAG, "requestNetwork failed", e)
            if (done.compareAndSet(false, true)) {
                onFailure("This phone couldn't ask for the Wi-Fi group")
            }
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun joinLegacy(
        ssid: String,
        passphrase: String,
        onReady: (Network?) -> Unit,
        onFailure: (String) -> Unit,
    ) {
        try {
            val config = WifiConfiguration().apply {
                SSID = "\"$ssid\""
                preSharedKey = "\"$passphrase\""
                status = WifiConfiguration.Status.ENABLED
            }
            legacyNetworkId = wifiManager.addNetwork(config)
            if (legacyNetworkId < 0) {
                onFailure("This phone couldn't join the Wi-Fi group")
                return
            }
            wifiManager.disconnect()
            val ok = wifiManager.enableNetwork(legacyNetworkId, true)
            wifiManager.reconnect()
            if (ok) {
                onReady(null) // pre-Q: default routing reaches the group subnet
            } else {
                onFailure("This phone couldn't join the Wi-Fi group")
            }
        } catch (e: Exception) {
            Log.w(TAG, "legacy join failed", e)
            onFailure("This phone couldn't join the Wi-Fi group")
        }
    }

    fun leave() {
        callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        callback = null
        if (legacyNetworkId >= 0) {
            @Suppress("DEPRECATION")
            runCatching { wifiManager.removeNetwork(legacyNetworkId) }
            legacyNetworkId = -1
        }
    }

    companion object {
        private const val TAG = "WifiDirectJoiner"
    }
}
