package com.p2pchat.messenger.core.util

fun nowMs(): Long = System.currentTimeMillis()

fun isExpired(expiryMs: Long, nowMs: Long = nowMs()): Boolean = nowMs > expiryMs
