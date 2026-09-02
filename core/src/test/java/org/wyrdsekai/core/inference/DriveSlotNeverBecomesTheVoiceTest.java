package org.wyrdsekai.core.inference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The "already served" redirect must never point the drive slot at the voice.
 *
 * <p>Live on the household node from 2026-07-30 to 09-02: {@code wyrd setup}
 * wrote the 4B voice model as MODEL_PATH, the redirect found it served on
 * :8201, and both router backends became the voice server. The 9B drive on
 * :8200 served zero companion tokens for five weeks. Every boot logged it.
 */
class DriveSlotNeverBecomesTheVoiceTest {

    private static final String VOICE = "http://127.0.0.1:8201";
    private static final String DRIVE = "http://127.0.0.1:8200";
    private static final String DEAD = "http://127.0.0.1:11525";

    @Test
    @DisplayName("MODEL_PATH names the voice model, :8200 serves the drive → drive slot takes :8200")
    void takesTheOtherServerWhenServedAtIsTheVoice() {
        var served = Map.of(DRIVE, List.of("/models/9b-drive.gguf"), VOICE, List.of("/models/4b.gguf"));
        var chosen = InferenceConfig.chooseDriveUrl(VOICE, VOICE, List.of(DEAD, DRIVE),
            u -> served.getOrDefault(u, List.of()));
        assertThat(chosen).isEqualTo(DRIVE);
    }

    @Test
    @DisplayName("single-model node: nothing else answers → stays on the voice, loudly")
    void staysOnVoiceWhenNothingElseServes() {
        var served = Map.of(VOICE, List.of("/models/4b.gguf"));
        var chosen = InferenceConfig.chooseDriveUrl(VOICE, VOICE, List.of(DEAD, DRIVE),
            u -> served.getOrDefault(u, List.of()));
        assertThat(chosen).isEqualTo(VOICE);
    }

    @Test
    @DisplayName("MODEL_PATH names the drive model served on :8200 → unchanged redirect")
    void normalRedirectIsUntouched() {
        var served = Map.of(DRIVE, List.of("/models/9b-drive.gguf"), VOICE, List.of("/models/4b.gguf"));
        var chosen = InferenceConfig.chooseDriveUrl(DRIVE, VOICE, List.of(DEAD, DRIVE),
            u -> served.getOrDefault(u, List.of()));
        assertThat(chosen).isEqualTo(DRIVE);
    }

    @Test
    void localhostAndLoopbackAreTheSameServer() {
        assertThat(InferenceConfig.sameServer("http://localhost:8201/", "http://127.0.0.1:8201")).isTrue();
        assertThat(InferenceConfig.sameServer("http://127.0.0.1:8200", "http://127.0.0.1:8201")).isFalse();
        assertThat(InferenceConfig.sameServer(null, VOICE)).isFalse();
    }
}
