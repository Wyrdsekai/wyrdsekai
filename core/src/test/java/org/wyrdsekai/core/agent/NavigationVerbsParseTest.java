package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.model.Exit;
import org.wyrdsekai.core.room.ZoneTopology;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parser-level tests for the new same-zone navigation verbs introduced in
 * Verifies that {"action":"travel_to",...} and
 * {"action":"teleport_to",...} JSON outputs from the model parse into the
 * correct {@link ActionParser.AgentAction} records — and that the action-type
 * registry / policy / type-of switches all recognise them.
 *
 * <p>Behavioural tests for the handlers (cross-zone redirect, knownRooms gating,
 * pathfind walk) live as integration tests; this is a fast unit pass to catch
 * any drift in the parser/policy surface.
 */
class NavigationVerbsParseTest {

    @Test
    void travelTo_parsesIntoTravelToRecord() {
        var json = "```json\n{\"action\":\"travel_to\",\"target\":\"workshop\",\"reason\":\"need templates\"}\n```";
        var action = ActionParser.parse(json);
        assertThat(action).isInstanceOf(ActionParser.AgentAction.TravelTo.class);
        var tt = (ActionParser.AgentAction.TravelTo) action;
        assertThat(tt.target()).isEqualTo("workshop");
        assertThat(tt.reason()).isEqualTo("need templates");
    }

    @Test
    void teleportTo_parsesIntoTeleportToRecord() {
        var json = "```json\n{\"action\":\"teleport_to\",\"target\":\"hearth\"}\n```";
        var action = ActionParser.parse(json);
        assertThat(action).isInstanceOf(ActionParser.AgentAction.TeleportTo.class);
        var tp = (ActionParser.AgentAction.TeleportTo) action;
        assertThat(tp.target()).isEqualTo("hearth");
    }

    @Test
    void travelTo_blankTargetIsRejected() {
        var json = "```json\n{\"action\":\"travel_to\",\"target\":\"\"}\n```";
        var action = ActionParser.parse(json);
        // Blank target → no action emitted (parser refuses to construct).
        assertThat(action).isNull();
    }

    @Test
    void actionTypeOf_recognisesNewVerbs() {
        var travel = new ActionParser.AgentAction.TravelTo("workshop", "");
        var teleport = new ActionParser.AgentAction.TeleportTo("hearth", "");
        assertThat(ActionPolicy.actionTypeOf(travel)).isEqualTo("travel_to");
        assertThat(ActionPolicy.actionTypeOf(teleport)).isEqualTo("teleport_to");
    }

    @Test
    void actionPolicy_registryIncludesNewVerbs() {
        assertThat(ActionPolicy.REGISTRY).containsKey("travel_to");
        assertThat(ActionPolicy.REGISTRY).containsKey("teleport_to");
        // Both Tier-0 navigation, like go_to_room.
        var travelPolicy = ActionPolicy.REGISTRY.get("travel_to");
        var teleportPolicy = ActionPolicy.REGISTRY.get("teleport_to");
        assertThat(travelPolicy.requiredTier()).isEqualTo(0);
        assertThat(travelPolicy.domain()).isEqualTo("navigation");
        assertThat(teleportPolicy.requiredTier()).isEqualTo(0);
        assertThat(teleportPolicy.domain()).isEqualTo("navigation");
    }

    @Test
    void skillCostMatrix_assignsMonotonicallyIncreasingFloors() {
        // go_to_room < travel_to < teleport_to — skipping the world has a price.
        var goFloor = SkillCostMatrix.floorFor("go_to_room");
        var travelFloor = SkillCostMatrix.floorFor("travel_to");
        var teleportFloor = SkillCostMatrix.floorFor("teleport_to");
        assertThat(goFloor).isLessThan(travelFloor);
        assertThat(travelFloor).isLessThan(teleportFloor);
    }

    // ── Phase 1 bondholder-fallback gate ────────────────────────────────────
    // looksLikeBondholderTarget is the positive-evidence gate that prevents
    // the silent misdirection bug (JA Ember task8 2026-05-06). Bare room
    // names must NOT trigger the fallback; explicit "rejoin user" tokens MUST.

    @Test
    void looksLikeBondholderTarget_genericTokens() {
        assertThat(CompanionActor.looksLikeBondholderTarget("back", "operator")).isTrue();
        assertThat(CompanionActor.looksLikeBondholderTarget("home", "operator")).isTrue();
        assertThat(CompanionActor.looksLikeBondholderTarget("return", "operator")).isTrue();
        assertThat(CompanionActor.looksLikeBondholderTarget("requester", "operator")).isTrue();
        assertThat(CompanionActor.looksLikeBondholderTarget("owner", "operator")).isTrue();
        assertThat(CompanionActor.looksLikeBondholderTarget("you", "operator")).isTrue();
    }

