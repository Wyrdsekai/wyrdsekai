package org.wyrdsekai.core.coding;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2e regression: Claude SDK adapter must switch wire shape based
 * on whether the resolver returned an {@link AuthMode.OAuthSession} or
 * an {@link AuthMode.ApiKey}. The {@code --bare} flag is the load-bearing
 * difference: bare-mode skips OAuth/keychain reads, which is what we
 * want for ApiKey paths but absolutely NOT what we want for households
 * on Anthropic subscription tiers.
 */
class ClaudeSdkAuthSwitchingTest {

    /**
     * Recording runner that captures argv + env + stdin from the most
     * recent invocation so the test can assert against them.
     */
    private static final class RecordingRunner implements ClaudeSdkBackend.ProcessRunner {
        final AtomicReference<List<String>> capturedArgs = new AtomicReference<>();
        final AtomicReference<Map<String, String>> capturedEnv = new AtomicReference<>();

        @Override
        public ClaudeSdkBackend.ProcessResult run(List<String> args, Map<String, String> env,
                                                   String stdin, Duration timeout) {
            capturedArgs.set(List.copyOf(args));
            capturedEnv.set(Map.copyOf(env));
            // Return a minimal valid Claude SDK JSON response.
            return new ClaudeSdkBackend.ProcessResult(0,
                "{\"result\":\"ok\",\"session_id\":\"s\",\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}",
                "", false);
        }
    }

    private static ClaudeSdkRuntimeConfig enabledDefaults() {
        var d = ClaudeSdkRuntimeConfig.defaults();
        return new ClaudeSdkRuntimeConfig(true, d.executablePath(), d.model(),
            d.useBare(), d.maxWallclock(), d.extraFlags());
    }

    @Test void oauth_path_does_not_pass_bare_and_does_not_set_api_key_env() throws Exception {
        var runner = new RecordingRunner();
        AuthResolver oauth = name -> new AuthMode.OAuthSession();
        var b = new ClaudeSdkBackend(enabledDefaults(), oauth, runner);

        var result = b.submitTask(new TaskSpec(UUID.randomUUID(), "did:c", "code", "x",
            null, List.of(), 0L, null)).get(5, TimeUnit.SECONDS);
        assertThat(result.status()).isEqualTo(TaskStatus.SUCCEEDED);

        var args = runner.capturedArgs.get();
        var env = runner.capturedEnv.get();
        assertThat(args).doesNotContain("--bare");
        assertThat(env).doesNotContainKey("ANTHROPIC_API_KEY");
    }

    @Test void apikey_path_passes_bare_and_sets_api_key_env() throws Exception {
        var runner = new RecordingRunner();
        AuthResolver apiKey = name -> new AuthMode.ApiKey("sk-ant-test-key");
        var b = new ClaudeSdkBackend(enabledDefaults(), apiKey, runner);

        var result = b.submitTask(new TaskSpec(UUID.randomUUID(), "did:c", "code", "x",
            null, List.of(), 0L, null)).get(5, TimeUnit.SECONDS);
        assertThat(result.status()).isEqualTo(TaskStatus.SUCCEEDED);

        var args = runner.capturedArgs.get();
        var env = runner.capturedEnv.get();
        assertThat(args).contains("--bare");
        assertThat(env).containsEntry("ANTHROPIC_API_KEY", "sk-ant-test-key");
    }

    @Test void apikey_path_with_use_bare_false_skips_bare_but_still_sets_env() throws Exception {
        // Households can opt OUT of --bare via use-bare=false; the env
        // var still flows so the upstream CLI can pick up the key from
        // its own ANTHROPIC_API_KEY read path.
        var runner = new RecordingRunner();
        var cfg = new ClaudeSdkRuntimeConfig(true, "claude", "sonnet", false,
            Duration.ofMinutes(30), List.of());
        AuthResolver apiKey = name -> new AuthMode.ApiKey("sk-ant-test-key");
        var b = new ClaudeSdkBackend(cfg, apiKey, runner);

        b.submitTask(TaskSpec.create("did:c", "code", "x")).get(5, TimeUnit.SECONDS);

        assertThat(runner.capturedArgs.get()).doesNotContain("--bare");
        assertThat(runner.capturedEnv.get()).containsEntry("ANTHROPIC_API_KEY", "sk-ant-test-key");
    }
}
