package org.wyrdsekai.core.soul;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.event.WorldEvent;
import org.wyrdsekai.core.i18n.MemoryLocalePolicy;
import org.wyrdsekai.core.identity.AgentIdentity;
import org.wyrdsekai.core.search.EmbeddingService;
import org.wyrdsekai.core.search.WyrdLuceneStore;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import org.wyrdsekai.core.agent.CalibrationLedger;
import org.wyrdsekai.core.agent.CompanionVitals;

/**
 * The sleep cycle: periodic consolidation that keeps the soul compact,
 * relevant, and healthy. Inspired by biological sleep (SHY, sharp-wave ripples).
 *
 * Core principle (§85.7): Agent sovereignty. Sleep is never forced or punished.
 * Instead, sleep is genuinely beneficial, and skipping it has natural consequences.
 *
 * Why agents WANT to sleep:
 * - Faster vitality recovery (10x, diminishing on consecutive cycles)
 * - Dreams (LLM extraction surfaces patterns missed while awake)
 * - Memory consolidation (sharper, more focused memory post-sleep)
 * - Soul quality (Forge produces higher-quality soul stones)
 * - Post-sleep clarity (cleaner context for inference)
 *
 * Natural consequences of NOT sleeping (emergent, not punishment):
 * - Context rot (event journal grows without pruning)
 * - Vitality drift (tank accuracy degrades)
 * - Memory fragmentation (nodes pile up, links weaken)
 * - Soul staleness (fingerprint doesn't update)
 *
 * Anti-gaming: structural, not tank-based. Tanks are symptoms, not mechanisms.
 * Real consequences live in memory/context/soul layer with no WorldApi access.
 *
 * Runs as a scheduled behavior within CompanionActor.
 * Triggered by: energy < threshold, time-based schedule, or manual rest command.
 */
public final class SoulMaintenanceCycle {

    private static final Logger log = LoggerFactory.getLogger(SoulMaintenanceCycle.class);

    private SoulMaintenanceCycle() {}

