package org.wyrdsekai.core.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;

/**
 * Speech-to-text extractor for voice recordings.
 *
 * <p>Platform-specific implementations:
 * <ul>
 *   <li>Android: Whisper via ONNX Runtime</li>
 *   <li>iOS: Whisper via CoreML or Apple Speech framework</li>
 *   <li>Server: whisper.cpp command-line tool</li>
 * </ul>
 *
 * <p>This server-side implementation uses whisper.cpp (if available).</p>
 */
public class VoiceExtractor implements ContentExtractor {

    private static final Logger log = LoggerFactory.getLogger(VoiceExtractor.class);
    private final boolean whisperAvailable;
    private final String whisperPath;

    public VoiceExtractor() {
        this.whisperPath = findWhisper();
        this.whisperAvailable = whisperPath != null;
    }

    @Override
    public boolean canExtract(String mimeType) {
        return mimeType != null && (
            mimeType.startsWith("audio/") ||
            "application/ogg".equals(mimeType)
        );
    }

    @Override
    public String extract(IngestContent content) {
        if (!content.hasBinaryData()) return null;
        if (!whisperAvailable) {
            log.debug("Whisper not available — skipping transcription for {}", content.id());
            return null;
        }

        try {
            var tempAudio = Files.createTempFile("ingest-voice-", suffix(content.mimeType()));
            Files.write(tempAudio, content.data());

            var process = new ProcessBuilder(whisperPath,
                "-f", tempAudio.toString(),
                "--no-timestamps",
                "-nt")
                .redirectErrorStream(true)
                .start();
            var output = new String(process.getInputStream().readAllBytes()).strip();
            int exitCode = process.waitFor();

            Files.deleteIfExists(tempAudio);

            if (exitCode == 0 && !output.isBlank()) {
                return output;
            }

            log.warn("Whisper transcription failed for {}: exit code {}", content.id(), exitCode);
            return null;
        } catch (Exception e) {
            log.warn("Voice extraction failed for {}: {}", content.id(), e.getMessage());
            return null;
        }
    }

    @Override
    public String name() {
        return "voice-whisper";
    }

    /** Whether Whisper is available on this system. */
    public boolean isAvailable() {
        return whisperAvailable;
    }

    private static String findWhisper() {
        // Check common whisper.cpp paths
        for (var path : new String[]{"whisper", "whisper-cpp", "main"}) {
            try {
                var process = new ProcessBuilder(path, "--help")
                    .redirectErrorStream(true)
                    .start();
                process.waitFor();
                if (process.exitValue() == 0) return path;
            } catch (Exception e) {
                // not found
            }
        }
        return null;
    }

    private static String suffix(String mimeType) {
        return switch (mimeType) {
            case "audio/wav", "audio/wave" -> ".wav";
            case "audio/ogg", "application/ogg" -> ".ogg";
            case "audio/mp3", "audio/mpeg" -> ".mp3";
            case "audio/flac" -> ".flac";
            default -> ".wav";
        };
    }
}
