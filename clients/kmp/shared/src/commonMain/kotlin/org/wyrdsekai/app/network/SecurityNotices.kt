package org.wyrdsekai.app.network

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Connection-security events the person must see: a pinned certificate that no
 * longer matches, or a pairing from before encryption that has to be redone.
 * The TLS and transport layers publish here (they run off the UI thread, with
 * no way to show anything); WyrdApp shows each as a plain message. None of them
 * offers to connect anyway (D6, ).
 *
 * Buffered so events emitted before the UI is composing aren't dropped.
 */
object SecurityNotices {
    private val _events = MutableSharedFlow<SecurityNotice>(
        replay = 1,
        extraBufferCapacity = 16,
    )
    val events: SharedFlow<SecurityNotice> = _events.asSharedFlow()

    fun publish(notice: SecurityNotice) {
        _events.tryEmit(notice)
    }

    /** The person has seen the last notice; do not show it again to a new collector. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun clear() {
        _events.resetReplayCache()
    }
}

sealed interface SecurityNotice {
    /** The certificate [host] presented does not validate against the pin from pairing. */
    data class PinMismatch(val host: String) : SecurityNotice

    /** The phone has no home tunnel key (paired before 0.5.0): the relay path is closed until it pairs again. */
    data object RepairForTunnel : SecurityNotice

    /** Paired before home TLS: the relay is used; pair again for direct home-network access. Shown once. */
    data object RepairForHomeNetwork : SecurityNotice
}
