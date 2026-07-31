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
 * Phase 2d — unit tests for {@link ContinueBackend}.
 */
class ContinueBackendTest {

    @AfterEach
    void tearDown() {
        BackendRegistry.get().clear();
    }

    // ─── Sealed-family conformance ──────────────────────────────────

    @Test void sealed_interface_now_permits_continue() {
        var permitted = CodingTaskBackend.class.getPermittedSubclasses();
        assertThat(permitted).contains(ContinueBackend.class);
    }

    // ─── Basic contract ─────────────────────────────────────────────

    @Test void name_is_continue() {
        var b = new ContinueBackend(ContinueRuntimeConfig.defaults(), oauthResolver(), stub("", "", 0, false));
        assertThat(b.name()).isEqualTo("continue");
        assertThat(b.name()).isEqualTo(ContinueBackend.NAME);
    }

    @Test void tier_is_cloud_paid() {
        var b = new ContinueBackend(ContinueRuntimeConfig.defaults(), oauthResolver(), stub("", "", 0, false));
        assertThat(b.tier()).isEqualTo(BackendTier.CLOUD_PAID);
    }

    @Test void estimated_cu_is_capped_at_2000() {
        var b = new ContinueBackend(ContinueRuntimeConfig.defaults(), oauthResolver(), stub("", "", 0, false));
        for (var t : List.of("explore", "implement_feature", "refactor", "code")) {
            assertThat(b.estimatedCu(TaskSpec.create("did:c", t, "x")))
                .isLessThanOrEqualTo(2000L);
        }
    }

    // ─── Config loading ─────────────────────────────────────────────

    @Test void config_falls_back_to_defaults_when_block_missing() {
        var cfg = ContinueRuntimeConfig.fromConfig(ConfigFactory.empty());
        assertThat(cfg.enabled()).isFalse();
        assertThat(cfg.executablePath()).isEqualTo(ContinueRuntimeConfig.DEFAULT_EXECUTABLE);
        assertThat(cfg.agent()).isNull();
    }

    @Test void config_overrides_apply_via_typesafe_dash_case() {
        var raw = ""
            + "wyrdsekai.coding.backends.continue {\n"
            + "  enabled = true\n"
            + "  executable-path = \"/opt/wyrdsekai/coding-cli/cn\"\n"
            + "  agent = \"refactor-bot\"\n"
            + "  max-wallclock-min = 60\n"
            + "  extra-flags = [\"--auto\"]\n"
            + "}";
        Config c = ConfigFactory.parseString(raw);
        var cfg = ContinueRuntimeConfig.fromConfig(c);

        assertThat(cfg.enabled()).isTrue();
        assertThat(cfg.executablePath()).isEqualTo("/opt/wyrdsekai/coding-cli/cn");
        assertThat(cfg.agent()).isEqualTo("refactor-bot");
        assertThat(cfg.maxWallclock()).isEqualTo(Duration.ofMinutes(60));
        assertThat(cfg.extraFlags()).containsExactly("--auto");
    }

    @Test void config_underscore_keys_are_accepted_too() {
        var raw = ""
            + "wyrdsekai.coding.backends.continue {\n"
            + "  enabled = true\n"
            + "  executable_path = \"/path/cn\"\n"
            + "  agent = \"explore-bot\"\n"
            + "  max_wallclock_min = 5\n"
            + "  extra_flags = [\"-q\"]\n"
            + "}";
        var cfg = ContinueRuntimeConfig.fromConfig(ConfigFactory.parseString(raw));
        assertThat(cfg.executablePath()).isEqualTo("/path/cn");
        assertThat(cfg.agent()).isEqualTo("explore-bot");
        assertThat(cfg.maxWallclock()).isEqualTo(Duration.ofMinutes(5));
        assertThat(cfg.extraFlags()).containsExactly("-q");
    }

    // ─── argv construction ─────────────────────────────────────────
    //
    // 2026-05-04 reconciliation: Continue CLI v1.5+ argv is
    // `cn -p "<prompt>" [--agent <name>]` — prompt is positional after
    // -p. Pre-2026-05 the adapter used `cn run --message=... --headless
    // --workspace=...` (none upstream).

