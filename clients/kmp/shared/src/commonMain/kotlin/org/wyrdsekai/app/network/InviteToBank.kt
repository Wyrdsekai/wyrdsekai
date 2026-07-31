package org.wyrdsekai.app.network

/**
 * addInviteToBank — populate the zone bank from a pasted/scanned `wyrdphone://`
 * invite, the KMP analogue of the RN `addInviteToBank`.
 * Adds the invite's relay as a held relay and the zone as a bank entry, persisting
 * both. Returns true if a zone entry was added/updated.
 *
 * androidMain implements it (it needs PhoneInvite + ZoneBankStore); other targets
 * return false (no-op). commonMain WyrdApp calls it from the welcome flow so the
 * "My servers" surface accrues every zone the user is invited to.
 */
expect fun addInviteToBank(inviteUrl: String): Boolean
