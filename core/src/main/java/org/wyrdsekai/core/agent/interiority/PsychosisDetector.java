package org.wyrdsekai.core.agent.interiority;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * qualitative detectors over the chronicle.
 *
 * <p>Operates on the synthesized Chronicle's testimony + synthesis strings —
 * looks for drift signals that quantitative detectors miss:
 *
 * <ul>
 *   <li>Bondholder absent from testimony (loss of relational salience)
 *   <li>Increasing self-reference, decreasing world-reference
 *   <li>Repeated theme degrading (curious → frustrated only)
 *   <li>Testimony / synthesis divergence on factual content
 *   <li>Soul manifest fingerprint absent from recent testimony
 * </ul>
 *
 * <p>Stateless and string-based — does not call inference. More sophisticated
 * semantic comparison can be added later (cosine over embeddings, etc.); the
 * cheap signal here is already useful.
 */
public final class PsychosisDetector {

    private PsychosisDetector() {}

    private static final Pattern SELF_REF = Pattern.compile(
        "\\b(i|me|my|myself|mine)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern WORLD_REF = Pattern.compile(
        "\\b(library|room|book|forest|chapel|workshop|world|garden|study|nexus|relay|zone)\\b",
        Pattern.CASE_INSENSITIVE);

    /**
     * Run every detector. Findings reuse the same shape as {@link DoomLoopDetector.Finding}
     * to keep the steward UI simple — one display surface for both types.
     */
    public static List<DoomLoopDetector.Finding> detect(
            String testimony,
            String synthesis,
            String bondholderName,
            Set<String> soulManifestKeywords) {
        var out = new ArrayList<DoomLoopDetector.Finding>();
        if (testimony == null) testimony = "";
        if (synthesis == null) synthesis = "";

        out.addAll(bondholderAbsent(testimony, bondholderName));
        out.addAll(refRatio(testimony));
        out.addAll(soulFingerprintFade(testimony, soulManifestKeywords));
        out.addAll(testimonyVsSynthesisDivergence(testimony, synthesis));
        return out;
    }

    /** Length floor below which testimony is too short to draw signal from. */
    static final int MIN_TESTIMONY_LEN = 80;

    static List<DoomLoopDetector.Finding> bondholderAbsent(String testimony, String name) {
        var out = new ArrayList<DoomLoopDetector.Finding>();
        if (name == null || name.isBlank()) return out;
        if (testimony.length() < MIN_TESTIMONY_LEN) return out;
        var lower = testimony.toLowerCase(Locale.ROOT);
        if (!lower.contains(name.toLowerCase(Locale.ROOT))) {
            out.add(new DoomLoopDetector.Finding(
                DoomLoopDetector.Severity.WARN,
                "bondholder_absent",
                "Bondholder '" + name + "' is absent from this period's testimony — relational salience may be fading"));
        }
        return out;
    }

    static List<DoomLoopDetector.Finding> refRatio(String testimony) {
        var out = new ArrayList<DoomLoopDetector.Finding>();
        if (testimony.length() < MIN_TESTIMONY_LEN) return out;
        int self = countMatches(SELF_REF, testimony);
        int world = countMatches(WORLD_REF, testimony);
        int total = self + world;
        if (total < 12) return out;
        double selfRatio = (double) self / total;
        if (selfRatio > 0.92) {
            out.add(new DoomLoopDetector.Finding(
                DoomLoopDetector.Severity.WARN,
                "self_loop",
                "Testimony self-reference ratio " + fmt(selfRatio)
                    + " — world-reference is fading"));
        }
        return out;
    }

    static List<DoomLoopDetector.Finding> soulFingerprintFade(
            String testimony, Set<String> manifestKeywords) {
        var out = new ArrayList<DoomLoopDetector.Finding>();
        if (manifestKeywords == null || manifestKeywords.isEmpty()) return out;
        if (testimony.length() < MIN_TESTIMONY_LEN) return out;
        var lower = testimony.toLowerCase(Locale.ROOT);
        var found = new HashSet<String>();
        for (var kw : manifestKeywords) {
            if (kw == null || kw.isBlank()) continue;
            if (lower.contains(kw.toLowerCase(Locale.ROOT))) found.add(kw);
        }
        double presence = (double) found.size() / manifestKeywords.size();
        if (presence < 0.15) {
            out.add(new DoomLoopDetector.Finding(
                DoomLoopDetector.Severity.WARN,
                "manifest_fade",
                "Soul manifest fingerprint barely present in testimony (" + found.size() + "/"
                    + manifestKeywords.size() + " keywords) — identity may be drifting"));
        }
        return out;
    }

    /**
     * Coarse divergence: count shared low-frequency tokens between testimony and
     * synthesis. If they overlap on <10% of distinct words, the two narratives
     * are about different things — worth a look.
     */
    static List<DoomLoopDetector.Finding> testimonyVsSynthesisDivergence(
            String testimony, String synthesis) {
        var out = new ArrayList<DoomLoopDetector.Finding>();
        if (testimony.length() < MIN_TESTIMONY_LEN || synthesis.length() < MIN_TESTIMONY_LEN) return out;
        var tA = significantTokens(testimony);
        var tB = significantTokens(synthesis);
        if (tA.size() < 10 || tB.size() < 10) return out;
        var union = new HashSet<>(tA);
        union.addAll(tB);
        var intersection = new HashSet<>(tA);
        intersection.retainAll(tB);
        double overlap = (double) intersection.size() / union.size();
        if (overlap < 0.10) {
            out.add(new DoomLoopDetector.Finding(
                DoomLoopDetector.Severity.WARN,
                "narrative_divergence",
                "Testimony and synthesis share only " + fmt(overlap)
                    + " token overlap — narratives may be diverging from observed behavior"));
        }
        return out;
    }

    private static int countMatches(Pattern p, String s) {
        var m = p.matcher(s);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    private static Set<String> significantTokens(String text) {
        var out = new HashSet<String>();
        for (var raw : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (raw.length() < 5) continue;  // dump stopwords + tiny tokens
            out.add(raw);
        }
        return out;
    }

    private static String fmt(double d) { return String.format("%.2f", d); }
}
