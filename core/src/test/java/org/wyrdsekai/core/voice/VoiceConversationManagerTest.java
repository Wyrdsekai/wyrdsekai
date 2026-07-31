package org.wyrdsekai.core.voice;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link VoiceConversationManager} -- voice conversation lifecycle.
 */
class VoiceConversationManagerTest {

    private VoiceService voiceService;
    private SpeechToTextService stt;
    private TextToSpeechService tts;
    private VoiceConversationManager manager;

    @BeforeEach
    void setUp() {
        VoiceService.init();
        SpeechToTextService.init();
        TextToSpeechService.init();
        voiceService = VoiceService.get();
        stt = SpeechToTextService.get();
        tts = TextToSpeechService.get();
        manager = new VoiceConversationManager(stt, tts, voiceService);
    }

    @AfterEach
    void tearDown() {
        VoiceService.reset();
        SpeechToTextService.reset();
        TextToSpeechService.reset();
    }

    @Test
    void process_disabled_returns_disabled_response() throws Exception {
        voiceService.setMode(VoiceMode.DISABLED);

        var response = manager.processVoiceInput("player1", new byte[]{1, 2, 3}, "wav").get();
        assertThat(response.isEmpty()).isTrue();
        assertThat(response.transcribedText()).isNull();
        assertThat(response.responseAudio()).isNull();
    }

    @Test
    void process_enabled_but_no_stt_returns_disabled() throws Exception {
        voiceService.setMode(VoiceMode.PUSH_TO_TALK);
        // STT has no backend by default (NONE)
        assertThat(stt.isAvailable()).isFalse();

        var response = manager.processVoiceInput("player1", new byte[]{1, 2, 3}, "wav").get();
        assertThat(response.isEmpty()).isTrue();
    }

    @Test
    void process_with_stt_available_transcribes() throws Exception {
        voiceService.setMode(VoiceMode.PUSH_TO_TALK);

        // Create a mock STT service that returns a fixed transcription
        var mockStt = new MockSpeechToTextService("Hello world", "en", 0.95, 100);
        var mockManager = new VoiceConversationManager(mockStt, tts, voiceService);

        var response = mockManager.processVoiceInput("player1", new byte[]{1, 2, 3}, "wav").get();
        assertThat(response.isEmpty()).isFalse();
        assertThat(response.transcribedText()).isEqualTo("Hello world");
        // No response audio since TTS is not available
        assertThat(response.responseAudio()).isNull();
    }

    @Test
    void synthesize_with_no_tts_returns_null() throws Exception {
        // TTS has no backend by default (NONE)
        assertThat(tts.isAvailable()).isFalse();

        var audio = manager.synthesizeResponse("Hello world", null).get();
        assertThat(audio).isNull();
    }

    @Test
    void synthesize_with_tts_available_returns_audio() throws Exception {
        // Create a mock TTS service that returns fixed audio
        var mockTts = new MockTextToSpeechService(new byte[]{42, 43, 44}, "wav", 50);
        var mockManager = new VoiceConversationManager(stt, mockTts, voiceService);

        var audio = mockManager.synthesizeResponse("Hello world", "default").get();
        assertThat(audio).isNotNull();
        assertThat(audio).containsExactly(42, 43, 44);
    }

    @Test
    void isInputAvailable_requires_voice_enabled_and_stt() {
        // Both disabled
        voiceService.setMode(VoiceMode.DISABLED);
        assertThat(manager.isInputAvailable()).isFalse();

        // Voice enabled but no STT
        voiceService.setMode(VoiceMode.PUSH_TO_TALK);
        assertThat(manager.isInputAvailable()).isFalse();

        // Both enabled
        stt.setActiveBackend(SpeechToTextService.SttBackend.LOCAL_WHISPER);
        assertThat(manager.isInputAvailable()).isTrue();
    }

    @Test
    void isOutputAvailable_requires_tts() {
        assertThat(manager.isOutputAvailable()).isFalse();

        tts.setActiveBackend(TextToSpeechService.TtsBackend.SYSTEM);
        assertThat(manager.isOutputAvailable()).isTrue();
    }

    @Test
    void voiceResponse_disabled_factory() {
        var response = VoiceConversationManager.VoiceResponse.disabled();
        assertThat(response.isEmpty()).isTrue();
        assertThat(response.transcribedText()).isNull();
        assertThat(response.responseAudio()).isNull();
    }

    @Test
    void fromSingletons_works_when_all_initialized() {
        var mgr = VoiceConversationManager.fromSingletons();
        assertThat(mgr).isNotNull();
        assertThat(mgr.voiceService()).isSameAs(voiceService);
    }

    @Test
    void full_roundtrip_audio_in_text_out_synthesis() throws Exception {
        voiceService.setMode(VoiceMode.PUSH_TO_TALK);

        var mockStt = new MockSpeechToTextService("What is the weather?", "en", 0.9, 200);
        var mockTts = new MockTextToSpeechService(new byte[]{1, 2, 3, 4}, "wav", 100);
        var roundtripManager = new VoiceConversationManager(mockStt, mockTts, voiceService);

        // Step 1: Audio in -> text out
        var response = roundtripManager.processVoiceInput("player1", new byte[]{10, 20, 30}, "wav").get();
        assertThat(response.transcribedText()).isEqualTo("What is the weather?");

        // Step 2: Text -> synthesis
        var audio = roundtripManager.synthesizeResponse(
            "It is sunny and 22 degrees.", "Ma").get();
        assertThat(audio).containsExactly(1, 2, 3, 4);
    }

    // --- Mock implementations ---

    /** Mock STT service that returns a fixed transcription. */
    private static class MockSpeechToTextService extends SpeechToTextService {
        private final String text;
        private final String language;
        private final double confidence;
        private final long durationMs;

        MockSpeechToTextService(String text, String language, double confidence, long durationMs) {
            this.text = text;
            this.language = language;
            this.confidence = confidence;
            this.durationMs = durationMs;
            setActiveBackend(SttBackend.LOCAL_WHISPER);
        }

        @Override
        public CompletableFuture<TranscriptionResult> transcribe(byte[] audioData, String format) {
            return CompletableFuture.completedFuture(
                new TranscriptionResult(text, language, confidence, durationMs));
        }

        @Override
        public boolean isAvailable() { return true; }
    }

    /** Mock TTS service that returns fixed audio. */
    private static class MockTextToSpeechService extends TextToSpeechService {
        private final byte[] audioData;
        private final String format;
        private final long durationMs;

        MockTextToSpeechService(byte[] audioData, String format, long durationMs) {
            this.audioData = audioData;
            this.format = format;
            this.durationMs = durationMs;
            setActiveBackend(TtsBackend.SYSTEM);
        }

        @Override
        public CompletableFuture<SynthesisResult> synthesize(String text, String voice) {
            return CompletableFuture.completedFuture(
                new SynthesisResult(audioData, format, durationMs));
        }

        @Override
        public boolean isAvailable() { return true; }
    }
}
