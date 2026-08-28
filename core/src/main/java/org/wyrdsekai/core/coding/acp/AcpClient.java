package org.wyrdsekai.core.coding.acp;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.coding.ConsentBroker;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * The ACP v1 protocol driver — everything above the wire, nothing about
 * processes. Speaks the four client-side verbs a conformant integration
 * needs ({@code initialize}, {@code session/new}, {@code session/prompt},
 * {@code session/cancel}), collects {@code session/update} notifications
 * for the duration of a prompt turn, and answers
 * {@code session/request_permission} through a pluggable
 * {@link PermissionPolicy}.
 *
 * <p><b>Version negotiation, not assumption:</b> we offer
 * {@link AcpMethods#PROTOCOL_VERSION} and verify the agent's echo. An
 * agent that answers with a version we don't speak fails initialization
 * loudly — no method call is ever issued on an un-negotiated dialect.</p>
 *
 * <p><b>Capabilities we decline:</b> {@code fs/*} and {@code terminal}
 * are declared false at initialize. Agents on the other side (CodeZaiku,
 * Goose) carry their own confined file/shell tooling; wyrdsekai's
 * EgressGate governs the spawn env instead. If an agent calls a declined
 * capability anyway, the default agent-request handler answers with an
 * error rather than touching the filesystem.</p>
 */
public final class AcpClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AcpClient.class);
    private static final Duration RPC_TIMEOUT = Duration.ofSeconds(30);

    /** Chooses an option id for a permission request; null = reject. */
    @FunctionalInterface
    public interface PermissionPolicy {
        String choose(JsonNode toolCall, JsonNode options);
    }

    /**
     * Permissive policy: first option whose kind starts with "allow" —
     * within-workspace confinement is the agent's own (declined fs/*
     * means we never write on its behalf). Kept for tests and callers
     * that explicitly want it; NOT the default.
     */
    public static final PermissionPolicy ALLOW_FIRST = (toolCall, options) -> {
        if (options != null && options.isArray()) {
            for (var opt : options) {
                if (opt.path("kind").asText("").startsWith("allow")) {
                    return opt.path("optionId").asText(null);
                }
            }
        }
        return null;
    };

    /** Git-state-writing commands, as CodeZaiku's permission gate frames them
     *  (2026-08-15: commit, add, push, checkout, reset, rebase, tag, remote…). */
    private static final Pattern GIT_WRITE =
        Pattern.compile(
            "\\bgit\\s+(commit|push|add|reset|rebase|checkout|switch|restore"
                + "|tag|remote|merge|cherry-pick|revert|am|apply|stash|clean)\\b");

    /**
     * The DEFAULT policy, and it encodes the household's standing rule at
     * the wire: <b>the human commits</b>. A permission request whose tool
     * call reads as a git-state write is declined ({@code reject_once} —
     * never {@code *_always}, so every ask stays visible); everything else
     * is allowed. CodeZaiku's gate (built 2026-08-15 at our framing: "the
     * guard is on your side of the wire") continues the run with the
     * commit refused and the work left in the tree — exactly the outcome
     * this house wants. Full steward routing replaces this seam when the
     * consent flow is wired post-0.1.6.
     */
    public static final PermissionPolicy HOUSE_POLICY = (toolCall, options) -> {
        var text = toolCall == null ? ""
            : toolCall.toString().toLowerCase(Locale.ROOT);
        if (GIT_WRITE.matcher(text).find()) {
            var reject = optionOfKind(options, "reject_once", "reject_always", "reject");
            if (reject != null) return reject;
            return null; // no reject option offered → cancelled outcome = still "no"
        }
        return ALLOW_FIRST.choose(toolCall, options);
    };

    /**
     * Live steward consent on top of HOUSE_POLICY's frame (2026-08-16).
     * Git-state writes no longer auto-refuse: the ask is routed to the
     * steward (in-world tell / phone / {@code wyrd consent}) and this
     * thread waits up to {@code wait} for the answer. Silence, timeout, or
     * an explicit deny all resolve to the same {@code reject_once} the
     * static policy produced — the broker can only ADD a real-time yes,
     * never widen what silence permits. Grants are allow-ONCE; the
     * {@code *_always} kinds stay off the table on both branches.
     *
     * @param broker  the pending-consent registry (singleton in prod)
     * @param wait    how long a run pauses for the steward; keep well under
     *                the agent's own no-answer window (CodeZaiku: 600s)
     * @param backend name shown to the steward (e.g. "acp")
     * @param taskId  task the ask belongs to, for the list surfaces
     */
    public static PermissionPolicy stewardConsent(
            ConsentBroker broker, Duration wait, String backend, String taskId) {
        return (toolCall, options) -> {
            var text = toolCall == null ? ""
                : toolCall.toString().toLowerCase(Locale.ROOT);
            if (GIT_WRITE.matcher(text).find()) {
                var title = toolCall == null ? "" : toolCall.path("title").asText("");
                var summary = title.isBlank()
                    ? text.substring(0, Math.min(text.length(), 160)) : title;
                var pending = broker.request(backend, taskId, summary);
                if (broker.await(pending.id(), wait)) {
                    var allow = optionOfKind(options, "allow_once", "allow");
                    if (allow != null) return allow;
                    // No allow-once shaped option → refuse rather than pick
                    // an *_always kind the steward never granted.
                }
                var reject = optionOfKind(options, "reject_once", "reject_always", "reject");
                return reject; // null → cancelled outcome = still "no"
            }
            return ALLOW_FIRST.choose(toolCall, options);
        };
    }

    private static String optionOfKind(JsonNode options, String... kinds) {
        if (options == null || !options.isArray()) return null;
        for (var kind : kinds) {
            for (var opt : options) {
                if (kind.equals(opt.path("kind").asText(""))) {
                    return opt.path("optionId").asText(null);
                }
            }
        }
        return null;
    }

    private final AcpConnection conn;
    private final PermissionPolicy permissionPolicy;
    private final List<JsonNode> updates = new ArrayList<>();
    private volatile int negotiatedVersion = -1;

    public AcpClient(AcpConnection conn, PermissionPolicy permissionPolicy) {
        this.conn = conn;
        this.permissionPolicy = permissionPolicy != null ? permissionPolicy : HOUSE_POLICY;
        conn.onNotification((method, params) -> {
            if (AcpMethods.SESSION_UPDATE.equals(method)) {
                synchronized (updates) { updates.add(params); }
            }
        });
        conn.onAgentRequest((method, params) -> {
            if (AcpMethods.SESSION_REQUEST_PERMISSION.equals(method)) {
                return answerPermission(params);
            }
            // Declined capabilities (fs/*, terminal/*) or unknown methods:
            // refuse by name. The agent declared-capability contract means
            // a conformant agent never calls these; a non-conformant one
            // gets an honest error, not silent filesystem access.
            throw new UnsupportedOperationException(
                "client capability not offered: " + method);
        });
    }

    /** Negotiated protocol version, or -1 before initialize. */
    public int negotiatedVersion() { return negotiatedVersion; }

    /** Snapshot of session/update params collected so far. */
    public List<JsonNode> updates() {
        synchronized (updates) { return List.copyOf(updates); }
    }

    /** initialize → verify the echoed protocol version. */
    public JsonNode initialize(String clientName, String clientVersion)
            throws IOException, InterruptedException, TimeoutException {
        var params = AcpConnection.obj(p -> {
            p.put("protocolVersion", AcpMethods.PROTOCOL_VERSION);
            var caps = p.putObject("clientCapabilities");
            var fs = caps.putObject("fs");
            fs.put("readTextFile", false);
            fs.put("writeTextFile", false);
            caps.put("terminal", false);
            var info = p.putObject("clientInfo");
            info.put("name", clientName);
            info.put("version", clientVersion);
            return p;
        });
        var result = conn.request(AcpMethods.INITIALIZE, params, RPC_TIMEOUT);
        var agentVersion = result.path("protocolVersion").asInt(-1);
        if (agentVersion != AcpMethods.PROTOCOL_VERSION) {
            throw new IOException("ACP version mismatch: we offered "
                + AcpMethods.PROTOCOL_VERSION + ", agent answered " + agentVersion
                + " — refusing to speak an un-negotiated dialect");
        }
        negotiatedVersion = agentVersion;
        return result;
    }

    /** session/new → sessionId. */
    public String newSession(String cwd)
            throws IOException, InterruptedException, TimeoutException {
        requireNegotiated();
        var params = AcpConnection.obj(p -> {
            p.put("cwd", cwd);
            p.putArray("mcpServers");
            return p;
        });
        var result = conn.request(AcpMethods.SESSION_NEW, params, RPC_TIMEOUT);
        var sessionId = result.path("sessionId").asText(null);
        if (sessionId == null || sessionId.isBlank()) {
            throw new IOException("ACP session/new returned no sessionId");
        }
        return sessionId;
    }

    /**
     * session/prompt with one text block; blocks until the turn ends.
     * Returns the FULL PromptResponse — callers read {@code stopReason},
     * and agents may attach a structured result under {@code _meta}
     * (CodeZaiku carries its result document at {@code _meta.codezaiku};
     * throwing the response away almost lost that, 2026-08-15).
     */
    public JsonNode prompt(String sessionId, String text, Duration turnTimeout)
            throws IOException, InterruptedException, TimeoutException {
        requireNegotiated();
        var params = AcpConnection.obj(p -> {
            p.put("sessionId", sessionId);
            var block = p.putArray("prompt").addObject();
            block.put("type", "text");
            block.put("text", text);
            return p;
        });
        return conn.request(AcpMethods.SESSION_PROMPT, params, turnTimeout);
    }

    /** session/cancel — fire-and-forget per spec. */
    public void cancel(String sessionId) {
        try {
            conn.notify(AcpMethods.SESSION_CANCEL,
                AcpConnection.obj(p -> { p.put("sessionId", sessionId); return p; }));
        } catch (IOException e) {
            log.debug("acp: cancel notify failed: {}", e.getMessage());
        }
    }

    private JsonNode answerPermission(JsonNode params) {
        var choice = permissionPolicy.choose(
            params.path("toolCall"), params.path("options"));
        return AcpConnection.obj(p -> {
            var outcome = p.putObject("outcome");
            if (choice != null) {
                outcome.put("outcome", "selected");
                outcome.put("optionId", choice);
            } else {
                // no allowable option — schema's cancelled variant is the
                // conformant "no" that every agent must accept
                outcome.put("outcome", "cancelled");
            }
            return p;
        });
    }

    private void requireNegotiated() throws IOException {
        if (negotiatedVersion != AcpMethods.PROTOCOL_VERSION) {
            throw new IOException("ACP connection not initialized");
        }
    }

    @Override public void close() { conn.close(); }
}
