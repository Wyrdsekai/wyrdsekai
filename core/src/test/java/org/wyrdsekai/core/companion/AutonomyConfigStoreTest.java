package org.wyrdsekai.core.companion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AutonomyConfigStoreTest {

    @Test
    void defaults_are_normal_across_all_keys(@TempDir Path tmp) {
        var store = new AutonomyConfigStore("did:key:auto1", tmp);
        var cfg = store.get();
        assertThat(cfg.restPreference()).isEqualTo("normal");
        assertThat(cfg.explorationPreference()).isEqualTo("normal");
        assertThat(cfg.trainingPreference()).isEqualTo("normal");
        assertThat(cfg.federationPreference()).isEqualTo("normal");
        assertThat(cfg.readingPreference()).isEqualTo("normal");
    }

    @Test
    void set_persists_and_round_trips(@TempDir Path tmp) {
        var store = new AutonomyConfigStore("did:key:auto2", tmp);
        store.set("rest", "high");
        store.set("exploration", "low");
        store.set("notes", "I want to be quieter when she's away.");

        var reloaded = new AutonomyConfigStore("did:key:auto2", tmp);
        var cfg = reloaded.get();
        assertThat(cfg.restPreference()).isEqualTo("high");
        assertThat(cfg.explorationPreference()).isEqualTo("low");
        assertThat(cfg.notes()).contains("quieter");
    }

    @Test
    void unknown_key_is_ignored(@TempDir Path tmp) {
        var store = new AutonomyConfigStore("did:key:auto3", tmp);
        var cfg = store.set("not_a_real_key", "high");
        // Defaults preserved.
        assertThat(cfg.restPreference()).isEqualTo("normal");
    }

    @Test
    void aliases_for_keys_work(@TempDir Path tmp) {
        var store = new AutonomyConfigStore("did:key:auto4", tmp);
        store.set("explore", "high");
        store.set("train", "low");
        store.set("library", "high");
        var cfg = store.get();
        assertThat(cfg.explorationPreference()).isEqualTo("high");
        assertThat(cfg.trainingPreference()).isEqualTo("low");
        assertThat(cfg.readingPreference()).isEqualTo("high");
    }
}
