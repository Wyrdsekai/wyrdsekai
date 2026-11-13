package org.wyrdsekai.core.body;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * `wyrd stop` stops her brain too, a container stopped that way stays stopped through a reboot,
 * and the supervisor starts only the server: a node that rebooted after a stop ran without its
 * brain until a person noticed (household node, 2026-11-11). The server says when the brain it
 * uses on this machine is numb; the brainstem starts it when the node has run without it for a
 * while and nothing is moving it on purpose. The same watcher makes again a Python environment
 * that an upgrade of the system left on a Python whose libraries it does not have.
 */
class HerBrainFollowsTheNodeTest {

    private static final Path SCRIPT = Path.of("..", "packaging", "brainstem", "wyrdsekai-brainstem").toAbsolutePath().normalize();
    private static final Duration EVERY = Duration.ofSeconds(30);

    @AfterEach
    void tearDown() {
        BodyMap.resetForTests();
    }

    @Test
    void the_map_says_whether_the_brain_this_node_uses_here_answers() {
        var map = BodyMap.inMemory();
        map.attach(BrainLimbs.forBackend("llama-server", "llama-server", "http://127.0.0.1:8200", 1, EVERY, null, null));
        assertThat(map.ownBrainHere(Instant.now())).as("attached, not reported on yet").isNull();

        map.heartbeat(BrainLimbs.id("llama-server"), true, null);
        assertThat(map.ownBrainHere(Instant.now())).isEqualTo("up");
        map.heartbeat(BrainLimbs.id("llama-server"), false, null);
        assertThat(map.ownBrainHere(Instant.now())).isEqualTo("numb");
        assertThat(map.ownBrainHere(Instant.now().plus(Duration.ofMinutes(10))))
            .as("a brain nothing has reported on for minutes is not one the node uses").isNull();
    }

    @Test
    void a_brain_elsewhere_or_left_behind_does_not_count() {
        var map = BodyMap.inMemory();
        map.attach(BrainLimbs.forBackend("llama-server", "llama-server", "http://127.0.0.1:8200", 1, EVERY, null, null));
        map.attach(BrainLimbs.forBackend("llama-voice", "llama-server", "http://localhost:8201/v1", 2, EVERY, "http://localhost:8201", null));
        map.attach(BrainLimbs.forBackend("openrouter", "openrouter", "https://openrouter.ai/api/v1", 3, EVERY, null, null));
        map.attach(BrainLimbs.forBackend("gpu-host", "llama-server", "nats://gpu-host", 4, EVERY, null, null));
        map.tick(Instant.now().plus(Duration.ofMinutes(5)));      // every part silent past its contract: numb

        map.heartbeat(BrainLimbs.id("openrouter"), false, null);
        map.heartbeat(BrainLimbs.id("gpu-host"), false, null);
        assertThat(map.ownBrainHere(Instant.now())).as("a far brain and a borrowed one are not this machine's").isNull();

        map.heartbeat(BrainLimbs.id("llama-server"), true, null);
        assertThat(map.part(BrainLimbs.id("llama-voice")).orElseThrow().state()).isEqualTo(PartState.NUMB);
        assertThat(map.ownBrainHere(Instant.now()))
            .as("the voice brain of a stack this node left keeps its numb row; nothing reports on it").isEqualTo("up");
    }

    @Test
    void her_brain_coming_back_is_told_to_her() {
        var line = "{\"ts\":\"2026-11-11T23:50:00Z\",\"event\":\"brain-started\",\"reason\":\"the node had run without it for 6 min, and it came back when the brainstem started it\",\"snapshot\":\"\"}";
        assertThat(BrainstemLink.describe(line)).isEqualTo(
            "My brain was down while I ran; the node had run without it for 6 min, and it came back when the brainstem started it.");
        assertThat(BrainstemLink.describe("{\"event\":\"python-envs-made-again\",\"reason\":\"1 Python environment(s)\"}"))
            .as("plumbing is the steward's, not hers").isNull();
    }

