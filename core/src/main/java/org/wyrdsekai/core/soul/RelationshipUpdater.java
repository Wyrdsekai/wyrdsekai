package org.wyrdsekai.core.soul;

import org.wyrdsekai.common.event.WorldEvent;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Updates agent relationships based on interactions during a wake cycle.
 *
 * Called during the Forge sleep cycle (SoulMaintenanceCycle step 5).
 * Reviews all Said events since last sleep and adjusts trust, rapport,
 * and bond depth for each entity the agent interacted with.
 *
 * New entities encountered → auto-created as acquaintances.
 * Existing relationships → trust/rapport drift based on interaction quality.
 * Bond depth promotions → based on interaction count and emotional significance.
 *
 * Genome-driven: the agent's topic affinities and sensitivities affect how
 * interactions are scored. Kai trusts entities who talk about architecture.
 * Sora trusts entities who are consistent. Ma trusts entities who are genuine.
 */
public final class RelationshipUpdater {

    private RelationshipUpdater() {}

    /**
     * Update relationships from recent interactions.
     *
     * @param existing       current relationships from manifest
     * @param recentSaid     Said events since last sleep
     * @param recentCharges  emotional charges for those events (parallel list, may be shorter)
     * @param genome         agent's genome (for topic affinity scoring)
     * @param selfDid        agent's own DID (to exclude self-speech)
     * @return updated relationship list (existing + new acquaintances)
     */
    public static List<Relationship> update(
            List<Relationship> existing,
            List<WorldEvent.Said> recentSaid,
            List<EmotionalCharge> recentCharges,
            GenomeProfile genome,
            String selfDid) {

        var social = SocialRecord.forDid(selfDid);

        // Index existing relationships by entity name (case-insensitive). She is not one of her own
        // relationships: an entry the list made from her own lines (SocialRecord) is not carried on.
        var byName = new LinkedHashMap<String, Relationship>();
        if (existing != null) {
            for (var rel : existing) {
                if (social.isOwnPlaceholder(rel.entityDid())) continue;
                byName.put(rel.entityName().toLowerCase(), rel);
            }
        }

        if (recentSaid == null || recentSaid.isEmpty()) {
            return List.copyOf(withBonds(byName, social).values());
        }

        // Group interactions by speaker
        var interactionsByEntity = recentSaid.stream()
            .filter(s -> !SocialRecord.isSelf(selfDid, s.entityId()))
            .filter(s -> !"narrator".equals(s.entityId()))
            .filter(s -> !"system".equals(s.entityId()))
            .collect(Collectors.groupingBy(WorldEvent.Said::entityName));

        // Build charge index (entityId → charges)
        var chargeByEntityId = new HashMap<String, List<EmotionalCharge>>();
        for (int i = 0; i < Math.min(recentSaid.size(), recentCharges != null ? recentCharges.size() : 0); i++) {
            var said = recentSaid.get(i);
            var charge = recentCharges.get(i);
            chargeByEntityId.computeIfAbsent(said.entityId(), _ -> new ArrayList<>()).add(charge);
        }

        // Update or create relationships
        for (var entry : interactionsByEntity.entrySet()) {
            var entityName = entry.getKey();
            var interactions = entry.getValue();
            var entityId = interactions.getFirst().entityId();
            var key = entityName.toLowerCase();

            var rel = byName.get(key);
            String effectiveKey = key; // key used for byName storage
            if (rel == null) {
                // Fuzzy match: "Dr. Smith" might match existing "John Smith"
                var fuzzyResult = fuzzyLookup(byName, entityName);
                if (fuzzyResult != null) {
                    rel = fuzzyResult;
                    // Use the existing relationship's key to avoid duplicate entries
                    effectiveKey = rel.entityName().toLowerCase();
                }
            }
            if (rel == null) {
                // Genuinely new acquaintance
                rel = Relationship.acquaintance("did:key:" + entityId + "-placeholder", entityName);
            }

            // Score interactions
            int count = interactions.size();
            var charges = chargeByEntityId.getOrDefault(entityId, List.of());

            float trustDelta = 0;
            float rapportDelta = 0;

            // Base rapport from interaction count
            rapportDelta += Math.min(count * 0.02f, 0.1f); // cap at +0.1 per cycle

            // Emotional charge scoring
            for (var charge : charges) {
                if (charge == null) continue;

                // Genuine positive interactions build trust
                if ("genuine".equals(charge.contextType()) && charge.intensity() > 0.3) {
                    trustDelta += 0.01f;
                    rapportDelta += 0.01f;
                }

                // Manipulative interactions erode trust faster than they build
                if ("manipulative".equals(charge.contextType())) {
                    trustDelta -= 0.03f;
                }

                // High-intensity genuine interactions build deeper bonds
                if ("genuine".equals(charge.contextType()) && charge.intensity() > 0.6) {
                    trustDelta += 0.02f;
                }
            }

            // Clamp deltas
            float newTrust = Math.max(0.0f, Math.min(1.0f, rel.trust() + trustDelta));
            float newRapport = Math.max(0.0f, Math.min(1.0f, rel.rapport() + rapportDelta));

            // Bond depth promotion
            int newBondDepth = rel.bondDepth();
            int totalInteractions = rel.interactionCount() + count;

            // 0 → 1: 10+ positive interactions
            if (newBondDepth == 0 && totalInteractions >= 10 && newTrust > 0.5f) {
                newBondDepth = 1;
            }
            // 1 → 2: 50+ interactions, high trust
            if (newBondDepth == 1 && totalInteractions >= 50 && newTrust > 0.7f) {
                newBondDepth = 2;
            }

            // Create updated relationship
            rel = new Relationship(
                rel.entityDid(), rel.entityName(),
                newTrust, newRapport, newBondDepth,
                totalInteractions, Instant.now(),
                rel.summaryIsAutomatic()
                    ? Relationship.describe(newBondDepth, totalInteractions)
                    : rel.summary()
            );

            byName.put(effectiveKey, rel);
        }

        return List.copyOf(withBonds(byName, social).values());
    }

