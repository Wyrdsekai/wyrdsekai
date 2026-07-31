package org.wyrdsekai.core.skill;

import org.wyrdsekai.common.i18n.I18n;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AP2 (Agent Payments Protocol) extension for Wyrdsekai.
 * Bridges agent-to-agent payments via the A2A Gateway (Docks room).
 *
 * AP2 is a Google/PayPal/Visa joint standard for agent payments.
 * Three transaction modes:
 * - Intent: agent expresses desire to pay
 * - Cart: itemized payment with approval
 * - Payment Mandate: recurring or pre-approved payments
 *
 * Integration points:
 * - Counting House: budget enforcement, balance tracking
 * - The Safe: payment credentials (Privacy.com/Stripe API keys)
 * - Trading Post room: agent-facing payment interface
 * - A2A Gateway (Docks): inter-household payment routing
 *
 * @see <a href="https://ap2-protocol.org/specification/">AP2 Specification</a>
 */
public class Ap2Extension {

    private final Map<String, AgentAccount> accounts = new ConcurrentHashMap<>();
    private final List<Transaction> ledger = Collections.synchronizedList(new ArrayList<>());
    private PaymentProvider paymentProvider;

    /** Set the payment provider (Privacy.com, Stripe, or mock). */
    public void setPaymentProvider(PaymentProvider provider) {
        this.paymentProvider = provider;
    }

    // --- Account Management ---

    /** Create or get an agent's payment account. */
    public AgentAccount getOrCreateAccount(String agentDid, String stewardDid) {
        return accounts.computeIfAbsent(agentDid, did ->
            new AgentAccount(did, stewardDid, 0, 0, 5000, // $50.00 default monthly limit in cents
                Instant.now(), new ArrayList<>()));
    }

    /** Get account for an agent. */
    public Optional<AgentAccount> getAccount(String agentDid) {
        return Optional.ofNullable(accounts.get(agentDid));
    }

    /** Fund an agent's account (steward adds credits). */
    public FundResult fund(String agentDid, long amountCents, String stewardDid, String description) {
        AgentAccount account = accounts.get(agentDid);
        if (account == null) {
            return new FundResult(false, I18n.get("ap2.fund.not_found"));
        }
        if (!account.stewardDid().equals(stewardDid)) {
            return new FundResult(false, I18n.get("ap2.fund.not_steward"));
        }
        var updated = account.addBalance(amountCents);
        accounts.put(agentDid, updated);
        recordTransaction(agentDid, null, amountCents, TransactionType.FUND, description);
        return new FundResult(true, I18n.get("ap2.fund.success", formatCents(amountCents)));
    }

    // --- AP2 Intent Flow ---

    /** Agent expresses intent to pay (AP2 Intent). */
    public IntentResult createIntent(String agentDid, String merchantDescription,
                                      long amountCents, Map<String, String> metadata) {
        AgentAccount account = accounts.get(agentDid);
        if (account == null) {
            return new IntentResult(false, null, I18n.get("ap2.intent.no_account"));
        }
        if (account.balance() < amountCents) {
            return new IntentResult(false, null, I18n.get("ap2.intent.insufficient",
                formatCents(account.balance()), formatCents(amountCents)));
        }
        if (account.monthlySpent() + amountCents > account.monthlyLimitCents()) {
            return new IntentResult(false, null, I18n.get("ap2.intent.monthly_limit"));
        }

        String intentId = UUID.randomUUID().toString();
        return new IntentResult(true, intentId,
            I18n.get("ap2.intent.created", formatCents(amountCents), merchantDescription));
    }

