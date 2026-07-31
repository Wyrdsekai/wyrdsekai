package org.wyrdsekai.core.skill;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.coding.StubItemWorldApiProvider;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;
import org.wyrdsekai.scripting.sandbox.ItemScriptExecutor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * proves the mutation gate measures harness teeth: a value-checking harness
 * catches perturbed skills (has teeth); a presence-only harness survives them (toothless); a skill
 * with nothing to perturb is not assessable (fail open).
 */
class HarnessMutationGateTest {

    private HarnessMutationGate gate;
    private final ItemCapabilitySet restricted = ItemCapabilitySet.of(List.of());

    private static final String GOOD = "function execute(p){ return { fahrenheit: p.celsius * 9 / 5 + 32 }; }";

    @BeforeEach
    void setUp() { gate = new HarnessMutationGate(new SkillVerifier(new ItemScriptExecutor())); }

    /** A harness that checks the actual value at two points. */
    private static AnchorHarness valueHarness() {
        return new AnchorHarness("celsius_to_fahrenheit", List.of(
            new AnchorHarness.VerificationCase(Map.of("celsius", 0), "fahrenheit",
                AnchorHarness.Check.numeric(32.0, 1e-9), "freezing"),
            new AnchorHarness.VerificationCase(Map.of("celsius", 100), "fahrenheit",
                AnchorHarness.Check.numeric(212.0, 1e-9), "boiling")));
    }

    /** A toothless harness: only checks the output key is present, never its value. */
    private static AnchorHarness presenceOnlyHarness() {
        return new AnchorHarness("celsius_to_fahrenheit", List.of(
            new AnchorHarness.VerificationCase(Map.of("celsius", 0), "fahrenheit",
                AnchorHarness.Check.nonEmpty(), "exists")));
    }

    @Test
    void value_checking_harness_has_teeth() {
        var v = gate.assess("good", GOOD, valueHarness(), StubItemWorldApiProvider.INSTANCE, restricted);
        assertThat(v.assessable()).isTrue();
        assertThat(v.mutantsRun()).isGreaterThan(0);
        assertThat(v.mutantsCaught()).isGreaterThan(0);
        assertThat(v.hasTeeth()).isTrue();
    }

    @Test
    void presence_only_harness_is_toothless() {
        var v = gate.assess("good", GOOD, presenceOnlyHarness(), StubItemWorldApiProvider.INSTANCE, restricted);
        assertThat(v.assessable()).isTrue();
        assertThat(v.mutantsRun()).isGreaterThan(0);
        // Every value-perturbing mutant still returns a non-empty fahrenheit → all survive.
        assertThat(v.mutantsCaught()).isZero();
        assertThat(v.hasTeeth()).as("a harness that never checks the value has no teeth").isFalse();
        assertThat(v.survivors()).isNotEmpty();
    }

    @Test
    void skill_with_nothing_to_perturb_is_not_assessable_and_fails_open() {
        String passthrough = "function execute(p){ return { echo: p.text }; }";
        var harness = new AnchorHarness("echo", List.of(
            new AnchorHarness.VerificationCase(Map.of("text", "hi"), "echo",
                AnchorHarness.Check.string("hi"), "identity")));
        var v = gate.assess("echo", passthrough, harness, StubItemWorldApiProvider.INSTANCE, restricted);
        assertThat(v.assessable()).as("no arithmetic/literals to mutate").isFalse();
        assertThat(v.hasTeeth()).as("fail open when not assessable").isTrue();
    }
}
