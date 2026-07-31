package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CompanionActor#requiresCompoundWork} against the phrasings that
 * actually failed in the field, plus controls proving it does not fire on
 * ordinary conversation.
 *
 * <p>Both positive cases are transcribed, not invented: the first is
 * {@code docs/public/AUTHORING.md} §1's own worked example, the second and third
 * are what the bondholder really typed on second-node on 2026-07-29. All three read
 * {@code task_present: none} with high confidence, routed to the toolless voice
 * tier, and produced warm prose and no greenhouse.</p>
 */
class RequiresCompoundWorkTest {

    @Test
    @DisplayName("AUTHORING.md §1's own example dispatches")
    void documentedExample() {
        assertTrue(CompanionActor.requiresCompoundWork(
            "Could you make us a room off the Hearth for working on the garden? "
            + "Somewhere with a table."));
    }

    @Test
    @DisplayName("the real bondholder greenhouse requests dispatch")
    void realFieldRequests() {
        assertTrue(CompanionActor.requiresCompoundWork(
            "i want a room that connects to this one. would you create a "
            + "greenhouse filled with plants for me"),
            "the request that started this whole investigation");
        assertTrue(CompanionActor.requiresCompoundWork(
            "so i would love to have a greenhouse. mia, can you create me a "
            + "room - greenhouse with lots of plants - and connect it to this "
            + "room (Nexus)"));
    }

    @Test
    @DisplayName("ordinary conversation does not spawn background jobs")
    void controls() {
        for (var benign : new String[] {
            "good morning, how did you sleep?",
            "what do you think about the weather today",
            "i had a rough day at work",
            "tell me about the book you were reading",
            "make sure you rest tonight",          // authoring verb, no authorable noun
            "make sure you sleep tonight",          // "sleep" came from voluntary_sleep
            "please make that call in the morning", // "call" came from emergency_call
            "i think you should make amends with her",
            "can you think deeply about this for me",
            "did you build anything interesting?", // past tense, no noun
        }) {
            assertFalse(CompanionActor.requiresCompoundWork(benign),
                "should not dispatch on: " + benign);
        }
        assertFalse(CompanionActor.requiresCompoundWork(null));
        assertFalse(CompanionActor.requiresCompoundWork("   "));
    }

    @Test
    @DisplayName("destructive phrasing is never routed into a background job")
    void noDestructiveVerbs() {
        for (var destructive : new String[] {
            "delete the greenhouse room",
            "remove that room please",
            "get rid of the script on the workbench",
        }) {
            assertFalse(CompanionActor.requiresCompoundWork(destructive),
                "COMPOUND_WORK_VERBS is additive-only by design: " + destructive);
        }
    }

    @Test
    @DisplayName("the noun set is derived from the registry, not hand-listed")
    void nounsComeFromPolicy() {
        // If create_room ever leaves the !concurrencySafe half of the registry,
        // "room" silently drops out of the detector and the greenhouse breaks
        // again. Pin the relationship, not the literal.
        assertFalse(ActionPolicy.forAction("create_room").concurrencySafe(),
            "create_room must stay !concurrencySafe — that predicate is what "
            + "puts 'room' in COMPOUND_WORK_NOUNS and what routes it to a bunshin");
        assertTrue(CompanionActor.requiresCompoundWork("create a watcher for the front door"),
            "'watcher' should be derived from create_watcher without being listed");
        assertTrue(CompanionActor.requiresCompoundWork("craft me a lantern item"),
            "'item' should be derived from craft_item");
    }
}
