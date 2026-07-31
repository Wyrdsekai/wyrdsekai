package org.wyrdsekai.core.library;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Local audio transcription using whisper.cpp.
 * Rudimentary v1 — uses the CLI binary, not a library binding.
 *
 * Requires: whisper-cpp installed (brew install whisper-cpp on macOS,
 * or build from source on Linux). Uses the "base" model by default
 * (~150MB, decent accuracy, fast).
 *
 * Future: could use whisper.cpp HTTP server mode or Ollama's Whisper support.
 */
public final class WhisperTranscriber {

    private static final Logger log = LoggerFactory.getLogger(WhisperTranscriber.class);

    private final String whisperBinary;
    private final String modelPath; // nullable — uses default model if null

    public WhisperTranscriber(String whisperBinary, String modelPath) {
        this.whisperBinary = whisperBinary;
        this.modelPath = modelPath;
    }

    /** Create with auto-detected binary. */
    public static WhisperTranscriber autoDetect() {
        // Try common locations
        for (var path : new String[]{
            "whisper-cpp", "/opt/homebrew/bin/whisper-cpp",
            "whisper", "/usr/local/bin/whisper"
        }) {
            try {
                var proc = new ProcessBuilder(path, "--help")
                    .redirectErrorStream(true)
                    .start();
                proc.waitFor(5, TimeUnit.SECONDS);
                if (proc.exitValue() == 0 || proc.exitValue() == 1) {
                    log.info("[Whisper] Found whisper at: {}", path);
                    return new WhisperTranscriber(path, null);
                }
            } catch (Exception ignored) {}
        }
        log.info("[Whisper] whisper-cpp not found — audio transcription disabled");
        return null;
    }

    /** Check if Whisper is available. */
    public boolean isAvailable() {
        return whisperBinary != null;
    }

    /** Transcription result. */
    public record TranscriptionResult(String text, long durationMs, boolean success, String error) {}

    /**
     * Transcribe an audio file to text.
     * Supports: wav, mp3, m4a, ogg, flac (whisper.cpp handles conversion).
     *
     * @param audioFile Path to the audio file
     * @return TranscriptionResult with the transcribed text
     */
    public TranscriptionResult transcribe(Path audioFile) {
        if (!Files.exists(audioFile)) {
            return new TranscriptionResult(null, 0, false, "File not found: " + audioFile);
        }

        long start = System.currentTimeMillis();

        try {
            var cmd = new ArrayList<String>();
            cmd.add(whisperBinary);
            cmd.add("-f"); cmd.add(audioFile.toAbsolutePath().toString());
            cmd.add("--no-timestamps");
            cmd.add("--output-txt");
            if (modelPath != null) {
                cmd.add("-m"); cmd.add(modelPath);
            }

            var pb = new ProcessBuilder(cmd)
                .redirectErrorStream(true);
            var proc = pb.start();

            var output = new String(proc.getInputStream().readAllBytes());
            boolean finished = proc.waitFor(300, TimeUnit.SECONDS); // 5 min max
            long elapsed = System.currentTimeMillis() - start;

            if (!finished) {
                proc.destroyForcibly();
                return new TranscriptionResult(null, elapsed, false, "Transcription timed out (5 min)");
            }

            if (proc.exitValue() != 0) {
                return new TranscriptionResult(null, elapsed, false,
                    "Whisper exited with code " + proc.exitValue() + ": " + output.substring(0, Math.min(200, output.length())));
            }

            // whisper-cpp with --output-txt writes to a .txt file alongside the audio
            var txtFile = Path.of(audioFile + ".txt");
            String text;
            if (Files.exists(txtFile)) {
                text = Files.readString(txtFile).trim();
                Files.deleteIfExists(txtFile); // Clean up
            } else {
                // Some versions output to stdout
                text = output.trim();
            }

            if (text.isBlank()) {
                return new TranscriptionResult(null, elapsed, false, "No speech detected");
            }

            log.info("[Whisper] Transcribed {} in {}s ({} chars)",
                audioFile.getFileName(), elapsed / 1000, text.length());
            return new TranscriptionResult(text, elapsed, true, null);

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            return new TranscriptionResult(null, elapsed, false, e.getMessage());
        }
    }

    /**
     * Transcribe and index into a user's Study as a voice memo.
     */
    public String transcribeAndIndex(Path audioFile, String userDid, StudyService studyService) {
        var result = transcribe(audioFile);
        if (!result.success()) {
            log.warn("[Whisper] Transcription failed for {}: {}", audioFile.getFileName(), result.error());
            return null;
        }

        return studyService.addVoiceMemo(userDid, result.text(), audioFile.getFileName().toString());
    }
}
