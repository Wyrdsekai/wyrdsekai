package org.wyrdsekai.app.network

/** iOS phone-zone-bank wiring lives in the RN client; KMP iOS is a no-op here. */
actual fun addInviteToBank(inviteUrl: String): Boolean = false