    @Test void argv_includes_print_flag_and_positional_prompt() {
        var cfg = new ContinueRuntimeConfig(true, "cn", "agent-x",
            Duration.ofMinutes(30), List.of());
        var b = new ContinueBackend(cfg, oauthResolver(), stub("", "", 0, false));
        var spec = TaskSpec.create("did:c", "code", "fix bug X");
        var args = b.buildArgs(spec);

        assertThat(args.get(0)).isEqualTo("cn");
        // -p positional prompt is the description wrapped with the
        // ITEMS_AS_TOOLS_PREAMBLE — assert ordering + contents.
        assertThat(args).contains("-p");
        int pIdx = args.indexOf("-p");
        var prompt = args.get(pIdx + 1);
        assertThat(prompt).contains("fix bug X");
        assertThat(prompt).contains("ITEMS-AS-TOOLS OUTPUT CONTRACT");
        assertThat(prompt).contains("--- TASK ---");
        assertThat(args).containsSequence("--agent", "agent-x");
        // No invented flags — none of these exist upstream.
        assertThat(args).doesNotContain("run", "--headless");
        for (var a : args) {
            assertThat(a).doesNotStartWith("--message=");
            assertThat(a).doesNotStartWith("--workspace");
        }
    }

    @Test void argv_omits_agent_flag_when_unset() {
        var cfg = new ContinueRuntimeConfig(true, "cn", null,
            Duration.ofMinutes(30), List.of());
        var b = new ContinueBackend(cfg, oauthResolver(), stub("", "", 0, false));
        var args = b.buildArgs(TaskSpec.create("did:c", "code", "x"));
        assertThat(args).doesNotContain("--agent");
    }

    @Test void argv_does_not_include_api_key_value_or_oauth_path() {
        var cfg = new ContinueRuntimeConfig(true, "cn", null,
            Duration.ofMinutes(30), List.of());
        var b = new ContinueBackend(cfg, name -> new AuthMode.ApiKey("super-secret"),
            stub("", "", 0, false));
        var args = b.buildArgs(TaskSpec.create("did:c", "code", "x"));
        var serialized = String.join(" ", args);
        assertThat(serialized).doesNotContain("super-secret");
        assertThat(serialized).doesNotContain(".continue/auth.json");
    }

    // ─── env construction ──────────────────────────────────────────

    @Test void env_includes_api_key_when_apikey_resolved() {
        var b = new ContinueBackend(ContinueRuntimeConfig.defaults(),
            oauthResolver(), stub("", "", 0, false));
        var env = b.buildEnv(new AuthMode.ApiKey("k-12345"));
        assertThat(env).containsEntry("CONTINUE_API_KEY", "k-12345");
    }

    @Test void env_omits_key_when_oauth_session() {
        var b = new ContinueBackend(ContinueRuntimeConfig.defaults(),
            oauthResolver(), stub("", "", 0, false));
        var env = b.buildEnv(new AuthMode.OAuthSession());
        assertThat(env).doesNotContainKey("CONTINUE_API_KEY");
    }

    // ─── Auth gate ─────────────────────────────────────────────────

