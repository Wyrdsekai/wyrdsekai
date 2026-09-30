package org.wyrdsekai.core.room;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.identity.PersonIdentityResolver;
import org.wyrdsekai.core.identity.PersonIds;
import org.wyrdsekai.core.persistence.SchemaInitializer;
import org.wyrdsekai.core.persistence.WardService;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Audit W4 (2026-09-28): a person's Study was provisioned with no ward rows, and a room with
 * no wards is open, so anyone in the household could walk into anyone's Study. It is sealed
 * now: its owner under every id they carry, those they let in, and the companion whose
 * bondholder they are.
 */
class AStudyOpensForItsOwnerTest {

    private static final String ALICE = "did:key:alice-study";
    private static final String ALICE_LOGIN = "0b1c-alice-login";
    private static final String STUDY = StudyProvisioner.studyRoomId(ALICE);

    private WardService wards;
    private HomeWardGate gate;

    @BeforeEach
    void setUp(@TempDir Path dir) {
        var jdbc = SchemaInitializer.initialize(dir.resolve("wards.db"));
        wards = new WardService(jdbc);
        gate = HomeWardGate.install(wards);
        PersonIds.resetForTesting(new PersonIdentityResolver(jdbc) {
            @Override
            public Optional<String> resolve(String id) {
                return Optional.ofNullable(Map.of(ALICE_LOGIN, ALICE).get(id));
            }
        });
    }

    @AfterEach
    void tearDown() {
        HomeWardGate.resetForTests();
        PersonIds.resetForTesting(null);
    }

    @Test
    @DisplayName("a sealed Study opens for its owner by DID and by login id, and for no other member")
    void ownerOnly() {
        assertTrue(gate.canEnter(STUDY, "did:key:bob"), "before the seal the Study is open — the defect");
        assertTrue(gate.sealStudy(STUDY, ALICE));
        assertTrue(gate.canEnter(STUDY, ALICE));
        assertTrue(gate.canEnter(STUDY, ALICE_LOGIN), "the ssh door presents the login id");
        assertFalse(gate.canEnter(STUDY, "did:key:bob"));
        assertFalse(gate.canEnter(STUDY, "companion-lulu"), "a companion not bonded to her stays out");
        assertTrue(wards.isAdmin(STUDY, ALICE), "she keeps the door");
    }

    @Test
    @DisplayName("someone she lets in may enter; her bondholder-companion may enter; the seal is kept as she arranged it")
    void inviteesAndHerCompanion() {
        gate.sealStudy(STUDY, ALICE);
        wards.grantSilent(STUDY, "did:key:bob", "enter", ALICE);          // knock → approve
        assertTrue(gate.canEnter(STUDY, "did:key:bob"));

        HomeWardGate.noteBondholder("companion-mia", ALICE_LOGIN);
        assertTrue(gate.canEnter(STUDY, "companion-mia"), "the companion whose bondholder she is");
        HomeWardGate.noteBondholder("companion-mia", "did:key:someone-else");
        assertFalse(gate.canEnter(STUDY, "companion-mia"), "not once her bondholder is someone else");

        assertFalse(gate.sealStudy(STUDY, ALICE), "already hers: left as she arranged it");
        assertTrue(gate.canEnter(STUDY, "did:key:bob"));
    }

    @Test
    @DisplayName("an anonymous visitor's room is not sealed")
    void anonymousNotSealed() {
        assertFalse(gate.sealStudy(StudyProvisioner.studyRoomId("anon-123"), "anon-123"));
    }
}