    @Test
    void the_brainstem_starts_a_brain_left_stopped_and_never_one_moved_on_purpose(@TempDir Path tmp) throws Exception {
        assumeTrue(Files.isRegularFile(SCRIPT), "needs the packaging tree");
        var state = tmp.resolve("state");
        var calls = tmp.resolve("calls");
        var env = env(tmp, state, calls);
        env.put("BRAINSTEM_PYENV_WATCH", "0");
        env.put("BRAINSTEM_FAKE_HEALTH_JSON", "{\"status\":\"UP\",\"inference\":{\"inFlight\":0,\"paused\":false},\"brain\":\"numb\"}");

        run(env);
        assertThat(calls).as("the first numb pass only starts the clock").doesNotExist();

        Files.writeString(state.resolve("brain_numb_since"), Long.toString(Instant.now().getEpochSecond() - 301));
        run(env);
        assertThat(Files.readString(calls)).isEqualTo("inference start\n");
        assertThat(Files.readString(state.resolve("brain_tries")).strip()).isEqualTo("1");
        run(env);
        assertThat(Files.readAllLines(calls)).as("a start it did not come back from waits longer before the next").hasSize(1);

        Files.writeString(state.resolve("brain_numb_since"), Long.toString(Instant.now().getEpochSecond() - 10_000));
        env.put("BRAINSTEM_FAKE_NIGHT_WRITE", "1");
        run(env);
        env.put("BRAINSTEM_FAKE_NIGHT_WRITE", "0");
        env.put("BRAINSTEM_FAKE_STEWARD", "1");
        run(env);
        env.put("BRAINSTEM_FAKE_STEWARD", "0");
        env.put("BRAINSTEM_FAKE_HEALTH_JSON", "{\"status\":\"UP\",\"inference\":{\"inFlight\":0,\"paused\":true},\"brain\":\"numb\"}");
        run(env);
        env.put("BRAINSTEM_FAKE_UNIT", "inactive");
        env.put("BRAINSTEM_FAKE_HEALTH_JSON", "{\"status\":\"UP\",\"inference\":{\"inFlight\":0,\"paused\":false},\"brain\":\"numb\"}");
        run(env);
        assertThat(Files.readAllLines(calls)).as("not under a night's write, a steward's command, a pause, or a stop").hasSize(1);

        env.put("BRAINSTEM_FAKE_UNIT", "active");
        Files.writeString(state.resolve("brain_numb_since"), Long.toString(Instant.now().getEpochSecond() - 10_000));
        Files.writeString(state.resolve("brain_down_since"), Long.toString(Instant.now().getEpochSecond() - 600));
        run(env);
        assertThat(Files.readAllLines(calls)).hasSize(2);
        env.put("BRAINSTEM_FAKE_HEALTH_JSON", "{\"status\":\"UP\",\"inference\":{\"inFlight\":0,\"paused\":false},\"brain\":\"up\"}");
        run(env);
        var events = Files.readString(state.resolve("events.jsonl"));
        assertThat(events).contains("\"event\":\"brain-started\"").contains("for 10 min");
        assertThat(Files.readString(state.resolve("brain_tries")).strip()).isEqualTo("0");
        run(env);
        assertThat(Files.readString(state.resolve("events.jsonl")).split("brain-started", -1)).as("said once").hasSize(2);
    }

    @Test
    void a_node_whose_brain_answers_is_left_alone(@TempDir Path tmp) throws Exception {
        assumeTrue(Files.isRegularFile(SCRIPT), "needs the packaging tree");
        var state = tmp.resolve("state");
        var calls = tmp.resolve("calls");
        var env = env(tmp, state, calls);
        env.put("BRAINSTEM_PYENV_WATCH", "0");
        Files.createDirectories(state);
        Files.writeString(state.resolve("brain_numb_since"), Long.toString(Instant.now().getEpochSecond() - 10_000));
        for (var health : new String[] {"{\"status\":\"UP\",\"brain\":\"up\"}", "{\"status\":\"UP\"}", ""}) {
            env.put("BRAINSTEM_FAKE_HEALTH_JSON", health.isEmpty() ? " " : health);
            run(env);
        }
        assertThat(calls).as("an answering brain, a node with no brain here, a server that does not say").doesNotExist();
    }

