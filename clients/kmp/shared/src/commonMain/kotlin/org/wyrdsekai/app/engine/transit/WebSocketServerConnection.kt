package org.wyrdsekai.app.engine.transit

import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.wyrdsekai.app.protocol.C2SMessage
import org.wyrdsekai.app.protocol.S2CMessage
import org.wyrdsekai.app.platform.AppProps
import org.wyrdsekai.app.platform.AppFiles
import kotlin.time.Clock

/**
 * WebSocket-based ServerConnection for visiting rooms on the household server.
 * Uses Ktor WebSocket client (auto-selects OkHttp engine on Android).
 * The session stays alive via a continuous receive loop inside the webSocket block.
 */
class WebSocketServerConnection(
    private val wsUrl: String,
    private val scope: CoroutineScope,
) : ServerConnection {

    private val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "type"
    }

    private val client = HttpClient {
        install(WebSockets) {
            pingIntervalMillis = 20_000
        }
    }

    private var session: DefaultClientWebSocketSession? = null
    private val handlers = mutableListOf<(S2CMessage) -> Unit>()
    private var sessionJob: Job? = null
    private val outgoing = kotlinx.coroutines.channels.Channel<String>(64)

    override var isConnected: Boolean = false
        private set

    suspend fun connect() {
        val connected = CompletableDeferred<Unit>()

        sessionJob = scope.launch(Dispatchers.IO) {
            try {
                client.webSocket(wsUrl) {
                    session = this
                    isConnected = true
                    debugLog("WS session opened")
                    connected.complete(Unit)

                    // Keepalive ping every 30 seconds (OkHttp ignores Ktor's pingIntervalMillis)
                    val pingJob = launch {
                        while (isActive) {
                            delay(30_000)
                            try {
                                send(Frame.Ping(byteArrayOf()))
                            } catch (_: Exception) { break }
                        }
                    }

                    // Two concurrent tasks: receive from server + send from outgoing channel
                    val recvJob = launch {
                        try {
                            while (true) {
                                val frame = incoming.receive()
                                if (frame is Frame.Text) {
                                    val text = frame.readText()
                                    debugLog("WS RECV: ${text.take(100)}")
                                    try {
                                        val msg = json.decodeFromString<S2CMessage>(text)
                                        for (handler in handlers) {
                                            handler(msg)
                                        }
                                    } catch (e: Exception) {
                                        debugLog("WS PARSE ERROR: ${e.message?.take(100)} | raw: ${text.take(150)}")
                                    }
                                } else if (frame is Frame.Close) {
                                    debugLog("WS close frame received")
                                    break
                                }
                            }
                        } catch (e: Exception) {
                            debugLog("WS recv ended: ${e::class.simpleName}")
                        }
                    }

                    val sendJob = launch {
                        debugLog("WS send job started")
                        try {
                            for (text in outgoing as kotlinx.coroutines.channels.ReceiveChannel<String>) {
                                debugLog("WS SEND: ${text.take(150)}")
                                send(Frame.Text(text))
                            }
                        } catch (e: Exception) {
                            debugLog("WS send ended: ${e::class.simpleName}")
                        }
                    }

                    // Wait for recv to finish (session close, error, etc.)
                    recvJob.join()
                    sendJob.cancel()
                    pingJob.cancel()
                }
            } catch (e: Exception) {
                debugLog("WS session error: ${e::class.simpleName}: ${e.message?.take(100)}")
                if (!connected.isCompleted) connected.completeExceptionally(e)
            } finally {
                isConnected = false
                session = null
                debugLog("WS session ended")
            }
        }

        withTimeout(10_000) {
            connected.await()
        }
    }

    override suspend fun send(message: C2SMessage) {
        val ws = session
        if (ws == null) {
            debugLog("WS SEND: no session")
            return
        }
        try {
            val text = json.encodeToString(C2SMessage.serializer(), message)
            debugLog("WS SEND direct: ${text.take(150)}")
            ws.send(Frame.Text(text))
        } catch (e: Exception) {
            debugLog("WS SEND ERROR: ${e::class.simpleName}: ${e.message}")
        }
    }

    override fun onMessage(handler: (S2CMessage) -> Unit): () -> Unit {
        handlers.add(handler)
        return { handlers.remove(handler) }
    }

    override fun remoteRoomIds(): Set<String> = emptySet()

    suspend fun disconnect() {
        outgoing.close()
        sessionJob?.cancel()
        session = null
        isConnected = false
    }

    private fun debugLog(msg: String) {
        try {
            val dir = AppProps.get("wyrdsekai.data.dir") ?: return
            AppFiles.appendText("$dir/wyrd-debug.log", "${Clock.System.now()}: $msg\n")
        } catch (_: Exception) {}
    }
}
