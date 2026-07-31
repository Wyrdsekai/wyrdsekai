package org.wyrdsekai.core.skill;

import java.util.List;

/**
 * (authoring time, stage 1) — mines independently-verifiable
 * {@link VerificationAnchor}s for a skill from the open world.
 *
 * <p>This is the one genuinely new component vs. the existing acquire pipeline: the acquire-bunshin
 * finds <i>sources</i> (URLs, packs); this turns sourced content into <i>anchors</i> — the ground
 * truths a correct skill must agree with. Its output feeds {@link HarnessGenerator}, which compiles
 * the anchor facts into a frozen, deterministic {@link AnchorHarness}.</p>
 *
 * <p>The contract: every returned anchor is grounded in retrieved evidence (the leakage barrier).
 * An implementation that cannot retrieve any evidence returns an empty list — never invented
 * anchors — so the downstream harness is honestly "unverified" rather than falsely confident.</p>
 */
public interface AnchorMiner {

    /**
     * @param skillName        the skill being verified
     * @param skillDescription one line describing what it does
     * @param skillCode        the GraalJS source (so the miner knows the I/O shape)
     * @return anchors grounded in retrieved sources; empty if no evidence could be found
     */
    List<VerificationAnchor> mine(String skillName, String skillDescription, String skillCode);
}
