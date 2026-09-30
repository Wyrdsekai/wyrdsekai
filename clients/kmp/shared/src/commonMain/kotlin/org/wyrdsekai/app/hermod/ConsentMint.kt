package org.wyrdsekai.app.hermod

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.wyrdsekai.app.network.HomeLink
import org.wyrdsekai.app.network.homeBaseUrl
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

    private val _paired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /**
     * Fires when a pairing door saved a login for the home's bus, so a running
     * node joins the bus now instead of at the next start.
     */
    val paired: SharedFlow<Unit> = _paired.asSharedFlow()

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
        val creds = store.homeBaseUrl()
            ?.let { PairingClient.pairSelf(it, session, label) }
            ?: RemoteMint.installed()?.pairDevice(session, label, "phone")
        if (creds != null) save(store, creds)
        return creds != null
    }

    /**
     * Persist minted credentials — the one place every pairing door (the
     * code ceremony, the session over HTTPS, the session over the relay)
     * saves what the home answered.
     *
     * The reply's `natsUrl` is the home's BUS as a phone reaches it on the
     * home network (wss://host:<bus port + 1>). It is kept as the bus address
     * only, never as the relay's: the relay comes from the invite alone, and
     * the reply carries none. A plain or loopback address is not kept at all
     * (an older home answered nats://127.0.0.1:4222, its own loopback).
     */
    fun save(store: TokenStore, c: PairingClient.PairingCredentials) {
        store.savePairingToken(c.token)
        store.saveHouseholdId(c.householdId)
        store.saveHouseholdName(c.householdName)
        store.saveServerDid(c.serverDid)
        HomeLink.usableHomeBus(c.natsUrl)?.let { store.saveHomeBusUrl(it) }
        val serverHost = HomeLink.hostOf(c.serverUrl)
        if (serverHost != null && !HomeLink.isDeviceLoopback(serverHost)) store.saveServerUrl(c.serverUrl)
        // This phone's own account on the home's bus (D3).
        if (!c.natsUser.isNullOrBlank() && !c.natsPass.isNullOrBlank()) {
            store.saveHomeNatsUser(c.natsUser)
            store.saveHomeNatsPassword(c.natsPass)
            _paired.tryEmit(Unit)
        }
    }
}
