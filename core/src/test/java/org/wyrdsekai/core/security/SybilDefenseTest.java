package org.wyrdsekai.core.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SybilDefenseTest {

    private SybilDefense defense;

    @BeforeEach void setUp() {
        defense = new SybilDefense();
    }

    // --- Latency Challenge ---

    @Test void issueChallenge_creates_challenge() {
        var challenge = defense.issueChallenge("alice");
        assertThat(challenge.entityId()).isEqualTo("alice");
        assertThat(challenge.nonce()).hasSize(16);
        assertThat(defense.pendingChallengeCount()).isEqualTo(1);
    }

    @Test void verifyChallenge_passes_with_correct_nonce() {
        var challenge = defense.issueChallenge("alice");
        var result = defense.verifyChallenge(challenge.challengeId(), challenge.nonce());
        assertThat(result.passed()).isTrue();
        assertThat(defense.pendingChallengeCount()).isEqualTo(0);
    }

    @Test void verifyChallenge_fails_with_wrong_nonce() {
        var challenge = defense.issueChallenge("alice");
        var result = defense.verifyChallenge(challenge.challengeId(), new byte[16]);
        assertThat(result.passed()).isFalse();
        assertThat(result.reason()).contains("nonce");
    }

    @Test void verifyChallenge_fails_with_unknown_id() {
        var result = defense.verifyChallenge("nonexistent", new byte[16]);
        assertThat(result.passed()).isFalse();
        assertThat(result.reason()).contains("Unknown");
    }

    // --- Proof of Work ---

    @Test void issueProofOfWork_creates_challenge() {
        var pow = defense.issueProofOfWork();
        assertThat(pow.prefix()).isNotEmpty();
        assertThat(pow.difficulty()).isEqualTo(4);
    }

    @Test void verifyProofOfWork_passes_with_leading_zeros() {
        var pow = defense.issueProofOfWork();
        assertThat(defense.verifyProofOfWork(pow, "0000abcdef")).isTrue();
    }

    @Test void verifyProofOfWork_fails_without_leading_zeros() {
        var pow = defense.issueProofOfWork();
        assertThat(defense.verifyProofOfWork(pow, "1234abcdef")).isFalse();
    }

    // --- Graduated Trust ---

    @Test void new_entity_starts_untrusted() {
        var record = defense.getTrustRecord("alice");
        assertThat(record.level()).isEqualTo(SybilDefense.TrustLevel.UNTRUSTED);
    }

    @Test void passing_challenge_upgrades_to_verified() {
        var challenge = defense.issueChallenge("alice");
        defense.verifyChallenge(challenge.challengeId(), challenge.nonce());

        var record = defense.getTrustRecord("alice");
        assertThat(record.level()).isEqualTo(SybilDefense.TrustLevel.VERIFIED);
        assertThat(record.challengesPassed()).isEqualTo(1);
    }

    @Test void interactions_upgrade_verified_to_established() {
        // First become VERIFIED
        var challenge = defense.issueChallenge("alice");
        defense.verifyChallenge(challenge.challengeId(), challenge.nonce());

        // Then interact enough times to reach ESTABLISHED
        for (int i = 0; i < 20; i++) {
            defense.recordInteraction("alice");
        }

        assertThat(defense.getTrustRecord("alice").level())
            .isEqualTo(SybilDefense.TrustLevel.ESTABLISHED);
    }

    @Test void setTrustLevel_manually_sets_level() {
        defense.setTrustLevel("admin", SybilDefense.TrustLevel.CITIZEN);
        assertThat(defense.getTrustRecord("admin").level())
            .isEqualTo(SybilDefense.TrustLevel.CITIZEN);
    }

    @Test void trackedEntityCount_tracks_all() {
        defense.getTrustRecord("alice");
        defense.getTrustRecord("bob");
        assertThat(defense.trackedEntityCount()).isEqualTo(2);
    }
}
