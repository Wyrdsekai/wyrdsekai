package org.wyrdsekai.e2e.infra;

import org.junit.jupiter.api.Assumptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.inference.ClaudeCliInference;
import org.wyrdsekai.core.inference.InferenceBackend;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

/**
 * Fixture for using Claude CLI as an inference backend in E2E tests.
 * No Docker container needed — uses the user's existing Claude Code CLI
 * with OAuth authentication.
 *
 * <p>Prerequisites:
 * <ul>
 *   <li>{@code claude} CLI on PATH
 *   <li>Authenticated via OAuth ({@code claude auth status} returns valid session)
 * </ul>
 *
 * <p>This bypasses local GPU requirements entirely — inference runs on
 * Anthropic's servers. Useful for CPU-only machines or CI environments.
 */
public final class ClaudeCliFixture implements InferenceServerFixture {

    private static final Logger log = LoggerFactory.getLogger(ClaudeCliFixture.class);
    private static final String CLI_PATH = System.getenv().getOrDefault(
        "CLAUDE_CLI_PATH", "claude");
    private static final Duration TIMEOUT = Duration.ofSeconds(120);

    private String subscriptionType;
    private List<String> models;
    private boolean started;

    /**
     * Check if claude CLI is installed and authenticated.
     */
    public static boolean isAvailable() {
        try {
            var proc = new ProcessBuilder(CLI_PATH, "--version")
                .redirectErrorStream(true)
                .start();
            var output = new String(proc.getInputStream().readAllBytes()).trim();
            proc.waitFor();
            if (proc.exitValue() != 0 || output.isEmpty()) return false;

            // Check auth status
            var authProc = new ProcessBuilder(CLI_PATH, "auth", "status")
                .redirectErrorStream(true)
                .start();
            var authOutput = new String(authProc.getInputStream().readAllBytes()).trim();
            authProc.waitFor();
            return authProc.exitValue() == 0 &&
                !authOutput.toLowerCase().contains("not logged in");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Detect subscription type from claude CLI auth status.
     */
    public static String detectSubscriptionType() {
        try {
            var proc = new ProcessBuilder(CLI_PATH, "auth", "status")
                .redirectErrorStream(true)
                .start();
            var output = new String(proc.getInputStream().readAllBytes()).trim().toLowerCase();
            proc.waitFor();

            if (output.contains("max")) return "max";
            if (output.contains("pro")) return "pro";
            return "free";
        } catch (Exception e) {
            return "free";
        }
    }

    /**
     * JUnit assumption: skip if claude CLI not available or not authenticated.
     */
    public static void assumeAvailable() {
        Assumptions.assumeTrue(isAvailable(),
            "Claude CLI not available or not authenticated " +
            "(install claude CLI and run 'claude auth login')");
    }

    @Override
    public void start() throws IOException, InterruptedException {
        log.info("Initializing Claude CLI fixture");
        assumeAvailable();

        subscriptionType = detectSubscriptionType();
        models = switch (subscriptionType) {
            case "max" -> List.of("claude-opus-4-6", "claude-sonnet-4-6", "claude-haiku-4-5-20251001");
            case "pro" -> List.of("claude-sonnet-4-6", "claude-haiku-4-5-20251001");
            default -> List.of("claude-haiku-4-5-20251001");
        };

        started = true;
        log.info("Claude CLI ready (subscription: {}, models: {})", subscriptionType, models);
    }

    @Override
    public void stop() {
        started = false;
    }

    @Override
    public void restart() throws IOException, InterruptedException {
        stop();
        start();
    }

    @Override
    public boolean isRunning() {
        return started;
    }

    @Override
    public String baseUrl() {
        return "claude-cli://oauth";
    }

    @Override
    public int port() {
        return -1; // no port — subprocess
    }

    @Override
    public BackendType backendType() {
        return BackendType.CLAUDE_CLI;
    }

    /**
     * Create an InferenceBackend.ClaudeCli for test use.
     * This overrides the default createBackend() since Claude CLI
     * doesn't use an HTTP client.
     */
    public InferenceBackend createClaudeBackend(String name, int priority) {
        var cli = new ClaudeCliInference(CLI_PATH, TIMEOUT, subscriptionType, models);
        return new InferenceBackend.ClaudeCli(name, cli, priority, models);
    }

    @Override
    public InferenceBackend createBackend(String name, int priority) {
        return createClaudeBackend(name, priority);
    }
}
