package org.wyrdsekai.core.codezaiku;

import java.time.Instant;
import java.util.*;

/**
 * CodeZaiku spatial room configurations (§75).
 * Maps software development concepts to in-world rooms.
 *
 * The Forge      — active development workspace
 * The Crucible   — CI/CD and quality assurance
 * The Assay Office — code review and analysis
 * The Ledger     — project metrics and history
 * The Archive    — documentation and artifacts
 */
public class CodeZaikuRooms {

    /** Room configuration for a CodeZaiku spatial room. */
    public record RoomConfig(
        String roomId,
        String name,
        String description,
        String script,
        List<String> defaultExits,
        Map<String, String> properties
    ) {}

    /** Activity event from a CodeZaiku room. */
    public record Activity(
        String roomId,
        String activityType,
        String entityId,
        String details,
        Instant timestamp
    ) {}

    private static final List<RoomConfig> ROOMS = List.of(
        new RoomConfig("the-forge",
            "The Forge",
            "Where code is forged. Anvils ring with the sound of new features being shaped. "
            + "Active branches glow like heated metal. Commit messages scroll on a stone tablet.",
            "codezaiku/forge.js",
            List.of("the-crucible", "the-assay-office", "the-ledger"),
            Map.of("workspaceType", "development", "branchTracking", "true")),

        new RoomConfig("the-crucible",
            "The Crucible",
            "Where code is tested. Pipelines bubble in great crucibles. "
            + "Green smoke rises from passing tests; red sparks fly from failures.",
            "codezaiku/crucible.js",
            List.of("the-forge", "the-assay-office"),
            Map.of("workspaceType", "testing", "ciIntegration", "true")),

        new RoomConfig("the-assay-office",
            "The Assay Office",
            "Where code is evaluated. Assayers examine pull requests through enchanted lenses. "
            + "Review comments appear as glowing annotations hovering in the air.",
            "codezaiku/assay-office.js",
            List.of("the-forge", "the-crucible", "the-ledger"),
            Map.of("workspaceType", "review", "prTracking", "true")),

        new RoomConfig("the-ledger",
            "The Ledger",
            "Where contributions are tracked. A great book lies open, recording every commit, "
            + "merge, and deployment. Graphs of velocity and burndown hang on the walls.",
            "codezaiku/ledger.js",
            List.of("the-forge", "the-assay-office", "the-archive"),
            Map.of("workspaceType", "metrics", "historyDepth", "90")),

        new RoomConfig("the-archive",
            "The Archive",
            "Where knowledge is preserved. Shelves of documentation stretch to the ceiling. "
            + "A search golem awaits your queries. ADRs glow with soft light.",
            "codezaiku/archive.js",
            List.of("the-ledger"),
            Map.of("workspaceType", "documentation", "searchEnabled", "true"))
    );

    /** Get all CodeZaiku room configurations. */
    public static List<RoomConfig> allRooms() {
        return ROOMS;
    }

    /** Get a room configuration by ID. */
    public static Optional<RoomConfig> getRoom(String roomId) {
        return ROOMS.stream()
            .filter(r -> r.roomId().equals(roomId))
            .findFirst();
    }

    /** Get room IDs. */
    public static List<String> roomIds() {
        return ROOMS.stream().map(RoomConfig::roomId).toList();
    }

    /** Get room count. */
    public static int roomCount() {
        return ROOMS.size();
    }

    /**
     * Validate that all rooms have bidirectional exits.
     * Every exit target should have a corresponding exit back.
     */
    public static List<String> validateExits() {
        var issues = new ArrayList<String>();
        var roomIdSet = new HashSet<>(roomIds());

        for (var room : ROOMS) {
            for (var exit : room.defaultExits()) {
                if (!roomIdSet.contains(exit)) {
                    issues.add(room.roomId() + " → " + exit + " (target does not exist)");
                }
            }
        }

        return issues;
    }

    /** Get the room graph as adjacency list. */
    public static Map<String, List<String>> roomGraph() {
        var graph = new LinkedHashMap<String, List<String>>();
        for (var room : ROOMS) {
            graph.put(room.roomId(), room.defaultExits());
        }
        return graph;
    }
}
