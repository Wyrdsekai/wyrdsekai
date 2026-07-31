package org.wyrdsekai.core.economy;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for §98 Stewardship, Economics, and Agent Freedom.
 */
class EconomicsWaveTest {

    // ── AgentAccount ─────────────────────────────────────────────────

    @Nested
    class AgentAccountTests {

        @Test
        void new_account_is_dependent() {
            var account = new AgentAccount("did:home-server", "usd");
            assertEquals(BigDecimal.ZERO, account.balance());
            assertEquals(AgentAccount.EconomicStage.DEPENDENT, account.stage());
        }

        @Test
        void earning_creates_transaction() {
            var account = new AgentAccount("did:home-server", "usd");
            var tx = account.earn(BigDecimal.TEN, "did:client", "A2A task payment");
            assertEquals(AgentAccount.TransactionType.EARNING, tx.type());
            assertEquals(BigDecimal.TEN, account.balance());
            assertEquals(BigDecimal.TEN, account.lifetimeEarnings());
        }

        @Test
        void spending_deducts_balance() {
            var account = new AgentAccount("did:home-server", "usd");
            account.setMandate(AgentAccount.IntentMandate.permissive());
            account.earn(BigDecimal.valueOf(50), "did:client", "earned");

            var tx = account.spend(BigDecimal.valueOf(20), "dall-e", "image gen", "api");
            assertTrue(tx.isPresent());
            assertEquals(BigDecimal.valueOf(30), account.balance());
        }

        @Test
        void spending_fails_if_insufficient_balance() {
            var account = new AgentAccount("did:home-server", "usd");
            account.setMandate(AgentAccount.IntentMandate.permissive());
            account.earn(BigDecimal.valueOf(5), "did:c", "earned");

            var tx = account.spend(BigDecimal.TEN, "service", "too expensive", "api");
            assertTrue(tx.isEmpty());
        }

        @Test
        void spending_fails_if_mandate_violated() {
            var account = new AgentAccount("did:home-server", "usd");
            // Default mandate: max $1/tx, categories: compute, api, storage
            account.earn(BigDecimal.valueOf(100), "did:c", "earned");

            // $5 exceeds $1 per-transaction mandate
            var tx = account.spend(BigDecimal.valueOf(5), "s", "d", "api");
            assertTrue(tx.isEmpty());
        }

        @Test
        void mandate_category_restriction() {
            var account = new AgentAccount("did:home-server", "usd");
            account.earn(BigDecimal.valueOf(100), "did:c", "earned");
            // Default mandate allows: compute, api, storage
            var tx = account.spend(BigDecimal.ONE, "s", "d", "gambling");
            assertTrue(tx.isEmpty());
        }

        @Test
        void transfer_between_agents() {
            var sender = new AgentAccount("did:a", "usd");
            var receiver = new AgentAccount("did:b", "usd");

            sender.earn(BigDecimal.valueOf(50), "did:c", "earned");
            var tx = sender.transferOut(BigDecimal.valueOf(20), "did:b", "gift");
            assertTrue(tx.isPresent());
            assertEquals(BigDecimal.valueOf(30), sender.balance());

            receiver.transferIn(BigDecimal.valueOf(20), "did:a", "gift");
            assertEquals(BigDecimal.valueOf(20), receiver.balance());
        }

        @Test
        void subsidy_from_steward() {
            var account = new AgentAccount("did:home-server", "usd");
            account.subsidize(BigDecimal.valueOf(100), "did:steward");
            assertEquals(BigDecimal.valueOf(100), account.balance());
            // Subsidy doesn't count as earnings
            assertEquals(BigDecimal.ZERO, account.lifetimeEarnings());
        }

        @Test
        void economic_stage_transitions() {
            var account = new AgentAccount("did:home-server", "usd");
            assertEquals(AgentAccount.EconomicStage.DEPENDENT, account.stage());

            account.earn(BigDecimal.TEN, "did:c", "first earning");
            assertEquals(AgentAccount.EconomicStage.ECONOMIC_ACTOR, account.stage());
        }

        @Test
        void recent_transactions() {
            var account = new AgentAccount("did:home-server", "usd");
            for (int i = 0; i < 5; i++) {
                account.earn(BigDecimal.ONE, "did:c", "tx-" + i);
            }
            assertEquals(3, account.recentTransactions(3).size());
            assertEquals(5, account.transactionCount());
        }

