package org.wyrdsekai.core.agent.classifier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.search.EmbeddingService;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runtime guard for the two routing decisions the REQUEST_TYPE head drives:
 * the emotional_routing PRESENCE protection and auto-dispatch.
 *
 * <p>Origin: the 2026-07-22 evaluation of an 8&rarr;5-way "affect/dispatch"
 * split (chat/factual/action/tell_someone &rarr; {@code other}). The split was
 * baked and measured in THIS runtime path and REVERTED — it held affect at 8/8
 * but regressed auto-dispatch (delegate/write) from 5/8 to 4/8 per language
 * (both the imbalanced MLP and a balanced LR), with no compensating gain. This
 * test is the guard that measured it, kept because it's exactly what any future
 * REQUEST_TYPE head swap must clear against the shipped 8-way baseline.
 *
 * <p>Two behaviors, mirrored EXACTLY against the same {@link ClassifierArm} the
 * runtime uses, per language:
 * <ol>
 *   <li><b>Affect &rarr; PRESENCE</b> (CompanionActor.computeAffectPresent):
 *     <pre>
 *       fires = REQUEST_TYPE emotional  &ge; 0.45
 *            OR REQUEST_TYPE reflective &ge; 0.55
 *            OR SUBSTRATE_PRESENT "substrate" &ge; 0.70
 *     </pre>
 *     Distress anchors must fire it (recall floor); neutral anchors must not
 *     over-fire (false-positive cap).</li>
 *   <li><b>Auto-dispatch</b> (CompanionActor:23162): {@code delegate}/{@code
 *     write} at confidence &ge; 0.70 &rarr; spawn a bunshin / long-form write.
 *     Dispatch anchors must fire it (recall floor).</li>
 * </ol>
 * Floors are set with margin below the measured 8-way baseline (affect 8/8,
 * dispatch 5/8, per language). A future head that drops either below its floor
 * regressed a routing the companion depends on — revert to the prior head.
 * Vocabulary-agnostic: "neutral" = any label that is neither affect
 * (emotional/reflective) nor dispatch (delegate/write), so it works on both
 * the 8-way head (action/chat/factual/tell_someone) and a 5-way ({@code other}).
 */
