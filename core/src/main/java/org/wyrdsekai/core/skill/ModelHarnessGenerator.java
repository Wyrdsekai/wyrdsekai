package org.wyrdsekai.core.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Function;

/**
 * a {@link HarnessGenerator} backed by an LLM completion.
 *
 * <p>The model call is an injected {@code Function<String,String>} (prompt → completion). That
 * indirection IS the cloud-optional seam: wire it to a strong cloud
 * model (Claude SDK / cross-zone), the local 9B, or — in tests — a stub. The same code, the
 * same prompt; only the function changes. If the model is unavailable or its output won't parse,
 * {@link #generate} returns {@code null} (unverified — the gate then permits, but unprotected).</p>
 */
public final class ModelHarnessGenerator implements HarnessGenerator {

    private static final Logger log = LoggerFactory.getLogger(ModelHarnessGenerator.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Function<String, String> completion;

    /** @param completion prompt → model completion (may throw / return null on failure) */
    public ModelHarnessGenerator(Function<String, String> completion) {
        this.completion = completion;
    }

    @Override
    public AnchorHarness generate(String skillName, String skillDescription, String skillCode,
                                  List<String> anchorFacts) {
        String prompt = buildPrompt(skillName, skillDescription, skillCode, anchorFacts);
        String raw;
        try {
            raw = completion.apply(prompt);
        } catch (RuntimeException e) {
            log.warn("Harness generation: model call failed for '{}': {}", skillName, e.getMessage());
            return null; // cloud-optional: no model → unverified
        }
        if (raw == null || raw.isBlank()) {
            log.warn("Harness generation: empty completion for '{}'", skillName);
            return null;
        }
        String json = extractJsonObject(raw);
        if (json == null) {
            log.warn("Harness generation: no JSON object in completion for '{}'", skillName);
            return null;
        }
        try {
            AnchorHarness harness = MAPPER.readValue(json, AnchorHarness.class);
            if (harness.cases() == null || harness.cases().isEmpty()) {
                log.warn("Harness generation: parsed harness for '{}' has no cases", skillName);
                return null;
            }
            return harness;
        } catch (Exception e) {
            log.warn("Harness generation: unparseable harness for '{}': {}", skillName, e.getMessage());
            return null;
        }
    }

    /**
     * Strip markdown fences / surrounding prose and return the first balanced {@code {...}}
     * object. Small models routinely wrap JSON in ```json fences or chatter; be forgiving.
     */
    static String extractJsonObject(String raw) {
        String s = raw.trim();
        int fence = s.indexOf("```");
        if (fence >= 0) {
            int nl = s.indexOf('\n', fence);
            int end = s.indexOf("```", fence + 3);
            if (nl >= 0 && end > nl) s = s.substring(nl + 1, end).trim();
        }
        int start = s.indexOf('{');
        if (start < 0) return null;
        int depth = 0;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return s.substring(start, i + 1);
        }
        return null;
    }

    private static String buildPrompt(String name, String description, String code, List<String> anchorFacts) {
        var facts = new StringBuilder();
        for (var f : anchorFacts) facts.append("  - ").append(f).append('\n');
        return """
            You build deterministic verification harnesses for small JavaScript skills.

            Skill name: %s
            What it does: %s
            Code:
            %s

            Independently-verifiable anchor facts (ground every test in these — never invent answers):
            %s
            Output ONLY a JSON object (no prose, no markdown) matching this schema:
            {
              "skillName": "%s",
              "cases": [
                {
                  "params": { "<input key>": <value> },
                  "outputKey": "<result key the skill returns>",
                  "check": { "kind": "NUMERIC_EQUALS", "expected": <number>, "epsilon": 1e-9 },
                  "source": "<which anchor fact this case is grounded in>"
                }
              ]
            }
            check.kind is one of NUMERIC_EQUALS | STRING_EQUALS | NON_EMPTY | REGEX_MATCHES.
            Use the input/result key names from the code. One case per anchor fact.
            """.formatted(name, description, code, facts, name);
    }
}
