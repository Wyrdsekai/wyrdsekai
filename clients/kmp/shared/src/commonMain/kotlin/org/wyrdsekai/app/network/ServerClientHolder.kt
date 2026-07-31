package org.wyrdsekai.app.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-wide singleton for the phone's authenticated server client.
 *
 * Set by NodeManager (per-platform) after a successful probe + login on the
 * household relay. The instance is a [PhoneRemoteClient] so screens see the
 * same surface (`tell`, `doCommand`) regardless of whether the transport is
 * NATS WebSocket+TLS (the Phase 5+ path) or the legacy HTTP REST shim
 * (pre-Phase 6 holdover; deletable when Phase 6 lands).
 *
 * The StateFlow lets Compose recompose when the client becomes available
 * (mid-flight probe completes), but reads from `value` are cheap and safe
 * outside composition too.
 */
object ServerClientHolder {
    private val _client = MutableStateFlow<PhoneRemoteClient?>(null)
    val client: StateFlow<PhoneRemoteClient?> = _client

    fun set(c: PhoneRemoteClient?) {
        _client.value = c
    }

    fun get(): PhoneRemoteClient? = _client.value

    /** Clear on logout / app reset. */
    fun clear() {
        _client.value = null
    }
}
