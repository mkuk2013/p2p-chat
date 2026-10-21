package com.p2pchat.messenger.mesh

/**
 * Delivery lifecycle of an outgoing message, from this sender's point of view.
 *
 * CRITICAL SEMANTICS — read before touching UI ticks:
 *  - [QUEUED]: sitting in the local outbox, no neighbour has accepted it yet.
 *  - [RELAYED]: at least one neighbour accepted the envelope for forwarding.
 *    **This is NOT delivery.** The relay cannot read the message and gives no
 *    guarantee the recipient will ever receive it. A UI must never render
 *    RELAYED as "delivered" (no double-tick).
 *  - [DELIVERED]: the *recipient device itself* returned a signed ACK
 *    envelope addressed to us naming this message's ID. Only this state may
 *    be shown as delivered.
 *  - [EXPIRED]: the envelope's expiry passed while still undelivered; it was
 *    purged from the outbox and will never be delivered by this node.
 *  - [FAILED]: retry policy exhausted / a fatal send error occurred.
 */
enum class DeliveryState {
    QUEUED,
    RELAYED,
    DELIVERED,
    EXPIRED,
    FAILED,
}
