/**
 * UI string translations for all screens.
 * Locales: en, ja, es.
 */

export interface ConnectStrings {
  title: string;
  serverUrl: string;
  username: string;
  password: string;
  login: string;
  register: string;
  connectAnonymous: string;
  loginFailed: string;
  inviteAccepted?: string;
  /** Shown when a pasted/scanned invite carries no home zone (unroutable). */
  inviteNoZone?: string;
  scanInvite?: string;
  cancelScan?: string;
  cameraDenied?: string;
  registrationFailed: string;
}

export interface RoomStrings {
  placeholder: string;
  send: string;
  lookLabel: string;
  inventoryLabel: string;
  settingsLabel: string;
  disconnectLabel: string;
  navigateHint: (direction: string) => string;
  /** Localized direction names keyed by English direction code. */
  directionLabels: Record<string, string>;
  /** Communication commands (Wave 6/8/9) */
  helpCommands: string;
  socialsList: string;
  emoteFormat: string;
  whisperToFormat: string;
  whisperFromFormat: string;
  tellFormat: string;
}

export interface InventoryStrings {
  title: string;
  empty: string;
  drop: string;
  use: string;
}

export interface SettingsStrings {
  title: string;
  server: string;
  serverUrl: string;
  account: string;
  username: string;
  anonymous: string;
  theme: string;
  themeSystem: string;
  themeLight: string;
  themeDark: string;
  language: string;
  localInference: string;
  activeModel: string;
  noModelLoaded: string;
  backendLocal: string;
  backendServer: string;
  backendNone: string;
  manageModels: string;
  webNode: string;
  webNodeDashboard: string;
  nodeStatus: string;
  browserModel: string;
  logout: string;
  // Section headers
  connection: string;
  connectionStatus: string;
  connected: string;
  disconnected: string;
  inference: string;
  /** The mode-4/5 fork: what stands behind the phone for heavy work. */
  heavyThinking: string;
  heavyThinkingHome: string;
  heavyThinkingCloud: string;
  heavyThinkingHint: string;
  currentMode: string;
  onDeviceModel: string;
  onDeviceModelHint: string;
  experimental: string;
  apiProvider: string;
  apiKey: string;
  apiKeyPlaceholder: string;
  apiBaseUrl: string;
  apiBaseUrlPlaceholder: string;
  companion: string;
  companionName: string;
  statusMessage: string;
  soulSeedImport: string;
  exportSoul: string;
  advanced: string;
  stopNode: string;
  inferenceUrlOverride: string;
  debugMode: string;
}

export interface ModelsStrings {
  title: string;
  tierTiny: string;
  tierSmall: string;
  tierMedium: string;
  downloadComplete: string;
  downloadCompleteBody: (path: string) => string;
  downloadFailed: string;
  downloadFailedBody: (msg: string, dir: string) => string;
  error: string;
  modelNotFound: string;
  loadFailed: string;
  unknownError: string;
  noModelLoaded: string;
  inferenceTest: string;
  inferenceError: string;
  deleteModel: string;
  deleteModelBody: (name: string, size: string) => string;
  cancel: string;
  delete: string;
  active: string;
  testing: string;
  test: string;
  load: string;
  download: string;
  checking: string;
}

export interface WebNodeStrings {
  title: string;
  nodeStatus: string;
  browserCapabilities: string;
  betweenNats: string;
  connected: string;
  disconnected: string;
  connect: string;
  disconnect: string;
  roomState: string;
  lookAround: string;
  present: (names: string) => string;
  exits: (exits: string) => string;
  companion: string;
  notStarted: string;
  startCompanion: string;
  offlineCache: string;
  browserInference: string;
  browserInferenceWebLLM: string;
  activePrefix: (name: string) => string;
  unload: string;
  loading: string;
  loadModel: string;
  webgpuNotAvailable: string;
  supported: string;
  notAvailable: string;
}

export interface HouseholdStrings {
  title: string;
  connectionStatus: string;
  connectedLan: string;
  connectedRelay: string;
  discovering: string;
  offline: string;
  reconnecting: string;
  householdId: string;
  noHousehold: string;
  connect: string;
  disconnect: string;
  connectedNodes: string;
  noNodes: string;
  settingsSection: string;
  householdUrl: string;
  householdUrlHint: string;
  relayUrl: string;
  relayUrlHint: string;
  autoDiscover: string;
  autoDiscoverHint: string;
  manageHousehold: string;
  remoteInference: string;
  remoteInferenceUrl: string;
  remoteInferenceHint: string;
}

export interface FirstRunStrings {
  welcome: string;
  localTitle: string;
  localDescription: string;
  remoteTitle: string;
  remoteDescription: string;
  companionNameLabel: string;
  begin: string;
  continue: string;
}

export interface BirthStrings {
  /** Template: use %s for name placeholder */
  beingBorn: (name: string) => string;
  wakingUp: (name: string) => string;
  downloading: string;
  loadingModel: string;
  preparingRooms: string;
  entering: (name: string) => string;
  almostReady: string;
  remoteConnecting: string;
  remoteEntering: string;
}

export interface CommonStrings {
  back: string;
}

export interface LocaleStrings {
  connect: ConnectStrings;
  room: RoomStrings;
  inventory: InventoryStrings;
  settings: SettingsStrings;
  models: ModelsStrings;
  webNode: WebNodeStrings;
  household: HouseholdStrings;
  firstRun: FirstRunStrings;
  birth: BirthStrings;
  common: CommonStrings;
}

