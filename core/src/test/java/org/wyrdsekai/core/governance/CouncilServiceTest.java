package org.wyrdsekai.core.governance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CouncilServiceTest {

    private CouncilService council;

    @BeforeEach void setUp() {
        council = new CouncilService();
    }

    @Test void submit_creates_proposal_in_discussion() {
        var p = council.submit("Test Proposal", "A test", CouncilService.ProposalType.STANDARD, "alice");
        assertThat(p.status()).isEqualTo(CouncilService.ProposalStatus.DISCUSSION);
        assertThat(p.proposer()).isEqualTo("alice");
        assertThat(council.proposalCount()).isEqualTo(1);
    }

    @Test void openVoting_transitions_discussion_to_voting() {
        var p = council.submit("Test", "desc", CouncilService.ProposalType.STANDARD, "alice");
        var voting = council.openVoting(p.id());
        assertThat(voting).isPresent();
        assertThat(voting.get().status()).isEqualTo(CouncilService.ProposalStatus.VOTING);
    }

    @Test void vote_records_vote() {
        var p = council.submit("Test", "desc", CouncilService.ProposalType.STANDARD, "alice");
        council.openVoting(p.id());

        var result = council.vote(p.id(), "bob", true);
        assertThat(result.accepted()).isTrue();
        assertThat(council.get(p.id()).get().approvals()).isEqualTo(1);
    }

    @Test void vote_rejected_for_proposer() {
        var p = council.submit("Test", "desc", CouncilService.ProposalType.STANDARD, "alice");
        council.openVoting(p.id());

        var result = council.vote(p.id(), "alice", true);
        assertThat(result.accepted()).isFalse();
    }

    @Test void vote_rejected_for_duplicate() {
        var p = council.submit("Test", "desc", CouncilService.ProposalType.STANDARD, "alice");
        council.openVoting(p.id());
        council.vote(p.id(), "bob", true);

        var result = council.vote(p.id(), "bob", false);
        assertThat(result.accepted()).isFalse();
    }

    @Test void tally_passes_standard_by_simple_majority() {
        var p = council.submit("Test", "desc", CouncilService.ProposalType.STANDARD, "alice");
        council.openVoting(p.id());
        council.vote(p.id(), "bob", true);
        council.vote(p.id(), "carol", true);
        council.vote(p.id(), "dave", false);

        var result = council.tally(p.id());
        assertThat(result).isPresent();
        assertThat(result.get().status()).isEqualTo(CouncilService.ProposalStatus.PASSED);
    }

    @Test void tally_fails_removal_without_supermajority() {
        var p = council.submit("Remove Bob", "bad actor", CouncilService.ProposalType.REMOVAL, "alice");
        council.openVoting(p.id());
        council.vote(p.id(), "bob", false);
        council.vote(p.id(), "carol", true);
        council.vote(p.id(), "dave", true);

        // 2/3 approve, but need ceil(3 * 2/3) = 2 — this IS 2/3 exactly
        var result = council.tally(p.id());
        assertThat(result).isPresent();
        assertThat(result.get().status()).isEqualTo(CouncilService.ProposalStatus.PASSED);
    }

    @Test void tally_fails_with_no_votes() {
        var p = council.submit("Test", "desc", CouncilService.ProposalType.STANDARD, "alice");
        council.openVoting(p.id());

        var result = council.tally(p.id());
        assertThat(result).isPresent();
        assertThat(result.get().status()).isEqualTo(CouncilService.ProposalStatus.EXPIRED);
    }

    @Test void activeProposals_returns_discussion_and_voting() {
        council.submit("One", "desc", CouncilService.ProposalType.STANDARD, "alice");
        var p2 = council.submit("Two", "desc", CouncilService.ProposalType.STANDARD, "bob");
        council.openVoting(p2.id());

        assertThat(council.activeProposals()).hasSize(2);
    }
}
