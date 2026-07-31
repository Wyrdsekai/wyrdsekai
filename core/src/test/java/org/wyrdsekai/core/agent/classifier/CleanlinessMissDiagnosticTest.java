package org.wyrdsekai.core.agent.classifier;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.search.EmbeddingService;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * SCRATCH DIAGNOSTIC (2026-07-22, new-experience-improvement experiment) —
 * prints every cleanliness anchor the deployed head misclassifies in the
 * runtime ClassifierArm path, so the experiment can identify the missed
 * phenomenon CLASS (e.g. emote-as-thought) and author genuinely new
 * experience examples of that class — never anchor paraphrases. Delete after
 * the experiment.
 */
@Tag("integration")
@Tag("needs-classifier")
class CleanlinessMissDiagnosticTest {

    private static ClassifierArm arm;

    @BeforeAll
    static void setUp() {
        EmbeddingService.init();
        arm = ClassifierArm.forAgent("did:test:cleanliness-miss-diagnostic");
    }

    @Test void list_cleanliness_misses() throws IOException {
        if (arm == null) Assumptions.abort("ClassifierArm unavailable");
        var warm = arm.classify(ClassifierHead.CLEANLINESS, "hello there friend");
        if (warm.label() == null) Assumptions.abort("no cleanliness head");

        var mapper = new ObjectMapper();
        try (InputStream in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("classifier/probe-anchors/cleanliness.jsonl")) {
            var content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            int total = 0, misses = 0;
            System.out.println("\n═══ cleanliness misses (runtime ClassifierArm path) ═══");
            for (var line : content.split("\n")) {
                if (line.isBlank()) continue;
                var row = mapper.readTree(line);
                var text = row.path("text").asText();
                var expected = row.path("label").asText();
                var lang = row.path("lang").asText("und");
                total++;
                var r = arm.classify(ClassifierHead.CLEANLINESS, text);
                if (!expected.equals(r.label())) {
                    misses++;
                    System.out.printf("  MISS [%s] expected=%s got=%s conf=%.3f :: %s%n",
                        lang, expected, r.label(), r.confidence(), text);
                }
            }
            System.out.printf("  total: %d/%d misses%n%n", misses, total);
        }
    }
}
