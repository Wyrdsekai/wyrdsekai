package org.wyrdsekai.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.wyrdsekai.app.i18n.LocalUiStrings
import org.wyrdsekai.app.i18n.UiStrings
import org.wyrdsekai.app.inference.ModelCatalog
import org.wyrdsekai.app.inference.ModelInfo
import org.wyrdsekai.app.viewmodel.InferenceViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelDownloadScreen(
    inferenceViewModel: InferenceViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalUiStrings.current
    val downloadProgress by inferenceViewModel.downloadProgress.collectAsState()
    val downloadedModels by inferenceViewModel.downloadedModels.collectAsState()
    val activeModelId by inferenceViewModel.activeModelId.collectAsState()
    val serverState by inferenceViewModel.serverState.collectAsState()
    val modelLoading by inferenceViewModel.modelLoading.collectAsState()
    val smokeTestResult by inferenceViewModel.smokeTestResult.collectAsState()
    val smokeTestRunning by inferenceViewModel.smokeTestRunning.collectAsState()
    val serverError by inferenceViewModel.serverError.collectAsState()

    LaunchedEffect(Unit) {
        inferenceViewModel.refreshDownloadedModels()
    }

    // Smoke test result dialog
    if (smokeTestResult != null) {
        AlertDialog(
            onDismissRequest = { inferenceViewModel.clearSmokeTestResult() },
            title = { Text(strings.inferenceTest) },
            text = {
                Text(
                    text = smokeTestResult ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = { inferenceViewModel.clearSmokeTestResult() }) {
                    Text(strings.ok)
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(strings.models) },
                actions = {
                    TextButton(onClick = onClose) {
                        Text(strings.close)
                    }
                },
            )
        },
        modifier = modifier.testTag("model-download-screen"),
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Error banner
            if (serverError != null) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                    ) {
                        Text(
                            text = serverError ?: "",
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            items(ModelCatalog.models) { model ->
                ModelCard(
                    model = model,
                    isDownloaded = downloadedModels.any { it.id == model.id },
                    isActive = activeModelId == model.id,
                    isLoading = modelLoading && activeModelId == null,
                    downloadProgress = downloadProgress[model.id],
                    serverState = serverState,
                    smokeTestRunning = smokeTestRunning,
                    strings = strings,
                    onDownload = { inferenceViewModel.downloadModel(model.id) },
                    onLoad = { inferenceViewModel.loadModel(model.id) },
                    onDelete = { inferenceViewModel.deleteModel(model.id) },
                    onUnload = { inferenceViewModel.unloadModel() },
                    onSmokeTest = { inferenceViewModel.runSmokeTest() },
                )
            }
        }
    }
}

@Composable
private fun ModelCard(
    model: ModelInfo,
    isDownloaded: Boolean,
    isActive: Boolean,
    isLoading: Boolean,
    downloadProgress: Float?,
    serverState: String,
    smokeTestRunning: Boolean = false,
    strings: UiStrings,
    onDownload: () -> Unit,
    onLoad: () -> Unit,
    onDelete: () -> Unit,
    onUnload: () -> Unit,
    onSmokeTest: () -> Unit = {},
) {
    val tierColor = when (model.tier) {
        "tiny" -> MaterialTheme.colorScheme.primary
        "small" -> MaterialTheme.colorScheme.secondary
        "medium" -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.outline
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = model.name,
                    style = MaterialTheme.typography.titleSmall,
                )
                SuggestionChip(
                    onClick = {},
                    label = {
                        Text(
                            text = model.tier.replaceFirstChar { it.uppercaseChar() },
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        containerColor = tierColor.copy(alpha = 0.12f),
                        labelColor = tierColor,
                    ),
                )
            }

            Text(
                text = model.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                text = formatSize(model.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )

            // Download progress
            if (downloadProgress != null) {
                LinearProgressIndicator(
                    progress = { downloadProgress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "${(downloadProgress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                )
            }

            // Action buttons
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (isActive) {
                    FilledTonalButton(onClick = onUnload) {
                        Text(strings.unload)
                    }
                    if (serverState == "running") {
                        Button(
                            onClick = onSmokeTest,
                            enabled = !smokeTestRunning,
                        ) {
                            Text(if (smokeTestRunning) strings.testing else strings.test)
                        }
                        SuggestionChip(
                            onClick = {},
                            label = { Text(strings.active) },
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                        )
                    }
                } else if (isDownloaded) {
                    Button(
                        onClick = onLoad,
                        enabled = !isLoading,
                    ) {
                        Text(strings.load)
                    }
                    OutlinedButton(
                        onClick = onDelete,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Text(strings.delete)
                    }
                } else if (downloadProgress == null) {
                    Button(onClick = onDownload) {
                        Text(strings.download)
                    }
                }
            }
        }
    }
}

private fun formatSize(bytes: Long): String {
    return when {
        bytes >= 1_000_000_000 -> {
            val gb = bytes / 100_000_000
            "${gb / 10}.${gb % 10} GB"
        }
        bytes >= 1_000_000 -> "${bytes / 1_000_000} MB"
        else -> "${bytes / 1_000} KB"
    }
}
