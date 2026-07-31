package org.wyrdsekai.core.economy;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cross-zone credit exchange with negotiated exchange rates (§69).
 * Allows zones to trade credits at agreed-upon rates, with provenance tracking.
 */
public class CrossZoneExchange {

    private static volatile CrossZoneExchange instance;
    public static void init() { instance = new CrossZoneExchange(); }
    public static CrossZoneExchange get() { return instance; }

    /** An exchange rate between two zones. */
    public record ExchangeRate(
        String sourceZoneId,
        String targetZoneId,
        double rate,           // 1 source credit = rate target credits
        Instant negotiatedAt,
        Instant expiresAt
    ) {
        public boolean isExpired() {
            return expiresAt != null && Instant.now().isAfter(expiresAt);
        }

        public boolean isValid() {
            return rate > 0 && !isExpired();
        }

        /** Convert source credits to target credits. */
        public long convert(long sourceCredits) {
            return Math.round(sourceCredits * rate);
        }

        /** Reverse conversion: target credits to source credits. */
        public long reverseConvert(long targetCredits) {
            return Math.round(targetCredits / rate);
        }
    }

    /** A cross-zone exchange transaction. */
    public record ExchangeTransaction(
        String txId,
        String sourceZoneId,
        String targetZoneId,
        String sourceEntityId,
        String targetEntityId,
        long sourceAmount,
        long targetAmount,
        double appliedRate,
        ExchangeStatus status,
        Instant createdAt,
        String description
    ) {}

    public enum ExchangeStatus { PENDING, COMPLETED, FAILED, CANCELLED }

    /** Result of an exchange attempt. */
    public record ExchangeResult(boolean success, String message, ExchangeTransaction transaction) {
        public static ExchangeResult failure(String message) {
            return new ExchangeResult(false, message, null);
        }

        public static ExchangeResult success(ExchangeTransaction tx) {
            return new ExchangeResult(true, "Exchange completed", tx);
        }
    }

    private final Map<String, ExchangeRate> rates = new ConcurrentHashMap<>();
    private final List<ExchangeTransaction> transactions = Collections.synchronizedList(new ArrayList<>());
    private int nextTxId = 1;

    /**
     * Negotiate an exchange rate between two zones.
     * The rate key is "sourceZoneId:targetZoneId".
     */
    public void setRate(String sourceZoneId, String targetZoneId, double rate, Instant expiresAt) {
        var key = rateKey(sourceZoneId, targetZoneId);
        rates.put(key, new ExchangeRate(sourceZoneId, targetZoneId, rate, Instant.now(), expiresAt));
    }

    /** Get the current exchange rate between two zones, if any. */
    public Optional<ExchangeRate> getRate(String sourceZoneId, String targetZoneId) {
        var rate = rates.get(rateKey(sourceZoneId, targetZoneId));
        if (rate == null || rate.isExpired()) return Optional.empty();
        return Optional.of(rate);
    }

    /** List all active (non-expired) exchange rates. */
    public List<ExchangeRate> activeRates() {
        return rates.values().stream()
            .filter(ExchangeRate::isValid)
            .sorted(Comparator.comparing(ExchangeRate::sourceZoneId))
            .toList();
    }

    /**
     * Execute a cross-zone credit exchange.
     * Returns an ExchangeResult with the transaction if successful.
     */
    public synchronized ExchangeResult exchange(
            String sourceZoneId, String targetZoneId,
            String sourceEntityId, String targetEntityId,
            long sourceAmount, String description) {

        if (sourceAmount <= 0) {
            return ExchangeResult.failure("Amount must be positive");
        }

        var rateOpt = getRate(sourceZoneId, targetZoneId);
        if (rateOpt.isEmpty()) {
            return ExchangeResult.failure("No exchange rate between " + sourceZoneId + " and " + targetZoneId);
        }

        var rate = rateOpt.get();
        var targetAmount = rate.convert(sourceAmount);

        if (targetAmount <= 0) {
            return ExchangeResult.failure("Exchange amount too small at current rate");
        }

        var txId = "xz-tx-" + nextTxId++;
        var tx = new ExchangeTransaction(txId, sourceZoneId, targetZoneId,
            sourceEntityId, targetEntityId, sourceAmount, targetAmount,
            rate.rate(), ExchangeStatus.COMPLETED, Instant.now(),
            description != null ? description : "Cross-zone exchange");

        transactions.add(tx);
        return ExchangeResult.success(tx);
    }

    /** Get all transactions for a given entity (as source or target). */
    public List<ExchangeTransaction> transactionsFor(String entityId) {
        return transactions.stream()
            .filter(tx -> tx.sourceEntityId().equals(entityId) || tx.targetEntityId().equals(entityId))
            .toList();
    }

    /** Get all transactions between two zones. */
    public List<ExchangeTransaction> transactionsBetweenZones(String zoneA, String zoneB) {
        return transactions.stream()
            .filter(tx -> (tx.sourceZoneId().equals(zoneA) && tx.targetZoneId().equals(zoneB))
                || (tx.sourceZoneId().equals(zoneB) && tx.targetZoneId().equals(zoneA)))
            .toList();
    }

    /** Total completed transactions. */
    public int transactionCount() {
        return transactions.size();
    }

    /** Calculate net flow from source zone to target zone. */
    public long netFlow(String sourceZoneId, String targetZoneId) {
        return transactions.stream()
            .filter(tx -> tx.status() == ExchangeStatus.COMPLETED)
            .filter(tx -> tx.sourceZoneId().equals(sourceZoneId) && tx.targetZoneId().equals(targetZoneId))
            .mapToLong(ExchangeTransaction::sourceAmount)
            .sum();
    }

    private static String rateKey(String sourceZoneId, String targetZoneId) {
        return sourceZoneId + ":" + targetZoneId;
    }
}
