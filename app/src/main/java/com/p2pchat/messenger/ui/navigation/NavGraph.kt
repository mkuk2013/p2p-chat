package com.p2pchat.messenger.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.p2pchat.messenger.AppContainer
import com.p2pchat.messenger.ble.BlePermissions
import com.p2pchat.messenger.ui.screens.CallOverlay
import com.p2pchat.messenger.ui.screens.ChatScreen
import com.p2pchat.messenger.ui.screens.HomeScreen
import com.p2pchat.messenger.ui.screens.MeshStatusScreen
import com.p2pchat.messenger.ui.screens.MessageInfoScreen
import com.p2pchat.messenger.ui.screens.OnboardingScreen
import com.p2pchat.messenger.ui.screens.PeersScreen
import com.p2pchat.messenger.ui.screens.ProfileScreen
import com.p2pchat.messenger.ui.screens.SettingsScreen
import com.p2pchat.messenger.ui.screens.SplashScreen
import kotlinx.coroutines.launch

/** Route table for the whole app. */
object Routes {
    const val SPLASH = "splash"
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val CHAT = "chat/{peerId}"
    const val PEERS = "peers"
    const val MESH = "mesh"
    const val MESSAGE_INFO = "messageInfo/{messageId}"
    const val PROFILE = "profile"
    const val SETTINGS = "settings"

    fun chat(peerId: String): String = "chat/$peerId"
    fun messageInfo(messageId: String): String = "messageInfo/$messageId"
}

/**
 * App navigation graph.
 *
 * Start: splash → (identity missing OR permissions missing OR Bluetooth
 * off) onboarding → home. Home and everything below it require an identity,
 * because the repositories/router are only wired once one exists; when a
 * destination is reached without one (e.g. after Clear-data), the user is
 * bounced back to onboarding instead of crashing.
 */
@Composable
fun P2PNavGraph(container: AppContainer) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val identity by container.identity.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
    NavHost(
        navController = navController,
        startDestination = Routes.SPLASH
    ) {
        composable(Routes.SPLASH) {
            SplashScreen(
                onFinished = {
                    val needsOnboarding = identity == null ||
                        !BlePermissions.hasAll(context) ||
                        !container.isBluetoothEnabled()
                    navController.navigate(
                        if (needsOnboarding) Routes.ONBOARDING else Routes.HOME
                    ) {
                        popUpTo(Routes.SPLASH) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                hasIdentity = identity != null,
                onCreateIdentity = { name ->
                    scope.launch { container.ensureIdentity(name) }
                },
                onReady = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.HOME) {
            val chatRepository = container.chatRepository
            if (chatRepository == null) {
                NeedsIdentity { navController.navigate(Routes.ONBOARDING) }
            } else {
                HomeScreen(
                    chatRepository = chatRepository,
                    onOpenChat = { peerId -> navController.navigate(Routes.chat(peerId)) },
                    onOpenPeers = { navController.navigate(Routes.PEERS) },
                    onOpenMesh = { navController.navigate(Routes.MESH) },
                    onOpenProfile = { navController.navigate(Routes.PROFILE) },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) }
                )
            }
        }

        composable(
            route = Routes.CHAT,
            arguments = listOf(navArgument("peerId") { type = NavType.StringType })
        ) { backStackEntry ->
            val peerId = backStackEntry.arguments?.getString("peerId").orEmpty()
            val chatRepository = container.chatRepository
            if (chatRepository == null) {
                NeedsIdentity { navController.navigate(Routes.ONBOARDING) }
            } else {
                ChatScreen(
                    peerId = peerId,
                    container = container,
                    chatRepository = chatRepository,
                    peerRepository = container.peerRepository,
                    onMessageInfo = { messageId ->
                        navController.navigate(Routes.messageInfo(messageId))
                    },
                    onBack = { navController.popBackStack() }
                )
            }
        }

        composable(Routes.PEERS) {
            PeersScreen(
                container = container,
                bleTransport = container.bleTransport,
                chatRepository = container.chatRepository,
                peerRepository = container.peerRepository,
                onOpenChat = { peerId -> navController.navigate(Routes.chat(peerId)) },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.MESH) {
            MeshStatusScreen(
                container = container,
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Routes.MESSAGE_INFO,
            arguments = listOf(navArgument("messageId") { type = NavType.StringType })
        ) { backStackEntry ->
            val messageId = backStackEntry.arguments?.getString("messageId").orEmpty()
            val chatRepository = container.chatRepository
            if (chatRepository == null) {
                NeedsIdentity { navController.navigate(Routes.ONBOARDING) }
            } else {
                MessageInfoScreen(
                    messageId = messageId,
                    chatRepository = chatRepository,
                    onBack = { navController.popBackStack() }
                )
            }
        }

        composable(Routes.PROFILE) {
            val current = identity
            if (current == null) {
                NeedsIdentity { navController.navigate(Routes.ONBOARDING) }
            } else {
                ProfileScreen(
                    peerId = current.peerId,
                    displayName = current.displayName,
                    fingerprintHex = container.fingerprintHex(current),
                    onDisplayNameChanged = { container.updateDisplayName(it) }
                )
            }
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                container = container,
                onBack = { navController.popBackStack() }
            )
        }
    }

    // Voice-call overlay (experimental Nearby direct-link calls) floats
    // above whichever screen is showing when a call starts or arrives.
    CallOverlay(container = container)
    }
}

/** Shown when a destination needs an identity that does not exist (yet). */
@Composable
private fun NeedsIdentity(onGoToSetup: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Setting up your identity… if this stays, go back to setup."
        )
    }
    androidx.compose.runtime.LaunchedEffect(Unit) { onGoToSetup() }
}
