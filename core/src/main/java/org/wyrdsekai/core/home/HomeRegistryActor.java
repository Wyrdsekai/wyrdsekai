package org.wyrdsekai.core.home;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.home.AuditEntry;
import org.wyrdsekai.common.home.Capability;
import org.wyrdsekai.common.home.Grant;
import org.wyrdsekai.common.home.GrantRequest;
import org.wyrdsekai.common.home.GrantTemplate;
import org.wyrdsekai.common.home.ResourceTypeRegistry;
import org.wyrdsekai.common.home.ResourceUri;
import org.wyrdsekai.common.home.TrustTier;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Single registry actor mediating all Home-level authority decisions.
 *
 * <p>This is the one choke point for {@code CheckAccess}. Every grant issuance,
 * every revocation, every access check, every audit append flows through here.
 * When scale demands it, this actor can be replaced by per-owner sharded
 * {@code HomeActor}s without changing the message protocol — callers don't
 * need to know which implementation is running.</p>
 *
 * <p>M1a scope: in-memory pass-through to {@link HomeStore}. No caching of
 * CheckAccess decisions (all queries hit the DB). Caching layer is a later
 * optimization once we see real load patterns.</p>
 */
public final class HomeRegistryActor extends AbstractBehavior<HomeRegistryActor.Command> {

    private static final Logger log = LoggerFactory.getLogger(HomeRegistryActor.class);

    // --- Protocol ------------------------------------------------------

    public sealed interface Command {}

    /**
     * Check whether {@code subject} holds a valid grant on {@code resource} with
     * {@code capability}. The single authorization choke point (§5.3).
     */
    public record CheckAccess(
        String subject,
        ResourceUri resource,
        Capability capability,
        Map<String, Object> requestedScope,
        ActorRef<AccessDecision> replyTo
    ) implements Command {}

    public sealed interface AccessDecision {}
    public record Allow(Grant byGrant) implements AccessDecision {}
    public record Deny(String reason) implements AccessDecision {}

    /** Issue a new grant. Validates capability × resource type; rejects if invalid. */
    public record IssueGrant(Grant grant, ActorRef<IssueResult> replyTo) implements Command {}

    public sealed interface IssueResult {}
    public record Issued(Grant grant) implements IssueResult {}
    public record IssueError(String reason) implements IssueResult {}

    /** Revoke a grant by id. Only the issuer may revoke. Cascades to delegated children. */
    public record RevokeGrant(
        String grantId,
        String actor,
        ActorRef<RevokeResult> replyTo
    ) implements Command {}

    public sealed interface RevokeResult {}
    public record Revoked(Grant grant, int cascadeCount) implements RevokeResult {}
    public record RevokeError(String reason) implements RevokeResult {}

    /** List grants issued by a given owner. */
    public record EnumerateIssued(String ownerDid, ActorRef<GrantList> replyTo) implements Command {}

    /** List grants held by a given subject. */
    public record EnumerateHeld(String subjectDid, ActorRef<GrantList> replyTo) implements Command {}

    public record GrantList(List<Grant> grants) {}

    /** Fetch a single grant by id. */
    public record FetchGrant(String id, ActorRef<GrantDetail> replyTo) implements Command {}

    public record GrantDetail(Grant grant /* nullable */) {}

    /** Append an arbitrary audit entry (e.g. from room enter/exit, resource-modified). */
    public record AppendAudit(AuditEntry entry) implements Command {}

    /** Query a Home's audit log. Gated by caller's DID at the REST layer, not here. */
    public record QueryAudit(
        String homeOwner,
        Instant since,
        int limit,
        ActorRef<AuditList> replyTo
    ) implements Command {}

    public record AuditList(List<AuditEntry> entries) {}

    // --- §108 Agent Protection — seal/unseal -------------------------

    /** Seal the owner's Home against new grant-requests. */
    public record SealHome(
        String ownerDid,
        String reason,
        ActorRef<SealResult> replyTo
    ) implements Command {}

    /** Remove an existing seal. */
    public record UnsealHome(
        String ownerDid,
        ActorRef<SealResult> replyTo
    ) implements Command {}

    /** Query the current seal state for a Home. */
    public record QuerySeal(
        String ownerDid,
        ActorRef<SealResult> replyTo
    ) implements Command {}

