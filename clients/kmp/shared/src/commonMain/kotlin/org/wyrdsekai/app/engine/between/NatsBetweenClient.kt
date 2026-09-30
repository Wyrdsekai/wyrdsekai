package org.wyrdsekai.app.engine.between

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.wyrdsekai.app.network.createWsHttpClient
import kotlin.random.Random

/**
 * NATS WebSocket BetweenClient implementation.
 *
 * Speaks the NATS text protocol over a single WebSocket connection.
 * Protocol reference: https://docs.nats.io/reference/reference-protocols/nats-protocol
 *
 * Wire format (all messages delimited by \r\n):
 *   Server INFO → Client CONNECT → SUB/PUB/MSG/PING/PONG
 *
 * Design notes:
 * - Payloads are BYTES. Between envelopes are JSON, but sealed tunnel frames
 * ( W3) are binary, so the protocol is read and
 *   written as bytes and framed by the byte length NATS declares ([NatsReadBuffer]).
 * - Everything the client writes after CONNECT (SUB, UNSUB, PUB, PONG) goes
 *   through one ordered outbox, so a SUB always reaches the server before a
 *   PUB issued after it, and sealed frames leave in the order they were sealed.
 * - Thread safety: all mutable state is accessed from [scope]'s coroutine
 *   context. [handlers] and [pendingSubs] are only touched from scope.launch
 *   or the receive loop, both bound to the same scope.
 * - Reconnection is not handled here. The caller (e.g. BetweenHeadlineSyncClient)
 *   can detect disconnection via [isConnected] and re-call [connect].
 *
 */
