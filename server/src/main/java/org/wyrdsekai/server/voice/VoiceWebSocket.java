package org.wyrdsekai.server.voice;

import io.javalin.websocket.WsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

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
    private static volatile BiConsumer<String, String> transcriptionSink;

    public static void setTranscriptionSink(BiConsumer<String, String> sink) {
        transcriptionSink = sink;
    }

    public VoiceWebSocket(VoiceAdapter adapter) {
        this.adapter = adapter;
    }

    /** Forward a finished transcription to the world-session sink, if wired. */
    private static void forwardToWorld(String sessionId, String text) {
        var sink = transcriptionSink;
        if (sink == null || text == null || text.isBlank()) return;
        try {
            sink.accept(sessionId, text);
        } catch (RuntimeException e) {
            log.warn("Voice transcription sink failed for {}: {}", sessionId, e.getMessage());
        }
    }

    @Override
    public void accept(WsConfig ws) {
        ws.onConnect(ctx -> {
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
                        forwardToWorld(sessionId, result.text());
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
                forwardToWorld(sessionId, result.text());
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
