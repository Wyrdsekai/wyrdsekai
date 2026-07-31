package org.wyrdsekai.core.skill;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** — the mutation operators produce runnable, behaviour-changing variants. */
class SkillMutatorTest {

    private static final String ARITH = "function execute(p){ return { fahrenheit: p.celsius * 9 / 5 + 32 }; }";

    @Test
    void mutates_numeric_literals_and_arithmetic_operators() {
        List<SkillMutator.Mutant> mutants = SkillMutator.mutate(ARITH);
        assertThat(mutants).isNotEmpty();
        // Each literal (9, 5, 32) yields an increment mutant, distinct from the original.
        assertThat(mutants).anyMatch(m -> m.description().startsWith("literal 32"));
        assertThat(mutants).anyMatch(m -> m.description().startsWith("op"));
        assertThat(mutants).allMatch(m -> !m.code().equals(ARITH));
    }

    @Test
    void does_not_corrupt_compound_operators() {
        // ++ / += / === must not be split into single-op mutants that break syntax.
        String code = "function execute(p){ var i = 0; i += p.n; i++; return { ok: i === 3 }; }";
        List<SkillMutator.Mutant> mutants = SkillMutator.mutate(code);
        for (var m : mutants) {
            assertThat(m.code()).doesNotContain("+ =").doesNotContain("=  =");
        }
    }

    @Test
    void string_passthrough_has_nothing_to_perturb() {
        assertThat(SkillMutator.mutate("function execute(p){ return { echo: p.text }; }")).isEmpty();
    }
}
