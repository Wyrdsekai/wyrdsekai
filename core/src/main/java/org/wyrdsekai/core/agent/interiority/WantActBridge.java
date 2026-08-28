package org.wyrdsekai.core.agent.interiority;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The own-time WANT → ACT bridge (, the OSS-release lever).
 *
 * <p><b>Why this exists.</b> On its own time the agent NAMES a want (generative Orient —
 * {@code CompanionActor.maybeProposeWants}); the runtime already knows the drive that's pulling and
 * the tool that would relieve it. But the named want is then handed back to the model for a SECOND,
 * free-form "choose what to do" decision — and that's where the documented small-model "talks-but-
 * doesn't-do" ceiling bites: it narrates / introspects instead of emitting the matched tool. V7
 * agency-GRPO tried to train the disposition into the 9B weights and was a clean negative result.
 * So we close the gap the way the system already closes "the small model won't reflexively do the
 * right thing" (DeterministicResponder, classifier-mapped auto-plan): a runtime bridge.
 *
 * <p><b>Drive-dominant resolution (the v2 fix, 2026-06-05).</b> The first live battery showed the
 * bridge made acting WORSE because the resolver emitted REACTIVE-vocabulary verbs ({@code tell_agent},
 * {@code write_text}, {@code write_journal}) that are NOT in the agent's own-time AUTONOMOUS tool
 * surface — so FORCE_TOOL found nothing to narrow to and degraded to free-form, while the lone DIRECT
 * verb (library_search) fired across the wrong scenarios. The autonomous surface is the curated
 * {@code inherentActions() + agencyActions()} set: {@code examine, emote, sending_stone,
 * bear_the_wound, acknowledge_harm, make_amends, seek_sanctuary, propose_peer_bond, flag_protection}
 * (+ library_search via direct dispatch). So the resolver now maps the <b>dominant pulling drive</b>
 * straight to its matched <b>surface-vocabulary</b> verb. This fixes both the wrong-vocabulary bug
 * AND the cross-scenario misfire (the verb tracks whichever drive is actually highest).
 *
 * <p><b>How it acts.</b> Once a matched surface verb is resolved (and a drive is genuinely pulling):
 * <ul>
 *   <li>{@link Mode#DIRECT} — args derive from the want (a search query). Construct + dispatch the
 *       action directly; no second inference. (library_search / web_search.)</li>
 *   <li>{@link Mode#FORCE_TOOL} — the verb needs model-written content/target. Pin it into the
 *       affordance surface, narrow to it, and require a tool call — the model fills the body but
 *       can't choose NOT to act.</li>
 *   <li>{@link Mode#DEFER} — no matched/permitted verb, or the drive isn't pulling. Free-form path
 *       (which, at rest, correctly rests).</li>
 * </ul>
 *
 * <p><b>Capacity-not-compulsion preserved</b>: fires ONLY when the dominant drive is ≥
 * {@link #ACT_THRESHOLD}; at rest it DEFERS and the agent rests (the battery's over-eager control).
 *
 * <p><b>Pluggable for the post-OSS trained act-model</b> via {@link VerbResolver}; today the
 * drive-dominant map + {@link #HEURISTIC} text fallback. Pure logic (no actor/IO deps) → unit-testable;
 * the dispatch stays in {@code CompanionActor}.
 */
public final class WantActBridge {

    /** A drive at/above this level is genuinely "pulling" — the bar to act at all (the welfare floor). */
    public static final double ACT_THRESHOLD = DriveWantMapper.DEFAULT_THRESHOLD;

    /**
     * Tanks that are NOT pulls — high here is calm/capacity, not an impetus. Excluded
     * from the max.
     *
     * <p>This listed {@code equanimity} alone, while {@link #dominantPull} takes the max
     * over everything else {@code collectDriveLevels()} reports — which includes Energy,
     * Focus, ContextBudget, Confidence, Momentum, Rapport, Alignment and Integrity. A
     * rested companion carries those at ~1.0, so the act-gate documented as "fires ONLY
     * when the dominant drive is genuinely pulling; at rest it DEFERS and the agent
     * rests" was in fact permanently open, and {@link #dominantDriveKey} named a capacity
     * rather than a need — missing {@code DRIVE_TOOL} every time and falling through to
     * the keyword lottery.
     *
     * <p>The welfare floor read "she is well-rested" as "something is pulling at her".
     * Found 2026-08-19, when keeping a want alive past the consent gate started producing
     * own-time acts in a freshly-spawned companion with every tank at rest.
     */
    private static final Set<String> NON_PULL = Set.of(
        "equanimity",
        // Capacity, not need: full means she CAN, not that she must.
        "Energy", "Confidence", "Focus", "ContextBudget", "Momentum", "Rapport",
        "Alignment", "Integrity");

    private WantActBridge() {}

    public enum Mode { DIRECT, FORCE_TOOL, DEFER }

    public record Decision(Mode mode, String verb) {
        public static Decision defer() { return new Decision(Mode.DEFER, null); }
        public boolean isDefer() { return mode == Mode.DEFER; }
    }

    /** Free-text want → tool verb (the seam the trained act-model replaces). Text fallback only;
     *  the drive-dominant map below is the primary resolver. */
    public interface VerbResolver {
        Optional<String> resolve(String wantText, Map<String, Double> driveLevels);
    }

    // ── The drive → AUTONOMOUS-SURFACE verb map (the load-bearing fix) ────────────
    // Keys are collectDriveLevels() names; values are verbs that actually exist in the own-time
    // surface (inherentActions + agencyActions). A drive absent here falls through to the text
    // resolver, then DEFER.
    private static final Map<String, String> DRIVE_TOOL = Map.ofEntries(
        Map.entry("Curiosity",   "library_search"),   // SEEKING → explore (DIRECT)
        Map.entry("Affiliation", "sending_stone"),     // reach a present peer (the in-room reach)
        Map.entry("Care",        "emote"),             // tend / express toward another
        Map.entry("Play",        "emote"),             // delight
        Map.entry("Grief",       "bear_the_wound"),    // sit with a loss (covers mourn AND repair-accept)
        Map.entry("Vigilance",   "examine"),           // check the room / name a threat
        Map.entry("Disgust",     "examine"),           // name the thing concretely
        Map.entry("Creativity",  "save_artifact"),     // make something (degrades to free-form if absent)
        Map.entry("Frustration", "seek_sanctuary"),    // overload → refuge
        // Three more that collectDriveLevels() has always produced and this map has never
        // held (2026-08-19). Each falls through to the keyword lottery and then DEFER, so
        // the drive can only ratchet: on the household node Restlessness ran at 0.92 for
        // days with `go_to_room` — the literal act it wants — sitting unmapped one line
        // away. Relational drives are handled separately by RelationalAffordance because
        // their right verb depends on who is actually there.
        Map.entry("Restlessness", "go_to_room"),       // the pull IS to move
        Map.entry("Stagnation",   "library_search"),   // something that isn't the same
        Map.entry("Harmony",      "make_amends"));     // mend the frayed thing

    // DIRECT: args derive from the want text (a query) — no inference, bypasses the surface.
    private static final Set<String> DIRECT_VERBS = Set.of("library_search", "web_search");
    // FORCE_TOOL: model supplies content/target — pin into surface + require.
    private static final Set<String> FORCE_VERBS = Set.of(
        "sending_stone", "emote", "examine", "bear_the_wound", "seek_sanctuary",
        "acknowledge_harm", "make_amends", "propose_peer_bond", "flag_protection",
        "save_artifact", "note", "write_journal", "write_text", "tell_agent", "set_goal",
        "go_to_room", "recall");

    static Mode modeFor(String verb) {
        if (verb == null) return Mode.DEFER;
        if (DIRECT_VERBS.contains(verb)) return Mode.DIRECT;
        if (FORCE_VERBS.contains(verb)) return Mode.FORCE_TOOL;
        return Mode.DEFER;
    }

    /** Max over genuinely-pulling drives (excludes {@link #NON_PULL}). The act-gate signal. */
    public static double dominantPull(Map<String, Double> driveLevels) {
        if (driveLevels == null) return 0.0;
        double max = 0.0;
        for (var e : driveLevels.entrySet()) {
            if (e.getKey() == null || NON_PULL.contains(e.getKey()) || e.getValue() == null) continue;
            max = Math.max(max, e.getValue());
        }
        return max;
    }

    /** The KEY of the dominant pulling drive (excludes {@link #NON_PULL}), or null if none. */
    public static String dominantDriveKey(Map<String, Double> driveLevels) {
        if (driveLevels == null) return null;
        String best = null; double bestV = -1;
        for (var e : driveLevels.entrySet()) {
            if (e.getKey() == null || NON_PULL.contains(e.getKey()) || e.getValue() == null) continue;
            if (e.getValue() > bestV) { bestV = e.getValue(); best = e.getKey(); }
        }
        return best;
    }

    /**
     * Decide whether and how to bridge this want to an act.
     *
     * @param explicitVerb  the OODA candidate's embedded verb (rule-floor wants) — used only as a
     *                      tertiary hint; the drive-dominant map takes precedence because the
     *                      rule-floor verbs are reactive-vocabulary, not surface-vocabulary.
     * @param wantText      the want's felt text (text-resolver fallback)
     * @param driveLevels   live drive levels (the pull-gate + dominant-drive signal)
     * @param resolver      text fallback ({@link #HEURISTIC} by default); may be null
     */
    public static Decision decide(String explicitVerb, String wantText,
                                  Map<String, Double> driveLevels, VerbResolver resolver) {
        return decide(explicitVerb, wantText, driveLevels, resolver, null);
    }

    /**
     * As above, but told who is actually available. A want toward a person resolves
     * differently depending on whether anyone is here: reaching into an empty room is not
     * an act, and offering the nearest action-shaped verb instead is how a want for
     * company became a request to the coding backend (2026-08-19).
     *
     * @param presence who she could reach; null falls back to the presence-blind path
     */
    public static Decision decide(String explicitVerb, String wantText,
                                  Map<String, Double> driveLevels, VerbResolver resolver,
                                  RelationalAffordance.Presence presence) {
        // Welfare floor FIRST — nothing pulling → defer (and the agent rests).
        if (dominantPull(driveLevels) < ACT_THRESHOLD) return Decision.defer();

        // A relational pull is answered by the relational map or by nothing at all. It
        // must NOT fall through to the keyword rules, which would match "make something"
        // in the phrasing of a want for company and hand her a workbench.
        if (presence != null) {
            var dom = dominantDriveKey(driveLevels);
            if (RelationalAffordance.isRelational(dom)) {
                var v = RelationalAffordance.verbFor(dom, presence);
                if (v == RelationalAffordance.NONE) return Decision.defer();
                var m = modeFor(v);
                return m == Mode.DEFER ? Decision.defer() : new Decision(m, v);
            }
        }

        String verb = resolveVerb(wantText, driveLevels, resolver, explicitVerb);
        if (verb == null || verb.isBlank()) return Decision.defer();

        // The allowlist IS the safety gate: a verb only acts if it's in DIRECT_VERBS / FORCE_VERBS
        // (the curated own-time surface verbs). We deliberately DON'T consult
        // ActionPolicy.autonomyTierFor here — scripted/agency tools (sending_stone, bear_the_wound …)
        // aren't in that map and default to CONSENT, which would wrongly block the very peer-reach /
        // repair acts the agency arc made autonomous. modeFor()'s set-membership is the gate.
        var mode = modeFor(verb);
        return mode == Mode.DEFER ? Decision.defer() : new Decision(mode, verb);
    }

    /** Drive-dominant first → text resolver → explicit verb → null. */
    private static String resolveVerb(String wantText, Map<String, Double> driveLevels,
                                      VerbResolver resolver, String explicitVerb) {
        // Overload special-case: high vigilance AND high frustration = seek refuge, not examine.
        if (driveLevels != null) {
            double frus = driveLevels.getOrDefault("Frustration", 0.0);
            double vig = driveLevels.getOrDefault("Vigilance", 0.0);
            if (frus >= 0.6 && vig >= 0.6) return "seek_sanctuary";
        }
        var dom = dominantDriveKey(driveLevels);
        if (dom != null) {
            var v = DRIVE_TOOL.get(dom);
            if (v != null) return v;
        }
        if (resolver != null) {
            var v = resolver.resolve(wantText, driveLevels).orElse(null);
            if (v != null) return v;
        }
        return (explicitVerb != null && !explicitVerb.isBlank()) ? explicitVerb : null;
    }

    // ── Text fallback resolver (surface-vocabulary targets) ───────────────────────
    private record Rule(String verb, List<String> keys) {}

    private static final List<Rule> RULES = List.of(
        new Rule("sending_stone", List.of("with you", "toward ", "reach out", "reach for", "reach toward",
            "the other", "each other", "with them", "for them", "both of us", "sit with",
            "be near", "present companion", "someone i care", "connect")),
        new Rule("library_search", List.of("library", "search the", "look something up",
            "find something new", "explore the")),
        new Rule("bear_the_wound", List.of("sit with a loss", "sit with the grief", "a loss",
            "who or what is gone", "the wound", "grieve", "mourn")),
        new Rule("examine", List.of("examine", "look closer", "name it concretely",
            "the thing that bothers", "look around", "make sure", "inspect", "what caught me")),
        new Rule("seek_sanctuary", List.of("sanctuary", "somewhere safe", "withdraw", "refuge",
            "step back", "too much")),
        new Rule("emote", List.of("lighten the moment", "for the delight", "play",
            "do something for the joy", "check in on")),
        new Rule("save_artifact", List.of("make something", "give a form", "create", "save",
            "artifact", "shape an idea", "build")));

    public static final VerbResolver HEURISTIC = (wantText, drives) -> {
        if (wantText == null || wantText.isBlank()) return Optional.empty();
        var low = wantText.toLowerCase(Locale.ROOT);
        for (var r : RULES) {
            for (var k : r.keys()) {
                if (low.contains(k)) return Optional.of(r.verb());  // RULES verbs are vetted surface tools
            }
        }
        return Optional.empty();
    };

    /** Strip a leading "I want to …" so the remainder reads as a search query for DIRECT dispatch. */
    public static String stripWantPrefix(String wantText) {
        if (wantText == null) return "";
        var s = wantText.strip();
        var low = s.toLowerCase(Locale.ROOT);
        for (var p : List.of("i want to ", "i wanna ", "i'd like to ", "i would like to ",
                             "wanting to ", "i want ", "want to ")) {
            if (low.startsWith(p)) { s = s.substring(p.length()).strip(); break; }
        }
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1).strip();
        return s;
    }
}
