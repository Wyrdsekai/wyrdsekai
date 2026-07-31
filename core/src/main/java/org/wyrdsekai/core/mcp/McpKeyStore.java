package org.wyrdsekai.core.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * API key management for MCP services (§89.1).
 * Integrates with The Safe for secure key storage.
 *
 * Keys are:
 * - Stored encrypted in The Safe's threshold secret sharing system
 * - Cached in memory with configurable TTL
 * - Never written to disk outside The Safe
 * - Never exposed to agents (injected transparently by MCP Gateway)
 *
 * Only zone administrators can store/revoke keys.
 */
public class McpKeyStore {

    private static final Logger log = LoggerFactory.getLogger(McpKeyStore.class);

    /** In-memory key cache: safeKey → (value, expiry). */
    private final Map<String, CachedKey> cache = new ConcurrentHashMap<>();

    /** TTL for cached keys (default 5 minutes). */
    private final long cacheTtlMs;

    /** Backing store for keys. In production, delegates to TheSafe actor. */
    private final KeyBackend backend;

    /**
     * Backend abstraction for actual key storage.
     * Production: TheSafe actor messages.
     * Testing: in-memory map.
     */
    @FunctionalInterface
    public interface KeyBackend {
        /**
         * Retrieve a key value by its safe key identifier.
         * @return the key value, or null if not found
         */
        String getKey(String safeKey);
    }

    public McpKeyStore(KeyBackend backend) {
        this(backend, 5 * 60 * 1000); // 5 minute TTL
    }

    public McpKeyStore(KeyBackend backend, long cacheTtlMs) {
        this.backend = backend;
        this.cacheTtlMs = cacheTtlMs;
    }

    /**
     * Resolve the auth header value for a service config.
     * Uses the service's auth config to look up the key from The Safe.
     *
     * @param config Service configuration with auth details
     * @return Auth header value (e.g., "Bearer sk-xxx"), or null if no auth needed
     */
    public String resolveAuth(McpServiceConfig config) {
        if (!config.requiresAuth()) return null;

        var auth = config.auth();
        if (auth == null || auth.safeKey() == null) return null;

        String keyValue = getCachedOrFetch(auth.safeKey());
        if (keyValue == null) {
            log.warn("Key not found in The Safe: {} (service: {})", auth.safeKey(), config.id());
            return null;
        }

        // Format auth header based on type
        return switch (auth.type()) {
            case "bearer" -> "Bearer " + keyValue;
            case "api_key" -> keyValue;
            case "basic" -> "Basic " + Base64.getEncoder().encodeToString(keyValue.getBytes());
            default -> keyValue;
        };
    }

    /**
     * Check if a key exists for a service (without revealing the value).
     */
    public boolean hasKey(String safeKey) {
        return getCachedOrFetch(safeKey) != null;
    }

    /**
     * List all stored key identifiers (not values).
     */
    public Set<String> listKeyIds() {
        // In production, this would query The Safe.
        // For now, return cached key IDs.
        return Set.copyOf(cache.keySet());
    }

    /**
     * Invalidate cached key (e.g., after rotation or revocation).
     */
    public void invalidate(String safeKey) {
        cache.remove(safeKey);
        log.info("Invalidated cached key: {}", safeKey);
    }

    /** Clear all cached keys. */
    public void clearCache() {
        cache.clear();
    }

    private String getCachedOrFetch(String safeKey) {
        var cached = cache.get(safeKey);
        if (cached != null && !cached.isExpired()) {
            return cached.value;
        }

        // Fetch from backend
        String value = backend.getKey(safeKey);
        if (value != null) {
            cache.put(safeKey, new CachedKey(value, System.currentTimeMillis() + cacheTtlMs));
        }
        return value;
    }

    private record CachedKey(String value, long expiresAt) {
        boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }
}
