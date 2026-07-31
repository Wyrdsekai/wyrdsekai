package org.wyrdsekai.app.network

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * One-line event bus for TLS trust-state changes that the UI needs to react
 * to. The TLS layer can't synchronously prompt the user (it runs on an
 * OkHttp dispatcher thread, no Compose context); it just publishes here.
 * UI code observes the flow and shows a re-TOFU dialog.
 *
 * Buffered so events emitted before the UI is composing aren't dropped.
 *
 */
object TrustEventBus {
  private val _events = MutableSharedFlow<TrustEvent>(
    replay = 1,
    extraBufferCapacity = 16,
  )
  val events: SharedFlow<TrustEvent> = _events.asSharedFlow()

  fun publish(event: TrustEvent) {
    _events.tryEmit(event)
  }
}

sealed class TrustEvent {
  /**
   * The peer presented a chain that does NOT validate against the
   * stored pin for this host. UI should ask the user whether to re-trust
   * the new fingerprint (cert rotation case).
   */
  data class PinMismatch(
    val host: String,
    val newFingerprint: String,
    val pinnedFingerprint: String?,
  ) : TrustEvent()
}
