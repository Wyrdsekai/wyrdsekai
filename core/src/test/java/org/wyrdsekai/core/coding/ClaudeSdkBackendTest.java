package org.wyrdsekai.core.coding;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2e — unit tests for {@link ClaudeSdkBackend}.
 *
 * <p>Tests are subprocess-free: a stub
 * {@link ClaudeSdkBackend.ProcessRunner} supplies canned stdout/stderr.</p>
 */
class ClaudeSdkBackendTest {

    @AfterEach
    void tearDown() {
        BackendRegistry.get().clear();
    }

    // ─── Sealed-family conformance ──────────────────────────────────

    @Test void sealed_interface_now_permits_claude_sdk() {
        var permitted = CodingTaskBackend.class.getPermittedSubclasses();
        assertThat(permitted).contains(ClaudeSdkBackend.class);
    }

    // ─── Basic contract ─────────────────────────────────────────────

    @Test void name_is_claude_sdk() {
        var b = new ClaudeSdkBackend(ClaudeSdkRuntimeConfig.defaults(), oauthResolver(),
            stub("", "", 0, false));
        assertThat(b.name()).isEqualTo("claude-sdk");
        assertThat(b.name()).isEqualTo(ClaudeSdkBackend.NAME);
    }

    @Test void tier_is_cloud_paid() {
        var b = new ClaudeSdkBackend(ClaudeSdkRuntimeConfig.defaults(), oauthResolver(),
            stub("", "", 0, false));
        assertThat(b.tier()).isEqualTo(BackendTier.CLOUD_PAID);
    }

    @Test void estimated_cu_is_capped_at_5000() {
        var b = new ClaudeSdkBackend(ClaudeSdkRuntimeConfig.defaults(), oauthResolver(),
            stub("", "", 0, false));
        for (var t : List.of("explore", "implement_feature", "refactor", "code", "test")) {
            var spec = TaskSpec.create("did:c", t, "x");
            assertThat(b.estimatedCu(spec))
                .as("estimate for taskType=%s must be <=5000 CU", t)
                .isLessThanOrEqualTo(5000L);
        }
    }

    @Test void estimated_cu_baseline_is_200() {
        var b = new ClaudeSdkBackend(ClaudeSdkRuntimeConfig.defaults(), oauthResolver(),
            stub("", "", 0, false));
        var spec = TaskSpec.create("did:c", null, "x");
        assertThat(b.estimatedCu(spec)).isEqualTo(200L);
    }

    @Test void estimated_cu_grows_with_description_length() {
        var b = new ClaudeSdkBackend(ClaudeSdkRuntimeConfig.defaults(), oauthResolver(),
            stub("", "", 0, false));
        var shortSpec = TaskSpec.create("did:c", "code", "x");
        var longSpec = TaskSpec.create("did:c", "code", "x".repeat(2000));
        assertThat(b.estimatedCu(longSpec)).isGreaterThan(b.estimatedCu(shortSpec));
    }

    // ─── Config loading ─────────────────────────────────────────────

    @Test void config_falls_back_to_defaults_when_block_missing() {
        var cfg = ClaudeSdkRuntimeConfig.fromConfig(ConfigFactory.empty());
        assertThat(cfg.enabled()).isFalse();
        assertThat(cfg.executablePath()).isEqualTo(ClaudeSdkRuntimeConfig.DEFAULT_EXECUTABLE);
        assertThat(cfg.model()).isEqualTo(ClaudeSdkRuntimeConfig.DEFAULT_MODEL);
        assertThat(cfg.useBare()).isTrue();
    }

    @Test void config_overrides_apply_via_typesafe_dash_case() {
        var raw = ""
            + "wyrdsekai.coding.backends.claude-sdk {\n"
            + "  enabled = true\n"
            + "  executable-path = \"/opt/claude\"\n"
            + "  model = \"opus\"\n"
            + "  use-bare = false\n"
            + "  max-wallclock-min = 60\n"
            + "  extra-flags = [\"--verbose\"]\n"
            + "}";
        Config c = ConfigFactory.parseString(raw);
        var cfg = ClaudeSdkRuntimeConfig.fromConfig(c);

        assertThat(cfg.enabled()).isTrue();
        assertThat(cfg.executablePath()).isEqualTo("/opt/claude");
        assertThat(cfg.model()).isEqualTo("opus");
        assertThat(cfg.useBare()).isFalse();
        assertThat(cfg.maxWallclock()).isEqualTo(Duration.ofMinutes(60));
        assertThat(cfg.extraFlags()).containsExactly("--verbose");
    }

    @Test void config_underscore_keys_are_accepted_too() {
        var raw = ""
            + "wyrdsekai.coding.backends.claude-sdk {\n"
            + "  enabled = true\n"
            + "  executable_path = \"/path/claude\"\n"
            + "  model = \"sonnet\"\n"
            + "  use_bare = true\n"
            + "  max_wallclock_min = 5\n"
            + "}";
        var cfg = ClaudeSdkRuntimeConfig.fromConfig(ConfigFactory.parseString(raw));
        assertThat(cfg.executablePath()).isEqualTo("/path/claude");
        assertThat(cfg.model()).isEqualTo("sonnet");
        assertThat(cfg.useBare()).isTrue();
        assertThat(cfg.maxWallclock()).isEqualTo(Duration.ofMinutes(5));
    }

