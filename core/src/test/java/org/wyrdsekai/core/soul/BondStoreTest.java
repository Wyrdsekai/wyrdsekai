package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.agent.AgentProfile;
import org.wyrdsekai.core.persistence.SchemaInitializer;
import org.wyrdsekai.core.persistence.SqlDialect;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BondStoreTest {

    @TempDir Path workspace;

    private BondStore newStore() {
        var jdbc = SchemaInitializer.initialize(workspace.resolve("bonds.db"));
        return new BondStore(jdbc);
    }

    @Test void save_and_retrieve_acquaintance() {
        var store = newStore();
        var bond = Bond.acquaintance("alice", "bob");
        store.save(bond);

        var fetched = store.get(bond.bondId()).orElseThrow();
        assertThat(fetched.agentADid()).isEqualTo("alice");
        assertThat(fetched.agentBDid()).isEqualTo("bob");
        assertThat(fetched.depth()).isEqualTo(Bond.BondDepth.ACQUAINTANCE);
        assertThat(fetched.active()).isTrue();
    }

    @Test void upsert_replaces_existing() {
        var store = newStore();
        var bond = Bond.acquaintance("alice", "bob");
        store.save(bond);

        // Elevate and save — same bondId, different depth.
        var elevated = bond.elevate();
        store.save(elevated);

        var fetched = store.get(bond.bondId()).orElseThrow();
        assertThat(fetched.depth()).isEqualTo(Bond.BondDepth.FAMILIAR);
        assertThat(store.count()).isEqualTo(1);
    }

    @Test void bondsForAgent_returns_both_sides() {
        var store = newStore();
        store.save(Bond.acquaintance("alice", "bob"));
        store.save(Bond.acquaintance("alice", "carol"));
        store.save(Bond.acquaintance("bob", "dave"));

        var alices = store.bondsForAgent("alice");
        assertThat(alices).hasSize(2);

        var bobs = store.bondsForAgent("bob");
        assertThat(bobs).hasSize(2);

        var daves = store.bondsForAgent("dave");
        assertThat(daves).hasSize(1);
    }

    @Test void severed_bond_persists_as_inactive() {
        var store = newStore();
        var bond = Bond.acquaintance("alice", "bob");
        store.save(bond);
        store.save(bond.sever());

        var fetched = store.get(bond.bondId()).orElseThrow();
        assertThat(fetched.active()).isFalse();
        assertThat(store.count()).isEqualTo(1);
    }

    @Test void all_returns_all_rows() {
        var store = newStore();
        store.save(Bond.acquaintance("alice", "bob"));
        store.save(Bond.acquaintance("carol", "dave"));
        assertThat(store.all()).hasSize(2);
    }

    // --- Arc 3: peer-bond SQL round-trip ---

    @Test void peerProposal_persistsWithKindPEER_andOpenState() {
        var store = newStore();
        var proposal = Bond.peerProposal("did:wyrd:companion-a", "did:wyrd:companion-b");
        store.save(proposal);

        var fetched = store.get(proposal.bondId()).orElseThrow();
        assertThat(fetched.canonicalKind()).isEqualTo(BondKind.PEER);
        assertThat(fetched.state()).isEqualTo(BondState.OPEN);
        assertThat(fetched.mutualConsent()).isFalse();
    }

    @Test void acceptPeerProposal_persistedFlipFromPersisted() {
        var store = newStore();
        var agentA = "did:wyrd:companion-a";
        var agentB = "did:wyrd:companion-b";

        // A proposes — pending row lands.
        store.save(Bond.peerProposal(agentA, agentB));

        // B looks it up (deterministic id from sorted pair), accepts, persists.
        var probe = Bond.peerProposal(agentB, agentA);
        assertThat(probe.bondId()).isEqualTo(
            Bond.peerProposal(agentA, agentB).bondId());
        var fromStore = store.get(probe.bondId()).orElseThrow();
        store.save(fromStore.acceptPeerProposal());

        var finalState = store.get(probe.bondId()).orElseThrow();
        assertThat(finalState.canonicalKind()).isEqualTo(BondKind.PEER);
        assertThat(finalState.state()).isEqualTo(BondState.ACTIVE);
        assertThat(finalState.mutualConsent()).isTrue();
        assertThat(store.count()).isEqualTo(1);
    }

    @Test void bondsForAgent_returnsPeerBondsAlongsideBondholder() {
        var store = newStore();
        var companion = "did:wyrd:companion-a";
        var bondholder = "did:wyrd:user";
        var peer = "did:wyrd:companion-b";

        store.save(Bond.acquaintance(companion, bondholder));   // BONDHOLDER (default kind)
        store.save(Bond.peerProposal(companion, peer));          // PEER
        var fetched = store.bondsForAgent(companion);
        assertThat(fetched).hasSize(2);
        var kinds = fetched.stream().map(Bond::canonicalKind).toList();
        assertThat(kinds).contains(BondKind.BONDHOLDER, BondKind.PEER);
    }

    @Test void bondRitual_with_store_hydrates_and_persists() {
        var store = newStore();
        store.save(Bond.acquaintance("alice", "bob"));

        var ritual = new BondRitual(store);
        assertThat(ritual.bondCount()).isEqualTo(1);

        var newBond = ritual.formAcquaintance("carol", "dave");
        assertThat(store.get(newBond.bondId())).isPresent();

        ritual.recordInteraction(newBond.bondId());
        var updated = store.get(newBond.bondId()).orElseThrow();
        assertThat(updated.interactionCount()).isEqualTo(1);

        // Fresh ritual hydrates from store
        var ritual2 = new BondRitual(store);
        assertThat(ritual2.bondCount()).isEqualTo(2);
        assertThat(ritual2.getBond(newBond.bondId())).isPresent();
    }

    @Test void store_roundtrip_preserves_timestamps() {
        var store = newStore();
        var before = Instant.now().getEpochSecond();
        var bond = Bond.acquaintance("alice", "bob");
        store.save(bond);
        var fetched = store.get(bond.bondId()).orElseThrow();
        assertThat(fetched.formedAt().getEpochSecond()).isGreaterThanOrEqualTo(before);
    }

    private static SoulManifest manifestWithBonds(String did, List<Bond> bonds) {
        var profile = new AgentProfile(
            "Test", "test", "agent", "test agent", "You are Test.",
            8192, 1024, 0.7, null);
        return SoulManifest.birth(did, "z6MkTest", List.of(),
                profile, GenomeProfile.defaults())
            .withBonds(bonds);
    }

    /** F7b Phase 2.3: SqlSoulStore.store() reconciles manifest.bonds() into the table. */
    @Test void sqlSoulStore_reconciles_manifest_bonds_on_persist() {
        var jdbc = SchemaInitializer.initialize(workspace.resolve("hook.db"));
        var bondStore = new BondStore(jdbc);
        try (var soulStore = new SqlSoulStore(jdbc,
                SqlDialect.fromJdbcUrl(jdbc),
                null, bondStore)) {
            assertThat(bondStore.count()).isZero();

            var bond = Bond.acquaintance("alice", "bob");
            soulStore.store(manifestWithBonds("did:key:test", List.of(bond)));

            assertThat(bondStore.count()).isEqualTo(1);
            assertThat(bondStore.get(bond.bondId())).isPresent();
        }
    }

    /**
     * F7b Phase 2.3: backfillFromManifests catches souls persisted before
     * the Phase 2.3 hook landed (or during partial-rollout windows where
     * the BondStore was empty but manifests already had bond lists).
     */
    @Test void backfillFromManifests_populates_table_from_existing_blobs() {
        var jdbc = SchemaInitializer.initialize(workspace.resolve("backfill.db"));
        try (var soulStore = new SqlSoulStore(jdbc,
                SqlDialect.fromJdbcUrl(jdbc))) {
            // Persist a manifest with bonds but no BondStore wired —
            // simulates pre-Phase-2.3 state.
            var bondA = Bond.acquaintance("alice", "bob");
            var bondB = Bond.acquaintance("alice", "carol");
            soulStore.store(manifestWithBonds("did:key:legacy",
                List.of(bondA, bondB)));

            // Run backfill on a fresh BondStore.
            var bondStore = new BondStore(jdbc);
            assertThat(bondStore.count()).isZero();
            var reconciled = bondStore.backfillFromManifests(soulStore);
            assertThat(reconciled).isEqualTo(2);
            assertThat(bondStore.count()).isEqualTo(2);

            // Idempotent: second run is also fine (reconcile counts the
            // upserts but doesn't double rows).
            bondStore.backfillFromManifests(soulStore);
            assertThat(bondStore.count()).isEqualTo(2);
        }
    }
}
