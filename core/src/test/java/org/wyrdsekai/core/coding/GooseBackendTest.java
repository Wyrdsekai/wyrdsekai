package org.wyrdsekai.core.coding;

import org.wyrdsekai.core.inference.LocalInferenceEndpoint;
import org.junit.jupiter.api.BeforeEach;
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
 * Phase 2d — unit tests for {@link GooseBackend}.
 *
 * <p>Tests are subprocess-free: a stub {@link GooseBackend.ProcessRunner}
 * supplies canned stdout/stderr so the parser + argv-building paths can be
 * verified without the {@code goose} binary on PATH.</p>
 *
 * <p><b>2026-05-05 reconciliation</b>: argv shape verified against
 * {@code aaif-goose/goose@main} v1.33.1
 * ({@code crates/goose-cli/src/cli.rs}). The pre-2026-05 tests asserted
 * a fabricated argv ({@code --task=}, {@code --format=json},
 * {@code --workspace=}) — none of those flags exist upstream.</p>
 */
class GooseBackendTest {
    @BeforeEach
    void pinNothingLive() {
        // These tests assert the compiled-in FALLBACK endpoint. On a developer box with
        // a live local model the resolver would (correctly) find it instead.
        LocalInferenceEndpoint.pinNothingLiveForTests(true);
    }

    @AfterEach
    void unpinNothingLive() {
        LocalInferenceEndpoint.pinNothingLiveForTests(false);
    }


    @AfterEach
    void tearDown() {
        BackendRegistry.get().clear();
    }

    // ─── Sealed-family conformance ──────────────────────────────────

    @Test void sealed_interface_now_permits_goose() {
        var permitted = CodingTaskBackend.class.getPermittedSubclasses();
        assertThat(permitted).contains(GooseBackend.class);
    }

    // ─── Basic contract ─────────────────────────────────────────────

    @Test void name_is_goose() {
        var b = new GooseBackend(GooseRuntimeConfig.defaults(), oauthResolver(), stub("", "", 0, false));
        assertThat(b.name()).isEqualTo("goose");
        assertThat(b.name()).isEqualTo(GooseBackend.NAME);
    }

    @Test void tier_is_cloud_paid() {
        var b = new GooseBackend(GooseRuntimeConfig.defaults(), oauthResolver(), stub("", "", 0, false));
        assertThat(b.tier()).isEqualTo(BackendTier.CLOUD_PAID);
    }

    @Test void estimated_cu_is_capped_at_2000() {
        var b = new GooseBackend(GooseRuntimeConfig.defaults(), oauthResolver(), stub("", "", 0, false));
        for (var t : List.of("explore", "implement_feature", "refactor", "code", "test")) {
            var spec = TaskSpec.create("did:c", t, "x");
            assertThat(b.estimatedCu(spec))
                .as("estimate for taskType=%s must be <=2000 CU", t)
                .isLessThanOrEqualTo(2000L);
        }
    }

    @Test void estimated_cu_local_provider_lower_than_cloud() {
        // Real Goose providers: ollama is local, anthropic is cloud-billed.
        var localCfg = new GooseRuntimeConfig(
            true, "goose", "ollama", "qwen3", "http://localhost:11434",
            Duration.ofMinutes(30), List.of());
        var cloudCfg = new GooseRuntimeConfig(
            true, "goose", "anthropic", "claude-sonnet-4", null,
            Duration.ofMinutes(30), List.of());
        var local = new GooseBackend(localCfg, oauthResolver(), stub("", "", 0, false));
        var cloud = new GooseBackend(cloudCfg, oauthResolver(), stub("", "", 0, false));
        var spec = TaskSpec.create("did:c", "code", "x");
        assertThat(local.estimatedCu(spec)).isLessThan(cloud.estimatedCu(spec));
    }

    // ─── Config loading ─────────────────────────────────────────────

