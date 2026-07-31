package org.wyrdsekai.core.economy;

import org.wyrdsekai.common.i18n.I18n;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Computes reputation vectors from MutualCreditLedger data (§17).
 * Aggregates trade history into multi-dimensional reputation scores.
 */
public class ReputationService {

    private final MutualCreditLedger ledger;

    public ReputationService(MutualCreditLedger ledger) {
        this.ledger = ledger;
    }

    /** Compute reputation for a single entity. */
    public ReputationVector computeReputation(String entityId) {
        var balance = ledger.getBalance(entityId);
        var allBalances = ledger.allBalances();

        // Uptime: entities with any activity get credit, more transactions = higher
        double uptime = balance.totalEarned() > 0 || balance.totalSpent() > 0 ? 0.6 : 0.3;

        // Quality: ratio of earned to total volume (balanced participation is better)
        long totalVolume = balance.totalEarned() + balance.totalSpent();
        double quality;
        if (totalVolume == 0) {
            quality = 0.5; // neutral for inactive
        } else {
            // Best quality when earned/spent ratio is near 1.0 (balanced)
            double ratio = (double) balance.totalEarned() / totalVolume;
            quality = 1.0 - Math.abs(ratio - 0.5) * 2.0; // peaks at 0.5 ratio
        }

        // Contribution: normalized by max volume across all entities
        long maxVolume = allBalances.values().stream()
            .mapToLong(b -> b.totalEarned() + b.totalSpent())
            .max().orElse(1);
        double contribution = Math.min(1.0, (double) totalVolume / Math.max(maxVolume, 1));

        // Consistency: positive balance relative to credit limit = stable participant
        double consistency;
        if (balance.creditLimit() == 0) {
            consistency = 0.5;
        } else {
            // Range: -1.0 (at limit) to +1.0 (at +creditLimit), mapped to 0..1
            double normalized = (double) balance.balance() / balance.creditLimit();
            consistency = Math.max(0.0, Math.min(1.0, (normalized + 1.0) / 2.0));
        }

        return ReputationVector.of(entityId, uptime, quality, contribution, consistency);
    }

    /** Compute reputation for all known entities. */
    public Map<String, ReputationVector> computeAll() {
        return ledger.allBalances().keySet().stream()
            .collect(Collectors.toMap(id -> id, this::computeReputation));
    }

    /** Human-readable summary of all reputations. */
    public String describe() {
        var all = computeAll();
        if (all.isEmpty()) {
            return I18n.get("economy.reputation.no_data");
        }
        var sb = new StringBuilder("=== ").append(I18n.get("economy.reputation.title")).append(" ===\n\n");
        all.entrySet().stream()
            .sorted((a, b) -> Double.compare(b.getValue().composite(), a.getValue().composite()))
            .forEach(e -> sb.append("  ").append(e.getValue().describe()).append("\n"));
        return sb.toString().stripTrailing();
    }
}
