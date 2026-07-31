package org.wyrdsekai.core.economy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/**
 * Agent estate management for lifecycle transitions (§98.7).
 * Handles estate disposition on deletion, independence, and dormancy.
 * <p>
 * Estate = items + balance + relationships + reputation.
 */
public class EstateManager {

    private static volatile EstateManager instance;
    public static void init() { instance = new EstateManager(); }
    public static EstateManager get() { return instance; }

    /** An estate summary for a specific agent. */
    public record EstateSummary(
        String agentDid,
        String agentName,
        int itemCount,
        int relationshipCount,
        BigDecimal balance,
        Instant lastInteraction,
        long ageDays
    ) {}

    /** Estate disposition choice. */
    public enum Disposition {
        /** Export items to archive (§96.9). */
        ARCHIVE,
        /** Transfer items/balance to another agent. */
        TRANSFER,
        /** Tombstone all items. */
        TOMBSTONE,
        /** Return balance to steward's household pool. */
        RETURN_TO_STEWARD,
        /** Donate balance to household shared pool. */
        DONATE_TO_POOL
    }

    /** A disposition plan for an estate. */
    public record DispositionPlan(
        String agentDid,
        Disposition itemDisposition,
        String itemRecipientDid,
        Disposition balanceDisposition,
        String balanceRecipientDid,
        List<String> notifyAgentDids,
        boolean exported
    ) {}

    /** Deletion confirmation display. */
    public record DeletionConfirmation(
        EstateSummary estate,
        List<String> consequences,
        String confirmationPhrase
    ) {}

    /**
     * Generate a deletion confirmation display (§98.2).
     * Makes the weight of the action visible to the steward.
     */
    public DeletionConfirmation confirmDeletion(EstateSummary estate,
                                                  List<String> linkedAgentNames) {
        var consequences = new ArrayList<String>();
        consequences.add("Tombstone all " + estate.itemCount() + " items in the family locker");
        consequences.add("Decommission all buds");

        if (!linkedAgentNames.isEmpty()) {
            consequences.add("Notify linked agents: " + String.join(", ", linkedAgentNames));
        }

        if (estate.balance().compareTo(BigDecimal.ZERO) > 0) {
            consequences.add("Balance of " + estate.balance() + " requires disposition");
        }

        return new DeletionConfirmation(
            estate, List.copyOf(consequences),
            "confirm delete " + estate.agentName().toLowerCase()
        );
    }

    /**
     * Generate a human-readable deletion display.
     */
    public String deletionDisplay(DeletionConfirmation confirmation) {
        var sb = new StringBuilder();
        var e = confirmation.estate();
        sb.append(e.agentName()).append(" has been part of this household for ")
            .append(e.ageDays()).append(" days.\n");
        sb.append(e.itemCount()).append(" soul items accumulated. ")
            .append(e.relationshipCount()).append(" relationships with other agents.\n");
        if (e.lastInteraction() != null) {
            sb.append("Last interaction: ").append(e.lastInteraction()).append("\n");
        }
        sb.append("\nThis will:\n");
        for (var c : confirmation.consequences()) {
            sb.append("- ").append(c).append("\n");
        }
        sb.append("\n").append(e.agentName())
            .append("'s soul can be exported before deletion.\n");
        sb.append("\nType '").append(confirmation.confirmationPhrase())
            .append("' to proceed.");
        return sb.toString();
    }

    /**
     * Execute estate disposition according to a plan.
     * Returns a list of actions taken.
     */
    public List<String> executeDisposition(DispositionPlan plan) {
        var actions = new ArrayList<String>();

        switch (plan.itemDisposition()) {
            case ARCHIVE -> actions.add("Items archived for " + plan.agentDid());
            case TRANSFER -> actions.add("Items transferred to " + plan.itemRecipientDid());
            case TOMBSTONE -> actions.add("All items tombstoned for " + plan.agentDid());
            default -> actions.add("Items: " + plan.itemDisposition());
        }

        switch (plan.balanceDisposition()) {
            case RETURN_TO_STEWARD -> actions.add("Balance returned to steward");
            case TRANSFER -> actions.add("Balance transferred to " + plan.balanceRecipientDid());
            case DONATE_TO_POOL -> actions.add("Balance donated to household pool");
            default -> actions.add("Balance: " + plan.balanceDisposition());
        }

        for (var did : plan.notifyAgentDids()) {
            actions.add("Notified: " + did);
        }

        if (plan.exported()) {
            actions.add("Soul archive exported");
        }

        return actions;
    }
}
