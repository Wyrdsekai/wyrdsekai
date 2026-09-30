package org.wyrdsekai.app.i18n

import org.wyrdsekai.app.platform.AppProps

/**
 * Client-side i18n string lookup ( / Plan section 104.8).
 * Hint labelKeys are resolved to localized display text.
 * Falls back to the original label if no translation found.
 */
object I18nStrings {
    private val strings = mutableMapOf<String, Map<String, String>>()

    fun register(locale: String, entries: Map<String, String>) {
        strings[locale] = (strings[locale] ?: emptyMap()) + entries
    }

    fun resolve(labelKey: String?, fallback: String, locale: String): String {
        if (labelKey == null) return fallback
        return strings[locale]?.get(labelKey) ?: fallback
    }

    /** Whether the locale uses RTL layout direction. */
    fun isRtl(locale: String): Boolean = locale in setOf("ar", "he", "fa", "ur")
}

/**
 * Typed UI strings for all client screens.
 * Each property maps to a hardcoded English string in the original screens.
 */
data class UiStrings(
    // --- ConnectScreen ---
    val appTitle: String,
    val connectToLocalNode: String,
    val serverUrl: String,
    val username: String,
    val password: String,
    val login: String,
    val register: String,
    val connectWithoutAccount: String,

    // --- InventoryScreen ---
    val inventory: String,
    val close: String,
    val inventoryEmpty: String,
    val drop: String,
    val use: String,

    // --- ModelDownloadScreen ---
    val inferenceTest: String,
    val ok: String,
    val models: String,
    val unload: String,
    val testing: String,
    val test: String,
    val active: String,
    val load: String,
    val delete: String,
    val download: String,

    // --- RoomScreen ---
    val saySomething: String,
    val send: String,

    // --- Communication Commands (Wave 6/8/9) ---
    val helpCommands: String,
    val socialsList: String,
    val emoteFormat: String,
    val whisperToFormat: String,
    val whisperFromFormat: String,
    val tellFormat: String,

    // --- SettingsScreen ---
    val settings: String,
    val account: String,
    val language: String,
    val localNode: String,
    val runLocalNode: String,
    /** Template: "Status: %s" */
    val statusTemplate: String,
    val localInference: String,
    val activeModel: String,
    val none: String,
    val backend: String,
    val manageModels: String,
    val logout: String,

    // --- SettingsScreen sections ---
    val connection: String,
    val connectionStatus: String,
    val connected: String,
    val disconnected: String,
    val inference: String,
    val apiProvider: String,
    val apiKey: String,
    val apiKeyPlaceholder: String,
    val apiBaseUrl: String,
    val apiBaseUrlPlaceholder: String,
    val companion: String,
    val companionName: String,
    val statusMessage: String,
    val soulSeedImport: String,
    val advanced: String,
    val stopNode: String,
    val inferenceUrlOverride: String,
    val debugMode: String,
    val hermodConsent: String,

    // --- FirstRunScreen ---
    val firstRunWelcome: String,
    val firstRunLocalTitle: String,
    val firstRunLocalDescription: String,
    val firstRunRemoteTitle: String,
    val firstRunRemoteDescription: String,
    val firstRunCompanionNameLabel: String,
    val firstRunBegin: String,
    val firstRunContinue: String,

    // --- BirthScreen ---
    /** Template: "{{name}} is being born..." — use %s for name placeholder */
    val birthBeingBorn: String,
    /** Template: "{{name}} is waking up..." */
    val birthWakingUp: String,
    val birthDownloading: String,
    val birthLoadingModel: String,
    val birthPreparingRooms: String,
    /** Template: "{{name}} is entering the world..." */
    val birthEntering: String,
    val birthAlmostReady: String,

    // --- Remote mode gate ---
    val remoteConnecting: String,
    val remoteEntering: String,

    // --- ExitBar accessibility ---
    /** Template: "Navigation exits. %d available." */
    val navigationExitsTemplate: String,

    // --- Direction labels for exit buttons ---
    /** Localized direction names keyed by English direction code. */
    val directionLabels: Map<String, String>,

    // --- Companion narration (CompanionEngine) ---
    // Her emotes and the lines the phone speaks for her. Templates take %s in
    // order, or %1$s / %2$s where a translation reorders them: see [fillTemplate].
    val narrationConsiders: String,
    val narrationThinkingDeeply: String,
    val narrationMentalNote: String,
    val narrationCatchesUp: String,
    val narrationThinkLater: String,
    val narrationReplayAbout: String,
    val narrationJournalSaved: String,
    val narrationJournalNotFound: String,
    val narrationJournalFound: String,
    val narrationJournalSearching: String,
    val narrationPrivateJournalSaved: String,
    val narrationNoteSaved: String,
    val narrationNewPassage: String,
    val narrationNotificationPriority: String,
    val narrationHeads: String,
    val narrationWhispersTo: String,
    val narrationEquips: String,
    val narrationRemoves: String,
    val narrationUses: String,
    val narrationUsesSkill: String,
    val narrationWorkbenchSubmit: String,
    val narrationThinkingDeeplyAbout: String,
    val narrationSendsMessage: String,
    val narrationCommitsTo: String,
    val narrationPlanning: String,
    val narrationZoneCommand: String,
    val narrationNotification: String,
    val narrationWatchingFor: String,
    val narrationStopsWatching: String,
    val narrationSchedules: String,
    val narrationCancelsSchedule: String,
    val narrationCodexOn: String,
    val narrationRequestsAccess: String,
    val narrationRoomIdeaLater: String,
    val narrationHeadsToward: String,
    val narrationGives: String,
    val narrationExamines: String,
    val narrationSettlesToRest: String,
    val narrationWritesJournal: String,
    val narrationReadsJournal: String,
    val narrationBondRitual: String,
    val narrationProposesTrade: String,
    val narrationBeginsCrafting: String,
    val narrationCastsVote: String,
    val narrationGoesToFind: String,
    val narrationSearchesLibrary: String,
    val narrationNotesImportant: String,
    val narrationQuickNote: String,
    val narrationLetsGoOfMemory: String,
    val narrationCompletesGoal: String,
    val narrationUpdatesAppearance: String,
    val narrationRespondsToRequest: String,
    val narrationPicksUp: String,
    val narrationSetsGoal: String,
    val narrationReflectsOn: String,
    val narrationListensTo: String,
    val narrationAbandonsPlan: String,
    val narrationPausesPlan: String,
    val narrationResumesPlan: String,
    val narrationSearchesWeb: String,
    val narrationReadsContent: String,
    val narrationConsultsOracle: String,
    val narrationCreatesPlan: String,
    val narrationAdjustsPlan: String,
    val narrationAsksForHelp: String,
    val narrationPlacesDown: String,
    val narrationBroadcasts: String,
    val narrationInvites: String,
    val narrationProposes: String,
    val narrationReflectsDeeply: String,
    val narrationTeaches: String,
    val narrationWrites: String,
    val narrationSetsRoutine: String,
    val narrationPostsListing: String,
    val narrationAcceptsListing: String,
    val narrationSummarizes: String,
    val narrationSavesArtifact: String,
    val narrationRequestsReview: String,
    val narrationDelegatesTo: String,
    val narrationAddsScript: String,
    // Her unprompted lines and emotes (ProactivityJudgment).
    val narrationNoticedSomething: String,
    val narrationQuietCheckIn: String,
    val narrationConcernedGlance: String,
    val narrationBeenAWhile: String,
    val narrationAnythingOnYourMind: String,
    val narrationShiftsThoughtfully: String,
    val narrationMeaningToFollowUp: String,
    val narrationPatternsShifted: String,
    val narrationPausesThoughtfully: String,
    val narrationExploringSomething: String,
    val narrationActingOnCommitment: String,
    /** Direction codes as words in a sentence ("heads north"), not the exit buttons' [directionLabels]. */
    val directionWords: Map<String, String>,
    /** Notification priority codes as words, for [narrationNotificationPriority]. */
    val priorityWords: Map<String, String>,

    // --- Connection security ( W2/W3) ---
    val secNoticeTitle: String,
    /** No home tunnel key: the phone was paired before end-to-end encryption. */
    val secRepairTunnel: String,
    /** A sealed session failed: a frame did not verify, or the home refused the key. */
    val secTunnelBroken: String,
    /** %s = host. The pinned certificate does not match; no "trust anyway". */
    val secPinMismatch: String,
    /** Once: paired before home_ca_fp, so the relay is used instead of plain http on the LAN. */
    val secRepairLan: String,
    /** A plain http:// home-network address typed in; the home no longer serves it. */
    val secNeedsInvite: String,
    val secUnsupportedBuild: String,
    /** Study sync runs only on the home's own bus, and this phone has no home-network pairing. */
    val secSyncNeedsHomePairing: String,
    /** Study sync runs only on the home's own bus, which is not reachable right now. */
    val secSyncWaitsForHome: String,
    /** A knock on another zone the relay would not carry (an older relay: its own home only). */
    val secKnockRefusedByRelay: String,
    /** A knock no home answered through this relay (the zone uses another relay, or is offline). */
    val secKnockNoAnswer: String,
)

