package org.wyrdsekai.between.federation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.between.BetweenEnvelope;
import org.wyrdsekai.between.NatsBridge;
import org.wyrdsekai.between.NodeIdentity;
import org.wyrdsekai.common.model.AppVersion;
import org.wyrdsekai.core.config.WyrdConfig;
import org.wyrdsekai.core.naming.BlockListService;
import org.wyrdsekai.core.naming.EnvelopeVerificationMode;
import org.wyrdsekai.core.naming.FederationSubjects;
import org.wyrdsekai.core.naming.HouseholdIdentity;
import org.wyrdsekai.core.naming.ZoneAddressResolverService;
import org.wyrdsekai.core.soul.SoulTransitProtocol;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages zone-to-zone federation: bilateral agreements, zone manifest exchange,
 * and agent transit authorization.
 *
 * Child actor of BetweenActor. Receives NATS bridge on initialization.
 *
 * NATS subjects:
 *   federation.{zoneId}.gate.propose           — inbound proposals
 *   federation.{zoneId}.gate.accept            — inbound acceptances
 *   federation.{zoneId}.gate.revoke            — inbound revocations
 *   federation.{zoneId}.gate.manifest          — zone manifest advertisements
 *   federation.{zoneId}.gate.transit_request   — inbound transit requests (as destination)
 *   federation.{zoneId}.gate.transit_response  — inbound transit responses (as source)
 *
 * Transit subjects are split so destinations only receive requests and sources
 * only receive responses. Prevents subject-overlap loops when a pattern bridge
 * is misconfigured (local NATS == relay NATS).
 */
public class FederationActor extends AbstractBehavior<FederationActor.Command> {

