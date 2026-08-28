package org.wyrdsekai.app.network

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object PairingClient {
    private val http = HttpClient {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    @Serializable
    data class PairingChallenge(val challengeId: String, val expiresIn: Int)

    @Serializable
    data class PairingCredentials(
        val token: String,
        val householdId: String,
        val householdName: String,
        val serverDid: String,
        val natsUrl: String,
        val serverUrl: String,
        val relayUrl: String? = null,
        val relayToken: String? = null,
    )

    /** Request pairing with a server. Returns challengeId or null on error. */
    suspend fun requestPairing(
        serverUrl: String,
        deviceName: String,
        deviceType: String,
    ): PairingChallenge? {
        return try {
            val url = normalizeHttpUrl(serverUrl)
            val resp = http.post("$url/api/pair/request") {
                contentType(ContentType.Application.Json)
                setBody(mapOf("deviceName" to deviceName, "deviceType" to deviceType))
            }
            if (resp.status.value in 200..299) resp.body() else null
        } catch (_: Exception) { null }
    }

    /** Verify a pairing code. Returns credentials or null. */
    suspend fun verifyCode(
        serverUrl: String,
        challengeId: String,
        code: String,
    ): PairingCredentials? {
        return try {
            val url = normalizeHttpUrl(serverUrl)
            val resp = http.post("$url/api/pair/verify") {
                contentType(ContentType.Application.Json)
                setBody(mapOf("challengeId" to challengeId, "code" to code))
            }
            if (resp.status.value in 200..299) resp.body() else null
        } catch (_: Exception) { null }
    }

    /**
     * Mint this device's identity through an authenticated session — the
     * hermod consent path (many doors, one identity: same wyrd_dev_ token
     * and registry row as the code ceremony, no ceremony). Idempotent
     * server-side. Returns credentials or null.
     */
    suspend fun pairSelf(
        serverUrl: String,
        sessionToken: String,
        deviceName: String,
        deviceType: String = "phone",
    ): PairingCredentials? {
        return try {
            val url = normalizeHttpUrl(serverUrl)
            val resp = http.post("$url/api/pair/device") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $sessionToken")
                setBody(mapOf("deviceName" to deviceName, "deviceType" to deviceType))
            }
            if (resp.status.value in 200..299) resp.body() else null
        } catch (_: Exception) { null }
    }

    /** Check if a device token is still valid. */
    suspend fun checkStatus(serverUrl: String, token: String): Boolean {
        return try {
            val url = normalizeHttpUrl(serverUrl)
            val resp = http.get("$url/api/pair/status") {
                header("Authorization", "Bearer $token")
            }
            resp.status.value in 200..299
        } catch (_: Exception) { false }
    }
}