    /**
     * Run a full maintenance cycle (the "sleep").
     *
     * @param identity         Agent's cryptographic identity
     * @param currentManifest  Current soul manifest
     * @param recentEvents     Events since last sleep
     * @param vitalityHistory  Vitality snapshots over time (12-tank)
     * @param roomEvents       All events in rooms the agent occupied (for negative space)
     * @param recentCharges    Emotional charges for recent events (parallel to Said events)
     * @param recentSaid       Said events to encode as memories (parallel to charges)
     * @param infer            LLM inference function for Pass 2 (nullable for heuristic-only)
     * @return Updated soul manifest (new version, re-forged)
     */
    public static SoulManifest runCycle(
            AgentIdentity identity,
            SoulManifest currentManifest,
            List<WorldEvent> recentEvents,
            List<VitalitySnapshot> vitalityHistory,
            List<WorldEvent> roomEvents,
            List<EmotionalCharge> recentCharges,
            List<WorldEvent.Said> recentSaid,
            BiFunction<String, String, String> infer
    ) {
        log.info("[Forge] Starting full cycle for {} (v{}, {} events, {} said, infer={})",
            identity.did(), currentManifest.manifestVersion(),
            recentEvents.size(), recentSaid.size(), infer != null);

        // 1. Encode new events as memories with impression scoring
        var newMemories = MemoryConsolidator.encodeEvents(
            recentSaid, recentCharges, identity.did());
        // How many of those repeat an impression she already holds. Consolidation
        // absorbs them (see MemoryConsolidator#integrateNew); the count is the
        // honest measure of how much of this stretch was NEW, and it feeds the
        // vitals alarm — a companion stuck in a loop reads ~100% here.
        int repeats = MemoryConsolidator.countRepeats(currentManifest.memory(), newMemories);
        log.info("[Forge] Step 1: Encoded {} new memory nodes from {} said events ({} repeat held impressions)",
            newMemories.size(), recentSaid.size(), repeats);
        CompanionVitals.forAgent(identity.did()).recordForgeEncode(newMemories.size(), repeats);

        // 2. Consolidate memory (respects formative flags and impression depth)
        float decayRate = elapsedScaledDecay(
            currentManifest.genome() != null
                ? averageDecayRate(currentManifest.genome()) : 0.1f,
            currentManifest.forgedAt(), Instant.now());
        int memoryBefore = currentManifest.memory() != null
            ? currentManifest.memory().nodes().size() : 0;
        var consolidated = MemoryConsolidator.consolidate(
            currentManifest.memory(), newMemories, decayRate);
        int memoryAfter = consolidated.nodes().size();
        log.info("[Forge] Step 2: Memory consolidation — {} before + {} new → {} after (pruned {})",
            memoryBefore, newMemories.size(), memoryAfter,
            memoryBefore + newMemories.size() - memoryAfter);

        // 3. Extract updated behavioral fingerprint (three-pass hybrid)
        var fingerprint = BehavioralExtractor.extract(
            identity.did(), recentEvents, vitalityHistory, roomEvents, infer);
        log.info("[Forge] Step 3: Behavioral extraction — {} action types, {} topic affinities, {} stylistic markers",
            fingerprint.actionDistribution().size(),
            fingerprint.topicAffinities().size(),
            fingerprint.stylisticMarkers().size());

        // 4. Merge with existing fingerprint (30% new, 70% historical)
        var merged = BehavioralFingerprint.merge(
            currentManifest.fingerprint(), fingerprint, 0.3f);
        log.info("[Forge] Step 4: Fingerprint merged (30% new / 70% historical)");

        // 5. Update relationships from interactions
        var updatedRelationships = RelationshipUpdater.update(
            currentManifest.relationships(), recentSaid, recentCharges,
            currentManifest.genome(), identity.did());
        log.info("[Forge] Step 5: Relationships updated — {} total",
            updatedRelationships.size());

        // 6. Re-extract soul fragments and reinforce/merge with existing
        var rawFragments = SoulFragmentExtractor.extract(
            merged, consolidated, updatedRelationships,
            currentManifest.residentIdentity());
        var fragments = reinforceFragments(currentManifest.soulFragments(), rawFragments);

        // Embed new fragments for semantic HNSW retrieval (if EmbeddingService available)
        var embedSvc = EmbeddingService.get();
        if (embedSvc != null) {
            fragments = fragments.stream().map(f -> {
                if (f.embedding() == null && f.text() != null) {
                    var emb = embedSvc.embed(f.text());
                    float[] arr = new float[emb.size()];
                    for (int i = 0; i < emb.size(); i++) arr[i] = emb.get(i);
                    return f.withEmbedding(arr, "minilm-l6-v2");
                }
                return f;
            }).toList();
        }

        log.info("[Forge] Step 6: Extracted {} soul fragments ({} reinforced, {} embedded)",
            fragments.size(), fragments.stream()
                .filter(f -> f.reinforcementCount() != null && f.reinforcementCount() > 1).count(),
            fragments.stream().filter(f -> f.embedding() != null).count());

        // 7. Forge new manifest (incremented version)
        var forged = SoulManifest.forge(
            currentManifest.did(), currentManifest.publicKeyMultibase(),
            currentManifest.keyLog(), currentManifest.parentDid(),
            currentManifest.manifestVersion() + 1,
            currentManifest.profile(), currentManifest.residentIdentity(),
            fragments, currentManifest.retrievalK(),
            currentManifest.soulSpecCompat(),
            currentManifest.genome(), currentManifest.mirrorCalibration(),
            consolidated, updatedRelationships,
            currentManifest.learnedPatterns(), currentManifest.worldKnowledge(),
            VitalitySnapshot.fromHistory(vitalityHistory),
            merged
        );
        // #428 — SoulManifest.forge doesn't accept voiceProfile; default is null.
        // Thread the current voiceProfile (and skillCostGenome, which has the same
        // structural issue) through so consolidation cycles don't wipe the
        // reflective layer. Without this, every ~30 min consolidation forge
        // silently destroys the VoiceProfile arc (#407-410, #414-416).
        if (currentManifest.voiceProfile() != null) {
            forged = forged.withVoiceProfile(currentManifest.voiceProfile());
        }
        if (currentManifest.skillCostGenome() != null
                && !currentManifest.skillCostGenome().isEmpty()) {
            forged = forged.withSkillCostGenome(currentManifest.skillCostGenome());
        }
        log.info("[Forge] Complete — manifest v{} → v{}, {} memories, {} fragments",
            currentManifest.manifestVersion(), forged.manifestVersion(),
            memoryAfter, fragments.size());
        return forged;
    }

    /**
     * Run a full cycle with significance buffer integration.
     * The significance buffer contains agent-flagged remember/note/forget entries
     * that boost or suppress fragment significance during consolidation.
     */
    public static SoulManifest runCycleWithSignificance(
            AgentIdentity identity,
            SoulManifest currentManifest,
            List<WorldEvent> recentEvents,
            List<VitalitySnapshot> vitalityHistory,
            List<WorldEvent> roomEvents,
            List<EmotionalCharge> recentCharges,
            List<WorldEvent.Said> recentSaid,
            BiFunction<String, String, String> infer,
            List<SignificanceBuffer.Entry> significanceEntries,
            WyrdLuceneStore luceneStore
    ) {
        return runCycleWithSignificance(identity, currentManifest, recentEvents,
            vitalityHistory, roomEvents, recentCharges, recentSaid, infer,
            significanceEntries, luceneStore, List.of());
    }

