package org.wyrdsekai.core.room;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.model.Exit;
import org.wyrdsekai.common.model.RoomObject;

import java.util.List;

/**
 * Provisions per-bondholder CodePlane Workshop rooms.
 *
 * <p>: each bondholder with CodePlane
 * installed gets a private {@code workshop-codeplane-{userId}} room. The
 * workshop holds the bondholder's Coding Familiar, a workbench for form
 * shaping, the Library shelves, a Chronicle stone surfacing the familiar's
 * story slice, and the rack of project portals linking to external git
 * repos.</p>
 *
 * <p>Ward model (§2.2): bondholder-private. Bondholder + their other agents
 * enter freely; everyone else needs an invite. The Coding Familiar can
 * {@code flag_protection} on the bondholder per the same substrate that
 * protects Wyrd.</p>
 *
 * <p>Furnishings here are declarative placeholders. The active workbench
 * shape protocol, library compartment population, and project-portal
 * lifecycle each have their own follow-on tasks (§5, §6.5, §17.7).</p>
 *
 * <p>Mirrors {@link StudyProvisioner}.</p>
 */
public final class WorkshopProvisioner {

    private static final Logger log = LoggerFactory.getLogger(WorkshopProvisioner.class);

    /** Workshop room ID prefix. Per-bondholder rooms are {@code PREFIX + userId}. */
    public static final String ROOM_ID_PREFIX = "workshop-codeplane-";

    /**
     * what the Workshop's ambient state contributes.
     * Per-bondholder coding-familiar workshop; same shape for every bondholder,
     * so a single summary suffices. i18n key:
     * {@code room.workshop.embodiment_summary}.
     */
    public static final String EMBODIMENT_SUMMARY =
        "The clutter-quiet of code-work in progress; the workbench-glow of an active "
        + "shaping, sawdust scent of last week's drafts, the focused light over a panel "
        + "where pieces are coming together.";

    private WorkshopProvisioner() {}

    /** Workshop room ID for a bondholder. */
    public static String workshopRoomId(String bondholderId) {
        return ROOM_ID_PREFIX + bondholderId;
    }

    /** True if the room ID looks like a CodePlane workshop room. */
    public static boolean isWorkshopRoom(String roomId) {
        return roomId != null && roomId.startsWith(ROOM_ID_PREFIX);
    }

    /** Extract the bondholder ID from a workshop room ID, or {@code null}. */
    public static String bondholderIdFromWorkshop(String roomId) {
        if (!isWorkshopRoom(roomId)) return null;
        return roomId.substring(ROOM_ID_PREFIX.length());
    }

    /**
     * Build the {@link ZoneGuardian.RoomSeed} for a bondholder's CodePlane
     * workshop. The seed is deterministic — calling twice with the same
     * inputs produces identical room state — so re-provisioning is safe.
     */
    public static ZoneGuardian.RoomSeed createWorkshopSeed(String bondholderId,
                                                            String bondholderName) {
        var roomId = workshopRoomId(bondholderId);
        var name = bondholderName + "'s CodePlane Workshop";

        var description =
            "A long, well-lit room with the air of a working studio. A heavy "
            + "workbench dominates the center, its surface marked by countless past "
            + "drafts. One wall is lined with library shelves — compartments for "
            + "frameworks, project conventions, and the Coding DNA of every "
            + "repository linked here. A pale stone rests on a pedestal near the "
            + "wall — a Chronicle stone, holding the familiar's coding-side slice "
            + "of story. To one side, a brass rack stands ready to receive project "
            + "portals, each portal a doorway into an external repository. A "
            + "Forge link glows faintly on the back wall — the path to sleep-pass "
            + "consolidation of what the day taught.";

        var exits = List.of(
            new Exit("out", "nexus", "Step out to The Nexus")
        );

        var objects = List.of(
            new RoomObject(
                "workshop-bench",
                "workbench",
                "A heavy workbench for shaping and revising thought forms. "
                + "Bring a form draft here to refine it.",
                false),
            new RoomObject(
                "workshop-library-shelves",
                "library shelves",
                "Compartments hold framework knowledge, project conventions, "
                + "and accumulated coding DNA. Per-project compartments materialize "
                + "as project portals are linked.",
                false),
            new RoomObject(
                "workshop-chronicle-stone",
                "chronicle stone",
                "A smooth pale stone that surfaces the familiar's chronicle "
                + "slice — the narrative thread of what the familiar has been "
                + "doing here. Look at it to read recent entries.",
                false),
            new RoomObject(
                "workshop-portal-rack",
                "project portal rack",
                "A brass rack with hooks for project portals. Each portal "
                + "links to an external repository; engaging one mounts that "
                + "project's working tree for the familiar.",
                false),
            new RoomObject(
                "workshop-forge-link",
                "forge link",
                "A subtle archway in the back wall, glowing faintly during "
                + "deep-sleep cycles. This is the path by which the day's coding "
                + "experiences become soul fragments via the Forge.",
                false),
            new RoomObject(
                "workshop-familiar-perch",
                "familiar perch",
                "A simple wooden perch where the Coding Familiar rests "
                + "between summonings. When the familiar is not present, the "
                + "perch is bare.",
                false)
        );

        log.info("Provisioning CodePlane workshop for bondholder {} ({}): {}",
            bondholderName, bondholderId, roomId);

        return new ZoneGuardian.RoomSeed(
            roomId, name, description,
            List.of("workshop", "codeplane", "code", "studio"),
            exits, objects, null);
    }
}
