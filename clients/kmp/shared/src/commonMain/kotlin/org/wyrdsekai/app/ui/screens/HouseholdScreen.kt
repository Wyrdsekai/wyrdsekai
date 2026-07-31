package org.wyrdsekai.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.wyrdsekai.app.engine.between.PresenceState
import org.wyrdsekai.app.engine.discovery.ConnectivityState
import org.wyrdsekai.app.viewmodel.HouseholdViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HouseholdScreen(
    viewModel: HouseholdViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val connectivityState by viewModel.connectivityState.collectAsState()
    val householdId by viewModel.householdId.collectAsState()
    val connectedNodes by viewModel.connectedNodes.collectAsState()
    val error by viewModel.error.collectAsState()
    val isConnecting by viewModel.isConnecting.collectAsState()
    val relayUrl by viewModel.relayUrl.collectAsState()
    val householdUrl by viewModel.householdUrl.collectAsState()
    val autoDiscover by viewModel.autoDiscover.collectAsState()

    // Refresh node list periodically while connected
    LaunchedEffect(connectivityState) {
        if (connectivityState == ConnectivityState.CONNECTED_LAN ||
            connectivityState == ConnectivityState.CONNECTED_RELAY
        ) {
            viewModel.refreshNodes()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Household") },
                actions = {
                    TextButton(onClick = onClose) {
                        Text("Close")
                    }
                },
            )
        },
        modifier = modifier.testTag("household-screen"),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // --- Connection Status Section ---
            Text(
                text = "Connection Status",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Status dot
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(connectivityState.dotColor())
                        .testTag("household-status-dot"),
                )

                Column {
                    Text(
                        text = connectivityState.displayText(),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    if (householdId != null) {
                        Text(
                            text = "Household: $householdId",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            error?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // Connect / Disconnect button
            val isConnected = connectivityState == ConnectivityState.CONNECTED_LAN ||
                connectivityState == ConnectivityState.CONNECTED_RELAY
            Button(
                onClick = {
                    if (isConnected) viewModel.disconnect() else viewModel.connect()
                },
                enabled = !isConnecting,
                colors = if (isConnected) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    )
                } else {
                    ButtonDefaults.buttonColors()
                },
                modifier = Modifier.fillMaxWidth().testTag("household-connect-button"),
            ) {
                if (isConnecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Connecting...")
                } else {
                    Text(if (isConnected) "Disconnect" else "Connect")
                }
            }

            HorizontalDivider()

            // --- Connected Nodes Section ---
            if (isConnected) {
                Text(
                    text = "Connected Nodes",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )

                if (connectedNodes.isEmpty()) {
                    Text(
                        text = "No other nodes detected",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f, fill = false),
                    ) {
                        items(connectedNodes, key = { it.nodeId }) { node ->
                            NodePresenceCard(node)
                        }
                    }
                }

                HorizontalDivider()
            }

            // --- Settings Section ---
            Text(
                text = "Settings",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )

            OutlinedTextField(
                value = householdUrl,
                onValueChange = viewModel::setSavedHouseholdUrl,
                label = { Text("Household URL") },
                placeholder = { Text("ws://198.51.100.100:9222") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = relayUrl,
                onValueChange = viewModel::setRelayUrl,
                label = { Text("Relay URL") },
                placeholder = { Text("wss://relay.wyrdsekai.org:9222") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Auto-discover (mDNS)")
                Switch(
                    checked = autoDiscover,
                    onCheckedChange = viewModel::setAutoDiscover,
                )
            }

            Text(
                text = "Auto-discover scans for household servers on your local network. " +
                    "Set a Relay URL to connect when away from home.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NodePresenceCard(node: PresenceState) {
    val isOnline = node.status == "online"

    Card(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Online indicator dot
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(
                        if (isOnline) Color(0xFF4CAF50) else Color(0xFF9E9E9E)
                    ),
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = node.nodeId,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = node.status.replaceFirstChar { it.uppercaseChar() },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (node.tier != null) {
                SuggestionChip(
                    onClick = {},
                    label = {
                        Text(
                            text = node.tier,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                )
            }
        }
    }
}

/**
 * Returns the dot color for the given connectivity state.
 */
@Composable
internal fun ConnectivityState.dotColor(): Color = when (this) {
    ConnectivityState.CONNECTED_LAN -> Color(0xFF4CAF50)     // Green
    ConnectivityState.CONNECTED_RELAY -> Color(0xFF2196F3)   // Blue
    ConnectivityState.DISCOVERING -> Color(0xFFFFC107)       // Yellow/amber
    ConnectivityState.RECONNECTING -> Color(0xFFFFC107)      // Yellow/amber
    ConnectivityState.OFFLINE -> MaterialTheme.colorScheme.error
}

/**
 * Returns human-readable status text for the given connectivity state.
 */
private fun ConnectivityState.displayText(): String = when (this) {
    ConnectivityState.CONNECTED_LAN -> "Connected via LAN"
    ConnectivityState.CONNECTED_RELAY -> "Connected via Relay"
    ConnectivityState.DISCOVERING -> "Searching..."
    ConnectivityState.RECONNECTING -> "Reconnecting..."
    ConnectivityState.OFFLINE -> "Offline"
}
