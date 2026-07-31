package org.wyrdsekai.app.network

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.wyrdsekai.app.engine.soul.ClientSoulManifest

/**
 * Version history entry returned by the server.
 */
@Serializable
data class VersionEntry(
    val version: Int,
    val forgedAt: Long,
    val agentName: String,
    val did: String,
)

/**
 * Server response after syncing a manifest.
 */
@Serializable
data class SyncResponse(
    val did: String,
    val version: Int,
    val accepted: Boolean,
    val message: String = "",
)

/**
 * HTTP client for the server-side soul REST API.
 *
 * Endpoints:
 * - GET  /api/soul/{did}                   — latest manifest
 * - GET  /api/soul/{did}/history           — version list
 * - GET  /api/soul/{did}/version/{version} — specific version
 * - POST /api/soul/{did}                   — sync (upload) manifest
 *
 * Authentication is via query parameter `?token=...`.
 *
 * Follows the same Ktor + ContentNegotiation pattern as [AuthClient].
 */
class SoulClient(baseUrl: String) {
    private val normalizedUrl = normalizeHttpUrl(baseUrl)
    private val http = HttpClient {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    /**
     * Fetch the latest soul manifest for the given DID.
     */
    suspend fun getLatest(did: String, token: String): Result<ClientSoulManifest> =
        runCatching {
            http.get(soulUrl(did)) {
                parameter("token", token)
            }.body()
        }

    /**
     * Fetch the version history for the given DID.
     */
    suspend fun getHistory(did: String, token: String): Result<List<VersionEntry>> =
        runCatching {
            http.get("${soulUrl(did)}/history") {
                parameter("token", token)
            }.body()
        }

    /**
     * Fetch a specific manifest version for the given DID.
     */
    suspend fun getVersion(did: String, version: Int, token: String): Result<ClientSoulManifest> =
        runCatching {
            http.get("${soulUrl(did)}/version/$version") {
                parameter("token", token)
            }.body()
        }

    /**
     * Sync (upload) a manifest to the server. The server persists this as the
     * latest version and returns a [SyncResponse] indicating acceptance.
     */
    suspend fun syncManifest(did: String, manifest: ClientSoulManifest, token: String): Result<SyncResponse> =
        runCatching {
            http.post(soulUrl(did)) {
                contentType(ContentType.Application.Json)
                parameter("token", token)
                setBody(manifest)
            }.body()
        }

    fun close() {
        http.close()
    }

    private fun soulUrl(did: String): String =
        "$normalizedUrl/api/soul/${did.encodeURLPath()}"
}
