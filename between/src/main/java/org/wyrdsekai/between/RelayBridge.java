package org.wyrdsekai.between;

import io.nats.client.*;
import io.nats.client.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Bridges a local NATS server with a remote relay NATS server.
 * <p>
 * All messages on {@code between.{zoneId}.>} are forwarded in both directions:
 * local -> relay and relay -> local. Deduplication prevents infinite loops
 * by checking the {@code src} field in Between envelopes — messages that
 * originated from this node are not re-published back to the side they came from.
 * <p>
 * The relay is a "dumb pipe" NATS server (see deploy/relay/relay.conf).
 * Authentication uses NATS user/password from the relay token.
 * <p>
 * Lifecycle: created after local NatsBridge connects. Closed on shutdown.
 */
public final class RelayBridge implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RelayBridge.class);

    private final String relayUrl;
    private final String localNatsUrl;
    private final String zoneId;
    private final String localNodeId;
    private final String authUser;
    private final String authPassword;
    /**
     * when present, supplies a NATS NKey AuthHandler instead of
     * user/password for relay connection. {@link NodeIdentity#nkeyAuthHandler()}
     * produces this. Null in legacy/test paths; in that case we fall back to
     * {@link #authUser}/{@link #authPassword}. Both modes coexist while we
     * migrate the mesh; Phase 4 retires password mode entirely.
     */
    private final NodeIdentity nodeIdentity;

    /**
     * shared across a zone's relay legs to drop a
     * duplicate inbound envelope that arrived over more than one shared relay.
     * Null for a single-leg zone (no dedup needed — behavior unchanged).
     */
    private final RelaySeenSet inboundDedup;

    /**
     * (privacy rail R1): false for a PUBLIC leg.
     * A zone never broadcasts its {@code federation.*.gate.*} traffic out a
     * public relay — that namespace carries who-talks-to-whom metadata and must
     * stay off a bus shared with strangers. Public legs still carry phone/public
     * client sessions (their actual job); only federation egress is withheld.
     * Defaults true (private/LAN legs and the legacy single-relay case).
     */
    private final boolean forwardFederationEgress;

    private Connection relayConnection;
    private Connection localListenerConnection;
    private final Set<String> subscribedRemoteZones = ConcurrentHashMap.newKeySet();

    /**
     * True if local NATS and relay NATS point at the same server. In that
     * topology there is nothing to bridge — every subscriber on the shared
     * server already sees every message directly via {@link NatsBridge}. We
     * skip the bidirectional forwarding dispatchers to avoid a publish→echo
     * loop where each message is re-published to the same server it just
     * arrived on (the src-based dedup assumes two distinct servers).
     */
    private boolean sameServerCollapsed = false;

    /**
     * @param relayUrl     NATS URL of the relay server (e.g. "nats://relay.example.com:4222")
     * @param localNatsUrl NATS URL of the local server (e.g. "nats://127.0.0.1:4222")
     * @param zoneId       Household zone ID (scopes NATS subjects)
     * @param localNodeId  This node's ID (for dedup)
     * @param authUser     NATS user for relay auth (e.g. "hh-{zoneId}")
     * @param authPassword NATS password for relay auth (the relay token)
     */
    public RelayBridge(String relayUrl, String localNatsUrl, String zoneId,
                       String localNodeId, String authUser, String authPassword) {
        this(relayUrl, localNatsUrl, zoneId, localNodeId, authUser, authPassword, null);
    }

    /**: per-leg bridge sharing an inbound-dedup set. */
    public RelayBridge(String relayUrl, String localNatsUrl, String zoneId,
                       String localNodeId, String authUser, String authPassword,
                       NodeIdentity nodeIdentity) {
        this(relayUrl, localNatsUrl, zoneId, localNodeId, authUser, authPassword,
            nodeIdentity, null);
    }

    /**
     * constructor — pass a {@link NodeIdentity} to use NKey auth.
     * When {@code nodeIdentity} is non-null, {@link #start()} configures NATS with
     * {@link Options.Builder#authHandler} via {@link NodeIdentity#nkeyAuthHandler()};
     * the {@code authUser}/{@code authPassword} are ignored. When null, the bridge
     * falls back to legacy user/password auth (for backwards compat / tests).
     *
     * <p>Both modes are exercised in production during the mesh migration — relay
     * accepts either NKey or password users in the same {@code users = []} array.
     * Phase 4 retires password mode.</p>
     */
    public RelayBridge(String relayUrl, String localNatsUrl, String zoneId,
                       String localNodeId, String authUser, String authPassword,
                       NodeIdentity nodeIdentity, RelaySeenSet inboundDedup) {
        this(relayUrl, localNatsUrl, zoneId, localNodeId, authUser, authPassword,
            nodeIdentity, inboundDedup, true);
    }

    /**: {@code forwardFederationEgress=false} for a PUBLIC leg. */
    public RelayBridge(String relayUrl, String localNatsUrl, String zoneId,
                       String localNodeId, String authUser, String authPassword,
                       NodeIdentity nodeIdentity, RelaySeenSet inboundDedup,
                       boolean forwardFederationEgress) {
        this.relayUrl = relayUrl;
        this.localNatsUrl = localNatsUrl;
        this.zoneId = zoneId;
        this.localNodeId = localNodeId;
        this.authUser = authUser;
        this.authPassword = authPassword;
        this.nodeIdentity = nodeIdentity;
        this.inboundDedup = inboundDedup;
        this.forwardFederationEgress = forwardFederationEgress;
    }

    /**
     * Connect to the relay and start bidirectional message forwarding.
     */
    public void start() throws IOException, InterruptedException {
        var shortId = localNodeId.substring(0, Math.min(8, localNodeId.length()));
        var subject = "between." + zoneId + ".>";

        // Same-server safety: if both sides of the bridge point at the same
        // NATS URL, the dispatchers below would form a publish→echo loop
        // (every forwarded message re-enters the same subscription). Detect
        // and collapse into a single connection with no forwarding.
        this.sameServerCollapsed = urlsResolveSame(relayUrl, localNatsUrl);
        if (sameServerCollapsed) {
            log.warn("Relay bridge: local NATS ({}) and relay ({}) resolve to the same server — "
                + "bridge forwarding disabled (would loop). NatsBridge already sees all traffic. "
                + "For true multi-zone federation, run a separate local NATS (e.g. 127.0.0.1:4222) "
                + "on each node and keep the relay URL distinct.", localNatsUrl, relayUrl);
        }

        // 1. Connect to relay NATS with auth
        var relayBuilder = new Options.Builder()
            .server(relayUrl)
            .connectionName("wyrd-relay-" + shortId)
            .maxReconnects(-1)
            .reconnectWait(Duration.ofSeconds(5))
            .pingInterval(Duration.ofSeconds(30))
            .maxPingsOut(10)
            .connectionListener((conn, type) -> {
                switch (type) {
                    case CONNECTED -> log.info("Relay bridge connected to {}", relayUrl);
                    case RECONNECTED -> log.info("Relay bridge reconnected");
                    case DISCONNECTED -> log.warn("Relay bridge disconnected");
                    case CLOSED -> log.info("Relay bridge connection closed");
                    default -> log.debug("Relay bridge event: {}", type);
                }
            })
            .errorListener(new ErrorListener() {
                @Override
                public void errorOccurred(Connection conn, String error) {
                    log.error("Relay bridge error: {}", error);
                }
                @Override
                public void exceptionOccurred(Connection conn, Exception exp) {
                    log.error("Relay bridge exception", exp);
                }
                @Override
                public void slowConsumerDetected(Connection conn, Consumer consumer) {
                    log.warn("Relay bridge slow consumer");
                }
            });

        // prefer NKey auth when NodeIdentity is wired, fall back to
        // user/password for legacy / pre-migration paths. The relay accepts both in
        // the same users array, so a mesh in transition is fine.
        if (nodeIdentity != null) {
            relayBuilder.authHandler(nodeIdentity.nkeyAuthHandler());
            log.info("Relay bridge: NKey auth (pubkey={})",
                truncatePubkey(nodeIdentity.nkeyPublicKey()));
        } else if (authUser != null && !authUser.isEmpty()) {
            // deprecation:
            // password-mode is on its way out. Log a WARN every time it's used so
            // operators can spot it in startup logs and migrate. If
            // WYRDSEKAI_RELAY_PASSWORD_DEPRECATION_DATE (ISO-8601 date, e.g.
            // 2026-09-01) is set and today is past it, REFUSE password-mode at
            // startup — the migration window has closed. Default = no enforcement.
            enforcePasswordDeprecation();
            log.warn("Relay bridge: password auth (user={}). DEPRECATED — migrate to NKey: "
                + "`wyrd relay register-nkey <invite-url>`. A future release will remove "
                + "password support; set WYRDSEKAI_RELAY_PASSWORD_DEPRECATION_DATE to enforce "
                + "rejection after a date.", authUser);
            relayBuilder.userInfo(authUser, authPassword != null ? authPassword : "");
        }

        relayConnection = Nats.connect(relayBuilder.build());

        // Same-server short-circuit: relay connection is enough; everything
        // else (federation gate, between.zone.> forwarding, remote-zone
        // subscription) would echo on itself. NatsBridge delivers all traffic.
        if (sameServerCollapsed) {
            log.info("Relay bridge: single-server mode — skipping bidirectional forwarding "
                + "and remote-zone subscription dispatchers.");
            return;
        }

        // 2. Connect a second client to local NATS for bridge forwarding.
        //    We need our own connection so we see ALL local messages (the main
        //    NatsBridge filters out messages from our own nodeId).
        var localBuilder = new Options.Builder()
            .server(localNatsUrl)
            .connectionName("wyrd-relay-fwd-" + shortId)
            .maxReconnects(-1)
            .reconnectWait(Duration.ofSeconds(2))
            .build();

        localListenerConnection = Nats.connect(localBuilder);

        // 3. Local -> Relay: forward messages that originated from this node.
        //    Skip zone-internal high-frequency messages (heartbeats, room primaries)
        //    that are only relevant to same-zone peers. These waste relay bandwidth
        //    and cause connection degradation when nobody on the relay subscribes.
        localListenerConnection.createDispatcher(msg -> {
            try {
                // Skip zone-internal traffic that doesn't belong on the relay
                var sub = msg.getSubject();
                if (sub.contains(".cluster.heartbeat") || sub.contains(".room.primary.heartbeat")) return;

                var envelope = BetweenEnvelope.fromBytes(msg.getData());
                if (!localNodeId.equals(envelope.src())) return;

                if (relayConnection != null && relayConnection.getStatus() == Connection.Status.CONNECTED) {
                    relayConnection.publish(msg.getSubject(), msg.getData());
                }
            } catch (Exception e) {
                log.debug("Local->relay forward error on {}: {}", msg.getSubject(), e.getMessage());
            }
        }).subscribe(subject);

        // 4. Relay -> Local: forward messages from remote nodes (phones, other peers).
        //    Same filter — skip zone-internal heartbeats.
        relayConnection.createDispatcher(msg -> {
            try {
                var sub = msg.getSubject();
                if (sub.contains(".cluster.heartbeat") || sub.contains(".room.primary.heartbeat")) return;

                var envelope = BetweenEnvelope.fromBytes(msg.getData());
                if (localNodeId.equals(envelope.src())) return;
                // Multi-homing: drop a duplicate that already arrived via another leg.
                if (inboundDedup != null && !inboundDedup.firstSight(envelope.sig())) return;

                if (localListenerConnection != null
                        && localListenerConnection.getStatus() == Connection.Status.CONNECTED) {
                    localListenerConnection.publish(msg.getSubject(), msg.getData());
                }
            } catch (Exception e) {
                log.debug("Relay->local forward error on {}: {}", msg.getSubject(), e.getMessage());
            }
        }).subscribe(subject);

        log.info("Relay bridge started — bidirectional forwarding on between.{}.>", zoneId);

        // 5/6. Federation forwarding.
        //
        // Scope: ONLY envelope-wrapped gate messages (propose / accept / revoke /
        // manifest / transit). These are the subjects FederationActor subscribes
        // to on local NATS and needs to see bidirectionally with the relay.
        //
        // Deliberately NOT `federation.>`: that wildcard also matches
        //   - `federation.inference.*.complete` and `federation.inference.stream.*`
        //     (NatsInferenceClient/Server) — these travel directly on the relay
        //     connection via RelaySessionTransport.
        //   - `federation.*.session.*` — VirtualSessionHandler, same story.
        //
        // If we forwarded those, the src/dst check below would misclassify them
        // (they're not BetweenEnvelope JSON; @JsonIgnoreProperties-lenient parse
        // yields a null-src envelope, which `!localNodeId.equals(null)` treats
        // as "outbound from this node"), triggering a large-scale amplification
        // loop between relay and local NATS that showed up as thousands of
        // redeliveries of a single inference request. See task #264 / #156.
        //
        // Gate subjects are explicitly envelope-wrapped and carry this node's
        // src when outbound, so the src-based dedup works correctly.
        //
        // Phase-1 dual form:
        //   Legacy    : federation.{zoneId}.gate.*               → `federation.*.gate.>`
        //   Canonical : federation.{fingerprint}.{label}.gate.*  → `federation.*.*.gate.>`
        //
        // NATS wildcards match a single token, so the legacy pattern won't
        // match the canonical form (it has one extra token between prefix and
        // `gate`). We subscribe to both and forward through the same handler.
        // Same-subject republish works for both because NATS preserves the
        // original subject, and the src-dedup rule applies unchanged.
        var legacyGatePattern = "federation.*.gate.>";
        var canonicalGatePattern = "federation.*.*.gate.>";

        BiConsumer<String, Connection> subscribeRelayToLocal =
            (pattern, ignored) -> relayConnection.createDispatcher(msg -> {
                try {
                    var envelope = BetweenEnvelope.fromBytes(msg.getData());
                    if (envelope.src() == null) return;
                    if (localNodeId.equals(envelope.src())) return;
                    // Multi-homing: drop a gate message already delivered via another leg.
                    if (inboundDedup != null && !inboundDedup.firstSight(envelope.sig())) return;

                    if (localListenerConnection != null
                            && localListenerConnection.getStatus() == Connection.Status.CONNECTED) {
                        localListenerConnection.publish(msg.getSubject(), msg.getData());
                    }
                } catch (Exception e) {
                    log.debug("Relay->local federation forward error on {}: {}",
                        msg.getSubject(), e.getMessage());
                }
            }).subscribe(pattern);

        BiConsumer<String, Connection> subscribeLocalToRelay =
            (pattern, ignored) -> localListenerConnection.createDispatcher(msg -> {
                try {
                    var envelope = BetweenEnvelope.fromBytes(msg.getData());
                    if (envelope.src() == null) return;
                    if (!localNodeId.equals(envelope.src())) return;

                    if (relayConnection != null
                            && relayConnection.getStatus() == Connection.Status.CONNECTED) {
                        relayConnection.publish(msg.getSubject(), msg.getData());
                    }
                } catch (Exception e) {
                    log.debug("Local->relay federation forward error on {}: {}",
                        msg.getSubject(), e.getMessage());
                }
            }).subscribe(pattern);

        // 5. Relay -> Local: inbound gate messages (proposals, accepts, etc.)
        subscribeRelayToLocal.accept(legacyGatePattern, relayConnection);
        subscribeRelayToLocal.accept(canonicalGatePattern, relayConnection);

        // 6. Local -> Relay: outbound gate messages from this node only.
        // (privacy rail R1): withheld on a PUBLIC
        //    leg so this zone's federation metadata never lands on a commons bus.
        if (forwardFederationEgress) {
            subscribeLocalToRelay.accept(legacyGatePattern, localListenerConnection);
            subscribeLocalToRelay.accept(canonicalGatePattern, localListenerConnection);
            log.info("Relay bridge: federation gate forwarding active on '{}' + '{}'",
                legacyGatePattern, canonicalGatePattern);
        } else {
            log.info("Relay bridge: federation gate EGRESS withheld on public leg {} "
                + "(privacy rail) — inbound gate still accepted", relayUrl);
        }
    }

    /**
     * Subscribe to a remote zone's namespace on the relay.
     * Called when federation is activated with that zone.
     * Enables cross-zone peer discovery, heartbeats, and room events.
     */
    public void subscribeRemoteZone(String remoteZoneId) {
        if (sameServerCollapsed) {
            // Everyone shares one NATS server — the remote zone's publications
            // are already visible via NatsBridge; adding a forwarding
            // subscription here would just echo.
            log.debug("Relay bridge: subscribeRemoteZone('{}') is a no-op in single-server mode",
                remoteZoneId);
            return;
        }
        if (relayConnection == null || relayConnection.getStatus() != Connection.Status.CONNECTED) {
            log.warn("Cannot subscribe to remote zone '{}' — relay not connected", remoteZoneId);
            return;
        }
        if (remoteZoneId.equals(zoneId)) {
            return; // don't subscribe to own zone on relay
        }
        if (!subscribedRemoteZones.add(remoteZoneId)) {
            log.debug("Already subscribed to remote zone '{}' — skipping", remoteZoneId);
            return; // idempotent
        }

        // Only subscribe to capability announcements from the remote zone.
        // Room primaries, cluster heartbeats, soul messages, etc. are zone-internal
        // and must NOT leak across zones — they pollute the local RoomPrimaryProtocol.
        var remoteSubject = "between." + remoteZoneId + ".*.*.capability.announce";

        // Relay → Local: forward remote zone's capability announcements to local NATS
        relayConnection.createDispatcher(msg -> {
            try {
                if (localListenerConnection != null
                        && localListenerConnection.getStatus() == Connection.Status.CONNECTED) {
                    localListenerConnection.publish(msg.getSubject(), msg.getData());
                }
            } catch (Exception e) {
                log.debug("Relay->local remote zone forward error on {}: {}", msg.getSubject(), e.getMessage());
            }
        }).subscribe(remoteSubject);

        log.info("Relay bridge: subscribed to remote zone '{}' capabilities via relay ({})",
            remoteZoneId, remoteSubject);
    }

    public boolean isConnected() {
        return relayConnection != null && relayConnection.getStatus() == Connection.Status.CONNECTED;
    }

    /**
     * Get the raw relay NATS connection for direct point-to-point messaging.
     * Used by session proxy (RemoteZoneSession / VirtualSessionHandler) to
     * bypass the relay bridge forwarding and avoid feedback loops.
     */
    public Connection relayConnection() {
        return relayConnection;
    }

    /**
     * Normalise two NATS URLs and report whether they resolve to the same
     * server endpoint (host+port). Handles the common "nats://" vs bare
     * "host:port" forms, defaults the port to 4222, and lowercases the host.
     * Does not perform DNS resolution — two different DNS names pointing at
     * the same box are treated as distinct servers (safer default).
     */
    static boolean urlsResolveSame(String a, String b) {
        if (a == null || b == null) return false;
        return normalizeNatsUrl(a).equals(normalizeNatsUrl(b));
    }

    private static String normalizeNatsUrl(String url) {
        var s = url.trim();
        int scheme = s.indexOf("://");
        if (scheme >= 0) s = s.substring(scheme + 3);
        // Strip user:pass@ prefix if present (rare here, but harmless).
        int at = s.indexOf('@');
        if (at >= 0) s = s.substring(at + 1);
        // Strip any path/query.
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);
        // Default port.
        if (!s.contains(":")) s = s + ":4222";
        return s.toLowerCase(Locale.ROOT);
    }

    @Override
    public void close() {
        if (localListenerConnection != null) {
            try {
                localListenerConnection.close();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            localListenerConnection = null;
        }
        if (relayConnection != null) {
            try {
                relayConnection.close();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            relayConnection = null;
        }
        log.info("Relay bridge closed");
    }

    /** First 8 + last 4 chars of a NATS NKey pubkey, for log readability without revealing full key. */
    private static String truncatePubkey(String pubkey) {
        if (pubkey == null || pubkey.length() < 16) return pubkey;
        return pubkey.substring(0, 8) + "…" + pubkey.substring(pubkey.length() - 4);
    }

    /**
     * hard cut-off for password-mode after a configured
     * date. Reads {@code WYRDSEKAI_RELAY_PASSWORD_DEPRECATION_DATE} (ISO-8601
     * {@code yyyy-MM-dd}); if today is on or after that date, throws to abort
     * startup. Default: unset → no enforcement (warnings only).
     *
     * <p>Why an env var instead of a hard-coded date: each operator sets their
     * own migration window. Default: warnings only (set the env var to bind a
     * deadline). Production rollout flips the default to a future date in a
     * later release after Phase 2 is verified live across the mesh.</p>
     *
     * <p>Package-private for tests to inject a clock.</p>
     */
    static void enforcePasswordDeprecation() {
        var raw = System.getenv("WYRDSEKAI_RELAY_PASSWORD_DEPRECATION_DATE");
        if (raw == null || raw.isBlank()) return;
        try {
            var deadline = LocalDate.parse(raw.trim());
            var today = LocalDate.now();
            if (!today.isBefore(deadline)) {
                throw new IllegalStateException(
                    "Relay password auth is past its deprecation date ("
                        + raw + "; today is " + today + "). "
                        + "Run `wyrd relay register-nkey <invite-url>` to migrate. "
                        + "Or to extend the window, set "
                        + "WYRDSEKAI_RELAY_PASSWORD_DEPRECATION_DATE to a future date.");
            }
            log.info("Relay bridge: password auth still allowed; deprecation date {} (today {})",
                raw, today);
        } catch (DateTimeParseException e) {
            log.warn("Ignoring malformed WYRDSEKAI_RELAY_PASSWORD_DEPRECATION_DATE='{}' "
                + "(expected yyyy-MM-dd): {}", raw, e.getMessage());
        }
    }
}