    @Test void config_falls_back_to_defaults_when_block_missing() {
        var cfg = GooseRuntimeConfig.fromConfig(ConfigFactory.empty());
        assertThat(cfg.enabled()).isFalse();
        // Resolution now searches the machine, so the default is an absolute
        // path wherever goose is actually installed and the bare name only when
        // it is not. Assert the intent -- we fell back to the goose executable
        // rather than some configured other -- not the machine's answer.
        assertThat(cfg.executablePath()).endsWith(GooseRuntimeConfig.DEFAULT_EXECUTABLE);
        assertThat(cfg.provider()).isEqualTo(GooseRuntimeConfig.DEFAULT_PROVIDER);
        assertThat(cfg.model()).isEqualTo(GooseRuntimeConfig.DEFAULT_MODEL);
        assertThat(cfg.baseUrl()).isEqualTo(GooseRuntimeConfig.DEFAULT_BASE_URL);
        assertThat(cfg.maxWallclock()).isEqualTo(GooseRuntimeConfig.DEFAULT_MAX_WALLCLOCK);
    }

    @Test void config_overrides_apply_via_typesafe_dash_case() {
        var raw = ""
            + "wyrdsekai.coding.backends.goose {\n"
            + "  enabled = true\n"
            + "  executable-path = \"/opt/wyrdsekai/coding-cli/goose\"\n"
            + "  provider = \"anthropic\"\n"
            + "  model = \"claude-sonnet-4\"\n"
            + "  base-url = \"http://example/v1\"\n"
            + "  max-wallclock-min = 60\n"
            + "  extra-flags = [\"--max-turns\", \"30\"]\n"
            + "}";
        Config c = ConfigFactory.parseString(raw);
        var cfg = GooseRuntimeConfig.fromConfig(c);

        assertThat(cfg.enabled()).isTrue();
        assertThat(cfg.executablePath()).isEqualTo("/opt/wyrdsekai/coding-cli/goose");
        assertThat(cfg.provider()).isEqualTo("anthropic");
        assertThat(cfg.model()).isEqualTo("claude-sonnet-4");
        assertThat(cfg.baseUrl()).isEqualTo("http://example/v1");
        assertThat(cfg.maxWallclock()).isEqualTo(Duration.ofMinutes(60));
        assertThat(cfg.extraFlags()).containsExactly("--max-turns", "30");
    }

    @Test void config_underscore_keys_are_accepted_too() {
        var raw = ""
            + "wyrdsekai.coding.backends.goose {\n"
            + "  enabled = true\n"
            + "  executable_path = \"/path/goose\"\n"
            + "  provider = \"openai\"\n"
            + "  model = \"gpt-4o\"\n"
            + "  base_url = \"http://other/v1\"\n"
            + "  max_wallclock_min = 5\n"
            + "  extra_flags = [\"-q\"]\n"
            + "}";
        var cfg = GooseRuntimeConfig.fromConfig(ConfigFactory.parseString(raw));
        assertThat(cfg.executablePath()).isEqualTo("/path/goose");
        assertThat(cfg.provider()).isEqualTo("openai");
        assertThat(cfg.model()).isEqualTo("gpt-4o");
        assertThat(cfg.baseUrl()).isEqualTo("http://other/v1");
        assertThat(cfg.maxWallclock()).isEqualTo(Duration.ofMinutes(5));
        assertThat(cfg.extraFlags()).containsExactly("-q");
    }

    // ─── argv construction ─────────────────────────────────────────

