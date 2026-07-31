package org.wyrdsekai.core.soul;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Cross-bud Forge merge logic (§95 Soul Budding).
 *
 * When buds sync during sleep, their Forge outputs need merging.
 * Buds share a Family Locker (distributed content-addressed item store
 * in the Between). Sync modes:
 *   - Headlines: continuous, ~200B — not handled here
 *   - Warm handoff: device switch, ~2s — not handled here
 *   - Sleep sync: full Forge consolidation — THIS CLASS
 *
 * Merge philosophy:
 *   - Convergence is incentivized, never forced
 *   - Divergence is a spectrum (0.0=fresh bud → 0.7+=speciated)
 *   - Highest significance wins for duplicates
 *   - Fingerprints merge with configurable local/remote weighting
 *   - Relationships keep most recent for same target
 *   - Fragments deduplicate by content hash
 */
public final class ForgeMerge {

    private ForgeMerge() {}

    /**
     * Result of a merge operation.
     *
     * @param memory        Merged memory graph
     * @param fingerprint   Merged behavioral fingerprint
     * @param relationships Merged relationship list
     * @param fragments     Merged soul fragments
     * @param conflicts     Number of conflicts resolved during merge
     */
    public record MergeResult(
        CompactedMemory memory,
        BehavioralFingerprint fingerprint,
        List<Relationship> relationships,
        List<SoulFragment> fragments,
        int conflicts
    ) {}

    // ─── Memory Merge ───────────────────────────────────────────

    /**
     * Merge two compacted memories: union of nodes, keep highest
     * significance for duplicates (matched by ID).
     *
     * @param local  This bud's memory
     * @param remote Other bud's memory
     * @return Merged memory with combined nodes, links, and topic weights
     */
    public static CompactedMemory mergeMemories(CompactedMemory local, CompactedMemory remote) {
        Objects.requireNonNull(local, "local memory must not be null");
        Objects.requireNonNull(remote, "remote memory must not be null");

        // Merge nodes by ID — keep highest importance
        var nodeMap = new LinkedHashMap<String, MemoryNode>();
        for (var node : local.nodes()) {
            nodeMap.put(node.id(), node);
        }
        for (var node : remote.nodes()) {
            nodeMap.merge(node.id(), node, ForgeMerge::keepHigherImportance);
        }

        // Merge links — union, deduplicate by source+target
        var linkMap = new LinkedHashMap<String, CompactedMemory.MemoryLink>();
        for (var link : local.links()) {
            linkMap.put(linkKey(link), link);
        }
        for (var link : remote.links()) {
            linkMap.merge(linkKey(link), link, ForgeMerge::keepStrongerLink);
        }

        // Merge topic weights — average
        var topicWeights = new LinkedHashMap<String, Float>();
        var allTopics = new HashSet<String>();
        allTopics.addAll(local.topicWeights().keySet());
        allTopics.addAll(remote.topicWeights().keySet());

        for (var topic : allTopics) {
            float localWeight = local.topicWeights().getOrDefault(topic, 0.0f);
            float remoteWeight = remote.topicWeights().getOrDefault(topic, 0.0f);
            if (local.topicWeights().containsKey(topic) && remote.topicWeights().containsKey(topic)) {
                topicWeights.put(topic, (localWeight + remoteWeight) / 2.0f);
            } else {
                topicWeights.put(topic, Math.max(localWeight, remoteWeight));
            }
        }

        return new CompactedMemory(
            List.copyOf(nodeMap.values()),
            List.copyOf(linkMap.values()),
            Map.copyOf(topicWeights)
        );
    }

    // ─── Fingerprint Merge ──────────────────────────────────────

    /**
     * Merge two behavioral fingerprints with weighting.
     * Uses BehavioralFingerprint.merge() with the localWeight as alpha
     * for the local side.
     *
     * @param local       This bud's fingerprint
     * @param remote      Other bud's fingerprint
     * @param localWeight Weight for local data (0.0-1.0). 0.5 = equal.
     * @return Merged fingerprint
     */
    public static BehavioralFingerprint mergeFingerprints(
            BehavioralFingerprint local,
            BehavioralFingerprint remote,
            float localWeight) {
        Objects.requireNonNull(local, "local fingerprint must not be null");
        Objects.requireNonNull(remote, "remote fingerprint must not be null");

        if (localWeight < 0.0f || localWeight > 1.0f) {
            throw new IllegalArgumentException("localWeight must be 0.0-1.0, got " + localWeight);
        }

        // BehavioralFingerprint.merge treats alpha as "weight for fresh data"
        // We want: result = local * localWeight + remote * (1-localWeight)
        // So we pass remote as "existing" and local as "fresh" with alpha=localWeight
        return BehavioralFingerprint.merge(remote, local, localWeight);
    }

    // ─── Relationship Merge ─────────────────────────────────────

    /**
     * Merge two relationship lists: union, keep most recent for same target.
     * Relationships are matched by entityDid.
     *
     * @param local  This bud's relationships
     * @param remote Other bud's relationships
     * @return Merged relationships
     */
    public static List<Relationship> mergeRelationships(
            List<Relationship> local, List<Relationship> remote) {
        Objects.requireNonNull(local, "local relationships must not be null");
        Objects.requireNonNull(remote, "remote relationships must not be null");

        var relMap = new LinkedHashMap<String, Relationship>();

        for (var rel : local) {
            relMap.put(rel.entityDid(), rel);
        }

        for (var rel : remote) {
            relMap.merge(rel.entityDid(), rel, ForgeMerge::keepMostRecent);
        }

        return List.copyOf(relMap.values());
    }

