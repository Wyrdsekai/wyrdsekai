package org.wyrdsekai.core.voice;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.protocol.C2SMessage;
import org.wyrdsekai.common.protocol.S2CMessage;
import org.wyrdsekai.common.util.Json;

import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for voice protocol messages and end-to-end voice flow.
 */
class VoiceIntegrationTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = Json.mapper();
        VoiceService.init();
        SpeechToTextService.init();
        TextToSpeechService.init();
    }

    @AfterEach
    void tearDown() {
        VoiceService.reset();
        SpeechToTextService.reset();
        TextToSpeechService.reset();
    }

    @Test
    void voiceAudio_c2s_serializes_and_deserializes() throws Exception {
        var audioBytes = "Hello audio data".getBytes();
        var audioBase64 = Base64.getEncoder().encodeToString(audioBytes);

        var msg = new C2SMessage.VoiceAudio("req-1", audioBase64, "wav");
        var json = mapper.writeValueAsString(msg);

        assertThat(json).contains("\"type\":\"voice_audio\"");
        assertThat(json).contains("\"format\":\"wav\"");
        assertThat(json).contains("\"id\":\"req-1\"");

        var deserialized = mapper.readValue(json, C2SMessage.class);
        assertThat(deserialized).isInstanceOf(C2SMessage.VoiceAudio.class);

        var voiceAudio = (C2SMessage.VoiceAudio) deserialized;
        assertThat(voiceAudio.id()).isEqualTo("req-1");
        assertThat(voiceAudio.format()).isEqualTo("wav");
        assertThat(Base64.getDecoder().decode(voiceAudio.audioBase64())).isEqualTo(audioBytes);
    }

    @Test
    void voiceAudio_s2c_serializes_and_deserializes() throws Exception {
        var audioBytes = "Synthesized audio".getBytes();
        var audioBase64 = Base64.getEncoder().encodeToString(audioBytes);

        var msg = new S2CMessage.VoiceAudio(42L, audioBase64, "wav", "Ma");
        var json = mapper.writeValueAsString(msg);

        assertThat(json).contains("\"type\":\"voice_audio\"");
        assertThat(json).contains("\"format\":\"wav\"");
        assertThat(json).contains("\"speaker\":\"Ma\"");
        assertThat(json).contains("\"seq\":42");

        var deserialized = mapper.readValue(json, S2CMessage.class);
        assertThat(deserialized).isInstanceOf(S2CMessage.VoiceAudio.class);

        var voiceAudio = (S2CMessage.VoiceAudio) deserialized;
        assertThat(voiceAudio.seq()).isEqualTo(42L);
        assertThat(voiceAudio.speaker()).isEqualTo("Ma");
        assertThat(voiceAudio.format()).isEqualTo("wav");
        assertThat(Base64.getDecoder().decode(voiceAudio.audioBase64())).isEqualTo(audioBytes);
    }

    @Test
    void voice_flag_on_say_c2s() throws Exception {
        // Say with voice=true
        var say = new C2SMessage.Say("say-1", "nexus", "What time is it?", null, true);
        var json = mapper.writeValueAsString(say);

        assertThat(json).contains("\"voice\":true");
        assertThat(say.isVoice()).isTrue();

        var deserialized = mapper.readValue(json, C2SMessage.class);
        assertThat(deserialized).isInstanceOf(C2SMessage.Say.class);
        assertThat(((C2SMessage.Say) deserialized).isVoice()).isTrue();
    }

    @Test
    void voice_flag_on_prose_s2c() throws Exception {
        // Prose with voice=true
        var prose = new S2CMessage.Prose(10L, "Ma", "It is 3 PM.",
            List.of(), null, "normal", "en", true, List.of(), true);
        var json = mapper.writeValueAsString(prose);

        assertThat(json).contains("\"voice\":true");
        assertThat(prose.isVoice()).isTrue();

        var deserialized = mapper.readValue(json, S2CMessage.class);
        assertThat(deserialized).isInstanceOf(S2CMessage.Prose.class);
        assertThat(((S2CMessage.Prose) deserialized).isVoice()).isTrue();
    }

    @Test
    void voice_flag_absent_defaults_to_not_voice() throws Exception {
        // Backward-compatible Say without voice flag
        var say = new C2SMessage.Say("say-2", "nexus", "Hello");
        assertThat(say.isVoice()).isFalse();

        var json = mapper.writeValueAsString(say);
        var deserialized = (C2SMessage.Say) mapper.readValue(json, C2SMessage.class);
        assertThat(deserialized.isVoice()).isFalse();
    }

    @Test
    void voice_mode_permission_lifecycle() {
        var voiceService = VoiceService.get();

        // Default: disabled
        assertThat(voiceService.getMode()).isEqualTo(VoiceMode.DISABLED);
        assertThat(voiceService.isVoiceEnabled()).isFalse();

        // Enable push-to-talk
        voiceService.setMode(VoiceMode.PUSH_TO_TALK);
        assertThat(voiceService.isVoiceEnabled()).isTrue();
        assertThat(voiceService.shouldSpeakResponse()).isTrue();

        // Upgrade to wake word
        voiceService.setMode(VoiceMode.WAKE_WORD);
        assertThat(voiceService.isVoiceEnabled()).isTrue();

        // Upgrade to always-on
        voiceService.setMode(VoiceMode.ALWAYS_ON);
        assertThat(voiceService.isVoiceEnabled()).isTrue();

        // Disable
        voiceService.setMode(VoiceMode.DISABLED);
        assertThat(voiceService.isVoiceEnabled()).isFalse();
    }

    @Test
    void voice_upgrade_request_access_action_format() throws Exception {
        // Verify the format described in the spec for request_access voice upgrade
        var requestJson = """
            {"action": "request_access", "source": "voice", "scope": "push_to_talk",
             "reason": "I'd love to chat with you while you're walking. Want to enable voice?"}
            """;
        var node = mapper.readTree(requestJson);
        assertThat(node.get("source").asText()).isEqualTo("voice");
        assertThat(node.get("scope").asText()).isEqualTo("push_to_talk");
        assertThat(node.get("reason").asText()).contains("voice");
    }

    @Test
    void stt_and_tts_services_coordinate_with_voice_service() {
        var stt = SpeechToTextService.get();
        var tts = TextToSpeechService.get();
        var voiceService = VoiceService.get();
        var manager = new VoiceConversationManager(stt, tts, voiceService);

        // Voice disabled: input not available regardless of STT
        voiceService.setMode(VoiceMode.DISABLED);
        stt.setActiveBackend(SpeechToTextService.SttBackend.LOCAL_WHISPER);
        assertThat(manager.isInputAvailable()).isFalse();

        // Voice enabled + STT available: input available
        voiceService.setMode(VoiceMode.PUSH_TO_TALK);
        assertThat(manager.isInputAvailable()).isTrue();

        // TTS controls output availability independently
        assertThat(manager.isOutputAvailable()).isFalse();
        tts.setActiveBackend(TextToSpeechService.TtsBackend.SYSTEM);
        assertThat(manager.isOutputAvailable()).isTrue();
    }
}
