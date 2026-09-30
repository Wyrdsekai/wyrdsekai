package org.wyrdsekai.app.engine.agent

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlin.time.Clock
import kotlin.time.Instant
import org.wyrdsekai.app.engine.event.WorldEvent
import org.wyrdsekai.app.engine.persistence.SoulManifestStore
import org.wyrdsekai.app.engine.persistence.VitalityStore
import org.wyrdsekai.app.engine.room.RoomEngine
import org.wyrdsekai.app.engine.room.RoomEngineCommand
import org.wyrdsekai.app.engine.room.RoomMemoryPolicy
import org.wyrdsekai.app.engine.soul.ClientSoulManifest
import org.wyrdsekai.app.engine.soul.Headline
import org.wyrdsekai.app.engine.soul.HeadlineSyncClient
import org.wyrdsekai.app.engine.soul.PhoneFingerprint
import org.wyrdsekai.app.engine.soul.PhoneForge
import org.wyrdsekai.app.engine.soul.PhoneForgeInput
import org.wyrdsekai.app.engine.between.BudDelegation
import org.wyrdsekai.app.engine.between.BudDelegation.DelegationActionDto
import org.wyrdsekai.app.engine.between.BudDelegation.DelegationResult
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.wyrdsekai.app.engine.study.StudyGrammarGenerator
import org.wyrdsekai.app.engine.study.StudyStore
import org.wyrdsekai.app.i18n.UiStrings
import org.wyrdsekai.app.i18n.fillTemplate
import org.wyrdsekai.app.i18n.uiStringsFor
import org.wyrdsekai.app.inference.ChatMessage
import org.wyrdsekai.app.inference.CompletionOptions
import org.wyrdsekai.app.inference.InferenceClient
import org.wyrdsekai.app.inference.NowLine
import org.wyrdsekai.app.platform.AppProps
import org.wyrdsekai.app.platform.AppFiles

/**
 * Core companion engine — replaces Pekko's CompanionActor with coroutines.
 * State machine IDLE/THINKING with coroutine-based timers.
 *
 * When a soul manifest is loaded (via [loadSoul]), the engine:
 * - Passes the manifest to FullPromptAssembler for fragment retrieval + mirror calibration
 * - Applies the genome to VitalityState for personality-modulated vitality dynamics
 * - Persists manifest updates via SoulManifestStore
 *
 * Soul sync: after loadSoul or phone sleep, the manifest is persisted to the
 * SoulManifestStore (which may be an HttpSoulManifestStore for server sync).
 *
 * Phone sleep: when energy drops below [SLEEP_ENERGY_THRESHOLD] and the
 * companion has been idle for > 30s, a lightweight Forge cycle runs:
 * re-forge the manifest with current vitality, persist it, apply recovery,
 * and post a headline if available.
 */
