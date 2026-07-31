package org.wyrdsekai.core.voice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

/**
 * Manages the voice conversation lifecycle:
 * receives audio, transcribes, feeds to agent, gets response, synthesizes.
 *
 * <p>Pipeline:
 * <pre>
 *   Audio bytes → STT (transcribe) → text → Say event with voice=true →
 *   Agent processes → response with voice=true →
 *   TTS (synthesize) → audio bytes back to client
 * </pre>
 *
 * <p>If TTS is unavailable, the client handles speech synthesis locally
 * using the text Prose with voice=true flag.
 *
 * @see SpeechToTextService
 * @see TextToSpeechService
 * @see VoiceService
 */
public class VoiceConversationManager {

    private static final Logger log = LoggerFactory.getLogger(VoiceConversationManager.class);

    private final SpeechToTextService stt;
    private final TextToSpeechService tts;
    private final VoiceService voiceService;

    /**
     * Create a VoiceConversationManager with explicit service references.
     *
     * @param stt          Speech-to-text service
     * @param tts          Text-to-speech service
     * @param voiceService Voice mode manager
     */
    public VoiceConversationManager(SpeechToTextService stt, TextToSpeechService tts,
                                     VoiceService voiceService) {
        this.stt = stt;
        this.tts = tts;
        this.voiceService = voiceService;
    }

    /**
     * Create a VoiceConversationManager using global singleton services.
     * Requires that all three services have been initialized.
     *
     * @throws IllegalStateException if any service is not initialized
     */
    public static VoiceConversationManager fromSingletons() {
        var stt = SpeechToTextService.get();
        var tts = TextToSpeechService.get();
        var voice = VoiceService.get();
        if (stt == null || tts == null || voice == null) {
            throw new IllegalStateException(
                "Voice services not initialized. Call SpeechToTextService.init(), " +
                "TextToSpeechService.init(), and VoiceService.init() first.");
        }
        return new VoiceConversationManager(stt, tts, voice);
    }

    /** Result of processing voice input. */
    public record VoiceResponse(
        /** Transcribed text from the audio input. Null if voice is disabled or STT failed. */
        String transcribedText,
        /** Synthesized audio response. Null if TTS is unavailable. */
        byte[] responseAudio
    ) {
        /** Response when voice is disabled. */
        public static VoiceResponse disabled() {
            return new VoiceResponse(null, null);
        }

        /** Check if this response represents a disabled/failed state. */
        public boolean isEmpty() {
            return transcribedText == null;
        }
    }

    /**
     * Process incoming voice audio from a player.
     * Transcribes the audio to text. The caller is responsible for routing
     * the transcribed text as a Say event to the room.
     *
     * @param playerId Player who sent the audio
     * @param audio    Raw audio bytes
     * @param format   Audio format ("wav", "pcm16", "opus", "mp3")
     * @return Future completing with the voice response
     */
    public CompletableFuture<VoiceResponse> processVoiceInput(String playerId, byte[] audio, String format) {
        if (!voiceService.isVoiceEnabled()) {
            log.debug("Voice disabled -- ignoring audio from {}", playerId);
            return CompletableFuture.completedFuture(VoiceResponse.disabled());
        }

        if (!stt.isAvailable()) {
            log.warn("Voice enabled but no STT backend available for {}", playerId);
            return CompletableFuture.completedFuture(VoiceResponse.disabled());
        }

        log.debug("Transcribing {} bytes of {} audio from {}", audio.length, format, playerId);

        return stt.transcribe(audio, format)
            .thenApply(transcription -> {
                log.debug("Transcribed for {}: '{}' (confidence={}, lang={}, {}ms)",
                    playerId, transcription.text(), transcription.confidence(),
                    transcription.language(), transcription.durationMs());
                return new VoiceResponse(transcription.text(), null);
            })
            .exceptionally(ex -> {
                log.warn("STT transcription failed for {}: {}", playerId, ex.getMessage());
                return VoiceResponse.disabled();
            });
    }

    /**
     * Synthesize an agent's text response to audio.
     * Returns null if TTS is not available -- the client will use its own TTS.
     *
     * @param text       Text to synthesize
     * @param agentVoice Voice identifier for the agent (nullable -- uses default)
     * @return Future completing with audio bytes, or null if TTS unavailable
     */
    public CompletableFuture<byte[]> synthesizeResponse(String text, String agentVoice) {
        if (!tts.isAvailable()) {
            return CompletableFuture.completedFuture(null);
        }

        return tts.synthesize(text, agentVoice)
            .thenApply(result -> result != null ? result.audioData() : null)
            .exceptionally(ex -> {
                log.warn("TTS synthesis failed: {}", ex.getMessage());
                return null;
            });
    }

    /**
     * Check if voice input processing is available (voice enabled + STT available).
     */
    public boolean isInputAvailable() {
        return voiceService.isVoiceEnabled() && stt.isAvailable();
    }

    /**
     * Check if voice output synthesis is available (TTS available).
     */
    public boolean isOutputAvailable() {
        return tts.isAvailable();
    }

    /**
     * Get the underlying voice service (for mode checks).
     */
    public VoiceService voiceService() {
        return voiceService;
    }
}
