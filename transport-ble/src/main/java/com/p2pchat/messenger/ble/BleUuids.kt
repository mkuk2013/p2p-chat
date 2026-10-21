package com.p2pchat.messenger.ble

import java.util.UUID

/**
 * GATT layout for P2P Chat.
 *
 * Custom 128-bit UUIDs in the `b7e6a120-…-2026102100xx` block (project date
 * 2026-10-21 + suffix). Both phones run the GATT server AND the GATT client
 * role simultaneously:
 *
 *  - A peer that connects to us **writes** chunks to our [TX_CHAR] and
 *    **subscribes** to [RX_CHAR] notifications to receive chunks from us.
 *  - When we connect to a peer the roles mirror: we write to their TX and
 *    subscribe to their RX.
 *
 * [IDENTITY_CHAR] is a read-only characteristic serving the owner's encoded
 * identity payload (peerId + public keys + display name, encoded by the app
 * layer — this module treats it as opaque bytes).
 */
object BleUuids {

    /** Primary mesh service, also used in advertising + scan filtering. */
    val SERVICE_UUID: UUID = UUID.fromString("b7e6a120-4c3a-4d2a-9f01-202610210001")

    /** Write / Write-No-Response: chunk bytes from the connecting peer to us. */
    val TX_CHAR_UUID: UUID = UUID.fromString("b7e6a120-4c3a-4d2a-9f01-202610210002")

    /** Notify: chunk bytes from us to the connected peer. */
    val RX_CHAR_UUID: UUID = UUID.fromString("b7e6a120-4c3a-4d2a-9f01-202610210003")

    /** Read: opaque encoded identity payload of the device serving it. */
    val IDENTITY_CHAR_UUID: UUID = UUID.fromString("b7e6a120-4c3a-4d2a-9f01-202610210004")

    /** Standard Client Characteristic Configuration Descriptor (0x2902). */
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}