    public sealed interface SealResult {}
    public record SealOk(boolean sealed, Instant sealedAt, String reason) implements SealResult {}
    public record SealError(String reason) implements SealResult {}

    /**
     * Periodic sweep: find grants that crossed their {@code expiresAt}
     * threshold since the last sweep and fire {@code grant-expired} audit
     * entries so caches/notifications drop them. Safe to run often; the
     * unique audit entries are keyed by grant id so repeat sweeps fire
     * duplicate audits only if the grant is still expired (rare).
     */
    public record ReapExpiredGrants(ActorRef<ExpiryReport> replyTo) implements Command {}
    public record ExpiryReport(int expiredCount) {}

    // --- Grant requests (§10) ---

    /**
     * Create a pending grant-request. Caller supplies the request template;
     * actor validates shape + assigns a final id/timestamp.
     */
    public record CreateGrantRequest(
        GrantRequest request,
        ActorRef<GrantRequestResult> replyTo
    ) implements Command {}

    /** Approve a pending grant-request — mints a Grant and closes the request. */
    public record ApproveGrantRequest(
        String requestId,
        String actor,             // DID of approver — must equal owner
        Instant expiresAt,        // optional expiry on the issued Grant
        String note,
        ActorRef<GrantRequestResult> replyTo
    ) implements Command {}

    /** Deny a pending grant-request. */
    public record DenyGrantRequest(
        String requestId,
        String actor,
        String note,
        ActorRef<GrantRequestResult> replyTo
    ) implements Command {}

    /** Requester cancels their own pending request. */
    public record CancelGrantRequest(
        String requestId,
        String actor,
        ActorRef<GrantRequestResult> replyTo
    ) implements Command {}

    /** Enumerate pending requests waiting for an owner's response. */
    public record PendingRequestsForOwner(
        String ownerDid,
        ActorRef<GrantRequestList> replyTo
    ) implements Command {}

    /** Enumerate all requests made by a given requester. */
    public record RequestsByRequester(
        String requesterDid,
        ActorRef<GrantRequestList> replyTo
    ) implements Command {}

    public sealed interface GrantRequestResult {}
    public record GrantRequestOk(GrantRequest request) implements GrantRequestResult {}
    public record GrantRequestError(String reason) implements GrantRequestResult {}
    public record GrantRequestList(List<GrantRequest> requests) {}

    /** High-level owner summary: counts of grants issued/held, recent audit. */
    public record GetSummary(String ownerDid, ActorRef<HomeSummary> replyTo) implements Command {}

    public record HomeSummary(
        String ownerDid,
        int grantsIssued,
        int grantsHeld,
        int grantsIssuedActive,
        int grantsHeldActive,
        List<AuditEntry> recentAudit
    ) {}

    // --- Factory -------------------------------------------------------

    public static Behavior<Command> create(HomeStore store) {
        return Behaviors.setup(ctx -> new HomeRegistryActor(ctx, store));
    }

    // --- State ---------------------------------------------------------

    private final HomeStore store;
    /** Optional event listener for push-notification wiring (see {@link HomeEventListener}). */
    private volatile HomeEventListener listener = HomeEventListener.NOOP;

    private HomeRegistryActor(ActorContext<Command> ctx, HomeStore store) {
        super(ctx);
        this.store = store;
        log.info("HomeRegistryActor started");
    }

    /** Install a listener — call once at startup. */
    public static Behavior<Command> create(HomeStore store, HomeEventListener listener) {
        return Behaviors.setup(ctx -> {
            var actor = new HomeRegistryActor(ctx, store);
            if (listener != null) actor.listener = listener;
            return actor;
        });
    }

