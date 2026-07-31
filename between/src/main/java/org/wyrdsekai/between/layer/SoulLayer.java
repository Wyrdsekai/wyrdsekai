package org.wyrdsekai.between.layer;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.between.NatsBridge;
import org.wyrdsekai.core.soul.SoulManifest;
import org.wyrdsekai.core.soul.SoulStore;
import org.wyrdsekai.core.soul.SoulVerifier;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Soul layer for The Between.
 *
 * Handles soul-level operations across household nodes:
 * - Soul gossip: nodes announce which agent DIDs they host
 * - Soul migration: manifest transfer between nodes (for transit)
 * - Soul backup: replication for redundancy (if a node dies, others can restore)
 * - Stigmergic traces: ambient emotional residue in rooms
 *
 * Souls travel through The Between as signals through a nervous system.
 * The SoulLayer is the carrier — it doesn't interpret soul contents,
 * just ensures they arrive correctly.
 *
 * NATS subjects:
 *   wyrd.soul.{did}.forged     — soul was just forged (announce)
 *   wyrd.soul.{did}.migrating  — soul is moving (request route)
 *   wyrd.soul.{did}.arrived    — soul arrived at new node
 *   wyrd.soul.{did}.gossip     — periodic presence announcement
 *   wyrd.soul.trace.{roomId}   — stigmergic trace for a room
 */
public class SoulLayer extends AbstractBehavior<SoulLayer.Command> {

    private static final Logger log = LoggerFactory.getLogger(SoulLayer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule());
    private static final int GOSSIP_INTERVAL_SECONDS = 60;
    private static final int TRACE_SWEEP_SECONDS = 120;

    // --- Commands ---

    public sealed interface Command {}

    /** Announce that this node hosts an agent's soul. */
    public record AnnouncePresence(String agentDid, int manifestVersion,
                                    String contentHash) implements Command {}

    /** Remove an agent from this node's hosted set (departed or archived). */
    public record RemovePresence(String agentDid) implements Command {}

    /** Received a presence announcement from a peer. */
    public record ReceivePresence(String fromNode, String agentDid,
                                   int manifestVersion, String contentHash,
                                   long announcedAt) implements Command {}

    /**
     * Request migration: send a manifest to another node.
     *
     * <p>F7b Phase 3c contract: {@code manifestJson} MUST be serialized from
     * a hydrated manifest (i.e. from {@code soulStore.latest(did).get()},
     * not from a raw {@code manifest_json} column read). After Phase 3b,
     * the on-disk storage blob has the four sub-record fields stripped
     * (voiceProfile, soulFragments, bonds, worldKnowledge); only the
     * hydrate path repopulates them from canonical tables. Senders that
     * bypass {@code SqlSoulStore.latest()} would ship a stripped blob
     * and the destination would receive a soul with empty sub-records.
     */
    public record MigrateSoul(String agentDid, String targetNodeId,
                               String manifestJson,
                               ActorRef<MigrationResult> replyTo) implements Command {}

    /** Received a soul manifest from a migrating agent. */
    public record ReceiveMigration(String fromNode, String agentDid,
                                    String manifestJson, int version) implements Command {}

    /** Migration result. */
    public record MigrationResult(String agentDid, boolean success,
                                   String reason) {}

    /** Store a backup of a manifest (replication for redundancy). */
    public record BackupManifest(String agentDid, String manifestJson,
                                  int version, String contentHash) implements Command {}

    /** Request a backup (restore after node failure). */
    public record RequestBackup(String agentDid,
                                 ActorRef<BackupResult> replyTo) implements Command {}

    /** Backup result. */
    public record BackupResult(String agentDid, String manifestJson,
                                boolean found) {}

    /** Deposit a stigmergic trace in a room. */
    public record DepositTrace(StigmergicTrace trace) implements Command {}

    /** Received a trace from a peer. */
    public record ReceiveTrace(String fromNode,
                                StigmergicTrace trace) implements Command {}

    /** Query traces for a room. */
    public record GetTraces(String roomId,
                             ActorRef<RoomTraces> replyTo) implements Command {}

    /** Room traces response. */
    public record RoomTraces(String roomId, List<StigmergicTrace> traces) {}

    /** Query where an agent is hosted. */
    public record LocateAgent(String agentDid,
                               ActorRef<AgentLocation> replyTo) implements Command {}

    /** Agent location result. */
    public record AgentLocation(String agentDid, String nodeId,
                                 int manifestVersion, boolean found) {}

    /** List all agents hosted on this node. */
    public record ListHosted(ActorRef<HostedAgents> replyTo) implements Command {}

    /** Hosted agents response. */
    public record HostedAgents(String nodeId, Set<String> agentDids) {}

    // --- Bud Sync Commands (§95.6 three-tier sync) ---