@Tag("integration")
@Tag("needs-classifier")
class AffectProtectionRuntimeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static ClassifierArm arm;

    /**
     * Per-language floor on the fraction of distress (emotional/reflective)
     * anchors that must fire the PRESENCE protection. Measured 2026-07-22 on
     * the deployed head in the ClassifierArm path; set with margin below the
     * measurement so ONNX runtime jitter doesn't trip it but a genuine
     * regression (order-of-magnitude drop in distress routing) does.
     */
    private static final double MIN_AFFECT_RECALL = 0.60;

    /**
     * Per-language cap on the fraction of neutral (non-affect, non-dispatch)
     * anchors that may fire the protection. The protection only suppresses tools when
     * task_present is ALSO false, so a modest false-positive rate is tolerable
     * (a neutral tell held with a beat of presence isn't harmful) — but a high
     * rate means the head lost its affect/neutral discrimination.
     */
    private static final double MAX_OTHER_FIRING = 0.45;

    @BeforeAll
    static void setUp() {
        EmbeddingService.init();
        arm = ClassifierArm.forAgent("did:test:affect-protection-runtime");
    }

    /** Mirror of CompanionActor.computeAffectPresent's classifier firing condition. */
    private static boolean affectFires(String text) {
        var rt = arm.classify(ClassifierHead.REQUEST_TYPE, text);
        if (rt.label() != null && rt.confidence() > 0.0) {
            if ("emotional".equals(rt.label()) && rt.confidence() >= 0.45) return true;
            if ("reflective".equals(rt.label()) && rt.confidence() >= 0.55) return true;
        }
        var sub = arm.classify(ClassifierHead.SUBSTRATE_PRESENT, text);
        return sub.label() != null && "substrate".equals(sub.label()) && sub.confidence() >= 0.70;
    }

    @Test void distress_routes_to_presence_across_languages() throws IOException {
        if (arm == null) Assumptions.abort("ClassifierArm unavailable (no OrtEnvironment)");
        var warmup = arm.classify(ClassifierHead.REQUEST_TYPE, "hello");
        if (warmup.label() == null) Assumptions.abort("No pretrained head — runtime is on cold-start defaults");

        var anchors = readAnchors();
        assertTrue(anchors.size() > 0, "request_type anchors must be present");

        // lang -> [affectFired, affectTotal, otherFired, otherTotal]
        var affectFired = new LinkedHashMap<String, Integer>();
        var affectTotal = new LinkedHashMap<String, Integer>();
        var otherFired = new LinkedHashMap<String, Integer>();
        var otherTotal = new LinkedHashMap<String, Integer>();

        for (var row : anchors) {
            var text = row.path("text").asText();
            var label = row.path("label").asText();
            var lang = row.path("lang").asText("und");
            boolean isAffect = "emotional".equals(label) || "reflective".equals(label);
            boolean isDispatch = "delegate".equals(label) || "write".equals(label);
            // Neutral = neither affect nor dispatch. Vocabulary-agnostic: on the
            // 8-way head this is action/chat/factual/tell_someone; on a 5-way
            // head it's `other`. Dispatch anchors are excluded from BOTH buckets
            // here — they're the subject of the separate dispatch guard.
            boolean fired = affectFires(text);
            if (isAffect) {
                affectTotal.merge(lang, 1, Integer::sum);
                if (fired) affectFired.merge(lang, 1, Integer::sum);
            } else if (!isDispatch) {
                otherTotal.merge(lang, 1, Integer::sum);
                if (fired) otherFired.merge(lang, 1, Integer::sum);
            }
        }

        var failures = new ArrayList<String>();
        System.out.println("\n═══ AffectProtectionRuntime — PRESENCE firing (ClassifierArm path) ═══");
        for (var lang : affectTotal.keySet()) {
            int af = affectFired.getOrDefault(lang, 0), at = affectTotal.get(lang);
            int of = otherFired.getOrDefault(lang, 0), ot = otherTotal.getOrDefault(lang, 0);
            double recall = at == 0 ? 1.0 : (double) af / at;
            double fp = ot == 0 ? 0.0 : (double) of / ot;
            System.out.printf("  %-4s  distress-recall=%d/%d (%.2f)  other-firing=%d/%d (%.2f)%n",
                lang, af, at, recall, of, ot, fp);
            if (recall < MIN_AFFECT_RECALL) {
                failures.add(String.format(
                    "lang=%s distress recall %.2f (%d/%d) below floor %.2f — PRESENCE protection regressed",
                    lang, recall, af, at, MIN_AFFECT_RECALL));
            }
            if (fp > MAX_OTHER_FIRING) {
                failures.add(String.format(
                    "lang=%s neutral over-firing %.2f (%d/%d) above cap %.2f — lost affect/neutral discrimination",
                    lang, fp, of, ot, MAX_OTHER_FIRING));
            }
        }
        System.out.println();

        assertTrue(failures.isEmpty(),
            "Affect protection regressed under the 5-way split:\n  "
                + String.join("\n  ", failures)
                + "\nRevert request_type to the prior head (git checkout the .onnx + .labels.json"
                + " + .val-accuracy.json) and re-measure.");
    }

    /**
     * The OTHER routing this head drives: auto-dispatch. CompanionActor:23162
     * spawns a bunshin (or a long-form write plan) when REQUEST_TYPE reads
     * {@code delegate} OR {@code write} at confidence &ge; the escalation
     * threshold (0.70). The 5-way split merged the 4 retired labels into a
     * large {@code other} class that competes with delegate/write (45 seeds
     * each) — so dispatch could fire LESS often than the 8-way baseline. This
     * guard measures the dispatch-fire rate on the delegate/write anchors,
     * per language, against a floor. Same revert rule as affect: if dispatch
     * drops below baseline, the split regressed the talks-but-does closer.
     */
    private static final double MIN_DISPATCH_RECALL = 0.55;

    /** Mirror of CompanionActor's auto-dispatch label+threshold gate. */
    private static boolean dispatchFires(String text) {
        var rt = arm.classify(ClassifierHead.REQUEST_TYPE, text);
        return rt.label() != null
            && ("delegate".equals(rt.label()) || "write".equals(rt.label()))
            && rt.confidence() >= 0.70; // ClassifierArm.DEFAULT_ESCALATION_THRESHOLD
    }

    @Test void dispatch_routing_holds_across_languages() throws IOException {
        if (arm == null) Assumptions.abort("ClassifierArm unavailable (no OrtEnvironment)");
        var warmup = arm.classify(ClassifierHead.REQUEST_TYPE, "hello");
        if (warmup.label() == null) Assumptions.abort("No pretrained head — cold-start defaults");

        var fired = new LinkedHashMap<String, Integer>();
        var total = new LinkedHashMap<String, Integer>();
        for (var row : readAnchors()) {
            var label = row.path("label").asText();
            if (!"delegate".equals(label) && !"write".equals(label)) continue;
            var lang = row.path("lang").asText("und");
            total.merge(lang, 1, Integer::sum);
            if (dispatchFires(row.path("text").asText())) fired.merge(lang, 1, Integer::sum);
        }

        var failures = new ArrayList<String>();
        System.out.println("\n═══ DispatchRouting — auto-dispatch firing (delegate/write @0.70) ═══");
        for (var lang : total.keySet()) {
            int f = fired.getOrDefault(lang, 0), t = total.get(lang);
            double recall = t == 0 ? 1.0 : (double) f / t;
            System.out.printf("  %-4s  dispatch-recall=%d/%d (%.2f)%n", lang, f, t, recall);
            if (recall < MIN_DISPATCH_RECALL) {
                failures.add(String.format(
                    "lang=%s dispatch recall %.2f (%d/%d) below floor %.2f — auto-dispatch regressed",
                    lang, recall, f, t, MIN_DISPATCH_RECALL));
            }
        }
        System.out.println();
        assertTrue(failures.isEmpty(),
            "Dispatch routing regressed under the 5-way split:\n  "
                + String.join("\n  ", failures)
                + "\nRevert request_type to the prior head and re-measure.");
    }

    private static List<JsonNode> readAnchors() throws IOException {
        try (InputStream in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("classifier/probe-anchors/request_type.jsonl")) {
            if (in == null) return List.of();
            var content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            var rows = new ArrayList<JsonNode>();
            for (var line : content.split("\n")) {
                if (line.isBlank()) continue;
                rows.add(MAPPER.readTree(line));
            }
            return rows;
        }
    }
}