    private void emit(HomeEventListener.Kind k, String ownerDid, String actorDid,
                       String subjectDid, String resource, String detail) {
        try {
            listener.onHomeEvent(k, ownerDid, actorDid, subjectDid, resource, detail);
        } catch (Exception ex) {
            log.warn("HomeEventListener failed ({}): {}", k, ex.getMessage());
        }
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
            .onMessage(CheckAccess.class, this::onCheckAccess)
            .onMessage(IssueGrant.class, this::onIssueGrant)
            .onMessage(RevokeGrant.class, this::onRevokeGrant)
            .onMessage(EnumerateIssued.class, this::onEnumerateIssued)
            .onMessage(EnumerateHeld.class, this::onEnumerateHeld)
            .onMessage(FetchGrant.class, this::onFetchGrant)
            .onMessage(AppendAudit.class, msg -> { store.appendAudit(msg.entry()); return this; })
            .onMessage(QueryAudit.class, this::onQueryAudit)
            .onMessage(GetSummary.class, this::onGetSummary)
            .onMessage(CreateGrantRequest.class, this::onCreateGrantRequest)
            .onMessage(ApproveGrantRequest.class, this::onApproveGrantRequest)
            .onMessage(DenyGrantRequest.class, this::onDenyGrantRequest)
            .onMessage(CancelGrantRequest.class, this::onCancelGrantRequest)
            .onMessage(PendingRequestsForOwner.class, msg -> {
                msg.replyTo().tell(new GrantRequestList(store.pendingForOwner(msg.ownerDid())));
                return this;
            })
            .onMessage(RequestsByRequester.class, msg -> {
                msg.replyTo().tell(new GrantRequestList(store.byRequester(msg.requesterDid())));
                return this;
            })
            .onMessage(ReapExpiredGrants.class, this::onReapExpiredGrants)
            .onMessage(SealHome.class, this::onSealHome)
            .onMessage(UnsealHome.class, this::onUnsealHome)
            .onMessage(QuerySeal.class, msg -> {
                var g = findSealGrant(msg.ownerDid());
                msg.replyTo().tell(g
                    .<SealResult>map(seal -> new SealOk(
                        true,
                        seal.issuedAt(),
                        seal.scope() == null ? null
                            : (String) seal.scope().get("sealReason")))
                    .orElse(new SealOk(false, null, null)));
                return this;
            })
            .build();
    }

    /**
     * Seal = self-grant on {@code home://owner/home-room} with
     * {@code scope.sealed=true} ( "grant-held boundary").
     * Upsert semantics: an existing seal grant is revoked and replaced.
     */
    private Behavior<Command> onSealHome(SealHome msg) {
        var resource = ResourceUri.of(msg.ownerDid(), ResourceTypeRegistry.HOME_ROOM);
        // Revoke any existing seal grant (idempotent re-seal).
        findSealGrant(msg.ownerDid()).ifPresent(g ->
            store.revokeGrant(g.id(), Instant.now()));

        var scope = new HashMap<String, Object>();
        scope.put("sealed", true);
        if (msg.reason() != null) scope.put("sealReason", msg.reason());
        var grant = Grant.issue(
            msg.ownerDid(), msg.ownerDid(), resource, Capability.use,
            scope, Instant.now(), null,
            msg.reason() != null ? msg.reason() : "home sealed");
        store.saveGrant(grant);
        store.appendAudit(AuditEntry.now(
            msg.ownerDid(), msg.ownerDid(),
            AuditEntry.Verb.HOME_SEALED,
            resource.toString(),
            AuditEntry.Outcome.ok,
            msg.reason() == null
                ? Map.of("grantId", grant.id())
                : Map.of("grantId", grant.id(), "reason", msg.reason()),
            grant.id()));
        emit(HomeEventListener.Kind.HOME_SEALED,
            msg.ownerDid(), msg.ownerDid(), null, resource.toString(),
            msg.reason() != null ? msg.reason() : "sealed");
        msg.replyTo().tell(new SealOk(true, grant.issuedAt(), msg.reason()));
        return this;
    }

    private Behavior<Command> onUnsealHome(UnsealHome msg) {
        var existing = findSealGrant(msg.ownerDid());
        if (existing.isPresent()) {
            var g = existing.get();
            store.revokeGrant(g.id(), Instant.now());
            store.appendAudit(AuditEntry.now(
                msg.ownerDid(), msg.ownerDid(),
                AuditEntry.Verb.HOME_UNSEALED,
                g.resource().toString(),
                AuditEntry.Outcome.ok,
                Map.of("grantId", g.id()),
                g.id()));
            emit(HomeEventListener.Kind.HOME_UNSEALED,
                msg.ownerDid(), msg.ownerDid(), null,
                g.resource().toString(), "unsealed");
        }
        msg.replyTo().tell(new SealOk(false, null, null));
        return this;
    }

