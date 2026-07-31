package org.wyrdsekai.core.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;
import org.wyrdsekai.scripting.api.ItemWorldApiProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * mutation-tests an authored harness to measure whether it has <b>teeth</b>.
 *
 * <p>A small model can emit a well-formed, anchor-grounded harness that nonetheless does not actually
 * <i>discriminate</i> — e.g. it only checks that an output is present, or its cases don't exercise the
 * logic the bug lives in. Such a harness reads as "verified" while protecting nothing. This is the
 * 9B's real ceiling on the authoring task: not format, but discrimination power — and the model can't
 * self-assess it.</p>
 *
 * <p>The gate makes that ceiling <i>measured</i> instead of invisible: it perturbs the skill with
 * {@link SkillMutator} (value-changing mutations that keep the code runnable) and re-runs the frozen
 * harness against each mutant. A harness with teeth <b>catches</b> mutants (verdict fails); a toothless
 * one passes them all. Pure code, deterministic, no model — runs right after authoring, off the hot
 * path. When a harness is toothless, {@link SkillVerificationAuthoring} declines to attach it and the
 * skill is left unverified (escalate authoring to a stronger model) rather than falsely trusted.</p>
 */
public final class HarnessMutationGate {

    private static final Logger log = LoggerFactory.getLogger(HarnessMutationGate.class);

    /**
     * @param hasTeeth      true if the harness caught at least one value-perturbing mutant (or could
     *                      not be assessed — fail-open: too few mutants to judge)
     * @param assessable    false when the skill had no mutable arithmetic/literals to perturb
     * @param mutantsRun    number of mutants generated and run
     * @param mutantsCaught number the harness rejected (good — it discriminated)
     * @param survivors     descriptions of mutants the harness FAILED to catch (its blind spots)
     */
    public record MutationVerdict(boolean hasTeeth, boolean assessable,
                                  int mutantsRun, int mutantsCaught, List<String> survivors) {
        public int score() { return mutantsRun == 0 ? 0 : (100 * mutantsCaught / mutantsRun); }
    }

    private final SkillVerifier verifier;

    public HarnessMutationGate(SkillVerifier verifier) {
        this.verifier = verifier;
    }

    /**
     * Assess whether {@code harness} discriminates the real skill from plausible wrong versions.
     *
     * <p>Precondition: the ORIGINAL skill should already pass the harness (the caller checks this —
     * a harness its own skill fails is a different, anchor-miscompilation failure). Here we only ask:
     * do wrong versions get caught?</p>
     */
    public MutationVerdict assess(String skillId, String skillCode, AnchorHarness harness,
                                  ItemWorldApiProvider provider, ItemCapabilitySet caps) {
        List<SkillMutator.Mutant> mutants = SkillMutator.mutate(skillCode);
        if (mutants.isEmpty()) {
            // Nothing to perturb (e.g. a pure string passthrough) — can't judge teeth. Fail open.
            log.debug("Mutation gate: no mutants for '{}' — not assessable, permitting", skillId);
            return new MutationVerdict(true, false, 0, 0, List.of());
        }

        int caught = 0;
        var survivors = new ArrayList<String>();
        for (int i = 0; i < mutants.size(); i++) {
            var mutant = mutants.get(i);
            // Unique id per mutant: ItemScriptExecutor caches compiled source by id, so a shared id
            // would make every mutant execute the first mutant's code (and mis-measure teeth).
            SkillVerifier.Verdict v = verifier.verify(skillId + "#mut" + i, mutant.code(), harness, provider, caps);
            if (!v.passed()) {
                caught++;                 // harness rejected a wrong version — good, it has teeth here
            } else {
                survivors.add(mutant.description()); // harness blind to this perturbation
            }
        }

        boolean hasTeeth = caught >= 1;  // must discriminate at least one value perturbation
        var verdict = new MutationVerdict(hasTeeth, true, mutants.size(), caught, survivors);
        log.info("Mutation gate '{}': {} of {} mutants caught (score {}%), teeth={}",
            skillId, caught, mutants.size(), verdict.score(), hasTeeth);
        return verdict;
    }
}