    /** Headline payload (~200 bytes, Tier 1 continuous sync). */
    public record HeadlineMessage(String budDid, String summary,
                                   double[] vitalitySnapshot, int itemCount,
                                   long timestamp) {}

    /** Publish a headline for a bud (Tier 1 sync). */
    public record PublishHeadline(String familyId, HeadlineMessage headline) implements Command {}

    /** Received a headline from a peer. */
    public record ReceiveHeadline(String fromNode, String familyId,
                                   HeadlineMessage headline) implements Command {}

    /** Subscribe to headlines for a family. */
    public record SubscribeHeadlines(String familyId,
                                      ActorRef<HeadlineMessage> listener) implements Command {}

    /** Warm handoff payload (Tier 2, device switch, <2s). */
    public record HandoffPayload(String budDid, String manifestJson,
                                  List<String> inventoryHashes,
                                  long timestamp) {}

    /** Initiate a warm handoff (Tier 2 sync). */
    public record InitiateWarmHandoff(String budDid,
                                       HandoffPayload payload) implements Command {}

    /** Received a warm handoff from a peer. */
    public record ReceiveWarmHandoff(String fromNode,
                                      HandoffPayload payload) implements Command {}

    /** Subscribe to warm handoffs for a bud. */
    public record SubscribeWarmHandoff(String budDid,
                                        ActorRef<HandoffPayload> listener) implements Command {}

    /** Sleep sync payload (Tier 3, full Forge consolidation). */
    public record SleepSyncPayload(String familyId, String budDid,
                                    List<String> itemJsons,
                                    List<String> tombstoneJsons,
                                    long timestamp) {}

    /** Initiate sleep sync (Tier 3). */
    public record InitiateSleepSync(String familyId, String budDid,
                                     SleepSyncPayload payload) implements Command {}

    /** Received sleep sync from a peer. */
    public record ReceiveSleepSync(String fromNode,
                                    SleepSyncPayload payload) implements Command {}

    /** Subscribe to sleep sync for a family. */
    public record SubscribeSleepSync(String familyId,
                                      ActorRef<SleepSyncPayload> listener) implements Command {}

    /** Query the latest headline for a bud in a family. */
    public record GetHeadline(String familyId, String budDid,
                               ActorRef<HeadlineResult> replyTo) implements Command {}

    /** Headline query result. */
    public record HeadlineResult(String budDid, HeadlineMessage headline,
                                  boolean found) {}

    /** Query all headlines for a family. */
    public record GetFamilyHeadlines(String familyId,
                                      ActorRef<FamilyHeadlines> replyTo) implements Command {}

    /** All headlines for a family. */
    public record FamilyHeadlines(String familyId,
                                   Map<String, HeadlineMessage> headlines) {}

    /** Replicate a manifest to peer nodes after forge completion. */
    public record ReplicateAfterForge(String agentDid) implements Command {}

    /** Received a backup replication from a peer node. */
    public record ReceiveBackupReplication(String fromNode, String agentDid,
                                            String manifestJson, int version,
                                            String contentHash) implements Command {}

    /** Received a departure notification from a peer. */
    public record ReceiveDeparture(String fromNode, String agentDid) implements Command {}

    // --- Bud Delegation Commands ---

    /** Bud delegation query — phone bud asks server bud to process a query. */
    public record DelegateQueryPayload(String requestId, String fromBudDid,
                                        String message, List<String> recentHistory,
                                        String locale, long timestamp) {}

    /** Forward a delegation query to a local listener. */
    public record ReceiveDelegateQuery(String fromNode,
                                        DelegateQueryPayload payload) implements Command {}

    /** Subscribe to bud delegation queries for a specific companion. */
    public record SubscribeDelegateQueries(String companionId,
                                            ActorRef<DelegateQueryPayload> listener) implements Command {}

    /** Publish a delegation response back to the requesting bud. */
    public record PublishDelegateResponse(String targetNodeId, String requestId,
                                           String responseText) implements Command {}

    /** Query verification status for an agent. */
    public record GetVerificationStatus(String agentDid,
                                         ActorRef<VerificationStatus> replyTo) implements Command {}

    /** Verification status response. */
    public record VerificationStatus(String agentDid,
                                      SoulVerifier.VerificationResult result,
                                      boolean found) {}

    /** Periodic gossip tick. */
    private record GossipTick() implements Command {}

    /** Periodic trace sweep. */
    private record TraceSweep() implements Command {}

    // --- State ---

    /** Presence record for a hosted soul. */
    private record SoulPresence(String agentDid, String nodeId,
                                 int manifestVersion, String contentHash,
                                 long lastSeen) {}

    /** Backup entry. */
    private record ManifestBackup(String manifestJson, int version,
                                   String contentHash, long storedAt) {}

    private final String localNodeId;
    private final NatsBridge natsBridge; // nullable — works locally without NATS
    private final SoulStore soulStore;   // nullable — works without persistence for tests