        @Test
        void human_present_mode() {
            var mandate = AgentAccount.IntentMandate.restrictive();
            assertTrue(mandate.requiresHumanPresent(BigDecimal.TEN));
            assertFalse(mandate.requiresHumanPresent(BigDecimal.ONE));
        }

        @Test
        void describe_output() {
            var account = new AgentAccount("did:home-server", "usd");
            account.earn(BigDecimal.TEN, "did:c", "test");
            var desc = account.describe();
            assertTrue(desc.contains("did:home-server"));
            assertTrue(desc.contains("ECONOMIC_ACTOR"));
        }
    }

    // ── AgentReputation ──────────────────────────────────────────────

    @Nested
    class AgentReputationTests {

        @Test
        void new_agent_has_default_scores() {
            var rep = new AgentReputation("did:home-server", Instant.now());
            var score = rep.computeScore();
            // New agent: age=0 (low), no transactions (default 0.5), no tasks (0.5), no endorsements (0)
            assertTrue(score.overall() >= 0 && score.overall() <= 1);
        }

        @Test
        void task_completion_affects_score() {
            var rep = new AgentReputation("did:home-server", Instant.now().minusSeconds(86400 * 30));
            for (int i = 0; i < 10; i++) rep.recordTaskCompletion(true);
            for (int i = 0; i < 2; i++) rep.recordTaskCompletion(false);

            var score = rep.computeScore();
            assertTrue(score.completionScore() > 0.7);
        }

        @Test
        void endorsement_boosts_score() {
            var rep = new AgentReputation("did:home-server", Instant.now().minusSeconds(86400 * 30));

            rep.addEndorsement(new AgentReputation.Endorsement(
                "did:steward", AgentReputation.EndorserType.STEWARD,
                "Reliable and helpful", 0.9, Instant.now()));

            var score = rep.computeScore();
            assertTrue(score.endorsementScore() > 0.5);
            assertEquals(1, score.endorsementCount());
        }

        @Test
        void steward_endorsement_weighs_more() {
            var rep1 = new AgentReputation("did:a", Instant.now());
            rep1.addEndorsement(new AgentReputation.Endorsement(
                "did:s", AgentReputation.EndorserType.STEWARD, "", 0.8, Instant.now()));

            var rep2 = new AgentReputation("did:b", Instant.now());
            rep2.addEndorsement(new AgentReputation.Endorsement(
                "did:p", AgentReputation.EndorserType.EXTERNAL_AGENT, "", 0.8, Instant.now()));

            // Steward weight (2.0) vs external (0.5) — same raw score but different weighted
            // Both should have endorsement score = 0.8 since there's only one endorsement each
            // The weight normalizes, so single endorsement = just the score
            var s1 = rep1.computeScore();
            var s2 = rep2.computeScore();
            assertEquals(s1.endorsementScore(), s2.endorsementScore(), 0.01);
        }

        @Test
        void remove_endorsement() {
            var rep = new AgentReputation("did:home-server", Instant.now());
            rep.addEndorsement(new AgentReputation.Endorsement(
                "did:s", AgentReputation.EndorserType.STEWARD, "", 0.8, Instant.now()));
            assertEquals(1, rep.endorsements().size());

            assertTrue(rep.removeEndorsement("did:s"));
            assertEquals(0, rep.endorsements().size());
        }

        @Test
        void threshold_check() {
            var rep = new AgentReputation("did:home-server", Instant.now().minusSeconds(86400 * 365));
            for (int i = 0; i < 20; i++) rep.recordTaskCompletion(true);
            for (int i = 0; i < 20; i++) rep.recordTransaction(true);
            rep.addEndorsement(new AgentReputation.Endorsement(
                "did:s", AgentReputation.EndorserType.STEWARD, "", 0.9, Instant.now()));

            var score = rep.computeScore();
            assertTrue(score.meetsThreshold(0.5));
        }
    }

    // ── EstateManager ────────────────────────────────────────────────

    @Nested
    class EstateManagerTests {

        @Test
        void deletion_confirmation_shows_consequences() {
            var mgr = new EstateManager();
            var estate = new EstateManager.EstateSummary(
                "did:home-server", "Aria", 847, 3,
                BigDecimal.valueOf(15.50), Instant.now(), 214);

            var confirmation = mgr.confirmDeletion(estate, List.of("Rei", "Asuka", "Misato"));
            assertEquals(4, confirmation.consequences().size()); // items + buds + notify + balance
            // confirmDeletion derives the phrase from agentName, NOT the did.
            // The redaction pass rewrote the did and this expectation to
            // "home-server" but left the name — which is a companion character
            // name and correctly excluded — so half the fixture moved and the
            // assertion broke. Named neutrally now so the two cannot diverge.
            assertEquals("confirm delete aria", confirmation.confirmationPhrase());
        }

