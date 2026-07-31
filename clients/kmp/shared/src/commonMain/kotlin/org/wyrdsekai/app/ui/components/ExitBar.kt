package org.wyrdsekai.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.wyrdsekai.app.i18n.LocalUiStrings
import org.wyrdsekai.app.protocol.Exit

@Composable
fun ExitBar(
    exits: List<Exit>,
    onExitSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (exits.isEmpty()) return

    val strings = LocalUiStrings.current
    val exitsDescription = strings.navigationExitsTemplate.replace("%d", exits.size.toString())

    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = exitsDescription },
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(exits) { exit ->
            FilledTonalButton(
                onClick = { onExitSelected(exit.direction) },
                modifier = Modifier
                    .testTag("exit-${exit.direction}")
                    .semantics {
                        contentDescription = exit.label
                    },
            ) {
                Text(strings.directionLabels[exit.direction] ?: exit.direction.replaceFirstChar { it.uppercase() })
            }
        }
    }
}
