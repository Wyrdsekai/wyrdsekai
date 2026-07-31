package org.wyrdsekai.server.voice;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Voice input adapter — WebSocket audio→text endpoint (§55).
 * Integration point for edge STT (Whisper.cpp or equivalent).
 * Audio arrives as PCM frames, gets transcribed, and returns text commands.
 */
public class VoiceAdapter {

    /** A voice transcription result. */
    public record Transcription(
        String sessionId,
        String text,
        String language,
        double confidence,
        long durationMs,
        Instant transcribedAt
    ) {}

    /** Audio buffer accumulating frames before transcription. */
    public record AudioBuffer(
        String sessionId,
        List<byte[]> frames,
        int totalBytes,
        Instant startedAt
    ) {
        public AudioBuffer addFrame(byte[] frame) {
            var newFrames = new ArrayList<>(frames);
            newFrames.add(frame);
            return new AudioBuffer(sessionId, newFrames, totalBytes + frame.length, startedAt);
        }
    }

    /** Voice session state. */
    public enum SessionState { IDLE, LISTENING, TRANSCRIBING }

    /** Result of processing an audio frame. */
    public record ProcessResult(
        boolean transcriptionReady,
        String text,
        SessionState newState
    ) {
        public static ProcessResult listening() {
            return new ProcessResult(false, null, SessionState.LISTENING);
        }

        public static ProcessResult transcribed(String text) {
            return new ProcessResult(true, text, SessionState.IDLE);
        }
    }

    private final SttConfig config;
    private final Map<String, AudioBuffer> buffers = new ConcurrentHashMap<>();
    private final Map<String, SessionState> states = new ConcurrentHashMap<>();
    private final List<Transcription> transcriptionLog = Collections.synchronizedList(new ArrayList<>());

    /** Transcription hook — in production, this calls Whisper.cpp. Testable via override. */
    private TranscriptionEngine engine = (frames, config) ->
        new Transcription("test", "[mock transcription]", "en", 0.95, 100, Instant.now());

    @FunctionalInterface
    public interface TranscriptionEngine {
        Transcription transcribe(List<byte[]> frames, SttConfig config);
    }

    public VoiceAdapter(SttConfig config) {
        this.config = config;
    }

    /** Set a custom transcription engine (for testing or alternative STT backends). */
    public void setEngine(TranscriptionEngine engine) {
        this.engine = engine;
    }

    /** Start a voice session. */
    public void startSession(String sessionId) {
        buffers.put(sessionId, new AudioBuffer(sessionId, new ArrayList<>(), 0, Instant.now()));
        states.put(sessionId, SessionState.IDLE);
    }

    /** Begin listening (voice activity detected). */
    public void beginListening(String sessionId) {
        states.put(sessionId, SessionState.LISTENING);
        buffers.put(sessionId, new AudioBuffer(sessionId, new ArrayList<>(), 0, Instant.now()));
    }

    /**
     * Process an audio frame.
     * Returns a transcription result if enough audio has been collected.
     */
    public ProcessResult processFrame(String sessionId, byte[] audioFrame) {
        var state = states.getOrDefault(sessionId, SessionState.IDLE);
        if (state != SessionState.LISTENING) return ProcessResult.listening();

        var buffer = buffers.get(sessionId);
        if (buffer == null) return ProcessResult.listening();

        buffer = buffer.addFrame(audioFrame);
        buffers.put(sessionId, buffer);

        // Check if we have enough audio (based on sample rate and max duration)
        int maxBytes = config.sampleRate() * 2 * config.maxDurationSeconds(); // 16-bit PCM
        if (buffer.totalBytes() >= maxBytes) {
            return finishTranscription(sessionId);
        }

        return ProcessResult.listening();
    }

    /** End listening and trigger transcription. */
    public ProcessResult finishTranscription(String sessionId) {
        var buffer = buffers.get(sessionId);
        if (buffer == null || buffer.frames().isEmpty()) {
            states.put(sessionId, SessionState.IDLE);
            return new ProcessResult(false, null, SessionState.IDLE);
        }

        states.put(sessionId, SessionState.TRANSCRIBING);

        var transcription = engine.transcribe(buffer.frames(), config);
        var result = new Transcription(sessionId, transcription.text(),
            transcription.language(), transcription.confidence(),
            transcription.durationMs(), Instant.now());
        transcriptionLog.add(result);

        // Clear buffer
        buffers.put(sessionId, new AudioBuffer(sessionId, new ArrayList<>(), 0, Instant.now()));
        states.put(sessionId, SessionState.IDLE);

        return ProcessResult.transcribed(result.text());
    }

    /** End a voice session. */
    public void endSession(String sessionId) {
        buffers.remove(sessionId);
        states.remove(sessionId);
    }

    /** Get session state. */
    public SessionState getState(String sessionId) {
        return states.getOrDefault(sessionId, SessionState.IDLE);
    }

    /** Get recent transcriptions. */
    public List<Transcription> recentTranscriptions(int limit) {
        int start = Math.max(0, transcriptionLog.size() - limit);
        return List.copyOf(transcriptionLog.subList(start, transcriptionLog.size()));
    }

    /** Count active sessions. */
    public int activeSessionCount() {
        return states.size();
    }

    /** Total transcriptions processed. */
    public int transcriptionCount() {
        return transcriptionLog.size();
    }

    /** Get the STT config. */
    public SttConfig config() {
        return config;
    }
}
