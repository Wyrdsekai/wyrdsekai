package org.wyrdsekai.core.voice;

/**
 * Manages voice mode and routing for companion agents.
 * Singleton pattern — initialized at startup, accessed via {@link #get()}.
 *
 * <p>This is a service interface. Actual STT (Speech-to-Text) and TTS (Text-to-Speech)
 * are platform-specific (phone/desktop). This service manages the mode state and
 * provides routing decisions.</p>
 *
 * <p>Voice pipeline: Microphone → Local VAD → STT → Text (Say event) →
 * Agent responds → TTS → Speaker</p>
 *
 * @see VoiceMode
 */
public class VoiceService {

    private volatile VoiceMode mode = VoiceMode.DISABLED;

    /** Global singleton instance. */
    private static volatile VoiceService instance;

    /** Initialize the global instance. Called by Main.java at startup. */
    public static void init() { instance = new VoiceService(); }

    /** Get the global instance. May be null if not initialized. */
    public static VoiceService get() { return instance; }

    /** Reset for testing. */
    public static void reset() { instance = null; }

    /**
     * Set the voice mode.
     *
     * @param mode New voice mode
     */
    public void setMode(VoiceMode mode) {
        this.mode = mode != null ? mode : VoiceMode.DISABLED;
    }

    /**
     * Get the current voice mode.
     *
     * @return Current mode (never null)
     */
    public VoiceMode getMode() {
        return mode;
    }

    /**
     * Check if voice is enabled (any mode other than DISABLED).
     */
    public boolean isVoiceEnabled() {
        return mode != VoiceMode.DISABLED;
    }

    /**
     * Check if the agent's response should be spoken aloud.
     * True when voice is enabled in any mode.
     */
    public boolean shouldSpeakResponse() {
        return isVoiceEnabled();
    }

    /**
     * Process voice input (route to STT).
     * This is a stub — actual STT is platform-specific.
     *
     * @param audio Raw audio bytes
     * @return Transcribed text, or null if STT is unavailable
     */
    public String processVoiceInput(byte[] audio) {
        // Stub: actual implementation delegates to platform-specific STT
        // (Whisper on device, Whisper on household GPU, etc.)
        return null;
    }
}
