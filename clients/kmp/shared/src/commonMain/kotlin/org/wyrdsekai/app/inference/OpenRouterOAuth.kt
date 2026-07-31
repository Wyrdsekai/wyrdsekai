package org.wyrdsekai.app.inference

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random
import org.wyrdsekai.app.platform.percentEncode
import org.wyrdsekai.app.platform.sha256
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * OpenRouter OAuth PKCE flow for automated API key acquisition.
 *
 * Flow:
 * 1. Generate code_verifier + code_challenge (S256)
 * 2. Open browser to openrouter.ai/auth?callback_url=...&code_challenge=...
 * 3. User logs in, redirected back with ?code=AUTH_CODE
 * 4. Exchange code for API key via POST /api/v1/auth/keys
 *
 * Callback constraint: HTTPS on port 443 or 3000, or http://localhost:3000
 */
object OpenRouterOAuth {

    private const val AUTH_URL = "https://openrouter.ai/auth"
    private const val EXCHANGE_URL = "https://openrouter.ai/api/v1/auth/keys"

    /**
     * Default callback URL for phone clients. OpenRouter only permits
     * https:443, https:3000, or http://localhost:3000 — no custom URI
     * schemes. We use the loopback variant and intercept the redirect
     * inside our in-app WebView before it actually tries to reach
     * localhost. See OpenRouterAuthScreen.
     */
    const val LOOPBACK_CALLBACK = "http://localhost:3000/callback"

    private val json = Json { ignoreUnknownKeys = true }

    /** PKCE state — held between redirect and callback. */
    data class PkceState(
        val codeVerifier: String,
        val codeChallenge: String,
    )

    private var pendingState: PkceState? = null

    /**
     * Build the authorization URL to open in the browser.
     * @param callbackUrl Where OpenRouter redirects after auth (e.g. "https://yourapp.com/callback")
     * @return The full URL to open, and the PKCE state to hold for exchange.
     */
    fun buildAuthUrl(callbackUrl: String): Pair<String, PkceState> {
        val verifier = generateCodeVerifier()
        val challenge = computeS256Challenge(verifier)
        val state = PkceState(verifier, challenge)
        pendingState = state

        val url = "$AUTH_URL?callback_url=${encodeUrl(callbackUrl)}" +
            "&code_challenge=$challenge" +
            "&code_challenge_method=S256"

        return url to state
    }

    /**
     * Exchange the authorization code for an API key.
     * @param code The code from the callback URL query parameter.
     * @param pkceState The PKCE state from buildAuthUrl.
     * @return The API key string, or null on failure.
     */
    suspend fun exchangeCode(code: String, pkceState: PkceState? = pendingState): OAuthResult {
        val state = pkceState ?: return OAuthResult(null, "No PKCE state — did you call buildAuthUrl first?")

        val client = HttpClient()
        return try {
            val response = client.post(EXCHANGE_URL) {
                contentType(ContentType.Application.Json)
                setBody("""{"code":"$code","code_verifier":"${state.codeVerifier}","code_challenge_method":"S256"}""")
            }

            if (response.status.value == 200) {
                val body = json.decodeFromString<ExchangeResponse>(response.bodyAsText())
                pendingState = null
                OAuthResult(body.key, null)
            } else {
                val body = response.bodyAsText()
                OAuthResult(null, "Exchange failed (${response.status.value}): $body")
            }
        } catch (e: Exception) {
            OAuthResult(null, "Exchange error: ${e.message}")
        } finally {
            client.close()
        }
    }

    data class OAuthResult(val key: String?, val error: String?)

    @Serializable
    private data class ExchangeResponse(val key: String? = null)

    // ── PKCE helpers ─────────────────────────────────────────────────

    private fun generateCodeVerifier(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        return (1..64).map { chars[Random.nextInt(chars.length)] }.joinToString("")
    }

    /**
     * S256 challenge: base64url(sha256(verifier)).
     * Uses platform expect/actual for SHA-256 — falls back to plain if unavailable.
     */
    private fun computeS256Challenge(verifier: String): String {
        return try {
            base64UrlEncode(sha256(verifier.encodeToByteArray()))
        } catch (_: Exception) {
            // Fallback: plain (less secure but functional)
            verifier
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun base64UrlEncode(bytes: ByteArray): String =
        Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(bytes)

    private fun encodeUrl(url: String): String = percentEncode(url)
}
