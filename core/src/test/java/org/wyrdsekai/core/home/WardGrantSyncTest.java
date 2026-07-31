package org.wyrdsekai.core.home;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.common.home.Capability;
import org.wyrdsekai.common.home.Grant;
import org.wyrdsekai.common.home.ResourceTypeRegistry;
import org.wyrdsekai.common.home.ResourceUri;
import org.wyrdsekai.core.persistence.SchemaInitializer;
import org.wyrdsekai.core.persistence.WardService;
import org.wyrdsekai.core.room.StudyProvisioner;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M2 migration: Home-room wards mirror as
 * {@code home://{owner}/home-room} Grants. Non-Home rooms are ignored
 * (WardService stays the isAllowed authority).
 */
class WardGrantSyncTest {

    private ActorTestKit testKit;
    private HomeClient homeClient;
    private WardService wardService;
    @TempDir Path workspace;

    @BeforeEach void setUp() {
        testKit = ActorTestKit.create("WardGrantSyncTest",
            ConfigFactory.parseString("pekko.actor.provider = \"local\""));
        var jdbc = SchemaInitializer.initialize(workspace.resolve("ward.db"));
        var homeStore = new HomeStore(jdbc);
        var registry = testKit.spawn(HomeRegistryActor.create(homeStore));
        homeClient = new HomeClient(registry, testKit.system());
        wardService = new WardService(jdbc);
        wardService.setGrantSync(new WardGrantSync(homeClient));
    }

    @AfterEach void tearDown() {
        if (testKit != null) testKit.shutdownTestKit();
    }

    @Test void study_room_ward_mirrors_as_home_room_grant() {
        var ownerId = "alice-001";
        var studyRoom = StudyProvisioner.studyRoomId(ownerId);
        var friend = "bob-002";

        assertThat(wardService.grant(studyRoom, friend, "enter", ownerId)).isTrue();

        var resource = ResourceUri.of(ownerId, ResourceTypeRegistry.HOME_ROOM);
        var active = homeClient.listIssuedBy(ownerId).stream()
            .filter(g -> g.isActive(Instant.now()))
            .toList();
        assertThat(active).hasSize(1);
        var g = active.get(0);
        assertThat(g.subject()).isEqualTo(friend);
        assertThat(g.resource().toString()).isEqualTo(resource.toString());
        assertThat(g.capability()).isEqualTo(Capability.use);
        assertThat(g.scope()).containsEntry("ward", "enter");
    }

    @Test void non_home_room_ward_is_not_mirrored() {
        wardService.grant("nexus", "bob-002", "enter", "system");
        // No owner, no grant.
        assertThat(homeClient.listIssuedBy("nexus")).isEmpty();
    }

    @Test void ward_revoke_revokes_grant() {
        var ownerId = "alice-002";
        var studyRoom = StudyProvisioner.studyRoomId(ownerId);
        var friend = "bob-003";
        wardService.grant(studyRoom, friend, "enter", ownerId);
        wardService.revoke(studyRoom, friend, "enter");

        var active = homeClient.listIssuedBy(ownerId).stream()
            .filter(g -> g.isActive(Instant.now()))
            .toList();
        assertThat(active).isEmpty();
    }

    @Test void wildcard_principal_becomes_public_subject() {
        var ownerId = "alice-003";
        var studyRoom = StudyProvisioner.studyRoomId(ownerId);
        wardService.grant(studyRoom, "*", "enter", ownerId);

        var active = homeClient.listIssuedBy(ownerId).stream()
            .filter(g -> g.isActive(Instant.now()))
            .toList();
        assertThat(active).hasSize(1);
        assertThat(active.get(0).subject()).isEqualTo(Grant.PUBLIC_SUBJECT);
    }

    @Test void clearWards_revokes_all_mirror_grants() {
        var ownerId = "alice-004";
        var studyRoom = StudyProvisioner.studyRoomId(ownerId);
        wardService.grant(studyRoom, "bob-005", "enter", ownerId);
        wardService.grant(studyRoom, "carol-006", "enter", ownerId);

        var activeBefore = homeClient.listIssuedBy(ownerId).stream()
            .filter(g -> g.isActive(Instant.now()))
            .toList();
        assertThat(activeBefore).hasSize(2);

        wardService.clearWards(studyRoom);

        var activeAfter = homeClient.listIssuedBy(ownerId).stream()
            .filter(g -> g.isActive(Instant.now()))
            .toList();
        assertThat(activeAfter).isEmpty();
    }
}
