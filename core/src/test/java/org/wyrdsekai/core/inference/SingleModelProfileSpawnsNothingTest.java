package org.wyrdsekai.core.inference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Under the single-model profile the launcher starts the one server; this process starts none.
 *
 * <p>An install that was set up with two models keeps MODEL_PATH naming the old drive model and
 * VOICE_ENABLED=true after it moves to the single-model profile. Nothing serves the old file any
 * more, so the wait for a sibling ran its full 120 s, a CPU copy of the old model was started on
 * :11525 and took the drive slot, and the large model on the configured url served nothing. The
 * voice backend was registered against a port with no server behind it.
 */
class SingleModelProfileSpawnsNothingTest {

    private static final String OLD_DRIVE = "/var/lib/wyrdsekai/models/drive.gguf";

    @Test
    @DisplayName("the configured model path is not spawned under the single-model profile")
    void modelPathIsIgnoredUnderTheSingleModelProfile() {
        assertThat(InferenceConfig.spawnablePath(OLD_DRIVE, true)).isEmpty();
    }

    @Test
    @DisplayName("the two-model profile keeps the model path it was given")
    void modelPathIsKeptUnderTheTwoModelProfile() {
        assertThat(InferenceConfig.spawnablePath(OLD_DRIVE, false)).isEqualTo(OLD_DRIVE);
        assertThat(InferenceConfig.spawnablePath("", false)).isEmpty();
    }

    @Test
    @DisplayName("only the voice backend is left out, and only under the single-model profile")
    void theVoiceBackendIsLeftOutUnderTheSingleModelProfile() {
        assertThat(InferenceConfig.unservedUnderSingleModel("llama-voice", true)).isTrue();
        assertThat(InferenceConfig.unservedUnderSingleModel("llama-server", true)).isFalse();
        assertThat(InferenceConfig.unservedUnderSingleModel("llama-voice", false)).isFalse();
    }
}
