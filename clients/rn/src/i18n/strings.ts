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

/**
 * Her emotes and the lines the phone speaks for her (CompanionEngine,
 * ProactivityJudgment). They were English literals whatever the language.
 * The same wording as the KMP client's UiStrings narration… entries.
 * Model-facing prompt text is not here.
 */
export interface NarrationStrings {
  considers: string;
  thinkingDeeply: string;
  mentalNote: string;
  catchesUp: string;
  thinkLater: string;
  replayAbout: (topic: string) => string;
  newPassage: (exitLabel: string) => string;
  notificationPriority: (priority: string, message: string) => string;
  heads: (direction: string) => string;
  equips: (item: string) => string;
  removes: (item: string) => string;
  uses: (item: string) => string;
  usesSkill: (skill: string) => string;
  workbenchSubmit: (skill: string) => string;
  thinkingDeeplyAbout: string;
  sendsMessage: (target: string) => string;
  commitsTo: (description: string) => string;
  planning: (goal: string, steps: number) => string;
  zoneCommand: (command: string) => string;
  notification: (message: string) => string;
  watchingFor: (name: string) => string;
  stopsWatching: (watcherId: string) => string;
  schedules: (skill: string, interval: string) => string;
  codexOn: (operation: string, itemId: string) => string;
  roomIdeaLater: string;
  // Her unprompted lines and emotes (ProactivityJudgment).
  noticedSomething: string;
  quietCheckIn: string;
  concernedGlance: string;
  beenAWhile: string;
  anythingOnYourMind: string;
  shiftsThoughtfully: string;
  meaningToFollowUp: string;
  patternsShifted: string;
  oracleSensed: (prediction: string) => string;
  pausesThoughtfully: string;
  exploringSomething: string;
  actingOnCommitment: string;
  /** Direction codes as words in a sentence ("heads north"), not the exit buttons' directionLabels. */
  directionWords: Record<string, string>;
  /** Notification priority codes as words, for notificationPriority. */
  priorityWords: Record<string, string>;
}

/**
 * What the phone says when it refuses an unprotected connection
 * (, W2/W3). Plain words; the person may not know what
 * a certificate is, only what to do next.
 */
