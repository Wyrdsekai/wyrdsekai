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
 * - Payloads are UTF-8 text (Between uses JSON envelopes), but nats-server
 *   transmits the protocol as WebSocket BINARY frames — so both Frame.Text and
 *   Frame.Binary are decoded as UTF-8 (see [frameText]).
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
    private var lastConnectUrl: String? = null

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
        val infoText = frameText(infoFrame)
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
        wsSession.send(Frame.Text("CONNECT $connectJson\r\n"))

        // Start the receive loop before re-subscribing so we can process +OK / messages
        startReceiveLoop(wsSession)

        // Send SUB for all registered handlers.
        // This covers both:
        //   - subscriptions registered before first connect (were in pendingSubs)
        //   - subscriptions surviving from a previous session (reconnect)
        pendingSubs.clear()
        for ((sid, pair) in handlers) {
            wsSession.send(Frame.Text("SUB ${pair.first} $sid\r\n"))
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
        val s = session ?: return
        if (!_connected) return

        scope.launch {
            try {
                val payload = data.decodeToString()
                // PUB {subject} {length}\r\n{payload}\r\n
                val msg = "PUB $subject ${data.size}\r\n$payload\r\n"
                s.send(Frame.Text(msg))
            } catch (_: Exception) {
                // Send failure is non-fatal; caller can check isConnected
            }
        }
    }

    override fun subscribe(subject: String, handler: (String, ByteArray) -> Unit): () -> Unit {
        val sid = nextSid++
        handlers[sid] = subject to handler

        val s = session
        if (s != null && _connected) {
            scope.launch {
                try {
                    s.send(Frame.Text("SUB $subject $sid\r\n"))
                } catch (_: Exception) {
                    // Will be re-subscribed on reconnect
                }
            }
        } else {
            pendingSubs.add(sid to subject)
        }

        return {
            handlers.remove(sid)
            val currentSession = session
            if (currentSession != null && _connected) {
                scope.launch {
                    try {
                        currentSession.send(Frame.Text("UNSUB $sid\r\n"))
                    } catch (_: Exception) {
                        // Best-effort unsubscribe
                    }
                }
            }
        }
    }

    /**
     * Request/reply over a one-shot inbox subscription. Returns the reply
     * payload as UTF-8 text, or null on timeout / not-connected. Without
     * headers in CONNECT there is no fast no-responders signal — an
     * unanswered subject simply times out.
     */
    suspend fun request(subject: String, payload: String, timeoutMs: Long = 5_000L): String? {
        val s = session ?: return null
        if (!_connected) return null
        val inbox = "_INBOX." + buildString {
            repeat(16) { append("abcdefghijklmnopqrstuvwxyz0123456789"[Random.nextInt(36)]) }
        }
        val reply = CompletableDeferred<String>()
        // Register the handler and send the inbox SUB *inline, before the PUB*,
        // on this same coroutine. The old path went through subscribe(), which
        // dispatches the SUB frame on a separate scope.launch — so the inline PUB
        // raced ahead and reached the relay before the inbox subscription was
        // registered, the responder's reply had nowhere to route, and every
        // request timed out as a phantom "no responder" (the iOS relay-login
        // zone-discovery + mcp.login blocker, #1268). NATS processes SUB then PUB
        // in send order, so registering the inbox first guarantees the reply lands.
        val sid = nextSid++
        handlers[sid] = inbox to { _, data ->
            if (!reply.isCompleted) reply.complete(data.decodeToString())
        }
        return try {
            s.send(Frame.Text("SUB $inbox $sid\r\n"))
            val bytes = payload.encodeToByteArray()
            s.send(Frame.Text("PUB $subject $inbox ${bytes.size}\r\n$payload\r\n"))
            if (DEBUG_WIRE) println("[NATS-tx] request subj=$subject inbox=$inbox sid=$sid")
            withTimeoutOrNull(timeoutMs) { reply.await() }
        } catch (_: Exception) {
            null
        } finally {
            handlers.remove(sid)
            try {
                if (_connected) s.send(Frame.Text("UNSUB $sid\r\n"))
            } catch (_: Exception) {
                // Best-effort unsubscribe.
            }
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
     * Decode a NATS protocol frame to text. nats-server's WebSocket transport
     * uses BINARY frames; some engines/paths still deliver Text. Return null for
     * control frames (ping/pong/close) we don't parse here.
     */
    private fun frameText(frame: Frame): String? = when (frame) {
        is Frame.Text -> frame.readText()
        is Frame.Binary -> frame.readBytes().decodeToString()
        else -> null
    }

    private fun startReceiveLoop(wsSession: DefaultClientWebSocketSession) {
        receiveJob = scope.launch {
            var buffer = ""
            try {
                for (frame in wsSession.incoming) {
                    // nats-server frames the NATS protocol as BINARY over WebSocket;
                    // the Darwin/iOS engine surfaces them as Frame.Binary (Android's
                    // OkHttp engine as Frame.Text). Accept either — dropping binary
                    // frames silently swallowed every MSG, so request() replies never
                    // arrived and looked like a phantom "no responder" (#1268).
                    val chunk = frameText(frame) ?: continue
                    buffer += chunk
                    buffer = processBuffer(buffer, wsSession)
                }
            } catch (_: Exception) {
                // Connection lost or cancelled
            } finally {
                val wasConnected = _connected
                _connected = false
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

    /**
     * Parse and dispatch complete NATS messages from the buffer.
     * Returns the remaining unparsed portion of the buffer.
     */
    private suspend fun processBuffer(
        inputBuffer: String,
        wsSession: DefaultClientWebSocketSession,
    ): String {
        var buffer = inputBuffer

        while (buffer.contains("\r\n")) {
            val idx = buffer.indexOf("\r\n")
            val line = buffer.substring(0, idx)
            if (DEBUG_WIRE && !line.startsWith("PING")) println("[NATS-line] ${line.take(80)}")

            when {
                line == "PING" -> {
                    buffer = buffer.substring(idx + 2)
                    wsSession.send(Frame.Text("PONG\r\n"))
                }

                line.startsWith("MSG ") -> {
                    // MSG {subject} {sid} [{reply-to}] {length}\r\n{payload}\r\n
                    val parts = line.split(" ")
                    if (parts.size < 4) {
                        // Malformed MSG line, skip
                        buffer = buffer.substring(idx + 2)
                        continue
                    }

                    val msgSubject: String
                    val msgSid: Int
                    val msgLen: Int

                    if (parts.size == 4) {
                        // MSG subject sid length
                        msgSubject = parts[1]
                        msgSid = parts[2].toIntOrNull() ?: run {
                            buffer = buffer.substring(idx + 2)
                            continue
                        }
                        msgLen = parts[3].toIntOrNull() ?: run {
                            buffer = buffer.substring(idx + 2)
                            continue
                        }
                    } else {
                        // MSG subject sid reply-to length
                        msgSubject = parts[1]
                        msgSid = parts[2].toIntOrNull() ?: run {
                            buffer = buffer.substring(idx + 2)
                            continue
                        }
                        // Last part is always length
                        msgLen = parts[parts.size - 1].toIntOrNull() ?: run {
                            buffer = buffer.substring(idx + 2)
                            continue
                        }
                    }

                    // The payload follows after the \r\n of the MSG line
                    val afterMsgLine = buffer.substring(idx + 2)

                    // msgLen is a BYTE count (NATS), but `buffer` is a UTF-16 String.
                    // A room_state with multi-byte UTF-8 chars (em-dashes, ellipses in
                    // descriptions) has byteLen > charLen, so the old char-indexed
                    // substring(0, msgLen) OVER-READ, bleeding the next frame's
                    // "…\r\nMSG wyrd…" bytes into the payload — kotlinx then rejected
                    // the whole room_state ("Expected EOF, had M") and it was dropped,
                    // so a steward saw the GENERIC Study on the phone. Find the char
                    // index whose UTF-8 prefix is exactly msgLen bytes. (2026-07-24)
                    val payloadChars = utf8PrefixCharLen(afterMsgLine, msgLen)
                    if (payloadChars < 0 || afterMsgLine.length < payloadChars + 2) {
                        // Incomplete payload (or its trailing \r\n) — wait for more data.
                        // Leave buffer as-is (including the MSG line).
                        return buffer
                    }

                    val payload = afterMsgLine.substring(0, payloadChars)
                    buffer = afterMsgLine.substring(payloadChars + 2) // skip payload + \r\n

                    // Dispatch to handler
                    val handlerPair = handlers[msgSid]
                    if (DEBUG_WIRE) println("[NATS-rx] MSG subj=$msgSubject sid=$msgSid len=$msgLen handler=${handlerPair != null}")
                    if (handlerPair != null) {
                        try {
                            handlerPair.second(msgSubject, payload.encodeToByteArray())
                        } catch (_: Exception) {
                            // Handler threw — don't crash the receive loop
                        }
                    }
                }

                line.startsWith("INFO ") -> {
                    // Server info after initial connect (e.g., on cluster change). Ignore.
                    buffer = buffer.substring(idx + 2)
                }

                line == "+OK" -> {
                    buffer = buffer.substring(idx + 2)
                }

                line.startsWith("-ERR") -> {
                    // NATS error — log but don't disconnect
                    println("[NATS] Server error: $line")
                    buffer = buffer.substring(idx + 2)
                }

                line == "PONG" -> {
                    // Response to our PING (if we ever send one). Ignore.
                    buffer = buffer.substring(idx + 2)
                }

                else -> {
                    // Unknown line — skip
                    buffer = buffer.substring(idx + 2)
                }
            }
        }

        return buffer
    }

    companion object {
        /** Temporary wire-level debug for the iOS relay request/reply probe (#1268). */
        internal const val DEBUG_WIRE = false

        /**
         * The number of leading CHARS of [s] whose UTF-8 encoding totals exactly
         * [targetBytes] bytes, or -1 if [s] does not (yet) hold that many complete
         * UTF-8 bytes. NATS frames a MSG payload by BYTE length, but the receive
         * buffer is a UTF-16 String — indexing by the byte count over-reads on any
         * multi-byte character (em-dash, ellipsis in room descriptions), bleeding
         * the next frame's "…\r\nMSG …" into the payload. Visible for testing.
         */
        internal fun utf8PrefixCharLen(s: String, targetBytes: Int): Int {
            if (targetBytes < 0) return -1
            var bytes = 0
            var i = 0
            while (bytes < targetBytes) {
                if (i >= s.length) return -1 // not enough bytes buffered yet
                val code = s[i].code
                val (b, adv) = when {
                    code < 0x80 -> 1 to 1
                    code < 0x800 -> 2 to 1
                    code in 0xD800..0xDBFF ->
                        if (i + 1 < s.length && s[i + 1].code in 0xDC00..0xDFFF) 4 to 2
                        else return -1 // lone high surrogate — pair not fully buffered
                    else -> 3 to 1
                }
                bytes += b
                i += adv
            }
            // Exact hit → char count; overshoot means the boundary fell mid-character
            // (incomplete buffer / bad framing) — treat as "wait for more".
            return if (bytes == targetBytes) i else -1
        }

        /** Max backoff delay (16 seconds). */
        internal const val MAX_BACKOFF_MS = 16_000L

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
