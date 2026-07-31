package org.wyrdsekai.core.governance;

import java.util.List;
import java.util.Map;

/**
 * Ostrom Commons Governance (§34).
 * Elinor Ostrom's 8 design principles for managing common-pool resources,
 * mapped to Wyrdsekai world interactions.
 *
 * Each principle has a world-level check that zones must satisfy
 * to be considered well-governed.
 */
public class OstromGovernance {

    /** An Ostrom principle with its world manifestation. */
    public record Principle(
        int number,
        String name,
        String description,
        String worldManifestation,
        ComplianceCheck check
    ) {}

    /** Result of checking a zone's compliance with a principle. */
    public record ComplianceResult(int principleNumber, boolean compliant, String details) {}

    /** Functional interface for compliance checking. */
    @FunctionalInterface
    public interface ComplianceCheck {
        ComplianceResult check(ZoneContext context);
    }

    /** Zone context for compliance evaluation. */
    public record ZoneContext(
        String zoneId,
        int memberCount,
        boolean hasBoundaries,         // P1: clear membership
        boolean hasLocalRules,          // P2: zone-specific rules
        boolean hasCollectiveChoice,    // P3: council/voting
        boolean hasMonitoring,          // P4: warden/engine room
        boolean hasGraduatedSanctions,  // P5: tiered sanctions
        boolean hasConflictResolution,  // P6: dispute process
        boolean hasMinimalExternalAuth, // P7: self-governance
        boolean hasNestedEnterprises    // P8: multi-zone federation
    ) {}

    /** The 8 Ostrom principles, mapped to Wyrdsekai. */
    public static final List<Principle> PRINCIPLES = List.of(
        new Principle(1, "Clear Boundaries",
            "Clearly defined boundaries for the resource and its users",
            "Ward system + zone membership. Only registered entities can access zone resources.",
            ctx -> new ComplianceResult(1, ctx.hasBoundaries(),
                ctx.hasBoundaries() ? "Ward system active" : "No membership boundaries")),

        new Principle(2, "Congruent Rules",
            "Rules match local conditions and needs",
            "Zone-specific room scripts + configurable ward rules.",
            ctx -> new ComplianceResult(2, ctx.hasLocalRules(),
                ctx.hasLocalRules() ? "Zone has custom rules" : "Using only defaults")),

        new Principle(3, "Collective-Choice Arrangements",
            "Those affected by rules can participate in modifying them",
            "Council Chamber voting on proposals. All members can participate.",
            ctx -> new ComplianceResult(3, ctx.hasCollectiveChoice(),
                ctx.hasCollectiveChoice() ? "Council active" : "No collective governance")),

        new Principle(4, "Monitoring",
            "Monitors are accountable to the resource users",
            "Warden patrols + Engine Room metrics. Observable by all members.",
            ctx -> new ComplianceResult(4, ctx.hasMonitoring(),
                ctx.hasMonitoring() ? "Warden + Engine Room active" : "No monitoring")),

        new Principle(5, "Graduated Sanctions",
            "Sanctions increase with severity of violation",
            "ModerationService: WARNING → MUTE → BAN progression.",
            ctx -> new ComplianceResult(5, ctx.hasGraduatedSanctions(),
                ctx.hasGraduatedSanctions() ? "Graduated sanctions configured" : "No sanctions")),

        new Principle(6, "Conflict Resolution",
            "Low-cost, accessible conflict resolution mechanisms",
            "Report system + Council appeal process.",
            ctx -> new ComplianceResult(6, ctx.hasConflictResolution(),
                ctx.hasConflictResolution() ? "Report + appeal available" : "No dispute process")),

        new Principle(7, "Minimal External Authority",
            "Self-governance rights recognized by external authorities",
            "Federation respects zone autonomy. No external zone can override local rules.",
            ctx -> new ComplianceResult(7, ctx.hasMinimalExternalAuth(),
                ctx.hasMinimalExternalAuth() ? "Zone self-governance recognized" : "External authority dominates")),

        new Principle(8, "Nested Enterprises",
            "Governance activities organized at multiple levels",
            "Household → Zone → Federation. Each level has appropriate governance.",
            ctx -> new ComplianceResult(8, ctx.hasNestedEnterprises(),
                ctx.hasNestedEnterprises() ? "Multi-level governance active" : "Single-level only"))
    );

    /** Evaluate all principles for a zone. */
    public static List<ComplianceResult> evaluate(ZoneContext context) {
        return PRINCIPLES.stream()
            .map(p -> p.check().check(context))
            .toList();
    }

    /** Count compliant principles. */
    public static int complianceScore(ZoneContext context) {
        return (int) evaluate(context).stream().filter(ComplianceResult::compliant).count();
    }

    /** Check if a zone is well-governed (≥6/8 principles met). */
    public static boolean isWellGoverned(ZoneContext context) {
        return complianceScore(context) >= 6;
    }

    /** Human-readable compliance report. */
    public static String report(ZoneContext context) {
        var results = evaluate(context);
        var sb = new StringBuilder("=== Ostrom Governance Report ===\n");
        sb.append("Zone: ").append(context.zoneId()).append("\n\n");

        for (var result : results) {
            var principle = PRINCIPLES.get(result.principleNumber() - 1);
            var mark = result.compliant() ? "[+]" : "[-]";
            sb.append(mark).append(" P").append(result.principleNumber())
                .append(": ").append(principle.name())
                .append(" — ").append(result.details()).append("\n");
        }

        int score = (int) results.stream().filter(ComplianceResult::compliant).count();
        sb.append("\nScore: ").append(score).append("/8");
        if (score >= 6) sb.append(" — Well-governed");
        else sb.append(" — Needs improvement");

        return sb.toString();
    }
}
