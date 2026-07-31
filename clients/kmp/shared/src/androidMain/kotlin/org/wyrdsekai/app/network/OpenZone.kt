package org.wyrdsekai.app.network

/**
 * OpenZone — the orchestration behind "tap a server in your bank"
 * KMP (Android) parity with the RN `openZone.ts`.
 * The Servers screen calls this; the testable policy lives here, the screen
 * stays thin.
 *
 * Flow:
 *   1. Resolve the held relays that reach the zone (bank.relaysForZone).
 *   2. Resolve the password: explicit arg > the per-device remembered one
 *      (via [ZonePasswordStore], never synced). None → NeedsPassword.
 *   3. Auto-attempt the login across the relays (ZoneConnect).
 *   4. On success: remember the password (this device), bump the winning relay
 *      to the front, touch lastUsedAt, and best-effort sync the bank (§4).
 */

/** Per-device password storage for a zone — NEVER synced (§4.4). Backed by
 *  TokenStore on Android; injected so this logic stays platform-agnostic. */
interface ZonePasswordStore {
    fun getPassword(zoneId: String): String?
    fun setPassword(zoneId: String, password: String)
    fun forgetPassword(zoneId: String)
}

sealed interface OpenZoneResult {
    data class Ok(val client: NatsServerClient, val relayUrl: String) : OpenZoneResult
    data object NeedsPassword : OpenZoneResult
    data class AuthRejected(val error: String) : OpenZoneResult
    data class Unreachable(val error: String) : OpenZoneResult
}

sealed interface CreateAccountResult {
    data class Ok(
        val client: NatsServerClient,
        val relayUrl: String,
        val role: String?,
        /** ONE-TIME password-reset credential for the first/steward account —
         *  the caller MUST show it; it is never available again. */
        val recoveryKey: String?,
    ) : CreateAccountResult
    /** Household is invite-only — collect a steward invite code and retry. */
    data class RegistrationClosed(val error: String) : CreateAccountResult
    data class Rejected(val error: String) : CreateAccountResult
    data class Unreachable(val error: String) : CreateAccountResult
}

object OpenZone {

    /**
     * @param now monotonic-ish timestamp (System.currentTimeMillis()); passed in
     *            so the policy stays deterministic/testable.
     * @param sync when true and login succeeds, the connected client (now
     *            authenticated) syncs the bank best-effort (§4). Never blocks.
     */
    suspend fun openZone(
        bank: ZoneBank,
        zoneId: String,
        passwords: ZonePasswordStore,
        now: Long,
        explicitPassword: String? = null,
        sync: Boolean = true,
    ): OpenZoneResult {
        val zone = bank.getZone(zoneId)
            ?: return OpenZoneResult.Unreachable("That server is not in your bank.")
        val relays = bank.relaysForZone(zoneId)

        val password = explicitPassword ?: passwords.getPassword(zoneId)
        ?: return OpenZoneResult.NeedsPassword

        return when (val res = ZoneConnect.connectToZone(zone, relays, password)) {
            is ZoneConnectResult.Ok -> {
                // Success — remember the password on THIS device, learn the relay.
                passwords.setPassword(zoneId, password)
                bank.bumpRelay(zoneId, res.relayUrl)
                bank.touchZone(zoneId, now)
                // §4 — sync the bank across the user's devices, best-effort. The
                // freshly-authenticated client IS the sync transport.
                if (sync) {
                    try { ZoneBankSync.syncZoneBank(bank, res.client, now) } catch (_: Exception) { /* never blocks */ }
                }
                OpenZoneResult.Ok(res.client, res.relayUrl)
            }
            is ZoneConnectResult.Error ->
                if (res.authRejected) OpenZoneResult.AuthRejected(res.error)
                else OpenZoneResult.Unreachable(res.error)
        }
    }

    /**
     * Create a NAMED account on a banked zone over the relay — the phone-first
     * onboarding path (2026-07-23, parity with RN createZoneAccount). Same
     * relay-attempt ladder and post-success persistence as [openZone], but
     * step 3 is auth.register (or auth.redeem when the household is
     * invite-only and the user holds a steward code). On a fresh household
     * the first registrant becomes the steward and gets a one-time
     * recoveryKey — the caller MUST show it.
     */
    suspend fun createAccount(
        bank: ZoneBank,
        zoneId: String,
        passwords: ZonePasswordStore,
        now: Long,
        username: String,
        password: String,
        inviteCode: String? = null,
        sync: Boolean = true,
    ): CreateAccountResult {
        val zone = bank.getZone(zoneId)
            ?: return CreateAccountResult.Unreachable("That server is not in your bank.")
        val relays = bank.relaysForZone(zoneId)

        var role: String? = null
        var recoveryKey: String? = null
        val res = ZoneConnect.connectToZoneWith(zone, relays) { client ->
            val named = if (inviteCode.isNullOrBlank()) {
                client.registerNamed(username, password)
            } else {
                client.redeemNamed(inviteCode, username, password)
            }
            role = named.role
            recoveryKey = named.recoveryKey
            named.auth
        }

        return when (res) {
            is ZoneConnectResult.Ok -> {
                // Bank the username the account was created under, then the
                // same session persistence as a login.
                bank.addOrUpdateZone(zone.copy(username = username))
                passwords.setPassword(zoneId, password)
                bank.bumpRelay(zoneId, res.relayUrl)
                bank.touchZone(zoneId, now)
                if (sync) {
                    try { ZoneBankSync.syncZoneBank(bank, res.client, now) } catch (_: Exception) { /* never blocks */ }
                }
                CreateAccountResult.Ok(res.client, res.relayUrl, role, recoveryKey)
            }
            is ZoneConnectResult.Error ->
                if (res.authRejected && res.error.contains("registration_closed", ignoreCase = true)) {
                    CreateAccountResult.RegistrationClosed(
                        "This household is invite-only — ask the steward for an invite code " +
                        "(minted from the invitation scroll in their Study).")
                } else if (res.authRejected) {
                    CreateAccountResult.Rejected(res.error)
                } else {
                    CreateAccountResult.Unreachable(res.error)
                }
        }
    }
}