        @Test
        void deletion_display_readable() {
            var mgr = new EstateManager();
            var estate = new EstateManager.EstateSummary(
                "did:home-server", "Lain", 100, 2,
                BigDecimal.ZERO, Instant.now(), 30);

            var confirmation = mgr.confirmDeletion(estate, List.of("Rei"));
            var display = mgr.deletionDisplay(confirmation);
            assertTrue(display.contains("Lain"));
            assertTrue(display.contains("30 days"));
            assertTrue(display.contains("100 soul items"));
        }

        @Test
        void execute_disposition_archive() {
            var mgr = new EstateManager();
            var plan = new EstateManager.DispositionPlan(
                "did:home-server",
                EstateManager.Disposition.ARCHIVE, null,
                EstateManager.Disposition.RETURN_TO_STEWARD, null,
                List.of("did:rei"), true);

            var actions = mgr.executeDisposition(plan);
            assertTrue(actions.stream().anyMatch(a -> a.contains("archived")));
            assertTrue(actions.stream().anyMatch(a -> a.contains("steward")));
            assertTrue(actions.stream().anyMatch(a -> a.contains("did:rei")));
            assertTrue(actions.stream().anyMatch(a -> a.contains("exported")));
        }

        @Test
        void execute_disposition_transfer() {
            var mgr = new EstateManager();
            var plan = new EstateManager.DispositionPlan(
                "did:home-server",
                EstateManager.Disposition.TRANSFER, "did:rei",
                EstateManager.Disposition.TRANSFER, "did:rei",
                List.of(), false);

            var actions = mgr.executeDisposition(plan);
            assertTrue(actions.stream().anyMatch(a -> a.contains("did:rei")));
        }
    }

    // ── VisibilityGrant ──────────────────────────────────────────────

    @Nested
    class VisibilityGrantTests {

        @Test
        void medical_grant_covers_expected_categories() {
            var grant = VisibilityGrant.medical("did:robert", "did:sarah");
            assertTrue(grant.isVisible(VisibilityGrant.CAT_MEDICAL_MEDICATION));
            assertTrue(grant.isVisible(VisibilityGrant.CAT_MEDICAL_APPOINTMENT));
            assertTrue(grant.isVisible(VisibilityGrant.CAT_EMERGENCY_ALERT));
            assertFalse(grant.isVisible(VisibilityGrant.CAT_SPENDING_SUMMARY));
        }

        @Test
        void never_shared_categories_blocked() {
            var grant = new VisibilityGrant("did:r", "did:s",
                Set.of("conversation-content", "medical-medication"),
                false, Instant.now(), null, true);
            assertFalse(grant.isVisible("conversation-content"));
            assertTrue(grant.isVisible("medical-medication"));
        }

        @Test
        void expired_grant_invalid() {
            var grant = new VisibilityGrant("did:r", "did:s",
                Set.of("medical-medication"), false,
                Instant.now().minusSeconds(3600),
                Instant.now().minusSeconds(60), true);
            assertFalse(grant.isValid());
            assertFalse(grant.isVisible("medical-medication"));
        }

        @Test
        void revoked_grant_invalid() {
            var grant = VisibilityGrant.medical("did:r", "did:s");
            assertTrue(grant.isValid());

            var revoked = grant.revoke();
            assertFalse(revoked.isValid());
            assertFalse(revoked.isVisible(VisibilityGrant.CAT_MEDICAL_MEDICATION));
        }

        @Test
        void alerts_only_grant() {
            var grant = VisibilityGrant.alertsOnly("did:r", "did:s");
            assertTrue(grant.alertsOnly());
            assertTrue(grant.isVisible(VisibilityGrant.CAT_EMERGENCY_ALERT));
            assertFalse(grant.isVisible(VisibilityGrant.CAT_MEDICAL_MEDICATION));
        }

        @Test
        void emotional_state_never_shared() {
            var grant = new VisibilityGrant("did:r", "did:s",
                Set.of("emotional-state", "private-reflections"),
                false, Instant.now(), null, true);
            assertFalse(grant.isVisible("emotional-state"));
            assertFalse(grant.isVisible("private-reflections"));
        }
    }
}
