package org.wyrdsekai.core.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.agent.VitalityState;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class VitalityPersistenceTest {

    private VitalityPersistence persistence;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        var dbPath = tempDir.resolve("test.db");
        var jdbcUrl = SchemaInitializer.initialize(dbPath);
        persistence = new VitalityPersistence(jdbcUrl);
    }

    @Test void save_and_load_roundtrip() {
        var state = new VitalityState(0.8, 0.7, 0.6, 0.5, 0.4, 0.3, 0.2, 0.1);
        persistence.save("agent-1", state);

        var loaded = persistence.load("agent-1");
        assertThat(loaded).isPresent();
        assertThat(loaded.get().contextBudget()).isEqualTo(0.8);
        assertThat(loaded.get().confidence()).isEqualTo(0.7);
        assertThat(loaded.get().energy()).isEqualTo(0.6);
        assertThat(loaded.get().alignment()).isEqualTo(0.5);
        assertThat(loaded.get().errorPressure()).isEqualTo(0.4);
        assertThat(loaded.get().momentum()).isEqualTo(0.3);
        assertThat(loaded.get().rapport()).isEqualTo(0.2);
        assertThat(loaded.get().focus()).isEqualTo(0.1);
    }

    @Test void load_nonexistent_returns_empty() {
        var loaded = persistence.load("does-not-exist");
        assertThat(loaded).isEmpty();
    }

    @Test void save_upserts_existing() {
        var initial = VitalityState.initial();
        persistence.save("agent-1", initial);
        assertThat(persistence.load("agent-1").get().energy()).isEqualTo(1.0);

        var updated = initial.withEnergy(0.3);
        persistence.save("agent-1", updated);
        assertThat(persistence.load("agent-1").get().energy()).isEqualTo(0.3);
    }

    @Test void delete_removes_snapshot() {
        persistence.save("agent-1", VitalityState.initial());
        assertThat(persistence.exists("agent-1")).isTrue();

        boolean deleted = persistence.delete("agent-1");
        assertThat(deleted).isTrue();
        assertThat(persistence.exists("agent-1")).isFalse();
    }

    @Test void delete_nonexistent_returns_false() {
        assertThat(persistence.delete("ghost")).isFalse();
    }

    @Test void exists_checks_presence() {
        assertThat(persistence.exists("agent-1")).isFalse();
        persistence.save("agent-1", VitalityState.initial());
        assertThat(persistence.exists("agent-1")).isTrue();
    }

    @Test void count_tracks_total() {
        assertThat(persistence.count()).isEqualTo(0);
        persistence.save("agent-1", VitalityState.initial());
        assertThat(persistence.count()).isEqualTo(1);
        persistence.save("agent-2", VitalityState.initial());
        assertThat(persistence.count()).isEqualTo(2);
        persistence.delete("agent-1");
        assertThat(persistence.count()).isEqualTo(1);
    }

    @Test void multiple_agents_independent() {
        var state1 = VitalityState.initial().withEnergy(0.1);
        var state2 = VitalityState.initial().withEnergy(0.9);
        persistence.save("agent-1", state1);
        persistence.save("agent-2", state2);

        assertThat(persistence.load("agent-1").get().energy()).isEqualTo(0.1);
        assertThat(persistence.load("agent-2").get().energy()).isEqualTo(0.9);
    }

    @Test void initial_state_roundtrip() {
        var initial = VitalityState.initial();
        persistence.save("agent-1", initial);
        var loaded = persistence.load("agent-1").get();
        assertThat(loaded).isEqualTo(initial);
    }

    @Test void extreme_values_roundtrip() {
        var extreme = new VitalityState(0.0, 0.0, 0.0, 0.0, 1.0, 1.0, 1.0, 1.0);
        persistence.save("extreme", extreme);
        var loaded = persistence.load("extreme").get();
        assertThat(loaded).isEqualTo(extreme);
    }
}
