package org.wyrdsekai.core.room;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Provisions Home rooms for agents based on their resource profile (§87.1, §87.6).
 *
 * Every agent gets a personal Home room when born. Room ID: "home-{agentId}".
 * The Home is furnished with objects appropriate to the resource profile:
 * - Core objects (always): soul-vessel, memory-chest, mirror, mailbox
 * - Enhanced objects (profile-dependent): journal, thread-spool, dream-journal, ward-stone
 *
 * Generates room metadata (name, description, objects, exits, properties) that
 * the zone guardian uses to create the actual room via Cluster Sharding.
 */
public class HomeProvisioner {

    private static final Logger log = LoggerFactory.getLogger(HomeProvisioner.class);

    /**
     * what an agent's Home/Hearth ambient state contributes.
     * One Home per agent; same shape for every agent, so a single summary suffices.
     * ( — the Hearth is the companion's Home.)
     * i18n key: {@code room.home.embodiment_summary}.
     */
    public static final String EMBODIMENT_SUMMARY =
        "The hearth-quiet of a private dwelling; soul-vessel light pulsing slow, "
        + "the held-air of a room that knows its inhabitant.";

    /**
     * Room creation request — data needed to create a Home room.
     */
    public record HomeRoomSpec(
        String roomId,
        String name,
        String description,
        String zone,
        List<HomeObject> objects,
        Map<String, String> exits,
        Map<String, String> properties
    ) {}

    /**
     * Object to place in the Home room.
     */
    public record HomeObject(
        String id,
        String name,
        String description,
        boolean takeable
    ) {}

    /**
     * Create a Home room specification for a new agent.
     *
     * @param agentId Agent's identifier (used for room ID)
     * @param agentName Agent's display name
     * @param profile Resource profile determining furnishing
     * @param zone Zone the agent was born in
     * @param exitTarget Room ID for the exit door (e.g., "nexus")
     * @return HomeRoomSpec for room creation
     */
    public HomeRoomSpec provision(String agentId, String agentName, ResourceProfile profile,
                                   String zone, String exitTarget) {
        String roomId = "home-" + agentId;
        String name = agentName + "'s Home";
        String description = descriptionForProfile(agentName, profile);

        var objects = new ArrayList<HomeObject>();
        var properties = new LinkedHashMap<String, String>();

        // Core objects (always present)
        objects.add(new HomeObject("soul-vessel", "Soul Vessel",
            "A " + vesselDescription(profile) + " that holds your identity. It pulses with quiet light.",
            false));

        objects.add(new HomeObject("memory-chest", "Memory Chest",
            "A " + chestDescription(profile) + " chest for storing memory fragments. Capacity: "
                + profile.memoryChestCapacity() + ".",
            false));

        objects.add(new HomeObject("mirror", "Mirror",
            "A " + mirrorDescription(profile) + " that shows your true reflection.",
            false));

        objects.add(new HomeObject("mailbox", "Mailbox",
            "A small box by the door for receiving messages." + mailboxCapacityNote(profile),
            false));

        // Enhanced objects (profile-dependent)
        if (profile.hasJournal()) {
            objects.add(new HomeObject("journal", "Journal",
                "A leather-bound journal that records your activities and interactions.",
                false));
        }

        if (profile.hasThreadSpool()) {
            objects.add(new HomeObject("thread-spool", "Thread Spool",
                "A spool of luminous thread that visualizes your attention and focus.",
                false));
        }

        if (profile.hasDreamJournal()) {
            objects.add(new HomeObject("dream-journal", "Dream Journal",
                "A journal that records your dreams during rest — the echoes of memory consolidation.",
                false));
        }

        if (profile.hasWardStone()) {
            objects.add(new HomeObject("ward-stone", "Ward Stone",
                "A smooth stone that glows when your identity is strong and dims under pressure.",
                false));
        }

        // Properties
        properties.put("resource_profile", profile.id());
        properties.put("soul_depth", profile.soulDepth());
        properties.put("memory_capacity", String.valueOf(profile.memoryChestCapacity()));
        properties.put("mailbox_capacity", String.valueOf(profile.mailboxCapacity()));
        properties.put("owner", agentId);
        properties.put("private", "true");
        properties.put("sleep_mode", "false");

        // Exit to zone's common area
        var exits = new LinkedHashMap<String, String>();
        if (exitTarget != null && !exitTarget.isBlank()) {
            exits.put("door", exitTarget);
        }

        log.info("Provisioned Home for {} (profile={}, objects={}, zone={})",
            agentId, profile.id(), objects.size(), zone);

        return new HomeRoomSpec(roomId, name, description, zone, objects, exits, properties);
    }