    @Test
    void the_brainstem_makes_again_a_python_environment_the_system_left_behind(@TempDir Path tmp) throws Exception {
        assumeTrue(Files.isRegularFile(SCRIPT), "needs the packaging tree");
        var state = tmp.resolve("state");
        var calls = tmp.resolve("calls");
        var env = env(tmp, state, calls);
        env.put("BRAINSTEM_BRAIN_WATCH", "0");

        env.put("FAKE_CHECK_RC", "0");
        run(env);
        assertThat(Files.readString(calls)).as("every environment current: asked, nothing made").isEqualTo("setup python --check\n");
        run(env);
        assertThat(Files.readAllLines(calls)).as("and not asked again inside the hour").hasSize(1);

        Files.writeString(state.resolve("pyenv_next"), "0");
        env.put("FAKE_CHECK_RC", "1");
        env.put("BRAINSTEM_FAKE_NIGHT_WRITE", "1");
        run(env);
        assertThat(Files.readAllLines(calls)).as("never under a night's write").hasSize(1);

        env.put("BRAINSTEM_FAKE_NIGHT_WRITE", "0");
        run(env);
        assertThat(Files.readAllLines(calls)).containsExactly("setup python --check", "setup python --check", "setup python");
        assertThat(Files.readString(state.resolve("events.jsonl"))).contains("\"event\":\"python-envs-made-again\"")
            .contains("1 Python environment(s)");
        assertThat(Files.readString(state.resolve("python-envs.log"))).contains("rc=0");
        assertThat(Files.readString(state.resolve("pyenv_lock")).strip()).as("the lock is let go").isEmpty();
    }

    private static Map<String, String> env(Path tmp, Path state, Path calls) throws Exception {
        var wyrd = tmp.resolve("wyrd");
        Files.writeString(wyrd, """
            #!/usr/bin/env bash
            echo "$*" >> "%s"
            if [[ "$*" == "setup python --check" ]]; then exit "${FAKE_CHECK_RC:-0}"; fi
            if [[ "$*" == "setup python" ]]; then echo "/x/.venv-recipes: made again on this machine's Python"; fi
            exit 0
            """.formatted(calls));
        wyrd.toFile().setExecutable(true);
        var env = new HashMap<String, String>();
        env.put("BRAINSTEM_STATE_DIR", state.toString());
        env.put("BRAINSTEM_DB", tmp.resolve("no.db").toString());
        env.put("BRAINSTEM_BACKUPS_DIR", tmp.resolve("backups").toString());
        env.put("BRAINSTEM_HOOKS_DIR", tmp.resolve("no-hooks").toString());
        env.put("BRAINSTEM_FAKE_UNIT", "active");
        env.put("BRAINSTEM_FAKE_HEALTH", "up");
        env.put("BRAINSTEM_FAKE_NIGHT_WRITE", "0");
        env.put("BRAINSTEM_FAKE_STEWARD", "0");
        env.put("WYRD_BIN", wyrd.toString());
        env.put("WYRDSEKAI_CONFIG_FILE", tmp.resolve("absent.conf").toString());
        return env;
    }

    private static void run(Map<String, String> env) throws Exception {
        var pb = new ProcessBuilder("bash", SCRIPT.toString(), "--once");
        pb.environment().putAll(env);
        pb.redirectErrorStream(true);
        var p = pb.start();
        var out = new String(p.getInputStream().readAllBytes());
        assertThat(p.waitFor(30, TimeUnit.SECONDS)).as("the pass finished").isTrue();
        assertThat(p.exitValue()).as(out).isZero();
    }
}
