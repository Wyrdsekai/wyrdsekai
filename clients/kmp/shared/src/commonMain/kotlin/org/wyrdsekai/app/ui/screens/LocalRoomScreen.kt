package org.wyrdsekai.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import org.wyrdsekai.app.ui.enableTestTagsAsResourceId
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import org.wyrdsekai.app.engine.PhoneNode
import org.wyrdsekai.app.engine.PhoneNodeEvent
import org.wyrdsekai.app.engine.transit.LocalServerConnection
import org.wyrdsekai.app.engine.transit.MappedInput
import org.wyrdsekai.app.engine.transit.ServerConnection
import org.wyrdsekai.app.engine.transit.SessionInputMapper
import org.wyrdsekai.app.engine.transit.SessionS2CRenderer
import org.wyrdsekai.app.protocol.C2SMessage
import org.wyrdsekai.app.protocol.S2CMessage
import androidx.compose.foundation.text.selection.SelectionContainer
import org.wyrdsekai.app.engine.soul.NamedBootstrapManifest
import org.wyrdsekai.app.engine.soul.SoulSeedImporter
import org.wyrdsekai.app.i18n.LocalUiStrings
import org.wyrdsekai.app.protocol.Entity
import org.wyrdsekai.app.protocol.Exit
import org.wyrdsekai.app.protocol.Hint
import org.wyrdsekai.app.protocol.PriorityLevel
import org.wyrdsekai.app.state.ProseEntry
import org.wyrdsekai.app.ui.components.ExitBar
import org.wyrdsekai.app.ui.components.HintChips
import org.wyrdsekai.app.ui.components.ProseStream
import org.wyrdsekai.app.platform.AppProps
import org.wyrdsekai.app.platform.openUrlInBrowser

