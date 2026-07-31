package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bunshin tool surface, and the human-directed bypass, tested in BOTH
 * directions.
 *
 * <p>{@code CompanionActor.bunshinToolSurface} is private and needs a live
 * actor, so these assertions re-derive the surface from the same public
 * {@link ActionPolicy} facts the production code reads. That makes the test a
 * guard on the <i>policy data</i> — which is where the two bugs this replaces
 * actually lived: {@code create_room} silently vanishing from the surface
 * because it is FORBIDDEN-tier, and the bypass being blanket rather than
 * scoped.</p>
 */
class BunshinToolSurfaceTest {

    /** Mirrors CompanionActor.BUNSHIN_EXCLUDED. */
    private static final Set<String> EXCLUDED = Set.of(
        "dispatch_bunshin", "delegate", "voluntary_sleep",
        "emergency_call", "go_to_bondholder",
        "craft_from_template", "codex_action", "configure_channel");

    /** Mirrors CompanionActor.BUNSHIN_HUMAN_DIRECTED_VERBS. */
    private static final Set<String> HUMAN_DIRECTED = Set.of("create_room");

    private static Set<String> surface(boolean humanDirected) {
        return ActionPolicy.REGISTRY.values().stream()
            .filter(pol -> !pol.concurrencySafe())
            .map(ActionPolicy::actionType)
            .filter(n -> ActionPolicy.autonomyTierFor(n) != ActionPolicy.AutonomyTier.FORBIDDEN
                || (humanDirected && HUMAN_DIRECTED.contains(n)))
            .filter(n -> !EXCLUDED.contains(n))
            .collect(Collectors.toSet());
    }

    @Test
    @DisplayName("a bondholder-directed bunshin can create a room (docs/public/ROOMS.md)")
    void humanDirectedSurfaceIncludesCreateRoom() {
        assertTrue(surface(true).contains("create_room"),
            "AUTHORING.md §1 and ROOMS.md promise a person can ask for a room. "
            + "If create_room is missing here the greenhouse cannot be built.");
    }

    @Test
    @DisplayName("on her own time it cannot — FORBIDDEN means never unprompted")
    void autonomousSurfaceExcludesCreateRoom() {
        assertFalse(surface(false).contains("create_room"),
            "create_room is FORBIDDEN tier: reshaping the household's world "
            + "unprompted needs a person to have asked.");
    }

    @Test
    @DisplayName("the bypass is scoped — a build request cannot reach bond or identity verbs")
    void bypassDoesNotAdmitTheOtherForbiddenVerbs() {
        var granted = surface(true);
        for (var verb : Set.of("release_bond", "zone_command", "destroy_tool",
                "revoke_summon_key", "promote_familiar", "retire_form",
                "set_autonomy_preference", "set_deviation_thresholds")) {
            assertEquals(ActionPolicy.AutonomyTier.FORBIDDEN,
                ActionPolicy.autonomyTierFor(verb), verb + " should be FORBIDDEN");
            assertFalse(granted.contains(verb),
                verb + " must NOT be reachable from a human-directed bunshin — the "
                + "bunshin picks its own verb, so a blanket bypass would be a "
                + "confused deputy.");
        }
        // The bypass adds exactly one verb, nothing more.
        var added = new java.util.TreeSet<>(granted);
        added.removeAll(surface(false));
        assertEquals(HUMAN_DIRECTED, added,
            "the human-directed surface should differ from the autonomous one by "
            + "exactly BUNSHIN_HUMAN_DIRECTED_VERBS");
    }

    @Test
    @DisplayName("the surface is compound-only: no atomic action leaks into a background job")
    void surfaceIsCompoundOnly() {
        for (var name : surface(true)) {
            assertFalse(ActionPolicy.forAction(name).concurrencySafe(),
                name + " is concurrencySafe, so it belongs inline on her own turn "
                + "where the round trip IS the value — not as a 30-second background job");
        }
        // Control: the surface is not vacuously small, and the atomic verbs the
        // voice tier keeps are genuinely absent.
        assertTrue(surface(true).size() >= 20, "surface collapsed to " + surface(true).size());
        for (var atomic : Set.of("remember", "go_to_room", "examine", "recall")) {
            assertFalse(surface(true).contains(atomic), atomic + " should stay inline");
        }
    }
}
