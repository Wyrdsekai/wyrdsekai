@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package org.wyrdsekai.app.state

import platform.Foundation.NSUserDefaults

actual class TokenStore actual constructor() {
    private val defaults = NSUserDefaults.standardUserDefaults

    actual fun saveToken(token: String) { defaults.setObject(token, forKey = KEY_TOKEN) }
    actual fun loadToken(): String? = defaults.stringForKey(KEY_TOKEN)
    actual fun saveServerUrl(url: String) { defaults.setObject(url, forKey = KEY_SERVER_URL) }
    actual fun loadServerUrl(): String? = defaults.stringForKey(KEY_SERVER_URL)
    actual fun saveUsername(username: String) { defaults.setObject(username, forKey = KEY_USERNAME) }
    actual fun loadUsername(): String? = defaults.stringForKey(KEY_USERNAME)
    actual fun saveLocale(locale: String) { defaults.setObject(locale, forKey = KEY_LOCALE) }
    actual fun loadLocale(): String? = defaults.stringForKey(KEY_LOCALE)
    actual fun saveCompanionName(name: String) { defaults.setObject(name, forKey = KEY_COMPANION_NAME) }
    actual fun loadCompanionName(): String? = defaults.stringForKey(KEY_COMPANION_NAME)
    actual fun saveHomeName(name: String) { defaults.setObject(name, forKey = KEY_HOME_NAME) }
    actual fun loadHomeName(): String? = defaults.stringForKey(KEY_HOME_NAME)
    actual fun saveMode(mode: String) { defaults.setObject(mode, forKey = KEY_APP_MODE) }
    actual fun loadMode(): String? = defaults.stringForKey(KEY_APP_MODE)
    actual fun saveInferenceUrl(url: String) { defaults.setObject(url, forKey = KEY_INFERENCE_URL) }
    actual fun loadInferenceUrl(): String? = defaults.stringForKey(KEY_INFERENCE_URL)
    actual fun savePairingToken(token: String) { defaults.setObject(token, forKey = KEY_PAIRING_TOKEN) }
    actual fun loadPairingToken(): String? = defaults.stringForKey(KEY_PAIRING_TOKEN)
    actual fun saveHouseholdId(id: String) { defaults.setObject(id, forKey = KEY_HOUSEHOLD_ID) }
    actual fun loadHouseholdId(): String? = defaults.stringForKey(KEY_HOUSEHOLD_ID)
    actual fun saveHouseholdName(name: String) { defaults.setObject(name, forKey = KEY_HOUSEHOLD_NAME) }
    actual fun loadHouseholdName(): String? = defaults.stringForKey(KEY_HOUSEHOLD_NAME)
    actual fun saveServerDid(did: String) { defaults.setObject(did, forKey = KEY_SERVER_DID) }
    actual fun loadServerDid(): String? = defaults.stringForKey(KEY_SERVER_DID)
    actual fun saveNatsUrl(url: String) { defaults.setObject(url, forKey = KEY_NATS_URL) }
    actual fun loadNatsUrl(): String? = defaults.stringForKey(KEY_NATS_URL)
    actual fun saveNatsUser(user: String) { defaults.setObject(user, forKey = KEY_NATS_USER) }
    actual fun loadNatsUser(): String? = defaults.stringForKey(KEY_NATS_USER)
    actual fun saveNatsPassword(password: String) { defaults.setObject(password, forKey = KEY_NATS_PASSWORD) }
    actual fun loadNatsPassword(): String? = defaults.stringForKey(KEY_NATS_PASSWORD)
    actual fun saveRelayFingerprints(fingerprints: String) { defaults.setObject(fingerprints, forKey = KEY_RELAY_FPS) }
    actual fun loadRelayFingerprints(): String? = defaults.stringForKey(KEY_RELAY_FPS)
    actual fun saveRelayUrl(url: String) { defaults.setObject(url, forKey = KEY_RELAY_URL) }
    actual fun loadRelayUrl(): String? = defaults.stringForKey(KEY_RELAY_URL)
    actual fun saveRelayToken(token: String) { defaults.setObject(token, forKey = KEY_RELAY_TOKEN) }
    actual fun loadRelayToken(): String? = defaults.stringForKey(KEY_RELAY_TOKEN)
    actual fun saveAuthToken(token: String) { defaults.setObject(token, forKey = KEY_AUTH_TOKEN) }
    actual fun loadAuthToken(): String? = defaults.stringForKey(KEY_AUTH_TOKEN)
    actual fun saveUserId(id: String) { defaults.setObject(id, forKey = KEY_USER_ID) }
    actual fun loadUserId(): String? = defaults.stringForKey(KEY_USER_ID)
    actual fun saveUserRole(role: String) { defaults.setObject(role, forKey = KEY_USER_ROLE) }
    actual fun loadUserRole(): String? = defaults.stringForKey(KEY_USER_ROLE)

    actual fun saveApiKey(key: String) { defaults.setObject(key, forKey = KEY_API_KEY) }
    actual fun loadApiKey(): String? = defaults.stringForKey(KEY_API_KEY)
    actual fun saveApiProvider(provider: String) { defaults.setObject(provider, forKey = KEY_API_PROVIDER) }
    actual fun loadApiProvider(): String? = defaults.stringForKey(KEY_API_PROVIDER)
    actual fun savePreferredBacking(backing: String) { defaults.setObject(backing, forKey = KEY_PREFERRED_BACKING) }
    actual fun loadPreferredBacking(): String? = defaults.stringForKey(KEY_PREFERRED_BACKING)
    actual fun saveOnDeviceModelOptIn(enabled: Boolean) { defaults.setBool(enabled, forKey = KEY_ON_DEVICE_MODEL_OPT_IN) }
    actual fun loadOnDeviceModelOptIn(): Boolean = defaults.boolForKey(KEY_ON_DEVICE_MODEL_OPT_IN)
    actual fun saveApiBaseUrl(url: String) { defaults.setObject(url, forKey = KEY_API_BASE_URL) }
    actual fun loadApiBaseUrl(): String? = defaults.stringForKey(KEY_API_BASE_URL)
    actual fun saveDebugMode(enabled: Boolean) { defaults.setBool(enabled, forKey = KEY_DEBUG_MODE) }
    actual fun loadDebugMode(): Boolean = defaults.boolForKey(KEY_DEBUG_MODE)
    actual fun saveHermodConsent(enabled: Boolean) { defaults.setBool(enabled, forKey = KEY_HERMOD_CONSENT) }
    actual fun loadHermodConsent(): Boolean = defaults.boolForKey(KEY_HERMOD_CONSENT)

    actual fun saveMcpUsername(name: String) { defaults.setObject(name, forKey = KEY_MCP_USERNAME) }
    actual fun loadMcpUsername(): String? = defaults.stringForKey(KEY_MCP_USERNAME)
    actual fun saveMcpPassword(password: String) { defaults.setObject(password, forKey = KEY_MCP_PASSWORD) }
    actual fun loadMcpPassword(): String? = defaults.stringForKey(KEY_MCP_PASSWORD)
    actual fun saveZoneId(zone: String) { defaults.setObject(zone, forKey = KEY_NATS_ZONE) }
    actual fun loadZoneId(): String? = defaults.stringForKey(KEY_NATS_ZONE)
    actual fun saveZonePasswords(blobJson: String) { defaults.setObject(blobJson, forKey = KEY_ZONE_PASSWORDS) }
    actual fun loadZonePasswords(): String? = defaults.stringForKey(KEY_ZONE_PASSWORDS)
    actual fun saveZoneBank(blobJson: String) { defaults.setObject(blobJson, forKey = KEY_ZONE_BANK) }
    actual fun loadZoneBank(): String? = defaults.stringForKey(KEY_ZONE_BANK)

    actual fun clearAuth() {
        defaults.removeObjectForKey(KEY_AUTH_TOKEN)
        defaults.removeObjectForKey(KEY_USER_ID)
        defaults.removeObjectForKey(KEY_USER_ROLE)
    }

    actual fun clear() {
        defaults.removeObjectForKey(KEY_TOKEN)
        defaults.removeObjectForKey(KEY_SERVER_URL)
        defaults.removeObjectForKey(KEY_USERNAME)
        // Note: locale, companion name, and mode are NOT cleared on logout — user preferences, not credentials
    }

    actual fun disconnectHomeZone() {
        // Drop only the home-zone relay leg; keep the local Study mirror + all else.
        // The SESSION token goes too — a disconnected phone holding a live zone
        // session token isn't logged out (2026-07-25).
        defaults.removeObjectForKey(KEY_RELAY_URL)
        defaults.removeObjectForKey(KEY_NATS_URL)
        defaults.removeObjectForKey(KEY_NATS_ZONE)
        defaults.removeObjectForKey(KEY_RELAY_TOKEN)
        defaults.removeObjectForKey(KEY_AUTH_TOKEN)
        defaults.setObject("local", forKey = KEY_APP_MODE)
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
        const val KEY_MCP_USERNAME = "wyrd_mcp_username"
        const val KEY_MCP_PASSWORD = "wyrd_mcp_password"
        const val KEY_NATS_ZONE = "wyrd_nats_zone"
        const val KEY_ZONE_PASSWORDS = "wyrd_zone_passwords"
        const val KEY_ZONE_BANK = "wyrd_zone_bank"
        const val KEY_USER_ID = "wyrd_user_id"
        const val KEY_USER_ROLE = "wyrd_user_role"
        const val KEY_API_KEY = "wyrd_api_key"
        const val KEY_API_PROVIDER = "wyrd_api_provider"
        const val KEY_PREFERRED_BACKING = "wyrd_preferred_backing"
        const val KEY_ON_DEVICE_MODEL_OPT_IN = "wyrd_on_device_model_opt_in"
        const val KEY_API_BASE_URL = "wyrd_api_base_url"
        const val KEY_DEBUG_MODE = "wyrd_debug_mode"
        const val KEY_HERMOD_CONSENT = "wyrd_hermod_consent"
    }
}
