package org.wyrdsekai.app.hermod

import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.wyrdsekai.app.inference.LocalInferenceProvider

/**
 * The phone's half of one open door, independent of transport: heartbeat
 * the device's battery-truth, answer knocks through HermodEngine, stop
 * the moment policy withdraws. LAN WebSocket and relay tunnel both run
 * EXACTLY this — roaming changes the pipe, never the protocol, which is
 * what makes home↔away seamless for the zone (same identity, same
 * frames, same admission).
 */
class DoorSession(
    local: LocalInferenceProvider,
    private val models: () -> List<String>,
    private val policy: () -> HermodListener.HermodPolicy,
    private val capabilityClass: String,
    private val heartbeatMillis: Long,
) {
    private val engine = HermodEngine(local)

    /**
     * Pump [inbound] frames until the pipe closes or policy withdraws
     * (in which case [close] is invoked and the pipe drains). Returns
     * true when the session ended because policy withdrew — the caller
     * then waits for eligibility instead of reconnecting.
     */
    suspend fun run(
        inbound: ReceiveChannel<String>,
        send: suspend (String) -> Unit,
        close: suspend () -> Unit,
    ): Boolean = coroutineScope {
        var withdrew = false
        val beat = launch {
            while (isActive) {
                val p = policy()
                if (!p.eligible) {
                    // Silence is how consent is exercised: close the pipe,
                    // the advertisement ages out of every table by TTL.
                    withdrew = true
                    runCatching { close() }
                    return@launch
                }
                val sent = runCatching {
                    send(encodeHermod(HermodMessage.Heartbeat(
                        capabilityClass = capabilityClass,
                        models = models(),
                        residentDataDomains = emptyList(),
                        charging = p.charging,
                        idle = p.idle,
                    )))
                }
                if (sent.isFailure) return@launch
                delay(heartbeatMillis)
            }
        }
        try {
            for (frame in inbound) {
                when (val msg = decodeHermod(frame)) {
                    is HermodMessage.Knock -> launch {
                        // Child coroutine: a long errand never blocks the
                        // next frame (or the heartbeat).
                        val answer = engine.answer(msg, policy().eligible)
                        runCatching { send(encodeHermod(answer)) }
                    }
                    else -> Unit // hello and unknowns need no reply
                }
            }
        } finally {
            beat.cancel()
        }
        withdrew
    }
}
