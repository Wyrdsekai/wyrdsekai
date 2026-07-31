package org.wyrdsekai.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.wyrdsekai.app.network.DiscoverZones
import org.wyrdsekai.app.network.FindZoneViewModel
import org.wyrdsekai.app.network.NatsServerClient
import org.wyrdsekai.app.network.ZoneBank

/**
 * FindZoneScreen — "Find a zone" (/P5), Android parity
 * with the RN FindZoneScreen. Search the opt-in directory over the connected
 * [client]; on a result, "Request access" knocks on the target zone's steward —
 * a REAL access request the steward reviews out-of-band, not theater.
 *
 * Thin shell over [FindZoneViewModel]: it injects DiscoverZones.discover and
 * NatsServerClient.requestAccess as the live effects, so the screen stays a
 * render-and-dispatch layer.
 */
@Composable
fun FindZoneScreen(
    client: NatsServerClient,
    bank: ZoneBank,
    scope: CoroutineScope,
    requesterName: () -> String = { "a wyrdsekai user" },
    onBack: () -> Unit,
) {
    val vm = remember {
        FindZoneViewModel(
            discover = { query, limit -> DiscoverZones.discover(client, bank, query, limit) },
            requestAccessFn = { zoneLabel, name -> client.requestAccess(zoneLabel, name) != null },
            requesterName = requesterName,
        )
    }
    var query by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
            .testTag("find-zone-screen"),
    ) {
        Text(
            "Find a zone",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.testTag("find-zone-title"),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Search zones that list themselves. To join one, ask its steward for an " +
                "invite — public relays are never enumerated.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        Row(Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Name, tag: or capability:") },
                singleLine = true,
                modifier = Modifier.weight(1f).testTag("find-zone-query"),
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { scope.launch { vm.search(query.trim()) } },
                enabled = !vm.busy,
                modifier = Modifier.testTag("find-zone-search"),
            ) {
                if (vm.busy) CircularProgressIndicator(Modifier.height(18.dp).width(18.dp))
                else Text("Search")
            }
        }

        vm.error?.let { err ->
            Spacer(Modifier.height(8.dp))
            Text(
                err,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("find-zone-error"),
            )
        }

        if (vm.searched && !vm.busy && vm.error == null && vm.results.isEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text(
                "No listed zones found. Most zones are private — ask the steward for an invite.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("find-zone-empty"),
            )
        }

        for (zone in vm.results) {
            Spacer(Modifier.height(12.dp))
            Card(modifier = Modifier.fillMaxWidth().testTag("find-zone-card-${zone.zoneLabel}")) {
                Column(Modifier.padding(16.dp)) {
                    Text(zone.displayName ?: zone.zoneLabel, style = MaterialTheme.typography.titleMedium)
                    zone.tagline?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (zone.tags.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            zone.tags.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    when {
                        zone.inBank -> Text(
                            "Already in your servers.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        vm.knockState[zone.zoneLabel] == "sent" -> Text(
                            "Request sent — the steward will review it and send you an invite.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.testTag("find-zone-sent-${zone.zoneLabel}"),
                        )
                        else -> {
                            val asking = vm.knockState[zone.zoneLabel] == "asking"
                            Button(
                                onClick = { scope.launch { vm.requestAccess(zone) } },
                                enabled = !asking,
                                modifier = Modifier.testTag("find-zone-knock-${zone.zoneLabel}"),
                            ) {
                                if (asking) CircularProgressIndicator(Modifier.height(18.dp).width(18.dp))
                                else Text("Request access")
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        TextButton(onClick = onBack, modifier = Modifier.testTag("find-zone-back")) {
            Text("Back to your servers")
        }
    }
}
