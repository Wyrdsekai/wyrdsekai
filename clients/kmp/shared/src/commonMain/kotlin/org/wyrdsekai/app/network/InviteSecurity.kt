package org.wyrdsekai.app.network

import org.wyrdsekai.app.engine.discovery.PhoneInvite
import org.wyrdsekai.app.state.TokenStore

/**
 * What a pairing invite says about reaching the home safely (D1,
 * ): the home's tunnel key `zk`, and for its own
 * network the household CA fingerprint, HTTPS address and bus address. Every
 * place an invite is read (QR, link, paste) keeps these the same way.
 */
object InviteSecurity {

    fun remember(invite: PhoneInvite, store: TokenStore) {
        // An invite without a key clears an older one: the phone then asks to
        // pair again instead of sealing to a key this invite did not name.
        store.saveZoneKey(invite.zk ?: "")
        if (invite.homeCaFp != null && invite.lanHttps != null) {
            store.saveHomeCaFp(invite.homeCaFp)
            store.saveLanHttps(invite.lanHttps)
            invite.homeBus?.let { store.saveHomeBusUrl(it) }
        }
    }

    /**
     * Pins the household CA from `home_ca_fp` for the home's own addresses:
     * its HTTPS address and its bus ([HomeLink.homeBusUrl]), each as the
     * certificate that address serves whose SHA-256 is the invite's. Pins are
     * kept per address (host and port), so a relay on the same machine keeps
     * its own. False when an address is not reachable now; the node retries
     * at start.
     */
    suspend fun pinHome(lanHttps: String?, homeCaFp: String?, homeBus: String? = null): Boolean {
        if (lanHttps.isNullOrBlank() || homeCaFp.isNullOrBlank()) return false
        val (host, port) = parseWsHostPort(lanHttps) ?: return false
        val lanPinned = pinRelayFromInviteFingerprints(host, port, listOf(homeCaFp))
        val bus = HomeLink.homeBusUrl(homeBus, lanHttps)?.let { parseWsHostPort(it) }
        val busPinned = bus == null || pinRelayFromInviteFingerprints(bus.first, bus.second, listOf(homeCaFp))
        return lanPinned && busPinned
    }
}

/** The home's HTTP base for this phone ([HomeLink.homeBase]); null → use the relay. */
fun TokenStore.homeBaseUrl(): String? = HomeLink.homeBase(loadServerUrl(), loadLanHttps(), loadHomeCaFp())

/** The home's bus for this phone on the home network ([HomeLink.homeBusUrl]); null → not paired for it. */
fun TokenStore.homeBusUrl(): String? = HomeLink.homeBusUrl(loadHomeBusUrl(), loadLanHttps())

/** The relay leg: the invite's relay address only ([HomeLink.usableRelayUrl]); null → no relay. */
fun TokenStore.relayLegUrl(): String? = HomeLink.usableRelayUrl(loadRelayUrl())
