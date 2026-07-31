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
 * Phase 2d — unit tests for {@link ClineBackend}.
 */
class ClineBackendTest {

    @AfterEach
    void tearDown() {
        BackendRegistry.get().clear();
    }

    // ─── Sealed-family conformance ──────────────────────────────────

    @Test void sealed_interface_now_permits_cline() {
        var permitted = CodingTaskBackend.class.getPermittedSubclasses();
        assertThat(permitted).contains(ClineBackend.class);
    }

    // ─── Basic contract ─────────────────────────────────────────────

    @Test void name_is_cline() {
        var b = new ClineBackend(ClineRuntimeConfig.defaults(), oauthResolver(), stub("", "", 0, false));
        assertThat(b.name()).isEqualTo("cline");
        assertThat(b.name()).isEqualTo(ClineBackend.NAME);
    }

    @Test void tier_is_cloud_paid() {
        var b = new ClineBackend(ClineRuntimeConfig.defaults(), oauthResolver(), stub("", "", 0, false));
        assertThat(b.tier()).isEqualTo(BackendTier.CLOUD_PAID);
    }

    @Test void estimated_cu_is_capped_at_2000() {
        var b = new ClineBackend(ClineRuntimeConfig.defaults(), oauthResolver(), stub("", "", 0, false));
        for (var t : List.of("explore", "implement_feature", "refactor", "code", "test")) {
            assertThat(b.estimatedCu(TaskSpec.create("did:c", t, "x")))
                .isLessThanOrEqualTo(2000L);
        }
    }

    // ─── Config loading ─────────────────────────────────────────────

    @Test void config_falls_back_to_defaults_when_block_missing() {
        var cfg = ClineRuntimeConfig.fromConfig(ConfigFactory.empty());
        assertThat(cfg.enabled()).isFalse();
        assertThat(cfg.executablePath()).isEqualTo(ClineRuntimeConfig.DEFAULT_EXECUTABLE);
        assertThat(cfg.provider()).isNull();
    }

    @Test void config_overrides_apply_via_typesafe_dash_case() {
        var raw = ""
            + "wyrdsekai.coding.backends.cline {\n"
            + "  enabled = true\n"
            + "  executable-path = \"/opt/wyrdsekai/coding-cli/cline\"\n"
            + "  provider = \"anthropic\"\n"
            + "  yolo = true\n"
            + "  max-wallclock-min = 60\n"
            + "  extra-flags = [\"--auto\"]\n"
            + "}";
        Config c = ConfigFactory.parseString(raw);
        var cfg = ClineRuntimeConfig.fromConfig(c);

        assertThat(cfg.enabled()).isTrue();
        assertThat(cfg.executablePath()).isEqualTo("/opt/wyrdsekai/coding-cli/cline");
        assertThat(cfg.provider()).isEqualTo("anthropic");
        assertThat(cfg.yolo()).isTrue();
        assertThat(cfg.maxWallclock()).isEqualTo(Duration.ofMinutes(60));
        assertThat(cfg.extraFlags()).containsExactly("--auto");
    }

    @Test void config_underscore_keys_are_accepted_too() {
        var raw = ""
            + "wyrdsekai.coding.backends.cline {\n"
            + "  enabled = true\n"
            + "  executable_path = \"/path/cline\"\n"
            + "  provider = \"openai\"\n"
            + "  max_wallclock_min = 5\n"
            + "  extra_flags = [\"-q\"]\n"
            + "}";
        var cfg = ClineRuntimeConfig.fromConfig(ConfigFactory.parseString(raw));
        assertThat(cfg.executablePath()).isEqualTo("/path/cline");
        assertThat(cfg.provider()).isEqualTo("openai");
        assertThat(cfg.maxWallclock()).isEqualTo(Duration.ofMinutes(5));
        assertThat(cfg.extraFlags()).containsExactly("-q");
    }

    // ─── argv construction ─────────────────────────────────────────
    //
    // 2026-05-04 reconciliation: Cline CLI 2.18+ argv is `cline --json
    // "<task>"` (or `cline -y "<task>"` for yolo) — prompt is positional.
    // Pre-2026-05 the adapter used `cline task --message=... --no-interactive
    // --provider=...` flags (none upstream).

    @Test void argv_includes_json_flag_and_positional_prompt() {
        var cfg = new ClineRuntimeConfig(true, "cline", "anthropic",
            false, Duration.ofMinutes(30), List.of());
        var b = new ClineBackend(cfg, oauthResolver(), stub("", "", 0, false));
        var spec = TaskSpec.create("did:c", "code", "fix bug X");
        var args = b.buildArgs(spec);

        assertThat(args.get(0)).isEqualTo("cline");
        // Positional prompt is the description wrapped with the
        // ITEMS_AS_TOOLS_PREAMBLE — assert ordering + contents rather
        // than exact equality.
        assertThat(args).contains("--json");
        int jsonIdx = args.indexOf("--json");
        var prompt = args.get(jsonIdx + 1);
        assertThat(prompt).contains("fix bug X");
        assertThat(prompt).contains("ITEMS-AS-TOOLS OUTPUT CONTRACT");
        assertThat(prompt).contains("--- TASK ---");
        // No invented flags — none of these exist upstream.
        assertThat(args).doesNotContain("task", "--no-interactive");
        for (var a : args) {
            assertThat(a).doesNotStartWith("--provider=");
            assertThat(a).doesNotStartWith("--message=");
            assertThat(a).doesNotStartWith("--workspace=");
        }
    }

