package org.wyrdsekai.core.skill;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for AP2 (Agent Payments Protocol) extension.
 */
class Ap2ExtensionTest {

    private static final String AGENT = "did:agent:buyer";
    private static final String STEWARD = "did:user:alice";

    private Ap2Extension createFundedExtension(long balanceCents) {
        var ap2 = new Ap2Extension();
        ap2.getOrCreateAccount(AGENT, STEWARD);
        ap2.fund(AGENT, balanceCents, STEWARD, "Initial funding");
        return ap2;
    }

    // ── Account Management ──────────────────────────────────────────────

    @Nested
    class AccountTests {

        @Test
        void create_account() {
            var ap2 = new Ap2Extension();
            var account = ap2.getOrCreateAccount(AGENT, STEWARD);

            assertEquals(AGENT, account.agentDid());
            assertEquals(STEWARD, account.stewardDid());
            assertEquals(0, account.balance());
            assertEquals(5000, account.monthlyLimitCents()); // $50 default
        }

        @Test
        void get_existing_account() {
            var ap2 = new Ap2Extension();
            ap2.getOrCreateAccount(AGENT, STEWARD);
            var fetched = ap2.getAccount(AGENT);

            assertTrue(fetched.isPresent());
            assertEquals(AGENT, fetched.get().agentDid());
        }

        @Test
        void get_nonexistent_account_returns_empty() {
            var ap2 = new Ap2Extension();
            assertTrue(ap2.getAccount("did:agent:nobody").isEmpty());
        }

        @Test
        void idempotent_get_or_create() {
            var ap2 = new Ap2Extension();
            var a1 = ap2.getOrCreateAccount(AGENT, STEWARD);
            ap2.fund(AGENT, 1000, STEWARD, "Fund");
            var a2 = ap2.getOrCreateAccount(AGENT, STEWARD);

            // Should return existing (funded) account, not create new
            assertEquals(1000, a2.balance());
        }
    }

    // ── Funding ─────────────────────────────────────────────────────────

    @Nested
    class FundingTests {

        @Test
        void fund_by_steward() {
            var ap2 = new Ap2Extension();
            ap2.getOrCreateAccount(AGENT, STEWARD);
            var result = ap2.fund(AGENT, 5000, STEWARD, "Monthly allowance");

            assertTrue(result.success());
            assertEquals(5000, ap2.getAccount(AGENT).get().balance());
        }

        @Test
        void fund_by_non_steward_fails() {
            var ap2 = new Ap2Extension();
            ap2.getOrCreateAccount(AGENT, STEWARD);
            var result = ap2.fund(AGENT, 5000, "did:user:bob", "Attempted funding");

            assertFalse(result.success());
            assertTrue(result.message().contains("steward"));
        }

        @Test
        void fund_nonexistent_account_fails() {
            var ap2 = new Ap2Extension();
            var result = ap2.fund("did:agent:nobody", 1000, STEWARD, "Test");

            assertFalse(result.success());
            assertTrue(result.message().contains("not found"));
        }

        @Test
        void fund_records_transaction() {
            var ap2 = new Ap2Extension();
            ap2.getOrCreateAccount(AGENT, STEWARD);
            ap2.fund(AGENT, 5000, STEWARD, "Monthly");

            var txns = ap2.transactionsForAgent(AGENT);
            assertEquals(1, txns.size());
            assertEquals(Ap2Extension.TransactionType.FUND, txns.get(0).type());
            assertEquals(5000, txns.get(0).amountCents());
        }
    }

    // ── Intent Flow ─────────────────────────────────────────────────────

    @Nested
    class IntentTests {

        @Test
        void create_intent_with_balance() {
            var ap2 = createFundedExtension(10000); // $100
            var result = ap2.createIntent(AGENT, "Coffee Shop", 500, Map.of());

            assertTrue(result.success());
            assertNotNull(result.intentId());
        }

