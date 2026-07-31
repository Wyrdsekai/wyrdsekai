package org.wyrdsekai.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.wyrdsekai.app.engine.study.StudyItem

/**
 * Bottom sheet for resolving Study sync conflicts.
 *
 * Shows the local version vs the remote version side-by-side,
 * with options: Keep Mine, Keep Theirs, Keep Both.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConflictResolutionSheet(
    conflicts: List<ConflictPair>,
    onResolve: (itemId: String, resolution: ConflictResolution) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (conflicts.isEmpty()) return

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier.testTag("conflict-sheet"),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "${conflicts.size} sync conflicts",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.weight(1f, fill = false),
            ) {
                items(conflicts, key = { it.local.id }) { pair ->
                    ConflictCard(
                        pair = pair,
                        onResolve = { resolution -> onResolve(pair.local.id, resolution) },
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ConflictCard(
    pair: ConflictPair,
    onResolve: (ConflictResolution) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                pair.local.title.ifEmpty { pair.local.content.take(60) },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(8.dp))

            // Local version
            VersionPreview(
                label = "Mine (${pair.local.lastModifiedBy.takeLast(8)})",
                content = pair.local.content,
            )
            Spacer(modifier = Modifier.height(4.dp))

            // Remote version
            VersionPreview(
                label = "Theirs (${pair.remote.lastModifiedBy.takeLast(8)})",
                content = pair.remote.content,
            )
            Spacer(modifier = Modifier.height(8.dp))

            // Resolution buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { onResolve(ConflictResolution.KEEP_MINE) },
                    modifier = Modifier.weight(1f).testTag("conflict-keep-mine"),
                ) { Text("Keep Mine") }
                OutlinedButton(
                    onClick = { onResolve(ConflictResolution.KEEP_THEIRS) },
                    modifier = Modifier.weight(1f).testTag("conflict-keep-theirs"),
                ) { Text("Keep Theirs") }
                FilledTonalButton(
                    onClick = { onResolve(ConflictResolution.KEEP_BOTH) },
                    modifier = Modifier.weight(1f).testTag("conflict-keep-both"),
                ) { Text("Keep Both") }
            }
        }
    }
}

@Composable
private fun VersionPreview(label: String, content: String) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            content.take(200),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

data class ConflictPair(
    val local: StudyItem,
    val remote: StudyItem,
)

enum class ConflictResolution {
    KEEP_MINE,
    KEEP_THEIRS,
    KEEP_BOTH,
}