    @Test void argv_uses_yolo_flag_when_configured() {
        var cfg = new ClineRuntimeConfig(true, "cline", null,
            true /* yolo */, Duration.ofMinutes(30), List.of());
        var b = new ClineBackend(cfg, oauthResolver(), stub("", "", 0, false));
        var args = b.buildArgs(TaskSpec.create("did:c", "code", "do it"));
        assertThat(args).contains("-y");
        int yIdx = args.indexOf("-y");
        var prompt = args.get(yIdx + 1);
        assertThat(prompt).contains("do it");
        assertThat(prompt).contains("ITEMS-AS-TOOLS OUTPUT CONTRACT");
        assertThat(args).doesNotContain("--json");
    }

    @Test void argv_does_not_include_api_key_value_or_oauth_path() {
        var cfg = new ClineRuntimeConfig(true, "cline", null,
            false, Duration.ofMinutes(30), List.of());
        var b = new ClineBackend(cfg, name -> new AuthMode.ApiKey("super-secret"),
            stub("", "", 0, false));
        var args = b.buildArgs(TaskSpec.create("did:c", "code", "x"));
        var serialized = String.join(" ", args);
        assertThat(serialized).doesNotContain("super-secret");
        // Cline OAuth credential path is not publicly documented as of
        // May 2026 — but we still verify it doesn't leak via argv.
        assertThat(serialized).doesNotContain(".cline/auth.json");
    }

    // ─── env construction ──────────────────────────────────────────
    //
    // Per the 2026-05-04 reconciliation: Cline reads standard provider
    // env vars (ANTHROPIC_API_KEY / OPENAI_API_KEY / etc.) directly. The
    // pre-2026-05 CLINE_PROVIDER_KEY indirection was a wyrdsekai
    // invention and is no longer wired.

    @Test void env_anthropic_provider_lands_in_anthropic_api_key() {
        var cfg = new ClineRuntimeConfig(true, "cline", "anthropic",
            false, Duration.ofMinutes(30), List.of());
        var b = new ClineBackend(cfg, oauthResolver(), stub("", "", 0, false));
        var env = b.buildEnv(new AuthMode.ApiKey("sk-ant-xyz"));
        assertThat(env).containsEntry("ANTHROPIC_API_KEY", "sk-ant-xyz");
        assertThat(env).doesNotContainKey("CLINE_PROVIDER_KEY");
    }

    @Test void env_openai_provider_lands_in_openai_api_key() {
        var cfg = new ClineRuntimeConfig(true, "cline", "openai",
            false, Duration.ofMinutes(30), List.of());
        var b = new ClineBackend(cfg, oauthResolver(), stub("", "", 0, false));
        var env = b.buildEnv(new AuthMode.ApiKey("sk-oai-xyz"));
        assertThat(env).containsEntry("OPENAI_API_KEY", "sk-oai-xyz");
    }

    @Test void env_omits_key_when_oauth_session() {
        var b = new ClineBackend(ClineRuntimeConfig.defaults(),
            oauthResolver(), stub("", "", 0, false));
        var env = b.buildEnv(new AuthMode.OAuthSession());
        assertThat(env).isEmpty();
    }

    @Test void env_unknown_provider_falls_through_to_pre_staged_auth() {
        // No upstream env-var mapping → adapter discards the key and the
        // steward must pre-stage via `cline auth -p`.
        var cfg = new ClineRuntimeConfig(true, "cline", "exotic-cloud",
            false, Duration.ofMinutes(30), List.of());
        var b = new ClineBackend(cfg, oauthResolver(), stub("", "", 0, false));
        var env = b.buildEnv(new AuthMode.ApiKey("k-12345"));
        assertThat(env).isEmpty();
    }

    @Test void provider_key_env_var_lookup_is_case_insensitive() {
        assertThat(ClineBackend.providerKeyEnvVarFor("ANTHROPIC")).isEqualTo("ANTHROPIC_API_KEY");
        assertThat(ClineBackend.providerKeyEnvVarFor("OpenAI")).isEqualTo("OPENAI_API_KEY");
        assertThat(ClineBackend.providerKeyEnvVarFor("Google")).isEqualTo("GOOGLE_API_KEY");
        assertThat(ClineBackend.providerKeyEnvVarFor("local")).isNull();
        assertThat(ClineBackend.providerKeyEnvVarFor(null)).isNull();
    }