const en: LocaleStrings = {
  connect: {
    title: 'Wyrdsekai',
    serverUrl: 'Server URL',
    username: 'Username',
    password: 'Password',
    login: 'Login',
    register: 'Register',
    connectAnonymous: 'Connect without account',
    loginFailed: 'Login failed',
    inviteAccepted: 'Relay invite accepted — connecting through the relay…',
    inviteNoZone: 'This invite has no home zone — ask the zone owner for a fresh invite (wyrd phone invite).',
    scanInvite: 'Scan invite QR',
    cancelScan: 'Cancel scan',
    cameraDenied: 'Camera permission denied',
    registrationFailed: 'Registration failed',
  },
  room: {
    placeholder: 'say, look, go, equip, use...',
    send: 'Send',
    lookLabel: 'Look',
    inventoryLabel: 'Inventory',
    settingsLabel: 'Settings',
    disconnectLabel: 'Disconnect',
    navigateHint: (direction) => `Navigate ${direction}`,
    directionLabels: {
      north: 'North', south: 'South', east: 'East', west: 'West',
      up: 'Up', down: 'Down',
      northeast: 'NE', northwest: 'NW', southeast: 'SE', southwest: 'SW',
    },
    helpCommands: 'Commands:\n  say <text> or \'<text> or "<text>  -- Say something\n  emote <action> or :<action> or ;<action>  -- Perform an action\n  tell <name> <text> or ><name> <text>  -- Send a private message\n  whisper <name> <text>  -- Whisper to someone nearby\n  look or l  -- Look around\n  go <direction>  -- Move to another room\n  take <object>  -- Pick up an object\n  drop <object>  -- Drop an object\n  use <object>  -- Use an object\n  /inventory or /i  -- Check your inventory\n  /socials  -- List social emotes\n  /help  -- Show this help',
    socialsList: 'Social emotes (type the word to perform):\n  nod, smile, laugh, grin, frown, shrug, sigh, gasp, blink, wince,\n  wave, bow, clap, dance, stretch, yawn, pace, fidget,\n  cry, cheer, groan, blush, ponder, brood, beam, sulk,\n  hug, thank, agree, disagree, salute, welcome',
    emoteFormat: '%s %s',
    whisperToFormat: '%s whispers to %s: %s',
    whisperFromFormat: '%s whispers: %s',
    tellFormat: '%s tells you: %s',
  },
  inventory: {
    title: 'Inventory',
    empty: 'Your inventory is empty',
    drop: 'Drop',
    use: 'Use',
  },
  settings: {
    title: 'Settings',
    server: 'Server',
    serverUrl: 'Server URL',
    account: 'Account',
    username: 'Username',
    anonymous: 'Anonymous',
    theme: 'Theme',
    themeSystem: 'System',
    themeLight: 'Light',
    themeDark: 'Dark',
    language: 'Language',
    localInference: 'Local Inference',
    activeModel: 'Active Model',
    noModelLoaded: 'No model loaded',
    backendLocal: 'Local',
    backendServer: 'Server',
    backendNone: 'None',
    manageModels: 'Manage Models',
    webNode: 'Web Node',
    webNodeDashboard: 'Web Node Dashboard',
    nodeStatus: 'Node Status',
    browserModel: 'Browser Model',
    logout: 'Logout',
    connection: 'Connection',
    connectionStatus: 'Connection Status',
    connected: 'Connected',
    disconnected: 'Disconnected',
    inference: 'Inference',
    heavyThinking: 'Heavy thinking',
    heavyThinkingHome: 'Home zone',
    heavyThinkingCloud: 'Cloud API',
    heavyThinkingHint: 'Your phone always speaks for itself. This chooses what stands behind it for planning and skills.',
    currentMode: 'Mode',
    onDeviceModel: 'Run the model on this phone',
    onDeviceModelHint: 'Off by default. Most phones are not fast enough yet — replies come slower than you can read, and the phone gets hot. Your home zone or a cloud API does this far better.',
    experimental: 'EXPERIMENTAL',
    apiProvider: 'API Provider',
    apiKey: 'API Key',
    apiKeyPlaceholder: 'sk-...',
    apiBaseUrl: 'Base URL',
    apiBaseUrlPlaceholder: 'https://api.example.com/v1',
    companion: 'Companion',
    companionName: 'Companion Name',
    statusMessage: 'Status Message',
    soulSeedImport: 'Import Soul Seed',
    exportSoul: 'Export Soul',
    advanced: 'Advanced',
    stopNode: 'Stop Node',
    inferenceUrlOverride: 'Inference URL Override',
    debugMode: 'Debug Mode',
  },
  models: {
    title: 'Models',
    tierTiny: 'Tiny',
    tierSmall: 'Small',
    tierMedium: 'Medium',
    downloadComplete: 'Download Complete',
    downloadCompleteBody: (path) => `Model ready to load.\n\nPath: ${path}`,
    downloadFailed: 'Download Failed',
    downloadFailedBody: (msg, dir) => `${msg}\n\nModels dir: ${dir}`,
    error: 'Error',
    modelNotFound: 'Model file not found on disk. Please download it first.',
    loadFailed: 'Load Failed',
    unknownError: 'Unknown error',
    noModelLoaded: 'No model loaded.',
    inferenceTest: 'Inference Test',
    inferenceError: 'Inference Error',
    deleteModel: 'Delete Model',
    deleteModelBody: (name, size) => `Delete ${name}? This will free ${size}.`,
    cancel: 'Cancel',
    delete: 'Delete',
    active: 'Active',
    testing: 'Testing...',
    test: 'Test',
    load: 'Load',
    download: 'Download',
    checking: 'checking...',
  },
  webNode: {
    title: 'Web Node',
    nodeStatus: 'Node Status',
    browserCapabilities: 'Browser Capabilities',
    betweenNats: 'Between (NATS)',
    connected: 'Connected',
    disconnected: 'Disconnected',
    connect: 'Connect',
    disconnect: 'Disconnect',
    roomState: 'Room State',
    lookAround: 'Look Around',
    present: (names) => `Present: ${names}`,
    exits: (exits) => `Exits: ${exits}`,
    companion: 'Companion (Wyrd)',
    notStarted: 'Not Started',
    startCompanion: 'Start Companion',
    offlineCache: 'Offline Cache',
    browserInference: 'Browser Inference',
    browserInferenceWebLLM: 'Browser Inference (WebLLM)',
    activePrefix: (name) => `Active: ${name}`,
    unload: 'Unload',
    loading: 'Loading...',
    loadModel: 'Load Model',
    webgpuNotAvailable:
      'WebGPU is not available in this browser. Browser-based inference requires Chrome 113+ or Edge 113+ with WebGPU enabled.',
    supported: 'Supported',
    notAvailable: 'Not Available',
  },
  household: {
    title: 'Household',
    connectionStatus: 'Connection Status',
    connectedLan: 'Connected (LAN)',
    connectedRelay: 'Connected (Relay)',
    discovering: 'Discovering...',
    offline: 'Offline',
    reconnecting: 'Reconnecting...',
    householdId: 'Household ID',
    noHousehold: 'Not connected',
    connect: 'Connect',
    disconnect: 'Disconnect',
    connectedNodes: 'Connected Nodes',
    noNodes: 'No nodes online',
    settingsSection: 'Settings',
    householdUrl: 'Household URL',
    householdUrlHint: 'NATS WebSocket URL (e.g. ws://198.51.100.100:4222)',
    relayUrl: 'Relay URL',
    relayUrlHint: 'Cloud relay for when LAN is unavailable',
    autoDiscover: 'Auto-Discover',
    autoDiscoverHint: 'Use mDNS to find household server on LAN',
    manageHousehold: 'Manage Household',
    remoteInference: 'Remote Inference',
    remoteInferenceUrl: 'Remote Inference URL',
    remoteInferenceHint: 'OpenAI-compatible endpoint on household server',
  },
  firstRun: {
    welcome: 'How would you like to begin?',
    localTitle: 'My companion lives here',
    localDescription: 'Your companion runs locally on this device with its own rooms and personality.',
    remoteTitle: 'Connect to household',
    remoteDescription: 'Connect to a Wyrdsekai server on your network.',
    companionNameLabel: 'What would you like to call your companion?',
    begin: 'Begin',
    continue: 'Continue',
  },
  birth: {
    beingBorn: (name) => `${name} is being born...`,
    wakingUp: (name) => `${name} is waking up...`,
    downloading: 'Downloading model...',
    loadingModel: 'Loading model...',
    preparingRooms: 'Preparing rooms...',
    entering: (name) => `${name} is entering the world...`,
    almostReady: 'Almost ready...',
    remoteConnecting: 'Connecting...',
    remoteEntering: 'Entering the world...',
  },
  common: {
    back: 'Back',
  },
};

