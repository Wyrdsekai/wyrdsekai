package org.wyrdsekai.app.network

import org.wyrdsekai.app.engine.discovery.PhoneInvite

/**
 * Android [addInviteToBank]: parse a `wyrdphone://` invite into a held relay + a
 * zone bank entry and persist both via [ZoneBankStore]. The account username is
 * left blank — the Servers screen captures it at first sign-in (the invite is the
 * relay/zone trust decision, not an account). Best-effort: a malformed invite or
 * one with no relays returns false.
 */
actual fun addInviteToBank(inviteUrl: String): Boolean {
    if (!PhoneInvite.isPhoneInviteUrl(inviteUrl)) return false
    val invite = runCatching { PhoneInvite.parse(inviteUrl) }.getOrNull() ?: return false
    val relay = invite.relays.firstOrNull() ?: return false
    val zoneId = invite.zoneId?.takeIf { it.isNotBlank() } ?: return false

    val now = System.currentTimeMillis()
    val bank = ZoneBankStore().load()
    bank.addRelay(
        HeldRelay(
            wsUrl = relay.wsUrl,
            caFp = relay.caFp ?: relay.fp,
            natsUser = relay.natsUser,
            natsPass = relay.natsPassword,
            addedAt = now,
        )
    )
    bank.addOrUpdateZone(
        ZoneBankEntry(
            zoneId = zoneId,
            displayName = zoneId,
            relayUrls = listOf(relay.wsUrl),
            username = "",
            addedAt = now,
        )
    )
    return true
}
