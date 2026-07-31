package org.wyrdsekai.between.layer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bridge between the core FamilyLocker (in-memory, per-node) and
 * the Between network for distributed replication.
 *
 * Soul items are content-addressed (SHA-256), so deduplication is
 * trivial: if we already have the hash, skip the item.
 *
 * NATS subjects:
 *   wyrd.locker.{familyId}.items      — new soul items
 *   wyrd.locker.{familyId}.tombstones — item deletions
 *
 * This bridge does NOT depend on NATS directly. It uses a
 * {@link MessageTransport} interface so it can be tested without
 * external infrastructure.
 */
public class BetweenLockerBridge {

    private static final Logger log = LoggerFactory.getLogger(BetweenLockerBridge.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    /**
     * Transport abstraction for publishing and subscribing to messages.
     * In production, this wraps NatsBridge. In tests, a simple in-memory
     * implementation suffices.
     */
    public interface MessageTransport {
        void publish(String subject, String json);
        void subscribe(String subject, MessageHandler handler);
    }

    /** Callback for received messages. */
    @FunctionalInterface
    public interface MessageHandler {
        void onMessage(String json);
    }

    /** Listener for incoming soul items. */
    @FunctionalInterface
    public interface ItemListener {
        void onItem(ItemMessage item);
    }

    /** Listener for incoming tombstones. */
    @FunctionalInterface
    public interface TombstoneListener {
        void onTombstone(TombstoneMessage tombstone);
    }

    /** Wire format for a soul item broadcast. */
    public record ItemMessage(
        String hash,
        String category,
        String label,
        String text,
        String creatorDid,
        double significance,
        long timestamp
    ) {}

    /** Wire format for a tombstone broadcast. */
    public record TombstoneMessage(
        String itemHash,
        String deletedBy,
        String reason,
        long timestamp
    ) {}

    private final MessageTransport transport;

    // Track hashes we already have, for content-addressed deduplication
    private final Set<String> knownItemHashes = ConcurrentHashMap.newKeySet();
    private final Set<String> knownTombstoneHashes = ConcurrentHashMap.newKeySet();

    public BetweenLockerBridge(MessageTransport transport) {
        this.transport = transport;
    }

    /**
     * Broadcast a new soul item to the family's locker channel.
     * Other nodes subscribed to this family will receive it.
     */
    public void publishItem(ItemMessage item, String familyId) {
        knownItemHashes.add(item.hash());
        String subject = itemSubject(familyId);
        try {
            String json = MAPPER.writeValueAsString(item);
            transport.publish(subject, json);
            log.debug("Published item {} to family {}", item.hash(), familyId);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize item {}: {}", item.hash(), e.getMessage());
        }
    }

    /**
     * Subscribe to soul items for a family. Items already known
     * locally are silently dropped (content-addressed dedup).
     */
    public void subscribeItems(String familyId, ItemListener listener) {
        String subject = itemSubject(familyId);
        transport.subscribe(subject, json -> {
            try {
                var item = MAPPER.readValue(json, ItemMessage.class);
                if (knownItemHashes.add(item.hash())) {
                    listener.onItem(item);
                } else {
                    log.debug("Skipped duplicate item {}", item.hash());
                }
            } catch (Exception e) {
                log.error("Failed to deserialize item: {}", e.getMessage());
            }
        });
        log.debug("Subscribed to items for family {}", familyId);
    }

    /**
     * Broadcast a tombstone (item deletion) to the family's locker channel.
     */
    public void publishTombstone(TombstoneMessage tombstone, String familyId) {
        knownTombstoneHashes.add(tombstone.itemHash());
        String subject = tombstoneSubject(familyId);
        try {
            String json = MAPPER.writeValueAsString(tombstone);
            transport.publish(subject, json);
            log.debug("Published tombstone for {} to family {}", tombstone.itemHash(), familyId);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize tombstone {}: {}", tombstone.itemHash(), e.getMessage());
        }
    }

    /**
     * Subscribe to tombstones for a family. Tombstones for items
     * already known to be deleted are silently dropped.
     */
    public void subscribeTombstones(String familyId, TombstoneListener listener) {
        String subject = tombstoneSubject(familyId);
        transport.subscribe(subject, json -> {
            try {
                var tombstone = MAPPER.readValue(json, TombstoneMessage.class);
                if (knownTombstoneHashes.add(tombstone.itemHash())) {
                    listener.onTombstone(tombstone);
                } else {
                    log.debug("Skipped duplicate tombstone for {}", tombstone.itemHash());
                }
            } catch (Exception e) {
                log.error("Failed to deserialize tombstone: {}", e.getMessage());
            }
        });
        log.debug("Subscribed to tombstones for family {}", familyId);
    }

    /**
     * Register a hash as already known (for items loaded from local storage
     * before the bridge was connected).
     */
    public void registerKnownHash(String hash) {
        knownItemHashes.add(hash);
    }

    /**
     * Register a tombstone hash as already known.
     */
    public void registerKnownTombstone(String itemHash) {
        knownTombstoneHashes.add(itemHash);
    }

    /** Number of known item hashes (for diagnostics). */
    public int knownItemCount() {
        return knownItemHashes.size();
    }

    /** Number of known tombstone hashes (for diagnostics). */
    public int knownTombstoneCount() {
        return knownTombstoneHashes.size();
    }

    private static String itemSubject(String familyId) {
        return "wyrd.locker." + familyId + ".items";
    }

    private static String tombstoneSubject(String familyId) {
        return "wyrd.locker." + familyId + ".tombstones";
    }
}