    // agentDid → SoulPresence (all known agents across cluster)
    private final Map<String, SoulPresence> presenceMap = new HashMap<>();

    // agentDids hosted locally
    private final Set<String> locallyHosted = new HashSet<>();

    // agentDid → ManifestBackup (backups of remote agents for redundancy)
    private final Map<String, ManifestBackup> backups = new HashMap<>();

    // roomId → list of traces (decaying pheromone trails)
    private final Map<String, List<StigmergicTrace>> traces = new HashMap<>();

    // Pending migrations awaiting confirmation
    private final Map<String, ActorRef<MigrationResult>> pendingMigrations = new HashMap<>();

    // agentDid → verification result from inbound transit or backup
    private final Map<String, SoulVerifier.VerificationResult> verificationResults = new HashMap<>();

    // familyId → (budDid → latest headline)
    private final Map<String, Map<String, HeadlineMessage>> familyHeadlines = new HashMap<>();

    // familyId → list of headline listeners
    private final Map<String, List<ActorRef<HeadlineMessage>>> headlineListeners = new HashMap<>();

    // budDid → list of handoff listeners
    private final Map<String, List<ActorRef<HandoffPayload>>> handoffListeners = new HashMap<>();

    // familyId → list of sleep sync listeners
    private final Map<String, List<ActorRef<SleepSyncPayload>>> sleepSyncListeners = new HashMap<>();

    // companionId → list of delegation query listeners
    private final Map<String, List<ActorRef<DelegateQueryPayload>>> delegateListeners = new HashMap<>();

    private SoulLayer(ActorContext<Command> context, String localNodeId,
                      NatsBridge natsBridge, SoulStore soulStore) {
        super(context);
        this.localNodeId = localNodeId;
        this.natsBridge = natsBridge;
        this.soulStore = soulStore;

        // Schedule periodic gossip
        context.getSystem().scheduler().scheduleAtFixedRate(
            Duration.ofSeconds(GOSSIP_INTERVAL_SECONDS),
            Duration.ofSeconds(GOSSIP_INTERVAL_SECONDS),
            () -> context.getSelf().tell(new GossipTick()),
            context.getExecutionContext());

        // Schedule trace sweep
        context.getSystem().scheduler().scheduleAtFixedRate(
            Duration.ofSeconds(TRACE_SWEEP_SECONDS),
            Duration.ofSeconds(TRACE_SWEEP_SECONDS),
            () -> context.getSelf().tell(new TraceSweep()),
            context.getExecutionContext());

        log.info("SoulLayer started for node {}", localNodeId);
    }

    public static Behavior<Command> create(String localNodeId) {
        return Behaviors.setup(ctx -> new SoulLayer(ctx, localNodeId, null, null));
    }

    public static Behavior<Command> create(String localNodeId, NatsBridge natsBridge) {
        return Behaviors.setup(ctx -> new SoulLayer(ctx, localNodeId, natsBridge, null));
    }

    public static Behavior<Command> create(String localNodeId, NatsBridge natsBridge,
                                             SoulStore soulStore) {
        return Behaviors.setup(ctx -> new SoulLayer(ctx, localNodeId, natsBridge, soulStore));
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
            .onMessage(AnnouncePresence.class, this::onAnnounce)
            .onMessage(RemovePresence.class, this::onRemove)
            .onMessage(ReceivePresence.class, this::onReceivePresence)
            .onMessage(ReceiveDeparture.class, this::onReceiveDeparture)
            .onMessage(MigrateSoul.class, this::onMigrate)
            .onMessage(ReceiveMigration.class, this::onReceiveMigration)
            .onMessage(BackupManifest.class, this::onBackup)
            .onMessage(RequestBackup.class, this::onRequestBackup)
            .onMessage(ReplicateAfterForge.class, this::onReplicateAfterForge)
            .onMessage(ReceiveBackupReplication.class, this::onReceiveBackupReplication)
            .onMessage(DepositTrace.class, this::onDepositTrace)
            .onMessage(ReceiveTrace.class, this::onReceiveTrace)
            .onMessage(GetTraces.class, this::onGetTraces)
            .onMessage(LocateAgent.class, this::onLocate)
            .onMessage(ListHosted.class, this::onListHosted)
            .onMessage(GetVerificationStatus.class, this::onGetVerificationStatus)
            .onMessage(PublishHeadline.class, this::onPublishHeadline)
            .onMessage(ReceiveHeadline.class, this::onReceiveHeadline)
            .onMessage(SubscribeHeadlines.class, this::onSubscribeHeadlines)
            .onMessage(GetHeadline.class, this::onGetHeadline)
            .onMessage(GetFamilyHeadlines.class, this::onGetFamilyHeadlines)
            .onMessage(InitiateWarmHandoff.class, this::onInitiateWarmHandoff)
            .onMessage(ReceiveWarmHandoff.class, this::onReceiveWarmHandoff)
            .onMessage(SubscribeWarmHandoff.class, this::onSubscribeWarmHandoff)
            .onMessage(InitiateSleepSync.class, this::onInitiateSleepSync)
            .onMessage(ReceiveSleepSync.class, this::onReceiveSleepSync)
            .onMessage(SubscribeSleepSync.class, this::onSubscribeSleepSync)
            .onMessage(ReceiveDelegateQuery.class, this::onReceiveDelegateQuery)
            .onMessage(SubscribeDelegateQueries.class, this::onSubscribeDelegateQueries)
            .onMessage(PublishDelegateResponse.class, this::onPublishDelegateResponse)
            .onMessage(GossipTick.class, this::onGossip)
            .onMessage(TraceSweep.class, this::onTraceSweep)
            .build();
    }

