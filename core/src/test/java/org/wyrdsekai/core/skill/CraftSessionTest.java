package org.wyrdsekai.core.skill;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CraftSessionTest {

    @Test void create_session() {
        var session = CraftSession.create("s1", "Build a weather tool", "did:alice");
        assertThat(session.sessionId()).isEqualTo("s1");
        assertThat(session.goal()).isEqualTo("Build a weather tool");
        assertThat(session.participants()).containsExactly("did:alice");
        assertThat(session.status()).isEqualTo(CraftSession.SessionStatus.OPEN);
        assertThat(session.isActive()).isTrue();
    }

    @Test void add_participant() {
        var session = CraftSession.create("s1", "goal", "did:alice");
        var updated = session.addParticipant("did:bob");
        assertThat(updated.participants()).containsExactly("did:alice", "did:bob");
    }

    @Test void duplicate_participant_ignored() {
        var session = CraftSession.create("s1", "goal", "did:alice");
        var updated = session.addParticipant("did:alice");
        assertThat(updated.participants()).hasSize(1);
    }

    @Test void add_contribution_transitions_to_in_progress() {
        var session = CraftSession.create("s1", "goal", "did:alice");
        var updated = session.addContribution("did:alice", "design", "Here's my idea");
        assertThat(updated.status()).isEqualTo(CraftSession.SessionStatus.IN_PROGRESS);
        assertThat(updated.contributionCount()).isEqualTo(1);
        assertThat(updated.contributions().getFirst().content()).isEqualTo("Here's my idea");
    }

    @Test void submit_for_review() {
        var session = CraftSession.create("s1", "goal", "did:alice")
            .addContribution("did:alice", "implement", "code here");
        var reviewed = session.submitForReview("function execute(p) {}");
        assertThat(reviewed.status()).isEqualTo(CraftSession.SessionStatus.REVIEWING);
        assertThat(reviewed.currentArtifact()).isEqualTo("function execute(p) {}");
    }

    @Test void complete() {
        var session = CraftSession.create("s1", "goal", "did:alice")
            .submitForReview("artifact");
        var completed = session.complete();
        assertThat(completed.status()).isEqualTo(CraftSession.SessionStatus.COMPLETE);
        assertThat(completed.isActive()).isFalse();
    }

    @Test void abandon() {
        var session = CraftSession.create("s1", "goal", "did:alice");
        var abandoned = session.abandon();
        assertThat(abandoned.status()).isEqualTo(CraftSession.SessionStatus.ABANDONED);
        assertThat(abandoned.isActive()).isFalse();
    }

    @Test void full_lifecycle() {
        var session = CraftSession.create("s1", "Weather skill", "did:alice")
            .addParticipant("did:bob")
            .addContribution("did:alice", "design", "API call to weather.com")
            .addContribution("did:bob", "implement", "function execute(p) { ... }")
            .addContribution("did:alice", "test", "Tested: Tokyo returns data")
            .submitForReview("function execute(p) { return fetch(p.city); }")
            .complete();

        assertThat(session.participants()).containsExactly("did:alice", "did:bob");
        assertThat(session.contributionCount()).isEqualTo(3);
        assertThat(session.status()).isEqualTo(CraftSession.SessionStatus.COMPLETE);
    }
}