    @Test void argv_uses_text_flag_and_output_format_json() {
        // Wire shape (verified against aaif-goose/goose v1.33.1
        // crates/goose-cli/src/cli.rs):
        //   goose run --text <DESC> --output-format json --no-session -q [...]
        var cfg = new GooseRuntimeConfig(true, "goose", "anthropic",
            "claude-sonnet-4", null, Duration.ofMinutes(30), List.of());
        var b = new GooseBackend(cfg, oauthResolver(), stub("", "", 0, false));
        var spec = TaskSpec.create("did:c", "code", "fix bug X");
        var args = b.buildArgs(spec);

        // The constructor deliberately re-resolves the bare default, so argv[0]
        // is wherever goose actually lives on this machine. Asserting the literal
        // "goose" only held while resolution FAILED to find anything -- a green
        // that meant the opposite of what it looked like.
        assertThat(args.get(0)).endsWith("goose");
        assertThat(args.get(1)).isEqualTo("run");

        // --text <value> (value as separate argv element, NOT --text=value).
        // Value is the user's description prefixed with the
        // ITEMS_AS_TOOLS_PREAMBLE — assert both the contract preamble
        // marker AND the description survive the wrap.
        assertThat(args).contains("--text");
        int textIdx = args.indexOf("--text");
        var textValue = args.get(textIdx + 1);
        assertThat(textValue).contains("fix bug X");
        assertThat(textValue).contains("ITEMS-AS-TOOLS OUTPUT CONTRACT");
        assertThat(textValue).contains("--- TASK ---");

        // --output-format json (NOT --format=json — that flag doesn't exist).
        assertThat(args).contains("--output-format");
        int fmtIdx = args.indexOf("--output-format");
        assertThat(args.get(fmtIdx + 1)).isEqualTo("json");

        // Mandatory headless flags.
        assertThat(args).contains("--no-session");
        assertThat(args).contains("-q");

        // Provider override (redundant with GOOSE_PROVIDER env, kept explicit).
        assertThat(args).contains("--provider");
        int provIdx = args.indexOf("--provider");
        assertThat(args.get(provIdx + 1)).isEqualTo("anthropic");

        // Pre-2026-05 fabricated flags must not appear.
        assertThat(args).doesNotContain("--format=json");
        assertThat(args.stream().anyMatch(a -> a.startsWith("--task")))
            .as("--task flag does not exist in upstream Goose")
            .isFalse();
        assertThat(args.stream().anyMatch(a -> a.startsWith("--workspace")))
            .as("--workspace flag does not exist in upstream Goose")
            .isFalse();
    }

    @Test void argv_shell_exec_taskType_skips_items_as_tools_preamble() {
        // Recipe BACKEND steps with tools=[shell] tag taskType=shell-exec
        // (CodingBackendDispatcher); the adapter must skip the
        // ITEMS_AS_TOOLS_PREAMBLE wrap because the recipe author named
        // shell execution as the contract, not scripted-item generation.
        // #1009 closure; B2 live-verify finding.
        var cfg = new GooseRuntimeConfig(true, "goose", "openai", "wyrd",
            "http://localhost:8200", Duration.ofMinutes(30), List.of());
        var b = new GooseBackend(cfg, oauthResolver(), stub("", "", 0, false));
        var spec = TaskSpec.create("did:c", "shell-exec",
            "Use the shell tool. Run this exact command: echo hello. Then stop.");
        var args = b.buildArgs(spec);

        int textIdx = args.indexOf("--text");
        var textValue = args.get(textIdx + 1);
        // The shell instruction survives intact:
        assertThat(textValue).contains("Run this exact command: echo hello");
        // And NEITHER the items-as-tools preamble marker nor the wrap
        // separator appears — the prompt is raw shell intent.
        assertThat(textValue).doesNotContain("ITEMS-AS-TOOLS OUTPUT CONTRACT");
        assertThat(textValue).doesNotContain("--- TASK ---");
    }

    @Test void argv_legacy_local_provider_is_coerced_to_openai() {
        // Pre-2026-05 the adapter accepted provider="local" — Goose has no
        // such provider. The adapter now coerces "local" → "openai" and
        // pairs it with OPENAI_HOST in the env (see env_local_*).
        var cfg = new GooseRuntimeConfig(true, "goose", "local",
            "wyrd", "http://localhost:8200", Duration.ofMinutes(30), List.of());
        var b = new GooseBackend(cfg, oauthResolver(), stub("", "", 0, false));
        var args = b.buildArgs(TaskSpec.create("did:c", "code", "x"));
        int provIdx = args.indexOf("--provider");
        assertThat(args.get(provIdx + 1)).isEqualTo("openai");
    }