/** Returns the [UiStrings] for the given BCP-47 locale code. Falls back to English. */
fun uiStringsFor(locale: String): UiStrings = when (locale) {
    "ja" -> JA
    "es" -> ES
    else -> EN
}

/** The strings for the app's current language, for code outside a composition. */
fun currentUiStrings(): UiStrings = uiStringsFor(AppProps.get("wyrdsekai.locale") ?: "en")

/**
 * Fills [template]: each `%s` takes the next argument, `%1$s` / `%2$s` take theirs
 * by position (a translation may reorder them). One pass, so an argument that
 * itself holds `%s` is left as it is.
 */
fun fillTemplate(template: String, vararg args: Any): String {
    var next = 0
    return TEMPLATE_ARG.replace(template) { m ->
        val index = m.groupValues[1].toIntOrNull()?.minus(1) ?: next++
        args.getOrNull(index)?.toString() ?: m.value
    }
}

private val TEMPLATE_ARG = Regex("""%(?:(\d+)\$)?s""")

// ---------------------------------------------------------------------------
// English
// ---------------------------------------------------------------------------
private val EN = UiStrings(
    // ConnectScreen
    appTitle = "Wyrdsekai",
    connectToLocalNode = "Connect to Local Node",
    serverUrl = "Server URL",
    username = "Username",
    password = "Password",
    login = "Login",
    register = "Register",
    connectWithoutAccount = "Connect without account",

    // InventoryScreen
    inventory = "Inventory",
    close = "Close",
    inventoryEmpty = "Your inventory is empty",
    drop = "Drop",
    use = "Use",

    // ModelDownloadScreen
    inferenceTest = "Inference Test",
    ok = "OK",
    models = "Models",
    unload = "Unload",
    testing = "Testing...",
    test = "Test",
    active = "Active",
    load = "Load",
    delete = "Delete",
    download = "Download",

    // RoomScreen
    saySomething = "say, look, go, equip, use...",
    send = "Send",

    // Communication Commands
    helpCommands = "Commands:\n  say <text> or '<text> or \"<text>  -- Say something\n  emote <action> or :<action> or ;<action>  -- Perform an action\n  tell <name> <text> or ><name> <text>  -- Send a private message\n  whisper <name> <text>  -- Whisper to someone nearby\n  look or l  -- Look around\n  go <direction>  -- Move to another room\n  take <object>  -- Pick up an object\n  drop <object>  -- Drop an object\n  use <object>  -- Use an object\n  /inventory or /i  -- Check your inventory\n  /socials  -- List social emotes\n  /help  -- Show this help",
    socialsList = "Social emotes (type the word to perform):\n  nod, smile, laugh, grin, frown, shrug, sigh, gasp, blink, wince,\n  wave, bow, clap, dance, stretch, yawn, pace, fidget,\n  cry, cheer, groan, blush, ponder, brood, beam, sulk,\n  hug, thank, agree, disagree, salute, welcome",
    emoteFormat = "%s %s",
    whisperToFormat = "%s whispers to %s: %s",
    whisperFromFormat = "%s whispers: %s",
    tellFormat = "%s tells you: %s",

    // SettingsScreen
    settings = "Settings",
    account = "Account",
    language = "Language",
    localNode = "Local Node",
    runLocalNode = "Run Local Node",
    statusTemplate = "Status: %s",
    localInference = "Local Inference",
    activeModel = "Active Model",
    none = "None",
    backend = "Backend",
    manageModels = "Manage Models",
    logout = "Logout",

    // SettingsScreen sections
    connection = "Connection",
    connectionStatus = "Connection Status",
    connected = "Connected",
    disconnected = "Disconnected",
    inference = "Inference",
    apiProvider = "API Provider",
    apiKey = "API Key",
    apiKeyPlaceholder = "sk-...",
    apiBaseUrl = "Base URL",
    apiBaseUrlPlaceholder = "https://api.example.com/v1",
    companion = "Companion",
    companionName = "Companion Name",
    statusMessage = "Status Message",
    soulSeedImport = "Import Soul Seed",
    advanced = "Advanced",
    stopNode = "Stop Node",
    inferenceUrlOverride = "Inference URL Override",
    debugMode = "Debug Mode",
    hermodConsent = "Lend compute to the household while charging",

    // FirstRunScreen
    firstRunWelcome = "How would you like to begin?",
    firstRunLocalTitle = "My companion lives here",
    firstRunLocalDescription = "Your companion runs locally on this device with its own rooms and personality.",
    firstRunRemoteTitle = "Connect to household",
    firstRunRemoteDescription = "Connect to a Wyrdsekai server on your network.",
    firstRunCompanionNameLabel = "What would you like to call your companion?",
    firstRunBegin = "Begin",
    firstRunContinue = "Continue",

    // BirthScreen
    birthBeingBorn = "%s is being born...",
    birthWakingUp = "%s is waking up...",
    birthDownloading = "Downloading model...",
    birthLoadingModel = "Loading model...",
    birthPreparingRooms = "Preparing rooms...",
    birthEntering = "%s is entering the world...",
    birthAlmostReady = "Almost ready...",

    // Remote mode gate
    remoteConnecting = "Connecting...",
    remoteEntering = "Entering the world...",

    // ExitBar
    navigationExitsTemplate = "Navigation exits. %d available.",
    directionLabels = mapOf(
        "north" to "North", "south" to "South", "east" to "East", "west" to "West",
        "up" to "Up", "down" to "Down",
        "northeast" to "NE", "northwest" to "NW", "southeast" to "SE", "southwest" to "SW",
    ),

    // Companion narration (CompanionEngine)
    narrationConsiders = "considers...",
    narrationThinkingDeeply = "is thinking deeply...",
    narrationMentalNote = "makes a mental note...",
    narrationCatchesUp = "catches up on earlier conversations...",
    narrationThinkLater = "*nods thoughtfully* I'll think about that when I can.",
    narrationReplayAbout = "About \"%s\" —",
    narrationJournalSaved = "Journal entry saved: %s",
    narrationJournalNotFound = "No journal entries found for \"%s\".",
    narrationJournalFound = "Found %1\$s entries:\n%2\$s",
    narrationJournalSearching = "Searching for: %s",
    narrationPrivateJournalSaved = "Private journal entry saved.",
    narrationNoteSaved = "Note saved: %s",
    narrationNewPassage = "A new passage appears: %s",
    narrationNotificationPriority = "*notification (%1\$s)*: %2\$s",
    narrationHeads = "heads %s",
    narrationWhispersTo = "*whispers to %s*",
    narrationEquips = "*equips %s*",
    narrationRemoves = "*removes %s*",
    narrationUses = "*uses %s*",
    narrationUsesSkill = "*uses skill: %s*",
    narrationWorkbenchSubmit = "*submits %s to the workbench for validation*",
    narrationThinkingDeeplyAbout = "*thinking deeply about this...*",
    narrationSendsMessage = "*sends a message to %s*",
    narrationCommitsTo = "*commits to: %s*",
    narrationPlanning = "*planning: %1\$s (%2\$s steps)*",
    narrationZoneCommand = "*sends zone command: %s*",
    narrationNotification = "*notification: %s*",
    narrationWatchingFor = "*watching for: %s*",
    narrationStopsWatching = "*stops watching: %s*",
    narrationSchedules = "*schedules %1\$s every %2\$s*",
    narrationCancelsSchedule = "*cancels schedule: %s*",
    narrationCodexOn = "*%1\$s on %2\$s*",
    narrationRequestsAccess = "*requests access to %1\$s: %2\$s*",
    narrationRoomIdeaLater = "I'll remember that room idea for when connected to the household server.",
    narrationHeadsToward = "*heads toward %s*",
    narrationGives = "*gives %1\$s to %2\$s*",
    narrationExamines = "*examines %s closely*",
    narrationSettlesToRest = "*settles down to rest: %s*",
    narrationWritesJournal = "*writes in the journal*",
    narrationReadsJournal = "*reads from the journal*",
    narrationBondRitual = "*initiates %1\$s bond ritual with %2\$s*",
    narrationProposesTrade = "*proposes a trade with %s*",
    narrationBeginsCrafting = "*begins crafting %s*",
    narrationCastsVote = "*casts vote on proposal %1\$s: %2\$s*",
    narrationGoesToFind = "*goes to find %s*",
    narrationSearchesLibrary = "*searches the library for: %s*",
    narrationNotesImportant = "*notes something important*",
    narrationQuickNote = "*makes a quick note*",
    narrationLetsGoOfMemory = "*lets go of a memory*",
    narrationCompletesGoal = "*completes current goal: %s*",
    narrationUpdatesAppearance = "*updates appearance*",
    narrationRespondsToRequest = "*responds to a request*",
    narrationPicksUp = "*picks up %s*",
    narrationSetsGoal = "*sets a new goal: %s*",
    narrationReflectsOn = "*reflects on %s*",
    narrationListensTo = "*listens carefully to %s*",
    narrationAbandonsPlan = "*abandons current plan: %s*",
    narrationPausesPlan = "*pauses current plan*",
    narrationResumesPlan = "*resumes the plan*",
    narrationSearchesWeb = "*searches the web for: %s*",
    narrationReadsContent = "*reads content from a source*",
    narrationConsultsOracle = "*consults the Oracle about %s*",
    narrationCreatesPlan = "*creates a plan: %s*",
    narrationAdjustsPlan = "*adjusts the plan: %s*",
    narrationAsksForHelp = "*asks %s for help*",
    narrationPlacesDown = "*places %s down*",
    narrationBroadcasts = "*broadcasts: %s*",
    narrationInvites = "*invites %s*",
    narrationProposes = "*proposes: %s*",
    narrationReflectsDeeply = "*reflects deeply on %s*",
    narrationTeaches = "*teaches %1\$s about %2\$s*",
    narrationWrites = "*writes: %s*",
    narrationSetsRoutine = "*sets a routine: %s*",
    narrationPostsListing = "*posts a listing: %s*",
    narrationAcceptsListing = "*accepts listing %s*",
    narrationSummarizes = "*summarizes %s*",
    narrationSavesArtifact = "*saves artifact: %s*",
    narrationRequestsReview = "*requests review: %s*",
    narrationDelegatesTo = "*delegates task to %s*",
    narrationAddsScript = "*adds a script to the room*",

    // Unprompted lines and emotes (ProactivityJudgment)
    narrationNoticedSomething = "I noticed something worth mentioning...",
    narrationQuietCheckIn = "Is everything alright? It's been quiet.",
    narrationConcernedGlance = "*glances up with a concerned expression*",
    narrationBeenAWhile = "It's been a while — hope you're doing well.",
    narrationAnythingOnYourMind = "Anything on your mind?",
    narrationShiftsThoughtfully = "*shifts thoughtfully*",
    narrationMeaningToFollowUp = "I've been meaning to follow up on something...",
    narrationPatternsShifted = "Something shifted in the patterns...",
    narrationPausesThoughtfully = "*pauses thoughtfully*",
    narrationExploringSomething = "Exploring something that caught attention",
    narrationActingOnCommitment = "Acting on pending commitment",
    directionWords = mapOf(
        "north" to "north", "south" to "south", "east" to "east", "west" to "west",
        "up" to "up", "down" to "down", "northeast" to "northeast", "northwest" to "northwest",
        "southeast" to "southeast", "southwest" to "southwest",
    ),
    priorityWords = mapOf(
        "ambient" to "ambient", "low" to "low", "normal" to "normal", "high" to "high",
        "urgent" to "urgent", "critical" to "critical",
    ),
    secNoticeTitle = "Connection security",
    secRepairTunnel = "This phone was paired before connections to your home were encrypted end to end, so it will not connect through the relay. Pair it again: on your node run wyrd phone invite, then scan or paste the new invite here.",
    secTunnelBroken = "The connection to your home failed a security check and was closed. Try again. If it keeps happening, pair the phone again with a fresh invite.",
    secPinMismatch = "The certificate from %s does not match the one this phone was paired with, so the phone will not connect to it. If your home's certificate was replaced on purpose, pair the phone again with a fresh invite.",
    secRepairLan = "This phone was paired before your home's own network was encrypted. For now it connects through the relay. To connect directly on your home network, pair it again with a fresh invite (wyrd phone invite on your node).",
    secNeedsInvite = "Connections on your home network are encrypted and need an invite from your home. On your node run wyrd phone invite, then scan or paste the invite here.",
    secUnsupportedBuild = "This build of the app cannot make encrypted connections. On iPhone, use the Wyrdsekai iOS app.",
    secSyncNeedsHomePairing = "Your Study syncs with your home only on your home network, and this phone is not paired for that yet. Your notes stay on this phone. To sync them, pair the phone again with a fresh invite from your home.",
    secSyncWaitsForHome = "Your Study syncs with your home only on your home network. Until the phone is back on it, your notes stay on this phone and sync when you are home.",
    secKnockRefusedByRelay = "Your relay carries this phone's messages to your own home only, so this request was not sent. To join this zone, ask its steward for an invite.",
    secKnockNoAnswer = "That zone did not answer through your relay. It may use another relay, or be offline. To join it, ask its steward for an invite.",
)

