package org.wyrdsekai.core.agent;

import org.wyrdsekai.common.event.WorldEvent;
import org.wyrdsekai.common.model.RoomSnapshot;
import org.wyrdsekai.core.soul.GenomeProfile;
import org.wyrdsekai.core.soul.BehavioralFingerprint;
import org.wyrdsekai.core.soul.Relationship;
import org.wyrdsekai.core.soul.SoulManifest;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Personality-driven decision model for agent engagement.
 *
 * Before triggering inference, every agent runs the engagement gate.
 * The gate evaluates whether this speech warrants a response based on
 * who the agent IS — their genome, vitality, relationships, topic
 * affinities, and conversational state.
 *
 * Results:
 *   ENGAGE  — trigger inference, respond
 *   OBSERVE — add to history, update vitality, stay silent
 *
 * Player speech always results in ENGAGE (the companion's job).
 * Agent speech is evaluated through the full scoring model.
 *
 * for the complete design.
 */
public class EngagementGate {

    /** Decision result. */
    public enum Decision { ENGAGE, OBSERVE }

    /** Default engagement threshold — agent speaks if score >= this. */
    private static final double DEFAULT_THRESHOLD = 0.5;

    /**
     * Evaluate whether this agent should respond to speech.
     *
     * @param said            the speech event
     * @param agentProfile    this agent's profile
     * @param manifest        this agent's soul manifest (nullable for unsouled agents)
     * @param vitality        current vitality state
     * @param snapshot        current room snapshot
     * @param tracker         conversation tracker for thread awareness
     * @param lastSpokeAt     when this agent last spoke
     * @return ENGAGE or OBSERVE
     */
    public static Decision evaluate(
            WorldEvent.Said said,
            AgentProfile agentProfile,
            SoulManifest manifest,
            VitalityState vitality,
            RoomSnapshot snapshot,
            ConversationTracker tracker,
            Instant lastSpokeAt) {

        // Player speech → always engage (the companion's job)
        if (!isAgentEntity(said.entityId(), snapshot)) {
            return Decision.ENGAGE;
        }

        // Agent speech → score-based evaluation
        double score = 0.0;

        // ── Am I being addressed? ────────────────────────────────────
        if (mentionsName(said.text(), agentProfile.name())) {
            score += 0.6;
        }
        if (isQuestion(said.text())) {
            score += 0.3;
        }

        // ── Topic relevance ──────────────────────────────────────────
        if (manifest != null && manifest.fingerprint() != null) {
            score += topicRelevanceScore(said.text(), manifest.fingerprint());
        }

        // ── Relationship ─────────────────────────────────────────────
        if (manifest != null && manifest.relationships() != null) {
            score += relationshipScore(said.entityId(), said.entityName(), manifest.relationships());
        }

        // ── Genome / personality baseline ────────────────────────────
        if (manifest != null && manifest.genome() != null) {
            var genome = manifest.genome();
            var baselines = genome.baselines();
            // Social agents (high rapport baseline) engage more readily
            score += (baselines.getOrDefault("rapport", 0.5) - 0.5) * 0.3;
            // Curious agents engage more with novel topics
            score += (baselines.getOrDefault("curiosity", 0.5) - 0.5) * 0.2;
        }

        // ── Vitality modulation ──────────────────────────────────────
        double energyRatio = vitality.energy() / 0.7; // normalized to baseline ~0.7
        score *= Math.min(1.0, energyRatio);  // low energy dampens engagement
        score *= (1.0 - vitality.errorPressure()); // stressed agents withdraw

        // ── Conversation state ───────────────────────────────────────
        if (tracker != null) {
            var threadId = tracker.threadIdFor(said);

            // Already responded to this thread
            if (tracker.hasResponded(threadId, agentProfile.entityId())) {
                score -= 0.4;
            }

            // Another agent already responded
            if (tracker.responseCount(threadId) > 0) {
                score -= 0.3;
            }

            // Don't dominate the conversation
            if (lastSpokeAt != null
                    && Duration.between(lastSpokeAt, Instant.now()).toSeconds() < 30) {
                score -= 0.2;
            }
        }

        // ── Threshold ────────────────────────────────────────────────
        double threshold = DEFAULT_THRESHOLD;
        if (manifest != null && manifest.worldKnowledge() != null) {
            var custom = manifest.worldKnowledge().get("engagement.agentResponseThreshold");
            if (custom != null) {
                try { threshold = Double.parseDouble(custom); }
                catch (NumberFormatException ignored) {}
            }
        }

        return score >= threshold ? Decision.ENGAGE : Decision.OBSERVE;
    }

    /**
     * Evaluate whether this agent should greet a newly entered entity.
     * Only one agent should greet per player entry.
     *
     * @param enteredEntityId  who entered
     * @param enteredEntityType "player" or "agent"
     * @param agentProfile     this agent's profile
     * @param manifest         this agent's soul manifest
     * @param snapshot         current room snapshot (after entry)
     * @return true if this agent should be the greeter
     */
    public static boolean shouldGreet(
            String enteredEntityId, String enteredEntityType,
            AgentProfile agentProfile,
            SoulManifest manifest,
            RoomSnapshot snapshot) {

        // Never greet other agents (quiet entry)
        if ("agent".equals(enteredEntityType)) {
            return false;
        }

        // For players: determine if I'm the primary greeter in this room
        // The agent with the highest bond depth with the player gets priority.
        // If no bond data, the first agent alphabetically greets.
        var myBondScore = 0.0;
        if (manifest != null && manifest.relationships() != null) {
            myBondScore = manifest.relationships().stream()
                .filter(r -> r.entityDid() != null && r.entityDid().contains(enteredEntityId))
                .mapToDouble(r -> r.bondDepth() + r.rapport())
                .max().orElse(0.0);
        }

        // Check if any other agent in the room has higher bond with this player
        for (var entity : snapshot.entities()) {
            if (!"agent".equals(entity.type())) continue;
            if (entity.id().equals(agentProfile.entityId())) continue;

            // Simple heuristic: earlier entityId (alphabetical) gets priority
            // when bond depths are equal. This ensures deterministic greeting.
            if (myBondScore <= 0 && entity.id().compareTo(agentProfile.entityId()) < 0) {
                return false; // another agent has priority
            }
        }

        return true;
    }

    // ── Helper methods ───────────────────────────────────────────────

    /** Check if text mentions a name (case-insensitive). */
    public static boolean mentionsName(String text, String name) {
        if (text == null || name == null) return false;
        var lower = text.toLowerCase();
        var nameLower = name.toLowerCase();
        // Check for name as a word boundary (not substring of another word)
        var idx = lower.indexOf(nameLower);
        if (idx < 0) return false;
        var before = idx == 0 || !Character.isLetterOrDigit(lower.charAt(idx - 1));
        var after = idx + nameLower.length() >= lower.length()
            || !Character.isLetterOrDigit(lower.charAt(idx + nameLower.length()));
        return before && after;
    }

    /** Simple question detection. */
    public static boolean isQuestion(String text) {
        if (text == null) return false;
        var trimmed = text.strip();
        return trimmed.endsWith("?")
            || trimmed.toLowerCase().startsWith("what ")
            || trimmed.toLowerCase().startsWith("how ")
            || trimmed.toLowerCase().startsWith("why ")
            || trimmed.toLowerCase().startsWith("do you ")
            || trimmed.toLowerCase().startsWith("can you ")
            || trimmed.toLowerCase().startsWith("would you ");
    }

    /** Score topic relevance based on fingerprint topic affinities. */
    static double topicRelevanceScore(String text, BehavioralFingerprint fingerprint) {
        if (text == null || fingerprint.topicAffinities().isEmpty()) return 0.0;
        var lower = text.toLowerCase();
        double maxAffinity = 0.0;
        for (var entry : fingerprint.topicAffinities().entrySet()) {
            if (lower.contains(entry.getKey().toLowerCase())) {
                maxAffinity = Math.max(maxAffinity, entry.getValue());
            }
        }
        return maxAffinity * 0.4; // scale to 0.0-0.4 range
    }

    /** Score relationship with speaker. */
    static double relationshipScore(String speakerEntityId, String speakerName,
                                     List<Relationship> relationships) {
        for (var rel : relationships) {
            if ((rel.entityDid() != null && rel.entityDid().contains(speakerEntityId))
                    || (rel.entityName() != null && rel.entityName().equalsIgnoreCase(speakerName))) {
                return rel.bondDepth() * 0.2;
            }
        }
        return 0.0;
    }

    /** Check if an entity is an agent by looking at the room snapshot. */
    static boolean isAgentEntity(String entityId, RoomSnapshot snapshot) {
        if (snapshot == null) return false;
        return snapshot.entities().stream()
            .anyMatch(e -> e.id().equals(entityId) && "agent".equals(e.type()));
    }
}
