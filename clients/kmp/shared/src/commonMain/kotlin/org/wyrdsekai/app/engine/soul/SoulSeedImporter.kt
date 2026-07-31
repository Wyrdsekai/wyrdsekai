package org.wyrdsekai.app.engine.soul

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Soul Seed Importer — Wave 5 of Phone Forge plan.
 *
 * Supports two import paths:
 *   1. Local JSON file: parse a .soul.json file into a ClientSoulManifest.
 *   2. Household server: fetch available souls from GET /api/soul/list and
 *      download a specific soul by DID from GET /api/soul/{did}.
 *
 * All methods are null-safe: network or parse errors return null / empty list.
 */
object SoulSeedImporter {

    private val json = Json { ignoreUnknownKeys = true }

    private val http = HttpClient {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    // -----------------------------------------------------------------------
    // Local import
    // -----------------------------------------------------------------------

    /**
     * Import a soul manifest from a JSON string (.soul.json format).
     * Returns null on any parse error or if required fields are missing.
     */
    fun importFromJson(jsonString: String): ClientSoulManifest? {
        return try {
            val manifest = json.decodeFromString<ClientSoulManifest>(jsonString)
            if (validateManifest(manifest)) manifest else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Validate that a manifest has the minimum required fields for use
     * as a soul seed. We need identity, name, and prompt text at minimum.
     */
    fun validateManifest(manifest: ClientSoulManifest): Boolean {
        return manifest.did.isNotBlank() &&
            manifest.agentName.isNotBlank() &&
            manifest.residentIdentity.isNotBlank() &&
            manifest.systemPrompt.isNotBlank()
    }

    // -----------------------------------------------------------------------
    // Export
    // -----------------------------------------------------------------------

    /**
     * Export a soul manifest as a JSON string (.soul.json format).
     * The string can be shared, saved to a file, or pasted into another device's import.
     */
    fun exportToJson(manifest: ClientSoulManifest): String {
        return json.encodeToString(ClientSoulManifest.serializer(), manifest)
    }

    // -----------------------------------------------------------------------
    // Household server import
    // -----------------------------------------------------------------------

    /**
     * Fetch the list of available souls from a household server.
     *
     * Calls `GET {serverUrl}/api/soul/list?token={token}`.
     * Returns an empty list on any error (network, parse, auth).
     */
    suspend fun fetchHouseholdSouls(
        serverUrl: String,
        token: String? = null,
    ): List<SoulListEntry> {
        return try {
            http.get("${normalizeUrl(serverUrl)}/api/soul/list") {
                if (token != null) parameter("token", token)
            }.body()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Import a specific soul manifest from a household server by DID.
     *
     * Calls `GET {serverUrl}/api/soul/{did}?token={token}`.
     * Returns null on any error (network, parse, auth, 404).
     */
    suspend fun importFromHousehold(
        serverUrl: String,
        did: String,
        token: String? = null,
    ): ClientSoulManifest? {
        return try {
            http.get("${normalizeUrl(serverUrl)}/api/soul/${did}") {
                if (token != null) parameter("token", token)
            }.body()
        } catch (_: Exception) {
            null
        }
    }

    private fun normalizeUrl(url: String): String {
        val trimmed = url.trim().trimEnd('/')
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed
        else "http://$trimmed"
    }
}

/**
 * Lightweight entry for the soul list endpoint — just enough metadata
 * to let the user pick which soul to import without downloading full manifests.
 */
@Serializable
data class SoulListEntry(
    val did: String,
    val agentName: String,
    val manifestVersion: Int,
    val forgedAt: Long,
)
