package org.wyrdsekai.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.wyrdsekai.core.agent.classifier.ClassifierArm;
import org.wyrdsekai.core.search.EmbeddingService;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runtime-space over-routing probe (gate-runtime parity, 2026-07-22).
 *
 * <p>Drop-in replacement for {@code scripts/classifier/probe_overrouting.py}
 * in the release bake's <b>deciding</b> gate. The Python probe embeds with
 * Python-transformers tokenization; production classifies through
 * {@link ClassifierArm} (DJL tokenizer + the committed classifier encoder).
 * The two spaces disagree at the margin — proven 2026-07-22 when the offline
 * probe vetoed a cleanliness candidate that was measurably BETTER in the
 * runtime path (4/90 vs 5/90). This probe classifies the held-out anchors
 * through {@link ClassifierArm.CandidateHead} — the exact production
 * inference code — so the deploy/keep decision is made in the space the
 * companion actually lives in.
 *
 * <p>Contract (same as the Python probe, so the recipe gate is unchanged):
 * prints a single JSON line with {@code overrouting_probe_passes} plus
 * counts, and exits 0 whether the gate passes or not (a failing gate is a
 * GATE result, not a step error — RecipeService merges the JSON either way).
 * Non-zero exit only for hard errors (missing files, encoder unavailable).
 *
 * <p>Flags: {@code --head}, {@code --classifier <onnx>}, {@code --labels
 * <json>}, {@code --max-misses N}, {@code --max-misses-per-lang N},
 * {@code --max-misses-per-lang-map "en:X,es:Y,ja:Z"}. {@code --embedding}
 * is REJECTED if non-empty: this probe's whole point is the committed
 * runtime encoder; probing an arbitrary encoder would reintroduce the
 * wrong-space bug under a runtime-probe banner.
 */
public final class ProbeHeadMain {

    /** Probe outcome in the runtime ClassifierArm space. */
    public record ProbeResult(int total, int misses,
            Map<String, Integer> perLangMisses,
            Map<String, Integer> perLangTotal) {}

    /**
     * Classify every anchor for {@code head} through the candidate at
     * {@code onnx}/{@code labels} using the production inference path.
     * Also used in-process by {@link RecipeBakeMain} for the baseline side
     * of the comparison — both sides measured in the same space.
     */
    public static ProbeResult probe(String head, Path onnx, Path labels)
            throws Exception {
        EmbeddingService.init();
        if (EmbeddingService.classifierEncoder() == null) {
            throw new IllegalStateException(
                "classifier encoder unavailable — cannot probe in runtime space");
        }
        var anchors = readAnchors(head);
        if (anchors == null || anchors.isEmpty()) {
            throw new IllegalStateException(
                "no probe anchors at classifier/probe-anchors/" + head + ".jsonl");
        }
        try (var candidate = ClassifierArm.loadCandidate(onnx, labels)) {
            int total = 0, misses = 0;
            var perLangMisses = new LinkedHashMap<String, Integer>();
            var perLangTotal = new LinkedHashMap<String, Integer>();
            for (var row : anchors) {
                var text = row.get("text");
                var expected = row.get("label");
                var lang = row.getOrDefault("lang", "und");
                var c = candidate.classify(text);
                if (c.label() == null) {
                    throw new IllegalStateException(
                        "candidate returned unavailable mid-probe (text=" + text + ")");
                }
                total++;
                perLangTotal.merge(lang, 1, Integer::sum);
                if (!expected.equals(c.label())) {
                    misses++;
                    perLangMisses.merge(lang, 1, Integer::sum);
                }
            }
            return new ProbeResult(total, misses, perLangMisses, perLangTotal);
        }
    }

    public static void main(String[] args) throws Exception {
        // STDOUT PURITY: RecipeRunner.mergeJsonStdout parses the ENTIRE trimmed
        // stdout as one JSON object — a single logback/ONNX line on stdout and
        // the gate silently reads nothing (then fails on a missing variable).
        // Route everything to stderr for the duration; only the final JSON
        // goes to the real stdout.
        var realOut = System.out;
        System.setOut(System.err);
        String head = null, classifier = null, labels = null, embedding = null;
        Integer maxMisses = null, maxPerLang = null;
        String perLangMap = null;
        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--head" -> head = args[++i];
                case "--classifier" -> classifier = args[++i];
                case "--labels" -> labels = args[++i];
                case "--max-misses" -> maxMisses = Integer.parseInt(args[++i]);
                case "--max-misses-per-lang" -> maxPerLang = Integer.parseInt(args[++i]);
                case "--max-misses-per-lang-map" -> perLangMap = args[++i];
                case "--embedding" -> embedding = args[++i];
                default -> { }
            }
        }
        if (head == null || classifier == null || labels == null || maxMisses == null) {
            System.err.println("usage: ProbeHeadMain --head H --classifier X.onnx "
                + "--labels X.labels.json --max-misses N "
                + "[--max-misses-per-lang N | --max-misses-per-lang-map en:X,es:Y]");
            System.exit(2);
        }
        if (embedding != null && !embedding.isBlank()) {
            System.err.println("--embedding is not supported: the runtime probe "
                + "always uses the committed classifier encoder (that IS the "
                + "parity guarantee). Freeze the encoder (setfit_encoder_path="
                + "\"\") instead of probing an untracked one.");
            System.exit(2);
        }

        var r = probe(head, Path.of(classifier), Path.of(labels));

        boolean pass = r.misses() <= maxMisses;
        var perLangFailures = new ArrayList<String>();
        if (perLangMap != null && !perLangMap.isBlank()) {
            for (var pair : perLangMap.split(",")) {
                var kv = pair.trim().split(":");
                if (kv.length != 2) continue;
                int got = r.perLangMisses().getOrDefault(kv[0], 0);
                int cap = Integer.parseInt(kv[1].trim());
                if (got > cap) {
                    pass = false;
                    perLangFailures.add(kv[0] + ":" + got + ">" + cap);
                }
            }
        } else if (maxPerLang != null) {
            for (var e : r.perLangMisses().entrySet()) {
                if (e.getValue() > maxPerLang) {
                    pass = false;
                    perLangFailures.add(e.getKey() + ":" + e.getValue() + ">" + maxPerLang);
                }
            }
        }

        var out = new LinkedHashMap<String, Object>();
        out.put("overrouting_probe_passes", pass);
        out.put("anchors_tested", r.total());
        out.put("misclassified", r.misses());
        out.put("max_misses", maxMisses);
        out.put("per_lang_misses", r.perLangMisses());
        out.put("per_lang_failures", perLangFailures);
        out.put("probe_space", "runtime-classifierarm");
        realOut.println(new ObjectMapper().writeValueAsString(out));
        realOut.flush();
        // Exit 0 regardless of pass/fail: the boolean is the gate's input,
        // not this process's exit code (a failed gate must surface as
        // GATE_FAILED downstream, never STEP_FAILED).
    }

    private static List<Map<String, String>> readAnchors(String head) throws Exception {
        var mapper = new ObjectMapper();
        try (InputStream in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("classifier/probe-anchors/" + head + ".jsonl")) {
            if (in == null) return null;
            var content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            var rows = new ArrayList<Map<String, String>>();
            for (var line : content.split("\n")) {
                if (line.isBlank()) continue;
                var node = mapper.readTree(line);
                rows.add(Map.of(
                    "text", node.path("text").asText(),
                    "label", node.path("label").asText(),
                    "lang", node.path("lang").asText("und")));
            }
            return rows;
        }
    }

    private ProbeHeadMain() {}
}
