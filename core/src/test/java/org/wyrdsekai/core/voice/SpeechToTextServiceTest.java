package org.wyrdsekai.core.voice;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tests for {@link SpeechToTextService} -- STT backend detection and transcription routing.
 */
class SpeechToTextServiceTest {

    @BeforeEach
    void setUp() {
        SpeechToTextService.init();
    }

    @AfterEach
    void tearDown() {
        SpeechToTextService.reset();
    }

    @Test
    void singleton_init_and_get() {
        var service = SpeechToTextService.get();
        assertThat(service).isNotNull();

        // Second init replaces instance
        SpeechToTextService.init();
        var second = SpeechToTextService.get();
        assertThat(second).isNotNull().isNotSameAs(service);
    }

    @Test
    void default_backend_is_none() {
        var service = SpeechToTextService.get();
        assertThat(service.getActiveBackend()).isEqualTo(SpeechToTextService.SttBackend.NONE);
        assertThat(service.isAvailable()).isFalse();
    }

    @Test
    void isAvailable_reflects_detected_backends() {
        var service = SpeechToTextService.get();

        service.setActiveBackend(SpeechToTextService.SttBackend.LOCAL_WHISPER);
        assertThat(service.isAvailable()).isTrue();

        service.setActiveBackend(SpeechToTextService.SttBackend.HOUSEHOLD);
        assertThat(service.isAvailable()).isTrue();

        service.setActiveBackend(SpeechToTextService.SttBackend.NONE);
        assertThat(service.isAvailable()).isFalse();
    }

    @Test
    void transcribe_with_no_backend_returns_error() {
        var service = SpeechToTextService.get();
        assertThat(service.getActiveBackend()).isEqualTo(SpeechToTextService.SttBackend.NONE);

        var future = service.transcribe(new byte[]{1, 2, 3}, "wav");
        assertThatThrownBy(future::get)
            .isInstanceOf(ExecutionException.class)
            .hasCauseInstanceOf(IllegalStateException.class)
            .hasMessageContaining("No STT backend available");
    }

    @Test
    void transcribe_with_null_audio_returns_error() {
        var service = SpeechToTextService.get();
        service.setActiveBackend(SpeechToTextService.SttBackend.LOCAL_WHISPER);

        var future = service.transcribe(null, "wav");
        assertThatThrownBy(future::get)
            .isInstanceOf(ExecutionException.class)
            .hasCauseInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("No audio data");
    }

    @Test
    void transcribe_with_empty_audio_returns_error() {
        var service = SpeechToTextService.get();
        service.setActiveBackend(SpeechToTextService.SttBackend.LOCAL_WHISPER);

        var future = service.transcribe(new byte[0], "wav");
        assertThatThrownBy(future::get)
            .isInstanceOf(ExecutionException.class)
            .hasCauseInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("No audio data");
    }

    @Test
    void detect_backends_finds_system_tools() {
        var service = SpeechToTextService.get();

        // detectBackends runs real detection -- if whisper is not installed,
        // backend stays NONE (which is the expected case in CI)
        service.detectBackends();

        // We can only assert the detection ran without error
        // and that the backend is one of the valid values
        assertThat(service.getActiveBackend()).isIn(
            SpeechToTextService.SttBackend.LOCAL_WHISPER,
            SpeechToTextService.SttBackend.HOUSEHOLD,
            SpeechToTextService.SttBackend.NONE
        );
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void findExecutableOnPath_finds_common_binaries() {
        // 'ls' exists on all Unix-like systems
        var path = SpeechToTextService.findExecutableOnPath("ls");
        assertThat(path).isNotNull();
        assertThat(path).contains("ls");
    }

    @Test
    void findExecutableOnPath_returns_null_for_nonexistent() {
        var path = SpeechToTextService.findExecutableOnPath("nonexistent_binary_xyz_12345");
        assertThat(path).isNull();
    }

    @Test
    void set_active_backend_null_defaults_to_none() {
        var service = SpeechToTextService.get();
        service.setActiveBackend(SpeechToTextService.SttBackend.LOCAL_WHISPER);
        assertThat(service.isAvailable()).isTrue();

        service.setActiveBackend(null);
        assertThat(service.getActiveBackend()).isEqualTo(SpeechToTextService.SttBackend.NONE);
        assertThat(service.isAvailable()).isFalse();
    }

    @Test
    void household_backend_transcription_returns_unsupported() {
        var service = SpeechToTextService.get();
        service.setActiveBackend(SpeechToTextService.SttBackend.HOUSEHOLD);

        var future = service.transcribe(new byte[]{1, 2, 3}, "wav");
        assertThatThrownBy(future::get)
            .isInstanceOf(ExecutionException.class)
            .hasCauseInstanceOf(UnsupportedOperationException.class)
            .hasMessageContaining("Household STT not yet wired");
    }
}
