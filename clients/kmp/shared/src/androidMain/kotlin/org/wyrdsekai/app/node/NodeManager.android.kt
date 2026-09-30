package org.wyrdsekai.app.node

import org.wyrdsekai.app.i18n.currentUiStrings
import org.wyrdsekai.app.engine.between.HomeBusLink
import org.wyrdsekai.app.engine.between.NatsBetweenClient
import org.wyrdsekai.app.state.TokenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.wyrdsekai.app.engine.PhoneNode
import org.wyrdsekai.app.engine.agent.CompanionCapabilityBridge
import org.wyrdsekai.app.engine.agent.SkillUsageTracker
import org.wyrdsekai.app.engine.item.EquipmentService
import org.wyrdsekai.app.engine.item.StarterKitProvisioner
import org.wyrdsekai.app.engine.persistence.AndroidEventJournal
import org.wyrdsekai.app.engine.persistence.AndroidVitalityStore
import org.wyrdsekai.app.engine.persistence.AndroidSoulManifestStore
import org.wyrdsekai.app.engine.persistence.FileBackedItemStore
import org.wyrdsekai.app.engine.soul.BootstrapSoulManifest
import org.wyrdsekai.app.engine.soul.NamedBootstrapManifest
import org.wyrdsekai.app.engine.soul.SoulSyncManager
import org.wyrdsekai.app.engine.tier.AndroidResourceProbe
import org.wyrdsekai.app.engine.tier.ResourceProbe
import org.wyrdsekai.app.engine.tier.ResourceSnapshot
import org.wyrdsekai.app.engine.tier.ThermalState
import org.wyrdsekai.app.engine.tier.TierManager
import org.wyrdsekai.app.hermod.ConsentMint
import org.wyrdsekai.app.hermod.HermodDoorman
import org.wyrdsekai.app.hermod.HermodListener
import org.wyrdsekai.app.inference.ChatMessage
import org.wyrdsekai.app.inference.CompletionOptions
import org.wyrdsekai.app.inference.InferenceClient
import org.wyrdsekai.app.inference.LocalInferenceProvider
import org.wyrdsekai.app.inference.LocalFirstInferenceClient
import org.wyrdsekai.app.inference.LlamaServerManager
import org.wyrdsekai.app.inference.ModelCatalog
import org.wyrdsekai.app.inference.ModelManager
import org.wyrdsekai.app.inference.RemoteAuthType
import org.wyrdsekai.app.engine.study.SqliteStudyStore
import org.wyrdsekai.app.crypto.decodeZoneKey
import org.wyrdsekai.app.network.HomeLink
import org.wyrdsekai.app.network.HouseholdTrustStore
import org.wyrdsekai.app.network.InviteSecurity
import org.wyrdsekai.app.network.SecurityNotice
import org.wyrdsekai.app.network.SecurityNotices
import org.wyrdsekai.app.network.homeBaseUrl
import org.wyrdsekai.app.network.homeBusUrl
import org.wyrdsekai.app.network.relayLegUrl
import org.wyrdsekai.app.network.SoulClient
import org.wyrdsekai.app.network.parseWsHostPort
import org.wyrdsekai.app.network.pinRelayFromInviteFingerprints
import org.wyrdsekai.app.platform.PlatformContext

/**
 * Android NodeManager — wires PhoneNode for full local node operation.
 *
 * PhoneNode is pure Kotlin (no JDK server required). It boots foundation rooms,
 * spawns the companion (Wyrd), and runs inference via any OpenAI-compatible endpoint
 * (local llama.cpp JNI, household Ollama, or cloud).
 *
 * Inference endpoint is configured via system property "wyrdsekai.inference.url"
 * (default: "http://localhost:8080" for on-device llama-server).
 */
