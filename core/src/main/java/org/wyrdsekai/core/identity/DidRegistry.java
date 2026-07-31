package org.wyrdsekai.core.identity;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-zone DID resolution service (§18).
 * Maps did:wyrd URIs to public keys and metadata.
 */
public class DidRegistry {

    /** A DID document entry. */
    public record DidDocument(
        String didUri,
        byte[] publicKey,
        String entityType,       // "human", "agent", "service"
        Instant registeredAt,
        Instant lastSeen,
        boolean active
    ) {}

    private final Map<String, DidDocument> documents = new ConcurrentHashMap<>();

    /** Register a new DID. Returns false if already registered. */
    public boolean register(DidWyrd did, byte[] publicKey, String entityType) {
        var uri = did.toUri();
        if (documents.containsKey(uri)) return false;
        documents.put(uri, new DidDocument(uri, publicKey, entityType,
            Instant.now(), Instant.now(), true));
        return true;
    }

    /** Resolve a DID to its document. */
    public Optional<DidDocument> resolve(String didUri) {
        return Optional.ofNullable(documents.get(didUri))
            .filter(DidDocument::active);
    }

    /** Resolve a DID to its public key. */
    public Optional<byte[]> resolvePublicKey(String didUri) {
        return resolve(didUri).map(DidDocument::publicKey);
    }

    /** Deactivate a DID (soft delete). */
    public boolean deactivate(String didUri) {
        var doc = documents.get(didUri);
        if (doc == null) return false;
        documents.put(didUri, new DidDocument(doc.didUri(), doc.publicKey(),
            doc.entityType(), doc.registeredAt(), doc.lastSeen(), false));
        return true;
    }

    /** Update last-seen timestamp. */
    public void touch(String didUri) {
        var doc = documents.get(didUri);
        if (doc != null) {
            documents.put(didUri, new DidDocument(doc.didUri(), doc.publicKey(),
                doc.entityType(), doc.registeredAt(), Instant.now(), doc.active()));
        }
    }

    /** List all active DIDs. */
    public List<DidDocument> listActive() {
        return documents.values().stream()
            .filter(DidDocument::active)
            .sorted(Comparator.comparing(DidDocument::registeredAt))
            .toList();
    }

    /** Number of registered DIDs (including inactive). */
    public int size() { return documents.size(); }

    /** Number of active DIDs. */
    public int activeCount() {
        return (int) documents.values().stream().filter(DidDocument::active).count();
    }
}
