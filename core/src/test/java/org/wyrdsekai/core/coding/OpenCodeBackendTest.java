package org.wyrdsekai.core.coding;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2b — unit tests for {@link OpenCodeBackend} and the Phase 2b
 * additions to the coding-backend abstraction.
 *
 * <p>Tests are subprocess-free: a stub {@link OpenCodeBackend.ProcessRunner}
 * supplies canned stdout/stderr so the parser + argv-building paths can be
 * verified without {@code opencode} on PATH. The single live-binary check
 * ({@link #healthCheck_returns_false_when_binary_missing}) calls the
 * production runner with a deliberately-bogus path.</p>
 */
class OpenCodeBackendTest {

    @AfterEach
    void tearDown() {
        BackendRegistry.get().clear();
    }

    // ─── Sealed-family conformance ──────────────────────────────────

    @Test void sealed_interface_now_permits_opencode() {
        var permitted = CodingTaskBackend.class.getPermittedSubclasses();
        assertThat(permitted).contains(OpenCodeBackend.class);
        assertThat(permitted).contains(CodeZaikuBackend.class);
    }

    // ─── Basic contract ─────────────────────────────────────────────

    @Test void name_is_opencode() {
        var b = new OpenCodeBackend(OpenCodeRuntimeConfig.defaults(), stub("", "", 0, false));
        assertThat(b.name()).isEqualTo("opencode");
        assertThat(b.name()).isEqualTo(OpenCodeBackend.NAME);
    }

    @Test void tier_is_local_free() {
        var b = new OpenCodeBackend(OpenCodeRuntimeConfig.defaults(), stub("", "", 0, false));
        assertThat(b.tier()).isEqualTo(BackendTier.LOCAL_FREE);
    }

    @Test void estimated_cu_is_zero() {
        var b = new OpenCodeBackend(OpenCodeRuntimeConfig.defaults(), stub("", "", 0, false));
        var spec = TaskSpec.create("did:c", "code", "anything");
        assertThat(b.estimatedCu(spec)).isEqualTo(0L);
    }

    // ─── Config loading ─────────────────────────────────────────────

    @Test void config_falls_back_to_defaults_when_block_missing() {
        var cfg = OpenCodeRuntimeConfig.fromConfig(ConfigFactory.empty());
        assertThat(cfg.enabled()).isTrue();
        assertThat(cfg.executablePath()).isEqualTo(OpenCodeRuntimeConfig.DEFAULT_EXECUTABLE);
        assertThat(cfg.baseUrl()).isEqualTo(OpenCodeRuntimeConfig.DEFAULT_BASE_URL);
        assertThat(cfg.model()).isEqualTo(OpenCodeRuntimeConfig.DEFAULT_MODEL);
    }

    @Test void config_overrides_apply_via_typesafe() {
        var raw = ""
            + "wyrdsekai.coding.backends.opencode {\n"
            + "  enabled = false\n"
            + "  executable-path = \"/opt/wyrdsekai/coding-cli/opencode\"\n"
            + "  base-url = \"http://home-server:8200/v1\"\n"
            + "  model = \"wyrdsekai-3.5-9b-vitality-v6\"\n"
            + "  api-key = \"sk-not-used\"\n"
            + "  max-files-per-task = 10\n"
            + "  max-wallclock-min = 5\n"
            + "  extra-flags = [\"--share\"]\n"
            + "}";
        Config c = ConfigFactory.parseString(raw);
        var cfg = OpenCodeRuntimeConfig.fromConfig(c);

        assertThat(cfg.enabled()).isFalse();
        assertThat(cfg.executablePath()).isEqualTo("/opt/wyrdsekai/coding-cli/opencode");
        assertThat(cfg.baseUrl()).isEqualTo("http://home-server:8200/v1");
        assertThat(cfg.model()).isEqualTo("wyrdsekai-3.5-9b-vitality-v6");
        assertThat(cfg.apiKey()).isEqualTo("sk-not-used");
        assertThat(cfg.maxFilesPerTask()).isEqualTo(10);
        assertThat(cfg.maxWallclock()).isEqualTo(Duration.ofMinutes(5));
        assertThat(cfg.extraFlags()).containsExactly("--share");
    }

    @Test void config_underscore_keys_are_accepted_too() {
        // The spec block uses snake_case in JSON-style examples; the
        // typesafe-config side accepts both. fromConfig() should read
        // either, since the schema validator is permissive about case.
        var raw = ""
            + "wyrdsekai.coding.backends.opencode {\n"
            + "  enabled = true\n"
            + "  executable_path = \"/path/to/oc\"\n"
            + "  base_url = \"http://x:9000/v1\"\n"
            + "  max_files_per_task = 7\n"
            + "  max_wallclock_min = 3\n"
            + "  extra_flags = [\"-q\"]\n"
            + "}";
        var cfg = OpenCodeRuntimeConfig.fromConfig(ConfigFactory.parseString(raw));
        assertThat(cfg.executablePath()).isEqualTo("/path/to/oc");
        assertThat(cfg.baseUrl()).isEqualTo("http://x:9000/v1");
        assertThat(cfg.maxFilesPerTask()).isEqualTo(7);
        assertThat(cfg.maxWallclock()).isEqualTo(Duration.ofMinutes(3));
        assertThat(cfg.extraFlags()).containsExactly("-q");
    }

    // ─── argv construction ─────────────────────────────────────────

    @Test void argv_includes_run_format_json_and_model() {
        var cfg = new OpenCodeRuntimeConfig(
            true, "opencode", "http://home-server:8200/v1",
            "wyrd-9b", "wyrd-local", "k", 50, Duration.ofMinutes(30), List.of());
        var b = new OpenCodeBackend(cfg, stub("", "", 0, false));
        var spec = TaskSpec.create("did:c", "code", "fix bug X");
        var args = b.buildArgs(spec);

        // Must match the headless invocation contract:
        //   opencode run --format json --model wyrd-local/wyrd-9b "<PREAMBLE+TASK>"
        assertThat(args.get(0)).isEqualTo("opencode");
        assertThat(args).contains("run");
        assertThat(args).containsSequence("--format", "json");
        assertThat(args).containsSequence("--model", "wyrd-local/wyrd-9b");
        assertThat(args).contains("--dangerously-skip-permissions");
        // Trailing message is the description wrapped with the
        // ITEMS_AS_TOOLS_PREAMBLE — assert contents instead of equality.
        var last = args.get(args.size() - 1);
        assertThat(last).contains("fix bug X");
        assertThat(last).contains("ITEMS-AS-TOOLS OUTPUT CONTRACT");
        assertThat(last).contains("--- TASK ---");
    }

    @Test void argv_inserts_workspace_dir_when_set() {
        var cfg = OpenCodeRuntimeConfig.defaults();
        var b = new OpenCodeBackend(cfg, stub("", "", 0, false));
        var spec = new TaskSpec(UUID.randomUUID(), null, "code", "msg",
            "/tmp/repo", List.of(), 0L, null);
        var args = b.buildArgs(spec);

        assertThat(args).containsSequence("--dir", "/tmp/repo");
    }

    @Test void argv_appends_extra_flags_before_message() {
        var cfg = new OpenCodeRuntimeConfig(
            true, "opencode", "http://x/v1", "m", "p", "k",
            50, Duration.ofMinutes(30), List.of("--thinking", "--share"));
        var b = new OpenCodeBackend(cfg, stub("", "", 0, false));
        var args = b.buildArgs(TaskSpec.create("did:c", "code", "the message"));

        // The flags slot in before the trailing message arg.
        // Message arg is the wrapped prompt — locate it by content match
        // since exact-equal won't work after the preamble wrap.
        int thinkingIdx = args.indexOf("--thinking");
        int messageIdx = -1;
        for (int i = 0; i < args.size(); i++) {
            if (args.get(i).contains("the message")) { messageIdx = i; break; }
        }
        assertThat(thinkingIdx).isPositive();
        assertThat(messageIdx).isPositive();
        assertThat(thinkingIdx).isLessThan(messageIdx);
    }

    // ─── env construction ─────────────────────────────────────────

    @Test void env_writes_provider_config_with_base_url() throws IOException {
        var cfg = new OpenCodeRuntimeConfig(
            true, "opencode", "http://home-server:8200/v1",
            "qwen3.5-9b", "wyrd-local", "k",
            50, Duration.ofMinutes(30), List.of());
        var b = new OpenCodeBackend(cfg, stub("", "", 0, false));

        var env = b.buildEnv();
        var configPath = env.get("OPENCODE_CONFIG");
        assertThat(configPath).isNotBlank();
        assertThat(env).containsKey("OPENAI_API_KEY");

        var contents = Files.readString(Path.of(configPath));
        assertThat(contents).contains("http://home-server:8200/v1");
        assertThat(contents).contains("qwen3.5-9b");
        assertThat(contents).contains("wyrd-local");
        assertThat(contents).contains("@ai-sdk/openai-compatible");
    }

    // ─── submitTask happy path ─────────────────────────────────────

    @Test void submit_task_returns_succeeded_on_clean_run() throws Exception {
        // OpenCode JSON output mentions "edited_files" — parser should
        // extract them into the SourceArtifact.
        var stdout = """
            {"event": "task_started"}
            {"event": "edit", "file": "src/foo.java"}
            {"event": "edit", "file": "src/bar.java"}
            {"event": "complete", "edited_files": ["src/foo.java", "src/baz.java"]}
            """;
        var b = new OpenCodeBackend(OpenCodeRuntimeConfig.defaults(),
            stub(stdout, "", 0, false));

        var spec = TaskSpec.create("did:c", "code", "do stuff");
        var result = b.submitTask(spec).get(5, TimeUnit.SECONDS);

        assertThat(result.status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(result.backend()).isEqualTo("opencode");
        assertThat(result.cuConsumed()).isEqualTo(0L);
        assertThat(result.summary()).contains("OpenCode");
        assertThat(result.artifactIds()).hasSize(1);

        var artifacts = b.artifactsFor(result.taskId().toString())
            .toList();
        assertThat(artifacts).hasSize(1);
        var src = (SourceArtifact) artifacts.get(0);
        assertThat(src.backend()).isEqualTo("opencode");
        // Files dedup: src/foo.java appears twice (single mention +
        // edited_files); src/bar.java once; src/baz.java once.
        assertThat(src.files()).contains("src/foo.java", "src/bar.java", "src/baz.java");
        assertThat(src.files().size())
            .as("dedup should collapse repeated paths")
            .isEqualTo((int) src.files().stream().distinct().count());
    }

    // ─── submitTask negative paths ─────────────────────────────────

    @Test void submit_task_marks_failed_on_nonzero_exit() throws Exception {
        var b = new OpenCodeBackend(OpenCodeRuntimeConfig.defaults(),
            stub("", "couldn't reach llama-server", 2, false));
        var result = b.submitTask(TaskSpec.create("did:c", "code", "x"))
            .get(5, TimeUnit.SECONDS);

        assertThat(result.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(result.summary()).contains("exit");
        assertThat(result.summary()).contains("llama-server");
    }

    @Test void submit_task_marks_timed_out_when_runner_reports_timeout() throws Exception {
        var b = new OpenCodeBackend(OpenCodeRuntimeConfig.defaults(),
            stub("", "", -1, true));
        var result = b.submitTask(TaskSpec.create("did:c", "code", "x"))
            .get(5, TimeUnit.SECONDS);

        assertThat(result.status()).isEqualTo(TaskStatus.TIMED_OUT);
    }

    @Test void submit_task_marks_failed_on_subprocess_exception() throws Exception {
        OpenCodeBackend.ProcessRunner throwing = (args, env, timeout) -> {
            throw new IOException("opencode: command not found");
        };
        var b = new OpenCodeBackend(OpenCodeRuntimeConfig.defaults(), throwing);
        var result = b.submitTask(TaskSpec.create("did:c", "code", "x"))
            .get(5, TimeUnit.SECONDS);

        assertThat(result.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(result.summary()).contains("subprocess error");
    }

    @Test void submit_task_handles_malformed_json_gracefully() throws Exception {
        // OpenCode stdout is opaque bytes — task still SUCCEEDS but the
        // file list is empty, which is fine: caller sees the bridge
        // placed an artifact with whatever metadata could be salvaged.
        var b = new OpenCodeBackend(OpenCodeRuntimeConfig.defaults(),
            stub("not json at all", "", 0, false));
        var result = b.submitTask(TaskSpec.create("did:c", "code", "x"))
            .get(5, TimeUnit.SECONDS);

        assertThat(result.status()).isEqualTo(TaskStatus.SUCCEEDED);
        var artifacts = b.artifactsFor(result.taskId().toString()).toList();
        assertThat(artifacts).hasSize(1);
        var src = (SourceArtifact) artifacts.get(0);
        assertThat(src.files()).isEmpty();
    }

    @Test void submit_task_when_disabled_returns_failed_immediately() throws Exception {
        var disabled = new OpenCodeRuntimeConfig(
            false, null, null, null, null, null, 0, null, List.of());
        var b = new OpenCodeBackend(disabled, stub("never called", "", 0, false));
        var result = b.submitTask(TaskSpec.create("did:c", "code", "x"))
            .get(5, TimeUnit.SECONDS);

        assertThat(result.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(result.summary()).contains("disabled");
    }

    // ─── Health check ──────────────────────────────────────────────

    @Test void healthCheck_returns_true_when_version_probe_succeeds() throws Exception {
        var b = new OpenCodeBackend(OpenCodeRuntimeConfig.defaults(),
            stub("opencode 1.14.0", "", 0, false));
        var healthy = b.healthCheck().get(5, TimeUnit.SECONDS);
        assertThat(healthy).isTrue();
    }

    @Test void healthCheck_returns_false_when_disabled() throws Exception {
        var disabled = new OpenCodeRuntimeConfig(
            false, null, null, null, null, null, 0, null, List.of());
        var b = new OpenCodeBackend(disabled, stub("opencode 1.14", "", 0, false));
        assertThat(b.healthCheck().get(5, TimeUnit.SECONDS)).isFalse();
    }

    @Test void healthCheck_returns_false_when_binary_missing() throws Exception {
        // Live binary check — uses a deliberately-bogus path so the
        // production runner returns IOException, which the backend
        // translates into a graceful `false` (with the install-hint
        // log line). No fixture needed.
        var cfg = new OpenCodeRuntimeConfig(
            true,
            "/nonexistent/path/to/opencode-binary-" + UUID.randomUUID(),
            null, null, null, null, 0, Duration.ofSeconds(2), List.of());
        var b = new OpenCodeBackend(cfg);
        var healthy = b.healthCheck().get(10, TimeUnit.SECONDS);
        assertThat(healthy).isFalse();
    }

    @Test void healthCheck_returns_false_on_nonzero_exit() throws Exception {
        var b = new OpenCodeBackend(OpenCodeRuntimeConfig.defaults(),
            stub("", "boom", 1, false));
        assertThat(b.healthCheck().get(5, TimeUnit.SECONDS)).isFalse();
    }

    // ─── Bootstrap registration ────────────────────────────────────

    @Test void bootstrap_registers_backend_and_adapter_when_enabled() {
        BackendRegistry.get().clear();
        CodingBackendBootstrap.init(ConfigFactory.parseString(
            "wyrdsekai.coding.backends.opencode { enabled = true }"));

        assertThat(BackendRegistry.get().backendFor("opencode")).isPresent();
        assertThat(BackendRegistry.get().adapterFor("opencode")).isPresent();
    }

    @Test void bootstrap_skips_when_disabled() {
        BackendRegistry.get().clear();
        CodingBackendBootstrap.init(ConfigFactory.parseString(
            "wyrdsekai.coding.backends.opencode { enabled = false }"));

        assertThat(BackendRegistry.get().backendFor("opencode")).isEmpty();
    }

    @Test void bootstrap_idempotent_does_not_double_register() {
        BackendRegistry.get().clear();
        var cfg = ConfigFactory.parseString(
            "wyrdsekai.coding.backends.opencode { enabled = true }");
        CodingBackendBootstrap.init(cfg);
        var first = BackendRegistry.get().backendFor("opencode").orElseThrow();
        CodingBackendBootstrap.init(cfg);
        var second = BackendRegistry.get().backendFor("opencode").orElseThrow();
        assertThat(second).isSameAs(first);
    }

    @Test void bootstrap_with_null_config_uses_defaults_and_registers() {
        BackendRegistry.get().clear();
        CodingBackendBootstrap.init(null);
        assertThat(BackendRegistry.get().backendFor("opencode")).isPresent();
    }

    // ─── Helpers ──────────────────────────────────────────────────

    /** Build a ProcessRunner that returns a fixed result regardless of args. */
    private static OpenCodeBackend.ProcessRunner stub(
            String stdout, String stderr, int exitCode, boolean timedOut) {
        return (args, env, timeout) -> new OpenCodeBackend.ProcessResult(
            exitCode, stdout, stderr, timedOut);
    }

    @SuppressWarnings("unused") // reserved for future tests checking env
    private static Map.Entry<String, String> e(String k, String v) {
        return Map.entry(k, v);
    }
}
