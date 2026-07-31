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

/** Phase 2e — unit tests for {@link GeminiCliBackend}. */
class GeminiCliBackendTest {

    @AfterEach
    void tearDown() {
        BackendRegistry.get().clear();
    }

    // ─── Sealed-family conformance ──────────────────────────────────

    @Test void sealed_interface_now_permits_gemini_cli() {
        var permitted = CodingTaskBackend.class.getPermittedSubclasses();
        assertThat(permitted).contains(GeminiCliBackend.class);
    }

    // ─── Basic contract ─────────────────────────────────────────────

    @Test void name_is_gemini_cli() {
        var b = new GeminiCliBackend(GeminiCliRuntimeConfig.defaults(), apiKeyResolver(),
            stub("", "", 0, false));
        assertThat(b.name()).isEqualTo("gemini-cli");
        assertThat(b.name()).isEqualTo(GeminiCliBackend.NAME);
    }

    @Test void tier_is_cloud_paid() {
        var b = new GeminiCliBackend(GeminiCliRuntimeConfig.defaults(), apiKeyResolver(),
            stub("", "", 0, false));
        assertThat(b.tier()).isEqualTo(BackendTier.CLOUD_PAID);
    }

    @Test void estimated_cu_is_capped_at_5000() {
        var b = new GeminiCliBackend(GeminiCliRuntimeConfig.defaults(), apiKeyResolver(),
            stub("", "", 0, false));
        for (var t : List.of("explore", "implement_feature", "refactor", "code", "test")) {
            var spec = TaskSpec.create("did:c", t, "x");
            assertThat(b.estimatedCu(spec))
                .as("estimate for taskType=%s must be <=5000 CU", t)
                .isLessThanOrEqualTo(5000L);
        }
    }

    @Test void estimated_cu_grows_more_aggressively_with_prompt_length() {
        // Gemini's 1M-context window means long prompts are pricier than
        // other backends — the adapter weighs description length more
        // heavily.
        var b = new GeminiCliBackend(GeminiCliRuntimeConfig.defaults(), apiKeyResolver(),
            stub("", "", 0, false));
        var shortSpec = TaskSpec.create("did:c", "code", "x");
        var longSpec = TaskSpec.create("did:c", "code", "x".repeat(5000));
        assertThat(b.estimatedCu(longSpec)).isGreaterThan(b.estimatedCu(shortSpec));
    }

    // ─── Config loading ─────────────────────────────────────────────

    @Test void config_falls_back_to_defaults_when_block_missing() {
        var cfg = GeminiCliRuntimeConfig.fromConfig(ConfigFactory.empty());
        assertThat(cfg.enabled()).isFalse();
        assertThat(cfg.executablePath()).isEqualTo(GeminiCliRuntimeConfig.DEFAULT_EXECUTABLE);
        assertThat(cfg.model()).isEqualTo(GeminiCliRuntimeConfig.DEFAULT_MODEL);
        assertThat(cfg.trustWorkspace()).isFalse();
    }

    @Test void config_overrides_apply_via_typesafe_dash_case() {
        var raw = ""
            + "wyrdsekai.coding.backends.gemini-cli {\n"
            + "  enabled = true\n"
            + "  executable-path = \"/opt/gemini\"\n"
            + "  model = \"gemini-2.5-pro\"\n"
            + "  temperature = 0.7\n"
            + "  trust-workspace = true\n"
            + "  max-wallclock-min = 60\n"
            + "}";
        Config c = ConfigFactory.parseString(raw);
        var cfg = GeminiCliRuntimeConfig.fromConfig(c);

        assertThat(cfg.enabled()).isTrue();
        assertThat(cfg.executablePath()).isEqualTo("/opt/gemini");
        assertThat(cfg.model()).isEqualTo("gemini-2.5-pro");
        assertThat(cfg.temperature()).isEqualTo(0.7);
        assertThat(cfg.trustWorkspace()).isTrue();
        assertThat(cfg.maxWallclock()).isEqualTo(Duration.ofMinutes(60));
    }

    @Test void config_underscore_keys_are_accepted_too() {
        var raw = ""
            + "wyrdsekai.coding.backends.gemini-cli {\n"
            + "  enabled = true\n"
            + "  trust_workspace = true\n"
            + "  max_wallclock_min = 5\n"
            + "}";
        var cfg = GeminiCliRuntimeConfig.fromConfig(ConfigFactory.parseString(raw));
        assertThat(cfg.trustWorkspace()).isTrue();
        assertThat(cfg.maxWallclock()).isEqualTo(Duration.ofMinutes(5));
    }

    // ─── argv construction ─────────────────────────────────────────