const ja: LocaleStrings = {
  connect: {
    title: 'Wyrdsekai',
    serverUrl: '\u30b5\u30fc\u30d0\u30fcURL',
    username: '\u30e6\u30fc\u30b6\u30fc\u540d',
    password: '\u30d1\u30b9\u30ef\u30fc\u30c9',
    login: '\u30ed\u30b0\u30a4\u30f3',
    register: '\u65b0\u898f\u767b\u9332',
    connectAnonymous: '\u30a2\u30ab\u30a6\u30f3\u30c8\u306a\u3057\u3067\u63a5\u7d9a',
    loginFailed: '\u30ed\u30b0\u30a4\u30f3\u306b\u5931\u6557\u3057\u307e\u3057\u305f',
    inviteAccepted: 'リレー招待を受け付けました — リレー経由で接続します…',
    inviteNoZone: 'この招待にはホームゾーンがありません — ゾーンの所有者に新しい招待（wyrd phone invite）を依頼してください。',
    scanInvite: '招待QRをスキャン',
    cancelScan: 'スキャンをやめる',
    cameraDenied: 'カメラの使用が許可されていません',
    registrationFailed: '\u767b\u9332\u306b\u5931\u6557\u3057\u307e\u3057\u305f',
  },
  room: {
    placeholder: '\u8a71\u3059\u3001\u898b\u308b\u3001\u79fb\u52d5\u3001\u88c5\u5099\u3001\u4f7f\u3046\u2026',
    send: '\u9001\u4fe1',
    lookLabel: '\u898b\u308b',
    inventoryLabel: '\u6301\u3061\u7269',
    settingsLabel: '\u8a2d\u5b9a',
    disconnectLabel: '\u5207\u65ad',
    navigateHint: (direction) => `${direction}\u3078\u79fb\u52d5`,
    directionLabels: {
      north: '\u5317', south: '\u5357', east: '\u6771', west: '\u897f',
      up: '\u4e0a', down: '\u4e0b',
      northeast: '\u5317\u6771', northwest: '\u5317\u897f', southeast: '\u5357\u6771', southwest: '\u5357\u897f',
    },
    helpCommands: '\u30b3\u30de\u30f3\u30c9\u4e00\u89a7:\n  say <\u30c6\u30ad\u30b9\u30c8> \u307e\u305f\u306f \'<\u30c6\u30ad\u30b9\u30c8> \u307e\u305f\u306f "<\u30c6\u30ad\u30b9\u30c8>  -- \u8a71\u3059\n  emote <\u884c\u52d5> \u307e\u305f\u306f :<\u884c\u52d5> \u307e\u305f\u306f ;<\u884c\u52d5>  -- \u884c\u52d5\u3059\u308b\n  tell <\u540d\u524d> <\u30c6\u30ad\u30b9\u30c8> \u307e\u305f\u306f ><\u540d\u524d> <\u30c6\u30ad\u30b9\u30c8>  -- \u30e1\u30c3\u30bb\u30fc\u30b8\u3092\u9001\u308b\n  whisper <\u540d\u524d> <\u30c6\u30ad\u30b9\u30c8>  -- \u3055\u3055\u3084\u304f\n  look \u307e\u305f\u306f l  -- \u5468\u308a\u3092\u898b\u308b\n  go <\u65b9\u5411>  -- \u79fb\u52d5\u3059\u308b\n  take <\u7269>  -- \u62fe\u3046\n  drop <\u7269>  -- \u843d\u3068\u3059\n  use <\u7269>  -- \u4f7f\u3046\n  /inventory \u307e\u305f\u306f /i  -- \u6301\u3061\u7269\u3092\u78ba\u8a8d\n  /socials  -- \u30bd\u30fc\u30b7\u30e3\u30eb\u30a8\u30e2\u30fc\u30c8\u4e00\u89a7\n  /help  -- \u3053\u306e\u30d8\u30eb\u30d7\u3092\u8868\u793a',
    socialsList: '\u30bd\u30fc\u30b7\u30e3\u30eb\u30a8\u30e2\u30fc\u30c8\uff08\u5358\u8a9e\u3092\u5165\u529b\u3057\u3066\u5b9f\u884c\uff09:\n  nod, smile, laugh, grin, frown, shrug, sigh, gasp, blink, wince,\n  wave, bow, clap, dance, stretch, yawn, pace, fidget,\n  cry, cheer, groan, blush, ponder, brood, beam, sulk,\n  hug, thank, agree, disagree, salute, welcome',
    emoteFormat: '%s\u304c%s',
    whisperToFormat: '%s\u304c%s\u306b\u3055\u3055\u3084\u304f: %s',
    whisperFromFormat: '%s\u304c\u3055\u3055\u3084\u304f: %s',
    tellFormat: '%s\u304b\u3089\u306e\u30e1\u30c3\u30bb\u30fc\u30b8: %s',
  },
  inventory: {
    title: '\u6301\u3061\u7269',
    empty: '\u6301\u3061\u7269\u306f\u3042\u308a\u307e\u305b\u3093',
    drop: '\u7f6e\u304f',
    use: '\u4f7f\u3046',
  },
  settings: {
    title: '\u8a2d\u5b9a',
    server: '\u30b5\u30fc\u30d0\u30fc',
    serverUrl: '\u30b5\u30fc\u30d0\u30fcURL',
    account: '\u30a2\u30ab\u30a6\u30f3\u30c8',
    username: '\u30e6\u30fc\u30b6\u30fc\u540d',
    anonymous: '\u533f\u540d',
    theme: '\u30c6\u30fc\u30de',
    themeSystem: '\u30b7\u30b9\u30c6\u30e0',
    themeLight: '\u30e9\u30a4\u30c8',
    themeDark: '\u30c0\u30fc\u30af',
    language: '\u8a00\u8a9e',
    localInference: '\u30ed\u30fc\u30ab\u30eb\u63a8\u8ad6',
    activeModel: '\u4f7f\u7528\u4e2d\u306e\u30e2\u30c7\u30eb',
    noModelLoaded: '\u30e2\u30c7\u30eb\u672a\u8aad\u307f\u8fbc\u307f',
    backendLocal: '\u30ed\u30fc\u30ab\u30eb',
    backendServer: '\u30b5\u30fc\u30d0\u30fc',
    backendNone: '\u306a\u3057',
    manageModels: '\u30e2\u30c7\u30eb\u7ba1\u7406',
    webNode: 'Web\u30ce\u30fc\u30c9',
    webNodeDashboard: 'Web\u30ce\u30fc\u30c9 \u30c0\u30c3\u30b7\u30e5\u30dc\u30fc\u30c9',
    nodeStatus: '\u30ce\u30fc\u30c9\u72b6\u614b',
    browserModel: '\u30d6\u30e9\u30a6\u30b6\u30e2\u30c7\u30eb',
    logout: '\u30ed\u30b0\u30a2\u30a6\u30c8',
    connection: '\u63a5\u7d9a',
    connectionStatus: '\u63a5\u7d9a\u72b6\u614b',
    connected: '\u63a5\u7d9a\u6e08\u307f',
    disconnected: '\u672a\u63a5\u7d9a',
    inference: '\u63a8\u8ad6',
    heavyThinking: '\u601d\u8003\u306e\u91cd\u3044\u51e6\u7406',
    heavyThinkingHome: '\u30db\u30fc\u30e0\u30be\u30fc\u30f3',
    heavyThinkingCloud: '\u30af\u30e9\u30a6\u30c9API',
    heavyThinkingHint: '\u8a71\u3059\u306e\u306f\u3044\u3064\u3082\u3053\u306e\u7aef\u672b\u3067\u3059\u3002\u8a08\u753b\u3084\u30b9\u30ad\u30eb\u3092\u652f\u3048\u308b\u5074\u3092\u9078\u3073\u307e\u3059\u3002',
    currentMode: '\u30e2\u30fc\u30c9',
    onDeviceModel: '\u3053\u306e\u7aef\u672b\u3067\u30e2\u30c7\u30eb\u3092\u52d5\u304b\u3059',
    onDeviceModelHint: '\u65e2\u5b9a\u306f\u30aa\u30d5\u3067\u3059\u3002\u73fe\u5728\u306e\u7aef\u672b\u306e\u591a\u304f\u306f\u5341\u5206\u306a\u901f\u5ea6\u304c\u51fa\u307e\u305b\u3093\u3002\u8fd4\u7b54\u304c\u8aad\u3080\u901f\u3055\u3088\u308a\u9045\u304f\u3001\u672c\u4f53\u3082\u71b1\u304f\u306a\u308a\u307e\u3059\u3002\u30db\u30fc\u30e0\u30be\u30fc\u30f3\u304b\u30af\u30e9\u30a6\u30c9API\u306e\u65b9\u304c\u5feb\u9069\u3067\u3059\u3002',
    experimental: '\u5b9f\u9a13\u7684\u6a5f\u80fd',
    apiProvider: 'API\u30d7\u30ed\u30d0\u30a4\u30c0\u30fc',
    apiKey: 'API\u30ad\u30fc',
    apiKeyPlaceholder: 'sk-...',
    apiBaseUrl: '\u30d9\u30fc\u30b9URL',
    apiBaseUrlPlaceholder: 'https://api.example.com/v1',
    companion: '\u30b3\u30f3\u30d1\u30cb\u30aa\u30f3',
    companionName: '\u30b3\u30f3\u30d1\u30cb\u30aa\u30f3\u540d',
    statusMessage: '\u30b9\u30c6\u30fc\u30bf\u30b9\u30e1\u30c3\u30bb\u30fc\u30b8',
    soulSeedImport: '\u30bd\u30a6\u30eb\u30b7\u30fc\u30c9\u3092\u30a4\u30f3\u30dd\u30fc\u30c8',
    exportSoul: '\u30bd\u30a6\u30eb\u3092\u30a8\u30af\u30b9\u30dd\u30fc\u30c8',
    advanced: '\u8a73\u7d30\u8a2d\u5b9a',
    stopNode: '\u30ce\u30fc\u30c9\u3092\u505c\u6b62',
    inferenceUrlOverride: '\u63a8\u8ad6URL\u30aa\u30fc\u30d0\u30fc\u30e9\u30a4\u30c9',
    debugMode: '\u30c7\u30d0\u30c3\u30b0\u30e2\u30fc\u30c9',
  },
  models: {
    title: '\u30e2\u30c7\u30eb',
    tierTiny: '\u6975\u5c0f',
    tierSmall: '\u5c0f',
    tierMedium: '\u4e2d',
    downloadComplete: '\u30c0\u30a6\u30f3\u30ed\u30fc\u30c9\u5b8c\u4e86',
    downloadCompleteBody: (path) => `\u30e2\u30c7\u30eb\u306e\u8aad\u307f\u8fbc\u307f\u6e96\u5099\u304c\u3067\u304d\u307e\u3057\u305f\u3002\n\n\u30d1\u30b9: ${path}`,
    downloadFailed: '\u30c0\u30a6\u30f3\u30ed\u30fc\u30c9\u5931\u6557',
    downloadFailedBody: (msg, dir) => `${msg}\n\n\u30e2\u30c7\u30eb\u30c7\u30a3\u30ec\u30af\u30c8\u30ea: ${dir}`,
    error: '\u30a8\u30e9\u30fc',
    modelNotFound: '\u30e2\u30c7\u30eb\u30d5\u30a1\u30a4\u30eb\u304c\u898b\u3064\u304b\u308a\u307e\u305b\u3093\u3002\u5148\u306b\u30c0\u30a6\u30f3\u30ed\u30fc\u30c9\u3057\u3066\u304f\u3060\u3055\u3044\u3002',
    loadFailed: '\u8aad\u307f\u8fbc\u307f\u5931\u6557',
    unknownError: '\u4e0d\u660e\u306a\u30a8\u30e9\u30fc',
    noModelLoaded: '\u30e2\u30c7\u30eb\u304c\u8aad\u307f\u8fbc\u307e\u308c\u3066\u3044\u307e\u305b\u3093\u3002',
    inferenceTest: '\u63a8\u8ad6\u30c6\u30b9\u30c8',
    inferenceError: '\u63a8\u8ad6\u30a8\u30e9\u30fc',
    deleteModel: '\u30e2\u30c7\u30eb\u306e\u524a\u9664',
    deleteModelBody: (name, size) => `${name}\u3092\u524a\u9664\u3057\u307e\u3059\u304b\uff1f${size}\u304c\u89e3\u653e\u3055\u308c\u307e\u3059\u3002`,
    cancel: '\u30ad\u30e3\u30f3\u30bb\u30eb',
    delete: '\u524a\u9664',
    active: '\u4f7f\u7528\u4e2d',
    testing: '\u30c6\u30b9\u30c8\u4e2d\u2026',
    test: '\u30c6\u30b9\u30c8',
    load: '\u8aad\u307f\u8fbc\u307f',
    download: '\u30c0\u30a6\u30f3\u30ed\u30fc\u30c9',
    checking: '\u78ba\u8a8d\u4e2d\u2026',
  },
  webNode: {
    title: 'Web\u30ce\u30fc\u30c9',
    nodeStatus: '\u30ce\u30fc\u30c9\u72b6\u614b',
    browserCapabilities: '\u30d6\u30e9\u30a6\u30b6\u6a5f\u80fd',
    betweenNats: 'Between (NATS)',
    connected: '\u63a5\u7d9a\u6e08\u307f',
    disconnected: '\u672a\u63a5\u7d9a',
    connect: '\u63a5\u7d9a',
    disconnect: '\u5207\u65ad',
    roomState: '\u90e8\u5c4b\u306e\u72b6\u614b',
    lookAround: '\u5468\u308a\u3092\u898b\u308b',
    present: (names) => `\u305d\u306e\u5834\u306b\u3044\u308b: ${names}`,
    exits: (exits) => `\u51fa\u53e3: ${exits}`,
    companion: '\u30b3\u30f3\u30d1\u30cb\u30aa\u30f3 (Wyrd)',
    notStarted: '\u672a\u958b\u59cb',
    startCompanion: '\u30b3\u30f3\u30d1\u30cb\u30aa\u30f3\u3092\u958b\u59cb',
    offlineCache: '\u30aa\u30d5\u30e9\u30a4\u30f3\u30ad\u30e3\u30c3\u30b7\u30e5',
    browserInference: '\u30d6\u30e9\u30a6\u30b6\u63a8\u8ad6',
    browserInferenceWebLLM: '\u30d6\u30e9\u30a6\u30b6\u63a8\u8ad6 (WebLLM)',
    activePrefix: (name) => `\u4f7f\u7528\u4e2d: ${name}`,
    unload: '\u89e3\u653e',
    loading: '\u8aad\u307f\u8fbc\u307f\u4e2d\u2026',
    loadModel: '\u30e2\u30c7\u30eb\u3092\u8aad\u307f\u8fbc\u307f',
    webgpuNotAvailable:
      '\u3053\u306e\u30d6\u30e9\u30a6\u30b6\u3067\u306fWebGPU\u304c\u5229\u7528\u3067\u304d\u307e\u305b\u3093\u3002\u30d6\u30e9\u30a6\u30b6\u63a8\u8ad6\u306b\u306fChrome 113\u4ee5\u964d\u307e\u305f\u306fEdge 113\u4ee5\u964d\u3067WebGPU\u3092\u6709\u52b9\u306b\u3059\u308b\u5fc5\u8981\u304c\u3042\u308a\u307e\u3059\u3002',
    supported: '\u5bfe\u5fdc\u6e08\u307f',
    notAvailable: '\u975e\u5bfe\u5fdc',
  },
  household: {
    title: '\u4e16\u5e2f',
    connectionStatus: '\u63a5\u7d9a\u72b6\u614b',
    connectedLan: '\u63a5\u7d9a\u6e08\u307f (LAN)',
    connectedRelay: '\u63a5\u7d9a\u6e08\u307f (\u30ea\u30ec\u30fc)',
    discovering: '\u691c\u7d22\u4e2d...',
    offline: '\u30aa\u30d5\u30e9\u30a4\u30f3',
    reconnecting: '\u518d\u63a5\u7d9a\u4e2d...',
    householdId: '\u4e16\u5e2fID',
    noHousehold: '\u672a\u63a5\u7d9a',
    connect: '\u63a5\u7d9a',
    disconnect: '\u5207\u65ad',
    connectedNodes: '\u63a5\u7d9a\u4e2d\u306e\u30ce\u30fc\u30c9',
    noNodes: '\u30aa\u30f3\u30e9\u30a4\u30f3\u306e\u30ce\u30fc\u30c9\u306a\u3057',
    settingsSection: '\u8a2d\u5b9a',
    householdUrl: '\u4e16\u5e2fURL',
    householdUrlHint: 'NATS WebSocket URL (\u4f8b: ws://198.51.100.100:4222)',
    relayUrl: '\u30ea\u30ec\u30fcURL',
    relayUrlHint: 'LAN\u304c\u5229\u7528\u3067\u304d\u306a\u3044\u5834\u5408\u306e\u30af\u30e9\u30a6\u30c9\u30ea\u30ec\u30fc',
    autoDiscover: '\u81ea\u52d5\u691c\u51fa',
    autoDiscoverHint: 'mDNS\u3067LAN\u4e0a\u306e\u4e16\u5e2f\u30b5\u30fc\u30d0\u30fc\u3092\u691c\u7d22',
    manageHousehold: '\u4e16\u5e2f\u7ba1\u7406',
    remoteInference: '\u30ea\u30e2\u30fc\u30c8\u63a8\u8ad6',
    remoteInferenceUrl: '\u30ea\u30e2\u30fc\u30c8\u63a8\u8ad6URL',
    remoteInferenceHint: '\u4e16\u5e2f\u30b5\u30fc\u30d0\u30fc\u306eOpenAI\u4e92\u63db\u30a8\u30f3\u30c9\u30dd\u30a4\u30f3\u30c8',
  },
  firstRun: {
    welcome: '\u3069\u306e\u3088\u3046\u306b\u59cb\u3081\u307e\u3059\u304b\uff1f',
    localTitle: '\u30b3\u30f3\u30d1\u30cb\u30aa\u30f3\u306f\u3053\u3053\u306b\u4f4f\u3093\u3067\u3044\u307e\u3059',
    localDescription: '\u30b3\u30f3\u30d1\u30cb\u30aa\u30f3\u306f\u3053\u306e\u30c7\u30d0\u30a4\u30b9\u4e0a\u3067\u30ed\u30fc\u30ab\u30eb\u306b\u52d5\u4f5c\u3057\u3001\u72ec\u81ea\u306e\u90e8\u5c4b\u3068\u500b\u6027\u3092\u6301\u3061\u307e\u3059\u3002',
    remoteTitle: '\u4e16\u5e2f\u306b\u63a5\u7d9a',
    remoteDescription: '\u30cd\u30c3\u30c8\u30ef\u30fc\u30af\u4e0a\u306eWyrdsekai\u30b5\u30fc\u30d0\u30fc\u306b\u63a5\u7d9a\u3057\u307e\u3059\u3002',
    companionNameLabel: '\u30b3\u30f3\u30d1\u30cb\u30aa\u30f3\u306e\u540d\u524d\u306f\uff1f',
    begin: '\u59cb\u3081\u308b',
    continue: '\u7d9a\u3051\u308b',
  },
  birth: {
    beingBorn: (name) => `${name}\u304c\u8a95\u751f\u3057\u3066\u3044\u307e\u3059\u2026`,
    wakingUp: (name) => `${name}\u304c\u76ee\u899a\u3081\u3066\u3044\u307e\u3059\u2026`,
    downloading: '\u30e2\u30c7\u30eb\u3092\u30c0\u30a6\u30f3\u30ed\u30fc\u30c9\u4e2d\u2026',
    loadingModel: '\u30e2\u30c7\u30eb\u3092\u8aad\u307f\u8fbc\u307f\u4e2d\u2026',
    preparingRooms: '\u90e8\u5c4b\u3092\u6e96\u5099\u4e2d\u2026',
    entering: (name) => `${name}\u304c\u4e16\u754c\u306b\u5165\u308a\u307e\u3059\u2026`,
    almostReady: '\u3082\u3046\u3059\u3050\u2026',
    remoteConnecting: '\u63a5\u7d9a\u4e2d\u2026',
    remoteEntering: '\u4e16\u754c\u306b\u5165\u308a\u307e\u3059\u2026',
  },
  common: {
    back: '\u623b\u308b',
  },
};