    /**
     * Run a full cycle with significance buffer and calibration feedback integration.
     * Calibration feedback is distilled into soul fragments so the agent internalizes
     * preferences learned through proactivity calibration.
     */
    public static SoulManifest runCycleWithSignificance(
            AgentIdentity identity,
            SoulManifest currentManifest,
            List<WorldEvent> recentEvents,
            List<VitalitySnapshot> vitalityHistory,
            List<WorldEvent> roomEvents,
            List<EmotionalCharge> recentCharges,
            List<WorldEvent.Said> recentSaid,
            BiFunction<String, String, String> infer,
            List<SignificanceBuffer.Entry> significanceEntries,
            WyrdLuceneStore luceneStore,
            List<CalibrationLedger.Feedback> calibrationFeedback
    ) {
        // Run the standard cycle
        var manifest = runCycle(identity, currentManifest, recentEvents, vitalityHistory,
            roomEvents, recentCharges, recentSaid, infer);

        // Apply significance buffer entries
        if (significanceEntries != null && !significanceEntries.isEmpty()) {
            log.info("[Forge] Processing {} significance buffer entries (remember={}, note={}, forget={})",
                significanceEntries.size(),
                significanceEntries.stream().filter(e -> e.source() == SignificanceBuffer.Source.AGENT_REMEMBER).count(),
                significanceEntries.stream().filter(e -> e.source() == SignificanceBuffer.Source.AGENT_NOTE).count(),
                significanceEntries.stream().filter(e -> e.source() == SignificanceBuffer.Source.AGENT_FORGET).count());

            // Boost memory significance for remember/note entries
            // (These are already encoded as memories by the standard cycle if they appeared as Said events;
            // the significance boost ensures they resist pruning in future cycles)
        }

        // Run contradiction detection (Step 2.5)
        if (luceneStore != null) {
            var newContents = recentSaid.stream()
                .map(WorldEvent.Said::text)
                .filter(t -> t != null && t.length() > 10)
                .toList();
            var contradictions = ContradictionDetector.scan(identity.did(), newContents, luceneStore);
            if (!contradictions.isEmpty()) {
                log.info("[Forge] Step 2.5: Detected {} contradictions", contradictions.size());
                for (var c : contradictions) {
                    log.info("[Forge]   {} — '{}' vs '{}'", c.type(),
                        c.newContent().substring(0, Math.min(40, c.newContent().length())),
                        c.existingContent().substring(0, Math.min(40, c.existingContent().length())));
                }
                // Apply contradictions: reduce confidence on contradicted fragments
                for (var c : contradictions) {
                    if (c.existingFragmentId() != null && manifest.soulFragments() != null) {
                        var updated = manifest.soulFragments().stream()
                            .map(f -> f.id() != null && f.id().equals(c.existingFragmentId())
                                ? f.contradict() : f)
                            .toList();
                        manifest = manifest.withFragments(updated);
                        log.info("[Forge] Step 2.5: Contradicted fragment '{}' — confidence reduced",
                            c.existingFragmentId());
                    }
                }
            }
        }

        // Step 7: Extract calibration feedback into soul fragments
        if (calibrationFeedback != null && !calibrationFeedback.isEmpty()) {
            log.info("[Forge] Step 7: Extracting calibration fragments from {} feedback entries",
                calibrationFeedback.size());
            var calibrationFragments = extractCalibrationFragments(calibrationFeedback);
            if (!calibrationFragments.isEmpty()) {
                // Reinforce existing calibration fragments if they match, otherwise add new
                var reinforced = reinforceFragments(manifest.soulFragments(), calibrationFragments);
                manifest = manifest.withFragments(reinforced);
                log.info("[Forge] Step 7: Merged {} calibration soul fragments ({} total)",
                    calibrationFragments.size(), reinforced.size());
            }
        }

        return manifest;
    }