    @Test
    void looksLikeBondholderTarget_requesterNameMatch() {
        assertThat(CompanionActor.looksLikeBondholderTarget("operator", "operator")).isTrue();
        assertThat(CompanionActor.looksLikeBondholderTarget("Masumi", "operator")).isTrue();
        assertThat(CompanionActor.looksLikeBondholderTarget("operator's study", "operator")).isTrue();
        assertThat(CompanionActor.looksLikeBondholderTarget("operator's home", "operator")).isTrue();
        assertThat(CompanionActor.looksLikeBondholderTarget("operator room", "operator")).isTrue();
    }

    @Test
    void looksLikeBondholderTarget_bareRoomNamesRejected() {
        // The bug: previously these would trigger the fallback, silently
        // teleporting the agent to the requester's Study while pretending the
        // navigation succeeded. Must now return false → handler emits
        // "I can't find a way to get to <room> from here" with exit hints.
        assertThat(CompanionActor.looksLikeBondholderTarget("workshop", "operator")).isFalse();
        assertThat(CompanionActor.looksLikeBondholderTarget("library", "operator")).isFalse();
        assertThat(CompanionActor.looksLikeBondholderTarget("nexus", "operator")).isFalse();
        assertThat(CompanionActor.looksLikeBondholderTarget("hearth", "operator")).isFalse();
        assertThat(CompanionActor.looksLikeBondholderTarget("garden", "operator")).isFalse();
    }

    @Test
    void looksLikeBondholderTarget_nullAndBlankAreFalse() {
        assertThat(CompanionActor.looksLikeBondholderTarget(null, "operator")).isFalse();
        assertThat(CompanionActor.looksLikeBondholderTarget("", "operator")).isFalse();
        assertThat(CompanionActor.looksLikeBondholderTarget("   ", "operator")).isFalse();
    }

    @Test
    void looksLikeBondholderTarget_nullRequesterNameStillAcceptsGenericTokens() {
        // Name-less requester (anonymous flow) — generic tokens still work.
        assertThat(CompanionActor.looksLikeBondholderTarget("home", null)).isTrue();
        assertThat(CompanionActor.looksLikeBondholderTarget("back", "")).isTrue();
        // ...but bare room names still don't.
        assertThat(CompanionActor.looksLikeBondholderTarget("workshop", null)).isFalse();
    }

    // ── Phase 2 ZoneTopology pathfinding (foundation for travel_to) ─────────
    // travel_to's handler delegates to ZoneTopology.pathBetween + directionBetween.
    // These exercise that the foundation works as the handlers expect.

    @Test
    void zoneTopology_pathBetweenLinearGraph_walksEveryHop() {
        // nexus → north → hearth → east → workshop
        var seeds = List.of(
            new ZoneTopology.RoomSeed("nexus", "Nexus", "alpha",
                List.of(new Exit("north", "hearth", "Hearth"))),
            new ZoneTopology.RoomSeed("hearth", "Hearth", "alpha",
                List.of(
                    new Exit("south", "nexus", "The Nexus"),
                    new Exit("east", "workshop", "Workshop"))),
            new ZoneTopology.RoomSeed("workshop", "Workshop", "alpha",
                List.of(new Exit("west", "hearth", "Hearth")))
        );
        var topo = ZoneTopology.build(seeds);

        var path = topo.pathBetween("nexus", "workshop");
        assertThat(path).isPresent();
        assertThat(path.get()).containsExactly("nexus", "hearth", "workshop");

        // travel_to walks each hop using directionBetween — verify the chain.
        assertThat(topo.directionBetween("nexus", "hearth")).hasValue("north");
        assertThat(topo.directionBetween("hearth", "workshop")).hasValue("east");
    }

    @Test
    void zoneTopology_pathBetween_disconnected_isEmpty() {
        // Two-component graph: travel_to should fail with "no path" message,
        // model can fall back to teleport_to.
        var seeds = List.of(
            new ZoneTopology.RoomSeed("nexus", "Nexus", "alpha",
                List.of()),
            new ZoneTopology.RoomSeed("island", "Island", "alpha",
                List.of())
        );
        var topo = ZoneTopology.build(seeds);

        assertThat(topo.pathBetween("nexus", "island")).isEmpty();
    }
}
