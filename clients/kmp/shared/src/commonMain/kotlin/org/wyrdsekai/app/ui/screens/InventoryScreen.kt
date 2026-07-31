package org.wyrdsekai.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.wyrdsekai.app.i18n.LocalUiStrings
import org.wyrdsekai.app.viewmodel.RoomViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InventoryScreen(
    viewModel: RoomViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalUiStrings.current
    val inventory by viewModel.inventory.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(strings.inventory) },
                actions = {
                    TextButton(onClick = onClose, modifier = Modifier.testTag("inventory-close")) {
                        Text(strings.close)
                    }
                },
            )
        },
        modifier = modifier.testTag("inventory-screen"),
    ) { padding ->
        if (inventory.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = strings.inventoryEmpty,
                    modifier = Modifier.testTag("empty-inventory"),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(inventory, key = { it.id }) { item ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                        ) {
                            Text(
                                text = item.name,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            if (item.description.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = item.description,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                OutlinedButton(
                                    onClick = { viewModel.drop(item.name) },
                                ) {
                                    Text(strings.drop)
                                }
                                Button(
                                    onClick = { viewModel.use(item.name) },
                                ) {
                                    Text(strings.use)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
