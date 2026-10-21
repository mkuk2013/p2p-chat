package com.p2pchat.messenger.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.p2pchat.messenger.R

/**
 * Own profile. The header shows the official P2P Chat logo
 * (drawable-nodpi/logo_splash.png, the transparent 512px branding asset).
 *
 * Values are passed in from the app container / identity store by the
 * NavHost — this screen renders only real local identity data, nothing
 * fabricated. [fingerprintHex] is the hex fingerprint of the local
 * Ed25519 public key, shown for out-of-band identity verification.
 */
@Composable
fun ProfileScreen(
    peerId: String,
    displayName: String,
    fingerprintHex: String,
    onDisplayNameChanged: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var editedName by remember(displayName) { mutableStateOf(displayName) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ---- Header with official logo ----
        Image(
            painter = painterResource(id = R.drawable.logo_splash),
            contentDescription = "P2P Chat logo",
            modifier = Modifier.size(96.dp)
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "P2P Chat",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "Connect. Relay. Chat.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary
        )

        Spacer(Modifier.height(28.dp))

        OutlinedTextField(
            value = editedName,
            onValueChange = { editedName = it },
            label = { Text("Display name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = { onDisplayNameChanged(editedName.trim()) },
            enabled = editedName.isNotBlank() && editedName.trim() != displayName,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Save display name")
        }

        Spacer(Modifier.height(24.dp))

        Text(
            text = "Your Peer ID",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = peerId,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Public key fingerprint (Ed25519, hex)",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = fingerprintHex,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = {
                val clipboard =
                    context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(
                    ClipData.newPlainText("P2P Chat Peer ID", peerId)
                )
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Copy Peer ID")
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Share your Peer ID and compare fingerprints in person before " +
                "trusting a contact — relays can forward messages but cannot read them.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
