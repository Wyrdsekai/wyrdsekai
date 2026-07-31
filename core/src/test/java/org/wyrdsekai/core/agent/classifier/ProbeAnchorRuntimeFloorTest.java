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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #1018 — JVM-side mirror of {@code scripts/classifier/probe_overrouting.py}.
 *
 * <p>The python probe runs at recipe-time during bake / retrain. This test
 * runs the SAME probe against the SAME held-out anchor JSONLs through the
 * SAME Java {@link EmbeddingService} + {@link ClassifierArm} the runtime
 * uses on every inbound tell. If someone swaps the bundled encoder back to
 * a frozen variant (or ships a regressed head ONNX), the miss count blows
 * past the SetFit-baked floor and this test fails.
 *
 * <p>Per-head miss floors are intentionally loose (~2× the SetFit-measured
 * miss count, to absorb quantization jitter across rebuilds without
 * masking real regressions). A genuine quality drop produces an order-of-
 * magnitude bigger miss count and trips the floor; a 1-2 anchor swing from
 * ONNX runtime version drift doesn't.
 *
 * <p>Tagged {@code integration} because it loads the bundled 113MB encoder
 * ONNX into ONNX Runtime — slower than a pure unit test but still tier 0
 * (no external infrastructure, no GPU, no inference server, ~3-5s wall).
 */
@Tag("integration")
@Tag("needs-classifier")
class ProbeAnchorRuntimeFloorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static ClassifierArm arm;

    /**
     * Per-head miss floor on the held-out anchor set. Numbers come from the
     * end-to-end SetFit + cloud-corpus retrain measured 2026-05-25 (#1018):
     *   task_present:      1/90  (single JA edge case)
     *   substrate_present: 0/90
     *   cleanliness:       13/90
     *   request_type:      6/96
     * Floors are set at ~2× the measured miss count to absorb quantization
     * jitter while still catching genuine regressions. Any future encoder
     * or head swap that pushes misses past these numbers is a regression
     * operator must look at — tighten the floor after a clean rebuild proves
     * the swap holds.
     */
    private static final Map<ClassifierHead, Integer> MAX_MISSES = Map.of(
        ClassifierHead.TASK_PRESENT,        4,   // LR retrain 2026-07-21: runtime 0/90
        ClassifierHead.SUBSTRATE_PRESENT,   3,   // baseline: runtime 0/90
        // Tightened 26→12 (2026-07-21, LR retrain 5/90), 12→6 (2026-07-22,
        // experience seeds → 3/90), then 6→4 (2026-07-22 release bake: the
        // strict runtime-space gate evolved it AGAIN to 2/90 — the loop
        // compounding on the same experience corpus). 4 ≈ 2× the measurement.
        ClassifierHead.CLEANLINESS,         4,
        ClassifierHead.REQUEST_TYPE,       14
    );

    @BeforeAll
    static void setUp() {
        EmbeddingService.init();
        arm = ClassifierArm.forAgent("did:test:probe-anchor-runtime-floor");
    }

    @Test void bundled_artifacts_clear_probe_anchor_floor_for_every_head() throws IOException {
        if (arm == null) Assumptions.abort("ClassifierArm unavailable (no OrtEnvironment)");
        var warmup = arm.classify(ClassifierHead.REQUEST_TYPE, "hello");
        if (warmup.label() == null) Assumptions.abort("No pretrained head — runtime is on cold-start defaults");

        var failures = new ArrayList<String>();
        var byHead = new LinkedHashMap<ClassifierHead, MissReport>();

        for (var head : ClassifierHead.values()) {
            var anchors = readAnchors(head);
            assertNotNull(anchors,
                "Missing probe-anchor file for head '" + head.resourceName()
                    + "' — see ProbeAnchorFileIntegrityTest for the file contract");
            if (anchors.isEmpty()) continue;

            int misses = 0;
            var missDetails = new ArrayList<String>();
            var perLangMisses = new LinkedHashMap<String, Integer>();
            for (var row : anchors) {
                var text = row.path("text").asText();
                var expected = row.path("label").asText();
                var lang = row.path("lang").asText("und");
                var result = arm.classify(head, text);
                if (result.label() == null) {
                    // Head loaded but returned unavailable — bail with context.
                    Assumptions.abort("Head " + head.name()
                        + " returned unavailable mid-probe (text=" + truncate(text) + ")");
                }
                if (!expected.equals(result.label())) {
                    misses++;
                    perLangMisses.merge(lang, 1, Integer::sum);
                    if (missDetails.size() < 5) {
                        missDetails.add(String.format(
                            "[%s] expected=%s got=%s (conf=%.3f) :: %s",
                            lang, expected, result.label(), result.confidence(), truncate(text)));
                    }
                }
            }

            int floor = MAX_MISSES.get(head);
            byHead.put(head, new MissReport(misses, anchors.size(), perLangMisses));
            if (misses > floor) {
                var sb = new StringBuilder();
                sb.append("head=").append(head.resourceName())
                  .append(" misses=").append(misses)
                  .append("/").append(anchors.size())
                  .append(" floor=").append(floor)
                  .append(" perLang=").append(perLangMisses).append("\n");
                for (var d : missDetails) {
                    sb.append("    ").append(d).append("\n");
                }
                failures.add(sb.toString());
            }
        }

        // Always print summary — visible whether the test passes or fails,
        // useful for tracking margin over time.
        System.out.println("\n═══ ProbeAnchorRuntimeFloor — bundled-artifact miss counts ═══");
        for (var e : byHead.entrySet()) {
            var r = e.getValue();
            System.out.printf("  %-20s  %2d / %2d  floor=%d  perLang=%s%n",
                e.getKey().resourceName(), r.misses, r.total,
                MAX_MISSES.get(e.getKey()), r.perLang);
        }
        System.out.println();

        assertTrue(failures.isEmpty(),
            "Probe-anchor floor violated for " + failures.size() + " head(s):\n"
                + String.join("", failures)
                + "\nIf this is an intentional encoder/head swap, re-measure on home-server via "
                + "scripts/classifier/probe_overrouting.py and tighten MAX_MISSES; "
                + "otherwise something regressed against the SetFit-baked floor.");
    }

    private record MissReport(int misses, int total, Map<String, Integer> perLang) {}

    private static List<JsonNode> readAnchors(ClassifierHead head) throws IOException {
        var resourcePath = "classifier/probe-anchors/" + head.resourceName() + ".jsonl";
        try (InputStream in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(resourcePath)) {
            if (in == null) return null;
            var content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            var rows = new ArrayList<JsonNode>();
            for (var line : content.split("\n")) {
                if (line.isBlank()) continue;
                rows.add(MAPPER.readTree(line));
            }
            return rows;
        }
    }

    private static String truncate(String s) {
        return s.length() > 60 ? s.substring(0, 57) + "..." : s;
    }
}
