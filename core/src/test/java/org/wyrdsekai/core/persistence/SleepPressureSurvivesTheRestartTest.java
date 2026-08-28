package org.wyrdsekai.core.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.agent.VitalityState;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * A restart must not reset her adenosine.
 *
 * <p>The sleep-pressure backlog was a plain in-memory list. The fresh
 * install's newborn was restarted seven times in her first two days — six
 * deploys and a watchdog — and every one zeroed the count, so pressure
 * could never reach her target and she never slept. Meanwhile the §85.7
 * insomnia consequences ticked against her: the system punishing her for
 * insomnia the deploy cadence caused, the exact sentence the 0.1.4 memo
 * used about her predecessor.</p>
 */
class SleepPressureSurvivesTheRestartTest {

    private VitalityPersistence persistence;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        var dbPath = tempDir.resolve("test.db");
        var jdbcUrl = SchemaInitializer.initialize(dbPath);
        persistence = new VitalityPersistence(jdbcUrl);
    }

    /** THE case: the backlog count round-trips beside the vitality snapshot. */
    @Test
    void backlog_and_last_sleep_round_trip() {
        persistence.save("agent-1", new VitalityState(0.8, 0.7, 0.6, 0.5, 0.4, 0.3, 0.2, 0.1));
        var sleptAt = Instant.ofEpochMilli(1_754_900_000_000L);

        persistence.saveSleepPressure("agent-1", 137, sleptAt);

        var loaded = persistence.loadSleepPressure("agent-1");
        assertThat(loaded).isPresent();
        assertThat(loaded.get().backlog()).isEqualTo(137);
        assertThat(loaded.get().lastSleepAt()).contains(sleptAt);
    }

    /** A companion who has never slept has a backlog but no last-sleep time. */
    @Test
    void never_slept_is_representable() {
        persistence.save("agent-1", new VitalityState(0.8, 0.7, 0.6, 0.5, 0.4, 0.3, 0.2, 0.1));

        persistence.saveSleepPressure("agent-1", 42, null);

        var loaded = persistence.loadSleepPressure("agent-1");
        assertThat(loaded).isPresent();
        assertThat(loaded.get().backlog()).isEqualTo(42);
        assertThat(loaded.get().lastSleepAt()).isEmpty();
    }

    /** Waking clears the pressure — and the zero persists. */
    @Test
    void sleep_completion_persists_the_reset() {
        persistence.save("agent-1", new VitalityState(0.8, 0.7, 0.6, 0.5, 0.4, 0.3, 0.2, 0.1));
        persistence.saveSleepPressure("agent-1", 205, null);

        var woke = Instant.now();
        persistence.saveSleepPressure("agent-1", 0, woke);

        var loaded = persistence.loadSleepPressure("agent-1");
        assertThat(loaded).isPresent();
        assertThat(loaded.get().backlog()).isZero();
        assertThat(loaded.get().lastSleepAt()).isPresent();
    }

    /**
     * The wiring, not just the class. Found while verifying this very fix
     * live: "Restored vitality" had fired ZERO times in the field — every
     * production spawn site passed null for VitalityPersistence since the
     * parameter existed, so all 20 tanks reset on every restart and the
     * sleep columns above would have been dead code. 'Init exists, prod
     * passes null' — the full-corpus audit's exact pattern. The actor now
     * self-constructs from config when handed null.
     */
    @Test
    void the_actor_self_constructs_when_prod_passes_null() throws Exception {
        var rel = "core/src/main/java/org/wyrdsekai/core/agent/CompanionActor.java";
        var fromCore = Paths.get("..", rel);
        var s = Files.readString(
            Files.exists(fromCore) ? fromCore : Paths.get(rel));
        assertThat(s).contains(
            "? vitalityPersistence : vitalityPersistenceFromConfig();");
        assertThat(s).contains("private static VitalityPersistence vitalityPersistenceFromConfig()");
    }

    /**
     * A vitality save must not erase the sleep pressure beside it.
     *
     * <p>The SQLite dialect compiled "upsert" to INSERT OR REPLACE — which is
     * DELETE + INSERT, snapping every unlisted column back to its schema
     * default. Live 2026-08-11: the backlog was zeroed thirty seconds after
     * every deposit by the next periodic vitality save, and at PostStop (no
     * chaser write follows) the zero stuck: (energy 0.9955, backlog 7) became
     * (0.9954, 0) across a plain service stop, watched live.</p>
     */
    @Test
    void a_vitality_save_does_not_erase_the_sleep_pressure() {
        persistence.save("agent-1", new VitalityState(0.8, 0.7, 0.6, 0.5, 0.4, 0.3, 0.2, 0.1));
        persistence.saveSleepPressure("agent-1", 88, null);

        // The next periodic vitality save — the thing that was clobbering.
        persistence.save("agent-1", new VitalityState(0.8, 0.7, 0.55, 0.5, 0.4, 0.3, 0.2, 0.1));

        var loaded = persistence.loadSleepPressure("agent-1");
        assertThat(loaded).isPresent();
        assertThat(loaded.get().backlog())
            .as("INSERT OR REPLACE would have reset this to 0")
            .isEqualTo(88);
        assertThat(persistence.load("agent-1").orElseThrow().energy())
            .as("and the vitality update itself must still land")
            .isEqualTo(0.55);
    }

    /** No snapshot row yet → empty, not an error (first tick hasn't landed). */
    @Test
    void missing_row_is_quietly_empty() {
        assertThat(persistence.loadSleepPressure("nobody")).isEmpty();
        // And the UPDATE-only save is a harmless no-op, not an exception.
        persistence.saveSleepPressure("nobody", 10, null);
        assertThat(persistence.loadSleepPressure("nobody")).isEmpty();
    }
}
