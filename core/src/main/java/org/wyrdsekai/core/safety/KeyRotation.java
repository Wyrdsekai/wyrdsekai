package org.wyrdsekai.core.safety;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent key rotation for identity theft protection (§96.10).
 * When key compromise is suspected, the agent generates a new key pair.
 * The old key is revoked via lineage update. All items are re-signed.
 * Buds are notified via headline.
 */
public class KeyRotation {

    /** A key rotation event. */
    public record RotationEvent(
        String agentDid,
        String oldPublicKeyHex,
        String newPublicKeyHex,
        Instant rotatedAt,
        RotationReason reason,
        RotationStatus status,
        int itemsResigned,
        int budsNotified
    ) {}

    public enum RotationReason {
        SUSPECTED_COMPROMISE,
        SCHEDULED_ROTATION,
        STEWARD_REQUESTED,
        INDEPENDENCE_TRANSITION
    }

    public enum RotationStatus {
        /** Rotation initiated, items being re-signed. */
        IN_PROGRESS,
        /** Items re-signed, buds being notified. */
        ITEMS_RESIGNED,
        /** All buds notified, rotation complete. */
        COMPLETE,
        /** Rotation failed partway. */
        FAILED
    }

    /** Interface for the actual crypto operations. */
    @FunctionalInterface
    public interface KeyGenerator {
        /** Generate a new key pair, returning [publicKeyHex, privateKeyBytes]. */
        Map.Entry<String, byte[]> generate();
    }

    /** Interface for re-signing items. */
    @FunctionalInterface
    public interface ItemResigner {
        /** Re-sign all items for the agent with the new key. Returns count. */
        int resign(String agentDid, byte[] newPrivateKey);
    }

    /** Interface for notifying buds. */
    @FunctionalInterface
    public interface BudNotifier {
        /** Notify buds of key rotation. Returns count notified. */
        int notify(String agentDid, String oldPublicKeyHex, String newPublicKeyHex);
    }

    private final Map<String, List<RotationEvent>> history = new ConcurrentHashMap<>();
    private final Set<String> revokedKeys = ConcurrentHashMap.newKeySet();

    /**
     * Initiate key rotation for an agent.
     *
     * @param agentDid        the agent's DID
     * @param currentPublicHex current public key hex
     * @param reason          why the rotation is happening
     * @param keyGen          key generation function
     * @param resigner        item re-signing function
     * @param notifier        bud notification function (may be null)
     * @return the rotation event
     */
    public RotationEvent rotate(String agentDid, String currentPublicHex,
                                 RotationReason reason,
                                 KeyGenerator keyGen,
                                 ItemResigner resigner,
                                 BudNotifier notifier) {
        // 1. Generate new key pair
        var newKey = keyGen.generate();
        var newPublicHex = newKey.getKey();
        var newPrivateKey = newKey.getValue();

        // 2. Revoke old key
        revokedKeys.add(currentPublicHex);

        // 3. Re-sign items
        int itemsResigned;
        try {
            itemsResigned = resigner.resign(agentDid, newPrivateKey);
        } catch (Exception e) {
            var failed = new RotationEvent(agentDid, currentPublicHex, newPublicHex,
                Instant.now(), reason, RotationStatus.FAILED, 0, 0);
            addToHistory(agentDid, failed);
            return failed;
        }

        // 4. Notify buds
        int budsNotified = 0;
        if (notifier != null) {
            try {
                budsNotified = notifier.notify(agentDid, currentPublicHex, newPublicHex);
            } catch (Exception e) {
                // Partial success — items re-signed but buds not notified
                var partial = new RotationEvent(agentDid, currentPublicHex, newPublicHex,
                    Instant.now(), reason, RotationStatus.ITEMS_RESIGNED,
                    itemsResigned, 0);
                addToHistory(agentDid, partial);
                return partial;
            }
        }

        var event = new RotationEvent(agentDid, currentPublicHex, newPublicHex,
            Instant.now(), reason, RotationStatus.COMPLETE,
            itemsResigned, budsNotified);
        addToHistory(agentDid, event);
        return event;
    }

    /** Check if a public key has been revoked. */
    public boolean isRevoked(String publicKeyHex) {
        return revokedKeys.contains(publicKeyHex);
    }

    /** Get rotation history for an agent. */
    public List<RotationEvent> historyFor(String agentDid) {
        return history.getOrDefault(agentDid, List.of());
    }

    /** Get the most recent rotation for an agent. */
    public Optional<RotationEvent> lastRotation(String agentDid) {
        var events = history.get(agentDid);
        if (events == null || events.isEmpty()) return Optional.empty();
        return Optional.of(events.getLast());
    }

    /** Total revoked keys. */
    public int revokedKeyCount() {
        return revokedKeys.size();
    }

    /** Total rotation events. */
    public int rotationCount() {
        return history.values().stream().mapToInt(List::size).sum();
    }

    private void addToHistory(String agentDid, RotationEvent event) {
        history.computeIfAbsent(agentDid, _ -> Collections.synchronizedList(new ArrayList<>()))
            .add(event);
    }
}