/**
 * Standalone room screen — talks directly to PhoneNode, no WebSocket.
 * Used when the phone IS the node (full local Ma).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalRoomScreen(
    phoneNode: PhoneNode,
    scope: CoroutineScope,
    onStop: () -> Unit,
    onSwitchMode: (() -> Unit)? = null,
    /** True when a home-zone relay leg is configured — drives the honest
     * mode label + switch copy in Node Settings (2026-07-22 live-where UX). */
    hasHomeZone: Boolean = false,
    /** Open the "My servers" zone-bank surface. */
    onMyServers: (() -> Unit)? = null,
    /** Log out of the home zone: end the live session (tunnel + relay
     * connection + session token) but keep the zone saved in My zones.
     * Only meaningful when [hasHomeZone]; RN Settings parity (2026-07-25). */
    onLogout: (() -> Unit)? = null,
    modelStatusText: String? = null,
    modelProgress: Float = 0f,
    onInferenceUrlChanged: ((String) -> Unit)? = null,
    /**
     * optional injected session transport. When the host
     * is in relay-login mode it passes a RelayTunnelServerConnection (the real
     * zone, tunneled); offline it leaves this null and the screen drives the
     * in-process LocalServerConnection. Either way the terminal only speaks the
     * C2S/S2C protocol — it never knows which node answers.
     */
    remoteConnection: ServerConnection? = null,
    modifier: Modifier = Modifier,
) {
    val strings = LocalUiStrings.current

    var roomName by remember { mutableStateOf("Home") }
    var exits by remember { mutableStateOf<List<Exit>>(emptyList()) }
    var entities by remember { mutableStateOf<List<Entity>>(emptyList()) }
    var hints by remember { mutableStateOf<List<Hint>>(emptyList()) }
    var proseLines by remember { mutableStateOf<List<ProseEntry>>(emptyList()) }
    var inputText by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var enterToSend by remember { mutableStateOf(true) }

    val PLAYER_ID = "player"
    val PLAYER_NAME = "You"

    // the screen is a terminal that speaks the C2S/S2C
    // session protocol to a ServerConnection. Offline mode points it at the
    // local node (LocalServerConnection); the terminal never calls phoneNode.*
    // for session actions. Swapping this for a tunnel-backed ServerConnection
    // is the whole "remote" change.
    val connection = remember(phoneNode, remoteConnection) {
        remoteConnection ?: LocalServerConnection(phoneNode, scope, PLAYER_ID, PLAYER_NAME)
    }
    val c2sCounter = remember { intArrayOf(0) }
    fun nextC2sId(): String = "c2s-${c2sCounter[0]++}"

    val directionAliases = mapOf(
        "n" to "north", "s" to "south", "e" to "east", "w" to "west",
        "u" to "up", "d" to "down",
        "ne" to "northeast", "nw" to "northwest", "se" to "southeast", "sw" to "southwest",
    )
    val bareDirections = directionAliases.keys + directionAliases.values + setOf("out", "back")

    fun resolveDirection(raw: String): String = directionAliases[raw.lowercase()] ?: raw.lowercase()

    fun addProse(speaker: String, text: String) {
        proseLines = proseLines + ProseEntry(
            speaker = speaker,
            text = text,
            priority = PriorityLevel.NORMAL,
        )
    }

    // Render session content from the connection's S2C stream. In offline mode
    // these frames originate from the local node; over a tunnel they originate
    // from the real zone. The terminal renders them identically.
    LaunchedEffect(connection) {
        val unsub = connection.onMessage { msg ->
            // SessionS2CRenderer is the EXECUTABLE render contract shared with RN
            // (clients/parity/parity.json) — the prose rules live there, tested
            // against the same table on both clients (2026-07-25).
            val render = SessionS2CRenderer.render(msg)
            render.room?.let { room ->
                roomName = room.name
                exits = room.exits
                entities = room.entities
                hints = room.hints
            }
            for ((speaker, text) in render.prose) addProse(speaker, text)
        }
        // Initial render — ask the node to look, exactly as a fresh session would.
        connection.send(C2SMessage.Look(nextC2sId(), ""))
        try { awaitCancellation() } finally { unsub() }
    }

    // Out-of-band CONTROL events (not session prose). Session events
    // (Prose/RoomChanged/StateChanged/Error) arrive via the connection above,
    // so they are ignored here to avoid double-rendering.
    LaunchedEffect(phoneNode) {
        phoneNode.notifications.collect { event ->
            when (event) {
                is PhoneNodeEvent.ServerRoomEntered -> proseLines = emptyList()
                is PhoneNodeEvent.ServerRoomLeft -> proseLines = emptyList()
                is PhoneNodeEvent.OpenBrowser -> openUrlInBrowser(event.url)
                is PhoneNodeEvent.TierChanged -> addProse("system", "Tier: ${event.from} -> ${event.to}")
                else -> {}
            }
        }
    }

    fun processInput(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val lower = trimmed.lowercase()

        // Server-routed Study commands: journal write/search and library search
        // delegate to the server's Study + library_card scripted item when a
        // ServerClient session is active (the server has the shared Lucene
        // corpus the phone lacks, plus a per-user durable journal). Checked
        // FIRST — before the session mapper — in both modes.
        run {
            val body = if (lower.startsWith("/")) trimmed.substring(1) else trimmed
            val bl = body.lowercase()
            val isStudyCmd = bl.startsWith("journal ")
                || bl.startsWith("journal entry ")
                || bl.startsWith("journal private ")
                || bl.startsWith("journal search ")
                || bl.startsWith("search the library for ")
                || bl.startsWith("search library for ")
                || bl.startsWith("library search ")
                || bl.startsWith("use library card ")
                || bl.startsWith("use library_card ")
            val sc = org.wyrdsekai.app.network.ServerClientHolder.get()
            if (isStudyCmd && sc != null) {
                addProse("You", trimmed)
                scope.launch {
                    val res = sc.doCommand("say $body")
                    if (res.ok && !res.data.isNullOrBlank()) {
                        addProse("narrator", res.data)
                    } else if (!res.ok) {
                        addProse("system",
                            "Server command failed: ${res.error ?: "unknown error"} — falling back to local handler.")
                        connection.send(C2SMessage.Say(nextC2sId(), "", body))
                    }
                }
                return
            }
        }

        // LIVE ZONE SESSION → the shared mapper, the EXECUTABLE parity contract
        // with RN (clients/parity/parity.json). All typed input over a tunnel /
        // direct WS routes through SessionInputMapper so both clients produce
        // byte-identical frames; behavior changes go table-first (2026-07-25).
        if (remoteConnection != null) {
            when (val mapped = SessionInputMapper.map(trimmed, hints, { nextC2sId() })) {
                is MappedInput.Send -> {
                    // Terminal-style input echo ("> l") — muted, unmistakably
                    // NOT speech (parity.json echoPolicy, 2026-07-25).
                    addProse("system", mapped.echo)
                    scope.launch { connection.send(mapped.frame) }
                }
                is MappedInput.LocalText -> addProse(mapped.speaker, mapped.text)
                MappedInput.Ignore -> {}
            }
            return
        }

        if (lower.startsWith("/")) {
            val parts = trimmed.substring(1).split(Regex("\\s+"), limit = 2)
            when (parts[0].lowercase()) {
                "help" -> addProse("system",
                    "Commands:\n" +
                    "  say <text> or '<text> or \"<text>  -- Say something\n" +
                    "  emote <action> or :<action> or ;<action>  -- Perform an action\n" +
                    "  tell <name> <text> or ><name> <text>  -- Send a private message\n" +
                    "  whisper <name> <text>  -- Whisper to someone nearby\n" +
                    "  look or l  -- Look around\n" +
                    "  go <direction>  -- Move to another room\n" +
                    "  take <object>  -- Pick up an object\n" +
                    "  drop <object>  -- Drop an object\n" +
                    "  use <object>  -- Use an object\n" +
                    "  /inventory or /i  -- Check your inventory\n" +
                    "  /socials  -- List social emotes\n" +
                    "  /help  -- Show this help")
                "socials" -> addProse("system",
                    "Social emotes (type the word to perform):\n" +
                    "  nod, smile, laugh, grin, frown, shrug, sigh, gasp, blink, wince,\n" +
                    "  wave, bow, clap, dance, stretch, yawn, pace, fidget,\n" +
                    "  cry, cheer, groan, blush, ponder, brood, beam, sulk,\n" +
                    "  hug, thank, agree, disagree, salute, welcome")
                "inventory", "i" ->
                    scope.launch { connection.send(C2SMessage.Command(nextC2sId(), "inventory")) }
                // /journal and /library (or /search) are server-Study commands.
                // Don't eat them here — fall through to the server-Study routing
                // block below, which strips the slash, recognizes "journal " /
                // "library search " prefixes, and routes via ServerClient.doCommand
                // (→ NATS wyrd.zone.{zone}.study.journal / library.search).
                "journal", "library", "search" -> { /* fall through */ }
                else -> {
                    scope.launch { connection.send(C2SMessage.Say(nextC2sId(), "", trimmed.substring(1))) }
                    return
                }
            }
            // Only fall through to Study routing for the journal/library/search cases.
            if (parts[0].lowercase() !in setOf("journal", "library", "search")) return
        }

        // "actions" — list the room's available actions (SSH/CLI parity: the
        // renderActionsMenu equivalent). The chips already show these, but the
        // typed command + number-select is what ssh users expect (2026-07-24).
        if (lower == "actions") {
            if (hints.isEmpty()) {
                addProse("system", "Nothing to do here right now.")
            } else {
                val sb = StringBuilder("Things to do here:")
                hints.forEachIndexed { i, h -> sb.append("\n  [${i + 1}] ${h.label}") }
                sb.append("\n(type a number to choose)")
                addProse("system", sb.toString())
            }
            return
        }

        // Bare number → choose that action from the current hint list (parity
        // with the CLI's numbered menu). Falls through if no such action.
        trimmed.toIntOrNull()?.let { n ->
            hints.getOrNull(n - 1)?.let { hint ->
                val action = hint.action ?: "say:${hint.label}"
                val command = when {
                    action.startsWith("say:") -> action.removePrefix("say:")
                    action.startsWith("go:") -> "go " + action.removePrefix("go:")
                    action == "look" -> "look"
                    action.startsWith("use:") -> "use " + action.removePrefix("use:")
                    action.startsWith("take:") -> "take " + action.removePrefix("take:")
                    else -> action
                }
                scope.launch { connection.send(C2SMessage.Say(nextC2sId(), "", command)) }
                return
            }
        }

        // examine <target> / ex <target> / look at <target> — passive
        // observation. Check BEFORE bare "look"
        // so "look at X" doesn't shortcut to a room render.
        val examineMatch =
            Regex("^examine\\s+(.+)$").find(lower)
                ?: Regex("^ex\\s+(.+)$").find(lower)
                ?: Regex("^look\\s+at\\s+(.+)$").find(lower)
                ?: Regex("^l\\s+at\\s+(.+)$").find(lower)
        if (examineMatch != null) {
            val target = examineMatch.groupValues[1].trim()
            // Typed Examine envelope → the zone's handleExamine returns the
            // object/entity description. (Was C2SMessage.Command("examine", …),
            // but the zone's handleCommand has no "examine" case — it fell to
            // "Unknown zone command", so examine silently did nothing over a
            // live session. The structured envelope is what SSH/CLI/web use.)
            scope.launch { connection.send(C2SMessage.Examine(nextC2sId(), "", target)) }
            return
        }

        // rename me <name> — local-only display name update (SPEC §7.4)
        val renameMatch = Regex("^rename\\s+me(?:\\s+(.+))?$").find(lower)
        if (renameMatch != null) {
            val newName = renameMatch.groupValues.getOrNull(1)?.trim().orEmpty()
            val result = phoneNode.rename(newName)
            when (result) {
                is PhoneNode.RenameResult.Ok -> addProse("system",
                    "You are now known as ${result.newName}.")
                is PhoneNode.RenameResult.Rejected -> addProse("system", result.message)
            }
            return
        }

        // drop <object> (SPEC §4 — symmetric with take)
        val dropMatch = (Regex("^drop\\s+(.+)$").find(lower)
            ?: Regex("^put\\s+down\\s+(.+)$").find(lower))
        if (dropMatch != null) {
            scope.launch { connection.send(C2SMessage.Drop(nextC2sId(), "", dropMatch.groupValues[1].trim())) }
            return
        }

        if (lower == "look" || lower == "l") {
            scope.launch { connection.send(C2SMessage.Look(nextC2sId(), "")) }
            return
        }

        val goMatch = Regex("^(?:go|move)\\s+(.+)$").find(lower)
        if (goMatch != null) {
            scope.launch { connection.send(C2SMessage.Go(nextC2sId(), "", resolveDirection(goMatch.groupValues[1].trim()))) }
            return
        }

        if (lower in bareDirections || trimmed in bareDirections) {
            val raw = if (lower in bareDirections) lower else trimmed
            scope.launch { connection.send(C2SMessage.Go(nextC2sId(), "", resolveDirection(raw))) }
            return
        }

        val takeMatch = (Regex("^(?:take|get)\\s+(.+)$").find(lower)
            ?: Regex("^pick\\s+up\\s+(.+)$").find(lower))
        if (takeMatch != null) {
            scope.launch { connection.send(C2SMessage.Take(nextC2sId(), "", takeMatch.groupValues[1].trim())) }
            return
        }

        val useMatch = Regex("^use\\s+(.+)$").find(lower)
        if (useMatch != null) {
            scope.launch { connection.send(C2SMessage.Use(nextC2sId(), "", useMatch.groupValues[1].trim(), null)) }
            return
        }

        // Say shorthands: ' or "
        if (trimmed.startsWith("'") || trimmed.startsWith("\"")) {
            scope.launch { connection.send(C2SMessage.Say(nextC2sId(), "", trimmed.substring(1))) }
            return
        }

        // Emote: : or ; prefix — route to emote
        if (trimmed.startsWith(":") || trimmed.startsWith(";")) {
            val emoteText = trimmed.substring(1).trim()
            if (emoteText.isNotBlank()) {
                scope.launch { connection.send(C2SMessage.Emote(nextC2sId(), "", emoteText)) }
            }
            return
        }

        // Tell: >name text — route as say for now (companion hears it)
        if (trimmed.startsWith(">")) {
            scope.launch { connection.send(C2SMessage.Say(nextC2sId(), "", trimmed)) }
            return
        }

        // Full word commands
        if (lower.startsWith("emote ")) {
            scope.launch { connection.send(C2SMessage.Emote(nextC2sId(), "", trimmed.substring(6).trim())) }
            return
        }
        if (lower.startsWith("tell ") || lower.startsWith("whisper ")) {
            val verb = if (lower.startsWith("tell ")) "tell" else "whisper"
            val rest = trimmed.substring(verb.length + 1).trim()
            val match = Regex("^(\\S+)\\s+(.+)$").find(rest)
            if (match != null) {
                val target = match.groupValues[1]
                val message = match.groupValues[2]
                val verbCap = if (verb == "tell") "tell" else "whisper to"
                val sc = org.wyrdsekai.app.network.ServerClientHolder.get()
                if (remoteConnection != null) {
                    // PRIMARY path — a LIVE session to the zone (the relay tunnel,
                    // or a direct WS). Route the tell over it: the zone's session
                    // parser turns "tell <name> <msg>" into a directed tell
                    // (WyrdWebSocket → handleWebSocketTell), exactly like SSH/CLI,
                    // and the companion's REPLY streams back as an S2C Prose frame
                    // that renders in this stream — a real two-way conversation.
                    //
                    // talking to a companion MUST go over the
                    // live session, NEVER the delivery-only RPC shortcut below
                    // (mcp.tell over NATS returns an ack with no reply — that path
                    // left phones write-only over the relay, the bug we fixed).
                    //
                    // NO client-side echo here: the zone echoes "You tell X: ..."
                    // back to the sender (WyrdWebSocket ui.tell_sent) over the same
                    // session, so a local echo would render the line twice.
                    scope.launch { connection.send(C2SMessage.Say(nextC2sId(), "", trimmed)) }
                } else {
                    // No live session — nothing echoes back, so echo locally.
                    addProse("narrator", "You $verbCap $target: \"$message\"")
                    if (sc != null) {
                        // Offline node holding RPC creds (e.g. a cross-zone tell with
                        // no tunnel). RPC delivers but cannot return the reply inline.
                        scope.launch {
                            val res = sc.tell(target, message)
                            if (res.ok) {
                                res.data?.takeIf { it.isNotBlank() }?.let { addProse("narrator", it) }
                            } else {
                                addProse("system", "Tell failed: ${res.error ?: "unknown error"}")
                            }
                        }
                    } else if (target.contains(".")) {
                        addProse(
                            "system",
                            "$target is in another zone — connect to your server to deliver cross-zone tells.",
                        )
                    } else {
                        scope.launch { connection.send(C2SMessage.Say(nextC2sId(), "", trimmed)) }
                    }
                }
            } else {
                scope.launch { connection.send(C2SMessage.Say(nextC2sId(), "", trimmed)) }
            }
            return
        }

        val sayMatch = (Regex("^say\\s+(.+)$", RegexOption.IGNORE_CASE).find(trimmed)
            ?: Regex("^\"(.+)\"$").find(trimmed))
        if (sayMatch != null) {
            scope.launch { connection.send(C2SMessage.Say(nextC2sId(), "", sayMatch.groupValues[1])) }
            return
        }

        // Default (2026-07-24): forward to the zone as a generic Command instead
        // of a local "Huh?". The zone re-parses it through the SAME CommandParser
        // SSH/CLI uses (WyrdWebSocket full-parity fallback), so map / where /
        // nearby / rooms / path / exits / score / follow / etc. behave EXACTLY
        // like ssh; a truly unknown verb falls through to room speech there.
        // Previously these never left the phone, so the phone terminal lacked
        // most of the command surface the ssh session has.
        val parts = trimmed.split(Regex("\\s+"))
        val cmdWord = parts[0]
        val cmdArgs = parts.drop(1)
        scope.launch { connection.send(C2SMessage.Command(nextC2sId(), cmdWord, cmdArgs)) }
    }

    // Settings dialog
    if (showSettings) {
        NodeSettingsDialog(
            phoneNode = phoneNode,
            scope = scope,
            onDismiss = { showSettings = false },
            onInferenceUrlChanged = onInferenceUrlChanged,
            onSwitchMode = onSwitchMode,
            hasHomeZone = hasHomeZone,
            onMyServers = onMyServers,
            onLogout = onLogout,
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(roomName, modifier = Modifier.testTag("standalone-room-name")) },
                actions = {
                    IconButton(
                        onClick = { showSettings = true },
                        modifier = Modifier.testTag("standalone-settings-button"),
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                    // "Stop Node" moved to Settings → Advanced (not user-facing)
                }
            )
        },
        modifier = modifier,
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize(),
        ) {
            // Model status banner — only show for error states (BirthScreen handles download/load)
            if (modelStatusText != null && (
                modelStatusText.contains("error", ignoreCase = true) ||
                modelStatusText.contains("unavailable", ignoreCase = true) ||
                modelStatusText.contains("failed", ignoreCase = true)
            )) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(
                            text = modelStatusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        if (modelProgress > 0f && modelProgress < 1f) {
                            Spacer(modifier = Modifier.height(4.dp))
                            LinearProgressIndicator(
                                progress = { modelProgress },
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth().testTag("standalone-prose-list")) {
                ProseStream(
                    entries = proseLines,
                    streamingText = emptyMap(),
                )
            }

            // Entity presence line
            if (entities.any { it.type == "agent" }) {
                Text(
                    text = "Present: ${entities.filter { it.type == "agent" }.joinToString(", ") { it.name }}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            if (exits.isNotEmpty()) {
                ExitBar(
                    exits = exits,
                    onExitSelected = { direction ->
                        // Over a live zone session (relay tunnel / direct WS) route the
                        // move to the REAL zone as a TYPED go frame — the same envelope
                        // the text-input parser produces. This used to send
                        // Say("go $direction") assuming the zone re-parses say text as a
                        // command; it doesn't (InputParser only splits say/emote), so the
                        // button made the player SAY "go out" while typing "out" moved
                        // them (2026-07-25). Offline → the local node.
                        if (remoteConnection != null) {
                            scope.launch { connection.send(C2SMessage.Go(nextC2sId(), "", direction)) }
                        } else {
                            scope.launch { phoneNode.go(PLAYER_ID, PLAYER_NAME, direction) }
                        }
                    },
                )
            }

            if (hints.isNotEmpty()) {
                HintChips(
                    hints = hints,
                    onSelect = { index ->
                        val hint = hints.getOrNull(index) ?: return@HintChips
                        val action = hint.action ?: "say:${hint.label}"
                        // Over a live zone session, select the hint by INDEX — the
                        // canonical hint_select the zone already dispatches (same as the
                        // RN chip tap), so the action's intent runs exactly as the server
                        // defined it. This used to wrap the action in Say("go north" /
                        // "use …") assuming the zone re-parses say text as a command; it
                        // doesn't (InputParser only splits say/emote), so every hint chip
                        // made the player SPEAK the command (2026-07-25). Offline → the
                        // local node dispatch below.
                        if (remoteConnection != null) {
                            scope.launch { connection.send(C2SMessage.HintSelect(nextC2sId(), "", index)) }
                            return@HintChips
                        }
                        when {
                            action.startsWith("say:") -> {
                                val text = action.removePrefix("say:")
                                scope.launch { phoneNode.say(PLAYER_ID, PLAYER_NAME, text) }
                            }
                            action.startsWith("go:") -> {
                                val dir = action.removePrefix("go:")
                                scope.launch { phoneNode.go(PLAYER_ID, PLAYER_NAME, dir) }
                            }
                            action == "look" -> {
                                scope.launch {
                                    val snapshot = phoneNode.look()
                                    if (snapshot != null) {
                                        roomName = snapshot.name
                                        exits = snapshot.exits
                                        hints = snapshot.hints
                                        addProse("narrator", "${snapshot.name}\n${snapshot.description}")
                                    }
                                }
                            }
                            action.startsWith("use:") -> {
                                // Real verb, not say — the local node HAS use(); routing
                                // through say sent "use X" to companion inference instead
                                // of firing the object (offline twin of the chip-say bug).
                                val obj = action.removePrefix("use:")
                                scope.launch { phoneNode.use(PLAYER_ID, obj, null) }
                            }
                            action.startsWith("take:") -> {
                                val obj = action.removePrefix("take:")
                                scope.launch { phoneNode.take(PLAYER_ID, obj) }
                            }
                            else -> {
                                // Default: treat as say
                                scope.launch { phoneNode.say(PLAYER_ID, PLAYER_NAME, action) }
                            }
                        }
                    },
                )
            }

            // Enter-to-send toggle
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = enterToSend,
                    onCheckedChange = { enterToSend = it },
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Enter sends",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = { Text("'say :emote /help") },
                    singleLine = true,
                    modifier = Modifier.weight(1f).testTag("standalone-input"),
                    keyboardOptions = KeyboardOptions(
                        imeAction = if (enterToSend) ImeAction.Send else ImeAction.Default,
                    ),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (enterToSend && inputText.isNotBlank()) {
                                processInput(inputText)
                                inputText = ""
                            }
                        },
                    ),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        processInput(inputText)
                        inputText = ""
                    },
                    enabled = inputText.isNotBlank(),
                    modifier = Modifier.testTag("standalone-send-button"),
                ) {
                    Text(strings.send)
                }
            }
        }
    }
}

