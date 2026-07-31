package org.wyrdsekai.app.state

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.security.KeyStore

actual class TokenStore actual constructor() {
    private val prefs: SharedPreferences
        get() = encryptedPrefs(appContext!!)

    actual fun saveToken(token: String) {
        prefs.edit().putString(KEY_TOKEN, token).apply()
    }

    actual fun loadToken(): String? {
        return prefs.getString(KEY_TOKEN, null)
    }

    actual fun saveServerUrl(url: String) {
        prefs.edit().putString(KEY_SERVER_URL, url).apply()
    }

    actual fun loadServerUrl(): String? {
        return prefs.getString(KEY_SERVER_URL, null)
    }

    actual fun saveUsername(username: String) {
        prefs.edit().putString(KEY_USERNAME, username).apply()
    }

    actual fun loadUsername(): String? {
        return prefs.getString(KEY_USERNAME, null)
    }

    actual fun saveLocale(locale: String) {
        prefs.edit().putString(KEY_LOCALE, locale).apply()
    }

    actual fun loadLocale(): String? {
        return prefs.getString(KEY_LOCALE, null)
    }

    actual fun saveCompanionName(name: String) {
        prefs.edit().putString(KEY_COMPANION_NAME, name).apply()
    }

    actual fun loadCompanionName(): String? {
        return prefs.getString(KEY_COMPANION_NAME, null)
    }

    actual fun saveHomeName(name: String) {
        prefs.edit().putString(KEY_HOME_NAME, name).apply()
    }

    actual fun loadHomeName(): String? {
        return prefs.getString(KEY_HOME_NAME, null)
    }

    actual fun saveMode(mode: String) {
        prefs.edit().putString(KEY_APP_MODE, mode).apply()
    }

    actual fun loadMode(): String? {
        return prefs.getString(KEY_APP_MODE, null)
    }

    actual fun saveInferenceUrl(url: String) {
        prefs.edit().putString(KEY_INFERENCE_URL, url).apply()
    }

    actual fun loadInferenceUrl(): String? {
        return prefs.getString(KEY_INFERENCE_URL, null)
    }

    actual fun savePairingToken(token: String) {
        prefs.edit().putString(KEY_PAIRING_TOKEN, token).apply()
    }

    actual fun loadPairingToken(): String? {
        return prefs.getString(KEY_PAIRING_TOKEN, null)
    }

    actual fun saveHouseholdId(id: String) {
        prefs.edit().putString(KEY_HOUSEHOLD_ID, id).apply()
    }

    actual fun loadHouseholdId(): String? {
        return prefs.getString(KEY_HOUSEHOLD_ID, null)
    }

    actual fun saveHouseholdName(name: String) {
        prefs.edit().putString(KEY_HOUSEHOLD_NAME, name).apply()
    }

    actual fun loadHouseholdName(): String? {
        return prefs.getString(KEY_HOUSEHOLD_NAME, null)
    }

    actual fun saveServerDid(did: String) {
        prefs.edit().putString(KEY_SERVER_DID, did).apply()
    }

    actual fun loadServerDid(): String? {
        return prefs.getString(KEY_SERVER_DID, null)
    }

    actual fun saveNatsUrl(url: String) {
        prefs.edit().putString(KEY_NATS_URL, url).apply()
    }

    actual fun loadNatsUrl(): String? {
        return prefs.getString(KEY_NATS_URL, null)
    }

    actual fun saveNatsUser(user: String) {
        prefs.edit().putString(KEY_NATS_USER, user).apply()
    }

    actual fun loadNatsUser(): String? {
        return prefs.getString(KEY_NATS_USER, null)
    }

    actual fun saveNatsPassword(password: String) {
        prefs.edit().putString(KEY_NATS_PASSWORD, password).apply()
    }

    actual fun loadNatsPassword(): String? {
        return prefs.getString(KEY_NATS_PASSWORD, null)
    }

    actual fun saveRelayFingerprints(fingerprints: String) {
        prefs.edit().putString(KEY_RELAY_FPS, fingerprints).apply()
    }

    actual fun loadRelayFingerprints(): String? {
        return prefs.getString(KEY_RELAY_FPS, null)
    }

    actual fun saveRelayUrl(url: String) {
        prefs.edit().putString(KEY_RELAY_URL, url).apply()
    }

    actual fun loadRelayUrl(): String? {
        return prefs.getString(KEY_RELAY_URL, null)
    }

    actual fun saveRelayToken(token: String) {
        prefs.edit().putString(KEY_RELAY_TOKEN, token).apply()
    }

    actual fun loadRelayToken(): String? {
        return prefs.getString(KEY_RELAY_TOKEN, null)
    }

    actual fun saveAuthToken(token: String) {
        prefs.edit().putString(KEY_AUTH_TOKEN, token).apply()
    }

    actual fun loadAuthToken(): String? {
        return prefs.getString(KEY_AUTH_TOKEN, null)
    }

    actual fun saveUserId(id: String) {
        prefs.edit().putString(KEY_USER_ID, id).apply()
    }

    actual fun loadUserId(): String? {
        return prefs.getString(KEY_USER_ID, null)
    }

    actual fun saveUserRole(role: String) {
        prefs.edit().putString(KEY_USER_ROLE, role).apply()
    }

    actual fun loadUserRole(): String? {
        return prefs.getString(KEY_USER_ROLE, null)
    }

    actual fun saveApiKey(key: String) {
        prefs.edit().putString(KEY_API_KEY, key).apply()
    }

    actual fun loadApiKey(): String? {
        return prefs.getString(KEY_API_KEY, null)
    }

    actual fun saveApiProvider(provider: String) {
        prefs.edit().putString(KEY_API_PROVIDER, provider).apply()
    }

    actual fun loadApiProvider(): String? {
        return prefs.getString(KEY_API_PROVIDER, null)
    }

    actual fun savePreferredBacking(backing: String) {
        prefs.edit().putString(KEY_PREFERRED_BACKING, backing).apply()
    }

    actual fun loadPreferredBacking(): String? {
        return prefs.getString(KEY_PREFERRED_BACKING, null)
    }

    actual fun saveOnDeviceModelOptIn(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ON_DEVICE_MODEL_OPT_IN, enabled).apply()
    }

    actual fun loadOnDeviceModelOptIn(): Boolean {
        return prefs.getBoolean(KEY_ON_DEVICE_MODEL_OPT_IN, false)
    }

    actual fun saveApiBaseUrl(url: String) {
        prefs.edit().putString(KEY_API_BASE_URL, url).apply()
    }

    actual fun loadApiBaseUrl(): String? {
        return prefs.getString(KEY_API_BASE_URL, null)
    }

    actual fun saveDebugMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DEBUG_MODE, enabled).apply()
    }

    actual fun loadDebugMode(): Boolean {
        return prefs.getBoolean(KEY_DEBUG_MODE, false)
    }

    actual fun saveMcpUsername(name: String) {
        prefs.edit().putString(KEY_MCP_USERNAME, name).apply()
    }

    actual fun loadMcpUsername(): String? {
        return prefs.getString(KEY_MCP_USERNAME, null)
    }

    actual fun saveMcpPassword(password: String) {
        prefs.edit().putString(KEY_MCP_PASSWORD, password).apply()
    }

    actual fun loadMcpPassword(): String? {
        return prefs.getString(KEY_MCP_PASSWORD, null)
    }

    actual fun saveZoneId(zone: String) {
        prefs.edit().putString(KEY_NATS_ZONE, zone).apply()
    }

    actual fun loadZoneId(): String? {
        return prefs.getString(KEY_NATS_ZONE, null)
    }

    actual fun saveZonePasswords(blobJson: String) {
        prefs.edit().putString(KEY_ZONE_PASSWORDS, blobJson).apply()
    }

    actual fun loadZonePasswords(): String? {
        return prefs.getString(KEY_ZONE_PASSWORDS, null)
    }

    actual fun saveZoneBank(blobJson: String) {
        prefs.edit().putString(KEY_ZONE_BANK, blobJson).apply()
    }

    actual fun loadZoneBank(): String? {
        return prefs.getString(KEY_ZONE_BANK, null)
    }

    actual fun clearAuth() {
        prefs.edit()
            .remove(KEY_AUTH_TOKEN)
            .remove(KEY_USER_ID)
            .remove(KEY_USER_ROLE)
            .apply()
    }

    actual fun clear() {
        prefs.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_SERVER_URL)
            .remove(KEY_USERNAME)
            // Note: locale, companion name, and mode are NOT cleared on logout — user preferences, not credentials
            .apply()
    }

    actual fun disconnectHomeZone() {
        // Drop only the home-zone relay leg; keep the local Study (the last-synced
        // mirror), soul, and everything else. The phone runs local-only afterwards.
        // The SESSION token goes too — a disconnected phone holding a live zone
        // session token isn't logged out (2026-07-25).
        prefs.edit()
            .remove(KEY_RELAY_URL)
            .remove(KEY_NATS_URL)
            .remove(KEY_NATS_ZONE)
            .remove(KEY_RELAY_TOKEN)
            .remove(KEY_AUTH_TOKEN)
            .putString(KEY_APP_MODE, "local")
            .apply()
    }

    companion object {
        private var appContext: Context? = null

        @Volatile
        private var cachedPrefs: SharedPreferences? = null

        fun init(context: Context) {
            val app = context.applicationContext
            appContext = app
            // Hard-cutover: the v1 plaintext prefs file is now obsolete. Drop it
            // so old tokens can't be recovered via `adb backup` or root. Anyone
            // upgrading must re-log in.
            try {
                app.deleteSharedPreferences(LEGACY_PREFS_NAME)
            } catch (_: Throwable) {
                // older SDKs / unusual fs — best-effort.
            }
            // Eagerly warm up encrypted prefs in init so the first call from a
            // UI path doesn't pay the Keystore-init cost on the main thread.
            encryptedPrefs(app)
            // Debug-only: e2e scripts seed credentials via a plaintext file at
            // /data/data/<pkg>/shared_prefs/wyrdsekai_prefs_seed.xml. On launch
            // we import any keys present, then delete the seed file so the
            // plaintext doesn't linger.
            if (isDebuggable(app)) {
                maybeImportSeedPrefs(app)
            }
        }

        private fun encryptedPrefs(ctx: Context): SharedPreferences {
            cachedPrefs?.let { return it }
            synchronized(this) {
                cachedPrefs?.let { return it }
                val sp = try {
                    createEncryptedPrefs(ctx)
                } catch (e: Throwable) {
                    // Reinstall-robustness (2026-07-19): EncryptedSharedPreferences
                    // throws AEADBadTagException / GeneralSecurityException when the
                    // Android Keystore master key can no longer decrypt a keyset left
                    // by a PRIOR install (the classic "app crashes in onCreate after
                    // --install" bug — the encrypted-prefs file survives the reinstall
                    // but the Keystore key was regenerated/invalidated). Recover by
                    // wiping the corrupted prefs file + master key and recreating
                    // fresh. Cost: the user re-logs in — those tokens were
                    // undecryptable anyway, so nothing recoverable is lost.
                    Log.w("TokenStore", "EncryptedSharedPreferences unreadable "
                        + "(${e.javaClass.simpleName}: ${e.message}) — resetting secure store", e)
                    resetSecureStore(ctx)
                    createEncryptedPrefs(ctx)
                }
                cachedPrefs = sp
                return sp
            }
        }

        private fun createEncryptedPrefs(ctx: Context): SharedPreferences {
            val key = MasterKey.Builder(ctx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                ctx,
                PREFS_NAME,
                key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }

        /** Wipe the corrupted encrypted prefs + Keystore master key so a fresh
         *  keyset can be created. Best-effort at every step. */
        private fun resetSecureStore(ctx: Context) {
            try { ctx.deleteSharedPreferences(PREFS_NAME) } catch (_: Throwable) {}
            // deleteSharedPreferences can no-op on some OEM builds — remove the
            // backing xml directly too.
            try {
                File(ctx.filesDir?.parentFile, "shared_prefs/$PREFS_NAME.xml").delete()
            } catch (_: Throwable) {}
            // Drop the invalidated Keystore master key so MasterKey.Builder mints
            // a new one instead of reusing the un-decryptable entry.
            try {
                val ks = KeyStore.getInstance("AndroidKeyStore")
                ks.load(null)
                if (ks.containsAlias(MasterKey.DEFAULT_MASTER_KEY_ALIAS)) {
                    ks.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
                }
            } catch (_: Throwable) {}
        }

        private fun isDebuggable(ctx: Context): Boolean {
            return (ctx.applicationInfo.flags and
                android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        }

        /**
         * E2E test seed path. The probe runner writes a plaintext
         * `wyrdsekai_prefs_seed.xml` under shared_prefs/ with the values it
         * wants the app to launch with. We copy them into the encrypted
         * store, then drop the plaintext file. Debug builds only.
         */
        private fun maybeImportSeedPrefs(ctx: Context) {
            val seedFile = java.io.File(
                ctx.applicationInfo.dataDir, "shared_prefs/$SEED_PREFS_NAME.xml"
            )
            if (!seedFile.exists()) return
            val seed = ctx.getSharedPreferences(SEED_PREFS_NAME, Context.MODE_PRIVATE)
            val all = seed.all
            if (all.isEmpty()) return
            val ep = encryptedPrefs(ctx)
            val ed = ep.edit()
            for ((k, v) in all) {
                when (v) {
                    is String -> ed.putString(k, v)
                    is Boolean -> ed.putBoolean(k, v)
                    is Int -> ed.putInt(k, v)
                    is Long -> ed.putLong(k, v)
                    is Float -> ed.putFloat(k, v)
                    else -> { /* skip unknown types */ }
                }
            }
            ed.apply()
            // Wipe seed values + drop file.
            seed.edit().clear().apply()
            try { ctx.deleteSharedPreferences(SEED_PREFS_NAME) } catch (_: Throwable) {}
        }

        private const val LEGACY_PREFS_NAME = "wyrdsekai_prefs"
        private const val PREFS_NAME = "wyrdsekai_prefs_enc"
        private const val SEED_PREFS_NAME = "wyrdsekai_prefs_seed"
        private const val KEY_TOKEN = "wyrd_token"
        private const val KEY_SERVER_URL = "wyrd_server_url"
        private const val KEY_USERNAME = "wyrd_username"
        private const val KEY_LOCALE = "wyrd_locale"
        private const val KEY_COMPANION_NAME = "wyrd_companion_name"
        private const val KEY_HOME_NAME = "wyrd_home_name"
        private const val KEY_APP_MODE = "wyrd_app_mode"
        private const val KEY_INFERENCE_URL = "wyrd_inference_url"
        private const val KEY_PAIRING_TOKEN = "wyrd_pairing_token"
        private const val KEY_HOUSEHOLD_ID = "wyrd_household_id"
        private const val KEY_HOUSEHOLD_NAME = "wyrd_household_name"
        private const val KEY_SERVER_DID = "wyrd_server_did"
        private const val KEY_NATS_URL = "wyrd_nats_url"
        private const val KEY_NATS_USER = "wyrd_nats_user"
        private const val KEY_NATS_PASSWORD = "wyrd_nats_password"
        private const val KEY_RELAY_FPS = "wyrd_relay_fps"
        private const val KEY_RELAY_URL = "wyrd_relay_url"
        private const val KEY_RELAY_TOKEN = "wyrd_relay_token"
        private const val KEY_AUTH_TOKEN = "wyrd_auth_token"
        private const val KEY_USER_ID = "wyrd_user_id"
        private const val KEY_USER_ROLE = "wyrd_user_role"
        private const val KEY_MCP_USERNAME = "wyrd_mcp_username"
        private const val KEY_MCP_PASSWORD = "wyrd_mcp_password"
        // Cached NATS zone scope — see commonMain TokenStore.saveZoneId/loadZoneId
        // for context. Also recognized in the e2e seed-prefs import path.
        private const val KEY_NATS_ZONE = "wyrd_nats_zone"
        private const val KEY_ZONE_PASSWORDS = "wyrd_zone_passwords"
        private const val KEY_ZONE_BANK = "wyrd_zone_bank"
        private const val KEY_API_KEY = "wyrd_api_key"
        private const val KEY_API_PROVIDER = "wyrd_api_provider"
        private const val KEY_PREFERRED_BACKING = "wyrd_preferred_backing"
        private const val KEY_ON_DEVICE_MODEL_OPT_IN = "wyrd_on_device_model_opt_in"
        private const val KEY_API_BASE_URL = "wyrd_api_base_url"
        private const val KEY_DEBUG_MODE = "wyrd_debug_mode"
    }
}