    @Test void argv_does_not_include_workspace_flag_workdir_used_instead() {
        // Goose has no --workspace flag. resolveWorkdir() returns the spec
        // workspace as the subprocess CWD instead.
        var cfg = new GooseRuntimeConfig(true, "goose", "openai", "wyrd",
            "http://localhost:8200", Duration.ofMinutes(30), List.of());
        var b = new GooseBackend(cfg, oauthResolver(), stub("", "", 0, false));
        var spec = new TaskSpec(UUID.randomUUID(), null, "code", "msg",
            "/tmp/repo", List.of(), 0L, null);
        var args = b.buildArgs(spec);
        assertThat(args).noneMatch(a -> a.contains("/tmp/repo"));
        // Workdir resolves out of the spec hint.
        var workdir = GooseBackend.resolveWorkdir(spec);
        assertThat(workdir).isNotNull();
        assertThat(workdir.getPath()).isEqualTo("/tmp/repo");
    }

    @Test void argv_does_not_include_api_key_value() {
        // The api-key must travel via env, never argv (would leak in
        // process-listing / log scrapes).
        var cfg = new GooseRuntimeConfig(true, "goose", "anthropic",
            "claude-sonnet-4", null, Duration.ofMinutes(30), List.of());
        var b = new GooseBackend(cfg, key -> new AuthMode.ApiKey("super-secret-must-not-appear"),
            stub("", "", 0, false));
        var args = b.buildArgs(TaskSpec.create("did:c", "code", "x"));
        var serialized = String.join(" ", args);
        assertThat(serialized).doesNotContain("super-secret-must-not-appear");
    }

    // ─── env construction ──────────────────────────────────────────
    //
    // Per the 2026-05-04 reconciliation: Goose reads the upstream-conventional
    // provider env var directly (ANTHROPIC_API_KEY / OPENAI_API_KEY /
    // GOOGLE_API_KEY) keyed off `coding.backends.goose.provider`. The
    // pre-2026-05 GOOSE_PROVIDER_KEY indirection was a wyrdsekai invention
    // and is no longer wired.
    //
    // Per the 2026-05-05 reconciliation: GOOSE_PROVIDER + GOOSE_MODEL are
    // also exported to be belt-and-suspenders explicit alongside the
    // --provider / --model argv flags.

    @Test void env_anthropic_provider_lands_in_anthropic_api_key() {
        var cfg = new GooseRuntimeConfig(true, "goose", "anthropic",
            "claude-sonnet-4", null, Duration.ofMinutes(30), List.of());
        var b = new GooseBackend(cfg, oauthResolver(), stub("", "", 0, false));
        var env = b.buildEnv(new AuthMode.ApiKey("sk-ant-xyz"));
        assertThat(env).containsEntry("ANTHROPIC_API_KEY", "sk-ant-xyz");
        assertThat(env).containsEntry("GOOSE_PROVIDER", "anthropic");
        assertThat(env).containsEntry("GOOSE_MODEL", "claude-sonnet-4");
        assertThat(env).doesNotContainKey("GOOSE_PROVIDER_KEY");
        assertThat(env).doesNotContainKey("OPENAI_HOST");
    }

    @Test void env_openai_provider_with_local_baseurl_sets_openai_host() {
        var cfg = new GooseRuntimeConfig(true, "goose", "openai",
            "wyrd-9b", "http://localhost:8200",
            Duration.ofMinutes(30), List.of());
        var b = new GooseBackend(cfg, oauthResolver(), stub("", "", 0, false));
        // No ApiKey resolved — but local llama-server still needs the env
        // var set. The adapter plants a sentinel value.
        var env = b.buildEnv(new AuthMode.OAuthSession());
        assertThat(env).containsEntry("OPENAI_HOST", "http://localhost:8200");
        assertThat(env).containsEntry("OPENAI_API_KEY", "not-required");
        assertThat(env).containsEntry("GOOSE_PROVIDER", "openai");
        assertThat(env).containsEntry("GOOSE_MODEL", "wyrd-9b");
    }

