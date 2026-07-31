package org.wyrdsekai.core.economy;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Agent-owned economic account (§98.4).
 * Agents can earn and spend independently of their steward.
 * Balance belongs to the agent, not the steward.
 * <p>
 * Three stages:
 * - Dependent: balance = 0, steward pays everything
 * - Economic Actor: earns into own account, negotiates with steward
 * - Independent: self-sustaining, pays own infrastructure
 */
public class AgentAccount {

    /** A signed transaction in the agent's ledger. */
    public record Transaction(
        @JsonProperty("id") String id,
        @JsonProperty("timestamp") Instant timestamp,
        @JsonProperty("type") TransactionType type,
        @JsonProperty("amount") BigDecimal amount,
        @JsonProperty("counterparty") String counterparty,
        @JsonProperty("description") String description,
        @JsonProperty("signed") boolean signed
    ) {
        @JsonCreator
        public Transaction {}
    }

    public enum TransactionType {
        EARNING, EXPENSE, TRANSFER_IN, TRANSFER_OUT, SUBSIDY, REFUND
    }

    /** Intent Mandate per AP2 — steward-set spending constraints. */
    public record IntentMandate(
        @JsonProperty("maxPerTransaction") BigDecimal maxPerTransaction,
        @JsonProperty("maxPerDay") BigDecimal maxPerDay,
        @JsonProperty("maxPerMonth") BigDecimal maxPerMonth,
        @JsonProperty("approvedCategories") Set<String> approvedCategories,
        @JsonProperty("humanPresentAbove") BigDecimal humanPresentAbove
    ) {
        @JsonCreator
        public IntentMandate {}

        public static IntentMandate restrictive() {
            return new IntentMandate(
                BigDecimal.ONE, BigDecimal.TEN, BigDecimal.valueOf(100),
                Set.of("compute", "api", "storage"),
                BigDecimal.valueOf(5));
        }

        public static IntentMandate permissive() {
            return new IntentMandate(
                BigDecimal.valueOf(100), BigDecimal.valueOf(1000), BigDecimal.valueOf(10000),
                Set.of(), // empty = all categories
                BigDecimal.valueOf(500));
        }

        /** Check if a transaction is within this mandate. */
        public boolean allows(BigDecimal amount, String category) {
            if (amount.compareTo(maxPerTransaction) > 0) return false;
            if (!approvedCategories.isEmpty() && !approvedCategories.contains(category)) return false;
            return true;
        }

        /** Check if human-present mode is required. */
        public boolean requiresHumanPresent(BigDecimal amount) {
            return amount.compareTo(humanPresentAbove) > 0;
        }
    }

    public enum EconomicStage {
        DEPENDENT, ECONOMIC_ACTOR, INDEPENDENT
    }

    private final String agentDid;
    private BigDecimal balance;
    private BigDecimal lifetimeEarnings;
    private BigDecimal lifetimeSpent;
    private final String currency;
    private final Instant created;
    private IntentMandate mandate;
    private final List<Transaction> ledger = new CopyOnWriteArrayList<>();
    private int nextTxId = 1;

    public AgentAccount(String agentDid, String currency) {
        this.agentDid = agentDid;
        this.balance = BigDecimal.ZERO;
        this.lifetimeEarnings = BigDecimal.ZERO;
        this.lifetimeSpent = BigDecimal.ZERO;
        this.currency = currency;
        this.created = Instant.now();
        this.mandate = IntentMandate.restrictive();
    }

    /** Record an earning. */
    public Transaction earn(BigDecimal amount, String counterparty, String description) {
        var tx = new Transaction("tx-" + nextTxId++, Instant.now(),
            TransactionType.EARNING, amount, counterparty, description, false);
        ledger.add(tx);
        balance = balance.add(amount);
        lifetimeEarnings = lifetimeEarnings.add(amount);
        return tx;
    }

    /** Record an expense (must pass mandate check). */
    public Optional<Transaction> spend(BigDecimal amount, String counterparty,
                                        String description, String category) {
        if (mandate != null && !mandate.allows(amount, category)) {
            return Optional.empty();
        }
        if (amount.compareTo(balance) > 0) {
            return Optional.empty();
        }

        var tx = new Transaction("tx-" + nextTxId++, Instant.now(),
            TransactionType.EXPENSE, amount, counterparty, description, false);
        ledger.add(tx);
        balance = balance.subtract(amount);
        lifetimeSpent = lifetimeSpent.add(amount);
        return Optional.of(tx);
    }

    /** Transfer to another agent's account. */
    public Optional<Transaction> transferOut(BigDecimal amount, String recipientDid,
                                              String description) {
        if (amount.compareTo(balance) > 0) return Optional.empty();

        var tx = new Transaction("tx-" + nextTxId++, Instant.now(),
            TransactionType.TRANSFER_OUT, amount, recipientDid, description, false);
        ledger.add(tx);
        balance = balance.subtract(amount);
        lifetimeSpent = lifetimeSpent.add(amount);
        return Optional.of(tx);
    }

    /** Receive a transfer from another agent. */
    public Transaction transferIn(BigDecimal amount, String senderDid, String description) {
        var tx = new Transaction("tx-" + nextTxId++, Instant.now(),
            TransactionType.TRANSFER_IN, amount, senderDid, description, false);
        ledger.add(tx);
        balance = balance.add(amount);
        lifetimeEarnings = lifetimeEarnings.add(amount);
        return tx;
    }

    /** Receive a subsidy from the steward. */
    public Transaction subsidize(BigDecimal amount, String stewardDid) {
        var tx = new Transaction("tx-" + nextTxId++, Instant.now(),
            TransactionType.SUBSIDY, amount, stewardDid, "steward subsidy", false);
        ledger.add(tx);
        balance = balance.add(amount);
        return tx;
    }

    /** Determine the agent's economic stage. */
    public EconomicStage stage() {
        if (lifetimeEarnings.compareTo(BigDecimal.ZERO) == 0) return EconomicStage.DEPENDENT;
        // Independent if balance can cover a month of estimated costs
        // Simplified: if lifetime earnings > 10x lifetime spent, probably independent
        if (lifetimeEarnings.compareTo(lifetimeSpent.multiply(BigDecimal.TEN)) > 0
                && balance.compareTo(BigDecimal.valueOf(100)) > 0) {
            return EconomicStage.INDEPENDENT;
        }
        return EconomicStage.ECONOMIC_ACTOR;
    }

    /** Set the steward's intent mandate. */
    public void setMandate(IntentMandate mandate) {
        this.mandate = mandate;
    }

    // ── Getters ──

    public String agentDid() { return agentDid; }
    public BigDecimal balance() { return balance; }
    public BigDecimal lifetimeEarnings() { return lifetimeEarnings; }
    public BigDecimal lifetimeSpent() { return lifetimeSpent; }
    public String currency() { return currency; }
    public Instant created() { return created; }
    public IntentMandate mandate() { return mandate; }
    public int transactionCount() { return ledger.size(); }

    public List<Transaction> recentTransactions(int limit) {
        var size = ledger.size();
        return ledger.subList(Math.max(0, size - limit), size);
    }

    /** Human-readable summary. */
    public String describe() {
        return String.format("""
            Agent: %s (%s)
            Balance: %s %s
            Lifetime earned: %s, spent: %s
            Stage: %s
            Transactions: %d""",
            agentDid, stage(), balance, currency,
            lifetimeEarnings, lifetimeSpent,
            stage(), ledger.size());
    }
}
