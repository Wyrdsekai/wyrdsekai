package org.wyrdsekai.app.hermod

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.wyrdsekai.app.engine.between.BetweenClient
import org.wyrdsekai.app.inference.LocalInferenceProvider
import org.wyrdsekai.app.network.createWsHttpClient
import kotlin.random.Random

/**
 * Owns the phone's hermod lifecycle, independent of any local node or
 * presence session: while consented and a device identity exists, keep
 * exactly ONE door open to the zone — LAN WebSocket when the zone is
 * reachable directly, the relay tunnel otherwise — and move between them
 * as the phone moves. Both legs run the same DoorSession over the same
 * identity, and the zone treats a switch as a channel supersede, so
 * home↔away roaming is seamless by construction.
 *
 * Election order: LAN first (lower latency, no relay hop). While on the
 * tunnel, the LAN door is re-probed on an interval; the moment home
 * answers, the tunnel leg closes and the loop re-elects LAN. When the
 * LAN leg dies (left home), the loop falls through to the tunnel.
 * doors() is re-read every cycle, so an identity minted mid-session
 * (the consent toggle) arms the mesh without any restart.
 */
class HermodDoorman(
    private val scope: CoroutineScope,
    local: LocalInferenceProvider,
    models: () -> List<String>,
    private val policy: () -> HermodListener.HermodPolicy,
    private val doors: () -> Doors,
    private val capabilityClass: String = "llm.phone",
    heartbeatMillis: Long = 30_000,
    private val reprobeMillis: Long = 60_000,
    private val client: HttpClient = createWsHttpClient(),
    private val lanProbe: (suspend (String) -> Boolean)? = null,
) {

    /** What the phone currently knows about its ways home. */
    data class Doors(
        val deviceToken: String?,   // no identity → no mesh, full stop
        val serverUrl: String?,     // LAN candidate (http/https base)
        val tunnel: TunnelDoor?,    // relay candidate
    )

    data class TunnelDoor(val between: BetweenClient, val zoneId: String)

    private val session = DoorSession(local, models, policy, capabilityClass, heartbeatMillis)
    private val _state = MutableStateFlow("stopped")

    /** "stopped" | "waiting" | "lan" | "tunnel" | "backoff" */
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
        var failures = 0
        while (scope.isActive) {
            val d = doors()
            if (!policy().eligible || d.deviceToken.isNullOrBlank()) {
                _state.value = "waiting"
                delay(ELIGIBILITY_POLL_MILLIS)
                continue
            }
            val lanUp = d.serverUrl != null && probe(d.serverUrl)
            val ranCleanly = when {
                lanUp -> {
                    _state.value = "lan"
                    runCatching { lanLeg(d.serverUrl!!, d.deviceToken) }.getOrDefault(false)
                }
                d.tunnel != null && d.tunnel.between.isConnected -> {
                    _state.value = "tunnel"
                    runCatching { tunnelLeg(d.tunnel, d.deviceToken, d.serverUrl) }
                        .getOrDefault(false)
                }
                else -> {
                    _state.value = "waiting"
                    delay(NO_DOOR_POLL_MILLIS)
                    continue
                }
            }
            if (ranCleanly) {
                failures = 0 // a session that lived (or withdrew) resets backoff
            } else {
                failures++
                _state.value = "backoff"
                val base = minOf(2_000L shl minOf(failures, 5), 60_000L)
                delay(base + Random.nextLong(base / 4 + 1))
            }
        }
    }

    /** LAN leg: direct WebSocket to /ws/hermod. Returns true when the
     *  session genuinely ran (ended by close/withdrawal, not connect failure). */
    private suspend fun lanLeg(serverUrl: String, deviceToken: String): Boolean {
        val wsUrl = serverUrl.replace("http://", "ws://").replace("https://", "wss://")
            .trimEnd('/') + "/ws/hermod?device_token=$deviceToken"
        var ran = false
        client.webSocket(wsUrl) {
            ran = true
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
                session.run(inbound,
                    send = { text -> send(Frame.Text(text)) },
                    close = { ws.close() })
            } finally {
                pump.cancel()
            }
        }
        return ran
    }

    /** Tunnel leg: DoorSession over the relay pipe, with a parallel
     *  LAN re-probe — the moment home answers, this leg closes and the
     *  loop re-elects LAN. Returns true when the session genuinely ran. */
    private suspend fun tunnelLeg(
        door: TunnelDoor,
        deviceToken: String,
        serverUrl: String?,
    ): Boolean = coroutineScope {
        val pipe = TunnelDoorFrames(door.between, door.zoneId, deviceToken)
        pipe.open()
        val roamWatch = if (serverUrl != null) launch {
            while (isActive) {
                delay(reprobeMillis)
                if (probe(serverUrl)) {
                    pipe.close() // drains the session; loop re-elects LAN
                    return@launch
                }
            }
        } else null
        try {
            session.run(pipe.inbound,
                send = { text -> pipe.send(text) },
                close = { pipe.close() })
            true
        } finally {
            roamWatch?.cancel()
            pipe.close()
        }
    }

    private suspend fun probe(serverUrl: String): Boolean {
        lanProbe?.let { return it(serverUrl) }
        return withTimeoutOrNull(PROBE_TIMEOUT_MILLIS) {
            runCatching {
                val resp: HttpResponse = client.get(serverUrl.trimEnd('/') + "/health")
                resp.status.value in 200..299
            }.getOrDefault(false)
        } ?: false
    }

    companion object {
        private const val ELIGIBILITY_POLL_MILLIS = 60_000L
        private const val NO_DOOR_POLL_MILLIS = 30_000L
        private const val PROBE_TIMEOUT_MILLIS = 3_000L
    }
}
