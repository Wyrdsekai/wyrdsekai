package org.wyrdsekai.core.room;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.model.Exit;
import org.wyrdsekai.common.model.RoomObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Provisions private Study rooms for players.
 * Each player gets their own Study: "study-{playerId}".
 *
 * The Study is the player's landing room — private workspace with
 * journal, dashboard, shelves, pinboard. Only the player and their
 * companion can enter by default (ward-controlled).
 *
 * Pattern follows HomeProvisioner for agent Home rooms.
 */
public final class StudyProvisioner {

    private static final Logger log = LoggerFactory.getLogger(StudyProvisioner.class);

    /**
     * what the Study's ambient state contributes.
     * Per-steward room provisioned at first login; same shape for every steward,
     * so a single summary suffices. i18n key: {@code room.study.embodiment_summary}.
     */
    public static final String EMBODIMENT_SUMMARY =
        "Warm lamplight on worn leather; the hearth-quiet of a steward's private quarters, "
        + "paper and brass-bound ledgers settled in their accustomed places.";

    private StudyProvisioner() {}

    /**
     * Get the Study room ID for a player.
     */
    public static String studyRoomId(String playerId) {
        return "study-" + playerId;
    }

    /**
     * Create a ZoneGuardian.RoomSeed for a player's private Study.
     *
     * @param playerId   Player's identifier
     * @param playerName Player's display name
     * @return RoomSeed for the Study
     */
    public static ZoneGuardian.RoomSeed createStudySeed(String playerId, String playerName) {
        return createStudySeed(playerId, playerName, false);
    }

