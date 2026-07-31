package org.wyrdsekai.app.inference

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * API provider configuration — defines known providers, their endpoints,
 * OAuth flows, and help links for obtaining API keys.
 *
 * Loaded from a bundled JSON with optional remote update. This allows
 * adding/changing providers without an app release.
 *
 * Remote config URL: https://wyrdsekai.org/api/providers.json (future)
 */
@Serializable
data class ApiProviderConfig(
    val providers: List<ApiProvider>,
    val version: Int = 1,
)

@Serializable
data class ApiProvider(
    /** Unique ID: "openrouter", "anthropic", "openai", "custom". */
    val id: String,
    /** Display name: "OpenRouter", "Anthropic (Claude)", "OpenAI". */
    val name: String,
    /** Base URL for chat completions (OpenAI-compatible). */
    val baseUrl: String,
    /** Auth header format: "bearer" (Authorization: Bearer {key}) or "x-api-key" ({key}). */
    val authType: String = "bearer",
    /** OAuth PKCE auth URL, or null if manual key only. */
    val oauthUrl: String? = null,
    /** OAuth code exchange endpoint, or null. */
    val oauthExchangeUrl: String? = null,
    /** URL where user can manually get an API key. */
    val keyHelpUrl: String? = null,
    /** Short description shown during onboarding. */
    val description: String = "",
    /** Whether this is the recommended default. */
    val recommended: Boolean = false,
    /** Default model to use with this provider. */
    val defaultModel: String? = null,
)

object ApiProviderRegistry {

    private val json = Json { ignoreUnknownKeys = true }

    /** Bundled default config. Updated via remote fetch on boot. */
    private var config: ApiProviderConfig = bundledConfig()

    fun providers(): List<ApiProvider> = config.providers

    fun find(id: String): ApiProvider? = config.providers.find { it.id == id }

    fun recommended(): ApiProvider? = config.providers.find { it.recommended }

    /** Update config from remote JSON (call on boot, non-blocking). */
    fun update(jsonText: String) {
        try {
            config = json.decodeFromString<ApiProviderConfig>(jsonText)
        } catch (_: Exception) {
            // Keep bundled config on parse failure
        }
    }

    private fun bundledConfig() = ApiProviderConfig(
        providers = listOf(
            ApiProvider(
                id = "openrouter",
                name = "OpenRouter",
                baseUrl = "https://openrouter.ai/api",
                authType = "bearer",
                oauthUrl = "https://openrouter.ai/auth",
                oauthExchangeUrl = "https://openrouter.ai/api/v1/auth/keys",
                keyHelpUrl = "https://openrouter.ai/settings/keys",
                description = "Access Claude, GPT, Llama, Gemini — all providers through one account.",
                recommended = true,
                defaultModel = "anthropic/claude-sonnet-4",
            ),
            ApiProvider(
                id = "anthropic",
                name = "Anthropic (Claude)",
                baseUrl = "https://api.anthropic.com",
                authType = "x-api-key",
                keyHelpUrl = "https://console.anthropic.com/settings/keys",
                description = "Direct access to Claude models. Requires an Anthropic API key.",
                defaultModel = "claude-sonnet-4-6",
            ),
            ApiProvider(
                id = "openai",
                name = "OpenAI",
                baseUrl = "https://api.openai.com",
                authType = "bearer",
                keyHelpUrl = "https://platform.openai.com/api-keys",
                description = "Direct access to GPT models. Requires an OpenAI API key.",
                defaultModel = "gpt-4o",
            ),
            ApiProvider(
                id = "custom",
                name = "Custom Endpoint",
                baseUrl = "",
                authType = "bearer",
                description = "Any OpenAI-compatible API endpoint (Ollama, vLLM, etc.).",
            ),
        ),
    )
}
