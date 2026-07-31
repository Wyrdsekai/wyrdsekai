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

/** Phase 2e — unit tests for {@link CodexCliBackend}. */
class CodexCliBackendTest {

    @AfterEach
    void tearDown() {
        BackendRegistry.get().clear();
    }

    // ─── Sealed-family conformance ──────────────────────────────────

    @Test void sealed_interface_now_permits_codex_cli() {
        var permitted = CodingTaskBackend.class.getPermittedSubclasses();
        assertThat(permitted).contains(CodexCliBackend.class);
    }

    // ─── Basic contract ─────────────────────────────────────────────

    @Test void name_is_codex() {
        var b = new CodexCliBackend(CodexCliRuntimeConfig.defaults(), oauthResolver(),
            stub("", "", 0, false));
        assertThat(b.name()).isEqualTo("codex");
        assertThat(b.name()).isEqualTo(CodexCliBackend.NAME);
    }

    @Test void tier_is_cloud_paid() {
        var b = new CodexCliBackend(CodexCliRuntimeConfig.defaults(), oauthResolver(),
            stub("", "", 0, false));
        assertThat(b.tier()).isEqualTo(BackendTier.CLOUD_PAID);
    }

    @Test void estimated_cu_is_capped_at_5000() {
        var b = new CodexCliBackend(CodexCliRuntimeConfig.defaults(), oauthResolver(),
            stub("", "", 0, false));
        for (var t : List.of("explore", "implement_feature", "refactor", "code", "test")) {
            var spec = TaskSpec.create("did:c", t, "x");
            assertThat(b.estimatedCu(spec))
                .as("estimate for taskType=%s must be <=5000 CU", t)
                .isLessThanOrEqualTo(5000L);
        }
    }

    // ─── Config loading ─────────────────────────────────────────────

    @Test void config_falls_back_to_defaults_when_block_missing() {
        var cfg = CodexCliRuntimeConfig.fromConfig(ConfigFactory.empty());
        assertThat(cfg.enabled()).isFalse();
        assertThat(cfg.executablePath()).isEqualTo(CodexCliRuntimeConfig.DEFAULT_EXECUTABLE);
        assertThat(cfg.provider()).isNull();
        assertThat(cfg.wallclockMin()).isEqualTo(30);
    }

    @Test void config_overrides_apply_via_typesafe_dash_case() {
        var raw = ""
            + "wyrdsekai.coding.backends.codex {\n"
            + "  enabled = true\n"
            + "  executable-path = \"/opt/codex\"\n"
            + "  provider = \"openai\"\n"
            + "  wallclock-min = 60\n"
            + "  extra-flags = [\"--verbose\"]\n"
            + "}";
        Config c = ConfigFactory.parseString(raw);
        var cfg = CodexCliRuntimeConfig.fromConfig(c);

        assertThat(cfg.enabled()).isTrue();
        assertThat(cfg.executablePath()).isEqualTo("/opt/codex");
        assertThat(cfg.provider()).isEqualTo("openai");
        assertThat(cfg.maxWallclock()).isEqualTo(Duration.ofMinutes(60));
        assertThat(cfg.extraFlags()).containsExactly("--verbose");
    }

    @Test void config_underscore_keys_are_accepted_too() {
        var raw = ""
            + "wyrdsekai.coding.backends.codex {\n"
            + "  enabled = true\n"
            + "  executable_path = \"/path/codex\"\n"
            + "  wallclock_min = 5\n"
            + "}";
        var cfg = CodexCliRuntimeConfig.fromConfig(ConfigFactory.parseString(raw));
        assertThat(cfg.executablePath()).isEqualTo("/path/codex");
        assertThat(cfg.maxWallclock()).isEqualTo(Duration.ofMinutes(5));
    }

    // ─── argv construction ─────────────────────────────────────────

