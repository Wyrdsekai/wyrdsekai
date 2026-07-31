package org.wyrdsekai.between.discovery;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Web of Trust for relay selection.
 *
 * Trust propagates through the bond graph:
 * - Direct bond (1 hop): trust = 1.0
 * - Bond-of-bond (2 hops): trust = 0.6
 * - 3 hops: trust = 0.3
 * - 4+ hops: trust = 0.0 (unknown)
 *
 * When selecting a relay, trust score weights the choice:
 * higher trust = preferred relay. Untrusted relays still work
 * but the user is warned.
 *
 * Each household can sign a TrustAttestation vouching for a relay.
 * Attestations propagate through the bond graph.
 */
public class RelayTrustGraph {

    /** Trust decay per hop in the bond graph. Index = hop count. */
    private static final double[] HOP_TRUST = {1.0, 1.0, 0.6, 0.3};
    private static final int MAX_HOPS = 3;

    /** A trust attestation — a household vouches for a relay. */
    public record TrustAttestation(
        @JsonProperty("relayUrl") String relayUrl,
        @JsonProperty("attestorZoneId") String attestorZoneId,
        @JsonProperty("attestorPublicKey") String attestorPublicKey,
        @JsonProperty("signature") byte[] signature,
        @JsonProperty("attestedAt") Instant attestedAt,
        @JsonProperty("trustLevel") double trustLevel,
        @JsonProperty("comment") String comment
    ) {
        @JsonCreator
        public TrustAttestation {}

        /** Create an unsigned attestation. */
        public static TrustAttestation create(String relayUrl, String zoneId,
                                               String publicKey, double trustLevel, String comment) {
            return new TrustAttestation(relayUrl, zoneId, publicKey, null,
                Instant.now(), trustLevel, comment);
        }

        /** Attach a signature. */
        public TrustAttestation signed(byte[] sig) {
            return new TrustAttestation(relayUrl, attestorZoneId, attestorPublicKey,
                sig, attestedAt, trustLevel, comment);
        }
    }

    /** A scored relay — URL + computed trust score from the bond graph. */
    public record ScoredRelay(
        String relayUrl,
        double trustScore,
        int attestationCount,
        int shortestHopDistance,
        List<String> attestorZones
    ) implements Comparable<ScoredRelay> {
        @Override
        public int compareTo(ScoredRelay other) {
            return Double.compare(other.trustScore, this.trustScore); // higher first
        }
    }

    // Bond graph: zoneId → set of bonded zoneIds
    private final Map<String, Set<String>> bonds = new ConcurrentHashMap<>();
    // Attestations: relayUrl → list of attestations
    private final Map<String, List<TrustAttestation>> attestations = new ConcurrentHashMap<>();
    // Local zone ID
    private final String localZoneId;

    public RelayTrustGraph(String localZoneId) {
        this.localZoneId = localZoneId;
    }

    // --- Bond management ---

    /** Register a bond between two zones. */
    public void addBond(String zoneA, String zoneB) {
        bonds.computeIfAbsent(zoneA, k -> ConcurrentHashMap.newKeySet()).add(zoneB);
        bonds.computeIfAbsent(zoneB, k -> ConcurrentHashMap.newKeySet()).add(zoneA);
    }

    /** Remove a bond. */
    public void removeBond(String zoneA, String zoneB) {
        var setA = bonds.get(zoneA);
        if (setA != null) setA.remove(zoneB);
        var setB = bonds.get(zoneB);
        if (setB != null) setB.remove(zoneA);
    }

    /** Get all bonded zones for a zone. */
    public Set<String> bondsFor(String zoneId) {
        return bonds.getOrDefault(zoneId, Set.of());
    }

    // --- Attestation management ---

    /** Add a trust attestation for a relay. */
    public void addAttestation(TrustAttestation attestation) {
        attestations.computeIfAbsent(attestation.relayUrl,
            k -> Collections.synchronizedList(new ArrayList<>()))
            .add(attestation);
    }

    /** Get all attestations for a relay. */
    public List<TrustAttestation> attestationsFor(String relayUrl) {
        return attestations.getOrDefault(relayUrl, List.of());
    }

    // --- Trust computation ---

