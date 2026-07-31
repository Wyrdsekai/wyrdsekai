package org.wyrdsekai.app.engine.soul

import org.wyrdsekai.app.engine.agent.VitalityState
import org.wyrdsekai.app.engine.event.WorldEvent
import org.wyrdsekai.app.inference.InferenceClient

/**
 * Wave 4 input to a full Forge cycle during phone sleep.
 *
 * @property manifest           Current soul manifest
 * @property events             WorldEvents accumulated since last sleep
 * @property vitalityHistory    Vitality snapshots over time (sampled periodically)
 * @property vitality           Current vitality state at sleep time
 * @property agentEntityId      The agent's entity ID
 * @property inferenceClient    Inference HTTP client, or null for heuristic-only mode
 * @property inferenceBaseUrl   Base URL of inference endpoint, or null for heuristic-only
 * @property sleepCount         Number of sleep cycles completed so far
 * @property previousFingerprint Fingerprint from the previous sleep, for merging
 */
data class PhoneForgeInput(
    val manifest: ClientSoulManifest,
    val events: List<WorldEvent>,
    val vitalityHistory: List<VitalityState>,
    val vitality: VitalityState,
    val agentEntityId: String,
    val inferenceClient: InferenceClient?,
    val inferenceBaseUrl: String?,
    val sleepCount: Int,
    val previousFingerprint: PhoneFingerprint?,
)

/**
 * Result of a full PhoneForge cycle.
 *
 * @property newManifest      The forged manifest with updated fragments, genome, vitality
 * @property fingerprint      The merged behavioral fingerprint (saved for next sleep)
 * @property energyRecovery   Energy points recovered during sleep
 * @property focusRecovery    Focus points recovered during sleep
 * @property sleepQuality     Overall sleep quality 0.0-1.0
 * @property extractionLevel  "heuristic" or "llm" — which level of extraction succeeded
 * @property fragmentsChanged Number of fragments that were created or modified
 */
data class PhoneForgeResult(
    val newManifest: ClientSoulManifest,
    val fingerprint: PhoneFingerprint,
    val energyRecovery: Double,
    val focusRecovery: Double,
    val sleepQuality: Double,
    val extractionLevel: String,
    val fragmentsChanged: Int,
)

/**
 * Wave 4: PhoneForge orchestrator — the centerpiece of phone-side soul evolution.
 *
 * Replaces the previous sleep snapshot (re-forge manifest with same fragments)
 * with real soul evolution by orchestrating:
 * 1. HeuristicExtractor (Wave 1) — instant, always
 * 2. LlmExtractor (Wave 2) — one inference call, if available
 * 3. FragmentEvolver + IdentityEvolver (Wave 3) — fragment generation/evolution
 *
 * The result is a new manifest with evolved fragments, tuned genome, and
 * optionally a regenerated identity — the companion becomes more itself
 * with each sleep cycle.
 *
 * Phone port of the server's ForgeActor sleep consolidation pipeline.
 */
object PhoneForge {

    /** Minimum events required before attempting LLM extraction. */
    private const val LLM_MIN_EVENTS = 10
    /** Weight for fresh fingerprint data when merging with historical. */
    private const val MERGE_ALPHA = 0.3

