package org.wyrdsekai.core.voice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Routes text to the best available TTS (Text-to-Speech) backend.
 * Singleton pattern -- initialized at startup, accessed via {@link #get()}.
 *
 * <p>Backend priority:
 * <ol>
 *   <li>Local Piper TTS (high-quality neural TTS)</li>
 *   <li>System TTS (espeak-ng on Linux, say on macOS, PowerShell on Windows)</li>
 *   <li>Household TTS (via household GPU node)</li>
 *   <li>None -- TTS unavailable, client handles locally</li>
 * </ol>
 *
 * <p>Cross-platform system TTS detection:
 * <ul>
 *   <li>Linux: {@code espeak-ng} or {@code espeak}</li>
 *   <li>macOS: {@code say} (built-in)</li>
 *   <li>Windows: PowerShell System.Speech</li>
 * </ul>
 *
 * @see VoiceService
 * @see VoiceConversationManager
 */
public class TextToSpeechService {

    private static final Logger log = LoggerFactory.getLogger(TextToSpeechService.class);

    /** TTS backend types in priority order. */
    public enum TtsBackend {
        /** Piper TTS running locally (high-quality neural voices). */
        LOCAL_PIPER,
        /** System TTS (espeak, say, PowerShell). */
        SYSTEM,
        /** TTS running on a household GPU node. */
        HOUSEHOLD,
        /** No TTS backend available. Client must handle TTS locally. */
        NONE
    }

    /** Result of a synthesis operation. */
    public record SynthesisResult(
        byte[] audioData,
        String format,
        long durationMs
    ) {}

    /** Global singleton instance. */
    private static volatile TextToSpeechService instance;

    /** Initialize the global instance. Called by Main.java at startup. */
    public static void init() { instance = new TextToSpeechService(); }

    /** Get the global instance. May be null if not initialized. */
    public static TextToSpeechService get() { return instance; }

    /** Reset for testing. */
    public static void reset() { instance = null; }

    /** Timeout for household HTTP calls. */
    private static final Duration HOUSEHOLD_TIMEOUT = Duration.ofSeconds(30);

    private volatile TtsBackend activeBackend = TtsBackend.NONE;
    private volatile String systemTtsCommand;

    /** Lazy-initialized HTTP client for household TTS calls. */
    private volatile HttpClient householdHttpClient;

    /**
     * Auto-detect available TTS backends.
     * Checks for local Piper, then system TTS, then household.
     */
    public void detectBackends() {
        // Check for local Piper TTS
        if (SpeechToTextService.findExecutableOnPath("piper") != null) {
            activeBackend = TtsBackend.LOCAL_PIPER;
            log.info("TTS backend: LOCAL_PIPER");
            return;
        }

        // Check for system TTS (platform-specific)
        var osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

        if (osName.contains("mac") || osName.contains("darwin")) {
            // macOS: built-in 'say' command
            if (SpeechToTextService.findExecutableOnPath("say") != null) {
                systemTtsCommand = "say";
                activeBackend = TtsBackend.SYSTEM;
                log.info("TTS backend: SYSTEM (macOS say)");
                return;
            }
        } else if (osName.contains("linux")) {
            // Linux: espeak-ng or espeak
            if (SpeechToTextService.findExecutableOnPath("espeak-ng") != null) {
                systemTtsCommand = "espeak-ng";
                activeBackend = TtsBackend.SYSTEM;
                log.info("TTS backend: SYSTEM (espeak-ng)");
                return;
            }
            if (SpeechToTextService.findExecutableOnPath("espeak") != null) {
                systemTtsCommand = "espeak";
                activeBackend = TtsBackend.SYSTEM;
                log.info("TTS backend: SYSTEM (espeak)");
                return;
            }
        } else if (osName.contains("win")) {
            // Windows: PowerShell System.Speech is always available
            systemTtsCommand = "powershell";
            activeBackend = TtsBackend.SYSTEM;
            log.info("TTS backend: SYSTEM (PowerShell System.Speech)");
            return;
        }

        // Check household TTS
        var householdTts = System.getProperty("wyrdsekai.tts.household-url");
        if (householdTts != null && !householdTts.isBlank()) {
            activeBackend = TtsBackend.HOUSEHOLD;
            log.info("TTS backend: HOUSEHOLD ({})", householdTts);
            return;
        }

        activeBackend = TtsBackend.NONE;
        log.info("TTS backend: NONE -- client will handle TTS locally");
    }

    /**
     * Synthesize text to audio.
     *
     * @param text  Text to synthesize
     * @param voice Voice name/identifier (nullable -- uses default)
     * @return Future completing with synthesis result, or null if TTS unavailable
     */
    public CompletableFuture<SynthesisResult> synthesize(String text, String voice) {
        if (text == null || text.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }

        return switch (activeBackend) {
            case LOCAL_PIPER -> synthesizePiper(text, voice);
            case SYSTEM -> synthesizeSystem(text, voice);
            case HOUSEHOLD -> synthesizeHousehold(text, voice);
            case NONE -> CompletableFuture.completedFuture(null);
        };
    }

    /**
     * Check if TTS is available (any backend detected).
     */
    public boolean isAvailable() {
        return activeBackend != TtsBackend.NONE;
    }

    /**
     * Get the currently active backend.
     */
    public TtsBackend getActiveBackend() {
        return activeBackend;
    }

    /**
     * Override the active backend (for testing or manual configuration).
     */
    public void setActiveBackend(TtsBackend backend) {
        this.activeBackend = backend != null ? backend : TtsBackend.NONE;
    }

    /**
     * List available voices for the current backend.
     *
     * @return List of voice identifiers, or empty if no voices detected
     */
    public List<String> availableVoices() {
        return switch (activeBackend) {
            case LOCAL_PIPER -> List.of("en_US-lessac-medium", "en_US-amy-medium", "en_GB-alan-medium");
            case SYSTEM -> detectSystemVoices();
            case HOUSEHOLD -> List.of("default");
            case NONE -> List.of();
        };
    }

    // --- Internal ---

    /**
     * Synthesize using local Piper TTS.
     * Pipes text to piper process and captures WAV output.
     */
    private CompletableFuture<SynthesisResult> synthesizePiper(String text, String voice) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                var cmd = new ArrayList<String>();
                cmd.add("piper");
                if (voice != null && !voice.isBlank()) {
                    cmd.add("--model");
                    cmd.add(voice);
                }
                cmd.add("--output-raw");

                var pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(false);

                long startMs = System.currentTimeMillis();
                var process = pb.start();
                process.getOutputStream().write(text.getBytes(StandardCharsets.UTF_8));
                process.getOutputStream().close();

                var audioData = process.getInputStream().readAllBytes();
                int exitCode = process.waitFor();
                long durationMs = System.currentTimeMillis() - startMs;

                if (exitCode != 0) {
                    var error = new String(process.getErrorStream().readAllBytes());
                    throw new IOException("Piper TTS exited with code " + exitCode + ": " + error);
                }

                return new SynthesisResult(audioData, "raw", durationMs);
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException("Piper TTS synthesis failed", e);
            }
        });
    }

    /**
     * Synthesize using system TTS.
     * Platform-specific: espeak/say/powershell.
     */
    private CompletableFuture<SynthesisResult> synthesizeSystem(String text, String voice) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                var cmd = new ArrayList<String>();
                long startMs = System.currentTimeMillis();

                if ("say".equals(systemTtsCommand)) {
                    // macOS: say --data-format=LEI16@16000 -o output.wav "text"
                    var tempFile = Files.createTempFile("wyrd-tts-", ".wav");
                    try {
                        cmd.add("say");
                        if (voice != null && !voice.isBlank()) {
                            cmd.add("-v");
                            cmd.add(voice);
                        }
                        cmd.add("--data-format=LEI16@16000");
                        cmd.add("-o");
                        cmd.add(tempFile.toAbsolutePath().toString());
                        cmd.add(text);

                        var process = new ProcessBuilder(cmd).start();
                        process.waitFor();

                        var audioData = Files.readAllBytes(tempFile);
                        long durationMs = System.currentTimeMillis() - startMs;
                        return new SynthesisResult(audioData, "wav", durationMs);
                    } finally {
                        Files.deleteIfExists(tempFile);
                    }
                } else if ("powershell".equals(systemTtsCommand)) {
                    // Windows: PowerShell with System.Speech
                    var tempFile = Files.createTempFile("wyrd-tts-", ".wav");
                    try {
                        var script = String.format(
                            "Add-Type -AssemblyName System.Speech; " +
                            "$synth = New-Object System.Speech.Synthesis.SpeechSynthesizer; " +
                            "$synth.SetOutputToWaveFile('%s'); " +
                            "$synth.Speak('%s'); " +
                            "$synth.Dispose()",
                            tempFile.toAbsolutePath().toString().replace("'", "''"),
                            text.replace("'", "''")
                        );
                        cmd.add("powershell");
                        cmd.add("-Command");
                        cmd.add(script);

                        var process = new ProcessBuilder(cmd).start();
                        process.waitFor();

                        var audioData = Files.readAllBytes(tempFile);
                        long durationMs = System.currentTimeMillis() - startMs;
                        return new SynthesisResult(audioData, "wav", durationMs);
                    } finally {
                        Files.deleteIfExists(tempFile);
                    }
                } else {
                    // Linux: espeak-ng / espeak -- output WAV to stdout
                    cmd.add(systemTtsCommand);
                    if (voice != null && !voice.isBlank()) {
                        cmd.add("-v");
                        cmd.add(voice);
                    }
                    cmd.add("--stdout");
                    cmd.add(text);

                    var process = new ProcessBuilder(cmd).start();
                    var audioData = process.getInputStream().readAllBytes();
                    process.waitFor();
                    long durationMs = System.currentTimeMillis() - startMs;
                    return new SynthesisResult(audioData, "wav", durationMs);
                }
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException("System TTS synthesis failed", e);
            }
        });
    }

    /**
     * Synthesize using household TTS endpoint.
     * POSTs a JSON payload to the household GPU node's TTS service and receives
     * WAV audio bytes back. The endpoint URL is configured via the system property
     * {@code wyrdsekai.tts.household-url}.
     *
     * <p>Request: {@code POST {householdUrl}/api/tts}
     * <pre>{@code
     * { "text": "...", "voice": "..." }
     * }</pre>
     *
     * <p>Response: raw WAV audio bytes ({@code audio/wav}).
     *
     * <p>On any failure (timeout, connection refused, non-2xx status), returns {@code null}
     * and logs a warning so the caller can fall back gracefully.
     */
    private CompletableFuture<SynthesisResult> synthesizeHousehold(String text, String voice) {
        return CompletableFuture.supplyAsync(() -> {
            var householdUrl = System.getProperty("wyrdsekai.tts.household-url");
            if (householdUrl == null || householdUrl.isBlank()) {
                log.warn("Household TTS URL not configured (wyrdsekai.tts.household-url)");
                return null;
            }

            // Strip trailing slash for clean URL construction
            if (householdUrl.endsWith("/")) {
                householdUrl = householdUrl.substring(0, householdUrl.length() - 1);
            }

            try {
                var client = getOrCreateHouseholdHttpClient();

                // Build JSON payload -- manual construction to avoid Jackson dependency in this class
                var jsonBody = buildJsonPayload(text, voice);

                var request = HttpRequest.newBuilder()
                    .uri(URI.create(householdUrl + "/api/tts"))
                    .header("Content-Type", "application/json")
                    .header("Accept", "audio/wav")
                    .timeout(HOUSEHOLD_TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                    .build();

                long startMs = System.currentTimeMillis();
                var response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                long durationMs = System.currentTimeMillis() - startMs;

                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    var audioData = response.body();
                    if (audioData == null || audioData.length == 0) {
                        log.warn("Household TTS returned empty audio response");
                        return null;
                    }
                    log.debug("Household TTS synthesized {} bytes in {}ms", audioData.length, durationMs);
                    return new SynthesisResult(audioData, "wav", durationMs);
                } else {
                    log.warn("Household TTS returned HTTP {}: {}",
                        response.statusCode(),
                        truncateBody(response.body(), 256));
                    return null;
                }
            } catch (ConnectException e) {
                log.warn("Household TTS connection refused ({}): {}", householdUrl, e.getMessage());
                return null;
            } catch (HttpTimeoutException e) {
                log.warn("Household TTS request timed out ({}): {}", householdUrl, e.getMessage());
                return null;
            } catch (IOException e) {
                log.warn("Household TTS I/O error ({}): {}", householdUrl, e.getMessage());
                return null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Household TTS interrupted");
                return null;
            } catch (Exception e) {
                log.warn("Household TTS unexpected error: {}", e.getMessage(), e);
                return null;
            }
        });
    }

    /**
     * Get or lazily create the HTTP client used for household TTS calls.
     * Double-checked locking for thread safety.
     */
    private HttpClient getOrCreateHouseholdHttpClient() {
        var client = householdHttpClient;
        if (client == null) {
            synchronized (this) {
                client = householdHttpClient;
                if (client == null) {
                    client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .build();
                    householdHttpClient = client;
                }
            }
        }
        return client;
    }

    /**
     * Build a JSON payload for the household TTS request.
     * Escapes text for safe JSON embedding without requiring a JSON library.
     */
    private static String buildJsonPayload(String text, String voice) {
        var sb = new StringBuilder(text.length() + 64);
        sb.append("{\"text\":\"");
        escapeJson(sb, text);
        sb.append('"');
        if (voice != null && !voice.isBlank()) {
            sb.append(",\"voice\":\"");
            escapeJson(sb, voice);
            sb.append('"');
        }
        sb.append('}');
        return sb.toString();
    }

    /**
     * Escape a string for safe embedding in JSON.
     */
    private static void escapeJson(StringBuilder sb, String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
    }

    /**
     * Truncate a byte array to a string for error logging.
     */
    private static String truncateBody(byte[] body, int maxLen) {
        if (body == null || body.length == 0) return "(empty)";
        var s = new String(body, 0, Math.min(body.length, maxLen), StandardCharsets.UTF_8);
        return body.length > maxLen ? s + "..." : s;
    }

    /**
     * Detect available system voices.
     */
    private List<String> detectSystemVoices() {
        if (systemTtsCommand == null) return List.of();
        // For now return a placeholder -- actual enumeration is platform-specific
        return switch (systemTtsCommand) {
            case "say" -> List.of("Alex", "Samantha", "Daniel", "Karen");
            case "espeak-ng", "espeak" -> List.of("en", "en-us", "en-gb", "ja", "es");
            case "powershell" -> List.of("Microsoft David", "Microsoft Zira");
            default -> List.of("default");
        };
    }
}