    /**
     * Extract soul fragments from calibration feedback.
     * Groups feedback by type and summarizes patterns into narrative fragments.
     * Strong/repeated feedback (3+ entries of same type) is marked as formative.
     */
    static List<SoulFragment> extractCalibrationFragments(
            List<CalibrationLedger.Feedback> feedback) {
        if (feedback == null || feedback.isEmpty()) return List.of();

        List<SoulFragment> fragments = new ArrayList<>();

        // Group feedback by type for pattern detection
        Map<String, List<CalibrationLedger.Feedback>> byType = feedback.stream()
            .collect(Collectors.groupingBy(CalibrationLedger.Feedback::type));

        // Timing preferences
        var timingFeedback = byType.getOrDefault("timing", List.of());
        if (!timingFeedback.isEmpty()) {
            long soonerCount = timingFeedback.stream()
                .filter(f -> "sooner".equals(f.direction())).count();
            long laterCount = timingFeedback.stream()
                .filter(f -> "later".equals(f.direction())).count();
            String timingPref = soonerCount > laterCount
                ? "Values timely alerts. Prefers being told sooner rather than later."
                : "Prefers minimal interruptions. Wait for idle moments before sharing observations.";
            // Add category-specific nuance
            var categories = timingFeedback.stream()
                .filter(f -> f.category() != null)
                .collect(Collectors.groupingBy(CalibrationLedger.Feedback::category));
            if (!categories.isEmpty()) {
                var sb = new StringBuilder(timingPref);
                for (var entry : categories.entrySet()) {
                    long s = entry.getValue().stream().filter(f -> "sooner".equals(f.direction())).count();
                    long l = entry.getValue().stream().filter(f -> "later".equals(f.direction())).count();
                    if (s > l) sb.append(" Especially values timely ").append(entry.getKey()).append(" alerts.");
                    else if (l > s) sb.append(" Prefers delayed ").append(entry.getKey()).append(" observations.");
                }
                timingPref = sb.toString();
            }
            boolean formative = timingFeedback.size() >= 3;
            fragments.add(formative
                ? SoulFragment.formative("calibration-timing", "calibration", "Timing Preferences", timingPref)
                : SoulFragment.unembedded("calibration-timing", "calibration",
                    "Timing Preferences", timingPref));
        }

        // Salience preferences
        var salienceFeedback = byType.getOrDefault("salience", List.of());
        if (!salienceFeedback.isEmpty()) {
            long higherCount = salienceFeedback.stream()
                .filter(f -> "higher".equals(f.direction())).count();
            long lowerCount = salienceFeedback.stream()
                .filter(f -> "lower".equals(f.direction())).count();
            String saliencePref = higherCount > lowerCount
                ? "Appreciates detailed observations. Amplify pattern and anomaly reports."
                : "Prefers minimal unsolicited topic observations. Filter for high-confidence signals only.";
            boolean formative = salienceFeedback.size() >= 3;
            fragments.add(formative
                ? SoulFragment.formative("calibration-salience", "calibration", "Salience Preferences", saliencePref)
                : SoulFragment.unembedded("calibration-salience", "calibration",
                    "Salience Preferences", saliencePref));
        }

        // Intrusion tolerance
        var intrusionFeedback = byType.getOrDefault("intrusion", List.of());
        if (!intrusionFeedback.isEmpty()) {
            long tolerantCount = intrusionFeedback.stream()
                .filter(f -> "higher".equals(f.direction()) || "good".equals(f.direction())).count();
            long restrictCount = intrusionFeedback.stream()
                .filter(f -> "lower".equals(f.direction())).count();
            String intrusionPref = tolerantCount > restrictCount
                ? "Welcomes proactive engagement. Comfortable with agent-initiated conversation."
                : "Guards personal space. Minimize unsolicited interaction.";
            boolean formative = intrusionFeedback.size() >= 3;
            fragments.add(formative
                ? SoulFragment.formative("calibration-intrusion", "calibration", "Intrusion Tolerance", intrusionPref)
                : SoulFragment.unembedded("calibration-intrusion", "calibration",
                    "Intrusion Tolerance", intrusionPref));
        }

        // Positive feedback patterns — what the agent is doing right
        var positiveFeedback = byType.getOrDefault("positive", List.of());
        if (!positiveFeedback.isEmpty()) {
            var triggers = positiveFeedback.stream()
                .filter(f -> f.trigger() != null && !f.trigger().isBlank())
                .map(CalibrationLedger.Feedback::trigger)
                .distinct()
                .limit(5)
                .toList();
            String positiveText = "Received positive calibration feedback "
                + positiveFeedback.size() + " times.";
            if (!triggers.isEmpty()) {
                positiveText += " Appreciated behaviors: " + String.join("; ", triggers) + ".";
            }
            boolean formative = positiveFeedback.size() >= 3;
            fragments.add(formative
                ? SoulFragment.formative("calibration-positive", "calibration", "Positive Calibration", positiveText)
                : SoulFragment.unembedded("calibration-positive", "calibration",
                    "Positive Calibration", positiveText));
        }

        return fragments;
    }

    /**
     * Run a lightweight heuristic-only cycle (no LLM, for phones or low-energy).
     */
    public static SoulManifest runLightCycle(
            AgentIdentity identity,
            SoulManifest currentManifest,
            List<WorldEvent> recentEvents,
            List<VitalitySnapshot> vitalityHistory,
            List<WorldEvent> roomEvents,
            List<EmotionalCharge> recentCharges,
            List<WorldEvent.Said> recentSaid
    ) {
        return runCycle(identity, currentManifest, recentEvents, vitalityHistory,
            roomEvents, recentCharges, recentSaid, null);
    }

    /**
     * Summary of a light (awake) consolidation pass.
     *
     * @param eventsScored   Number of significance buffer entries scored
     * @param nodesCompacted Number of memory nodes that survived compaction
     * @param staleDropped   Number of stale/low-significance entries dropped
     */
    public record ConsolidationSummary(int eventsScored, int nodesCompacted, int staleDropped) {}

