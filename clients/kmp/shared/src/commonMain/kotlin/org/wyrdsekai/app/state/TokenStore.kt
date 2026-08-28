package org.wyrdsekai.app.state

expect class TokenStore() {
    fun saveToken(token: String)
    fun loadToken(): String?
    fun saveServerUrl(url: String)
    fun loadServerUrl(): String?
    fun saveUsername(username: String)
    fun loadUsername(): String?
    fun saveLocale(locale: String)
    fun loadLocale(): String?
    fun saveCompanionName(name: String)
    fun loadCompanionName(): String?
    fun saveHomeName(name: String)
    fun loadHomeName(): String?
    fun saveMode(mode: String) // "local" | "remote"
    fun loadMode(): String?
    fun saveInferenceUrl(url: String)
    fun loadInferenceUrl(): String?
    fun savePairingToken(token: String)
    fun loadPairingToken(): String?
    fun saveHouseholdId(id: String)
    fun loadHouseholdId(): String?
    fun saveHouseholdName(name: String)
    fun loadHouseholdName(): String?
    fun saveServerDid(did: String)
    fun loadServerDid(): String?
    fun saveNatsUrl(url: String)
    fun loadNatsUrl(): String?
    fun saveNatsUser(user: String)
    fun loadNatsUser(): String?
    fun saveNatsPassword(password: String)
    fun loadNatsPassword(): String?
    fun saveRelayFingerprints(fingerprints: String)
    fun loadRelayFingerprints(): String?
    fun saveRelayUrl(url: String)
    fun loadRelayUrl(): String?
    fun saveRelayToken(token: String)
    fun loadRelayToken(): String?
    fun saveAuthToken(token: String)
    fun loadAuthToken(): String?
    fun saveUserId(id: String)
    fun loadUserId(): String?
    fun saveUserRole(role: String)
    fun loadUserRole(): String?
    fun saveApiKey(key: String)
    fun loadApiKey(): String?
    fun saveApiProvider(provider: String) // "openai" | "anthropic" | "openrouter" | "custom"
    fun loadApiProvider(): String?
    fun savePreferredBacking(backing: String) // "home" | "cloud" — the mode-4/5 fork
    fun loadPreferredBacking(): String?
    // EXPERIMENTAL opt-in to running the companion's model on this device.
    fun saveOnDeviceModelOptIn(enabled: Boolean)
    fun loadOnDeviceModelOptIn(): Boolean
    fun saveApiBaseUrl(url: String)
    fun loadApiBaseUrl(): String?
    fun saveDebugMode(enabled: Boolean)
    fun loadDebugMode(): Boolean
    // Consent to serve household hermod errands from this device while
    // charging. Off by default — lending compute is a choice, never a side
    // effect of pairing.
    fun saveHermodConsent(enabled: Boolean)
    fun loadHermodConsent(): Boolean
    // MCP-layer credentials. Separate from auth token because /api/mcp/login
    // mints a new session token from these every cold start; the older
    // wyrd_auth_token holds the most recent mint but isn't sufficient on its
    // own — when the server restarts, the saved auth token is dead and the
    // app must re-login with username+password to get a fresh one.
    fun saveMcpUsername(name: String)
    fun loadMcpUsername(): String?
    fun saveMcpPassword(password: String)
    fun loadMcpPassword(): String?
    // NATS zone scope the phone uses for subjects (wyrd.zone.<zone>.*).
    // Cached after a successful redeem/register/login so subsequent runs
    // skip the racey `wyrd.discover.zone` request (multiple α/β nodes
    // subscribe to it without a queue group; first responder wins).
    fun saveZoneId(zone: String)
    fun loadZoneId(): String?
    // Per-device remembered zone passwords. Stored
    // as ONE serialized JSON blob ({zoneId: password}) in secure storage. NEVER
    // synced — passwords stay on this device only; only the zone bank (zones +
    // relay creds) crosses devices.
    fun saveZonePasswords(blobJson: String)
    fun loadZonePasswords(): String?
    // The zone bank itself: held relays + zone address
    // book, persisted as ONE JSON blob ({relays, zones}). Syncs cross-device via
    // the home zone; this is the local mirror (the RN zustand-store equivalent).
    fun saveZoneBank(blobJson: String)
    fun loadZoneBank(): String?
    fun clearAuth()  // logout — clears auth token, userId, role
    fun clear()
    /** Drop the home-zone relay leg (relay/nats/zone) → local-only. Keeps the
     *  local Study (the last-synced mirror) and all other identity/creds. */
    fun disconnectHomeZone()
}
