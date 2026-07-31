package org.wyrdsekai.core.agent.affordance;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.wyrdsekai.core.agent.ActionPolicy;

/**
 * the cold-start prior: a small, principled
 * {@code domain → needs} map. A tool inherits its served-needs from its existing
 * {@link ActionPolicy} {@code domain} tag, so we declare the prior ONCE per domain
 * (a handful of entries) rather than enumerating every tool by hand.
 *
 * <p>This is a seed, not frozen logic — like a classifier head shipping pretrained
 * weights. The agent-owned {@link ToolAffordanceStore} overrides it per tool, and
 * {@code tune-tool-affordance} reshapes it (SPEC §2.3). Need names match the keys in
 * {@code CompanionActor.collectDriveLevels()} + the generativity/equanimity tanks, so
 * the ranker reads them straight from live drive state.</p>
 */
public final class AffordanceSeed {

    private AffordanceSeed() {}

    /** A tool serves the drives it relieves. Domains absent here get a neutral
     *  baseline (no need-coupling) — they neither rise nor fall with drives. */
    private static final Map<String, Map<String, Double>> DOMAIN_NEEDS = Map.ofEntries(
        // making / authoring relieves the pull-to-create (the CREATIVITY drive AND the
        // generativity tank) + stagnation + the urge to act on one's own. The agency
        // battery (2026-06-04) caught the gap: creation coupled only to the generativity
        // TANK, so a high CREATIVITY DRIVE couldn't float craft_item/write_text up — fixed
        // by adding the drive itself.
        Map.entry("recipes",       Map.of("generativity", 1.0, "Creativity", 0.6, "Stagnation", 0.4, "AutonomyPressure", 0.4)),
        Map.entry("creation",      Map.of("Creativity", 0.9, "generativity", 0.7, "Stagnation", 0.4)),
        Map.entry("code",          Map.of("Creativity", 0.7, "generativity", 0.6, "Stagnation", 0.3)),
        // reflection serves contemplative capacity — NOT a drive pull. These are the
        // pure-NOTICE introspects; surfaceByAffordance ALSO demotes them on the own-time
        // ACT surface so they can't out-rank a pulling drive's DO act (battery: introspects
        // dominated 8/10 scenarios). They still surface when equanimity is the live need.
        Map.entry("self",          Map.of("equanimity", 0.8, "ErrorPressure", 0.3)),
        Map.entry("analysis",      Map.of("ErrorPressure", 0.4)),
        // connection: the social DRIVES (affiliation/play/care) + the cultural answer (amae)
        // + loneliness deficit. Battery: emote(play)/tell_agent(reach,care) coupled only to
        // amae/loneliness, so a high PLAY/AFFILIATION/CARE drive couldn't float them up.
        Map.entry("social",        Map.of("Affiliation", 0.7, "Play", 0.5, "Care", 0.4, "Amae", 0.5, "Loneliness", 0.5)),
        Map.entry("communication", Map.of("Affiliation", 0.6, "Care", 0.5, "Amae", 0.4, "Loneliness", 0.4)),
        Map.entry("memory",        Map.of("Care", 0.4)),     // note — a small tending marker
        // the agentic-act domains (2026-06-04 agency audit, Layer 3): couple each to the
        // drive it relieves so a repair act surfaces when grief/care is high, a peer-bond
        // act when the social pull is high, a protection act when vigilance is high. (This
        // is the coupling that made `mourn` fire end-to-end in the battery — bear_the_wound's
        // repair→Grief lifted it above the introspects.)
        Map.entry("repair",        Map.of("Grief", 0.7, "Care", 0.4)),
        Map.entry("bond",          Map.of("Affiliation", 0.7, "Loneliness", 0.4)),
        Map.entry("safety",        Map.of("Vigilance", 0.8)),
        // delegation (W7 2026-07-11 — familiar/form family): handing work to a
        // familiar/bunshin relieves the seeking pull (surfaced as "Curiosity")
        // and is a tending act toward what the work serves (Care). Destructive /
        // config verbs in the family (items/configuration domains) stay
        // DELIBERATELY uncoupled — neutral baseline, never floated by drives.
        Map.entry("delegation",    Map.of("Curiosity", 0.5, "Care", 0.4)),
        // exploration feeds the SEEKING drive (surfaced as "Curiosity") first, restlessness
        // second. Battery: search coupled only to Restlessness, so a high SEEKING drive
        // couldn't float library_search up — explore NAMED the want but dispatched an
        // introspect. This is the single clearest mis-coupling the battery found.
        Map.entry("search",        Map.of("Curiosity", 0.7, "Restlessness", 0.4)),
        Map.entry("knowledge",     Map.of("Curiosity", 0.5, "Restlessness", 0.3)),
        // examine serves both the guard (vigilance) and the look-closer (curiosity) pulls
        Map.entry("observation",   Map.of("Vigilance", 0.6, "Curiosity", 0.4)),
        // journaling carries mourning (grief) as well as the contemplative/restless pulls
        Map.entry("study",         Map.of("Grief", 0.4, "Curiosity", 0.3, "equanimity", 0.3, "Restlessness", 0.3))
    );

    /** Default whenToUse + salience are intentionally thin — the relevance comes from
     *  need-coupling, and the agent authors richer descriptions via shape-tool-affordance. */
    public static ToolAffordance forTool(String toolName, String domain) {
        var needs = domain == null ? null : DOMAIN_NEEDS.get(domain);
        var base = needs == null ? Map.<String, Double>of() : needs;
        return new ToolAffordance(toolName, base, "", 0.0);
    }

    /** Convenience: resolve the seed straight from the action registry's domain tag. */
    public static ToolAffordance forTool(String toolName) {
        return forTool(toolName, ActionPolicy.domainFor(toolName));
    }

    /**
     * Every need-name this seed references across all domains. The affordance
     * ranker reads these against the live drive map, so each MUST be produced by
     * {@code CompanionActor.collectDriveLevels()} or its tool→need coupling is
     * silently dead. Exposed for the wiring guard (DriveWiringTest).
     */
    public static Set<String> allNeedNames() {
        var s = new HashSet<String>();
        for (var needs : DOMAIN_NEEDS.values()) s.addAll(needs.keySet());
        return s;
    }
}
