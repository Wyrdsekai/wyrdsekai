package org.wyrdsekai.core.forge;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The wire's contract at the Java seam: env-gated, never throws, resolves
 * the shipped script. The training itself is the python script's job (its
 * gates are exercised live); what Java owes is that a disabled or broken
 * environment can never hurt sleep completion.
 */
class SleepWeightWriteTest {

    @AfterEach
    void clear() {
        System.clearProperty("wyrdsekai.sleep.write");
    }

    @Test
    void on_by_default_only_where_the_trainer_is_set_up() {
        // Unset: the trainer decides. The test JVM has no trainer, so the night skips quietly.
        assertThat(SleepWeightWrite.enabled()).isFalse();
        assertThat(SleepWeightWrite.enabled(null, () -> true)).isTrue();
        assertThat(SleepWeightWrite.enabled(null, () -> false)).isFalse();
    }

    @Test
    void an_explicit_setting_wins_over_the_trainer_check() {
        for (var on : new String[] {"1", "true", "TRUE", "on", "yes"}) {
            assertThat(SleepWeightWrite.enabled(on, () -> false)).as(on).isTrue();
        }
        for (var off : new String[] {"0", "false", "off", "no", " False "}) {
            assertThat(SleepWeightWrite.enabled(off, () -> true)).as(off).isFalse();
        }
        assertThat(SleepWeightWrite.enabled("maybe", () -> true)).as("unreadable: the trainer decides").isTrue();
    }

    @Test
    void each_trainer_is_set_up_when_its_environment_and_its_model_are_there(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        var data = tmp.resolve("data"); var home = tmp.resolve("home");
        java.util.function.Function<String, String> noEnv = k -> null;
        // The larger model on Linux: the brainwrite environment and the training bundle.
        assertThat(SleepWeightWrite.trainerReady(SleepWeightWrite.HOME_SPARSE, noEnv, data, home)).isFalse();
        python(data.resolve("brainwrite-venv"));
        assertThat(SleepWeightWrite.trainerReady(SleepWeightWrite.HOME_SPARSE, noEnv, data, home)).as("no bundle yet").isFalse();
        var bundle = java.nio.file.Files.createDirectories(data.resolve("models/brain-bundle"));
        java.nio.file.Files.writeString(bundle.resolve("bundle.json"), "{}");
        java.nio.file.Files.writeString(bundle.resolve("spine.safetensors"), "x");
        assertThat(SleepWeightWrite.trainerReady(SleepWeightWrite.HOME_SPARSE, noEnv, data, home)).isTrue();
        // On a Mac: the MLX environment and the MLX model.
        assertThat(SleepWeightWrite.trainerReady(SleepWeightWrite.HOME_SPARSE_MLX, noEnv, data, home)).isFalse();
        python(home.resolve(".wyrdsekai/mlx-venv"));
        var mlx = java.nio.file.Files.createDirectories(data.resolve("models/brain-mlx"));
        java.nio.file.Files.writeString(mlx.resolve("config.json"), "{}");
        assertThat(SleepWeightWrite.trainerReady(SleepWeightWrite.HOME_SPARSE_MLX, noEnv, data, home)).isTrue();
        // The two-model stack: the sleepwrite environment and the voice model's checkpoint.
        assertThat(SleepWeightWrite.trainerReady(SleepWeightWrite.VOICE_4B, noEnv, data, home)).isFalse();
        python(data.resolve("sleepwrite-venv"));
        java.nio.file.Files.createDirectories(data.resolve("models/sleepwrite-base"));
        assertThat(SleepWeightWrite.trainerReady(SleepWeightWrite.VOICE_4B, noEnv, data, home)).isTrue();
        // A setting points somewhere else: that place is what counts.
        java.util.function.Function<String, String> elsewhere = k -> k.equals(SleepWeightWrite.VENV_ENV) ? tmp.resolve("nowhere").toString() : null;
        assertThat(SleepWeightWrite.trainerReady(SleepWeightWrite.VOICE_4B, elsewhere, data, home)).isFalse();
    }

    private static void python(java.nio.file.Path venv) throws Exception {
        var bin = java.nio.file.Files.createDirectories(venv.resolve("bin"));
        var py = java.nio.file.Files.writeString(bin.resolve("python"), "#!/bin/sh\n");
        py.toFile().setExecutable(true);
    }