    @Test void argv_uses_p_flag_with_positional_prompt() {
        var cfg = enabledDefaults();
        var b = new GeminiCliBackend(cfg, apiKeyResolver(), stub("", "", 0, false));
        var args = b.buildArgs(TaskSpec.create("did:c", "code", "fix bug X"));

        assertThat(args.get(0)).isEqualTo("gemini");
        assertThat(args).contains("-p");
        // -p positional prompt is wrapped with ITEMS_AS_TOOLS_PREAMBLE.
        assertThat(args.stream().anyMatch(a -> a.contains("fix bug X"))).isTrue();
        assertThat(args.stream().anyMatch(a -> a.contains("ITEMS-AS-TOOLS"))).isTrue();
        assertThat(args).contains("-m");
    }

    @Test void argv_includes_model_flag() {
        var cfg = enabledDefaults();
        var b = new GeminiCliBackend(cfg, apiKeyResolver(), stub("", "", 0, false));
        var args = b.buildArgs(TaskSpec.create("did:c", "code", "x"));
        // -m model
        int idx = args.indexOf("-m");
        assertThat(idx).isGreaterThanOrEqualTo(0);
        assertThat(args.get(idx + 1)).isEqualTo(GeminiCliRuntimeConfig.DEFAULT_MODEL);
    }

    @Test void argv_temperature_emitted_only_when_non_negative() {
        var cfgNoTemp = enabledDefaults(); // temperature = -1 sentinel
        var bNo = new GeminiCliBackend(cfgNoTemp, apiKeyResolver(), stub("", "", 0, false));
        assertThat(bNo.buildArgs(TaskSpec.create("did:c", "code", "x")))
            .doesNotContain("--temperature");

        var cfgWithTemp = new GeminiCliRuntimeConfig(true, "gemini",
            "gemini-2.5-flash", 0.5, false, Duration.ofMinutes(30), List.of());
        var bYes = new GeminiCliBackend(cfgWithTemp, apiKeyResolver(), stub("", "", 0, false));
        assertThat(bYes.buildArgs(TaskSpec.create("did:c", "code", "x")))
            .contains("--temperature");
    }

    @Test void argv_trust_emitted_only_when_trust_workspace_true() {
        var cfgNoTrust = enabledDefaults(); // trustWorkspace = false
        var bNo = new GeminiCliBackend(cfgNoTrust, apiKeyResolver(), stub("", "", 0, false));
        assertThat(bNo.buildArgs(TaskSpec.create("did:c", "code", "x")))
            .doesNotContain("--trust");

        var cfgTrust = new GeminiCliRuntimeConfig(true, "gemini",
            "gemini-2.5-flash", -1.0, true, Duration.ofMinutes(30), List.of());
        var bYes = new GeminiCliBackend(cfgTrust, apiKeyResolver(), stub("", "", 0, false));
        assertThat(bYes.buildArgs(TaskSpec.create("did:c", "code", "x")))
            .contains("--trust");
    }

    @Test void argv_does_not_include_api_key_value() {
        var cfg = enabledDefaults();
        var b = new GeminiCliBackend(cfg, name -> new AuthMode.ApiKey("super-secret-google-key"),
            stub("", "", 0, false));
        var args = b.buildArgs(TaskSpec.create("did:c", "code", "x"));
        var serialized = String.join(" ", args);
        assertThat(serialized).doesNotContain("super-secret-google-key");
    }

    // ─── env construction ──────────────────────────────────────────

    @Test void env_apikey_lands_in_gemini_api_key() {
        var b = new GeminiCliBackend(enabledDefaults(), apiKeyResolver(), stub("", "", 0, false));
        var env = b.buildEnv(new AuthMode.ApiKey("AIza-xyz"));
        assertThat(env).containsEntry("GEMINI_API_KEY", "AIza-xyz");
        // Adapter sets GOOGLE_API_KEY too as an alternate.
        assertThat(env).containsEntry("GOOGLE_API_KEY", "AIza-xyz");
    }

    @Test void env_oauth_session_emits_no_key() {
        // Defensive: even though headless OAuth shouldn't happen for
        // gemini-cli (manifest declares headless_supported=false), the
        // adapter must handle it gracefully.
        var b = new GeminiCliBackend(enabledDefaults(), apiKeyResolver(), stub("", "", 0, false));
        var env = b.buildEnv(new AuthMode.OAuthSession());
        assertThat(env).isEmpty();
    }

    // ─── Auth gate ─────────────────────────────────────────────────

