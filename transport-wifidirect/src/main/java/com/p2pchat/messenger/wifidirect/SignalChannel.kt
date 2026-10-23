package com.p2pchat.messenger.wifidirect

import android.net.Network
import android.util.Log
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import org.json.JSONObject

/**
 * One TCP connection carrying the WebRTC signalling for a single call:
 * newline-delimited JSON objects ({type:"offer"|"answer"|"ice"|"hello"|
 * "bye", ...}). The channel itself is plain because the Wi-Fi group is
 * WPA2-encrypted with a per-call passphrase that only ever travelled
 * inside the end-to-end encrypted mesh invite, the first message is a
 * hello that must name the expected peer + call id, and the media that
 * follows is DTLS-SRTP encrypted by WebRTC itself.
 */
class SignalChannel {

    interface Listener {
        fun onMessage(json: JSONObject)
        fun onClosed(reason: String)
    }

    @Volatile
    private var socket: Socket? = null
    @Volatile
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var writer: BufferedWriter? = null
    @Volatile
    private var closed = false
    private val writeLock = Any()

    val isOpen: Boolean get() = socket?.isConnected == true && !closed

    /** Group-owner side: listen (wildcard — the group interface is the
     *  only local route the peer can reach) and accept ONE client. */
    fun listen(port: Int, listener: Listener, onListening: () -> Unit) {
        Thread({
            try {
                val server = ServerSocket()
                server.reuseAddress = true
                server.bind(InetSocketAddress(port))
                serverSocket = server
                onListening()
                val client = server.accept()
                runCatching { server.close() }
                serverSocket = null
                attach(client, listener)
            } catch (e: Exception) {
                if (!closed) listener.onClosed("listen failed: ${e.message}")
            }
        }, "p2p-signal-server").apply { isDaemon = true; start() }
    }

    /** Callee side: connect through the joined group [network] when one
     *  is available, so the socket cannot take another interface. */
    fun connect(
        network: Network?,
        host: String,
        port: Int,
        listener: Listener,
        onConnected: () -> Unit = {},
    ) {
        Thread({
            try {
                val s = network?.socketFactory?.createSocket() ?: Socket()
                s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                attach(s, listener)
                onConnected()
            } catch (e: Exception) {
                if (!closed) listener.onClosed("connect failed: ${e.message}")
            }
        }, "p2p-signal-client").apply { isDaemon = true; start() }
    }

    private fun attach(s: Socket, listener: Listener) {
        socket = s
        writer = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))
        Thread({
            var closeReason = "Connection closed"
            try {
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    val json = runCatching { JSONObject(line) }.getOrNull() ?: continue
                    listener.onMessage(json)
                }
            } catch (e: Exception) {
                closeReason = "Connection lost"
                Log.w(TAG, "signal channel read ended", e)
            } finally {
                if (!closed) listener.onClosed(closeReason)
            }
        }, "p2p-signal-read").apply { isDaemon = true; start() }
    }

    fun send(json: JSONObject): Boolean {
        val w = writer ?: return false
        return try {
            synchronized(writeLock) {
                w.write(json.toString())
                w.newLine()
                w.flush()
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "signal send failed", e)
            false
        }
    }

    fun close() {
        closed = true
        runCatching { writer?.close() }
        writer = null
        runCatching { socket?.close() }
        socket = null
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    companion object {
        private const val TAG = "SignalChannel"
        const val PORT: Int = 18777
        const val CONNECT_TIMEOUT_MS: Int = 20_000
    }
}