    @Test
    void enabled_by_property() {
        System.setProperty("wyrdsekai.sleep.write", "1");
        assertThat(SleepWeightWrite.enabled()).isTrue();
        System.setProperty("wyrdsekai.sleep.write", "true");
        assertThat(SleepWeightWrite.enabled()).isTrue();
        System.setProperty("wyrdsekai.sleep.write", "0");
        assertThat(SleepWeightWrite.enabled()).isFalse();
    }

    @Test
    void fire_and_forget_never_throws_when_disabled() {
        SleepWeightWrite.fireAndForget("test-agent");
    }

    @Test
    void wyrd_cli_resolvable_from_the_repo_tree() {
        // The auto-apply path shells out to the CLI; the ".." candidate must
        // find the real bin/wyrd this deb ships.
        var p = SleepWeightWrite.resolveWyrd();
        assertThat(p).isNotNull();
    }

    @Test
    void resolves_the_shipped_script_from_the_repo_tree() {
        // Test working dir is the core module; the ".." candidate must find
        // the real script this deb will ship.
        var p = SleepWeightWrite.resolveScript();
        assertThat(p).as("sleep_write.py resolvable from module dir").isNotNull();
        assertThat(p.toString()).endsWith("sleep_write.py");
    }

    // --- the night picks its trainer by serving profile ---

    @Test
    void theTwoModelStackKeepsItsTrainer() {
        var t = SleepWeightWrite.trainerFor(false);
        assertThat(t.scriptLeaf()).isEqualTo("sleep_write.py");
        assertThat(t.outSubdir()).isEqualTo("sleepwrite");
        assertThat(t.container()).isEqualTo("wyrdsekai-llama-voice");
        assertThat(t.applyFlag()).as("the launcher's apply command is called exactly as before").isNull();
    }

    @Test
    void oneResidentModelGetsItsOwnTrainerAndItsOwnResults() {
        var t = SleepWeightWrite.trainerFor(true, "Linux");
        assertThat(t.scriptLeaf()).isEqualTo("brain_write.py");
        assertThat(t.outSubdir()).isEqualTo("brainwrite");
        assertThat(t.container()).isEqualTo("wyrdsekai-llama-brain");
        assertThat(t.applyFlag()).isEqualTo("--brain");
    }

    @Test
    void oneResidentModelOnAMacGetsTheMlxTrainer() {
        var t = SleepWeightWrite.trainerFor(true, "Mac OS X");
        assertThat(t).isSameAs(SleepWeightWrite.HOME_SPARSE_MLX);
        assertThat(t.scriptLeaf()).isEqualTo("brain_write_mlx.py");
        assertThat(t.outSubdir()).as("same results directory as the CUDA trainer").isEqualTo("brainwrite");
        assertThat(t.container()).as("a native server: no container to restart").isNull();
        assertThat(t.applyFlag()).isEqualTo("--brain");
    }

    @Test
    void theOperatingSystemOnlyMattersForTheSingleModel() {
        assertThat(SleepWeightWrite.trainerFor(false, "Mac OS X")).isSameAs(SleepWeightWrite.VOICE_4B);
        assertThat(SleepWeightWrite.trainerFor(false, "Linux")).isSameAs(SleepWeightWrite.VOICE_4B);
        assertThat(SleepWeightWrite.trainerFor(true, "Linux")).isSameAs(SleepWeightWrite.HOME_SPARSE);
        assertThat(SleepWeightWrite.trainerFor(true, "Windows 11")).isSameAs(SleepWeightWrite.HOME_SPARSE);
        assertThat(SleepWeightWrite.trainerFor(true, null)).isSameAs(SleepWeightWrite.HOME_SPARSE);
    }

    @Test
    void theMlxTrainerScriptShipsBesideTheOthers() {
        var brain = SleepWeightWrite.resolveScript("brain_write.py");
        var mlx = SleepWeightWrite.resolveScript("brain_write_mlx.py");
        assertThat(brain).isNotNull(); assertThat(mlx).isNotNull();
        assertThat(mlx.getParent()).isEqualTo(brain.getParent());
    }

    @Test
    void bothTrainerScriptsShipBesideEachOther() {
        var voice = SleepWeightWrite.resolveScript("sleep_write.py");
        var brain = SleepWeightWrite.resolveScript("brain_write.py");
        assertThat(voice).isNotNull(); assertThat(brain).isNotNull();
        assertThat(brain.getParent()).isEqualTo(voice.getParent());
    }

