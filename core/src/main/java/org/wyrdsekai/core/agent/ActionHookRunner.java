package org.wyrdsekai.core.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.util.Json;
import org.wyrdsekai.core.security.Denial;
import org.wyrdsekai.core.security.DenialCatalog;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

// Executes external shell commands before/after agent actions.
// Hooks receive JSON payload via stdin and return exit codes:
//   0 = allow
//   2 = deny (blocks the action)
//   1,3+ = warn (continue but log warning)
public class ActionHookRunner {

    private static final Logger log = LoggerFactory.getLogger(ActionHookRunner.class);
    private static final Duration HOOK_TIMEOUT = Duration.ofSeconds(10);

    public enum HookEvent { PRE_ACTION, POST_ACTION }

    /**
     * F13: when {@code allowed=false}, {@code denial} is populated with
     * a structured remediation hint (parsed from JSON-stdout, or a
     * text-fallback Denial built from the raw output).
     */
    public record HookResult(
        boolean allowed,
        String output,
        int exitCode,
        Denial denial
    ) {
        public static final HookResult ALLOW = new HookResult(true, "", 0, null);

        /** Legacy 3-arg constructor — kept for source compatibility (denial=null). */
        public HookResult(boolean allowed, String output, int exitCode) {
            this(allowed, output, exitCode, null);
        }
    }

    public record HookPayload(
        String hookEvent,
        String agentId,
        String agentName,
        String actionType,
        Map<String, Object> actionParams,
        String roomId,
        int agentTier
    ) {}

    private final Map<HookEvent, List<String>> hooks;
    private final ObjectMapper mapper = Json.mapper();

    public ActionHookRunner(Map<HookEvent, List<String>> hooks) {
        this.hooks = hooks != null ? hooks : Map.of();
    }

    // Empty runner (no hooks configured).
    public static ActionHookRunner none() {
        return new ActionHookRunner(Map.of());
    }

    // Run all hooks for an event. Returns deny result if any hook denies.
    public HookResult run(HookEvent event, HookPayload payload) {
        var commands = hooks.getOrDefault(event, List.of());
        if (commands.isEmpty()) return HookResult.ALLOW;

        String payloadJson;
        try {
            payloadJson = mapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.warn("Failed to serialize hook payload: {}", e.getMessage());
            return HookResult.ALLOW;
        }

        for (var command : commands) {
            var result = executeHook(command, payloadJson);
            if (result.exitCode() == 2) {
                // F13: enrich the deny result with a structured Denial.
                // If the hook emitted JSON {deny, reason, remediation,
                // cliHint, inWorldResolution}, parse it; otherwise fall
                // back to a text-only Denial wrapping the hook's stdout.
                var denial = parseHookDenial(command, result.output());
                log.info("Hook denied action: {}", denial.summary());
                return new HookResult(false, result.output(), 2, denial);
            }
            if (result.exitCode() != 0) {
                log.warn("Hook warning: command={}, exit={}, output={}",
                    command, result.exitCode(), result.output());
            }
        }
        return HookResult.ALLOW;
    }

    private HookResult executeHook(String command, String payloadJson) {
        try {
            var pb = new ProcessBuilder("sh", "-c", command);
            pb.redirectErrorStream(true);
            var process = pb.start();

            // Write payload to stdin
            try (var os = process.getOutputStream()) {
                os.write(payloadJson.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            // Read output
            String output;
            try (var is = process.getInputStream()) {
                output = new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
            }

            boolean finished = process.waitFor(HOOK_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("Hook timed out after {}s: {}", HOOK_TIMEOUT.toSeconds(), command);
                return new HookResult(true, "timeout", -1);
            }

            int exitCode = process.exitValue();
            return new HookResult(exitCode != 2, output, exitCode);

        } catch (Exception e) {
            log.warn("Hook execution failed: {} -- {}", command, e.getMessage());
            return new HookResult(true, e.getMessage(), -1); // fail-open
        }
    }

    public boolean hasHooks(HookEvent event) {
        return !hooks.getOrDefault(event, List.of()).isEmpty();
    }

    /**
     * F13: build a structured {@link org.wyrdsekai.core.security.Denial}
     * from the hook's stdout. Hook authors are encouraged to emit JSON:
     * <pre>
     *   {"deny": true, "reason": "...", "remediation": "...",
     *    "code": "fabricated_credential",
     *    "cliHint": {"command": "wyrd relay register ..."},
     *    "inWorldResolution": {"action": "request_access", "source": "...",
     *                          "scope": "use", "reason": "..."}}
     * </pre>
     * For legacy hooks that only print text, the entire stdout becomes
     * the {@code reason} and the canonical {@code DenialCatalog.hookDenied}
     * template provides remediation phrasing.
     */
    private Denial parseHookDenial(
            String command, String hookOutput) {
        if (hookOutput == null || hookOutput.isBlank()) {
            return DenialCatalog.hookDenied(
                command, null, null);
        }
        // Try JSON parse first — only commits to JSON path if the entire
        // output is a JSON object starting with '{'.
        var trimmed = hookOutput.trim();
        if (trimmed.startsWith("{")) {
            try {
                var node = mapper.readTree(trimmed);
                String reason = node.path("reason").asText(null);
                String remediation = node.path("remediation").asText(null);
                String code = node.path("code").asText(
                    DenialCatalog.CODE_HOOK_DENIED);

                Denial.RequestTemplate inWorld = null;
                var iwr = node.get("inWorldResolution");
                if (iwr != null && iwr.isObject()) {
                    inWorld = new Denial.RequestTemplate(
                        iwr.path("action").asText("request_access"),
                        iwr.path("source").asText(""),
                        iwr.path("scope").asText("use"),
                        iwr.path("reason").asText(""));
                }
                Map<String, String> cliHint = null;
                var ch = node.get("cliHint");
                if (ch != null && ch.isObject()) {
                    var m = new HashMap<String, String>();
                    ch.fields().forEachRemaining(e -> m.put(e.getKey(), e.getValue().asText()));
                    cliHint = m;
                }
                return new Denial(
                    code,
                    reason != null ? reason : "Hook denied (no reason given).",
                    remediation, inWorld, cliHint);
            } catch (Exception e) {
                log.debug("Hook stdout starts with '{{' but isn't valid JSON, "
                    + "falling back to text: {}", e.getMessage());
            }
        }
        // Text fallback — wrap the raw output as the reason.
        return DenialCatalog.hookDenied(
            command, trimmed, null);
    }
}
