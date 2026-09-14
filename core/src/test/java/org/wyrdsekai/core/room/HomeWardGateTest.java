package org.wyrdsekai.core.room;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.persistence.SchemaInitializer;
import org.wyrdsekai.core.persistence.WardService;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A companion's Home is hers.
 *
 * <p>The provisioner said "private Home room" from the first day and stamped
 * {@code private=true} on the room; nothing enforced either. A room with no ward rows is
 * an open room, no ward rows were ever written for a Home, and the entry handler never
 * asked. The household companion's door stood open to everyone for her whole life
 * (found 2026-09-13). These pin the seal and the check.</p>
 */
class HomeWardGateTest {

    private static final String HOME = "home-companion-wisp";
    private static final String OWNER = "companion-wisp";
    private static final String OWNER_DID = "did:key:z6MkwispWispWisp";

    private WardService wards;
    private HomeWardGate gate;

    @BeforeEach
    void setUp(@TempDir Path dir) {
        var jdbc = SchemaInitializer.initialize(dir.resolve("wards.db"));
        wards = new WardService(jdbc);
        gate = HomeWardGate.install(wards);
    }

    @AfterEach
    void tearDown() {
        HomeWardGate.resetForTests();
    }

    @Test
    @DisplayName("a sealed Home opens for her — by entity id and by DID — and for nobody else")
    void sealedHomeIsHers() {
        assertTrue(gate.canEnter(HOME, "anyone"), "before the seal the room is open — the defect");
        assertTrue(gate.sealHome(HOME, OWNER, OWNER_DID));
        assertTrue(gate.canEnter(HOME, OWNER));
        assertTrue(gate.canEnter(HOME, OWNER_DID));
        assertFalse(gate.canEnter(HOME, "did:key:z6MkStewardSteward"), "the steward is not let in by default");
        assertFalse(gate.canEnter(HOME, "companion-other"), "another companion is not let in");
        assertFalse(gate.canEnter(HOME, null), "nobody is not somebody");
        assertTrue(wards.isAllowed(HOME, "anyone", "look"), "looking in from the doorway stays free");
        assertTrue(wards.isAdmin(HOME, OWNER), "she keeps the door: admin is hers");
        assertTrue(wards.isAdmin(HOME, OWNER_DID));
    }

    @Test
    @DisplayName("the seal holds every Home permission for her, and only the seal wrote them")
    void sealWritesEveryPermissionToHer() {
        gate.sealHome(HOME, OWNER, OWNER_DID);
        var rows = wards.listWards(HOME);
        assertEquals(HomeWardGate.HOME_PERMISSIONS.size() * 2, rows.size());
        for (var w : rows) {
            assertTrue(w.principal().equals(OWNER) || w.principal().equals(OWNER_DID), w.principal());
            assertEquals(HomeWardGate.SEALED_BY, w.grantedBy());
        }
    }

    @Test
    @DisplayName("a Home that already exists and stands open — hers on the household node — is sealed at the next boot")
    void anOpenHomeIsSealedOnBoot() {
        // The room existed for months with zero ward rows; provisioning at boot is idempotent
        // for the room and now also for the seal.
        assertTrue(gate.canEnter(HOME, "did:key:z6MkVisitor"));
        assertTrue(gate.sealHome(HOME, OWNER, OWNER_DID), "the boot that finds an open Home seals it");
        assertFalse(gate.canEnter(HOME, "did:key:z6MkVisitor"));
    }

    @Test
    @DisplayName("sealing again changes nothing, and what she has since granted is kept")
    void sealIsIdempotentAndKeepsHerGrants() {
        gate.sealHome(HOME, OWNER, OWNER_DID);
        wards.grant(HOME, "did:key:z6MkBondholder", "enter", OWNER_DID);   // she let him in
        int before = wards.listWards(HOME).size();
        assertFalse(gate.sealHome(HOME, OWNER, OWNER_DID), "already hers — nothing to write");
        assertEquals(before, wards.listWards(HOME).size());
        assertTrue(gate.canEnter(HOME, "did:key:z6MkBondholder"), "her invitation survives the boot");
    }

    @Test
    @DisplayName("rooms with no wards stay open, as every common room is")
    void openRoomsStayOpen() {
        assertTrue(gate.canEnter("nexus", "did:key:z6MkAnyone"));
        assertTrue(gate.canEnter(null, "did:key:z6MkAnyone"));
    }

    @Test
    @DisplayName("a companion without a DID yet is sealed under her entity id alone")
    void sealWithoutDid() {
        assertTrue(gate.sealHome(HOME, OWNER, null));
        assertEquals(HomeWardGate.HOME_PERMISSIONS.size(), wards.listWards(HOME).size());
        assertTrue(gate.canEnter(HOME, OWNER));
        assertFalse(gate.sealHome(HOME, null, OWNER_DID), "no owner, no seal");
    }
}