    @Test void auth_missing_short_circuits_without_subprocess() throws Exception {
        var ranSubprocess = new boolean[]{false};
        GeminiCliBackend.ProcessRunner neverRun = (args, env, t) -> {
            ranSubprocess[0] = true;
            throw new IllegalStateException("subprocess must not run on AuthMissing");
        };
        AuthResolver missing = name -> new AuthMode.AuthMissing(
            name, "set GEMINI_API_KEY in your Key Chest", "no auth");

        var b = new GeminiCliBackend(enabledDefaults(), missing, neverRun);
        var result = b.submitTask(TaskSpec.create("did:c", "code", "x"))
            .get(5, TimeUnit.SECONDS);

        assertThat(ranSubprocess[0]).isFalse();
        assertThat(result.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(result.summary()).contains("LOGIN_REQUIRED");
        assertThat(result.summary()).contains("GEMINI_API_KEY");
    }

    // ─── submitTask happy path ─────────────────────────────────────

    @Test void submit_task_returns_succeeded_on_clean_run() throws Exception {
        // Gemini's default output is prose; the adapter scans for file
        // mentions like "Edited foo.java".
        var stdout = "I edited src/foo.java successfully and created src/bar.java for you";
        var b = new GeminiCliBackend(enabledDefaults(), apiKeyResolver(),
            stub(stdout, "", 0, false));
        var result = b.submitTask(TaskSpec.create("did:c", "code", "x"))
            .get(5, TimeUnit.SECONDS);

        assertThat(result.status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(result.backend()).isEqualTo("gemini-cli");
        var artifacts = b.artifactsFor(result.taskId().toString()).toList();
        var src = (SourceArtifact) artifacts.get(0);
        assertThat(src.files()).contains("src/foo.java", "src/bar.java");
    }

    @Test void submit_task_marks_failed_on_nonzero_exit() throws Exception {
        var b = new GeminiCliBackend(enabledDefaults(), apiKeyResolver(),
            stub("", "auth error", 1, false));
        var result = b.submitTask(TaskSpec.create("did:c", "code", "x"))
            .get(5, TimeUnit.SECONDS);
        assertThat(result.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(result.summary()).contains("auth error");
    }

    @Test void submit_task_marks_timed_out_when_runner_reports_timeout() throws Exception {
        var b = new GeminiCliBackend(enabledDefaults(), apiKeyResolver(),
            stub("", "", -1, true));
        var result = b.submitTask(TaskSpec.create("did:c", "code", "x"))
            .get(5, TimeUnit.SECONDS);
        assertThat(result.status()).isEqualTo(TaskStatus.TIMED_OUT);
    }

    @Test void submit_task_when_disabled_returns_failed_immediately() throws Exception {
        var disabled = new GeminiCliRuntimeConfig(false, null, null, -1.0, false, null, List.of());
        var b = new GeminiCliBackend(disabled, apiKeyResolver(), stub("never", "", 0, false));
        var result = b.submitTask(TaskSpec.create("did:c", "code", "x"))
            .get(5, TimeUnit.SECONDS);
        assertThat(result.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(result.summary()).contains("disabled");
    }

    // ─── healthCheck ─────────────────────────────────────────────

    @Test void healthCheck_returns_true_when_version_probe_succeeds() throws Exception {
        var b = new GeminiCliBackend(enabledDefaults(), apiKeyResolver(),
            stub("gemini 0.40.1", "", 0, false));
        assertThat(b.healthCheck().get(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test void healthCheck_returns_false_when_disabled() throws Exception {
        var disabled = new GeminiCliRuntimeConfig(false, null, null, -1.0, false, null, List.of());
        var b = new GeminiCliBackend(disabled, apiKeyResolver(), stub("ok", "", 0, false));
        assertThat(b.healthCheck().get(5, TimeUnit.SECONDS)).isFalse();
    }

    @Test void healthCheck_returns_false_on_subprocess_ioexception() throws Exception {
        GeminiCliBackend.ProcessRunner throwing = (a, e, t) -> {
            throw new IOException("gemini: not found");
        };
        var b = new GeminiCliBackend(enabledDefaults(), apiKeyResolver(), throwing);
        assertThat(b.healthCheck().get(5, TimeUnit.SECONDS)).isFalse();
    }

    // ─── Helpers ──────────────────────────────────────────────────

    private static GeminiCliRuntimeConfig enabledDefaults() {
        var d = GeminiCliRuntimeConfig.defaults();
        return new GeminiCliRuntimeConfig(true, d.executablePath(), d.model(),
            d.temperature(), d.trustWorkspace(), d.maxWallclock(), d.extraFlags());
    }

    /** Gemini CLI is API-key-only on headless hosts (manifest enforces). */
    private static AuthResolver apiKeyResolver() {
        return name -> new AuthMode.ApiKey("test-gemini-key");
    }

    private static GeminiCliBackend.ProcessRunner stub(
            String stdout, String stderr, int exitCode, boolean timedOut) {
        return (args, env, timeout) -> new GeminiCliBackend.ProcessResult(
            exitCode, stdout, stderr, timedOut);
    }
}