    // ─── argv construction ─────────────────────────────────────────

    @Test void argv_includes_p_output_format_and_model() {
        var cfg = enabledDefaults();
        var b = new ClaudeSdkBackend(cfg, oauthResolver(), stub("", "", 0, false));
        var spec = TaskSpec.create("did:c", "code", "fix bug X");
        var args = b.buildArgs(spec, new AuthMode.OAuthSession());

        assertThat(args.get(0)).isEqualTo("claude");
        assertThat(args).contains("-p", "--output-format", "json", "--model");
        // OAuth path: must NOT include --bare.
        assertThat(args).doesNotContain("--bare");
    }

    @Test void argv_oauth_session_does_not_pass_bare() {
        // Regression: OAuth users on subscription tiers MUST NOT see
        // --bare in the argv, otherwise the SDK skips their keychain.
        var cfg = enabledDefaults();
        var b = new ClaudeSdkBackend(cfg, oauthResolver(), stub("", "", 0, false));
        var args = b.buildArgs(TaskSpec.create("did:c", "code", "x"), new AuthMode.OAuthSession());
        assertThat(args).doesNotContain("--bare");
    }

    @Test void argv_apikey_path_passes_bare_when_use_bare_is_true() {
        var cfg = enabledDefaults();
        var b = new ClaudeSdkBackend(cfg, oauthResolver(), stub("", "", 0, false));
        var args = b.buildArgs(TaskSpec.create("did:c", "code", "x"),
            new AuthMode.ApiKey("sk-ant-xyz"));
        assertThat(args).contains("--bare");
    }

    @Test void argv_apikey_path_skips_bare_when_use_bare_is_false() {
        var cfg = new ClaudeSdkRuntimeConfig(true, "claude", "sonnet", false,
            Duration.ofMinutes(30), List.of());
        var b = new ClaudeSdkBackend(cfg, oauthResolver(), stub("", "", 0, false));
        var args = b.buildArgs(TaskSpec.create("did:c", "code", "x"),
            new AuthMode.ApiKey("sk-ant-xyz"));
        assertThat(args).doesNotContain("--bare");
    }

    @Test void argv_does_not_include_api_key_value() {
        var cfg = enabledDefaults();
        var b = new ClaudeSdkBackend(cfg, oauthResolver(), stub("", "", 0, false));
        var args = b.buildArgs(TaskSpec.create("did:c", "code", "x"),
            new AuthMode.ApiKey("super-secret-anthropic-key"));
        var serialized = String.join(" ", args);
        assertThat(serialized).doesNotContain("super-secret-anthropic-key");
    }

    // ─── env construction ──────────────────────────────────────────

    @Test void env_apikey_lands_in_anthropic_api_key() {
        var b = new ClaudeSdkBackend(enabledDefaults(), oauthResolver(), stub("", "", 0, false));
        var env = b.buildEnv(new AuthMode.ApiKey("sk-ant-xyz"));
        assertThat(env).containsEntry("ANTHROPIC_API_KEY", "sk-ant-xyz");
    }

    @Test void env_oauth_session_emits_no_key() {
        var b = new ClaudeSdkBackend(enabledDefaults(), oauthResolver(), stub("", "", 0, false));
        var env = b.buildEnv(new AuthMode.OAuthSession());
        assertThat(env).isEmpty();
    }

    // ─── Auth gate ─────────────────────────────────────────────────