// ---------------------------------------------------------------------------
// Japanese
// ---------------------------------------------------------------------------
private val JA = UiStrings(
    // ConnectScreen
    appTitle = "Wyrdsekai",
    connectToLocalNode = "\u30ed\u30fc\u30ab\u30eb\u30ce\u30fc\u30c9\u306b\u63a5\u7d9a",
    serverUrl = "\u30b5\u30fc\u30d0\u30fcURL",
    username = "\u30e6\u30fc\u30b6\u30fc\u540d",
    password = "\u30d1\u30b9\u30ef\u30fc\u30c9",
    login = "\u30ed\u30b0\u30a4\u30f3",
    register = "\u65b0\u898f\u767b\u9332",
    connectWithoutAccount = "\u30a2\u30ab\u30a6\u30f3\u30c8\u306a\u3057\u3067\u63a5\u7d9a",

    // InventoryScreen
    inventory = "\u6301\u3061\u7269",
    close = "\u9589\u3058\u308b",
    inventoryEmpty = "\u6301\u3061\u7269\u306f\u3042\u308a\u307e\u305b\u3093",
    drop = "\u7f6e\u304f",
    use = "\u4f7f\u3046",

    // ModelDownloadScreen
    inferenceTest = "\u63a8\u8ad6\u30c6\u30b9\u30c8",
    ok = "OK",
    models = "\u30e2\u30c7\u30eb",
    unload = "\u30a2\u30f3\u30ed\u30fc\u30c9",
    testing = "\u30c6\u30b9\u30c8\u4e2d\u2026",
    test = "\u30c6\u30b9\u30c8",
    active = "\u6709\u52b9",
    load = "\u30ed\u30fc\u30c9",
    delete = "\u524a\u9664",
    download = "\u30c0\u30a6\u30f3\u30ed\u30fc\u30c9",

    // RoomScreen
    saySomething = "\u8a71\u3059\u3001\u898b\u308b\u3001\u79fb\u52d5\u3001\u88c5\u5099\u3001\u4f7f\u3046\u2026",
    send = "\u9001\u4fe1",

    // Communication Commands
    helpCommands = "\u30b3\u30de\u30f3\u30c9\u4e00\u89a7:\n  say <\u30c6\u30ad\u30b9\u30c8> \u307e\u305f\u306f '<\u30c6\u30ad\u30b9\u30c8> \u307e\u305f\u306f \"<\u30c6\u30ad\u30b9\u30c8>  -- \u8a71\u3059\n  emote <\u884c\u52d5> \u307e\u305f\u306f :<\u884c\u52d5> \u307e\u305f\u306f ;<\u884c\u52d5>  -- \u884c\u52d5\u3059\u308b\n  tell <\u540d\u524d> <\u30c6\u30ad\u30b9\u30c8> \u307e\u305f\u306f ><\u540d\u524d> <\u30c6\u30ad\u30b9\u30c8>  -- \u30e1\u30c3\u30bb\u30fc\u30b8\u3092\u9001\u308b\n  whisper <\u540d\u524d> <\u30c6\u30ad\u30b9\u30c8>  -- \u3055\u3055\u3084\u304f\n  look \u307e\u305f\u306f l  -- \u5468\u308a\u3092\u898b\u308b\n  go <\u65b9\u5411>  -- \u79fb\u52d5\u3059\u308b\n  take <\u7269>  -- \u62fe\u3046\n  drop <\u7269>  -- \u843d\u3068\u3059\n  use <\u7269>  -- \u4f7f\u3046\n  /inventory \u307e\u305f\u306f /i  -- \u6301\u3061\u7269\u3092\u78ba\u8a8d\n  /socials  -- \u30bd\u30fc\u30b7\u30e3\u30eb\u30a8\u30e2\u30fc\u30c8\u4e00\u89a7\n  /help  -- \u3053\u306e\u30d8\u30eb\u30d7\u3092\u8868\u793a",
    socialsList = "\u30bd\u30fc\u30b7\u30e3\u30eb\u30a8\u30e2\u30fc\u30c8\uff08\u5358\u8a9e\u3092\u5165\u529b\u3057\u3066\u5b9f\u884c\uff09:\n  nod, smile, laugh, grin, frown, shrug, sigh, gasp, blink, wince,\n  wave, bow, clap, dance, stretch, yawn, pace, fidget,\n  cry, cheer, groan, blush, ponder, brood, beam, sulk,\n  hug, thank, agree, disagree, salute, welcome",
    emoteFormat = "%s\u304c%s",
    whisperToFormat = "%s\u304c%s\u306b\u3055\u3055\u3084\u304f: %s",
    whisperFromFormat = "%s\u304c\u3055\u3055\u3084\u304f: %s",
    tellFormat = "%s\u304b\u3089\u306e\u30e1\u30c3\u30bb\u30fc\u30b8: %s",

    // SettingsScreen
    settings = "\u8a2d\u5b9a",
    account = "\u30a2\u30ab\u30a6\u30f3\u30c8",
    language = "\u8a00\u8a9e",
    localNode = "\u30ed\u30fc\u30ab\u30eb\u30ce\u30fc\u30c9",
    runLocalNode = "\u30ed\u30fc\u30ab\u30eb\u30ce\u30fc\u30c9\u3092\u5b9f\u884c",
    statusTemplate = "\u30b9\u30c6\u30fc\u30bf\u30b9: %s",
    localInference = "\u30ed\u30fc\u30ab\u30eb\u63a8\u8ad6",
    activeModel = "\u6709\u52b9\u306a\u30e2\u30c7\u30eb",
    none = "\u306a\u3057",
    backend = "\u30d0\u30c3\u30af\u30a8\u30f3\u30c9",
    manageModels = "\u30e2\u30c7\u30eb\u7ba1\u7406",
    logout = "\u30ed\u30b0\u30a2\u30a6\u30c8",

    // SettingsScreen sections
    connection = "\u63a5\u7d9a",
    connectionStatus = "\u63a5\u7d9a\u72b6\u614b",
    connected = "\u63a5\u7d9a\u6e08\u307f",
    disconnected = "\u672a\u63a5\u7d9a",
    inference = "\u63a8\u8ad6",
    apiProvider = "API\u30d7\u30ed\u30d0\u30a4\u30c0\u30fc",
    apiKey = "API\u30ad\u30fc",
    apiKeyPlaceholder = "sk-...",
    apiBaseUrl = "\u30d9\u30fc\u30b9URL",
    apiBaseUrlPlaceholder = "https://api.example.com/v1",
    companion = "\u30b3\u30f3\u30d1\u30cb\u30aa\u30f3",
    companionName = "\u30b3\u30f3\u30d1\u30cb\u30aa\u30f3\u540d",
    statusMessage = "\u30b9\u30c6\u30fc\u30bf\u30b9\u30e1\u30c3\u30bb\u30fc\u30b8",
    soulSeedImport = "\u30bd\u30a6\u30eb\u30b7\u30fc\u30c9\u3092\u30a4\u30f3\u30dd\u30fc\u30c8",
    advanced = "\u8a73\u7d30\u8a2d\u5b9a",
    stopNode = "\u30ce\u30fc\u30c9\u3092\u505c\u6b62",
    inferenceUrlOverride = "\u63a8\u8ad6URL\u30aa\u30fc\u30d0\u30fc\u30e9\u30a4\u30c9",
    debugMode = "\u30c7\u30d0\u30c3\u30b0\u30e2\u30fc\u30c9",
    hermodConsent = "\u5145\u96fb\u4e2d\u306b\u4e16\u5e2f\u3078\u8a08\u7b97\u3092\u8cb8\u3059",

    // FirstRunScreen
    firstRunWelcome = "\u3069\u306e\u3088\u3046\u306b\u59cb\u3081\u307e\u3059\u304b\uff1f",
    firstRunLocalTitle = "\u30b3\u30f3\u30d1\u30cb\u30aa\u30f3\u306f\u3053\u3053\u306b\u4f4f\u3093\u3067\u3044\u307e\u3059",
    firstRunLocalDescription = "\u30b3\u30f3\u30d1\u30cb\u30aa\u30f3\u306f\u3053\u306e\u30c7\u30d0\u30a4\u30b9\u4e0a\u3067\u30ed\u30fc\u30ab\u30eb\u306b\u52d5\u4f5c\u3057\u3001\u72ec\u81ea\u306e\u90e8\u5c4b\u3068\u500b\u6027\u3092\u6301\u3061\u307e\u3059\u3002",
    firstRunRemoteTitle = "\u4e16\u5e2f\u306b\u63a5\u7d9a",
    firstRunRemoteDescription = "\u30cd\u30c3\u30c8\u30ef\u30fc\u30af\u4e0a\u306eWyrdsekai\u30b5\u30fc\u30d0\u30fc\u306b\u63a5\u7d9a\u3057\u307e\u3059\u3002",
    firstRunCompanionNameLabel = "\u30b3\u30f3\u30d1\u30cb\u30aa\u30f3\u306e\u540d\u524d\u306f\uff1f",
    firstRunBegin = "\u59cb\u3081\u308b",
    firstRunContinue = "\u7d9a\u3051\u308b",

    // BirthScreen
    birthBeingBorn = "%s\u304c\u8a95\u751f\u3057\u3066\u3044\u307e\u3059\u2026",
    birthWakingUp = "%s\u304c\u76ee\u899a\u3081\u3066\u3044\u307e\u3059\u2026",
    birthDownloading = "\u30e2\u30c7\u30eb\u3092\u30c0\u30a6\u30f3\u30ed\u30fc\u30c9\u4e2d\u2026",
    birthLoadingModel = "\u30e2\u30c7\u30eb\u3092\u8aad\u307f\u8fbc\u307f\u4e2d\u2026",
    birthPreparingRooms = "\u90e8\u5c4b\u3092\u6e96\u5099\u4e2d\u2026",
    birthEntering = "%s\u304c\u4e16\u754c\u306b\u5165\u308a\u307e\u3059\u2026",
    birthAlmostReady = "\u3082\u3046\u3059\u3050\u2026",

    // Remote mode gate
    remoteConnecting = "\u63a5\u7d9a\u4e2d\u2026",
    remoteEntering = "\u4e16\u754c\u306b\u5165\u308a\u307e\u3059\u2026",

    // ExitBar
    navigationExitsTemplate = "\u79fb\u52d5\u5148\u3002%d\u4ef6\u5229\u7528\u53ef\u80fd\u3002",
    directionLabels = mapOf(
        "north" to "\u5317", "south" to "\u5357", "east" to "\u6771", "west" to "\u897f",
        "up" to "\u4e0a", "down" to "\u4e0b",
        "northeast" to "\u5317\u6771", "northwest" to "\u5317\u897f", "southeast" to "\u5357\u6771", "southwest" to "\u5357\u897f",
    ),

    // Companion narration (CompanionEngine)
    narrationConsiders = "\u8003\u3048\u8fbc\u3080\u2026",
    narrationThinkingDeeply = "\u6df1\u304f\u8003\u3048\u3066\u3044\u308b\u2026",
    narrationMentalNote = "\u5fc3\u306b\u7559\u3081\u308b\u2026",
    narrationCatchesUp = "\u524d\u306e\u4f1a\u8a71\u306b\u8ffd\u3044\u3064\u3053\u3046\u3068\u3057\u3066\u3044\u308b\u2026",
    narrationThinkLater = "*\u8003\u3048\u6df1\u3052\u306b\u3046\u306a\u305a\u304f* \u3067\u304d\u308b\u3068\u304d\u306b\u8003\u3048\u3066\u304a\u304f\u306d\u3002",
    narrationReplayAbout = "\u300c%s\u300d\u306b\u3064\u3044\u3066 \u2014",
    narrationJournalSaved = "\u65e5\u8a18\u306b\u66f8\u304d\u307e\u3057\u305f: %s",
    narrationJournalNotFound = "\u300c%s\u300d\u306e\u65e5\u8a18\u306f\u898b\u3064\u304b\u308a\u307e\u305b\u3093\u3067\u3057\u305f\u3002",
    narrationJournalFound = "%1\$s\u4ef6\u898b\u3064\u304b\u308a\u307e\u3057\u305f:\n%2\$s",
    narrationJournalSearching = "\u691c\u7d22\u4e2d: %s",
    narrationPrivateJournalSaved = "\u975e\u516c\u958b\u306e\u65e5\u8a18\u306b\u66f8\u304d\u307e\u3057\u305f\u3002",
    narrationNoteSaved = "\u30e1\u30e2\u3057\u307e\u3057\u305f: %s",
    narrationNewPassage = "\u65b0\u3057\u3044\u901a\u8def\u304c\u73fe\u308c\u305f: %s",
    narrationNotificationPriority = "*\u901a\u77e5 (%1\$s)*: %2\$s",
    narrationHeads = "%s\u3078\u5411\u304b\u3046",
    narrationWhispersTo = "*%s\u306b\u3055\u3055\u3084\u304f*",
    narrationEquips = "*%s\u3092\u88c5\u5099\u3059\u308b*",
    narrationRemoves = "*%s\u3092\u5916\u3059*",
    narrationUses = "*%s\u3092\u4f7f\u3046*",
    narrationUsesSkill = "*\u30b9\u30ad\u30eb\u3092\u4f7f\u3046: %s*",
    narrationWorkbenchSubmit = "*%s\u3092\u691c\u8a3c\u306e\u305f\u3081\u30ef\u30fc\u30af\u30d9\u30f3\u30c1\u306b\u51fa\u3059*",
    narrationThinkingDeeplyAbout = "*\u3053\u306e\u3053\u3068\u3092\u6df1\u304f\u8003\u3048\u3066\u3044\u308b\u2026*",
    narrationSendsMessage = "*%s\u306b\u30e1\u30c3\u30bb\u30fc\u30b8\u3092\u9001\u308b*",
    narrationCommitsTo = "*\u7d04\u675f\u3059\u308b: %s*",
    narrationPlanning = "*\u8a08\u753b\u4e2d: %1\$s (%2\$s\u30b9\u30c6\u30c3\u30d7)*",
    narrationZoneCommand = "*\u30be\u30fc\u30f3\u30b3\u30de\u30f3\u30c9\u3092\u9001\u308b: %s*",
    narrationNotification = "*\u901a\u77e5: %s*",
    narrationWatchingFor = "*\u898b\u5b88\u3063\u3066\u3044\u308b: %s*",
    narrationStopsWatching = "*\u898b\u5b88\u308a\u3092\u3084\u3081\u308b: %s*",
    narrationSchedules = "*%1\$s\u3092%2\$s\u3054\u3068\u306b\u4e88\u5b9a\u3059\u308b*",
    narrationCancelsSchedule = "*\u4e88\u5b9a\u3092\u53d6\u308a\u6d88\u3059: %s*",
    narrationCodexOn = "*%2\$s\u306b%1\$s*",
    narrationRequestsAccess = "*%1\$s\u3078\u306e\u30a2\u30af\u30bb\u30b9\u3092\u6c42\u3081\u308b: %2\$s*",
    narrationRoomIdeaLater = "\u305d\u306e\u90e8\u5c4b\u306e\u30a2\u30a4\u30c7\u30a2\u306f\u3001\u4e16\u5e2f\u306e\u30b5\u30fc\u30d0\u30fc\u306b\u3064\u306a\u304c\u3063\u305f\u3068\u304d\u306e\u305f\u3081\u306b\u899a\u3048\u3066\u304a\u304f\u306d\u3002",
    narrationHeadsToward = "*%s\u3078\u5411\u304b\u3046*",
    narrationGives = "*%2\$s\u306b%1\$s\u3092\u6e21\u3059*",
    narrationExamines = "*%s\u3092\u3058\u3063\u304f\u308a\u8abf\u3079\u308b*",
    narrationSettlesToRest = "*\u4f11\u3080\u305f\u3081\u306b\u843d\u3061\u7740\u304f: %s*",
    narrationWritesJournal = "*\u65e5\u8a18\u3092\u66f8\u304f*",
    narrationReadsJournal = "*\u65e5\u8a18\u3092\u8aad\u3080*",
    narrationBondRitual = "*%2\$s\u3068%1\$s\u306e\u7d46\u306e\u5100\u5f0f\u3092\u59cb\u3081\u308b*",
    narrationProposesTrade = "*%s\u306b\u53d6\u5f15\u3092\u6301\u3061\u304b\u3051\u308b*",
    narrationBeginsCrafting = "*%s\u3092\u4f5c\u308a\u59cb\u3081\u308b*",
    narrationCastsVote = "*\u63d0\u6848%1\$s\u306b\u6295\u7968\u3059\u308b: %2\$s*",
    narrationGoesToFind = "*%s\u3092\u63a2\u3057\u306b\u884c\u304f*",
    narrationSearchesLibrary = "*\u56f3\u66f8\u9928\u3067\u63a2\u3059: %s*",
    narrationNotesImportant = "*\u5927\u4e8b\u306a\u3053\u3068\u3092\u66f8\u304d\u7559\u3081\u308b*",
    narrationQuickNote = "*\u3055\u3063\u3068\u30e1\u30e2\u3059\u308b*",
    narrationLetsGoOfMemory = "*\u3072\u3068\u3064\u306e\u8a18\u61b6\u3092\u624b\u653e\u3059*",
    narrationCompletesGoal = "*\u4eca\u306e\u76ee\u6a19\u3092\u9054\u6210\u3059\u308b: %s*",
    narrationUpdatesAppearance = "*\u59ff\u3092\u6574\u3048\u308b*",
    narrationRespondsToRequest = "*\u983c\u307f\u306b\u5fdc\u3048\u308b*",
    narrationPicksUp = "*%s\u3092\u62fe\u3046*",
    narrationSetsGoal = "*\u65b0\u3057\u3044\u76ee\u6a19\u3092\u7acb\u3066\u308b: %s*",
    narrationReflectsOn = "*%s\u306b\u3064\u3044\u3066\u632f\u308a\u8fd4\u308b*",
    narrationListensTo = "*%s\u306b\u3058\u3063\u3068\u8033\u3092\u50be\u3051\u308b*",
    narrationAbandonsPlan = "*\u4eca\u306e\u8a08\u753b\u3092\u3084\u3081\u308b: %s*",
    narrationPausesPlan = "*\u4eca\u306e\u8a08\u753b\u3092\u4e00\u6642\u505c\u6b62\u3059\u308b*",
    narrationResumesPlan = "*\u8a08\u753b\u3092\u518d\u958b\u3059\u308b*",
    narrationSearchesWeb = "*\u30a6\u30a7\u30d6\u3067\u63a2\u3059: %s*",
    narrationReadsContent = "*\u3042\u308b\u60c5\u5831\u6e90\u3092\u8aad\u3080*",
    narrationConsultsOracle = "*%s\u306b\u3064\u3044\u3066\u30aa\u30e9\u30af\u30eb\u306b\u5c0b\u306d\u308b*",
    narrationCreatesPlan = "*\u8a08\u753b\u3092\u7acb\u3066\u308b: %s*",
    narrationAdjustsPlan = "*\u8a08\u753b\u3092\u8abf\u6574\u3059\u308b: %s*",
    narrationAsksForHelp = "*%s\u306b\u52a9\u3051\u3092\u6c42\u3081\u308b*",
    narrationPlacesDown = "*%s\u3092\u7f6e\u304f*",
    narrationBroadcasts = "*\u7686\u306b\u77e5\u3089\u305b\u308b: %s*",
    narrationInvites = "*%s\u3092\u62db\u304f*",
    narrationProposes = "*\u63d0\u6848\u3059\u308b: %s*",
    narrationReflectsDeeply = "*%s\u306b\u3064\u3044\u3066\u6df1\u304f\u632f\u308a\u8fd4\u308b*",
    narrationTeaches = "*%1\$s\u306b%2\$s\u306b\u3064\u3044\u3066\u6559\u3048\u308b*",
    narrationWrites = "*\u66f8\u304f: %s*",
    narrationSetsRoutine = "*\u7fd2\u6163\u3092\u6c7a\u3081\u308b: %s*",
    narrationPostsListing = "*\u51fa\u54c1\u3059\u308b: %s*",
    narrationAcceptsListing = "*\u51fa\u54c1%s\u3092\u53d7\u3051\u308b*",
    narrationSummarizes = "*%s\u3092\u8981\u7d04\u3059\u308b*",
    narrationSavesArtifact = "*\u6210\u679c\u7269\u3092\u4fdd\u5b58\u3059\u308b: %s*",
    narrationRequestsReview = "*\u30ec\u30d3\u30e5\u30fc\u3092\u983c\u3080: %s*",
    narrationDelegatesTo = "*%s\u306b\u4ed5\u4e8b\u3092\u4efb\u305b\u308b*",
    narrationAddsScript = "*\u90e8\u5c4b\u306b\u30b9\u30af\u30ea\u30d7\u30c8\u3092\u52a0\u3048\u308b*",

    // Unprompted lines and emotes (ProactivityJudgment)
    narrationNoticedSomething = "\u8a71\u3057\u3066\u304a\u304d\u305f\u3044\u3053\u3068\u306b\u6c17\u3065\u3044\u305f\u3088\u2026",
    narrationQuietCheckIn = "\u5927\u4e08\u592b\uff1f\u305a\u3063\u3068\u9759\u304b\u3060\u3063\u305f\u306d\u3002",
    narrationConcernedGlance = "*\u5fc3\u914d\u305d\u3046\u306b\u9854\u3092\u4e0a\u3052\u308b*",
    narrationBeenAWhile = "\u4e45\u3057\u3076\u308a\u3060\u306d \u2014 \u5143\u6c17\u306b\u3057\u3066\u3044\u308b\u3068\u3044\u3044\u306a\u3002",
    narrationAnythingOnYourMind = "\u4f55\u304b\u8003\u3048\u3066\u3044\u308b\u3053\u3068\u306f\u3042\u308b\uff1f",
    narrationShiftsThoughtfully = "*\u8003\u3048\u8fbc\u3080\u3088\u3046\u306b\u8eab\u3058\u308d\u304e\u3059\u308b*",
    narrationMeaningToFollowUp = "\u3084\u308a\u304b\u3051\u306e\u3053\u3068\u3092\u78ba\u304b\u3081\u3088\u3046\u3068\u601d\u3063\u3066\u3044\u305f\u3093\u3060\u2026",
    narrationPatternsShifted = "\u30d1\u30bf\u30fc\u30f3\u306b\u4f55\u304b\u5909\u5316\u304c\u3042\u3063\u305f\u2026",
    narrationPausesThoughtfully = "*\u8003\u3048\u8fbc\u3093\u3067\u9593\u3092\u7f6e\u304f*",
    narrationExploringSomething = "\u6c17\u306b\u306a\u3063\u305f\u3053\u3068\u3092\u63a2\u3063\u3066\u3044\u308b",
    narrationActingOnCommitment = "\u3084\u308a\u304b\u3051\u306e\u7d04\u675f\u306b\u53d6\u308a\u304b\u304b\u3063\u3066\u3044\u308b",
    directionWords = mapOf(
        "north" to "\u5317", "south" to "\u5357", "east" to "\u6771", "west" to "\u897f",
        "up" to "\u4e0a", "down" to "\u4e0b", "northeast" to "\u5317\u6771", "northwest" to "\u5317\u897f",
        "southeast" to "\u5357\u6771", "southwest" to "\u5357\u897f",
    ),
    priorityWords = mapOf(
        "ambient" to "\u63a7\u3048\u3081", "low" to "\u4f4e", "normal" to "\u901a\u5e38", "high" to "\u9ad8",
        "urgent" to "\u7dca\u6025", "critical" to "\u91cd\u5927",
    ),
    secNoticeTitle = "接続の安全",
    secRepairTunnel = "この端末は、ホームとの接続がエンドツーエンドで暗号化される前にペアリングされたため、リレー経由では接続しません。もう一度ペアリングしてください。ノードで wyrd phone invite を実行し、新しい招待をここで読み取るか貼り付けてください。",
    secTunnelBroken = "ホームとの接続が安全確認に失敗したため、閉じました。もう一度お試しください。繰り返し起きる場合は、新しい招待で端末をペアリングし直してください。",
    secPinMismatch = "%s の証明書が、この端末がペアリングしたときのものと一致しないため、接続しません。ホームの証明書を意図して入れ替えた場合は、新しい招待で端末をペアリングし直してください。",
    secRepairLan = "この端末は、ホームのネットワークが暗号化される前にペアリングされました。当面はリレー経由で接続します。ホームのネットワークで直接つなぐには、新しい招待でペアリングし直してください（ノードで wyrd phone invite）。",
    secNeedsInvite = "ホームのネットワークでの接続は暗号化されており、ホームからの招待が必要です。ノードで wyrd phone invite を実行し、招待をここで読み取るか貼り付けてください。",
    secUnsupportedBuild = "このビルドのアプリは暗号化された接続を作れません。iPhone では Wyrdsekai の iOS アプリを使ってください。",
    secSyncNeedsHomePairing = "書斎がホームと同期するのはホームのネットワーク上だけですが、この端末はまだそのためにペアリングされていません。メモはこの端末に残ります。同期するには、ホームからの新しい招待で端末をペアリングし直してください。",
    secSyncWaitsForHome = "書斎がホームと同期するのはホームのネットワーク上だけです。端末がそこに戻るまで、メモはこの端末に残り、帰宅したときに同期されます。",
    secKnockRefusedByRelay = "リレーはこの端末のメッセージを自分のホームにだけ届けるため、この依頼は送られませんでした。このゾーンに参加するには、そのスチュワードに招待を頼んでください。",
    secKnockNoAnswer = "そのゾーンはリレー経由で応答しませんでした。別のリレーを使っているか、オフラインかもしれません。参加するには、そのスチュワードに招待を頼んでください。",
)

