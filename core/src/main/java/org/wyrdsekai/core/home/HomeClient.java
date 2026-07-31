package org.wyrdsekai.core.home;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.javadsl.AskPattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.home.AuditEntry;
import org.wyrdsekai.common.home.Capability;
import org.wyrdsekai.common.home.Grant;
import org.wyrdsekai.common.home.GrantRequest;
import org.wyrdsekai.common.home.ResourceUri;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Synchronous wrapper around {@link HomeRegistryActor} for call-sites that
 * need authorization answers in-line (HTTP handlers, service methods). The
 * actor is the source of truth; this class only packages the ask pattern
 * so non-actor callers don't have to.
 *
 * <p>Timeouts are short by design — if the registry isn't responding in a
 * few seconds, something is very wrong and fail-closed is better than
 * blocking user operations.</p>
 */
public final class HomeClient {

    private static final Logger log = LoggerFactory.getLogger(HomeClient.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final ActorRef<HomeRegistryActor.Command> registry;
    private final ActorSystem<?> system;

    public HomeClient(ActorRef<HomeRegistryActor.Command> registry, ActorSystem<?> system) {
        this.registry = registry;
        this.system = system;
    }

    /** Underlying actor ref — exposed for callers that need to issue bespoke queries. */
    public ActorRef<HomeRegistryActor.Command> registry() { return registry; }

    /**
     * Check whether {@code subject} holds a valid grant on the resource with the
     * given capability. Fail-closed — any ask error returns {@code false}.
     */
    public boolean check(String subject, ResourceUri resource, Capability capability,
                          Map<String, Object> scope) {
        try {
            var decision = ask(replyTo -> new HomeRegistryActor.CheckAccess(
                subject, resource, capability, scope == null ? Map.of() : scope, replyTo),
                HomeRegistryActor.AccessDecision.class);
            return decision instanceof HomeRegistryActor.Allow;
        } catch (Exception e) {
            log.warn("CheckAccess failed for subject={} resource={} cap={}: {}",
                subject, resource, capability, e.getMessage());
            return false;
        }
    }

    /** Issue a new grant. Throws on validation failure. */
    public Grant issue(Grant grant) {
        var result = ask(replyTo -> new HomeRegistryActor.IssueGrant(grant, replyTo),
            HomeRegistryActor.IssueResult.class);
        if (result instanceof HomeRegistryActor.Issued ok) return ok.grant();
        throw new IllegalArgumentException(((HomeRegistryActor.IssueError) result).reason());
    }

    /** Issue or replace: if an identical grant (same resource, subject, capability) exists, revoke + re-issue. */
    public Grant issueOrReplace(String issuer, String subject, ResourceUri resource,
                                 Capability capability, Map<String, Object> scope,
                                 Instant expiresAt, String reason) {
        // Find any existing active grant with the same (issuer, subject, resource, capability).
        var existing = listIssuedBy(issuer).stream()
            .filter(g -> g.subject().equals(subject))
            .filter(g -> g.resource().toString().equals(resource.toString()))
            .filter(g -> g.capability() == capability)
            .filter(g -> g.isActive(Instant.now()))
            .findFirst();
        existing.ifPresent(g -> revoke(g.id(), issuer));
        return issue(Grant.issue(issuer, subject, resource, capability,
            scope, Instant.now(), expiresAt, reason));
    }

    /** Revoke by id. The actor validates that {@code actor} is allowed to revoke. */
    public boolean revoke(String grantId, String actor) {
        var result = ask(replyTo -> new HomeRegistryActor.RevokeGrant(grantId, actor, replyTo),
            HomeRegistryActor.RevokeResult.class);
        return result instanceof HomeRegistryActor.Revoked;
    }

    /** Revoke a grant identified by (issuer, subject, resource, capability). No-op if not found. */
    public boolean revokeByKey(String issuer, String subject, ResourceUri resource, Capability capability) {
        var target = listIssuedBy(issuer).stream()
            .filter(g -> g.subject().equals(subject))
            .filter(g -> g.resource().toString().equals(resource.toString()))
            .filter(g -> g.capability() == capability)
            .filter(g -> g.isActive(Instant.now()))
            .findFirst();
        if (target.isEmpty()) return false;
        return revoke(target.get().id(), issuer);
    }

    /** Fire-and-forget audit append. Callers use this for events like HOME_ENTERED. */
    public void appendAudit(AuditEntry entry) {
        try {
            registry.tell(new HomeRegistryActor.AppendAudit(entry));
        } catch (Exception e) {
            log.debug("appendAudit failed: {}", e.getMessage());
        }
    }

    public List<Grant> listIssuedBy(String issuer) {
        return ask(replyTo -> new HomeRegistryActor.EnumerateIssued(issuer, replyTo),
            HomeRegistryActor.GrantList.class).grants();
    }

    // --- Grant requests (§10) -------------------------------------------

    /** Submit a grant-request. Returns the stored request on success. */
    public GrantRequest createRequest(
            GrantRequest request) {
        var result = ask(replyTo -> new HomeRegistryActor.CreateGrantRequest(request, replyTo),
            HomeRegistryActor.GrantRequestResult.class);
        if (result instanceof HomeRegistryActor.GrantRequestOk ok) return ok.request();
        throw new IllegalArgumentException(
            ((HomeRegistryActor.GrantRequestError) result).reason());
    }

    /** Owner approves a pending request — mints the Grant. */
    public GrantRequest approveRequest(
            String requestId, String actor, Instant expiresAt, String note) {
        var result = ask(
            replyTo -> new HomeRegistryActor.ApproveGrantRequest(
                requestId, actor, expiresAt, note, replyTo),
            HomeRegistryActor.GrantRequestResult.class);
        if (result instanceof HomeRegistryActor.GrantRequestOk ok) return ok.request();
        throw new IllegalArgumentException(
            ((HomeRegistryActor.GrantRequestError) result).reason());
    }

    /** Owner denies a pending request. */
    public GrantRequest denyRequest(
            String requestId, String actor, String note) {
        var result = ask(
            replyTo -> new HomeRegistryActor.DenyGrantRequest(requestId, actor, note, replyTo),
            HomeRegistryActor.GrantRequestResult.class);
        if (result instanceof HomeRegistryActor.GrantRequestOk ok) return ok.request();
        throw new IllegalArgumentException(
            ((HomeRegistryActor.GrantRequestError) result).reason());
    }

    /** Requester withdraws their own request. */
    public GrantRequest cancelRequest(String requestId, String actor) {
        var result = ask(
            replyTo -> new HomeRegistryActor.CancelGrantRequest(requestId, actor, replyTo),
            HomeRegistryActor.GrantRequestResult.class);
        if (result instanceof HomeRegistryActor.GrantRequestOk ok) return ok.request();
        throw new IllegalArgumentException(
            ((HomeRegistryActor.GrantRequestError) result).reason());
    }

    public List<GrantRequest> pendingForOwner(String ownerDid) {
        return ask(replyTo -> new HomeRegistryActor.PendingRequestsForOwner(ownerDid, replyTo),
            HomeRegistryActor.GrantRequestList.class).requests();
    }

    public List<GrantRequest> requestsByRequester(String requesterDid) {
        return ask(replyTo -> new HomeRegistryActor.RequestsByRequester(requesterDid, replyTo),
            HomeRegistryActor.GrantRequestList.class).requests();
    }

    // --- §108 Seal / unseal / eject --------------------------------------

    /** Seal the owner's Home against new grant-requests. */
    public HomeRegistryActor.SealOk seal(String ownerDid, String reason) {
        var r = ask(replyTo -> new HomeRegistryActor.SealHome(ownerDid, reason, replyTo),
            HomeRegistryActor.SealResult.class);
        if (r instanceof HomeRegistryActor.SealOk ok) return ok;
        throw new IllegalArgumentException(((HomeRegistryActor.SealError) r).reason());
    }

    /** Remove any active seal. Safe to call when not sealed. */
    public HomeRegistryActor.SealOk unseal(String ownerDid) {
        var r = ask(replyTo -> new HomeRegistryActor.UnsealHome(ownerDid, replyTo),
            HomeRegistryActor.SealResult.class);
        if (r instanceof HomeRegistryActor.SealOk ok) return ok;
        throw new IllegalArgumentException(((HomeRegistryActor.SealError) r).reason());
    }

    /** Current seal state. */
    public HomeRegistryActor.SealOk sealState(String ownerDid) {
        var r = ask(replyTo -> new HomeRegistryActor.QuerySeal(ownerDid, replyTo),
            HomeRegistryActor.SealResult.class);
        if (r instanceof HomeRegistryActor.SealOk ok) return ok;
        throw new IllegalArgumentException(((HomeRegistryActor.SealError) r).reason());
    }

    public List<Grant> listHeldBy(String subject) {
        return ask(replyTo -> new HomeRegistryActor.EnumerateHeld(subject, replyTo),
            HomeRegistryActor.GrantList.class).grants();
    }

    @SuppressWarnings("unchecked")
    private <R> R ask(Function<ActorRef<R>, HomeRegistryActor.Command> factory,
                      Class<R> responseClass) {
        try {
            var stage = AskPattern.ask(registry, factory::apply, TIMEOUT, system.scheduler());
            return (R) ((CompletableFuture<Object>) stage.toCompletableFuture())
                .get(TIMEOUT.toSeconds() + 1, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("HomeRegistryActor ask failed: " + e.getMessage(), e);
        }
    }
}