    /**
     * Compute the shortest hop distance from the local zone to a target zone
     * through the bond graph. Uses BFS.
     *
     * @return hop count, or -1 if unreachable
     */
    public int hopDistance(String targetZoneId) {
        if (localZoneId.equals(targetZoneId)) return 0;

        var visited = new HashSet<String>();
        var queue = new ArrayDeque<String>();
        var depths = new HashMap<String, Integer>();

        queue.add(localZoneId);
        visited.add(localZoneId);
        depths.put(localZoneId, 0);

        while (!queue.isEmpty()) {
            var current = queue.poll();
            int currentDepth = depths.get(current);

            if (currentDepth >= MAX_HOPS) continue;

            for (var neighbor : bondsFor(current)) {
                if (visited.contains(neighbor)) continue;
                visited.add(neighbor);

                int neighborDepth = currentDepth + 1;
                depths.put(neighbor, neighborDepth);

                if (neighbor.equals(targetZoneId)) {
                    return neighborDepth;
                }
                queue.add(neighbor);
            }
        }
        return -1; // unreachable
    }

    /**
     * Compute trust score for a zone based on hop distance.
     */
    public double trustForZone(String zoneId) {
        int hops = hopDistance(zoneId);
        if (hops < 0 || hops >= HOP_TRUST.length) return 0.0;
        return HOP_TRUST[hops];
    }

    /**
     * Compute a trust score for a relay based on its attestations
     * and the trust graph distance to each attestor.
     *
     * Score = max(attestorTrust × attestation.trustLevel) across all attestors.
     * A relay trusted by a direct bond gets ~1.0.
     * A relay trusted only by a 3-hop neighbor gets ~0.3.
     * A relay with no attestations from known zones gets 0.0.
     */
    public double trustForRelay(String relayUrl) {
        var relayAttestations = attestationsFor(relayUrl);
        if (relayAttestations.isEmpty()) return 0.0;

        double maxTrust = 0.0;
        for (var att : relayAttestations) {
            double zoneTrust = trustForZone(att.attestorZoneId);
            double combined = zoneTrust * att.trustLevel;
            maxTrust = Math.max(maxTrust, combined);
        }
        return maxTrust;
    }

    /**
     * Score all known relays and return sorted by trust (highest first).
     */
    public List<ScoredRelay> scoreRelays() {
        var scored = new ArrayList<ScoredRelay>();

        for (var entry : attestations.entrySet()) {
            var relayUrl = entry.getKey();
            var atts = entry.getValue();

            double bestTrust = 0.0;
            int shortestHop = Integer.MAX_VALUE;
            var attestorZones = new ArrayList<String>();

            for (var att : atts) {
                double zoneTrust = trustForZone(att.attestorZoneId);
                double combined = zoneTrust * att.trustLevel;
                bestTrust = Math.max(bestTrust, combined);

                int hops = hopDistance(att.attestorZoneId);
                if (hops >= 0) shortestHop = Math.min(shortestHop, hops);

                attestorZones.add(att.attestorZoneId);
            }

            if (shortestHop == Integer.MAX_VALUE) shortestHop = -1;

            scored.add(new ScoredRelay(relayUrl, bestTrust, atts.size(),
                shortestHop, attestorZones));
        }

        Collections.sort(scored);
        return scored;
    }

    /**
     * Select the best relay — highest trust score, with fallback to any available.
     * Returns empty if no relays are known.
     */
    public Optional<ScoredRelay> selectBestRelay() {
        var scored = scoreRelays();
        return scored.isEmpty() ? Optional.empty() : Optional.of(scored.get(0));
    }

    /**
     * Select the best relay with a minimum trust threshold.
     * Returns empty if no relay meets the threshold.
     */
    public Optional<ScoredRelay> selectRelay(double minTrust) {
        return scoreRelays().stream()
            .filter(r -> r.trustScore >= minTrust)
            .findFirst();
    }

    // --- Stats ---

    public int bondCount() {
        int count = 0;
        for (var entry : bonds.entrySet()) {
            count += entry.getValue().size();
        }
        return count / 2; // each bond counted twice
    }

    public int attestationCount() {
        return attestations.values().stream().mapToInt(List::size).sum();
    }

    public int knownRelayCount() {
        return attestations.size();
    }

    public String localZoneId() {
        return localZoneId;
    }
}
