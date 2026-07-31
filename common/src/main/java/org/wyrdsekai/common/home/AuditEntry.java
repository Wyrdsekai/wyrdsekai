package org.wyrdsekai.common.home;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One append-only entry in a Home's audit log.
 *
 * <p>Every access decision, every grant lifecycle change, every resource
 * mutation should leave a trace here. Owners can read their own log
 * unconditionally; grantees with {@code read} on {@code audit-log} can query
 * slices. Entries are never edited; pruning is itself audited.</p>
 *
 * @param id           UUID string
 * @param homeOwner    DID of the Home whose log this belongs to
 * @param timestamp    when the event happened
 * @param actor        who did the thing (a DID, or {@code "system"} for internal)
 * @param verb         see {@link Verb} for canonical set
 * @param resource     string URI form; not required to parse at write time
 * @param outcome      {@link Outcome#ok} | {@code denied} | {@code error}
 * @param detail       verb-specific JSON payload (Map keyed by string)
 * @param correlation  optional id linking related entries (e.g. grant-issue + delegation)
 */
public record AuditEntry(
    String id,
    String homeOwner,
    Instant timestamp,
    String actor,
    String verb,
    String resource,
    Outcome outcome,
    Map<String, Object> detail,
    String correlation
) {

    public AuditEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(homeOwner, "homeOwner");
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(verb, "verb");
        Objects.requireNonNull(resource, "resource");
        if (outcome == null) outcome = Outcome.ok;
        if (detail == null) detail = Map.of();
    }

    /** Canonical verbs for audit entries. Callers may use others; these are what M1 writes. */
    public static final class Verb {
        public static final String GRANT_ISSUED     = "grant-issued";
        public static final String GRANT_REVOKED    = "grant-revoked";
        public static final String GRANT_EXPIRED    = "grant-expired";
        public static final String GRANT_REQUESTED  = "grant-requested";
        public static final String GRANT_APPROVED   = "grant-approved";
        public static final String GRANT_DENIED     = "grant-denied";
        public static final String ACCESS_GRANTED   = "access-granted";
        public static final String ACCESS_DENIED    = "access-denied";
        public static final String RESOURCE_CREATED = "resource-created";
        public static final String RESOURCE_MODIFIED = "resource-modified";
        public static final String RESOURCE_DELETED = "resource-deleted";
        public static final String HOME_ENTERED     = "home-entered";
        public static final String HOME_EXITED      = "home-exited";
        public static final String HOME_MIGRATED    = "home-migrated";
        public static final String HOME_SEALED      = "home-sealed";
        public static final String HOME_UNSEALED    = "home-unsealed";
        public static final String HOME_EJECT       = "home-eject";
        private Verb() {}
    }

    public enum Outcome { ok, denied, error }

    /** New entry with a generated id and current timestamp. */
    public static AuditEntry now(String homeOwner, String actor, String verb,
                                  String resource, Outcome outcome,
                                  Map<String, Object> detail, String correlation) {
        return new AuditEntry(
            UUID.randomUUID().toString(),
            homeOwner, Instant.now(), actor, verb, resource,
            outcome == null ? Outcome.ok : outcome,
            detail, correlation);
    }
}
