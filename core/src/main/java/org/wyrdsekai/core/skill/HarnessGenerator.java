package org.wyrdsekai.core.skill;

import java.util.List;

/**
 * (authoring time) — turns independently-verifiable anchor facts into a
 * frozen {@link AnchorHarness} for a skill. This is the "use the strong model to CONSTRUCT
 * the verifier" half: the harness is built once, off the hot path, by a capable model; the
 * harness then runs as pure code at the gate and at runtime.
 *
 * <p>The leakage barrier is the caller's responsibility: the
 * {@code anchorFacts} must be independently-verifiable evidence (documented reference values,
 * I/O formats, invariants), NOT the answer to the specific task the skill will be judged on.</p>
 */
public interface HarnessGenerator {

    /**
     * @param skillName        the skill's name (the generated harness is labelled with it)
     * @param skillDescription one line describing what the skill does
     * @param skillCode        the GraalJS source (so the model knows the I/O shape, e.g. param + result keys)
     * @param anchorFacts      independently-verifiable facts to ground assertions in
     * @return a frozen harness, or {@code null} if generation failed (e.g. unparseable output,
     * or no model available — cloud-optional degradation)
     */
    AnchorHarness generate(String skillName, String skillDescription, String skillCode,
                           List<String> anchorFacts);
}
