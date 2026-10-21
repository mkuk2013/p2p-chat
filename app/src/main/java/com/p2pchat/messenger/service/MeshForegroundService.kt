package com.p2pchat.messenger.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.p2pchat.messenger.MainActivity
import com.p2pchat.messenger.P2PApp
import com.p2pchat.messenger.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Background mesh relay.
 *
 * While running it keeps this phone discoverable (advertising stays on)
 * and scans in a duty cycle — scan for `scan_on_ms`, pause for
 * `scan_off_ms`, repeating — so the phone can relay envelopes for others
 * with the screen locked. Intervals come from SharedPreferences
 * "mesh_settings" (keys `scan_on_ms` / `scan_off_ms`, defaults
 * 10_000 / 25_000) and are re-read every cycle, so the settings screen's
 * battery choices apply without restarting the service.
 *
 * Honest platform limits: Android may still kill the process under
 * aggressive OEM battery management, and nothing here can run after a
 * force-stop until the user opens the app again. The persistent
 * notification is the user's visible, controllable switch.
 */
class MeshForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var dutyCycleJob: Job? = null
    private var notificationJob: Job? = null
    private var bluetoothReceiverRegistered = false

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            if (state == BluetoothAdapter.STATE_ON) {
                // Bluetooth came back: resume the duty cycle immediately
                // instead of waiting out the remainder of the pause.
                startDutyCycle()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        registerBluetoothReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundWithNotification(connectedPeers = 0)

        serviceScope.launch {
            val container = (application as P2PApp).container
            if (container == null) {
                // App startup failed before the container was built — there
                // is nothing to relay with; stop instead of crashing.
                stopSelf()
                return@launch
            }
            val identity = container.ensureIdentity(null)
            val transport = container.bleTransport
            if (identity == null || transport == null) {
                // Relay requested before onboarding created an identity —
                // there is no peerId to advertise; stop rather than run
                // a hollow service.
                stopSelf()
                return@launch
            }
            observeConnectionCount()
            startDutyCycle()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        dutyCycleJob?.cancel()
        notificationJob?.cancel()
        if (bluetoothReceiverRegistered) {
            runCatching { unregisterReceiver(bluetoothStateReceiver) }
            bluetoothReceiverRegistered = false
        }
        (application as? P2PApp)?.container?.bleTransport?.let { transport ->
            transport.stopScanning()
            transport.stopAdvertising()
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------------------------------------------------- duty cycle

    private fun startDutyCycle() {
        val transport = (application as P2PApp).container?.bleTransport ?: return
        dutyCycleJob?.cancel()
        dutyCycleJob = serviceScope.launch {
            // Advertising is cheap next to scanning and makes this node
            // discoverable as a relay, so it stays on continuously; only
            // scanning (the power-hungry half) is duty-cycled.
            transport.startAdvertising()
            while (isActive) {
                val (scanOnMs, scanOffMs) = readDutyCycle()
                transport.startScanning()
                delay(scanOnMs)
                transport.stopScanning()
                delay(scanOffMs)
            }
        }
    }

    private fun readDutyCycle(): Pair<Long, Long> {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val on = (prefs.all[KEY_SCAN_ON_MS] as? Number)?.toLong() ?: DEFAULT_SCAN_ON_MS
        val off = (prefs.all[KEY_SCAN_OFF_MS] as? Number)?.toLong() ?: DEFAULT_SCAN_OFF_MS
        return on.coerceAtLeast(1_000L) to off.coerceAtLeast(1_000L)
    }

    // ---------------------------------------------------------- notification

    private fun observeConnectionCount() {
        val transport = (application as P2PApp).container?.bleTransport ?: return
        notificationJob?.cancel()
        notificationJob = serviceScope.launch {
            transport.connections.collectLatest { connections ->
                val ready = connections.values.count {
                    it == com.p2pchat.messenger.ble.model.ConnState.READY
                }
                val manager = getSystemService(NotificationManager::class.java)
                manager?.notify(NOTIFICATION_ID, buildNotification(ready))
            }
        }
    }

    private fun startForegroundWithNotification(connectedPeers: Int) {
        val notification = buildNotification(connectedPeers)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(connectedPeers: Int): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            REQUEST_STOP,
            Intent(this, MeshForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = if (connectedPeers == 1) {
            "Relaying — 1 peer connected"
        } else {
            "Relaying — $connectedPeers peers connected"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("P2P Chat mesh relay is on")
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .addAction(R.drawable.ic_notification, "Stop", stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.mesh_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.mesh_notification_channel_description)
        }
        manager.createNotificationChannel(channel)
    }

    private fun registerBluetoothReceiver() {
        if (bluetoothReceiverRegistered) return
        runCatching {
            registerReceiver(
                bluetoothStateReceiver,
                IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
            )
            bluetoothReceiverRegistered = true
        }
    }

    companion object {
        const val CHANNEL_ID = "mesh_relay"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.p2pchat.messenger.action.STOP_MESH_RELAY"

        const val PREFS_NAME = "mesh_settings"
        const val KEY_SCAN_ON_MS = "scan_on_ms"
        const val KEY_SCAN_OFF_MS = "scan_off_ms"
        const val KEY_RELAY_ENABLED = "relay_enabled"
        const val DEFAULT_SCAN_ON_MS = 10_000L
        const val DEFAULT_SCAN_OFF_MS = 25_000L

        private const val REQUEST_OPEN = 10
        private const val REQUEST_STOP = 11

        /** Start background relay (foreground service). Safe to call when
         * already running — Android delivers another onStartCommand. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, MeshForegroundService::class.java)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MeshForegroundService::class.java))
        }
    }
}