    /**
     * Create a ZoneGuardian.RoomSeed for a player's private Study.
     *
     * @param playerId   Player's identifier
     * @param playerName Player's display name
     * @param isSteward  Whether this player is the household steward
     * @return RoomSeed for the Study (with additional steward objects if applicable)
     */
    public static ZoneGuardian.RoomSeed createStudySeed(String playerId, String playerName,
                                                         boolean isSteward) {
        var roomId = studyRoomId(playerId);
        var name = playerName + "'s Study";

        var descBase = "Your private quarters. A worn leather chair faces a low hearth. " +
            "A heavy desk stands against one wall, its surface holding a schedule board " +
            "and correspondence tray. A crystal sphere on a stand pulses gently with " +
            "system telemetry. Document shelves line the opposite wall with a pinboard " +
            "beside them, and a leather-bound journal rests on the arm of the chair.";

        var description = isSteward
            ? descBase + " A tall oak cabinet stands in the corner, its drawers labeled " +
                "with brass plates — the steward's administrative furnishings. A roster " +
                "ledger lies open on a reading stand, and an invitation scroll hangs " +
                "from a peg beside a heavy ward keyring."
            : descBase;

        var exits = List.of(
            new Exit("out", "nexus", "Step out to The Nexus")
        );

        // Base objects every member gets
        var objects = new ArrayList<>(List.of(
            new RoomObject("study-desk", "heavy desk",
                "A desk with a schedule board and correspondence tray. Messages and reminders accumulate here.",
                false),
            new RoomObject("study-dashboard", "dashboard crystal",
                "A crystal sphere on a brass stand, pulsing with faint light. It shows the status of the household's systems at a glance.",
                false),
            new RoomObject("study-journal", "journal",
                "A leather-bound journal resting on the arm of your chair. Its pages hold your thoughts — some shared, some private.",
                false),
            new RoomObject("study-shelves", "shelves",
                "Document shelves along one wall. Your private library — personal documents, research, and indexed collections.",
                false),
            new RoomObject("study-pinboard", "pinboard",
                "A cork board beside the shelves, covered in pinned cards. Bookmarks and clippings from the public Library.",
                false),
            // sittable furnishing. The state map carries
            // sit-specific narration so the Sit dispatcher can produce a
            // chair-flavored posture descriptor instead of the generic fallback.
            // Aliases let `sit at chair`, `sit in the worn chair`, etc. all resolve.
            new RoomObject("study-chair", "leather chair",
                "A worn leather chair facing the hearth. Your companion often sits in the one opposite.",
                /* takeable */ false, /* visible */ true, /* cloneable */ false,
                List.of("chair", "leather chair", "worn chair", "worn leather chair"),
                Map.of(
                    "sittable", "true",
                    "sitDescriptor", "settles into the worn leather chair, facing the hearth",
                    "sitBodyLanguage", "The chair creaks softly as {actor} leans back, watching the embers.",
                    "embodiment.emits", "posture_change,body_language",
                    "embodiment.silent", "false")),
            // Wave 1: every member gets these
            new RoomObject("study-companion-crystal", "companion bond crystal",
                "A warm crystal resting on the mantle. It glows softly when your companion is nearby. Use it to view bond strength and set communication preferences.",
                false),
            new RoomObject("study-companion-glass", "companion glass",
                "A small hand-glass on the mantle beside the bond crystal. Look into it to see how "
                + "your companion is doing — their drives and mood, and whether a quiet ache is "
                + "general loneliness or a specific longing for someone absent.",
                false),
            new RoomObject("study-privacy-ward", "privacy ward stone",
                "A smooth stone set into the wall beside the door. It controls what personal data is shared with the household. Touch it to review your consent grants.",
                false),
            new RoomObject("study-device-ledger", "device ledger",
                "A thin leather-bound book near the door. It lists your paired devices — phones, tablets, laptops. Use it to pair new devices or revoke lost ones.",
                false),
            new RoomObject("study-cost-ledger", "cost ledger",
                "A small ledger on the desk corner, its pages ruled with neat columns. It tracks your personal usage — inference queries, API calls, storage. Set budget alerts here.",
                false)
        ));

        // Steward-only objects (§: The Study as Control Panel)
        if (isSteward) {
            objects.addAll(List.of(
                new RoomObject("study-roster", "roster ledger",
                    "A large bound ledger on a reading stand. It lists all household members, their roles, devices, and last activity. Use it to grant or revoke roles, or remove members.",
                    false),
                new RoomObject("study-invitation", "invitation scroll",
                    "A rolled parchment hanging from a brass peg. Use it to create invite codes for new household members, view pending invitations, or set invite expiry.",
                    false, true, false,
                    List.of("invitation", "invitations", "invite scroll"), Map.of()),
                new RoomObject("study-ward-keyring", "ward keyring",
                    "A heavy iron ring holding dozens of warded keys. Each key controls access to a room or service. Use it to manage room permissions per member or role.",
                    false),
                new RoomObject("study-node-manifest", "node manifest",
                    "A mechanical display on the wall showing all enrolled nodes — their capabilities, health, and current workload. Use it to enroll or remove nodes.",
                    false),
                new RoomObject("study-treasury", "household treasury",
                    "An ornate lockbox on a shelf. It shows the household's cost overview — per-member usage, cloud costs, API spend. Set household budgets and per-member quotas.",
                    false),
                new RoomObject("study-audit-log", "audit log",
                    "A thick journal with a brass clasp. Security events — logins, failed attempts, role changes, node joins and departures. The ink is permanent.",
                    false),
                new RoomObject("study-parental", "parental controls scroll",
                    "A scroll in a protective case. Per-member controls: time limits, room restrictions, inference quotas, content filters. Ward-based enforcement.",
                    false, true, false,
                    List.of("parental", "parental controls"), Map.of()),
                new RoomObject("study-maintenance", "maintenance dial",
                    "A brass dial set into the wall near the door. Turn it to put nodes in maintenance mode, trigger updates, or schedule backups.",
                    false),
                // Phase 4: in-world runtime configuration. Backed by the same
                // /etc/wyrdsekai/wyrdsekai.conf that the systemd unit reads,
                // so 'wyrd config set' and 'use scroll set' converge.
                // Aliases pin the short forms: the steward Study holds THREE
                // scrolls (settings / invitation / parental), and the resolver's
                // partial-match tier would hand bare "scroll" to whichever it
                // met first — on second-node that sent the documented `use scroll set
                // KEY=VALUE` flow to the invitation scroll. Exact-alias match
                // outranks every other tier, so "scroll"/"settings" stay here.
                new RoomObject("study-scroll-of-settings", "scroll of settings",
                    "A long parchment scroll in a brass holder on the desk. It shows the current runtime configuration and, for stewards, lets you change it on the fly. "
                    + "Use it with no argument to list; 'use scroll get KEY' / 'use scroll set KEY=VALUE' / 'use scroll apply' to read, change, and apply.",
                    false, true, false,
                    List.of("scroll", "settings", "settings scroll"), Map.of()),
                new RoomObject("study-key-chest", "key chest",
                    "A small cedar chest clasped in brass. It holds the household's backup snapshots — keys, souls, federation agreements, world state. "
                    + "The steward can create a new snapshot or restore an older one with the wyrd backup / restore CLI; the chest reflects what's on disk.",
                    false),
                // steward-side UX for the Nostr bridge.
                // Backed by the scroll-of-settings config write path; this is a
                // focused surface so the steward sees status at a glance without
                // grepping the full config list.
                new RoomObject("study-nostr-sigil", "nostr sigil",
                    "A small wax sigil pressed into the corner of the desk, glowing faintly when companions speak across the open relay-mesh. "
                    + "Inspect it with 'use sigil' to see relay status; 'use sigil enable' / 'use sigil disable' toggles the bridge "
                    + "(then 'use scroll apply' to take effect). For deeper edits use 'use scroll set wyrdsekai.nostr.publish_relays=…'.",
                    false),
                // Track-C C7 — Steward-only console for the
                // recipe scheduler. Backed by scripts/items/recipes_console.js
                // (registered by ScriptedItemLoader at boot). RoomActor's
                // appendScriptCommandHints picks up the manifest commands so
                // 'use recipes_console', 'use recipes_console runs', etc. all
                // work without further wiring. Pause/resume/force-fire live on
                // the `wyrd recipes` CLI; this is read-side in-world.
                new RoomObject("recipes_console", "recipes console",
                    "A console on the steward's desk, panels showing each enrolled recipe — its cadence, "
                    + "next-fire estimate, queue depth, last status. A second panel scrolls recent runs. "
                    + "Use it to read; the steward CLI (wyrd recipes) handles pause / resume / force-fire. "
                    + "'use recipes_console settings' surfaces the scheduler configuration knobs "
                    + "(poll cadence, GPU budget, gap-detection thresholds) with current values — "
                    + "edit them through the Scroll of Settings ('use scroll set KEY=VALUE; use scroll apply').",
                    false)
            ));
        }

        log.info("Provisioning {} Study for player {} ({}): {}",
            isSteward ? "steward" : "member", playerName, playerId, roomId);
        return new ZoneGuardian.RoomSeed(roomId, name, description, exits, List.copyOf(objects));
    }

    /**
     * Check if a room ID is a player Study.
     */
    public static boolean isStudyRoom(String roomId) {
        return roomId != null && roomId.startsWith("study-");
    }

    /**
     * Extract the player ID from a Study room ID.
     */
    public static String playerIdFromStudy(String roomId) {
        if (!isStudyRoom(roomId)) return null;
        return roomId.substring("study-".length());
    }
}
