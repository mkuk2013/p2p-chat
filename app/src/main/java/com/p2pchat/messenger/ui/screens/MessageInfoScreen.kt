package com.p2pchat.messenger.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.p2pchat.messenger.data.repository.ChatRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Delivery detail for a single message, loaded from Room by ID.
 */
@Composable
fun MessageInfoScreen(
    messageId: String,
    chatRepository: ChatRepository,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val message by chatRepository.observeMessage(messageId)
        .collectAsState(initial = null)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Back") }
            Text(
                text = "Message details",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(12.dp))

        val current = message
        if (current == null) {
            Text(
                text = "Message not found. It may have been deleted, or its " +
                    "history was cleared.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@Column
        }

        InfoRow("Message ID", current.messageId, monospace = true)
        InfoRow("Delivery state", deliveryLabel(current.deliveryState))
        InfoRow("From", current.senderId, monospace = true)
        InfoRow("To", current.recipientId, monospace = true)
        InfoRow("Sent", formatFullTime(current.timestampMs))
        InfoRow("Expires", formatFullTime(current.expiryMs))
        InfoRow("Hops travelled", current.hopCount.toString())
        InfoRow("Direction", if (current.isMine) "Outgoing" else "Incoming")

        Spacer(Modifier.height(16.dp))
        Text(
            text = "What the states mean",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Queued — the message is stored on this phone and has not " +
                "been accepted by any other phone yet.\n\n" +
                "Relayed — a neighbouring phone accepted the encrypted envelope " +
                "for forwarding. That phone cannot read the message, and its " +
                "acceptance is NOT delivery.\n\n" +
                "Delivered — the recipient's own phone returned a signed " +
                "acknowledgement for this message ID. Only then is a message " +
                "marked delivered.\n\n" +
                "Expired — the expiry time passed while the message was still " +
                "undelivered, so it was dropped from the relay queue.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String, monospace: Boolean = false) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default
        )
    }
}

private fun formatFullTime(timestampMs: Long): String =
    if (timestampMs <= 0) "—" else {
        SimpleDateFormat("dd MMM yyyy, HH:mm:ss", Locale.getDefault()).format(Date(timestampMs))
    }
