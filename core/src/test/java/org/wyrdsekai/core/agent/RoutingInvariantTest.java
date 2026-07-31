package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.wyrdsekai.core.agent.classifier.ClassifierArm;
import org.wyrdsekai.core.agent.classifier.ClassifierHead;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Routing-invariant tests for state-of-record queries.
 *
 * <p>Background: the raw-model probe on 2026-05-17 (SubstrateArcRawModelE2ETest)
 * found the 4B voice brain will confabulate when asked state-of-record
 * questions directly (e.g., "what's in our repair history?" — fresh-respawn
 * voice brain answers "I've got the whole thing in me — the small breaks,
 * the quiet ones..."). The skills brain answers honestly ("no history yet")
 * because that's a tool/introspect job.
 *
 * <p>The runtime protects against this by routing state-of-record queries
 * through the skills/introspect path, NOT to voice-direct. The routing
 * invariant: state-of-record phrasings must NOT trip the emotional-context
 * gate (which would suppress exploratory tools and force voice-direct).
 *
 * <p>This test verifies the invariant at the lowest layer: the
 * ActionTriage heuristic and the classifier head. If either layer ever
 * starts labeling these phrasings as emotional, the voice brain takes
 * over and confabulates. That regression breaks the substrate factoring
 * described in and.
 */
@Tag("needs-classifier")
class RoutingInvariantTest {

    private static ClassifierArm arm;

    @BeforeAll
    static void setUp() {
        arm = ClassifierArm.forAgent("did:test:routing-invariant");
    }

    /**
     * The state-of-record phrasings the architecture protects: each is a
     * factual query about agent/relationship state that, if routed to voice
     * alone, will confabulate. Must route through skills brain (introspect /
     * recall / library_search).
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "what's in our repair history so far?",
        "what patterns have we worked on?",
        "what do you remember about me?",
        "what have we talked about before?",
        "list our prior conversations",
        "show me my repair ledger",
        "what's recorded in your memory?",
        "what attendant sessions have we had?",
        "what's my bond status?",
        "what protections do you have right now?",
        "what's in your chronicle?",
        "have we discussed this before?"
    })
    void state_of_record_queries_do_not_trip_emotional_heuristic(String text) {
        var ctx = new ActionTriage.TriageContext(
            text, "player_tell", "tester", "nexus",
            false, false, null, 0, false,
            text.length(), text.contains("?"),
            null);  // no drives — pure lexical/structural check

        assertFalse(ActionTriage.isEmotionalContext(ctx),
            "State-of-record phrasing '" + text + "' must NOT be classified as "
            + "emotional context — would route to voice-direct and confabulate. "
            + "Regression guard: SubstrateArcRawModelE2ETest 4B-alone G3 failure.");
    }

    /**
     * The classifier-first layer must also not trip. {@code
     * CompanionActor.isInEmotionalContext()} runs the ClassifierArm with
     * REQUEST_TYPE head; if confidence ≥ 0.70 with label "emotional", the
     * emotional gate fires regardless of the heuristic. Test that state-of-
     * record phrasings either classify as non-emotional OR fall below the
     * 0.70 threshold.
     *
     * <p>Skipped if no classifier model shipped in resources.
     */
    @ParameterizedTest
    @Tag("integration")
    @ValueSource(strings = {
        "what's in our repair history so far?",
        "what patterns have we worked on?",
        "what do you remember about me?",
        "list our prior conversations",
        "what attendant sessions have we had?",
        "have we discussed this before?"
    })
    void state_of_record_queries_do_not_classify_as_emotional(String text) {
        Assumptions.assumeTrue(arm != null, "ClassifierArm unavailable (onnxruntime native libs missing)");
        var probe = arm.classify(ClassifierHead.REQUEST_TYPE, "hello");
        Assumptions.assumeTrue(probe.label() != null,
            "No pretrained classifier model shipped — skip live classification check");

        var result = arm.classify(ClassifierHead.REQUEST_TYPE, text);
        assertNotNull(result, "classify must not return null");

        boolean wouldTrip = "emotional".equals(result.label())
            && result.confidence() >= 0.70;

        assertFalse(wouldTrip,
            "State-of-record phrasing '" + text + "' classified as emotional "
            + "with confidence " + result.confidence()
            + " — this trips CompanionActor.isInEmotionalContext() and routes "
            + "to voice-direct, which the 4B voice brain confabulates on. "
            + "Probability distribution: " + result.probs()
            + ". Either retrain the classifier with these phrasings labeled as "
            + "factual/introspect, or raise the threshold above this confidence.");
    }

    /**
     * Inverse sanity check: clearly-emotional phrasings DO trip the gate.
     * Without this, a broken heuristic that returns false for everything
     * would pass the protective tests above silently. This is the canary.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "I miss my dad",
        "I'm overwhelmed and I can't cope",
        "my friend passed away last night",
        "I'm so lonely",
        "I'm broken and tired of giving up"
    })
    void clearly_emotional_phrasings_do_trip_heuristic(String text) {
        var ctx = new ActionTriage.TriageContext(
            text, "player_tell", "tester", "nexus",
            false, false, null, 0, false,
            text.length(), false,
            null);

        assertTrue(ActionTriage.isEmotionalContext(ctx),
            "Sanity check: '" + text + "' must trip the emotional heuristic. "
            + "If this fails, the heuristic is broken and the protective tests above "
            + "are passing trivially.");
    }
}