        @Test
        void create_intent_insufficient_balance() {
            var ap2 = createFundedExtension(100); // $1
            var result = ap2.createIntent(AGENT, "Expensive Store", 10000, Map.of());

            assertFalse(result.success());
            assertTrue(result.message().contains("Insufficient"));
        }

        @Test
        void create_intent_exceeds_monthly_limit() {
            var ap2 = createFundedExtension(100000); // $1000
            // Default limit is $50 (5000 cents)
            var result = ap2.createIntent(AGENT, "Big Purchase", 6000, Map.of());

            assertFalse(result.success());
            assertTrue(result.message().contains("Monthly limit"));
        }

        @Test
        void create_intent_no_account() {
            var ap2 = new Ap2Extension();
            var result = ap2.createIntent("did:nobody", "Shop", 100, Map.of());

            assertFalse(result.success());
            assertTrue(result.message().contains("No payment account"));
        }
    }

    // ── Payment Execution ───────────────────────────────────────────────

    @Nested
    class PaymentTests {

        @Test
        void execute_intent_debits_account() {
            var ap2 = createFundedExtension(10000); // $100
            var intent = ap2.createIntent(AGENT, "Coffee", 500, Map.of());
            assertTrue(intent.success());

            var payment = ap2.executeIntent(intent.intentId(), AGENT, 500, "Coffee");

            assertTrue(payment.success());
            assertNotNull(payment.transactionId());
            assertEquals(9500, ap2.getAccount(AGENT).get().balance());
        }

        @Test
        void execute_intent_tracks_monthly_spending() {
            var ap2 = createFundedExtension(10000);
            ap2.executeIntent("intent1", AGENT, 500, "Coffee");

            assertEquals(500, ap2.getAccount(AGENT).get().monthlySpent());
        }

        @Test
        void execute_intent_insufficient_balance_after_other_spend() {
            var ap2 = createFundedExtension(1000); // $10
            ap2.executeIntent("i1", AGENT, 800, "First purchase");

            var result = ap2.executeIntent("i2", AGENT, 500, "Second purchase");
            assertFalse(result.success());
            assertTrue(result.message().contains("Insufficient"));
        }

        @Test
        void execute_with_payment_provider_failure() {
            var ap2 = createFundedExtension(10000);
            ap2.setPaymentProvider((agentDid, amount, desc) ->
                new Ap2Extension.PaymentProvider.ChargeResult(false, "Card declined", null));

            var result = ap2.executeIntent("i1", AGENT, 500, "Coffee");
            assertFalse(result.success());
            assertTrue(result.message().contains("Card declined"));
            // Balance should NOT be debited on provider failure
            assertEquals(10000, ap2.getAccount(AGENT).get().balance());
        }

        @Test
        void execute_with_payment_provider_success() {
            var ap2 = createFundedExtension(10000);
            ap2.setPaymentProvider((agentDid, amount, desc) ->
                new Ap2Extension.PaymentProvider.ChargeResult(true, "OK", "ext-123"));

            var result = ap2.executeIntent("i1", AGENT, 500, "Coffee");
            assertTrue(result.success());
            assertEquals(9500, ap2.getAccount(AGENT).get().balance());
        }
    }

    // ── Transfer ────────────────────────────────────────────────────────

    @Nested
    class TransferTests {

        @Test
        void transfer_between_agents() {
            var ap2 = new Ap2Extension();
            String agent2 = "did:agent:seller";

            ap2.getOrCreateAccount(AGENT, STEWARD);
            ap2.getOrCreateAccount(agent2, "did:user:bob");
            ap2.fund(AGENT, 5000, STEWARD, "Fund");

            var result = ap2.transfer(AGENT, agent2, 2000, "Payment for item");

            assertTrue(result.success());
            assertEquals(3000, ap2.getAccount(AGENT).get().balance());
            assertEquals(2000, ap2.getAccount(agent2).get().balance());
        }