actual class NodeManager actual constructor(private val scope: CoroutineScope) {
    private val _state = MutableStateFlow("stopped")
    actual val state: StateFlow<String> = _state.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    actual val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    actual val isAvailable: Boolean = true
    actual val port: Int = 0  // No HTTP server — PhoneNode is in-process

    private var _phoneNode: PhoneNode? = null
    actual val phoneNode: PhoneNode? get() = _phoneNode

    /** Model download/load status for UI display. */
    private val _modelStatus = MutableStateFlow("idle")
    actual val modelStatus: StateFlow<String> = _modelStatus.asStateFlow()

    private val _modelProgress = MutableStateFlow(0f)
    actual val modelProgress: StateFlow<Float> = _modelProgress.asStateFlow()

    private val _modelStatusText = MutableStateFlow<String?>(null)
    actual val modelStatusText: StateFlow<String?> = _modelStatusText.asStateFlow()

    private var llamaServerManager: LlamaServerManager? = null
    private var hermodDoorman: HermodDoorman? = null
    private var homeBusLink: HomeBusLink? = null
    private var pairingWatch: Job? = null

    actual fun start() {
        val logFile = java.io.File(System.getProperty("wyrdsekai.data.dir") ?: "/data/local/tmp", "wyrd-debug.log")
        fun log(msg: String) {
            try { logFile.appendText("${java.util.Date()}: $msg\n") } catch (_: Exception) {}
            android.util.Log.e("WyrdNode", msg)
        }
        log("start() called. state=${_state.value}")
        if (_state.value == "running" || _state.value == "starting") return
        log("start() proceeding. Setting state to starting.")
        _state.value = "starting"
        _errorMessage.value = null

        scope.launch {
            log("Coroutine launched.")
            try {
                val dataDir = System.getProperty("wyrdsekai.data.dir")
                    ?: System.getProperty("user.dir", "/data/data/org.wyrdsekai.kmp/files")
                log("Starting PhoneNode. dataDir=$dataDir")

                // Use saved URL immediately (don't block on discovery)
                val inferenceUrl = System.getProperty("wyrdsekai.inference.url")
                    ?: "http://localhost:8080"
                log("inferenceUrl=$inferenceUrl")

                val journal = AndroidEventJournal(dataDir)
                val vitalityStore = AndroidVitalityStore(dataDir)
                val soulManifestStore = AndroidSoulManifestStore(dataDir)

                // Create LlamaServerManager first — LocalFirstInferenceClient wraps it
                val lsm = LlamaServerManager(scope)
                llamaServerManager = lsm
                val inferenceClient = LocalFirstInferenceClient(lsm)

                // Cloud-API auth: WyrdApp.kt onComplete sets these system properties
                // after the Welcome wizard's "I have an API key" path. Plumb them
                // onto the InferenceClient so the HTTP fallback (which kicks in
                // because no local model is loaded yet) actually sends the right
                // header. anthropic → x-api-key, openai/openrouter → Bearer.
                val apiProvider = System.getProperty("wyrdsekai.api.provider")
                val apiKey = System.getProperty("wyrdsekai.api.key")
                if (apiProvider != null && apiKey != null) {
                    val authType = when (apiProvider) {
                        "anthropic" -> RemoteAuthType.X_API_KEY
                        else -> RemoteAuthType.BEARER
                    }
                    inferenceClient.setRemoteAuth(authType, apiKey)
                    // Cloud /v1/chat/completions 400s without `model`. Send the
                    // provider's default; an explicit override wins if set.
                    val apiModel = System.getProperty("wyrdsekai.api.model")
                        ?: when (apiProvider) {
                            "anthropic" -> "claude-sonnet-4-6"
                            "openrouter" -> "anthropic/claude-sonnet-4"
                            "openai" -> "gpt-4o"
                            else -> null
                        }
                    inferenceClient.setRemoteModel(apiModel)
                    log("Cloud-API auth wired: provider=$apiProvider authType=$authType model=$apiModel")
                }

                val companionName = System.getProperty("wyrdsekai.companion.name") ?: "Wyrd"
                log("companionName=$companionName")

                // Live device truth (battery/thermal/wifi) whenever the app
                // context exists; the JVM fallback keeps headless runs alive.
                // hermod's charging gate depends on this being REAL — the old
                // stub said isCharging=true forever.
                val probe = PlatformContext.app?.let { AndroidResourceProbe(it) }
                    ?: object : ResourceProbe {
                    override fun snapshot(): ResourceSnapshot {
                        val runtime = Runtime.getRuntime()
                        val availMb = (runtime.freeMemory() + runtime.maxMemory() - runtime.totalMemory()) / (1024 * 1024)
                        val totalMb = runtime.maxMemory() / (1024 * 1024)
                        return ResourceSnapshot(
                            availableMemoryMb = availMb,
                            totalMemoryMb = totalMb,
                            batteryPercent = 80,
                            isCharging = true,
                            thermalState = ThermalState.NOMINAL,
                            hasWifi = true,
                        )
                    }
                }
                val tierManager = TierManager(probe, scope = scope)
                tierManager.initialize()
                tierManager.startMonitoring()
                log("TierManager created, initial tier=${tierManager.currentTier.value}")

                val homeName = System.getProperty("wyrdsekai.home.name") ?: "Home"
                log("homeName=$homeName")

                // The home's HTTP base: the invite's https address on the home
                // network, or an encrypted / local one — never plain http off
                // the device ( W2).
                val homeStore = TokenStore()
                val pairedServerUrl = HomeLink.homeBase(
                    System.getProperty("wyrdsekai.server.url"), homeStore.loadLanHttps(), homeStore.loadHomeCaFp())
                val pairingToken = System.getProperty("wyrdsekai.pairing.token")
                log("pairedServerUrl=$pairedServerUrl, hasPairingToken=${pairingToken != null}")
                if (HomeLink.needsRepairForLan(homeStore.loadServerUrl(), homeStore.loadLanHttps(), homeStore.loadHomeCaFp())
                    && !homeStore.loadLanRepairNoticed()) {
                    // Paired before home TLS: the plain http address is not used;
                    // the relay carries everything. Say so once.
                    SecurityNotices.publish(SecurityNotice.RepairForHomeNetwork)
                    homeStore.saveLanRepairNoticed(true)
                    log("home address is plain http from before home TLS — not used; relay only, re-pair notice shown")
                }

                val selectedModelTier = System.getProperty("wyrdsekai.model.tier") ?: "tiny"
                log("modelTier=$selectedModelTier")

                // Study persistence (SQLite + FTS5)
                val studyStore = SqliteStudyStore(dataDir)
                log("SqliteStudyStore created at $dataDir/study.db")

                val node = PhoneNode(
                    journal = journal,
                    vitalityStore = vitalityStore,
                    inferenceClient = inferenceClient,
                    inferenceBaseUrl = inferenceUrl,
                    scope = scope,
                    tierManager = tierManager,
                    soulManifestStore = soulManifestStore,
                    nodeId = "android-${android.os.Build.MODEL.replace(" ", "-").lowercase()}",
                    companionName = companionName,
                    homeRoomName = homeName,
                    serverUrl = pairedServerUrl,
                    deviceToken = pairingToken,
                    modelTier = selectedModelTier,
                    studyStore = studyStore,
                    zoneId = org.wyrdsekai.app.state.TokenStore().loadZoneId(),
                    accountUserId = org.wyrdsekai.app.state.TokenStore().loadUserId(),
                )
                _phoneNode = node
                log("PhoneNode created, calling start()")
                node.start()

                log("Waiting for PhoneNode state...")
                // Wait for PhoneNode to reach RUNNING
                node.state.first { it == PhoneNode.State.RUNNING || it == PhoneNode.State.ERROR }
                log("PhoneNode reached state: ${node.state.value}")

                // hermod doorman: while consented + identified, keep ONE door
                // open to the zone — LAN /ws/hermod when home answers, the
                // relay tunnel otherwise — and roam between them as the phone
                // moves. doors() is re-read every cycle, so an identity minted
                // by the consent toggle arms the mesh without a restart.
                run {
                    val hermodLsm = llamaServerManager ?: return@run
                    val store = org.wyrdsekai.app.state.TokenStore()
                    val doorman = HermodDoorman(
                        scope = scope,
                        local = object : LocalInferenceProvider {
                            override val state get() = hermodLsm.state
                            override suspend fun completeLocal(
                                messages: List<ChatMessage>,
                                options: CompletionOptions,
                            ) = hermodLsm.completeLocal(messages, options)
                        },
                        models = {
                            if (hermodLsm.state.value == "running") listOf(LOCAL_MODEL_ID)
                            else emptyList()
                        },
                        policy = {
                            val snap = probe.snapshot()
                            HermodListener.HermodPolicy(
                                consented = store.loadHermodConsent(),
                                charging = snap.isCharging,
                                idle = true,
                            )
                        },
                        doors = {
                            HermodDoorman.Doors(
                                deviceToken = store.loadPairingToken(),
                                serverUrl = store.homeBaseUrl() ?: pairedServerUrl,
                                tunnel = org.wyrdsekai.app.engine.transit.RelayTunnelHolder.get()
                                    ?.let { bc ->
                                        store.loadZoneId()?.let { z ->
                                            HermodDoorman.TunnelDoor(bc, z, decodeZoneKey(store.loadZoneKey()))
                                        }
                                    },
                            )
                        },
                    )
                    hermodDoorman = doorman
                    doorman.start()
                    log("hermod doorman armed (consent-gated, LAN↔relay roaming)")
                }

                // Probe the configured server URL; if it's a wyrdsekai server,
                // auto-register a phone account and log in via MCP so tell /
                // library_search / journal commands route through the server's
                // CrossZoneTellService + Lucene-backed Study. Stored in a
                // process-global holder for LocalRoomScreen to consume.
                scope.launch {
                    setupNatsServerClient(
                        scope, companionName,
                        log = { log(it) },
                        onHomeZoneUnreachable = { reason ->
                            log("home zone unreachable — $reason")
                            val msg =
                                "Couldn't reach your home zone — check your connection and try again. " +
                                    "Your Study is running locally in the meantime."
                            _errorMessage.value = msg
                            // errorMessage is only rendered on Birth/Settings — in the
                            // local ROOM it was invisible (wired-but-dead audit find).
                            // Emit as system prose so the user actually sees it.
                            _phoneNode?.emitSystemProse(msg)
                        },
                        onAccountUserId = { uid, tok -> _phoneNode?.setStudyAccount(uid, tok) },
                    )
                }

                // Replace generic bootstrap with named bootstrap if companion was named.
                // BORN AS A PARTICULAR (2026-07-17): seeded from a persisted
                // TemperamentSeed with server-identical semantics — every phone birth
                // is a distinct individual, and the SAME particular survives reload.
                val comp = node.companion
                if (comp?.soulManifest?.did == BootstrapSoulManifest.BOOTSTRAP_DID) {
                    val seed = org.wyrdsekai.app.engine.soul.TemperamentSeed.loadOrBirth(dataDir)
                    comp.loadSoul(NamedBootstrapManifest.create(companionName, seed))
                }

                // --- Capability wiring: item store, equipment, starter kit ---
                val itemStore = FileBackedItemStore(dataDir)
                val equipmentService = EquipmentService()
                val usageTracker = SkillUsageTracker()

                // Provision starter kit on first boot
                val kitProvisioned = System.getProperty("wyrdsekai.starter.provisioned") != null
                if (!kitProvisioned && comp != null) {
                    val companionDid = comp.soulManifest?.did ?: "did:key:bootstrap-ma"
                    val kit = StarterKitProvisioner.provision(companionDid, isPhone = true)
                    for (item in kit) {
                        itemStore.store(item)
                    }
                    // Auto-equip Everyday Garb
                    val garb = kit.firstOrNull { it.label == "Everyday Garb" }
                    if (garb != null) {
                        equipmentService.equip(companionDid, garb)
                    }
                    System.setProperty("wyrdsekai.starter.provisioned", "true")
                    // TODO: persist this flag properly (SharedPreferences)
                }

                // Create bridge and set on companion
                val bridge = CompanionCapabilityBridge(equipmentService, itemStore, usageTracker)
                comp?.capabilityBridge = bridge

                // Wire offline queue for dual inference routing (Wave 2/4)
                val offlineQueue = org.wyrdsekai.app.engine.agent.OfflineQueue(dataDir)
                comp?.offlineQueue = offlineQueue

                // --- Soul Sync: pull latest manifest from server ---
                // Non-fatal: standalone works without server sync.
                val serverUrl = pairedServerUrl
                if (serverUrl != null && comp != null) {
                    try {
                        // /api/soul/* needs a login (ApiAuth): the session token,
                        // or this phone's device token when it is linked to a person.
                        val token = System.getProperty("wyrdsekai.auth.token")
                            ?: pairingToken ?: ""
                        val soulClient = SoulClient(serverUrl)
                        val syncManager = SoulSyncManager(
                            soulClient = soulClient,
                            soulManifestStore = soulManifestStore,
                            serverUrl = serverUrl,
                            token = token,
                        )
                        val currentManifest = comp.soulManifest
                        if (currentManifest != null) {
                            val pulled = syncManager.tryPullFromServer(
                                currentDid = currentManifest.did,
                                currentName = companionName,
                            )
                            if (pulled != null && !syncManager.isBootstrap(pulled)) {
                                comp.loadSoul(pulled)
                            }
                        }
                    } catch (_: Exception) {
                        // Soul sync is optional — companion works with local manifest
                    }
                }

                when (node.state.value) {
                    PhoneNode.State.RUNNING -> {
                        log("PhoneNode RUNNING — setting state")
                        _state.value = "running"

                        // --- Local model download + load (background, non-blocking) ---
                        scope.launch {
                            try {
                                // Mode 1 (remote terminal): a home zone is configured
                                // (relay/zone leg persisted), so inference comes from the
                                // home zone over the tunnel. Skip the ~639MB local Study
                                // model entirely — the phone is a thin terminal, not a
                                // standalone mini-zone. Pure-local modes 2/3 have no relay
                                // leg, so they still download + load as before.
                                val homeZoneTs = org.wyrdsekai.app.state.TokenStore()
                                // hermod: a phone that CONSENTED to lend compute is
                                // not a thin terminal — household errands run on ITS
                                // model. Consent overrides the skip, home zone or not.
                                val lendsCompute = homeZoneTs.loadHermodConsent()
                                if (!lendsCompute && homeZoneTs.relayLegUrl() != null) {
                                    _modelStatus.value = "remote"
                                    _modelStatusText.value = "Using your home zone"
                                    log("Home zone configured — skipping local model (inference over the relay)")
                                    return@launch
                                }

                                val mm = ModelManager()

                                _modelStatus.value = "checking"
                                _modelStatusText.value = "Checking for local model..."
                                log("Checking for local model...")

                                // Default model: 0.6B for Study command layer (grammar-constrained).
                                // Always download — small (639MB), fast, works offline.
                                // Companion personality requires household server or 7B+ (opt-in).
                                val preferredModel = LOCAL_MODEL_ID

                                var modelPath = mm.getModelPath(preferredModel)

                                if (modelPath == null) {
                                    val modelInfo = ModelCatalog.findById(preferredModel)!!
                                    val sizeMb = modelInfo.size / 1_000_000
                                    _modelStatus.value = "downloading"
                                    _modelStatusText.value = "Downloading ${modelInfo.name} (${sizeMb}MB)..."
                                    log("Downloading $preferredModel (${sizeMb}MB)...")

                                    modelPath = mm.downloadModel(preferredModel) { progress ->
                                        _modelProgress.value = progress
                                        _modelStatusText.value = "Downloading ${modelInfo.name}: ${(progress * 100).toInt()}%"
                                    }
                                    log("Download complete: $modelPath")
                                }

                                // Load model via JNI
                                val localLsm = llamaServerManager!!
                                if (localLsm.isAvailable) {
                                    _modelStatus.value = "loading"
                                    _modelStatusText.value = "Loading model..."
                                    log("Loading model from $modelPath")

                                    localLsm.start(modelPath)

                                    // Wait for llama server to be ready
                                    localLsm.state.first { it == "running" || it == "error" }

                                    if (localLsm.state.value == "running") {
                                        _modelStatus.value = "ready"
                                        _modelStatusText.value = "Model loaded — companion can think"
                                        log("Model loaded and ready!")
                                    } else {
                                        _modelStatus.value = "unavailable"
                                        _modelStatusText.value = "Model failed to load: ${localLsm.errorMessage.value}"
                                        log("Model load failed: ${localLsm.errorMessage.value}")
                                    }
                                } else {
                                    _modelStatus.value = "unavailable"
                                    _modelStatusText.value = "Native library not available — build with NDK for on-device inference"
                                    android.util.Log.w("WyrdNode", "Native library not available")
                                }
                            } catch (e: Exception) {
                                _modelStatus.value = "unavailable"
                                _modelStatusText.value = "Model setup failed: ${e.message}"
                                log("Model setup failed: ${e.message}")
                            }
                        }

                        // --- The home's own bus on the home network (W2) ---
                        // Study sync runs only over an encrypted, authenticated
                        // link: the home's NATS websocket at the address the home
                        // named (the invite's home_bus or the pairing reply's
                        // natsUrl; wss://<lan host>:4223 only when it named none),
                        // pinned to the household CA, as this phone's own NATS
                        // account from pairing (D3). Never plain ws://, never the
                        // relay (its pub/sub is readable there). Retried every
                        // minute while the phone is away from home. (Not keyed on
                        // budDelegation: a paired phone always has the HTTPS one,
                        // which kept this loop from ever starting.)
                        // A pairing made while the app runs (the consent toggle, any
                        // pairing door) starts the bus then, not at the next start.
                        if (node.studySync == null && node.headlineSyncClient == null) {
                            val link = HomeBusLink(
                                scope = scope,
                                target = {
                                    val store = TokenStore()
                                    val url = store.homeBusUrl()
                                    val user = store.loadHomeNatsUser()
                                    val pass = store.loadHomeNatsPassword()
                                    if (url == null || store.loadHomeCaFp() == null || user.isNullOrBlank() || pass.isNullOrBlank()) null
                                    else HomeBusLink.Target(url, user, pass)
                                },
                                open = { t ->
                                    val store = TokenStore()
                                    val busAt = parseWsHostPort(t.url)
                                    if (busAt != null && HouseholdTrustStore.getExact(busAt.first, busAt.second) == null) {
                                        val pinned = InviteSecurity.pinHome(store.loadLanHttps(), store.loadHomeCaFp(), store.loadHomeBusUrl())
                                        log("home CA pin for ${busAt.first}:${busAt.second}: ${if (pinned) "OK" else "not reachable yet"}")
                                    }
                                    NatsBetweenClient(scope).apply {
                                        autoReconnect = true
                                        setCredentials(t.user, t.pass)
                                        connect(t.url)
                                    }
                                },
                                // Study sync only, addressed by this phone's bus login
                                // (the home lets a phone hear only frames addressed to it).
                                attach = { bc, t -> node.attachHomeBus(bc, t.user) },
                                onNotPaired = {
                                    log("No home-network pairing (home_ca_fp + NATS account) — the home's bus is not used")
                                    // A phone with a home zone (relay or home network) is told
                                    // plainly when its Study sync waits; a standalone phone is not.
                                    if (!TokenStore().loadZoneId().isNullOrBlank()) {
                                        node.emitSystemProse(currentUiStrings().secSyncNeedsHomePairing)
                                    }
                                },
                                onWaiting = {
                                    if (!TokenStore().loadZoneId().isNullOrBlank()) {
                                        node.emitSystemProse(currentUiStrings().secSyncWaitsForHome)
                                    }
                                },
                                log = { log(it) },
                                retryMs = HOUSEHOLD_DISCOVERY_INTERVAL_MS,
                            )
                            homeBusLink = link
                            link.start()
                            pairingWatch = scope.launch {
                                ConsentMint.paired.collect {
                                    log("Paired while running — joining the home's bus")
                                    link.start()
                                }
                            }
                        }
                    }
                    PhoneNode.State.ERROR -> {
                        log("PhoneNode ERROR: ${node.error.value}")
                        _state.value = "error"
                        _errorMessage.value = node.error.value ?: "PhoneNode failed to start"
                    }
                    else -> {
                        log("PhoneNode unexpected state: ${node.state.value}")
                    }
                }
            } catch (e: Exception) {
                log("Node start failed: ${e.message}")
                _state.value = "error"
                _errorMessage.value = "Node start failed: ${e.message}"
            }
        }
    }

    actual fun stop() {
        pairingWatch?.cancel()
        pairingWatch = null
        homeBusLink?.stop()
        homeBusLink = null
        hermodDoorman?.stop()
        hermodDoorman = null
        _phoneNode?.stop()
        _phoneNode = null
        // Tear down the relay tunnel leg so the terminal falls back to offline.
        org.wyrdsekai.app.engine.transit.RelayTunnelHolder.clear()
        _state.value = "stopped"
        _errorMessage.value = null
    }

    companion object {
        /** Interval between background household discovery scans (60s). */
        private const val HOUSEHOLD_DISCOVERY_INTERVAL_MS = 60_000L

        /** The model this node loads locally (also advertised to hermod). */
        private const val LOCAL_MODEL_ID = "qwen3-0.6b-q4"
    }
}

