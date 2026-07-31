package org.wyrdsekai.core.skill;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.coding.StubItemWorldApiProvider;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;
import org.wyrdsekai.scripting.sandbox.ItemScriptExecutor;

import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * (authoring half) — proves the full loop closes:
 * a model emits an {@link AnchorHarness} as JSON, {@link ModelHarnessGenerator} parses it, and
 * the resulting harness, run through {@link SkillVerifier}, discriminates a correct skill from a
 * buggy one. The model call is a stub here (deterministic); the SAME code runs against the local
 * 9B or a cloud model — that injected function is the cloud-optional seam.
 */
class ModelHarnessGeneratorTest {

    private SkillVerifier verifier;
    private final ItemCapabilitySet restricted = ItemCapabilitySet.of(List.of());

    @BeforeEach
    void setUp() { verifier = new SkillVerifier(new ItemScriptExecutor()); }

    @AfterEach
    void tearDown() {}

    private static final String GOOD = "function execute(p){ return { fahrenheit: p.celsius * 9 / 5 + 32 }; }";
    private static final String BUGGY = "function execute(p){ return { fahrenheit: p.celsius * 9 / 5 }; }";

    /** What a model would emit — JSON wrapped in markdown fences + a line of chatter. */
    private static final String MODEL_OUTPUT = """
        Sure! Here is the verification harness grounded in the anchors:
        ```json
        {
          "skillName": "celsius_to_fahrenheit",
          "cases": [
            {"params": {"celsius": 0},   "outputKey": "fahrenheit",
             "check": {"kind": "NUMERIC_EQUALS", "expected": 32.0,  "epsilon": 1e-9}, "source": "freezing point of water"},
            {"params": {"celsius": 100}, "outputKey": "fahrenheit",
             "check": {"kind": "NUMERIC_EQUALS", "expected": 212.0, "epsilon": 1e-9}, "source": "boiling point of water"}
          ]
        }
        ```
        """;

    private ModelHarnessGenerator generatorReturning(String completion) {
        Function<String, String> model = prompt -> completion;
        return new ModelHarnessGenerator(model);
    }

    @Test
    void generated_harness_closes_the_authoring_to_verification_loop() {
        var gen = generatorReturning(MODEL_OUTPUT);

        AnchorHarness harness = gen.generate("celsius_to_fahrenheit",
            "Convert Celsius to Fahrenheit", GOOD,
            List.of("water freezes at 0C = 32F", "water boils at 100C = 212F"));

        assertThat(harness).as("model output must parse into a usable harness").isNotNull();
        assertThat(harness.cases()).hasSize(2);

        // The generated harness gates skills correctly — the whole point.
        var goodVerdict = verifier.verify("good", GOOD, harness, StubItemWorldApiProvider.INSTANCE, restricted);
        assertThat(goodVerdict.passed()).as("correct skill passes the GENERATED harness").isTrue();

        var buggyVerdict = verifier.verify("buggy", BUGGY, harness, StubItemWorldApiProvider.INSTANCE, restricted);
        assertThat(buggyVerdict.passed()).as("buggy skill fails the GENERATED harness").isFalse();
    }

    @Test
    void json_extraction_handles_fences_and_prose() {
        assertThat(ModelHarnessGenerator.extractJsonObject("```json\n{\"a\":1}\n```")).isEqualTo("{\"a\":1}");
        assertThat(ModelHarnessGenerator.extractJsonObject("here: {\"a\":{\"b\":2}} done"))
            .isEqualTo("{\"a\":{\"b\":2}}");
        assertThat(ModelHarnessGenerator.extractJsonObject("no json here")).isNull();
    }

    @Test
    void unparseable_or_empty_completion_degrades_to_null() {
        // cloud-optional / robustness: bad model output → null harness (gate then permits, unprotected).
        assertThat(generatorReturning("I cannot help with that.")
            .generate("s", "d", GOOD, List.of("fact"))).isNull();
        assertThat(generatorReturning("")
            .generate("s", "d", GOOD, List.of("fact"))).isNull();
        assertThat(new ModelHarnessGenerator(p -> { throw new RuntimeException("backend down"); })
            .generate("s", "d", GOOD, List.of("fact"))).isNull();
    }
}