    @Test void env_openai_host_strips_trailing_v1_so_goose_does_not_double_it() {
        // Goose 1.34.1's openai provider appends "/v1/chat/completions" to
        // OPENAI_HOST. reference.conf/RecipeBakeMain carry base-url WITH /v1
        // (backends like OpenCode want the full base), so OPENAI_HOST must be
        // stripped to the bare host — else requests hit /v1/v1/... → 404, which
        // silently broke the release bake's expand-corpus step (2026-07-21).
        var cfg = new GooseRuntimeConfig(true, "goose", "openai",
            "wyrd-9b", "http://localhost:8200/v1",
            Duration.ofMinutes(30), List.of());
        var env = new GooseBackend(cfg, oauthResolver(), stub("", "", 0, false))
            .buildEnv(new AuthMode.OAuthSession());
        assertThat(env).containsEntry("OPENAI_HOST", "http://localhost:8200");
    }

    @Test void env_openai_provider_with_real_apikey_uses_resolved_value() {
        var cfg = new GooseRuntimeConfig(true, "goose", "openai",
            "gpt-4o", "https://api.openai.com/v1",
            Duration.ofMinutes(30), List.of());
        var b = new GooseBackend(cfg, oauthResolver(), stub("", "", 0, false));
        var env = b.buildEnv(new AuthMode.ApiKey("sk-oai-xyz"));
        assertThat(env).containsEntry("OPENAI_API_KEY", "sk-oai-xyz");
        // Pointing at real OpenAI -> no OPENAI_HOST override.
        assertThat(env).doesNotContainKey("OPENAI_HOST");
    }

    @Test void env_google_provider_lands_in_google_api_key() {
        var cfg = new GooseRuntimeConfig(true, "goose", "google",
            "gemini-1.5-pro", null, Duration.ofMinutes(30), List.of());
        var b = new GooseBackend(cfg, oauthResolver(), stub("", "", 0, false));
        var env = b.buildEnv(new AuthMode.ApiKey("AIza-xyz"));
        assertThat(env).containsEntry("GOOGLE_API_KEY", "AIza-xyz");
    }

    @Test void env_ollama_provider_emits_no_key_var() {
        // Ollama runs locally; no key required.
        var cfg = new GooseRuntimeConfig(true, "goose", "ollama",
            "qwen3", null, Duration.ofMinutes(30), List.of());
        var b = new GooseBackend(cfg, oauthResolver(), stub("", "", 0, false));
        var env = b.buildEnv(new AuthMode.ApiKey("ignored"));
        assertThat(env).doesNotContainKey("OPENAI_API_KEY");
        assertThat(env).doesNotContainKey("ANTHROPIC_API_KEY");
        assertThat(env).containsEntry("GOOSE_PROVIDER", "ollama");
    }

    @Test void env_legacy_local_provider_is_coerced_in_env_too() {
        // Symmetric with the argv-coercion: env shows provider=openai so
        // a household reading the env via subprocess inheritance gets the
        // canonical name.
        var cfg = new GooseRuntimeConfig(true, "goose", "local",
            "wyrd", "http://localhost:8200", Duration.ofMinutes(30), List.of());
        var b = new GooseBackend(cfg, oauthResolver(), stub("", "", 0, false));
        var env = b.buildEnv(new AuthMode.OAuthSession());
        assertThat(env).containsEntry("GOOSE_PROVIDER", "openai");
        assertThat(env).containsEntry("OPENAI_HOST", "http://localhost:8200");
        assertThat(env).containsEntry("OPENAI_API_KEY", "not-required");
    }

