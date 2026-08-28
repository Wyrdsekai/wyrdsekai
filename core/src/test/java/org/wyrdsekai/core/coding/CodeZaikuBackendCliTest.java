package org.wyrdsekai.core.coding;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.coding.GooseBackend.ProcessResult;
import org.wyrdsekai.core.coding.GooseBackend.ProcessRunner;

import java.io.File;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the CLI contract agreed with the CodeZaiku team 2026-08-15
 * ( backend table): argv shape, env-only model
 * routing, workspace-as-CWD — and the TYPED result contract, whose whole
 * point is that "exit 0 with opaque stdout" is a FAILURE, never a silent
 * success with zero artifacts (the GooseBackend.parseArtifacts failure
 * class the shared schema exists to eliminate).
 */
class CodeZaikuBackendCliTest {

    private static final String RESULT_JSON = """
        {"taskId":"t-1","status":"success","files":["src/loom.js","README.md"],
         "gitRef":"abc1234","workspacePath":"/tmp/ws",
         "testsPassed":3,"testsFailed":1,"boardId":"b-9","language":"js"}""";

    private static CodeZaikuRuntimeConfig config() {
        return new CodeZaikuRuntimeConfig(true, "codezaiku",
            "http://127.0.0.1:9999", "test-model", Duration.ofMinutes(5), List.of());
    }

    private static TaskSpec spec(String workspace) {
        return new TaskSpec(UUID.randomUUID(), "did:test", "implement",
            "weave the loom", workspace, List.of(), 0L, null);
    }

    @Test
    void argv_env_and_cwd_follow_the_agreed_contract() throws Exception {
        var seenArgs = new AtomicReference<List<String>>();
        var seenEnv = new AtomicReference<Map<String, String>>();
        var seenDir = new AtomicReference<File>();
        ProcessRunner runner = (args, env, workdir, timeout) -> {
            seenArgs.set(args); seenEnv.set(env); seenDir.set(workdir);
            return new ProcessResult(0, RESULT_JSON, "", false);
        };
        var backend = new CodeZaikuBackend(config(), null, runner);

        backend.submitTask(spec("/tmp/ws")).get(5, TimeUnit.SECONDS);

        assertThat(seenArgs.get()).startsWith("codezaiku", "run", "--text");
        assertThat(seenArgs.get()).containsSequence("--output-format", "json");
        assertThat(seenArgs.get()).contains("--no-session", "-q");
        // items-as-tools preamble wraps non-shell tasks, task text included
        assertThat(seenArgs.get().get(3)).contains("weave the loom");
        // model routing is ENV-ONLY — never argv
        assertThat(seenArgs.get()).doesNotContain("--provider", "--model");
        // per-task correlation id (their --task-id, accepted 2026-08-15)
        assertThat(seenArgs.get()).contains("--task-id");
        assertThat(seenEnv.get())
            .containsEntry("CODEZAIKU_DRIVE", "http://127.0.0.1:9999")
            .containsEntry("CODEZAIKU_MODEL", "test-model");
        // workspace is the subprocess CWD
        assertThat(seenDir.get()).isEqualTo(new File("/tmp/ws"));
    }

    @Test
    void success_json_yields_typed_source_and_build_sibling() throws Exception {
        ProcessRunner runner = (args, env, workdir, timeout) ->
            new ProcessResult(0, RESULT_JSON, "", false);
        var backend = new CodeZaikuBackend(config(), null, runner);
        var s = spec("/tmp/ws");

        var result = backend.submitTask(s).get(5, TimeUnit.SECONDS);
        assertThat(result.status()).isEqualTo(TaskStatus.SUCCEEDED);

        var artifacts = backend.artifactsFor(s.taskId().toString()).toList();
        assertThat(artifacts).hasSize(2);
        var src = (SourceArtifact) artifacts.get(0);
        assertThat(src.files()).containsExactly("src/loom.js", "README.md");
        assertThat(src.gitRef()).isEqualTo("abc1234");
        assertThat(src.workspacePath()).isEqualTo("/tmp/ws");
        // sibling rides the same magic key CodeZaikuEventAdapter used, so
        // CodingTaskItemBridge places the pair without changes
        assertThat(src.backendMetadata()).containsKey("__sibling_build");
        var build = (BuildArtifact) artifacts.get(1);
        assertThat(build.status()).isEqualTo("success");
        assertThat(build.testsPassed()).isEqualTo(3);
        assertThat(build.testsFailed()).isEqualTo(1);
        // unmodeled extras land in backendMetadata verbatim
        assertThat(build.backendMetadata()).containsEntry("boardId", "b-9");
    }

