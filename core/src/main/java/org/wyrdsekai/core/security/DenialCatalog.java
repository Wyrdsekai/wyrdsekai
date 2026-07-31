package org.wyrdsekai.core.security;

import org.wyrdsekai.core.agent.ActionPolicy;

import java.util.Map;

/**
 * registry of canonical denial templates.
 *
 * <p>Keyed by stable {@link Denial#code()}. New gates that emit denials
 * should either:
 * <ol>
 *   <li>Use a code that already lives here (preferred — re-use existing
 *       remediation phrasing, consistent operator UX), OR</li>
 *   <li>Add a new entry in this file with a hint covering at least
 *       one of: {@code inWorldResolution} (preferred) or {@code cliHint}
 *       (fallback).</li>
 * </ol>
 *
 * <p>This file is the single place to update wording when a CLI surface
 * changes (e.g., when a {@code wyrd account reset} command lands and
 * we want all "credential" denials to point at it).
 */
public final class DenialCatalog {

    private DenialCatalog() {}

    // ── Codes ────────────────────────────────────────────────────────────

    /** Action requires a tier the agent hasn't earned. */
    public static final String CODE_TIER_GATED = "tier_gated";
    /** A PreAction shell hook returned exit 2. */
    public static final String CODE_HOOK_DENIED = "hook_denied";
    /** Caller lacks a Grant for the requested capability on the resource. */
    public static final String CODE_GRANT_REQUIRED = "grant_required";
    /** Ward (room/zone protection) blocked entry or interaction. */
    public static final String CODE_WARD_REJECTED = "ward_rejected";
    /** Fabricated credential / unauthorized token mint. */
    public static final String CODE_FABRICATED_CREDENTIAL = "fabricated_credential";
    /** Password / secret reset attempted without steward authority. */
    public static final String CODE_PASSWORD_RESET = "password_reset";
    /** Action rejected by autonomy policy (companion not yet trusted enough). */
    public static final String CODE_AUTONOMY_GATED = "autonomy_gated";

    // ── Templates ────────────────────────────────────────────────────────

    /**
     * Build a tier-gated denial. {@code actionDescription} is the verb
     * phrase from {@code ActionPolicy.describeAction}; {@code agentTier}
     * and {@code requiredTier} are integer tiers (0–3).
     */
    public static Denial tierGated(
            String actionType, String actionDescription,
            int agentTier, int requiredTier) {
        var reason = "I haven't earned the ability to " + actionDescription
            + " yet (tier " + agentTier + ", needs " + requiredTier + ").";
        var remediation = "This action unlocks at tier " + requiredTier
            + ". Bond depth and steward trust raise tier over time.";
        var template = Denial.RequestTemplate.forAccess(
            "wyrd:action/" + actionType,
            "use",
            "Steward, would you grant me a one-time pass for "
                + actionDescription + "? I'd use it for the current task.");
        return Denial.withInWorldResolution(
            CODE_TIER_GATED, reason, remediation, template);
    }

    /** Hook-denied with a reason string (and optional remediation hint from the hook). */
    public static Denial hookDenied(String hookCommand, String reasonFromHook,
                                     String remediationFromHook) {
        var reason = (reasonFromHook == null || reasonFromHook.isBlank())
            ? "A safety hook (" + hookCommand + ") blocked this action."
            : reasonFromHook;
        var remediation = (remediationFromHook == null || remediationFromHook.isBlank())
            ? "The hook configured by the steward says no. Ask the steward to relax it, or use the suggested resolution below."
            : remediationFromHook;
        return Denial.of(CODE_HOOK_DENIED, reason).withRemediation(remediation);
    }

    /**
     * Caller lacks a grant on a resource. {@code resourceUri} should be
     * the full {@code wyrd:...} URI; {@code capability} is read/write/use/
     * delegate/attest from.
     */
    public static Denial grantRequired(String resourceUri, String capability,
                                        String contextReason) {
        var reason = "Access to " + resourceUri + " requires the " + capability
            + " capability, which the caller doesn't hold.";
        var remediation = "Knock on the steward's Board to request the grant.";
        var template = Denial.RequestTemplate.forAccess(
            resourceUri, capability,
            contextReason != null ? contextReason
                : "Requesting " + capability + " on " + resourceUri);
        return Denial.withInWorldResolution(
            CODE_GRANT_REQUIRED, reason, remediation, template);
    }

