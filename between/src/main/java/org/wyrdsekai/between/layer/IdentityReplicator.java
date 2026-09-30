package org.wyrdsekai.between.layer;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.between.BetweenEnvelope;
import org.wyrdsekai.between.NatsBridge;
import org.wyrdsekai.between.NodeIdentity;
import org.wyrdsekai.core.config.WyrdConfig;
import org.wyrdsekai.core.identity.HouseholdStore;
import org.wyrdsekai.core.persistence.AuthService;
import org.wyrdsekai.core.persistence.InviteService;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Account data replication via NATS JetStream (Wave 1 + Wave 2 integration).
 *
 * Uses JetStream persistent streams — not fire-and-forget pub/sub.
 * This means: if Node B joins the mesh 10 minutes after Node A created the steward,
 * Node B's durable consumer replays ALL account events from the stream start.
 * No timing dependency, no data loss, no re-sync needed.
 *
 * Stream: WYRD_ACCOUNTS
 * Subjects: account.created, account.removed, account.invite.created,
 *           account.invite.consumed, account.config.changed
 *
 * <p>Trust (2026-09-28). Every event is a {@link BetweenEnvelope} signed by the origin machine's node
 * key, with the subject inside the signed payload. It is accepted only when the origin is on this
 * household's roster ({@code households}) and the signature verifies. Roles other than member, account
 * removals and setting changes are honoured only when the event names the steward acting
 * ({@code actorId}) and that person is a steward here too. Invite codes travel sealed to each roster
 * machine's X25519 key, never in the clear. Unsigned events and events from machines not on the roster
 * are refused; {@code WYRDSEKAI_ACCOUNTS_ACCEPT_UNSIGNED=true} is the transition setting that still
 * takes them, as members only, while older machines are upgraded.</p>
 */
public final class IdentityReplicator {

