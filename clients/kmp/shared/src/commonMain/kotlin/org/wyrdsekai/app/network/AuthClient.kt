package org.wyrdsekai.app.network

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class AuthResponse(
    val token: String,
    @kotlinx.serialization.SerialName("userId")
    val user_id: String,
    val username: String,
    val role: String = "user",
)

@Serializable
data class UserInfo(
    val user_id: String,
    val username: String,
    val display_name: String?,
)

@Serializable
data class ServerStatus(
    @kotlinx.serialization.SerialName("hasUsers")
    val has_users: Boolean,
    @kotlinx.serialization.SerialName("openRegistration")
    val open_registration: Boolean = true,
)

@Serializable
data class LinkDeviceResponse(
    val linked: Boolean = true,
)

/** Auto-prepend http:// if no scheme given. */
internal fun normalizeHttpUrl(url: String): String {
    val trimmed = url.trim().trimEnd('/')
    return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed
    else "http://$trimmed"
}

class AuthClient(baseUrl: String) {
    private val normalizedUrl = normalizeHttpUrl(baseUrl)
    private val http = HttpClient {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    suspend fun register(username: String, password: String, displayName: String): Result<AuthResponse> =
        runCatching {
            val response = http.post("$normalizedUrl/api/auth/register") {
                contentType(ContentType.Application.Json)
                setBody(mapOf(
                    "username" to username,
                    "password" to password,
                    "display_name" to displayName,
                ))
            }
            if (!response.status.isSuccess()) {
                val body = response.bodyAsText()
                throw IllegalStateException(
                    try { Json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.content ?: body }
                    catch (_: Exception) { body }
                )
            }
            response.body()
        }

    suspend fun login(username: String, password: String): Result<AuthResponse> =
        runCatching {
            val response = http.post("$normalizedUrl/api/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(mapOf(
                    "username" to username,
                    "password" to password,
                ))
            }
            if (!response.status.isSuccess()) {
                val body = response.bodyAsText()
                throw IllegalStateException(
                    try { Json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.content ?: body }
                    catch (_: Exception) { body }
                )
            }
            response.body()
        }

    suspend fun me(token: String): Result<UserInfo> =
        runCatching {
            http.get("$normalizedUrl/api/auth/me") {
                parameter("token", token)
            }.body()
        }

    suspend fun checkStatus(): Result<ServerStatus> =
        runCatching {
            http.get("$normalizedUrl/api/auth/status").body()
        }

    suspend fun linkDevice(authToken: String, deviceToken: String): Result<LinkDeviceResponse> =
        runCatching {
            http.post("$normalizedUrl/api/auth/link-device") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $authToken")
                setBody(mapOf("deviceToken" to deviceToken))
            }.body()
        }

    fun close() {
        http.close()
    }
}
