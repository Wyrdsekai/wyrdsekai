package org.wyrdsekai.server.voice;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SttConfigTest {

    @Test void default_config() {
        var config = SttConfig.DEFAULT;
        assertThat(config.modelPath()).isEqualTo("models/ggml-medium.bin");
        assertThat(config.language()).isEqualTo("auto");
        assertThat(config.beamSize()).isEqualTo(5);
        assertThat(config.sampleRate()).isEqualTo(16000);
    }

    @Test void minimal_config() {
        var config = SttConfig.MINIMAL;
        assertThat(config.modelPath()).isEqualTo("models/ggml-tiny.bin");
        assertThat(config.beamSize()).isEqualTo(1);
    }

    @Test void custom_config() {
        var config = new SttConfig("models/custom.bin", "fr", 3, 44100, true, 0.4f, 120);
        assertThat(config.language()).isEqualTo("fr");
        assertThat(config.translateToEnglish()).isTrue();
    }

    @Test void invalid_model_path() {
        assertThatThrownBy(() -> new SttConfig("", "en", 1, 16000, false, 0.5f, 60))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void invalid_beam_size() {
        assertThatThrownBy(() -> new SttConfig("model.bin", "en", 0, 16000, false, 0.5f, 60))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void invalid_sample_rate() {
        assertThatThrownBy(() -> new SttConfig("model.bin", "en", 1, 100, false, 0.5f, 60))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void model_exists_check() {
        // Default model file won't exist in test environment
        assertThat(SttConfig.DEFAULT.modelExists()).isFalse();
    }
}
