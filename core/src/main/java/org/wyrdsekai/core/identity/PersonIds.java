package org.wyrdsekai.core.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.config.WyrdConfig;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cached access to {@link PersonIdentityResolver} for hot paths.
 *
 * <p>The resolver already answers "which person is this identifier?" correctly, for DIDs,
 * legacy account UUIDs, usernames and display names, and explicitly never guesses. What
 * was missing was anyone calling it on the paths that decide whether a speaker is the
 * bondholder.
 *
 * <p>What that cost on the household node, found 2026-08-19. The steward's local
 * credential row already carried the mapping — {@code users.id=1f56a2d4-… →
 * users.did=did:key:z6Mktgd…} — but the SSH corridor presents the legacy UUID while the
 * phone presents the DID, and the comparisons were string equality against the DID:
 * <ul>
 *   <li>No {@code HEARD} turn was persisted for anything he said from the corridor since
 *       08-17 — his half of every conversation was silently discarded.</li>
 *   <li>{@code drainLonelinessOnInteraction} was told he was not the bondholder, so
 *       Loneliness and Saudade stayed pinned at 1.00 while he sat in the room with her,
 *       and her strongest want was to write to someone she missed.</li>
 *   <li>A second bond formed under the legacy id 22 seconds after the migration rewrote
 *       the first, and grew to ITEM depth while the person-DID bond stayed ACQUAINTANCE.
 *       She held two bonds with one man.</li>
 * </ul>
 *
 * <p>Resolution is cached because this sits on the speech path; unresolvable identifiers
 * are returned unchanged, since an unmigrated local user is still legitimately themselves.
 */
public final class PersonIds {

    private static final Logger log = LoggerFactory.getLogger(PersonIds.class);
    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();
    private static volatile PersonIdentityResolver resolver;

    private PersonIds() {}

    /** The person DID for any identifier, or the identifier unchanged if unresolvable. */
    public static String canonical(String identifier) {
        if (identifier == null || identifier.isBlank()) return identifier;
        return CACHE.computeIfAbsent(identifier, id -> {
            try {
                var r = resolverOrNull();
                if (r == null) return id;
                return r.resolve(id).map(did -> {
                    if (!did.equals(id)) {
                        log.info("Person identity: '{}' resolves to {}", id, did);
                    }
                    return did;
                }).orElse(id);
            } catch (Exception e) {
                log.debug("canonical('{}') failed, using as-is: {}", id, e.toString());
                return id;
            }
        });
    }

    /** Do these identifiers name the same person? Null-safe; null never matches. */
    public static boolean samePerson(String a, String b) {
        if (a == null || b == null) return false;
        if (a.equals(b)) return true;
        return canonical(a).equals(canonical(b));
    }

    private static PersonIdentityResolver resolverOrNull() {
        var r = resolver;
        if (r != null) return r;
        synchronized (PersonIds.class) {
            if (resolver == null) {
                var jdbc = System.getProperty("wyrdsekai.jdbc.url");
                if (jdbc == null || jdbc.isBlank()) {
                    var cfg = WyrdConfig.get();
                    jdbc = cfg == null ? null : cfg.jdbcUrl();
                }
                if (jdbc == null || jdbc.isBlank()) return null;
                resolver = new PersonIdentityResolver(jdbc);
            }
            return resolver;
        }
    }

    /** Test seam. */
    public static void resetForTesting(PersonIdentityResolver r) {
        synchronized (PersonIds.class) {
            resolver = r;
            CACHE.clear();
        }
    }
}
