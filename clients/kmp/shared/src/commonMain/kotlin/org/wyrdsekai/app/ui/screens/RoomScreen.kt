package org.wyrdsekai.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.wyrdsekai.app.i18n.LocalUiStrings
import org.wyrdsekai.app.network.WyrdWebSocket
import org.wyrdsekai.app.state.TokenStore
import org.wyrdsekai.app.engine.discovery.ConnectivityState
import org.wyrdsekai.app.ui.components.ExitBar
import org.wyrdsekai.app.ui.components.HintChips
import org.wyrdsekai.app.ui.components.ProseStream
import org.wyrdsekai.app.viewmodel.HouseholdViewModel
import org.wyrdsekai.app.viewmodel.InferenceViewModel
import org.wyrdsekai.app.viewmodel.NodeViewModel
import org.wyrdsekai.app.viewmodel.RoomViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomScreen(
    viewModel: RoomViewModel,
    onDisconnect: () -> Unit,
    serverUrl: String = "",
    username: String = "",
    onLogout: () -> Unit = onDisconnect,
    nodeViewModel: NodeViewModel? = null,
    inferenceViewModel: InferenceViewModel? = null,
    householdViewModel: HouseholdViewModel? = null,
    tokenStore: TokenStore? = null,
    webSocket: WyrdWebSocket? = null,
    onLocaleChanged: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val strings = LocalUiStrings.current
    val roomName by viewModel.roomName.collectAsState()
    val exits by viewModel.exits.collectAsState()
    val hints by viewModel.hints.collectAsState()
    val proseStream by viewModel.proseStream.collectAsState()
    val streamingText by viewModel.streamingText.collectAsState()
    var inputText by remember { mutableStateOf("") }

    // Overlay state: null = room view, "inventory" or "settings"
    var currentOverlay by remember { mutableStateOf<String?>(null) }

    // Re-read locale from tokenStore whenever overlay changes (picks up settings changes)
    val locale = remember(currentOverlay) { tokenStore?.loadLocale() ?: "en" }

    when (currentOverlay) {
        "inventory" -> {
            InventoryScreen(
                viewModel = viewModel,
                onClose = { currentOverlay = null },
                modifier = modifier,
            )
        }
        "settings" -> {
            SettingsScreen(
                roomViewModel = viewModel,
                serverUrl = serverUrl,
                username = username,
                onLogout = onLogout,
                onClose = { currentOverlay = null },
                nodeViewModel = nodeViewModel,
                inferenceViewModel = inferenceViewModel,
                onManageModels = { currentOverlay = "models" },
                householdViewModel = householdViewModel,
                onManageHousehold = { currentOverlay = "household" },
                tokenStore = tokenStore,
                webSocket = webSocket,
                onLocaleChanged = onLocaleChanged,
                modifier = modifier,
            )
        }
        "household" -> {
            if (householdViewModel != null) {
                HouseholdScreen(
                    viewModel = householdViewModel,
                    onClose = { currentOverlay = null },
                    modifier = modifier,
                )
            } else {
                currentOverlay = null
            }
        }
        "models" -> {
            if (inferenceViewModel != null) {
                ModelDownloadScreen(
                    inferenceViewModel = inferenceViewModel,
                    onClose = { currentOverlay = null },
                    modifier = modifier,
                )
            } else {
                currentOverlay = null
            }
        }
        "study" -> {
            val store = nodeViewModel?.nodeManager?.phoneNode?.studyStore
            val userDid = nodeViewModel?.nodeManager?.phoneNode?.companion?.soulManifest?.did ?: "local-user"
            val studyScope = rememberCoroutineScope()

            var journalEntries by remember { mutableStateOf<List<org.wyrdsekai.app.engine.study.StudyItem>>(emptyList()) }
            var entryCount by remember { mutableStateOf(0) }
            var searchResults by remember { mutableStateOf<List<org.wyrdsekai.app.engine.study.StudyItem>?>(null) }

            LaunchedEffect(store, userDid) {
                if (store != null) {
                    journalEntries = store.recentJournal(userDid, limit = 50)
                    entryCount = store.count(userDid)
                }
            }

            StudyScreen(
                journalEntries = journalEntries,
                entryCount = entryCount,
                searchResults = searchResults,
                mounts = emptyMap(),
                apps = emptyMap(),
                scheduleItems = emptyList(),
                ageBracket = null,
                onWriteJournal = { text ->
                    if (store != null) {
                        studyScope.launch {
                            store.writeJournal(userDid, text)
                            journalEntries = store.recentJournal(userDid, limit = 50)
                            entryCount = store.count(userDid)
                        }
                    }
                },
                onSearchJournal = { query ->
                    if (store != null) {
                        studyScope.launch {
                            searchResults = store.searchJournal(userDid, query, limit = 20)
                        }
                    }
                },
                onClearSearch = { searchResults = null },
                onSay = { viewModel.processInput(it) },
                onClose = { currentOverlay = null },
                modifier = modifier,
            )
        }
        else -> {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(roomName, modifier = Modifier.testTag("room-name")) },
                        actions = {
                            IconButton(onClick = { viewModel.look() }, modifier = Modifier.testTag("look-button")) {
                                Text("\uD83D\uDC41", style = MaterialTheme.typography.titleMedium)
                            }
                            // Inference status dot
                            if (inferenceViewModel != null && inferenceViewModel.isAvailable) {
                                val inferenceServerState by inferenceViewModel.serverState.collectAsState()
                                val dotColor = when (inferenceServerState) {
                                    "running" -> MaterialTheme.colorScheme.primary
                                    "starting" -> MaterialTheme.colorScheme.tertiary
                                    "error" -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.outlineVariant
                                }
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(dotColor),
                                )
                                Spacer(Modifier.width(4.dp))
                            }
                            // Between status dot
                            if (householdViewModel != null) {
                                val hhState by householdViewModel.connectivityState.collectAsState()
                                IconButton(onClick = { currentOverlay = "household" }) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(hhState.dotColor()),
                                    )
                                }
                            }
                            IconButton(onClick = { currentOverlay = "inventory" }, modifier = Modifier.testTag("bag-button")) {
                                Text("\uD83C\uDF92", style = MaterialTheme.typography.titleMedium)
                            }
                            IconButton(onClick = { currentOverlay = "settings" }, modifier = Modifier.testTag("settings-button")) {
                                Text("\u2699\uFE0F", style = MaterialTheme.typography.titleMedium)
                            }
                            IconButton(onClick = onDisconnect, modifier = Modifier.testTag("disconnect-button")) {
                                Text("\uD83D\uDEAA", style = MaterialTheme.typography.titleMedium)
                            }
                        },
                    )
                },
                modifier = modifier,
            ) { padding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                ) {
                    // Prose stream (main content area)
                    ProseStream(
                        entries = proseStream,
                        streamingText = streamingText,
                        modifier = Modifier.weight(1f).testTag("prose-list"),
                    )

                    // Exit bar
                    ExitBar(
                        exits = exits,
                        onExitSelected = { viewModel.go(it) },
                    )

                    // Hint chips — labels resolved via I18nStrings when labelKey is present
                    HintChips(
                        hints = hints,
                        onSelect = { viewModel.selectHint(it) },
                        locale = locale,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )

                    // Text input
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            placeholder = { Text(strings.saySomething) },
                            singleLine = true,
                            modifier = Modifier.weight(1f).testTag("room-input"),
                        )

                        Spacer(Modifier.width(8.dp))

                        Button(
                            onClick = {
                                if (inputText.isNotBlank()) {
                                    viewModel.processInput(inputText)
                                    inputText = ""
                                }
                            },
                            enabled = inputText.isNotBlank(),
                            modifier = Modifier.testTag("send-button"),
                        ) {
                            Text(strings.send)
                        }
                    }
                }
            }
        }
    }
}
