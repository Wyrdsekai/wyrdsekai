package org.wyrdsekai.core.room;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for StudyProvisioner — verifies member and steward study provisioning.
 */
class StudyProvisionerTest {

    @Test void studyRoomId_format() {
        assertThat(StudyProvisioner.studyRoomId("user-123")).isEqualTo("study-user-123");
    }

    @Test void isStudyRoom_detectsStudyRooms() {
        assertThat(StudyProvisioner.isStudyRoom("study-abc")).isTrue();
        assertThat(StudyProvisioner.isStudyRoom("nexus")).isFalse();
        assertThat(StudyProvisioner.isStudyRoom(null)).isFalse();
    }

    @Test void playerIdFromStudy_extractsPlayerId() {
        assertThat(StudyProvisioner.playerIdFromStudy("study-user-123")).isEqualTo("user-123");
        assertThat(StudyProvisioner.playerIdFromStudy("nexus")).isNull();
    }

    @Test void memberStudy_hasBaseObjects() {
        var seed = StudyProvisioner.createStudySeed("user-1", "Alice", false);
        assertThat(seed.roomId()).isEqualTo("study-user-1");
        assertThat(seed.name()).isEqualTo("Alice's Study");

        var objectIds = seed.objects().stream().map(o -> o.id()).toList();
        // Base member objects
        assertThat(objectIds).contains("study-desk", "study-dashboard", "study-journal",
            "study-shelves", "study-pinboard", "study-chair");
        // Wave 1 member objects
        assertThat(objectIds).contains("study-companion-crystal", "study-privacy-ward",
            "study-device-ledger", "study-cost-ledger");
        // Should NOT have steward objects
        assertThat(objectIds).doesNotContain("study-roster", "study-invitation",
            "study-ward-keyring", "study-node-manifest", "study-treasury",
            "study-audit-log", "study-parental", "study-maintenance");
    }

    @Test void stewardStudy_hasAdditionalObjects() {
        var seed = StudyProvisioner.createStudySeed("steward-1", "Masumi", true);
        assertThat(seed.roomId()).isEqualTo("study-steward-1");
        assertThat(seed.name()).isEqualTo("Masumi's Study");

        var objectIds = seed.objects().stream().map(o -> o.id()).toList();
        // Base member objects still present
        assertThat(objectIds).contains("study-desk", "study-dashboard", "study-journal");
        // Steward-only objects
        assertThat(objectIds).contains("study-roster", "study-invitation",
            "study-ward-keyring", "study-node-manifest", "study-treasury",
            "study-audit-log", "study-parental", "study-maintenance");
    }

    @Test void stewardStudy_hasStewardDescription() {
        var seed = StudyProvisioner.createStudySeed("s1", "Admin", true);
        assertThat(seed.description()).contains("steward");
        assertThat(seed.description()).contains("roster");
    }

    @Test void memberStudy_noStewardDescription() {
        var seed = StudyProvisioner.createStudySeed("m1", "User", false);
        assertThat(seed.description()).doesNotContain("steward");
    }

    @Test void stewardStudy_objectCountIsCorrect() {
        var memberSeed = StudyProvisioner.createStudySeed("m", "M", false);
        var stewardSeed = StudyProvisioner.createStudySeed("s", "S", true);
        // Steward has 12 more objects than a member: the 8 original steward-
        // only furnishings (roster, invitation, ward-keyring, node-manifest,
        // treasury, audit-log, parental, maintenance) + Phase 4's scroll of
        // settings + key chest + nostr sigil +
        // Track-C C7 recipes_console.
        assertThat(stewardSeed.objects().size())
            .isEqualTo(memberSeed.objects().size() + 12);
    }

    @Test void backwardCompatible_noStewardArg() {
        // The 2-arg constructor defaults to non-steward
        var seed = StudyProvisioner.createStudySeed("user-1", "Alice");
        var objectIds = seed.objects().stream().map(o -> o.id()).toList();
        assertThat(objectIds).doesNotContain("study-roster");
    }
}
