package org.wyrdsekai.app.engine

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.wyrdsekai.app.engine.agent.AgentProfile
import org.wyrdsekai.app.engine.agent.CompanionEngine
import org.wyrdsekai.app.engine.agent.Companions
import org.wyrdsekai.app.engine.between.BetweenClient
import org.wyrdsekai.app.engine.between.BetweenHeadlineSyncClient
import org.wyrdsekai.app.engine.between.BudDelegation
import org.wyrdsekai.app.engine.between.HouseholdEvent
import org.wyrdsekai.app.engine.between.HouseholdEventListener
import org.wyrdsekai.app.engine.between.ItemExchangeManager
import org.wyrdsekai.app.engine.between.PhoneDock
import org.wyrdsekai.app.engine.between.PresenceManager
import org.wyrdsekai.app.engine.between.VisitingRoomProxy
import org.wyrdsekai.app.engine.between.WarmHandoffManager
import org.wyrdsekai.app.engine.event.WorldEvent
import org.wyrdsekai.app.engine.mcp.McpGatewayLite
import org.wyrdsekai.app.engine.persistence.EventJournal
import org.wyrdsekai.app.engine.persistence.HttpSoulManifestStore
import org.wyrdsekai.app.engine.persistence.SoulManifestStore
import org.wyrdsekai.app.network.SoulClient
import org.wyrdsekai.app.engine.persistence.VitalityStore
import org.wyrdsekai.app.engine.soul.BootstrapSoulManifest
import org.wyrdsekai.app.engine.soul.Headline
import org.wyrdsekai.app.engine.room.RoomEngine
import org.wyrdsekai.app.engine.room.RoomEngineCommand
import org.wyrdsekai.app.engine.scripting.RoomScripts
import org.wyrdsekai.app.engine.scripting.ScriptEngine
import org.wyrdsekai.app.engine.study.StudyStore
import org.wyrdsekai.app.engine.study.StudySyncLayer
import org.wyrdsekai.app.engine.tier.*
import org.wyrdsekai.app.engine.transit.ServerConnection
import org.wyrdsekai.app.engine.transit.WebSocketServerConnection
import org.wyrdsekai.app.inference.InferenceClient
import org.wyrdsekai.app.protocol.*
import org.wyrdsekai.app.platform.AppProps
import org.wyrdsekai.app.platform.AppFiles
import kotlin.time.Clock

/**
 * Orchestrates the phone-class node subset.
 *
 * Boots Foundation rooms based on the current resource tier (T0-T3),
 * spawns the Wyrd companion, connects to inference.
 *
 * Tier transitions dynamically add/passivate rooms:
 * - T0: Companion only (no rooms, inference via server relay)
 * - T1: Home room + companion (default)
 * - T2: Home + Terminal + Dream Chamber + Mailroom + Between
 * - T3: Full peer — all available rooms + Between relay
 *
 * Exposes notifications as PhoneNodeEvents for the UI layer.
 */