    /**
     * The people and companions she is bonded with are in this list as their bond holds them: the
     * real DID where the list had a placeholder, the bond's depth where the list's own counting sat
     * lower, the bond's count of exchanges where it is the larger (the list counts lines spoken in
     * rooms only), and words that say what they are to her.
     */
    private static LinkedHashMap<String, Relationship> withBonds(LinkedHashMap<String, Relationship> byName,
                                                                  SocialRecord.Of social) {
        if (social.bonded().isEmpty()) return byName;
        for (var e : byName.entrySet()) {
            var rel = e.getValue();
            var bond = social.bondedByName(rel.entityName());
            if (bond == null) continue;
            int depth = Math.max(rel.bondDepth(), bond.depthLevel());
            int count = Math.max(rel.interactionCount(), bond.interactionCount());
            var did = rel.entityDid() != null && rel.entityDid().endsWith("-placeholder") && bond.did() != null
                ? bond.did() : rel.entityDid();
            e.setValue(new Relationship(did, rel.entityName(), rel.trust(), rel.rapport(), depth, count,
                rel.lastInteraction(),
                rel.summaryIsAutomatic() ? SocialRecord.describe(bond, count) : rel.summary()));
        }
        return byName;
    }

    /**
     * Fuzzy entity name lookup using Jaro-Winkler similarity.
     * Returns the best match above 0.85 threshold, or null.
     */
    private static Relationship fuzzyLookup(Map<String, Relationship> byName, String entityName) {
        if (byName.isEmpty()) return null;
        String target = entityName.toLowerCase();
        Relationship best = null;
        float bestScore = 0.85f; // threshold

        for (var entry : byName.entrySet()) {
            float score = AdmissionController.jaroWinklerSimilarity(target, entry.getKey());
            if (score > bestScore) {
                bestScore = score;
                best = entry.getValue();
            }
        }
        return best;
    }
}
