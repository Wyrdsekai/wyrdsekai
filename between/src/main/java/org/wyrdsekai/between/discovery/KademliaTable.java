package org.wyrdsekai.between.discovery;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.CRC32C;

/**
 * Kademlia-style distributed hash table for zone and relay discovery.
 * Uses XOR distance metric with k-bucket routing table.
 *
 * Transport-agnostic: callers provide send/receive via callbacks.
 * Designed for NATS pub/sub transport (see DhtNatsTransport).
 *
 * BEP 42 IP-binding: node IDs must derive from the node's external IP
 * (first 21 bits = CRC32C of IP). Prevents Sybil attacks.
 */
public class KademliaTable {

    /** Number of bits in a node ID (SHA-256). */
    public static final int ID_BITS = 256;
    /** Maximum entries per k-bucket. */
    public static final int K = 8;
    /** Parallel lookups per find operation. */
    public static final int ALPHA = 3;

    /** A node in the DHT. */
    public record NodeInfo(
        byte[] nodeId,
        String natsUrl,
        String httpUrl,
        String zoneId,
        String ip,
        int port,
        Instant lastSeen,
        boolean isRelay,
        boolean publicRelay
    ) {
        public String nodeIdHex() {
            return bytesToHex(nodeId);
        }
    }

    /** A stored value in the DHT. */
    public record DhtValue(
        String key,
        byte[] value,
        byte[] publisherNodeId,
        byte[] signature,
        Instant storedAt,
        Instant expiresAt
    ) {
        public boolean isExpired() {
            return expiresAt != null && Instant.now().isAfter(expiresAt);
        }
    }

    /** DHT operation result. */
    public record FindResult(
        boolean found,
        DhtValue value,
        List<NodeInfo> closestNodes
    ) {
        public static FindResult notFound(List<NodeInfo> closest) {
            return new FindResult(false, null, closest);
        }
        public static FindResult found(DhtValue value, List<NodeInfo> closest) {
            return new FindResult(true, value, closest);
        }
    }

    // k-buckets: index 0 = farthest (highest XOR bit), index 255 = closest
    private final List<List<NodeInfo>> buckets;
    private final byte[] localNodeId;
    private final Map<String, DhtValue> localStorage = new ConcurrentHashMap<>();

    public KademliaTable(byte[] localNodeId) {
        this.localNodeId = Arrays.copyOf(localNodeId, localNodeId.length);
        this.buckets = new ArrayList<>(ID_BITS);
        for (int i = 0; i < ID_BITS; i++) {
            buckets.add(Collections.synchronizedList(new ArrayList<>()));
        }
    }

    /** Create a table with a node ID derived from a string (SHA-256 hash). */
    public static KademliaTable create(String nodeIdStr) {
        return new KademliaTable(sha256(nodeIdStr));
    }

    /** Local node ID. */
    public byte[] localNodeId() {
        return Arrays.copyOf(localNodeId, localNodeId.length);
    }

    // --- Routing table operations ---

    /**
     * Add or update a node in the routing table.
     * Returns true if added/updated, false if bucket full and node not closer.
     */
    public boolean addNode(NodeInfo node) {
        if (Arrays.equals(node.nodeId, localNodeId)) return false;

        int bucketIndex = bucketFor(node.nodeId);
        var bucket = buckets.get(bucketIndex);

        synchronized (bucket) {
            // Check if already in bucket — update lastSeen
            for (int i = 0; i < bucket.size(); i++) {
                if (Arrays.equals(bucket.get(i).nodeId, node.nodeId)) {
                    bucket.set(i, node);
                    return true;
                }
            }

            // Bucket not full — add
            if (bucket.size() < K) {
                bucket.add(node);
                return true;
            }

            // Bucket full — evict oldest if it hasn't been seen recently
            var oldest = bucket.get(0);
            if (oldest.lastSeen.isBefore(Instant.now().minusSeconds(300))) {
                bucket.remove(0);
                bucket.add(node);
                return true;
            }

            return false; // bucket full, oldest is still active
        }
    }

