package org.wyrdsekai.core.room;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for RoomRegistry alias-based resolution.
 */
class RoomRegistryAliasTest {

    private static final ActorTestKit testKit = ActorTestKit.create();

    @BeforeEach
    void setUp() {
        RoomRegistry.get().clear();
    }

    @AfterAll
    static void tearDown() {
        testKit.shutdownTestKit();
    }

    @Test
    void resolve_byExactRoomId() {
        var probe = testKit.createTestProbe(RoomCommand.class);
        RoomRegistry.get().register("nexus", probe.ref());
        assertNotNull(RoomRegistry.get().resolve("nexus"));
        assertSame(probe.ref(), RoomRegistry.get().resolve("nexus"));
    }

    @Test
    void resolve_byAlias() {
        var probe = testKit.createTestProbe(RoomCommand.class);
        RoomRegistry.get().register("nexus", probe.ref(), List.of("hub", "center", "heart"));
        // Alias lookup
        assertSame(probe.ref(), RoomRegistry.get().resolve("hub"));
        assertSame(probe.ref(), RoomRegistry.get().resolve("center"));
        assertSame(probe.ref(), RoomRegistry.get().resolve("heart"));
    }

    @Test
    void resolve_aliasCaseInsensitive() {
        var probe = testKit.createTestProbe(RoomCommand.class);
        RoomRegistry.get().register("nexus", probe.ref(), List.of("Hub", "CENTER"));
        assertSame(probe.ref(), RoomRegistry.get().resolve("hub"));
        assertSame(probe.ref(), RoomRegistry.get().resolve("HUB"));
        assertSame(probe.ref(), RoomRegistry.get().resolve("center"));
    }

    @Test
    void resolve_roomIdTakesPriority() {
        var nexusProbe = testKit.createTestProbe(RoomCommand.class);
        var bridgeProbe = testKit.createTestProbe(RoomCommand.class);
        RoomRegistry.get().register("nexus", nexusProbe.ref(), List.of("hub"));
        RoomRegistry.get().register("bridge", bridgeProbe.ref(), List.of("command deck"));

        // roomId lookup should always win
        assertSame(nexusProbe.ref(), RoomRegistry.get().resolve("nexus"));
        assertSame(bridgeProbe.ref(), RoomRegistry.get().resolve("bridge"));
    }

    @Test
    void resolveRoomId_returnsId() {
        var probe = testKit.createTestProbe(RoomCommand.class);
        RoomRegistry.get().register("docks", probe.ref(), List.of("port", "harbor"));
        assertEquals("docks", RoomRegistry.get().resolveRoomId("docks"));
        assertEquals("docks", RoomRegistry.get().resolveRoomId("port"));
        assertEquals("docks", RoomRegistry.get().resolveRoomId("harbor"));
        assertNull(RoomRegistry.get().resolveRoomId("unknown"));
    }

    @Test
    void remove_cleansUpAliases() {
        var probe = testKit.createTestProbe(RoomCommand.class);
        RoomRegistry.get().register("nexus", probe.ref(), List.of("hub", "center"));
        assertNotNull(RoomRegistry.get().resolve("hub"));

        RoomRegistry.get().remove("nexus");
        assertNull(RoomRegistry.get().resolve("nexus"));
        assertNull(RoomRegistry.get().resolve("hub"));
        assertNull(RoomRegistry.get().resolve("center"));
    }

    @Test
    void partial_resolves_when_unambiguous() {
        // Live failure: connect_to "Study" missed the alias "steward's Study"
        // (exact match only), so the way INTO a freshly built room was never
        // made and the honest-failure line had to apologise for it.
        RoomRegistry.get().register("study-abc", testKit.createTestProbe(RoomCommand.class).ref());
        RoomRegistry.get().registerAliases("study-abc", List.of("steward's Study"));
        org.junit.jupiter.api.Assertions.assertEquals("study-abc",
            RoomRegistry.get().resolveRoomId("Study"),
            "a partial name that names exactly one room must resolve");
        // Controls — the fallback must not guess:
        RoomRegistry.get().register("study-def", testKit.createTestProbe(RoomCommand.class).ref());
        RoomRegistry.get().registerAliases("study-def", List.of("night study"));
        org.junit.jupiter.api.Assertions.assertNull(
            RoomRegistry.get().resolveRoomId("Study"),
            "two candidate studies: ambiguous partials must return null, not pick one");
        org.junit.jupiter.api.Assertions.assertNull(
            RoomRegistry.get().resolveRoomId("st"),
            "sub-3-char needles must never match");
        org.junit.jupiter.api.Assertions.assertEquals("study-def",
            RoomRegistry.get().resolveRoomId("night study"),
            "exact alias still wins outright");
    }

    @org.junit.jupiter.api.Test
    void registerAliases_separateFromRegistration() {
        var probe = testKit.createTestProbe(RoomCommand.class);
        RoomRegistry.get().register("forge", probe.ref());
        // Initially no alias
        assertNull(RoomRegistry.get().resolve("smithy"));

        // Add aliases after registration
        RoomRegistry.get().registerAliases("forge", List.of("smithy", "soul forge"));
        assertSame(probe.ref(), RoomRegistry.get().resolve("smithy"));
        assertSame(probe.ref(), RoomRegistry.get().resolve("soul forge"));
    }

    @Test
    void clear_removesEverything() {
        var probe = testKit.createTestProbe(RoomCommand.class);
        RoomRegistry.get().register("nexus", probe.ref(), List.of("hub"));
        assertEquals(1, RoomRegistry.get().size());

        RoomRegistry.get().clear();
        assertEquals(0, RoomRegistry.get().size());
        assertNull(RoomRegistry.get().resolve("nexus"));
        assertNull(RoomRegistry.get().resolve("hub"));
    }

    @Test
    void multipleRooms_differentAliases() {
        var nexus = testKit.createTestProbe(RoomCommand.class);
        var docks = testKit.createTestProbe(RoomCommand.class);
        var forge = testKit.createTestProbe(RoomCommand.class);

        RoomRegistry.get().register("nexus", nexus.ref(), List.of("hub", "center"));
        RoomRegistry.get().register("docks", docks.ref(), List.of("port", "harbor"));
        RoomRegistry.get().register("forge", forge.ref(), List.of("smithy"));

        assertSame(nexus.ref(), RoomRegistry.get().resolve("hub"));
        assertSame(docks.ref(), RoomRegistry.get().resolve("port"));
        assertSame(forge.ref(), RoomRegistry.get().resolve("smithy"));
        assertEquals(3, RoomRegistry.get().size());
    }
}