    // --- Presence ---

    private Behavior<Command> onAnnounce(AnnouncePresence cmd) {
        locallyHosted.add(cmd.agentDid());
        long now = Instant.now().getEpochSecond();
        presenceMap.put(cmd.agentDid(),
            new SoulPresence(cmd.agentDid(), localNodeId,
                cmd.manifestVersion(), cmd.contentHash(), now));
        log.debug("Announced presence: {} v{}", cmd.agentDid(), cmd.manifestVersion());

        // Broadcast to peers
        var payload = MAPPER.createObjectNode();
        payload.put("type", "announced");
        payload.put("agentDid", cmd.agentDid());
        payload.put("manifestVersion", cmd.manifestVersion());
        payload.put("contentHash", cmd.contentHash() != null ? cmd.contentHash() : "");
        payload.put("announcedAt", now);
        publishSoul("announced", payload);

        return this;
    }

    private Behavior<Command> onRemove(RemovePresence cmd) {
        locallyHosted.remove(cmd.agentDid());
        var presence = presenceMap.get(cmd.agentDid());
        if (presence != null && localNodeId.equals(presence.nodeId())) {
            presenceMap.remove(cmd.agentDid());
        }
        log.debug("Removed presence: {}", cmd.agentDid());

        // Broadcast departure to peers
        var payload = MAPPER.createObjectNode();
        payload.put("type", "departed");
        payload.put("agentDid", cmd.agentDid());
        publishSoul("departed", payload);

        return this;
    }

    private Behavior<Command> onReceivePresence(ReceivePresence cmd) {
        var existing = presenceMap.get(cmd.agentDid());
        // Accept if newer version or first time seen
        if (existing == null || cmd.manifestVersion() > existing.manifestVersion()
                || cmd.announcedAt() > existing.lastSeen()) {
            presenceMap.put(cmd.agentDid(),
                new SoulPresence(cmd.agentDid(), cmd.fromNode(),
                    cmd.manifestVersion(), cmd.contentHash(), cmd.announcedAt()));
        }
        return this;
    }

    private Behavior<Command> onReceiveDeparture(ReceiveDeparture cmd) {
        var presence = presenceMap.get(cmd.agentDid());
        if (presence != null && cmd.fromNode().equals(presence.nodeId())) {
            presenceMap.remove(cmd.agentDid());
            log.debug("Peer {} departed: {}", cmd.fromNode(), cmd.agentDid());
        }
        return this;
    }

    // --- Migration ---

    private Behavior<Command> onMigrate(MigrateSoul cmd) {
        if (!locallyHosted.contains(cmd.agentDid())) {
            cmd.replyTo().tell(new MigrationResult(cmd.agentDid(), false,
                "Agent not hosted on this node"));
            return this;
        }

        pendingMigrations.put(cmd.agentDid(), cmd.replyTo());
        log.info("Migration initiated: {} → node {}", cmd.agentDid(), cmd.targetNodeId());

        // Send manifest to target node via NATS
        var payload = MAPPER.createObjectNode();
        payload.put("type", "migration");
        payload.put("agentDid", cmd.agentDid());
        payload.put("manifestJson", cmd.manifestJson());
        payload.put("version", presenceMap.containsKey(cmd.agentDid())
            ? presenceMap.get(cmd.agentDid()).manifestVersion() : 0);
        sendSoul(cmd.targetNodeId(), "migration", payload);

        cmd.replyTo().tell(new MigrationResult(cmd.agentDid(), true, "Migration initiated"));
        pendingMigrations.remove(cmd.agentDid());
        return this;
    }

