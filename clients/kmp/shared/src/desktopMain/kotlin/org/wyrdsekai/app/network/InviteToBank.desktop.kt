package org.wyrdsekai.app.network

/** Desktop has no phone zone bank — no-op. */
actual fun addInviteToBank(inviteUrl: String): Boolean = false
