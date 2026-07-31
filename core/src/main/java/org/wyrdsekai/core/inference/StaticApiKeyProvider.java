package org.wyrdsekai.core.inference;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe API key provider backed by a {@link ConcurrentHashMap}.
 * Boot-time keys come from environment ({@link #fromEnvironment()}); runtime
 * mutations (OAuth callbacks, key chest unlocks) go through {@link #setKey}.
 *
 * <p>Used in two ways:
 * <ul>
 *   <li>At boot, {@code Main.java} loads {@code WYRDSEKAI_API_KEY_<BACKEND>}
 *       env vars into the provider and hands the instance to {@code InferenceRouter}.</li>
 *   <li>At runtime, {@code OpenRouterOAuthRoutes} writes the freshly-exchanged
 *       OpenRouter key here so subsequent inference calls pick it up without
 *       requiring a steward restart.</li>
 * </ul>
 *
 * <p>{@link InferenceRouter#withApiKeyIfNeeded} reads via {@link #getKey} on
 * every backend resolution, so mutations are observed on the next call.
 */
public final class StaticApiKeyProvider implements ApiKeyProvider {

    /**
     * Process-wide active provider. Set once by {@code Main} at boot so other
     * components (OAuth routes, key chest) can hot-install keys without
     * threading the reference through their constructors. Mirrors
     * {@code CapabilityRegistry.setActive}.
     */
    private static volatile StaticApiKeyProvider active;

    public static void setActive(StaticApiKeyProvider provider) { active = provider; }

    public static StaticApiKeyProvider getActive() { return active; }

    private final ConcurrentHashMap<String, String> keys;

    public StaticApiKeyProvider(Map<String, String> initial) {
        this.keys = new ConcurrentHashMap<>(initial);
    }

    public StaticApiKeyProvider() {
        this.keys = new ConcurrentHashMap<>();
    }

    @Override
    public String getKey(String backendName) {
        return keys.get(backendName);
    }

    /**
     * Install or replace a key at runtime. Used by OAuth callbacks and the
     * key chest to publish freshly-acquired credentials without a restart.
     * Passing a {@code null} or blank value removes the key.
     */
    public void setKey(String backendName, String value) {
        if (value == null || value.isBlank()) {
            keys.remove(backendName);
        } else {
            keys.put(backendName, value);
        }
    }

    /**
     * @return an unmodifiable snapshot of backend names that have keys configured.
     */
    public Set<String> configuredBackends() {
        return Collections.unmodifiableSet(keys.keySet());
    }

    /**
     * Create a provider from environment variables.
     * Scans for variables matching {@code WYRDSEKAI_API_KEY_<BACKEND_NAME>}
     * and normalizes the backend name to lowercase with hyphens replacing underscores.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code WYRDSEKAI_API_KEY_OPENAI=sk-xxx} → backend "openai"</li>
     *   <li>{@code WYRDSEKAI_API_KEY_ANTHROPIC_CLOUD=sk-xxx} → backend "anthropic-cloud"</li>
     * </ul>
     */
    public static StaticApiKeyProvider fromEnvironment() {
        return fromEnvironment(System.getenv());
    }

    /**
     * Create a provider from an explicit environment map (testable).
     */
    static StaticApiKeyProvider fromEnvironment(Map<String, String> env) {
        var prefix = "WYRDSEKAI_API_KEY_";
        var keys = new HashMap<String, String>();
        env.forEach((k, v) -> {
            if (k.startsWith(prefix) && v != null && !v.isBlank()) {
                var backendName = k.substring(prefix.length())
                        .toLowerCase()
                        .replace('_', '-');
                keys.put(backendName, v);
            }
        });
        return new StaticApiKeyProvider(keys);
    }
}