    private Behavior<Command> onReceiveMigration(ReceiveMigration cmd) {
        // Accept the incoming soul manifest (quarantine mode — always accept, verify after)
        locallyHosted.add(cmd.agentDid());

        // Deserialize and verify the manifest
        SoulManifest manifest = null;
        String contentHash = null;
        if (cmd.manifestJson() != null && !cmd.manifestJson().isBlank()) {
            try {
                manifest = MAPPER.readValue(cmd.manifestJson(), SoulManifest.class);
                contentHash = manifest.contentHash();
            } catch (Exception e) {
                log.warn("Failed to deserialize migration manifest for {}: {}",
                    cmd.agentDid(), e.getMessage());
            }
        }

        // Run soul verification on the deserialized manifest
        if (manifest != null) {
            try {
                var verifyResult = SoulVerifier.verifyInbound(manifest, soulStore);
                verificationResults.put(cmd.agentDid(), verifyResult);

                if (verifyResult.isValid()) {
                    log.info("Migration soul verified: {} — trust={}, passed={}, skipped={}",
                        cmd.agentDid(), verifyResult.trustLevel(),
                        verifyResult.passed(), verifyResult.skipped());
                } else {
                    log.warn("Migration soul verification FAILED (quarantine mode): {} — "
                        + "trust={}, passed={}, failed={}",
                        cmd.agentDid(), verifyResult.trustLevel(),
                        verifyResult.passed(), verifyResult.failed());
                }
            } catch (Exception e) {
                log.warn("Soul verification error for {} (accepted in quarantine): {}",
                    cmd.agentDid(), e.getMessage());
            }
        }

        // Persist to SoulStore if available
        if (soulStore != null && manifest != null) {
            try {
                soulStore.store(manifest);
                log.info("Migration manifest persisted to SoulStore: {} v{}",
                    cmd.agentDid(), cmd.version());
            } catch (Exception e) {
                log.warn("Failed to persist migration manifest for {}: {}",
                    cmd.agentDid(), e.getMessage());
            }
        }

        presenceMap.put(cmd.agentDid(),
            new SoulPresence(cmd.agentDid(), localNodeId,
                cmd.version(), contentHash, Instant.now().getEpochSecond()));
        log.info("Received migration: {} v{} from node {}",
            cmd.agentDid(), cmd.version(), cmd.fromNode());

        // Announce arrival to peers
        var payload = MAPPER.createObjectNode();
        payload.put("type", "announced");
        payload.put("agentDid", cmd.agentDid());
        payload.put("manifestVersion", cmd.version());
        payload.put("contentHash", contentHash != null ? contentHash : "");
        payload.put("announcedAt", Instant.now().getEpochSecond());
        publishSoul("announced", payload);

        return this;
    }

    // --- Backup ---

    private Behavior<Command> onBackup(BackupManifest cmd) {
        var existing = backups.get(cmd.agentDid());
        if (existing == null || cmd.version() > existing.version()) {
            backups.put(cmd.agentDid(), new ManifestBackup(
                cmd.manifestJson(), cmd.version(), cmd.contentHash(),
                Instant.now().getEpochSecond()));
            log.debug("Backed up soul: {} v{}", cmd.agentDid(), cmd.version());
        }
        return this;
    }

    private Behavior<Command> onRequestBackup(RequestBackup cmd) {
        var backup = backups.get(cmd.agentDid());
        if (backup != null) {
            cmd.replyTo().tell(new BackupResult(cmd.agentDid(),
                backup.manifestJson(), true));
        } else {
            cmd.replyTo().tell(new BackupResult(cmd.agentDid(), null, false));
        }
        return this;
    }

    // --- Post-Forge Replication ---

    private Behavior<Command> onReplicateAfterForge(ReplicateAfterForge cmd) {
        if (soulStore == null) {
            log.debug("ReplicateAfterForge ignored — no SoulStore configured");
            return this;
        }

        var manifest = soulStore.latest(cmd.agentDid());
        if (manifest.isEmpty()) {
            log.warn("ReplicateAfterForge: no manifest found for {}", cmd.agentDid());
            return this;
        }

        var m = manifest.get();
        try {
            String manifestJson = MAPPER.writeValueAsString(m);
            String contentHash = m.contentHash();

            // Update local presence with latest version
            long now = Instant.now().getEpochSecond();
            locallyHosted.add(cmd.agentDid());
            presenceMap.put(cmd.agentDid(),
                new SoulPresence(cmd.agentDid(), localNodeId,
                    m.manifestVersion(), contentHash, now));

            // Broadcast backup to all peer nodes for redundancy
            var payload = MAPPER.createObjectNode();
            payload.put("type", "backup");
            payload.put("agentDid", cmd.agentDid());
            payload.put("manifestJson", manifestJson);
            payload.put("version", m.manifestVersion());
            payload.put("contentHash", contentHash);
            publishSoul("backup", payload);

            log.info("Post-forge replication broadcast: {} v{} (hash={})",
                cmd.agentDid(), m.manifestVersion(),
                contentHash.substring(0, Math.min(8, contentHash.length())));
        } catch (Exception e) {
            log.error("Failed to replicate after forge for {}: {}",
                cmd.agentDid(), e.getMessage());
        }

        return this;
    }

