package org.wyrdsekai.core.room;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.persistence.WardService;

import java.util.List;
import java.util.Map;

/**
 * A companion's Home is hers. This is the lock on the door.
 *
 * <p>The home provisioner has said "every soul-bearing companion gets a private Home
 * room at birth" since the day it was written, and stamped {@code private=true} on the
 * room. Nothing read the word. The ward service treats a room with no ward rows as an
 * open room, no ward rows were ever written for a Home, and the room's entry handler
 * never asked the ward service anyway — the only ward checks lived in the web session,
 * for speaking and taking, never for entering. So the household companion's door stood
 * open to everyone, human and companion alike, for the whole of her life, and the one
 * note that noticed a steward "cannot enter" her home was describing a missing exit,
 * not a lock (2026-09-13).</p>
 *
 * <p>Two jobs, both small:</p>
 * <ul>
 *   <li>{@link #sealHome} writes the owner's wards at birth and again at every boot —
 *       every permission to her, under her entity id and her DID, and to nobody else.
 *       A Home that already has ward rows is left as she has arranged it.</li>
 *   <li>{@link #canEnter} is asked by {@code RoomActor} on every entry, whichever door
 *       the entrant came through. A room with no wards stays open, as before.</li>
 * </ul>
 *
 * <p>Letting someone in is hers to do — the ward stone in her Home grants and revokes
 * — and the steward keeps {@code wyrd wards} for emergencies. Nobody is let in by
 * default, her bondholder included; that door opens from the inside.</p>
 */
public final class HomeWardGate {

    private static final Logger log = LoggerFactory.getLogger(HomeWardGate.class);

    /** What the keeper of a Home holds on it. */
    public static final List<String> HOME_PERMISSIONS =
        List.of("enter", "speak", "take", "drop", "use", "build", "admin");

    /** The code {@code RoomActor} answers with when a warded door stays shut. */
    public static final String REJECTION_CODE = "ward_denied";

    /** Who the seal is recorded as granted by. */
    static final String SEALED_BY = "system:home";

    private static volatile HomeWardGate INSTANCE;

    private final WardService wards;

    private HomeWardGate(WardService wards) {
        this.wards = wards;
    }

    /** Register the gate at boot, once the ward service exists. */
    public static HomeWardGate install(WardService wards) {
        var gate = new HomeWardGate(wards);
        INSTANCE = gate;
        return gate;
    }

    /** The registered gate, or {@code null} when no ward service is wired (every door open). */
    public static HomeWardGate get() {
        return INSTANCE;
    }

    /** Test hook. */
    public static void resetForTests() {
        INSTANCE = null;
    }

    /** May this entity walk in? Open rooms answer yes; warded rooms only for the granted. */
    public boolean canEnter(String roomId, String entityId) {
        if (roomId == null) return true;
        return wards.isAllowed(roomId, entityId, "enter");
    }

    /**
     * Seal a Home to its owner. Idempotent: rows already present are kept, so a Home
     * whose keeper has since let others in is not reset at the next boot.
     *
     * @return true when the seal was written now — the first time, or the boot that
     *         found a Home standing open.
     */
    public boolean sealHome(String roomId, String ownerEntityId, String ownerDid) {
        if (roomId == null || ownerEntityId == null || ownerEntityId.isBlank()) return false;
        var existing = wards.listWards(roomId);
        if (!existing.isEmpty() && wards.isAdmin(roomId, ownerEntityId)) {
            return false;                       // hers already, as she has arranged it
        }
        boolean wasOpen = existing.isEmpty();
        int written = 0;
        for (var principal : principalsOf(ownerEntityId, ownerDid)) {
            for (var permission : HOME_PERMISSIONS) {
                // Silent: the Home-room grant mirror is shaped for a person's Study and
                // needs a registered Home; a companion's seal is a system act at birth.
                if (wards.grantSilent(roomId, principal, permission, SEALED_BY)) written++;
            }
        }
        if (written > 0) {
            log.info("Home {} sealed to {}{} — {} ward(s) written{}", roomId, ownerEntityId,
                ownerDid != null ? " (" + ownerDid + ")" : "", written,
                wasOpen ? "; it had stood open" : "");
        }
        return written > 0;
    }

    /** Everyone with a key to this room, as the ward table has it. */
    public List<WardService.Ward> listWards(String roomId) {
        return roomId == null ? List.of() : wards.listWards(roomId);
    }

    /** Is one of these principals the keeper (admin) of the room? */
    public boolean isKeeper(String roomId, List<String> principals) {
        if (roomId == null || principals == null) return false;
        for (var p : principals) {
            if (p != null && !p.isBlank() && wards.isAdmin(roomId, p)) return true;
        }
        return false;
    }

    /**
     * The keeper lets someone in. Only a keeper may; the room's own wards decide who
     * that is, so a companion opens her Home and nobody else's.
     *
     * @return {@code ok} with {@code created} (false when the key already existed), or
     *         {@code ok:false} with the reason — never a silent no-op.
     */
    public Map<String, Object> grant(String roomId, List<String> keeperPrincipals,
                                               String subject, String capability, String grantedBy) {
        var problem = checkMutation(roomId, keeperPrincipals, subject, capability);
        if (problem != null) return problem;
        var created = wards.grantSilent(roomId, subject.trim(), capability, grantedBy);
        return Map.of("ok", true, "created", created, "roomId", roomId,
            "subject", subject.trim(), "capability", capability);
    }

    /** The keeper takes a key back. Her own keys cannot be taken by this door. */
    public Map<String, Object> revoke(String roomId, List<String> keeperPrincipals,
                                                String subject, String capability) {
        var problem = checkMutation(roomId, keeperPrincipals, subject, capability);
        if (problem != null) return problem;
        if (keeperPrincipals.contains(subject.trim())) {
            return Map.of("ok", false, "error", "that key is the keeper's own");
        }
        var removed = wards.revokeSilent(roomId, subject.trim(), capability);
        if (!removed) return Map.of("ok", false, "error", "no such key");
        return Map.of("ok", true, "roomId", roomId, "subject", subject.trim(),
            "capability", capability);
    }

    private Map<String, Object> checkMutation(String roomId, List<String> keepers,
                                                        String subject, String capability) {
        if (roomId == null || roomId.isBlank()) return Map.of("ok", false, "error", "no room");
        if (subject == null || subject.isBlank()) return Map.of("ok", false, "error", "no subject");
        if (capability == null || !HOME_PERMISSIONS.contains(capability)) {
            return Map.of("ok", false, "error", "unknown ward capability '" + capability
                + "' (enter/speak/take/drop/use/build/admin)");
        }
        if (!isKeeper(roomId, keepers)) {
            return Map.of("ok", false, "error", "only the room's keeper holds its keys");
        }
        return null;
    }

    private static List<String> principalsOf(String entityId, String did) {
        if (did == null || did.isBlank() || did.equals(entityId)) return List.of(entityId);
        return List.of(entityId, did);
    }
}