    /** Look up an active sealed-marker self-grant for the owner, if any. */
    private Optional<Grant> findSealGrant(String ownerDid) {
        var resourceStr = "home://" + ownerDid + "/home-room";
        return store.findActiveGrants(ownerDid, resourceStr, Capability.use).stream()
            .filter(g -> ownerDid.equals(g.subject()))
            .filter(g -> Boolean.TRUE.equals(g.scope().get("sealed")))
            .findFirst();
    }

    /**
     * Track grants we've already audited as expired, so reap passes don't
     * fire duplicate {@code grant-expired} entries.
     */
    private final Set<String> reapedGrantIds = new HashSet<>();

    private Behavior<Command> onReapExpiredGrants(ReapExpiredGrants msg) {
        var now = Instant.now();
        var expired = store.findNewlyExpired(now);
        int audited = 0;
        for (var g : expired) {
            if (reapedGrantIds.contains(g.id())) continue;
            reapedGrantIds.add(g.id());
            audited++;
            store.appendAudit(AuditEntry.now(
                g.resource().owner(),
                g.issuer(),
                AuditEntry.Verb.GRANT_EXPIRED,
                g.resource().toString(),
                AuditEntry.Outcome.ok,
                Map.of("grantId", g.id(),
                       "subject", g.subject(),
                       "capability", g.capability().name(),
                       "expiresAt", g.expiresAt().toString()),
                g.id()));
            emit(HomeEventListener.Kind.GRANT_EXPIRED,
                g.resource().owner(), g.issuer(), g.subject(),
                g.resource().toString(),
                "expired at " + g.expiresAt());
        }
        if (audited > 0) log.info("Reaper: audited {} newly-expired grant(s)", audited);
        msg.replyTo().tell(new ExpiryReport(audited));
        return this;
    }

    // --- Grant request handlers ---

    private Behavior<Command> onCreateGrantRequest(CreateGrantRequest msg) {
        var r = msg.request();
        try {
            ResourceTypeRegistry.validate(r.resource(), r.capability());
        } catch (IllegalArgumentException ex) {
            msg.replyTo().tell(new GrantRequestError(ex.getMessage()));
            return this;
        }
        if (!r.owner().equals(r.resource().owner())) {
            msg.replyTo().tell(new GrantRequestError(
                "request owner '" + r.owner() + "' does not own " + r.resource()));
            return this;
        }
        // §108: if the owner has sealed their Home, reject incoming requests
        // (except self-requests — owner can still ask themselves for things).
        if (!r.requester().equals(r.owner())) {
            var seal = findSealGrant(r.owner());
            if (seal.isPresent()) {
                var reason = seal.get().scope() == null ? null
                    : (String) seal.get().scope().get("sealReason");
                msg.replyTo().tell(new GrantRequestError(
                    "home is sealed"
                    + (reason != null ? ": " + reason : "")));
                return this;
            }
        }
        store.saveGrantRequest(r);
        store.appendAudit(AuditEntry.now(
            r.owner(), r.requester(),
            AuditEntry.Verb.GRANT_REQUESTED,
            r.resource().toString(),
            AuditEntry.Outcome.ok,
            Map.of("requestId", r.id(), "capability", r.capability().name()),
            r.id()));
        emit(HomeEventListener.Kind.GRANT_REQUESTED,
            r.owner(), r.requester(), r.requester(), r.resource().toString(),
            r.reason() != null ? r.reason() : r.capability().name());
        msg.replyTo().tell(new GrantRequestOk(r));
        return this;
    }

