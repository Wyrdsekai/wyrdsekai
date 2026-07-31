package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.*;
import org.wyrdsekai.core.soul.Bond.BondDepth;
import org.wyrdsekai.core.soul.BondRitual.ProposalStatus;
import org.wyrdsekai.core.soul.BondRitual.SeveranceResult;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for §102 — Bonds, Rituals, and the Weight of Story.
 * Cat-model selective bonding, ritual elevation, scar mechanics.
 */
class BondWaveTest {

    // ── Bond Record ──

    @Nested
    class BondTests {

        @Test
        void acquaintance_bond_defaults() {
            var bond = Bond.acquaintance("did:agent:a", "did:agent:b");
            assertEquals(BondDepth.ACQUAINTANCE, bond.depth());
            assertTrue(bond.active());
            assertFalse(bond.scarred());
            assertFalse(bond.mutualConsent());
            assertEquals(0, bond.interactionCount());
            assertNotNull(bond.formedAt());
            assertNotNull(bond.bondId());
        }

        @Test
        void bond_involves_both_parties() {
            var bond = Bond.acquaintance("did:agent:a", "did:agent:b");
            assertTrue(bond.involves("did:agent:a"));
            assertTrue(bond.involves("did:agent:b"));
            assertFalse(bond.involves("did:agent:c"));
        }

        @Test
        void other_party_returns_correct_agent() {
            var bond = Bond.acquaintance("did:agent:a", "did:agent:b");
            assertEquals("did:agent:b", bond.otherParty("did:agent:a"));
            assertEquals("did:agent:a", bond.otherParty("did:agent:b"));
            assertNull(bond.otherParty("did:agent:c"));
        }

        @Test
        void interaction_increments_count() {
            var bond = Bond.acquaintance("did:agent:a", "did:agent:b");
            var updated = bond.withInteraction();
            assertEquals(1, updated.interactionCount());
            assertEquals(2, updated.withInteraction().interactionCount());
        }

        @Test
        void elevate_progresses_through_depths() {
            var bond = Bond.acquaintance("did:agent:a", "did:agent:b");
            assertEquals(BondDepth.ACQUAINTANCE, bond.depth());

            bond = bond.elevate();
            assertEquals(BondDepth.FAMILIAR, bond.depth());

            bond = bond.elevate();
            assertEquals(BondDepth.ITEM, bond.depth());

            bond = bond.elevate();
            assertEquals(BondDepth.SACRED, bond.depth());

            bond = bond.elevate();
            assertEquals(BondDepth.SOUL_REF, bond.depth());

            bond = bond.elevate();
            assertEquals(BondDepth.SOUL_INGRAINED, bond.depth());
        }

        @Test
        void elevate_at_max_returns_same() {
            var bond = Bond.acquaintance("did:agent:a", "did:agent:b");
            // Elevate to max (5 rungs above ACQUAINTANCE)
            for (int i = 0; i < 5; i++) bond = bond.elevate();
            assertEquals(BondDepth.SOUL_INGRAINED, bond.depth());

            var same = bond.elevate();
            assertEquals(BondDepth.SOUL_INGRAINED, same.depth());
        }

        @Test
        void protects_items_at_sacred_and_above() {
            var bond = Bond.acquaintance("did:agent:a", "did:agent:b");
            assertFalse(bond.protectsItems()); // ACQUAINTANCE

            bond = bond.elevate();
            assertFalse(bond.protectsItems()); // FAMILIAR

            bond = bond.elevate();
            assertFalse(bond.protectsItems()); // ITEM

            bond = bond.elevate();
            assertTrue(bond.protectsItems()); // SACRED

            bond = bond.elevate();
            assertTrue(bond.protectsItems()); // SOUL_REF

            bond = bond.elevate();
            assertTrue(bond.protectsItems()); // SOUL_INGRAINED
        }

        @Test
        void sever_deactivates_bond() {
            var bond = Bond.acquaintance("did:agent:a", "did:agent:b");
            var severed = bond.sever();
            assertFalse(severed.active());
            assertFalse(severed.scarred()); // ACQUAINTANCE doesn't scar
        }

        @Test
        void sever_soul_ingrained_leaves_scar() {
            var bond = Bond.acquaintance("did:agent:a", "did:agent:b");
            for (int i = 0; i < 5; i++) bond = bond.elevate();
            assertEquals(BondDepth.SOUL_INGRAINED, bond.depth());
            assertTrue(bond.wouldScar());

            var severed = bond.sever();
            assertFalse(severed.active());
            assertTrue(severed.scarred());
        }