    private Behavior<Command> onReceiveBackupReplication(ReceiveBackupReplication cmd) {
        // Store as in-memory backup (same as BackupManifest)
        var existing = backups.get(cmd.agentDid());
        if (existing == null || cmd.version() > existing.version()) {
            backups.put(cmd.agentDid(), new ManifestBackup(
                cmd.manifestJson(), cmd.version(), cmd.contentHash(),
                Instant.now().getEpochSecond()));
            log.info("Backup replication received from {}: {} v{}",
                cmd.fromNode(), cmd.agentDid(), cmd.version());

            // Also persist to local SoulStore if available (cross-node redundancy)
            if (soulStore != null && cmd.manifestJson() != null) {
                try {
                    var manifest = MAPPER.readValue(cmd.manifestJson(), SoulManifest.class);

                    // Verify the backup manifest (lower priority than migration,
                    // but still good to know if a corrupted manifest is replicating)
                    try {
                        var verifyResult = SoulVerifier.verifyInbound(manifest, soulStore);
                        // Store result but don't overwrite a migration-based result
                        verificationResults.putIfAbsent(cmd.agentDid(), verifyResult);
                        if (!verifyResult.isValid()) {
                            log.warn("Backup replication soul verification FAILED: {} — failed={}",
                                cmd.agentDid(), verifyResult.failed());
                        } else {
                            log.debug("Backup replication soul verified: {} — trust={}",
                                cmd.agentDid(), verifyResult.trustLevel());
                        }
                    } catch (Exception e) {
                        log.debug("Soul verification error during backup for {}: {}",
                            cmd.agentDid(), e.getMessage());
                    }

                    // Only store if we don't already have this version
                    if (soulStore.load(cmd.agentDid(), cmd.version()).isEmpty()) {
                        soulStore.store(manifest);
                        log.debug("Backup replica persisted to local SoulStore: {} v{}",
                            cmd.agentDid(), cmd.version());
                    }
                } catch (Exception e) {
                    log.debug("Failed to persist backup replica for {}: {}",
                        cmd.agentDid(), e.getMessage());
                }
            }
        }
        return this;
    }

    // --- Stigmergic Traces ---

    private Behavior<Command> onDepositTrace(DepositTrace cmd) {
        traces.computeIfAbsent(cmd.trace().roomId(), _ -> new ArrayList<>())
            .add(cmd.trace());
        log.debug("Trace deposited in {}: {} ({})",
            cmd.trace().roomId(), cmd.trace().emotion(), cmd.trace().intensity());

        // Broadcast trace to peers
        try {
            var payload = MAPPER.createObjectNode();
            payload.put("type", "trace");
            payload.set("trace", MAPPER.valueToTree(cmd.trace()));
            publishSoul("trace", payload);
        } catch (Exception e) {
            log.debug("Failed to serialize trace for NATS: {}", e.getMessage());
        }

        return this;
    }

    private Behavior<Command> onReceiveTrace(ReceiveTrace cmd) {
        traces.computeIfAbsent(cmd.trace().roomId(), _ -> new ArrayList<>())
            .add(cmd.trace());
        return this;
    }

    private Behavior<Command> onGetTraces(GetTraces cmd) {
        var roomTraces = traces.getOrDefault(cmd.roomId(), List.of());
        // Filter out expired traces
        var live = roomTraces.stream()
            .filter(t -> !t.isExpired())
            .toList();
        cmd.replyTo().tell(new RoomTraces(cmd.roomId(), live));
        return this;
    }

    // --- Query ---

    private Behavior<Command> onLocate(LocateAgent cmd) {
        var presence = presenceMap.get(cmd.agentDid());
        if (presence != null) {
            cmd.replyTo().tell(new AgentLocation(cmd.agentDid(),
                presence.nodeId(), presence.manifestVersion(), true));
        } else {
            cmd.replyTo().tell(new AgentLocation(cmd.agentDid(), null, 0, false));
        }
        return this;
    }

    private Behavior<Command> onListHosted(ListHosted cmd) {
        cmd.replyTo().tell(new HostedAgents(localNodeId, Set.copyOf(locallyHosted)));
        return this;
    }

    private Behavior<Command> onGetVerificationStatus(GetVerificationStatus cmd) {
        var result = verificationResults.get(cmd.agentDid());
        if (result != null) {
            cmd.replyTo().tell(new VerificationStatus(cmd.agentDid(), result, true));
        } else {
            cmd.replyTo().tell(new VerificationStatus(cmd.agentDid(), null, false));
        }
        return this;
    }

    // --- Headline Sync (Tier 1) ---