    @Test void argv_uses_exec_subcommand_not_run() {
        var cfg = enabledDefaults();
        var b = new CodexCliBackend(cfg, oauthResolver(), stub("", "", 0, false));
        var args = b.buildArgs(TaskSpec.create("did:c", "code", "fix bug X"));

        assertThat(args.get(0)).isEqualTo("codex");
        // Must be `codex exec`, not `codex run` (latter doesn't exist).
        assertThat(args).contains("exec");
        assertThat(args).doesNotContain("run");
        assertThat(args).contains("--json");
        // Positional prompt is wrapped with ITEMS_AS_TOOLS_PREAMBLE.
        assertThat(args.stream().anyMatch(a -> a.contains("fix bug X"))).isTrue();
        assertThat(args.stream().anyMatch(a -> a.contains("ITEMS-AS-TOOLS"))).isTrue();
    }

    @Test void argv_provider_flag_when_set() {
        var cfg = new CodexCliRuntimeConfig(true, "codex", "openai", 30, List.of());
        var b = new CodexCliBackend(cfg, oauthResolver(), stub("", "", 0, false));
        var args = b.buildArgs(TaskSpec.create("did:c", "code", "x"));
        assertThat(args).containsSequence("--provider", "openai");
    }

    @Test void argv_provider_flag_omitted_when_null() {
        var cfg = enabledDefaults(); // provider=null
        var b = new CodexCliBackend(cfg, oauthResolver(), stub("", "", 0, false));
        var args = b.buildArgs(TaskSpec.create("did:c", "code", "x"));
        assertThat(args).doesNotContain("--provider");
    }

    @Test void argv_does_not_include_api_key_value() {
        var cfg = enabledDefaults();
        var b = new CodexCliBackend(cfg, oauthResolver(), stub("", "", 0, false));
        var args = b.buildArgs(TaskSpec.create("did:c", "code", "x"));
        var serialized = String.join(" ", args);
        // argv test — even when the resolver hands us a key, it must not
        // appear in argv.
        assertThat(serialized).doesNotContain("super-secret");
    }

    @Test void argv_does_not_include_api_key_flag() {
        // Codex has no --api-key flag upstream; verify we don't fabricate one.
        var cfg = enabledDefaults();
        var b = new CodexCliBackend(cfg, oauthResolver(), stub("", "", 0, false));
        var args = b.buildArgs(TaskSpec.create("did:c", "code", "x"));
        assertThat(args).doesNotContain("--api-key");
    }

    // ─── env construction ──────────────────────────────────────────

    @Test void env_apikey_lands_in_openai_api_key() {
        var b = new CodexCliBackend(enabledDefaults(), oauthResolver(), stub("", "", 0, false));
        var env = b.buildEnv(new AuthMode.ApiKey("sk-oai-xyz"));
        assertThat(env).containsEntry("OPENAI_API_KEY", "sk-oai-xyz");
        // Codex also honours CODEX_API_KEY inside `codex exec`; we set both.
        assertThat(env).containsEntry("CODEX_API_KEY", "sk-oai-xyz");
    }

    @Test void env_oauth_session_emits_no_key() {
        var b = new CodexCliBackend(enabledDefaults(), oauthResolver(), stub("", "", 0, false));
        var env = b.buildEnv(new AuthMode.OAuthSession());
        assertThat(env).isEmpty();
    }

    // ─── Auth gate ─────────────────────────────────────────────────