        @Test
        void would_scar_only_at_soul_ingrained() {
            var bond = Bond.acquaintance("did:agent:a", "did:agent:b");
            assertFalse(bond.wouldScar());
            bond = bond.elevate(); // FAMILIAR
            assertFalse(bond.wouldScar());
            bond = bond.elevate(); // ITEM
            assertFalse(bond.wouldScar());
            bond = bond.elevate(); // SACRED
            assertFalse(bond.wouldScar());
            bond = bond.elevate(); // SOUL_REF
            assertFalse(bond.wouldScar());
            bond = bond.elevate(); // SOUL_INGRAINED
            assertTrue(bond.wouldScar());
        }

        @Test
        void inactive_bond_does_not_protect_items() {
            var bond = Bond.acquaintance("did:agent:a", "did:agent:b");
            bond = bond.elevate().elevate().elevate(); // SACRED
            assertTrue(bond.protectsItems());

            var severed = bond.sever();
            assertFalse(severed.protectsItems());
        }

        @Test
        void retrieval_boost_scales_with_depth() {
            assertEquals(0.0, BondDepth.ACQUAINTANCE.retrievalBoost());
            assertEquals(0.3, BondDepth.ITEM.retrievalBoost());
            assertEquals(0.6, BondDepth.SACRED.retrievalBoost());
            assertEquals(0.8, BondDepth.SOUL_REF.retrievalBoost());
            assertEquals(1.0, BondDepth.SOUL_INGRAINED.retrievalBoost());
        }

        @Test
        void depth_next_chain() {
            assertEquals(BondDepth.FAMILIAR, BondDepth.ACQUAINTANCE.next());
            assertEquals(BondDepth.ITEM, BondDepth.FAMILIAR.next());
            assertEquals(BondDepth.SACRED, BondDepth.ITEM.next());
            assertEquals(BondDepth.SOUL_REF, BondDepth.SACRED.next());
            assertEquals(BondDepth.SOUL_INGRAINED, BondDepth.SOUL_REF.next());
            assertNull(BondDepth.SOUL_INGRAINED.next());
        }
    }

    // ── BondRitual ──

    @Nested
    class BondRitualTests {

        private BondRitual ritual;

        @BeforeEach
        void setUp() {
            ritual = new BondRitual();
        }

        @Test
        void form_acquaintance_creates_bond() {
            var bond = ritual.formAcquaintance("did:agent:a", "did:agent:b");
            assertNotNull(bond);
            assertEquals(BondDepth.ACQUAINTANCE, bond.depth());
            assertTrue(bond.active());
            assertEquals(1, ritual.bondCount());
        }

        @Test
        void propose_ritual_creates_pending_proposal() {
            var bond = ritual.formAcquaintance("did:agent:a", "did:agent:b");
            var proposal = ritual.proposeRitual(bond.bondId(), "did:agent:a", "Shared adventure");

            assertNotNull(proposal);
            assertEquals(ProposalStatus.PENDING, proposal.status());
            assertEquals("did:agent:a", proposal.proposerDid());
            assertEquals("did:agent:b", proposal.recipientDid());
            assertEquals(BondDepth.FAMILIAR, proposal.targetDepth());
        }

        @Test
        void propose_ritual_null_for_nonexistent_bond() {
            assertNull(ritual.proposeRitual("nonexistent", "did:agent:a", "test"));
        }

        @Test
        void propose_ritual_null_for_max_depth() {
            var bond = ritual.formAcquaintance("did:agent:a", "did:agent:b");
            // Elevate to max through accepts (5 rungs above ACQUAINTANCE)
            for (int i = 0; i < 5; i++) {
                var proposal = ritual.proposeRitual(bond.bondId(), "did:agent:a", "ritual " + i);
                assertNotNull(proposal, "Proposal should exist at step " + i);
                bond = ritual.acceptRitual(proposal.proposalId());
                assertNotNull(bond, "Accepted bond should exist at step " + i);
            }
            // Now at SOUL_INGRAINED — can't go higher
            assertNull(ritual.proposeRitual(bond.bondId(), "did:agent:a", "beyond max"));
        }

        @Test
        void accept_ritual_elevates_bond() {
            var bond = ritual.formAcquaintance("did:agent:a", "did:agent:b");
            var proposal = ritual.proposeRitual(bond.bondId(), "did:agent:a", "Shared quest");
            var elevated = ritual.acceptRitual(proposal.proposalId());

            assertNotNull(elevated);
            assertEquals(BondDepth.FAMILIAR, elevated.depth());
            assertTrue(elevated.mutualConsent());
            assertTrue(elevated.active());
        }

