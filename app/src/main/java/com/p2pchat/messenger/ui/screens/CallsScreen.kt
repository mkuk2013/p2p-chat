package com.p2pchat.messenger.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.p2pchat.messenger.AppContainer
import com.p2pchat.messenger.data.entity.CallRecordEntity
import com.p2pchat.messenger.data.repository.ChatRepository
import com.p2pchat.messenger.ui.components.CallArrowIcon
import com.p2pchat.messenger.ui.components.PeerAvatar
import com.p2pchat.messenger.ui.components.formatChatListTime
import com.p2pchat.messenger.ui.components.formatDuration
import kotlinx.coroutines.launch

/**
 * The Calls tab: WhatsApp-style call history, straight from the
 * call_records table — one row per real call attempt on this phone,
 * newest first. Tapping a row opens that chat. No calls yet is an
 * honest empty state; nothing is ever invented here.
 */
@Composable
fun CallsScreen(
    container: AppContainer,
    chatRepository: ChatRepository,
    onOpenChat: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val calls by container.callHistoryRepository.observeCalls()
        .collectAsState(initial = emptyList())

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Column(modifier = Modifier.padding(top = 8.dp)) {
            Text(
                text = "Calls",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Voice calls you made or received on this phone",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(8.dp))

        if (calls.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No calls yet.\n\nOpen a chat and tap the call " +
                        "button to make your first nearby call — it works " +
                        "without internet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(calls, key = { it.id }) { record ->
                    CallRow(
                        record = record,
                        onClick = {
                            scope.launch {
                                chatRepository.ensureConversation(
                                    record.peerId,
                                    record.peerName
                                )
                                onOpenChat(record.peerId)
                            }
                        }
                    )
                }
            }
            Text(
                text = "These calls went phone-to-phone nearby — no " +
                    "internet, no SIM, no phone number.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 10.dp)
            )
        }
    }
}

@Composable
private fun CallRow(record: CallRecordEntity, onClick: () -> Unit) {
    val incoming = record.direction == CallRecordEntity.DIRECTION_INCOMING
    val answered = record.outcome == CallRecordEntity.OUTCOME_ANSWERED
    val missedIncoming = incoming &&
        record.outcome == CallRecordEntity.OUTCOME_MISSED

    val iconTint = when {
        answered -> MaterialTheme.colorScheme.primary
        missedIncoming || record.outcome == CallRecordEntity.OUTCOME_FAILED ->
            MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val detail = buildString {
        append(
            record.reason ?: when (record.outcome) {
                CallRecordEntity.OUTCOME_ANSWERED -> "Answered"
                CallRecordEntity.OUTCOME_MISSED -> "Missed"
                CallRecordEntity.OUTCOME_REJECTED -> "Declined"
                else -> "Couldn't connect"
            }
        )
        if (answered && record.durationMs > 0) {
            append(" · ")
            append(formatDuration(record.durationMs))
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PeerAvatar(name = record.peerName, peerId = record.peerId, size = 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = record.peerName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CallArrowIcon(
                    tint = iconTint,
                    incoming = incoming,
                    missed = missedIncoming,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
        Text(
            text = formatChatListTime(record.startedAtMs),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
