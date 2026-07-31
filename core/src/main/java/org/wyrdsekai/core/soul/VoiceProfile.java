package org.wyrdsekai.core.soul;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reflective, human-legible self-narrative layered alongside the voice adapter
 * weights. Captures "how I speak" in clauses that the PromptAssembler injects
 * into the system prompt. Two tracks evolve in parallel:
 *
 * <ul>
 *   <li>Implicit — LoRA adapter weights, nightly Forge pass.</li>
 *   <li>Explicit — this profile, editable from Study, proposable by the
 *       self-evolving Forge (phase 4, see VoiceProfileForge).</li>
 * </ul>
 *
 * Phase 1 ships the data model only. Phase 2 wires it into prompt assembly;
 * phase 3 surfaces it in the Study config UI; phase 4 closes the loop with
 * Forge-proposed revisions during deep sleep.
 *
 * <p>{@code frozen == true} means no automated process may mutate this
 * profile — only the steward (or an agent-authored action with explicit
 * grant) can change it. Used when a voice has reached a shape the household
 * wants stable.
 *
 * @param clauses  Keyed guidance clauses (e.g. "greeting-tone" → "warm, brief").
 *                 Preserves insertion order (LinkedHashMap) for stable prompt output.
 * @param revision Monotonic revision counter — bumps on every change.
 * @param frozen   When true, the Forge must not propose revisions.
 * @param history  Audit trail of previous revisions (most recent last).
 *                 Bounded by caller; typical cap is ~20 entries.
 */