class NatsBetweenClient(
    private val scope: CoroutineScope,
) : BetweenClient {

    private var client: HttpClient? = null
    private var session: DefaultClientWebSocketSession? = null
    private var _connected = false
    private var nextSid = 1
    private val handlers = mutableMapOf<Int, Pair<String, (String, ByteArray) -> Unit>>()
    private val pendingSubs = mutableListOf<Pair<Int, String>>()
    private var receiveJob: Job? = null
    private var reconnectJob: Job? = null
    private var writerJob: Job? = null
    private var outbox: Channel<ByteArray>? = null
    private var lastConnectUrl: String? = null
    /** Completed by the server's answer to the connect-time PING: "" for PONG, else the -ERR line. */
    private var handshake: CompletableDeferred<String>? = null

    /**
     * When true, the client will automatically attempt to reconnect when
     * the connection drops (detected in the receive loop's finally block).
     * The handler map survives disconnection, so all subscriptions are
     * re-established after reconnect.
     */
    var autoReconnect: Boolean = false

    // Relay credentials — sent in the CONNECT
    // message when set. Survive across reconnects like lastConnectUrl.
    private var natsUser: String? = null
    private var natsPassword: String? = null

    override fun setCredentials(user: String?, password: String?) {
        natsUser = user
        natsPassword = password
    }

    override val isConnected: Boolean get() = _connected

    override suspend fun connect(url: String) {
        lastConnectUrl = url

        // Platform factory: wires household-CA / invite-pinned trust into the
        // wss handshake (system-default trust rejects the relay's household
        // cert on every platform).
        val httpClient = createWsHttpClient()
        client = httpClient

        val wsSession = httpClient.webSocketSession(url)
        session = wsSession

        // Read the INFO line from the server.
        // The server sends INFO {...}\r\n immediately on connect. nats-server's
        // WebSocket transport frames the NATS protocol as BINARY frames (the
        // Darwin/iOS Ktor engine surfaces them as Frame.Binary, not Frame.Text);
        // accept either so the handshake — and every later MSG — is actually read.
        val infoFrame = wsSession.incoming.receive()
        val infoText = frameBytes(infoFrame)?.decodeToString()
        if (infoText != null && !infoText.trimEnd().startsWith("INFO ")) {
            wsSession.close()
            httpClient.close()
            throw IllegalStateException("Expected NATS INFO, got: ${infoText.take(80)}")
        }

        // Send CONNECT (with NATS user/pass auth when credentials are set —
        // the relay's NATS requires them; LAN NATS ignores extra fields).
        val auth = natsUser?.let { u ->
            ""","user":${jsonString(u)},"pass":${jsonString(natsPassword ?: "")}"""
        } ?: ""
        val connectJson =
            """{"verbose":false,"pedantic":false,"lang":"kotlin","version":"1.0","protocol":1$auth}"""
        wsSession.send(Frame.Binary(true, "CONNECT $connectJson\r\n".encodeToByteArray()))

        // One writer per connection: every later protocol line leaves in the
        // order it was queued.
        val box = Channel<ByteArray>(Channel.UNLIMITED)
        outbox = box
        writerJob = scope.launch {
            try {
                for (bytes in box) wsSession.send(Frame.Binary(true, bytes))
            } catch (_: Exception) {
                // Connection lost; the receive loop notices and reconnects.
            }
        }

        // Start the receive loop before re-subscribing so we can process +OK / messages
        val hs = CompletableDeferred<String>()
        handshake = hs
        startReceiveLoop(wsSession)

        // Confirm the server took this login before anything else goes out: PING,
        // then PONG. A refused login answers -ERR and the server closes; without
        // this the client would report itself connected on a dead link.
        box.trySend("PING\r\n".encodeToByteArray())
        val answer = withTimeoutOrNull(HANDSHAKE_TIMEOUT_MS) { hs.await() }
        handshake = null
        if (answer != "") {
            disconnect()
            throw IllegalStateException(
                if (answer == null) "NATS did not answer the connection handshake" else "NATS refused the connection: $answer")
        }

        // Send SUB for all registered handlers.
        // This covers both:
        //   - subscriptions registered before first connect (were in pendingSubs)
        //   - subscriptions surviving from a previous session (reconnect)
        pendingSubs.clear()
        for ((sid, pair) in handlers) {
            box.trySend("SUB ${pair.first} $sid\r\n".encodeToByteArray())
        }

        _connected = true
    }

    /**
     * Connect with exponential backoff retry.
     *
     * Attempts to connect up to [maxAttempts] times with exponential backoff:
     * 1s, 2s, 4s, 8s, 16s (capped at 16s).
     *
     * @param url The NATS WebSocket URL to connect to
     * @param maxAttempts Maximum number of connection attempts (default 5)
     * @throws Exception The last connection error if all attempts fail
     */
    suspend fun connectWithRetry(url: String, maxAttempts: Int = 5) {
        var lastError: Exception? = null
        for (attempt in 0 until maxAttempts) {
            try {
                connect(url)
                return // Success
            } catch (e: Exception) {
                lastError = e
                // Clean up failed connection attempt
                try { disconnect() } catch (_: Exception) {}

                // Don't delay after the last attempt
                if (attempt < maxAttempts - 1) {
                    val delayMs = backoffDelayMs(attempt)
                    delay(delayMs)
                }
            }
        }
        throw lastError ?: IllegalStateException("Connection failed after $maxAttempts attempts")
    }

    override suspend fun disconnect() {
        _connected = false
        reconnectJob?.cancel()
        reconnectJob = null
        receiveJob?.cancel()
        receiveJob = null
        outbox?.close()
        outbox = null
        writerJob?.cancel()
        writerJob = null
        try {
            session?.close()
        } catch (_: Exception) {
            // Best-effort close
        }
        session = null
        try {
            client?.close()
        } catch (_: Exception) {
            // Best-effort close
        }
        client = null
    }

    override fun publish(subject: String, data: ByteArray) {
        if (!_connected) return
        // Send failure is non-fatal; caller can check isConnected.
        outbox?.trySend(pubFrame(subject, null, data))
    }

    override fun subscribe(subject: String, handler: (String, ByteArray) -> Unit): () -> Unit {
        val sid = nextSid++
        handlers[sid] = subject to handler

        val box = outbox
        if (box != null && _connected) {
            // Re-subscribed on reconnect if this is lost.
            box.trySend("SUB $subject $sid\r\n".encodeToByteArray())
        } else {
            pendingSubs.add(sid to subject)
        }

        return {
            handlers.remove(sid)
            if (_connected) outbox?.trySend("UNSUB $sid\r\n".encodeToByteArray())
        }
    }

    /**
     * Request/reply over a one-shot inbox subscription. Returns the reply
     * payload as UTF-8 text, or null on timeout / not-connected. Without
     * headers in CONNECT there is no fast no-responders signal — an
     * unanswered subject simply times out.
     */
    suspend fun request(subject: String, payload: String, timeoutMs: Long = 5_000L): String? {
        val box = outbox ?: return null
        if (!_connected) return null
        val inbox = inboxFor(natsUser) + buildString {
            repeat(16) { append("abcdefghijklmnopqrstuvwxyz0123456789"[Random.nextInt(36)]) }
        }
        val reply = CompletableDeferred<String>()
        // The inbox SUB is queued BEFORE the PUB on the same ordered outbox. The
        // old path sent the SUB on a separate launch, so the PUB could reach the
        // relay first, the reply had nowhere to route, and every request timed
        // out as a phantom "no responder" (#1268).
        val sid = nextSid++
        handlers[sid] = inbox to { _, data ->
            if (!reply.isCompleted) reply.complete(data.decodeToString())
        }
        return try {
            box.trySend("SUB $inbox $sid\r\n".encodeToByteArray())
            box.trySend(pubFrame(subject, inbox, payload.encodeToByteArray()))
            if (DEBUG_WIRE) println("[NATS-tx] request subj=$subject inbox=$inbox sid=$sid")
            withTimeoutOrNull(timeoutMs) { reply.await() }
        } catch (_: Exception) {
            null
        } finally {
            handlers.remove(sid)
            if (_connected) outbox?.trySend("UNSUB $sid\r\n".encodeToByteArray())
        }
    }

    /**
     * Start the receive loop that processes incoming NATS frames.
     *
     * NATS messages are line-delimited (\r\n). A MSG command is followed
     * by a payload line of the declared byte length. The loop buffers
     * partial frames and parses complete messages as they arrive.
     */
    /** Minimal JSON string literal — credentials may contain any byte. */
    private fun jsonString(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') {
                    sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                } else {
                    sb.append(c)
                }
            }
        }
        return sb.append('"').toString()
    }

    /**
     * The bytes of a NATS protocol frame. nats-server's WebSocket transport
     * uses BINARY frames; some engines/paths still deliver Text. Null for
     * control frames (ping/pong/close) we don't parse here.
     */
    private fun frameBytes(frame: Frame): ByteArray? = when (frame) {
        is Frame.Text -> frame.readText().encodeToByteArray()
        is Frame.Binary -> frame.readBytes()
        else -> null
    }

    private fun startReceiveLoop(wsSession: DefaultClientWebSocketSession) {
        receiveJob = scope.launch {
            val reader = NatsReadBuffer()
            try {
                for (frame in wsSession.incoming) {
                    // nats-server frames the NATS protocol as BINARY over WebSocket;
                    // the Darwin/iOS engine surfaces them as Frame.Binary (Android's
                    // OkHttp engine as Frame.Text). Accept either — dropping binary
                    // frames silently swallowed every MSG, so request() replies never
                    // arrived and looked like a phantom "no responder" (#1268).
                    val chunk = frameBytes(frame) ?: continue
                    for (op in reader.feed(chunk)) dispatch(op)
                }
            } catch (_: Exception) {
                // Connection lost or cancelled
            } finally {
                handshake?.complete("connection closed")
                val wasConnected = _connected
                _connected = false
                outbox?.close()
                // Trigger auto-reconnect if enabled and we were previously connected
                // (i.e., this is a real disconnection, not an explicit disconnect() call)
                if (autoReconnect && wasConnected) {
                    val url = lastConnectUrl
                    if (url != null) {
                        reconnectJob = scope.launch {
                            try {
                                connectWithRetry(url)
                            } catch (_: Exception) {
                                // All reconnection attempts failed
                            }
                        }
                    }
                }
            }
        }
    }

    private fun dispatch(op: NatsReadBuffer.Op) {
        when (op) {
            NatsReadBuffer.Op.Ping -> outbox?.trySend("PONG\r\n".encodeToByteArray())
            NatsReadBuffer.Op.Pong -> handshake?.complete("")
            is NatsReadBuffer.Op.Err -> {
                handshake?.complete(op.line)
                println("[NATS] Server error: ${op.line}")
            }
            is NatsReadBuffer.Op.Msg -> {
                val handlerPair = handlers[op.sid]
                if (DEBUG_WIRE) println("[NATS-rx] MSG subj=${op.subject} sid=${op.sid} len=${op.payload.size} handler=${handlerPair != null}")
                if (handlerPair != null) {
                    try {
                        handlerPair.second(op.subject, op.payload)
                    } catch (_: Exception) {
                        // Handler threw — don't crash the receive loop
                    }
                }
            }
        }
    }

    companion object {
        /** Temporary wire-level debug for the iOS relay request/reply probe (#1268). */
        internal const val DEBUG_WIRE = false

        /**
         * The reply-inbox prefix: `_INBOX.<NATS username>.` when the client has
         * a username. A relay grants each user subscribe only on its own
         * `_INBOX.<user>.>`, so no other relay user can read its replies.
         */
        internal fun inboxFor(user: String?): String =
            if (user.isNullOrBlank()) "_INBOX." else "_INBOX.$user."

        /** `PUB <subject> [reply] <len>\r\n<payload>\r\n` as bytes; the payload may be binary. */
        internal fun pubFrame(subject: String, replyTo: String?, data: ByteArray): ByteArray {
            val head = if (replyTo == null) "PUB $subject ${data.size}\r\n" else "PUB $subject $replyTo ${data.size}\r\n"
            return head.encodeToByteArray() + data + CRLF
        }

        private val CRLF = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte())

        /** Max backoff delay (16 seconds). */
        internal const val MAX_BACKOFF_MS = 16_000L

        /** How long connect waits for the server to accept the login (PONG). */
        internal const val HANDSHAKE_TIMEOUT_MS = 10_000L

        /**
         * Exponential backoff: 1s, 2s, 4s, 8s, 16s (capped).
         * Visible for testing.
         */
        internal fun backoffDelayMs(attempt: Int): Long {
            val base = 1000L
            val delay = base shl attempt // 1000, 2000, 4000, 8000, 16000
            return minOf(delay, MAX_BACKOFF_MS)
        }
    }
}
