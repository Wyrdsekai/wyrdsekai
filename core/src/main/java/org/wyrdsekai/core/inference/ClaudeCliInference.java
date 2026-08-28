package org.wyrdsekai.core.inference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Claude CLI subprocess wrapper for inference via OAuth.
 * Uses the user's existing Claude Code CLI authentication — no API key needed.
 * <p>
 * Referenced from CodeZaiku ClaudeCliProvider.java (commit 3290271):
 * - Auth detection: lines 616-658
 * - Process spawning: lines 564-585
 * - Response parsing: lines 152-216
 * - Model aliases: lines 500-504
 */
public final class ClaudeCliInference {

    private static final Logger log = LoggerFactory.getLogger(ClaudeCliInference.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Map<String, String> MODEL_ALIASES = Map.of(
        "claude-opus-4-20250514", "opus",
        "claude-sonnet-4-20250514", "sonnet",
        "claude-haiku-3-5-20241022", "haiku"
    );

    private final String cliPath;
    private final Duration timeout;
    private final String subscriptionType;
    private final List<String> models;

    public ClaudeCliInference(String cliPath, Duration timeout,
                               String subscriptionType, List<String> models) {
        this.cliPath = cliPath;
        this.timeout = timeout;
        this.subscriptionType = subscriptionType;
        this.models = List.copyOf(models);
    }

    /**
     * Auto-detect Claude CLI installation and authentication.
     * Returns configured instance if CLI is available and user is authenticated.
     */
    public static Optional<ClaudeCliInference> autoDetect() {
        return autoDetect("claude");
    }

    public static Optional<ClaudeCliInference> autoDetect(String cliPath) {
        try {
            // Check CLI exists
            var versionCheck = new ProcessBuilder(cliPath, "--version");
            versionCheck.environment().remove("CLAUDECODE");
            versionCheck.redirectErrorStream(true);
            var p = versionCheck.start();
            if (!p.waitFor(5, TimeUnit.SECONDS) || p.exitValue() != 0) {
                return Optional.empty();
            }
            String version = new String(p.getInputStream().readAllBytes()).trim();

            // Check auth status (ref: CodeZaiku ClaudeCliProvider.java:628-648)
            var authCheck = new ProcessBuilder(cliPath, "auth", "status");
            authCheck.environment().remove("CLAUDECODE");
            authCheck.redirectErrorStream(true);
            var auth = authCheck.start();
            if (!auth.waitFor(5, TimeUnit.SECONDS)) {
                return Optional.empty();
            }
            String authOutput = new String(auth.getInputStream().readAllBytes());
            JsonNode status;
            try {
                status = MAPPER.readTree(authOutput);
            } catch (Exception e) {
                return Optional.empty();
            }
            if (!status.path("loggedIn").asBoolean(false)) {
                return Optional.empty();
            }

            // Determine models by subscription tier
            String subType = status.path("subscriptionType").asText("");
            List<String> models = switch (subType) {
                case "max" -> List.of(
                    "claude-sonnet-4-20250514", "claude-opus-4-20250514", "claude-haiku-3-5-20241022");
                case "pro" -> List.of(
                    "claude-sonnet-4-20250514", "claude-haiku-3-5-20241022");
                default -> List.of("claude-sonnet-4-20250514");
            };

            log.info("Claude CLI detected: v{} (subscription: {}, models: {})",
                version, subType.isEmpty() ? "free" : subType, models.size());
            return Optional.of(new ClaudeCliInference(cliPath, Duration.ofSeconds(300), subType, models));
        } catch (Exception e) {
            log.debug("Claude CLI auto-detect failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Run inference via Claude CLI subprocess.
     * Wraps in CompletableFuture via virtual thread.
     */
    public CompletableFuture<InferenceClient.ChatResponse> chatCompletion(
            InferenceClient.ChatRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return executeCli(request);
            } catch (InferenceClient.InferenceException e) {
                throw e;
            } catch (Exception e) {
                throw new InferenceClient.InferenceException(
                    "Claude CLI inference failed: " + e.getMessage(), e);
            }
        });
    }

    public List<String> availableModels() {
        return models;
    }

    public String getSubscriptionType() {
        return subscriptionType;
    }

    // --- Internal ---

    private InferenceClient.ChatResponse executeCli(InferenceClient.ChatRequest request) {
        // Build command args
        String modelAlias = resolveModelAlias(request.model());
        List<String> args = new ArrayList<>();
        args.add(cliPath);
        args.add("-p");
        args.add("--no-session-persistence");
        // INFERENCE ONLY — this backend is a companion's MIND, not an agent.
        // Without this, headless turns inherit the host CLI's default tool
        // permissions and a companion's musing can act on the host (observed
        // 2026-08-14: an e2e zone's companion, auto-backed by Claude CLI,
        // sent cross-session messages from inside its own-time turns). All
        // acting must go through the world's tool-affordance and consent
        // system; the substrate itself gets words only.
        args.add("--tools");
        args.add("");
        args.add("--output-format");
        args.add("json");
        args.add("--model");
        args.add(modelAlias);

        // Extract system prompt from messages
        String systemPrompt = null;
        var userMessages = new ArrayList<InferenceClient.ChatMessage>();
        for (var msg : request.messages()) {
            if ("system".equals(msg.role())) {
                systemPrompt = msg.content();
            } else {
                userMessages.add(msg);
            }
        }

        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            args.add("--system-prompt");
            args.add(systemPrompt);
        }

        // Format prompt: last user message as main prompt
        // Prior messages as context in XML format
        StringBuilder prompt = new StringBuilder();
        for (var msg : userMessages) {
            prompt.append("<").append(msg.role()).append(">\n");
            prompt.append(msg.content()).append("\n");
            prompt.append("</").append(msg.role()).append(">\n\n");
        }

        // Spawn process
        var result = runProcess(args, prompt.toString().trim());

        // Parse JSON response (ref: CodeZaiku ClaudeCliProvider.java:152-216)
        try {
            JsonNode root = MAPPER.readTree(result.stdout);

            if (root.path("is_error").asBoolean(false)) {
                String error = root.path("result").asText("Unknown error");
                throw new InferenceClient.InferenceException("Claude CLI error: " + error);
            }

            String responseText = root.path("result").asText("");

            // Parse token usage
            int inputTokens = 0, outputTokens = 0;
            JsonNode usage = root.path("usage");
            if (!usage.isMissingNode()) {
                inputTokens = usage.path("input_tokens").asInt(0);
                outputTokens = usage.path("output_tokens").asInt(0);
            }

            return new InferenceClient.ChatResponse(
                root.path("session_id").asText("cli"),
                "message",
                System.currentTimeMillis() / 1000,
                request.model(),
                List.of(new InferenceClient.Choice(
                    0,
                    new InferenceClient.ChatMessage("assistant", responseText),
                    "stop"
                )),
                new InferenceClient.Usage(inputTokens, outputTokens, inputTokens + outputTokens)
            );
        } catch (InferenceClient.InferenceException e) {
            throw e;
        } catch (Exception e) {
            throw new InferenceClient.InferenceException(
                "Failed to parse Claude CLI response: " + e.getMessage(), e);
        }
    }

