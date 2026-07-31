package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three authoring verbs {@code docs/public/AUTHORING.md} actually names are
 * item-tool builtins, not parsed {@code AgentAction}s, so they were dispatched
 * straight from {@code builtinHandler()} and reached neither
 * {@code enforceActionPolicy} nor {@link AutonomyGate}.
 *
 * <p>Net effect before this: the path the docs recommend had {@code
 * requiredTier=0} and no consent axis, while the equivalent parsed verb
 * ({@code create_room}) was tier 3 + FORBIDDEN. The documented path bypassed the
 * gate the undocumented one enforced.</p>
 */
class DocumentedAuthoringVerbsGatedTest {

    @Test
    @DisplayName("the documented verbs have real policy rows, not DEFAULT")
    void notFallingThroughToDefault() {
        int defaultTier = ActionPolicy.forAction("some_verb_that_does_not_exist").requiredTier();
        for (var verb : new String[] {
                "create_room_from_template", "craft_from_template", "create_zone" }) {
            var pol = ActionPolicy.forAction(verb);
            assertEquals(verb, pol.actionType(),
                verb + " must have its own registry row");
            assertNotEquals(defaultTier, pol.requiredTier(),
                verb + " still has the DEFAULT tier — the growth gate is inert");
            assertTrue(pol.requiredTier() >= 2, verb + " tier too low: " + pol.requiredTier());
            assertEquals("creation", pol.domain());
        }
    }

    @Test
    @DisplayName("each mirrors the parsed verb it stands in for")
    void mirrorsItsParsedCounterpart() {
        // The whole bug was these two disagreeing.
        assertEquals(ActionPolicy.forAction("create_room").requiredTier(),
            ActionPolicy.forAction("create_room_from_template").requiredTier(),
            "create_room_from_template must not be a cheaper way to do create_room");
        assertEquals(ActionPolicy.autonomyTierFor("create_room"),
            ActionPolicy.autonomyTierFor("create_room_from_template"),
            "…nor a more permissive one");
        assertEquals(ActionPolicy.forAction("craft_item").requiredTier(),
            ActionPolicy.forAction("craft_from_template").requiredTier());
        assertEquals(ActionPolicy.autonomyTierFor("craft_item"),
            ActionPolicy.autonomyTierFor("craft_from_template"));
    }

    @Test
    @DisplayName("crafting stays autonomous; reshaping the world does not")
    void tiersAreTheOnesIntended() {
        // Control: the gate is not uniformly restrictive. Crafting an item on her
        // own time is VISIBLE — she may, and the steward sees it. If this ever
        // flips to FORBIDDEN her own-time making stops, which is not the intent.
        assertEquals(ActionPolicy.AutonomyTier.VISIBLE,
            ActionPolicy.autonomyTierFor("craft_from_template"));
        for (var verb : new String[] { "create_room_from_template", "create_zone" }) {
            assertEquals(ActionPolicy.AutonomyTier.FORBIDDEN,
                ActionPolicy.autonomyTierFor(verb),
                verb + " reshapes the household's world — never unprompted");
        }
    }
}