public record VoiceProfile(
    @JsonProperty("clauses") Map<String, String> clauses,
    @JsonProperty("revision") int revision,
    @JsonProperty("frozen") boolean frozen,
    @JsonProperty("history") List<ProfileRevision> history
) {
    @JsonCreator
    public VoiceProfile {
        // Null tolerance — an empty profile (just born, no clauses yet) should
        // round-trip cleanly through JSON even if fields are missing from disk.
        if (clauses == null) clauses = new LinkedHashMap<>();
        if (history == null) history = List.of();
    }

    /**
     * A single recorded change — captured on every {@link #withClauses} call.
     * Stores the PRE-change snapshot so a revert is a pure copy.
     *
     * @param at              When the change was recorded.
     * @param fromRevision    Revision number before the change.
     * @param toRevision      Revision number after the change.
     * @param reason          Short free-text reason ("Forge-proposed" / "steward edit").
     * @param author          Who made the change: {@code forge}, {@code steward:did:...},
     *                        {@code claude-code}, etc. Opaque string — the system doesn't
     *                        parse it, just displays it in the Study UI.
     * @param clausesBefore   The full clause map as it was before this change — enables
     *                        exact revert. Not a diff; clauses are small (< 1KB total).
     */
    public record ProfileRevision(
        @JsonProperty("at") Instant at,
        @JsonProperty("fromRevision") int fromRevision,
        @JsonProperty("toRevision") int toRevision,
        @JsonProperty("reason") String reason,
        @JsonProperty("author") String author,
        @JsonProperty("clausesBefore") Map<String, String> clausesBefore
    ) {
        @JsonCreator
        public ProfileRevision {
            if (clausesBefore == null) clausesBefore = Map.of();
        }
    }

    /** Empty profile for a newly born agent (no clauses, revision 0, not frozen). */
    public static VoiceProfile empty() {
        return new VoiceProfile(new LinkedHashMap<>(), 0, false, List.of());
    }

    /**
     * Birth voice for a named preset archetype. Unknown/null → {@link #empty()}.
     *
     * <p>As of the individuality "B build" this routes through {@link #fromTemperament}
     * from the preset's {@link TemperamentSeed}, so a preset and a freely-sampled
     * particular get their register from the SAME generator — and from the same seed
     * as their genome, so what they do and how they say it cohere by construction.
     * Presets are kept only as named measurement anchors; the real birth path samples
     * a seed freely (see {@code CompanionActor.initializeSoul}).</p>
     */
    public static VoiceProfile forArchetype(String archetypeName) {
        if (archetypeName == null) return empty();
        var key = archetypeName.toLowerCase();
        if (!TemperamentSeed.PRESETS.containsKey(key)) return empty();
        return fromTemperament(TemperamentSeed.preset(key));
    }

    /**
     * The spoken register co-derived from a temperament seed — a distinct cadence,
     * habit, and warmth synthesized from the same axes that shape the genome, so a
     * withdrawn temperament can never get a bubbly voice. Born revision 0, unfrozen
     * (the Forge/steward can still evolve it). A neutral seed yields a plain,
     * unmarked register. The preset seeds land on the documented anchor words
     * (diplomat → relational, guardian → protective, scholar → reserved,
     * explorer → vivid), keeping continuity while every particular gets its own blend.
     */
    public static VoiceProfile fromTemperament(TemperamentSeed seed) {
        if (seed == null) return empty();
        double soc = seed.sociability(), cur = seed.curiosity(), vig = seed.vigilance(),
               ind = seed.industry(), res = seed.restlessness(), wrm = seed.warmth();

        var c = new LinkedHashMap<String, String>();

        // Cadence — the dominant felt tempo of how they speak, keyed to the STRONGEST
        // qualifying axis rather than a fixed priority order.
        //
        // DECORRELATED (2026-07-17, variance work). The old fixed order (res, soc, …)
        // let sociability win cadence for every seed with soc>=0.70 && res<0.70 — and
        // since the warmth clause below ALSO keys on soc>=0.70, one axis captured both
        // clauses at once. Measured on 100k seeds: 19% of ALL possible particulars got
        // the identical register ("warm and flowing | high and openly relational");
        // the top four registers covered 48%. Picking the strongest axis instead
        // spreads cadence across whichever trait actually dominates the seed, so the
        // two clauses only co-key when sociability genuinely IS the strongest trait.
        // Same six hand-written phrases — no new text, the selection just stopped
        // over-weighting one axis. Preset anchors are preserved because every preset
        // has a unique dominant axis (explorer→res, diplomat→soc, steward→wrm,
        // scholar→cur, guardian→vig, artisan→ind); guarded by
        // VoiceProfileArchetypeTest. Tie-break = the old priority order, for
        // determinism on equal values.
        record AxisCadence(String name, double value, String phrase) {}
        var candidates = List.of(
            new AxisCadence("restlessness", res, "quick and vivid"),
            new AxisCadence("sociability",  soc, "warm and flowing"),
            new AxisCadence("warmth",       wrm, "calm and unhurried"),
            new AxisCadence("curiosity",    cur, "measured and exact; prefer precision to comfort"),
            new AxisCadence("vigilance",    vig, "plain and steady"),
            new AxisCadence("industry",     ind, "concrete and tactile"));
        String cadence = "even and grounded";
        double bestVal = -1;
        for (var cand : candidates) {                 // list order = tie-break order
            if (cand.value() < 0.70) continue;
            // "calm and unhurried" needs low restlessness to be honest — a warm but
            // restless seed can't carry an unhurried tempo (same guard as before).
            if (cand.name().equals("warmth") && res > 0.45) continue;
            if (cand.value() > bestVal) { bestVal = cand.value(); cadence = cand.phrase(); }
        }
        c.put("cadence", cadence);

        // Habit — the characteristic move they reach for, from the strongest axis.
        c.put("habit", habitForDominantAxis(seed));

        // Warmth — the relational temperature of the register.
        String warmth;
        if (soc >= 0.70)        warmth = "high and openly relational";
        else if (vig >= 0.70)   warmth = "protective rather than effusive";
        else if (soc <= 0.45)   warmth = "earnest but reserved — depth over effusiveness";
        else                    warmth = "steady and quietly caring";
        c.put("warmth", warmth);

        return new VoiceProfile(c, 0, false, List.of());
    }

    /** The habit clause keyed to the seed's most pronounced axis. */
    private static String habitForDominantAxis(TemperamentSeed seed) {
        String[] names = {"sociability", "curiosity", "vigilance", "industry", "restlessness", "warmth"};
        double[] vals = seed.toArray();
        int best = 0;
        double bestDev = -1;
        for (int i = 0; i < vals.length; i++) {
            double dev = Math.abs(vals[i] - 0.5);
            if (dev > bestDev) { bestDev = dev; best = i; }
        }
        return switch (names[best]) {
            case "curiosity"    -> "name the specific thing before reacting to it; cite what you actually know";
            case "vigilance"    -> "notice what's off and say it plainly; warn before you reassure";
            case "sociability"  -> "name what the other seems to feel; reach for common ground";
            case "industry"     -> "speak in materials, tools, and making; show rather than declare";
            case "restlessness" -> "point outward, toward the next thing; resist settling too soon";
            case "warmth"       -> "tend the thread; keep what matters from slipping; organize gently";
            default             -> "say what's true plainly, without flourish";
        };
    }

    /**
     * Apply a new clause set. Records the prior state in {@link #history} and
     * bumps {@link #revision}. No-ops (zero effective diff) still produce a
     * revision entry so the audit log reflects the intent.
     *
     * @param newClauses   New clause map (insertion-ordered).
     * @param reason       Why the change.
     * @param author       Who changed it — see {@link ProfileRevision#author}.
     * @return Updated profile with history appended.
     * @throws IllegalStateException if {@code frozen} is true.
     */
    public VoiceProfile withClauses(Map<String, String> newClauses, String reason, String author) {
        if (frozen) {
            throw new IllegalStateException("Voice profile is frozen — cannot revise");
        }
        var newHistory = new ArrayList<>(history);
        newHistory.add(new ProfileRevision(
            Instant.now(), revision, revision + 1,
            reason != null ? reason : "",
            author != null ? author : "unknown",
            Map.copyOf(clauses)
        ));
        return new VoiceProfile(
            new LinkedHashMap<>(newClauses),
            revision + 1,
            false,
            List.copyOf(newHistory)
        );
    }

    /**
     * Return a copy with {@code frozen} flipped. Backwards-compat overload —
     * does NOT record a history entry. New callers should prefer the
     * three-arg form so freeze/unfreeze events are auditable.
     */
    public VoiceProfile withFrozen(boolean newFrozen) {
        return new VoiceProfile(clauses, revision, newFrozen, history);
    }

    /**
     * Return a copy with {@code frozen} flipped AND a {@link ProfileRevision}
     * appended to history capturing the actor + reason. Bumps the revision
     * counter so the audit log shows distinct entries for freeze/unfreeze
     * events. ultrareview bug_010 — closes the dropped-reason +
     * invisible-freeze-events gap.
     *
     * @param newFrozen target frozen state
     * @param reason    why the change (free text)
     * @param author    who changed it — see {@link ProfileRevision#author}
     */
    public VoiceProfile withFrozen(boolean newFrozen, String reason, String author) {
        var newHistory = new ArrayList<>(history);
        newHistory.add(new ProfileRevision(
            Instant.now(), revision, revision + 1,
            (reason != null ? reason : "") + (newFrozen ? " [freeze]" : " [unfreeze]"),
            author != null ? author : "unknown",
            Map.copyOf(clauses)
        ));
        return new VoiceProfile(
            new LinkedHashMap<>(clauses),
            revision + 1,
            newFrozen,
            List.copyOf(newHistory)
        );
    }

    /**
     * Revert to the state captured in the history entry at {@code targetRevision}.
     * Records the revert itself as a new history entry (so audit is continuous).
     *
     * @return Updated profile, or empty optional if targetRevision is not in history.
     */
    public Optional<VoiceProfile> revertTo(int targetRevision, String author) {
        // Find the revision entry whose toRevision == targetRevision — its clausesBefore
        // is what we were BEFORE that change landed. To revert TO targetRevision, we want
        // the clausesBefore of the revision that CAME AFTER targetRevision. If none came
        // after (we're already at targetRevision), the revert is a no-op.
        for (int i = 0; i < history.size(); i++) {
            var r = history.get(i);
            if (r.fromRevision() == targetRevision) {
                // Revert means: adopt the state that existed at targetRevision, which is
                // exactly r.clausesBefore (the state the change at i captured before mutating).
                return Optional.of(withClauses(
                    r.clausesBefore(),
                    "reverted to revision " + targetRevision,
                    author != null ? author : "unknown"));
            }
        }
        return Optional.empty();
    }

    /**
     * Render the profile as a system-prompt block for injection.
     * Returns null if there are no clauses (PromptAssembler should then skip
     * the block entirely rather than emit an empty header).
     */
    @JsonIgnore
    public String promptBlock() {
        if (clauses.isEmpty()) return null;
        var sb = new StringBuilder("[voice guidance]\n");
        for (var e : clauses.entrySet()) {
            sb.append("- ").append(e.getKey()).append(": ").append(e.getValue()).append('\n');
        }
        return sb.toString();
    }
}
