package org.wyrdsekai.app.engine.transit

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.wyrdsekai.app.engine.between.BetweenClient

/**
 * process-wide handle to a connected relay
 * [BetweenClient] (raw NATS pub/sub over the household relay).
 *
 * Set by NodeManager (per-platform) when the relay leg comes up in relay-login
 * mode. `WyrdApp` reads it to build a [RelayTunnelServerConnection] and hand it
 * to `LocalRoomScreen` as the session transport, so the phone terminal tunnels
 * a FULL session to the real zone instead of driving the in-process node.
 *
 * Empty (null) ⇒ no relay tunnel available ⇒ the terminal falls back to the
 * offline [LocalServerConnection]. Mirrors
 * [org.wyrdsekai.app.network.ServerClientHolder].
 */
object RelayTunnelHolder {
    private val _between = MutableStateFlow<BetweenClient?>(null)
    val between: StateFlow<BetweenClient?> = _between

    fun set(bc: BetweenClient?) { _between.value = bc }
    fun get(): BetweenClient? = _between.value
    fun clear() { _between.value = null }
}
