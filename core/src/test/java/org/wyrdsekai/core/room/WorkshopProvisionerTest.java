package org.wyrdsekai.core.room;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for WorkshopProvisioner — covers per-bondholder CodePlane workshop
 * provisioning.
 */
class WorkshopProvisionerTest {

    @Test void workshopRoomId_format() {
        assertThat(WorkshopProvisioner.workshopRoomId("user-123"))
            .isEqualTo("workshop-codeplane-user-123");
    }

    @Test void isWorkshopRoom_detectsWorkshopRooms() {
        assertThat(WorkshopProvisioner.isWorkshopRoom("workshop-codeplane-abc")).isTrue();
        assertThat(WorkshopProvisioner.isWorkshopRoom("workshop")).isFalse();
        assertThat(WorkshopProvisioner.isWorkshopRoom("study-abc")).isFalse();
        assertThat(WorkshopProvisioner.isWorkshopRoom(null)).isFalse();
    }

    @Test void bondholderIdFromWorkshop_extractsBondholderId() {
        assertThat(WorkshopProvisioner.bondholderIdFromWorkshop("workshop-codeplane-user-123"))
            .isEqualTo("user-123");
        assertThat(WorkshopProvisioner.bondholderIdFromWorkshop("nexus")).isNull();
        assertThat(WorkshopProvisioner.bondholderIdFromWorkshop("workshop-codeplane-"))
            .isEmpty();
    }

    @Test void createWorkshopSeed_hasCoreFurnishings() {
        var seed = WorkshopProvisioner.createWorkshopSeed("u-1", "Masumi");

        assertThat(seed.roomId()).isEqualTo("workshop-codeplane-u-1");
        assertThat(seed.name()).isEqualTo("Masumi's CodePlane Workshop");

        var objectIds = seed.objects().stream().map(o -> o.id()).toList();
        assertThat(objectIds).contains(
            "workshop-bench",
            "workshop-library-shelves",
            "workshop-chronicle-stone",
            "workshop-portal-rack",
            "workshop-forge-link",
            "workshop-familiar-perch"
        );
    }

    @Test void createWorkshopSeed_hasAliases() {
        var seed = WorkshopProvisioner.createWorkshopSeed("u-1", "Masumi");
        assertThat(seed.aliases()).contains("workshop", "codeplane", "code", "studio");
    }

    @Test void createWorkshopSeed_hasExitToNexus() {
        var seed = WorkshopProvisioner.createWorkshopSeed("u-1", "Masumi");
        assertThat(seed.exits()).hasSize(1);
        var exit = seed.exits().get(0);
        assertThat(exit.direction()).isEqualTo("out");
        assertThat(exit.targetRoom()).isEqualTo("nexus");
    }

    @Test void createWorkshopSeed_isDeterministic() {
        // Re-provisioning a workshop must produce identical room state. The
        // ZoneGuardian.seedRoom path is idempotent against the journal, but
        // the seed itself also has to be stable for re-seeds to be no-ops.
        var a = WorkshopProvisioner.createWorkshopSeed("u-1", "Masumi");
        var b = WorkshopProvisioner.createWorkshopSeed("u-1", "Masumi");

        assertThat(a.roomId()).isEqualTo(b.roomId());
        assertThat(a.name()).isEqualTo(b.name());
        assertThat(a.description()).isEqualTo(b.description());
        assertThat(a.aliases()).isEqualTo(b.aliases());
        assertThat(a.exits()).isEqualTo(b.exits());
        assertThat(a.objects()).isEqualTo(b.objects());
    }

    @Test void createWorkshopSeed_describesCodePlaneNature() {
        var seed = WorkshopProvisioner.createWorkshopSeed("u-1", "Masumi");
        // Description should reference the spec's working metaphors so the
        // familiar (and the bondholder) sees a coherent room.
        var desc = seed.description();
        assertThat(desc).contains("workbench");
        assertThat(desc).contains("library");
        assertThat(desc).contains("Chronicle");
        assertThat(desc).contains("Forge");
    }
}