    private Behavior<Command> onApproveGrantRequest(ApproveGrantRequest msg) {
        var maybe = store.getGrantRequest(msg.requestId());
        if (maybe.isEmpty()) {
            msg.replyTo().tell(new GrantRequestError("unknown request: " + msg.requestId()));
            return this;
        }
        var r = maybe.get();
        if (!r.isPending()) {
            msg.replyTo().tell(new GrantRequestError("request is " + r.status()));
            return this;
        }
        if (!r.owner().equals(msg.actor())) {
            msg.replyTo().tell(new GrantRequestError(
                "actor '" + msg.actor() + "' is not the resource owner"));
            return this;
        }
        // Mint the Grant. §97: if the request's scope carries a `trustTier`,
        // apply the tier template for default expiry + scope stamps; the
        // caller's explicit expiresAt still wins when provided.
        Grant grant;
        var tierScope = r.scope() == null ? null : r.scope().get("trustTier");
        if (tierScope instanceof String tierStr) {
            var tier = TrustTier.parse(tierStr);
            grant = GrantTemplate.forTier(
                tier, r.owner(), r.requester(), r.resource(), r.capability(),
                r.scope(), msg.expiresAt(), r.reason());
        } else {
            grant = Grant.issue(
                r.owner(), r.requester(), r.resource(), r.capability(),
                r.scope(), Instant.now(), msg.expiresAt(),
                r.reason());
        }
        store.saveGrant(grant);
        store.appendAudit(AuditEntry.now(
            r.owner(), r.owner(),
            AuditEntry.Verb.GRANT_ISSUED,
            r.resource().toString(),
            AuditEntry.Outcome.ok,
            Map.of("grantId", grant.id(), "viaRequest", r.id(),
                   "capability", r.capability().name()),
            grant.id()));
        emit(HomeEventListener.Kind.GRANT_ISSUED,
            r.owner(), r.owner(), r.requester(), r.resource().toString(),
            grant.capability().name() + " (via request " + r.id() + ")");
        var approved = r.approved(grant.id(), msg.note());
        store.saveGrantRequest(approved);
        store.appendAudit(AuditEntry.now(
            r.owner(), msg.actor(),
            AuditEntry.Verb.GRANT_APPROVED,
            r.resource().toString(),
            AuditEntry.Outcome.ok,
            Map.of("requestId", r.id(), "grantId", grant.id()),
            r.id()));
        emit(HomeEventListener.Kind.GRANT_APPROVED,
            r.owner(), msg.actor(), r.requester(), r.resource().toString(),
            "approved by " + msg.actor());
        msg.replyTo().tell(new GrantRequestOk(approved));
        return this;
    }

    private Behavior<Command> onDenyGrantRequest(DenyGrantRequest msg) {
        var maybe = store.getGrantRequest(msg.requestId());
        if (maybe.isEmpty()) {
            msg.replyTo().tell(new GrantRequestError("unknown request: " + msg.requestId()));
            return this;
        }
        var r = maybe.get();
        if (!r.isPending()) {
            msg.replyTo().tell(new GrantRequestError("request is " + r.status()));
            return this;
        }
        if (!r.owner().equals(msg.actor())) {
            msg.replyTo().tell(new GrantRequestError(
                "actor '" + msg.actor() + "' is not the resource owner"));
            return this;
        }
        var denied = r.denied(msg.note());
        store.saveGrantRequest(denied);
        store.appendAudit(AuditEntry.now(
            r.owner(), msg.actor(),
            AuditEntry.Verb.GRANT_DENIED,
            r.resource().toString(),
            AuditEntry.Outcome.denied,
            Map.of("requestId", r.id()),
            r.id()));
        emit(HomeEventListener.Kind.GRANT_DENIED,
            r.owner(), msg.actor(), r.requester(), r.resource().toString(),
            "denied by " + msg.actor());
        msg.replyTo().tell(new GrantRequestOk(denied));
        return this;
    }

    private Behavior<Command> onCancelGrantRequest(CancelGrantRequest msg) {
        var maybe = store.getGrantRequest(msg.requestId());
        if (maybe.isEmpty()) {
            msg.replyTo().tell(new GrantRequestError("unknown request: " + msg.requestId()));
            return this;
        }
        var r = maybe.get();
        if (!r.isPending()) {
            msg.replyTo().tell(new GrantRequestError("request is " + r.status()));
            return this;
        }
        if (!r.requester().equals(msg.actor())) {
            msg.replyTo().tell(new GrantRequestError(
                "actor '" + msg.actor() + "' is not the requester"));
            return this;
        }
        var cancelled = r.cancelled();
        store.saveGrantRequest(cancelled);
        msg.replyTo().tell(new GrantRequestOk(cancelled));
        return this;
    }

    // --- Handlers ------------------------------------------------------