    // ─── Auth gate ─────────────────────────────────────────────────

    @Test void auth_missing_short_circuits_without_subprocess() throws Exception {
        var ranSubprocess = new boolean[]{false};
        ClineBackend.ProcessRunner neverRun = (args, env, t) -> {
            ranSubprocess[0] = true;
            throw new IllegalStateException("subprocess must not run on AuthMissing");
        };
        AuthResolver missing = name -> new AuthMode.AuthMissing(
            name, "wyrd coding login cline", "no auth");

        var b = new ClineBackend(enabledDefaults(), missing, neverRun);
        var result = b.submitTask(TaskSpec.create("did:c", "code", "x"))
            .get(5, TimeUnit.SECONDS);

        assertThat(ranSubprocess[0]).isFalse();
        assertThat(result.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(result.summary()).contains("LOGIN_REQUIRED");
        assertThat(result.summary()).contains("wyrd coding login cline");
    }

    // ─── submitTask happy path ─────────────────────────────────────

    @Test void submit_task_returns_succeeded_on_clean_run() throws Exception {
        var stdout = """
            {"event": "edit", "file": "src/foo.java"}
            {"event": "complete", "files": ["src/foo.java", "src/bar.java"]}
            """;
        var b = new ClineBackend(enabledDefaults(), oauthResolver(),
            stub(stdout, "", 0, false));
        var result = b.submitTask(TaskSpec.create("did:c", "code", "x"))
            .get(5, TimeUnit.SECONDS);

        assertThat(result.status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(result.backend()).isEqualTo("cline");
        var artifacts = b.artifactsFor(result.taskId().toString()).toList();
        var src = (SourceArtifact) artifacts.get(0);
        assertThat(src.files()).contains("src/foo.java", "src/bar.java");
    }

    // ─── submitTask negative paths ─────────────────────────────────

    @Test void submit_task_marks_failed_on_nonzero_exit() throws Exception {
        var b = new ClineBackend(enabledDefaults(), oauthResolver(),
            stub("", "grpc connection refused", 2, false));
        var result = b.submitTask(TaskSpec.create("did:c", "code", "x"))
            .get(5, TimeUnit.SECONDS);
        assertThat(result.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(result.summary()).contains("grpc connection refused");
    }

    @Test void submit_task_marks_timed_out_when_runner_reports_timeout() throws Exception {
        var b = new ClineBackend(enabledDefaults(), oauthResolver(),
            stub("", "", -1, true));
        var result = b.submitTask(TaskSpec.create("did:c", "code", "x"))
            .get(5, TimeUnit.SECONDS);
        assertThat(result.status()).isEqualTo(TaskStatus.TIMED_OUT);
    }

    @Test void submit_task_when_disabled_returns_failed_immediately() throws Exception {
        var disabled = new ClineRuntimeConfig(false, null, null, false, null, List.of());
        var b = new ClineBackend(disabled, oauthResolver(), stub("never", "", 0, false));
        var result = b.submitTask(TaskSpec.create("did:c", "code", "x"))
            .get(5, TimeUnit.SECONDS);
        assertThat(result.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(result.summary()).contains("disabled");
    }

    // ─── healthCheck ─────────────────────────────────────────────

    @Test void healthCheck_returns_true_when_version_probe_succeeds() throws Exception {
        var b = new ClineBackend(enabledDefaults(), oauthResolver(),
            stub("cline 2.0.0", "", 0, false));
        assertThat(b.healthCheck().get(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test void healthCheck_returns_false_when_disabled() throws Exception {
        var disabled = new ClineRuntimeConfig(false, null, null, false, null, List.of());
        var b = new ClineBackend(disabled, oauthResolver(), stub("cline 2", "", 0, false));
        assertThat(b.healthCheck().get(5, TimeUnit.SECONDS)).isFalse();
    }

    @Test void healthCheck_returns_false_on_subprocess_ioexception() throws Exception {
        ClineBackend.ProcessRunner throwing = (a, e, t) -> {
            throw new IOException("cline: not found");
        };
        var b = new ClineBackend(enabledDefaults(), oauthResolver(), throwing);
        assertThat(b.healthCheck().get(5, TimeUnit.SECONDS)).isFalse();
    }

    // ─── Helpers ──────────────────────────────────────────────────

    private static ClineRuntimeConfig enabledDefaults() {
        var d = ClineRuntimeConfig.defaults();
        return new ClineRuntimeConfig(true, d.executablePath(), d.provider(),
            d.yolo(), d.maxWallclock(), d.extraFlags());
    }

    private static AuthResolver oauthResolver() {
        return name -> new AuthMode.OAuthSession();
    }

    private static ClineBackend.ProcessRunner stub(
            String stdout, String stderr, int exitCode, boolean timedOut) {
        return (args, env, timeout) -> new ClineBackend.ProcessResult(
            exitCode, stdout, stderr, timedOut);
    }
}
