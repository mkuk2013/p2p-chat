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
import com.p2pchat.messenger.ui.screens.WifiCallOverlay
import com.p2pchat.messenger.ui.screens.ChatScreen
import com.p2pchat.messenger.ui.screens.DeviceChatScreen
import com.p2pchat.messenger.ui.screens.HomeShellScreen
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
    const val DEVICE_CHAT = "deviceChat/{address}"
    const val PEERS = "peers"
    const val MESH = "mesh"
    const val MESSAGE_INFO = "messageInfo/{messageId}"
    const val PROFILE = "profile"
    const val SETTINGS = "settings"

    fun chat(peerId: String): String = "chat/$peerId"

    /** First-contact chat keyed by BLE address (the peerId does not
     * exist until the identity exchange completes). The address is
     * URI-encoded (it contains ':' separators). */
    fun deviceChat(address: String): String =
        "deviceChat/${android.net.Uri.encode(address)}"
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
                HomeShellScreen(
                    container = container,
                    chatRepository = chatRepository,
                    onOpenChat = { peerId -> navController.navigate(Routes.chat(peerId)) },
                    onOpenDeviceChat = { address ->
                        navController.navigate(Routes.deviceChat(address))
                    },
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

        composable(
            route = Routes.DEVICE_CHAT,
            arguments = listOf(navArgument("address") { type = NavType.StringType })
        ) { backStackEntry ->
            val address = backStackEntry.arguments?.getString("address").orEmpty()
            val chatRepository = container.chatRepository
            if (chatRepository == null) {
                NeedsIdentity { navController.navigate(Routes.ONBOARDING) }
            } else {
                DeviceChatScreen(
                    address = address,
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
                onOpenDeviceChat = { address ->
                    navController.navigate(Routes.deviceChat(address))
                },
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
                onBack = { navController.popBackStack() },
                onOpenMesh = { navController.navigate(Routes.MESH) }
            )
        }
    }

    // Voice-call overlay (experimental Nearby direct-link calls) floats
    // above whichever screen is showing when a call starts or arrives.
    CallOverlay(container = container)
    WifiCallOverlay(container = container)

    // Call notices (v1.3.1): a call tap must never be silent again —
    // its outcome is a full dialog here at the root, with a route to
    // the app settings when a permission is stuck off.
    val callNotice by container.callNotice.collectAsState()
    if (callNotice != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { container.dismissCallNotice() },
            title = { Text("Notice") },
            text = { Text(callNotice!!) },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = { container.dismissCallNotice() }
                ) { Text("OK") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        container.dismissCallNotice()
                        val intent = android.content.Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:" + context.packageName)
                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { context.startActivity(intent) }
                    }
                ) { Text("Open settings") }
            }
        )
    }
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
            text = "Getting things ready… if this stays, go back to setup."
        )
    }
    androidx.compose.runtime.LaunchedEffect(Unit) { onGoToSetup() }
}