    @Test
    void exit_zero_with_opaque_stdout_is_FAILED_not_silent_success() throws Exception {
        ProcessRunner runner = (args, env, workdir, timeout) ->
            new ProcessResult(0, "did some work, all good!", "", false);
        var backend = new CodeZaikuBackend(config(), null, runner);

        var result = backend.submitTask(spec(null)).get(5, TimeUnit.SECONDS);
        assertThat(result.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(result.summary()).contains("no parseable result JSON");
    }

    @Test
    void stream_json_tail_document_is_accepted() throws Exception {
        var stdout = "{\"event\":\"progress\",\"step\":1}\nnoise line\n" + RESULT_JSON;
        ProcessRunner runner = (args, env, workdir, timeout) ->
            new ProcessResult(0, stdout, "", false);
        var backend = new CodeZaikuBackend(config(), null, runner);
        var s = spec("/tmp/ws");

        var result = backend.submitTask(s).get(5, TimeUnit.SECONDS);
        assertThat(result.status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(backend.artifactsFor(s.taskId().toString())).hasSize(2);
    }

    @Test
    void reported_failure_status_fails_the_task_with_artifacts_kept() throws Exception {
        var failing = RESULT_JSON.replace("\"status\":\"success\"", "\"status\":\"failed\"");
        ProcessRunner runner = (args, env, workdir, timeout) ->
            new ProcessResult(0, failing, "", false);
        var backend = new CodeZaikuBackend(config(), null, runner);
        var s = spec("/tmp/ws");

        var result = backend.submitTask(s).get(5, TimeUnit.SECONDS);
        assertThat(result.status()).isEqualTo(TaskStatus.FAILED);
        // artifacts still recorded — a failed build's files are evidence
        assertThat(backend.artifactsFor(s.taskId().toString())).hasSize(2);
    }

    @Test
    void nonzero_exit_and_timeout_map_to_their_statuses() throws Exception {
        ProcessRunner crash = (args, env, workdir, timeout) ->
            new ProcessResult(3, "", "boom", false);
        var failed = new CodeZaikuBackend(config(), null, crash)
            .submitTask(spec(null)).get(5, TimeUnit.SECONDS);
        assertThat(failed.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(failed.summary()).contains("boom");

        ProcessRunner slow = (args, env, workdir, timeout) ->
            new ProcessResult(0, "", "", true);
        var timedOut = new CodeZaikuBackend(config(), null, slow)
            .submitTask(spec(null)).get(5, TimeUnit.SECONDS);
        assertThat(timedOut.status()).isEqualTo(TaskStatus.TIMED_OUT);
    }

    @Test
    void shell_exec_tasks_skip_the_items_as_tools_preamble() throws Exception {
        var seenArgs = new AtomicReference<List<String>>();
        ProcessRunner runner = (args, env, workdir, timeout) -> {
            seenArgs.set(args);
            return new ProcessResult(0, RESULT_JSON, "", false);
        };
        var backend = new CodeZaikuBackend(config(), null, runner);
        var s = new TaskSpec(UUID.randomUUID(), "did:test", "shell-exec",
            "ls -la", null, List.of(), 0L, null);

        backend.submitTask(s).get(5, TimeUnit.SECONDS);
        assertThat(seenArgs.get().get(3)).isEqualTo("ls -la");
    }
}
