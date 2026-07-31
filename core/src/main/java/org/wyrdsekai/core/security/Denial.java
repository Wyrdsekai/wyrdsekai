package org.wyrdsekai.core.security;

import java.util.Map;

/**
 * structured denial record. A wall without
 * a remediation path is operator-friction; this record is the contract
 * every gate (ActionPolicy, ActionHookRunner, HomeAccessGate, Ward) uses
 * to say "no" in a way that the receiver can act on.
 *
 * <p>Two surfaces:
 * <ul>
 *   <li><b>{@code inWorldResolution}</b> — a {@link RequestTemplate} the
 *       agent can pre-fill into the existing {@code request_access}
 *       action (T2). The Board furnishing already lists pending requests;
 *       the steward approves; a Grant is issued; the agent retries
 *       successfully. The whole loop stays in-world. This is the primary
 *       path.</li>
 *   <li><b>{@code cliHint}</b> — a structured CLI command + args for
 *       stewards working from a terminal. Fallback path when a steward
 *       isn't reachable in-world (e.g., during initial setup, or for
 *       infrastructure-mode operations like minting a relay token).</li>
 * </ul>
 *
 * <p>Discipline rule: every new denial site MUST construct a {@code Denial}.
 * If neither {@code inWorldResolution} nor {@code cliHint} applies, leave
 * both null and add a {@code // TODO(F13): no remediation path} comment.
 * Reviewers grep for {@code Denial.of(} when adding new gates and for
 * {@code TODO(F13)} when triaging operator-friction reports.
 *
 * <p>{@code code} is a stable machine-readable identifier (e.g.
 * {@code tier_gated}, {@code hook_denied}, {@code grant_required}); it
 * keys the {@link DenialCatalog} of canonical templates and is the
 * audit-log search term operators use when investigating recurrences.
 */
public record Denial(
    String code,
    String reason,
    String remediation,
    RequestTemplate inWorldResolution,
    Map<String, String> cliHint
) {

    /**
     * Pre-filled request template matching the {@code request_access}
     * action's {@code (source, scope, reason)} signature
     * (CompanionActor.handleRequestAccess, ActionParser.RequestAccess).
     * Agents that hit a denial with this populated should construct a
     * {@code request_access} call from these fields and emit it; the
     * existing Board + Grant pipeline does the rest.
     */
    public record RequestTemplate(
        String action,        // "request_access" today; future: "knock", "petition"
        String source,        // resource URI to be granted
        String scope,         // capability name (read/write/use/delegate/attest)
        String suggestedReason
    ) {
        public static RequestTemplate forAccess(String source, String scope, String reason) {
            return new RequestTemplate("request_access", source, scope, reason);
        }
    }

    // ── Builders ─────────────────────────────────────────────────────────

    /** Bare denial with reason only — use when no remediation path is known. */
    public static Denial of(String code, String reason) {
        return new Denial(code, reason, null, null, null);
    }

    /** Return a copy with the remediation field set. */
    public Denial withRemediation(String newRemediation) {
        return new Denial(code, reason, newRemediation, inWorldResolution, cliHint);
    }

    /** Denial that points at the in-world request_access flow. */
    public static Denial withInWorldResolution(
            String code, String reason, String remediation, RequestTemplate template) {
        return new Denial(code, reason, remediation, template, null);
    }

    /** Denial with a CLI fallback only (steward terminal mode). */
    public static Denial withCliHint(
            String code, String reason, String remediation, Map<String, String> hint) {
        return new Denial(code, reason, remediation, null, hint);
    }

    /** Denial with both surfaces. */
    public static Denial withBoth(
            String code, String reason, String remediation,
            RequestTemplate template, Map<String, String> hint) {
        return new Denial(code, reason, remediation, template, hint);
    }

    // ── Surface helpers ──────────────────────────────────────────────────

    /**
     * Render as a single-line human-readable string suitable for logs
     * and audit entries. CLI hint and request template are summarised.
     */
    public String summary() {
        var sb = new StringBuilder();
        sb.append("[").append(code).append("] ").append(reason);
        if (remediation != null && !remediation.isBlank()) {
            sb.append(" — ").append(remediation);
        }
        if (inWorldResolution != null) {
            sb.append(" (try: ").append(inWorldResolution.action())
                .append(" source=").append(inWorldResolution.source())
                .append(" scope=").append(inWorldResolution.scope()).append(")");
        }
        if (cliHint != null && !cliHint.isEmpty()) {
            sb.append(" (cli: ").append(cliHint.getOrDefault("command", "?")).append(")");
        }
        return sb.toString();
    }

    /**
     * Render as a multi-line block for the agent's prompt context. The
     * intent is that an LLM seeing this on its next turn understands
     * both why it failed and exactly what action sequence resolves the
     * block — so it can either retry the resolution path itself (T2
     * autonomy) or explain to the human what to do.
     */
    public String forAgentPrompt() {
        var sb = new StringBuilder();
        sb.append("Action denied (").append(code).append("): ").append(reason).append(".");
        if (remediation != null && !remediation.isBlank()) {
            sb.append("\n  ").append(remediation);
        }
        if (inWorldResolution != null) {
            sb.append("\n  To resolve in-world, file a request_access action with:");
            sb.append("\n    source: ").append(inWorldResolution.source());
            sb.append("\n    scope:  ").append(inWorldResolution.scope());
            sb.append("\n    reason: ").append(inWorldResolution.suggestedReason());
            sb.append("\n  The steward will see this on their Board and may approve.");
        }
        return sb.toString();
    }
}
