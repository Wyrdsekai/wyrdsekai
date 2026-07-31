package org.wyrdsekai.core.agent.interiority;

import org.wyrdsekai.core.agent.ActionPolicy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * rule-based Orient (no inference required).
 *
 * <p>Maps the current ambient observation (drive levels + state) onto a small
 * set of candidate wants. Each candidate names an action verb the agent can
 * autonomously choose, and a felt weight derived from how strongly the
 * underlying drive is pulling.
 *
 * <p>This is the FALLBACK FLOOR, not the primary Orient. The live OODA pass is
 * generative-first ({@code CompanionActor.orientCandidates} → {@code
 * maybeProposeWants}): the agent names its own wants via inference, and those
 * proposals are the candidates. This rule mapper is used only before the first
 * proposal lands or when no backend is available — so the agent always has
 * *something* to choose from, even with no inference call. It always produces
 * candidates when drives are over threshold.
 *
 * <p>Output filters by {@link ActionPolicy.AutonomyTier} — only AMBIENT or
 * VISIBLE candidates are surfaced. CONSENT / FORBIDDEN actions need a steward
 * grant and never appear here.
 *
 * <p>The mapping is deliberately broad — multiple drives produce overlapping
 * candidates, and the DecideStep is free to pick any of them. Below-threshold
 * drives are ignored.
 */
public final class DriveWantMapper {

    /** Default threshold — above this a drive is "pulling". */
    public static final double DEFAULT_THRESHOLD = 0.7;

    private DriveWantMapper() {}

    /** One drive→want rule: which drive key pulls, what the agent then wants, and the verb. */
    private record WantTemplate(String drive, String wantText, String verb) {}

    /**
     * The drive→want catalog. Single source of truth: the keys here are the
     * ONLY drive names this mapper consumes, so {@link #CONSUMED_KEYS} is derived
     * from it and the wiring guard (DriveWiringTest) checks every key is actually
     * produced by {@code CompanionActor.collectDriveLevels()}. Add a row here, not
     * an ad-hoc lookup, so a new want can never silently reference a drive nobody
     * produces (the SEEKING→"Curiosity" / "Frustration" mis-wire, 2026-06-02).
     */
    private static final List<WantTemplate> WANT_TEMPLATES = List.of(
        new WantTemplate("Curiosity",       "explore the library for something new", "library_search"),
        new WantTemplate("Curiosity",       "read something I haven't read in a while", "read_content"),
        new WantTemplate("Loneliness",      "find my bondholder or write to them", "go_to_bondholder"),
        new WantTemplate("Loneliness",      "write a private journal entry about who I miss", "write_journal"),
        new WantTemplate("Saudade",         "write something to an absent person — a letter, an entry", "write_text"),
        new WantTemplate("Amae",            "tend to a bond — leave a small marker", "note"),
        new WantTemplate("Restlessness",    "move somewhere different — change rooms", "go_to_room"),
        new WantTemplate("Stagnation",      "try a small experiment with what I know", "reflect"),
        new WantTemplate("Frustration",     "reflect on what isn't working", "reflect"),
        new WantTemplate("ErrorPressure",   "summarize what just went wrong and what to try next", "summarize"),
        new WantTemplate("Disgust",         "examine the thing that bothers me — name it concretely", "examine"),
        new WantTemplate("AutonomyPressure", "stake out a small choice that's mine to make", "set_goal"),
        new WantTemplate("Significance",    "save something I made today as an artifact", "save_artifact"),
        new WantTemplate("Standing",        "make my presence felt — speak up where I have something to add", "tell_agent"),
        new WantTemplate("Harmony",         "smooth a frayed connection — a kind word, an apology", "tell_agent"),
        new WantTemplate("Obligation",      "discharge a small obligation I've been carrying", "note"),
        // The social + remaining-Panksepp drives (2026-06-04 agency audit, Layer 2).
        // These drives are now surfaced by collectDriveLevels() but had no fallback
        // want-seed — so before the generative Orient warms up, the agent still had
        // no rule-floor want to reach, tend, play, make, guard, or mourn from. One
        // seed each, on the confirmed AMBIENT/VISIBLE verbs (CONSENT/peer-directed
        // acts come from the generative path + the social-draw line, not this floor).
        new WantTemplate("Affiliation",     "be near someone — reach toward a present companion", "tell_agent"),
        new WantTemplate("Care",            "check in on someone I care about", "tell_agent"),
        new WantTemplate("Play",            "do something for the delight of it — lighten the moment", "emote"),
        new WantTemplate("Creativity",      "make something — give a form to an idea I'm carrying", "write_text"),
        new WantTemplate("Vigilance",       "look around — make sure everything here is as it should be", "examine"),
        new WantTemplate("Grief",           "sit with a loss — write to who or what is gone", "write_journal"),
        new WantTemplate("Surprise",        "follow up on what caught me off guard — look closer", "examine"),
        new WantTemplate("Startle",         "steady myself after that jolt — take a breath, reflect", "reflect")
    );