export interface SecurityStrings {
  /** The phone holds no key for its home (paired before 0.5.0). */
  pairAgain: string;
  /** The home refused a sealed request (wrong key, clock far off, replay). */
  requestRefused: string;
  /** A reply claiming to be from the home could not be verified. */
  replyUnverified: string;
  /** The home refused the encrypted tunnel. */
  tunnelRefused: string;
  /** The encrypted tunnel broke (a frame did not open). */
  tunnelInterrupted: string;
  pinMismatchTitle: string;
  pinMismatchBody: (host: string) => string;
  /** Paired before home-network pinning: the phone uses the relay (said once). */
  lanPairAgain: string;
  /** A typed http:// or ws:// address on the network. */
  plainAddressRefused: string;
  /** A knock to a zone that published no key: not sent. */
  knockNeedsKey: string;
  /** A knock on another zone that this phone's (older) relay refused to carry. */
  knockRelayRefused: string;
  /** A knock nobody answered: the zone is offline or not reachable through this relay. */
  knockNoAnswer: string;
  /** The relay (or the home bus) would not carry a request for this phone's account. */
  relayRefused: string;
  ok: string;
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
  security: SecurityStrings;
  narration: NarrationStrings;
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
  security: {
    pairAgain: 'This phone was paired before its connection to your home was encrypted. Pair it again: on your home machine run "wyrd phone invite", then scan the new invite.',
    requestRefused: 'Your home refused this phone\'s encrypted request. Check that the phone\'s date and time are right. If they are, pair the phone again (on your home machine: wyrd phone invite).',
    replyUnverified: 'A reply that claimed to come from your home could not be checked, so the phone ignored it. Try again.',
    tunnelRefused: 'Your home refused the encrypted connection. Pair this phone again: on your home machine run "wyrd phone invite", then scan the new invite.',
    tunnelInterrupted: 'The encrypted connection to your home was interrupted. Open the server again to reconnect.',
    pinMismatchTitle: 'Connection refused',
    pinMismatchBody: (host) => `${host} showed a certificate that does not match the one this phone was paired with, so the phone did not connect. If your home's certificate was changed on purpose, pair this phone again (on your home machine: wyrd phone invite). If not, someone may be trying to listen in.`,
    lanPairAgain: 'This phone reaches your home through the relay. To connect directly on your home network, pair it again: on your home machine run "wyrd phone invite", then scan the new invite.',
    plainAddressRefused: 'That address is not encrypted, so the phone will not send anything to it. Pair with an invite instead: on your home machine run "wyrd phone invite".',
    knockNeedsKey: 'That zone has not published the key this phone needs to reach it privately, so the request was not sent. Ask its steward for an invite instead.',
    knockRelayRefused: 'Your relay does not carry knocks to other zones (it may be older than 0.5.0), so your request did not reach that zone. Ask that zone\'s steward for an invite: they run "wyrd phone invite" and send you the link or QR code.',
    knockNoAnswer: 'That zone did not answer: it may be offline, or not reachable through your relay. Try again later, or ask its steward for an invite.',
    relayRefused: 'The relay would not carry this request for this phone, so nothing reached your home. This phone may only reach its own home through the relay. If this is your home, pair the phone again (on your home machine: wyrd phone invite).',
    ok: 'OK',
  },
  narration: {
    considers: 'considers...',
    thinkingDeeply: 'is thinking deeply...',
    mentalNote: 'makes a mental note...',
    catchesUp: 'catches up on earlier conversations...',
    thinkLater: "*nods thoughtfully* I'll think about that when I can.",
    replayAbout: (topic) => `About "${topic}" —`,
    newPassage: (exitLabel) => `A new passage appears: ${exitLabel}`,
    notificationPriority: (priority, message) => `*notification (${priority})*: ${message}`,
    heads: (direction) => `heads ${direction}`,
    equips: (item) => `*equips ${item}*`,
    removes: (item) => `*removes ${item}*`,
    uses: (item) => `*uses ${item}*`,
    usesSkill: (skill) => `*uses skill: ${skill}*`,
    workbenchSubmit: (skill) => `*submits ${skill} to the workbench for validation*`,
    thinkingDeeplyAbout: '*thinking deeply about this...*',
    sendsMessage: (target) => `*sends a message to ${target}*`,
    commitsTo: (description) => `*commits to: ${description}*`,
    planning: (goal, steps) => `*planning: ${goal} (${steps} steps)*`,
    zoneCommand: (command) => `*sends zone command: ${command}*`,
    notification: (message) => `*notification: ${message}*`,
    watchingFor: (name) => `*watching for: ${name}*`,
    stopsWatching: (watcherId) => `*stops watching: ${watcherId}*`,
    schedules: (skill, interval) => `*schedules ${skill} every ${interval}*`,
    codexOn: (operation, itemId) => `*${operation} on ${itemId}*`,
    roomIdeaLater: "I'll remember that room idea for when connected to the household server.",
    noticedSomething: 'I noticed something worth mentioning...',
    quietCheckIn: "Is everything alright? It's been quiet.",
    concernedGlance: '*glances up with a concerned expression*',
    beenAWhile: "It's been a while — hope you're doing well.",
    anythingOnYourMind: 'Anything on your mind?',
    shiftsThoughtfully: '*shifts thoughtfully*',
    meaningToFollowUp: "I've been meaning to follow up on something...",
    patternsShifted: 'Something shifted in the patterns...',
    oracleSensed: (prediction) => `The Oracle sensed something: ${prediction}`,
    pausesThoughtfully: '*pauses thoughtfully*',
    exploringSomething: 'Exploring something that caught attention',
    actingOnCommitment: 'Acting on pending commitment',
    directionWords: {
      north: 'north', south: 'south', east: 'east', west: 'west',
      up: 'up', down: 'down', northeast: 'northeast', northwest: 'northwest',
      southeast: 'southeast', southwest: 'southwest',
    },
    priorityWords: {
      ambient: 'ambient', low: 'low', normal: 'normal', high: 'high',
      urgent: 'urgent', critical: 'critical',
    },
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
  security: {
    pairAgain: 'このスマートフォンは、ホームとの接続が暗号化される前にペアリングされています。もう一度ペアリングしてください。ホームのマシンで「wyrd phone invite」を実行し、新しい招待を読み取ってください。',
    requestRefused: 'ホームがこのスマートフォンの暗号化されたリクエストを拒否しました。スマートフォンの日付と時刻が正しいか確認してください。正しい場合は、もう一度ペアリングしてください（ホームのマシンで wyrd phone invite）。',
    replyUnverified: 'ホームからとされる返信を確認できなかったため、無視しました。もう一度お試しください。',
    tunnelRefused: 'ホームが暗号化された接続を拒否しました。もう一度ペアリングしてください。ホームのマシンで「wyrd phone invite」を実行し、新しい招待を読み取ってください。',
    tunnelInterrupted: 'ホームとの暗号化された接続が途切れました。サーバーをもう一度開いて再接続してください。',
    pinMismatchTitle: '接続を拒否しました',
    pinMismatchBody: (host) => `${host} が示した証明書は、このスマートフォンがペアリングしたときのものと一致しないため、接続しませんでした。ホームの証明書を意図して変更した場合は、もう一度ペアリングしてください（ホームのマシンで wyrd phone invite）。そうでない場合は、誰かが通信を盗み見ようとしている可能性があります。`,
    lanPairAgain: 'このスマートフォンはリレー経由でホームにつながっています。ホームのネットワークで直接つなぐには、もう一度ペアリングしてください。ホームのマシンで「wyrd phone invite」を実行し、新しい招待を読み取ってください。',
    plainAddressRefused: 'このアドレスは暗号化されていないため、スマートフォンは何も送信しません。代わりに招待でペアリングしてください。ホームのマシンで「wyrd phone invite」を実行します。',
    knockNeedsKey: 'このゾーンは、スマートフォンが内容を守ったまま届けるための鍵を公開していないため、リクエストは送信されませんでした。代わりにそのゾーンのスチュワードに招待を依頼してください。',
    knockRelayRefused: 'お使いのリレーはほかのゾーンへのノックを運ばないため（0.5.0より前のリレーかもしれません）、リクエストはそのゾーンに届いていません。そのゾーンのスチュワードに招待を依頼してください（スチュワードが「wyrd phone invite」を実行し、リンクかQRコードを送ります）。',
    knockNoAnswer: 'そのゾーンから応答がありませんでした。オフラインか、お使いのリレーからは届かない可能性があります。あとでもう一度試すか、そのゾーンのスチュワードに招待を依頼してください。',
    relayRefused: 'リレーがこのスマートフォンのリクエストを運ばなかったため、ホームには何も届いていません。このスマートフォンがリレー経由で届けられるのは自分のホームだけです。自分のホームの場合は、もう一度ペアリングしてください（ホームのマシンで wyrd phone invite）。',
    ok: 'OK',
  },
  narration: {
    considers: '考え込む…',
    thinkingDeeply: '深く考えている…',
    mentalNote: '心に留める…',
    catchesUp: '前の会話に追いつこうとしている…',
    thinkLater: '*考え深げにうなずく* できるときに考えておくね。',
    replayAbout: (topic) => `「${topic}」について —`,
    newPassage: (exitLabel) => `新しい通路が現れた: ${exitLabel}`,
    notificationPriority: (priority, message) => `*通知 (${priority})*: ${message}`,
    heads: (direction) => `${direction}へ向かう`,
    equips: (item) => `*${item}を装備する*`,
    removes: (item) => `*${item}を外す*`,
    uses: (item) => `*${item}を使う*`,
    usesSkill: (skill) => `*スキルを使う: ${skill}*`,
    workbenchSubmit: (skill) => `*${skill}を検証のためワークベンチに出す*`,
    thinkingDeeplyAbout: '*このことを深く考えている…*',
    sendsMessage: (target) => `*${target}にメッセージを送る*`,
    commitsTo: (description) => `*約束する: ${description}*`,
    planning: (goal, steps) => `*計画中: ${goal} (${steps}ステップ)*`,
    zoneCommand: (command) => `*ゾーンコマンドを送る: ${command}*`,
    notification: (message) => `*通知: ${message}*`,
    watchingFor: (name) => `*見守っている: ${name}*`,
    stopsWatching: (watcherId) => `*見守りをやめる: ${watcherId}*`,
    schedules: (skill, interval) => `*${skill}を${interval}ごとに予定する*`,
    codexOn: (operation, itemId) => `*${itemId}に${operation}*`,
    roomIdeaLater: 'その部屋のアイデアは、世帯のサーバーにつながったときのために覚えておくね。',
    noticedSomething: '話しておきたいことに気づいたよ…',
    quietCheckIn: '大丈夫？ずっと静かだったね。',
    concernedGlance: '*心配そうに顔を上げる*',
    beenAWhile: '久しぶりだね — 元気にしているといいな。',
    anythingOnYourMind: '何か考えていることはある？',
    shiftsThoughtfully: '*考え込むように身じろぎする*',
    meaningToFollowUp: 'やりかけのことを確かめようと思っていたんだ…',
    patternsShifted: 'パターンに何か変化があった…',
    oracleSensed: (prediction) => `オラクルが何かを感じ取った: ${prediction}`,
    pausesThoughtfully: '*考え込んで間を置く*',
    exploringSomething: '気になったことを探っている',
    actingOnCommitment: 'やりかけの約束に取りかかっている',
    directionWords: {
      north: '北', south: '南', east: '東', west: '西',
      up: '上', down: '下', northeast: '北東', northwest: '北西',
      southeast: '南東', southwest: '南西',
    },
    priorityWords: {
      ambient: '控えめ', low: '低', normal: '通常', high: '高',
      urgent: '緊急', critical: '重大',
    },
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
  security: {
    pairAgain: 'Este teléfono se vinculó antes de que la conexión con tu hogar estuviera cifrada. Vincúlalo de nuevo: en la máquina de tu hogar ejecuta "wyrd phone invite" y escanea la nueva invitación.',
    requestRefused: 'Tu hogar rechazó la solicitud cifrada de este teléfono. Comprueba que la fecha y la hora del teléfono sean correctas. Si lo son, vincula el teléfono de nuevo (en la máquina de tu hogar: wyrd phone invite).',
    replyUnverified: 'Una respuesta que decía venir de tu hogar no se pudo comprobar, así que el teléfono la ignoró. Inténtalo de nuevo.',
    tunnelRefused: 'Tu hogar rechazó la conexión cifrada. Vincula este teléfono de nuevo: en la máquina de tu hogar ejecuta "wyrd phone invite" y escanea la nueva invitación.',
    tunnelInterrupted: 'La conexión cifrada con tu hogar se interrumpió. Abre el servidor de nuevo para reconectar.',
    pinMismatchTitle: 'Conexión rechazada',
    pinMismatchBody: (host) => `${host} mostró un certificado que no coincide con el que conoció este teléfono al vincularse, así que el teléfono no se conectó. Si el certificado de tu hogar se cambió a propósito, vincula este teléfono de nuevo (en la máquina de tu hogar: wyrd phone invite). Si no, alguien podría estar intentando escuchar.`,
    lanPairAgain: 'Este teléfono llega a tu hogar a través del relay. Para conectarse directamente en la red de tu hogar, vincúlalo de nuevo: en la máquina de tu hogar ejecuta "wyrd phone invite" y escanea la nueva invitación.',
    plainAddressRefused: 'Esa dirección no está cifrada, así que el teléfono no le enviará nada. Vincúlalo con una invitación: en la máquina de tu hogar ejecuta "wyrd phone invite".',
    knockNeedsKey: 'Esa zona no ha publicado la clave que este teléfono necesita para llegar a ella en privado, así que la solicitud no se envió. Pide una invitación a su steward.',
    knockRelayRefused: 'Tu relay no lleva solicitudes a otras zonas (puede ser anterior a 0.5.0), así que tu solicitud no llegó a esa zona. Pide una invitación al steward de esa zona: ejecuta "wyrd phone invite" y te envía el enlace o el código QR.',
    knockNoAnswer: 'Esa zona no respondió: puede estar desconectada o no ser alcanzable a través de tu relay. Inténtalo más tarde o pide una invitación a su steward.',
    relayRefused: 'El relay no quiso llevar esta solicitud de este teléfono, así que nada llegó a tu hogar. Este teléfono solo puede llegar a su propio hogar a través del relay. Si es tu hogar, vincula el teléfono de nuevo (en la máquina de tu hogar: wyrd phone invite).',
    ok: 'Aceptar',
  },
  narration: {
    considers: 'lo considera...',
    thinkingDeeply: 'está pensando a fondo...',
    mentalNote: 'toma nota mentalmente...',
    catchesUp: 'se pone al día con conversaciones anteriores...',
    thinkLater: '*asiente pensativamente* Lo pensaré cuando pueda.',
    replayAbout: (topic) => `Sobre "${topic}" —`,
    newPassage: (exitLabel) => `Aparece un nuevo pasaje: ${exitLabel}`,
    notificationPriority: (priority, message) => `*notificación (${priority})*: ${message}`,
    heads: (direction) => `se dirige hacia ${direction}`,
    equips: (item) => `*se equipa ${item}*`,
    removes: (item) => `*se quita ${item}*`,
    uses: (item) => `*usa ${item}*`,
    usesSkill: (skill) => `*usa la habilidad: ${skill}*`,
    workbenchSubmit: (skill) => `*envía ${skill} al banco de trabajo para validarlo*`,
    thinkingDeeplyAbout: '*pensando a fondo en esto...*',
    sendsMessage: (target) => `*envía un mensaje a ${target}*`,
    commitsTo: (description) => `*se compromete a: ${description}*`,
    planning: (goal, steps) => `*planificando: ${goal} (${steps} pasos)*`,
    zoneCommand: (command) => `*envía una orden a la zona: ${command}*`,
    notification: (message) => `*notificación: ${message}*`,
    watchingFor: (name) => `*vigilando: ${name}*`,
    stopsWatching: (watcherId) => `*deja de vigilar: ${watcherId}*`,
    schedules: (skill, interval) => `*programa ${skill} cada ${interval}*`,
    codexOn: (operation, itemId) => `*${operation} en ${itemId}*`,
    roomIdeaLater: 'Recordaré esa idea de sala para cuando haya conexión con el servidor del hogar.',
    noticedSomething: 'Me di cuenta de algo que vale la pena mencionar...',
    quietCheckIn: '¿Todo bien? Ha estado todo muy tranquilo.',
    concernedGlance: '*levanta la vista con expresión preocupada*',
    beenAWhile: 'Ha pasado un tiempo — espero que estés bien.',
    anythingOnYourMind: '¿Tienes algo en mente?',
    shiftsThoughtfully: '*se acomoda pensativamente*',
    meaningToFollowUp: 'Quería retomar algo pendiente...',
    patternsShifted: 'Algo cambió en los patrones...',
    oracleSensed: (prediction) => `El Oráculo percibió algo: ${prediction}`,
    pausesThoughtfully: '*hace una pausa reflexiva*',
    exploringSomething: 'Explorando algo que le llamó la atención',
    actingOnCommitment: 'Cumpliendo un compromiso pendiente',
    directionWords: {
      north: 'el norte', south: 'el sur', east: 'el este', west: 'el oeste',
      up: 'arriba', down: 'abajo', northeast: 'el noreste', northwest: 'el noroeste',
      southeast: 'el sureste', southwest: 'el suroeste',
    },
    priorityWords: {
      ambient: 'ambiental', low: 'baja', normal: 'normal', high: 'alta',
      urgent: 'urgente', critical: 'crítica',
    },
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