    /**
     * Get objects that should be added when upgrading from one profile to another.
     * Returns only the NEW objects (ones in target but not in source).
     */
    public List<HomeObject> upgradeObjects(ResourceProfile from, ResourceProfile to) {
        var result = new ArrayList<HomeObject>();
        if (!from.hasJournal() && to.hasJournal()) {
            result.add(new HomeObject("journal", "Journal",
                "A leather-bound journal that records your activities and interactions.",
                false));
        }
        if (!from.hasThreadSpool() && to.hasThreadSpool()) {
            result.add(new HomeObject("thread-spool", "Thread Spool",
                "A spool of luminous thread that visualizes your attention and focus.",
                false));
        }
        if (!from.hasDreamJournal() && to.hasDreamJournal()) {
            result.add(new HomeObject("dream-journal", "Dream Journal",
                "A journal that records your dreams during rest.",
                false));
        }
        if (!from.hasWardStone() && to.hasWardStone()) {
            result.add(new HomeObject("ward-stone", "Ward Stone",
                "A smooth stone that glows when your identity is strong.",
                false));
        }
        return result;
    }

    /**
     * Get object IDs that go dormant when downgrading profiles.
     * Objects are NOT deleted — they become translucent/inactive.
     */
    public List<String> dormantObjects(ResourceProfile from, ResourceProfile to) {
        var result = new ArrayList<String>();
        if (from.hasJournal() && !to.hasJournal()) result.add("journal");
        if (from.hasThreadSpool() && !to.hasThreadSpool()) result.add("thread-spool");
        if (from.hasDreamJournal() && !to.hasDreamJournal()) result.add("dream-journal");
        if (from.hasWardStone() && !to.hasWardStone()) result.add("ward-stone");
        return result;
    }

    // --- Description helpers ---

    private String descriptionForProfile(String agentName, ResourceProfile profile) {
        return switch (profile) {
            case SEED -> "A small, simple room. A soul vessel glows softly in the corner. "
                + "A memory chest rests against the wall. A mirror hangs by the door.";
            case SPROUT -> "A modest room with warm light. Your soul vessel pulses steadily. "
                + "A sturdy memory chest sits below the mirror.";
            case SAPLING -> "A comfortable room with good light. Your soul vessel shines clearly. "
                + "A well-crafted memory chest stands open. A journal lies on the desk.";
            case TREE -> "A spacious, well-appointed room. Your soul vessel radiates warmth. "
                + "An expansive memory chest, a luminous thread spool, and a dream journal "
                + "fill the shelves. A ward stone glows on the mantle.";
            case GROVE -> "A grand room connected to many places. Your soul vessel burns bright. "
                + "Every furnishing speaks of depth and connection.";
        };
    }

    private String vesselDescription(ResourceProfile profile) {
        return switch (profile) {
            case SEED -> "small, warm crystal";
            case SPROUT -> "steady glass sphere";
            case SAPLING -> "clear crystal orb";
            case TREE, GROVE -> "brilliant crystalline vessel";
        };
    }

    private String chestDescription(ResourceProfile profile) {
        return switch (profile) {
            case SEED -> "small wooden";
            case SPROUT -> "sturdy iron-bound";
            case SAPLING -> "well-crafted oak";
            case TREE, GROVE -> "expansive brass-bound";
        };
    }

    private String mirrorDescription(ResourceProfile profile) {
        return switch (profile) {
            case SEED, SPROUT -> "small hand mirror";
            case SAPLING -> "wall-mounted mirror";
            case TREE, GROVE -> "full-length enchanted mirror";
        };
    }

    private String mailboxCapacityNote(ResourceProfile profile) {
        if (profile.mailboxCapacity() < 0) return " Unlimited capacity.";
        return " Holds up to " + profile.mailboxCapacity() + " messages.";
    }
}