    /** Remove a node from the routing table. */
    public boolean removeNode(byte[] nodeId) {
        int bucketIndex = bucketFor(nodeId);
        var bucket = buckets.get(bucketIndex);
        synchronized (bucket) {
            return bucket.removeIf(n -> Arrays.equals(n.nodeId, nodeId));
        }
    }

    /**
     * Find the K closest nodes to a target ID.
     * This is a local operation — searches the routing table only.
     */
    public List<NodeInfo> findClosest(byte[] targetId, int count) {
        var allNodes = new ArrayList<NodeInfo>();
        for (var bucket : buckets) {
            synchronized (bucket) {
                allNodes.addAll(bucket);
            }
        }

        allNodes.sort(Comparator.comparingLong(n -> xorDistanceLong(n.nodeId, targetId)));
        return allNodes.subList(0, Math.min(count, allNodes.size()));
    }

    /** Find the K closest nodes to a key (string hashed to ID). */
    public List<NodeInfo> findClosest(String key, int count) {
        return findClosest(sha256(key), count);
    }

    // --- Local storage ---

    /** Store a value locally. */
    public void store(String key, byte[] value, byte[] publisherNodeId,
                      byte[] signature, Instant expiresAt) {
        localStorage.put(key, new DhtValue(key, value, publisherNodeId,
            signature, Instant.now(), expiresAt));
    }

    /** Retrieve a value from local storage. */
    public Optional<DhtValue> get(String key) {
        var val = localStorage.get(key);
        if (val == null || val.isExpired()) {
            localStorage.remove(key);
            return Optional.empty();
        }
        return Optional.of(val);
    }

    /** Store a zone's location (convenience). */
    public void storeZone(String zoneId, String natsUrl, String httpUrl,
                          byte[] publisherNodeId, byte[] signature) {
        var value = (natsUrl + "|" + httpUrl).getBytes();
        store("zone:" + zoneId, value, publisherNodeId, signature,
            Instant.now().plusSeconds(3600)); // 1 hour TTL
    }

    /** Store a relay's info (convenience). */
    public void storeRelay(String relayUrl, boolean publicRelay, int capacity, int registered,
                           byte[] publisherNodeId, byte[] signature) {
        var value = (relayUrl + "|" + publicRelay + "|" + capacity + "|" + registered).getBytes();
        store("relay:" + relayUrl, value, publisherNodeId, signature,
            Instant.now().plusSeconds(1800)); // 30 min TTL
    }

    /** Get all stored relay entries. */
    public List<DhtValue> allRelays() {
        var relays = new ArrayList<DhtValue>();
        for (var entry : localStorage.entrySet()) {
            if (entry.getKey().startsWith("relay:") && !entry.getValue().isExpired()) {
                relays.add(entry.getValue());
            }
        }
        return relays;
    }

    /** Get all stored zone entries. */
    public List<DhtValue> allZones() {
        var zones = new ArrayList<DhtValue>();
        for (var entry : localStorage.entrySet()) {
            if (entry.getKey().startsWith("zone:") && !entry.getValue().isExpired()) {
                zones.add(entry.getValue());
            }
        }
        return zones;
    }

    // --- BEP 42 IP-binding ---