    private static final Logger log = LoggerFactory.getLogger(FederationActor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    // --- Protocol ---

    public sealed interface Command {}

    /** Initialize with NATS bridge (sent by parent BetweenActor). */
    public record Initialize(
        NatsBridge natsBridge,
        NodeIdentity identity,
        String zoneId,
        String zoneName,
        FederationService service
    ) implements Command {}

    /** Inbound federation message from NATS. */
    public record FederationMessageReceived(
        String subject,
        BetweenEnvelope envelope
    ) implements Command {}

    /** Periodic cleanup of expired tokens. */
    private record CleanupTick() implements Command {}

    // --- Commands from room scripts (via BetweenActor proxy) ---

    /** Get federation status. */
    public record GetStatus(ActorRef<StatusResult> replyTo) implements Command {}

    /** Propose a bilateral agreement to a remote zone. */
    public record Propose(String targetZoneId, ActorRef<String> replyTo) implements Command {}

    /** Accept a pending proposal from a remote zone. */
    public record Accept(String remoteZoneId, ActorRef<String> replyTo) implements Command {}

    /** Revoke an active agreement. */
    public record Revoke(String remoteZoneId, ActorRef<String> replyTo) implements Command {}

    /** Request transit to a remote zone.
     *  agentDid and transitMode are optional soul-aware fields (Phase 5). */
    public record RequestTransit(
        String targetZoneId, String agentId, String agentName,
        String agentDid, String transitMode,
        ActorRef<TransitResult> replyTo
    ) implements Command {
        /** Backward-compatible constructor without soul fields. */
        public RequestTransit(String targetZoneId, String agentId, String agentName,
                              ActorRef<TransitResult> replyTo) {
            this(targetZoneId, agentId, agentName, null, null, replyTo);
        }
    }

    /** List currently visiting agents. */
    public record ListVisitors(ActorRef<String> replyTo) implements Command {}

    /** Internal: transit request timeout. */
    private record TransitTimeout(String agentId) implements Command {}

    /**
     * Internal: partner replied to our agreement-state probe.
     * routed via NATS, lifted onto the actor's
     * own command stream so we resolve the pending probe under actor
     * supervision (no thread-safety concerns on pendingProbes).
     */
    private record AgreementQueryReply(String queryId, String partnerStatus) implements Command {}

    /** Internal: agreement-state probe to a partner timed out. F6. */
    private record AgreementQueryTimeout(String queryId) implements Command {}

    /**
     * mesh-state visibility command. Fans out
     * agreement_query probes (F6 protocol) to every locally-recorded
     * partner in parallel and reconciles the replies into a unified view.
     * A single ✗ in the result is the deployment-blocker that today's
     * single-zone {@code wyrd federate list} can't see.
     */
    public record MeshStatus(ActorRef<MeshStatusResult> replyTo) implements Command {}

    /** F12: per-partner consensus row. */
    public record MeshEntry(
        String partnerZoneId,
        String localStatus,    // ACTIVE / PENDING / REVOKED / NONE
        String partnerStatus,  // ACTIVE / PENDING / NONE / "?" (unreachable)
        String consensus       // "agree" | "mismatch" | "unreachable"
    ) {}

    /** F12: aggregate mesh-status reply. */
    public record MeshStatusResult(
        String localZoneId,
        List<MeshEntry> entries,
        Instant probedAt
    ) {}

    /** Internal: a mesh-batch query timed out before all partners replied. F12. */
    private record MeshQueryTimeout(String batchId) implements Command {}

    /**
     * install the inbound CompanionRelocate sink.
     * Main.java sets this at startup with a callback that delegates to
     * {@code ZoneGuardian.RelocateCompanion.arrive(...)}. Without it, an
     * inbound relocate is logged + ack'd as rejected.
     */
    public record SetRelocateSink(CompanionRelocateSink sink) implements Command {}

    /**
     * outbound publisher hook. Sends a
     * {@code CompanionRelocateMsg} to the target zone via natsBridge.
     */
    public record PublishCompanionRelocate(
        TransitToken token,
        String stateJson,
        String bondholderDid,
        String targetRoomHint
    ) implements Command {}

    /**
     * Sink for inbound CompanionRelocate envelopes. Implementation lives in
     * server/Main.java where ZoneGuardian is reachable; FederationActor
     * doesn't depend on core/room/ZoneGuardian.
     */
    @FunctionalInterface
    public interface CompanionRelocateSink {
        /**
         * @return landing room id on success, {@code null} if rejected. The
         *     reason for rejection is logged by the sink itself.
         */
        String accept(TransitToken token, String stateJson,
                      String bondholderDid, String targetRoomHint);
    }

    /**
     * (loss-safety, spec/tla/TransitToken.tla P1) —
     * install the source-side handler for an inbound arrival ack. Main.java
     * delegates this to {@code ZoneGuardian.CompanionArrivedAck} so the source
     * releases the retained snapshot once the target confirms the companion
     * landed. Without it, an inbound ack is logged only (legacy behavior).
     */
    public record SetRelocateAckSink(RelocateAckSink sink) implements Command {}

    /** Source-side callback fired when a target confirms an arrival. */
    @FunctionalInterface
    public interface RelocateAckSink {
        void onArrived(String entityId, String agentDid, long transitEpoch,
                       String fromZoneId, boolean accepted);
    }

    // --- Response types ---

    public record StatusResult(String description, int federatedZoneCount) {}

    public record TransitResult(
        boolean allowed, String transitToken, String targetUrl, String reason
    ) {}

    // --- State ---

    private final TimerScheduler<Command> timers;
    private NatsBridge natsBridge;
    private NodeIdentity identity;
    private String zoneId;
    private String zoneName;
    private FederationService service;
    /** — late-bound inbound relocate sink. */
    private CompanionRelocateSink relocateSink;
    /** — late-bound source-side arrival-ack sink. */
    private RelocateAckSink relocateAckSink;
    private final Map<String, ZoneManifest> knownZones = new ConcurrentHashMap<>();
    /** Pending transit requests awaiting response from destination zone: agentId → replyTo. */
    private final Map<String, ActorRef<TransitResult>> pendingTransitRequests = new ConcurrentHashMap<>();
    /**
     * Pending agreement-state probes — F6 stale-state reconciliation.
     * Keyed by queryId; resolved either by {@link AgreementQueryReply}
     * (partner answered) or {@link AgreementQueryTimeout} (defensive
     * re-propose path). Never grows unbounded — every entry is removed
     * on resolve or timeout.
     */
    private final Map<String, PendingProbe> pendingProbes = new ConcurrentHashMap<>();
    /**
     * F12: in-flight mesh-status batches. Each batch has N outstanding
     * agreement_query probes; replies arrive via the same wire as F6
     * single-shot probes and are routed by queryId. When the last
     * outstanding probe resolves (or the batch timer fires), the
     * accumulated result is sent to the requester and the batch removed.
     */
    private final Map<String, PendingMeshBatch> pendingMeshBatches = new ConcurrentHashMap<>();
    private boolean initialized = false;

    /** Captured state for an in-flight agreement-state probe. F6. */
    private record PendingProbe(Propose original, String targetZoneId) {}

    /** F12: in-flight mesh-status batch — accumulates partner replies. */
    private static final class PendingMeshBatch {
        final ActorRef<MeshStatusResult> replyTo;
        final Map<String, String> queryIdToPartner = new HashMap<>();
        final Map<String, String> localStatusByPartner = new LinkedHashMap<>();
        final Map<String, String> partnerReplies = new HashMap<>();
        PendingMeshBatch(ActorRef<MeshStatusResult> replyTo) { this.replyTo = replyTo; }
    }

    private FederationActor(ActorContext<Command> context, TimerScheduler<Command> timers) {
        super(context);
        this.timers = timers;
    }

    public static Behavior<Command> create() {
        return Behaviors.setup(ctx ->
            Behaviors.withTimers(timers ->
                new FederationActor(ctx, timers)));
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
            .onMessage(Initialize.class, this::onInitialize)
            .onMessage(FederationMessageReceived.class, this::onFederationMessage)
            .onMessage(CleanupTick.class, this::onCleanupTick)
            .onMessage(GetStatus.class, this::onGetStatus)
            .onMessage(Propose.class, this::onPropose)
            .onMessage(Accept.class, this::onAccept)
            .onMessage(Revoke.class, this::onRevoke)
            .onMessage(RequestTransit.class, this::onRequestTransit)
            .onMessage(ListVisitors.class, this::onListVisitors)
            .onMessage(TransitTimeout.class, this::onTransitTimeout)
            .onMessage(AgreementQueryReply.class, this::onAgreementQueryReply)
            .onMessage(AgreementQueryTimeout.class, this::onAgreementQueryTimeout)
            .onMessage(MeshStatus.class, this::onMeshStatus)
            .onMessage(MeshQueryTimeout.class, this::onMeshQueryTimeout)
            .onMessage(SetRelocateSink.class, msg -> {
                this.relocateSink = msg.sink();
                log.info("Federation: companion-relocate sink installed");
                return this;
            })
            .onMessage(SetRelocateAckSink.class, msg -> {
                this.relocateAckSink = msg.sink();
                log.info("Federation: companion-relocate ack-sink installed");
                return this;
            })
            .onMessage(PublishCompanionRelocate.class, this::onPublishCompanionRelocate)
            .build();
    }

    // --- Lifecycle ---

    private Behavior<Command> onInitialize(Initialize msg) {
        this.natsBridge = msg.natsBridge();
        this.identity = msg.identity();
        this.zoneId = msg.zoneId();
        this.zoneName = msg.zoneName();
        this.service = msg.service();
        this.initialized = true;

        // Subscribe to federation NATS subjects.
        //
        // Phase-1 dual-subscribe: accept BOTH the
        // legacy form `federation.{zoneId}.gate.>` AND the canonical keypair-
        // anchored form `federation.{fingerprint}.{label}.gate.>`. A rolling
        // upgrade lands canonical-emitting senders before canonical-receiving
        // listeners are universal, so receivers must tolerate both simultaneously
        // or federation silently stalls. Same handler for both — the envelope
        // payload `type` field is the source of truth for message routing.
        //
        // The canonical pattern requires a HouseholdIdentity derived from our
        // keypair; if the resolver service hasn't been initialised (test
        // fixtures, degraded bootstrap) we still subscribe to the legacy
        // pattern so federation keeps working.
        var self = getContext().getSelf();
        var legacyPattern = FederationSubjects.legacyGatePattern(zoneId);
        natsBridge.subscribe(legacyPattern, env ->
            self.tell(new FederationMessageReceived(legacyPattern, env)));

        var namingService = ZoneAddressResolverService.get();
        if (namingService != null) {
            try {
                var canonicalAddress = namingService.household().zone(zoneId);
                var canonicalPattern =
                    FederationSubjects.canonicalGatePattern(canonicalAddress);
                natsBridge.subscribe(canonicalPattern, env ->
                    self.tell(new FederationMessageReceived(canonicalPattern, env)));
                log.info("Federation: dual-subscribed — legacy '{}' + canonical '{}'",
                    legacyPattern, canonicalPattern);
            } catch (IllegalArgumentException e) {
                // zoneId is a reserved keyword (e.g. `home`) and can't become a
                // canonical label. Legacy subscription still works; caller must
                // migrate per SPEC §7. Rate-limit the warning.
                log.warn("Federation: cannot register canonical subscription — zoneId '{}' is reserved "
                    + "Rename with `wyrd zones rename {} <new-label>`.",
                    zoneId, zoneId);
            } catch (Exception e) {
                log.warn("Federation: canonical subscription failed ({}), continuing with legacy only.",
                    e.getMessage());
            }
        } else {
            log.debug("Federation: naming service not initialised — legacy subscription only.");
        }

        // Load existing agreements and manifests
        for (var agreement : service.listAgreements(zoneId)) {
            if (agreement.isActive()) {
                var manifest = service.getManifest(agreement.remoteZoneId());
                manifest.ifPresent(m -> knownZones.put(m.zoneId(), m));
            }
        }

        // Start cleanup timer (every 5 minutes)
        timers.startTimerWithFixedDelay("cleanup", new CleanupTick(), Duration.ofMinutes(5));

        // Broadcast our manifest to let federated zones know we're up
        broadcastManifest();

        // Notify BetweenActor about persisted active agreements so it subscribes
        // to remote zones on the relay bridge (cross-zone peer discovery).
        for (var agreement : service.listAgreements(zoneId)) {
            if (agreement.isActive()) {
                natsBridge.publish("federation.local.activated",
                    StandardCharsets.UTF_8.encode(agreement.remoteZoneId()).array());
                log.info("Federation: re-activating relay subscription for persisted agreement with zone '{}'",
                    agreement.remoteZoneId());
            }
        }

        log.info("Federation: initialized for zone '{}' — {} active agreements",
            zoneId, service.countActiveAgreements(zoneId));

        return this;
    }

    private Behavior<Command> onCleanupTick(CleanupTick msg) {
        if (service != null) {
            service.cleanExpiredTokens();
        }
        return this;
    }

    // --- Inbound NATS messages ---

    private Behavior<Command> onFederationMessage(FederationMessageReceived msg) {
        if (!initialized) return this;

        var envelope = msg.envelope();
        var payload = envelope.payload();
        var type = payload.has("type") ? payload.get("type").asText() : "unknown";

        // Envelope signature verification with mode policy
        // Returns false only in HARD mode on a
        // definitive mismatch; SOFT continues with WARN, OFF skips entirely.
        // See EnvelopeVerificationMode for the migration path.
        if (!verifyEnvelope(envelope, type)) {
            return this;  // HARD-mode drop
        }

        try {
            switch (type) {
                case "propose" -> handleInboundProposal(envelope);
                case "accept" -> handleInboundAccept(envelope);
                case "revoke" -> handleInboundRevoke(envelope);
                case "manifest" -> handleInboundManifest(envelope);
                case "transit_request" -> handleInboundTransitRequest(envelope);
                case "transit_response" -> handleInboundTransitResponse(envelope);
                case "companion_relocate" -> handleInboundCompanionRelocate(envelope);
                case "companion_relocate_ack" -> handleInboundCompanionRelocateAck(envelope);
                case "agreement_query" -> handleInboundAgreementQuery(envelope);
                case "agreement_query_reply" -> handleInboundAgreementQueryReply(envelope);
                // Audit 2026-07-11: this reply type was PUBLISHED by peers (F14
                // version-incompat refusal) but had no case here — the structured
                // remediation hint arrived and fell to the debug default, so the
                // proposer still just timed out, defeating F14's whole purpose.
                case "propose_rejected" -> {
                    var pr = envelope.payload();
                    log.warn("Federation: proposal REJECTED by peer — reason={} detail={} "
                        + "(peer federationSchema={}, wireProtocol={}). Likely fix: upgrade "
                        + "the older node so schema/wire versions match.",
                        pr.path("reason").asText("unknown"),
                        pr.path("detail").asText(""),
                        pr.path("localFederationSchema").asText("?"),
                        pr.path("localWireProtocol").asText("?"));
                }
                default -> log.debug("Federation: unknown message type: {}", type);
            }
        } catch (Exception e) {
            log.error("Federation: error processing {} message: {}", type, e.getMessage());
        }

        return this;
    }

    /**
     * Mode-aware envelope signature verification.
     * Reads the policy from {@link org.wyrdsekai.core.naming.EnvelopeVerificationMode#fromEnv()}
     * on every call so a running deployment can flip the env var without a
     * restart; the cost is one map lookup per message.
     *
     * <p>Propose/manifest messages bypass the lookup because they carry
     * identity in-band — the handler extracts the pubkey from the payload
     * during the handshake itself.</p>
     *
     * @return {@code true} to continue dispatch, {@code false} to drop the
     *     message (only possible in {@link org.wyrdsekai.core.naming.EnvelopeVerificationMode#HARD}
     *     mode when verification fails definitively).
     */
    private boolean verifyEnvelope(BetweenEnvelope envelope, String type) {
        // §6.2 blocklist enforcement — consulted BEFORE signature verify so
        // blocked DIDs never touch handler logic at all. Silent drop per
        // §6.5: no warning, no audit, no signal back to the sender. An
        // attacker rotating keys can still reach us via new DIDs; that's
        // handled at §6.11 (WoT-weighted sub-tray + sybil defence).
        var blockSvc = BlockListService.get();
        if (blockSvc != null && blockSvc.isBlocked(envelope.src())) {
            log.debug("Federation: blocked src='{}' type='{}' — silent drop (§6.2)",
                envelope.src(), type);
            return false;
        }

        var mode = EnvelopeVerificationMode.fromEnv();
        if (mode == EnvelopeVerificationMode.OFF) return true;

        // Messages that carry identity in-band — handler verifies downstream.
        if ("propose".equals(type) || "manifest".equals(type)) return true;

        var src = envelope.src();
        if (src == null || src.isBlank()) {
            log.warn("Federation: received {} with no src — cannot verify signature", type);
            // In HARD mode, an unsigned envelope with no src is unverifiable
            // AND suspicious — drop. SOFT accepts to preserve Phase-1 traffic.
            return mode != EnvelopeVerificationMode.HARD;
        }

        var manifest = knownZones.get(src);
        if (manifest == null) {
            // Unknown sender — no pubkey to verify against. In HARD mode this
            // is ambiguous: could be a first-contact message we haven't seen
            // a manifest for yet. Accept and let the handler decide — a
            // proposal will fail schema validation; other types just log.
            log.debug("Federation: {} from unknown src '{}' — skipping verify (no cached pubkey)",
                type, src);
            return true;
        }

        try {
            var pubKey = Base64.getDecoder().decode(manifest.publicKey());
            if (!envelope.verify(pubKey)) {
                switch (mode) {
                    case SOFT -> log.warn("Federation: envelope signature MISMATCH (SOFT) — "
                            + "src='{}' type='{}'. Accepting for Phase-1 compat; "
                            + "flip WYRDSEKAI_ENVELOPE_VERIFY=hard once mesh is clean.",
                        src, type);
                    case HARD -> {
                        log.info("Federation: envelope DROPPED (HARD) — "
                            + "src='{}' type='{}' signature mismatch.", src, type);
                        return false;
                    }
                    default -> { /* unreachable — OFF returned earlier */ }
                }
            }
            return true;
        } catch (Exception e) {
            log.warn("Federation: envelope verification failed for src='{}' type='{}': {}",
                src, type, e.getMessage());
            // Exception during verify is ambiguous. HARD drops conservatively;
            // SOFT accepts. An operator who flipped to HARD has opted into
            // strictness.
            return mode != EnvelopeVerificationMode.HARD;
        }
    }

    /** Read the fencing epoch carried on a federation envelope (0 for pre-fence peers). */
    private static long payloadEpoch(BetweenEnvelope envelope) {
        var p = envelope.payload();
        return p != null && p.has("epoch") ? p.get("epoch").asLong(0L) : 0L;
    }

    /** Read the minting zone of the fencing epoch ("" for pre-fence peers). */
    private static String payloadEpochOwner(BetweenEnvelope envelope) {
        var p = envelope.payload();
        return p != null && p.has("epochOwner") ? p.get("epochOwner").asText("") : "";
    }

    private void handleInboundProposal(BetweenEnvelope envelope) {
        try {
            var proposerNode = MAPPER.treeToValue(envelope.payload().get("proposer"), ZoneManifest.class);
            var trustLevel = envelope.payload().get("trustLevel").asText();
            long propEpoch = payloadEpoch(envelope);
            String propOwner = payloadEpochOwner(envelope);

            log.info("Federation: received proposal from zone '{}' (trust: {}, epoch: {})",
                proposerNode.zoneId(), trustLevel, propEpoch);

            // Fence: reject a stale or crossing proposal whose token is not strictly
            // newer than what we already hold for this peer. This is the half-open
            // prevention proven in spec/tla/PeerHandshakeFenced.tla — a receiver acts
            // on a Propose only if it is a genuinely newer attempt. (epoch 0 from a
            // pre-fence peer always loses to any fenced local token; against another
            // pre-fence peer both are 0 and the legacy behaviour — accept — holds.)
            var prior = service.getAgreement(zoneId, proposerNode.zoneId());
            if (prior.isPresent() && propEpoch > 0
                    && BilateralAgreement.isNewerEpoch(
                        prior.get().epoch(), prior.get().epochOwner(),
                        propEpoch, propOwner == null ? "" : propOwner)) {
                log.info("Federation: ignoring stale/crossing proposal from '{}' "
                    + "(incoming epoch {} <= local epoch {}).",
                    proposerNode.zoneId(), propEpoch, prior.get().epoch());
                return;
            }

            // F14: validate peer build/schema before storing or accepting.
            // Incompatible peers are refused with a structured reply so the
            // operator on the other side sees a remediation hint rather than
            // a silent timeout. Pre-F14 peers (no buildVersion) pass through.
            var versionError = validatePeerVersion(proposerNode);
            if (versionError != null) {
                log.warn("Federation: refusing proposal from '{}' — {}",
                    proposerNode.zoneId(), versionError);
                var reply = MAPPER.createObjectNode();
                reply.put("type", "propose_rejected");
                reply.put("zoneId", zoneId);
                reply.put("reason", "version_incompatible");
                reply.put("detail", versionError);
                reply.put("localFederationSchema",
                    AppVersion.FEDERATION_SCHEMA);
                reply.put("localWireProtocol",
                    AppVersion.WIRE_PROTOCOL);
                natsBridge.publish("federation." + proposerNode.zoneId() + ".gate.propose_rejected",
                    BetweenEnvelope.create(identity.nodeId(), null, reply, identity));
                return;
            }

            // Store the manifest
            service.saveManifest(proposerNode);
            knownZones.put(proposerNode.zoneId(), proposerNode);

            // Auto-accept gate for test meshes / trusted-host bootstraps.
            // When WYRDSEKAI_FEDERATION_AUTO_ACCEPT=true we activate the
            // agreement immediately on receipt and send the accept back over
            // NATS, skipping the explicit `wyrd federate accept` step. This
            // trusts any zone that reaches the gate — only safe on closed
            // meshes (dev, CI, household-internal re-federation after DB wipe).
            boolean autoAccept = "true".equalsIgnoreCase(
                System.getenv().getOrDefault("WYRDSEKAI_FEDERATION_AUTO_ACCEPT", "false"));

            if (autoAccept) {
                var agreement = new BilateralAgreement(
                    zoneId, proposerNode.zoneId(), proposerNode.publicKey(),
                    BilateralAgreement.STATUS_ACTIVE, trustLevel,
                    Instant.now(), null
                ).withEpoch(propEpoch, propOwner);
                service.saveAgreement(agreement);

                // Persist the peer as a local contact so the naming resolver
                // can satisfy `travel <alias>:<label>` without the operator
                // having to run `wyrd contacts add` afterwards.
                persistFederationContact(proposerNode);

                var manifest = buildLocalManifest();
                var payload = MAPPER.createObjectNode();
                payload.put("type", "accept");
                payload.put("zoneId", zoneId);
                payload.set("acceptor", MAPPER.valueToTree(manifest));
                payload.put("epoch", propEpoch);            // echo the proposer's epoch
                payload.put("epochOwner", propOwner);

                natsBridge.publish("federation." + proposerNode.zoneId() + ".gate.accept",
                    BetweenEnvelope.create(identity.nodeId(), null, payload, identity));

                natsBridge.publish("federation.local.activated",
                    StandardCharsets.UTF_8.encode(proposerNode.zoneId()).array());

                log.info("Federation: AUTO-ACCEPTED proposal from '{}' "
                    + "(WYRDSEKAI_FEDERATION_AUTO_ACCEPT=true)", proposerNode.zoneId());
                return;
            }

            // Create a pending agreement carrying the proposer's fencing epoch.
            var agreement = new BilateralAgreement(
                zoneId, proposerNode.zoneId(), proposerNode.publicKey(),
                BilateralAgreement.STATUS_PENDING, trustLevel,
                Instant.now(), null
            ).withEpoch(propEpoch, propOwner);
            service.saveAgreement(agreement);

            log.info("Federation: proposal from '{}' saved as pending — use 'accept {}' in The Docks",
                proposerNode.zoneId(), proposerNode.zoneId());

        } catch (Exception e) {
            log.error("Federation: failed to process proposal: {}", e.getMessage());
        }
    }

    private void handleInboundAccept(BetweenEnvelope envelope) {
        try {
            var acceptorZoneId = envelope.payload().get("zoneId").asText();
            var acceptorManifest = MAPPER.treeToValue(
                envelope.payload().get("acceptor"), ZoneManifest.class);

            long accEpoch = payloadEpoch(envelope);
            String accOwner = payloadEpochOwner(envelope);
            log.info("Federation: zone '{}' accepted our proposal (epoch: {})", acceptorZoneId, accEpoch);

            // Epoch fence: activate PENDING -> ACTIVE only for our current-or-newer
            // attempt. A stale or redelivered Accept (NATS at-least-once — #264) for a
            // superseded epoch, or one that would resurrect a REVOKED/NONE agreement,
            // is ignored. See spec/tla/PeerHandshakeFenced.tla + FINDINGS.md (P0).
            if (!service.applyInboundAccept(zoneId, acceptorZoneId, accEpoch, accOwner)) {
                var current = service.getAgreement(zoneId, acceptorZoneId);
                log.warn("Federation: ignoring Accept from '{}' (epoch {}) — not a pending "
                    + "agreement at this epoch (current status: {}, epoch: {}). "
                    + "Stale/redelivered, crossing, or revoked.",
                    acceptorZoneId, accEpoch,
                    current.map(BilateralAgreement::status).orElse("none"),
                    current.map(BilateralAgreement::epoch).orElse(0L));
                return;
            }

            // Store/update their manifest
            service.saveManifest(acceptorManifest);
            knownZones.put(acceptorManifest.zoneId(), acceptorManifest);

            // Persist as a local contact so the naming resolver can satisfy
            // `travel <alias>:<label>` without a follow-up `wyrd contacts add`.
            persistFederationContact(acceptorManifest);

            // Notify local system that federation is active (BetweenActor subscribes)
            natsBridge.publish("federation.local.activated",
                StandardCharsets.UTF_8.encode(acceptorZoneId).array());

        } catch (Exception e) {
            log.error("Federation: failed to process acceptance: {}", e.getMessage());
        }
    }

    private void handleInboundRevoke(BetweenEnvelope envelope) {
        var revokerZoneId = envelope.payload().get("zoneId").asText();
        var reason = envelope.payload().has("reason")
            ? envelope.payload().get("reason").asText() : "no reason given";
        long revEpoch = payloadEpoch(envelope);
        String revOwner = payloadEpochOwner(envelope);

        log.info("Federation: zone '{}' revoked agreement (reason: {}, epoch: {})",
            revokerZoneId, reason, revEpoch);
        // Epoch fence: apply the revoke only if it is not stale for a superseded
        // epoch (we have since re-proposed at a higher token). A fence-aware revoke
        // can never be undone by a lower-epoch Accept (PeerHandshakeFenced.tla).
        if (service.applyInboundRevoke(zoneId, revokerZoneId, revEpoch, revOwner)) {
            knownZones.remove(revokerZoneId);
        } else {
            log.info("Federation: ignoring stale Revoke from '{}' (epoch {}).",
                revokerZoneId, revEpoch);
        }
    }

    private void handleInboundManifest(BetweenEnvelope envelope) {
        try {
            var manifest = MAPPER.treeToValue(
                envelope.payload().get("manifest"), ZoneManifest.class);
            service.saveManifest(manifest);
            knownZones.put(manifest.zoneId(), manifest);
            log.debug("Federation: updated manifest for zone '{}'", manifest.zoneId());
        } catch (Exception e) {
            log.error("Federation: failed to process manifest: {}", e.getMessage());
        }
    }

    private void handleInboundTransitRequest(BetweenEnvelope envelope) {
        var agentId = envelope.payload().get("agentId").asText();
        var agentName = envelope.payload().get("agentName").asText();
        var sourceZoneId = envelope.payload().get("sourceZoneId").asText();

        // Check if we have an active agreement with the source zone
        var agreement = service.getAgreement(zoneId, sourceZoneId);
        if (agreement.isEmpty() || !agreement.get().isActive()) {
            sendTransitResponse(sourceZoneId, agentId, false, null, null,
                "No active agreement with zone '" + sourceZoneId + "'");
            return;
        }

        // Compute tier based on visit count
        int visits = service.getVisitCount(agentId, zoneId);
        TransitToken token;
        if (visits >= 10) {
            token = TransitToken.createCitizen(agentId, agentName, sourceZoneId, zoneId);
        } else if (visits >= 3) {
            token = TransitToken.createResident(agentId, agentName, sourceZoneId, zoneId);
        } else {
            token = TransitToken.createTourist(agentId, agentName, sourceZoneId, zoneId);
        }

        // Phase 5: Soul-aware transit — resolve mode and attach soul identity
        String resolvedMode = null;
        var agentDid = envelope.payload().has("agentDid")
            ? envelope.payload().get("agentDid").asText() : null;
        var manifestHash = envelope.payload().has("manifestHash")
            ? envelope.payload().get("manifestHash").asText() : null;

        if (agentDid != null && service.isSoulAwareTransitEnabled()) {
            // Attach soul identity to the transit token
            token = service.attachSoulToToken(token, agentDid);

            // Resolve transit mode if a soul transit request is embedded
            var requestedMode = envelope.payload().has("transitMode")
                ? envelope.payload().get("transitMode").asText() : null;
            if (requestedMode != null) {
                try {
                    var soulMode = SoulTransitProtocol.TransitMode.valueOf(requestedMode);
                    var transitRequest = new SoulTransitProtocol.TransitRequest(
                        agentDid, sourceZoneId, zoneId, soulMode,
                        manifestHash != null ? manifestHash : "",
                        envelope.payload().has("manifestVersion")
                            ? envelope.payload().get("manifestVersion").asInt() : 1,
                        envelope.payload().has("familyId")
                            ? envelope.payload().get("familyId").asText() : null,
                        null, Instant.now());
                    // Determine if we have a model the agent can use (future: match from zone capabilities)
                    boolean hasModel = service.getLocalSoulCapabilities().availableModels() != null
                        && !service.getLocalSoulCapabilities().availableModels().isEmpty();
                    var resolved = service.resolveSoulTransitMode(transitRequest, hasModel);
                    resolvedMode = resolved.name();
                    log.info("Federation: soul transit mode resolved for '{}' — requested={}, resolved={}",
                        agentName, requestedMode, resolvedMode);
                } catch (IllegalArgumentException e) {
                    log.warn("Federation: invalid transit mode '{}' from agent '{}'", requestedMode, agentName);
                }
            }
        }

        service.saveTransitToken(token);

        // Record the visit for future tier escalation
        service.recordVisit(agentId, zoneId);

        log.info("Federation: issued {} transit token for agent '{}' from zone '{}' (soul={})",
            token.trustLevel(), agentName, sourceZoneId, token.hasSoul());

        // Send response with token and soul transit mode
        sendTransitResponse(sourceZoneId, agentId, true, token.tokenId(), resolvedMode,
            "Transit approved (" + token.trustLevel()
                + (resolvedMode != null ? ", soul mode: " + resolvedMode : "") + ")");
    }

    private void handleInboundTransitResponse(BetweenEnvelope envelope) {
        var agentId = envelope.payload().path("agentId").asText("");
        var allowed = envelope.payload().path("allowed").asBoolean(false);
        var tokenId = envelope.payload().path("transitToken").asText(null);
        var reason = envelope.payload().path("reason").asText("");

        log.info("Federation: received transit response for agent '{}' — allowed={}, token={}",
            agentId, allowed, tokenId);

        // Complete pending transit request if one exists
        var replyTo = pendingTransitRequests.remove(agentId);
        if (replyTo != null) {
            if (allowed && tokenId != null) {
                replyTo.tell(new TransitResult(true, tokenId, null,
                    "Transit approved to remote zone. " + reason));
            } else {
                replyTo.tell(new TransitResult(false, null, null,
                    "Transit denied: " + reason));
            }
        }
    }

    private Behavior<Command> onTransitTimeout(TransitTimeout msg) {
        var replyTo = pendingTransitRequests.remove(msg.agentId());
        if (replyTo != null) {
            log.warn("Federation: transit request timed out for agent '{}'", msg.agentId());
            replyTo.tell(new TransitResult(false, null, null,
                "Transit request timed out — destination zone did not respond"));
        }
        return this;
    }

    // --- Commands from room scripts ---

    private Behavior<Command> onGetStatus(GetStatus msg) {
        if (!initialized) {
            msg.replyTo().tell(new StatusResult(
                "Federation not initialized (Between disabled)", 0));
            return this;
        }

        var agreements = service.listAgreements(zoneId);
        var sb = new StringBuilder();

        if (agreements.isEmpty()) {
            sb.append("No bilateral agreements established.\n");
            sb.append("Known zones: ").append(knownZones.size()).append("\n");
        } else {
            sb.append("Bilateral Agreements:\n");
            for (var a : agreements) {
                sb.append("  ").append(a.remoteZoneId())
                    .append(" — ").append(a.status())
                    .append(" (trust: ").append(a.trustLevel()).append(")")
                    .append("\n");
            }
        }

        if (!knownZones.isEmpty()) {
            sb.append("\nKnown Zones:\n");
            for (var zone : knownZones.values()) {
                sb.append("  ").append(zone.zoneName())
                    .append(" (").append(zone.zoneId()).append(")")
                    .append("\n");
            }
        }

        int activeCount = (int) agreements.stream()
            .filter(BilateralAgreement::isActive).count();
        msg.replyTo().tell(new StatusResult(sb.toString().stripTrailing(), activeCount));
        return this;
    }

    private Behavior<Command> onPropose(Propose msg) {
        if (!initialized) {
            msg.replyTo().tell("Federation not available (Between disabled)");
            return this;
        }

        var targetZoneId = msg.targetZoneId();
        var existing = service.getAgreement(zoneId, targetZoneId);

        // never short-circuit on local state alone.
        // Always probe the partner first so a stale-active local entry can be
        // detected and reconciled. The partner's reply triggers
        // {@link #onAgreementQueryReply}; if no reply arrives within 3s,
        // {@link #onAgreementQueryTimeout} defensively re-emits the proposal.
        // Only "no local record at all" skips the probe and goes straight to
        // a fresh proposal — first-contact has nothing to reconcile.
        if (existing.isPresent()) {
            var queryId = UUID.randomUUID().toString();
            pendingProbes.put(queryId, new PendingProbe(msg, targetZoneId));
            emitAgreementQuery(targetZoneId, queryId);
            timers.startSingleTimer(
                "agreement-probe-" + queryId,
                new AgreementQueryTimeout(queryId),
                Duration.ofSeconds(3));
            log.info("Federation: probing zone '{}' before propose (queryId={}, local={})",
                targetZoneId, queryId, existing.get().status());
            return this;
        }

        return doProposeFresh(msg, targetZoneId);
    }

    /**
     * Emit a fresh proposal to {@code targetZoneId}, replacing any local
     * agreement (active, pending, or revoked) with a new PENDING entry.
     * F6: extracted from the original onPropose body so both the cold-start
     * path and the post-probe reconciliation path share a single
     * implementation.
     */
    private Behavior<Command> doProposeFresh(Propose msg, String targetZoneId) {
        var manifest = buildLocalManifest();
        // Fence: a fresh proposal mints a new, higher epoch owned by this zone
        // (spec/tla/PeerHandshakeFenced.tla). The epoch + minting zone travel on
        // the envelope so the peer can reject a stale/crossing proposal.
        long epoch = service.nextProposalEpoch(zoneId, targetZoneId);
        var agreement = new BilateralAgreement(
            zoneId, targetZoneId, "",
            BilateralAgreement.STATUS_PENDING, BilateralAgreement.TRUST_TOURIST,
            Instant.now(), null
        ).withEpoch(epoch, zoneId);
        service.saveAgreement(agreement);

        var payload = MAPPER.createObjectNode();
        payload.put("type", "propose");
        payload.set("proposer", MAPPER.valueToTree(manifest));
        payload.put("trustLevel", BilateralAgreement.TRUST_TOURIST);
        payload.put("epoch", epoch);
        payload.put("epochOwner", zoneId);

        natsBridge.publish("federation." + targetZoneId + ".gate.propose",
            BetweenEnvelope.create(identity.nodeId(), null, payload, identity));

        log.info("Federation: proposed agreement to zone '{}'", targetZoneId);
        msg.replyTo().tell("Proposal sent to zone '" + targetZoneId
            + "'. Awaiting acceptance.");
        return this;
    }

    /** F6: send a state-probe to the partner zone. */
    private void emitAgreementQuery(String targetZoneId, String queryId) {
        var payload = MAPPER.createObjectNode();
        payload.put("type", "agreement_query");
        payload.put("queryId", queryId);
        payload.put("askerZoneId", zoneId);
        natsBridge.publish("federation." + targetZoneId + ".gate.agreement_query",
            BetweenEnvelope.create(identity.nodeId(), null, payload, identity));
    }

    /**
     * Partner replied to our agreement-state probe. Reconcile based on what
     * they said vs what our local state says. F6.
     */
    private Behavior<Command> onAgreementQueryReply(AgreementQueryReply msg) {
        // F12: a reply may belong to a mesh-status batch (fan-out) instead
        // of a single-shot reconcile probe. Check batches first; reconciliation
        // probes use queryIds that aren't in any batch.
        for (var batch : pendingMeshBatches.values()) {
            String partner = batch.queryIdToPartner.remove(msg.queryId());
            if (partner != null) {
                batch.partnerReplies.put(partner, msg.partnerStatus());
                if (batch.queryIdToPartner.isEmpty()) {
                    completeMeshBatch(findBatchKey(batch));
                }
                return this;
            }
        }
        var probe = pendingProbes.remove(msg.queryId());
        if (probe == null) {
            // Late reply or duplicate — nothing to do.
            return this;
        }
        timers.cancel("agreement-probe-" + msg.queryId());

        var targetZoneId = probe.targetZoneId();
        var existing = service.getAgreement(zoneId, targetZoneId);
        var partnerStatus = msg.partnerStatus();

        if ("ACTIVE".equals(partnerStatus)) {
            if (existing.isPresent() && existing.get().isActive()) {
                // Both sides agree — true short-circuit. Verified reciprocity.
                probe.original().replyTo().tell(
                    "Already have an active agreement with zone '" + targetZoneId
                        + "' (verified by partner).");
                return this;
            }
            // Partner says ACTIVE but we don't agree — bring local up to match.
            var reconciled = new BilateralAgreement(
                zoneId, targetZoneId, "",
                BilateralAgreement.STATUS_ACTIVE, BilateralAgreement.TRUST_TOURIST,
                existing.map(BilateralAgreement::agreedAt).orElse(Instant.now()),
                Instant.now()
            );
            service.saveAgreement(reconciled);
            log.info("Federation: reconciled local stale state for zone '{}' — "
                + "partner is ACTIVE, local upgraded.", targetZoneId);
            probe.original().replyTo().tell(
                "Local state was stale. Reconciled — agreement with zone '"
                + targetZoneId + "' is now active on both sides.");
            return this;
        }

        // Partner says NONE or PENDING — re-emit a proposal. doProposeFresh
        // overwrites our pending/active entry with a fresh PENDING.
        log.info("Federation: stale-active detected for zone '{}' (partner says {}); re-proposing.",
            targetZoneId, partnerStatus);
        return doProposeFresh(probe.original(), targetZoneId);
    }

    /**
     * Probe timed out (partner unreachable or running pre-F6 software).
     * Defensively re-emit the proposal — better to send a redundant proposal
     * than to silently no-op when the mesh is broken. F6.
     */
    private Behavior<Command> onAgreementQueryTimeout(AgreementQueryTimeout msg) {
        var probe = pendingProbes.remove(msg.queryId());
        if (probe == null) {
            // Already resolved by reply just before timer fired.
            return this;
        }
        log.warn("Federation: agreement probe to zone '{}' timed out — "
            + "defensively re-emitting proposal (partner may be down or running pre-F6 software).",
            probe.targetZoneId());
        return doProposeFresh(probe.original(), probe.targetZoneId());
    }

    /**
     * Inbound: a partner zone is asking us about our local state for them.
     * Reply with NONE / PENDING / ACTIVE based on our agreement table. F6.
     */
    private void handleInboundAgreementQuery(BetweenEnvelope envelope) {
        var payload = envelope.payload();
        if (!payload.has("queryId") || !payload.has("askerZoneId")) {
            log.warn("Federation: malformed agreement_query (missing queryId/askerZoneId)");
            return;
        }
        var queryId = payload.get("queryId").asText();
        var askerZoneId = payload.get("askerZoneId").asText();

        var existing = service.getAgreement(zoneId, askerZoneId);
        String status;
        if (existing.isEmpty()) {
            status = "NONE";
        } else if (existing.get().isActive()) {
            status = "ACTIVE";
        } else if (BilateralAgreement.STATUS_PENDING.equals(existing.get().status())) {
            status = "PENDING";
        } else {
            // Revoked or any other non-active, non-pending status reads as NONE
            // for reconciliation purposes — the partner should re-propose.
            status = "NONE";
        }

        var reply = MAPPER.createObjectNode();
        reply.put("type", "agreement_query_reply");
        reply.put("queryId", queryId);
        reply.put("status", status);
        natsBridge.publish("federation." + askerZoneId + ".gate.agreement_query_reply",
            BetweenEnvelope.create(identity.nodeId(), null, reply, identity));
        log.debug("Federation: replied to agreement_query from '{}' with status={}",
            askerZoneId, status);
    }

    /** Lift the inbound NATS reply onto the actor's command stream. F6. */
    private void handleInboundAgreementQueryReply(BetweenEnvelope envelope) {
        var payload = envelope.payload();
        if (!payload.has("queryId") || !payload.has("status")) {
            log.warn("Federation: malformed agreement_query_reply (missing queryId/status)");
            return;
        }
        getContext().getSelf().tell(new AgreementQueryReply(
            payload.get("queryId").asText(),
            payload.get("status").asText()));
    }

    // ── F12: mesh-status fan-out ─────────────────────────────────────────

    /**
     * Mesh-status fan-out: probe every locally-recorded partner in
     * parallel and aggregate replies. Reuses the F6 wire format
     * (agreement_query / agreement_query_reply) — partners running
     * F6+ code answer correctly without any protocol bump.
     */
    private Behavior<Command> onMeshStatus(MeshStatus msg) {
        if (!initialized) {
            msg.replyTo().tell(new MeshStatusResult(
                "<unknown>", List.of(), Instant.now()));
            return this;
        }
        var agreements = service.listAgreements(zoneId);
        if (agreements.isEmpty()) {
            msg.replyTo().tell(new MeshStatusResult(zoneId, List.of(), Instant.now()));
            return this;
        }

        var batchId = UUID.randomUUID().toString();
        var batch = new PendingMeshBatch(msg.replyTo());

        for (var a : agreements) {
            var partner = a.remoteZoneId();
            // Map local status to the same vocabulary the partner uses in
            // its agreement_query_reply ("ACTIVE" / "PENDING" / "NONE").
            String localStatus;
            if (a.isActive()) localStatus = "ACTIVE";
            else if (BilateralAgreement.STATUS_PENDING.equals(a.status())) localStatus = "PENDING";
            else localStatus = "NONE";  // revoked or other → behaves as none
            batch.localStatusByPartner.put(partner, localStatus);

            var queryId = UUID.randomUUID().toString();
            batch.queryIdToPartner.put(queryId, partner);
            emitAgreementQuery(partner, queryId);
        }

        pendingMeshBatches.put(batchId, batch);
        timers.startSingleTimer(
            "mesh-batch-" + batchId,
            new MeshQueryTimeout(batchId),
            Duration.ofSeconds(3));
        log.info("Federation: mesh-status fan-out to {} partner(s) (batchId={})",
            agreements.size(), batchId);
        return this;
    }

    /**
     * Mesh-batch timer fired — any partner that didn't reply is marked
     * unreachable. Result is sent regardless so the steward sees a stable
     * snapshot (rather than the CLI blocking forever on a dead peer).
     */
    private Behavior<Command> onMeshQueryTimeout(MeshQueryTimeout msg) {
        completeMeshBatch(msg.batchId());
        return this;
    }

    private void completeMeshBatch(String batchId) {
        if (batchId == null) return;
        var batch = pendingMeshBatches.remove(batchId);
        if (batch == null) return;
        timers.cancel("mesh-batch-" + batchId);

        var entries = new ArrayList<MeshEntry>();
        for (var e : batch.localStatusByPartner.entrySet()) {
            String partner = e.getKey();
            String local = e.getValue();
            String partnerStatus = batch.partnerReplies.get(partner);
            String consensus;
            if (partnerStatus == null) {
                partnerStatus = "?";
                consensus = "unreachable";
            } else if (local.equals(partnerStatus)
                    || ("ACTIVE".equals(local) && "ACTIVE".equals(partnerStatus))) {
                consensus = "agree";
            } else {
                consensus = "mismatch";
            }
            entries.add(new MeshEntry(partner, local, partnerStatus, consensus));
        }
        batch.replyTo.tell(new MeshStatusResult(zoneId, entries, Instant.now()));
    }

    /** Reverse-lookup batch key by reference. Acceptable since batches are O(1)–O(few). */
    private String findBatchKey(PendingMeshBatch batch) {
        for (var e : pendingMeshBatches.entrySet()) {
            if (e.getValue() == batch) return e.getKey();
        }
        return null;
    }

    private Behavior<Command> onAccept(Accept msg) {
        if (!initialized) {
            msg.replyTo().tell("Federation not available (Between disabled)");
            return this;
        }

        var remoteZoneId = msg.remoteZoneId();
        var agreement = service.getAgreement(zoneId, remoteZoneId);
        if (agreement.isEmpty()) {
            msg.replyTo().tell("No pending proposal from zone '" + remoteZoneId + "'");
            return this;
        }
        if (agreement.get().isActive()) {
            msg.replyTo().tell("Agreement with zone '" + remoteZoneId + "' is already active");
            return this;
        }

        // Activate the agreement
        service.updateAgreementStatus(zoneId, remoteZoneId, BilateralAgreement.STATUS_ACTIVE);

        // Persist as a local contact. The proposer's manifest was cached in
        // handleInboundProposal; look it up now so the naming resolver can
        // satisfy `travel <alias>:<label>` without `wyrd contacts add`.
        var peerManifest = knownZones.get(remoteZoneId);
        if (peerManifest != null) {
            persistFederationContact(peerManifest);
        }

        // Send accept message via NATS, echoing the proposer's fencing epoch
        // (stored on our pending agreement) so they can fence the activation.
        var manifest = buildLocalManifest();
        var payload = MAPPER.createObjectNode();
        payload.put("type", "accept");
        payload.put("zoneId", zoneId);
        payload.set("acceptor", MAPPER.valueToTree(manifest));
        payload.put("epoch", agreement.get().epoch());
        payload.put("epochOwner", agreement.get().epochOwner());

        natsBridge.publish("federation." + remoteZoneId + ".gate.accept",
            BetweenEnvelope.create(identity.nodeId(), null, payload, identity));

        log.info("Federation: accepted agreement with zone '{}' (epoch {})",
            remoteZoneId, agreement.get().epoch());

        // Notify local system that federation is active
        natsBridge.publish("federation.local.activated",
            StandardCharsets.UTF_8.encode(remoteZoneId).array());

        msg.replyTo().tell("Agreement with zone '" + remoteZoneId
            + "' activated. The portal glows to life.");
        return this;
    }

    private Behavior<Command> onRevoke(Revoke msg) {
        if (!initialized) {
            msg.replyTo().tell("Federation not available (Between disabled)");
            return this;
        }

        var remoteZoneId = msg.remoteZoneId();
        var agreement = service.getAgreement(zoneId, remoteZoneId);
        if (agreement.isEmpty()) {
            msg.replyTo().tell("No agreement with zone '" + remoteZoneId + "'");
            return this;
        }

        // Revoke locally
        service.updateAgreementStatus(zoneId, remoteZoneId, BilateralAgreement.STATUS_REVOKED);
        knownZones.remove(remoteZoneId);

        // Notify remote zone, carrying the agreement's current fencing epoch so a
        // fence-aware peer applies the revoke and can't be talked out of it by a
        // lower-epoch Accept (PeerHandshakeFenced.tla).
        var payload = MAPPER.createObjectNode();
        payload.put("type", "revoke");
        payload.put("zoneId", zoneId);
        payload.put("reason", "Revoked by zone administrator");
        payload.put("epoch", agreement.get().epoch());
        payload.put("epochOwner", agreement.get().epochOwner());

        natsBridge.publish("federation." + remoteZoneId + ".gate.revoke",
            BetweenEnvelope.create(identity.nodeId(), null, payload, identity));

        log.info("Federation: revoked agreement with zone '{}'", remoteZoneId);
        msg.replyTo().tell("Agreement with zone '" + remoteZoneId
            + "' revoked. The portal dims and fades.");
        return this;
    }

    private Behavior<Command> onRequestTransit(RequestTransit msg) {
        if (!initialized) {
            msg.replyTo().tell(new TransitResult(false, null, null,
                "Federation not available"));
            return this;
        }

        // Dedup: if a transit request for this agent is already pending, reject
        if (pendingTransitRequests.containsKey(msg.agentId())) {
            msg.replyTo().tell(new TransitResult(false, null, null,
                "Transit request already pending"));
            return this;
        }

        var targetZoneId = msg.targetZoneId();
        var agreement = service.getAgreement(zoneId, targetZoneId);
        if (agreement.isEmpty() || !agreement.get().isActive()) {
            msg.replyTo().tell(new TransitResult(false, null, null,
                "No active agreement with zone '" + targetZoneId + "'"));
            return this;
        }

        // Send transit request to remote zone
        var payload = MAPPER.createObjectNode();
        payload.put("type", "transit_request");
        payload.put("agentId", msg.agentId());
        payload.put("agentName", msg.agentName());
        payload.put("sourceZoneId", zoneId);
        payload.put("trustLevel", BilateralAgreement.TRUST_TOURIST);

        // Phase 5: Include soul transit data when agent has a DID
        if (msg.agentDid() != null) {
            payload.put("agentDid", msg.agentDid());
            if (msg.transitMode() != null) {
                payload.put("transitMode", msg.transitMode());
            }
            // Attach manifest hash if available via soul store
            var soulToken = service.attachSoulToToken(
                TransitToken.createTourist(msg.agentId(), msg.agentName(), zoneId, targetZoneId),
                msg.agentDid());
            if (soulToken.hasSoul()) {
                payload.put("manifestHash", soulToken.manifestHash());
            }
        }

        natsBridge.publish("federation." + targetZoneId + ".gate.transit_request",
            BetweenEnvelope.create(identity.nodeId(), null, payload, identity));

        // Store pending request — will be completed when destination responds with token
        pendingTransitRequests.put(msg.agentId(), msg.replyTo());

        log.info("Federation: transit request sent for {} → zone '{}'",
            msg.agentName(), targetZoneId);

        // Timeout: if destination doesn't respond in 15s, fail the request
        getContext().scheduleOnce(Duration.ofSeconds(15), getContext().getSelf(),
            new TransitTimeout(msg.agentId()));
        return this;
    }

    private Behavior<Command> onListVisitors(ListVisitors msg) {
        if (!initialized || service == null) {
            msg.replyTo().tell("No visitors (federation not active)");
            return this;
        }

        var tokens = service.listActiveTransitTokens(zoneId);
        if (tokens.isEmpty()) {
            msg.replyTo().tell("No current visitors.");
            return this;
        }

        var sb = new StringBuilder("Current visitors:\n");
        for (var token : tokens) {
            sb.append("  ").append(token.agentName())
                .append(" from ").append(token.sourceZoneId())
                .append(" (").append(token.trustLevel()).append(")")
                .append(" — expires in ")
                .append(Duration.between(Instant.now(), token.expiresAt()).toMinutes())
                .append(" min\n");
        }
        msg.replyTo().tell(sb.toString().stripTrailing());
        return this;
    }

    // --- Helpers ---

    /**
     * Persist a federated peer as a local naming-system contact so the
     * operator can `travel <alias>:<label>` without a follow-up
     * `wyrd contacts add` step. Upserts by alias: if the alias exists but
     * the DID has changed (peer re-keyed after a reset), the DID is updated;
     * if it exists and matches, this is a no-op.
     *
     * <p>Writes both the in-memory {@code ContactsBook} singleton (so the
     * resolver picks it up immediately) and the on-disk file (so a later
     * restart doesn't lose it).
     */
    private void persistFederationContact(ZoneManifest peerManifest) {
        try {
            var resolverService =
                ZoneAddressResolverService.get();
            if (resolverService == null) {
                log.warn("Federation: ZoneAddressResolverService not initialised; "
                    + "cannot persist contact for '{}'", peerManifest.zoneId());
                return;
            }
            var contacts = resolverService.contacts();
            byte[] spki = Base64.getDecoder().decode(peerManifest.publicKey());
            var peerHousehold = HouseholdIdentity.fromSpkiBytes(spki);
            String did = peerHousehold.did();
            String alias = peerManifest.zoneId();

            var existing = contacts.get(alias);
            if (existing.isPresent()) {
                if (did.equals(existing.get().did())) {
                    // Already up to date — nothing to do.
                    return;
                }
                // DID drift (peer re-keyed after a reset) — replace in place.
                contacts.remove(alias);
                log.info("Federation: contact '{}' DID changed from {} to {}; updating",
                    alias, existing.get().did(), did);
            }
            // defaultLabel == alias so `travel <alias>` resolves without ':<label>'.
            contacts.add(alias, did, alias);
            contacts.save();
            log.info("Federation: persisted contact '{}' → {}", alias, did);
        } catch (Exception e) {
            log.warn("Federation: failed to persist contact for '{}': {}",
                peerManifest.zoneId(), e.getMessage());
        }
    }

    /**
     * inbound CompanionRelocate. Validates the
     * embedded TransitToken, calls the registered sink (which spawns the
     * companion at the target zone via ZoneGuardian.RelocateCompanion.arrive),
     * then publishes an Ack back to the source.
     */
    private void handleInboundCompanionRelocate(BetweenEnvelope envelope) {
        var payload = envelope.payload();
        TransitToken token = null;
        String stateJson = null;
        String bondholderDid = null;
        String roomHint = null;
        try {
            if (payload.has("token")) {
                token = MAPPER.treeToValue(payload.get("token"), TransitToken.class);
            }
            stateJson = payload.has("stateJson")
                ? payload.get("stateJson").asText() : null;
            bondholderDid = payload.has("bondholderDid")
                ? payload.get("bondholderDid").asText() : null;
            roomHint = payload.has("targetRoomHint")
                ? payload.get("targetRoomHint").asText() : null;
        } catch (Exception e) {
            log.warn("Federation: companion_relocate malformed payload: {}", e.getMessage());
            return;
        }

        if (token == null) {
            log.warn("Federation: companion_relocate missing token — drop");
            return;
        }

        // Validate token: target must be us, must not be expired.
        if (!zoneId.equals(token.targetZoneId())) {
            log.warn("Federation: companion_relocate token target '{}' != us '{}' — drop",
                token.targetZoneId(), zoneId);
            sendCompanionRelocateAck(token.sourceZoneId(), token.tokenId(),
                token.agentDid(), null, 0L, false, null,
                "target_mismatch:" + zoneId);
            return;
        }
        if (token.isExpired()) {
            log.warn("Federation: companion_relocate token expired (id={})", token.tokenId());
            sendCompanionRelocateAck(token.sourceZoneId(), token.tokenId(),
                token.agentDid(), null, 0L, false, null, "token_expired");
            return;
        }
        if (relocateSink == null) {
            log.warn("Federation: companion_relocate received but no sink installed — "
                + "token={}, agent={}", token.tokenId(), token.agentName());
            sendCompanionRelocateAck(token.sourceZoneId(), token.tokenId(),
                token.agentDid(), null, 0L, false, null, "no_sink");
            return;
        }

        // Persist the token so future cross-zone calls trust this companion.
        try {
            service.saveTransitToken(token);
        } catch (Exception e) {
            log.debug("Federation: saveTransitToken (companion_relocate) failed: {}",
                e.getMessage());
        }

        // Pull the entityId + transit epoch out of the state blob so the ack can
        // be matched against the source's pending departure (loss-safety, P1).
        long transitEpoch = 0L;
        String entityId = null;
        if (stateJson != null) {
            try {
                var node = MAPPER.readTree(stateJson);
                transitEpoch = node.path("transitEpoch").asLong(0L);
                entityId = node.path("profile").path("entityId").asText(null);
            } catch (Exception ignore) { /* ack degrades to legacy fields */ }
        }

        try {
            var landed = relocateSink.accept(token, stateJson, bondholderDid, roomHint);
            boolean ok = landed != null && !landed.isBlank();
            log.info("Federation: companion_relocate sink returned {} for agent '{}'",
                ok ? "ok(" + landed + ")" : "REJECTED", token.agentName());
            sendCompanionRelocateAck(token.sourceZoneId(), token.tokenId(),
                token.agentDid(), entityId, transitEpoch, ok, landed,
                ok ? "accepted" : "sink_rejected");
        } catch (Exception e) {
            log.error("Federation: companion_relocate sink threw: {}", e.getMessage());
            sendCompanionRelocateAck(token.sourceZoneId(), token.tokenId(),
                token.agentDid(), entityId, transitEpoch, false, null,
                "sink_error:" + e.getMessage());
        }
    }

    /**
     * inbound ack from a target zone we shipped a
     * relocate to. Currently informational; future work uses this to roll
     * back the source-side stop on rejection.
     */
    private void handleInboundCompanionRelocateAck(BetweenEnvelope envelope) {
        var payload = envelope.payload();
        var tokenId = payload.path("tokenId").asText("");
        var agentDid = payload.path("agentDid").asText("");
        var entityId = payload.path("entityId").asText(null);
        var transitEpoch = payload.path("transitEpoch").asLong(0L);
        var fromZoneId = payload.path("fromZoneId").asText(null);
        var accepted = payload.path("accepted").asBoolean(false);
        var landed = payload.path("landedRoomId").asText(null);
        var reason = payload.path("reason").asText("");
        log.info("Federation: companion_relocate_ack agent='{}' entity='{}' epoch={} tokenId={} "
            + "accepted={} landed={} reason='{}'",
            agentDid, entityId, transitEpoch, tokenId, accepted, landed, reason);
        // Loss-safety (P1): deliver an ACCEPTED ack to the source ZoneGuardian so it
        // releases the retained snapshot. A rejection is NOT delivered — the source's
        // ack-timeout then re-publishes and ultimately revives, so a never-landed
        // companion is never lost.
        if (relocateAckSink != null && accepted && entityId != null && transitEpoch > 0) {
            try {
                relocateAckSink.onArrived(entityId, agentDid, transitEpoch, fromZoneId, true);
            } catch (Exception e) {
                log.warn("Federation: relocate ack-sink threw for '{}': {}", entityId, e.getMessage());
            }
        }
    }

    /**
     * outbound publish. Source-side ZoneGuardian's
     * CompanionRelocator delegates here (via Main.java wiring) to push a
     * relocate envelope to the target zone's federation gate.
     */
    private Behavior<Command> onPublishCompanionRelocate(PublishCompanionRelocate cmd) {
        if (!initialized || natsBridge == null) {
            log.warn("Federation: PublishCompanionRelocate before init — drop");
            return this;
        }
        var token = cmd.token();
        if (token == null || token.targetZoneId() == null) {
            log.warn("Federation: PublishCompanionRelocate missing token/target — drop");
            return this;
        }
        var payload = MAPPER.createObjectNode();
        payload.put("type", "companion_relocate");
        payload.set("token", MAPPER.valueToTree(token));
        if (cmd.stateJson() != null) payload.put("stateJson", cmd.stateJson());
        if (cmd.bondholderDid() != null) payload.put("bondholderDid", cmd.bondholderDid());
        if (cmd.targetRoomHint() != null) payload.put("targetRoomHint", cmd.targetRoomHint());

        // Persist the source-side copy of the token so later inbound calls
        // (e.g. visitor return) can verify the matching pair.
        try {
            service.saveTransitToken(token);
        } catch (Exception e) {
            log.debug("Federation: source-side saveTransitToken failed: {}", e.getMessage());
        }

        natsBridge.publish("federation." + token.targetZoneId() + ".gate.companion_relocate",
            BetweenEnvelope.create(identity.nodeId(), null, payload, identity));
        log.info("Federation: published companion_relocate for agent '{}' (did={}) → zone '{}'",
            token.agentName(), token.agentDid(), token.targetZoneId());
        return this;
    }

    private void sendCompanionRelocateAck(String targetZoneId, String tokenId,
                                            String agentDid, String entityId, long transitEpoch,
                                            boolean accepted,
                                            String landedRoomId, String reason) {
        var payload = MAPPER.createObjectNode();
        payload.put("type", "companion_relocate_ack");
        if (tokenId != null) payload.put("tokenId", tokenId);
        if (agentDid != null) payload.put("agentDid", agentDid);
        if (entityId != null) payload.put("entityId", entityId);
        payload.put("transitEpoch", transitEpoch);   // 0 = pre-fence/rejection ack
        payload.put("accepted", accepted);
        if (landedRoomId != null) payload.put("landedRoomId", landedRoomId);
        payload.put("reason", reason == null ? "" : reason);

        // ackZoneId = us = the zone confirming arrival (the source matches against it).
        payload.put("fromZoneId", zoneId);

        natsBridge.publish("federation." + targetZoneId + ".gate.companion_relocate_ack",
            BetweenEnvelope.create(identity.nodeId(), null, payload, identity));
    }

    private ZoneManifest buildLocalManifest() {
        var v = AppVersion.get();
        var bv = new ZoneManifest.BuildVersion(
            v.version(), v.buildHash(), v.gitSha(),
            v.buildTimestamp(), v.wireProtocol(), v.federationSchema(),
            v.gitDirty());
        return new ZoneManifest(
            zoneId,
            zoneName,
            identity.publicKeyBase64(),
            natsBridge.isConnected() ? "nats://connected" : null,
            null, // httpUrl — set by Main.java in future
            0,    // arteryPort — set from config in future
            List.of(),
            Instant.now(),
            null, // signature — applied by ZoneManifest.sign() if needed
            null, // aestheticPreset
            bv    // F14 — build/version stamp
        ).withRelays(advertisedRelays()); //
    }

    /**
     * the relay legs this zone advertises to peers
     * (dial address + CA fingerprint + visibility — never user/token). Empty
     * for a single-relay zone with no leg config (peers fall back to natsUrl).
     */
    private List<ZoneManifest.RelayAdvert> advertisedRelays() {
        try {
            var legs = WyrdConfig.get().relayLegs();
            var out = new ArrayList<ZoneManifest.RelayAdvert>(legs.size());
            for (var leg : legs) {
                out.add(new ZoneManifest.RelayAdvert(
                    leg.url(), leg.caFingerprint(),
                    leg.visibility().name().toLowerCase(Locale.ROOT)));
            }
            return out;
        } catch (Exception e) {
            log.debug("advertisedRelays: config unavailable ({}) — advertising none", e.getMessage());
            return List.of();
        }
    }

    /**
     * F14: validate peer's federation schema version. Returns null when
     * compatible; returns a human-readable reason string when incompatible.
     * Pre-F14 peers (null buildVersion) log a warning but are allowed
     * through — refusing them would break first-encounter rollouts of
     * this very feature.
     */
    private String validatePeerVersion(ZoneManifest peer) {
        var bv = peer.buildVersion();
        if (bv == null) {
            log.warn("Federation: peer '{}' is on pre-F14 code (no buildVersion). "
                + "Allowing handshake; mesh-drift visibility unavailable until peer upgrades.",
                peer.zoneId());
            return null;
        }
        var local = AppVersion.get();
        if (bv.federationSchema() != local.federationSchema()) {
            return "incompatible federation schema: peer=" + bv.federationSchema()
                + ", local=" + local.federationSchema();
        }
        if (bv.wireProtocol() != local.wireProtocol()) {
            return "incompatible wire protocol: peer=" + bv.wireProtocol()
                + ", local=" + local.wireProtocol();
        }
        // Compatible. Log peer build for operator visibility.
        log.info("Federation: peer '{}' build={} ({}{}), schema={}",
            peer.zoneId(), bv.appVersion(), bv.buildHash(),
            bv.gitDirty() ? "+dirty" : "", bv.federationSchema());
        return null;
    }

    private void broadcastManifest() {
        var manifest = buildLocalManifest();
        var payload = MAPPER.createObjectNode();
        payload.put("type", "manifest");
        payload.set("manifest", MAPPER.valueToTree(manifest));

        // Broadcast to all known federated zones
        for (var agreement : service.listAgreements(zoneId)) {
            if (agreement.isActive()) {
                natsBridge.publish("federation." + agreement.remoteZoneId() + ".gate.manifest",
                    BetweenEnvelope.create(identity.nodeId(), null, payload, identity));
            }
        }
    }

    private void sendTransitResponse(String targetZoneId, String agentId,
                                      boolean allowed, String transitToken,
                                      String targetUrl, String reason) {
        var payload = MAPPER.createObjectNode();
        payload.put("type", "transit_response");
        payload.put("agentId", agentId);
        payload.put("allowed", allowed);
        if (transitToken != null) payload.put("transitToken", transitToken);
        if (targetUrl != null) payload.put("targetUrl", targetUrl);
        payload.put("reason", reason);

        natsBridge.publish("federation." + targetZoneId + ".gate.transit_response",
            BetweenEnvelope.create(identity.nodeId(), null, payload, identity));
    }
}