/**
 * Set up the phone-side NATS transport: discover the zone, login with saved
 * MCP creds (or register/redeem on a fresh phone), and publish the client to
 * [ServerClientHolder] for screens to consume.
 *
 * "home" is reserved as a zone label — the phone
 * refuses to use it via [NatsServerClient.setZoneId].
 */
private suspend fun setupNatsServerClient(
    scope: CoroutineScope,
    companionName: String,
    log: (String) -> Unit,
    // Tier-1: reaching discovery/tunnel means a home-zone connection was intended;
    // if it fails we must SAY SO instead of silently dropping to the local Study.
    // The caller wires this to a user-visible error channel (_errorMessage).
    onHomeZoneUnreachable: (String) -> Unit = {},
    // The authenticated account userId (owns the Study) — surfaced after mcp.login
    // so the phone syncs the Study under the account, not the companion soul DID.
    onAccountUserId: (String, String?) -> Unit = { _, _ -> },
) {
    try {
        // The relay leg is the invite's relay, and only
        // that: never an address derived from the home's own, never a pairing
        // reply's natsUrl (that is the home's bus), never a loopback address.
        val explicitNatsUrl = System.getProperty("wyrdsekai.nats.url")
        val wssUrl = HomeLink.usableRelayUrl(explicitNatsUrl) ?: run {
            log(if (explicitNatsUrl.isNullOrBlank()) "No relay from an invite — the relay leg is not used"
                else "Saved relay address is not usable ($explicitNatsUrl) — the relay leg is not used; scan the invite again")
            return
        }
        log("NATS setup wss=$wssUrl")

        // Pin the relay cert from the invite fingerprints BEFORE connecting —
        // in the same coroutine, so the wss handshake can never race the pin.
        // This also self-heals: if the app died before the onboarding-time pin
        // completed, the persisted fps (wyrdsekai.relay.fps, restored by
        // WyrdApp) re-establish trust here on the next start.
        val fps = System.getProperty("wyrdsekai.relay.fps")
            ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            .orEmpty()
        if (fps.isNotEmpty()) {
            parseWsHostPort(wssUrl)?.let { (host, port) ->
                PlatformContext.app?.let { HouseholdTrustStore.init(it) }
                if (HouseholdTrustStore.getExact(host, port) == null) {
                    val pinned = pinRelayFromInviteFingerprints(host, port, fps)
                    log("NATS pre-connect pin for $host: ${if (pinned) "OK" else "FAILED"}")
                }
            }
        }

        // Transport-level account that lets a phone open the wss connection at
        // all; per-user identity comes from the MCP token inside each request
        // envelope (set after login/redeem). The credential arrives in the
        // INVITE — there is deliberately no compiled-in fallback, because
        // relays now generate their own infrastructure secrets on first run
        // (OSS hardening 2026-07-25), so a baked default could only ever be
        // wrong, or a shipped secret for every relay that kept it.
        val natsCreds = org.wyrdsekai.app.state.TokenStore()
        val natsUser = System.getProperty("wyrdsekai.nats.user")
            ?: natsCreds.loadNatsUser()
        val natsPass = System.getProperty("wyrdsekai.nats.pass")
            ?: natsCreds.loadNatsPassword()
        if (natsUser.isNullOrBlank() || natsPass.isNullOrBlank()) {
            android.util.Log.w("WyrdNode", "No relay credentials on this device — scan/paste the invite again")
            return
        }
        // Everything on the relay is sealed to the home's key from the invite
        // ( W3). A phone paired before 0.5.0 has none:
        // nothing is sent, and the person is told to pair again.
        val zoneKey = decodeZoneKey(natsCreds.loadZoneKey())
        if (zoneKey == null) {
            log("No home tunnel key (paired before 0.5.0) — the relay is not used until the phone is paired again")
            SecurityNotices.publish(SecurityNotice.RepairForTunnel)
            onHomeZoneUnreachable("no home tunnel key; pair the phone again")
            return
        }

        // We need a zoneId to scope subsequent subjects. Two paths:
        // 1. Cached from a prior auth — fast, deterministic, the common case.
        // 2. Bootstrap with `wyrd.discover.zone` — racey on multi-node mesh
        //    (no queue group; first responder wins) but unavoidable on cold
        //    start. After auth succeeds we persist the zone so the next run
        //    skips this step.
        val tokenStore = org.wyrdsekai.app.state.TokenStore()
        val cachedZone = tokenStore.loadZoneId()?.takeIf {
            it.isNotBlank() && it != "_unknown" && it != "home"
        }
        val nc = org.wyrdsekai.app.network.NatsServerClient(
            relayUrl = wssUrl,
            zoneId = cachedZone ?: "_unknown",
            natsUser = natsUser,
            natsPassword = natsPass,
            zoneKey = zoneKey,
        )
        val resolvedZone: String = if (cachedZone != null) {
            log("NATS zone=$cachedZone (cached — discovery skipped)")
            cachedZone
        } else {
            val discovered = nc.discoverZone()
            if (discovered.isNullOrBlank() || discovered == "home") {
                log("NATS zone discovery returned ${discovered ?: "null"} — refusing 'home' / staying local-only")
                onHomeZoneUnreachable("zone discovery returned ${discovered ?: "null"}")
                return
            }
            nc.setZoneId(discovered)
            log("NATS zone=$discovered (discovered)")
            discovered
        }

        // Authenticate. Reuse saved creds if present (read from TokenStore so
        // they survive cold starts; legacy System.getProperty kept as a
        // fallback for tests). On a fresh phone where the household allows
        // open registration, fall through to registerAndLogin. Closed-reg
        // households need an invite code — see SharedPreferences key
        // `wyrd_invite_code` (paste UI pending).
        val savedUser = tokenStore.loadMcpUsername()
            ?: System.getProperty("wyrdsekai.mcp.username")
        val savedPass = tokenStore.loadMcpPassword()
            ?: System.getProperty("wyrdsekai.mcp.password")
        val loginAuth = if (!savedUser.isNullOrBlank() && !savedPass.isNullOrBlank()) {
            val a = nc.login(savedUser, savedPass)
            log("NATS login OK with saved creds for $savedUser")
            a
        } else {
            val (creds, auth) = nc.registerAndLogin(companionName)
            tokenStore.saveMcpUsername(creds.first)
            tokenStore.saveMcpPassword(creds.second)
            System.setProperty("wyrdsekai.mcp.username", creds.first)
            System.setProperty("wyrdsekai.mcp.password", creds.second)
            log("NATS registerAndLogin OK as ${creds.first}")
            auth
        }
        // The account owns the Study — persist + surface the userId so the phone
        // syncs under the ACCOUNT, not the companion soul DID.
        loginAuth.userId?.let { uid ->
            tokenStore.saveUserId(uid)
            onAccountUserId(uid, loginAuth.token)
        }

        // Persist the zone we just authenticated against, so the next cold
        // start can skip `wyrd.discover.zone` and avoid the responder race.
        // We only save AFTER successful auth — saving on discovery alone
        // would lock us to the wrong zone if the racey discovery picked a
        // node where our creds don't exist.
        if (tokenStore.loadZoneId() != resolvedZone) {
            tokenStore.saveZoneId(resolvedZone)
            log("NATS zone $resolvedZone persisted for future cold starts")
        }

        org.wyrdsekai.app.network.ServerClientHolder.set(nc)
        // hermod: the consent toggle can now mint a device identity over
        // THIS leg when no HTTP reaches the zone (relay-resident phones).
        org.wyrdsekai.app.hermod.RemoteMint.install { session, name, type ->
            nc.pairDevice(session, name, type)
        }
        log("NATS client authenticated; tell/journal/library route through wyrd.zone.$resolvedZone.*")

        // stand up the dumb-pipe leg. The session token we
        // just minted (mcp.login → session.token()) is exactly what the zone's
        // /ws?token= accepts, so persist it for WyrdApp to hand the tunnel
        // connection. Then open a SEPARATE raw NATS pub/sub BetweenClient on the
        // same relay: it carries C2S/S2C frames on wyrd.tunnel.{zone}.* so the
        // phone terminal tunnels a FULL session into the real zone instead of
        // driving the offline node. RelayTunnelHolder publishes it to WyrdApp.
        val sessionToken = nc.getToken()
        if (!sessionToken.isNullOrBlank()) {
            tokenStore.saveAuthToken(sessionToken)
        }
        try {
            val tunnel = org.wyrdsekai.app.engine.between.NatsBetweenClient(scope)
            tunnel.autoReconnect = true
            tunnel.setCredentials(natsUser, natsPass)
            // Retry with backoff (1s,2s,4s,8s,16s) instead of a single shot. The
            // relay leg + this tunnel come up in parallel during boot, so the
            // first attempt can lose a transient race; without retry the tunnel
            // stays down for the WHOLE session and the terminal silently falls to
            // the offline local node (Mode 1 → Mode 2/3) — the "shows local Study
            // not the remote room" symptom. autoReconnect handles drops AFTER this.
            tunnel.connectWithRetry(wssUrl)
            if (tunnel.isConnected) {
                org.wyrdsekai.app.engine.transit.RelayTunnelHolder.set(tunnel)
                log("Relay tunnel up — phone terminal tunnels full session over wyrd.tunnel.$resolvedZone.* (sealed)")
                // The Between subsystems (presence, Study sync, …) do NOT come up
                // on this leg: their pub/sub would cross the relay readable,
                // session token included. They run on the home's own bus (see
                // the home-bus loop in start()).
            } else {
                org.wyrdsekai.app.engine.transit.RelayTunnelHolder.clear()
                log("Relay tunnel BetweenClient failed to connect — terminal stays on offline node")
                onHomeZoneUnreachable("relay tunnel could not connect")
            }
        } catch (e: Exception) {
            org.wyrdsekai.app.engine.transit.RelayTunnelHolder.clear()
            log("Relay tunnel setup error: ${e.message} — terminal stays on offline node")
            onHomeZoneUnreachable("relay tunnel error: ${e.message}")
        }
    } catch (e: Throwable) {
        log("NATS setup failed: ${e.message} — staying local-only")
        onHomeZoneUnreachable("connection failed: ${e.message}")
    }
}