    /** Execute a payment intent (after optional human approval). */
    public PaymentResult executeIntent(String intentId, String agentDid, long amountCents,
                                        String merchantDescription) {
        AgentAccount account = accounts.get(agentDid);
        if (account == null) {
            return new PaymentResult(false, I18n.get("ap2.intent.no_account"), null);
        }

        // Debit agent account
        if (account.balance() < amountCents) {
            return new PaymentResult(false, I18n.get("ap2.transfer.insufficient"), null);
        }

        // Execute via payment provider (Privacy.com / Stripe)
        if (paymentProvider != null) {
            PaymentProvider.ChargeResult charge = paymentProvider.charge(
                agentDid, amountCents, merchantDescription);
            if (!charge.success()) {
                return new PaymentResult(false, I18n.get("ap2.payment.failed", charge.message()), null);
            }
        }

        // Update account
        var updated = account.debit(amountCents);
        accounts.put(agentDid, updated);
        String txId = recordTransaction(agentDid, merchantDescription, amountCents,
            TransactionType.PAYMENT, merchantDescription);

        return new PaymentResult(true, I18n.get("ap2.payment.success", formatCents(amountCents)), txId);
    }

    // --- AP2 Transfer (Agent-to-Agent) ---

    /** Transfer credits between agents (AP2 Transfer). */
    public TransferResult transfer(String fromAgent, String toAgent,
                                    long amountCents, String description) {
        AgentAccount from = accounts.get(fromAgent);
        AgentAccount to = accounts.get(toAgent);
        if (from == null) return new TransferResult(false, I18n.get("ap2.transfer.sender_missing"));
        if (to == null) return new TransferResult(false, I18n.get("ap2.transfer.recipient_missing"));
        if (from.balance() < amountCents) return new TransferResult(false, I18n.get("ap2.transfer.insufficient"));

        accounts.put(fromAgent, from.debit(amountCents));
        accounts.put(toAgent, to.addBalance(amountCents));

        recordTransaction(fromAgent, toAgent, amountCents, TransactionType.TRANSFER, description);
        return new TransferResult(true, I18n.get("ap2.transfer.success", formatCents(amountCents), toAgent));
    }

    // --- Ledger ---

    /** Get transaction history for an agent. */
    public List<Transaction> transactionsForAgent(String agentDid) {
        return ledger.stream()
            .filter(t -> agentDid.equals(t.agentDid()) || agentDid.equals(t.counterparty()))
            .toList();
    }

    /** Get recent transactions across all agents. */
    public List<Transaction> recentTransactions(int limit) {
        int size = ledger.size();
        return ledger.subList(Math.max(0, size - limit), size);
    }

    private String recordTransaction(String agentDid, String counterparty,
                                      long amountCents, TransactionType type, String description) {
        String txId = UUID.randomUUID().toString();
        ledger.add(new Transaction(txId, agentDid, counterparty, amountCents,
            type, description, Instant.now()));
        return txId;
    }

    private String formatCents(long cents) {
        return String.format("$%.2f", cents / 100.0);
    }

    // --- Records ---

    /** Agent payment account. */
    public record AgentAccount(
        String agentDid,
        String stewardDid,
        long balance,           // in cents
        long monthlySpent,      // in cents, resets monthly
        long monthlyLimitCents, // max monthly spending
        Instant createdAt,
        List<String> cardIds    // virtual card IDs (Privacy.com / Stripe)
    ) {
        public AgentAccount addBalance(long amount) {
            return new AgentAccount(agentDid, stewardDid, balance + amount,
                monthlySpent, monthlyLimitCents, createdAt, cardIds);
        }
        public AgentAccount debit(long amount) {
            return new AgentAccount(agentDid, stewardDid, balance - amount,
                monthlySpent + amount, monthlyLimitCents, createdAt, cardIds);
        }
    }

    public record Transaction(
        String id,
        String agentDid,
        String counterparty,
        long amountCents,
        TransactionType type,
        String description,
        Instant timestamp
    ) {}

    public enum TransactionType { FUND, PAYMENT, TRANSFER, REFUND }

    public record IntentResult(boolean success, String intentId, String message) {}
    public record PaymentResult(boolean success, String message, String transactionId) {}
    public record TransferResult(boolean success, String message) {}
    public record FundResult(boolean success, String message) {}

    /** Abstraction over payment providers (Privacy.com, Stripe, etc.). */
    public interface PaymentProvider {
        ChargeResult charge(String agentDid, long amountCents, String description);
        record ChargeResult(boolean success, String message, String externalId) {}
    }
}