    @Test void auth_missing_short_circuits_without_subprocess() throws Exception {
        var ranSubprocess = new boolean[]{false};
        ContinueBackend.ProcessRunner neverRun = (args, env, t) -> {
            ranSubprocess[0] = true;
            throw new IllegalStateException("subprocess must not run on AuthMissing");
        };
        AuthResolver missing = name -> new AuthMode.AuthMissing(
            name, "wyrd coding login continue", "no auth");

        var b = new ContinueBackend(enabledDefaults(), missing, neverRun);
        var result = b.submitTask(TaskSpec.create("did:c", "code", "x"))
            .get(5, TimeUnit.SECONDS);

        assertThat(ranSubprocess[0]).isFalse();
        assertThat(result.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(result.summary()).contains("LOGIN_REQUIRED");
        assertThat(result.summary()).contains("wyrd coding login continue");
    }

    // ─── submitTask happy path ─────────────────────────────────────

    @Test void submit_task_returns_succeeded_on_clean_run() throws Exception {
        var stdout = """
            {"event": "edit", "file": "src/foo.java"}
            {"event": "complete", "files": ["src/foo.java", "src/bar.java"]}
            """;
        var b = new ContinueBackend(enabledDefaults(), oauthResolver(),
            stub(stdout, "", 0, false));
        var result = b.submitTask(TaskSpec.create("did:c", "code", "x"))
            .get(5, TimeUnit.SECONDS);

        assertThat(result.status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(result.backend()).isEqualTo("continue");
        var artifacts = b.artifactsFor(result.taskId().toString()).toList();
        var src = (SourceArtifact) artifacts.get(0);
        assertThat(src.files()).contains("src/foo.java", "src/bar.java");
    }

    // ─── submitTask negative paths ─────────────────────────────────

    @Test void submit_task_marks_failed_on_nonzero_exit() throws Exception {
        var b = new ContinueBackend(enabledDefaults(), oauthResolver(),
            stub("", "hub auth lapsed", 2, false));
        var result = b.submitTask(TaskSpec.create("did:c", "code", "x"))
            .get(5, TimeUnit.SECONDS);
        assertThat(result.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(result.summary()).contains("hub auth lapsed");
    }

    @Test void submit_task_marks_timed_out_when_runner_reports_timeout() throws Exception {
        var b = new ContinueBackend(enabledDefaults(), oauthResolver(),
            stub("", "", -1, true));
        var result = b.submitTask(TaskSpec.create("did:c", "code", "x"))
            .get(5, TimeUnit.SECONDS);
        assertThat(result.status()).isEqualTo(TaskStatus.TIMED_OUT);
    }

    @Test void submit_task_when_disabled_returns_failed_immediately() throws Exception {
        var disabled = new ContinueRuntimeConfig(false, null, null, null, List.of());
        var b = new ContinueBackend(disabled, oauthResolver(), stub("never", "", 0, false));
        var result = b.submitTask(TaskSpec.create("did:c", "code", "x"))
            .get(5, TimeUnit.SECONDS);
        assertThat(result.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(result.summary()).contains("disabled");
    }

    // ─── healthCheck ─────────────────────────────────────────────

    @Test void healthCheck_returns_true_when_version_probe_succeeds() throws Exception {
        var b = new ContinueBackend(enabledDefaults(), oauthResolver(),
            stub("cn 1.0.0", "", 0, false));
        assertThat(b.healthCheck().get(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test void healthCheck_returns_false_when_disabled() throws Exception {
        var disabled = new ContinueRuntimeConfig(false, null, null, null, List.of());
        var b = new ContinueBackend(disabled, oauthResolver(), stub("cn 1", "", 0, false));
        assertThat(b.healthCheck().get(5, TimeUnit.SECONDS)).isFalse();
    }

    @Test void healthCheck_returns_false_on_subprocess_ioexception() throws Exception {
        ContinueBackend.ProcessRunner throwing = (a, e, t) -> {
            throw new IOException("cn: not found");
        };
        var b = new ContinueBackend(enabledDefaults(), oauthResolver(), throwing);
        assertThat(b.healthCheck().get(5, TimeUnit.SECONDS)).isFalse();
    }

    // ─── Helpers ──────────────────────────────────────────────────

    private static ContinueRuntimeConfig enabledDefaults() {
        var d = ContinueRuntimeConfig.defaults();
        return new ContinueRuntimeConfig(true, d.executablePath(), d.agent(),
            d.maxWallclock(), d.extraFlags());
    }

    private static AuthResolver oauthResolver() {
        return name -> new AuthMode.OAuthSession();
    }

    private static ContinueBackend.ProcessRunner stub(
            String stdout, String stderr, int exitCode, boolean timedOut) {
        return (args, env, timeout) -> new ContinueBackend.ProcessResult(
            exitCode, stdout, stderr, timedOut);
    }
}