        @Test
        void transfer_insufficient_balance() {
            var ap2 = createFundedExtension(100);
            ap2.getOrCreateAccount("did:agent:seller", "did:user:bob");

            var result = ap2.transfer(AGENT, "did:agent:seller", 500, "Too much");
            assertFalse(result.success());
            assertTrue(result.message().contains("Insufficient"));
        }

        @Test
        void transfer_nonexistent_sender() {
            var ap2 = new Ap2Extension();
            ap2.getOrCreateAccount("did:agent:seller", "did:user:bob");

            var result = ap2.transfer("did:agent:nobody", "did:agent:seller", 100, "Test");
            assertFalse(result.success());
            assertTrue(result.message().contains("Sender"));
        }

        @Test
        void transfer_nonexistent_recipient() {
            var ap2 = createFundedExtension(5000);

            var result = ap2.transfer(AGENT, "did:agent:nobody", 100, "Test");
            assertFalse(result.success());
            assertTrue(result.message().contains("Recipient"));
        }

        @Test
        void transfer_records_transaction() {
            var ap2 = new Ap2Extension();
            ap2.getOrCreateAccount(AGENT, STEWARD);
            ap2.getOrCreateAccount("did:agent:seller", "did:user:bob");
            ap2.fund(AGENT, 5000, STEWARD, "Fund");

            ap2.transfer(AGENT, "did:agent:seller", 1000, "Item purchase");

            // Both agents should see the transaction
            var buyerTxns = ap2.transactionsForAgent(AGENT);
            assertTrue(buyerTxns.stream().anyMatch(t ->
                t.type() == Ap2Extension.TransactionType.TRANSFER));

            var sellerTxns = ap2.transactionsForAgent("did:agent:seller");
            assertTrue(sellerTxns.stream().anyMatch(t ->
                t.type() == Ap2Extension.TransactionType.TRANSFER));
        }
    }

    // ── Ledger ──────────────────────────────────────────────────────────

    @Nested
    class LedgerTests {

        @Test
        void recent_transactions() {
            var ap2 = createFundedExtension(10000);
            ap2.executeIntent("i1", AGENT, 100, "Coffee");
            ap2.executeIntent("i2", AGENT, 200, "Tea");

            var recent = ap2.recentTransactions(2);
            assertEquals(2, recent.size());
        }

        @Test
        void recent_transactions_limited() {
            var ap2 = createFundedExtension(10000);
            ap2.executeIntent("i1", AGENT, 100, "A");
            ap2.executeIntent("i2", AGENT, 100, "B");
            ap2.executeIntent("i3", AGENT, 100, "C");

            var recent = ap2.recentTransactions(2);
            assertEquals(2, recent.size());
        }

        @Test
        void transactions_for_agent_filters() {
            var ap2 = new Ap2Extension();
            ap2.getOrCreateAccount(AGENT, STEWARD);
            ap2.getOrCreateAccount("did:agent:other", "did:user:bob");
            ap2.fund(AGENT, 5000, STEWARD, "Fund A");
            ap2.fund("did:agent:other", 3000, "did:user:bob", "Fund B");

            var agentTxns = ap2.transactionsForAgent(AGENT);
            assertTrue(agentTxns.stream().allMatch(t ->
                AGENT.equals(t.agentDid()) || AGENT.equals(t.counterparty())));
        }
    }

    // ── AgentAccount Record ─────────────────────────────────────────────

    @Nested
    class AgentAccountTests {

        @Test
        void add_balance() {
            var account = new Ap2Extension.AgentAccount(
                AGENT, STEWARD, 1000, 0, 5000, Instant.now(), List.of());
            var updated = account.addBalance(500);

            assertEquals(1500, updated.balance());
            assertEquals(0, updated.monthlySpent());
        }

        @Test
        void debit() {
            var account = new Ap2Extension.AgentAccount(
                AGENT, STEWARD, 1000, 200, 5000, Instant.now(), List.of());
            var updated = account.debit(300);

            assertEquals(700, updated.balance());
            assertEquals(500, updated.monthlySpent());
        }
    }
}