/**
 * Settings dialog for the local node — shows companion name (editable),
 * inference backend (read-only), soul version (read-only), and a mode
 * switch placeholder.
 */
@Composable
private fun NodeSettingsDialog(
    phoneNode: PhoneNode,
    scope: CoroutineScope,
    onDismiss: () -> Unit,
    onInferenceUrlChanged: ((String) -> Unit)? = null,
    onSwitchMode: (() -> Unit)? = null,
    hasHomeZone: Boolean = false,
    onMyServers: (() -> Unit)? = null,
    onLogout: (() -> Unit)? = null,
) {
    val companion = phoneNode.companion
    val manifest = companion?.soulManifest
    val companionName = manifest?.agentName ?: AppProps.get("wyrdsekai.companion.name") ?: "Wyrd"
    val currentInferenceUrl = AppProps.get("wyrdsekai.inference.url") ?: "http://localhost:8080"
    val soulVersion = manifest?.manifestVersion ?: 0
    val isBootstrap = manifest?.did?.startsWith("did:key:bootstrap-") == true

    var editedName by remember { mutableStateOf(companionName) }
    val currentHomeName = AppProps.get("wyrdsekai.home.name") ?: "Home"
    var editedHomeName by remember { mutableStateOf(currentHomeName) }
    var editedInferenceUrl by remember { mutableStateOf(currentInferenceUrl) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Node Settings") },
        text = {
            // verticalScroll lets the dialog content scroll on small screens so
            // entries near the bottom (Switch to remote, Export Soul) stay reachable.
            // The dialog renders in a separate popup window — Compose's
            // testTagsAsResourceId opt-in at the activity root doesn't reach
            // here, so we re-enable it locally so Maestro's `id:` selectors
            // can find dialog elements like `switch-mode-button`.
            Column(
                modifier = Modifier.fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .enableTestTagsAsResourceId(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Mode + Switch-to-remote first — kept above the fold so the e2e
                // suite can find `switch-mode-button` without having to scroll
                // inside the AlertDialog.
                // Honest live-where framing (2026-07-22, parity with RN
                // Settings): say where the companion lives, and make switching
                // read as the reversible act it is — the zone bank keeps every
                // zone + relay creds, so returning is one tap on My servers.
                Text("Where your companion lives", style = MaterialTheme.typography.labelSmall)
                Text(
                    text = if (hasHomeZone) "Home zone (this phone is her window)"
                           else "On this phone (standalone)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (onMyServers != null) {
                    TextButton(
                        onClick = {
                            onDismiss()
                            onMyServers()
                        },
                        modifier = Modifier.testTag("my-servers-button"),
                    ) {
                        Text("My zones")
                    }
                }
                if (hasHomeZone && onLogout != null) {
                    TextButton(
                        onClick = {
                            onDismiss()
                            onLogout()
                        },
                        modifier = Modifier.testTag("logout-button"),
                    ) {
                        Text(
                            "Log out (zone stays in My zones)",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (onSwitchMode != null) {
                    TextButton(
                        onClick = {
                            onDismiss()
                            onSwitchMode()
                        },
                        modifier = Modifier.testTag("switch-mode-button"),
                    ) {
                        Text(
                            if (hasHomeZone)
                                "Switch to standalone (your zone stays saved)"
                            else
                                "Connect to a server instead",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                HorizontalDivider()

                // Companion Name (editable)
                Text("Companion Name", style = MaterialTheme.typography.labelSmall)
                OutlinedTextField(
                    value = editedName,
                    onValueChange = { editedName = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                HorizontalDivider()

                // Room Name (editable)
                Text("Room Name", style = MaterialTheme.typography.labelSmall)
                OutlinedTextField(
                    value = editedHomeName,
                    onValueChange = { editedHomeName = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "Takes effect on restart.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                HorizontalDivider()

                // Inference Backend (editable)
                Text("Inference Backend", style = MaterialTheme.typography.labelSmall)
                OutlinedTextField(
                    value = editedInferenceUrl,
                    onValueChange = { editedInferenceUrl = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                HorizontalDivider()

                // Soul Version (read-only)
                Text("Soul Version", style = MaterialTheme.typography.labelSmall)
                Text(
                    text = if (isBootstrap) "Bootstrap (v$soulVersion)" else "v$soulVersion",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                HorizontalDivider()

                // Export Soul
                var exportedJson by remember { mutableStateOf<String?>(null) }
                Button(
                    onClick = {
                        if (manifest != null) {
                            exportedJson = SoulSeedImporter.exportToJson(manifest)
                        }
                    },
                    enabled = manifest != null,
                ) {
                    Text("Export Soul")
                }
                if (exportedJson != null) {
                    SelectionContainer {
                        Text(
                            text = exportedJson!!.take(500) + if ((exportedJson?.length ?: 0) > 500) "\n..." else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // Apply name change if different
                    val trimmed = editedName.trim().ifEmpty { "Wyrd" }
                    if (trimmed != companionName && companion != null && manifest != null) {
                        val renamed = NamedBootstrapManifest.create(trimmed)
                        scope.launch {
                            try { companion.loadSoul(renamed) } catch (_: Exception) {}
                        }
                    }
                    // Apply room name change (takes effect on restart)
                    val trimmedHomeName = editedHomeName.trim().ifEmpty { "Home" }
                    if (trimmedHomeName != currentHomeName) {
                        AppProps.set("wyrdsekai.home.name", trimmedHomeName)
                        // Persist via TokenStore
                        try {
                            val ts = org.wyrdsekai.app.state.TokenStore()
                            ts.saveHomeName(trimmedHomeName)
                        } catch (_: Exception) {}
                    }
                    // Apply inference URL change
                    val trimmedUrl = editedInferenceUrl.trim()
                    if (trimmedUrl.isNotBlank() && trimmedUrl != currentInferenceUrl) {
                        AppProps.set("wyrdsekai.inference.url", trimmedUrl)
                        onInferenceUrlChanged?.invoke(trimmedUrl)
                    }
                    onDismiss()
                }
            ) {
                Text("Done")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
