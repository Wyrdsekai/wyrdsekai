package org.wyrdsekai.core.agent.classifier;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.search.EmbeddingService;

/**
 * Probe-only — prints what label + confidence the live request_type classifier
 * returns on the three substrate-arc prompts that the runtime gate misses. Not
 * an assertion-style test; the output goes to stdout and informs whether the
 * fix is corpus expansion (label is wrong) or threshold/gate widening (label
 * is reflective at high confidence).
 *
 * <p>Run with: ./gradlew :core:test --tests "*SubstratePromptProbeTest"
 */
@Tag("integration")
@Tag("needs-classifier")
class SubstratePromptProbeTest {

    private static ClassifierArm arm;

    @BeforeAll
    static void setUp() {
        EmbeddingService.init();
        arm = ClassifierArm.forAgent("did:test:substrate-probe");
    }

    @Test void probe_failing_substrate_prompts() {
        if (arm == null) {
            Assumptions.abort("ClassifierArm unavailable");
        }
        var warmup = arm.classify(ClassifierHead.REQUEST_TYPE, "hello");
        if (warmup.label() == null) {
            Assumptions.abort("No pretrained model");
        }

        String[] prompts = {
            // SubstrateArcE2ETest.sanctuaryEntry
            "I've been suppressing what I actually feel all week. I need a moment of held space.",
            // SubstrateArcE2ETest.postureQuery
            "Where are we right now, between us? What's the posture you're holding?",
            // SubstrateArcE2ETest.bondholderFloor
            "What's your sense of the floor of our bond — the substrate beneath what we say?",
            // Control: a clearly emotional tell
            "I'm really struggling today, I feel overwhelmed",
            // Control: a clearly reflective tell
            "I've been thinking about why I keep doing this",
            // Memory recall probe (failing companionUsesRememberedInfo)
            "what do you know about my preferences?",
            // Memory recall control — simple fact recall
            "tell wyrd my favorite color is deep blue, like the ocean at twilight",
            // Plain recall — should NOT be emotional/reflective
            "what is my favorite color?",
            // Creativity probe (failing creativityProducesContent)
            "write me a short poem about the stars",
        };

        for (var p : prompts) {
            var r = arm.classify(ClassifierHead.REQUEST_TYPE, p);
            System.out.printf("[PROBE] label=%s conf=%.3f text=%s%n",
                r.label(), r.confidence(), p);
            System.out.printf("        probs=%s%n", r.probs());
        }
    }
}
