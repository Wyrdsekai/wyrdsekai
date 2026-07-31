package org.wyrdsekai.app.engine.soul

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Client-side soul manifest — the portable agent identity for phones/web.
 *
 * This is a lightweight mirror of the server's SoulManifest (4-layer model)
 * adapted for constrained devices:
 * - Layer D: Identity (DID, public key, manifest version)
 * - Layer A: Profile + resident identity (MEDIUM soul text, ~69 tokens)
 * - Layer A.5: Genome (12-tank sensitivity/coupling/decay configuration)
 * - Layer B: Memory fragments (for retrieval — k=1 on phone, k=3 on 7B+)
 * - Layer C: Vitality snapshot (12 tanks)
 *
 * Phone-specific constraints (from experiments):
 * - Prompt injection only (no steering vectors — Exp 16: steering hurts at 3B)
 * - Retrieval k=1 (single most relevant fragment per turn)
 * - Genome computation is pure arithmetic (no LLM needed)
 * - MirrorResonance calibration examples included for emotional charge detection
 */
@Serializable
data class ClientSoulManifest(
    // Layer D: Identity
    val did: String,
    val publicKeyMultibase: String,
    val manifestVersion: Int,
    val forgedAt: Long, // epoch millis

    // Layer A: Profile
    val agentName: String,
    val entityId: String,
    val residentIdentity: String, // MEDIUM soul text (~69 tokens)
    val systemPrompt: String,
    val contextWindowTokens: Int,
    val maxResponseTokens: Int,
    val temperature: Double,

    // Layer A.5: Genome
    val genome: ClientGenome? = null,
    val mirrorCalibration: List<String> = emptyList(),

    // Layer B: Memory fragments (for semantic retrieval)
    val fragments: List<ClientSoulFragment> = emptyList(),
    val retrievalK: Int = 1, // k=1 for phone, k=3 for 7B+

    // Layer C: Vitality (12 tanks)
    val vitalityTanks: Map<String, Double> = emptyMap(),

    // Relationships (social graph)
    val relationships: List<ClientRelationship> = emptyList(),
) {
    companion object {
        private val json = Json {
            prettyPrint = false
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun fromJson(jsonString: String): ClientSoulManifest =
            json.decodeFromString(jsonString)
    }

    fun toJson(): String = json.encodeToString(this)
}

/**
 * Soul fragment for retrieval (mirrors server's SoulFragment).
 * Embedding is optional — phones may use keyword matching instead.
 */
@Serializable
data class ClientSoulFragment(
    val id: String,
    val category: String, // personality, memory, values, style, relationships
    val label: String,
    val text: String,
    val keywords: List<String> = emptyList(),
    val formative: Boolean = false,
)

/**
 * Client-side genome — 12-tank personality configuration.
 * Pure arithmetic: dTi/dt = sensitivity_i(inputs) + Σ_j(coupling_ij * Tj) + decay_i(baseline_i - Ti)
 */
@Serializable
data class ClientGenome(
    val name: String,
    val sensitivity: Map<String, Double> = emptyMap(),
    val coupling: Map<String, Double> = emptyMap(),
    val baselines: Map<String, Double> = emptyMap(),
    val decayRates: Map<String, Double> = emptyMap(),
)

/**
 * Simplified relationship for the client.
 */
@Serializable
data class ClientRelationship(
    val entityDid: String,
    val entityName: String,
    val trust: Double = 0.5,
    val rapport: Double = 0.3,
    val bondDepth: Int = 0,
    val summary: String = "",
)
