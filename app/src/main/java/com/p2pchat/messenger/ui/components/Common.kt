package com.p2pchat.messenger.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Deterministic avatar palette (muted, readable on the navy theme). */
private val AvatarPalette = listOf(
    Color(0xFF2E7CB6), Color(0xFF5B5BD6), Color(0xFF7C4DBE), Color(0xFF0E7C66),
    Color(0xFFB65D2E), Color(0xFF8C3A54), Color(0xFF3E6B35), Color(0xFF546E7A),
)

fun avatarColor(peerId: String): Color =
    AvatarPalette[(peerId.hashCode() and Int.MAX_VALUE) % AvatarPalette.size]

/** Circular avatar: the peer's initial on a colour derived from peerId. */
@Composable
fun PeerAvatar(name: String, peerId: String, size: Dp = 48.dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(avatarColor(peerId)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?",
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.titleMedium
        )
    }
}

/**
 * WhatsApp-style delivery ticks. The mesh protocol has no read receipts,
 * so there is deliberately NO "read" state:
 *  QUEUED   → small clock (spinner)
 *  RELAYED  → single check (a neighbour/mesh accepted it — not delivered)
 *  DELIVERED→ double check in the azure accent (recipient's own ACK)
 *  EXPIRED/FAILED → red cross
 */
@Composable
fun DeliveryTicks(state: String, modifier: Modifier = Modifier, size: Dp = 15.dp) {
    when (state) {
        "QUEUED" -> CircularProgressIndicator(
            modifier = modifier.size(size - 3.dp),
            strokeWidth = 1.5.dp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        "RELAYED" -> Icon(
            imageVector = Icons.Filled.Done,
            contentDescription = "Relayed",
            modifier = modifier.size(size),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        "DELIVERED" -> Box(modifier = modifier.size(size + 7.dp, size)) {
            Icon(
                imageVector = Icons.Filled.Done,
                contentDescription = "Delivered",
                modifier = Modifier
                    .size(size)
                    .align(Alignment.CenterStart),
                tint = MaterialTheme.colorScheme.primary
            )
            Icon(
                imageVector = Icons.Filled.Done,
                contentDescription = null,
                modifier = Modifier
                    .size(size)
                    .align(Alignment.CenterStart)
                    .offset(x = 7.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        "EXPIRED", "FAILED" -> Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = "Not delivered",
            modifier = modifier.size(size),
            tint = MaterialTheme.colorScheme.error
        )
        else -> Unit
    }
}

/** Microphone glyph drawn with Canvas (not in the bundled icon set). */
@Composable
fun MicIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.36f, h * 0.06f),
            size = Size(w * 0.28f, h * 0.50f),
            cornerRadius = CornerRadius(w * 0.14f, w * 0.14f)
        )
        drawArc(
            color = tint,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(w * 0.21f, h * 0.28f),
            size = Size(w * 0.58f, h * 0.46f),
            style = Stroke(width = w * 0.07f)
        )
        drawLine(
            color = tint,
            start = Offset(w * 0.5f, h * 0.74f),
            end = Offset(w * 0.5f, h * 0.88f),
            strokeWidth = w * 0.07f
        )
        drawLine(
            color = tint,
            start = Offset(w * 0.33f, h * 0.90f),
            end = Offset(w * 0.67f, h * 0.90f),
            strokeWidth = w * 0.07f
        )
    }
}

/** Pause glyph (two bars) drawn with Canvas. */
@Composable
fun PauseIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val barW = w * 0.26f
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.14f, h * 0.16f),
            size = Size(barW, h * 0.68f),
            cornerRadius = CornerRadius(barW / 2, barW / 2)
        )
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.60f, h * 0.16f),
            size = Size(barW, h * 0.68f),
            cornerRadius = CornerRadius(barW / 2, barW / 2)
        )
    }
}

/** Chat-bubble glyph for the New Chat FAB, drawn with Canvas. */
@Composable
fun ChatBubbleIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.10f, h * 0.12f),
            size = Size(w * 0.80f, h * 0.58f),
            cornerRadius = CornerRadius(w * 0.16f, w * 0.16f)
        )
        val tail = Path().apply {
            moveTo(w * 0.30f, h * 0.68f)
            lineTo(w * 0.30f, h * 0.90f)
            lineTo(w * 0.52f, h * 0.68f)
            close()
        }
        drawPath(tail, tint)
        // Three dots "typing" cut-outs in the bubble.
        val dotR = w * 0.055f
        listOf(0.32f, 0.50f, 0.68f).forEach { fx ->
            drawCircle(
                color = Color(0xFF0B0F1C),
                radius = dotR,
                center = Offset(w * fx, h * 0.41f)
            )
        }
    }
}

/** Speaker glyph drawn with Canvas (for the call screen). */
@Composable
fun SpeakerIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val body = Path().apply {
            moveTo(w * 0.12f, h * 0.38f)
            lineTo(w * 0.34f, h * 0.38f)
            lineTo(w * 0.56f, h * 0.18f)
            lineTo(w * 0.56f, h * 0.82f)
            lineTo(w * 0.34f, h * 0.62f)
            lineTo(w * 0.12f, h * 0.62f)
            close()
        }
        drawPath(body, tint)
        drawArc(
            color = tint,
            startAngle = -50f,
            sweepAngle = 100f,
            useCenter = false,
            topLeft = Offset(w * 0.52f, h * 0.26f),
            size = Size(w * 0.36f, h * 0.48f),
            style = Stroke(width = w * 0.07f)
        )
        drawArc(
            color = tint,
            startAngle = -50f,
            sweepAngle = 100f,
            useCenter = false,
            topLeft = Offset(w * 0.60f, h * 0.16f),
            size = Size(w * 0.34f, h * 0.68f),
            style = Stroke(width = w * 0.07f)
        )
    }
}

// ------------------------------------------------------------- formatting

fun formatChatListTime(timestampMs: Long): String {
    if (timestampMs <= 0) return ""
    val then = Calendar.getInstance().apply { timeInMillis = timestampMs }
    val now = Calendar.getInstance()
    val pattern = when {
        isSameDay(then, now) -> "HH:mm"
        isYesterday(then, now) -> "'Yesterday'"
        then.get(Calendar.YEAR) == now.get(Calendar.YEAR) -> "dd MMM"
        else -> "dd/MM/yy"
    }
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(timestampMs))
}

fun formatBubbleTime(timestampMs: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestampMs))

/** "Today" / "Yesterday" / "12 Oct 2026" for date separators. */
fun formatDaySeparator(timestampMs: Long): String {
    val then = Calendar.getInstance().apply { timeInMillis = timestampMs }
    val now = Calendar.getInstance()
    return when {
        isSameDay(then, now) -> "Today"
        isYesterday(then, now) -> "Yesterday"
        else -> SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(timestampMs))
    }
}

fun isSameDay(a: Calendar, b: Calendar): Boolean =
    a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
        a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

fun isYesterday(then: Calendar, now: Calendar): Boolean {
    val y = now.clone() as Calendar
    y.add(Calendar.DAY_OF_YEAR, -1)
    return isSameDay(then, y)
}

/** m:ss for durations (voice notes, calls). */
fun formatDuration(ms: Long): String {
    val totalSec = (ms / 1000).toInt()
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}
