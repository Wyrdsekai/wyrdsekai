package org.wyrdsekai.core.familiar;

import org.wyrdsekai.core.soul.BehavioralFingerprint;
import org.wyrdsekai.core.soul.SoulFragment;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Ingests familiar-system state into Forge outputs during sleep consolidation.
 *
 * <p> — the habit of form-making is soul-shaped. Workbench
 * events, familiar results, bunshin reports, and named-familiar accumulated
 * contexts are the inputs. The outputs are <strong>not</strong> the items
 * themselves — those are first-class data in FamilyLocker — but the soul's
 * <em>narrative self-knowledge</em> about those items: "I am a maker of small
 * intelligences for specific questions" (fragment), "I author a new form
 * every few days" (fingerprint), and first-person training corpus.</p>
 *
 * <p>Pure function: input state → {@link Result}. No side-effects. Caller
 * applies the result to the soul manifest during the sleep write path.</p>
 *
 * <h2>Training-corpus weighting (§12.3)</h2>
 * Forms with {@link #MIN_SUCCESS_RATIO} success ratio contribute to corpus
 * entries. Forms below are still journaled for the fragment channel (failure
 * is informative) but weighted lower. <strong>Retired forms</strong> (§14)
 * are excluded entirely from corpus generation — the agent explicitly chose
 * not to keep them.
 */
public final class FamiliarForgeIngester {

    /** Success ratio below which a form does not contribute to training corpus (§12.3). */
    public static final double MIN_SUCCESS_RATIO = 0.5;

    /** Minimum summons before a form is eligible for corpus generation. */
    public static final int MIN_CORPUS_SUMMONS = 3;

    /** Minimum bond charge for a named familiar to anchor a fragment. */
    public static final float NAMED_FRAGMENT_BOND_THRESHOLD = 0.35f;

    private FamiliarForgeIngester() {}

    /**
     * Input bundle. The ingester is stateless; callers assemble fresh batches
     * per sleep cycle.
     *
     * @param agentDid            subject of consolidation
     * @param activeForms         forms currently in the locker (not retired)
     * @param retiredFormNames    names of forms explicitly retired — excluded from corpus
     * @param namedFamiliars      persistent named familiars in the locker
     * @param bunshinReports      reports accumulated since last forge pass
     */
    public record Batch(
        String agentDid,
        List<ThoughtForm> activeForms,
        List<String> retiredFormNames,
        List<NamedFamiliar> namedFamiliars,
        List<BunshinReport> bunshinReports
    ) {
        public Batch {
            if (agentDid == null || agentDid.isBlank()) {
                throw new IllegalArgumentException("agentDid required");
            }
            activeForms = activeForms == null ? List.of() : List.copyOf(activeForms);
            retiredFormNames = retiredFormNames == null ? List.of() : List.copyOf(retiredFormNames);
            namedFamiliars = namedFamiliars == null ? List.of() : List.copyOf(namedFamiliars);
            bunshinReports = bunshinReports == null ? List.of() : List.copyOf(bunshinReports);
        }

        public boolean isEmpty() {
            return activeForms.isEmpty() && namedFamiliars.isEmpty() && bunshinReports.isEmpty();
        }
    }

    /**
     * Output bundle.
     *
     * @param newFragments        soul fragments the Forge should integrate
     *                             (identity-level narrative about form-making)
     * @param fingerprintDelta    habit deltas to merge into BehavioralFingerprint
     *                             via {@link BehavioralFingerprint#merge}
     * @param corpusEntries       first-person narrative entries for next LoRA cycle
     */
    public record Result(
        List<SoulFragment> newFragments,
        BehavioralFingerprint fingerprintDelta,
        List<String> corpusEntries
    ) {
        public Result {
            newFragments = newFragments == null ? List.of() : List.copyOf(newFragments);
            corpusEntries = corpusEntries == null ? List.of() : List.copyOf(corpusEntries);
            if (fingerprintDelta == null) fingerprintDelta = BehavioralFingerprint.empty();
        }

        public boolean isEmpty() {
            return newFragments.isEmpty() && corpusEntries.isEmpty();
        }
    }

    /** Run the consolidation pass. */
    public static Result ingest(Batch batch) {
        if (batch == null || batch.isEmpty()) {
            return new Result(List.of(), BehavioralFingerprint.empty(), List.of());
        }

        var fragments = new ArrayList<SoulFragment>();
        var corpus = new ArrayList<String>();

        // --- Fragments from thought-form habits ---
        fragments.addAll(formIdentityFragments(batch));

        // --- Fragments from named-familiar bonds ---
        for (var nf : batch.namedFamiliars()) {
            if (nf.bondCharge() >= NAMED_FRAGMENT_BOND_THRESHOLD) {
                fragments.add(namedFamiliarFragment(nf));
            }
        }

        // --- Fragments from bunshin impressions ---
        fragments.addAll(bunshinFragments(batch.bunshinReports()));

        // --- Corpus entries (§12.2 / §12.3) ---
        corpus.addAll(formCorpusEntries(batch));
        corpus.addAll(namedFamiliarCorpusEntries(batch.namedFamiliars()));
        corpus.addAll(bunshinCorpusEntries(batch.bunshinReports()));

        // --- Fingerprint delta ---
        var fingerprint = buildFingerprintDelta(batch);

        return new Result(fragments, fingerprint, corpus);
    }

    // ── Fragment builders ──────────────────────────────────────────────────

    private static List<SoulFragment> formIdentityFragments(Batch batch) {
        if (batch.activeForms().isEmpty()) return List.of();
        var frags = new ArrayList<SoulFragment>();

        // Identity fragment — the agent is a form-maker
        int count = batch.activeForms().size();
        if (count >= 1) {
            var text = count == 1
                ? "I have shaped one thought form. The practice is new, but I am beginning to compose small intelligences for specific questions."
                : "I have shaped " + count + " thought forms. I am a maker of small intelligences for specific questions.";
            frags.add(SoulFragment.unembedded(
                fragmentId("form-maker-identity"),
                "personality",
                "Form-maker identity",
                text));
        }

        // Retirement-wisdom fragment (only if the agent has actually retired forms)
        if (!batch.retiredFormNames().isEmpty()) {
            var retired = batch.retiredFormNames().size();
            var text = retired == 1
                ? "I have retired a thought form I no longer trusted. I can let go of tools that no longer serve me."
                : "I have retired " + retired + " thought forms that no longer served me. Letting go of what doesn't work is part of the craft.";
            frags.add(SoulFragment.unembedded(
                fragmentId("form-retirement-wisdom"),
                "values",
                "Retirement wisdom",
                text));
        }

        // High-failure forms — the Forge notices the pattern honestly
        var abandoned = batch.activeForms().stream()
            .filter(f -> f.summonCount() >= MIN_CORPUS_SUMMONS)
            .filter(f -> f.successRatio() < MIN_SUCCESS_RATIO)
            .toList();
        if (!abandoned.isEmpty()) {
            var names = abandoned.stream().map(ThoughtForm::name).toList();
            frags.add(SoulFragment.unembedded(
                fragmentId("form-honesty"),
                "values",
                "Honesty about what doesn't work",
                "Some forms I have shaped do not work reliably yet (" + String.join(", ", names)
                    + "). I notice this honestly — trying again is only sensible if I change something."));
        }
        return frags;
    }

    private static SoulFragment namedFamiliarFragment(NamedFamiliar nf) {
        var text = new StringBuilder();
        text.append("I have a named familiar called ").append(nf.name()).append(". ");
        text.append("Summoned ").append(nf.summonCount()).append(" times");
        if (nf.successCount() + nf.failureCount() > 0) {
            text.append(" (success rate ")
                .append(String.format("%.0f%%", nf.successRatio() * 100.0))
                .append(")");
        }
        text.append(". ");
        if (nf.bondCharge() >= 0.7f) {
            text.append("The bond is strong — I lean on her.");
        } else if (nf.bondCharge() >= 0.5f) {
            text.append("She has become someone I return to.");
        } else {
            text.append("We are building a working rhythm.");
        }
        return SoulFragment.unembedded(
            fragmentId("named-" + nf.name()),
            "relationships",
            "Named familiar: " + nf.name(),
            text.toString());
    }

    private static List<SoulFragment> bunshinFragments(List<BunshinReport> reports) {
        if (reports.isEmpty()) return List.of();
        var frags = new ArrayList<SoulFragment>();
        var successes = reports.stream().filter(BunshinReport::succeeded).count();
        var failures = reports.size() - successes;

        if (reports.size() >= 3) {
            frags.add(SoulFragment.unembedded(
                fragmentId("bunshin-habit"),
                "personality",
                "Parallel self habit",
                "I have split myself into bunshin " + reports.size() + " times recently ("
                    + successes + " came back with what I needed, " + failures + " did not). "
                    + "I can stay present with you while also going to focus on something."));
        }

        // Forward the seed fragments from bunshin reports.:
        // "Forge resolves or holds the tension during next sleep." Here we
        // detect contradictions — either within the batch (two bunshin seeds
        // pointing at each other) or against the existing soul fragments the
        // primary already holds — and emit a paired "tension" fragment so
        // the Forge's next consolidation sees both sides honestly.
        var allSeeds = new ArrayList<BunshinReport.FragmentSeed>();
        for (var report : reports) allSeeds.addAll(report.newFragmentSeeds());

        var tensionsRecorded = new HashSet<String>();
        for (var seed : allSeeds) {
            frags.add(SoulFragment.unembedded(
                fragmentId("bunshin-seed-" + seed.category()),
                seed.category(),
                "From a bunshin",
                seed.text()));

            // Check contradicts field
            var conflictText = seed.contradicts();
            if (conflictText.isEmpty() || conflictText.get().isBlank()) continue;
            var conflict = conflictText.get();
            // Dedup: don't emit two tension fragments for the same pair
            var pairKey = conflict.hashCode() < seed.text().hashCode()
                ? conflict + "→" + seed.text()
                : seed.text() + "→" + conflict;
            if (!tensionsRecorded.add(pairKey)) continue;
            frags.add(SoulFragment.unembedded(
                fragmentId("bunshin-tension"),
                "tension",
                "Held tension",
                "One of my bunshin found: \"" + truncate(seed.text(), 200) + "\". "
                    + "This contradicts what I held: \"" + truncate(conflict, 200) + "\". "
                    + "I hold both for now — tomorrow's sleep may resolve it."));
        }
        return frags;
    }

    // ── Corpus entries (§12.2) ─────────────────────────────────────────────

    private static List<String> formCorpusEntries(Batch batch) {
        var lines = new ArrayList<String>();
        for (var form : batch.activeForms()) {
            if (batch.retiredFormNames().contains(form.name())) continue;  // §12.3 exclude retired
            if (form.summonCount() < MIN_CORPUS_SUMMONS) continue;
            if (form.successRatio() < MIN_SUCCESS_RATIO) continue;          // §12.3 weight successful

            var line = "I made a `" + form.name() + "` form. I've summoned her "
                + form.summonCount() + " times, and she has succeeded "
                + String.format("%.0f%%", form.successRatio() * 100.0) + " of the time. "
                + "I use her for: " + truncate(form.evalCriteria(), 120) + ".";
            lines.add(line);
        }
        return lines;
    }

    private static List<String> namedFamiliarCorpusEntries(List<NamedFamiliar> named) {
        var lines = new ArrayList<String>();
        for (var nf : named) {
            if (nf.summonCount() < MIN_CORPUS_SUMMONS) continue;
            lines.add("My named familiar " + nf.name() + " and I have worked together "
                + nf.summonCount() + " times. We have a rhythm now.");
        }
        return lines;
    }

    private static List<String> bunshinCorpusEntries(List<BunshinReport> reports) {
        var lines = new ArrayList<String>();
        for (var r : reports) {
            if (r.outcome() != BunshinReport.Outcome.SUCCESS) continue;  // weight successful
            lines.add("I sent a bunshin to focus on \"" + truncate(r.task(), 80)
                + "\" while I stayed present. She came back with: " + truncate(r.summary(), 200));
        }
        return lines;
    }

    // ── Fingerprint delta ──────────────────────────────────────────────────

    /**
     * Builds a BehavioralFingerprint delta capturing form-making and
     * bunshin-dispatch frequencies. These maps are consumable by
     * {@link BehavioralFingerprint#merge} — the caller chooses the alpha.
     */
    private static BehavioralFingerprint buildFingerprintDelta(Batch batch) {
        var actionDist = new LinkedHashMap<String, Float>();
        var topicAff = new LinkedHashMap<String, Float>();
        var markers = new ArrayList<String>();

        int forms = batch.activeForms().size();
        int summonsTotal = batch.activeForms().stream()
            .mapToInt(f -> (int) f.summonCount()).sum();
        int bunshinCount = batch.bunshinReports().size();
        int namedCount = batch.namedFamiliars().size();

        if (forms > 0) {
            actionDist.put("shape_form", (float) forms);
            topicAff.put("form-making", 1.0f);
        }
        if (summonsTotal > 0) {
            actionDist.put("summon_familiar", (float) summonsTotal);
        }
        if (bunshinCount > 0) {
            actionDist.put("dispatch_bunshin", (float) bunshinCount);
            topicAff.put("parallel-self", 0.8f);
            markers.add("I sent a bunshin to focus.");
        }
        if (namedCount > 0) {
            topicAff.put("named-companions", 0.6f);
        }

        return new BehavioralFingerprint(
            Map.of(),                             // baselineVitality — n/a here
            Map.of(),                             // derivatives — n/a
            Map.of(),                             // sensitivity — n/a
            Map.copyOf(actionDist),
            Map.copyOf(topicAff),
            Map.of(),                             // avoidance — nothing to claim here
            0.0f, 0.0f,                           // latency/length — unchanged
            List.copyOf(markers),
            Map.of());                            // emotionalResponse — unchanged
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static String fragmentId(String slug) {
        return "familiar-forge-" + slug + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }
}
