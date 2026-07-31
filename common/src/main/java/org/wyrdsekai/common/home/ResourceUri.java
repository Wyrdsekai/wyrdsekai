package org.wyrdsekai.common.home;

import java.util.Objects;

/**
 * Parsed {@code home://{owner-did}/{type}/{id...}} URI.
 *
 * <p>Every resource in an internal design note has a stable address of this form. The owner
 * is a DID string; the type is a resource-type name registered in the
 * ResourceTypeRegistry; the id is type-specific and may contain additional
 * slashes (e.g. {@code home://alice/mcp/github/issue_create}).</p>
 *
 * <p>Examples:
 * <ul>
 *   <li>{@code home://alice/journal/entry-42}</li>
 *   <li>{@code home://alice/collection/library-notes}</li>
 *   <li>{@code home://wyrd/memory/episodic}</li>
 *   <li>{@code home://alpha-steward/inference-budget} — no id, type-only</li>
 *   <li>{@code home://alice/home-room} — no id, the Home's physical room</li>
 * </ul>
 * </p>
 */
public record ResourceUri(String owner, String type, String id) {

    private static final String SCHEME = "home://";

    public ResourceUri {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(type, "type");
        if (owner.isBlank()) throw new IllegalArgumentException("owner must not be blank");
        if (type.isBlank()) throw new IllegalArgumentException("type must not be blank");
        // id may be null for type-only resources (inference-budget, home-room, audit-log)
    }

    /** Parse a home-URI. Throws {@link IllegalArgumentException} on malformed input. */
    public static ResourceUri parse(String uri) {
        if (uri == null || !uri.startsWith(SCHEME)) {
            throw new IllegalArgumentException("not a home URI: " + uri);
        }
        var rest = uri.substring(SCHEME.length());
        var slash1 = rest.indexOf('/');
        if (slash1 < 0) {
            throw new IllegalArgumentException("missing type in URI: " + uri);
        }
        var owner = rest.substring(0, slash1);
        var afterOwner = rest.substring(slash1 + 1);
        var slash2 = afterOwner.indexOf('/');
        if (slash2 < 0) {
            // type-only: home://alice/home-room
            return new ResourceUri(owner, afterOwner, null);
        }
        var type = afterOwner.substring(0, slash2);
        var id = afterOwner.substring(slash2 + 1);
        return new ResourceUri(owner, type, id.isEmpty() ? null : id);
    }

    /** Nullable variant — returns {@code null} on parse failure. */
    public static ResourceUri parseOrNull(String uri) {
        try { return parse(uri); } catch (IllegalArgumentException e) { return null; }
    }

    @Override public String toString() {
        if (id == null) return SCHEME + owner + "/" + type;
        return SCHEME + owner + "/" + type + "/" + id;
    }

    /** Convenience builder for type-only URIs (no id). */
    public static ResourceUri of(String owner, String type) {
        return new ResourceUri(owner, type, null);
    }

    /** Convenience builder for typed-with-id URIs. */
    public static ResourceUri of(String owner, String type, String id) {
        return new ResourceUri(owner, type, id);
    }
}