    private Behavior<Command> onCheckAccess(CheckAccess msg) {
        var now = Instant.now();
        var uri = msg.resource().toString();
        var candidates = store.findActiveGrants(msg.subject(), uri, msg.capability());
        // Also check public grants — a grant with subject="public" covers any subject.
        if (!Grant.PUBLIC_SUBJECT.equals(msg.subject())) {
            candidates = new ArrayList<>(candidates);
            candidates.addAll(store.findActiveGrants(Grant.PUBLIC_SUBJECT, uri, msg.capability()));
        }
        Grant winner = null;
        for (var g : candidates) {
            if (g.isActive(now) && scopeSatisfied(g.scope(), msg.requestedScope())) {
                winner = g;
                break;
            }
        }
        if (winner != null) {
            auditAccess(msg.resource().owner(), msg.subject(), msg.resource(),
                msg.capability(), AuditEntry.Outcome.ok, winner.id());
            msg.replyTo().tell(new Allow(winner));
        } else {
            var reason = candidates.isEmpty()
                ? "no grant"
                : "grant found but scope/validity failed";
            auditAccess(msg.resource().owner(), msg.subject(), msg.resource(),
                msg.capability(), AuditEntry.Outcome.denied, null);
            msg.replyTo().tell(new Deny(reason));
        }
        return this;
    }

    private Behavior<Command> onIssueGrant(IssueGrant msg) {
        var g = msg.grant();
        // Validate the shape against the resource-type registry.
        try {
            ResourceTypeRegistry.validate(g.resource(), g.capability());
        } catch (IllegalArgumentException ex) {
            msg.replyTo().tell(new IssueError(ex.getMessage()));
            return this;
        }
        // §4.5: non-owner issuer must either reference an explicit parent via
        // delegatedFrom, or hold an active delegate grant on the resource.
        // In both cases the child must be a subset of the parent.
        if (!g.issuer().equals(g.resource().owner())) {
            Grant parent;
            if (g.delegatedFrom() != null) {
                var maybe = store.getGrant(g.delegatedFrom());
                if (maybe.isEmpty()) {
                    msg.replyTo().tell(new IssueError(
                        "delegatedFrom references unknown grant: " + g.delegatedFrom()));
                    return this;
                }
                parent = maybe.get();
                if (!parent.isActive(Instant.now())) {
                    msg.replyTo().tell(new IssueError(
                        "parent grant '" + parent.id() + "' is not active"));
                    return this;
                }
                if (!parent.subject().equals(g.issuer())) {
                    msg.replyTo().tell(new IssueError(
                        "issuer '" + g.issuer() + "' does not hold parent grant '"
                        + parent.id() + "'"));
                    return this;
                }
                if (!parent.resource().toString().equals(g.resource().toString())) {
                    msg.replyTo().tell(new IssueError(
                        "child resource '" + g.resource()
                        + "' does not match parent resource '" + parent.resource() + "'"));
                    return this;
                }
                if (parent.capability() != Capability.delegate) {
                    msg.replyTo().tell(new IssueError(
                        "parent grant must have delegate capability, has "
                        + parent.capability()));
                    return this;
                }
            } else {
                var delegates = store.findActiveGrants(g.issuer(),
                    g.resource().toString(), Capability.delegate);
                if (delegates.isEmpty()) {
                    msg.replyTo().tell(new IssueError(
                        "issuer '" + g.issuer() + "' is not the resource owner "
                        + "and holds no delegate grant on " + g.resource()));
                    return this;
                }
                parent = delegates.get(0);
            }
            var err = validateSubset(parent, g);
            if (err != null) {
                msg.replyTo().tell(new IssueError(err));
                return this;
            }
        } else if (g.delegatedFrom() != null) {
            // Owner-issued grant with an explicit parent — unusual but validate anyway.
            var maybe = store.getGrant(g.delegatedFrom());
            if (maybe.isEmpty()) {
                msg.replyTo().tell(new IssueError(
                    "delegatedFrom references unknown grant: " + g.delegatedFrom()));
                return this;
            }
            var err = validateSubset(maybe.get(), g);
            if (err != null) {
                msg.replyTo().tell(new IssueError(err));
                return this;
            }
        }

        store.saveGrant(g);
        store.appendAudit(AuditEntry.now(
            g.resource().owner(),      // audit lands on the resource owner's Home
            g.issuer(),
            AuditEntry.Verb.GRANT_ISSUED,
            g.resource().toString(),
            AuditEntry.Outcome.ok,
            Map.of(
                "grantId", g.id(),
                "subject", g.subject(),
                "capability", g.capability().name(),
                "expiresAt", g.expiresAt() == null ? "" : g.expiresAt().toString(),
                "revocationMode", g.revocationMode().name()),
            g.id()));
        emit(HomeEventListener.Kind.GRANT_ISSUED,
            g.resource().owner(), g.issuer(), g.subject(), g.resource().toString(),
            g.capability().name());
        msg.replyTo().tell(new Issued(g));
        return this;
    }