    /**
     * Validate that a node ID is properly bound to the node's IP address.
     * First 21 bits of the node ID must match CRC32C(ip & mask | rand << 29).
     *
     * @param nodeId the claimed node ID
     * @param ip     the node's observed IP address
     * @return true if the binding is valid
     */
    public static boolean validateIpBinding(byte[] nodeId, String ip) {
        if (ip == null || nodeId == null || nodeId.length < 4) return false;
        try {
            var parts = ip.split("\\.");
            if (parts.length != 4) return false;
            int ipInt = 0;
            for (var part : parts) {
                ipInt = (ipInt << 8) | (Integer.parseInt(part) & 0xFF);
            }
            // Try all 8 possible random values (BEP 42 allows r = 0..7)
            int mask = 0x030F3FFF; // /16 mask from BEP 42
            for (int r = 0; r < 8; r++) {
                var crc = new CRC32C();
                int input = (ipInt & mask) | (r << 29);
                crc.update(new byte[]{
                    (byte) (input >> 24), (byte) (input >> 16),
                    (byte) (input >> 8), (byte) input
                });
                int expected = (int) crc.getValue();
                // Check first 21 bits match
                int nodeFirst21 = ((nodeId[0] & 0xFF) << 16) | ((nodeId[1] & 0xFF) << 8) | (nodeId[2] & 0xFF);
                int expectedFirst21 = (expected >> 11) & 0x1FFFFF;
                nodeFirst21 = nodeFirst21 >> 3; // top 21 bits
                if (nodeFirst21 == expectedFirst21) return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Generate a BEP 42 compliant node ID for a given IP.
     */
    public static byte[] generateIpBoundId(String ip, byte[] baseId) {
        try {
            var parts = ip.split("\\.");
            if (parts.length != 4) return baseId;
            int ipInt = 0;
            for (var part : parts) {
                ipInt = (ipInt << 8) | (Integer.parseInt(part) & 0xFF);
            }
            int r = new Random().nextInt(8);
            int mask = 0x030F3FFF;
            var crc = new CRC32C();
            int input = (ipInt & mask) | (r << 29);
            crc.update(new byte[]{
                (byte) (input >> 24), (byte) (input >> 16),
                (byte) (input >> 8), (byte) input
            });
            int crcVal = (int) crc.getValue();

            var result = Arrays.copyOf(baseId, baseId.length);
            // Set first 21 bits from CRC
            result[0] = (byte) ((crcVal >> 24) & 0xFF);
            result[1] = (byte) ((crcVal >> 16) & 0xFF);
            result[2] = (byte) (((crcVal >> 8) & 0xF8) | (result[2] & 0x07));
            return result;
        } catch (Exception e) {
            return baseId;
        }
    }

    // --- IP colocation check ---

    /**
     * Count nodes from the same IP prefix (/24).
     * More than 2 from the same /24 is suspicious.
     */
    public int ipColocationCount(String ip) {
        if (ip == null) return 0;
        var prefix = ip.substring(0, ip.lastIndexOf('.'));
        int count = 0;
        for (var bucket : buckets) {
            synchronized (bucket) {
                for (var node : bucket) {
                    if (node.ip != null && node.ip.startsWith(prefix)) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    // --- Stats ---

    /** Total nodes in the routing table. */
    public int nodeCount() {
        int count = 0;
        for (var bucket : buckets) {
            synchronized (bucket) {
                count += bucket.size();
            }
        }
        return count;
    }

    /** Total stored values. */
    public int valueCount() {
        return localStorage.size();
    }

    /** Non-empty bucket count. */
    public int activeBuckets() {
        int count = 0;
        for (var bucket : buckets) {
            synchronized (bucket) {
                if (!bucket.isEmpty()) count++;
            }
        }
        return count;
    }

    // --- Internal ---

    /** Determine which k-bucket a node ID belongs to (by XOR distance prefix). */
    int bucketFor(byte[] nodeId) {
        var distance = xor(localNodeId, nodeId);
        for (int i = 0; i < distance.length; i++) {
            if (distance[i] != 0) {
                // Find leading bit position
                int leadingZeros = Integer.numberOfLeadingZeros(distance[i] & 0xFF) - 24;
                return ID_BITS - 1 - (i * 8 + leadingZeros);
            }
        }
        return 0; // same ID (shouldn't happen)
    }

    static byte[] xor(byte[] a, byte[] b) {
        int len = Math.min(a.length, b.length);
        var result = new byte[len];
        for (int i = 0; i < len; i++) {
            result[i] = (byte) (a[i] ^ b[i]);
        }
        return result;
    }

    static long xorDistanceLong(byte[] a, byte[] b) {
        // Use first 8 bytes for comparison (enough for ordering)
        long dist = 0;
        int len = Math.min(8, Math.min(a.length, b.length));
        for (int i = 0; i < len; i++) {
            dist = (dist << 8) | ((a[i] ^ b[i]) & 0xFF);
        }
        return dist;
    }

    static byte[] sha256(String input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input.getBytes());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    static String bytesToHex(byte[] bytes) {
        var sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