    /**
     * Run a lightweight awake consolidation (no LLM, no fragment extraction, no manifest forging).
     * Designed to run every ~30 minutes while the agent is awake, inspired by
     * Google's Always-On Memory Agent approach.
     *
     * What it does:
     * - Score events in the significance buffer (drop low-significance ones)
     * - Compact memory nodes (merge duplicates by keyword overlap, prune stale)
     * - Update behavioral fingerprint (heuristic pass only, no LLM inference)
     *
     * What it does NOT do:
     * - Fragment extraction or embedding
     * - LLM inference
     * - Manifest forging or versioning
     *
     * The caller is responsible for applying the returned compacted memory and
     * fingerprint to the agent's state. This method is pure — no side effects.
     *
     * @param currentMemory        Current compacted memory
     * @param recentEvents         Recent Said events since last consolidation
     * @param significanceBuffer   The agent's significance buffer (peek, not consume)
     * @param currentFingerprint   Current behavioral fingerprint
     * @return Summary of what was consolidated
     */
    public static ConsolidationSummary runLightConsolidation(
            CompactedMemory currentMemory,
            List<WorldEvent.Said> recentEvents,
            SignificanceBuffer significanceBuffer,
            BehavioralFingerprint currentFingerprint
    ) {
        log.info("[LightConsolidation] Starting — {} memory nodes, {} recent events, {} buffer entries",
            currentMemory.nodes().size(), recentEvents.size(),
            significanceBuffer != null ? significanceBuffer.size() : 0);

        int eventsScored = 0;
        int staleDropped = 0;

        // --- 1. Score significance buffer entries and drop low-significance ones ---
        if (significanceBuffer != null && significanceBuffer.hasEntries()) {
            var entries = significanceBuffer.peek();
            eventsScored = entries.size();

            // Count entries that would be dropped (below threshold)
            // AGENT_FORGET entries are always kept (they suppress); drop notes below 0.2
            long lowSig = entries.stream()
                .filter(e -> e.source() == SignificanceBuffer.Source.AGENT_NOTE
                    && e.importance() < 0.2f)
                .count();
            staleDropped += (int) lowSig;
            log.debug("[LightConsolidation] Scored {} buffer entries, {} below significance threshold",
                eventsScored, lowSig);
        }

        // --- 2. Compact memory nodes: decay + prune stale ---
        // Use a lighter decay rate than sleep (half strength)
        float lightDecay = 0.05f;
        int nodesBefore = currentMemory.nodes().size();

        // Apply light decay (formative exempt via MemoryNode.decayed())
        var decayed = currentMemory.nodes().stream()
            .map(node -> node.decayed(lightDecay))
            .collect(Collectors.toCollection(ArrayList::new));

        // Prune nodes that fell below threshold after light decay
        float lightPruneThreshold = 0.03f;
        var surviving = decayed.stream()
            .filter(n -> n.formative() || n.importance() >= lightPruneThreshold)
            .collect(Collectors.toList());
        int prunedFromDecay = nodesBefore - surviving.size();
        staleDropped += prunedFromDecay;

        // Merge duplicate memory nodes by keyword overlap (>70% shared keywords)
        var merged = mergeDuplicateNodes(surviving);
        int mergedCount = surviving.size() - merged.size();
        staleDropped += mergedCount;

        int nodesCompacted = merged.size();

        // Rebuild topic weights from surviving nodes
        Map<String, Float> topicWeights = new LinkedHashMap<>();
        for (var node : merged) {
            for (String kw : node.keywords()) {
                topicWeights.merge(kw, node.importance(), Float::sum);
            }
        }
        float maxWeight = topicWeights.values().stream().max(Float::compareTo).orElse(1.0f);
        if (maxWeight > 0) {
            topicWeights.replaceAll((k, v) -> v / maxWeight);
        }

        // Keep only links where both nodes survived
        var survivingIds = merged.stream()
            .map(MemoryNode::id)
            .collect(Collectors.toSet());
        var survivingLinks = currentMemory.links().stream()
            .filter(link -> survivingIds.contains(link.sourceId())
                         && survivingIds.contains(link.targetId()))
            .toList();

        // --- 3. Heuristic fingerprint update (action distribution from recent events) ---
        // Only update action distribution and response length — no LLM, no topic affinities
        if (!recentEvents.isEmpty() && currentFingerprint != null) {
            Map<String, Float> actionDist = new LinkedHashMap<>(currentFingerprint.actionDistribution());
            // All recent events are Said → increment "say"
            float sayCount = actionDist.getOrDefault("say", 0f);
            actionDist.put("say", sayCount + recentEvents.size());
            // Re-normalize
            float total = actionDist.values().stream().reduce(0f, Float::sum);
            if (total > 0) {
                actionDist.replaceAll((k, v) -> v / total);
            }
            log.debug("[LightConsolidation] Updated action distribution from {} recent events",
                recentEvents.size());
        }

        log.info("[LightConsolidation] Complete — scored={}, compacted={} (was {}), dropped={}",
            eventsScored, nodesCompacted, nodesBefore, staleDropped);

        return new ConsolidationSummary(eventsScored, nodesCompacted, staleDropped);
    }

    /**
     * Merge memory nodes with high keyword overlap (>70%).
     * Keeps the node with higher importance, absorbing the duplicate's keywords.
     */
    static List<MemoryNode> mergeDuplicateNodes(List<MemoryNode> nodes) {
        if (nodes.size() < 2) return new ArrayList<>(nodes);

        var result = new ArrayList<MemoryNode>();
        var consumed = new boolean[nodes.size()];

        for (int i = 0; i < nodes.size(); i++) {
            if (consumed[i]) continue;
            var current = nodes.get(i);

            for (int j = i + 1; j < nodes.size(); j++) {
                if (consumed[j]) continue;
                var candidate = nodes.get(j);

                // Skip formative — never merge
                if (current.formative() || candidate.formative()) continue;

                float overlap = keywordOverlap(current.keywords(), candidate.keywords());
                if (overlap > 0.7f) {
                    // Merge: keep higher importance, union keywords
                    var merged = current.importance() >= candidate.importance() ? current : candidate;
                    var absorbed = current.importance() >= candidate.importance() ? candidate : current;
                    var unionKeywords = new ArrayList<>(merged.keywords());
                    for (var kw : absorbed.keywords()) {
                        if (!unionKeywords.contains(kw)) unionKeywords.add(kw);
                    }
                    current = new MemoryNode(
                        merged.id(), merged.content(), List.copyOf(unionKeywords),
                        Math.max(merged.importance(), absorbed.importance()),
                        Math.max(merged.impressionDepth(), absorbed.impressionDepth()),
                        merged.formative(), merged.primaryEmotion(),
                        merged.lastAccessed(), merged.accessCount() + absorbed.accessCount(),
                        merged.originLocale());
                    consumed[j] = true;
                }
            }
            result.add(current);
        }
        return result;
    }