    /**
     * Run a full Forge cycle during phone sleep.
     *
     * Pipeline:
     * 1. Heuristic extraction (instant, always)
     * 2. LLM extraction (one call, if client available and events >= 10)
     * 3. Merge with previous fingerprint (weighted average)
     * 4. Generate or evolve fragments
     * 5. Optionally regenerate identity (after 5+ sleeps, bootstrap DID)
     * 6. Tune genome based on observed vitality trends
     * 7. Compute sleep quality + recovery
     * 8. Forge new manifest
     */
    suspend fun forgeFromSleep(input: PhoneForgeInput): PhoneForgeResult {
        val agentName = input.manifest.agentName

        // Step 1: Heuristic extraction (always — free, instant)
        var fingerprint = HeuristicExtractor.extract(
            agentEntityId = input.agentEntityId,
            events = input.events,
            vitalityHistory = input.vitalityHistory,
        )
        var extractionLevel = "heuristic"

        // Step 2: LLM extraction (if available and enough events)
        if (input.inferenceClient != null && input.inferenceBaseUrl != null
            && input.events.size >= LLM_MIN_EVENTS) {
            fingerprint = LlmExtractor.extractWithLlm(
                inferenceClient = input.inferenceClient,
                inferenceBaseUrl = input.inferenceBaseUrl,
                fingerprint = fingerprint,
                events = input.events,
                agentName = agentName,
            )
            // If LLM enriched the fingerprint with any new data, mark as llm level
            if (fingerprint.topicAffinities.isNotEmpty() || fingerprint.stylisticMarkers.isNotEmpty()) {
                extractionLevel = "llm"
            }
        }

        // Step 3: Merge with previous fingerprint (weighted average preserves history)
        if (input.previousFingerprint != null) {
            fingerprint = PhoneFingerprint.merge(input.previousFingerprint, fingerprint, MERGE_ALPHA)
        }

        // Step 4: Generate or evolve fragments
        val existingFragments = input.manifest.fragments
        val isFirstRealSleep = existingFragments.isEmpty() ||
            existingFragments.all { it.id.startsWith("bootstrap-") }
        val newFragments = if (isFirstRealSleep) {
            FragmentEvolver.generateInitialFragments(
                fingerprint = fingerprint,
                residentIdentity = input.manifest.residentIdentity,
                agentName = agentName,
            )
        } else {
            FragmentEvolver.evolveFragments(
                existing = existingFragments,
                fingerprint = fingerprint,
                agentName = agentName,
                sleepCount = input.sleepCount,
            )
        }
        val fragmentsChanged = countChanges(existingFragments, newFragments)

        // Step 5: Optionally regenerate identity (bootstrap DID, 5+ sleeps, 3+ fragments)
        var residentIdentity = input.manifest.residentIdentity
        if (input.inferenceClient != null && input.inferenceBaseUrl != null) {
            val tempManifest = input.manifest.copy(fragments = newFragments)
            if (IdentityEvolver.shouldRegenerateIdentity(tempManifest, input.sleepCount)) {
                val newIdentity = IdentityEvolver.regenerateIdentity(
                    inferenceClient = input.inferenceClient,
                    inferenceBaseUrl = input.inferenceBaseUrl,
                    manifest = tempManifest,
                )
                if (newIdentity != null) {
                    residentIdentity = newIdentity
                }
            }
        }

        // Step 6: Tune genome based on observed vitality trends
        val genome = tuneGenome(input.manifest.genome, fingerprint)

        // Step 7: Sleep quality + recovery
        val sleepQuality = computeSleepQuality(input.events.size, input.vitality.energy)
        val energyRecovery = 0.2 * sleepQuality
        val focusRecovery = 0.15 * sleepQuality

        // Step 8: Forge new manifest with evolved soul
        val newManifest = LocalForge.forge(
            did = input.manifest.did,
            publicKey = input.manifest.publicKeyMultibase,
            version = input.manifest.manifestVersion + 1,
            profile = LocalForge.restoreProfile(input.manifest),
            residentIdentity = residentIdentity,
            vitality = input.vitality,
            fragments = newFragments,
            genome = genome,
            calibration = input.manifest.mirrorCalibration,
            relationships = input.manifest.relationships,
            retrievalK = input.manifest.retrievalK,
        )

        return PhoneForgeResult(
            newManifest = newManifest,
            fingerprint = fingerprint,
            energyRecovery = energyRecovery,
            focusRecovery = focusRecovery,
            sleepQuality = sleepQuality,
            extractionLevel = extractionLevel,
            fragmentsChanged = fragmentsChanged,
        )
    }

    /**
     * Tune genome based on observed vitality trends.
     *
     * Adjusts sensitivity toward observed patterns, clamped to +/- 0.05 per sleep.
     * If a tank showed large movement (|trend| > 0.1), increase sensitivity slightly;
     * if it barely moved, decrease slightly. This makes the companion more responsive
     * to the emotional patterns that actually occur in conversation.
     */
    internal fun tuneGenome(genome: ClientGenome?, fingerprint: PhoneFingerprint): ClientGenome? {
        if (genome == null) return null
        if (fingerprint.vitalityTrends.isEmpty()) return genome

        val newSensitivity = genome.sensitivity.toMutableMap()
        for ((tank, trend) in fingerprint.vitalityTrends) {
            val current = newSensitivity[tank] ?: 1.0
            val adjustment = if (kotlin.math.abs(trend) > 0.1) 0.05 else -0.02
            newSensitivity[tank] = (current + adjustment).coerceIn(0.1, 3.0)
        }

        return genome.copy(sensitivity = newSensitivity)
    }

    /**
     * Compute sleep quality from event count and current energy level.
     *
     * Quality scales with:
     * - Fatigue bonus: how depleted energy is (more tired = better quality rest)
     * - Event factor: how much material the Forge has to work with (capped at 100 events)
     *
     * Returns 0.0-1.0.
     */
    internal fun computeSleepQuality(eventCount: Int, energy: Double): Double {
        val eventFactor = (eventCount.toDouble() / 100.0).coerceAtMost(1.0)
        val fatigueBonus = (1.0 - energy).coerceIn(0.0, 1.0)
        return (0.3 + 0.5 * fatigueBonus + 0.2 * eventFactor).coerceIn(0.0, 1.0)
    }

    /**
     * Count how many fragments were created or had their text changed.
     */
    private fun countChanges(old: List<ClientSoulFragment>, new: List<ClientSoulFragment>): Int {
        val oldMap = old.associateBy { it.id }
        return new.count { fragment ->
            val existing = oldMap[fragment.id]
            existing == null || existing.text != fragment.text
        }
    }
}
