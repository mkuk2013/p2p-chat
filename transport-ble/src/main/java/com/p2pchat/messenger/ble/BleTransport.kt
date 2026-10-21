package com.p2pchat.messenger.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import com.p2pchat.messenger.ble.model.BlePeer
import com.p2pchat.messenger.ble.model.ConnState
import com.p2pchat.messenger.ble.model.IncomingData
import com.p2pchat.messenger.core.transport.ChunkReassembler
import com.p2pchat.messenger.core.transport.PacketChunker
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Real BLE transport for P2P Chat: scanning, advertising, GATT server and
 * GATT client in one class, because every mesh node plays both roles.
 *
 * Sending path: [send] splits the payload with [PacketChunker] using a
 * per-connection payload size derived from the negotiated MTU, then writes
 * the chunks **serially** over one of two routes:
 *  1. preferred — we are GATT client to the peer: write to their TX char;
 *  2. fallback — the peer is GATT client to us: notify them on our RX char.
 *
 * Receiving path: chunk bytes arrive either on our server's TX char
 * (peer-as-client writes) or as notifications from a server we connected to;
 * both feed a per-address [ChunkReassembler] and complete payloads are
 * emitted on [incoming] fully reassembled.
 *
 * Honest limits (also in the app README):
 *  - at most [MAX_CONNECTIONS] simultaneous GATT links;
 *  - Bluetooth is never enabled silently — [isBluetoothEnabled] only reports
 *    state; the app shows Android's official enable prompt;
 *  - Android may throttle scans/advertising; failures surface via state,
 *    nothing here pretends a link exists when it does not.
 */
