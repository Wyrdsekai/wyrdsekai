package org.wyrdsekai.core.inference;

/**
 * Provides API keys for inference backends at request time.
 * Keys are resolved lazily — not stored in the router.
 * The Safe implements this; tests use a simple map.
 *
 * <p>This decouples the InferenceRouter from any specific credential storage
 * mechanism (The Safe, environment variables, config files, etc.).
 */
public interface ApiKeyProvider {

    /**
     * Get the API key for a named backend.
     *
     * @param backendName the backend name (e.g., "openai", "anthropic-cloud")
     * @return the API key, or null if no key configured for this backend
     */
    String getKey(String backendName);
}
