package org.wyrdsekai.core.voice;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link VoiceService} — voice mode management and routing.
 */
class VoiceServiceTest {

    private VoiceService service;

    @BeforeEach
    void setUp() {
        VoiceService.init();
        service = VoiceService.get();
    }

    @AfterEach
    void tearDown() {
        VoiceService.reset();
    }

    @Test
    void default_mode_is_disabled() {
        assertThat(service.getMode()).isEqualTo(VoiceMode.DISABLED);
    }

    @Test
    void set_mode_and_retrieve() {
        service.setMode(VoiceMode.PUSH_TO_TALK);
        assertThat(service.getMode()).isEqualTo(VoiceMode.PUSH_TO_TALK);

        service.setMode(VoiceMode.WAKE_WORD);
        assertThat(service.getMode()).isEqualTo(VoiceMode.WAKE_WORD);

        service.setMode(VoiceMode.ALWAYS_ON);
        assertThat(service.getMode()).isEqualTo(VoiceMode.ALWAYS_ON);
    }

    @Test
    void isVoiceEnabled_for_each_mode() {
        service.setMode(VoiceMode.DISABLED);
        assertThat(service.isVoiceEnabled()).isFalse();

        service.setMode(VoiceMode.PUSH_TO_TALK);
        assertThat(service.isVoiceEnabled()).isTrue();

        service.setMode(VoiceMode.WAKE_WORD);
        assertThat(service.isVoiceEnabled()).isTrue();

        service.setMode(VoiceMode.ALWAYS_ON);
        assertThat(service.isVoiceEnabled()).isTrue();
    }

    @Test
    void shouldSpeakResponse_when_voice_enabled() {
        service.setMode(VoiceMode.DISABLED);
        assertThat(service.shouldSpeakResponse()).isFalse();

        service.setMode(VoiceMode.PUSH_TO_TALK);
        assertThat(service.shouldSpeakResponse()).isTrue();
    }

    @Test
    void disabled_means_no_speech() {
        service.setMode(VoiceMode.DISABLED);
        assertThat(service.isVoiceEnabled()).isFalse();
        assertThat(service.shouldSpeakResponse()).isFalse();
    }

    @Test
    void set_null_mode_defaults_to_disabled() {
        service.setMode(VoiceMode.PUSH_TO_TALK);
        assertThat(service.isVoiceEnabled()).isTrue();

        service.setMode(null);
        assertThat(service.getMode()).isEqualTo(VoiceMode.DISABLED);
        assertThat(service.isVoiceEnabled()).isFalse();
    }
}
