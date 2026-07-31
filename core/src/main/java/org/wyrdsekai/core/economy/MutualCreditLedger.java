package org.wyrdsekai.core.economy;

import org.wyrdsekai.common.i18n.I18n;

import java.time.Instant;
import java.util.*;

/**
 * Double-entry bookkeeping for mutual credit (§17, §68).
 * Every transaction creates two entries: a debit on the sender and a credit on the receiver.
 * Entities can go negative up to their credit limit.
 *
 * Thread-safe via synchronized methods.
 */
public class MutualCreditLedger {

    /** A single transaction record. */
    public record Transaction(
        String id,
        String fromEntity,
        String toEntity,
        long amount,
        String description,
        Instant timestamp
    ) {}

    private final LedgerPersistence persistence; // nullable
    private final Map<String, CreditBalance> balances = new HashMap<>();
    private final List<Transaction> transactionLog = new ArrayList<>();

    public MutualCreditLedger() { this(null); }

    public MutualCreditLedger(LedgerPersistence persistence) {
        this.persistence = persistence;
    }

    /** Get or create a balance for an entity. */
    public synchronized CreditBalance getBalance(String entityId) {
        return balances.computeIfAbsent(entityId, CreditBalance::initial);
    }

    /**
     * Transfer credits from one entity to another.
     * @return the transaction if successful, or empty if insufficient credit
     */
    public synchronized Optional<Transaction> transfer(String fromEntity, String toEntity,
                                                        long amount, String description) {
        if (amount <= 0) return Optional.empty();
        if (fromEntity.equals(toEntity)) return Optional.empty();

        var fromBalance = getBalance(fromEntity);
        if (!fromBalance.canSpend(amount)) {
            return Optional.empty();
        }

        var txId = UUID.randomUUID().toString().substring(0, 8);
        var tx = new Transaction(txId, fromEntity, toEntity, amount, description, Instant.now());

        balances.put(fromEntity, fromBalance.debit(amount));
        balances.put(toEntity, getBalance(toEntity).credit(amount));
        transactionLog.add(tx);

        if (persistence != null) {
            persistence.saveTransaction(tx);
            persistence.saveBalance(fromEntity, balances.get(fromEntity));
            persistence.saveBalance(toEntity, balances.get(toEntity));
        }

        return Optional.of(tx);
    }

    /**
     * Grant credits to an entity (from system/treasury).
     * Does not require a sender balance.
     */
    public synchronized Transaction grant(String toEntity, long amount, String description) {
        var txId = UUID.randomUUID().toString().substring(0, 8);
        var tx = new Transaction(txId, "system", toEntity, amount, description, Instant.now());

        balances.put(toEntity, getBalance(toEntity).credit(amount));
        transactionLog.add(tx);

        if (persistence != null) {
            persistence.saveTransaction(tx);
            persistence.saveBalance(toEntity, balances.get(toEntity));
        }

        return tx;
    }

    /** Update the credit limit for an entity. */
    public synchronized void setCreditLimit(String entityId, long newLimit) {
        balances.put(entityId, getBalance(entityId).withCreditLimit(newLimit));
        if (persistence != null) persistence.saveBalance(entityId, balances.get(entityId));
    }

    /** Get all balances. */
    public synchronized Map<String, CreditBalance> allBalances() {
        return Map.copyOf(balances);
    }

    /** Get recent transactions (most recent first). */
    public synchronized List<Transaction> recentTransactions(int limit) {
        int start = Math.max(0, transactionLog.size() - limit);
        var subList = transactionLog.subList(start, transactionLog.size());
        var result = new ArrayList<>(subList);
        Collections.reverse(result);
        return result;
    }

    /** Total number of transactions. */
    public synchronized int transactionCount() {
        return transactionLog.size();
    }

    /** Human-readable summary. */
    public synchronized String describe() {
        if (balances.isEmpty()) {
            return I18n.get("economy.ledger.no_accounts");
        }
        var sb = new StringBuilder("=== ").append(I18n.get("economy.ledger.title")).append(" ===\n\n");
        sb.append(I18n.get("economy.ledger.accounts", balances.size())).append("\n");
        sb.append(I18n.get("economy.ledger.transactions", transactionLog.size())).append("\n\n");

        balances.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(e -> {
                var b = e.getValue();
                sb.append("  ").append(e.getKey())
                    .append(": ").append(b.balance())
                    .append(" (").append(I18n.get("economy.ledger.limit")).append(": -").append(b.creditLimit())
                    .append(", ").append(I18n.get("economy.ledger.earned")).append(": ").append(b.totalEarned())
                    .append(", ").append(I18n.get("economy.ledger.spent")).append(": ").append(b.totalSpent())
                    .append(")\n");
            });

        return sb.toString().stripTrailing();
    }
}
