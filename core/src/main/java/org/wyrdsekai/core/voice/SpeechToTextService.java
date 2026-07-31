package org.wyrdsekai.core.voice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * Routes audio to the best available STT (Speech-to-Text) backend.
 * Singleton pattern -- initialized at startup, accessed via {@link #get()}.
 *
 * <p>Backend priority:
 * <ol>
 *   <li>Local Whisper (whisper.cpp or whisper binary on PATH)</li>
 *   <li>Household Whisper (via InferenceRouter capability registry)</li>
 *   <li>None -- STT unavailable</li>
 * </ol>
 *
 * <p>Audio is never stored. Transcription is ephemeral -- only the resulting text
 * enters the agent's context.
 *
 * @see VoiceService
 * @see VoiceConversationManager
 */
public class SpeechToTextService {

    private static final Logger log = LoggerFactory.getLogger(SpeechToTextService.class);

    /** STT backend types in priority order. */
    public enum SttBackend {
        /** Whisper binary running locally (whisper.cpp or openai/whisper CLI). */
        LOCAL_WHISPER,
        /** Whisper running on a household GPU node via InferenceRouter. */
        HOUSEHOLD,
        /** No STT backend available. */
        NONE
    }

    /** Result of a transcription operation. */
    public record TranscriptionResult(
        String text,
        String language,
        double confidence,
        long durationMs
    ) {}

    /** Global singleton instance. */
    private static volatile SpeechToTextService instance;

    /** Initialize the global instance. Called by Main.java at startup. */
    public static void init() { instance = new SpeechToTextService(); }

    /** Get the global instance. May be null if not initialized. */
    public static SpeechToTextService get() { return instance; }

    /** Reset for testing. */
    public static void reset() { instance = null; }

    private volatile SttBackend activeBackend = SttBackend.NONE;
    private volatile String whisperBinaryPath;

    /**
     * Auto-detect available STT backends.
     * Checks for local whisper binary on PATH, then household capability.
     */
    public void detectBackends() {
        // Check for local whisper.cpp or whisper binary
        var localPath = findExecutableOnPath("whisper");
        if (localPath == null) {
            localPath = findExecutableOnPath("whisper-cpp");
        }
        if (localPath == null) {
            localPath = findExecutableOnPath("main"); // whisper.cpp default binary name
        }

        if (localPath != null) {
            whisperBinaryPath = localPath;
            activeBackend = SttBackend.LOCAL_WHISPER;
            log.info("STT backend: LOCAL_WHISPER ({})", localPath);
            return;
        }

        // Check household capability via InferenceRouter's CapabilityRegistry
        // This would require a reference to the registry; for now, check system property
        var householdStt = System.getProperty("wyrdsekai.stt.household-url");
        if (householdStt != null && !householdStt.isBlank()) {
            activeBackend = SttBackend.HOUSEHOLD;
            log.info("STT backend: HOUSEHOLD ({})", householdStt);
            return;
        }

        activeBackend = SttBackend.NONE;
        log.info("STT backend: NONE -- no speech-to-text available");
    }

    /**
     * Transcribe audio bytes to text.
     *
     * @param audioData Raw audio bytes (PCM 16-bit or WAV format)
     * @param format    Audio format: "wav", "pcm16", "opus", "mp3"
     * @return Future completing with transcription result, or failing if STT unavailable
     */
    public CompletableFuture<TranscriptionResult> transcribe(byte[] audioData, String format) {
        if (audioData == null || audioData.length == 0) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("No audio data provided"));
        }

        return switch (activeBackend) {
            case LOCAL_WHISPER -> transcribeLocal(audioData, format);
            case HOUSEHOLD -> transcribeHousehold(audioData, format);
            case NONE -> CompletableFuture.failedFuture(
                new IllegalStateException("No STT backend available"));
        };
    }

    /**
     * Check if STT is available (any backend detected).
     */
    public boolean isAvailable() {
        return activeBackend != SttBackend.NONE;
    }

    /**
     * Get the currently active backend.
     */
    public SttBackend getActiveBackend() {
        return activeBackend;
    }

    /**
     * Override the active backend (for testing or manual configuration).
     */
    public void setActiveBackend(SttBackend backend) {
        this.activeBackend = backend != null ? backend : SttBackend.NONE;
    }

    // --- Internal ---

    /**
     * Transcribe using local whisper binary.
     * Writes audio to a temp file, runs whisper, reads output.
     */
    private CompletableFuture<TranscriptionResult> transcribeLocal(byte[] audioData, String format) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Write audio to temp file
                var suffix = format != null ? "." + format : ".wav";
                var tempFile = Files.createTempFile("wyrd-stt-", suffix);
                Files.write(tempFile, audioData);

                try {
                    var binaryPath = whisperBinaryPath != null ? whisperBinaryPath : "whisper";
                    var pb = new ProcessBuilder(
                        binaryPath,
                        "--model", System.getProperty("wyrdsekai.stt.model", "base"),
                        "--output-txt",
                        "--no-timestamps",
                        tempFile.toAbsolutePath().toString()
                    );
                    pb.redirectErrorStream(true);

                    long startMs = System.currentTimeMillis();
                    var process = pb.start();
                    var output = new String(process.getInputStream().readAllBytes()).trim();
                    int exitCode = process.waitFor();
                    long durationMs = System.currentTimeMillis() - startMs;

                    if (exitCode != 0) {
                        throw new IOException("Whisper exited with code " + exitCode + ": " + output);
                    }

                    // Try to read the output text file (whisper creates .txt alongside)
                    var txtFile = Path.of(tempFile.toString() + ".txt");
                    String text;
                    if (Files.exists(txtFile)) {
                        text = Files.readString(txtFile).trim();
                        Files.deleteIfExists(txtFile);
                    } else {
                        text = output;
                    }

                    return new TranscriptionResult(text, "auto", 0.9, durationMs);
                } finally {
                    Files.deleteIfExists(tempFile);
                }
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException("Local STT transcription failed", e);
            }
        });
    }

    /**
     * Transcribe using household whisper endpoint.
     * Stub: sends audio to a household STT service via HTTP.
     */
    private CompletableFuture<TranscriptionResult> transcribeHousehold(byte[] audioData, String format) {
        // Household STT is a stub -- actual implementation would POST to household whisper endpoint
        return CompletableFuture.failedFuture(
            new UnsupportedOperationException("Household STT not yet wired to InferenceRouter"));
    }

    /**
     * Check if an executable exists on the system PATH.
     *
     * @param name Executable name
     * @return Full path if found, null otherwise
     */
    static String findExecutableOnPath(String name) {
        var pathEnv = System.getenv("PATH");
        if (pathEnv == null) return null;

        for (var dir : pathEnv.split(File.pathSeparator)) {
            var candidate = Path.of(dir, name);
            if (Files.isExecutable(candidate) && !Files.isDirectory(candidate)) {
                return candidate.toAbsolutePath().toString();
            }
        }
        return null;
    }
}