    private static String resolveModelAlias(String modelId) {
        if (modelId == null) return "sonnet";
        String alias = MODEL_ALIASES.get(modelId);
        return alias != null ? alias : modelId;
    }

    private record ProcessResult(String stdout, String stderr, int exitCode) {}

    private ProcessResult runProcess(List<String> args, String prompt) {
        try {
            var pb = new ProcessBuilder(args);
            pb.environment().remove("CLAUDECODE");
            pb.redirectErrorStream(false);
            var process = pb.start();

            // Write prompt to stdin
            if (prompt != null && !prompt.isEmpty()) {
                try (var out = process.getOutputStream()) {
                    out.write(prompt.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            }

            String stdout = new String(process.getInputStream().readAllBytes());
            String stderr = new String(process.getErrorStream().readAllBytes());
            boolean finished = process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new InferenceClient.InferenceException(
                    "Claude CLI timed out after " + timeout.toSeconds() + "s");
            }
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new InferenceClient.InferenceException(
                    "Claude CLI exited with code " + exitCode + ": " + stderr.trim());
            }
            return new ProcessResult(stdout, stderr, exitCode);
        } catch (InferenceClient.InferenceException e) {
            throw e;
        } catch (IOException e) {
            throw new InferenceClient.InferenceException(
                "Claude CLI not found at '" + cliPath + "'. Install: https://code.claude.com", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InferenceClient.InferenceException("Interrupted waiting for Claude CLI", e);
        } catch (Exception e) {
            throw new InferenceClient.InferenceException(
                "Claude CLI process error: " + e.getMessage(), e);
        }
    }
}
