package org.wyrdsekai.core.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.coding.StubItemWorldApiProvider;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;
import org.wyrdsekai.scripting.sandbox.ItemScriptExecutor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * tests for the real {@link SkillVerifier} service.
 *
 * <p>Promotes the scripting-module proof-of-concept into a test of the production
 * component: a draft skill ({@code SkillDraft.code}-shaped GraalJS) is executed in the
 * real item sandbox against an {@link AnchorHarness}, the verifier discriminates a correct
 * skill from a buggy one, and the harness survives a JSON round-trip unchanged (the
 * "proof travels with the skill" property used on Trading Post copy / cross-zone transit).</p>
 */
class SkillVerifierTest {

    private SkillVerifier verifier;
    private final ItemCapabilitySet restricted = ItemCapabilitySet.of(List.of());

    @BeforeEach
    void setUp() {
        verifier = new SkillVerifier(new ItemScriptExecutor());
    }

    @AfterEach
    void tearDown() {
        // ItemScriptExecutor is Closeable; the verifier holds it. Re-create per test is fine;
        // nothing to close here since we let GC handle the shared engine.
    }

    /** Correct Celsius→Fahrenheit skill (F = C·9/5 + 32) — the shape SkillProposer emits. */
    private static final String GOOD_SKILL = """
        function invoke(params) { return { fahrenheit: params.celsius * 9 / 5 + 32 }; }
        """;

    /** Buggy skill: forgot the +32 offset. Compiles, runs, and is wrong. */
    private static final String BUGGY_SKILL = """
        function invoke(params) { return { fahrenheit: params.celsius * 9 / 5 }; }
        """;

    /** Anchors grounded in documented physical constants — NOT a target-task answer key. */
    private static AnchorHarness converterHarness() {
        return new AnchorHarness("celsius_to_fahrenheit", List.of(
            new AnchorHarness.VerificationCase(
                Map.of("celsius", 0), "fahrenheit",
                AnchorHarness.Check.numeric(32.0, 1e-9), "freezing point of water (NIST)"),
            new AnchorHarness.VerificationCase(
                Map.of("celsius", 100), "fahrenheit",
                AnchorHarness.Check.numeric(212.0, 1e-9), "boiling point of water at 1 atm (NIST)"),
            new AnchorHarness.VerificationCase(
                Map.of("celsius", 37), "fahrenheit",
                AnchorHarness.Check.numeric(98.6, 1e-9), "normal human body temperature")
        ));
    }

    @Test
    void good_skill_passes_all_anchors() {
        var v = verifier.verify("good", GOOD_SKILL, converterHarness(),
            StubItemWorldApiProvider.INSTANCE, restricted);
        assertTrue(v.passed(), "correct skill should pass; failures=" + v.failures());
        assertEquals(3, v.casesRun());
        assertEquals(3, v.casesPassed());
        // → gate at WorkshopPinboard.approve would PERMIT materialize().
    }

    @Test
    void buggy_skill_is_blocked_with_provenance() {
        var v = verifier.verify("buggy", BUGGY_SKILL, converterHarness(),
            StubItemWorldApiProvider.INSTANCE, restricted);
        assertFalse(v.passed(), "buggy skill must be caught");
        assertEquals(0, v.casesPassed());
        assertEquals(3, v.failures().size());
        // Each failure carries the anchor's provenance for the human reviewer.
        assertTrue(v.failures().get(0).source().contains("freezing point"));
        // → gate would BLOCK materialize().
    }

    @Test
    void harness_round_trips_as_json_and_verifies_identically() throws Exception {
        var mapper = new ObjectMapper();
        var original = converterHarness();

        // Freeze → serialize (what ships WITH the skill item) → deserialize on the recipient.
        String json = mapper.writeValueAsString(original);
        AnchorHarness reloaded = mapper.readValue(json, AnchorHarness.class);

        var fromOriginal = verifier.verify("good", GOOD_SKILL, original,
            StubItemWorldApiProvider.INSTANCE, restricted);
        var fromReloaded = verifier.verify("good2", GOOD_SKILL, reloaded,
            StubItemWorldApiProvider.INSTANCE, restricted);

        assertEquals(fromOriginal.passed(), fromReloaded.passed());
        assertEquals(fromOriginal.casesPassed(), fromReloaded.casesPassed());
        assertTrue(fromReloaded.passed(), "re-run of the JSON-reloaded harness must still pass");
        assertEquals("celsius_to_fahrenheit", reloaded.skillName());
        assertEquals(3, reloaded.cases().size());
    }
}