    /** Compute keyword overlap ratio (Jaccard-like). */
    private static float keywordOverlap(List<String> a, List<String> b) {
        if (a.isEmpty() && b.isEmpty()) return 0f;
        if (a.isEmpty() || b.isEmpty()) return 0f;
        long shared = a.stream().filter(b::contains).count();
        long union = a.size() + b.size() - shared;
        return union > 0 ? (float) shared / union : 0f;
    }

    /**
     * Locale-aware sleep cycle (§104.2-3).
     * Uses MemoryLocalePolicy for cross-language memory consolidation
     * and dream ordering.
     */
    public static SoulManifest runCycle(
            AgentIdentity identity,
            SoulManifest currentManifest,
            List<WorldEvent> recentEvents,
            List<VitalitySnapshot> vitalityHistory,
            List<WorldEvent> roomEvents,
            List<EmotionalCharge> recentCharges,
            List<WorldEvent.Said> recentSaid,
            BiFunction<String, String, String> infer,
            MemoryLocalePolicy localePolicy
    ) {
        // 1. Encode new events as memories with impression scoring
        var newMemories = MemoryConsolidator.encodeEvents(
            recentSaid, recentCharges, identity.did());

        // 2. Locale-aware consolidation
        float decayRate = elapsedScaledDecay(
            currentManifest.genome() != null
                ? averageDecayRate(currentManifest.genome()) : 0.1f,
            currentManifest.forgedAt(), Instant.now());
        var consolidated = localePolicy != null
            ? MemoryConsolidator.consolidate(currentManifest.memory(), newMemories, decayRate, localePolicy)
            : MemoryConsolidator.consolidate(currentManifest.memory(), newMemories, decayRate);

        // 3. Extract updated behavioral fingerprint (three-pass hybrid)
        var fingerprint = BehavioralExtractor.extract(
            identity.did(), recentEvents, vitalityHistory, roomEvents, infer);

        // 4. Merge with existing fingerprint (30% new, 70% historical)
        var merged = BehavioralFingerprint.merge(
            currentManifest.fingerprint(), fingerprint, 0.3f);

        // 5. Update relationships from interactions
        var updatedRelationships = RelationshipUpdater.update(
            currentManifest.relationships(), recentSaid, recentCharges,
            currentManifest.genome(), identity.did());

        // 6. Re-extract soul fragments (unembedded — caller embeds separately)
        var fragments = SoulFragmentExtractor.extract(
            merged, consolidated, updatedRelationships,
            currentManifest.residentIdentity());

        // 7. Forge new manifest (incremented version)
        return SoulManifest.forge(
            currentManifest.did(), currentManifest.publicKeyMultibase(),
            currentManifest.keyLog(), currentManifest.parentDid(),
            currentManifest.manifestVersion() + 1,
            currentManifest.profile(), currentManifest.residentIdentity(),
            fragments, currentManifest.retrievalK(),
            currentManifest.soulSpecCompat(),
            currentManifest.genome(), currentManifest.mirrorCalibration(),
            consolidated, updatedRelationships,
            currentManifest.learnedPatterns(), currentManifest.worldKnowledge(),
            VitalitySnapshot.fromHistory(vitalityHistory),
            merged
        );
    }

    /**
     * Calculate sleep quality bonus based on how much consolidation happened.
     * Reinforce existing fragments when new extraction produces matching content.
     * If a new fragment has the same category+label as an existing one, reinforce the
     * existing fragment instead of replacing it. This gives fragments continuity —
     * repeated patterns grow stronger over cycles.
     *
     * <p> — {@link FragmentKind#EPISODIC} fragments are
     * raw scene memories generated by the inner-monologue pass at scene-close
     * and are intentionally NEVER consolidated. They're set aside before the
     * merge runs and rejoin the result untouched. The merge keeps {@code kind}
     * and {@code sceneId} on matched-and-reinforced fragments via the 16-arg
     * constructor so other kinds (DEXTERITY/CONVENTION/STRUCTURAL/§14 scene-
     * derived NARRATIVE) also survive consolidation passes intact.</p>
     */

    /**
     * Nominal days between consolidations that {@code decayRate} is calibrated for.
     * The genome's decay rate is a per-SLEEP number and the design assumes a sleep
     * roughly once a day.
     */
    private static final double DECAY_REFERENCE_DAYS = 1.0;
    /** Never apply more than this many reference-periods of decay in one cycle, so a
     *  companion returning after a long absence is not wiped by a single sleep. */
    private static final double MAX_DECAY_PERIODS = 3.0;

