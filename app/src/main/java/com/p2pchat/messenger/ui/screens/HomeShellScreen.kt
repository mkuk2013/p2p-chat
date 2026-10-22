package com.p2pchat.messenger.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.p2pchat.messenger.AppContainer
import com.p2pchat.messenger.data.repository.ChatRepository
import com.p2pchat.messenger.ui.components.ChatBubbleIcon
import com.p2pchat.messenger.ui.components.PeopleIcon

/**
 * The home shell: WhatsApp-style bottom tabs — Chats | People | Calls.
 *
 * Chats is the conversation list (its top bar keeps the overflow menu:
 * Connection details, My profile, Settings; its round button jumps to
 * the People tab). People is the nearby-people list, embedded. Calls is
 * the honest call history — only calls that really happened on this
 * phone.
 */
@Composable
fun HomeShellScreen(
    container: AppContainer,
    chatRepository: ChatRepository,
    onOpenChat: (String) -> Unit,
    onOpenDeviceChat: (String) -> Unit,
    onOpenMesh: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var tabIndex by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tabIndex == 0,
                    onClick = { tabIndex = 0 },
                    icon = {
                        ChatBubbleIcon(
                            tint = LocalContentColor.current,
                            modifier = Modifier.size(24.dp)
                        )
                    },
                    label = { Text("Chats") }
                )
                NavigationBarItem(
                    selected = tabIndex == 1,
                    onClick = { tabIndex = 1 },
                    icon = {
                        PeopleIcon(
                            tint = LocalContentColor.current,
                            modifier = Modifier.size(24.dp)
                        )
                    },
                    label = { Text("People") }
                )
                NavigationBarItem(
                    selected = tabIndex == 2,
                    onClick = { tabIndex = 2 },
                    icon = { Icon(Icons.Filled.Call, contentDescription = null) },
                    label = { Text("Calls") }
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (tabIndex) {
                0 -> HomeScreen(
                    chatRepository = chatRepository,
                    onOpenChat = onOpenChat,
                    onOpenPeers = { tabIndex = 1 },
                    onOpenMesh = onOpenMesh,
                    onOpenProfile = onOpenProfile,
                    onOpenSettings = onOpenSettings
                )
                1 -> PeersScreen(
                    container = container,
                    bleTransport = container.bleTransport,
                    chatRepository = chatRepository,
                    peerRepository = container.peerRepository,
                    onOpenChat = onOpenChat,
                    onOpenDeviceChat = onOpenDeviceChat,
                    onBack = { tabIndex = 0 },
                    embedded = true
                )
                else -> CallsScreen(
                    container = container,
                    chatRepository = chatRepository,
                    onOpenChat = onOpenChat
                )
            }
        }
    }
}