    /** Ward blocked an entry/interaction. */
    public static Denial wardRejected(String roomId, String wardReason) {
        var reason = (wardReason == null || wardReason.isBlank())
            ? "A ward at " + roomId + " refused this action."
            : "Ward at " + roomId + ": " + wardReason;
        var template = Denial.RequestTemplate.forAccess(
            "wyrd:room/" + roomId, "enter",
            "Ward refused entry; requesting steward consent.");
        return Denial.withInWorldResolution(
            CODE_WARD_REJECTED, reason,
            "Knock to request entry from the room owner or zone steward.",
            template);
    }

    /** Fabricated/unauthorized credential mint attempt. */
    public static Denial fabricatedCredential(String what) {
        return Denial.withCliHint(
            CODE_FABRICATED_CREDENTIAL,
            "Refused to fabricate a credential for " + what + ".",
            "Credentials must be issued by the steward through the canonical CLI.",
            Map.of(
                "command", "wyrd relay register <wyrdrelay://...>",
                "alternative", "wyrd invite create <name>",
                "context", "Run from the joining node, with a steward-issued invite URL."));
    }

    /** Password / secret reset attempt. */
    public static Denial passwordReset(String username) {
        return Denial.withCliHint(
            CODE_PASSWORD_RESET,
            "Refused to reset credentials for '" + username + "' without steward authority.",
            "Steward must run the canonical reset command, which writes a new "
                + "bcrypt + audit-logs the reset.",
            Map.of(
                "command", "wyrd account reset " + username,
                "context", "Steward terminal only. Logged in audit_log."));
    }

    /**
     * Consent-axis autonomy denial (, wired via
     * {@code AutonomyGate}): an autonomous CONSENT-tier verb under strict
     * grants, or a FORBIDDEN-tier verb without an owner grant. The template
     * names the {@code home://{owner}/action/{verb}} resource so the
     * companion's {@code request_access} lands in the live GrantRequest
     * pipeline (Board approve mints the grant).
     */
    public static Denial autonomyGated(String actionType, String actionDescription,
            ActionPolicy.AutonomyTier tier, String ownerDid) {
        String reason;
        String remediation;
        if (tier == ActionPolicy.AutonomyTier.FORBIDDEN) {
            reason = "I can't " + actionDescription + " on my own initiative — "
                + "it's irreversible enough that it always needs my person's explicit ok.";
            remediation = "The owner can grant this verb for autonomous use "
                + "(that grant IS the ladder upgrade); human-directed use is unaffected.";
        } else {
            reason = "I'd need my person's ok before I " + actionDescription
                + " on my own initiative (this household requires consent grants).";
            remediation = "The owner approves the request at the Board, or sets "
                + "WYRDSEKAI_ACTION_STRICT_GRANTS=false to reopen CONSENT verbs by default.";
        }
        var resource = ownerDid != null
            ? "home://" + ownerDid + "/action/" + actionType
            : "wyrd:action/" + actionType;
        var template = Denial.RequestTemplate.forAccess(
            resource, "use",
            "Requesting permission to " + actionDescription
                + " autonomously when it serves the current goal.");
        return Denial.withInWorldResolution(
            CODE_AUTONOMY_GATED, reason, remediation, template);
    }

    /** Autonomy-policy denial (similar to tier_gated but specifically about ActionPolicy autonomy band). */
    public static Denial autonomyGated(String actionType, String currentBand, String requiredBand) {
        var reason = "Action " + actionType + " requires autonomy band '"
            + requiredBand + "'; current band is '" + currentBand + "'.";
        var template = Denial.RequestTemplate.forAccess(
            "wyrd:autonomy/" + actionType,
            "use",
            "Requesting one-time elevation for " + actionType + ".");
        return Denial.withInWorldResolution(
            CODE_AUTONOMY_GATED, reason,
            "Steward can elevate your autonomy band via the Hearth's Autonomy Console.",
            template);
    }

}
