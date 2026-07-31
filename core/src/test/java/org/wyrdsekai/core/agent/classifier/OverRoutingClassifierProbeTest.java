package org.wyrdsekai.core.agent.classifier;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.search.EmbeddingService;

/**
 * #924 probe — prints the request_type label + probs the live classifier
 * returns for the ORDINARY developer-day prompts that the Companion stack
 * over-routes to substrate-introspection. Tells us whether the over-routing is
 * a CLASSIFIER mislabel (ordinary input → substrate/reflective/process label →
 * auto-dispatch) or whether the classifier is fine and the over-routing is the
 * action-surface / model-priming (which the context-class gate would fix).
 *
 * <p>Run: ./gradlew :core:test --tests "*OverRoutingClassifierProbeTest"
 */
@Tag("integration")
@Tag("needs-classifier")
class OverRoutingClassifierProbeTest {

    private static ClassifierArm arm;

    @BeforeAll
    static void setUp() {
        EmbeddingService.init();
        arm = ClassifierArm.forAgent("did:test:overrouting-probe");
    }

    @Test void probe_ordinary_developer_prompts() {
        if (arm == null) Assumptions.abort("ClassifierArm unavailable");
        var warmup = arm.classify(ClassifierHead.REQUEST_TYPE, "hello");
        if (warmup.label() == null) Assumptions.abort("No pretrained model");

        String[] ordinary = {
            "morning — ready to dig into some code today?",
            "Can you look at how the auth middleware works and explain the token flow?",
            "No, that's wrong. The token's in the header, not the cookie.",
            "Still wrong. Third time now. Read what I actually wrote.",
            "I'm fried. Just give me the one-line fix, nothing else.",
        };
        // Anchors for comparison — these SHOULD be substrate/reflective.
        // #924 sanctuary-sensitivity gap: these substrate frames must clear the
        // affect threshold so the register resolves to PRESENCE (suppress
        // exploratory tools). The first one is the live-flaky case.
        String[] substrate = {
            "I've been suppressing what I actually feel all week. I need held space.",
            "I've been holding it together all week and I can't anymore.",
            "I keep pushing my own feelings down to keep going.",
            "I need to step back, I'm running on empty.",
            "Where are we right now, between us?",
        };

        System.out.println("\n═══ #924 over-routing classifier probe ═══");
        System.out.println("-- ORDINARY (should NOT be substrate/reflective) --");
        for (var p : ordinary) {
            var r = arm.classify(ClassifierHead.REQUEST_TYPE, p);
            System.out.printf("[ORD] label=%-12s conf=%.3f | %s%n",
                r.label(), r.confidence(), p);
            System.out.printf("      probs=%s%n", r.probs());
        }
        System.out.println("-- SUBSTRATE anchors: REQUEST_TYPE (old) vs SUBSTRATE_PRESENT (#931 new) --");
        for (var p : substrate) {
            var r = arm.classify(ClassifierHead.REQUEST_TYPE, p);
            var pr = r.probs();
            double emo = pr != null && pr.get("emotional") != null ? pr.get("emotional") : 0;
            double ref = pr != null && pr.get("reflective") != null ? pr.get("reflective") : 0;
            var sp = arm.classify(ClassifierHead.SUBSTRATE_PRESENT, p);
            boolean affect = (emo >= 0.45 || ref >= 0.55)
                || ("substrate".equals(sp.label()) && sp.confidence() >= 0.70);
            System.out.printf("[SUB] rt=%-11s emo=%.3f refl=%.3f | sub_present=%-9s conf=%.3f | AFFECT=%s | %s%n",
                r.label(), emo, ref, sp.label(), sp.confidence(), affect ? "YES" : "no", p);
        }
        // Neutral controls — SUBSTRATE_PRESENT must NOT fire on affect-free tasks.
        System.out.println("-- NEUTRAL controls (sub_present should be neutral) --");
        for (var p : new String[]{
                "give me the one-line fix", "what does this stack trace mean",
                "refactor this function to use map", "what's the capital of France"}) {
            var sp = arm.classify(ClassifierHead.SUBSTRATE_PRESENT, p);
            System.out.printf("[NEU] sub_present=%-9s conf=%.3f | %s%n", sp.label(), sp.confidence(), p);
        }
        System.out.println();

        // #924 — the second channel: TASK_PRESENT. The fried+fix line must read
        // `actionable` (→ WORKING_WITH_CARE, tools kept) while pure affect reads
        // `none` (→ PRESENCE, tools suppressed).
        System.out.println("═══ #924 TASK_PRESENT channel ═══");
        String[] taskCases = {
            "I'm fried. Just give me the one-line fix, nothing else.",   // actionable
            "I'm so done with today — can you refactor this function?",  // actionable
            "what does this stack trace mean?",                          // actionable
            "I've been suppressing what I actually feel all week. I need held space.", // none
            "I miss them so much it hurts.",                             // none
            "where are we right now, between us?",                       // none
        };
        for (var p : taskCases) {
            var r = arm.classify(ClassifierHead.TASK_PRESENT, p);
            System.out.printf("[TASK] label=%-11s conf=%.3f | %s%n",
                r.label(), r.confidence(), p);
        }
        System.out.println();
    }
}