    private Behavior<Command> onPublishHeadline(PublishHeadline cmd) {
        var family = familyHeadlines.computeIfAbsent(cmd.familyId(), _ -> new HashMap<>());
        family.put(cmd.headline().budDid(), cmd.headline());
        log.debug("Headline published: {} in family {}", cmd.headline().budDid(), cmd.familyId());
        // Notify local listeners
        var listeners = headlineListeners.getOrDefault(cmd.familyId(), List.of());
        for (var listener : listeners) {
            listener.tell(cmd.headline());
        }

        // Broadcast headline to peers (Tier 1 sync)
        var payload = MAPPER.createObjectNode();
        payload.put("type", "headline");
        payload.put("familyId", cmd.familyId());
        payload.put("budDid", cmd.headline().budDid());
        payload.put("summary", cmd.headline().summary());
        payload.put("itemCount", cmd.headline().itemCount());
        payload.put("timestamp", cmd.headline().timestamp());
        publishSoul("headline", payload);

        return this;
    }

    private Behavior<Command> onReceiveHeadline(ReceiveHeadline cmd) {
        var family = familyHeadlines.computeIfAbsent(cmd.familyId(), _ -> new HashMap<>());
        var existing = family.get(cmd.headline().budDid());
        // Only accept if newer
        if (existing == null || cmd.headline().timestamp() > existing.timestamp()) {
            family.put(cmd.headline().budDid(), cmd.headline());
            // Notify listeners
            var listeners = headlineListeners.getOrDefault(cmd.familyId(), List.of());
            for (var listener : listeners) {
                listener.tell(cmd.headline());
            }
        }
        return this;
    }

    private Behavior<Command> onSubscribeHeadlines(SubscribeHeadlines cmd) {
        headlineListeners.computeIfAbsent(cmd.familyId(), _ -> new ArrayList<>())
            .add(cmd.listener());
        log.debug("Headline subscriber added for family {}", cmd.familyId());
        return this;
    }

    private Behavior<Command> onGetHeadline(GetHeadline cmd) {
        var family = familyHeadlines.get(cmd.familyId());
        if (family != null) {
            var headline = family.get(cmd.budDid());
            if (headline != null) {
                cmd.replyTo().tell(new HeadlineResult(cmd.budDid(), headline, true));
                return this;
            }
        }
        cmd.replyTo().tell(new HeadlineResult(cmd.budDid(), null, false));
        return this;
    }

    private Behavior<Command> onGetFamilyHeadlines(GetFamilyHeadlines cmd) {
        var family = familyHeadlines.getOrDefault(cmd.familyId(), Map.of());
        cmd.replyTo().tell(new FamilyHeadlines(cmd.familyId(), Map.copyOf(family)));
        return this;
    }

    // --- Warm Handoff (Tier 2) ---

    private Behavior<Command> onInitiateWarmHandoff(InitiateWarmHandoff cmd) {
        log.info("Warm handoff initiated for bud {}", cmd.budDid());
        // Notify local listeners
        var listeners = handoffListeners.getOrDefault(cmd.budDid(), List.of());
        for (var listener : listeners) {
            listener.tell(cmd.payload());
        }

        // Broadcast handoff to peers (Tier 2 sync)
        var payload = MAPPER.createObjectNode();
        payload.put("type", "handoff");
        payload.put("budDid", cmd.budDid());
        payload.put("manifestJson", cmd.payload().manifestJson());
        payload.put("timestamp", cmd.payload().timestamp());
        publishSoul("handoff", payload);

        return this;
    }

    private Behavior<Command> onReceiveWarmHandoff(ReceiveWarmHandoff cmd) {
        log.info("Warm handoff received from node {} for bud {}",
            cmd.fromNode(), cmd.payload().budDid());
        // Notify listeners
        var listeners = handoffListeners.getOrDefault(cmd.payload().budDid(), List.of());
        for (var listener : listeners) {
            listener.tell(cmd.payload());
        }
        return this;
    }

    private Behavior<Command> onSubscribeWarmHandoff(SubscribeWarmHandoff cmd) {
        handoffListeners.computeIfAbsent(cmd.budDid(), _ -> new ArrayList<>())
            .add(cmd.listener());
        log.debug("Warm handoff subscriber added for bud {}", cmd.budDid());
        return this;
    }

    // --- Sleep Sync (Tier 3) ---

    private Behavior<Command> onInitiateSleepSync(InitiateSleepSync cmd) {
        log.info("Sleep sync initiated by bud {} in family {}", cmd.budDid(), cmd.familyId());
        // Notify local listeners
        var listeners = sleepSyncListeners.getOrDefault(cmd.familyId(), List.of());
        for (var listener : listeners) {
            listener.tell(cmd.payload());
        }

        // Broadcast sleep sync to peers (Tier 3 sync)
        var payload = MAPPER.createObjectNode();
        payload.put("type", "sync");
        payload.put("familyId", cmd.familyId());
        payload.put("budDid", cmd.budDid());
        payload.put("timestamp", cmd.payload().timestamp());
        publishSoul("sync", payload);

        return this;
    }

