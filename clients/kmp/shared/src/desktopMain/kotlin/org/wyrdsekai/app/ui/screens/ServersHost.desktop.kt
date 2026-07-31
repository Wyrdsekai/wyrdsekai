package org.wyrdsekai.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope

/** Desktop placeholder — the "Your servers" surface is a phone feature. */
actual val zoneBankSurfaceSupported: Boolean = false

@Composable
actual fun ServersHost(scope: CoroutineScope, onExit: () -> Unit, onEnterLocal: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text("Your servers", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        Text(
            "The zone bank is a phone feature.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onExit) { Text("Back") }
    }
}
