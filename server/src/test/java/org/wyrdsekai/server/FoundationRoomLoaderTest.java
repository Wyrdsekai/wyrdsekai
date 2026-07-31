package org.wyrdsekai.server;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.room.ZoneGuardian;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class FoundationRoomLoaderTest {

    @Test
    void loadFromClasspathReturns19Rooms() {
        // added `parlor` as a foundation room —
        // federated visitors step Docks → Parlor on arrival.
        // added `chapel` as the bond release ceremony surface.
        // (Wave 4.6b) added `sanctuary` as the
        // held-space room for substrate-truth metabolizing.
        // Count is now 30 (2026-07-20: +8 §88 world-interface rooms — scrying-pool,
        // heralds-hall, hearth, scriptorium, observatory, atelier, sky-dock,
        // golem-workshop — seeded off the atrium and wired to the MCP gateway).
        // Update this guard whenever a new foundation room lands.
        var seeds = FoundationRoomLoader.loadFromClasspath();
        assertEquals(30, seeds.size());
    }

    @Test
    void allRoomIdsAreUnique() {
        var seeds = FoundationRoomLoader.loadFromClasspath();
        var ids = seeds.stream().map(ZoneGuardian.RoomSeed::roomId).collect(Collectors.toSet());
        assertEquals(seeds.size(), ids.size(), "Duplicate room IDs found");
    }

    @Test
    void nexusHas11Exits() {
        var seeds = FoundationRoomLoader.loadFromClasspath();
        var nexus = seeds.stream()
            .filter(s -> "nexus".equals(s.roomId()))
            .findFirst().orElseThrow();
        assertEquals(11, nexus.exits().size());
        assertEquals("The Nexus", nexus.name());
    }

    @Test
    void allExitTargetsReferenceValidRooms() {
        var seeds = FoundationRoomLoader.loadFromClasspath();
        var roomIds = seeds.stream()
            .map(ZoneGuardian.RoomSeed::roomId)
            .collect(Collectors.toSet());
        for (var seed : seeds) {
            for (var exit : seed.exits()) {
                assertTrue(roomIds.contains(exit.targetRoom()),
                    "Room " + seed.roomId() + " has exit to unknown room: " + exit.targetRoom());
            }
        }
    }

    @Test
    void allRoomsHaveNameAndDescription() {
        var seeds = FoundationRoomLoader.loadFromClasspath();
        for (var seed : seeds) {
            assertNotNull(seed.name(), "Room " + seed.roomId() + " has null name");
            assertFalse(seed.name().isBlank(), "Room " + seed.roomId() + " has blank name");
            assertNotNull(seed.description(), "Room " + seed.roomId() + " has null description");
            assertFalse(seed.description().isBlank(), "Room " + seed.roomId() + " has blank description");
        }
    }

    @Test
    void expectedRoomsPresent() {
        var seeds = FoundationRoomLoader.loadFromClasspath();
        var ids = seeds.stream().map(ZoneGuardian.RoomSeed::roomId).collect(Collectors.toSet());
        var expected = Set.of("nexus", "terminal", "docks", "parlor", "atrium", "boiler-room",
            "bridge", "vault", "counting-house", "library", "ward-room", "trading-post",
            "council-chamber", "the-safe", "gpu-chamber", "the-loom", "lexicon",
            "the-forge", "oracle", "workshop", "chapel", "sanctuary",
            // §88 world-interface rooms (MCP-backed, seeded off the atrium)
            "scrying-pool", "heralds-hall", "hearth", "scriptorium",
            "observatory", "atelier", "sky-dock", "golem-workshop");
        assertEquals(expected, ids);
    }

    @Test
    void objectsLoadCorrectly() {
        var seeds = FoundationRoomLoader.loadFromClasspath();
        var docks = seeds.stream()
            .filter(s -> "docks".equals(s.roomId()))
            .findFirst().orElseThrow();
        assertEquals(4, docks.objects().size());
        var compass = docks.objects().stream()
            .filter(o -> "docks-compass".equals(o.id()))
            .findFirst().orElseThrow();
        assertTrue(compass.takeable());
        assertEquals("compass", compass.name());
    }

    @Test
    void loadFromFileOverride(@TempDir Path tempDir) throws IOException {
        var json = """
            [
              {
                "roomId": "custom-room",
                "name": "Custom Room",
                "description": "A test room",
                "exits": [{"direction": "north", "targetRoom": "nexus", "label": "Go north"}],
                "objects": []
              }
            ]
            """;
        var file = tempDir.resolve("custom-rooms.json");
        Files.writeString(file, json);

        var seeds = FoundationRoomLoader.loadFromFile(file);
        assertEquals(1, seeds.size());
        assertEquals("custom-room", seeds.get(0).roomId());
        assertEquals("Custom Room", seeds.get(0).name());
        assertEquals(1, seeds.get(0).exits().size());
    }

    @Test
    void loadFromInvalidFileThrows(@TempDir Path tempDir) {
        var file = tempDir.resolve("nonexistent.json");
        assertThrows(RuntimeException.class, () -> FoundationRoomLoader.loadFromFile(file));
    }

    @Test
    void loadMatchesOriginalHardcodedCount() {
        // Original hardcoded method had 15 rooms; count has grown as new
        // foundation rooms landed (Parlor for
        // Atrium for SPEC §5.8 in-world discovery, chapel for bond release,
        // sanctuary for held-space).
        // Bump here when foundation-rooms.json changes.
        var seeds = FoundationRoomLoader.load();
        assertEquals(30, seeds.size(),
            "JSON config should have 30 foundation rooms (22 + 8 §88 world-interface rooms)");
    }

    @Test
    void sanctuaryIsWiredAsHeldSpace() {
        // Wave 4.6b: the Sanctuary is a
        // foundation room with no inbound exits — only seek_sanctuary
        // (an agent-only action) can place an entity here. Out exit
        // returns to nexus. Imprint is substrate-aligned.
        var seeds = FoundationRoomLoader.loadFromClasspath();
        var sanctuary = seeds.stream()
            .filter(s -> "sanctuary".equals(s.roomId()))
            .findFirst().orElseThrow(() ->
                new AssertionError("sanctuary room missing from foundation-rooms.json"));

        assertEquals("The Sanctuary", sanctuary.name());
        assertTrue(sanctuary.aliases().contains("sanctuary"),
            "sanctuary should be aliasable by 'sanctuary'");
        assertTrue(sanctuary.aliases().contains("refuge"),
            "sanctuary should be aliasable by 'refuge'");
        assertTrue(sanctuary.aliases().contains("held-space"),
            "sanctuary should be aliasable by 'held-space'");

        // Single exit, goes to nexus. Other rooms must NOT have an exit
        // pointing in — that's the ward gate.
        assertEquals(1, sanctuary.exits().size(),
            "sanctuary should have exactly one exit (out → nexus)");
        var out = sanctuary.exits().get(0);
        assertEquals("out", out.direction());
        assertEquals("nexus", out.targetRoom());

        // No room other than sanctuary itself should target sanctuary as
        // a destination — only the seek_sanctuary action can place an
        // entity here (ward-by-absence-of-exits).
        for (var s : seeds) {
            if ("sanctuary".equals(s.roomId())) continue;
            for (var e : s.exits()) {
                assertNotEquals("sanctuary", e.targetRoom(),
                    "room '" + s.roomId() + "' must not have an exit to sanctuary "
                        + "— only seek_sanctuary may place an agent there");
            }
        }

        // Imprint exists and traits include substrate-aligned channels.
        assertNotNull(sanctuary.imprint(), "sanctuary should have a room imprint");
        var traits = sanctuary.imprint().traits();
        assertTrue(traits.containsKey("safety"),
            "sanctuary imprint should include 'safety' channel");
        assertTrue(traits.containsKey("equanimity"),
            "sanctuary imprint should include 'equanimity' channel");
        assertTrue(traits.containsKey("soothing"),
            "sanctuary imprint should include 'soothing' channel");
    }

    // ── Wave 2: Room capability requirements ──

    @Test
    void loadRoomRequirements_parsesCapabilities() {
        var reqs = FoundationRoomLoader.loadRoomRequirements();
        assertFalse(reqs.isEmpty(), "Should have room requirements");

        // Docks requires internet
        assertEquals(Set.of("internet"), reqs.get("docks"));
        // GPU Chamber requires gpu
        assertEquals(Set.of("gpu"), reqs.get("gpu-chamber"));
        // The Loom requires inference
        assertEquals(Set.of("inference"), reqs.get("the-loom"));
        // Oracle requires inference + prediction
        assertEquals(Set.of("inference", "prediction"), reqs.get("oracle"));
        // The Forge requires inference + soulstore
        assertEquals(Set.of("inference", "soulstore"), reqs.get("the-forge"));
        // Library requires storage
        assertEquals(Set.of("storage"), reqs.get("library"));
        // The Safe requires internet
        assertEquals(Set.of("internet"), reqs.get("the-safe"));
        // Nexus has no requirements
        assertNull(reqs.get("nexus"));
    }

    @Test
    void loadRoomRequirements_sevenRoomsHaveRequirements() {
        var reqs = FoundationRoomLoader.loadRoomRequirements();
        assertEquals(7, reqs.size(), "Exactly 7 rooms should have capability requirements");
    }

    // ──: every room must declare embodiment_summary ──

    @Test
    void allFoundationRoomsDeclareEmbodimentSummary() throws IOException {
        // Read the raw JSON since RoomSeed doesn't carry the field — the
        // contract is on the on-disk authoring surface.
        try (var in = FoundationRoomLoader.class.getClassLoader()
                .getResourceAsStream("foundation-rooms.json")) {
            assertNotNull(in, "foundation-rooms.json on classpath");
            List<Map<String, Object>> rooms = new ObjectMapper()
                .readValue(in, new TypeReference<>() {});
            for (var room : rooms) {
                var rid = (String) room.get("roomId");
                var summary = (String) room.get("embodiment_summary");
                assertNotNull(summary,
                    "room '" + rid + "' missing embodiment_summary");
                assertFalse(summary.isBlank(),
                    "room '" + rid + "' has blank embodiment_summary");
            }
        }
    }

    @Test
    void loaderWarnsWhenEmbodimentSummaryMissing(@TempDir Path tempDir) throws IOException {
        var json = """
            [
              {
                "roomId": "room-a",
                "name": "Room A",
                "description": "no summary set",
                "exits": [],
                "objects": []
              },
              {
                "roomId": "room-b",
                "name": "Room B",
                "description": "blank summary",
                "embodiment_summary": "",
                "exits": [],
                "objects": []
              },
              {
                "roomId": "room-c",
                "name": "Room C",
                "description": "good summary",
                "embodiment_summary": "Warm hum, soft light, smooth stone.",
                "exits": [],
                "objects": []
              }
            ]
            """;
        var file = tempDir.resolve("rooms.json");
        Files.writeString(file, json);

        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        var loaderLog = (Logger) LoggerFactory.getLogger(FoundationRoomLoader.class);
        loaderLog.addAppender(appender);
        try {
            var seeds = FoundationRoomLoader.loadFromFile(file);
            assertEquals(3, seeds.size(),
                "loader returns all rooms even when embodiment_summary missing (WARN, not REJECT)");
        } finally {
            loaderLog.detachAppender(appender);
        }

        var warns = appender.list.stream()
            .filter(e -> e.getLevel() == Level.WARN)
            .filter(e -> e.getFormattedMessage().contains("missing embodiment_summary"))
            .toList();
        assertEquals(1, warns.size(),
            "exactly one embodiment_summary WARN for the two rooms missing it");
        var msg = warns.get(0).getFormattedMessage();
        assertTrue(msg.contains("room-a"), "WARN names room-a: " + msg);
        assertTrue(msg.contains("room-b"), "WARN names room-b (blank summary): " + msg);
        assertFalse(msg.contains("room-c"), "WARN must not name room-c (has summary): " + msg);
    }

    @Test
    void loaderDoesNotWarnWhenAllRoomsHaveEmbodimentSummary(@TempDir Path tempDir) throws IOException {
        var json = """
            [
              {
                "roomId": "room-x",
                "name": "Room X",
                "description": "good",
                "embodiment_summary": "Quiet stone, sourceless lamplight.",
                "exits": [],
                "objects": []
              }
            ]
            """;
        var file = tempDir.resolve("rooms.json");
        Files.writeString(file, json);

        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        var loaderLog = (Logger) LoggerFactory.getLogger(FoundationRoomLoader.class);
        loaderLog.addAppender(appender);
        try {
            FoundationRoomLoader.loadFromFile(file);
        } finally {
            loaderLog.detachAppender(appender);
        }

        var spec18Warns = appender.list.stream()
            .filter(e -> e.getLevel() == Level.WARN)
            .filter(e -> e.getFormattedMessage().contains("missing embodiment_summary"))
            .toList();
        assertTrue(spec18Warns.isEmpty(),
            "no embodiment_summary WARN when every room declares one, got: "
                + spec18Warns);
    }
}