    private Behavior<Command> onRevokeGrant(RevokeGrant msg) {
        var existing = store.getGrant(msg.grantId()).orElse(null);
        if (existing == null) {
            msg.replyTo().tell(new RevokeError("grant not found: " + msg.grantId()));
            return this;
        }
        if (existing.isRevoked()) {
            msg.replyTo().tell(new RevokeError("grant already revoked"));
            return this;
        }
        // Only issuer (or resource owner) can revoke. Resource owner is always sovereign.
        if (!msg.actor().equals(existing.issuer()) && !msg.actor().equals(existing.resource().owner())) {
            msg.replyTo().tell(new RevokeError(
                "actor '" + msg.actor() + "' not authorized to revoke grant " + msg.grantId()));
            return this;
        }
        var now = Instant.now();
        store.revokeGrant(msg.grantId(), now);
        var cascade = store.revokeDelegatedFrom(msg.grantId(), now);
        var revoked = existing.revoke(now);
        store.appendAudit(AuditEntry.now(
            existing.resource().owner(),
            msg.actor(),
            AuditEntry.Verb.GRANT_REVOKED,
            existing.resource().toString(),
            AuditEntry.Outcome.ok,
            Map.of("grantId", msg.grantId(), "cascade", cascade),
            msg.grantId()));
        emit(HomeEventListener.Kind.GRANT_REVOKED,
            existing.resource().owner(), msg.actor(), existing.subject(),
            existing.resource().toString(),
            "revoked by " + msg.actor()
                + (cascade > 0 ? " (cascade=" + cascade + ")" : ""));
        msg.replyTo().tell(new Revoked(revoked, cascade));
        return this;
    }

    private Behavior<Command> onEnumerateIssued(EnumerateIssued msg) {
        msg.replyTo().tell(new GrantList(store.grantsByIssuer(msg.ownerDid())));
        return this;
    }

    private Behavior<Command> onEnumerateHeld(EnumerateHeld msg) {
        msg.replyTo().tell(new GrantList(store.grantsBySubject(msg.subjectDid())));
        return this;
    }

    private Behavior<Command> onFetchGrant(FetchGrant msg) {
        msg.replyTo().tell(new GrantDetail(store.getGrant(msg.id()).orElse(null)));
        return this;
    }

    private Behavior<Command> onQueryAudit(QueryAudit msg) {
        msg.replyTo().tell(new AuditList(store.queryAudit(msg.homeOwner(), msg.since(), msg.limit())));
        return this;
    }

    private Behavior<Command> onGetSummary(GetSummary msg) {
        var issued = store.grantsByIssuer(msg.ownerDid());
        var held = store.grantsBySubject(msg.ownerDid());
        var now = Instant.now();
        var issuedActive = (int) issued.stream().filter(g -> g.isActive(now)).count();
        var heldActive = (int) held.stream().filter(g -> g.isActive(now)).count();
        var recent = store.queryAudit(msg.ownerDid(), null, 20);
        msg.replyTo().tell(new HomeSummary(
            msg.ownerDid(),
            issued.size(), held.size(),
            issuedActive, heldActive,
            recent));
        return this;
    }

    // --- Helpers -------------------------------------------------------

