package org.wyrdsekai.core.governance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.persistence.SchemaInitializer;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CouncilPersistenceTest {

    private CouncilPersistence persistence;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        var dbPath = tempDir.resolve("test.db");
        var jdbcUrl = SchemaInitializer.initialize(dbPath);
        persistence = new CouncilPersistence(jdbcUrl);
    }

    @Test void save_and_load_proposal() {
        var proposal = new CouncilService.Proposal("p-1", "Build a park",
            "We should build a park in the nexus", CouncilService.ProposalType.STANDARD,
            CouncilService.ProposalStatus.DISCUSSION, "alice",
            Instant.ofEpochSecond(1700000000), null, Map.of());
        persistence.saveProposal(proposal);

        var loaded = persistence.loadProposal("p-1");
        assertThat(loaded).isPresent();
        assertThat(loaded.get().title()).isEqualTo("Build a park");
        assertThat(loaded.get().type()).isEqualTo(CouncilService.ProposalType.STANDARD);
        assertThat(loaded.get().status()).isEqualTo(CouncilService.ProposalStatus.DISCUSSION);
        assertThat(loaded.get().proposer()).isEqualTo("alice");
    }

    @Test void proposal_not_found() {
        assertThat(persistence.loadProposal("ghost")).isEmpty();
    }

    @Test void proposal_with_votes() {
        var proposal = new CouncilService.Proposal("p-1", "Tax change",
            "Reduce tithe", CouncilService.ProposalType.TITHE_CHANGE,
            CouncilService.ProposalStatus.VOTING, "alice",
            Instant.ofEpochSecond(1700000000), Instant.ofEpochSecond(1700086400),
            Map.of("bob", true, "carol", false, "dave", true));
        persistence.saveProposal(proposal);

        var loaded = persistence.loadProposal("p-1");
        assertThat(loaded.get().votes()).hasSize(3);
        assertThat(loaded.get().votes().get("bob")).isTrue();
        assertThat(loaded.get().votes().get("carol")).isFalse();
        assertThat(loaded.get().approvals()).isEqualTo(2);
        assertThat(loaded.get().rejections()).isEqualTo(1);
    }

    @Test void active_proposals() {
        persistence.saveProposal(new CouncilService.Proposal("p-1", "A", "",
            CouncilService.ProposalType.STANDARD, CouncilService.ProposalStatus.DISCUSSION,
            "alice", Instant.now(), null, Map.of()));
        persistence.saveProposal(new CouncilService.Proposal("p-2", "B", "",
            CouncilService.ProposalType.STANDARD, CouncilService.ProposalStatus.VOTING,
            "bob", Instant.now(), Instant.now(), Map.of()));
        persistence.saveProposal(new CouncilService.Proposal("p-3", "C", "",
            CouncilService.ProposalType.STANDARD, CouncilService.ProposalStatus.PASSED,
            "carol", Instant.now(), null, Map.of()));

        var active = persistence.activeProposals();
        assertThat(active).hasSize(2);
    }

    @Test void proposal_count() {
        assertThat(persistence.proposalCount()).isEqualTo(0);
        persistence.saveProposal(new CouncilService.Proposal("p-1", "A", "",
            CouncilService.ProposalType.STANDARD, CouncilService.ProposalStatus.DISCUSSION,
            "alice", Instant.now(), null, Map.of()));
        assertThat(persistence.proposalCount()).isEqualTo(1);
    }

    @Test void proposal_upsert() {
        persistence.saveProposal(new CouncilService.Proposal("p-1", "Old", "",
            CouncilService.ProposalType.STANDARD, CouncilService.ProposalStatus.DISCUSSION,
            "alice", Instant.now(), null, Map.of()));
        persistence.saveProposal(new CouncilService.Proposal("p-1", "Old", "",
            CouncilService.ProposalType.STANDARD, CouncilService.ProposalStatus.VOTING,
            "alice", Instant.now(), Instant.now(), Map.of("bob", true)));

        var loaded = persistence.loadProposal("p-1");
        assertThat(loaded.get().status()).isEqualTo(CouncilService.ProposalStatus.VOTING);
        assertThat(loaded.get().votes()).hasSize(1);
    }
}