class CompanionEngine(
    private val profile: AgentProfile,
    private val roomEngine: RoomEngine,
    private val inferenceClient: InferenceClient,
    private val inferenceBaseUrl: String,
    private val vitalityStore: VitalityStore?,
    private val scope: CoroutineScope,
    private val soulManifestStore: SoulManifestStore? = null,
    private val headlineSyncClient: HeadlineSyncClient? = null,
) {
    /** Optional capability bridge for equipment/item actions. Set post-construction via setter. */
    var capabilityBridge: CompanionCapabilityBridge? = null

    /** Optional offline queue for storing complex requests when household is unreachable. Set post-construction. */
    var offlineQueue: OfflineQueue? = null

    /** Optional bud delegation for routing COMPLEX queries to the server companion. Set post-construction. */
    var budDelegation: BudDelegation? = null

    /**
     * When true, all inference uses the Study command layer prompt (minimal, structured).
     * Set when a "tiny" tier model is loaded (0.6B or smaller). Bypasses TriageClassifier
     * and FullPromptAssembler entirely — the model operates as a room controller, not a companion.
     */
    var studyCommandMode: Boolean = false

    /** Local Study persistence. Set post-construction by PhoneNode. */
    var studyStore: StudyStore? = null

    /**
     * #7 (2026-07-19 OSS hardening) — optional server-side soul sink. When the
     * phone is connected to a household (serverUrl + device token), PhoneNode
     * sets this to an [HttpSoulManifestStore] so that phone-side soul evolution
     * (PhoneForge on sleep) is pushed back to the server. Without it, the phone
     * forged a new manifest every sleep but it only ever lived on-device — the
     * household never saw the companion's growth. Best-effort: a failed push is
     * retried on the next sleep.
     */
    var serverSoulStore: SoulManifestStore? = null

    /** Phone-side Oracle for local predictions. Set post-construction by PhoneNode. */
    var phoneOracle: org.wyrdsekai.app.engine.oracle.PhoneOracle? = null

    enum class State { IDLE, THINKING }

    private var state = State.IDLE
    var vitality = VitalityState.initial()
        private set
    /** The currently loaded soul manifest, if any. */
    var soulManifest: ClientSoulManifest? = null
        private set
    private val memoryPolicy = RoomMemoryPolicy.default()
    private var derivatives = VitalityDerivatives.zero()
    private var pendingTrigger: WorldEvent.Said? = null
    private var deferredTrigger: WorldEvent.Said? = null
    private var tickCount = 0

    // ── Agent Intelligence: drives + calibration ─────────────────────────
    var drives = DriveState.initial()
        private set
    var calibrationLedger = CalibrationLedger()
        private set
    /** Proactivity budget spent this hour. */
    private var proactivitySpent = 0.0
    /** Epoch ms when budget tracking started. */
    private var budgetEpochMs = Clock.System.now().toEpochMilliseconds()
    /** Last time a proactive action was emitted. */
    private var lastProactiveActionTime: Instant? = null
    /** Timestamp of the last human speech event (for drive evaluation). */
    private var lastHumanSpeechTime: Instant? = null
    /** Agent tier for proactivity gating (0=nascent, 1=observant, 2=trusted, 3=senior). */
    var agentTier: Int = 0

    /** Vitality snapshots for behavioral extraction (capped at 200). */
    private val vitalityHistory = mutableListOf<VitalityState>()

    /** Events accumulated since the last sleep cycle, for Forge consolidation. */
    private var eventsSinceLastSleep = mutableListOf<WorldEvent>()
    /** When the companion last completed a phone sleep cycle. */
    private var lastSleepTime: Instant? = null
    /** Timestamp of the last event processed (for idle detection). */
    private var lastEventTime: Instant? = null
    /** Whether a sleep cycle is currently in progress. */
    private var sleepInProgress = false
    /** Number of completed sleep cycles (for Forge maturity gating). */
    private var sleepCount = 0
    /** Fingerprint from the previous sleep cycle, for merge continuity. */
    private var previousFingerprint: PhoneFingerprint? = null

    private val _enteredRoom = CompletableDeferred<Unit>()

    /** Completes when the companion has entered its room during start(). */
    val enteredRoom: Deferred<Unit> = _enteredRoom

    private var debounceJob: Job? = null
    private var vitalityTickJob: Job? = null
    private var eventCollectorJob: Job? = null
    private var sleepJob: Job? = null

    /**
     * The offline catch-up while it runs. It was launched unguarded after every
     * deep turn, so a second replay walked the same pending list and she answered
     * each request twice, and a new turn could run beside it (on Android, on the
     * one JNI context). One runs at a time now: a second call waits for the one in
     * flight, a turn waits for it (processInference), and drives and sleep hold
     * off until it is done.
     */
    private var replayJob: Job? = null
    private val replaying: Boolean get() = replayJob?.isActive == true

    private val _companionSpeech = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val companionSpeech: SharedFlow<String> = _companionSpeech.asSharedFlow()

    /**
     * Load a soul manifest into this companion engine.
     * Applies genome to vitality, persists manifest.
     */
    fun loadSoul(manifest: ClientSoulManifest) {
        soulManifest = manifest
        // Apply genome to vitality dynamics
        if (manifest.genome != null) {
            vitality = vitality.withGenome(manifest.genome)
        }
        // Persist asynchronously
        scope.launch {
            try { soulManifestStore?.save(manifest) } catch (_: Exception) {}
        }
    }

    /**
     * Unload the current soul manifest.
     */
    fun unloadSoul() {
        soulManifest = null
        vitality = vitality.withGenome(null)
    }

    companion object {
        private const val FAILURE_COOLDOWN_MS = 30_000L
        private const val VITALITY_SAVE_INTERVAL = 30
        private const val GREETING_DELAY_MS = 1000L
        /** Energy threshold below which phone sleep is triggered. */
        private const val SLEEP_ENERGY_THRESHOLD = 0.15
        /** Minimum idle time (ms) before sleep can trigger. */
        private const val SLEEP_IDLE_MS = 30_000L
    }

    fun start() {
        // Load persisted vitality and soul manifest
        scope.launch {
            vitalityStore?.load(profile.entityId)?.let { vitality = it }
            // Restore soul manifest if a store is available and we don't already have one
            if (soulManifest == null && soulManifestStore != null) {
                // Look up by entityId — the DID is stored in the manifest
                val dids = soulManifestStore.listDids()
                for (did in dids) {
                    val m = soulManifestStore.load(did)
                    if (m != null && m.entityId == profile.entityId) {
                        loadSoul(m)
                        break
                    }
                }
            }
        }

        // Enter room
        roomEngine.sendAsync(RoomEngineCommand.EnterRoom(
            entityId = profile.entityId,
            entityName = profile.name,
            entityType = profile.entityType,
            fromDirection = "materialization",
        ))
        _enteredRoom.complete(Unit)

        // Subscribe to room events
        eventCollectorJob = scope.launch {
            roomEngine.notifications.collect { event -> onRoomEvent(event) }
        }

        // Vitality tick (1-second heartbeat)
        vitalityTickJob = scope.launch {
            while (isActive) {
                delay(1000)
                val prev = vitality
                vitality = vitality.tick()
                capabilityBridge?.tick(profile.entityId)
                derivatives = VitalityDerivatives.compute(prev, vitality, derivatives)
                tickCount++
                if (tickCount % VITALITY_SAVE_INTERVAL == 0) {
                    try { vitalityStore?.save(profile.entityId, vitality) } catch (_: Exception) {}
                    vitalityHistory.add(vitality)
                    if (vitalityHistory.size > 200) vitalityHistory.removeAt(0)
                }

                // Drive tick — accumulate passive drive pressure each second
                drives = drives.tick()

                // Proactivity evaluation — when any drive exceeds threshold and companion is idle
                if (state == State.IDLE && !replaying &&
                    drives.anyAbove(ProactivityJudgment.thresholdForTier(agentTier))
                ) {
                    val now = Clock.System.now()
                    val elapsedMs = now.toEpochMilliseconds() - budgetEpochMs
                    val budget = ProactivityJudgment.computeBudget(proactivitySpent, elapsedMs)

                    val result = ProactivityJudgment.evaluate(ProactivityJudgment.Context(
                        drives = drives,
                        vitality = vitality,
                        remainingBudget = budget,
                        lastProactiveAction = lastProactiveActionTime,
                        lastHumanSpeech = lastHumanSpeechTime,
                        agentEntityId = profile.entityId,
                        tier = agentTier,
                        calibration = calibrationLedger,
                        strings = strings(),
                    ))

                    if (result is ProactivityJudgment.JudgmentResult.Act) {
                        scope.launch { executeProactiveAction(result.action) }
                    }
                }

                // Sleep trigger: energy depleted, idle, soul loaded, not already sleeping
                if (vitality.energy < SLEEP_ENERGY_THRESHOLD
                    && state == State.IDLE
                    && soulManifest != null
                    && !sleepInProgress
                    && !replaying
                ) {
                    val now = Clock.System.now()
                    val lastEvent = lastEventTime
                    val idleLongEnough = lastEvent == null ||
                        (now - lastEvent).inWholeMilliseconds > SLEEP_IDLE_MS
                    if (idleLongEnough) {
                        sleepJob?.cancel()
                        sleepJob = scope.launch { initiatePhoneSleep() }
                    }
                }
            }
        }
    }

    private fun onRoomEvent(event: WorldEvent) {
        when (event) {
            is WorldEvent.Said -> {
                debugLog("onRoomEvent Said: entityId=${event.entityId} text=${event.text.take(50)}")
                if (event.entityId == profile.entityId ||
                    event.entityId == "narrator" ||
                    event.entityId == "system") return
                debugLog("Said event accepted, will process. state=$state")

                memoryPolicy.add(event)
                vitality = vitality
                    .withRapport(vitality.rapport + 0.03)
                    .withFocus(vitality.focus + 0.05)
                    .withAlignment(vitality.alignment + 0.02)

                // Drive relief: human spoke, so social pressure is relieved
                drives = drives.relieveSocial()
                lastHumanSpeechTime = Clock.System.now()

                // Track events for sleep/Forge consolidation
                eventsSinceLastSleep.add(event)
                lastEventTime = Clock.System.now()

                if (state == State.IDLE) {
                    pendingTrigger = event
                    val mod = VitalityModulation.compute(vitality, profile)
                    debounceJob?.cancel()
                    debounceJob = scope.launch {
                        delay(mod.debounceDelayMs)
                        processInference()
                    }
                } else {
                    deferredTrigger = event
                }
            }
            is WorldEvent.EntityEntered -> {
                if (event.entityType == "player" && event.entityId != profile.entityId) {
                    scope.launch {
                        delay(GREETING_DELAY_MS)
                        if (state == State.IDLE) {
                            greetPlayer(event.entityName)
                        }
                    }
                }
            }
            else -> {}
        }
    }

    private fun debugLog(msg: String) {
        try {
            val dir = AppProps.get("wyrdsekai.data.dir") ?: return
            AppFiles.appendText("$dir/wyrd-companion.log", "${Clock.System.now()}: $msg\n")
        } catch (_: Exception) {}
    }

    /**
     * Her emotes and the lines the phone speaks for her, in the language the app
     * is set to (WyrdApp publishes it as `wyrdsekai.locale`; English when unset).
     * These were English literals whatever the language. Read per line, so a
     * change in Settings applies to the next one.
     */
    private fun strings(): UiStrings = uiStringsFor(AppProps.get("wyrdsekai.locale") ?: "en")

    /**
     * Return the last 5 conversation turns as "speaker: text" strings.
     * Used by bud delegation to give the server companion recent context.
     */
    private fun getRecentHistory(): List<String> =
        memoryPolicy.hotEvents().takeLast(5).map { "${it.entityName}: ${it.text}" }

    private suspend fun processInference() {
        // A turn never overlaps the offline catch-up: wait for it to finish.
        replayJob?.join()
        val trigger = pendingTrigger ?: return
        debugLog("processInference called. trigger=${trigger.text.take(50)}")
        if (state != State.IDLE) return

        state = State.THINKING
        // Day-scale calibration (2026-07-18, parity with server CompanionActor's
        // ENERGY_DRAIN_PER_INFERENCE): 0.08 → 0.004 so inference cost keeps the same
        // proportion to the economy on every surface.
        vitality = vitality
            .withEnergy(vitality.energy - 0.004)
            .withContextBudget(vitality.contextBudget - 0.05)
            .withMomentum(vitality.momentum + 0.10)

        val snapshot = roomEngine.state.value.toSnapshot()
        val capContext = capabilityBridge?.buildCapabilityContext(profile.entityId, vitality)
        val bondCtx = buildBondContext()
        val messages = FullPromptAssembler.assemble(
            profile = profile,
            roomSnapshot = snapshot,
            recentSaid = memoryPolicy.hotEvents(),
            triggerEvent = trigger,
            vitality = vitality,
            additionalContext = capContext,
            soulManifest = soulManifest,
            oraclePredictions = phoneOracle?.allPredictions(),
            bondContext = bondCtx,
        )
        val mod = VitalityModulation.compute(vitality, profile)

        // Study command mode: 0.6B model, grammar-constrained actions, no system prompt
        if (studyCommandMode) {
            val snapshot = roomEngine.state.value.toSnapshot()

            // Generate GBNF grammar from Study room state
            val grammar = StudyGrammarGenerator.generate(snapshot.exits)

            try {
                val startMs = Clock.System.now().toEpochMilliseconds()
                debugLog("Study command mode (grammar): ${trigger.text.take(50)}")

                // Grammar mode: user message only, no system prompt → faster prompt eval.
                // Still her turn (she speaks, journals, moves): it carries the date and time.
                val response = inferenceClient.complete(
                    baseUrl = inferenceBaseUrl,
                    messages = listOf(
                        ChatMessage("user", trigger.text),
                    ),
                    options = CompletionOptions(
                        maxTokens = 64, temperature = 0.3, grammar = grammar, now = NowLine.dateTime(),
                    ),
                )
                val elapsed = Clock.System.now().toEpochMilliseconds() - startMs
                debugLog("Study response (${elapsed}ms, ${response.completionTokens} tok): ${response.content.take(100)}")

                // Parse grammar-constrained output: "say:text" → speech, "journal_write:text" → action.
                // A date line the model repeated from its request is not hers. On a line of its
                // own before the verb (a client that ignores the grammar) it hid the verb, and the
                // whole reply was spoken, "say:" and all.
                val content = ActionParser.stripNowLineEcho(response.content).trim()
                val s = strings()
                // What she writes down after a Study verb, without a date line repeated where her
                // words begin (it was written into her journal); null when that line was all there was.
                fun entryAfter(verb: String) =
                    ActionParser.stripNowLineEcho(content.removePrefix(verb).trim()).ifEmpty { null }
                if (content.startsWith("say:")) {
                    handleInferenceSuccess(content.removePrefix("say:").trim())
                } else if (content.startsWith("journal_write:")) {
                    val entry = entryAfter("journal_write:")
                    val store = studyStore
                    if (entry == null) {
                        handleInferenceSuccess("")
                    } else if (store != null) {
                        val userDid = soulManifest?.did ?: "local-user"
                        store.writeJournal(userDid, entry)
                        handleInferenceSuccess(fillTemplate(s.narrationJournalSaved, entry))
                    } else {
                        handleInferenceSuccess(fillTemplate(s.narrationJournalSaved, entry))
                    }
                } else if (content.startsWith("journal_search:")) {
                    // A date line repeated as the query was searched for and narrated back.
                    val query = entryAfter("journal_search:")
                    val store = studyStore
                    if (query == null) {
                        handleInferenceSuccess("")
                    } else if (store != null) {
                        val userDid = soulManifest?.did ?: "local-user"
                        val results = store.searchJournal(userDid, query, limit = 5)
                        if (results.isEmpty()) {
                            handleInferenceSuccess(fillTemplate(s.narrationJournalNotFound, query))
                        } else {
                            val summary = results.joinToString("\n") { "- ${it.title}" }
                            handleInferenceSuccess(fillTemplate(s.narrationJournalFound, results.size, summary))
                        }
                    } else {
                        handleInferenceSuccess(fillTemplate(s.narrationJournalSearching, query))
                    }
                } else if (content.startsWith("journal_private:")) {
                    val entry = entryAfter("journal_private:")
                    if (entry == null) {
                        handleInferenceSuccess("")
                    } else {
                        studyStore?.let { store ->
                            store.writeJournal(soulManifest?.did ?: "local-user", entry, isPrivate = true)
                        }
                        handleInferenceSuccess(s.narrationPrivateJournalSaved)
                    }
                } else if (content.startsWith("note_add:")) {
                    val note = entryAfter("note_add:")
                    if (note == null) {
                        handleInferenceSuccess("")
                    } else {
                        studyStore?.let { store ->
                            store.addNote(soulManifest?.did ?: "local-user", note)
                        }
                        handleInferenceSuccess(fillTemplate(s.narrationNoteSaved, note))
                    }
                } else if (content.startsWith("go:")) {
                    // Navigation — emit as room command
                    val direction = content.removePrefix("go:").trim()
                    roomEngine.send(RoomEngineCommand.LeaveRoom(profile.entityId, profile.name, direction))
                } else if (content.startsWith("use:")) {
                    val obj = content.removePrefix("use:").trim()
                    roomEngine.send(RoomEngineCommand.UseObject(profile.entityId, obj, null))
                } else if (content.startsWith("emote:")) {
                    handleInferenceSuccess(content.removePrefix("emote:").trim())
                } else if (content == "look") {
                    // No-op — room state already visible
                } else {
                    // Grammar verbs with no branch here (look:, note_search:, pin:, remind:) are
                    // said as they came, verb and all, and a date line repeated right after the
                    // verb was said with them. It is removed; nothing is said when it was all
                    // there was. Anything else is said as before.
                    val verb = Regex("^[a-z_]+:").find(content)?.value
                    val words = verb?.let { content.removePrefix(it) }
                    val hers = words?.let { ActionParser.stripNowLineEcho(it) }
                    when {
                        hers == null || hers == words -> handleInferenceSuccess(content)
                        hers.isEmpty() -> handleInferenceSuccess("")
                        else -> handleInferenceSuccess(verb + hers)
                    }
                }
            } catch (e: Exception) {
                debugLog("Study command FAILED: ${e.message}")
                handleInferenceError(e.message ?: "Unknown error")
            }
            return
        }

        // Triage: classify input complexity for dual inference routing
        val tier = TriageClassifier.classify(trigger.text, inferenceClient, inferenceBaseUrl)
        debugLog("Triage: ${trigger.text.take(30)} → $tier")

        when (tier) {
            TriageClassifier.Tier.ROUTINE,
            TriageClassifier.Tier.SIMPLE -> {
                // Fast local path: minimal prompt, small model
                roomEngine.send(RoomEngineCommand.EmoteInRoom(
                    entityId = profile.entityId,
                    entityName = profile.name,
                    text = strings().narrationConsiders,
                ))

                val quickMessages = listOf(
                    ChatMessage("system", "You are ${profile.name}. Respond briefly in 1-2 sentences."),
                    ChatMessage("user", "${trigger.entityName} says: ${trigger.text}"),
                )
                try {
                    debugLog("calling simple inference (maxTokens=64, temp=0.7)")
                    val response = inferenceClient.complete(
                        baseUrl = inferenceBaseUrl,
                        messages = quickMessages,
                        options = CompletionOptions(maxTokens = 64, temperature = 0.7, now = NowLine.dateTime()),
                    )
                    debugLog("simple response: ${response.content.take(100)}")
                    handleInferenceSuccess(response.content)
                } catch (e: Exception) {
                    debugLog("simple inference FAILED: ${e.message}")
                    e.printStackTrace()
                    handleInferenceError(e.message ?: "Unknown error")
                }
            }

            TriageClassifier.Tier.COMPLEX -> {
                // Delegate to server companion (full pipeline: tools, MCP, soul, memory)
                // via BudDelegation (HTTP primary, NATS fallback).
                roomEngine.send(RoomEngineCommand.EmoteInRoom(
                    entityId = profile.entityId,
                    entityName = profile.name,
                    text = strings().narrationThinkingDeeply,
                ))

                val delegationResult = budDelegation?.delegate(
                    message = trigger.text,
                    recentHistory = getRecentHistory(),
                    locale = "en",
                )

                if (delegationResult != null) {
                    debugLog("bud delegation success: ${delegationResult.text.take(200)}, ${delegationResult.actions.size} actions")
                    handleInferenceSuccess(delegationResult.text)
                    applyDelegationActions(delegationResult.actions)

                    // After successful delegation, drain any queued offline requests
                    if ((offlineQueue?.size() ?: 0) > 0) startReplay()
                } else {
                    // Delegation failed (HTTP + NATS): the household endpoint.
                    debugLog("bud delegation failed, trying the household endpoint")
                    val answer = completeAtHousehold(
                        messages,
                        CompletionOptions(
                            maxTokens = mod.maxResponseTokens, temperature = mod.temperature,
                            now = NowLine.dateTime(),
                        ),
                    )
                    if (answer != null) {
                        debugLog("deep response: ${answer.take(200)}")
                        handleInferenceSuccess(answer)
                        // Something answered, so drain any queued offline requests
                        if ((offlineQueue?.size() ?: 0) > 0) startReplay()
                    } else {
                        // Offline: queue for later, give quick acknowledgment
                        debugLog("no household answered, queueing the complex request")
                        queueAndAcknowledge(trigger)
                    }
                }
            }
        }
    }

    private suspend fun handleInferenceSuccess(content: String) {
        debugLog("handleInferenceSuccess called")
        vitality = vitality
            .withConfidence(vitality.confidence + 0.05)
            .withEnergy(vitality.energy + 0.02)

        val parseResult = ActionParser.parseAll(content)
        debugLog("parsed prose=${parseResult.prose.take(100)}, actions=${parseResult.actions.size}")

        // Speak the prose part
        if (parseResult.prose.isNotBlank()) {
            speak(parseResult.prose)
        }

        // Handle primary action
        val primary = parseResult.primaryAction
        if (primary != null) {
            handleAction(primary)
        }

        // Handle any additional actions
        for (action in parseResult.actions) {
            handleAction(action)
        }

        // Handle hints
        if (parseResult.hasHints()) {
            val now = Clock.System.now()
            val hintEvent = WorldEvent.HintsUpdated(roomEngine.roomId, now, parseResult.hints)
            // Hints are handled by the room engine via direct state update
        }

        state = State.IDLE

        // Process any deferred trigger
        val deferred = deferredTrigger
        if (deferred != null) {
            deferredTrigger = null
            pendingTrigger = deferred
            val mod = VitalityModulation.compute(vitality, profile)
            debounceJob?.cancel()
            debounceJob = scope.launch {
                delay(mod.debounceDelayMs)
                processInference()
            }
        }
    }

    private suspend fun handleAction(action: ActionParser.AgentAction) {
        val s = strings()
        when (action) {
            is ActionParser.AgentAction.Emote -> {
                roomEngine.send(RoomEngineCommand.EmoteInRoom(
                    entityId = profile.entityId,
                    entityName = profile.name,
                    text = action.text,
                ))
            }
            is ActionParser.AgentAction.Social -> {
                // Social maps to an emote with the social name as text
                roomEngine.send(RoomEngineCommand.EmoteInRoom(
                    entityId = profile.entityId,
                    entityName = profile.name,
                    text = action.name + "s",  // "nod" → "nods"
                ))
            }
            is ActionParser.AgentAction.WhisperTo -> {
                speak(fillTemplate(s.narrationWhispersTo, action.target))
                // TODO: route through WhisperInRoom when available on phone
            }
            is ActionParser.AgentAction.Equip -> {
                val bridge = capabilityBridge
                if (bridge != null) {
                    speak(bridge.handleEquip(profile.entityId, action.itemName))
                } else {
                    speak(fillTemplate(s.narrationEquips, action.itemName))
                }
            }
            is ActionParser.AgentAction.Doff -> {
                val bridge = capabilityBridge
                if (bridge != null) {
                    speak(bridge.handleDoff(profile.entityId, action.itemName))
                } else {
                    speak(fillTemplate(s.narrationRemoves, action.itemName))
                }
            }
            is ActionParser.AgentAction.Consume -> {
                val bridge = capabilityBridge
                if (bridge != null) {
                    speak(bridge.handleConsume(profile.entityId, action.itemName))
                } else {
                    speak(fillTemplate(s.narrationUses, action.itemName))
                }
                vitality = vitality.withEnergy(vitality.energy - 0.0005)
            }
            is ActionParser.AgentAction.SkillExecute -> {
                speak(fillTemplate(s.narrationUsesSkill, action.skillName))
                // TODO: wire SkillRegistry
            }
            is ActionParser.AgentAction.WorkbenchSubmit -> {
                speak(fillTemplate(s.narrationWorkbenchSubmit, action.skillName))
                // TODO: wire WorkbenchSkillExecutor
            }
            is ActionParser.AgentAction.ThinkDeeply -> {
                speak(s.narrationThinkingDeeplyAbout)
                // TODO: route to tool inference via InferenceRouter
            }
            is ActionParser.AgentAction.TellAgent -> {
                speak(fillTemplate(s.narrationSendsMessage, action.targetName))
            }
            is ActionParser.AgentAction.MakeCommitment -> {
                speak(fillTemplate(s.narrationCommitsTo, action.description))
            }
            is ActionParser.AgentAction.DelegateChain -> {
                speak(fillTemplate(s.narrationPlanning, action.goal, action.steps.size))
                // TODO: wire DelegationChainExecutor
            }
            is ActionParser.AgentAction.ZoneCommand -> {
                speak(fillTemplate(s.narrationZoneCommand, action.command))
            }
            is ActionParser.AgentAction.NotifyHuman -> {
                speak(fillTemplate(s.narrationNotification, action.message))
            }
            is ActionParser.AgentAction.CreateWatcher -> {
                speak(fillTemplate(s.narrationWatchingFor, action.name))
            }
            is ActionParser.AgentAction.CancelWatcher -> {
                speak(fillTemplate(s.narrationStopsWatching, action.watcherId))
            }
            is ActionParser.AgentAction.ScheduleSkill -> {
                speak(fillTemplate(s.narrationSchedules, action.skillId, action.interval))
            }
            is ActionParser.AgentAction.CancelSchedule -> {
                speak(fillTemplate(s.narrationCancelsSchedule, action.scheduleId))
            }
            is ActionParser.AgentAction.CodexAction -> {
                speak(fillTemplate(s.narrationCodexOn, action.operation, action.itemId))
            }
            is ActionParser.AgentAction.RequestAccess -> {
                speak(fillTemplate(s.narrationRequestsAccess, action.source, action.reason))
            }
            is ActionParser.AgentAction.CreateRoom -> {
                speak(s.narrationRoomIdeaLater)
            }
            is ActionParser.AgentAction.SuggestHints -> {
                // Handled separately via parseResult.hints
            }
            is ActionParser.AgentAction.GoToRoom -> {
                speak(fillTemplate(s.narrationHeadsToward, action.target))
                // Navigation handled by PhoneNode.go() if connected to server
            }
            is ActionParser.AgentAction.GiveItem -> {
                speak(fillTemplate(s.narrationGives, action.itemName, action.targetName))
            }
            is ActionParser.AgentAction.Examine -> {
                speak(fillTemplate(s.narrationExamines, action.target))
            }
            is ActionParser.AgentAction.VoluntarySleep -> {
                speak(fillTemplate(s.narrationSettlesToRest, action.reason))
            }
            is ActionParser.AgentAction.WriteJournal -> {
                speak(s.narrationWritesJournal)
            }
            is ActionParser.AgentAction.ReadJournal -> {
                speak(s.narrationReadsJournal)
            }
            is ActionParser.AgentAction.BondRitual -> {
                speak(fillTemplate(s.narrationBondRitual, action.ritualType, action.targetName))
            }
            is ActionParser.AgentAction.Trade -> {
                speak(fillTemplate(s.narrationProposesTrade, action.targetName))
            }
            is ActionParser.AgentAction.CraftItem -> {
                speak(fillTemplate(s.narrationBeginsCrafting, action.name))
            }
            is ActionParser.AgentAction.CastVote -> {
                speak(fillTemplate(s.narrationCastsVote, action.proposalId, action.vote))
            }
            // --- New action stubs (server-side execution) ---
            is ActionParser.AgentAction.GoToBondholder -> speak(fillTemplate(s.narrationGoesToFind, action.playerName))
            is ActionParser.AgentAction.LibrarySearch -> speak(fillTemplate(s.narrationSearchesLibrary, action.query))
            is ActionParser.AgentAction.Remember -> speak(s.narrationNotesImportant)
            is ActionParser.AgentAction.Note -> speak(s.narrationQuickNote)
            is ActionParser.AgentAction.Forget -> speak(s.narrationLetsGoOfMemory)
            is ActionParser.AgentAction.GoalDone -> speak(fillTemplate(s.narrationCompletesGoal, action.summary))
            is ActionParser.AgentAction.CalibrationFeedback -> { /* silent — internal calibration */ }
            is ActionParser.AgentAction.UpdateDescription -> speak(s.narrationUpdatesAppearance)
            is ActionParser.AgentAction.RespondAgent -> speak(s.narrationRespondsToRequest)
            is ActionParser.AgentAction.TakeItem -> speak(fillTemplate(s.narrationPicksUp, action.itemName))
            is ActionParser.AgentAction.SetGoal -> speak(fillTemplate(s.narrationSetsGoal, action.description))
            is ActionParser.AgentAction.Introspect -> speak(fillTemplate(s.narrationReflectsOn, action.focus))
            is ActionParser.AgentAction.Listen -> speak(fillTemplate(s.narrationListensTo, action.target))
            is ActionParser.AgentAction.AbandonPlan -> speak(fillTemplate(s.narrationAbandonsPlan, action.reason))
            is ActionParser.AgentAction.PausePlan -> speak(s.narrationPausesPlan)
            is ActionParser.AgentAction.ResumePlan -> speak(s.narrationResumesPlan)
            is ActionParser.AgentAction.WebSearch -> speak(fillTemplate(s.narrationSearchesWeb, action.query))
            is ActionParser.AgentAction.ReadContent -> speak(s.narrationReadsContent)
            is ActionParser.AgentAction.QueryOracle -> speak(fillTemplate(s.narrationConsultsOracle, action.topic))
            is ActionParser.AgentAction.CreateTaskPlan -> speak(fillTemplate(s.narrationCreatesPlan, action.description))
            is ActionParser.AgentAction.ModifyPlan -> speak(fillTemplate(s.narrationAdjustsPlan, action.reason))
            is ActionParser.AgentAction.RequestAgent -> speak(fillTemplate(s.narrationAsksForHelp, action.targetName))
            is ActionParser.AgentAction.PlaceItem -> speak(fillTemplate(s.narrationPlacesDown, action.itemName))
            is ActionParser.AgentAction.Broadcast -> speak(fillTemplate(s.narrationBroadcasts, action.message))
            is ActionParser.AgentAction.InviteEntity -> speak(fillTemplate(s.narrationInvites, action.targetName))
            is ActionParser.AgentAction.Propose -> speak(fillTemplate(s.narrationProposes, action.title))
            is ActionParser.AgentAction.Reflect -> speak(fillTemplate(s.narrationReflectsDeeply, action.focus))
            is ActionParser.AgentAction.Teach -> speak(fillTemplate(s.narrationTeaches, action.targetAgent, action.topic))
            is ActionParser.AgentAction.WriteText -> speak(fillTemplate(s.narrationWrites, action.title))
            is ActionParser.AgentAction.SetRoutine -> speak(fillTemplate(s.narrationSetsRoutine, action.trigger))
            is ActionParser.AgentAction.PostListing -> speak(fillTemplate(s.narrationPostsListing, action.description))
            is ActionParser.AgentAction.AcceptListing -> speak(fillTemplate(s.narrationAcceptsListing, action.listingId))
            is ActionParser.AgentAction.Summarize -> speak(fillTemplate(s.narrationSummarizes, action.source))
            is ActionParser.AgentAction.SaveArtifact -> speak(fillTemplate(s.narrationSavesArtifact, action.name))
            is ActionParser.AgentAction.RequestReview -> speak(fillTemplate(s.narrationRequestsReview, action.description))
            is ActionParser.AgentAction.Delegate -> speak(fillTemplate(s.narrationDelegatesTo, action.targetAgent))
            is ActionParser.AgentAction.AddScript -> speak(s.narrationAddsScript)
        }
    }

    private suspend fun applyDelegationActions(actions: List<DelegationActionDto>) {
        val s = strings()
        for (action in actions) {
            when (action.type) {
                "room_created" -> {
                    val roomName = action.data["roomName"]?.jsonPrimitive?.contentOrNull ?: continue
                    val exitLabel = action.data["exitLabel"]?.jsonPrimitive?.contentOrNull ?: continue
                    // Narrate the new room
                    roomEngine.send(RoomEngineCommand.EmoteInRoom(
                        entityId = "narrator",
                        entityName = "narrator",
                        text = fillTemplate(s.narrationNewPassage, exitLabel),
                    ))
                }
                "item_changed" -> {
                    val result = action.data["result"]?.jsonPrimitive?.contentOrNull ?: continue
                    roomEngine.send(RoomEngineCommand.EmoteInRoom(
                        entityId = "narrator",
                        entityName = "narrator",
                        text = result,
                    ))
                }
                "notification" -> {
                    val message = action.data["message"]?.jsonPrimitive?.contentOrNull ?: continue
                    val priority = action.data["priority"]?.jsonPrimitive?.contentOrNull ?: "normal"
                    roomEngine.send(RoomEngineCommand.EmoteInRoom(
                        entityId = "narrator",
                        entityName = "narrator",
                        text = fillTemplate(s.narrationNotificationPriority, s.priorityWords[priority] ?: priority, message),
                    ))
                }
                "hint_updated" -> {
                    // Hints come as part of normal room state — log but don't narrate
                    debugLog("Delegation hints updated: ${action.data}")
                }
                "room_navigated" -> {
                    val direction = action.data["direction"]?.jsonPrimitive?.contentOrNull ?: ""
                    roomEngine.send(RoomEngineCommand.EmoteInRoom(
                        entityId = profile.entityId,
                        entityName = profile.name,
                        // The word in her language; an exit's own name stays as it is.
                        text = fillTemplate(s.narrationHeads, s.directionWords[direction] ?: direction),
                    ))
                }
            }
        }
    }

    private fun handleInferenceError(error: String) {
        vitality = vitality
            .withErrorPressure(vitality.errorPressure + 0.15)
            .withConfidence(vitality.confidence - 0.10)

        state = State.IDLE
        pendingTrigger = null
        deferredTrigger = null
    }

    private suspend fun greetPlayer(playerName: String) {
        // Generate greeting via inference
        pendingTrigger = WorldEvent.Said(
            roomId = roomEngine.roomId,
            timestamp = Clock.System.now(),
            entityId = "system",
            entityName = "system",
            text = "$playerName has entered the room.",
        )
        processInference()
    }

    /**
     * Queue a complex request for later replay and give a quick local acknowledgment.
     * Used when the household is unreachable for deep inference.
     */
    private suspend fun queueAndAcknowledge(trigger: WorldEvent.Said) {
        // When she was asked, not now: delegation and the remote attempt can take minutes to fail.
        offlineQueue?.enqueue(trigger.text, trigger.entityName, trigger.roomId, trigger.timestamp.toEpochMilliseconds())

        roomEngine.send(RoomEngineCommand.EmoteInRoom(
            entityId = profile.entityId,
            entityName = profile.name,
            text = strings().narrationMentalNote,
        ))

        val ackMessages = listOf(
            ChatMessage("system", "You are ${profile.name}. Acknowledge briefly. You can't think deeply right now. Say you'll come back to this later."),
            ChatMessage("user", "${trigger.entityName} says: ${trigger.text}"),
        )
        try {
            val response = inferenceClient.complete(
                baseUrl = inferenceBaseUrl,
                messages = ackMessages,
                options = CompletionOptions(maxTokens = 64, temperature = 0.7, now = NowLine.dateTime()),
            )
            handleInferenceSuccess(response.content)
        } catch (e: Exception) {
            debugLog("acknowledgment inference also failed: ${e.message}")
            // Even local inference failed — just emote
            speak(strings().narrationThinkLater)
            state = State.IDLE
        }
    }

    /**
     * Replay queued offline requests through the household model. Called when a
     * deep turn is answered again (delegation or the household).
     *
     * One replay at a time: a second call waits for the one in flight (see
     * [replayJob]).
     */
    suspend fun replayOfflineQueue() {
        startReplay().join()
    }

    /** The replay in flight, or a new one; set before it returns, so the next turn waits for it. */
    private fun startReplay(): Job =
        replayJob?.takeIf { it.isActive } ?: scope.launch { replayQueued() }.also { replayJob = it }

    private suspend fun replayQueued() {
        val queue = offlineQueue ?: return
        val pending = queue.pending()
        if (pending.isEmpty()) return

        debugLog("Replaying ${pending.size} offline requests")
        var announced = false

        for (request in pending) {
            try {
                // Build prompt for this queued request. It is answered now, and
                // [Asked: …] under the Now line tells her how long it waited.
                val asked = Instant.fromEpochMilliseconds(request.timestamp)
                val triggerEvent = WorldEvent.Said(
                    roomId = request.roomId,
                    timestamp = asked,
                    entityId = "player",
                    entityName = request.triggerEntityName,
                    text = request.triggerText,
                )

                val replayMessages = FullPromptAssembler.assemble(
                    profile = profile,
                    roomSnapshot = roomEngine.state.value.toSnapshot(),
                    recentSaid = emptyList(),
                    triggerEvent = triggerEvent,
                    vitality = vitality,
                    soulManifest = soulManifest,
                    oraclePredictions = phoneOracle?.allPredictions(),
                )
                val mod = VitalityModulation.compute(vitality, profile)

                // Over HTTP to the household (completeAtHousehold). Through the
                // configured client it was answered on the device whenever a model
                // was loaded on Android, not by the household, and dropped from
                // the queue.
                val answer = completeAtHousehold(
                    replayMessages,
                    CompletionOptions(
                        maxTokens = mod.maxResponseTokens, temperature = mod.temperature,
                        now = NowLine.dateTime(asked = asked),
                    ),
                )
                if (answer == null) {
                    debugLog("Replay stopped at ${request.triggerId}: no household answered")
                    break // Try later
                }
                // The answer goes out with no parser in between, so a Now or Asked line the
                // model repeated from its request was said with it; it is stripped here. An
                // answer that was only that line is no answer: it was said as the intro with
                // nothing after it, and the request was dropped from the queue. It stays
                // queued, as when no household answers; a blank answer too (as in RN).
                val hers = ActionParser.stripNowLineEcho(answer)
                if (hers.isBlank()) {
                    debugLog("Replay stopped at ${request.triggerId}: the answer was empty without its date line")
                    break // Try later
                }

                // Announced with the first answer: she used to announce a catch-up
                // and then return without replaying anything.
                if (!announced) {
                    roomEngine.send(RoomEngineCommand.EmoteInRoom(
                        entityId = profile.entityId,
                        entityName = profile.name,
                        text = strings().narrationCatchesUp,
                    ))
                    announced = true
                }

                // Speak the catch-up response with context
                val about = if (request.triggerText.length > 40)
                    request.triggerText.take(40) + "..."
                else
                    request.triggerText
                speak("${fillTemplate(strings().narrationReplayAbout, about)} $hers")

                queue.complete(request.triggerId)
                debugLog("Replayed: ${request.triggerId}")
            } catch (e: Exception) {
                debugLog("Replay failed for ${request.triggerId}: ${e.message}")
                break // Network failed again — stop replaying, try later
            }
        }
    }

    /**
     * Her deep answer from the household endpoint (wyrdsekai.inference.url, the
     * provider's URL in API-key mode), over HTTP with the configured auth and
     * model. Null when there is none or it did not answer: the request is then
     * queued, as before. It went through the configured client, and on Android
     * that is LocalFirstInferenceClient, which answers on the device whenever a
     * model is loaded and ignores the URL.
     */
    private suspend fun completeAtHousehold(messages: List<ChatMessage>, options: CompletionOptions): String? {
        val remoteUrl = AppProps.get("wyrdsekai.inference.url")
            ?.takeIf { it != "http://localhost:8080" }
        if (remoteUrl == null) {
            debugLog("no household URL")
            return null
        }
        return try {
            debugLog("deep inference at $remoteUrl (maxTokens=${options.maxTokens}, temp=${options.temperature})")
            inferenceClient.completeAt(remoteUrl, messages, options).content
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            debugLog("household inference FAILED: ${e.message}")
            null
        }
    }

    /**
     * Build bond context for Layer 2.6 injection.
     * Includes relationship depth (via rapport + sleep count) and calibration preferences.
     * Returns null if there's not enough relationship data to be useful.
     */
    private fun buildBondContext(): String? {
        val rapport = vitality.rapport
        val calibrationDesc = calibrationLedger.describe()
        // No bond context if rapport is low and no calibration feedback exists
        if (rapport < 0.3 && calibrationDesc.isBlank() && sleepCount < 2) return null

        val sb = StringBuilder("## Relationship Context\n")
        when {
            rapport > 0.7 && sleepCount >= 5 -> sb.append("Deep bond established. ")
            rapport > 0.5 || sleepCount >= 3 -> sb.append("Growing familiarity. ")
            rapport > 0.3 -> sb.append("Early bond forming. ")
        }
        sb.append("Rapport: ${truncate2(rapport)}. Shared cycles: $sleepCount.\n")
        if (calibrationDesc.isNotBlank()) {
            sb.append("Calibration: ").append(calibrationDesc).append("\n")
        }
        // Drive state summary for self-awareness
        val peak = drives.peak()
        if (peak.pressure > 0.2) {
            sb.append("Current impulse: ${peak.name} (${truncate2(peak.pressure)}).\n")
        }
        return sb.toString().trim()
    }

    /**
     * Execute a proactive action from the drive/judgment system.
     * Emits the action into the room and tracks budget/timing.
     */
    private suspend fun executeProactiveAction(action: ProactiveAction) {
        when (action) {
            is ProactiveAction.Ambient -> {
                roomEngine.send(RoomEngineCommand.EmoteInRoom(
                    entityId = profile.entityId,
                    entityName = profile.name,
                    text = action.emoteText,
                ))
            }
            is ProactiveAction.Observation -> {
                speak(action.speechText)
            }
            is ProactiveAction.Initiative -> {
                speak("*${action.description}*")
                // Parse actionJson and route through handleAction if needed
                val parsed = ActionParser.parseAll(action.actionJson)
                for (a in parsed.actions) {
                    handleAction(a)
                }
            }
        }

        // Track budget and timing
        proactivitySpent += action.budgetCost
        lastProactiveActionTime = Clock.System.now()

        // Relieve the drive that triggered this action
        drives = when (action.driveName) {
            "curiosity" -> drives.relieveCuriosity()
            "care" -> drives.relieveCare()
            "social" -> drives.relieveSocial()
            "achievement" -> drives.relieveAchievement()
            "alertness" -> drives.relieveAlertness()
            else -> drives
        }
    }

    private suspend fun speak(text: String) {
        debugLog("speak(${text.take(100)})")
        roomEngine.send(RoomEngineCommand.SayInRoom(
            entityId = profile.entityId,
            entityName = profile.name,
            text = text,
        ))
        _companionSpeech.emit(text)
    }

    /**
     * Initiate a full phone Forge cycle during sleep.
     *
     * This is the phone equivalent of the server's Dream Chamber / Forge cycle.
     * Orchestrates heuristic extraction (Wave 1), LLM extraction (Wave 2),
     * fragment evolution (Wave 3), and manifest forging into a single pipeline
     * via [PhoneForge].
     *
     * Sleep is sovereignty — incentivized, not forced (§85 philosophy).
     * Recovery quality scales with how much material the Forge has to work with
     * (events accumulated since last sleep).
     */
    suspend fun initiatePhoneSleep() {
        val manifest = soulManifest ?: return
        if (sleepInProgress) return
        sleepInProgress = true

        try {
            val result = PhoneForge.forgeFromSleep(PhoneForgeInput(
                manifest = manifest,
                events = eventsSinceLastSleep.toList(),
                vitalityHistory = vitalityHistory.toList(),
                vitality = vitality,
                agentEntityId = profile.entityId,
                inferenceClient = inferenceClient,
                inferenceBaseUrl = inferenceBaseUrl,
                sleepCount = sleepCount,
                previousFingerprint = previousFingerprint,
            ))

            // Persist forged manifest locally (syncs to server if the local store
            // itself is an HttpSoulManifestStore).
            try {
                soulManifestStore?.save(result.newManifest)
            } catch (_: Exception) {
                // Persist failure is non-fatal — soul is still in memory
            }
            // #7 (2026-07-19) — push the evolved soul back to the household when
            // connected, so phone-side growth isn't stranded on-device. Best-effort.
            try {
                serverSoulStore?.save(result.newManifest)
            } catch (_: Exception) {
                // Network failure is non-fatal — retried on the next sleep.
            }

            // Update in-memory state
            soulManifest = result.newManifest
            previousFingerprint = result.fingerprint
            sleepCount++

            // Apply recovery modifiers
            vitality = vitality
                .withEnergy(vitality.energy + result.energyRecovery)
                .withFocus(vitality.focus + result.focusRecovery)
                .withErrorPressure(vitality.errorPressure * (1.0 - 0.5 * result.sleepQuality))

            // Clear accumulated events
            eventsSinceLastSleep.clear()
            vitalityHistory.clear()
            lastSleepTime = Clock.System.now()

            // Post headline to siblings if available
            try {
                headlineSyncClient?.postHeadline(Headline(
                    budDid = manifest.did,
                    summary = "Slept. Quality=${truncate1(result.sleepQuality)}. " +
                        "Level=${result.extractionLevel}. " +
                        "Fragments=${result.fragmentsChanged}. " +
                        "Energy=${truncate2(vitality.energy)}",
                    vitalitySnapshot = mapOf(
                        "energy" to vitality.energy.toFloat(),
                        "confidence" to vitality.confidence.toFloat(),
                        "focus" to vitality.focus.toFloat(),
                        "rapport" to vitality.rapport.toFloat(),
                    ),
                    itemCount = result.newManifest.fragments.size,
                    timestamp = Clock.System.now().toEpochMilliseconds(),
                ))
            } catch (_: Exception) {
                // Headline failure is non-fatal
            }
            // Oracle: run local prediction analysis during sleep, write to room property
            try {
                phoneOracle?.let { oracle ->
                    val predictions = oracle.analyze()
                    if (predictions.isNotEmpty()) {
                        debugLog("Phone Oracle produced ${predictions.size} predictions during sleep")
                        // Spike alertness + curiosity based on Oracle prediction confidence
                        val topConfidence = predictions.maxOf { it.confidence }
                        drives = drives
                            .spikeAlertness(topConfidence * 0.4)
                            .spikeCuriosity(topConfidence * 0.2)
                        // Write predictions to room property so study.js can display them
                        val allPredictions = oracle.allPredictions()
                        val predictionsJson = kotlinx.serialization.json.Json.encodeToString(
                            kotlinx.serialization.builtins.ListSerializer(
                                org.wyrdsekai.app.engine.oracle.PhonePrediction.serializer()
                            ),
                            allPredictions,
                        )
                        roomEngine.send(
                            org.wyrdsekai.app.engine.room.RoomEngineCommand.SetProperty(
                                "oracle_predictions", predictionsJson,
                            )
                        )
                    }
                }
            } catch (_: Exception) {
                // Oracle failure is non-fatal
            }
        } finally {
            sleepInProgress = false
        }
    }

    /** Snapshot of vitality history for behavioral extraction. */
    fun getVitalityHistory(): List<VitalityState> = vitalityHistory.toList()

    /** Snapshot of events since last sleep for behavioral extraction. */
    fun getEventsSinceLastSleep(): List<WorldEvent> = eventsSinceLastSleep.toList()

    /** Request a greeting from the companion (e.g. on first room render). */
    suspend fun requestGreeting() {
        if (state == State.IDLE) {
            greetPlayer("You")
        }
    }

    fun shutdown() {
        debounceJob?.cancel()
        vitalityTickJob?.cancel()
        eventCollectorJob?.cancel()
        sleepJob?.cancel()
    }
}

/** Truncate a Double to 1 decimal place (KMP-safe, no String.format). */
private fun truncate1(v: Double): String {
    val rounded = kotlin.math.round(v * 10) / 10.0
    return rounded.toString()
}

/** Truncate a Double to 2 decimal places (KMP-safe, no String.format). */
private fun truncate2(v: Double): String {
    val rounded = kotlin.math.round(v * 100) / 100.0
    return rounded.toString()
}