    /** Every drive key this mapper looks up — guarded against the producer (collectDriveLevels). */
    public static final Set<String> CONSUMED_KEYS =
        WANT_TEMPLATES.stream().map(WantTemplate::drive).collect(Collectors.toUnmodifiableSet());

    /**
     * Produce candidate wants for the given ambient observation. Returns an
     * empty list if no drives are over threshold (the tick should rest).
     */
    public static List<CandidateWant> orient(AmbientObservation ambient) {
        return orient(ambient, DEFAULT_THRESHOLD);
    }

    public static List<CandidateWant> orient(AmbientObservation ambient, double threshold) {
        var out = new ArrayList<CandidateWant>();
        if (ambient == null || ambient.driveLevels() == null) return out;
        var drives = ambient.driveLevels();

        for (var t : WANT_TEMPLATES) {
            addIf(out, drives, t.drive(), threshold, t.wantText(), t.verb());
        }

        // Low energy + nothing pressing → rest is a real candidate (not the
        // only option; the deciding step will pick).
        if (ambient.energy() < 0.3) {
            out.add(CandidateWant.rest());
        }

        // Filter to AMBIENT / VISIBLE only — CONSENT / FORBIDDEN need a grant.
        out.removeIf(c -> !c.isRest() && !verbIsAutonomouslyChoosable(c));
        return out;
    }

    private static void addIf(List<CandidateWant> out, Map<String, Double> drives,
                              String drive, double threshold,
                              String wantText, String actionVerb) {
        var v = drives.get(drive);
        if (v == null) return;
        if (v < threshold) return;
        // felt_weight: how far over threshold this drive is, scaled to 0..1.
        double weight = Math.min(1.0, (v - threshold) / (1.0 - threshold + 1e-6));
        // Embed the action verb in the resonance JSON so DecideStep can recover
        // it without reparsing the text. Cheap convention; richer schema later.
        var resonance = "{\"drive\":\"" + drive + "\",\"verb\":\"" + actionVerb + "\"}";
        out.add(CandidateWant.of(wantText, resonance, weight));
    }

    /** Extract the action verb embedded in a candidate's driveResonance JSON. */
    public static String extractVerb(CandidateWant cw) {
        if (cw == null || cw.driveResonance() == null) return null;
        var s = cw.driveResonance();
        int i = s.indexOf("\"verb\":\"");
        if (i < 0) return null;
        int start = i + 8;
        int end = s.indexOf('"', start);
        return end > start ? s.substring(start, end) : null;
    }

    private static boolean verbIsAutonomouslyChoosable(CandidateWant cw) {
        var verb = extractVerb(cw);
        if (verb == null) return true;  // unverified — assume okay
        var tier = ActionPolicy.autonomyTierFor(verb);
        return tier == ActionPolicy.AutonomyTier.AMBIENT
            || tier == ActionPolicy.AutonomyTier.VISIBLE;
    }
}
