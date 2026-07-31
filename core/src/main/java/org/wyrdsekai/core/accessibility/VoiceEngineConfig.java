package org.wyrdsekai.core.accessibility;

import java.util.*;

/**
 * TTS voice engine configuration (§103.7).
 * Default: Kokoro-82M (82M params, 54 voices, Apache 2.0).
 * Runs on-device — no cloud dependency for basic voice.
 *
 * HARD BOUNDARY: Voice cloning for deceased persons is banned (§99).
 */
public record VoiceEngineConfig(
    String engineType,
    String modelPath,
    String defaultVoice,
    double defaultRate,
    double defaultPitch,
    String language,
    boolean enabled,
    Set<String> bannedVoiceCloneTargets
) {

    /** Default Kokoro-82M config. */
    public static VoiceEngineConfig kokoroDefault() {
        return new VoiceEngineConfig(
            "kokoro-82m",
            "models/kokoro-v1.0.onnx",
            "af_heart",
            1.0, 1.0,
            "auto",
            false,
            Set.of()
        );
    }

    /** Minimal config for low-resource devices. */
    public static VoiceEngineConfig minimal() {
        return new VoiceEngineConfig(
            "kokoro-82m",
            "models/kokoro-v1.0.onnx",
            "af_heart",
            1.0, 1.0,
            "en",
            false,
            Set.of()
        );
    }

    /** Disabled config. */
    public static VoiceEngineConfig disabled() {
        return new VoiceEngineConfig("none", "", "", 1.0, 1.0, "", false, Set.of());
    }

    /** Whether voice cloning is allowed for a given target. */
    public boolean canCloneVoice(String targetIdentifier) {
        return !bannedVoiceCloneTargets.contains(targetIdentifier);
    }

    /** Voice cloning is ALWAYS banned for deceased persons (§99). */
    public boolean isDeceasedVoiceCloningBanned() {
        return true; // Hard boundary, not configurable
    }

    /** Available Kokoro voices (subset — full list has 54). */
    public static List<String> availableVoices() {
        return List.of(
            "af_heart", "af_bella", "af_nicole", "af_sarah", "af_sky",
            "am_adam", "am_michael",
            "bf_emma", "bf_isabella",
            "bm_george", "bm_lewis"
        );
    }

    /** Configure for specific user preferences. */
    public VoiceEngineConfig withUserPreferences(AccessibilityPreferences prefs) {
        if (!prefs.ttsEnabled()) return disabled();
        return new VoiceEngineConfig(
            engineType, modelPath,
            prefs.ttsVoice().equals("default") ? defaultVoice : prefs.ttsVoice(),
            prefs.ttsRate(), defaultPitch,
            language, true, bannedVoiceCloneTargets
        );
    }
}