    @Test void provider_key_env_var_lookup_is_case_insensitive() {
        assertThat(GooseBackend.providerKeyEnvVarFor("ANTHROPIC")).isEqualTo("ANTHROPIC_API_KEY");
        assertThat(GooseBackend.providerKeyEnvVarFor("OpenAI")).isEqualTo("OPENAI_API_KEY");
        assertThat(GooseBackend.providerKeyEnvVarFor("GOOGLE")).isEqualTo("GOOGLE_API_KEY");
        assertThat(GooseBackend.providerKeyEnvVarFor("ollama")).isNull();
        assertThat(GooseBackend.providerKeyEnvVarFor(null)).isNull();
        assertThat(GooseBackend.providerKeyEnvVarFor("unknown-thing")).isNull();
    }

    // ─── Auth gate ─────────────────────────────────────────────────

    @Test void auth_missing_short_circuits_for_keyed_providers() throws Exception {
        // Keyed provider (anthropic): AuthMissing -> no subprocess.
        var ranSubprocess = new boolean[]{false};
        GooseBackend.ProcessRunner neverRun = (args, env, wd, t) -> {
            ranSubprocess[0] = true;
            throw new IllegalStateException("subprocess must not run on AuthMissing");
        };
        AuthResolver missing = name -> new AuthMode.AuthMissing(
            name, "set ANTHROPIC_API_KEY in your Key Chest", "no key");

        var cfg = new GooseRuntimeConfig(true, "goose", "anthropic",
            "claude-sonnet-4", null, Duration.ofMinutes(30), List.of());
        var b = new GooseBackend(cfg, missing, neverRun);
        var result = b.submitTask(TaskSpec.create("did:c", "code", "x"))
            .get(5, TimeUnit.SECONDS);

        assertThat(ranSubprocess[0]).isFalse();
        assertThat(result.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(result.summary()).contains("LOGIN_REQUIRED");
        assertThat(result.summary()).contains("ANTHROPIC_API_KEY");
    }

    @Test void auth_missing_does_not_short_circuit_for_keyless_providers() throws Exception {
        // Ollama + local-llama-server (provider=openai+OPENAI_HOST) need
        // no real key, so AuthMissing is fine — submission proceeds.
        AuthResolver missing = name -> new AuthMode.AuthMissing(
            name, "no key in chest", "no key");
        var cfg = new GooseRuntimeConfig(true, "goose", "ollama",
            "qwen3", null, Duration.ofMinutes(30), List.of());
        var b = new GooseBackend(cfg, missing, stub("", "", 0, false));
        var result = b.submitTask(TaskSpec.create("did:c", "code", "x"))
            .get(5, TimeUnit.SECONDS);
        // Stub returns exit=0 with no stdout — the submit succeeds with
        // an empty file list.
        assertThat(result.status()).isEqualTo(TaskStatus.SUCCEEDED);
    }

    // ─── submitTask happy path ─────────────────────────────────────

    @Test void submit_task_returns_succeeded_on_clean_run() throws Exception {
        // stream-json output: one event per line.
        var stdout = """
            {"type": "message", "message": {"role": "assistant"}}
            {"event": "edit", "file": "src/foo.java"}
            {"event": "complete", "files": ["src/foo.java", "src/bar.java"]}
            """;
        var b = new GooseBackend(enabledDefaults(), oauthResolver(),
            stub(stdout, "", 0, false));
        var spec = TaskSpec.create("did:c", "code", "do stuff");
        var result = b.submitTask(spec).get(5, TimeUnit.SECONDS);

        assertThat(result.status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(result.backend()).isEqualTo("goose");
        assertThat(result.summary()).contains("Goose");
        assertThat(result.artifactIds()).hasSize(1);

        var artifacts = b.artifactsFor(result.taskId().toString()).toList();
        var src = (SourceArtifact) artifacts.get(0);
        assertThat(src.files()).contains("src/foo.java", "src/bar.java");
    }

    @Test void submit_task_parses_single_json_document_format() throws Exception {
        // `--output-format json` emits one trailing pretty-printed
        // {messages, metadata} document. Verify the parser handles it.
        var stdout = """
            {
              "messages": [
                {"role": "assistant", "content": [
                  {"type": "tool_call", "input": {"path": "src/a.java"}}
                ]}
              ],
              "metadata": {"total_tokens": 42, "status": "success"}
            }
            """;
        var b = new GooseBackend(enabledDefaults(), oauthResolver(),
            stub(stdout, "", 0, false));
        var result = b.submitTask(TaskSpec.create("did:c", "code", "x"))
            .get(5, TimeUnit.SECONDS);
        assertThat(result.status()).isEqualTo(TaskStatus.SUCCEEDED);
        var src = (SourceArtifact) b.artifactsFor(result.taskId().toString())
            .toList().get(0);
        assertThat(src.files()).contains("src/a.java");
    }

    // ─── submitTask negative paths ─────────────────────────────────

    @Test void submit_task_marks_failed_on_nonzero_exit() throws Exception {
        var b = new GooseBackend(enabledDefaults(), oauthResolver(),
            stub("", "provider boom", 2, false));
        var result = b.submitTask(TaskSpec.create("did:c", "code", "x"))
            .get(5, TimeUnit.SECONDS);
        assertThat(result.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(result.summary()).contains("provider boom");
    }

    @Test void submit_task_marks_timed_out_when_runner_reports_timeout() throws Exception {
        var b = new GooseBackend(enabledDefaults(), oauthResolver(),
            stub("", "", -1, true));
        var result = b.submitTask(TaskSpec.create("did:c", "code", "x"))
            .get(5, TimeUnit.SECONDS);
        assertThat(result.status()).isEqualTo(TaskStatus.TIMED_OUT);
    }

    @Test void submit_task_when_disabled_returns_failed_immediately() throws Exception {
        var disabled = new GooseRuntimeConfig(false, null, null, null, null,
            null, List.of());
        var b = new GooseBackend(disabled, oauthResolver(), stub("never", "", 0, false));
        var result = b.submitTask(TaskSpec.create("did:c", "code", "x"))
            .get(5, TimeUnit.SECONDS);
        assertThat(result.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(result.summary()).contains("disabled");
    }

    // ─── healthCheck ─────────────────────────────────────────────

    @Test void healthCheck_returns_true_when_version_probe_succeeds() throws Exception {
        var b = new GooseBackend(enabledDefaults(), oauthResolver(),
            stub("goose 1.33.1", "", 0, false));
        assertThat(b.healthCheck().get(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test void healthCheck_returns_false_when_disabled() throws Exception {
        var disabled = new GooseRuntimeConfig(false, null, null, null, null,
            null, List.of());
        var b = new GooseBackend(disabled, oauthResolver(), stub("goose 1", "", 0, false));
        assertThat(b.healthCheck().get(5, TimeUnit.SECONDS)).isFalse();
    }

    @Test void healthCheck_returns_false_on_subprocess_ioexception() throws Exception {
        GooseBackend.ProcessRunner throwing = (a, e, wd, t) -> {
            throw new IOException("goose: not found");
        };
        var b = new GooseBackend(enabledDefaults(), oauthResolver(), throwing);
        assertThat(b.healthCheck().get(5, TimeUnit.SECONDS)).isFalse();
    }

    // ─── Helpers ──────────────────────────────────────────────────

    private static GooseRuntimeConfig enabledDefaults() {
        var d = GooseRuntimeConfig.defaults();
        return new GooseRuntimeConfig(true, d.executablePath(), d.provider(),
            d.model(), d.baseUrl(), d.maxWallclock(), d.extraFlags());
    }

    private static AuthResolver oauthResolver() {
        return name -> new AuthMode.OAuthSession();
    }

    private static GooseBackend.ProcessRunner stub(
            String stdout, String stderr, int exitCode, boolean timedOut) {
        return (args, env, workdir, timeout) -> new GooseBackend.ProcessResult(
            exitCode, stdout, stderr, timedOut);
    }
}
