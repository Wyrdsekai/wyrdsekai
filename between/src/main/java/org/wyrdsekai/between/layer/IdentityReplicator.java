package org.wyrdsekai.between.layer;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.between.NatsBridge;
import org.wyrdsekai.core.persistence.AuthService;
import org.wyrdsekai.core.persistence.InviteService;

import java.time.Instant;

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
 */
public final class IdentityReplicator {

    private static final Logger log = LoggerFactory.getLogger(IdentityReplicator.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    /** JetStream stream name for account events. */
    public static final String STREAM_NAME = "WYRD_ACCOUNTS";
    private static final String SUBJECT_PREFIX = "account.";

    // ── Event records ──

    public record AccountCreated(
        @JsonProperty("userId") String userId,
        @JsonProperty("username") String username,
        @JsonProperty("passwordHash") String passwordHash,
        @JsonProperty("displayName") String displayName,
        @JsonProperty("role") String role,
        @JsonProperty("sourceNodeId") String sourceNodeId,
        @JsonProperty("timestamp") Instant timestamp
    ) {
        @JsonCreator public AccountCreated {}
    }

    public record AccountRemoved(
        @JsonProperty("userId") String userId,
        @JsonProperty("sourceNodeId") String sourceNodeId,
        @JsonProperty("timestamp") Instant timestamp
    ) {
        @JsonCreator public AccountRemoved {}
    }

    public record InviteCreated(
        @JsonProperty("id") String id,
        @JsonProperty("code") String code,
        @JsonProperty("intendedName") String intendedName,
        @JsonProperty("role") String role,
        @JsonProperty("createdBy") String createdBy,
        @JsonProperty("expiresAt") Instant expiresAt,
        @JsonProperty("sourceNodeId") String sourceNodeId,
        @JsonProperty("timestamp") Instant timestamp
    ) {
        @JsonCreator public InviteCreated {}
    }

    public record InviteConsumed(
        @JsonProperty("code") String code,
        @JsonProperty("consumedBy") String consumedBy,
        @JsonProperty("sourceNodeId") String sourceNodeId,
        @JsonProperty("timestamp") Instant timestamp
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
    private final String localNodeId;
    private final AuthService authService;
    private final InviteService inviteService;

    public IdentityReplicator(NatsBridge nats, String localNodeId,
                              AuthService authService, InviteService inviteService) {
        this.nats = nats;
        this.localNodeId = localNodeId;
        this.authService = authService;
        this.inviteService = inviteService;
    }

    // ── Publishing (JetStream — persistent) ──

    /** Call after a new account is created locally. */
    public void publishAccountCreated(String userId, String username, String passwordHash,
                                       String displayName, String role) {
        var event = new AccountCreated(userId, username, passwordHash, displayName, role,
            localNodeId, Instant.now());
        publish("account.created", event);
        log.info("Replicated account.created: {} ({})", username, role);
    }

    /** Call after an account is removed locally. */
    public void publishAccountRemoved(String userId) {
        publish("account.removed", new AccountRemoved(userId, localNodeId, Instant.now()));
        log.info("Replicated account.removed: {}", userId);
    }

    /** Call after an invite is created locally. */
    public void publishInviteCreated(InviteService.Invite invite) {
        var event = new InviteCreated(invite.id(), invite.code(), invite.intendedName(),
            invite.role(), invite.createdBy(), invite.expiresAt(),
            localNodeId, Instant.now());
        publish("account.invite.created", event);
        log.info("Replicated invite.created for '{}'", invite.intendedName());
    }

    /** Call after an invite is consumed locally. */
    public void publishInviteConsumed(String code, String consumedBy) {
        publish("account.invite.consumed",
            new InviteConsumed(code, consumedBy, localNodeId, Instant.now()));
    }

    /** Call after household config is changed locally. */
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
                var data = msg.getData();
                var subject = msg.getSubject();

                // Parse the event based on subject
                if (subject.equals("account.created")) {
                    var event = MAPPER.readValue(data, AccountCreated.class);
                    if (localNodeId.equals(event.sourceNodeId())) return; // ignore own
                    if (authService.findUserByUsername(event.username()).isPresent()) return;
                    // (revised) — the zone steward is the household
                    // SUPER-ADMIN on EVERY household node, so account replication
                    // carries the role as authority within the (trusted, household-
                    // scoped) mesh: a steward who created the account on the origin
                    // node is a steward here too — they can manage members, rooms,
                    // and nodes across the whole household. Members replicate as
                    // members. The ONE carve-out is a member's PRIVATE Study, which
                    // is gated separately by owner DID + token (StudySyncPeer), so a
                    // steward's super-admin authority never reaches a member's Study.
                    var localRole = event.role() != null && !event.role().isBlank()
                        ? event.role() : "member";
                    authService.registerWithHash(event.userId(), event.username(),
                        event.passwordHash(), event.displayName(), localRole);
                    log.info("Replicated remote identity: {} (role={})",
                        event.username(), localRole);

                } else if (subject.equals("account.removed")) {
                    var event = MAPPER.readValue(data, AccountRemoved.class);
                    if (localNodeId.equals(event.sourceNodeId())) return;
                    authService.removeUserDirect(event.userId());
                    log.info("Replicated remote account removal: {}", event.userId());

                } else if (subject.equals("account.invite.created")) {
                    var event = MAPPER.readValue(data, InviteCreated.class);
                    if (localNodeId.equals(event.sourceNodeId())) return;
                    inviteService.replicateInvite(event.id(), event.code(), event.intendedName(),
                        event.role(), event.createdBy(), event.expiresAt());
                    log.info("Replicated remote invite for '{}'", event.intendedName());

                } else if (subject.equals("account.invite.consumed")) {
                    var event = MAPPER.readValue(data, InviteConsumed.class);
                    if (localNodeId.equals(event.sourceNodeId())) return;
                    inviteService.redeemInvite(event.code(), event.consumedBy());

                } else if (subject.equals("account.config.changed")) {
                    var event = MAPPER.readValue(data, ConfigChanged.class);
                    if (localNodeId.equals(event.sourceNodeId())) return;
                    authService.setConfig(event.key(), event.value(), event.updatedBy());
                    log.info("Replicated remote config: {} = {}", event.key(), event.value());
                }
            } catch (Exception e) {
                log.warn("Failed to process account event on {}: {}", msg.getSubject(), e.getMessage());
            }
        });

        log.info("IdentityReplicator: JetStream replication started (stream={}, durable={})",
            STREAM_NAME, durableName);
    }

    // ── Internal ──

    private void publish(String subject, Object event) {
        try {
            nats.jetStreamPublish(subject, MAPPER.writeValueAsBytes(event));
        } catch (Exception e) {
            log.warn("Failed to publish {}: {}", subject, e.getMessage());
        }
    }
}
