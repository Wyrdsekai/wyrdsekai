package org.wyrdsekai.core.economy;

/**
 * Per-entity credit balance in the Mutual Credit system (§17, §68).
 * Credits can go negative up to the credit limit (mutual credit model).
 *
 * @param entityId    entity identifier
 * @param balance     current balance (positive = credit, negative = debit)
 * @param creditLimit maximum debit allowed (positive number)
 * @param totalEarned cumulative credits earned
 * @param totalSpent  cumulative credits spent
 */
public record CreditBalance(
    String entityId,
    long balance,
    long creditLimit,
    long totalEarned,
    long totalSpent
) {
    /** Default balance for a new entity. */
    public static CreditBalance initial(String entityId) {
        return new CreditBalance(entityId, 0, 100, 0, 0);
    }

    /** Check if this entity can spend the given amount. */
    public boolean canSpend(long amount) {
        return balance - amount >= -creditLimit;
    }

    /** Apply a credit (receiving). */
    public CreditBalance credit(long amount) {
        return new CreditBalance(entityId, balance + amount, creditLimit,
            totalEarned + amount, totalSpent);
    }

    /** Apply a debit (spending). */
    public CreditBalance debit(long amount) {
        return new CreditBalance(entityId, balance - amount, creditLimit,
            totalEarned, totalSpent + amount);
    }

    /** Update credit limit. */
    public CreditBalance withCreditLimit(long newLimit) {
        return new CreditBalance(entityId, balance, newLimit, totalEarned, totalSpent);
    }
}
