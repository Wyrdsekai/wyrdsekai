package org.wyrdsekai.common.home;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * A pending request for a Grant ( grant-request / knock).
 *
 * <p>Created when an entity needs access to a resource but does not yet
 * hold the required Grant. The resource owner approves (→ issues a Grant),
 * denies (→ request closed), or lets it expire (→ request closed).</p>
 *
 * <p>Immutable: status/responded changes produce a new record.</p>
 */
public record GrantRequest(
    String id,
    String requester,        // DID of the asking entity
    String owner,            // DID of the resource owner (who must approve)
    ResourceUri resource,
    Capability capability,
    Map<String, Object> scope,
    String reason,           // human-readable justification
    Status status,
    Instant createdAt,
    Instant respondedAt,     // null while pending
    String responderNote,    // owner's note on approval/denial, optional
    String issuedGrantId     // on approval, the grant that was minted
) {

    public enum Status { pending, approved, denied, expired, cancelled }

    public GrantRequest {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id");
        if (requester == null) throw new IllegalArgumentException("requester");
        if (owner == null) throw new IllegalArgumentException("owner");
        if (resource == null) throw new IllegalArgumentException("resource");
        if (capability == null) throw new IllegalArgumentException("capability");
        if (scope == null) scope = Map.of();
        if (status == null) status = Status.pending;
        if (createdAt == null) createdAt = Instant.now();
    }

    public static GrantRequest create(String requester, String owner, ResourceUri resource,
                                       Capability capability, Map<String, Object> scope,
                                       String reason) {
        return new GrantRequest(
            UUID.randomUUID().toString(),
            requester, owner, resource, capability,
            scope == null ? Map.of() : scope,
            reason,
            Status.pending, Instant.now(), null, null, null);
    }

    public GrantRequest approved(String grantId, String note) {
        return new GrantRequest(id, requester, owner, resource, capability, scope,
            reason, Status.approved, createdAt, Instant.now(), note, grantId);
    }

    public GrantRequest denied(String note) {
        return new GrantRequest(id, requester, owner, resource, capability, scope,
            reason, Status.denied, createdAt, Instant.now(), note, null);
    }

    public GrantRequest cancelled() {
        return new GrantRequest(id, requester, owner, resource, capability, scope,
            reason, Status.cancelled, createdAt, Instant.now(), null, null);
    }

    public boolean isPending() { return status == Status.pending; }
    public boolean isResolved() { return status != Status.pending; }
}
