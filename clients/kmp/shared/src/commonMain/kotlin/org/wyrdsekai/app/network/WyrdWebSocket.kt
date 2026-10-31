package org.wyrdsekai.app.network

import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.wyrdsekai.app.protocol.*
import kotlin.math.min
import kotlin.random.Random

/**
 * WebSocket client with exponential backoff reconnection and seq-based replay.
 */
class WyrdWebSocket(
    private val scope: CoroutineScope,
) {
    private val _messages = MutableSharedFlow<S2CMessage>(extraBufferCapacity = 64)
    val messages: SharedFlow<S2CMessage> = _messages.asSharedFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val client = createWsHttpClient().config {
        install(WebSockets) {
            pingIntervalMillis = 15_000
        }
    }
    private var session: DefaultClientWebSocketSession? = null
    private var connectJob: Job? = null
    private var lastSeenSeq: Long = 0

    /**
     * This app's own name, sent on every connect. A connection that drops comes back to the same
     * session: no one in the room sees it leave or arrive, and what was said meanwhile is replayed.
     */
    private val clientKey: String = buildString {
        repeat(2) { append(Random.nextLong().toULong().toString(36)) }
    }

    private lateinit var serverUrl: String
    private var token: String? = null
    private var locale: String = "en"
    private var currentRoomId: String? = null

    enum class ConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING
    }

    private companion object {
        /** The server's close when a newer connection with this app's name took the session up. */
        const val MOVED_TO_NEWER: Short = 4005
    }

    fun connect(serverUrl: String, token: String?, locale: String = "en") {
        this.serverUrl = serverUrl
        this.token = token
        this.locale = locale
        this.lastSeenSeq = 0
        startConnection(isReconnect = false)
    }

    /** Update locale for reconnect URLs (called when user changes language). */
    fun setLocale(locale: String) {
        this.locale = locale
    }

    /** Track current room for reconnect URL. */
    fun setCurrentRoomId(roomId: String) {
        this.currentRoomId = roomId
    }

    fun disconnect() {
        connectJob?.cancel()
        connectJob = null
        scope.launch {
            session?.close(CloseReason(CloseReason.Codes.NORMAL, "Client disconnect"))
            session = null
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    suspend fun send(message: C2SMessage) {
        session?.send(Frame.Text(message.toJson()))
    }

    private fun startConnection(isReconnect: Boolean) {
        connectJob?.cancel()
        connectJob = scope.launch {
            val state = if (isReconnect) ConnectionState.RECONNECTING else ConnectionState.CONNECTING
            _connectionState.value = state

            var attempt = 0
            val maxBackoff = 30_000L

            var movedAway = false
            while (isActive && !movedAway) {
                try {
                    val wsUrl = buildWsUrl()
                    client.webSocket(wsUrl) {
                        session = this
                        _connectionState.value = ConnectionState.CONNECTED
                        attempt = 0

                        // The server closes a connection with nothing from the client for five
                        // minutes, and counts only messages (not pings) as use: say so every 30 s.
                        val keepalive = launch {
                            while (isActive) {
                                delay(30_000)
                                send(Frame.Text("{\"type\":\"ping\"}"))
                            }
                        }

                        // Request replay of what was said while away, from the newest line seen
                        // (a fresh connect starts at 0 and asks for none).
                        if (lastSeenSeq > 0) {
                            val reconnectMsg = C2SMessage.Reconnect(
                                id = "reconnect-${Random.nextInt()}",
                                roomId = "",
                                lastSeenSeq = lastSeenSeq,
                            )
                            send(Frame.Text(reconnectMsg.toJson()))
                        }

                        // Read loop
                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                val text = frame.readText()
                                try {
                                    val msg = parseS2CMessage(text)
                                    // The newest line of this session: a fresh session counts from 1 again.
                                    if (msg.seq > 0) lastSeenSeq = msg.seq
                                    _messages.emit(msg)
                                } catch (e: Exception) {
                                    // Unknown message type — log and skip (forward compat)
                                    println("[WyrdWS] Unknown message: ${e.message}")
                                }
                            }
                        }
                        keepalive.cancel()
                        // 4005: a newer connection with this app's name took the session up;
                        // reconnecting would take it back.
                        if (closeReason.await()?.code == MOVED_TO_NEWER) movedAway = true
                    }

                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    println("[WyrdWS] Connection error: ${e.message}")
                }

                if (movedAway) {
                    session = null
                    _connectionState.value = ConnectionState.DISCONNECTED
                    break
                }
                // Connection lost — backoff and retry
                session = null
                _connectionState.value = ConnectionState.RECONNECTING
                val delay = min(1000L * (1L shl min(attempt, 5)), maxBackoff)
                val jitter = Random.nextLong(delay / 4)
                delay(delay + jitter)
                attempt++
            }
        }
    }

    internal fun buildWsUrl(): String {
        var raw = serverUrl.trim().trimEnd('/')
        // Auto-prepend scheme if missing
        if (!raw.startsWith("http://") && !raw.startsWith("https://") &&
            !raw.startsWith("ws://") && !raw.startsWith("wss://")) {
            raw = "http://$raw"
        }
        val base = raw
            .replace("http://", "ws://")
            .replace("https://", "wss://")

        return buildString {
            append("$base/ws")
            val params = mutableListOf<String>()
            token?.let { params.add("token=$it") }
            if (locale != "en") params.add("locale=$locale")
            currentRoomId?.let { params.add("room=$it") }
            params.add("client=$clientKey")
            if (params.isNotEmpty()) {
                append("?${params.joinToString("&")}")
            }
        }
    }
}