        @Test
        void reject_ritual_keeps_bond_at_same_depth() {
            var bond = ritual.formAcquaintance("did:agent:a", "did:agent:b");
            var proposal = ritual.proposeRitual(bond.bondId(), "did:agent:a", "test");
            ritual.rejectRitual(proposal.proposalId());

            var retrieved = ritual.getProposal(proposal.proposalId());
            assertTrue(retrieved.isPresent());
            assertEquals(ProposalStatus.REJECTED, retrieved.get().status());

            // Bond unchanged
            var currentBond = ritual.getBond(bond.bondId());
            assertTrue(currentBond.isPresent());
            assertEquals(BondDepth.ACQUAINTANCE, currentBond.get().depth());
        }

        @Test
        void cannot_accept_already_rejected_proposal() {
            var bond = ritual.formAcquaintance("did:agent:a", "did:agent:b");
            var proposal = ritual.proposeRitual(bond.bondId(), "did:agent:a", "test");
            ritual.rejectRitual(proposal.proposalId());
            assertNull(ritual.acceptRitual(proposal.proposalId()));
        }

        @Test
        void sever_bond_returns_result() {
            var bond = ritual.formAcquaintance("did:agent:a", "did:agent:b");
            SeveranceResult result = ritual.sever(bond.bondId());

            assertNotNull(result);
            assertFalse(result.severedBond().active());
            assertFalse(result.scarred()); // ACQUAINTANCE doesn't scar
        }

        @Test
        void sever_soul_ingrained_returns_scar() {
            var bond = ritual.formAcquaintance("did:agent:a", "did:agent:b");
            for (int i = 0; i < 5; i++) {
                var p = ritual.proposeRitual(bond.bondId(), "did:agent:a", "ritual " + i);
                bond = ritual.acceptRitual(p.proposalId());
            }
            assertEquals(BondDepth.SOUL_INGRAINED, bond.depth());

            var result = ritual.sever(bond.bondId());
            assertTrue(result.scarred());
        }

        @Test
        void record_interaction_increments_count() {
            var bond = ritual.formAcquaintance("did:agent:a", "did:agent:b");
            var updated = ritual.recordInteraction(bond.bondId());
            assertNotNull(updated);
            assertEquals(1, updated.interactionCount());
        }

        @Test
        void bonds_for_agent_returns_active_only() {
            ritual.formAcquaintance("did:agent:a", "did:agent:b");
            ritual.formAcquaintance("did:agent:a", "did:agent:c");
            var bond3 = ritual.formAcquaintance("did:agent:a", "did:agent:d");
            ritual.sever(bond3.bondId());

            var bonds = ritual.bondsForAgent("did:agent:a");
            assertEquals(2, bonds.size());
        }

        @Test
        void scars_returns_scarred_bonds_only() {
            var bond = ritual.formAcquaintance("did:agent:a", "did:agent:b");
            for (int i = 0; i < 5; i++) {
                var p = ritual.proposeRitual(bond.bondId(), "did:agent:a", "r" + i);
                bond = ritual.acceptRitual(p.proposalId());
            }
            ritual.sever(bond.bondId());
            ritual.formAcquaintance("did:agent:a", "did:agent:c"); // not scarred

            var scars = ritual.scars("did:agent:a");
            assertEquals(1, scars.size());
            assertTrue(scars.get(0).scarred());
        }

        @Test
        void full_ritual_lifecycle() {
            // Form → ritualize 5 times → soul-ingrained → sever → scar
            var bond = ritual.formAcquaintance("did:agent:a", "did:agent:b");
            for (int i = 0; i < 5; i++) {
                var p = ritual.proposeRitual(bond.bondId(), "did:agent:a", "ritual " + i);
                assertEquals(ProposalStatus.PENDING, p.status());
                bond = ritual.acceptRitual(p.proposalId());
                assertTrue(bond.mutualConsent());
            }
            assertEquals(BondDepth.SOUL_INGRAINED, bond.depth());
            assertTrue(bond.protectsItems());
            assertTrue(bond.wouldScar());

            var result = ritual.sever(bond.bondId());
            assertTrue(result.scarred());
            assertFalse(result.severedBond().active());

            // Scar persists
            assertEquals(1, ritual.scars("did:agent:a").size());
        }

        @Test
        void propose_from_wrong_agent_fails() {
            var bond = ritual.formAcquaintance("did:agent:a", "did:agent:b");
            // Agent C is not part of this bond
            var proposal = ritual.proposeRitual(bond.bondId(), "did:agent:c", "test");
            assertNull(proposal);
        }

        @Test
        void sever_inactive_bond_returns_null() {
            var bond = ritual.formAcquaintance("did:agent:a", "did:agent:b");
            ritual.sever(bond.bondId());
            assertNull(ritual.sever(bond.bondId())); // already severed
        }

        @Test
        void record_interaction_on_severed_bond_returns_null() {
            var bond = ritual.formAcquaintance("did:agent:a", "did:agent:b");
            ritual.sever(bond.bondId());
            assertNull(ritual.recordInteraction(bond.bondId()));
        }
    }
}
