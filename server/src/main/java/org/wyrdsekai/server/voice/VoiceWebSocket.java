package org.wyrdsekai.server.voice;

import io.javalin.websocket.WsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Javalin WebSocket handler for voice input (§55).
 * Routes audio frames to VoiceAdapter and returns transcriptions.
 *
 * Protocol:
 *   Text "start"  → begin listening (voice activity detected)
 *   Binary frames → audio PCM data, forwarded to adapter
 *   Text "stop"   → finish transcription, return result
 *   Close         → end session
 */
public class VoiceWebSocket implements Consumer<WsConfig> {

    private static final Logger log = LoggerFactory.getLogger(VoiceWebSocket.class);
    private final VoiceAdapter adapter;

    /**
     * W5 (audit 2026-07-11): sink for finished transcriptions —
     * (sessionId, text). WyrdWebSocket registers itself here at construction
     * so transcriptions reach the transcriber's room via
     * {@code ClientSessionActor.VoiceTranscription} instead of dead-ending at
     * the voice socket. Null until the world socket exists (voice then only
     * echoes back to the caller, as before).
     */
    private static volatile TranscriptionSink transcriptionSink;

    /** A finished transcription: the world session it names, the text, and who is speaking. */
    @FunctionalInterface
    public interface TranscriptionSink {
        void accept(String sessionId, String text, String userId);
    }

    public static void setTranscriptionSink(TranscriptionSink sink) {
        transcriptionSink = sink;
    }

    /**
     * Login token → user id, or null when the token is not a login. Until 2026-09-28 the voice
     * socket asked for no login at all and spoke its transcriptions into whichever world
     * session the caller named. Unset (tests, a node without accounts) keeps the old behaviour.
     */
    private static volatile Function<String, String> tokenToUser;

    public static void setAuthenticator(Function<String, String> authenticator) {
        tokenToUser = authenticator;
    }

    public VoiceWebSocket(VoiceAdapter adapter) {
        this.adapter = adapter;
    }

    /** Forward a finished transcription to the world-session sink, if wired. */
    private static void forwardToWorld(String sessionId, String text, String userId) {
        var sink = transcriptionSink;
        if (sink == null || text == null || text.isBlank()) return;
        try {
            sink.accept(sessionId, text, userId);
        } catch (RuntimeException e) {
            log.warn("Voice transcription sink failed for {}: {}", sessionId, e.getMessage());
        }
    }

    @Override
    public void accept(WsConfig ws) {
        ws.onConnect(ctx -> {
            var auth = tokenToUser;
            if (auth != null) {
                var token = ctx.queryParam("token");
                var userId = token == null || token.isBlank() ? null : auth.apply(token);
                if (userId == null) {
                    ctx.closeSession(4001, "login required");
                    return;
                }
                ctx.attribute("voiceUserId", userId);
            }
            var sessionId = ctx.queryParam("session");
            if (sessionId == null) sessionId = UUID.randomUUID().toString();
            ctx.attribute("voiceSessionId", sessionId);
            adapter.startSession(sessionId);
            log.info("Voice session started: {}", sessionId);
        });

        ws.onMessage(ctx -> {
            var sessionId = (String) ctx.attribute("voiceSessionId");
            if (sessionId == null) return;

            var text = ctx.message().trim().toLowerCase();
            switch (text) {
                case "start" -> {
                    adapter.beginListening(sessionId);
                    ctx.send("{\"status\":\"listening\"}");
                }
                case "stop" -> {
                    var result = adapter.finishTranscription(sessionId);
                    if (result.transcriptionReady()) {
                        ctx.send("{\"transcription\":" + jsonString(result.text()) + "}");
                        forwardToWorld(sessionId, result.text(), ctx.attribute("voiceUserId"));
                    } else {
                        ctx.send("{\"status\":\"no_audio\"}");
                    }
                }
                default -> log.debug("Unknown voice command from {}: {}", sessionId, text);
            }
        });

        ws.onBinaryMessage(ctx -> {
            var sessionId = (String) ctx.attribute("voiceSessionId");
            if (sessionId == null) return;

            var buf = ctx.data();
            var bytes = new byte[buf.remaining()];
            buf.get(bytes);
            var result = adapter.processFrame(sessionId, bytes);
            if (result.transcriptionReady()) {
                ctx.send("{\"transcription\":" + jsonString(result.text()) + "}");
                forwardToWorld(sessionId, result.text(), ctx.attribute("voiceUserId"));
            }
        });

        ws.onClose(ctx -> {
            var sessionId = (String) ctx.attribute("voiceSessionId");
            if (sessionId != null) {
                adapter.endSession(sessionId);
                log.info("Voice session ended: {}", sessionId);
            }
        });

        ws.onError(ctx -> {
            var sessionId = (String) ctx.attribute("voiceSessionId");
            log.error("Voice WebSocket error for {}", sessionId, ctx.error());
        });
    }

    /** Escape a string as a JSON string value (with quotes). */
    static String jsonString(String value) {
        if (value == null) return "null";
        return "\"" + value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
            + "\"";
    }
}
