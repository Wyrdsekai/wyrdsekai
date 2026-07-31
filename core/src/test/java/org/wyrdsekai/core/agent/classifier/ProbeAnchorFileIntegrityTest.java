package org.wyrdsekai.core.agent.classifier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * #1018 — integrity guard for held-out probe-anchor JSONLs.
 *
 * <p>The {@code probe-anchors/<head>.jsonl} files are the held-out evaluation set
 * that {@code scripts/classifier/probe_overrouting.py} drives the over-routing
 * welfare gate against ( OPEN-R5 closure). The bake recipe's gate
 * is welfare-permanent — these files are load-bearing. If anyone authors a new
 * head and forgets a langs/labels/uniqueness invariant, the python probe will
 * either fail noisily at recipe-time OR worse, silently let a regression pass.
 *
 * <p>This test runs at tier 0 (no inference, no GPU) and catches authoring
 * mistakes early. Specifically:
 *
 * <ol>
 *   <li>Every {@link ClassifierHead} has a non-empty probe-anchor file.</li>
 *   <li>Every line is parseable JSON with {@code text}/{@code label}/{@code lang}.</li>
 *   <li>{@code lang} is in {en, es, ja} (the trilingual coverage standard).</li>
 *   <li>{@code label} is in the labels.json for that head (no orphan labels).</li>
 *   <li>No duplicate texts within a head's file.</li>
 *   <li>No overlap with the seeds.jsonl (anchors must be held-out).</li>
 *   <li>Every label is exercised at least once per language (minimum coverage).</li>
 * </ol>
 *
 * <p>If a new head is added to {@link ClassifierHead}, this test forces the
 * author to drop an anchor file alongside it — the {@code assertNotNull} on
 * the resource stream is the contract.
 */
class ProbeAnchorFileIntegrityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> EXPECTED_LANGS = Set.of("en", "es", "ja");

    @Test void every_head_has_a_probe_anchor_file_and_it_is_well_formed() throws IOException {
        var results = new LinkedHashMap<String, String>();
        for (var head : ClassifierHead.values()) {
            var headName = head.resourceName();
            var anchorPath = "classifier/probe-anchors/" + headName + ".jsonl";
            var seedsPath = "classifier/bootstrap/" + headName + "/seeds.jsonl";
            var labelsPath = head.labelsResourcePath();

            // Required: anchor file exists as a classpath resource.
            var anchors = readJsonLines(anchorPath);
            assertNotNull(anchors,
                "Missing probe-anchor file for head '" + headName + "' — "
                    + "every ClassifierHead must ship a held-out probe-anchor JSONL at " + anchorPath);
            assertFalse(anchors.isEmpty(),
                "Empty probe-anchor file for head '" + headName + "' at " + anchorPath);

            // Load the head's label set from labels.json.
            var labelsDoc = readJson(labelsPath);
            assertNotNull(labelsDoc,
                "Missing labels.json for head '" + headName + "' at " + labelsPath);
            var allowedLabels = new HashSet<String>();
            for (var node : labelsDoc.path("labels")) {
                allowedLabels.add(node.asText());
            }
            assertFalse(allowedLabels.isEmpty(),
                "labels.json for head '" + headName + "' has no 'labels' array");

            // Load seeds to enforce held-out-ness.
            var seedTexts = new HashSet<String>();
            var seedLines = readJsonLines(seedsPath);
            if (seedLines != null) {
                for (var seed : seedLines) {
                    seedTexts.add(seed.path("text").asText());
                }
            }

            // Walk anchors. Track per-line errors so a busted file surfaces all
            // problems in one run, not one-per-test-cycle.
            var errors = new ArrayList<String>();
            var seenTexts = new HashSet<String>();
            var seenPerLangLabel = new HashSet<String>();
            for (int i = 0; i < anchors.size(); i++) {
                var row = anchors.get(i);
                int line = i + 1;
                var text = row.path("text").asText(null);
                var label = row.path("label").asText(null);
                var lang = row.path("lang").asText(null);
                if (text == null || text.isBlank()) {
                    errors.add("line " + line + ": missing/blank 'text'");
                    continue;
                }
                if (label == null) {
                    errors.add("line " + line + ": missing 'label'");
                    continue;
                }
                if (lang == null) {
                    errors.add("line " + line + ": missing 'lang'");
                    continue;
                }
                if (!EXPECTED_LANGS.contains(lang)) {
                    errors.add("line " + line + ": lang '" + lang
                        + "' not in expected {en,es,ja} (text=" + truncate(text) + ")");
                }
                if (!allowedLabels.contains(label)) {
                    errors.add("line " + line + ": label '" + label
                        + "' not in head's labels.json {" + allowedLabels + "}");
                }
                if (!seenTexts.add(text)) {
                    errors.add("line " + line + ": duplicate text within anchor file: "
                        + truncate(text));
                }
                if (seedTexts.contains(text)) {
                    errors.add("line " + line + ": anchor overlaps a seed (not held-out): "
                        + truncate(text));
                }
                seenPerLangLabel.add(lang + ":" + label);
            }

            // Minimum coverage: every (lang, label) combination present.
            for (var lang : EXPECTED_LANGS) {
                for (var label : allowedLabels) {
                    if (!seenPerLangLabel.contains(lang + ":" + label)) {
                        errors.add("missing coverage: no anchor with lang=" + lang
                            + " label=" + label);
                    }
                }
            }

            if (!errors.isEmpty()) {
                results.put(headName, String.join("\n    - ", errors));
            }
        }

        if (!results.isEmpty()) {
            var sb = new StringBuilder("Probe-anchor file integrity violations:\n");
            results.forEach((head, errs) ->
                sb.append("  [").append(head).append("]\n    - ").append(errs).append("\n"));
            fail(sb.toString());
        }
    }

    @Test void all_anchor_files_use_consistent_trilingual_coverage() throws IOException {
        // Soft sibling assertion: every head's anchor file should have *some*
        // EN, ES, and JA rows. Catches the case where someone authors a new
        // head but only fills out EN.
        for (var head : ClassifierHead.values()) {
            var anchors = readJsonLines(
                "classifier/probe-anchors/" + head.resourceName() + ".jsonl");
            if (anchors == null) continue; // covered by the other test
            var langCounts = new LinkedHashMap<String, Integer>();
            for (var row : anchors) {
                langCounts.merge(row.path("lang").asText(""), 1, Integer::sum);
            }
            for (var lang : EXPECTED_LANGS) {
                var c = langCounts.getOrDefault(lang, 0);
                assertTrue(c >= 4,
                    "head=" + head.resourceName() + " has only " + c
                        + " anchors for lang=" + lang
                        + " — multilingual coverage is the OPEN-R5 contract; "
                        + "raise to ≥4 per lang per head (current counts: " + langCounts + ")");
            }
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /** Returns null if resource is missing; throws if it exists but is malformed. */
    private static List<JsonNode> readJsonLines(String resourcePath) throws IOException {
        try (InputStream in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(resourcePath)) {
            if (in == null) return null;
            var content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            var rows = new ArrayList<JsonNode>();
            int lineNum = 0;
            for (var line : content.split("\n")) {
                lineNum++;
                if (line.isBlank()) continue;
                try {
                    rows.add(MAPPER.readTree(line));
                } catch (IOException e) {
                    throw new IOException(
                        "Malformed JSON at " + resourcePath + ":" + lineNum
                            + " — " + e.getMessage(), e);
                }
            }
            return rows;
        }
    }

    private static JsonNode readJson(String resourcePath) throws IOException {
        try (InputStream in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(resourcePath)) {
            if (in == null) return null;
            return MAPPER.readTree(in);
        }
    }

    private static String truncate(String s) {
        return s.length() > 60 ? s.substring(0, 57) + "..." : s;
    }
}
