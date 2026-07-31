package org.wyrdsekai.core.home;

/**
 * Hook for observing Home lifecycle events without polling the audit log.
 * Used to push notifications (knock arrived, your request was approved, a
 * grant expired) via {@code NotificationService}.
 *
 * <p>Implementations MUST be cheap — they fire on the HomeRegistry actor
 * thread. Long-running work should offload to another executor.</p>
 */
public interface HomeEventListener {

    enum Kind {
        GRANT_REQUESTED,
        GRANT_APPROVED,
        GRANT_DENIED,
        GRANT_ISSUED,
        GRANT_REVOKED,
        GRANT_EXPIRED,
        HOME_SEALED,
        HOME_UNSEALED
    }

    /**
     * @param kind       event category
     * @param ownerDid   DID of the Home this event concerns
     * @param actorDid   DID of who acted (may equal ownerDid for self-events)
     * @param subjectDid DID the event applies to (requester, grantee, ...); may be null
     * @param resource   home:// URI involved, may be null
     * @param detail     short human-readable summary
     */
    void onHomeEvent(Kind kind, String ownerDid, String actorDid,
                      String subjectDid, String resource, String detail);

    HomeEventListener NOOP = (a, b, c, d, e, f) -> {};
}
