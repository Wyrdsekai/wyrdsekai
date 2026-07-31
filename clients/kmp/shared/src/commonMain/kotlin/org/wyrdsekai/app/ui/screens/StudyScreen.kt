package org.wyrdsekai.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.wyrdsekai.app.engine.study.StudyItem
import org.wyrdsekai.app.i18n.LocalUiStrings
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * The Study — personal workspace screen (journal-first on phone).
 *
 * Primary view: recent journal entries + write + search.
 * Shell mode toggle: desk commands (same as before).
 * Desktop mode (mounts/apps): hidden on phone, available on desktop.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudyScreen(
    journalEntries: List<StudyItem>,
    entryCount: Int,
    searchResults: List<StudyItem>?,
    mounts: Map<String, String>,
    apps: Map<String, String>,
    scheduleItems: List<String>,
    ageBracket: String?,
    onWriteJournal: (String) -> Unit,
    onSearchJournal: (String) -> Unit,
    onClearSearch: () -> Unit,
    onSay: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalUiStrings.current
    var inputText by remember { mutableStateOf("") }
    var showShell by remember { mutableStateOf(false) }
    var shellOutput by remember { mutableStateOf<List<String>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var isSearchMode by remember { mutableStateOf(false) }

    val title = when (ageBracket) {
        "seedling" -> "Playroom"
        "sprout" -> "Treehouse"
        "sapling" -> "Workshop"
        "young-tree" -> "Studio"
        else -> "The Study"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onClose, modifier = Modifier.testTag("study-back")) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Search toggle
                    IconButton(onClick = {
                        isSearchMode = !isSearchMode
                        if (!isSearchMode) {
                            searchQuery = ""
                            onClearSearch()
                        }
                    }) {
                        Icon(
                            if (isSearchMode) Icons.Default.Close else Icons.Default.Search,
                            contentDescription = if (isSearchMode) "Close search" else "Search",
                        )
                    }
                    // Shell mode toggle
                    IconButton(onClick = { showShell = !showShell }) {
                        Icon(
                            if (showShell) Icons.Default.Home else Icons.Default.Terminal,
                            contentDescription = if (showShell) "Room view" else "Shell mode",
                        )
                    }
                },
            )
        },
        modifier = modifier,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            if (showShell) {
                ShellView(
                    output = shellOutput,
                    onCommand = { cmd ->
                        shellOutput = shellOutput + "> $cmd"
                        onSay("desk:$cmd")
                    },
                    modifier = Modifier.weight(1f),
                )
            } else {
                // Search bar (when active)
                if (isSearchMode) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth().testTag("study-search"),
                        placeholder = { Text("Search journal...") },
                        singleLine = true,
                        trailingIcon = {
                            IconButton(onClick = {
                                if (searchQuery.isNotBlank()) onSearchJournal(searchQuery)
                            }) {
                                Icon(Icons.Default.Search, contentDescription = "Search")
                            }
                        },
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Entry count header
                Text(
                    if (isSearchMode && searchResults != null) {
                        "${searchResults.size} results"
                    } else {
                        "$entryCount journal entries"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )

                // Journal entries list
                val displayItems = if (isSearchMode && searchResults != null) searchResults else journalEntries
                LazyColumn(
                    modifier = Modifier.weight(1f).testTag("study-entries"),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (displayItems.isEmpty()) {
                        item {
                            Text(
                                if (isSearchMode) "No results found." else "Your journal is empty.\nWrite something below to get started.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 24.dp),
                            )
                        }
                    } else {
                        items(displayItems, key = { it.id }) { entry ->
                            JournalEntryCard(entry)
                        }
                    }
                }

                // Write input
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier.weight(1f).testTag("study-input"),
                        placeholder = { Text("Write in journal...") },
                        singleLine = true,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        modifier = Modifier.testTag("study-send"),
                        onClick = {
                            if (inputText.isNotBlank()) {
                                onWriteJournal(inputText)
                                inputText = ""
                            }
                        },
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = "Write")
                    }
                }
            }
        }
    }
}

@Composable
private fun JournalEntryCard(entry: StudyItem) {
    val isPrivate = entry.itemType == StudyItem.TYPE_JOURNAL_PRIVATE
    val timeText = remember(entry.timestamp) {
        val local = Instant.fromEpochMilliseconds(entry.timestamp)
            .toLocalDateTime(TimeZone.currentSystemDefault())
        val months = listOf("Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec")
        val hh = local.hour.toString().padStart(2, '0')
        val mm = local.minute.toString().padStart(2, '0')
        "${months[local.month.ordinal]} ${local.day}, $hh:$mm"
    }

    Card(
        modifier = Modifier.fillMaxWidth().testTag("study-entry-${entry.id}"),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isPrivate) {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = "Private",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text(
                        entry.title.ifEmpty { entry.content.take(60) },
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                Text(
                    timeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (entry.content.length > entry.title.length + 5) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    entry.content.take(200),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ShellView(
    output: List<String>,
    onCommand: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var input by remember { mutableStateOf("") }

    Column(modifier = modifier) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            reverseLayout = true,
        ) {
            items(output.reversed()) { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    modifier = Modifier.padding(vertical = 1.dp),
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "desk: ",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            )
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                ),
            )
            IconButton(
                onClick = {
                    if (input.isNotBlank()) {
                        onCommand(input)
                        input = ""
                    }
                },
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Run")
            }
        }
    }
}
