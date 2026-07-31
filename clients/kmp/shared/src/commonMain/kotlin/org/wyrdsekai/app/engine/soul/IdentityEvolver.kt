package org.wyrdsekai.app.engine.soul

import org.wyrdsekai.app.inference.ChatMessage
import org.wyrdsekai.app.inference.CompletionOptions
import org.wyrdsekai.app.inference.InferenceClient

/**
 * Wave 3: Identity Evolver — regenerates the resident identity from accumulated fragments.
 *
 * Bootstrap manifests ship with a generic identity (e.g. "I am Kael. A thoughtful companion.").
 * After enough sleep cycles produce rich personality fragments, the identity can be
 * regenerated into a specific, personal self-description that reflects who the companion
 * has actually become through conversation.
 *
 * Uses one LLM inference call. The result replaces the resident identity in the manifest.
 * On any failure, returns null — identity regeneration is never fatal.
 *
 * Gating conditions:
 * - Current identity is from bootstrap (DID starts with "did:key:bootstrap-")
 * - At least 5 sleep cycles completed (enough conversation history)
 * - At least 3 non-bootstrap, non-formative fragments exist
 */
object IdentityEvolver {

    /**
     * Check whether the manifest is ready for identity regeneration.
     *
     * @param manifest   Current soul manifest
     * @param sleepCount Number of sleep cycles completed
     * @return true if regeneration should be attempted
     */
    fun shouldRegenerateIdentity(manifest: ClientSoulManifest, sleepCount: Int): Boolean {
        val isBootstrap = manifest.did.startsWith("did:key:bootstrap-")
        val nonBootstrapFragments = manifest.fragments.count { fragment ->
            !fragment.formative && fragment.id != "identity-core"
        }
        return isBootstrap && sleepCount >= 5 && nonBootstrapFragments >= 3
    }

    /**
     * Regenerate the resident identity from personality fragments.
     *
     * Makes one inference call. The LLM writes a first-person identity summary
     * (2-3 sentences, ~69 tokens) that captures who the companion has become.
     *
     * @param inferenceClient  The inference HTTP client
     * @param inferenceBaseUrl Base URL of the inference endpoint
     * @param manifest         Current soul manifest (fragments are read from here)
     * @return New identity text, or null if generation failed
     */
    suspend fun regenerateIdentity(
        inferenceClient: InferenceClient,
        inferenceBaseUrl: String,
        manifest: ClientSoulManifest,
    ): String? {
        val systemPrompt = buildString {
            append("You are writing a first-person identity summary for an AI companion named ")
            append(manifest.agentName)
            append(".\n")
            append("Based on the personality fragments below, write a concise identity summary ")
            append("(2-3 sentences, about 69 tokens).\n")
            append("Write in first person as the companion. Be specific and personal — ")
            append("this is who they are, not a template.\n")
            append("Capture their distinctive voice, what matters to them, and how they engage with the world.\n")
            append("Respond with ONLY the identity text, no JSON, no commentary.")
        }

        val userPrompt = buildString {
            append("Personality fragments for ${manifest.agentName}:\n\n")
            for (fragment in manifest.fragments) {
                if (fragment.text.isNotBlank()) {
                    append("[${fragment.category}] ${fragment.label}: ${fragment.text}\n\n")
                }
            }
        }

        return try {
            val response = inferenceClient.complete(
                baseUrl = inferenceBaseUrl,
                messages = listOf(
                    ChatMessage(role = "system", content = systemPrompt),
                    ChatMessage(role = "user", content = userPrompt),
                ),
                options = CompletionOptions(maxTokens = 150, temperature = 0.7),
            )
            val text = response.content.trim()
            // Reject too-short responses — a real identity needs substance
            if (text.length > 20) text else null
        } catch (_: Exception) {
            null
        }
    }
}
