package org.wyrdsekai.scripting.sandbox;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.scripting.api.ItemWorldApiProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PROOF-OF-CONCEPT for (P0/P1).
 *
 * <p>Demonstrates the load-bearing claim: a draft skill (GraalJS source, exactly the
 * shape of {@code SkillDraft.code}) can be executed in the existing item sandbox
 * ({@link ItemScriptExecutor}) against a <b>frozen, deterministic, anchor-grounded
 * test harness</b> — and that harness <b>discriminates a correct skill from a buggy
 * one with zero model calls.</b> This is the runtime verifier: pure code at the edge.</p>
 *
 * <p>The "anchors" are independently-verifiable physical constants (the freezing /
 * boiling point of water, human body temperature) — NOT the answer to any specific
 * target task. That is the leakage barrier in miniature.</p>
 */
class SkillVerifierProofTest {

    private ItemScriptExecutor executor;
    private ItemWorldApiProvider provider;

    @BeforeEach
    void setUp() {
        executor = new ItemScriptExecutor();
        // The skill under test is a pure converter; it never touches world.*.
        // The provider is required only by the execute() signature.
        provider = new ItemScriptExecutorTest.MockItemWorldApiProvider();
    }

    @AfterEach
    void tearDown() {
        executor.close();
    }

    // ── The skill drafts (what SkillProposer would emit as SkillDraft.code) ──────

    /** Correct Celsius→Fahrenheit skill: F = C·9/5 + 32. */
    private static final String GOOD_SKILL = """
        function invoke(params) {
            return { fahrenheit: params.celsius * 9 / 5 + 32 };
        }
        """;

    /** Buggy skill: forgot the + 32 offset. Plausible, compiles, runs — and wrong. */
    private static final String BUGGY_SKILL = """
        function invoke(params) {
            return { fahrenheit: params.celsius * 9 / 5 };
        }
        """;

    // ── The frozen anchor harness (deterministic; would ship WITH the skill) ─────

    private record Anchor(double celsius, double expectedFahrenheit, String source) {}

    private static final List<Anchor> ANCHORS = List.of(
        new Anchor(0,   32.0,  "freezing point of water (NIST)"),
        new Anchor(100, 212.0, "boiling point of water at 1 atm (NIST)"),
        new Anchor(37,  98.6,  "normal human body temperature (documented)")
    );

    private record Verdict(boolean passed, List<String> failures) {}

    /**
     * THE VERIFIER. Pure code — no model, large or small. Runs the skill against
     * each anchor in the sandbox and checks the output within tolerance.
     */
    private Verdict verify(String skillCode, List<Anchor> anchors) {
        var failures = new ArrayList<String>();
        for (var a : anchors) {
            Map<String, Object> out = executor.execute(
                "skill-under-test", skillCode, Map.of("celsius", a.celsius()), provider);
            Object got = out.get("fahrenheit");
            if (!(got instanceof Number n)) {
                failures.add("celsius=" + a.celsius() + ": no numeric 'fahrenheit' (got " + got + ")");
                continue;
            }
            if (Math.abs(n.doubleValue() - a.expectedFahrenheit()) > 1e-9) {
                failures.add("celsius=" + a.celsius() + ": expected " + a.expectedFahrenheit()
                    + " (" + a.source() + "), got " + n.doubleValue());
            }
        }
        return new Verdict(failures.isEmpty(), failures);
    }

    // ── The proof ────────────────────────────────────────────────────────────────

    @Test
    void good_skill_passes_the_verifier() {
        Verdict v = verify(GOOD_SKILL, ANCHORS);
        assertTrue(v.passed(), "correct skill should pass all anchors; failures=" + v.failures());
        assertTrue(v.failures().isEmpty());
        // → the gate at WorkshopPinboard.approve would PERMIT materialize().
    }

    @Test
    void buggy_skill_is_caught_by_the_verifier() {
        Verdict v = verify(BUGGY_SKILL, ANCHORS);
        assertFalse(v.passed(), "buggy skill must NOT pass — this is the whole point");
        assertEquals(3, v.failures().size(), "all three anchors should expose the missing +32");
        // → the gate would BLOCK materialize(): a defect an LLM-judge might wave through,
        //   caught deterministically against independent reference values.
    }

    @Test
    void verifier_is_deterministic_and_model_free() {
        // Re-running the frozen harness yields the identical verdict every time —
        // the property that lets it travel WITH the skill and re-run on copy/transit
        // (Trading Post / cross-zone) with no inference.
        Verdict first = verify(GOOD_SKILL, ANCHORS);
        Verdict second = verify(GOOD_SKILL, ANCHORS);
        assertEquals(first.passed(), second.passed());
        assertEquals(first.failures(), second.failures());

        // And the skill itself never invoked any world.* / LLM surface — pure compute.
        assertFalse(GOOD_SKILL.contains("world."),
            "the verified skill makes no model/world calls; the gate ran as pure code");
    }
}