class PhoneNode(
    private val journal: EventJournal,
    private val vitalityStore: VitalityStore?,
    private val inferenceClient: InferenceClient,
    private val inferenceBaseUrl: String,
    private val scope: CoroutineScope,
    private val tierManager: TierManager? = null,
    private val soulManifestStore: SoulManifestStore? = null,
    private val betweenClient: BetweenClient? = null,
    internal val nodeId: String = "phone-node",
    internal val familyId: String = "default-family",
    /** Home-zone id: scopes study-sync subjects to what the
     *  relay forwards + the server peer keys on. Falls back to familyId when null. */
    internal val zoneId: String? = null,
    /** Logged-in account userId — owns the Study (stable across the user's devices).
     *  Null in pure-local mode → the companion soul DID owns it. */
    internal val accountUserId: String? = null,
    private val mcpGatewayLiteFactory: (() -> McpGatewayLite)? = null,
    private val companionName: String = "Wyrd",
    private val homeRoomName: String = "Home",
    /** "tiny" = Study command mode (0.6B), "phone"/"medium" = full companion mode. */
    private val modelTier: String = "phone",
    private val serverUrl: String? = null,
    private val deviceToken: String? = null,
    internal var serverConnection: ServerConnection? = null,
    /** Local Study persistence (SQLite on Android). Set by NodeManager. */
    val studyStore: StudyStore? = null,
) {
    enum class State { STOPPED, STARTING, RUNNING, ERROR }

    private val _state = MutableStateFlow(State.STOPPED)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _notifications = MutableSharedFlow<PhoneNodeEvent>(extraBufferCapacity = 64)
    val notifications: SharedFlow<PhoneNodeEvent> = _notifications.asSharedFlow()

    // Room registry — replaces hardcoded nexusRoom/terminalRoom fields
    private val activeRooms = mutableMapOf<String, RoomEngine>()
    private val passivatedRooms = mutableSetOf<String>()
    private var _roomsOnlyMode = false

    var companion: CompanionEngine? = null
        private set

    /** The headline sync client, created when betweenClient is provided and connected. */
    var headlineSyncClient: BetweenHeadlineSyncClient? = null
        private set
    private var headlinePublishJob: Job? = null
    var studySync: StudySyncLayer? = null
    private var studySyncJob: Job? = null
    /** Account userId that owns the Study when logged into a home zone (else null →
     *  companion soul DID owns it). Seeded from the ctor (cold start) and updated
     *  by setStudyAccount after a fresh mcp.login. */
    private var studyAccountUserId: String? = accountUserId
    /** Auth token for study sync (session token from mcp.login, or device pairing
     *  token). The server peer drops unauthenticated study messages. */
    private var studyAuthToken: String? = null

    /** A Between client adopted AFTER construction — the relay-login path builds
     *  its authenticated tunnel client during node boot, long after this node was
     *  constructed (the ctor [betweenClient] is null there). Adopting it brings up
     * presence/exchange/study-sync on the relay leg. */
    private var attachedBetween: BetweenClient? = null
    private val activeBetween: BetweenClient? get() = attachedBetween ?: betweenClient

    /**
     * Adopt an already-connected Between client (e.g. the relay tunnel leg) and
     * start the Between subsystems on it. Safe to call once after boot: with a
     * null ctor client nothing was started earlier, so this is the first start.
     */
    fun attachBetweenClient(bc: BetweenClient) {
        attachedBetween = bc
        startBetweenSync()
    }

    /**
     * The userId that OWNS the Study: the account id when logged into a home zone
     * (stable across the user's devices, matches the zone's account-keyed Study),
     * else the companion soul DID in pure-local mode. Used for both local Study
     * writes and the CRDT sync advertisement.
     */
    fun studyUserDid(): String =
        studyAccountUserId ?: companion?.soulManifest?.did ?: "local-user"

    /** Emit a system prose line into the room feed (visible in LocalRoomScreen).
     *  Used by sync/connectivity surfaces so failures are never silent. */
    fun emitSystemProse(text: String) {
        scope.launch { _notifications.emit(PhoneNodeEvent.Prose("system", text)) }
    }

    /**
     * Adopt the home-zone account as the Study owner. Re-keys any Study items
     * authored before this account was known (soul DID / "local-user") to the
     * account id so pre-account notes follow the user to the zone (option a).
     */
    fun setStudyAccount(accountUserId: String?, authToken: String? = null) {
        if (!authToken.isNullOrBlank()) studyAuthToken = authToken
        if (accountUserId.isNullOrBlank() || accountUserId == studyAccountUserId) return
        val soulDid = companion?.soulManifest?.did ?: "local-user"
        val prevOwner = studyAccountUserId ?: soulDid
        studyAccountUserId = accountUserId
        val store = studyStore
        val froms = if (store != null)
            linkedSetOf(prevOwner, soulDid, "local-user").filter { it != accountUserId }
        else emptyList()
        scope.launch {
            if (store != null) {
                for (from in froms) {
                    try { store.rekeyUserDid(from, accountUserId) } catch (_: Exception) { /* best-effort */ }
                }
            }
            // Re-advertise under the ACCOUNT: on a fresh mcp.login the account id
            // arrives DURING node boot, after the sync layer was already built with
            // the cold userDid — so rebuild it now under studyUserDid() (=account).
            startStudySync()
        }
    }

    /**
     * (Re)start the Study-sync layer under the current [studyUserDid]. Idempotent:
     * tears down any running layer first. No-op if Between isn't connected yet
     * (startBetweenSync will call it once the client is up).
     */
    private fun startStudySync() {
        val bc = activeBetween ?: return
        if (!bc.isConnected) return
        val store = studyStore ?: return
        studySyncJob?.cancel(); studySyncJob = null
        studySync?.stopListening(); studySync = null
        store.setDeviceId(nodeId)
        val ss = StudySyncLayer(bc, store, nodeId, zoneId ?: familyId, studyUserDid(), scope,
            authToken = studyAuthToken ?: deviceToken)
        // Surface sync outcomes as room prose — merges tell the user their Study
        // moved; a CONCURRENT conflict keeps the local copy and must be VISIBLE
        // (silent conflict-drop was a wired-but-dead audit find).
        ss.onSyncEvent { ev ->
            val text = when (ev) {
                is org.wyrdsekai.app.engine.study.SyncEvent.ItemsMerged ->
                    "Your Study synced ${ev.count} change(s) from your home zone."
                is org.wyrdsekai.app.engine.study.SyncEvent.ConflictsDetected ->
                    "Study sync: ${ev.count} conflicting edit(s) — kept your local version."
            }
            emitSystemProse(text)
        }
        ss.startListening()
        studySync = ss
        println("[StudySync] up: device=$nodeId household=${zoneId ?: familyId} user=${studyUserDid()}")
        studySyncJob = scope.launch {
            while (isActive) {
                // println → logcat (System.out): the broadcast is the sync heartbeat;
                // a silently-swallowed failure here looks like "subscribed but mute"
                // from outside (cost a debugging round on the fts5-less emulator).
                try {
                    ss.broadcastState()
                } catch (e: Exception) {
                    println("[StudySync] state broadcast FAILED: $e")
                }
                delay(STUDY_SYNC_INTERVAL_MS)
            }
        }
    }

    /** Presence manager, created when betweenClient is provided and connected. */
    var presenceManager: PresenceManager? = null
        private set

    /** MCP gateway for direct and proxy MCP calls. Created when Between is available. */
    var mcpGateway: McpGatewayLite? = null
        private set

    /** Phone Dock for A2A message quarantine/inbox. Created when Between is connected. */
    var phoneDock: PhoneDock? = null
        private set

    /** Item exchange manager for Between item transfers. Created when Between is connected. */
    var itemExchange: ItemExchangeManager? = null
        private set

    /** Warm handoff manager for device switching. Created when Between is connected. */
    var warmHandoff: WarmHandoffManager? = null
        private set

    /** Household event listener for household-wide events. Created when Between is connected. */
    var householdEventListener: HouseholdEventListener? = null
        private set

    /** Bud delegation for routing COMPLEX queries to the server companion. Created during Between sync. */
    var budDelegation: BudDelegation? = null
        internal set

    /** Active visiting room proxies, keyed by roomId. */
    private val visitingProxies = mutableMapOf<String, VisitingRoomProxy>()

    /** Whether the player is currently visiting a server room via WebSocket. */
    private var _visitingServerRoom: String? = null
    private var serverMessageUnsub: (() -> Unit)? = null
    private var serverIdCounter = 0L
    private fun nextServerId(): String = "phone-${++serverIdCounter}"

    /** The server room ID currently being visited, or null. */
    val visitingServerRoom: String? get() = _visitingServerRoom

    private var currentRoomId: String = "study"
    private var tierListenerJob: Job? = null

    // Backwards-compatible accessors
    @Deprecated("Use activeRooms[\"home\"] instead", replaceWith = ReplaceWith("activeRooms[\"home\"]"))
    val nexusRoom: RoomEngine? get() = activeRooms["home"]
    val terminalRoom: RoomEngine? get() = activeRooms["terminal"]

    /** Current tier (from TierManager, or T1 if no manager). */
    val currentTier: Tier get() = tierManager?.currentTier?.value ?: Tier.T1

    /** Current tier config. */
    val currentTierConfig: TierConfig get() = tierManager?.config?.value ?: TierConfig.T1

    fun start() {
        _state.value = State.STARTING
        scope.launch {
            try {
                // Initialize tier
                tierManager?.initialize()
                val tier = currentTier

                // Boot rooms for current tier
                bootRoomsForTier(tier)

                // Spawn companion in Study (player's home base on phone)
                val companionRoom = activeRooms["study"] ?: activeRooms["home"]!!
                val comp = CompanionEngine(
                    profile = Companions.create(companionName),
                    roomEngine = companionRoom,
                    inferenceClient = inferenceClient,
                    inferenceBaseUrl = inferenceBaseUrl,
                    vitalityStore = vitalityStore,
                    scope = scope,
                    soulManifestStore = soulManifestStore,
                )
                companion = comp
                // #7 (2026-07-19 OSS hardening) — when connected to a household,
                // give the companion a server-side soul sink so phone-side soul
                // evolution (PhoneForge on sleep) is pushed back, not stranded
                // on-device. HttpSoulManifestStore.save() → SoulClient.syncManifest.
                val srv = serverUrl
                val tok = deviceToken
                if (srv != null && tok != null) {
                    comp.serverSoulStore = HttpSoulManifestStore(SoulClient(srv), tok)
                }
                // Activate Study command mode for tiny models (0.6B or smaller)
                if (modelTier == "tiny") {
                    comp.studyCommandMode = true
                }
                // Wire Study store for journal/note/search operations
                comp.studyStore = studyStore
                // Wire Phone Oracle for local predictions
                if (studyStore != null) {
                    comp.phoneOracle = org.wyrdsekai.app.engine.oracle.PhoneOracle(
                        studyStore!!, nodeId, comp.soulManifest?.did ?: "local-user",
                    )
                }
                comp.start()

                // Wait for companion to be confirmed in room
                comp.enteredRoom.await()

                // If no soul was restored from store, load bootstrap manifest
                if (comp.soulManifest == null) {
                    comp.loadSoul(BootstrapSoulManifest.MANIFEST)
                }

                // Wire Between headline sync if client is connected
                startBetweenSync()

                // If Between wiring didn't create a BudDelegation (e.g., no NATS),
                // still create an HTTP-only delegation if we have server credentials.
                if (comp.budDelegation == null && serverUrl != null && deviceToken != null) {
                    val httpDelegation = BudDelegation(
                        between = null,
                        nodeId = nodeId,
                        familyId = familyId,
                        serverUrl = serverUrl,
                        deviceToken = deviceToken,
                    )
                    budDelegation = httpDelegation
                    comp.budDelegation = httpDelegation
                }

                // Listen for tier changes
                tierManager?.let { listenForTierChanges(it) }

                // Start resource monitoring
                tierManager?.startMonitoring()

                _state.value = State.RUNNING
            } catch (e: Exception) {
                _error.value = e.message
                _state.value = State.ERROR
            }
        }
    }

    /**
     * Start in rooms-only mode: boot rooms and tier listener, but no companion or
     * notification wiring. Used by tests that verify tier transitions and room lifecycle.
     */
    internal suspend fun startRoomsOnly() {
        _roomsOnlyMode = true
        _state.value = State.STARTING
        tierManager?.initialize()
        bootRoomsForTier(currentTier)
        tierManager?.let { listenForTierChanges(it) }
        _state.value = State.RUNNING
    }

    fun stop() {
        tierManager?.stopMonitoring()
        tierListenerJob?.cancel()
        stopBetweenSync()
        // Clean up server room visit
        serverMessageUnsub?.invoke()
        serverMessageUnsub = null
        _visitingServerRoom = null
        companion?.shutdown()
        for (room in activeRooms.values) {
            room.shutdown()
        }
        companion = null
        activeRooms.clear()
        passivatedRooms.clear()
        _state.value = State.STOPPED
    }

    /** Get the currently active room engine. */
    fun currentRoom(): RoomEngine? = activeRooms[currentRoomId]

    /** Get all active room IDs. */
    fun activeRoomIds(): Set<String> = activeRooms.keys.toSet()

    /** Get all passivated room IDs. */
    fun passivatedRoomIds(): Set<String> = passivatedRooms.toSet()

    /** Handle a player saying something in the current room. */
    suspend fun say(entityId: String, entityName: String, text: String) {
        if (_visitingServerRoom != null) {
            phoneLog("say() visiting mode: room=${_visitingServerRoom}, conn=${serverConnection != null}, connected=${serverConnection?.isConnected}")
            serverConnection?.send(C2SMessage.Say(
                id = nextServerId(),
                roomId = _visitingServerRoom ?: "",
                text = text,
            ))
            return
        }
        currentRoom()?.send(RoomEngineCommand.SayInRoom(entityId, entityName, text))
    }

    /** Handle a player emoting in the current room. */
    suspend fun emote(entityId: String, entityName: String, text: String) {
        if (_visitingServerRoom != null) {
            // Server doesn't have a distinct emote C2S type; send as say with emote prefix
            serverConnection?.send(C2SMessage.Say(
                id = nextServerId(),
                roomId = _visitingServerRoom ?: "",
                text = ":$text",
            ))
            return
        }
        currentRoom()?.send(RoomEngineCommand.EmoteInRoom(entityId, entityName, text))
    }

    /** Handle a player moving between rooms. */
    suspend fun go(entityId: String, entityName: String, direction: String) {
        // If currently visiting a server room, route navigation through server
        if (_visitingServerRoom != null) {
            // Special case: "back" / "home" return to the local Home room
            if (direction == "back" || direction == "home") {
                returnFromServerRoom()
                return
            }
            // Forward the Go command to the server
            val conn = serverConnection ?: return
            conn.send(C2SMessage.Go(
                id = nextServerId(),
                roomId = _visitingServerRoom ?: "",
                direction = direction,
            ))
            return
        }

        val room = currentRoom() ?: return
        val exit = room.state.value.exits[direction]
        if (exit == null) {
            _notifications.emit(PhoneNodeEvent.Error("no_exit", "There is no exit in that direction."))
            return
        }

        // Check if target is a server room (prefix "server:")
        if (exit.targetRoom.startsWith("server:")) {
            val serverRoomId = exit.targetRoom.removePrefix("server:")
            visitServerRoom(entityId, entityName, serverRoomId, direction)
            return
        }

        // Check if target room is passivated
        if (exit.targetRoom in passivatedRooms) {
            _notifications.emit(PhoneNodeEvent.Error("room_passivated",
                "That area is resting. It will wake when more resources are available."))
            return
        }

        // Check if target room exists
        if (exit.targetRoom !in activeRooms) {
            _notifications.emit(PhoneNodeEvent.Error("no_room",
                "That room isn't available at this tier."))
            return
        }

        // Leave current room
        room.send(RoomEngineCommand.LeaveRoom(entityId, entityName, direction))

        // Enter target room
        currentRoomId = exit.targetRoom
        val targetRoom = currentRoom()
        if (targetRoom != null) {
            targetRoom.send(RoomEngineCommand.EnterRoom(entityId, entityName, "player", direction))
            val snapshot = targetRoom.state.value.toSnapshot()
            _notifications.emit(PhoneNodeEvent.RoomChanged(snapshot.copy(
                exits = snapshot.exits.filter { it.targetRoom in activeRooms || it.targetRoom in passivatedRooms || it.targetRoom.startsWith("server:") },
            )))
        }
    }

    /** Handle looking around the current room. Filters exits to available rooms only. */
    suspend fun look(): RoomSnapshot? {
        if (_visitingServerRoom != null) {
            serverConnection?.send(C2SMessage.Look(
                id = nextServerId(),
                roomId = _visitingServerRoom ?: "",
            ))
            // Server responds async via S2C message → wireServerMessages will emit RoomChanged
            return null
        }
        val snapshot = currentRoom()?.state?.value?.toSnapshot() ?: return null
        return snapshot.copy(
            exits = snapshot.exits.filter { it.targetRoom in activeRooms || it.targetRoom in passivatedRooms || it.targetRoom.startsWith("server:") },
        )
    }

    /**
     * Player inventory — objects taken from rooms, keyed by object id.
     * The RoomEngine removes a taken object from the room's object map
     * ([RoomState.apply] of ObjectTaken) but tracks no "held" state, so the
     * node owns the carry list. Updated on [take] (add) and [drop] (remove).
     */
    private val heldItems = mutableMapOf<String, String>()

    /** Snapshot of carried object names (display order = insertion order). */
    fun inventory(): List<String> = heldItems.values.toList()

    /** Handle taking an object. Records it in [heldItems] on success. */
    suspend fun take(entityId: String, objectName: String) {
        val room = currentRoom() ?: return
        // Capture the object's id+name BEFORE the take removes it from the room.
        val target = room.state.value.objects.values.find {
            it.name.equals(objectName, ignoreCase = true)
        }
        val result = room.send(RoomEngineCommand.TakeObject(entityId, objectName))
        if (result is org.wyrdsekai.app.engine.room.RoomEngineResponse.Rejected) {
            _notifications.emit(PhoneNodeEvent.Error(result.code, result.reason))
            return
        }
        if (target != null) {
            heldItems[target.id] = target.name
        }
    }

    /** Handle using an object. */
    suspend fun use(entityId: String, objectName: String, target: String?) {
        currentRoom()?.send(RoomEngineCommand.UseObject(entityId, objectName, target))
    }

    /** Local player display name, mutable via [rename]. SPEC §7.4. */
    var playerName: String = "You"
        private set

    /**
     * Drop an object back into the current room. —
     * symmetric with [take]. Surfaces a Rejected response as an error event.
     */
    suspend fun drop(entityId: String, objectName: String) {
        // Resolve against the carry list so we drop the real object id (and can
        // remove it from inventory on success).
        val held = heldItems.entries.find { it.value.equals(objectName, ignoreCase = true) }
        val result = currentRoom()?.send(
            RoomEngineCommand.DropObject(
                entityId = entityId,
                objectName = held?.value ?: objectName,
                objectId = held?.key ?: objectName,
                description = "",
                takeable = true,
            )
        )
        if (result is org.wyrdsekai.app.engine.room.RoomEngineResponse.Rejected) {
            _notifications.emit(PhoneNodeEvent.Error(result.code, result.reason))
            return
        }
        if (held != null) {
            heldItems.remove(held.key)
        }
    }

    /**
     * Passive observation — return the description of a room object,
     * entity, or "me" (self). Mirrors the TS [examine] surface and the
     * server-side [ExamineLookup] semantics. Returns null when nothing
     * matches; caller surfaces the appropriate prose.
     *
     */
    suspend fun examine(target: String): ExamineResult? {
        val trimmed = target.trim()
        if (trimmed.isEmpty()) return null
        val lower = trimmed.lowercase()
        if (lower == "me" || lower == "self" || lower == "myself") {
            return ExamineResult(playerName, "")
        }
        val room = currentRoom() ?: return null
        val state = room.state.value
        for (obj in state.objects.values) {
            val n = obj.name.lowercase()
            if (n.isNotEmpty() && (n.contains(lower) || lower.contains(n))) {
                return ExamineResult(obj.name, obj.description ?: "")
            }
        }
        for (ent in state.entities.values) {
            val n = ent.name.lowercase()
            if (n.isNotEmpty() && (n.contains(lower) || lower.contains(n))) {
                return ExamineResult(ent.name, ent.description ?: "")
            }
        }
        return null
    }

    /**
     * Rename the local player. Validates the
     * new name and updates [playerName]. Returns a typed [RenameResult]
     * so callers can surface usage hints on rejection.
     */
    fun rename(newName: String): RenameResult {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) {
            return RenameResult.Rejected("Usage: rename me <new-name>")
        }
        if (trimmed.length > 40) {
            return RenameResult.Rejected("Name too long (max 40 chars).")
        }
        if (trimmed.any { it.code < 0x20 || it.code == 0x7f }) {
            return RenameResult.Rejected("Name contains invalid characters.")
        }
        playerName = trimmed
        return RenameResult.Ok(trimmed)
    }

    /** Result of [examine]. */
    data class ExamineResult(val name: String, val description: String)

    /** Result of [rename]. */
    sealed class RenameResult {
        data class Ok(val newName: String) : RenameResult()
        data class Rejected(val message: String) : RenameResult()
    }

    // ── Between headline sync ────────────────────────────────────────────

    /**
     * Creates and starts a BetweenHeadlineSyncClient if the Between transport
     * is provided and connected. Also starts a periodic headline publish job
     * that broadcasts the companion's vitality snapshot every 30 seconds.
     *
     * Additionally wires all Between subsystems:
     * - ItemExchangeManager for item exchange between nodes
     * - PhoneDock for A2A message quarantine/inbox
     * - McpGatewayLite for proxy MCP calls
     * - HouseholdEventListener for household-wide events
     * - WarmHandoffManager for device switching
     * - BudDelegation for routing COMPLEX queries to server companion
     */
    private fun startBetweenSync() {
        val bc = activeBetween ?: return
        if (!bc.isConnected) return

        // Start presence manager and announce online
        val presence = PresenceManager(bc, nodeId, familyId)
        presence.startListening()
        presence.announce("online")
        presenceManager = presence

        val sync = BetweenHeadlineSyncClient(bc, nodeId, familyId)
        sync.startListening()
        headlineSyncClient = sync

        // ── Item Exchange (T2+) ─────────────────────────────────────────
        val exchange = ItemExchangeManager(bc, nodeId, familyId)
        exchange.startListening()
        itemExchange = exchange

        // ── Phone Dock (T2+) ────────────────────────────────────────────
        val dock = PhoneDock(bc, nodeId, familyId)
        dock.startListening()
        phoneDock = dock

        // ── MCP Gateway (proxy mode via Between) ────────────────────────
        val gateway = mcpGatewayLiteFactory?.invoke() ?: McpGatewayLite()
        gateway.betweenClient = bc
        gateway.nodeId = nodeId
        gateway.householdId = familyId
        gateway.registerDefaults()
        mcpGateway = gateway

        // ── Household Event Listener ────────────────────────────────────
        val listener = HouseholdEventListener(bc, familyId) { event ->
            scope.launch {
                _notifications.emit(PhoneNodeEvent.HouseholdEventReceived(event))
            }
        }
        listener.startListening()
        householdEventListener = listener

        // ── Warm Handoff Manager ────────────────────────────────────────
        val handoff = WarmHandoffManager(bc, nodeId, familyId)
        handoff.startListening()
        warmHandoff = handoff

        // ── Bud Delegation (COMPLEX query routing to server) ─────────
        val delegation = BudDelegation(bc, nodeId, familyId, serverUrl, deviceToken)
        delegation.startListening()
        budDelegation = delegation
        companion?.budDelegation = delegation

        // ── Phone Oracle (server prediction sync via Between) ───────
        companion?.phoneOracle?.startListening(bc, familyId)

        // ── Study Sync (CRDT convergence with peers AND the home zone) ──
        // Scope by the zone id when we have one: the relay
        // forwards between.{zone}.> and the server peer keys on the zone, so
        // 'default' household traffic would never reach the server. Tick local
        // writes with THIS node's slot; advertise state now + periodically so the
        // zone pushes what we're missing and pulls what it lacks.
        startStudySync()

        // Periodic headline publishing — broadcasts current state to siblings
        headlinePublishJob = scope.launch {
            while (isActive) {
                delay(HEADLINE_PUBLISH_INTERVAL_MS)
                val comp = companion ?: continue
                val vState = comp.vitality
                val headline = Headline(
                    budDid = nodeId,
                    summary = "In room ${currentRoomId}, ${comp.vitality.energy.let {
                        if (it > 0.6) "energetic" else if (it > 0.3) "steady" else "tired"
                    }}",
                    vitalitySnapshot = mapOf(
                        "energy" to vState.energy.toFloat(),
                        "confidence" to vState.confidence.toFloat(),
                        "focus" to vState.focus.toFloat(),
                        "rapport" to vState.rapport.toFloat(),
                    ),
                    itemCount = heldItems.size,
                    timestamp = kotlin.time.Clock.System.now().toEpochMilliseconds(),
                )
                try {
                    sync.postHeadline(headline)
                } catch (_: Exception) {
                    // Non-fatal — headline publish failure doesn't affect operation
                }
            }
        }
    }

    /**
     * Post a headline immediately (for use by tests or explicit triggers).
     */
    suspend fun postHeadline(headline: Headline) {
        headlineSyncClient?.postHeadline(headline)
    }

    private fun stopBetweenSync() {
        headlinePublishJob?.cancel()
        headlinePublishJob = null
        studySyncJob?.cancel()
        studySyncJob = null
        studySync?.stopListening()
        studySync = null
        headlineSyncClient?.stopListening()
        headlineSyncClient = null
        presenceManager?.announce("offline")
        presenceManager?.stopListening()
        presenceManager = null

        // Stop all Between subsystems
        itemExchange?.stopListening()
        itemExchange = null
        phoneDock?.stopListening()
        phoneDock = null
        mcpGateway?.shutdown()
        mcpGateway = null
        householdEventListener?.stopListening()
        householdEventListener = null
        warmHandoff?.stopListening()
        warmHandoff = null
        budDelegation?.stopListening()
        budDelegation = null

        // Shutdown visiting room proxies
        for (proxy in visitingProxies.values) {
            proxy.shutdown()
        }
        visitingProxies.clear()
    }

    // ── Visiting room support ────────────────────────────────────────────

    /**
     * Visit a room hosted on another node via Between proxy.
     *
     * Creates a VisitingRoomProxy that subscribes to room events and
     * forwards commands over the Between network. Requires a connected
     * BetweenClient (T2+).
     *
     * @param roomId The ID of the remote room to visit
     * @param hostNodeId The node ID hosting the room (unused in subject but useful for logging)
     * @return The VisitingRoomProxy, or null if Between is unavailable
     */
    fun visitRoom(roomId: String, hostNodeId: String): VisitingRoomProxy? {
        val bc = activeBetween ?: return null
        if (!bc.isConnected) return null

        // If already visiting this room, return existing proxy
        visitingProxies[roomId]?.let { return it }

        val proxy = VisitingRoomProxy(roomId, bc, familyId, scope)
        proxy.startListening()
        visitingProxies[roomId] = proxy
        return proxy
    }

    /**
     * Stop visiting a remote room and clean up its proxy.
     */
    fun leaveVisitingRoom(roomId: String) {
        val proxy = visitingProxies.remove(roomId)
        proxy?.shutdown()
    }

    /**
     * Get all active visiting room proxies.
     */
    fun visitingRoomIds(): Set<String> = visitingProxies.keys.toSet()

    // ── Server room visiting (WebSocket) ────────────────────────────────

    /**
     * Visit a room on the household server via WebSocket.
     *
     * Opens the server connection (if not already connected), sends a Look command
     * for the target room, and enters server-visiting mode. All subsequent say/go/look
     * commands are proxied to the server until returnFromServerRoom() is called.
     *
     * The server authenticates via device_token query parameter.
     *
     * @param entityId   The player entity ID
     * @param entityName The player display name
     * @param serverRoomId The room ID on the server (e.g., "nexus")
     * @param direction  The direction the player exited through
     */
    private suspend fun visitServerRoom(entityId: String, entityName: String, serverRoomId: String, direction: String) {
        phoneLog("visitServerRoom called: room=$serverRoomId, serverUrl=$serverUrl, hasToken=${deviceToken != null}")
        // Create server connection on-demand if not already connected
        if (serverConnection == null || serverConnection?.isConnected != true) {
            if (serverUrl == null || deviceToken == null) {
                phoneLog("visitServerRoom: no serverUrl or deviceToken")
                _notifications.emit(PhoneNodeEvent.Error("no_server",
                    "Not paired with a household server. Pair first to visit server rooms."))
                return
            }
            try {
                val wsUrl = serverUrl!!.replace("http://", "ws://").replace("https://", "wss://")
                    .trimEnd('/') + "/ws?device_token=$deviceToken"
                phoneLog("visitServerRoom: connecting to $wsUrl")
                val conn = WebSocketServerConnection(wsUrl, scope)
                conn.connect()
                phoneLog("visitServerRoom: connected!")
                serverConnection = conn
            } catch (e: Exception) {
                phoneLog("visitServerRoom: FAILED: ${e::class.simpleName}: ${e.message}")
                _notifications.emit(PhoneNodeEvent.Error("server_connect_failed",
                    "Could not connect to household server: ${e.message}"))
                return
            }
        }
        val conn = serverConnection!!
        phoneLog("visitServerRoom: conn established, leaving local room")

        // Leave the current local room
        val room = currentRoom()
        room?.send(RoomEngineCommand.LeaveRoom(entityId, entityName, direction))

        // Clear the screen — emit a ServerRoomEntered event so UI knows to reset
        _notifications.emit(PhoneNodeEvent.ServerRoomEntered(serverRoomId))

        // Enter server-visiting mode
        _visitingServerRoom = serverRoomId
        phoneLog("visitServerRoom: server-visiting mode ON, room=$serverRoomId")

        // Subscribe to server messages for the duration of the visit
        wireServerMessages(conn)
        phoneLog("visitServerRoom: wireServerMessages done, sending Look")

        // Tell the server to navigate to this room (the server Go handler will
        // send back a room_state S2C message with the full room snapshot)
        conn.send(C2SMessage.Look(
            id = nextServerId(),
            roomId = serverRoomId,
        ))

        _notifications.emit(PhoneNodeEvent.Prose(
            speaker = "narrator",
            text = "You step outside into the household...",
        ))
    }

    /**
     * Return from a server room visit to the local Home room.
     * Unsubscribes from server messages and restores the local room state.
     */
    suspend fun returnFromServerRoom() {
        if (_visitingServerRoom == null) return

        val oldRoom = _visitingServerRoom
        _visitingServerRoom = null

        // Unsubscribe from server messages
        serverMessageUnsub?.invoke()
        serverMessageUnsub = null

        // Signal UI to clear server room content
        _notifications.emit(PhoneNodeEvent.ServerRoomLeft(oldRoom ?: ""))

        // Re-enter home room
        currentRoomId = "home"
        val homeRoom = activeRooms["home"]
        if (homeRoom != null) {
            homeRoom.send(RoomEngineCommand.EnterRoom("player", "You", "player", "outside"))
            _notifications.emit(PhoneNodeEvent.Prose(
                speaker = "narrator",
                text = "You return home.",
            ))
            val snapshot = homeRoom.state.value.toSnapshot()
            _notifications.emit(PhoneNodeEvent.RoomChanged(snapshot.copy(
                exits = snapshot.exits.filter { it.targetRoom in activeRooms || it.targetRoom in passivatedRooms || it.targetRoom.startsWith("server:") },
            )))
        }
    }

    /**
     * Wire server S2C messages to PhoneNode notifications while visiting.
     * Maps server prose/room_state/error messages to PhoneNodeEvents.
     *
     * Uses tryEmit (non-suspend) instead of scope.launch + emit to avoid
     * async dispatch issues — the handler callback runs on the WebSocket
     * recv thread and cannot suspend. tryEmit succeeds immediately when
     * the SharedFlow buffer (extraBufferCapacity=64) has space.
     */
    private fun wireServerMessages(conn: ServerConnection) {
        serverMessageUnsub?.invoke()
        serverMessageUnsub = conn.onMessage { msg ->
            phoneLog("wireServerMessages: received ${msg::class.simpleName}")
            if (_visitingServerRoom == null) return@onMessage
            when (msg) {
                is S2CMessage.Prose -> {
                    phoneLog("wireServerMessages: prose from ${msg.speaker}: ${msg.text.take(50)}")
                    _notifications.tryEmit(PhoneNodeEvent.Prose(
                        speaker = msg.speaker,
                        text = msg.text,
                    ))
                }
                is S2CMessage.RoomState -> {
                    _visitingServerRoom = msg.room.roomId
                    // Inject a "back" exit pointing home so the player can return
                    val exits = msg.room.exits.toMutableList()
                    if (exits.none { it.direction == "back" || it.direction == "home" }) {
                        exits.add(Exit("back", "home", "Return to your phone"))
                    }
                    _notifications.tryEmit(PhoneNodeEvent.RoomChanged(msg.room.copy(exits = exits)))
                }
                is S2CMessage.Error -> {
                    _notifications.tryEmit(PhoneNodeEvent.Error(msg.code, msg.message))
                }
                is S2CMessage.StateChange -> {
                    _notifications.tryEmit(PhoneNodeEvent.StateChanged(msg.description))
                }
                else -> {} // Other S2C messages not relevant while visiting
            }
        }
    }

    // ── Room definitions ────────────────────────────────────────────────

    /**
     * Room definitions by tier. Rooms are cumulative — T2 includes all T1 rooms.
     * Study is the starting room on phone. Home is only available at T2+.
     */
    internal fun roomsForTier(tier: Tier): List<String> = when (tier) {
        Tier.T0 -> listOf("study")
        Tier.T1 -> listOf("study")
        Tier.T2 -> listOf("study", "home", "terminal", "dream-chamber", "mailroom")
        Tier.T3 -> listOf("study", "home", "terminal", "dream-chamber", "mailroom",
            "soul-mirror", "memory-well", "scrying-pool")
    }

    // ── Tier transitions ────────────────────────────────────────────────

    private fun listenForTierChanges(tm: TierManager) {
        tierListenerJob?.cancel()
        tierListenerJob = scope.launch {
            tm.transitions.collect { transition ->
                handleTierTransition(transition)
            }
        }
    }

    private suspend fun handleTierTransition(transition: TierTransition) {
        if (_state.value != State.RUNNING) return

        val newRoomIds = roomsForTier(transition.to).toSet()
        val currentRoomIds = activeRooms.keys.toSet()

        // Passivate rooms that are above the new tier
        val toPassivate = currentRoomIds - newRoomIds
        for (roomId in toPassivate) {
            passivateRoom(roomId)
        }

        // Reactivate or boot rooms for the new tier
        val toActivate = newRoomIds - currentRoomIds
        for (roomId in toActivate) {
            if (roomId in passivatedRooms) {
                reactivateRoom(roomId)
            } else {
                bootRoom(roomId)
            }
        }

        // If current room was passivated, move player to home (or companion-only at T0)
        if (currentRoomId in passivatedRooms || currentRoomId !in activeRooms) {
            val fallback = activeRooms.keys.firstOrNull() ?: "home"
            currentRoomId = fallback
            val room = activeRooms[fallback]
            if (room != null) {
                _notifications.emit(PhoneNodeEvent.RoomChanged(room.state.value.toSnapshot()))
            }
        }

        _notifications.emit(PhoneNodeEvent.TierChanged(transition.from, transition.to))
    }

    private fun passivateRoom(roomId: String) {
        val room = activeRooms.remove(roomId) ?: return
        // State is already persisted in the journal — just shut down the engine
        room.shutdown()
        passivatedRooms.add(roomId)
    }

    private suspend fun reactivateRoom(roomId: String) {
        passivatedRooms.remove(roomId)
        bootRoom(roomId)
    }

    // ── Room boot ───────────────────────────────────────────────────────

    private suspend fun bootRoomsForTier(tier: Tier) {
        val roomIds = roomsForTier(tier)
        for (roomId in roomIds) {
            bootRoom(roomId)
        }
    }

    private suspend fun bootRoom(roomId: String) {
        if (roomId in activeRooms) return

        val def = ROOM_DEFINITIONS[roomId] ?: return
        val scriptEngine = ScriptEngine(roomId)

        // Inject i18n translations before the script loads (Study room needs them)
        val translations = roomTranslations()[roomId]
        val script = if (translations != null && def.script != null) {
            val i18nJs = buildI18nPreamble(translations)
            i18nJs + "\n" + def.script
        } else {
            def.script
        }

        val room = RoomEngine(roomId, journal, scriptEngine, script, scope)

        // Wait for recovery from journal
        delay(100)

        // Filter exits: at T0/T1 the Study room's "out" exit targets Home which doesn't exist
        val tier = currentTier
        val tierRoomIds = roomsForTier(tier).toSet()
        val exits = if (roomId == "study" && (tier == Tier.T0 || tier == Tier.T1)) {
            def.exits.filter { it.targetRoom in tierRoomIds }
        } else {
            def.exits
        }

        // Initialize if first boot
        if (room.state.value.name.isEmpty()) {
            val roomName = if (roomId == "home") homeRoomName else def.name
            room.send(RoomEngineCommand.CreateRoom(
                name = roomName,
                description = def.description,
                zone = def.zone,
                exits = exits,
                objects = def.objects,
            ))
        }

        activeRooms[roomId] = room
        if (!_roomsOnlyMode) {
            scope.launch { wireRoomNotifications(room, roomId) }
        }
    }

    // ── Room definitions ────────────────────────────────────────────────

    internal data class RoomDefinition(
        val name: String,
        val description: String,
        val zone: String,
        val exits: List<Exit> = emptyList(),
        val objects: List<RoomObject> = emptyList(),
        val script: String? = null,
    )

    private fun phoneLog(msg: String) {
        try {
            val dir = AppProps.get("wyrdsekai.data.dir") ?: return
            AppFiles.appendText("$dir/wyrd-debug.log", "${Clock.System.now()}: $msg\n")
        } catch (_: Exception) {}
    }

    /** Execute a study action against the StudyStore, emitting results as prose. */
    private suspend fun handleStudyAction(action: String, data: Map<String, String>) {
        val store = studyStore ?: return
        // Own local writes/reads under the ACCOUNT when logged into a home zone.
        val userDid = studyUserDid()
        try {
            when (action) {
                "journal_write" -> {
                    val content = data["content"] ?: return
                    val isPrivate = data["isPrivate"] == "true"
                    store.writeJournal(userDid, content, isPrivate)
                }
                "journal_search" -> {
                    val query = data["query"] ?: return
                    val results = store.searchJournal(userDid, query, limit = 5)
                    if (results.isEmpty()) {
                        _notifications.emit(PhoneNodeEvent.Prose("narrator", "No journal entries found for \"$query\"."))
                    } else {
                        val summary = results.joinToString("\n") { "- ${it.title}" }
                        _notifications.emit(PhoneNodeEvent.Prose("narrator", "Found ${results.size} entries:\n$summary"))
                    }
                }
                "search" -> {
                    val query = data["query"] ?: return
                    val results = store.searchAll(userDid, query, limit = 5)
                    if (results.isEmpty()) {
                        _notifications.emit(PhoneNodeEvent.Prose("narrator", "No results for \"$query\"."))
                    } else {
                        val summary = results.joinToString("\n") { "- [${it.itemType}] ${it.title}" }
                        _notifications.emit(PhoneNodeEvent.Prose("narrator", "Found ${results.size} results:\n$summary"))
                    }
                }
                "note" -> {
                    val content = data["content"] ?: return
                    store.addNote(userDid, content)
                }
                "recent_journal" -> {
                    val recent = store.recentJournal(userDid, limit = 5)
                    if (recent.isEmpty()) {
                        _notifications.emit(PhoneNodeEvent.Prose("narrator", "Your journal is empty. Write something with: journal <text>"))
                    } else {
                        val summary = recent.joinToString("\n") { "- ${it.title}" }
                        _notifications.emit(PhoneNodeEvent.Prose("narrator", "Recent journal entries:\n$summary"))
                    }
                }

                // ── Onboarding actions ───────────────────────────────────
                "connect_server" -> {
                    val url = data["url"] ?: return
                    phoneLog("Onboarding: connecting to server $url")
                    AppProps.set("wyrdsekai.server.url", url)
                    AppProps.set("wyrdsekai.inference.url", url)
                    // Attempt health check
                    try {
                        val client = inferenceClient
                        // TODO: actual health check against url/health
                        // For now, store the URL and trigger household discovery
                        _notifications.emit(PhoneNodeEvent.Prose("narrator",
                            "Server address saved. Looking for your companion..."))
                        // Set companion_connected so hints update
                        activeRooms["study"]?.send(
                            org.wyrdsekai.app.engine.room.RoomEngineCommand.SetProperty("companion_connected", "server"))
                    } catch (e: Exception) {
                        _notifications.emit(PhoneNodeEvent.Prose("narrator",
                            "Could not reach that server. Check the address and try again."))
                    }
                }
                "connect_api" -> {
                    val key = data["key"] ?: return
                    val provider = data["provider"] ?: "openai"
                    val customUrl = data["customUrl"] ?: ""
                    phoneLog("Onboarding: configuring API key for provider=$provider")

                    val baseUrl = when (provider) {
                        "anthropic" -> "https://api.anthropic.com"
                        "openai" -> "https://api.openai.com"
                        "openrouter" -> "https://openrouter.ai/api"
                        "custom" -> customUrl.ifEmpty { "http://localhost:8080" }
                        else -> "https://api.openai.com"
                    }
                    AppProps.set("wyrdsekai.api.key", key)
                    AppProps.set("wyrdsekai.api.provider", provider)
                    AppProps.set("wyrdsekai.inference.url", baseUrl)
                    _notifications.emit(PhoneNodeEvent.Prose("narrator",
                        "API key configured ($provider). Your companion can now think."))
                    activeRooms["study"]?.send(
                        org.wyrdsekai.app.engine.room.RoomEngineCommand.SetProperty("companion_connected", "api"))
                }
                "set_companion_name" -> {
                    val name = data["name"] ?: return
                    phoneLog("Onboarding: companion name = $name")
                    AppProps.set("wyrdsekai.companion.name", name)
                    companion?.let { comp ->
                        // BORN AS A PARTICULAR (2026-07-17): persisted seed → the same
                        // individual across reloads; server-identical birth semantics.
                        val seed = org.wyrdsekai.app.engine.soul.TemperamentSeed.loadOrBirth(
                            AppProps.get("wyrdsekai.data.dir"))
                        val manifest = org.wyrdsekai.app.engine.soul.NamedBootstrapManifest.create(name, seed)
                        comp.loadSoul(manifest)
                    }
                }
                "oauth_openrouter" -> {
                    // OpenRouter only accepts https:443, https:3000, or
                    // http://localhost:3000 as callback URLs (no custom URI
                    // schemes). We use http://localhost:3000/callback and
                    // intercept the redirect inside an in-app WebView before
                    // it tries to actually reach localhost — see
                    // OpenRouterAuthScreen.kt.
                    phoneLog("Onboarding: starting OpenRouter OAuth PKCE")
                    val callbackUrl = org.wyrdsekai.app.inference.OpenRouterOAuth.LOOPBACK_CALLBACK
                    val (authUrl, _) = org.wyrdsekai.app.inference.OpenRouterOAuth.buildAuthUrl(callbackUrl)
                    _notifications.emit(PhoneNodeEvent.OpenBrowser(authUrl))
                }
                "oauth_openrouter_callback" -> {
                    val code = data["code"] ?: return
                    phoneLog("Onboarding: exchanging OpenRouter auth code")
                    val result = org.wyrdsekai.app.inference.OpenRouterOAuth.exchangeCode(code)
                    if (result.key != null) {
                        AppProps.set("wyrdsekai.api.key", result.key)
                        AppProps.set("wyrdsekai.api.provider", "openrouter")
                        AppProps.set("wyrdsekai.inference.url", "https://openrouter.ai/api")
                        _notifications.emit(PhoneNodeEvent.Prose("narrator",
                            "Connected to OpenRouter! Your companion is arriving..."))
                        activeRooms["study"]?.send(
                            org.wyrdsekai.app.engine.room.RoomEngineCommand.SetProperty("companion_connected", "openrouter"))
                    } else {
                        _notifications.emit(PhoneNodeEvent.Prose("narrator",
                            "Could not connect: ${result.error ?: "unknown error"}. Try again."))
                    }
                }
                "onboard_standalone" -> {
                    phoneLog("Onboarding: standalone mode")
                    // Nothing to configure — Study already works standalone
                }
            }
        } catch (e: Exception) {
            phoneLog("StudyAction error: $action — ${e.message}")
        }
    }

    /**
     * Build a JS preamble that populates __world._i18n with translations.
     * Executed before the room script loads.
     */
    private fun buildI18nPreamble(translations: Map<String, String>): String {
        val entries = translations.entries.joinToString(",\n") { (k, v) ->
            val escaped = v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
            "  \"$k\": \"$escaped\""
        }
        return "if (typeof __world !== 'undefined') { __world._i18n = {\n$entries\n}; }"
    }

    companion object {
        /** Interval between automatic headline broadcasts to siblings (30s). */
        internal const val HEADLINE_PUBLISH_INTERVAL_MS = 30_000L
        internal const val STUDY_SYNC_INTERVAL_MS = 30_000L

        /** Load locale-specific translations for rooms that need i18n. */
        internal fun roomTranslations(locale: String = "en"): Map<String, Map<String, String>> = mapOf(
            "study" to RoomScripts.studyTranslations(locale),
        )

        internal val ROOM_DEFINITIONS = mapOf(
            "study" to RoomDefinition(
                name = "The Study",
                description = "Your personal study — a quiet room with a solid desk, an open journal, shelves of collected knowledge, and a pinboard of notes and reminders. This is your home base, where thoughts are gathered and plans take shape.",
                zone = "foundation",
                exits = listOf(
                    // Exit to Home only visible at T2+ when Home room is active
                    Exit("out", "home", "Step out (available when more rooms are active)"),
                ),
                objects = listOf(
                    RoomObject("obj-journal-01", "journal", "A leather-bound journal open on the desk — write your thoughts here", false),
                    RoomObject("obj-desk-01", "desk", "A sturdy desk with pen, ink, and space for work", false),
                    RoomObject("obj-shelves-01", "shelves", "Shelves of collected knowledge — documents, references, bookmarks", false),
                    RoomObject("obj-pinboard-01", "pinboard", "A cork pinboard covered with notes and reminders", false),
                    // Phone-side mirror of the server Study's library_card scripted
                    // furnishing. Phone has no Lucene corpus, so queries fall
                    // through to local SQLite FTS5 search via STUDY_SCRIPT's
                    // `search the library for <query>` regex.
                    RoomObject("obj-library-card-01", "library card", "A small bronze card etched with reading marks — use it to search your library of saved knowledge", true),
                ),
                script = RoomScripts.STUDY_SCRIPT,
            ),
            "home" to RoomDefinition(
                name = "Home",
                description = "A warm, quiet space that feels distinctly yours. Soft light pools in the corners. A comfortable chair sits near a low table. This is where your companion lives — the starting point for everything.",
                zone = "foundation",
                exits = listOf(
                    Exit("south", "study", "Back to The Study"),
                    Exit("north", "terminal", "A corridor leads to The Terminal"),
                    Exit("east", "dream-chamber", "A soft glow emanates from the Dream Chamber"),
                    Exit("west", "mailroom", "The hum of messages drifts from the Mailroom"),
                    Exit("out", "server:nexus", "Step outside to the household"),
                ),
                objects = listOf(
                    RoomObject("obj-crystal-01", "crystal", "A pulsing crystal that reveals hidden connections", false),
                ),
                script = RoomScripts.NEXUS_SCRIPT,
            ),
            "terminal" to RoomDefinition(
                name = "The Terminal",
                description = "Banks of crystalline screens line the walls, each displaying streams of data. A command prompt blinks steadily, awaiting input.",
                zone = "foundation",
                exits = listOf(
                    Exit("south", "home", "Back to Home"),
                ),
                script = RoomScripts.TERMINAL_SCRIPT,
            ),
            "dream-chamber" to RoomDefinition(
                name = "The Dream Chamber",
                description = "A twilight room where reality softens. Constellations drift across the domed ceiling. A bed of woven light invites rest. Here, the companion sleeps, dreams, and the Forge consolidates memories.",
                zone = "kokoro",
                exits = listOf(
                    Exit("west", "home", "Back to Home"),
                ),
                objects = listOf(
                    RoomObject("obj-dreambed-01", "dreambed", "A bed of woven light — rest here to trigger a Forge cycle", false),
                ),
            ),
            "mailroom" to RoomDefinition(
                name = "The Mailroom",
                description = "Shelves of luminous envelopes line the walls, sorted by sender and urgency. A sorting desk sits in the center. Messages from other agents and systems arrive here.",
                zone = "kokoro",
                exits = listOf(
                    Exit("east", "home", "Back to Home"),
                ),
            ),
            "soul-mirror" to RoomDefinition(
                name = "The Soul Mirror",
                description = "A tall obsidian mirror stands in a circular chamber. Your reflection isn't quite right — it shows not your appearance, but your behavioral patterns, your consistency, your drift from who you were.",
                zone = "kokoro",
                exits = listOf(
                    Exit("south", "home", "Back to Home"),
                ),
                objects = listOf(
                    RoomObject("obj-mirror-01", "mirror", "An obsidian mirror that reflects behavioral patterns — dims when alignment drops", false),
                ),
            ),
            "memory-well" to RoomDefinition(
                name = "The Memory Well",
                description = "A deep stone well at the center of a quiet garden. Memories float as luminous fragments in the dark water below. Drop a memory in, or draw one out.",
                zone = "kokoro",
                exits = listOf(
                    Exit("north", "home", "Back to Home"),
                ),
            ),
            "scrying-pool" to RoomDefinition(
                name = "The Scrying Pool",
                description = "A still pool of dark water in a vaulted chamber. Touch the surface with a question, and it searches the wider world for answers.",
                zone = "world-interface",
                exits = listOf(
                    Exit("south", "home", "Back to Home"),
                ),
            ),
        )
    }

    // ── Notification wiring ─────────────────────────────────────────────

    private suspend fun wireRoomNotifications(room: RoomEngine, roomId: String) {
        room.notifications.collect { event ->
            // Only emit for the current room
            if (roomId == currentRoomId) {
                when (event) {
                    is WorldEvent.Said -> {
                        _notifications.emit(PhoneNodeEvent.Prose(
                            speaker = event.entityName,
                            text = event.text,
                        ))
                    }
                    is WorldEvent.Emoted -> {
                        _notifications.emit(PhoneNodeEvent.Prose(
                            speaker = "emote",
                            text = "${event.entityName} ${event.text}",
                        ))
                    }
                    is WorldEvent.EntityEntered -> {
                        if (event.fromDirection != "materialization") {
                            // Second-person conjugation for the player's own echo ("You enter", not "You enters") — task #30.
                            _notifications.emit(PhoneNodeEvent.Prose(
                                speaker = "narrator",
                                text = "${event.entityName} ${if (event.entityName == "You") "enter" else "enters"} from the ${event.fromDirection}.",
                            ))
                        }
                    }
                    is WorldEvent.EntityLeft -> {
                        _notifications.emit(PhoneNodeEvent.Prose(
                            speaker = "narrator",
                            text = "${event.entityName} ${if (event.entityName == "You") "leave" else "leaves"} to the ${event.direction}.",
                        ))
                    }
                    is WorldEvent.DescriptionChanged -> {
                        _notifications.emit(PhoneNodeEvent.StateChanged(event.newDescription))
                    }
                    is WorldEvent.ScriptTriggered -> {
                        if (event.scriptName == "study_action") {
                            _notifications.emit(PhoneNodeEvent.StudyAction(event.trigger, event.context))
                            // Execute study action against the store
                            handleStudyAction(event.trigger, event.context)
                        }
                    }
                    else -> {}
                }
            }
        }
    }
}

/** Events emitted by PhoneNode for the UI layer. */
sealed class PhoneNodeEvent {
    data class Prose(val speaker: String, val text: String) : PhoneNodeEvent()
    data class RoomChanged(val snapshot: RoomSnapshot) : PhoneNodeEvent()
    data class StateChanged(val description: String) : PhoneNodeEvent()
    data class TierChanged(val from: Tier, val to: Tier) : PhoneNodeEvent()
    data class Error(val code: String, val message: String) : PhoneNodeEvent()
    data class HouseholdEventReceived(val event: HouseholdEvent) : PhoneNodeEvent()

    /** Study action triggered by room script (journal_write, journal_search, etc.). */
    data class StudyAction(val action: String, val data: Map<String, String>) : PhoneNodeEvent()
    /** Request to open a URL in the system browser (OAuth flows, help links). */
    data class OpenBrowser(val url: String) : PhoneNodeEvent()
    /** Player entered a server room via WebSocket. */
    data class ServerRoomEntered(val roomId: String) : PhoneNodeEvent()
    /** Player left a server room and returned home. */
    data class ServerRoomLeft(val roomId: String) : PhoneNodeEvent()
}