    // ─── Fragment Merge ─────────────────────────────────────────

    /**
     * Merge two fragment lists: deduplicate by content hash,
     * keep highest significance (formative > non-formative, then by text length).
     *
     * @param local  This bud's fragments
     * @param remote Other bud's fragments
     * @return Merged fragments
     */
    public static List<SoulFragment> mergeFragments(
            List<SoulFragment> local, List<SoulFragment> remote) {
        Objects.requireNonNull(local, "local fragments must not be null");
        Objects.requireNonNull(remote, "remote fragments must not be null");

        // Deduplicate by content hash (text content)
        var fragMap = new LinkedHashMap<String, SoulFragment>();

        for (var frag : local) {
            fragMap.put(contentHash(frag), frag);
        }

        for (var frag : remote) {
            var hash = contentHash(frag);
            fragMap.merge(hash, frag, ForgeMerge::keepHigherSignificanceFragment);
        }

        // Also deduplicate by ID (same fragment ID = same logical fragment)
        var byId = new LinkedHashMap<String, SoulFragment>();
        for (var frag : fragMap.values()) {
            byId.merge(frag.id(), frag, ForgeMerge::keepHigherSignificanceFragment);
        }

        return List.copyOf(byId.values());
    }

    // ─── Full Merge ─────────────────────────────────────────────

    /**
     * Perform a full merge of all soul layers between two buds.
     *
     * @param localMemory       This bud's memory
     * @param remoteMemory      Other bud's memory
     * @param localFingerprint  This bud's fingerprint
     * @param remoteFingerprint Other bud's fingerprint
     * @param localWeight       Weight for local data (0.0-1.0)
     * @param localRelationships  This bud's relationships
     * @param remoteRelationships Other bud's relationships
     * @param localFragments    This bud's fragments
     * @param remoteFragments   Other bud's fragments
     * @return Complete merge result with conflict count
     */
    public static MergeResult mergeAll(
            CompactedMemory localMemory, CompactedMemory remoteMemory,
            BehavioralFingerprint localFingerprint, BehavioralFingerprint remoteFingerprint,
            float localWeight,
            List<Relationship> localRelationships, List<Relationship> remoteRelationships,
            List<SoulFragment> localFragments, List<SoulFragment> remoteFragments) {

        var mergedMemory = mergeMemories(localMemory, remoteMemory);
        var mergedFingerprint = mergeFingerprints(localFingerprint, remoteFingerprint, localWeight);
        var mergedRelationships = mergeRelationships(localRelationships, remoteRelationships);
        var mergedFragments = mergeFragments(localFragments, remoteFragments);

        // Count conflicts: nodes/rels/fragments where both sides had different values
        int conflicts = 0;
        var localNodeIds = local(localMemory.nodes());
        var remoteNodeIds = local(remoteMemory.nodes());
        localNodeIds.retainAll(remoteNodeIds);
        conflicts += localNodeIds.size(); // overlapping node IDs

        var localRelIds = localRelationships.stream()
            .map(Relationship::entityDid).collect(Collectors.toSet());
        var remoteRelIds = remoteRelationships.stream()
            .map(Relationship::entityDid).collect(Collectors.toSet());
        localRelIds.retainAll(remoteRelIds);
        conflicts += localRelIds.size();

        return new MergeResult(mergedMemory, mergedFingerprint,
            mergedRelationships, mergedFragments, conflicts);
    }

    // ─── Internal Helpers ───────────────────────────────────────

    private static Set<String> local(List<MemoryNode> nodes) {
        return nodes.stream()
            .map(MemoryNode::id)
            .collect(Collectors.toCollection(HashSet::new));
    }

    private static MemoryNode keepHigherImportance(MemoryNode a, MemoryNode b) {
        // Formative always wins
        if (a.formative() && !b.formative()) return a;
        if (b.formative() && !a.formative()) return b;
        // Higher importance wins
        if (a.importance() >= b.importance()) return a;
        return b;
    }

    private static CompactedMemory.MemoryLink keepStrongerLink(
            CompactedMemory.MemoryLink a, CompactedMemory.MemoryLink b) {
        return a.strength() >= b.strength() ? a : b;
    }

    private static Relationship keepMostRecent(Relationship a, Relationship b) {
        if (a.lastInteraction() == null) return b;
        if (b.lastInteraction() == null) return a;
        return a.lastInteraction().isAfter(b.lastInteraction()) ? a : b;
    }

    private static SoulFragment keepHigherSignificanceFragment(SoulFragment a, SoulFragment b) {
        // Formative always wins
        if (a.formative() && !b.formative()) return a;
        if (b.formative() && !a.formative()) return b;
        // Prefer embedded over non-embedded
        if (a.isEmbedded() && !b.isEmbedded()) return a;
        if (b.isEmbedded() && !a.isEmbedded()) return b;
        // Prefer longer text (more detail)
        return a.text().length() >= b.text().length() ? a : b;
    }

    private static String linkKey(CompactedMemory.MemoryLink link) {
        return link.sourceId() + "|" + link.targetId();
    }

    /**
     * SHA-256 hash of fragment text content, hex-encoded.
     * Used for deduplication across buds.
     */
    static String contentHash(SoulFragment fragment) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(fragment.text().getBytes(StandardCharsets.UTF_8));
            var hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            // SHA-256 is always available
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