class BleTransport(
    private val context: Context,
    private val localPeerId: String,
    private val identityPayloadProvider: () -> ByteArray,
) {

    private val appContext: Context = context.applicationContext
    private val bluetoothManager: BluetoothManager? =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val handler = Handler(Looper.getMainLooper())

    // ---------------------------------------------------------------- flows

    private val _peers = MutableStateFlow<List<BlePeer>>(emptyList())
    val peers: StateFlow<List<BlePeer>> = _peers.asStateFlow()

    private val _connections = MutableStateFlow<Map<String, ConnState>>(emptyMap())
    val connections: StateFlow<Map<String, ConnState>> = _connections.asStateFlow()

    private val _incoming = MutableSharedFlow<IncomingData>(extraBufferCapacity = 64)
    val incoming: SharedFlow<IncomingData> = _incoming.asSharedFlow()

    /** Raw IDENTITY payloads read from peers, keyed by device address. The app
     * layer decodes them (peerId / keys / name) and calls [setPeerIdentity]. */
    private val _peerIdentities = MutableStateFlow<Map<String, ByteArray>>(emptyMap())
    val peerIdentities: StateFlow<Map<String, ByteArray>> = _peerIdentities.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _isAdvertising = MutableStateFlow(false)
    val isAdvertising: StateFlow<Boolean> = _isAdvertising.asStateFlow()

    // ---------------------------------------------------------------- state

    private val peerMap = ConcurrentHashMap<String, BlePeer>()
    private val clientGatts = ConcurrentHashMap<String, BluetoothGatt>()
    private val serverDevices = ConcurrentHashMap<String, BluetoothDevice>()
    private val subscribedServerPeers = ConcurrentHashMap.newKeySet<String>()
    private val reassemblers = ConcurrentHashMap<String, ChunkReassembler>()
    private val mtuByAddress = ConcurrentHashMap<String, Int>()
    private val sendMutexByAddress = ConcurrentHashMap<String, Mutex>()
    private val writeAckWaiters = ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<Boolean>>()
    private val connectTimeouts = ConcurrentHashMap<String, Runnable>()
    private val reconnectAttempts = ConcurrentHashMap<String, Int>()
    private val manualDisconnects = ConcurrentHashMap.newKeySet<String>()
    private val knownAddresses = ConcurrentHashMap.newKeySet<String>()

    private var scanner: BluetoothLeScanner? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null
    private var serverRxCharacteristic: BluetoothGattCharacteristic? = null

    private val transferIdCounter = AtomicLong(System.currentTimeMillis())

    /** When true (default), a dropped link to a known peer is retried with
     * exponential backoff (max [MAX_RECONNECT_ATTEMPTS] attempts). */
    var autoReconnect: Boolean = true

    /** When true, if a filtered scan yields nothing the app layer may restart
     * scanning unfiltered (some OEMs mishandle 128-bit UUID filters). */
    var useUnfilteredFallback: Boolean = false

    companion object {
        const val MAX_CONNECTIONS: Int = 5
        const val CONNECT_TIMEOUT_MS: Long = 10_000L
        const val MAX_RECONNECT_ATTEMPTS: Int = 3
        const val REQUESTED_MTU: Int = 517
        const val DEFAULT_MTU: Int = 23
        const val MAX_PAYLOAD_CAP: Int = 512
        const val REASSEMBLY_TIMEOUT_MS: Long = 15_000L

        /** First 8 bytes of SHA-256(peerId) — the advertisement identity hint. */
        fun identityHint(peerId: String): ByteArray =
            MessageDigest.getInstance("SHA-256")
                .digest(peerId.toByteArray(Charsets.UTF_8))
                .copyOf(8)
    }

    // ------------------------------------------------------------- BT state

    fun isBluetoothSupported(): Boolean = adapter != null

    /** Reports state only. NEVER enables Bluetooth — that is the user's
     * decision via Android's official enable prompt, launched by the app. */
    fun isBluetoothEnabled(): Boolean = adapter?.isEnabled == true

    private fun activeConnectionCount(): Int =
        _connections.value.count { it.value != ConnState.DISCONNECTED && it.value != ConnState.FAILED }

    private fun setConnState(address: String, state: ConnState) {
        _connections.value = _connections.value.toMutableMap().apply { put(address, state) }
    }

    private fun upsertPeer(peer: BlePeer) {
        peerMap[peer.deviceAddress] = peer
        _peers.value = peerMap.values.sortedByDescending { it.lastSeenMs }
    }

    /** The app layer calls this after decoding a peer's IDENTITY payload. */
    fun setPeerIdentity(address: String, peerId: String, displayName: String?) {
        val existing = peerMap[address]
        if (existing == null) {
            // Inbound-only peer (connected to our server without being scanned).
            upsertPeer(
                BlePeer(
                    deviceAddress = address,
                    peerId = peerId,
                    displayName = displayName,
                    lastSeenMs = System.currentTimeMillis(),
                ),
            )
        } else {
            upsertPeer(existing.copy(peerId = peerId, displayName = displayName ?: existing.displayName))
        }
    }

    // -------------------------------------------------------------- scanning

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val address = device.address ?: return
            val serviceData = result.scanRecord
                ?.getServiceData(ParcelUuid(BleUuids.SERVICE_UUID))
            // Name read is best-effort; scan permission was checked in startScanning().
            val name = try {
                result.scanRecord?.deviceName ?: if (BlePermissions.hasConnect(appContext)) device.name else null
            } catch (e: SecurityException) {
                result.scanRecord?.deviceName
            }
            val existing = peerMap[address]
            upsertPeer(
                BlePeer(
                    deviceAddress = address,
                    peerId = existing?.peerId,
                    displayName = name ?: existing?.displayName,
                    rssi = result.rssi,
                    lastSeenMs = System.currentTimeMillis(),
                    connectable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        result.isConnectable
                    } else {
                        true
                    },
                    identityHint = serviceData?.copyOf() ?: existing?.identityHint,
                ),
            )
        }

        override fun onScanFailed(errorCode: Int) {
            _isScanning.value = false
        }
    }

    @SuppressLint("MissingPermission")
    fun startScanning(): Boolean {
        if (!isBluetoothEnabled()) return false
        if (!BlePermissions.hasScan(appContext)) return false
        val leScanner = adapter?.bluetoothLeScanner ?: return false
        if (_isScanning.value) return true
        scanner = leScanner
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .build()
        try {
            if (useUnfilteredFallback) {
                leScanner.startScan(null, settings, scanCallback)
            } else {
                val filter = ScanFilter.Builder()
                    .setServiceUuid(ParcelUuid(BleUuids.SERVICE_UUID))
                    .build()
                leScanner.startScan(listOf(filter), settings, scanCallback)
            }
        } catch (e: SecurityException) {
            return false
        }
        _isScanning.value = true
        return true
    }

    @SuppressLint("MissingPermission")
    fun stopScanning() {
        if (_isScanning.value) {
            try {
                scanner?.stopScan(scanCallback)
            } catch (e: Exception) {
                // Scanner already gone (BT off) — state flag below is the truth we keep.
            }
        }
        _isScanning.value = false
    }

    // ----------------------------------------------------------- advertising

    private var advertiseIncludeServiceData = true

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            _isAdvertising.value = true
        }

        override fun onStartFailure(errorCode: Int) {
            _isAdvertising.value = false
            // 128-bit UUID + service data can exceed the legacy 31-byte
            // advertisement on some devices: retry once without service data
            // (peer identity is then resolved only after connecting).
            if (errorCode == ADVERTISE_FAILED_DATA_TOO_LARGE && advertiseIncludeServiceData) {
                advertiseIncludeServiceData = false
                handler.post { startAdvertising() }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startAdvertising(): Boolean {
        if (!isBluetoothEnabled()) return false
        if (!BlePermissions.hasAdvertise(appContext)) return false
        if (!BlePermissions.hasConnect(appContext)) return false
        val leAdvertiser = adapter?.bluetoothLeAdvertiser ?: return false
        startGattServer() // peers connecting to us must find our service
        if (_isAdvertising.value) return true
        advertiser = leAdvertiser
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val dataBuilder = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(BleUuids.SERVICE_UUID))
            .setIncludeDeviceName(false)
        if (advertiseIncludeServiceData) {
            dataBuilder.addServiceData(
                ParcelUuid(BleUuids.SERVICE_UUID),
                identityHint(localPeerId),
            )
        }
        return try {
            leAdvertiser.startAdvertising(settings, dataBuilder.build(), advertiseCallback)
            true
        } catch (e: SecurityException) {
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun stopAdvertising() {
        try {
            advertiser?.stopAdvertising(advertiseCallback)
        } catch (e: Exception) {
            // Advertiser gone with BT off.
        }
        _isAdvertising.value = false
    }

    // ------------------------------------------------------------ GATT server

    @SuppressLint("MissingPermission")
    fun startGattServer(): Boolean {
        if (gattServer != null) return true
        if (!BlePermissions.hasConnect(appContext)) return false
        val manager = bluetoothManager ?: return false
        val server = try {
            manager.openGattServer(appContext, gattServerCallback)
        } catch (e: SecurityException) {
            return false
        } ?: return false

        val tx = BluetoothGattCharacteristic(
            BleUuids.TX_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val rx = BluetoothGattCharacteristic(
            BleUuids.RX_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        val cccd = BluetoothGattDescriptor(
            BleUuids.CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
        )
        rx.addDescriptor(cccd)
        val identity = BluetoothGattCharacteristic(
            BleUuids.IDENTITY_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        val service = BluetoothGattService(BleUuids.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(tx)
        service.addCharacteristic(rx)
        service.addCharacteristic(identity)
        serverRxCharacteristic = rx
        gattServer = server
        try {
            server.addService(service)
        } catch (e: SecurityException) {
            return false
        }
        return true
    }

    fun stopGattServer() {
        try {
            gattServer?.close()
        } catch (e: Exception) {
            // Already closed.
        }
        gattServer = null
        serverRxCharacteristic = null
        serverDevices.clear()
        subscribedServerPeers.clear()
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val address = device.address ?: return
            scope.launch {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    knownAddresses.add(address)
                    serverDevices[address] = device
                    if (_connections.value[address] == null ||
                        _connections.value[address] == ConnState.DISCONNECTED
                    ) {
                        setConnState(address, ConnState.CONNECTED)
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    serverDevices.remove(address)
                    subscribedServerPeers.remove(address)
                    reassemblers.remove(address)
                    if (!clientGatts.containsKey(address)) {
                        setConnState(address, ConnState.DISCONNECTED)
                        maybeReconnect(address)
                    }
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            mtuByAddress[device.address] = mtu
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val server = gattServer ?: return
            if (characteristic.uuid == BleUuids.IDENTITY_CHAR_UUID) {
                val payload = try {
                    identityPayloadProvider()
                } catch (e: Exception) {
                    ByteArray(0)
                }
                val slice = if (offset < payload.size) {
                    payload.copyOfRange(offset, payload.size)
                } else {
                    ByteArray(0)
                }
                respond(server, device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice)
            } else {
                respond(server, device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            val server = gattServer
            if (characteristic.uuid == BleUuids.TX_CHAR_UUID && value != null) {
                handleIncomingChunk(device.address, value)
                if (responseNeeded && server != null) {
                    respond(server, device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
                }
            } else if (responseNeeded && server != null) {
                respond(server, device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            if (descriptor.uuid == BleUuids.CCCD_UUID) {
                val enabled = value != null &&
                    value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                if (enabled) {
                    subscribedServerPeers.add(device.address)
                    if (_connections.value[device.address] != ConnState.READY) {
                        setConnState(device.address, ConnState.READY)
                    }
                } else {
                    subscribedServerPeers.remove(device.address)
                }
            }
            if (responseNeeded) {
                gattServer?.let {
                    respond(it, device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
                }
            }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor,
        ) {
            val value = if (subscribedServerPeers.contains(device.address)) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else {
                BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            }
            gattServer?.let { respond(it, device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun respond(
        server: BluetoothGattServer,
        device: BluetoothDevice,
        requestId: Int,
        status: Int,
        offset: Int,
        value: ByteArray?,
    ) {
        try {
            server.sendResponse(device, requestId, status, offset, value)
        } catch (e: SecurityException) {
            // Peer vanished mid-request.
        }
    }

    // ------------------------------------------------------------ GATT client

    @SuppressLint("MissingPermission")
    fun connect(address: String): Boolean {
        if (!isBluetoothEnabled()) return false
        if (!BlePermissions.hasConnect(appContext)) return false
        val current = _connections.value[address]
        if (current == ConnState.CONNECTING || current == ConnState.CONNECTED ||
            current == ConnState.DISCOVERING || current == ConnState.READY
        ) {
            return true
        }
        if (activeConnectionCount() >= MAX_CONNECTIONS) return false
        val device = try {
            adapter?.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            null
        } ?: return false
        manualDisconnects.remove(address)
        knownAddresses.add(address)
        setConnState(address, ConnState.CONNECTING)
        val gatt = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(appContext, false, gattClientCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(appContext, false, gattClientCallback)
            }
        } catch (e: SecurityException) {
            setConnState(address, ConnState.FAILED)
            return false
        }
        if (gatt == null) {
            setConnState(address, ConnState.FAILED)
            return false
        }
        clientGatts[address] = gatt
        scheduleConnectTimeout(address)
        return true
    }

    @SuppressLint("MissingPermission")
    fun disconnect(address: String) {
        manualDisconnects.add(address)
        cancelConnectTimeout(address)
        reconnectAttempts.remove(address)
        try {
            clientGatts.remove(address)?.let { gatt ->
                gatt.disconnect()
                gatt.close()
            }
        } catch (e: Exception) {
            // Link already gone.
        }
        try {
            serverDevices[address]?.let { device -> gattServer?.cancelConnection(device) }
        } catch (e: Exception) {
            // Server already gone.
        }
        serverDevices.remove(address)
        subscribedServerPeers.remove(address)
        reassemblers.remove(address)
        setConnState(address, ConnState.DISCONNECTED)
    }

    private fun scheduleConnectTimeout(address: String) {
        cancelConnectTimeout(address)
        val runnable = Runnable {
            val state = _connections.value[address]
            if (state == ConnState.CONNECTING || state == ConnState.CONNECTED ||
                state == ConnState.DISCOVERING
            ) {
                setConnState(address, ConnState.FAILED)
                disconnectInternal(address, manual = false)
                maybeReconnect(address)
            }
        }
        connectTimeouts[address] = runnable
        handler.postDelayed(runnable, CONNECT_TIMEOUT_MS)
    }

    private fun cancelConnectTimeout(address: String) {
        connectTimeouts.remove(address)?.let { handler.removeCallbacks(it) }
    }

    @SuppressLint("MissingPermission")
    private fun disconnectInternal(address: String, manual: Boolean) {
        if (manual) manualDisconnects.add(address)
        cancelConnectTimeout(address)
        try {
            clientGatts.remove(address)?.let { it.disconnect(); it.close() }
        } catch (e: Exception) {
            // Already gone.
        }
    }

    private fun maybeReconnect(address: String) {
        if (!autoReconnect) return
        if (manualDisconnects.contains(address)) return
        if (!knownAddresses.contains(address)) return
        if (!isBluetoothEnabled()) return
        val attempts = (reconnectAttempts[address] ?: 0) + 1
        if (attempts > MAX_RECONNECT_ATTEMPTS) {
            reconnectAttempts.remove(address)
            return
        }
        reconnectAttempts[address] = attempts
        val backoffMs = (1L shl (attempts - 1)) * 1_000L // 1s, 2s, 4s
        handler.postDelayed({
            if (!manualDisconnects.contains(address) && isBluetoothEnabled()) {
                connect(address)
            }
        }, backoffMs)
    }

    private val gattClientCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val address = gatt.device?.address ?: return
            scope.launch {
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    setConnState(address, ConnState.CONNECTED)
                    try {
                        gatt.requestMtu(REQUESTED_MTU)
                    } catch (e: Exception) {
                        // MTU stays at default for this link.
                    }
                    setConnState(address, ConnState.DISCOVERING)
                    try {
                        gatt.discoverServices()
                    } catch (e: SecurityException) {
                        setConnState(address, ConnState.FAILED)
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    cancelConnectTimeout(address)
                    try {
                        gatt.close()
                    } catch (e: Exception) {
                        // Already closed.
                    }
                    clientGatts.remove(address)
                    reassemblers.remove(address)
                    if (!serverDevices.containsKey(address)) {
                        setConnState(address, ConnState.DISCONNECTED)
                        maybeReconnect(address)
                    }
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                mtuByAddress[gatt.device.address] = mtu
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val address = gatt.device?.address ?: return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                setConnState(address, ConnState.FAILED)
                return
            }
            val service = gatt.getService(BleUuids.SERVICE_UUID)
            if (service == null) {
                // Not a P2P Chat peer — do not pretend otherwise.
                setConnState(address, ConnState.FAILED)
                disconnect(address)
                return
            }
            val rx = service.getCharacteristic(BleUuids.RX_CHAR_UUID)
            val identity = service.getCharacteristic(BleUuids.IDENTITY_CHAR_UUID)
            var subscribing = false
            if (rx != null) {
                try {
                    if (gatt.setCharacteristicNotification(rx, true)) {
                        val cccd = rx.getDescriptor(BleUuids.CCCD_UUID)
                        if (cccd != null) {
                            writeDescriptorCompat(
                                gatt,
                                cccd,
                                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE,
                            )
                            subscribing = true
                        }
                    }
                } catch (e: SecurityException) {
                    // Handled by state below: not READY until proven.
                }
            }
            if (identity != null) {
                try {
                    gatt.readCharacteristic(identity)
                } catch (e: SecurityException) {
                    // Identity stays unresolved; link can still be used by address.
                }
            }
            if (!subscribing) {
                // No notify path — writes still work one-way; mark READY only
                // if we at least have the TX characteristic.
                if (service.getCharacteristic(BleUuids.TX_CHAR_UUID) != null) {
                    markReady(address)
                } else {
                    setConnState(address, ConnState.FAILED)
                }
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val address = gatt.device?.address ?: return
            if (descriptor.uuid == BleUuids.CCCD_UUID && status == BluetoothGatt.GATT_SUCCESS) {
                markReady(address)
            }
        }

        // API 33+ read callback with value.
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            if (characteristic.uuid == BleUuids.IDENTITY_CHAR_UUID && status == BluetoothGatt.GATT_SUCCESS) {
                handleIdentityPayload(gatt.device.address, value)
            }
        }

        // Pre-API 33 read callback.
        @Deprecated("Deprecated in API 33; both overloads are required for minSdk 26 support")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            if (characteristic.uuid == BleUuids.IDENTITY_CHAR_UUID && status == BluetoothGatt.GATT_SUCCESS) {
                @Suppress("DEPRECATION")
                val value = characteristic.value ?: return
                handleIdentityPayload(gatt.device.address, value)
            }
        }

        // API 33+ change callback with value.
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (characteristic.uuid == BleUuids.RX_CHAR_UUID) {
                handleIncomingChunk(gatt.device.address, value)
            }
        }

        // Pre-API 33 change callback.
        @Deprecated("Deprecated in API 33; both overloads are required for minSdk 26 support")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            if (characteristic.uuid == BleUuids.RX_CHAR_UUID) {
                @Suppress("DEPRECATION")
                val value = characteristic.value ?: return
                handleIncomingChunk(gatt.device.address, value)
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            val address = gatt.device?.address ?: return
            writeAckWaiters.remove(address)?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }
    }

    private fun markReady(address: String) {
        cancelConnectTimeout(address)
        reconnectAttempts.remove(address)
        setConnState(address, ConnState.READY)
    }

    private fun handleIdentityPayload(address: String, payload: ByteArray) {
        _peerIdentities.value = _peerIdentities.value.toMutableMap().apply { put(address, payload.copyOf()) }
        // Best-effort convenience parse: payloads that begin with a printable
        // token (peerId, optionally "|" + display name) resolve immediately.
        // Anything else is left for the app layer via [peerIdentities].
        val text = payload.toString(Charsets.UTF_8)
        val first = text.substringBefore('|').substringBefore('\n').trim()
        if (first.length in 8..64 && first.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
            val name = text.substringAfter('|', "").substringBefore('\n').trim()
                .takeIf { it.isNotEmpty() }
            setPeerIdentity(address, first, name)
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeDescriptorCompat(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        value: ByteArray,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value)
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = value
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeCharacteristicCompat(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        writeType: Int,
    ): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(characteristic, value, writeType) == statusSuccess
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = value
            characteristic.writeType = writeType
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(characteristic)
        }
    } catch (e: SecurityException) {
        false
    }

    /** BluetoothStatusCodes.SUCCESS (API 33+ return value of write/notify calls). */
    private val statusSuccess: Int = 0

    // ------------------------------------------------------------ data path

    private fun handleIncomingChunk(address: String, chunkBytes: ByteArray) {
        val chunk = try {
            PacketChunker.decode(chunkBytes)
        } catch (e: Exception) {
            return // malformed chunk — drop, never crash the radio callback
        }
        val reassembler = reassemblers.getOrPut(address) { ChunkReassembler(REASSEMBLY_TIMEOUT_MS) }
        val complete = try {
            reassembler.add(chunk)
        } catch (e: Exception) {
            null
        } ?: return
        scope.launch {
            _incoming.emit(IncomingData(address, complete))
        }
    }

    /** Payload bytes per chunk for [address] from its negotiated MTU:
     * mtu - 3 (ATT overhead), defaulting to the un-negotiated 23-byte MTU
     * (= 20 payload bytes) and capped at [MAX_PAYLOAD_CAP]. */
    fun chunkPayloadFor(address: String): Int {
        val mtu = mtuByAddress[address] ?: DEFAULT_MTU
        return (mtu - 3).coerceIn(1, MAX_PAYLOAD_CAP)
    }

    fun isReady(address: String): Boolean = _connections.value[address] == ConnState.READY

    fun readyAddresses(): Set<String> =
        _connections.value.filterValues { it == ConnState.READY }.keys

    /** Address → resolved peerId, for links that are READY. Used by the mesh
     * layer to bridge BLE addresses and mesh peer identities. */
    fun readyPeerIds(): Map<String, String> =
        readyAddresses().mapNotNull { address ->
            peerMap[address]?.peerId?.let { address to it }
        }.toMap()

    /**
     * Send [data] to the device at [address], chunked for its MTU.
     * Returns true only if every chunk was accepted by the radio path.
     * Returns false when the link is not READY — callers (mesh outbox) keep
     * the message queued; a relay accepting bytes is never a delivery proof.
     */
    suspend fun send(address: String, data: ByteArray): Boolean {
        if (!isBluetoothEnabled()) return false
        if (!BlePermissions.hasConnect(appContext)) return false
        if (!isReady(address) && !serverDevices.containsKey(address)) return false
        val mutex = sendMutexByAddress.getOrPut(address) { Mutex() }
        return mutex.withLock {
            val transferId = transferIdCounter.incrementAndGet()
            val chunks = PacketChunker.split(data, chunkPayloadFor(address), transferId)
            val gatt = clientGatts[address]
            val txChar = gatt?.getService(BleUuids.SERVICE_UUID)
                ?.getCharacteristic(BleUuids.TX_CHAR_UUID)
            if (gatt != null && txChar != null) {
                sendViaClientLocked(address, gatt, txChar, chunks.map { PacketChunker.encode(it) })
            } else if (serverDevices.containsKey(address)) {
                sendViaServerNotifyLocked(address, chunks.map { PacketChunker.encode(it) })
            } else {
                false
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun sendViaClientLocked(
        address: String,
        gatt: BluetoothGatt,
        txChar: BluetoothGattCharacteristic,
        encodedChunks: List<ByteArray>,
    ): Boolean {
        val canAck = (txChar.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
        for (bytes in encodedChunks) {
            if (canAck) {
                val waiter = kotlinx.coroutines.CompletableDeferred<Boolean>()
                writeAckWaiters[address] = waiter
                val initiated = writeCharacteristicCompat(
                    gatt, txChar, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                )
                if (!initiated) {
                    writeAckWaiters.remove(address)
                    return false
                }
                val ok = withTimeoutOrNull(3_000L) { waiter.await() } ?: false
                if (!ok) return false
            } else {
                val initiated = writeCharacteristicCompat(
                    gatt, txChar, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
                )
                if (!initiated) return false
                delay(15L) // pace no-response writes so the peer's stack keeps up
            }
        }
        return true
    }

    @SuppressLint("MissingPermission")
    private suspend fun sendViaServerNotifyLocked(
        address: String,
        encodedChunks: List<ByteArray>,
    ): Boolean {
        val server = gattServer ?: return false
        val device = serverDevices[address] ?: return false
        val rx = serverRxCharacteristic ?: return false
        if (!subscribedServerPeers.contains(address)) return false
        for (bytes in encodedChunks) {
            val ok = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    server.notifyCharacteristicChanged(device, rx, false, bytes) == statusSuccess
                } else {
                    @Suppress("DEPRECATION")
                    rx.value = bytes
                    @Suppress("DEPRECATION")
                    server.notifyCharacteristicChanged(device, rx, false)
                }
            } catch (e: SecurityException) {
                return false
            }
            if (!ok) return false
            delay(15L)
        }
        return true
    }

    // --------------------------------------------------------------- lifecycle

    /** Start everything the current permission/BT state allows. Each step is
     * independent: scanning may start while advertising is still blocked etc. */
    fun start() {
        startGattServer()
        startScanning()
        startAdvertising()
    }

    fun stop() {
        stopScanning()
        stopAdvertising()
        _connections.value.keys.toList().forEach { disconnect(it) }
        stopGattServer()
    }

    /** Release all resources. The transport must not be used after [close]. */
    fun close() {
        stop()
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
    }
}
