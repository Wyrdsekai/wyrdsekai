package org.wyrdsekai.app.hermod

import org.wyrdsekai.app.network.PairingClient
import org.wyrdsekai.app.platform.secureRandomBytes
import org.wyrdsekai.app.state.TokenStore

/**
 * The consent moment is the identity moment — many doors, one identity.
 * When someone says "lend compute", this device needs a wyrd_dev_ row in
 * the household registry. The doors, in order of silence:
 *   1. an authenticated session over LAN HTTP (POST /api/pair/device),
 *   2. the same session over the relay's NATS (RemoteMint seam),
 *   3. the classic 6-digit steward ceremony (the CALLER runs the UI for
 *      this one — see NodeSettingsDialog / FirstRunScreen).
 * All three end in the same registry row; a bare session never enters
 * the capability plane.
 */
object ConsentMint {

    /** One random label per mint: two phones on one account never collide. */
    fun freshLabel(): String = "phone-" + secureRandomBytes(3)
        .joinToString("") { b -> (b.toInt() and 0xff).toString(16).padStart(2, '0') }

    /**
     * Try the SILENT doors (session over HTTP, then over the relay).
     * Returns true when a device identity exists afterwards. False means
     * the caller should offer the ceremony — or wait for a door that can.
     */
    suspend fun mintWithSession(store: TokenStore): Boolean {
        if (!store.loadPairingToken().isNullOrBlank()) return true
        // Either session family: loadAuthToken is the post-pairing login
        // path, loadToken the account path — both validate server-side.
        val session = store.loadAuthToken()?.takeIf { it.isNotBlank() }
            ?: store.loadToken()
        if (session.isNullOrBlank()) return false
        val label = freshLabel()
        val creds = store.loadServerUrl()?.takeIf { it.isNotBlank() }
            ?.let { PairingClient.pairSelf(it, session, label) }
            ?: RemoteMint.installed()?.pairDevice(session, label, "phone")
        if (creds != null) save(store, creds)
        return creds != null
    }

    /** Persist minted credentials — identical to what the ceremony saves. */
    fun save(store: TokenStore, c: PairingClient.PairingCredentials) {
        store.savePairingToken(c.token)
        store.saveHouseholdId(c.householdId)
        store.saveHouseholdName(c.householdName)
        store.saveServerDid(c.serverDid)
        store.saveNatsUrl(c.natsUrl)
        store.saveServerUrl(c.serverUrl)
    }
}