    /**
     * Scope satisfaction: every key in {@code granted} that constrains the
     * request must be met by {@code requested}. Unknown/missing keys on the
     * request side default to "not requested" and fail closed on restrictive
     * grants. Deliberately strict: the caller of CheckAccess is responsible
     * for passing the relevant scope qualifiers.
     *
     * <p>M1a implementation: supports key-equality for simple scope payloads
     * (e.g. {@code {"collection": "notes"}}). Richer scope semantics (time
     * windows, token caps) are resource-handler-specific and will be added in
     * §9 handler integration.</p>
     */
    /**
     * §4.5: validate that {@code child} is a subset of {@code parent} — the
     * delegated grant cannot broaden capability, cannot narrow-then-expand
     * scope, and cannot outlive the parent. Returns null when subset holds,
     * otherwise a human-readable reason.
     */
    static String validateSubset(Grant parent, Grant child) {
        // Expiry: child cannot outlive parent. Parent with null = open-ended.
        if (parent.expiresAt() != null) {
            if (child.expiresAt() == null) {
                return "child grant must expire no later than parent ("
                    + parent.expiresAt() + ")";
            }
            if (child.expiresAt().isAfter(parent.expiresAt())) {
                return "child expiresAt " + child.expiresAt()
                    + " is after parent " + parent.expiresAt();
            }
        }
        // Scope: every key in parent must appear (and equal) in child. Child
        // may add narrower keys but cannot drop parent constraints.
        if (parent.scope() != null && !parent.scope().isEmpty()) {
            for (var e : parent.scope().entrySet()) {
                var pv = e.getValue();
                var cv = child.scope() == null ? null : child.scope().get(e.getKey());
                if (cv == null) {
                    return "child scope missing parent key '" + e.getKey() + "'";
                }
                if (!pv.equals(cv)) {
                    return "child scope key '" + e.getKey() + "' is '" + cv
                        + "', parent requires '" + pv + "'";
                }
            }
        }
        return null;
    }

    static boolean scopeSatisfied(Map<String, Object> granted, Map<String, Object> requested) {
        if (granted == null || granted.isEmpty()) return true;
        if (requested == null) return false;
        for (var e : granted.entrySet()) {
            // §22.3 confidential-scope exclusion: a grant with
            // {excludes: ["confidential", ...]} denies access when the request
            // carries a `tag` matching any exclude entry. Covers "mother holds
            // delegate on journal, confidential entries excluded."
            if ("excludes".equals(e.getKey())) {
                if (!(e.getValue() instanceof List<?> excludes)) return false;
                var requestedTag = requested.get("tag");
                if (requestedTag != null) {
                    for (var ex : excludes) {
                        if (ex != null && ex.equals(requestedTag)) return false;
                    }
                }
                // Also deny when the requested path/id is in the excludes list
                // (supports {excludes: ["entry-42"]} shape).
                var requestedPath = requested.get("path");
                if (requestedPath != null) {
                    for (var ex : excludes) {
                        if (ex != null && ex.equals(requestedPath)) return false;
                    }
                }
                continue; // excludes is directional — don't require it in requested.
            }
            var gVal = e.getValue();
            var rVal = requested.get(e.getKey());
            if (rVal == null) return false;
            if (!gVal.equals(rVal)) return false;
        }
        return true;
    }

    private void auditAccess(String homeOwner, String subject, ResourceUri resource,
                             Capability capability, AuditEntry.Outcome outcome, String byGrantId) {
        var verb = outcome == AuditEntry.Outcome.ok
            ? AuditEntry.Verb.ACCESS_GRANTED : AuditEntry.Verb.ACCESS_DENIED;
        Map<String, Object> detail = byGrantId == null
            ? Map.of("capability", capability.name())
            : Map.of("capability", capability.name(), "grantId", byGrantId);
        // Primary entry: lands on the resource owner's Home.
        store.appendAudit(AuditEntry.now(
            homeOwner, subject, verb, resource.toString(), outcome, detail, byGrantId));
        // §22.3 dual-audit: when the acting subject is distinct from the
        // resource owner AND subject looks like a DID (not the public marker),
        // the subject's Home also records the access so both parties can
        // audit from their own view.
        if (subject != null
                && !subject.equals(homeOwner)
                && !Grant.PUBLIC_SUBJECT.equals(subject)) {
            store.appendAudit(AuditEntry.now(
                subject, subject, verb, resource.toString(), outcome,
                // Replace detail on the subject's copy so the grantId and
                // the cross-home origin are legible.
                byGrantId == null
                    ? Map.of("capability", capability.name(), "onHome", homeOwner)
                    : Map.of("capability", capability.name(),
                             "grantId", byGrantId,
                             "onHome", homeOwner),
                byGrantId));
        }
    }
}
