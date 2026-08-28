package org.wyrdsekai.app.i18n

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
)

/** Returns the [UiStrings] for the given BCP-47 locale code. Falls back to English. */
fun uiStringsFor(locale: String): UiStrings = when (locale) {
    "ja" -> JA
    "es" -> ES
    else -> EN
}

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
)