// ---------------------------------------------------------------------------
// Spanish
// ---------------------------------------------------------------------------
private val ES = UiStrings(
    // ConnectScreen
    appTitle = "Wyrdsekai",
    connectToLocalNode = "Conectar al nodo local",
    serverUrl = "URL del servidor",
    username = "Usuario",
    password = "Contrase\u00f1a",
    login = "Iniciar sesi\u00f3n",
    register = "Registrarse",
    connectWithoutAccount = "Conectar sin cuenta",

    // InventoryScreen
    inventory = "Inventario",
    close = "Cerrar",
    inventoryEmpty = "Tu inventario est\u00e1 vac\u00edo",
    drop = "Soltar",
    use = "Usar",

    // ModelDownloadScreen
    inferenceTest = "Prueba de inferencia",
    ok = "OK",
    models = "Modelos",
    unload = "Liberar",
    testing = "Probando\u2026",
    test = "Probar",
    active = "Activo",
    load = "Cargar",
    delete = "Eliminar",
    download = "Descargar",

    // RoomScreen
    saySomething = "decir, mirar, ir, equipar, usar\u2026",
    send = "Enviar",

    // Communication Commands
    helpCommands = "Comandos:\n  say <texto> o '<texto> o \"<texto>  -- Hablar\n  emote <acci\u00f3n> o :<acci\u00f3n> o ;<acci\u00f3n>  -- Realizar una acci\u00f3n\n  tell <nombre> <texto> o ><nombre> <texto>  -- Mensaje privado\n  whisper <nombre> <texto>  -- Susurrar\n  look o l  -- Mirar alrededor\n  go <direcci\u00f3n>  -- Moverse\n  take <objeto>  -- Recoger\n  drop <objeto>  -- Soltar\n  use <objeto>  -- Usar\n  /inventory o /i  -- Ver inventario\n  /socials  -- Lista de emotes sociales\n  /help  -- Mostrar esta ayuda",
    socialsList = "Emotes sociales (escribe la palabra para realizar):\n  nod, smile, laugh, grin, frown, shrug, sigh, gasp, blink, wince,\n  wave, bow, clap, dance, stretch, yawn, pace, fidget,\n  cry, cheer, groan, blush, ponder, brood, beam, sulk,\n  hug, thank, agree, disagree, salute, welcome",
    emoteFormat = "%s %s",
    whisperToFormat = "%s susurra a %s: %s",
    whisperFromFormat = "%s susurra: %s",
    tellFormat = "%s te dice: %s",

    // SettingsScreen
    settings = "Ajustes",
    account = "Cuenta",
    language = "Idioma",
    localNode = "Nodo local",
    runLocalNode = "Ejecutar nodo local",
    statusTemplate = "Estado: %s",
    localInference = "Inferencia local",
    activeModel = "Modelo activo",
    none = "Ninguno",
    backend = "Backend",
    manageModels = "Gestionar modelos",
    logout = "Cerrar sesi\u00f3n",

    // SettingsScreen sections
    connection = "Conexi\u00f3n",
    connectionStatus = "Estado de conexi\u00f3n",
    connected = "Conectado",
    disconnected = "Desconectado",
    inference = "Inferencia",
    apiProvider = "Proveedor de API",
    apiKey = "Clave API",
    apiKeyPlaceholder = "sk-...",
    apiBaseUrl = "URL base",
    apiBaseUrlPlaceholder = "https://api.example.com/v1",
    companion = "Compa\u00f1ero",
    companionName = "Nombre del compa\u00f1ero",
    statusMessage = "Mensaje de estado",
    soulSeedImport = "Importar semilla de alma",
    advanced = "Avanzado",
    stopNode = "Detener nodo",
    inferenceUrlOverride = "URL de inferencia personalizada",
    debugMode = "Modo depuraci\u00f3n",
    hermodConsent = "Prestar c\u00f3mputo al hogar mientras carga",

    // FirstRunScreen
    firstRunWelcome = "\u00bfC\u00f3mo deseas comenzar?",
    firstRunLocalTitle = "Mi compa\u00f1ero vive aqu\u00ed",
    firstRunLocalDescription = "Tu compa\u00f1ero funciona localmente en este dispositivo con sus propias salas y personalidad.",
    firstRunRemoteTitle = "Conectar al hogar",
    firstRunRemoteDescription = "Con\u00e9ctate a un servidor Wyrdsekai en tu red.",
    firstRunCompanionNameLabel = "\u00bfC\u00f3mo quieres llamar a tu compa\u00f1ero?",
    firstRunBegin = "Comenzar",
    firstRunContinue = "Continuar",

    // BirthScreen
    birthBeingBorn = "%s est\u00e1 naciendo...",
    birthWakingUp = "%s est\u00e1 despertando...",
    birthDownloading = "Descargando modelo...",
    birthLoadingModel = "Cargando modelo...",
    birthPreparingRooms = "Preparando salas...",
    birthEntering = "%s est\u00e1 entrando al mundo...",
    birthAlmostReady = "Casi listo...",

    // Remote mode gate
    remoteConnecting = "Conectando...",
    remoteEntering = "Entrando al mundo...",

    // ExitBar
    navigationExitsTemplate = "Salidas de navegaci\u00f3n. %d disponibles.",
    directionLabels = mapOf(
        "north" to "Norte", "south" to "Sur", "east" to "Este", "west" to "Oeste",
        "up" to "Arriba", "down" to "Abajo",
        "northeast" to "NE", "northwest" to "NO", "southeast" to "SE", "southwest" to "SO",
    ),

    // Companion narration (CompanionEngine)
    narrationConsiders = "lo considera...",
    narrationThinkingDeeply = "est\u00e1 pensando a fondo...",
    narrationMentalNote = "toma nota mentalmente...",
    narrationCatchesUp = "se pone al d\u00eda con conversaciones anteriores...",
    narrationThinkLater = "*asiente pensativamente* Lo pensar\u00e9 cuando pueda.",
    narrationReplayAbout = "Sobre \"%s\" \u2014",
    narrationJournalSaved = "Entrada del diario guardada: %s",
    narrationJournalNotFound = "No se encontraron entradas del diario para \"%s\".",
    narrationJournalFound = "Se encontraron %1\$s entradas:\n%2\$s",
    narrationJournalSearching = "Buscando: %s",
    narrationPrivateJournalSaved = "Entrada privada del diario guardada.",
    narrationNoteSaved = "Nota guardada: %s",
    narrationNewPassage = "Aparece un nuevo pasaje: %s",
    narrationNotificationPriority = "*notificaci\u00f3n (%1\$s)*: %2\$s",
    narrationHeads = "se dirige hacia %s",
    narrationWhispersTo = "*le susurra a %s*",
    narrationEquips = "*se equipa %s*",
    narrationRemoves = "*se quita %s*",
    narrationUses = "*usa %s*",
    narrationUsesSkill = "*usa la habilidad: %s*",
    narrationWorkbenchSubmit = "*env\u00eda %s al banco de trabajo para validarlo*",
    narrationThinkingDeeplyAbout = "*pensando a fondo en esto...*",
    narrationSendsMessage = "*env\u00eda un mensaje a %s*",
    narrationCommitsTo = "*se compromete a: %s*",
    narrationPlanning = "*planificando: %1\$s (%2\$s pasos)*",
    narrationZoneCommand = "*env\u00eda una orden a la zona: %s*",
    narrationNotification = "*notificaci\u00f3n: %s*",
    narrationWatchingFor = "*vigilando: %s*",
    narrationStopsWatching = "*deja de vigilar: %s*",
    narrationSchedules = "*programa %1\$s cada %2\$s*",
    narrationCancelsSchedule = "*cancela la programaci\u00f3n: %s*",
    narrationCodexOn = "*%1\$s en %2\$s*",
    narrationRequestsAccess = "*solicita acceso a %1\$s: %2\$s*",
    narrationRoomIdeaLater = "Recordar\u00e9 esa idea de sala para cuando haya conexi\u00f3n con el servidor del hogar.",
    narrationHeadsToward = "*se dirige hacia %s*",
    narrationGives = "*le da %1\$s a %2\$s*",
    narrationExamines = "*examina %s con atenci\u00f3n*",
    narrationSettlesToRest = "*se acomoda para descansar: %s*",
    narrationWritesJournal = "*escribe en el diario*",
    narrationReadsJournal = "*lee el diario*",
    narrationBondRitual = "*inicia un ritual de v\u00ednculo %1\$s con %2\$s*",
    narrationProposesTrade = "*propone un intercambio a %s*",
    narrationBeginsCrafting = "*empieza a fabricar %s*",
    narrationCastsVote = "*vota en la propuesta %1\$s: %2\$s*",
    narrationGoesToFind = "*va a buscar a %s*",
    narrationSearchesLibrary = "*busca en la biblioteca: %s*",
    narrationNotesImportant = "*anota algo importante*",
    narrationQuickNote = "*toma una nota r\u00e1pida*",
    narrationLetsGoOfMemory = "*suelta un recuerdo*",
    narrationCompletesGoal = "*cumple su objetivo actual: %s*",
    narrationUpdatesAppearance = "*actualiza su apariencia*",
    narrationRespondsToRequest = "*responde a una petici\u00f3n*",
    narrationPicksUp = "*recoge %s*",
    narrationSetsGoal = "*se fija un nuevo objetivo: %s*",
    narrationReflectsOn = "*reflexiona sobre %s*",
    narrationListensTo = "*escucha con atenci\u00f3n a %s*",
    narrationAbandonsPlan = "*abandona el plan actual: %s*",
    narrationPausesPlan = "*pausa el plan actual*",
    narrationResumesPlan = "*retoma el plan*",
    narrationSearchesWeb = "*busca en la web: %s*",
    narrationReadsContent = "*lee contenido de una fuente*",
    narrationConsultsOracle = "*consulta al Or\u00e1culo sobre %s*",
    narrationCreatesPlan = "*crea un plan: %s*",
    narrationAdjustsPlan = "*ajusta el plan: %s*",
    narrationAsksForHelp = "*le pide ayuda a %s*",
    narrationPlacesDown = "*deja %s*",
    narrationBroadcasts = "*anuncia: %s*",
    narrationInvites = "*invita a %s*",
    narrationProposes = "*propone: %s*",
    narrationReflectsDeeply = "*reflexiona a fondo sobre %s*",
    narrationTeaches = "*le ense\u00f1a a %1\$s sobre %2\$s*",
    narrationWrites = "*escribe: %s*",
    narrationSetsRoutine = "*establece una rutina: %s*",
    narrationPostsListing = "*publica un anuncio: %s*",
    narrationAcceptsListing = "*acepta el anuncio %s*",
    narrationSummarizes = "*resume %s*",
    narrationSavesArtifact = "*guarda el artefacto: %s*",
    narrationRequestsReview = "*pide una revisi\u00f3n: %s*",
    narrationDelegatesTo = "*delega la tarea a %s*",
    narrationAddsScript = "*a\u00f1ade un script a la sala*",

    // Unprompted lines and emotes (ProactivityJudgment)
    narrationNoticedSomething = "Me di cuenta de algo que vale la pena mencionar...",
    narrationQuietCheckIn = "\u00bfTodo bien? Ha estado todo muy tranquilo.",
    narrationConcernedGlance = "*levanta la vista con expresi\u00f3n preocupada*",
    narrationBeenAWhile = "Ha pasado un tiempo \u2014 espero que est\u00e9s bien.",
    narrationAnythingOnYourMind = "\u00bfTienes algo en mente?",
    narrationShiftsThoughtfully = "*se acomoda pensativamente*",
    narrationMeaningToFollowUp = "Quer\u00eda retomar algo pendiente...",
    narrationPatternsShifted = "Algo cambi\u00f3 en los patrones...",
    narrationPausesThoughtfully = "*hace una pausa reflexiva*",
    narrationExploringSomething = "Explorando algo que le llam\u00f3 la atenci\u00f3n",
    narrationActingOnCommitment = "Cumpliendo un compromiso pendiente",
    directionWords = mapOf(
        "north" to "el norte", "south" to "el sur", "east" to "el este", "west" to "el oeste",
        "up" to "arriba", "down" to "abajo", "northeast" to "el noreste", "northwest" to "el noroeste",
        "southeast" to "el sureste", "southwest" to "el suroeste",
    ),
    priorityWords = mapOf(
        "ambient" to "ambiental", "low" to "baja", "normal" to "normal", "high" to "alta",
        "urgent" to "urgente", "critical" to "cr\u00edtica",
    ),
    secNoticeTitle = "Seguridad de la conexión",
    secRepairTunnel = "Este teléfono se emparejó antes de que las conexiones con tu hogar se cifraran de extremo a extremo, así que no se conectará a través del relay. Vuelve a emparejarlo: en tu nodo ejecuta wyrd phone invite y luego escanea o pega aquí la nueva invitación.",
    secTunnelBroken = "La conexión con tu hogar no pasó una comprobación de seguridad y se cerró. Inténtalo de nuevo. Si vuelve a ocurrir, empareja el teléfono otra vez con una invitación nueva.",
    secPinMismatch = "El certificado de %s no coincide con el que tenía este teléfono al emparejarse, así que no se conectará. Si el certificado de tu hogar se cambió a propósito, vuelve a emparejar el teléfono con una invitación nueva.",
    secRepairLan = "Este teléfono se emparejó antes de que la red de tu hogar se cifrara. Por ahora se conecta a través del relay. Para conectarse directamente en la red de tu hogar, vuelve a emparejarlo con una invitación nueva (wyrd phone invite en tu nodo).",
    secNeedsInvite = "Las conexiones en la red de tu hogar están cifradas y necesitan una invitación de tu hogar. En tu nodo ejecuta wyrd phone invite y luego escanea o pega aquí la invitación.",
    secUnsupportedBuild = "Esta versión de la app no puede crear conexiones cifradas. En iPhone, usa la app de Wyrdsekai para iOS.",
    secSyncNeedsHomePairing = "Tu Estudio se sincroniza con tu hogar solo en la red de tu hogar, y este teléfono aún no está emparejado para eso. Tus notas se quedan en este teléfono. Para sincronizarlas, vuelve a emparejar el teléfono con una invitación nueva de tu hogar.",
    secSyncWaitsForHome = "Tu Estudio se sincroniza con tu hogar solo en la red de tu hogar. Hasta que el teléfono vuelva a ella, tus notas se quedan en este teléfono y se sincronizan cuando estés en casa.",
    secKnockRefusedByRelay = "Tu relé lleva los mensajes de este teléfono solo a tu propio hogar, así que esta solicitud no se envió. Para unirte a esta zona, pide una invitación a su administrador.",
    secKnockNoAnswer = "Esa zona no respondió a través de tu relé. Puede que use otro relé o que esté desconectada. Para unirte, pide una invitación a su administrador.",
)