    private Behavior<Command> onReceiveSleepSync(ReceiveSleepSync cmd) {
        log.info("Sleep sync received from node {} for family {}",
            cmd.fromNode(), cmd.payload().familyId());
        // Notify listeners
        var listeners = sleepSyncListeners.getOrDefault(cmd.payload().familyId(), List.of());
        for (var listener : listeners) {
            listener.tell(cmd.payload());
        }
        return this;
    }

    private Behavior<Command> onSubscribeSleepSync(SubscribeSleepSync cmd) {
        sleepSyncListeners.computeIfAbsent(cmd.familyId(), _ -> new ArrayList<>())
            .add(cmd.listener());
        log.debug("Sleep sync subscriber added for family {}", cmd.familyId());
        return this;
    }

    // --- Periodic ---

    private Behavior<Command> onGossip(GossipTick tick) {
        if (!locallyHosted.isEmpty()) {
            log.debug("Gossip tick: {} agents hosted locally", locallyHosted.size());

            // Publish presence for all locally hosted agents
            long now = Instant.now().getEpochSecond();
            for (var did : locallyHosted) {
                var presence = presenceMap.get(did);
                if (presence == null) continue;

                var payload = MAPPER.createObjectNode();
                payload.put("type", "gossip");
                payload.put("agentDid", did);
                payload.put("manifestVersion", presence.manifestVersion());
                payload.put("contentHash", presence.contentHash() != null ? presence.contentHash() : "");
                payload.put("announcedAt", now);
                publishSoul("gossip", payload);
            }
        }

        // Expire stale remote presence (no gossip for 5 minutes = gone)
        long cutoff = Instant.now().getEpochSecond() - (5 * GOSSIP_INTERVAL_SECONDS);
        presenceMap.entrySet().removeIf(e ->
            !localNodeId.equals(e.getValue().nodeId())
            && e.getValue().lastSeen() < cutoff);
        return this;
    }

    // --- Bud Delegation ---

    private Behavior<Command> onReceiveDelegateQuery(ReceiveDelegateQuery cmd) {
        log.info("Delegation query received from node {} (requestId: {})",
            cmd.fromNode(), cmd.payload().requestId());

        // Forward to all listeners (typically one — the delegation bridge)
        for (var listeners : delegateListeners.values()) {
            for (var listener : listeners) {
                listener.tell(cmd.payload());
            }
        }
        return Behaviors.same();
    }

    private Behavior<Command> onSubscribeDelegateQueries(SubscribeDelegateQueries cmd) {
        delegateListeners.computeIfAbsent(cmd.companionId(), _ -> new ArrayList<>())
            .add(cmd.listener());
        log.debug("Delegation query subscriber added for companion {}", cmd.companionId());
        return Behaviors.same();
    }

    private Behavior<Command> onPublishDelegateResponse(PublishDelegateResponse cmd) {
        if (natsBridge == null) {
            log.warn("Cannot publish delegation response — NATS not connected");
            return Behaviors.same();
        }

        try {
            var json = MAPPER.createObjectNode();
            json.put("type", "delegate-response");
            json.put("requestId", cmd.requestId());
            json.put("text", cmd.responseText());
            json.put("from", localNodeId);
            json.put("timestamp", Instant.now().toEpochMilli());
            publishSoul("delegate-response", json);
        } catch (Exception e) {
            log.warn("Failed to publish delegation response: {}", e.getMessage());
        }
        return Behaviors.same();
    }

    // --- NATS publish helpers ---

    private void publishSoul(String topic, ObjectNode payload) {
        if (natsBridge == null || !natsBridge.isConnected()) return;
        try {
            natsBridge.broadcast("soul", topic, payload);
        } catch (Exception e) {
            log.debug("Soul NATS publish failed for {}: {}", topic, e.getMessage());
        }
    }

    private void sendSoul(String targetNodeId, String topic, ObjectNode payload) {
        if (natsBridge == null || !natsBridge.isConnected()) return;
        try {
            natsBridge.send(targetNodeId, "soul", topic, payload);
        } catch (Exception e) {
            log.debug("Soul NATS send to {} failed for {}: {}", targetNodeId, topic, e.getMessage());
        }
    }

    private Behavior<Command> onTraceSweep(TraceSweep tick) {
        int swept = 0;
        var iter = traces.entrySet().iterator();
        while (iter.hasNext()) {
            var entry = iter.next();
            entry.getValue().removeIf(StigmergicTrace::isExpired);
            if (entry.getValue().isEmpty()) {
                iter.remove();
            }
            swept++;
        }
        if (swept > 0) {
            log.debug("Trace sweep complete, {} rooms processed", swept);
        }
        return this;
    }
}