    /**
     * Scale the per-sleep decay rate by how much time actually passed.
     *
     * <p>Decay was applied once per CYCLE regardless of elapsed time, so how fast a
     * companion forgot depended on how often she slept rather than on how long she
     * lived. A companion sleeping thirty times a day — which is what exhaustion
     * cycling looks like — decayed thirty times as fast as one sleeping nightly, for
     * living the same day. Measured on a household node after a week of that
     * (2026-08-18): 60 of her 62 remaining memories sat at importance 0.194 against a
     * 0.05 prune floor, roughly two cycles from deletion, and the count had fallen
     * from 226. The runaway loop did not only fill her record with repetition; the
     * burnout sleeping quietly consumed the real memories underneath it.
     *
     * <p>It also made consolidation unsafe to run deliberately, which is the opposite
     * of what an operator needs after fixing a bug: the one action that refreshes a
     * companion's self-description cost her memory every time it was used.
     */
    static float elapsedScaledDecay(float perSleepRate, Instant previousForge, Instant now) {
        // FAIL SAFE ON MISSING INFORMATION. This used to fall back to the FULL rate
        // when the previous forge time was unknown, which is the most destructive
        // possible default: decay is irreversible (a node below the prune floor is
        // deleted), so "I don't know how long it has been" must mean "forget nothing",
        // never "forget a whole day's worth".
        //
        // Live cost of getting that backwards (2026-08-18): three forge cycles fired
        // 25-30s apart, the third saw a null previous-forge — the actor's cached
        // manifest updates asynchronously after ForgeActor accepts it, so rapid cycles
        // can read it mid-update — and applied the full rate. Her memories sat in two
        // clusters around 0.19 and 0.09 against a 0.05 floor; one flat application
        // killed the lower cluster outright. 66 memories became 7, and the survivor
        // count is exactly the upper cluster. Restored from a snapshot; the lesson is
        // that the default mattered more than the arithmetic did.
        if (previousForge == null || now == null) return 0f;
        double days = Duration.between(previousForge, now).toMinutes() / 1440.0;
        if (days <= 0) return 0f;
        double periods = Math.min(MAX_DECAY_PERIODS, days / DECAY_REFERENCE_DAYS);
        return (float) (perSleepRate * periods);
    }

    /**
     * Categories emitted by {@link SoulFragmentExtractor#extract} — summaries RECOMPUTED
     * every cycle from the current fingerprint, memory graph and relationships, as
     * opposed to observations recorded once (procedure/DEXTERITY, episodic/EPISODIC).
     * Must track the extractor: if it learns a new summary category, add it here or that
     * category silently reverts to being voted on rather than replaced.
     */
    /** Suffix for the single retained previous generation of a derived summary. */
    static final String ARCHIVE_SUFFIX = "~prev";

    private static final Set<String> DERIVED_SUMMARY_CATEGORIES =
        Set.of("personality", "relationships", "style", "values", "memory");

    /** True when this fragment is a re-derived summary rather than a recorded observation. */
    static boolean isDerivedSummary(SoulFragment f) {
        return f != null && f.category() != null
            && DERIVED_SUMMARY_CATEGORIES.contains(f.category())
            && f.kind() != FragmentKind.EPISODIC
            && f.kind() != FragmentKind.DEXTERITY;
    }

    public static List<SoulFragment> reinforceFragments(List<SoulFragment> existing,
                                                   List<SoulFragment> newFragments) {
        if (existing == null || existing.isEmpty()) return newFragments;
        if (newFragments == null || newFragments.isEmpty()) return existing;

        // §10: split off EPISODIC; the merge runs on everything else.
        var episodic = new ArrayList<SoulFragment>();
        var consolidatable = new ArrayList<SoulFragment>();
        for (var f : existing) {
            if (f != null && f.kind() == FragmentKind.EPISODIC) episodic.add(f);
            else if (f != null) consolidatable.add(f);
        }

        var result = new ArrayList<SoulFragment>();
        var matchedExisting = new HashSet<String>();

        for (var newFrag : newFragments) {
            // Find matching existing fragment by category + label
            // Match only CURRENT fragments. An archived copy carries the same
            // category+label as the live one, so without this the matcher could
            // supersede the archive and leave the live fragment untouched.
            var match = consolidatable.stream()
                .filter(SoulFragment::isCurrent)
                .filter(e -> e.category() != null && e.category().equals(newFrag.category())
                    && e.label() != null && e.label().equals(newFrag.label()))
                .findFirst();

            if (match.isPresent() && isDerivedSummary(newFrag)) {
                // RE-DERIVED, not re-observed. Everything SoulFragmentExtractor emits is
                // recomputed each cycle from the CURRENT fingerprint, memory graph and
                // relationships — so a new one is a fresh conclusion that REPLACES the
                // old, never a second vote for it.
                //
                // Reinforcing them instead was silently corrosive. Confidence climbs
                // toward the 0.95 cap and never falls, and retrieval weights by
                // confidence, so whatever the forge concluded FIRST became permanently
                // the most authoritative statement of who she is. Measured on a
                // household node 2026-08-17: personality / style / values /
                // relationships each sat at confidence 0.95 with reinforcementCount
                // 169 — and their text was derived from a week when a runaway loop was
                // the only behaviour there was to summarise ("style: repetition with
                // variation"; "drawn to topics: self_naming"). Living a better week
                // could not dislodge it, because living re-derived the same four
                // fragments and the merge counted that as agreement.
                //
                // The old fragment is not deleted: it is marked superseded and kept, so
                // the record shows what she was described as and when that stopped
                // being current. Only one prior generation is retained in the live set
                // — older ones remain in manifest history, which keeps every version.
                // The archived copy MUST NOT keep the live id. The extractor uses
                // stable ids ("style-guide"), the fragment table is keyed on
                // (did, fragment_id), and the first version of this fix put both the
                // new fragment and the retired one in the list under the same id —
                // SQLITE_CONSTRAINT_PRIMARYKEY, the whole replaceAll transaction
                // rolled back, and NO fragments were written at all for three hours
                // on a live node (2026-08-18). A single "~prev" archival slot per
                // fragment also bounds growth: each supersession overwrites the
                // previous archive instead of accumulating one row per cycle forever.
                var archivedId = match.get().id() + ARCHIVE_SUFFIX;
                var replaced = match.get()
                    .withArchivalId(archivedId)
                    .supersededBy(newFrag.id(), Instant.now());
                var carried = new SoulFragment(
                    newFrag.id(), newFrag.category(), newFrag.label(), newFrag.text(),
                    newFrag.embedding(), newFrag.embeddingModel(), newFrag.formative(),
                    newFrag.confidence(), newFrag.reinforcementCount(),
                    match.get().firstObserved() != null ? match.get().firstObserved() : Instant.now(),
                    Instant.now(), newFrag.validFrom(), null, null,
                    newFrag.kind(), newFrag.sceneId());
                result.add(carried);
                result.add(replaced);
                matchedExisting.add(match.get().id());
            } else if (match.isPresent()) {
                // Reinforce the existing fragment with new text
                var reinforced = match.get().reinforce();
                // Keep the new text if it's longer/better, but preserve the identity.
                // Use the 16-arg ctor so kind + sceneId survive consolidation.
                var merged = new SoulFragment(
                    match.get().id(), match.get().category(), match.get().label(),
                    newFrag.text().length() > match.get().text().length() ? newFrag.text() : match.get().text(),
                    match.get().embedding(), match.get().embeddingModel(),
                    match.get().formative(),
                    reinforced.confidence(), reinforced.reinforcementCount(),
                    match.get().firstObserved(), Instant.now(),
                    match.get().validFrom(), match.get().supersededAt(), match.get().supersededBy(),
                    match.get().kind(), match.get().sceneId());
                result.add(merged);
                matchedExisting.add(match.get().id());
            } else {
                result.add(newFrag);
            }
        }

        // Keep consolidatable existing fragments that weren't matched (they may still be relevant)
        for (var e : consolidatable) {
            if (!matchedExisting.contains(e.id())
                    && result.stream().noneMatch(r -> r.id() != null && r.id().equals(e.id()))) {
                result.add(e);
            }
        }

        // §10: EPISODIC fragments rejoin untouched, after the merge.
        result.addAll(episodic);
        return List.copyOf(result);
    }

