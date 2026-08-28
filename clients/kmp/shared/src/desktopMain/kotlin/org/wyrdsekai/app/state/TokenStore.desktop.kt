package org.wyrdsekai.app.state

import java.util.prefs.Preferences

actual class TokenStore actual constructor() {
    private val prefs = Preferences.userNodeForPackage(TokenStore::class.java)

    actual fun saveToken(token: String) {
        prefs.put(KEY_TOKEN, token)
        prefs.flush()
    }

    actual fun loadToken(): String? {
        return prefs.get(KEY_TOKEN, null)
    }

    actual fun saveServerUrl(url: String) {
        prefs.put(KEY_SERVER_URL, url)
        prefs.flush()
    }

    actual fun loadServerUrl(): String? {
        return prefs.get(KEY_SERVER_URL, null)
    }

    actual fun saveUsername(username: String) {
        prefs.put(KEY_USERNAME, username)
        prefs.flush()
    }

    actual fun loadUsername(): String? {
        return prefs.get(KEY_USERNAME, null)
    }

    actual fun saveLocale(locale: String) {
        prefs.put(KEY_LOCALE, locale)
        prefs.flush()
    }

    actual fun loadLocale(): String? {
        return prefs.get(KEY_LOCALE, null)
    }

    actual fun saveCompanionName(name: String) {
        prefs.put(KEY_COMPANION_NAME, name)
        prefs.flush()
    }

    actual fun loadCompanionName(): String? {
        return prefs.get(KEY_COMPANION_NAME, null)
    }

    actual fun saveHomeName(name: String) {
        prefs.put(KEY_HOME_NAME, name)
        prefs.flush()
    }

    actual fun loadHomeName(): String? {
        return prefs.get(KEY_HOME_NAME, null)
    }

    actual fun saveMode(mode: String) {
        prefs.put(KEY_APP_MODE, mode)
        prefs.flush()
    }

    actual fun loadMode(): String? {
        return prefs.get(KEY_APP_MODE, null)
    }

    actual fun saveInferenceUrl(url: String) {
        prefs.put(KEY_INFERENCE_URL, url)
        prefs.flush()
    }

    actual fun loadInferenceUrl(): String? {
        return prefs.get(KEY_INFERENCE_URL, null)
    }

    actual fun savePairingToken(token: String) {
        prefs.put(KEY_PAIRING_TOKEN, token)
        prefs.flush()
    }

    actual fun loadPairingToken(): String? {
        return prefs.get(KEY_PAIRING_TOKEN, null)
    }

    actual fun saveHouseholdId(id: String) {
        prefs.put(KEY_HOUSEHOLD_ID, id)
        prefs.flush()
    }

    actual fun loadHouseholdId(): String? {
        return prefs.get(KEY_HOUSEHOLD_ID, null)
    }

    actual fun saveHouseholdName(name: String) {
        prefs.put(KEY_HOUSEHOLD_NAME, name)
        prefs.flush()
    }

    actual fun loadHouseholdName(): String? {
        return prefs.get(KEY_HOUSEHOLD_NAME, null)
    }

    actual fun saveServerDid(did: String) {
        prefs.put(KEY_SERVER_DID, did)
        prefs.flush()
    }

    actual fun loadServerDid(): String? {
        return prefs.get(KEY_SERVER_DID, null)
    }

    actual fun saveNatsUrl(url: String) {
        prefs.put(KEY_NATS_URL, url)
        prefs.flush()
    }

    actual fun loadNatsUrl(): String? {
        return prefs.get(KEY_NATS_URL, null)
    }

    actual fun saveNatsUser(user: String) { prefs.put(KEY_NATS_USER, user); prefs.flush() }
    actual fun loadNatsUser(): String? = prefs.get(KEY_NATS_USER, null)
    actual fun saveNatsPassword(password: String) { prefs.put(KEY_NATS_PASSWORD, password); prefs.flush() }
    actual fun loadNatsPassword(): String? = prefs.get(KEY_NATS_PASSWORD, null)
    actual fun saveRelayFingerprints(fingerprints: String) { prefs.put(KEY_RELAY_FPS, fingerprints); prefs.flush() }
    actual fun loadRelayFingerprints(): String? = prefs.get(KEY_RELAY_FPS, null)

    actual fun saveRelayUrl(url: String) {
        prefs.put(KEY_RELAY_URL, url)
        prefs.flush()
    }

    actual fun loadRelayUrl(): String? {
        return prefs.get(KEY_RELAY_URL, null)
    }

    actual fun saveRelayToken(token: String) {
        prefs.put(KEY_RELAY_TOKEN, token)
        prefs.flush()
    }

    actual fun loadRelayToken(): String? {
        return prefs.get(KEY_RELAY_TOKEN, null)
    }

    actual fun saveAuthToken(token: String) {
        prefs.put(KEY_AUTH_TOKEN, token)
        prefs.flush()
    }

    actual fun loadAuthToken(): String? {
        return prefs.get(KEY_AUTH_TOKEN, null)
    }

    actual fun saveUserId(id: String) {
        prefs.put(KEY_USER_ID, id)
        prefs.flush()
    }

    actual fun loadUserId(): String? {
        return prefs.get(KEY_USER_ID, null)
    }

    actual fun saveUserRole(role: String) {
        prefs.put(KEY_USER_ROLE, role)
        prefs.flush()
    }

    actual fun loadUserRole(): String? {
        return prefs.get(KEY_USER_ROLE, null)
    }

    actual fun saveApiKey(key: String) {
        prefs.put(KEY_API_KEY, key)
        prefs.flush()
    }

    actual fun loadApiKey(): String? {
        return prefs.get(KEY_API_KEY, null)
    }

    actual fun saveApiProvider(provider: String) {
        prefs.put(KEY_API_PROVIDER, provider)
        prefs.flush()
    }

    actual fun loadApiProvider(): String? {
        return prefs.get(KEY_API_PROVIDER, null)
    }

    actual fun savePreferredBacking(backing: String) {
        prefs.put(KEY_PREFERRED_BACKING, backing)
        prefs.flush()
    }

    actual fun loadPreferredBacking(): String? {
        return prefs.get(KEY_PREFERRED_BACKING, null)
    }

    actual fun saveOnDeviceModelOptIn(enabled: Boolean) {
        prefs.putBoolean(KEY_ON_DEVICE_MODEL_OPT_IN, enabled)
        prefs.flush()
    }

    actual fun loadOnDeviceModelOptIn(): Boolean {
        return prefs.getBoolean(KEY_ON_DEVICE_MODEL_OPT_IN, false)
    }

    actual fun saveApiBaseUrl(url: String) {
        prefs.put(KEY_API_BASE_URL, url)
        prefs.flush()
    }

    actual fun loadApiBaseUrl(): String? {
        return prefs.get(KEY_API_BASE_URL, null)
    }

    actual fun saveDebugMode(enabled: Boolean) {
        prefs.putBoolean(KEY_DEBUG_MODE, enabled)
        prefs.flush()
    }

    actual fun loadDebugMode(): Boolean {
        return prefs.getBoolean(KEY_DEBUG_MODE, false)
    }

    actual fun saveHermodConsent(enabled: Boolean) {
        prefs.putBoolean(KEY_HERMOD_CONSENT, enabled)
        prefs.flush()
    }

    actual fun loadHermodConsent(): Boolean {
        return prefs.getBoolean(KEY_HERMOD_CONSENT, false)
    }

    actual fun saveMcpUsername(name: String) { prefs.put(KEY_MCP_USERNAME, name); prefs.flush() }
    actual fun loadMcpUsername(): String? = prefs.get(KEY_MCP_USERNAME, null)
    actual fun saveMcpPassword(password: String) { prefs.put(KEY_MCP_PASSWORD, password); prefs.flush() }
    actual fun loadMcpPassword(): String? = prefs.get(KEY_MCP_PASSWORD, null)
    actual fun saveZoneId(zone: String) { prefs.put(KEY_NATS_ZONE, zone); prefs.flush() }
    actual fun loadZoneId(): String? = prefs.get(KEY_NATS_ZONE, null)
    actual fun saveZonePasswords(blobJson: String) { prefs.put(KEY_ZONE_PASSWORDS, blobJson); prefs.flush() }
    actual fun loadZonePasswords(): String? = prefs.get(KEY_ZONE_PASSWORDS, null)
    actual fun saveZoneBank(blobJson: String) { prefs.put(KEY_ZONE_BANK, blobJson); prefs.flush() }
    actual fun loadZoneBank(): String? = prefs.get(KEY_ZONE_BANK, null)

    actual fun clearAuth() {
        prefs.remove(KEY_AUTH_TOKEN)
        prefs.remove(KEY_USER_ID)
        prefs.remove(KEY_USER_ROLE)
        prefs.flush()
    }

    actual fun clear() {
        prefs.remove(KEY_TOKEN)
        prefs.remove(KEY_SERVER_URL)
        prefs.remove(KEY_USERNAME)
        // Note: locale, companion name, and mode are NOT cleared on logout — user preferences, not credentials
        prefs.flush()
    }

    actual fun disconnectHomeZone() {
        // Drop only the home-zone relay leg; keep the local Study mirror + all else.
        // The SESSION token goes too — a disconnected phone holding a live zone
        // session token isn't logged out (2026-07-25).
        prefs.remove(KEY_RELAY_URL)
        prefs.remove(KEY_NATS_URL)
        prefs.remove(KEY_NATS_ZONE)
        prefs.remove(KEY_RELAY_TOKEN)
        prefs.remove(KEY_AUTH_TOKEN)
        prefs.put(KEY_APP_MODE, "local")
        prefs.flush()
    }

    private companion object {
        const val KEY_TOKEN = "wyrd_token"
        const val KEY_SERVER_URL = "wyrd_server_url"
        const val KEY_USERNAME = "wyrd_username"
        const val KEY_LOCALE = "wyrd_locale"
        const val KEY_COMPANION_NAME = "wyrd_companion_name"
        const val KEY_HOME_NAME = "wyrd_home_name"
        const val KEY_APP_MODE = "wyrd_app_mode"
        const val KEY_INFERENCE_URL = "wyrd_inference_url"
        const val KEY_PAIRING_TOKEN = "wyrd_pairing_token"
        const val KEY_HOUSEHOLD_ID = "wyrd_household_id"
        const val KEY_HOUSEHOLD_NAME = "wyrd_household_name"
        const val KEY_SERVER_DID = "wyrd_server_did"
        const val KEY_NATS_URL = "wyrd_nats_url"
        const val KEY_NATS_USER = "wyrd_nats_user"
        const val KEY_NATS_PASSWORD = "wyrd_nats_password"
        const val KEY_RELAY_FPS = "wyrd_relay_fps"
        const val KEY_RELAY_URL = "wyrd_relay_url"
        const val KEY_RELAY_TOKEN = "wyrd_relay_token"
        const val KEY_AUTH_TOKEN = "wyrd_auth_token"
        const val KEY_USER_ID = "wyrd_user_id"
        const val KEY_USER_ROLE = "wyrd_user_role"
        const val KEY_MCP_USERNAME = "wyrd_mcp_username"
        const val KEY_MCP_PASSWORD = "wyrd_mcp_password"
        const val KEY_NATS_ZONE = "wyrd_nats_zone"
        const val KEY_ZONE_PASSWORDS = "wyrd_zone_passwords"
        const val KEY_ZONE_BANK = "wyrd_zone_bank"
        const val KEY_API_KEY = "wyrd_api_key"
        const val KEY_API_PROVIDER = "wyrd_api_provider"
        const val KEY_PREFERRED_BACKING = "wyrd_preferred_backing"
        const val KEY_ON_DEVICE_MODEL_OPT_IN = "wyrd_on_device_model_opt_in"
        const val KEY_API_BASE_URL = "wyrd_api_base_url"
        const val KEY_DEBUG_MODE = "wyrd_debug_mode"
        const val KEY_HERMOD_CONSENT = "wyrd_hermod_consent"
    }
}
