package com.p2pchat.messenger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.p2pchat.messenger.ui.navigation.P2PNavGraph
import com.p2pchat.messenger.ui.theme.P2PChatTheme

/**
 * Single-activity host. All screens are Compose destinations in
 * [P2PNavGraph]; the shared [AppContainer] (identity, transport, router,
 * repositories) comes from [P2PApp].
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as P2PApp).container
        setContent {
            P2PChatTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    P2PNavGraph(container = container)
                }
            }
        }
    }
}
