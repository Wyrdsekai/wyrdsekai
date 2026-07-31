package org.wyrdsekai.server.voice;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Edge STT configuration for voice input (§55).
 * Configures the local speech-to-text model used by VoiceAdapter.
 */
public record SttConfig(
    String modelPath,
    String language,
    int beamSize,
    int sampleRate,
    boolean translateToEnglish,
    float vadThreshold,
    int maxDurationSeconds
) {
    /** Default config for Whisper.cpp medium model. */
    public static final SttConfig DEFAULT = new SttConfig(
        "models/ggml-medium.bin", "auto", 5, 16000, false, 0.5f, 60
    );

    /** Minimal config for low-resource devices. */
    public static final SttConfig MINIMAL = new SttConfig(
        "models/ggml-tiny.bin", "en", 1, 16000, false, 0.6f, 30
    );

    public SttConfig {
        if (modelPath == null || modelPath.isBlank()) throw new IllegalArgumentException("modelPath required");
        if (beamSize < 1) throw new IllegalArgumentException("beamSize must be >= 1");
        if (sampleRate < 8000) throw new IllegalArgumentException("sampleRate must be >= 8000");
        if (maxDurationSeconds < 1) throw new IllegalArgumentException("maxDurationSeconds must be >= 1");
    }

    /** Check if the model file appears to exist (relative to working directory). */
    public boolean modelExists() {
        return Files.isRegularFile(Path.of(modelPath));
    }
}
