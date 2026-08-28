package org.wyrdsekai.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import org.wyrdsekai.app.protocol.PriorityLevel
import org.wyrdsekai.app.rendering.ContentBlockRegistry
import org.wyrdsekai.app.state.ProseEntry

@Composable
fun ProseStream(
    entries: List<ProseEntry>,
    streamingText: Map<String, String>,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    // Auto-scroll to bottom on new entries
    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) {
            listState.animateScrollToItem(entries.size - 1)
        }
    }

    // Long-press to select and copy. A transcript you cannot copy out of is one you
    // cannot quote, paste into a bug report, or keep — and this is the main place a
    // person reads anything on the phone. LocalRoomScreen already wrapped its own
    // transcript this way; the networked one did not, which is the same verb-on-one-
    // surface gap that keeps costing us.
    SelectionContainer {
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(entries) { entry ->
            ProseEntryItem(entry)
        }

        // Streaming text (in-progress token streams)
        items(streamingText.entries.toList()) { (source, text) ->
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append("$source: ")
                    }
                    append(text)
                    append("\u2588") // Block cursor
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }
    }
    }
}

@Composable
private fun ProseEntryItem(entry: ProseEntry) {
    val color = when (entry.priority) {
        PriorityLevel.CRITICAL -> MaterialTheme.colorScheme.error
        PriorityLevel.AMBIENT -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        PriorityLevel.NORMAL -> MaterialTheme.colorScheme.onSurface
    }

    val styledText = buildAnnotatedString {
        when (entry.speaker) {
            "narrator" -> {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(entry.text)
                }
            }
            "emote" -> {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(entry.text)
                }
            }
            "system" -> {
                withStyle(SpanStyle(fontWeight = FontWeight.Medium)) {
                    append(entry.text)
                }
            }
            else -> {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append("${entry.speaker}: ")
                }
                append(entry.text)
            }
        }
    }

    Text(
        text = styledText,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
    )

    // Render content blocks (rich if renderer available, fallback otherwise)
    val registry = ContentBlockRegistry.global
    for (block in entry.blocks) {
        if (registry.canRenderRich(block.format)) {
            registry.findRenderer(block.format).Render(block)
        } else if (block.fallback.isNotEmpty()) {
            Text(
                text = "  ${block.fallback}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}