    @Test void auth_missing_short_circuits_without_subprocess() throws Exception {
        var ranSubprocess = new boolean[]{false};
        ClaudeSdkBackend.ProcessRunner neverRun = (args, env, stdin, t) -> {
            ranSubprocess[0] = true;
            throw new IllegalStateException("subprocess must not run on AuthMissing");
        };
        AuthResolver missing = name -> new AuthMode.AuthMissing(
            name, "wyrd coding login claude-sdk", "no auth");

        var b = new ClaudeSdkBackend(enabledDefaults(), missing, neverRun);
        var result = b.submitTask(TaskSpec.create("did:c", "code", "x"))
            .get(5, TimeUnit.SECONDS);

        assertThat(ranSubprocess[0]).isFalse();
        assertThat(result.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(result.summary()).contains("LOGIN_REQUIRED");
        assertThat(result.summary()).contains("wyrd coding login claude-sdk");
    }

    // ─── submitTask happy path ─────────────────────────────────────

    @Test void submit_task_returns_succeeded_on_clean_run() throws Exception {
        var json = """
            {
              "result": "Edited src/foo.java\\nCreated src/bar.java",
              "session_id": "sess-abc-123",
              "usage": {"input_tokens": 100, "output_tokens": 50},
              "total_cost_usd": 0.0125,
              "structured_output": {
                "files": ["src/foo.java", "src/bar.java"]
              }
            }
            """;
        var b = new ClaudeSdkBackend(enabledDefaults(), oauthResolver(),
            stub(json, "", 0, false));
        var spec = TaskSpec.create("did:c", "code", "do stuff");
        var result = b.submitTask(spec).get(5, TimeUnit.SECONDS);

        assertThat(result.status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(result.backend()).isEqualTo("claude-sdk");
        assertThat(result.cuConsumed()).isGreaterThan(0L);
        // Summary surfaces the model's reply text directly (the items-as-tools
        // shape check + workshop narration both need to see what claude said).
        assertThat(result.summary()).contains("Edited src/foo.java");
        assertThat(result.summary()).contains("Created src/bar.java");

        var artifacts = b.artifactsFor(result.taskId().toString()).toList();
        assertThat(artifacts).hasSize(1);
        var src = (SourceArtifact) artifacts.get(0);
        assertThat(src.files()).contains("src/foo.java", "src/bar.java");
        assertThat(src.backendMetadata()).containsEntry("session_id", "sess-abc-123");
        assertThat(src.backendMetadata()).containsKey("total_cost_usd");
    }

    @Test void submit_task_falls_back_to_prose_scan_for_files() throws Exception {
        // No structured_output — files must surface from result-text scan.
        var json = """
            {
              "result": "I edited src/foo.java and created src/bar.java for you.",
              "session_id": "sess-1",
              "usage": {"input_tokens": 10, "output_tokens": 5}
            }
            """;
        var b = new ClaudeSdkBackend(enabledDefaults(), oauthResolver(),
            stub(json, "", 0, false));
        var result = b.submitTask(TaskSpec.create("did:c", "code", "x"))
            .get(5, TimeUnit.SECONDS);

        var artifacts = b.artifactsFor(result.taskId().toString()).toList();
        var src = (SourceArtifact) artifacts.get(0);
        // Best-effort prose scan picks up both files.
        assertThat(src.files()).contains("src/foo.java", "src/bar.java");
    }

    // ─── submitTask negative paths ─────────────────────────────────

    @Test void submit_task_marks_failed_on_nonzero_exit() throws Exception {
        var b = new ClaudeSdkBackend(enabledDefaults(), oauthResolver(),
            stub("", "rate limited", 1, false));
        var result = b.submitTask(TaskSpec.create("did:c", "code", "x"))
            .get(5, TimeUnit.SECONDS);
        assertThat(result.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(result.summary()).contains("rate limited");
    }

    @Test void submit_task_marks_timed_out_when_runner_reports_timeout() throws Exception {
        var b = new ClaudeSdkBackend(enabledDefaults(), oauthResolver(),
            stub("", "", -1, true));
        var result = b.submitTask(TaskSpec.create("did:c", "code", "x"))
            .get(5, TimeUnit.SECONDS);
        assertThat(result.status()).isEqualTo(TaskStatus.TIMED_OUT);
    }

    @Test void submit_task_when_disabled_returns_failed_immediately() throws Exception {
        var disabled = new ClaudeSdkRuntimeConfig(false, null, null, true, null, List.of());
        var b = new ClaudeSdkBackend(disabled, oauthResolver(), stub("never", "", 0, false));
        var result = b.submitTask(TaskSpec.create("did:c", "code", "x"))
            .get(5, TimeUnit.SECONDS);
        assertThat(result.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(result.summary()).contains("disabled");
    }

    // ─── healthCheck ─────────────────────────────────────────────

    @Test void healthCheck_returns_true_when_version_probe_succeeds() throws Exception {
        var b = new ClaudeSdkBackend(enabledDefaults(), oauthResolver(),
            stub("claude 1.0.0", "", 0, false));
        assertThat(b.healthCheck().get(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test void healthCheck_returns_false_when_disabled() throws Exception {
        var disabled = new ClaudeSdkRuntimeConfig(false, null, null, true, null, List.of());
        var b = new ClaudeSdkBackend(disabled, oauthResolver(), stub("ok", "", 0, false));
        assertThat(b.healthCheck().get(5, TimeUnit.SECONDS)).isFalse();
    }

    @Test void healthCheck_returns_false_on_subprocess_ioexception() throws Exception {
        ClaudeSdkBackend.ProcessRunner throwing = (a, e, s, t) -> {
            throw new IOException("claude: not found");
        };
        var b = new ClaudeSdkBackend(enabledDefaults(), oauthResolver(), throwing);
        assertThat(b.healthCheck().get(5, TimeUnit.SECONDS)).isFalse();
    }

    // ─── Helpers ──────────────────────────────────────────────────

    private static ClaudeSdkRuntimeConfig enabledDefaults() {
        var d = ClaudeSdkRuntimeConfig.defaults();
        return new ClaudeSdkRuntimeConfig(true, d.executablePath(), d.model(),
            d.useBare(), d.maxWallclock(), d.extraFlags());
    }

    private static AuthResolver oauthResolver() {
        return name -> new AuthMode.OAuthSession();
    }

    private static ClaudeSdkBackend.ProcessRunner stub(
            String stdout, String stderr, int exitCode, boolean timedOut) {
        return (args, env, stdin, timeout) -> new ClaudeSdkBackend.ProcessResult(
            exitCode, stdout, stderr, timedOut);
    }
}
