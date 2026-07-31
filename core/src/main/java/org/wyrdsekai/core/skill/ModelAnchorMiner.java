package org.wyrdsekai.core.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.library.Provenance;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * an {@link AnchorMiner} that retrieves sourced evidence and asks a model
 * to extract independently-verifiable anchors from it.
 *
 * <p>Two injected functions, both cloud-optional seams:</p>
 * <ul>
 *   <li>{@code retrieve}: query &rarr; sourced snippets. Wire it to the Library /
 *       research-pack / web-acquire pipeline, or — in tests — a stub. If it returns nothing,
 *       mining returns nothing (honest "unverified", never invented anchors).</li>
 *   <li>{@code completion}: prompt &rarr; model output. The local 9B, a strong cloud model, or
 *       a stub. The model only ever sees the retrieved snippets, never the eval task.</li>
 * </ul>
 *
 * <p><b>The leakage barrier is enforced here, in code, not in the prompt.</b> The model tags each
 * candidate with the index of the snippet it claims to be grounded in; an anchor whose index is
 * out of range (the model guessing, not citing) is dropped. A kept anchor always carries the
 * {@link Provenance.Source} of a snippet it was actually given.</p>
 */
public final class ModelAnchorMiner implements AnchorMiner {

    private static final Logger log = LoggerFactory.getLogger(ModelAnchorMiner.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Function<String, List<SourcedSnippet>> retrieve;
    private final Function<String, String> completion;

    public ModelAnchorMiner(Function<String, List<SourcedSnippet>> retrieve,
                            Function<String, String> completion) {
        this.retrieve = retrieve;
        this.completion = completion;
    }

    @Override
    public List<VerificationAnchor> mine(String skillName, String skillDescription, String skillCode) {
        List<SourcedSnippet> snippets;
        try {
            snippets = retrieve.apply(retrievalQuery(skillName, skillDescription));
        } catch (RuntimeException e) {
            log.warn("Anchor mining: retrieval failed for '{}': {}", skillName, e.getMessage());
            return List.of();
        }
        if (snippets == null || snippets.isEmpty()) {
            log.info("Anchor mining: no evidence retrieved for '{}' — harness will be unverified", skillName);
            return List.of();
        }

        String raw;
        try {
            raw = completion.apply(buildPrompt(skillName, skillDescription, skillCode, snippets));
        } catch (RuntimeException e) {
            log.warn("Anchor mining: model call failed for '{}': {}", skillName, e.getMessage());
            return List.of();
        }
        if (raw == null || raw.isBlank()) return List.of();

        String json = ModelHarnessGenerator.extractJsonObject(raw);
        if (json == null) {
            log.warn("Anchor mining: no JSON object in completion for '{}'", skillName);
            return List.of();
        }

        List<VerificationAnchor> anchors = new ArrayList<>();
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode arr = root.get("anchors");
            if (arr == null || !arr.isArray()) {
                log.warn("Anchor mining: completion for '{}' has no 'anchors' array", skillName);
                return List.of();
            }
            for (JsonNode node : arr) {
                VerificationAnchor anchor = toAnchor(node, snippets, skillName);
                if (anchor != null) anchors.add(anchor);
            }
        } catch (Exception e) {
            log.warn("Anchor mining: unparseable completion for '{}': {}", skillName, e.getMessage());
            return List.of();
        }
        return anchors;
    }

    /**
     * Convert one model candidate into a grounded anchor, or {@code null} if it fails the
     * leakage barrier (missing/out-of-range source index → the model guessed, drop it).
     */
    private VerificationAnchor toAnchor(JsonNode node, List<SourcedSnippet> snippets, String skillName) {
        JsonNode factNode = node.get("fact");
        JsonNode idxNode = node.get("sourceIndex");
        if (factNode == null || factNode.asText().isBlank()) return null;
        if (idxNode == null || !idxNode.canConvertToInt()) {
            log.debug("Anchor mining: dropping ungrounded anchor (no sourceIndex) for '{}'", skillName);
            return null; // leakage barrier: no citation → not an anchor
        }
        int idx = idxNode.asInt();
        if (idx < 0 || idx >= snippets.size()) {
            log.debug("Anchor mining: dropping anchor with out-of-range sourceIndex {} for '{}'", idx, skillName);
            return null; // leakage barrier: invented citation → drop
        }
        SourcedSnippet snippet = snippets.get(idx);
        VerificationAnchor.AnchorKind kind = parseKind(node.path("kind").asText(null));
        return new VerificationAnchor(factNode.asText().trim(), kind, snippet.source(), snippet.trustTier());
    }

    private static VerificationAnchor.AnchorKind parseKind(String raw) {
        if (raw == null) return VerificationAnchor.AnchorKind.REFERENCE_VALUE;
        try {
            return VerificationAnchor.AnchorKind.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return VerificationAnchor.AnchorKind.REFERENCE_VALUE;
        }
    }

    private static String retrievalQuery(String name, String description) {
        return (name + " " + description).trim();
    }

    private static String buildPrompt(String name, String description, String code,
                                      List<SourcedSnippet> snippets) {
        var ev = new StringBuilder();
        for (int i = 0; i < snippets.size(); i++) {
            var s = snippets.get(i);
            String title = s.source() != null && s.source().title() != null ? s.source().title() : "(untitled)";
            ev.append('[').append(i).append("] ").append(title).append('\n')
              .append(s.text()).append("\n\n");
        }
        return """
            You mine independently-verifiable VERIFICATION ANCHORS for a small JavaScript skill.
            An anchor is a documented ground truth a CORRECT skill must already agree with —
            a reference value, an I/O format, or an invariant. It is NOT a guess and NOT the
            answer to any task; it must come from the evidence below.

            Skill name: %s
            What it does: %s
            Code:
            %s

            Evidence (you may ONLY ground anchors in these numbered snippets):
            %s
            Output ONLY a JSON object (no prose, no markdown):
            {
              "anchors": [
                {
                  "fact": "<one independently-verifiable fact, in words>",
                  "kind": "REFERENCE_VALUE | IO_FORMAT | INVARIANT | CROSS_VALIDATION",
                  "sourceIndex": <the [N] of the snippet this fact comes from>
                }
              ]
            }
            Rules: every anchor MUST cite a sourceIndex that appears above. If a fact is not
            supported by any snippet, do not include it. Prefer facts that pin the skill's output
            to a documented value.
            """.formatted(name, description, code, ev);
    }
}