    @Test
    @DisplayName("a rehearsal request that no sleep took up lapses, so a later real night is not turned into a rehearsal")
    void aRehearsalRequestLapses() {
        var asked = Instant.parse("2026-09-21T17:00:00Z");
        assertThat(SleepWeightWrite.rehearsalPending(null, asked)).isFalse();
        assertThat(SleepWeightWrite.rehearsalPending(asked, asked.plusSeconds(30))).isTrue();
        assertThat(SleepWeightWrite.rehearsalPending(asked, asked.plus(SleepWeightWrite.REHEARSAL_REQUEST_LIFETIME))).isTrue();
        assertThat(SleepWeightWrite.rehearsalPending(asked, asked.plus(SleepWeightWrite.REHEARSAL_REQUEST_LIFETIME).plusSeconds(1))).isFalse();
    }

    @Test
    @DisplayName("an applied night is judged again by her real lines, two hours on and then hourly")
    void theWatchWaitsForHerToSpeak() {
        assertThat(SleepWeightWrite.WATCH_AFTER).isEqualTo(java.time.Duration.ofHours(2));
        assertThat(SleepWeightWrite.WATCH_RETRY).isEqualTo(java.time.Duration.ofHours(1));
        assertThat(SleepWeightWrite.WATCH_ATTEMPTS).isGreaterThanOrEqualTo(4);
    }

    @Test
    @DisplayName("a pending night watch is kept on disk, and cleared only by the watch it belongs to")
    void a_pending_watch_is_kept_until_its_own_watch_clears_it(@TempDir Path dir) throws Exception {
        SleepWeightWrite.rememberWatch(dir, "mia", "2026-09-22T18:04:00Z", 2, Duration.ofHours(1));
        var f = dir.resolve(SleepWeightWrite.WATCH_PENDING);
        assertThat(Files.readString(f)).contains("\"since\":\"2026-09-22T18:04:00Z\"").contains("\"attempt\":2");
        SleepWeightWrite.forgetWatch(dir, "2026-09-21T09:44:00Z");
        assertThat(f).as("an older night's watch does not clear a later one").exists();
        SleepWeightWrite.forgetWatch(dir, "2026-09-22T18:04:00Z");
        assertThat(f).doesNotExist();
    }

    @Test
    @DisplayName("a watch is for one night: a later night replaces it and the watch lets go")
    void a_watch_lets_go_when_its_night_is_replaced(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("current.gguf"), "x");
        Files.writeString(dir.resolve("state.json"),
            "{\"last_success_ts\":\"2026-09-23T13:16:00Z\",\"last_adapter\":\"adapter-20260923-0916.gguf\"}");
        assertThat(SleepWeightWrite.servedNight(dir)).isEqualTo("adapter-20260923-0916.gguf");
        SleepWeightWrite.rememberWatch(dir, "mia", "2026-09-23T13:18:00Z", 1, Duration.ofHours(2), "adapter-20260923-0916.gguf");
        assertThat(Files.readString(dir.resolve(SleepWeightWrite.WATCH_PENDING))).contains("\"adapter\":\"adapter-20260923-0916.gguf\"");
        assertThat(SleepWeightWrite.watchedNightServed(dir, "adapter-20260923-0916.gguf")).isTrue();

        // the evening's night lands
        Files.writeString(dir.resolve("state.json"),
            "{\"last_success_ts\":\"2026-09-24T01:54:00Z\",\"last_adapter\":\"adapter-20260923-2154.gguf\"}");
        assertThat(SleepWeightWrite.watchedNightServed(dir, "adapter-20260923-0916.gguf"))
            .as("the morning's watch is for a night no longer served").isFalse();
        assertThat(SleepWeightWrite.watchedNightServed(dir, "adapter-20260923-2154.gguf")).isTrue();
        assertThat(SleepWeightWrite.watchedNightServed(dir, null)).as("a watch that does not know its night asks as before").isTrue();

        Files.delete(dir.resolve("current.gguf"));
        assertThat(SleepWeightWrite.watchedNightServed(dir, "adapter-20260923-2154.gguf")).as("no night served at all").isFalse();
    }
}
