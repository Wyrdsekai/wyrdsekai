package org.wyrdsekai.app.hermod

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.wyrdsekai.app.inference.LocalInferenceProvider
import org.wyrdsekai.app.network.createWsHttpClient
import kotlin.random.Random

/**
 * The LAN-only hermod listener: hold a WebSocket to the zone's
 * /ws/hermod and run the shared DoorSession over it. Production phones
 * use HermodDoorman (which roams LAN↔relay and owns lifecycle); this
 * class remains the single-leg harness the live e2e drives directly.
 * Presence is never involved: no session, no room, no companion state
 * rides this socket.
 */
class HermodListener(
    private val wsUrl: String, // .../ws/hermod?device_token=wyrd_dev_...
    private val scope: CoroutineScope,
    local: LocalInferenceProvider,
    models: () -> List<String>,
    private val policy: () -> HermodPolicy,
    capabilityClass: String = "llm.phone",
    private val client: HttpClient = createWsHttpClient(),
    heartbeatMillis: Long = 30_000,
) {

    data class HermodPolicy(val consented: Boolean, val charging: Boolean, val idle: Boolean) {
        val eligible: Boolean get() = consented && charging
    }

    private val doorSession = DoorSession(local, models, policy, capabilityClass, heartbeatMillis)
    private val _state = MutableStateFlow("stopped")
    val state: StateFlow<String> get() = _state
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.Default) { run() }
    }

    fun stop() {
        job?.cancel()
        job = null
        _state.value = "stopped"
    }

    private suspend fun run() {
        var attempt = 0
        while (scope.isActive) {
            if (!policy().eligible) {
                _state.value = "waiting"
                delay(ELIGIBILITY_POLL_MILLIS)
                continue
            }
            try {
                _state.value = "connecting"
                session()
                attempt = 0 // a session that lived resets the backoff
            } catch (e: MeshInertException) {
                _state.value = "mesh-inert"
                delay(MESH_INERT_RETRY_MILLIS)
                continue
            } catch (e: Exception) {
                // fall through to backoff
            }
            _state.value = "backoff"
            attempt++
            val base = minOf(2_000L shl minOf(attempt, 5), 60_000L)
            delay(base + Random.nextLong(base / 4 + 1))
        }
    }

    private class MeshInertException : Exception()

    private suspend fun session() {
        client.webSocket(wsUrl) {
            _state.value = "listening"
            val ws = this
            val inbound = Channel<String>(64)
            val pump = launch {
                try {
                    for (frame in incoming) {
                        if (frame is Frame.Text) inbound.trySend(frame.readText())
                    }
                } finally {
                    inbound.close()
                }
            }
            try {
                doorSession.run(inbound,
                    send = { text -> send(Frame.Text(text)) },
                    close = { ws.close() })
            } finally {
                pump.cancel()
            }
            val reason = closeReason.await()
            if (reason?.code?.toInt() == 1013) throw MeshInertException()
        }
    }

    companion object {
        private const val ELIGIBILITY_POLL_MILLIS = 60_000L
        private const val MESH_INERT_RETRY_MILLIS = 300_000L
    }
}
