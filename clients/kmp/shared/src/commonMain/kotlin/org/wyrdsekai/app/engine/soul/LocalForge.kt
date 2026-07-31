package org.wyrdsekai.app.engine.soul

import org.wyrdsekai.app.engine.agent.AgentProfile
import org.wyrdsekai.app.engine.agent.VitalityState

/**
 * Local Forge — serialize/restore agent state to/from soul manifests on phones.
 *
 * The Forge is where souls are crystallized into portable form. On the server,
 * this is a full event-sourced actor (ForgeActor). On phones, it's a simpler
 * stateless serializer that converts between runtime state and manifest JSON.
 *
 * Key constraint: phones use prompt injection only (no steering vectors),
 * retrieval k=1, and genome computation is pure arithmetic.
 */
object LocalForge {

    /**
     * Forge a soul manifest from current runtime state.
     *
     * @param did           Agent's decentralized identifier
     * @param publicKey     Ed25519 public key (multibase encoded)
     * @param version       Manifest version (increments each forge)
     * @param profile       Agent's profile (name, system prompt, params)
     * @param residentIdentity  MEDIUM soul text (~69 tokens, always in prompt)
     * @param vitality      Current vitality state (10 tanks)
     * @param fragments     Soul fragments for retrieval
     * @param genome        Tank genome (optional)
     * @param calibration   MirrorResonance calibration examples
     * @param relationships Social graph
     * @param retrievalK    Fragment retrieval count (1 for phone, 3 for 7B+)
     * @return Portable ClientSoulManifest
     */
    fun forge(
        did: String,
        publicKey: String,
        version: Int,
        profile: AgentProfile,
        residentIdentity: String,
        vitality: VitalityState,
        fragments: List<ClientSoulFragment> = emptyList(),
        genome: ClientGenome? = null,
        calibration: List<String> = emptyList(),
        relationships: List<ClientRelationship> = emptyList(),
        retrievalK: Int = 1,
    ): ClientSoulManifest {
        return ClientSoulManifest(
            did = did,
            publicKeyMultibase = publicKey,
            manifestVersion = version,
            forgedAt = currentTimeMillis(),
            agentName = profile.name,
            entityId = profile.entityId,
            residentIdentity = residentIdentity,
            systemPrompt = profile.systemPrompt,
            contextWindowTokens = profile.contextWindowTokens,
            maxResponseTokens = profile.maxResponseTokens,
            temperature = profile.temperature,
            genome = genome,
            mirrorCalibration = calibration,
            fragments = fragments,
            retrievalK = retrievalK,
            vitalityTanks = vitalityToTanks(vitality),
            relationships = relationships,
        )
    }

    /**
     * Restore an AgentProfile from a soul manifest.
     */
    fun restoreProfile(manifest: ClientSoulManifest): AgentProfile {
        return AgentProfile(
            name = manifest.agentName,
            entityId = manifest.entityId,
            entityType = "agent",
            description = "", // Not stored in manifest
            systemPrompt = manifest.systemPrompt,
            contextWindowTokens = manifest.contextWindowTokens,
            maxResponseTokens = manifest.maxResponseTokens,
            temperature = manifest.temperature,
        )
    }

    /**
     * Restore vitality state from a soul manifest (8 runtime tanks from 12 stored).
     */
    fun restoreVitality(manifest: ClientSoulManifest): VitalityState {
        val t = manifest.vitalityTanks
        return VitalityState(
            contextBudget = t["contextBudget"] ?: 0.5,
            confidence = t["confidence"] ?: 0.5,
            energy = t["energy"] ?: 1.0,
            alignment = t["alignment"] ?: 0.5,
            errorPressure = t["errorPressure"] ?: 0.0,
            momentum = t["momentum"] ?: 0.4,
            rapport = t["rapport"] ?: 0.5,
            focus = t["focus"] ?: 0.5,
        )
    }

    /**
     * Find the best matching fragments for a given input (keyword match for phones).
     * On phones without embedding models, we do simple keyword overlap.
     *
     * @param input     User's message or context
     * @param fragments All available fragments
     * @param k         Number of fragments to retrieve
     * @return Top-k matching fragments
     */
    fun retrieveFragments(
        input: String,
        fragments: List<ClientSoulFragment>,
        k: Int = 1,
    ): List<ClientSoulFragment> {
        if (fragments.isEmpty() || k <= 0) return emptyList()

        val inputWords = input.lowercase().split(Regex("\\W+")).filter { it.length > 3 }.toSet()
        if (inputWords.isEmpty()) return fragments.take(k)

        return fragments
            .map { fragment ->
                val textWords = fragment.text.lowercase().split(Regex("\\W+")).toSet()
                val keywordOverlap = fragment.keywords.count { it.lowercase() in inputWords }
                val textOverlap = inputWords.count { it in textWords }
                val formativeBonus = if (fragment.formative) 2 else 0
                val score = keywordOverlap * 3 + textOverlap + formativeBonus
                fragment to score
            }
            .sortedByDescending { it.second }
            .take(k)
            .map { it.first }
    }

    /** Convert 8-tank VitalityState to 12-tank map. */
    private fun vitalityToTanks(v: VitalityState): Map<String, Double> {
        return mapOf(
            "contextBudget" to v.contextBudget,
            "confidence" to v.confidence,
            "energy" to v.energy,
            "alignment" to v.alignment,
            "errorPressure" to v.errorPressure,
            "momentum" to v.momentum,
            "rapport" to v.rapport,
            "focus" to v.focus,
            // New 4 tanks default to moderate
            "valence" to 0.5,
            "safety" to 0.6,
            "resonance" to 0.5,
            "curiosity" to 0.5,
        )
    }

    private fun currentTimeMillis(): Long =
        kotlin.time.Clock.System.now().toEpochMilliseconds()
}