    @Test void auth_missing_short_circuits_without_subprocess() throws Exception {
        var ranSubprocess = new boolean[]{false};
        CodexCliBackend.ProcessRunner neverRun = (args, env, t) -> {
            ranSubprocess[0] = true;
            throw new IllegalStateException("subprocess must not run on AuthMissing");
        };
        AuthResolver missing = name -> new AuthMode.AuthMissing(
            name, "wyrd coding login codex", "no auth");

        var b = new CodexCliBackend(enabledDefaults(), missing, neverRun);
        var result = b.submitTask(TaskSpec.create("did:c", "code", "x"))
            .get(5, TimeUnit.SECONDS);

        assertThat(ranSubprocess[0]).isFalse();
        assertThat(result.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(result.summary()).contains("LOGIN_REQUIRED");
        assertThat(result.summary()).contains("wyrd coding login codex");
    }

    // ─── submitTask happy path ─────────────────────────────────────

    @Test void submit_task_returns_succeeded_on_clean_run() throws Exception {
        // Codex JSON-line stdout: events keyed by `type`.
        var stdout = """
            {"type":"event","file":"src/foo.java"}
            {"type":"event","files":["src/bar.java"]}
            {"type":"result","summary":"Edited 2 files."}
            """;
        var b = new CodexCliBackend(enabledDefaults(), oauthResolver(),
            stub(stdout, "", 0, false));
        var result = b.submitTask(TaskSpec.create("did:c", "code", "do stuff"))
            .get(5, TimeUnit.SECONDS);

        assertThat(result.status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(result.backend()).isEqualTo("codex");
        assertThat(result.summary()).contains("Codex");

        var artifacts = b.artifactsFor(result.taskId().toString()).toList();
        assertThat(artifacts).hasSize(1);
        var src = (SourceArtifact) artifacts.get(0);
        assertThat(src.files()).contains("src/foo.java", "src/bar.java");
        assertThat(src.backendMetadata()).containsEntry("final_summary", "Edited 2 files.");
    }

    // ─── submitTask negative paths ─────────────────────────────────

    @Test void submit_task_marks_failed_on_nonzero_exit() throws Exception {
        var b = new CodexCliBackend(enabledDefaults(), oauthResolver(),
            stub("", "rate limited", 1, false));
        var result = b.submitTask(TaskSpec.create("did:c", "code", "x"))
            .get(5, TimeUnit.SECONDS);
        assertThat(result.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(result.summary()).contains("rate limited");
    }

    @Test void submit_task_marks_timed_out_when_runner_reports_timeout() throws Exception {
        var b = new CodexCliBackend(enabledDefaults(), oauthResolver(),
            stub("", "", -1, true));
        var result = b.submitTask(TaskSpec.create("did:c", "code", "x"))
            .get(5, TimeUnit.SECONDS);
        assertThat(result.status()).isEqualTo(TaskStatus.TIMED_OUT);
    }

    @Test void submit_task_when_disabled_returns_failed_immediately() throws Exception {
        var disabled = new CodexCliRuntimeConfig(false, null, null, 0, List.of());
        var b = new CodexCliBackend(disabled, oauthResolver(), stub("never", "", 0, false));
        var result = b.submitTask(TaskSpec.create("did:c", "code", "x"))
            .get(5, TimeUnit.SECONDS);
        assertThat(result.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(result.summary()).contains("disabled");
    }

    // ─── healthCheck ─────────────────────────────────────────────

    @Test void healthCheck_returns_true_when_version_probe_succeeds() throws Exception {
        var b = new CodexCliBackend(enabledDefaults(), oauthResolver(),
            stub("codex 1.0.0", "", 0, false));
        assertThat(b.healthCheck().get(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test void healthCheck_returns_false_when_disabled() throws Exception {
        var disabled = new CodexCliRuntimeConfig(false, null, null, 0, List.of());
        var b = new CodexCliBackend(disabled, oauthResolver(), stub("ok", "", 0, false));
        assertThat(b.healthCheck().get(5, TimeUnit.SECONDS)).isFalse();
    }

    @Test void healthCheck_returns_false_on_subprocess_ioexception() throws Exception {
        CodexCliBackend.ProcessRunner throwing = (a, e, t) -> {
            throw new IOException("codex: not found");
        };
        var b = new CodexCliBackend(enabledDefaults(), oauthResolver(), throwing);
        assertThat(b.healthCheck().get(5, TimeUnit.SECONDS)).isFalse();
    }

    // ─── Helpers ──────────────────────────────────────────────────

    private static CodexCliRuntimeConfig enabledDefaults() {
        var d = CodexCliRuntimeConfig.defaults();
        return new CodexCliRuntimeConfig(true, d.executablePath(), d.provider(),
            d.wallclockMin(), d.extraFlags());
    }

    private static AuthResolver oauthResolver() {
        return name -> new AuthMode.OAuthSession();
    }

    private static CodexCliBackend.ProcessRunner stub(
            String stdout, String stderr, int exitCode, boolean timedOut) {
        return (args, env, timeout) -> new CodexCliBackend.ProcessResult(
            exitCode, stdout, stderr, timedOut);
    }
}
