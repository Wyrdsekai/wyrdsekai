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

    private val client = HttpClient {
        install(WebSockets) {
            pingIntervalMillis = 15_000
        }
    }
    private var session: DefaultClientWebSocketSession? = null
    private var connectJob: Job? = null
    private var lastSeenSeq: Long = 0

    private lateinit var serverUrl: String
    private var token: String? = null
    private var locale: String = "en"
    private var currentRoomId: String? = null

    enum class ConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING
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

            while (isActive) {
                try {
                    val wsUrl = buildWsUrl()
                    client.webSocket(wsUrl) {
                        session = this
                        _connectionState.value = ConnectionState.CONNECTED
                        attempt = 0

                        // Request replay if reconnecting
                        if (isReconnect && lastSeenSeq > 0) {
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
                                    lastSeenSeq = msg.seq
                                    _messages.emit(msg)
                                } catch (e: Exception) {
                                    // Unknown message type — log and skip (forward compat)
                                    println("[WyrdWS] Unknown message: ${e.message}")
                                }
                            }
                        }
                    }

                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    println("[WyrdWS] Connection error: ${e.message}")
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

    private fun buildWsUrl(): String {
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
            if (params.isNotEmpty()) {
                append("?${params.joinToString("&")}")
            }
        }
    }
}