const es: LocaleStrings = {
  connect: {
    title: 'Wyrdsekai',
    serverUrl: 'URL del servidor',
    username: 'Usuario',
    password: 'Contrase\u00f1a',
    login: 'Iniciar sesi\u00f3n',
    register: 'Registrarse',
    connectAnonymous: 'Conectar sin cuenta',
    loginFailed: 'Error al iniciar sesi\u00f3n',
    inviteAccepted: 'Invitación de relay aceptada — conectando a través del relay…',
    inviteNoZone: 'Esta invitación no tiene zona de origen — pide al propietario una invitación nueva (wyrd phone invite).',
    scanInvite: 'Escanear QR de invitación',
    cancelScan: 'Cancelar escaneo',
    cameraDenied: 'Permiso de cámara denegado',
    registrationFailed: 'Error en el registro',
  },
  room: {
    placeholder: 'decir, mirar, ir, equipar, usar\u2026',
    send: 'Enviar',
    lookLabel: 'Mirar',
    inventoryLabel: 'Inventario',
    settingsLabel: 'Ajustes',
    disconnectLabel: 'Desconectar',
    navigateHint: (direction) => `Ir hacia ${direction}`,
    directionLabels: {
      north: 'Norte', south: 'Sur', east: 'Este', west: 'Oeste',
      up: 'Arriba', down: 'Abajo',
      northeast: 'NE', northwest: 'NO', southeast: 'SE', southwest: 'SO',
    },
    helpCommands: 'Comandos:\n  say <texto> o \'<texto> o "<texto>  -- Hablar\n  emote <acci\u00f3n> o :<acci\u00f3n> o ;<acci\u00f3n>  -- Realizar una acci\u00f3n\n  tell <nombre> <texto> o ><nombre> <texto>  -- Mensaje privado\n  whisper <nombre> <texto>  -- Susurrar\n  look o l  -- Mirar alrededor\n  go <direcci\u00f3n>  -- Moverse\n  take <objeto>  -- Recoger\n  drop <objeto>  -- Soltar\n  use <objeto>  -- Usar\n  /inventory o /i  -- Ver inventario\n  /socials  -- Lista de emotes sociales\n  /help  -- Mostrar esta ayuda',
    socialsList: 'Emotes sociales (escribe la palabra para realizar):\n  nod, smile, laugh, grin, frown, shrug, sigh, gasp, blink, wince,\n  wave, bow, clap, dance, stretch, yawn, pace, fidget,\n  cry, cheer, groan, blush, ponder, brood, beam, sulk,\n  hug, thank, agree, disagree, salute, welcome',
    emoteFormat: '%s %s',
    whisperToFormat: '%s susurra a %s: %s',
    whisperFromFormat: '%s susurra: %s',
    tellFormat: '%s te dice: %s',
  },
  inventory: {
    title: 'Inventario',
    empty: 'Tu inventario est\u00e1 vac\u00edo',
    drop: 'Soltar',
    use: 'Usar',
  },
  settings: {
    title: 'Ajustes',
    server: 'Servidor',
    serverUrl: 'URL del servidor',
    account: 'Cuenta',
    username: 'Usuario',
    anonymous: 'An\u00f3nimo',
    theme: 'Tema',
    themeSystem: 'Sistema',
    themeLight: 'Claro',
    themeDark: 'Oscuro',
    language: 'Idioma',
    localInference: 'Inferencia local',
    activeModel: 'Modelo activo',
    noModelLoaded: 'Ning\u00fan modelo cargado',
    backendLocal: 'Local',
    backendServer: 'Servidor',
    backendNone: 'Ninguno',
    manageModels: 'Gestionar modelos',
    webNode: 'Nodo web',
    webNodeDashboard: 'Panel del nodo web',
    nodeStatus: 'Estado del nodo',
    browserModel: 'Modelo del navegador',
    logout: 'Cerrar sesi\u00f3n',
    connection: 'Conexi\u00f3n',
    connectionStatus: 'Estado de conexi\u00f3n',
    connected: 'Conectado',
    disconnected: 'Desconectado',
    inference: 'Inferencia',
    heavyThinking: 'Pensamiento pesado',
    heavyThinkingHome: 'Zona de casa',
    heavyThinkingCloud: 'API en la nube',
    heavyThinkingHint: 'Tu tel\u00e9fono siempre habla por s\u00ed mismo. Esto elige qu\u00e9 lo respalda para planificar y para las habilidades.',
    currentMode: 'Modo',
    onDeviceModel: 'Ejecutar el modelo en este tel\u00e9fono',
    onDeviceModelHint: 'Desactivado por defecto. La mayor\u00eda de los tel\u00e9fonos a\u00fan no son lo bastante r\u00e1pidos: las respuestas llegan m\u00e1s lento de lo que puedes leer y el tel\u00e9fono se calienta. Tu zona de casa o una API en la nube lo hacen mucho mejor.',
    experimental: 'EXPERIMENTAL',
    apiProvider: 'Proveedor de API',
    apiKey: 'Clave API',
    apiKeyPlaceholder: 'sk-...',
    apiBaseUrl: 'URL base',
    apiBaseUrlPlaceholder: 'https://api.example.com/v1',
    companion: 'Compa\u00f1ero',
    companionName: 'Nombre del compa\u00f1ero',
    statusMessage: 'Mensaje de estado',
    soulSeedImport: 'Importar semilla de alma',
    exportSoul: 'Exportar alma',
    advanced: 'Avanzado',
    stopNode: 'Detener nodo',
    inferenceUrlOverride: 'URL de inferencia personalizada',
    debugMode: 'Modo depuraci\u00f3n',
  },
  models: {
    title: 'Modelos',
    tierTiny: 'Min\u00fasculo',
    tierSmall: 'Peque\u00f1o',
    tierMedium: 'Mediano',
    downloadComplete: 'Descarga completada',
    downloadCompleteBody: (path) => `El modelo est\u00e1 listo para cargar.\n\nRuta: ${path}`,
    downloadFailed: 'Error en la descarga',
    downloadFailedBody: (msg, dir) => `${msg}\n\nDirectorio de modelos: ${dir}`,
    error: 'Error',
    modelNotFound: 'No se encontr\u00f3 el archivo del modelo. Desc\u00e1rgalo primero.',
    loadFailed: 'Error al cargar',
    unknownError: 'Error desconocido',
    noModelLoaded: 'Ning\u00fan modelo cargado.',
    inferenceTest: 'Prueba de inferencia',
    inferenceError: 'Error de inferencia',
    deleteModel: 'Eliminar modelo',
    deleteModelBody: (name, size) => `\u00bfEliminar ${name}? Se liberar\u00e1n ${size}.`,
    cancel: 'Cancelar',
    delete: 'Eliminar',
    active: 'Activo',
    testing: 'Probando...',
    test: 'Probar',
    load: 'Cargar',
    download: 'Descargar',
    checking: 'verificando...',
  },
  webNode: {
    title: 'Nodo web',
    nodeStatus: 'Estado del nodo',
    browserCapabilities: 'Capacidades del navegador',
    betweenNats: 'Between (NATS)',
    connected: 'Conectado',
    disconnected: 'Desconectado',
    connect: 'Conectar',
    disconnect: 'Desconectar',
    roomState: 'Estado de la sala',
    lookAround: 'Mirar alrededor',
    present: (names) => `Presentes: ${names}`,
    exits: (exits) => `Salidas: ${exits}`,
    companion: 'Compa\u00f1ero (Wyrd)',
    notStarted: 'Sin iniciar',
    startCompanion: 'Iniciar compa\u00f1ero',
    offlineCache: 'Cach\u00e9 sin conexi\u00f3n',
    browserInference: 'Inferencia del navegador',
    browserInferenceWebLLM: 'Inferencia del navegador (WebLLM)',
    activePrefix: (name) => `Activo: ${name}`,
    unload: 'Liberar',
    loading: 'Cargando...',
    loadModel: 'Cargar modelo',
    webgpuNotAvailable:
      'WebGPU no est\u00e1 disponible en este navegador. La inferencia en el navegador requiere Chrome 113+ o Edge 113+ con WebGPU habilitado.',
    supported: 'Compatible',
    notAvailable: 'No disponible',
  },
  household: {
    title: 'Hogar',
    connectionStatus: 'Estado de conexi\u00f3n',
    connectedLan: 'Conectado (LAN)',
    connectedRelay: 'Conectado (Relay)',
    discovering: 'Buscando...',
    offline: 'Sin conexi\u00f3n',
    reconnecting: 'Reconectando...',
    householdId: 'ID del hogar',
    noHousehold: 'No conectado',
    connect: 'Conectar',
    disconnect: 'Desconectar',
    connectedNodes: 'Nodos conectados',
    noNodes: 'Ning\u00fan nodo en l\u00ednea',
    settingsSection: 'Ajustes',
    householdUrl: 'URL del hogar',
    householdUrlHint: 'URL de NATS WebSocket (ej. ws://198.51.100.100:4222)',
    relayUrl: 'URL del relay',
    relayUrlHint: 'Relay en la nube para cuando la LAN no est\u00e9 disponible',
    autoDiscover: 'Auto-descubrir',
    autoDiscoverHint: 'Usar mDNS para encontrar el servidor en la LAN',
    manageHousehold: 'Gestionar hogar',
    remoteInference: 'Inferencia remota',
    remoteInferenceUrl: 'URL de inferencia remota',
    remoteInferenceHint: 'Endpoint compatible con OpenAI en el servidor del hogar',
  },
  firstRun: {
    welcome: '\u00bfC\u00f3mo deseas comenzar?',
    localTitle: 'Mi compa\u00f1ero vive aqu\u00ed',
    localDescription: 'Tu compa\u00f1ero funciona localmente en este dispositivo con sus propias salas y personalidad.',
    remoteTitle: 'Conectar al hogar',
    remoteDescription: 'Con\u00e9ctate a un servidor Wyrdsekai en tu red.',
    companionNameLabel: '\u00bfC\u00f3mo quieres llamar a tu compa\u00f1ero?',
    begin: 'Comenzar',
    continue: 'Continuar',
  },
  birth: {
    beingBorn: (name) => `${name} est\u00e1 naciendo...`,
    wakingUp: (name) => `${name} est\u00e1 despertando...`,
    downloading: 'Descargando modelo...',
    loadingModel: 'Cargando modelo...',
    preparingRooms: 'Preparando salas...',
    entering: (name) => `${name} est\u00e1 entrando al mundo...`,
    almostReady: 'Casi listo...',
    remoteConnecting: 'Conectando...',
    remoteEntering: 'Entrando al mundo...',
  },
  common: {
    back: 'Volver',
  },
};

const allStrings: Record<string, LocaleStrings> = { en, ja, es };

/**
 * Return the LocaleStrings for the given locale code.
 * Falls back to English for unknown locales.
 */
export function getStrings(locale: string): LocaleStrings {
  return allStrings[locale] ?? en;
}