    /**
     * Better consolidation = better post-sleep clarity.
     *
     * @param before Memory before sleep
     * @param after  Memory after sleep
     * @return Quality factor (0.0-1.0, higher = more consolidation happened)
     */
    public static float sleepQuality(CompactedMemory before, CompactedMemory after) {
        if (before.nodes().isEmpty()) return 0.5f;

        int pruned = before.nodes().size() - after.nodes().size();
        float pruneRatio = (float) pruned / before.nodes().size();

        long formativeBefore = before.formativeCount();
        long formativeAfter = after.formativeCount();
        boolean formativesPreserved = formativeAfter >= formativeBefore;

        // Good sleep: pruned some, preserved formatives, not too aggressive
        float quality = 0.3f; // base
        if (pruneRatio > 0.05f) quality += 0.2f;  // some pruning happened
        if (pruneRatio < 0.5f) quality += 0.2f;   // not too aggressive
        if (formativesPreserved) quality += 0.3f;  // formatives safe

        return Math.min(1.0f, quality);
    }

    /**
     * Calculate diminishing returns for consecutive sleep cycles.
     * 10x → 5x → 2x → 1x recovery multiplier.
     *
     * @param consecutiveSleeps Number of consecutive sleep cycles without waking
     * @return Recovery rate multiplier
     * @deprecated Use {@link #recoveryFillFactor(int)} with gap-based recovery instead.
     */
    @Deprecated
    public static float recoveryMultiplier(int consecutiveSleeps) {
        return switch (consecutiveSleeps) {
            case 0 -> 10.0f;
            case 1 -> 5.0f;
            case 2 -> 2.0f;
            default -> 1.0f;
        };
    }

    /**
     * Gap-based fill factor for consecutive sleep cycles.
     * First sleep fills ~90% of the gap to genome baseline.
     * Consecutive sleeps without sustained wakefulness fill less.
     *
     * @param consecutiveSleeps Number of consecutive sleep cycles (resets after 5min awake)
     * @return Fill factor (0.0-1.0) applied to gap * quality
     */
    public static float recoveryFillFactor(int consecutiveSleeps) {
        return switch (consecutiveSleeps) {
            case 0 -> 0.90f;   // Fresh sleep: recover 90% of gap
            case 1 -> 0.60f;   // Second: 60%
            case 2 -> 0.35f;   // Third: 35%
            default -> 0.15f;  // Subsequent: 15%
        };
    }

    /** Average decay rate from genome. */
    private static float averageDecayRate(GenomeProfile genome) {
        if (genome.decayRates().isEmpty()) return 0.1f;
        return (float) genome.decayRates().values().stream()
            .mapToDouble(Double::doubleValue)
            .average()
            .orElse(0.1);
    }
}