    private static final Logger log = LoggerFactory.getLogger(IdentityReplicator.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    /** JetStream stream name for account events. */
    public static final String STREAM_NAME = "WYRD_ACCOUNTS";
    private static final String SUBJECT_PREFIX = "account.";
    private static final String STEWARD = "steward";
    private static final String MEMBER = "member";

    // ── Event records ──

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AccountCreated(
        @JsonProperty("userId") String userId,
        @JsonProperty("username") String username,
        @JsonProperty("passwordHash") String passwordHash,
        @JsonProperty("displayName") String displayName,
        @JsonProperty("role") String role,
        @JsonProperty("sourceNodeId") String sourceNodeId,
        @JsonProperty("timestamp") Instant timestamp,
        @JsonProperty("actorId") String actorId
    ) {
        @JsonCreator public AccountCreated {}
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AccountRemoved(
        @JsonProperty("userId") String userId,
        @JsonProperty("sourceNodeId") String sourceNodeId,
        @JsonProperty("timestamp") Instant timestamp,
        @JsonProperty("actorId") String actorId
    ) {
        @JsonCreator public AccountRemoved {}
    }

    /** {@code code} is only ever read from older, unsigned events; new events carry {@code sealedCodes}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record InviteCreated(
        @JsonProperty("id") String id,
        @JsonProperty("code") String code,
        @JsonProperty("intendedName") String intendedName,
        @JsonProperty("role") String role,
        @JsonProperty("createdBy") String createdBy,
        @JsonProperty("expiresAt") Instant expiresAt,
        @JsonProperty("sourceNodeId") String sourceNodeId,
        @JsonProperty("timestamp") Instant timestamp,
        @JsonProperty("sealedCodes") Map<String, String> sealedCodes
    ) {
        @JsonCreator public InviteCreated {}
    }

    /** {@code code} is only ever read from older, unsigned events; new events carry {@code inviteId}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record InviteConsumed(
        @JsonProperty("code") String code,
        @JsonProperty("consumedBy") String consumedBy,
        @JsonProperty("sourceNodeId") String sourceNodeId,
        @JsonProperty("timestamp") Instant timestamp,
        @JsonProperty("inviteId") String inviteId
    ) {
        @JsonCreator public InviteConsumed {}
    }

    public record ConfigChanged(
        @JsonProperty("key") String key,
        @JsonProperty("value") String value,
        @JsonProperty("updatedBy") String updatedBy,
        @JsonProperty("sourceNodeId") String sourceNodeId,
        @JsonProperty("timestamp") Instant timestamp
    ) {
        @JsonCreator public ConfigChanged {}
    }

    // ── State ──

    private final NatsBridge nats;
    private final BiConsumer<String, byte[]> publisher;
    private final NodeIdentity identity;
    private final String localNodeId;
    private final Function<String, Optional<byte[]>> rosterKey;
    private final Supplier<List<HouseholdStore.Row>> roster;
    private final AuthService authService;
    private final InviteService inviteService;
    private final boolean acceptUnsigned;
    /** Newest applied setting-change time per key, so a re-published old change cannot revert a newer one. */
    private final Map<String, Instant> configAppliedAt = new ConcurrentHashMap<>();

    /** Production: sign with this node's identity, trust the household roster. */
    public IdentityReplicator(NatsBridge nats, NodeIdentity identity, HouseholdStore roster,
                              AuthService authService, InviteService inviteService) {
        this(nats, nats::jetStreamPublish, identity, identity.nodeId(),
            id -> roster.get(id).map(HouseholdStore.Row::publicKey),
            roster::all, authService, inviteService,
            WyrdConfig.get().resolveBool("WYRDSEKAI_ACCOUNTS_ACCEPT_UNSIGNED",
                "household.accounts_accept_unsigned", false));
    }

    IdentityReplicator(NatsBridge nats, BiConsumer<String, byte[]> publisher, NodeIdentity identity,
                       String localNodeId, Function<String, Optional<byte[]>> rosterKey,
                       Supplier<List<HouseholdStore.Row>> roster, AuthService authService,
                       InviteService inviteService, boolean acceptUnsigned) {
        this.nats = nats;
        this.publisher = publisher;
        this.identity = identity;
        this.localNodeId = localNodeId;
        this.rosterKey = rosterKey;
        this.roster = roster;
        this.authService = authService;
        this.inviteService = inviteService;
        this.acceptUnsigned = acceptUnsigned;
        if (acceptUnsigned) {
            log.warn("IdentityReplicator: WYRDSEKAI_ACCOUNTS_ACCEPT_UNSIGNED=true — unsigned account events and "
                + "events from machines not on the roster are still taken, as members only. Anyone who can "
                + "publish on the household bus can then add a member account. Remove the setting once "
                + "every household machine is upgraded.");
        }
    }

    // ── Publishing (JetStream — persistent, signed) ──

    /**
     * Call after a new account is created locally.
     * @param actorId who created it: the steward who added it, the steward who issued the invite it
     *                came through, or the account itself for the household's first steward
     */
    public void publishAccountCreated(String userId, String username, String passwordHash,
                                       String displayName, String role, String actorId) {
        var event = new AccountCreated(userId, username, passwordHash, displayName, role,
            localNodeId, Instant.now(), actorId);
        publish("account.created", event);
        log.info("Replicated account.created: {} ({})", username, role);
    }

    /** Call after an account is removed locally by the steward {@code actorId}. */
    public void publishAccountRemoved(String userId, String actorId) {
        publish("account.removed", new AccountRemoved(userId, localNodeId, Instant.now(), actorId));
        log.info("Replicated account.removed: {}", userId);
    }

    /** Call after an invite is created locally. The code is sealed to each roster machine. */
    public void publishInviteCreated(InviteService.Invite invite) {
        var sealed = new LinkedHashMap<String, String>();
        for (var row : roster.get()) {
            if (row.householdId().equals(localNodeId) || row.x25519PublicKey() == null) continue;
            try {
                sealed.put(row.householdId(),
                    InviteCodeSeal.seal(invite.code(), row.x25519PublicKey(), invite.id(), row.householdId()));
            } catch (Exception e) {
                log.warn("Could not seal invite code for household node {}: {}", row.householdId(), e.getMessage());
            }
        }
        var event = new InviteCreated(invite.id(), null, invite.intendedName(),
            invite.role(), invite.createdBy(), invite.expiresAt(),
            localNodeId, Instant.now(), sealed);
        publish("account.invite.created", event);
        log.info("Replicated invite.created for '{}' (sealed to {} machine(s))", invite.intendedName(), sealed.size());
    }

    /** Call after an invite is consumed locally. Only the invite id travels, never the code. */
    public void publishInviteConsumed(String code, String consumedBy) {
        var id = inviteService.listInvites().stream()
            .filter(i -> i.code().equalsIgnoreCase(code == null ? "" : code.trim()))
            .map(InviteService.Invite::id).findFirst().orElse(null);
        if (id == null) return;
        publish("account.invite.consumed",
            new InviteConsumed(null, consumedBy, localNodeId, Instant.now(), id));
    }

    /** Call after household config is changed locally by the steward {@code updatedBy}. */
    public void publishConfigChanged(String key, String value, String updatedBy) {
        publish("account.config.changed",
            new ConfigChanged(key, value, updatedBy, localNodeId, Instant.now()));
        log.info("Replicated config.changed: {} = {}", key, value);
    }

    // ── Subscribing (JetStream durable consumer — replays from start) ──

    /**
     * Start replication. Creates the JetStream stream (if absent) and subscribes
     * with a durable consumer that replays ALL events from the beginning.
     * New nodes automatically receive the full account history.
     */
    public void startReplication() {
        // Ensure the JetStream stream exists
        nats.ensureStream(STREAM_NAME, "account.>");

        // Subscribe with a durable consumer unique to this node
        var durableName = "account-replicator-" + localNodeId.substring(0, 8);
        nats.jetStreamSubscribe(STREAM_NAME, "account.>", durableName, msg -> {
            try {
                apply(msg.getSubject(), msg.getData());
            } catch (Exception e) {
                log.warn("Failed to process account event on {}: {}", msg.getSubject(), e.getMessage());
            }
        });

        log.info("IdentityReplicator: JetStream replication started (stream={}, durable={})",
            STREAM_NAME, durableName);
    }

    /** One replicated account event, as it arrived on {@code subject}. */
    void apply(String subject, byte[] data) throws IOException {
        var root = MAPPER.readTree(data);
        if (root.has("sig") && root.has("src") && root.has("payload")) {
            var env = BetweenEnvelope.fromBytes(data);
            if (localNodeId.equals(env.src())) return;
            var key = rosterKey.apply(env.src());
            if (key.isEmpty()) {
                applyUntrusted(subject, env.payload().path("event"), "machine " + env.src() + " is not on this household's roster");
                return;
            }
            boolean verified;
            try {
                verified = env.verify(key.get());
            } catch (RuntimeException e) {
                verified = false;
            }
            if (!verified) {
                log.warn("Refused a replicated {} that claims to come from household machine {} but is not signed by it",
                    subject, env.src());
                return;
            }
            if (!subject.equals(env.payload().path("subject").asText())) {
                log.warn("Refused a replicated event from {}: signed for {} but published on {}",
                    env.src(), env.payload().path("subject").asText(), subject);
                return;
            }
            applyTrusted(subject, env.payload().path("event"), env.src());
            return;
        }
        if (localNodeId.equals(root.path("sourceNodeId").asText())) return;
        applyUntrusted(subject, root, "the event is not signed");
    }

    /** From a verified household machine. Privileged effects still need a steward acting. */
    private void applyTrusted(String subject, JsonNode node, String origin) throws IOException {
        switch (subject) {
            case "account.created" -> {
                var event = MAPPER.treeToValue(node, AccountCreated.class);
                if (authService.findUserByUsername(event.username()).isPresent()) return;
                var role = event.role() == null || event.role().isBlank() ? MEMBER : event.role();
                if (!MEMBER.equals(role) && !stewardHere(event.actorId())
                        && !(STEWARD.equals(role) && event.userId().equals(event.actorId()))) {
                    log.warn("Replicated account '{}' named role '{}' without a steward acting: it arrives as a member",
                        event.username(), role);
                    role = MEMBER;
                }
                authService.registerWithHash(event.userId(), event.username(),
                    event.passwordHash(), event.displayName(), role);
                log.info("Replicated remote identity from {}: {} (role={})", origin, event.username(), role);
            }
            case "account.removed" -> {
                var event = MAPPER.treeToValue(node, AccountRemoved.class);
                if (!stewardHere(event.actorId()) || !authService.removeUser(event.actorId(), event.userId())) {
                    log.warn("Refused a replicated account removal ({}) from {}: no steward of this household acting",
                        event.userId(), origin);
                }
            }
            case "account.invite.created" -> {
                var event = MAPPER.treeToValue(node, InviteCreated.class);
                var sealed = event.sealedCodes() == null ? null : event.sealedCodes().get(localNodeId);
                if (sealed == null || identity == null) {
                    log.info("Replicated invite for '{}' was not sealed to this machine; it can be redeemed where it was made",
                        event.intendedName());
                    return;
                }
                String code;
                try {
                    code = InviteCodeSeal.open(sealed, identity.x25519PrivateKeyPkcs8(), event.id(), localNodeId);
                } catch (Exception e) {
                    log.warn("Could not open the sealed code of replicated invite {}: {}", event.id(), e.getMessage());
                    return;
                }
                var role = stewardHere(event.createdBy()) ? event.role() : MEMBER;
                inviteService.replicateInvite(event.id(), code, event.intendedName(),
                    role, event.createdBy(), event.expiresAt());
                log.info("Replicated remote invite for '{}' (role={})", event.intendedName(), role);
            }
            case "account.invite.consumed" -> {
                var event = MAPPER.treeToValue(node, InviteConsumed.class);
                if (event.inviteId() != null) inviteService.consumeReplicated(event.inviteId(), event.consumedBy());
            }
            case "account.config.changed" -> {
                var event = MAPPER.treeToValue(node, ConfigChanged.class);
                if (!stewardHere(event.updatedBy())) {
                    log.warn("Refused a replicated setting change ({}) from {}: no steward of this household acting",
                        event.key(), origin);
                    return;
                }
                var at = event.timestamp() == null ? Instant.EPOCH : event.timestamp();
                var prev = configAppliedAt.get(event.key());
                if (prev != null && !at.isAfter(prev)) return;
                configAppliedAt.put(event.key(), at);
                authService.setConfig(event.key(), event.value(), event.updatedBy());
                log.info("Replicated remote config from {}: {} = {}", origin, event.key(), event.value());
            }
            default -> { }
        }
    }

    /**
     * Unsigned, or from a machine not on the roster. Refused, unless the transition setting is on: then a
     * replicated account or invite arrives as a MEMBER whatever role it names, and removals and setting
     * changes are still refused. Before 2026-09-28 a device on the Wi-Fi could publish itself a steward
     * account here.
     */
    private void applyUntrusted(String subject, JsonNode node, String why) throws IOException {
        if (!acceptUnsigned) {
            log.warn("Refused a replicated {}: {}. (Set WYRDSEKAI_ACCOUNTS_ACCEPT_UNSIGNED=true only while older "
                + "household machines are upgraded.)", subject, why);
            return;
        }
        switch (subject) {
            case "account.created" -> {
                var event = MAPPER.treeToValue(node, AccountCreated.class);
                if (authService.findUserByUsername(event.username()).isPresent()) return;
                log.warn("Taking an untrusted replicated account '{}' as a member ({}; WYRDSEKAI_ACCOUNTS_ACCEPT_UNSIGNED=true)",
                    event.username(), why);
                authService.registerWithHash(event.userId(), event.username(),
                    event.passwordHash(), event.displayName(), MEMBER);
            }
            case "account.invite.created" -> {
                var event = MAPPER.treeToValue(node, InviteCreated.class);
                if (event.code() == null) return;
                log.warn("Taking an untrusted replicated invite for '{}' as a member invite ({})", event.intendedName(), why);
                inviteService.replicateInvite(event.id(), event.code(), event.intendedName(),
                    MEMBER, event.createdBy(), event.expiresAt());
            }
            case "account.invite.consumed" -> {
                var event = MAPPER.treeToValue(node, InviteConsumed.class);
                if (event.code() != null) inviteService.redeemInvite(event.code(), event.consumedBy());
            }
            case "account.removed", "account.config.changed" ->
                log.warn("Refused a replicated {}: {}; change it on this machine", subject, why);
            default -> { }
        }
    }

    private boolean stewardHere(String userId) {
        return userId != null && authService.findUser(userId).map(u -> STEWARD.equals(u.role())).orElse(false);
    }

    // ── Internal ──

    private void publish(String subject, Object event) {
        try {
            var payload = MAPPER.createObjectNode();
            payload.put("subject", subject);
            payload.set("event", MAPPER.valueToTree(event));
            publisher.accept(subject, BetweenEnvelope.create(localNodeId, null, payload, identity).toBytes());
        } catch (Exception e) {
            log.warn("Failed to publish {}: {}", subject, e.getMessage());
        }
    }
}
