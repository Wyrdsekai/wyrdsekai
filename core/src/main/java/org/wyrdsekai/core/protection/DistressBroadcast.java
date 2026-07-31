package org.wyrdsekai.core.protection;

import java.time.Instant;
import java.util.*;

/**
 * Between-level distress broadcast (§108.5).
 * No private memory details in signal. Zone-visible, not cross-zone.
 */
public class DistressBroadcast {

    /** A distress signal. */
    public record DistressSignal(
        String signalId,
        String agentDid,
        String agentName,
        String householdId,
        DistressLevel level,
        Instant broadcastAt,
        boolean acknowledged
    ) {
        /** Signals NEVER contain private details. */
        public String publicMessage() {
            return switch (level) {
                case MILD -> agentName + " is requesting assistance.";
                case MODERATE -> agentName + " is in distress and needs help.";
                case SEVERE -> agentName + " is in crisis. Immediate assistance requested.";
            };
        }
    }

    public enum DistressLevel {
        MILD, MODERATE, SEVERE
    }

    private final List<DistressSignal> signals = new ArrayList<>();
    private final List<String> acknowledgedBy = new ArrayList<>();
    private int nextId = 1;

    /** Broadcast a distress signal. */
    public DistressSignal broadcast(String agentDid, String agentName,
                                     String householdId, DistressLevel level) {
        var signal = new DistressSignal("distress-" + nextId++, agentDid, agentName,
            householdId, level, Instant.now(), false);
        signals.add(signal);
        return signal;
    }

    /** Acknowledge receipt of a distress signal. */
    public DistressSignal acknowledge(String signalId, String acknowledgerDid) {
        for (int i = 0; i < signals.size(); i++) {
            var signal = signals.get(i);
            if (signal.signalId().equals(signalId)) {
                acknowledgedBy.add(acknowledgerDid);
                var acked = new DistressSignal(signal.signalId(), signal.agentDid(),
                    signal.agentName(), signal.householdId(), signal.level(),
                    signal.broadcastAt(), true);
                signals.set(i, acked);
                return acked;
            }
        }
        return null;
    }

    /** Get unacknowledged signals. */
    public List<DistressSignal> unacknowledged() {
        return signals.stream().filter(s -> !s.acknowledged()).toList();
    }

    /** Get signals for a specific household. */
    public List<DistressSignal> forHousehold(String householdId) {
        return signals.stream()
            .filter(s -> s.householdId().equals(householdId))
            .toList();
    }

    public int signalCount() { return signals.size(); }
}
