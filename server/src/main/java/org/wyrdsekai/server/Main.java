package org.wyrdsekai.server;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.http.staticfiles.Location;
import io.nats.client.Nats;
import io.nats.client.Options;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.Props;
import org.apache.pekko.actor.typed.javadsl.AskPattern;
import org.apache.pekko.persistence.typed.ReplicaId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.familiar.CrossZoneCopyService;
import org.wyrdsekai.core.library.AgentIngestService;
import org.wyrdsekai.core.library.KnowledgePackIndexer;
import org.wyrdsekai.core.library.LibraryActor;
import org.wyrdsekai.core.library.LibraryConfig;
import org.wyrdsekai.core.library.LibraryMigration;
import org.wyrdsekai.core.library.LibraryStore;
import org.wyrdsekai.core.library.OutputSanitizer;
import org.wyrdsekai.core.library.SecurityPatternManager;
import org.wyrdsekai.core.library.BundledPackInstaller;
import org.wyrdsekai.core.library.DocumentIndexer;
import org.wyrdsekai.core.library.FirstShelfSeeder;
import org.wyrdsekai.core.library.StarterLibraryInstaller;
import org.wyrdsekai.core.library.StudyService;
import org.wyrdsekai.core.economy.ComputeUnitNormalizer;
import org.wyrdsekai.core.economy.CountingHouseActor;
import org.wyrdsekai.core.economy.CountingHouseCommand;
import org.wyrdsekai.core.economy.CountingHouseGateway;
import org.wyrdsekai.core.economy.CountingHouseState;
import org.wyrdsekai.core.economy.LedgerPersistence;
import org.wyrdsekai.core.economy.ReputationVector;
import org.wyrdsekai.core.economy.ResourceMeter;
import org.wyrdsekai.core.economy.ResourceUsage;
import org.wyrdsekai.core.economy.TradingPostService;
import org.wyrdsekai.core.economy.AgentReputation;
import org.wyrdsekai.core.economy.AttestationService;
import org.wyrdsekai.core.economy.MeteringService;
import org.wyrdsekai.core.economy.ReferenceRates;
import org.wyrdsekai.core.mcp.McpGatewayService;
import org.wyrdsekai.core.skill.SkillBootstrap;
import org.wyrdsekai.core.skill.SkillInstaller;
import org.wyrdsekai.core.skill.SkillMdImporter;
import org.wyrdsekai.core.skill.SchedulerService;
import org.wyrdsekai.core.mcp.McpServiceRegistry;
import org.wyrdsekai.core.home.ActionGrantCheck;
import org.wyrdsekai.core.home.ActionGrants;
import org.wyrdsekai.core.mcp.McpGrantCheck;
import org.wyrdsekai.core.mcp.McpKeyStore;
import org.wyrdsekai.core.mcp.McpServerManager;
import org.wyrdsekai.core.mcp.McpGrantAdmin;
import org.wyrdsekai.core.mcp.transport.McpTransportFactory;
import org.wyrdsekai.core.mcp.transport.McpTransportHandler;
import org.wyrdsekai.core.mcp.transport.HttpTransportHandler;
import org.wyrdsekai.core.inference.InferenceConfig;
import org.wyrdsekai.core.inference.InferenceRouter;
import org.wyrdsekai.between.layer.NodeCapabilities;
import org.wyrdsekai.server.hermod.HermodService;
import org.wyrdsekai.server.hermod.NatsGossip;
import org.wyrdsekai.server.hermod.NatsDoors;
import org.wyrdsekai.server.hermod.PhoneDoorProxy;
import org.wyrdsekai.server.hermod.HermodPhoneWs;
import org.wyrdsekai.core.inference.HermodInferenceExecutor;
import org.wyrdsekai.core.inference.InferenceClient;
import org.wyrdsekai.core.inference.MeshDispatch;
import org.wyrdsekai.core.inference.CapabilityRegistry;
import org.wyrdsekai.core.inference.InferenceClient;
import org.wyrdsekai.core.inference.MeshDispatch;
import org.wyrdsekai.core.inference.StaticApiKeyProvider;
import org.wyrdsekai.core.agent.LexiconService;
import org.wyrdsekai.core.agent.WorldDnaHarvester;
import org.wyrdsekai.core.naming.FederationSubjects;
import org.wyrdsekai.core.identity.AgentIdentityBootstrap;
import org.wyrdsekai.core.identity.PersonIdentityBootstrap;
import org.wyrdsekai.core.coding.ConsentBroker;
import org.wyrdsekai.core.persistence.AuthService;
import org.wyrdsekai.core.persistence.BridgeDataProviderImpl;
import org.wyrdsekai.core.persistence.InventoryService;
import org.wyrdsekai.core.persistence.PairingService;
import org.wyrdsekai.core.persistence.RoomMetadataService;
import org.wyrdsekai.core.agent.ModelAttribution;
import org.wyrdsekai.core.persistence.DataVersion;
import org.wyrdsekai.core.persistence.SchemaInitializer;
import org.wyrdsekai.core.persistence.SqlDialect;
import org.wyrdsekai.core.persistence.WardService;
import org.wyrdsekai.core.persistence.WorldDnaService;
import org.wyrdsekai.core.governance.ModerationService;
import org.wyrdsekai.core.governance.SanctionEnforcer;
import org.wyrdsekai.core.companion.SafetyAlertRouter;
import org.wyrdsekai.core.companion.SafetyMonitorService;
import org.wyrdsekai.core.companion.SafetyTrigger;
import org.wyrdsekai.core.room.TheSafe;
import org.wyrdsekai.core.room.RoomActor;
import org.wyrdsekai.core.room.RoomMcpBridge;
import org.wyrdsekai.core.study.StudyMountRegistry;
import org.wyrdsekai.core.study.StudySkillService;
import org.wyrdsekai.core.room.RoomCommand;
import org.wyrdsekai.core.room.RoomEventListener;
import org.wyrdsekai.core.room.RoomResponse;
import org.wyrdsekai.core.room.ZoneGuardian;
import org.wyrdsekai.core.room.RoomRegistry;
import org.wyrdsekai.core.room.Rooms;
import org.wyrdsekai.core.room.StudyProvisioner;
import org.wyrdsekai.core.room.ZoneAestheticService;
import org.wyrdsekai.core.room.ZoneTopology;
// ClusterSharding removed — rooms use RoomRegistry + child actors
import org.wyrdsekai.core.agent.AgentProfile;
import org.wyrdsekai.core.agent.Companions;
import org.wyrdsekai.core.agent.CompanionActor;
import org.wyrdsekai.core.agent.CompanionSpawner;
import org.wyrdsekai.core.agent.CompanionCapabilityRegistry;
import org.wyrdsekai.core.agent.CompanionRegistry;
import org.wyrdsekai.core.agent.CompanionTransitState;
import org.wyrdsekai.core.agent.CrossZonePeekService;
import org.wyrdsekai.core.agent.CrossZoneTellService;
import org.wyrdsekai.core.agent.GovernorEventMonitor;
import org.wyrdsekai.core.agent.NotificationService;
import org.wyrdsekai.core.agent.WatcherService;
import org.wyrdsekai.core.agent.channels.ChannelStateStore;
import org.wyrdsekai.core.agent.interiority.ChronicleEntryStore;
import org.wyrdsekai.core.soul.ForgeActor;
import org.wyrdsekai.core.soul.ForgeRoomBridge;
import org.wyrdsekai.core.soul.ForgeCommand;
import org.wyrdsekai.core.soul.SoulManifest;
import org.wyrdsekai.core.observability.ShadowLog;
import org.wyrdsekai.core.resilience.ResilienceConfig;
import org.wyrdsekai.core.soul.SoulStore;
import org.wyrdsekai.core.soul.SoulTransitProtocol;
import org.wyrdsekai.core.soul.SqlSoulStore;
import org.wyrdsekai.core.soul.BondRitual;
import org.wyrdsekai.core.soul.BondStore;
import org.wyrdsekai.core.soul.SoulAutoForge;
import org.wyrdsekai.core.soul.SoulFragmentStore;
import org.wyrdsekai.core.soul.SoulSeedWatcher;
import org.wyrdsekai.core.soul.VoiceProfileService;
import org.wyrdsekai.core.soul.VoiceProfileStore;
import org.wyrdsekai.core.soul.WorldKnowledgeStore;
import org.wyrdsekai.between.BetweenActor;
import org.wyrdsekai.between.layer.LocalRoomView;
import org.wyrdsekai.between.layer.HouseholdObservability;
import org.wyrdsekai.between.layer.IdentityReplicator;
import org.wyrdsekai.between.layer.NodeCapabilities;
import org.wyrdsekai.between.layer.PlacementEngine;
import org.wyrdsekai.between.layer.PresenceLayer;
import org.wyrdsekai.between.layer.ResourceHeartbeat;
import org.wyrdsekai.between.layer.ResourceRegistry;
import org.wyrdsekai.between.layer.RoomCommandBridge;
import org.wyrdsekai.between.layer.RoomEventReplicator;
import org.wyrdsekai.between.layer.RoomPrimaryProtocol;
import org.wyrdsekai.between.layer.UnifiedSessionService;
import org.wyrdsekai.between.federation.FederationActor;
import org.wyrdsekai.between.federation.CrossZonePeekBridge;
import org.wyrdsekai.between.federation.FederationService;
import org.wyrdsekai.between.federation.AgreementGrantSync;
import org.wyrdsekai.between.federation.BilateralAgreement;
import org.wyrdsekai.between.federation.TransitToken;
import org.wyrdsekai.between.NatsBridge;
import org.wyrdsekai.between.NatsServerManager;
import org.wyrdsekai.between.MultiHomedRelayPublisher;
import org.wyrdsekai.between.NatsZoneDirectory;
import org.wyrdsekai.between.NodeIdentity;
import org.wyrdsekai.between.RelaySessionTransport;
import org.wyrdsekai.between.inference.NatsInferenceClient;
import org.wyrdsekai.between.inference.NatsInferenceProtocol;
import org.wyrdsekai.between.recipe.CrossZoneRecipeWiring;
import org.wyrdsekai.between.recipe.NatsRecipeServer;
import org.wyrdsekai.between.training.NatsPeerTrainingTransport;
import org.wyrdsekai.between.zonegrant.NatsZoneGrantClient;
import org.wyrdsekai.between.zonegrant.NatsZoneGrantServer;
import org.wyrdsekai.common.topology.RoomAssignment;
import org.wyrdsekai.common.topology.RoomOwnership;
import org.wyrdsekai.scripting.loader.ScriptLoader;
import org.wyrdsekai.server.auth.WebAuthnService;
import org.wyrdsekai.server.http.AuthRoutes;
import org.wyrdsekai.server.http.HealthRoutes;
import org.wyrdsekai.server.http.ResidencyRoutes;
import org.wyrdsekai.server.study.StudySyncPeer;
import org.wyrdsekai.server.http.InferenceRoutes;
import org.wyrdsekai.server.http.MetricsCollector;
import org.wyrdsekai.server.http.PairingRoutes;
import org.wyrdsekai.server.http.HouseholdJoinRoutes;
import org.wyrdsekai.server.http.RateLimiter;
import org.wyrdsekai.core.household.MaintenanceService;
import org.wyrdsekai.core.household.ParentalControlService;
import org.wyrdsekai.core.household.PermissionChecker;
import org.wyrdsekai.core.household.StewardAuditLog;
import org.wyrdsekai.server.http.HouseholdRoutes;
import org.wyrdsekai.server.http.IssueRoutes;
import org.wyrdsekai.server.http.LibraryKnowledgeRoutes;
import org.wyrdsekai.server.http.StudyRoutes;
import org.wyrdsekai.server.http.SearchRoutes;
import org.wyrdsekai.server.http.SoulRoutes;
import org.wyrdsekai.server.http.TlsConfig;
import org.wyrdsekai.server.http.WardRoutes;
import org.wyrdsekai.server.http.CompanionAskRoutes;
import org.wyrdsekai.server.http.DirectoryRoutes;
import org.wyrdsekai.server.http.FamiliarJournalRoutes;
import org.wyrdsekai.server.http.HomeRoutes;
import org.wyrdsekai.server.http.IdentityOutboxRoutes;
import org.wyrdsekai.server.http.LibraryCompactRoutes;
import org.wyrdsekai.server.http.McpRoutes;
import org.wyrdsekai.server.http.OpenRouterOAuthRoutes;
import org.wyrdsekai.server.http.RecipeAuthorRoutes;
import org.wyrdsekai.server.http.RecipeBondholderRoutes;
import org.wyrdsekai.server.http.RecipeTuneRoutes;
import org.wyrdsekai.server.http.ConsentRoutes;
import org.wyrdsekai.server.http.ForgeRoutes;
import org.wyrdsekai.server.http.RepairRoutes;
import org.wyrdsekai.server.http.OperatorToken;
import org.wyrdsekai.server.http.RecipesRoutes;
import org.wyrdsekai.server.http.ResidentRoutes;
import org.wyrdsekai.server.http.SkillAuthorRoutes;
import org.wyrdsekai.server.http.SkillRoutes;
import org.wyrdsekai.server.http.UpdateRoutes;
import org.wyrdsekai.server.http.VoiceRoutes;
import org.wyrdsekai.server.http.WebhookRoutes;
import org.wyrdsekai.server.voice.SttConfig;
import org.wyrdsekai.server.voice.VoiceAdapter;
import org.wyrdsekai.server.voice.VoiceWebSocket;
import org.wyrdsekai.core.observability.EngineRoomService;
import org.wyrdsekai.server.ssh.SshAdapter;
import org.wyrdsekai.server.telnet.TelnetAdapter;
import org.wyrdsekai.server.ws.WyrdWebSocket;
import org.wyrdsekai.server.ws.ZoneBridgeEndpoint;
import org.wyrdsekai.server.inference.NatsInferenceServer;
import org.wyrdsekai.server.mcp.McpNatsHandler;
import org.wyrdsekai.server.mcp.TunnelSessionHandler;
import org.wyrdsekai.server.session.ClientConnectionRegistry;
import org.wyrdsekai.server.session.VirtualSessionHandler;

import org.wyrdsekai.common.system.SystemPaths;
import org.wyrdsekai.common.model.AppVersion;
import org.wyrdsekai.common.protocol.S2CMessage;
import org.wyrdsekai.common.util.Json;

import org.wyrdsekai.core.search.EmbeddingService;
import org.wyrdsekai.core.search.WyrdLuceneStore;
import org.wyrdsekai.core.search.LuceneLibraryAdapter;
import org.wyrdsekai.core.search.SearchCollections;

import org.wyrdsekai.core.persistence.BackupOrchestrator;
import org.wyrdsekai.core.persistence.InviteService;
import org.wyrdsekai.core.bootstrap.CoreServices;
import org.wyrdsekai.core.config.ConfigValidator;
import org.wyrdsekai.core.config.RelayLegConfig;
import org.wyrdsekai.core.config.WyrdConfig;
import org.wyrdsekai.core.crypto.ZoneSecrets;
import org.wyrdsekai.core.event.EventBusPluginLoader;
import org.wyrdsekai.core.event.InProcessEventBus;
import org.wyrdsekai.core.external.CredentialResolver;
import org.wyrdsekai.core.oracle.OracleBetweenSync;
import org.wyrdsekai.core.external.t.PhaseTAdaptersBootstrap;
import org.wyrdsekai.core.home.BondGrantSync;
import org.wyrdsekai.core.home.FederatedHomeProxy;
import org.wyrdsekai.core.home.ForeignIdentityStore;
import org.wyrdsekai.core.home.HomeClient;
import org.wyrdsekai.core.home.RelayGovernance;
import org.wyrdsekai.core.home.RelayGovernor;
import org.wyrdsekai.core.home.RelayGovernors;
import org.wyrdsekai.core.home.HomeClients;
import org.wyrdsekai.core.home.HomeProxy;
import org.wyrdsekai.core.home.HomeRegistryActor;
import org.wyrdsekai.core.home.HomeStore;
import org.wyrdsekai.core.home.NotificationHomeEventListener;
import org.wyrdsekai.core.home.Residency;
import org.wyrdsekai.core.home.ResidencyStore;
import org.wyrdsekai.core.home.WardGrantSync;
import org.wyrdsekai.core.home.ZoneDirectory;
import org.wyrdsekai.core.identity.AccountStore;
import org.wyrdsekai.core.identity.HouseholdStore;
import org.wyrdsekai.core.identity.IdentityOutboxStore;
import org.wyrdsekai.core.ingest.ImageExtractor;
import org.wyrdsekai.core.ingest.IngestPipeline;
import org.wyrdsekai.core.ingest.IngestTarget;
import org.wyrdsekai.core.ingest.TextExtractor;
import org.wyrdsekai.core.ingest.VoiceExtractor;
import org.wyrdsekai.core.interop.A2AGateway;
import org.wyrdsekai.core.interop.DockQuarantine;
import org.wyrdsekai.core.interop.TrustTierResolver;
import org.wyrdsekai.core.interop.VitalityRedactor;
import org.wyrdsekai.core.issue.IssueService;
import org.wyrdsekai.core.item.CompanionCodexView;
import org.wyrdsekai.core.item.EquipmentService;
import org.wyrdsekai.core.item.HouseholdItemContent;
import org.wyrdsekai.core.item.HouseholdResources;
import org.wyrdsekai.core.item.ItemWorldApiProviderImpl;
import org.wyrdsekai.core.item.ItemScheduleService;
import org.wyrdsekai.core.item.StudyFurnishingKit;
import org.wyrdsekai.core.naming.CompositeZoneDirectory;
import org.wyrdsekai.core.naming.FederatedZoneDirectory;
import org.wyrdsekai.core.naming.HouseholdIdentity;
import org.wyrdsekai.core.naming.RendezvousZoneDirectory;
import org.wyrdsekai.core.naming.WellKnownZoneDirectory;
import org.wyrdsekai.core.naming.ZoneAddressResolverService;
import org.wyrdsekai.core.naming.ZoneDirectoryService;
import org.wyrdsekai.core.naming.ZoneManifestV1;
import org.wyrdsekai.core.nostr.NostrAdapterBootstrap;
import org.wyrdsekai.core.oracle.OracleBridge;
import org.wyrdsekai.core.oracle.OracleEvent;
import org.wyrdsekai.core.oracle.feeds.FeedPoller;
import org.wyrdsekai.core.recipe.CloudRecipeDispatcher;
import org.wyrdsekai.core.recipe.CodingBackendDispatcher;
import org.wyrdsekai.core.recipe.ProcessCommandRunner;
import org.wyrdsekai.core.recipe.QueuedRecipe;
import org.wyrdsekai.core.recipe.RecipeRunner;
import org.wyrdsekai.core.recipe.RecipeScheduler;
import org.wyrdsekai.core.recipe.RecipeSchedulerBoot;
import org.wyrdsekai.core.recipe.RecipeService;
import org.wyrdsekai.core.skill.SkillDraftStore;
import org.wyrdsekai.core.skill.WorkshopPinboard;
import org.wyrdsekai.core.substrate.DeepSleepTrainer;
import org.wyrdsekai.core.substrate.training.PeerTrainingTransport;
import org.wyrdsekai.core.substrate.training.TrainingPeerService;
import org.wyrdsekai.core.update.UpdateChannelPoller;
import org.wyrdsekai.core.update.UpdateConfig;
import org.wyrdsekai.core.voice.SpeechToTextService;
import org.wyrdsekai.core.voice.TextToSpeechService;

import java.io.IOException;
import java.io.File;
import java.lang.management.ManagementFactory;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.security.MessageDigest;
import java.time.Clock;
import org.wyrdsekai.hermod.TaskEnvelope;

/**
 * Wyrdsekai server entry point.
 * Boots Pekko actor system with ZoneGuardian, seeds Foundation rooms,
 * starts inference router, starts Javalin HTTP/WS server.
 */
public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);
    private static final int DEFAULT_PORT = 7070;

    /**
     * This node's identity for replicated persistence and resource heartbeats.
     *
     * <p>Two failures shaped this, both found 2026-07-29 on real installs:</p>
     *
     * <ul>
     *   <li>The old default was the literal {@code "local"}. Only the .deb sets
     *       {@code WYRDSEKAI_NODE_ID}; the macOS .pkg seeds
     *       {@code WYRDSEKAI_NODE_NAME} instead, so every Mac fell back to
     *       {@code "local"} — fine alone, but two Macs in one household share a
     *       CountingHouse replica id, which is a silent data-correctness bug
     *       rather than a crash.</li>
     *   <li>A malformed value is worse than a missing one. When {@code wyrd
     *       setup} copied an unexpanded {@code $(hostname -s ...)} into the
     *       systemd conf, {@link ReplicationId} rejected it for containing
     *       {@code |} and the exception killed {@code main} <i>after</i> systemd
     *       had reported the unit active — green units, dead :7070.</li>
     * </ul>
     *
     * <p>So: fall back to the configured node name (which resolves
     * NODE_NAME → node.name → detected hostname) rather than a shared constant,
     * and strip the separator {@code ReplicationId} reserves so a surprising
     * hostname can never take the process down again.</p>
     */
    static String resolveNodeId() {
        var raw = System.getenv("WYRDSEKAI_NODE_ID");
        if (raw == null || raw.isBlank()) {
            raw = WyrdConfig.get().nodeName();
        }
        if (raw == null || raw.isBlank()) {
            raw = "node";
        }
        // ReplicationId uses '|' as its separator and rejects any id containing
        // one. Everything else it accepts, so this is a targeted strip, not a
        // general sanitiser that would quietly rewrite legitimate names.
        var cleaned = raw.replace('|', '-').trim();
        return cleaned.isBlank() ? "node" : cleaned;
    }

    /** Pre-connected NATS bridge (created before anything else in main). */
    private static volatile NatsBridge earlyNatsBridge;

    /** Zone directory handle — exposed to REST endpoints for {@code wyrd discover}.
     *  Fully qualified deliberately: core.home.ZoneDirectory is already imported here,
     *  so the simple name is taken and importing this one instead is ambiguous. */
    private static volatile org.wyrdsekai.core.naming.ZoneDirectory zoneDirectory;

    /** Latest locally-built manifest, refreshed by {@link #startZoneDirectoryPublish}.
     *  Served authoritatively at {@code GET /.well-known/wyrd-zone}. May be null
     *  if the deployment has no resolvable zone label. */
    private static volatile ZoneManifestV1 localManifest;

    /**
     * Tell-back delivery covering EVERY surface (second-node re-verify 2026-07-11
     * #29). Routes through the session registry first — one line per live
     * session the player holds (WS, SSH, Telnet alike) — and only falls back
     * to the WS handler's per-player fan-out when the registry produced no
     * delivery (e.g. a login window before registration). Returns true ONLY
     * when a live session actually took the line, so
     * {@link CrossZoneTellService}'s callers (CompanionActor's tell-back
     * path) can honestly fall back to teleport-and-speak instead of
     * swallowing the reply.
     */
    private static boolean deliverTellLineToPlayer(ClientConnectionRegistry registry,
                                                   WyrdWebSocket ws,
                                                   String playerId, String formatted) {
        boolean delivered = false;
        if (registry != null) {
            for (var conn : registry.sessionsFor(playerId)) {
                try {
                    delivered |= conn.deliverLine(formatted);
                } catch (RuntimeException e) {
                    log.debug("Tell-back deliverLine failed for session {}: {}",
                        conn.sessionId(), e.getMessage());
                }
            }
        }
        if (!delivered && ws != null) {
            delivered = ws.deliverToPlayer(playerId, new S2CMessage.Prose(
                0L, "tell", formatted, List.of(), null, "normal"));
        }
        return delivered;
    }

    /** True if the NATS URL resolves to a loopback host — used to decide
     *  whether to auto-spawn embedded nats-server before pre-connecting. */
    private static boolean isLocalhostNatsUrl(String url) {
        if (url == null) return false;
        var s = url.toLowerCase().trim();
        int scheme = s.indexOf("://");
        if (scheme >= 0) s = s.substring(scheme + 3);
        int at = s.indexOf('@');
        if (at >= 0) s = s.substring(at + 1);
        int colon = s.indexOf(':');
        var host = colon >= 0 ? s.substring(0, colon) : s;
        return host.equals("127.0.0.1") || host.equals("localhost") || host.equals("::1");
    }

    /**
     * Resolve this node's LAN IP for advertising to household peers.
     *
     * <p>Order: (1) explicit {@code WYRDSEKAI_LAN_IP} override; (2) the first
     * site-local IPv4 ({@code 10/8}, {@code 172.16/12}, {@code 192.168/16}) on
     * a real, up, non-loopback interface — explicitly SKIPPING docker/bridge/
     * VM/tunnel interfaces by name (their {@code 172.x} addresses are up,
     * site-local, and NOT {@code isVirtual()}, so a naive scan advertises an
     * unreachable {@code 172.18.0.1}); (3) fall back to
     * {@code InetAddress.getLocalHost()}. The fallback is the LAST resort
     * because on many Linux distros {@code getLocalHost()} returns the
     * {@code 127.0.1.1} loopback alias from {@code /etc/hosts}, which a peer
     * on the LAN can never reach.</p>
     */
    private static String resolveLanIp() {
        var override = System.getenv("WYRDSEKAI_LAN_IP");
        if (override != null && !override.isBlank()) return override.trim();
        try {
            var candidates = new ArrayList<Map.Entry<String, InetAddress>>();
            var ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                var iface = ifaces.nextElement();
                try {
                    if (!iface.isUp() || iface.isLoopback()) continue;
                } catch (Exception ignored) {
                    continue;
                }
                var name = iface.getName();
                var addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    candidates.add(Map.entry(name == null ? "" : name, addrs.nextElement()));
                }
            }
            var picked = selectLanIp(candidates);
            if (picked != null) return picked;
        } catch (Exception ignored) {}
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Pure selection over {@code (interfaceName, address)} pairs — factored out
     * of {@link #resolveLanIp()} so it's unit-testable without real interfaces.
     * Skips virtual/container interfaces by name, then prefers a site-local
     * IPv4, falling back to the first non-loopback IPv4 on a real interface.
     */
    static String selectLanIp(List<Map.Entry<String, InetAddress>> candidates) {
        String fallback = null;
        for (var e : candidates) {
            if (isVirtualIfaceName(e.getKey())) continue;
            var addr = e.getValue();
            if (!(addr instanceof Inet4Address) || addr.isLoopbackAddress()) continue;
            if (addr.isSiteLocalAddress()) return addr.getHostAddress();
            if (fallback == null) fallback = addr.getHostAddress();
        }
        return fallback;
    }

    /**
     * True for interface names that belong to docker/bridge/VM/tunnel devices
     * whose addresses must never be advertised as this node's LAN IP. Matched
     * case-insensitively by {@code startsWith} — deliberately NOT {@code contains}
     * so a real NIC like {@code wlo1} (which contains "lo") is not skipped.
     */
    static boolean isVirtualIfaceName(String name) {
        if (name == null || name.isBlank()) return false;
        var n = name.toLowerCase(Locale.ROOT);
        for (var p : new String[]{
                "docker", "br-", "veth", "virbr", "tun", "tap", "lo", "vmnet", "utun"}) {
            if (n.startsWith(p)) return true;
        }
        return false;
    }

    /**
     * diagnose the
     * {@code WYRDSEKAI_RELAY_{ENABLED,URL,USER,TOKEN}} quartet at startup.
     *
     * <p>Federation transport (RelayBridge) only initialises if all four
     * env vars are set. Operators routinely set just {@code _URL} (it's the
     * one mentioned in stale docs and copy-paste guides) and then wonder
     * why their zone won't federate. Silent failure mode: RelayBridge
     * never instantiates, no log line explains why, the operator burns
     * ~10min reading source before discovering the requirement.</p>
     *
     * <p>This audit emits exactly one of:
     * <ul>
     *   <li><b>nothing</b> — none of the four set (zone runs local-only,
     *       which is a valid configuration);</li>
     *   <li><b>info</b> — all four set (federation will start; URL logged
     *       as the operator-visible breadcrumb);</li>
     *   <li><b>warn</b> — partial set (lists which envs are missing by
     *       name and tells the operator federation will NOT start).</li>
     * </ul>
     */
    private static void auditFederationEnvQuartet() {
        var env = System.getenv();
        var enabled = env.get("WYRDSEKAI_RELAY_ENABLED");
        var url     = env.get("WYRDSEKAI_RELAY_URL");
        var user    = env.get("WYRDSEKAI_RELAY_USER");
        var token   = env.get("WYRDSEKAI_RELAY_TOKEN");
        boolean hasEnabled = enabled != null && !enabled.isBlank()
                && !"false".equalsIgnoreCase(enabled.trim());
        boolean hasUrl     = url != null && !url.isBlank();
        boolean hasUser    = user != null && !user.isBlank();
        boolean hasToken   = token != null && !token.isBlank();

        // Local-only configuration: nothing to audit.
        if (!hasEnabled && !hasUrl && !hasUser && !hasToken) return;

        if (hasEnabled && hasUrl && hasUser && hasToken) {
            System.out.println("[wyrdsekai] Federation relay configured: " + url);
            return;
        }

        // Partial — surface exactly what's missing.
        var missing = new ArrayList<String>();
        if (!hasEnabled) missing.add("WYRDSEKAI_RELAY_ENABLED=true");
        if (!hasUrl)     missing.add("WYRDSEKAI_RELAY_URL");
        if (!hasUser)    missing.add("WYRDSEKAI_RELAY_USER");
        if (!hasToken)   missing.add("WYRDSEKAI_RELAY_TOKEN");
        System.err.println("[wyrdsekai] WARN: federation relay is partially configured "
            + "— RelayBridge will NOT start. Missing: " + String.join(", ", missing) + ". "
            + "All four of WYRDSEKAI_RELAY_{ENABLED,URL,USER,TOKEN} must be set together. "
            + "See docs/RELAY.md.");
    }

    /**
     * (P4) — install the relay-governance factory when
     * this zone administers a relay. Resolution (simplest source per the spec):
     * the zone's configured relay (the one it joined / owns), via
     * {@code WYRDSEKAI_RELAY_REGISTRATION_URL} + {@code _FINGERPRINT} (process
     * env, falling back to {@code $WYRDSEKAI_CONF}). The relay's stable DID and
     * its owner DID come from {@code WYRDSEKAI_RELAY_DID} /
     * {@code WYRDSEKAI_RELAY_OWNER_DID}; for the home/co-located case the owner
     * defaults to this node's own DID (the zone auto-owns the relay it runs).
     *
     * <p>If no relay registration is configured, or the relay DID can't be
     * resolved, the factory is left uninstalled and the Warden furnishing
     * degrades to "no relay configured" — no error.</p>
     */
    private static void wireRelayGovernor(HomeClient homeClient) throws Exception {
        var registrationUrl = resolveRelayConf("WYRDSEKAI_RELAY_REGISTRATION_URL");
        if (registrationUrl == null || registrationUrl.isBlank()) {
            return; // zone hasn't joined / doesn't administer a relay
        }
        var fingerprint = resolveRelayConf("WYRDSEKAI_RELAY_FINGERPRINT");

        var dataDir = Path.of(System.getenv().getOrDefault("WYRDSEKAI_DATA_DIR",
            System.getProperty("user.home") + "/.wyrdsekai"));
        var nodeIdentity = NodeIdentity.loadOrGenerate(dataDir.resolve("node-identity.json"));
        // The NKey-derived DID is what the relay knows this node as (it stamps
        // nkey_to_did on the registration and verifies admin/claim signatures
        // against the NKey). Use it for both the relay-DID and owner defaults.
        var nodeDid = nodeIdentity.nkeyDid();

        // The relay's stable DID. Explicit env wins; else (home/co-located) the
        // relay is keyed off this node's own identity, so default to nodeDid.
        var relayDid = resolveRelayConf("WYRDSEKAI_RELAY_DID");
        if (relayDid == null || relayDid.isBlank()) relayDid = nodeDid;
        // The owner DID roots the relay's grant chain. Home case: this zone owns
        // the relay it deployed, so default to nodeDid.
        var ownerDid = resolveRelayConf("WYRDSEKAI_RELAY_OWNER_DID");
        if (ownerDid == null || ownerDid.isBlank()) ownerDid = nodeDid;

        var adminUrl = registrationUrl.replaceAll("/+$", "") + "/admin";
        var relayLabel = registrationUrl.replaceFirst("^https?://", "");

        final var fRelayDid = relayDid;
        final var fOwnerDid = ownerDid;
        var client = new RelayAdminClient(nodeIdentity, adminUrl, fRelayDid, fingerprint);
        var gateway = new RelayAdminGatewayImpl(client, fRelayDid, relayLabel);
        var governance = new RelayGovernance(homeClient);

        // A single governor (P4 = one administered relay). The callerDid is
        // supplied per-action, so the same instance is safe for any caller.
        // #13 (2026-07-19 OSS hardening) — the steward-check makes the zone
        // steward owner-equivalent for zone-side authz: on a home relay the
        // ownerDid is the node's NKey identity, but the steward administers via
        // their account DID, so without this they were locked out of the relay
        // they own.
        var governor = new RelayGovernor(governance, gateway, fOwnerDid, fRelayDid,
            relayLabel, Main::isZoneSteward);
        RelayGovernors.setFactory(callerDid -> governor);
        log.info("in-world Warden wired for relay {} (owner {})",
            relayLabel, fOwnerDid.length() > 24 ? fOwnerDid.substring(0, 24) + "…" : fOwnerDid);
    }

    /**
     * #13 (2026-07-19 OSS hardening) — is {@code did} a steward of the local
     * zone? ResidencyStore role check (same predicate the admin-delegation gate
     * uses). Used to make the steward owner-equivalent for relay governance.
     */
    private static boolean isZoneSteward(String did) {
        if (did == null || did.isBlank()) return false;
        try {
            var store = ResidencyStore.get();
            if (store == null) return false;
            var zone = store.localZoneId();
            if (zone == null || zone.isBlank()) return false;
            var res = store.get(did, zone);
            return res.isPresent() && Residency.ROLE_STEWARD.equals(res.get().role());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Read a relay config value: process env first, then the persisted
     * {@code $WYRDSEKAI_CONF} env file (where {@code wyrd relay register-nkey}
     * writes the relay URL/fingerprint). Returns null if unset.
     */
    private static String resolveRelayConf(String key) {
        var v = System.getenv(key);
        if (v != null && !v.isBlank()) return v;
        var conf = System.getenv("WYRDSEKAI_CONF");
        Path confPath = null;
        if (conf != null && !conf.isBlank()) {
            confPath = Path.of(conf);
        } else {
            var home = Path.of(System.getProperty("user.home"), ".wyrdsekai", "env");
            if (Files.isRegularFile(home)) confPath = home;
        }
        if (confPath == null || !Files.isRegularFile(confPath)) return null;
        try {
            for (var line : Files.readAllLines(confPath)) {
                var t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) continue;
                var eq = t.indexOf('=');
                if (eq <= 0) continue;
                if (!t.substring(0, eq).trim().equals(key)) continue;
                var val = t.substring(eq + 1).trim();
                if (val.length() >= 2
                    && ((val.startsWith("\"") && val.endsWith("\""))
                        || (val.startsWith("'") && val.endsWith("'")))) {
                    val = val.substring(1, val.length() - 1);
                }
                return val;
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    public static void main(String[] args) {
        // Force IPv4 for NATS on dual-homed networks (macOS dual-stack causes failures)
        System.setProperty("java.net.preferIPv4Stack", "true");

        // audit federation env-var quartet at
        // startup. RelayBridge silently fails to start unless ALL FOUR of
        // WYRDSEKAI_RELAY_{ENABLED,URL,USER,TOKEN} are set. Operators
        // historically set one or two, hit "federation isn't working",
        // burn ~10min before reading the source. Loud WARN here points
        // at the missing pieces by name.
        auditFederationEnvQuartet();

        // Pre-connect NATS as the VERY FIRST network operation.
        // Must happen before ConfigFactory.load(), initializeDatabase(), ActorSystem.create(),
        // or anything else that touches sockets. On macOS dual-homed machines, later socket
        // operations corrupt routing and prevent NATS connection permanently.
        var natsUrlEnv = WyrdConfig.get().resolve("WYRDSEKAI_NATS_URL", "nats.url", () -> null);
        var betweenEnv = WyrdConfig.get().resolve("WYRDSEKAI_BETWEEN_ENABLED", "between.enabled", () -> null);

        // Default to embedded-NATS topology when Between is on but no URL is
        // specified. Everything downstream (session-transport wiring, inference
        // server, virtual-session handler) keys off the pre-connected bridge,
        // so we must have one. The OSS-default path is local embedded NATS.
        if ("true".equalsIgnoreCase(betweenEnv) && (natsUrlEnv == null || natsUrlEnv.isEmpty())) {
            natsUrlEnv = "nats://127.0.0.1:4222";
            System.out.println("[wyrdsekai] NATS URL defaulted to " + natsUrlEnv
                + " (embedded-NATS topology)");
        }

        // If the URL targets localhost, start the embedded nats-server before
        // attempting to connect — otherwise the pre-connect retries (5 × 5s)
        // will all fail because nobody is listening yet. BetweenActor would
        // later spawn the same manager, which is idempotent (isHealthy → reuse).
        if ("true".equalsIgnoreCase(betweenEnv) && natsUrlEnv != null
                && isLocalhostNatsUrl(natsUrlEnv)) {
            try {
                var dataDir = Path.of(
                    System.getenv().getOrDefault("WYRDSEKAI_DATA_DIR",
                        System.getProperty("user.home") + "/.wyrdsekai"));
                Files.createDirectories(dataDir);
                // LAN-reachability fix: when this node offers household resources
                // (GPU share) — or the operator forces it — bind NATS on all
                // interfaces (0.0.0.0) so a household peer can actually borrow it.
                // The 5th NatsServerManager arg drives the generated nats.conf
                // `listen` address (0.0.0.0 vs 127.0.0.1) + client_advertise.
                boolean bindAll = WyrdConfig.get().inferenceHouseholdShare()
                    || "true".equalsIgnoreCase(
                        System.getenv().getOrDefault("WYRDSEKAI_NATS_BIND_ALL", ""));
                var mgr = new NatsServerManager(
                    System.getenv().getOrDefault("WYRDSEKAI_NATS_EXECUTABLE", "nats-server"),
                    4222, 8222, dataDir, bindAll);
                mgr.start();
                System.out.println("[wyrdsekai] embedded nats-server ready on " + natsUrlEnv
                    + (bindAll ? " (bound all interfaces — household share on)" : ""));
            } catch (Exception e) {
                System.out.println("[wyrdsekai] embedded nats-server bootstrap failed: "
                    + e.getMessage() + " — pre-connect will retry");
            }
        }

        if ("true".equalsIgnoreCase(betweenEnv) && natsUrlEnv != null && !natsUrlEnv.isEmpty()) {
            // Retry loop — WiFi networks can have brief dropouts at process start
            for (int natsAttempt = 1; natsAttempt <= 5; natsAttempt++) {
            try {
                var dataDir = Path.of(
                    System.getenv().getOrDefault("WYRDSEKAI_DATA_DIR",
                        System.getProperty("user.home") + "/.wyrdsekai"));
                Files.createDirectories(dataDir);
                var nodeId = NodeIdentity.loadOrGenerate(
                    dataDir.resolve("node-identity.json"));
                var zoneId = WyrdConfig.get().zoneId();
                earlyNatsBridge = new NatsBridge(
                    natsUrlEnv, nodeId.nodeId(), zoneId, nodeId);
                earlyNatsBridge.connect();
                System.out.println("[wyrdsekai] NATS pre-connected: " + natsUrlEnv);
                break; // connected
            } catch (Exception e) {
                if (natsAttempt == 5) {
                    System.out.println("[wyrdsekai] NATS pre-connect failed after 5 attempts: " + e.getMessage());
                    earlyNatsBridge = null;
                } else {
                    System.out.println("[wyrdsekai] NATS attempt " + natsAttempt + "/5 failed (" + e.getMessage() + "), retrying in 5s...");
                    try { Thread.sleep(5000); } catch (InterruptedException ie) { break; }
                }
            }
            } // end retry loop
        }

        int port = DEFAULT_PORT;
        boolean clusterFlag = false;
        for (int i = 0; i < args.length; i++) {
            if ("--port".equals(args[i]) && i + 1 < args.length) {
                port = Integer.parseInt(args[i + 1]);
            } else if ("--cluster".equals(args[i])) {
                clusterFlag = true;
            }
        }

        // WYRDSEKAI_CLUSTER env var also enables cluster mode
        if (!clusterFlag && WyrdConfig.get().resolveBool("WYRDSEKAI_CLUSTER", "cluster.enabled", false)) {
            clusterFlag = true;
        }

        // --cluster flag enables Between via system property (picked up by HOCON)
        if (clusterFlag) {
            System.setProperty("WYRDSEKAI_BETWEEN_ENABLED", "true");
            // Set artery port for cluster mode (env var overrides default 25520)
            var arteryPort = WyrdConfig.get().arteryPort();
            if (arteryPort == null) {
                arteryPort = "25520";
            }
            System.setProperty("WYRDSEKAI_ARTERY_PORT", arteryPort);
            log.info("Cluster mode enabled — artery port {}", arteryPort);
        } else {
            log.info("Single-node mode (use --cluster or WYRDSEKAI_CLUSTER=true for multi-node)");
        }

        // Set cross-platform DB path before HOCON resolves ${wyrdsekai.db.path}
        if (System.getProperty("wyrdsekai.db.path") == null) {
            System.setProperty("wyrdsekai.db.path", SystemPaths.dbPath().toString());
        }

        // Test mode check — logs warning, provisions test user later
        TestModeConfig.logWarningIfEnabled();

        // Initialize database before actor system starts
        var config = initializeConfig();

        // Validate configuration — errors block startup, warnings are logged
        if (!ConfigValidator.validateAndLog(config)) {
            System.exit(1);
        }

        // Config-based port (env var or config file) — only if CLI --port was not given
        if (port == DEFAULT_PORT) {
            try {
                port = config.getInt("wyrdsekai.port");
            } catch (Exception ignored) { /* use default */ }
        }

        // Load resilience configuration from HOCON — must happen before any component init
        try {
            var resilienceConfig = ResilienceConfig.fromConfig(config);
            ResilienceConfig.set(resilienceConfig);
            log.info("Resilience config loaded: {}", resilienceConfig);
        } catch (Exception e) {
            log.warn("Resilience config not found in HOCON, using defaults: {}", e.getMessage());
            ResilienceConfig.set(ResilienceConfig.defaults());
        }

        log.info("Starting Wyrdsekai server on port {}", port);
        var jdbcUrl = initializeDatabase(config);
        // Publish the resolved JDBC URL as a system property so subsystems that
        // need ad-hoc InventoryService/etc. access (e.g. CompanionActor.handleCraft*
        // paths) don't have to re-derive it from Pekko config. Env fallback
        // already exists in those paths — this just makes the happy path work
        // in the default server config where WYRDSEKAI_JDBC_URL isn't set.
        // Live-test finding 2026-04-22: without this, craft persistence silently
        // no-ops because neither env nor sysprop is set.
        System.setProperty("wyrdsekai.jdbc.url", jdbcUrl);
        // The per-agent fs sandbox (world.fs.*) resolves its root from the
        // WYRDSEKAI_DATA_DIR env or this sysprop; the env is set by systemd but
        // NOT in every launch context (macOS/docker/dev), and without either the
        // sandbox silently fell back to java.io.tmpdir — writes vanished on
        // restart (2026-07-18). Publish the resolved data dir so the fallback is
        // always the real one.
        try {
            System.setProperty("wyrdsekai.data.dir", SystemPaths.dataDir().toString());
        } catch (Exception e) {
            log.warn("Could not publish wyrdsekai.data.dir sysprop: {}", e.getMessage());
        }
        // Companion roster view (bond crystal `companions`, Codex furnishing)
        // reads the DB directly — hand it the resolved DSN, since WyrdConfig's
        // WYRDSEKAI_JDBC_URL is unset in the default install (home-server 2026-07-18:
        // the crystal showed "no companions" on a node with a living companion).
        CompanionCodexView.setJdbcUrl(jdbcUrl);

        // Wire BackupOrchestrator for periodic DB snapshots
        BackupOrchestrator backupOrchestrator = null;
        ScheduledExecutorService backupScheduler = null;
        try {
            var backupEnabled = config.getBoolean("wyrdsekai.backup.enabled");
            if (backupEnabled) {
                var backupDir = SystemPaths.dataDir().resolve("backups");
                backupOrchestrator = new BackupOrchestrator(backupDir);
                var maxSnapshots = config.getInt("wyrdsekai.backup.max-snapshots");
                backupOrchestrator.setMaxSnapshots(maxSnapshots);

                var intervalHours = config.getInt("wyrdsekai.backup.interval-hours");
                var dbPath = Path.of(config.getString("wyrdsekai.db.path"));
                var searchDir = SystemPaths.dataDir().resolve("search");
                // Household private key — irreplaceable. If lost, the zone's
                // cryptographic identity is gone and all signed soul
                // manifests fail verification.
                var nodeIdentityPath = SystemPaths.dataDir().resolve("node-identity.json");
                // Filesystem state outside world.db that is irreplaceable
                // or expensive to recover. agents/ holds FamilyLocker
                // (familiars, imprints, summon keys, forge cursor); classifiers/
                // holds per-agent learned event log; souls/ holds the legacy
                // manifest dir + incoming/ seed drops + .did files. substrate/
                // holds the Wave 9a-Persist sweep: RepairLedger, AttendantSession,
                // RepairMode, and per-companion ProtectionFlag JSON files —
                // load-bearing substrate-truth state
                // + that must survive zone restore.
                // adapters/ is intentionally NOT here — large + retrainable,
                // separate retention follow-up.
                final var extraBackupDirs = List.of(
                    SystemPaths.dataDir().resolve("agents"),
                    SystemPaths.dataDir().resolve("classifiers"),
                    SystemPaths.dataDir().resolve("souls"),
                    SystemPaths.dataDir().resolve("substrate"),
                    // §F.3 — story (scene/beat/arc JSON) and
                    // biography (per-day per-focal markdown journals) are
                    // load-bearing personhood data: lose these and the agent
                    // loses scene-organized lived experience.
                    SystemPaths.storyDir(),
                    SystemPaths.biographyDir());
                final var orchestrator = backupOrchestrator;
                backupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                    var t = new Thread(r, "backup-scheduler");
                    t.setDaemon(true);
                    return t;
                });
                backupScheduler.scheduleAtFixedRate(
                    () -> orchestrator.snapshotAll(
                        dbPath, searchDir, nodeIdentityPath, extraBackupDirs),
                    intervalHours, intervalHours, TimeUnit.HOURS);
                log.info("BackupOrchestrator enabled — interval={}h, maxSnapshots={}, dir={} "
                    + "(DB via VACUUM INTO + search/Study + node-identity + "
                    + "agents/classifiers/souls/substrate)",
                    intervalHours, maxSnapshots, backupDir);
            }
        } catch (Exception e) {
            log.info("Backup not configured (disabled): {}", e.getMessage());
        }

        var dialect = SqlDialect.fromJdbcUrl(jdbcUrl);
        var authService = new AuthService(jdbcUrl, dialect);

        // Test mode: auto-provision e2e_test/testpass123 so Maestro flows can log in
        // without an explicit register step. Idempotent — only creates if absent.
        if (TestModeConfig.isTestMode()
                && authService.findUserByUsername(TestModeConfig.testUsername()).isEmpty()) {
            var session = authService.register(
                TestModeConfig.testUsername(),
                TestModeConfig.testPassword(),
                TestModeConfig.testDisplayName());
            if (session.isPresent()) {
                log.warn("TEST MODE: provisioned user '{}'", TestModeConfig.testUsername());
            } else {
                log.error("TEST MODE: failed to provision user '{}'", TestModeConfig.testUsername());
            }
        }

        var wardService = new WardService(jdbcUrl, dialect);
        var inventoryService = new InventoryService(jdbcUrl, dialect);
        var metadataService = new RoomMetadataService(jdbcUrl, dialect);
        var bridgeDataProvider = new BridgeDataProviderImpl(wardService, metadataService, authService);

        // Pairing service for phone node onboarding
        var pairingHouseholdId = config.hasPath("wyrdsekai.between.zone-id")
            ? config.getString("wyrdsekai.between.zone-id") : "home";
        var pairingHouseholdName = config.hasPath("wyrdsekai.between.zone-name")
            ? config.getString("wyrdsekai.between.zone-name") : "Home Zone";
        var pairingNatsUrl = config.hasPath("wyrdsekai.between.nats.url")
            ? config.getString("wyrdsekai.between.nats.url") : "";
        // Use LAN IP for pairing URL so phones can reach us (localhost is useless for them).
        // Falls back to config hostname if LAN detection fails.
        var configHostname = config.getString("wyrdsekai.hostname");
        var lanHostname = configHostname;
        if ("localhost".equals(configHostname) || "127.0.0.1".equals(configHostname)) {
            // Reuse the docker/bridge-aware resolver so the pairing/QR URL never
            // advertises a 172.x docker bridge (same fix as the household-join natsUrl).
            lanHostname = resolveLanIp();
            if (!lanHostname.equals(configHostname)) {
                log.info("Pairing URL uses LAN IP: {} (config hostname was {})", lanHostname, configHostname);
            }
        }
        var pairingServerUrl = "http://" + lanHostname + ":" + port;
        // Read relay config for pairing responses and mDNS
        var relayUrl = "";
        var relayToken = "";
        try {
            if (config.hasPath("wyrdsekai.between.relay.enabled")
                    && config.getBoolean("wyrdsekai.between.relay.enabled")) {
                relayUrl = config.getString("wyrdsekai.between.relay.url");
                relayToken = config.getString("wyrdsekai.between.relay.token");
                if (!relayUrl.isEmpty()) {
                    log.info("Relay configured: {}", relayUrl);
                }
            }
        } catch (Exception e) {
            log.debug("Relay config not found: {}", e.getMessage());
        }

        var pairingService = new PairingService(jdbcUrl, dialect,
            pairingHouseholdId, pairingHouseholdName,
            "" /* serverDid — populated when AgentIdentity is available */,
            pairingNatsUrl, pairingServerUrl,
            relayUrl.isEmpty() ? null : relayUrl,
            relayToken.isEmpty() ? null : relayToken);
        pairingService.initSchema();
        PairingService.register(pairingService);

        // Parental controls — per-member time limits, room restrictions,
        // inference quotas, content filters (the Study parental-controls
        // scroll's promises, enforced). Steward-gated writes verify the
        // caller's role through authService. The minutes-accrual ticker is
        // started further below, once the ClientConnectionRegistry exists.
        ParentalControlService.init(jdbcUrl, dialect, authService);


        // Maintenance subsystem — maintenance mode (steward-only login while
        // on), in-world backup-now + persisted backup schedule, and staged
        // restore (the Study maintenance dial's and key chest's promises,
        // enforced). Reuses the boot BackupOrchestrator when periodic
        // backups are enabled; otherwise builds one on the same backups dir
        // so `backup now` still works. The staged-restore marker is applied
        // by initializeDatabase() BEFORE the world db opens.
        try {
            var maintBackups = backupOrchestrator != null
                ? backupOrchestrator
                : new BackupOrchestrator(SystemPaths.dataDir().resolve("backups"));
            MaintenanceService.init(jdbcUrl, dialect, authService, maintBackups,
                SystemPaths.dataDir(),
                Path.of(config.getString("wyrdsekai.db.path")),
                SystemPaths.dataDir().resolve("search"),
                SystemPaths.dataDir().resolve("node-identity.json"),
                List.of(
                    SystemPaths.dataDir().resolve("agents"),
                    SystemPaths.dataDir().resolve("classifiers"),
                    SystemPaths.dataDir().resolve("souls"),
                    SystemPaths.dataDir().resolve("substrate"),
                    SystemPaths.storyDir(),
                    SystemPaths.biographyDir()));
        } catch (Exception e) {
            log.warn("MaintenanceService init failed — maintenance dial unwired: {}",
                e.getMessage());
        }

        // Household permission checker and steward audit log (§101)
        var permissionChecker = new PermissionChecker();
        var stewardAuditLog = new StewardAuditLog(jdbcUrl, dialect);
        StewardAuditLog.register(stewardAuditLog);

        // Create moderation + sanction enforcement. Installed process-wide
        // (StewardAuditLog.register pattern) so ClientSessionActor.onReport
        // can file user reports into the SAME store the steward reviews.
        var moderationService = new ModerationService();
        ModerationService.install(moderationService);
        var sanctionEnforcer = new SanctionEnforcer(moderationService);

        // Create script loader — resolve the room scripts directory.
        // Search order (first hit wins):
        //   1. $WYRDSEKAI_SCRIPTS_DIR (explicit override)
        //   2. scripts/rooms / ../scripts/rooms — for source-mode runs
        //   3. Standard package install paths — /opt/wyrdsekai/rooms (.deb),
        //      /usr/local/wyrdsekai/rooms (.pkg), <installDir>/rooms via
        //      WYRDSEKAI_HOME if set.
        //
        // Without this search, .deb-installed deployments silently disable all
        // room scripts — including federation transit in docks.js, which made
        // `say "travel beta"` a no-op even after federation/inference worked.
        Path scriptDir = null;
        var scriptsEnv = WyrdConfig.get().scriptsDir();
        var candidates = new ArrayList<Path>();
        if (scriptsEnv != null && !scriptsEnv.isBlank()) {
            candidates.add(Path.of(scriptsEnv));
        }
        candidates.add(Path.of("scripts/rooms"));
        candidates.add(Path.of("../scripts/rooms"));
        var home = WyrdConfig.get().installRoot();
        if (home != null && !home.isBlank()) {
            candidates.add(Path.of(home, "rooms"));
        }
        candidates.add(Path.of("/opt/wyrdsekai/rooms"));
        candidates.add(Path.of("/usr/local/wyrdsekai/rooms"));
        for (var candidate : candidates) {
            if (candidate.toFile().isDirectory()) {
                scriptDir = candidate;
                log.info("Script directory: {}", scriptDir.toAbsolutePath());
                break;
            }
        }
        if (scriptDir == null) {
            log.warn("Script directory not found, room scripts disabled. "
                + "Tried: {}", candidates);
        }

        // User-generated scripts directory (companion-created rooms)
        var userScriptsDir = SystemPaths.scriptsDir();
        try {
            Files.createDirectories(userScriptsDir);
            log.info("User scripts directory: {}", userScriptsDir);
        } catch (IOException e) {
            log.warn("Failed to create user scripts directory: {}", e.getMessage());
        }
        var scriptLoader = scriptDir != null ? new ScriptLoader(scriptDir, userScriptsDir) : null;

        // earlyNatsBridge was pre-connected at the very start of main() (before config/DB/Pekko)
        final var preConnectedNats = earlyNatsBridge;

        // Boot actor system with ZoneGuardian (pass config for Slick backend selection)
        var system = ActorSystem.create(
            ZoneGuardian.create(scriptLoader, foundationRoomSeeds(),
                metadataService, bridgeDataProvider, sanctionEnforcer),
            "wyrdsekai", config);

        // Set scheduler for room ask patterns (replaces ClusterSharding's EntityRef.ask)
        Rooms.setScheduler(system.scheduler());
        RoomRegistry.get().setScheduler(system.scheduler());

        // Soul system: SoulStore (created early so Between can reference it)
        // F7b Phase 2.2: pair the manifest store with the canonical
        // soul_fragments store. Every manifest persist call dual-writes
        // fragments to the table FIRST. backfillFromManifests below covers
        // souls that pre-date the migration; the dual-write hook covers
        // every Forge cycle and cross-zone arrival from then on.
        // F7b Phase 2.3: BondStore is already canonical (writes go through
        // BondRitual). Pair it with SqlSoulStore so cross-zone manifest
        // arrivals reconcile their bonds list into the local table without
        // waiting for the next Forge cycle. Wired with idempotent upsert.
        // F7b Phase 2.4: WorldKnowledgeStore — Map<String,String> per DID.
        // Same atomic-replace pattern as fragments.
        // F7b Phase 3a: VoiceProfileStore wired into SqlSoulStore too so
        // all four sub-records dual-write through one place AND latest()
        // hydrates from canonical tables (canonical wins on conflict).
        var soulFragmentStore = new SoulFragmentStore(jdbcUrl);
        var bondStore = new BondStore(jdbcUrl);
        var worldKnowledgeStore = new WorldKnowledgeStore(jdbcUrl);
        var voiceProfileStoreForSoul = new VoiceProfileStore(jdbcUrl);
        var soulStore = new SqlSoulStore(jdbcUrl, dialect,
            soulFragmentStore, bondStore, worldKnowledgeStore, voiceProfileStoreForSoul);
        soulFragmentStore.backfillFromManifests(soulStore);
        bondStore.backfillFromManifests(soulStore);
        worldKnowledgeStore.backfillFromManifests(soulStore);
        // F7b Phase 4b: CompanionRegistry created early; backfill runs
        // a few lines below once localZoneId resolves, so the home_zone
        // column lights up correctly for legacy souls.
        var companionRegistry = new CompanionRegistry(jdbcUrl);

        // Track-C C5: ChronicleEntryStore singleton wired
        // once at boot. CompanionActor.completeSleep + Study furnishings
        // read via ChronicleEntryStore.get(). Singleton because every
        // companion in the zone shares the same chronicle_entries table
        // and there's no per-actor state to scope.
        ChronicleEntryStore.setInstance(
            new ChronicleEntryStore(jdbcUrl));

        // Shadow log: observation recording for agent perspective
        ShadowLog.init(ShadowLog.fromEnv());

        // Core singletons — one-stop init shared with TestServerBootstrap.
        // Covers EntityRegistry, AgentEventStream, InProcessEventBus,
        // CrossZoneTellService, NotificationService, ActivityLogger,
        // PersonalContextAggregator, CouncilService, AttestationService,
        // GovernorEventMonitor, MeteringService, AgentCostTracker,
        // TradingPostService, EstateManager, CrossZoneExchange,
        // WebSearchService, ZoneAestheticService.
        // See org.wyrdsekai.core.bootstrap.CoreServices — adding a new argless
        // singleton init? Put it there, not here, so tests pick it up.
        var localZoneId = WyrdConfig.get().zoneId();
        CoreServices.init(localZoneId);
        // F7b Phase 4b: companions backfill now that localZoneId is set.
        // Idempotent — safe to run on every boot until Phase 3 drops the
        // SoulManifest shadow fields and writers route through the
        // canonical tables alone.
        companionRegistry.backfillFromManifests(soulStore, localZoneId);

        // Track-C C9 — ship-default scheduler boot. No-op when
        // config.recipes.scheduler.enabled=false. Idempotent: enrollments
        // upsert by composite key, so repeated boots converge. Companion
        // DIDs come from the registry we just backfilled — any companion
        // spawned later will need a per-spawn re-provision call (TODO #C9-followup).
        // resource-requisites (option b) — cross-zone peer borrow.
        // The relay transport connects later in this method, so the wiring
        // resolves it lazily via this holder; until the relay is up the
        // scheduler is local-only. The SAME holder is populated at the
        // RelaySessionTransport.connect site below (used to also be a local
        // declaration there).
        final var sessionTransportHolder =
            new AtomicReference<RelaySessionTransport>();
        CrossZoneRecipeWiring crossZoneRecipeWiringTmp = null;
        try {
            var dataDirForRecipes = Path.of(
                System.getenv().getOrDefault("WYRDSEKAI_DATA_DIR",
                    System.getProperty("user.home") + "/.wyrdsekai"));
            var scriptsRoot = Path.of(
                System.getProperty("user.dir"), "scripts");
            var classifiersDir = dataDirForRecipes.resolve("classifiers");
            var companionDids = companionRegistry.all().stream()
                .map(CompanionRegistry.Row::did)
                .filter(d -> d != null && !d.isBlank())
                .distinct()
                .toList();
            // Build the cross-zone peer-borrow wiring. Trust = federation
            // bilateral agreement; peers = ResourceRegistry gossip; manifests
            // resolved by name from the recipe library. All guarded — any
            // failure leaves the scheduler local-only (the OSS single-node case).
            var recipesDirForBorrow = dataDirForRecipes.resolve("recipes");
            crossZoneRecipeWiringTmp = new CrossZoneRecipeWiring(
                localZoneId, sessionTransportHolder::get,
                new FederationService(jdbcUrl),
                name -> new RecipeService(recipesDirForBorrow, null).inspect(name),
                /* borrowTimeoutSec */ 24L * 3600);
            // (option c — BYO cloud) decorator: the third
            // fallback. Wraps the cross-zone dispatcher so the chain is
            // local → peer → cloud. Only fires when a steward configured
            // `recipes.cloud_launch.script`; otherwise RESOURCE_DENIED passes
            // through to the steward ask (option a). Composed so cloud is the
            // OUTER wrapper (runs after peer-borrow returns RESOURCE_DENIED).
            UnaryOperator<RecipeScheduler.Dispatcher>
                cloudDecorator = d -> new CloudRecipeDispatcher(
                    d,
                    () -> WyrdConfig.get().recipesCloudLaunchScript(),
                    name -> new RecipeService(recipesDirForBorrow, null).inspect(name),
                    ttl -> new ProcessCommandRunner(
                        new File(System.getProperty("user.dir")), ttl));
            var crossZoneDecorator = crossZoneRecipeWiringTmp.decorator();
            UnaryOperator<RecipeScheduler.Dispatcher>
                composedDecorator = d -> cloudDecorator.apply(crossZoneDecorator.apply(d));
            RecipeSchedulerBoot.bootIfEnabled(
                new RecipeSchedulerBoot.BootArgs(
                    system, jdbcUrl, dataDirForRecipes, scriptsRoot,
                    classifiersDir, companionDids,
                    WyrdConfig.get(),
                    composedDecorator));
        } catch (Exception e) {
            log.warn("RecipeScheduler boot failed: {}", e.toString());
        }
        final CrossZoneRecipeWiring crossZoneRecipeWiring =
            crossZoneRecipeWiringTmp;

        // Naming service — derives the household DID from the node keypair and
        // loads ~/.wyrdsekai/{contacts,my-zones}. Loaded after CoreServices so
        // the ZoneAddressResolver is available to docks.js / CLI / BridgeData.
        // NodeIdentity.loadOrGenerate is idempotent — if BetweenActor already
        // loaded it, we read the same file.
        try {
            var dataDir = Path.of(
                System.getenv().getOrDefault("WYRDSEKAI_DATA_DIR",
                    System.getProperty("user.home") + "/.wyrdsekai"));
            Files.createDirectories(dataDir);
            var nodeIdentity = NodeIdentity.loadOrGenerate(
                dataDir.resolve("node-identity.json"));
            CoreServices.initNaming(
                nodeIdentity.publicKeyBytes(), dataDir, localZoneId);
            // F7b Phase 4a: mirror this node's public key into the
            // households table for queries (federation status, doctor,
            // future peer registries). Private key stays in the file.
            try {
                var householdStore = new HouseholdStore(jdbcUrl);
                var householdId = HouseholdIdentity
                    .fromSpkiBytes(nodeIdentity.publicKeyBytes());
                var sha256 = MessageDigest.getInstance("SHA-256")
                    .digest(nodeIdentity.publicKeyBytes());
                var hex = new StringBuilder();
                for (int i = 0; i < sha256.length; i++) {
                    if (i > 0) hex.append(':');
                    hex.append(String.format("%02x", sha256[i] & 0xff));
                }
                householdStore.upsert(
                    nodeIdentity.nodeId(),
                    nodeIdentity.publicKeyBytes(),
                    hex.toString(),
                    householdId.did(),
                    // #1184: also mirror the X25519 grant key so a zone holder can
                    // ECDH-wrap the zone master to this node. Lazy-creates on first call.
                    nodeIdentity.x25519PublicKeyBytes());
                log.info("F7b Phase 4a: mirrored node-identity into households table ({}, +x25519 grant key)",
                    householdId.did());
            } catch (Exception hsErr) {
                log.warn("households mirror failed (non-fatal): {}", hsErr.getMessage());
            }

            // upgrade the NostrAdapter's seed
            // resolver from the placeholder (registered at CoreServices.init
            // time, before NodeIdentity was loaded) to a real implementation
            // that returns the node's Ed25519 seed for its own DID.
            // Companion-specific resolution is Phase 2c work.
            try {
                final var nodeDid = nodeIdentity.did();
                final var nodeSeed = nodeIdentity.privateKeySeedBytes();
                NostrAdapterBootstrap.setSeedResolver(
                    did -> nodeDid.equals(did) ? nodeSeed.clone() : null);
            } catch (Exception nostrErr) {
                log.warn("Nostr SeedResolver upgrade failed (non-fatal): {}",
                    nostrErr.getMessage());
            }

            // foundation — establish this node's per-zone shared secret and install the
            // argot key provider, so zone argot tokens become uncomputable without the secret
            // (real opacity + forge-resistance vs the public-seed fallback). Reuses the node seed
            // loaded just above; the master is held in memory + persisted WRAPPED per node. Non-fatal:
            // on any failure argot falls back to its public seed and the node still runs.
            try {
                var zsZoneId = WyrdConfig.get().zoneId();
                var zsNodeId = nodeIdentity.nodeId();
                var zsSeed = nodeIdentity.privateKeySeedBytes();
                var zsJdbc = System.getProperty("wyrdsekai.jdbc.url");
                // Enable the SECRET argot seed only when this is the sole known node (single-node
                // zone). Multi-node zones must wait for the X25519 grant so all same-zone nodes agree
                // on the master — else cross-node argot diverges. Conservative: any other known
                // household → stay on the public seed.
                boolean zsSoleNode = true;
                try {
                    zsSoleNode = new HouseholdStore(zsJdbc).count() <= 1;
                } catch (Exception ignore) { /* default sole-node → enable; safe for standalone */ }
                ZoneSecrets.bootstrapLocalZone(
                    zsJdbc, zsZoneId, zsNodeId, zsSeed, zsSoleNode);
            } catch (Exception zsErr) {
                log.warn("Zone-secret bootstrap failed (non-fatal; argot uses public seed): {}",
                    zsErr.getMessage());
            }
        } catch (Exception e) {
            log.warn("ZoneAddressResolverService init failed — docks.js / CLI zone lookup will degrade: {}",
                e.getMessage());
        }

        // Person identity — mint a real identity for each human and bring legacy
        // accounts across. MUST come after ZoneSecrets.bootstrapLocalZone above:
        // the household secret that encrypts person private keys is derived from
        // the zone master, so calling this earlier (as an initial version did)
        // could only ever report "zone master not installed yet" and stay off.
        // Agent identity — the same thing for companions, who until now were born
        // holding a did:key with the private half thrown away. Runs BEFORE the
        // person bootstrap so that its rebind-attestation reconcile can prefer a
        // companion's own signature over the steward's witness where one exists.
        // Same ordering rule: after ZoneSecrets.bootstrapLocalZone.
        AgentIdentityBootstrap.run(jdbcUrl);

        PersonIdentityBootstrap.run(jdbcUrl);

        // Plugin event bus plugins (CoreServices inits the bus; plugin wiring is deployment-specific)
        var pluginEventBus = InProcessEventBus.get();
        var eventBusConfigPath = Path.of(
            System.getProperty("user.home"), ".wyrdsekai", "eventbus.json");
        var eventBusPlugins = EventBusPluginLoader
            .load(eventBusConfigPath, pluginEventBus);
        if (!eventBusPlugins.isEmpty()) {
            log.info("Loaded {} EventBus plugin(s)", eventBusPlugins.size());
        }

        // External notification channels are companion-owned (not server-level).
        // Each CompanionActor initializes its own channels from TheSafe on spawn.
        // See CompanionActor.initNotificationChannels().

        var governorMonitor = GovernorEventMonitor.get();

        // Multi-modal ingestion pipeline (camera, voice, screenshots, clipboard)
        var ingestPipeline = IngestPipeline.init();
        ingestPipeline.registerExtractor(new TextExtractor());
        ingestPipeline.registerExtractor(new ImageExtractor());
        ingestPipeline.registerExtractor(new VoiceExtractor());
        // Register target routers — Oracle events submitted via IngestPipeline
        ingestPipeline.registerRouter(IngestTarget.ORACLE, event -> {
            var oracle = OracleBridge.getInstance();
            if (oracle != null) {
                var oracleEvent = new OracleEvent(
                    Instant.now(), event.sourceType(), "ingest", event.text());
                oracle.ingest(event.userId() != null ? event.userId() : "system",
                    List.of(oracleEvent));
            }
        });
        log.info("IngestPipeline initialized with {} extractors", 3);

        // Oracle prediction engine (sidecar at localhost:7073 or ORACLE_URL env)
        var oracleUrl = System.getenv().getOrDefault("ORACLE_URL", "http://localhost:7073");
        OracleBridge.init(oracleUrl);
        var oracleBridge = OracleBridge.getInstance();
        if (oracleBridge != null) {
            oracleBridge.isHealthy().thenAccept(healthy -> {
                if (healthy) {
                    log.info("Oracle sidecar connected at {}", oracleUrl);
                    // Start feed poller for external data sources
                    var feedPoller = new FeedPoller(oracleBridge, "system");
                    var lat = WyrdConfig.get().latitude();
                    var lon = WyrdConfig.get().longitude();
                    var arxivFields = WyrdConfig.get().arxivFields();
                    feedPoller.addDefaults(lat, lon,
                        arxivFields != null ? List.of(arxivFields.split(",")) : List.of());
                    feedPoller.start();
                } else {
                    log.info("Oracle sidecar not available at {} (predictions disabled)", oracleUrl);
                }
            });
        }

        // Watcher service: persistent condition monitors (script evaluator wired later)
        WatcherService.init(
            NotificationService.get(), null);

        // Spawn BetweenActor if cluster mode enabled
        // W3 (audit 2026-07-11): phones subscribe between.<zone>.*.*.oracle.predictions
        // on both RN and KMP; the only publisher class was constructed in tests.
        // Wire it so server-side oracle predictions reach household phones.
        if (preConnectedNats != null) {
            OracleBetweenSync.install(new OracleBetweenSync(
                preConnectedNats.nodeId(), localZoneId, preConnectedNats::publish));
            log.info("OracleBetweenSync wired (zone={})", localZoneId);
        }
        var betweenActor = spawnBetween(system, config, soulStore, preConnectedNats);

        // Wire topology data from BetweenActor to BridgeDataProvider
        if (betweenActor != null) {
            bridgeDataProvider.setTopologySuppliers(
                () -> askTopology(betweenActor, system),
                () -> askTopologyNodeCount(betweenActor, system)
            );
            // Wire federation data from BetweenActor to BridgeDataProvider
            bridgeDataProvider.setFederationSuppliers(
                () -> askFederationStatus(betweenActor, system),
                () -> askFederatedZoneCount(betweenActor, system),
                zoneId -> askProposeFederation(betweenActor, system, zoneId),
                zoneId -> askAcceptFederation(betweenActor, system, zoneId),
                zoneId -> askRevokeFederation(betweenActor, system, zoneId),
                () -> askListVisitors(betweenActor, system)
            );
            // Wire transit request handler
            bridgeDataProvider.setTransitRequester(
                (playerId, playerName, targetZoneId) ->
                    askRequestTransit(betweenActor, system, playerId, playerName, targetZoneId));

            // Start RoomLayer with a supplier for local room assignments.
            // The supplier returns the foundation room list as RoomAssignment records.
            final var betweenRef = betweenActor;
            betweenActor.tell(new BetweenActor.StartRoomLayer(
                () -> foundationRoomSeeds().stream()
                    .map(seed -> new RoomAssignment(
                        seed.roomId(), RoomOwnership.SHARED, null,
                        null, null, 2,
                        Instant.now(), Instant.now()))
                    .toList()));

            // Wire the broadcast-all RoomEventReplicator into ZoneGuardian so that
            // every room event is published to NATS for external subscribers
            // (e.g. Claude room-resident bridge). Must happen before room seeding (3s timeout).
            system.scheduler().scheduleOnce(
                Duration.ofMillis(500),
                () -> {
                    try {
                        var replicator = AskPattern
                            .<BetweenActor.Command, RoomEventListener>ask(
                                betweenRef,
                                ref -> new BetweenActor.GetRoomEventReplicator(ref),
                                Duration.ofSeconds(2),
                                system.scheduler())
                            .toCompletableFuture().get(3, TimeUnit.SECONDS);
                        if (replicator != null) {
                            system.tell(new ZoneGuardian.SetRoomEventListener(replicator));
                            log.info("Room event replicator wired — room events will be published to NATS");
                        } else {
                            log.debug("No room event replicator available (Between may not have started)");
                        }
                    } catch (Exception e) {
                        log.debug("Failed to wire room event replicator: {}", e.getMessage());
                    }
                },
                system.executionContext());


            // After a 2-second delay, query the RoomLayer view and send it to ZoneGuardian
            // so deferred seeding can skip rooms already claimed by peers.
            system.scheduler().scheduleOnce(
                Duration.ofSeconds(2),
                () -> {
                    try {
                        var viewSnapshot = AskPattern
                            .<BetweenActor.Command, LocalRoomView.Snapshot>ask(
                                betweenRef,
                                ref -> new BetweenActor.GetRoomView(ref),
                                Duration.ofSeconds(2),
                                system.scheduler())
                            .toCompletableFuture().get(3, TimeUnit.SECONDS);

                        // Build map of roomId -> primaryNodeId for peer-claimed rooms
                        var claimedMap = new HashMap<String, String>();
                        for (var entry : viewSnapshot.rooms().entrySet()) {
                            var roomEntry = entry.getValue();
                            if (roomEntry.primaryNodeId() != null) {
                                claimedMap.put(entry.getKey(), roomEntry.primaryNodeId());
                            }
                        }

                        system.tell(new ZoneGuardian.ApplyRoomView(claimedMap));
                        log.info("Room topology view applied to ZoneGuardian — {} claimed rooms",
                            claimedMap.size());

                        // Wire room command transport for cross-node proxying
                        try {
                            var bridge = AskPattern
                                .<BetweenActor.Command, RoomCommandBridge>ask(
                                    betweenRef,
                                    ref -> new BetweenActor.GetRoomCommandBridge(ref),
                                    Duration.ofSeconds(2),
                                    system.scheduler())
                                .toCompletableFuture().get(3, TimeUnit.SECONDS);
                            var roomPrimary = AskPattern
                                .<BetweenActor.Command, RoomPrimaryProtocol>ask(
                                    betweenRef,
                                    ref -> new BetweenActor.GetRoomPrimaryProtocol(ref),
                                    Duration.ofSeconds(2),
                                    system.scheduler())
                                .toCompletableFuture().get(3, TimeUnit.SECONDS);
                            if (bridge != null && roomPrimary != null) {
                                system.tell(new ZoneGuardian.SetRoomTransport(
                                    roomId -> {
                                        var primary = roomPrimary.getPrimaryNode(roomId);
                                        if (primary.isEmpty()) return null;
                                        var localNodeId = roomPrimary.getLocalNodeId();
                                        if (primary.get().equals(localNodeId)) return null;
                                        return primary.get();
                                    },
                                    bridge::sendCommand,
                                    bridge::subscribeEvents,
                                    bridge::subscribeState,
                                    bridge::listenForCommands,
                                    nodeId -> ResourceRegistry.get()
                                        .getSnapshot(nodeId).isPresent()
                                ));
                                log.info("Room command transport wired — cross-node room proxying enabled");
                            }
                        } catch (Exception e) {
                            log.debug("Failed to wire room transport: {}", e.getMessage());
                        }
                    } catch (Exception e) {
                        log.debug("Failed to query room view for ZoneGuardian (will use timeout fallback): {}",
                            e.getMessage());
                    }
                },
                system.executionContext());
        }

        // AgentCostTracker, TradingPostService, EstateManager, CrossZoneExchange,
        // ZoneAestheticService are all initialised by CoreServices above.

        // Spawn CountingHouseActor (event-sourced, singleton per zone)
        // Wire JDBC ledger persistence so credit balances survive restart
        var ledgerPersistence = new LedgerPersistence(jdbcUrl);
        var nodeId = resolveNodeId();
        var zoneId = WyrdConfig.get().zoneId();
        var isBetweenEnabled = WyrdConfig.get().betweenEnabled()
            || WyrdConfig.get().resolveBool("WYRDSEKAI_CLUSTER", "cluster.enabled", false);
        ReplicaId selfReplica;
        Set<ReplicaId> allReplicas;
        if (isBetweenEnabled) {
            // Multi-replica: use nodeId as replica ID so economy events replicate across nodes
            selfReplica = new ReplicaId(nodeId);
            // Start with self only — peers add themselves via Pekko RES discovery
            allReplicas = Set.of(selfReplica);
            log.info("CountingHouse multi-replica mode: self={}", nodeId);
        } else {
            selfReplica = CountingHouseActor.DEFAULT_REPLICA;
            allReplicas = CountingHouseActor.DEFAULT_REPLICAS;
        }
        var countingHouse = system.<CountingHouseCommand>systemActorOf(
            CountingHouseActor.create(selfReplica, allReplicas,
                CountingHouseActor.DEFAULT_QUERY_PLUGIN, ledgerPersistence),
            "counting-house", Props.empty());
        log.info("CountingHouseActor spawned with JDBC ledger persistence (replica={})", selfReplica);

        // Economy + ZoneAesthetic singletons initialised by CoreServices above;
        // only the config load is deployment-specific and lives here.
        ZoneAestheticService.get().loadConfig();

        // Wire economy data from CountingHouse to BridgeDataProvider
        bridgeDataProvider.setEconomySupplier(
            () -> askEconomyStatus(countingHouse, system));

        // Create MCP gateway service and wire cost recording to CountingHouse (§86.2)
        var mcpRegistry = new McpServiceRegistry();
        // Load MCP service config if present (optional — gateway works without services)
        var mcpConfigPath = SystemPaths.dataDir().resolve("mcp-services.json");
        if (Files.exists(mcpConfigPath)) {
            try {
                mcpRegistry.loadFromFile(mcpConfigPath);
                log.info("Loaded MCP service config from {}", mcpConfigPath);
            } catch (Exception e) {
                log.warn("Failed to load MCP service config (gateway will have no services): {}", e.getMessage());
            }
        }
        // Real transport (W-MCP 2026-07-20): resolve the service whose endpoint
        // this is (to learn its transport type: http/stdio/ws/sse), build the
        // matching handler via McpTransportFactory, run the MCP initialize→callTool
        // handshake, and return the tool's text content. Per-call handler (no pooled
        // connection) — fine for the opt-in service set; can be pooled later.
        // Before this, the gateway's transport threw unconditionally, so every
        // external world.mcp() failed and only the in-process Study skill worked.
        var mcpGateway = new McpGatewayService(mcpRegistry,
            (endpoint, toolName, params, authHeader) -> {
                var cfg = mcpRegistry.enabledServices().stream()
                    .filter(s -> endpoint != null && endpoint.equals(s.endpoint()))
                    .findFirst()
                    .orElse(null);
                McpTransportHandler handler = (cfg != null)
                    ? McpTransportFactory.create(cfg, authHeader)
                    : new HttpTransportHandler(endpoint, Map.of(), authHeader);
                try {
                    handler.initialize();
                    var result = handler.callTool(toolName, params != null ? params : Map.of());
                    return result.textContent();
                } finally {
                    try { handler.close(); } catch (Exception ignore) { /* best-effort */ }
                }
            });
        mcpGateway.setCostRecorder((agentId, serviceId, toolName, cost, latencyMs) -> {
            countingHouse.tell(new CountingHouseCommand.RecordUsage(
                new ResourceUsage(agentId, "mcp:" + serviceId + ":" + toolName,
                    0, 0, serviceId, Instant.now())));
        });
        // W5 (audit 2026-07-11) — single wiring block:
        // 1) Counting House WRITE API (Transfer/QueryBalance) was test-only;
        //    install the gateway so the household_treasury item's pay/balance
        //    verbs reach the real ledger.
        CountingHouseGateway.install(countingHouse, system.scheduler());
        // 2) Feed the Between's wyrd.discovery.capabilities announcements the
        //    REAL MCP service set (registry loaded just above), so household
        //    peers/daemons learn which gateway services this node offers.
        if (betweenActor != null) {
            betweenActor.tell(new BetweenActor.SetMcpServiceIds(mcpRegistry::serviceIds));
        }
        // Wire CredentialResolver for external adapters (Phase U/Q/O — maps,
        // weather, comms). Until 2026-07-11 setSafeReader was only ever called
        // in TESTS, so every adapter returned credential_missing regardless of
        // what the steward configured — there was NO production path to
        // populate a slot at all (found via second-node: morning_briefing → geocode →
        // "credential slot 'googlemaps.api_key' is not populated").
        // W13: resolution order is now The Safe slot
        // (steward-populated, persisted at dataDir/credentials.safe — see the
        // TheSafe.initLocal wiring block below), then WYRDSEKAI_CRED_* env var
        // (compat path, fed by the single canonical conf via the systemd
        // EnvironmentFile), then system property.
        // Slot "googlemaps.api_key" → WYRDSEKAI_CRED_GOOGLEMAPS_API_KEY.
        CredentialResolver.get().setSafeReader(CredentialResolver.chainedReader(
            slot -> TheSafe.local().readSlot(slot),
            System::getenv, System::getProperty));
        log.info("CredentialResolver wired: slots resolve via The Safe (credentials.safe), "
            + "then WYRDSEKAI_CRED_* env (from /etc/wyrdsekai/wyrdsekai.conf), "
            + "then -Dwyrdsekai.cred.* properties");
        // Sibling hook, same test-only gap: the on-miss steward nudge. Rate-limited
        // to once per slot per hour inside the resolver; delivers a Mailbox line
        // telling the steward exactly which slot to populate and how.
        CredentialResolver.get().setMailboxNotifier((stewardDid, slot) -> {
            var svc = NotificationService.get();
            if (svc == null) return;
            // Adapters call resolve(slot) with no steward in scope — the miss
            // notice goes to the household steward (first role=steward user).
            var target = stewardDid;
            if (target == null) {
                try {
                    target = authService.findSteward().map(u -> u.id()).orElse(null);
                } catch (Exception e) {
                    return;
                }
            }
            if (target == null) return;
            final var stewardTarget = target;
            var envKey = "WYRDSEKAI_CRED_"
                + slot.toUpperCase().replace('-', '_').replace('.', '_');
            svc.notify(stewardTarget,
                "A tool needs a credential that isn't set: '" + slot + "'. "
                + "Populate it with `use scroll set " + envKey + "=<key>` in your Study "
                + "(then `use scroll apply`), or `wyrd config set " + envKey + "=<key>` "
                + "+ restart.", "normal", "credential-resolver");
        });

        // Wire McpKeyStore for TheSafe credential resolution (§89.1)
        var mcpKeyStore = new McpKeyStore(safeKey -> {
            // Read key from environment variables (WYRDSEKAI_MCP_KEY_<SAFE_KEY>)
            var envKey = "WYRDSEKAI_MCP_KEY_" + safeKey.toUpperCase().replace('-', '_').replace('.', '_');
            var envValue = System.getenv(envKey);
            if (envValue != null) return envValue;
            // Fallback: check system property
            return System.getProperty("wyrdsekai.mcp.key." + safeKey);
        });
        mcpGateway.setKeyStore(mcpKeyStore);
        log.info("McpGatewayService created with cost recording and TheSafe key store");

        // Construct McpServerManager (W-MCP 2026-07-20). Nothing built this before,
        // so McpServerManager.get() returned null everywhere — the companion/item
        // MCP discovery+invoke path and the grant-check attach (below) were dead.
        // Its ctor publishes the singleton. Connect the enabled services now; an
        // empty registry connects to nothing (the safe default). Connect failures
        // are non-fatal — a mis-configured service must not abort boot.
        var mcpServerManager = new McpServerManager(mcpKeyStore);
        for (var svc : mcpRegistry.enabledServices()) {
            try {
                var tools = mcpServerManager.connect(svc);
                log.info("MCP server '{}' connected ({} tools)", svc.id(), tools.size());
            } catch (Exception e) {
                log.warn("MCP server '{}' connect failed (skipping): {}", svc.id(), e.getMessage());
            }
        }

        // W1: bridge room scripts' world.mcp() to this
        // gateway — RoomActor builds its RoomScriptEngine without a provider,
        // so without this install every world.mcp() call in every room
        // answered "MCP gateway not available".
        RoomMcpBridge.install(mcpGateway);
        // ...and register the Study's local "skill" service (study.fs.read/
        // list/search/mounts + vault.doc.extract) over the persisted
        // shelf-mount table, so the Study's ls/find/grep/cat/take surface is
        // real instead of theater.
        StudySkillService.register(mcpGateway, StudyMountRegistry.get());
        log.info("Study 'skill' MCP service registered; room world.mcp() bridged to gateway");

        // Phase 1 (2026-07-21) — hand the companion spawn path the SAME gateway +
        // a populated native SkillRegistry. Before this every companion was built
        // with mcpGateway=null AND skillRegistry=null (ZoneGuardian), so the 30+
        // native skills were unreachable except via the Between mesh bridge.
        McpGatewayService.installShared(mcpGateway);
        // 1.3 (2026-07-21) — the HOCON→flat-key bridge lights the config-gated
        // executors (Kiwix/HomeAssistant/CalDAV/Signal/…/OpenClaw gateway):
        // wyrdsekai.skills.* from reference.conf/wyrdsekai.conf flattens into
        // the map create() reads. Before this the map was Map.of() and every
        // config-dependent skill silently never registered.
        var nativeSkillRegistry = SkillBootstrap.create(SkillBootstrap.configFromHocon());
        // …and import SKILL.md skills: bundled
        // resources/openclaw-skills seeds land in <dataDir>/skills (steward-
        // editable), then the whole dir is scanned — OpenClaw/ClawHub/Hermes
        // modern format + legacy structured. Skills whose bins/env are missing
        // stay dormant (logged), not offered-but-broken.
        var importedSkills = SkillBootstrap.importSkillMd(
            nativeSkillRegistry, SystemPaths.dataDir().resolve("skills"));
        SkillBootstrap.installShared(nativeSkillRegistry);
        // Phase 2.2 — initialize the SchedulerService with the shared registry so
        // scheduled/timed skills work (was test-only init → SchedulerService.get()
        // returned null in prod and `schedule` actions silently no-op'd).
        SchedulerService.init(nativeSkillRegistry);
        log.info("Native SkillRegistry installed for companions ({} skills, {} imported "
            + "SKILL.md live); companion capability surface now carries skills + gateway",
            nativeSkillRegistry.allSkills().size(), importedSkills);
        // Discovery scan ( "available but not installed") — runs
        // in the background because it probes local HTTP services with timeouts.
        var skillInstaller = new SkillInstaller(
            nativeSkillRegistry, new SkillMdImporter(), SystemPaths.dataDir().resolve("skills"));
        SkillInstaller.installShared(skillInstaller);
        Thread.ofVirtual().name("skill-scan").start(() -> {
            try {
                skillInstaller.scanForSkills();
            } catch (Exception e) {
                log.warn("Skill discovery scan failed: {}", e.getMessage());
            }
        });

        // A2A Gateway: interop with external agents via Docks room (§97.1)
        var trustResolver = new TrustTierResolver();
        var dockQuarantine = new DockQuarantine();
        var vitalityRedactor = new VitalityRedactor();
        var a2aGateway = new A2AGateway(
            trustResolver, dockQuarantine, vitalityRedactor);

        // Schedule daily dormancy check for Isekai residency tokens (§110)
        var dormancyScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "dormancy-scheduler");
            t.setDaemon(true);
            return t;
        });
        dormancyScheduler.scheduleAtFixedRate(
            () -> a2aGateway.runDormancyCheck(), 1, 24, TimeUnit.HOURS);
        log.info("A2AGateway created with daily dormancy check scheduled");

        // Wire reputation data from CountingHouse to BridgeDataProvider
        bridgeDataProvider.setReputationSuppliers(
            () -> askAllReputations(countingHouse, system),
            entityId -> askReputation(countingHouse, system, entityId));

        // Library: migrate old schema, create store, spawn actor
        var libraryActor = spawnLibrary(system, jdbcUrl, bridgeDataProvider);

        // Lucene search store — vector + text search for soul fragments, memory, rooms, world DNA.
        // Runs alongside SQLite FTS5 for migration safety; FTS5 remains the default library backend.
        WyrdLuceneStore luceneStore = null;
        try {
            // Dimension follows the CONFIGURED retrieval model (env/selector-file), not a
            // hardcoded 384: a bge-m3 node (1024d) with a 384-pinned store had every dense
            // leg skipped — "Query embedding dim 1024 != index dim 384" (second-node 2026-07-09).
            // resolveActiveModel() reads config only; no ONNX load at boot.
            int embedDim = EmbeddingService.resolveActiveModel().dimension();
            luceneStore = new WyrdLuceneStore(
                SystemPaths.dataDir().resolve("search"),
                embedDim
            );
            luceneStore.ensureAllCollections();
            // Study content was written under whatever string the ingest CLI had
            // — usually $(whoami). Move it onto the person identity, or the
            // companion's Study corridor resolves an owner that owns nothing.
            // BACKGROUND: the first live run took 69 minutes over 13.7M documents
            // and, being synchronous here, kept the whole household offline for
            // all of it. The server now comes up immediately and the pass runs
            // behind it.
            PersonIdentityBootstrap.migrateStudyOwnersAsync(luceneStore);

            // Give every chunk its place in the document it came from.
            //
            // `title` was stored but never indexed, so nothing could ask what
            // came before a passage — a 471-part book was 471 unrelated pieces.
            // Same background shape as the owner migration above, and for the
            // same reason: it is a pass over millions of documents and has no
            // business holding up a boot. Resumable, so a restart mid-pass costs
            // nothing, and a finished pass finds nothing to do.
            final var orderStore = luceneStore;
            var chunkOrder = new Thread(() -> {
                try {
                    var n = orderStore.backfillChunkOrder(SearchCollections.STUDY, 500,
                        done -> {
                            if (done % 100_000 == 0) {
                                log.info("[ChunkOrder] {} documents placed in reading order", done);
                            }
                        });
                    if (n > 0) {
                        log.info("[ChunkOrder] finished — {} documents can now be read in "
                            + "sequence with their neighbours", n);
                    }
                } catch (WyrdLuceneStore.BackfillInterrupted stopped) {
                    // Say STOPPED, not finished. The first version of this logged
                    // "finished — 500 documents" for a pass that had died on its
                    // second query, against a corpus of 15,585,914. A wrapper that
                    // cannot tell completion from failure will always report the
                    // flattering one.
                    log.warn("[ChunkOrder] STOPPED after {} documents — NOT complete. "
                        + "It resumes on the next start; if this repeats at the same count, "
                        + "the pass is not making progress.", stopped.placed);
                } catch (RuntimeException e) {
                    log.warn("[ChunkOrder] stopped: {} — resumes on the next start", e.toString());
                }
            }, "chunk-order-backfill");
            chunkOrder.setDaemon(true);          // never delay shutdown
            chunkOrder.setPriority(Thread.MIN_PRIORITY);   // yield to the household
            chunkOrder.start();
            // Seed starter knowledge content on first run (mythology, science, history, philosophy)
            KnowledgeSeeder.seedIfEmpty(luceneStore);
            // bundled Tier-0 shelf (Book of the World, reference core
            // trilingual culture seed); idempotent, version-marked, never blocks boot.
            FirstShelfSeeder.seed(luceneStore);
            // Starter packs the steward opted into at `wyrd setup` (background, idempotent).
            StarterLibraryInstaller.installIfRequested(
                luceneStore, SystemPaths.dataDir().resolve("packs"));
            // Dictionaries shipped inside the installer payload (share/library-bundle)
            // indexed on first boot, no network.
            BundledPackInstaller.installBundled(
                luceneStore, BundledPackInstaller.defaultBundleDir());
            // Wire Lucene into ZoneGuardian so foundation rooms are indexed on seed
            system.tell(new ZoneGuardian.SetLuceneStore(luceneStore));
            // Wire Lucene into BridgeDataProvider for knowledge search from room scripts
            bridgeDataProvider.setLuceneStore(luceneStore);
            // Enable in-world pack installs from the Library/Study/Bridge
            bridgeDataProvider.setPacksDir(SystemPaths.dataDir().resolve("packs"));
            log.info("WyrdLuceneStore initialized ({} collections, {}-dim embeddings)",
                SearchCollections.ALL.length, embedDim);
            // Embed-migration nudge (data-durability, 2026-07-09): if stored fragments were
            // embedded by a DIFFERENT model than the active one, say so at boot with the
            // remedy — instead of semantic recall quietly degrading to BM25.
            try (var nudgeConn = DriverManager.getConnection(jdbcUrl);
                 var nudgeStmt = nudgeConn.createStatement()) {
                var current = EmbeddingService.resolveActiveModel().version();
                var rs = nudgeStmt.executeQuery(
                    "SELECT COUNT(*) FROM soul_fragments WHERE embedding IS NOT NULL "
                    + "AND embedding_model IS NOT NULL AND embedding_model != '" + current + "'");
                if (rs.next() && rs.getInt(1) > 0) {
                    log.warn("{} soul fragments were embedded by a different model than the active "
                        + "'{}' — semantic recall for them is degraded. Run `wyrd embed-migrate --run`.",
                        rs.getInt(1), current);
                }
            } catch (Exception nudgeEx) {
                log.debug("Embed-migration nudge skipped: {}", nudgeEx.getMessage());
            }
        } catch (Exception e) {
            log.warn("WyrdLuceneStore initialization failed (search disabled): {}", e.getMessage());
        }

        // World DNA service and harvester
        var worldDnaService = new WorldDnaService(jdbcUrl, dialect);
        system.<WorldDnaHarvester.Command>systemActorOf(
            WorldDnaHarvester.create(worldDnaService, metadataService),
            "world-dna-harvester", Props.empty());
        log.info("WorldDnaHarvester spawned");

        // Soul system: ForgeActor (SoulStore created earlier for Between)
        var forgeActor = system.<ForgeCommand>systemActorOf(
            ForgeActor.create(soulStore), "forge-actor", Props.empty());
        log.info("ForgeActor spawned");

        // Spawn InferenceRouter with ResourceMeter + ComputeUnitNormalizer
        var computeNormalizer = new ComputeUnitNormalizer();
        var resourceMeter = new ResourceMeter(countingHouse, computeNormalizer);
        var inferenceRouter = spawnInferenceRouter(system, config, resourceMeter);

        // Spawn HomeRegistryActor — the single authority layer for grants + audit.
        var homeStore = new HomeStore(jdbcUrl);
        var homeEventListener = new NotificationHomeEventListener();
        var homeRegistry = system.systemActorOf(
            HomeRegistryActor.create(homeStore, homeEventListener),
            "home-registry",
            Props.empty());
        log.info("HomeRegistryActor spawned — grants + audit active, notifications wired");

        // Wire inference status to BridgeDataProvider
        if (inferenceRouter != null) {
            final var router = inferenceRouter;
            bridgeDataProvider.setInferenceSuppliers(
                () -> askInferenceStatus(router, system),
                () -> askInferenceBackendCount(router, system));
        }

        // The household's library / model / web, for items a PERSON is holding.
        // HomeOwnerItemProvider extends the FOREIGN-zone provider and only ever added
        // the household ADMIN surfaces; every content surface stayed a
        // "visiting foreign zone" stub, inside the person's own house. Only the
        // companion's provider was ever built with the Lucene store and the router, so
        // world.library.search and world.llm.* worked when SHE used an item and not when
        // he did. Content only — identity, speech and memory come from the caller's own
        // provider, and this one carries no callbacks for them. See HouseholdItemContent.
        if (luceneStore != null || inferenceRouter != null) {
            // Resources are shared; providers are not. A caller-aware surface
            // reads its inputs from HouseholdResources and answers identity
            // questions on the CALLER's own provider — see HouseholdResources.
            HouseholdResources.register(luceneStore);
            // PER CALLER, not one shared instance. The identity handed in here
            // is the person actually holding the item, so every surface that
            // reads it — study reach, note ownership, host audit, cost booking
            // — answers for them. A null caller means nothing knows who is
            // asking, and those surfaces must fail closed rather than borrow
            // the placeholder identity this used to carry.
            final var contentStore = luceneStore;
            final var contentRouter = inferenceRouter;
            HouseholdItemContent.registerFactory(callerDid -> {
                var who = callerDid != null && !callerDid.isBlank() ? callerDid : "household";
                return new ItemWorldApiProviderImpl(
                    contentStore, contentRouter, system.scheduler(), system,
                    who, who,
                    /* speak */ null, /* remember */ null, /* tell */ null,
                    EquipmentService.get(), null);
            });
        } else {
            log.info("HouseholdItemContent: no library or router on this node — "
                + "player-held items keep the visitor-safe content stubs");
        }

        // ── W13 + W14 wiring block ──────────────────
        // W13: TheSafe as the production credential backend. Single-node
        // local mode — credential slots are K=N=1 Shamir shares persisted
        // encrypted-at-rest to dataDir/credentials.safe (600-mode). The file
        // key derives from the node identity's Ed25519 seed
        // (loadOrGenerate is idempotent — same file every other loader uses);
        // if the identity can't be read the safe honestly falls back to a
        // plaintext 600-mode file and says so in the log. The
        // CredentialResolver chain above (Safe slot → WYRDSEKAI_CRED_* env →
        // system property) reads through TheSafe.local(), so this init just
        // has to happen before the first adapter resolve — boot time is fine.
        try {
            var safeDataDir = Path.of(System.getenv().getOrDefault("WYRDSEKAI_DATA_DIR",
                System.getProperty("user.home") + "/.wyrdsekai"));
            byte[] safeKeyMaterial = null;
            try {
                var safeNodeIdentity = NodeIdentity.loadOrGenerate(
                    safeDataDir.resolve("node-identity.json"));
                safeKeyMaterial = safeNodeIdentity.privateKeySeedBytes();
            } catch (Exception idErr) {
                log.warn("W13: node identity unavailable ({}) — credentials.safe "
                    + "will be plaintext at 600-mode (filesystem perms are the only "
                    + "at-rest protection)", idErr.getMessage());
            }
            var credentialSafe = TheSafe.initLocal(
                safeDataDir.resolve("credentials.safe"), safeKeyMaterial);
            log.info("W13: TheSafe credential store online at {} ({}, {} slot(s) loaded)",
                safeDataDir.resolve("credentials.safe"),
                safeKeyMaterial != null ? "encrypted-at-rest via node-identity seed"
                                        : "plaintext-600 fallback",
                credentialSafe.listSlots().size());
        } catch (Exception safeErr) {
            log.warn("W13: TheSafe local store failed to initialize — credential "
                + "slots fall back to env/property only: {}", safeErr.getMessage());
        }
        // W14: SafetyTrigger wired at the companion ingest seam (§100.6).
        // CompanionActor calls SafetyMonitorService.inspect() on every heard
        // WorldEvent.Said; the service gates on parental controls being set
        // for the speaker, runs regex (+ optional LLM one-shot classification
        // via InferenceRouter) off-thread, and routes concerns through
        // SafetyAlertRouter to Mailbox notifications — never straight to the
        // parent when the parent may be the problem.
        try {
            var safetyTrigger = new SafetyTrigger();
            if (inferenceRouter != null) {
                safetyTrigger.setLlmClassifier(
                    SafetyMonitorService.classifierViaRouter(inferenceRouter, system));
            }
            var safetyAlertRouter = new SafetyAlertRouter();
            SafetyMonitorService.init(safetyTrigger, safetyAlertRouter,
                (target, message, priority, source) -> {
                    var notif = NotificationService.get();
                    if (notif != null) notif.notify(target, message, priority, source);
                });
            log.info("W14: SafetyMonitorService online ({} pattern locale(s), LLM fallback {})",
                safetyTrigger.localeCount(),
                inferenceRouter != null ? "via InferenceRouter" : "off — no router");
        } catch (Exception safetyErr) {
            log.warn("W14: SafetyMonitorService failed to initialize — safety "
                + "triggers inert: {}", safetyErr.getMessage());
        }
        // ── end W13 + W14 wiring block ───────────────────────────────────

        // ── hermod (task #178): capability gossip for the two-way mesh.
        // Advertisement only at this stage — placement/execution arrive with
        // seat-config. Guarded: no NATS, no gossip; hermod stays inert.
        // The phone proxy exists either way (its WS route registers below);
        // unattached it just tells phones the mesh is inert.
        var hermodPhoneProxy = new PhoneDoorProxy(Clock.systemUTC());
        if (earlyNatsBridge != null && earlyNatsBridge.rawConnection() != null) {
            try {
                var hermodGossip = new NatsGossip(
                    earlyNatsBridge.rawConnection(), WyrdConfig.get().zoneId());
                var hermodCapClass = NodeCapabilities.hostHasGpu()
                    ? "llm.local-gpu" : "llm.local-cpu";
                // The hands seat is hermod's local executor: explicit task
                // dispatch is exactly the work the mesh places. Seat unset →
                // hands runs on the default inference endpoint.
                var handsUrl = WyrdConfig.get().seatUrl("hands");
                var handsModel = WyrdConfig.get().seatModel("hands");
                var hermodModels = handsModel.isBlank()
                    ? List.<String>of()
                    : List.of(handsModel);
                var handsThinking = !"nothink".equals(WyrdConfig.get().seatMode("hands"));
                var hermodExecutor = new HermodInferenceExecutor(
                    new InferenceClient(
                        handsUrl, "", Duration.ofSeconds(120)),
                    120, handsThinking);
                var hermodIdentity = NodeIdentity.loadOrGenerate(
                    Path.of(System.getenv().getOrDefault(
                        "WYRDSEKAI_DATA_DIR", System.getProperty("user.home") + "/.wyrdsekai"))
                        .resolve("node-identity.json"));
                var hermodService = new HermodService(
                    hermodGossip, WyrdConfig.get().zoneId(),
                    hermodIdentity.nodeId(),
                    hermodCapClass, hermodModels,
                    hermodExecutor, Clock.systemUTC(),
                    hermodIdentity.publicKeyBytes());
                hermodService.residentDomains(WyrdConfig.get().hermodDataDomains());
                hermodService.start();
                // P3: doors — answer our own, knock on others' (one RPC per offer).
                var hermodDoors = new NatsDoors(
                    earlyNatsBridge.rawConnection(), WyrdConfig.get().zoneId());
                hermodDoors.serve(hermodService.deviceId(), hermodService.ownDoor());
                hermodService.remoteDoors(hermodDoors);
                // Phones have no NATS: this zone gossips for them and serves
                // their door subjects, forwarding knocks over /ws/hermod.
                hermodPhoneProxy.attach(
                    hermodGossip, hermodDoors::serve, WyrdConfig.get().zoneId());
                // Bunshin turns ride the mesh: install the core-side carrier.
                // Tries GPU-class devices first, then CPU-class; null when
                // nobody takes it, and the caller falls back to the local
                // router. taskType inference.chat.full = tools intact.
                final var meshForDispatch = hermodService;
                MeshDispatch.install((chatJson, budget) -> {
                    for (var cls : new String[]{"llm.local-gpu", "llm.local-cpu"}) {
                        var envelope = new TaskEnvelope(
                            UUID.randomUUID().toString(),
                            WyrdConfig.get().zoneId(),
                            meshForDispatch.deviceId(),
                            HermodInferenceExecutor.TASK_TYPE_FULL,
                            "none", cls,
                            Map.of("chatRequestJson", chatJson),
                            budget, Instant.now(),
                            Instant.now().plusSeconds(180),
                            Optional.empty(), new byte[]{1});
                        var r = meshForDispatch.mesh().submit(envelope);
                        if (r.ok()) return r.output();
                    }
                    return null;
                });
            } catch (Exception hermodErr) {
                log.warn("hermod gossip failed to start (mesh inert): {}", hermodErr.getMessage());
            }
        }

        // Wire cross-node inference discovery: periodically sync ResourceRegistry → InferenceRouter.
        //
        // Debounced removal: a single tick of "endpoint absent" does NOT tear
        // down the backend. We track a miss count per backend and only remove
        // after REMOVE_AFTER_MISSES consecutive empty ticks. This absorbs
        // transient RelayBridge jitter (a single missed capability announcement
        // shouldn't kill the inference path mid-request). Combined with
        // ResourceRegistry.STALE_THRESHOLD=120s, a cross-zone backend needs to
        // genuinely go quiet for ~2 minutes before the router forgets it.
        if (inferenceRouter != null && betweenActor != null) {
            final var router = inferenceRouter;
            // Household inference auto-share (consumer side, ):
            // a node with no usable local GPU prefers a household peer's GPU backend
            // (priority below the local CPU backend), local CPU staying the
            // health-fallback. Membership is keyed on HouseholdStore — the same
            // trust set used for zone-secret grants; non-household peers are never
            // auto-preferred. localHasGpu is hardware, computed once.
            final var householdStore = new HouseholdStore(jdbcUrl);
            final boolean localHasGpu = NodeCapabilities.hostHasGpu();
            final int HOUSEHOLD_GPU_PRIORITY = 2; // below the default local backend (~5), above 0
            var discoveryScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                var t = new Thread(r, "inference-discovery");
                t.setDaemon(true);
                return t;
            });
            final var knownRemoteBackends = new HashSet<String>();
            final var missCounts = new HashMap<String, Integer>();
            final int REMOVE_AFTER_MISSES = 4; // 4 × 15s = 60s of absence before removal
            discoveryScheduler.scheduleAtFixedRate(() -> {
                try {
                    var endpoints = ResourceRegistry.get()
                        .allInferenceEndpoints();
                    log.debug("Inference discovery tick: {} endpoints, {} known remote",
                        endpoints.size(), knownRemoteBackends.size());
                    var currentRemote = new HashSet<String>();
                    // Read the borrow toggle per tick so `wyrd inference share/borrow`
                    // changes apply without a restart.
                    boolean householdBorrow = WyrdConfig.get().inferenceHouseholdBorrow();
                    for (var ep : endpoints) {
                        if (ep.isLocal()) continue; // skip local — already configured
                        var backendName = "remote-" + ep.nodeId() + "-" + ep.endpoint().backendType();
                        currentRemote.add(backendName);
                        // seen this tick — reset miss counter. If it had been missed
                        // (and thus marked DOWN below), lift the exile now that the
                        // peer is announcing again (task #36 — no permanent removal).
                        boolean wasMissed = missCounts.remove(backendName) != null;
                        if (wasMissed && knownRemoteBackends.contains(backendName)) {
                            router.tell(new InferenceRouter.SetBackendHealth(backendName, true));
                            log.info("Remote inference {} reappeared — marked healthy", backendName);
                        }
                        if (!knownRemoteBackends.contains(backendName)) {
                            // Prefer this peer iff: borrow enabled, I have no local GPU,
                            // the peer is a household member, and the peer actually has a GPU.
                            boolean householdGpu = householdBorrow && !localHasGpu
                                    && householdStore.get(ep.nodeId()).isPresent()
                                    && ResourceRegistry.get().peerHasGpu(ep.nodeId());
                            int priority = householdGpu
                                    ? HOUSEHOLD_GPU_PRIORITY
                                    : 100 + (int) (ep.latencyMs() / 10.0);
                            router.tell(new InferenceRouter.AddRemoteBackend(
                                backendName, ep.endpoint().backendType(), ep.resolvedUrl(),
                                List.of(ep.endpoint().modelName()),
                                priority, householdGpu));
                            knownRemoteBackends.add(backendName);
                            log.info("Discovered remote inference: {} at {} (latency={}ms{})",
                                backendName, ep.resolvedUrl(), String.format("%.1f", ep.latencyMs()),
                                householdGpu ? ", household-GPU preferred" : "");
                        }
                    }
                    // Mark known-but-currently-absent backends as missed this tick;
                    // only remove after REMOVE_AFTER_MISSES consecutive misses.
                    for (var name : new HashSet<>(knownRemoteBackends)) {
                        if (currentRemote.contains(name)) continue;
                        var misses = missCounts.getOrDefault(name, 0) + 1;
                        // First miss (~15s of absence): proactively mark the backend
                        // DOWN so selectBackend skips it and falls back to local 4B
                        // immediately, instead of routing to a likely-dead peer and
                        // eating the full ~120s NATS dispatch timeout. Removal still
                        // waits for REMOVE_AFTER_MISSES; this only stops routing there.
                        // See task #36 — cross-zone borrowed-9B outage fast-degrade.
                        if (misses == 1) {
                            router.tell(new InferenceRouter.SetBackendHealth(name, false));
                            log.info("Remote inference {} missed a tick — marked DOWN "
                                + "(fast-degrade to local)", name);
                        }
                        if (misses >= REMOVE_AFTER_MISSES) {
                            router.tell(new InferenceRouter.RemoveRemoteBackend(name));
                            knownRemoteBackends.remove(name);
                            missCounts.remove(name);
                            log.info("Removed remote inference: {} (peer absent for {} ticks)",
                                name, REMOVE_AFTER_MISSES);
                        } else {
                            missCounts.put(name, misses);
                            log.debug("Remote inference {} missed tick {}/{} — keeping",
                                name, misses, REMOVE_AFTER_MISSES);
                        }
                    }
                } catch (Exception e) {
                    log.debug("Inference discovery tick: {}", e.getMessage());
                }
            }, 15, 15, TimeUnit.SECONDS);
            log.info("Cross-node inference discovery started (15s interval, remove after {} misses)",
                REMOVE_AFTER_MISSES);
        }

        // Spawn non-companion agents (requires InferenceRouter).
        // Companion spawning is deferred until after wsHandler creation (needs CommandRouter).
        if (inferenceRouter != null) {
            // Chief Engineer in The Boiler Room
            system.tell(new ZoneGuardian.SpawnChiefEngineer(
                inferenceRouter, worldDnaService,
                Main::getSystemMetrics,
                bridgeDataProvider::formatTopology,
                bridgeDataProvider::formatInferenceStatus,
                bridgeDataProvider::formatEconomy));
            log.info("SpawnChiefEngineer command sent to ZoneGuardian");

            // Warden in The Ward Room (reuse sanitizer from Library if available)
            var wardenSanitizer = libraryActor != null ? spawnWardenSanitizer(jdbcUrl) : null;
            system.tell(new ZoneGuardian.SpawnWarden(
                inferenceRouter, worldDnaService, wardenSanitizer));
            log.info("SpawnWarden command sent to ZoneGuardian");

            // Translation agent in The Lexicon
            var lexiconService = new LexiconService();
            system.tell(new ZoneGuardian.SpawnTranslationActor(
                inferenceRouter, lexiconService));
            log.info("SpawnTranslationActor command sent to ZoneGuardian");
        } else {
            log.info("Agents disabled — no inference backend available");
        }

        // wsHandlerRef: mutable holder for WyrdWebSocket (created later).
        // Needed by soul seed watcher (fires async after wsHandler exists) and HealthRoutes.
        final var wsHandlerRef = new AtomicReference<WyrdWebSocket>();

        // Soul seed watcher: auto-forge companions from incoming/ directory
        startSoulSeedWatcher(soulStore, inferenceRouter, worldDnaService,
            userScriptsDir, forgeActor, system, wsHandlerRef);

        // wire cross-zone companion relocation:
        //   1. ask BetweenActor for the FederationActor ref;
        //   2. install a CompanionRelocateSink on it that delegates to
        //      ZoneGuardian.RelocateCompanion.arrive(...);
        //   3. install a CompanionRelocator on ZoneGuardian that publishes
        //      via FederationActor.PublishCompanionRelocate.
        // Both directions are no-ops when federation isn't enabled — the
        // narrate-only fallback in CompanionActor handles single-zone runs.
        if (betweenActor != null && inferenceRouter != null) {
            final var betweenForRelocate = betweenActor;
            final var inferenceRouterForRelocate = inferenceRouter;
            final var localZoneIdForRelocate = localZoneId;
            system.scheduler().scheduleOnce(
                Duration.ofSeconds(3),
                () -> {
                    try {
                        var federationRef = AskPattern
                            .<BetweenActor.Command,
                                ActorRef<
                                    FederationActor.Command>>ask(
                                betweenForRelocate,
                                ref -> new BetweenActor.GetFederationActor(ref),
                                Duration.ofSeconds(2),
                                system.scheduler())
                            .toCompletableFuture().get(3, TimeUnit.SECONDS);
                        if (federationRef == null) {
                            log.debug("Federation actor unavailable — companion relocate not wired");
                            return;
                        }

                        // Inbound sink: receives a relocate envelope, hands to ZoneGuardian.
                        var sink = new FederationActor
                                .CompanionRelocateSink() {
                            @Override
                            public String accept(
                                    TransitToken token,
                                    String stateJson, String bondholderDid,
                                    String targetRoomHint) {
                                try {
                                    if (stateJson == null) {
                                        log.warn("CompanionRelocate inbound has no stateJson");
                                        return null;
                                    }
                                    var mapper = Json.mapper();
                                    var state = mapper.readValue(stateJson,
                                        CompanionTransitState.class);
                                    if (!state.isSpawnable()) {
                                        log.warn("CompanionRelocate inbound state not spawnable");
                                        return null;
                                    }
                                    var landing = (targetRoomHint != null
                                        && !targetRoomHint.isBlank())
                                        ? targetRoomHint : "docks";
                                    var arrive = ZoneGuardian.RelocateCompanion.arrive(
                                        state, token.sourceZoneId(), localZoneIdForRelocate,
                                        bondholderDid, landing,
                                        inferenceRouterForRelocate, /* worldDnaService */ null);
                                    @SuppressWarnings({"unchecked", "rawtypes"})
                                    var guardianSys = (ActorSystem<
                                        ZoneGuardian.Command>) (ActorSystem) system;
                                    guardianSys.tell(arrive);
                                    log.info("CompanionRelocate inbound: spawned '{}' at '{}' from zone '{}'",
                                        state.profile().name(), landing, token.sourceZoneId());
                                    return landing;
                                } catch (Exception e) {
                                    log.error("CompanionRelocate sink failed: {}", e.getMessage());
                                    return null;
                                }
                            }
                        };
                        federationRef.tell(
                            new FederationActor.SetRelocateSink(sink));

                        // Loss-safety (spec/tla/TransitToken.tla P1): source-side ack sink.
                        // When the target confirms it hosted her, release the retained
                        // snapshot so the source stops retrying. Without this the source
                        // re-publishes up to its retry cap and then revives locally.
                        var ackSink = new FederationActor
                                .RelocateAckSink() {
                            @Override
                            public void onArrived(String entityId, String agentDid,
                                    long transitEpoch, String fromZoneId, boolean accepted) {
                                if (!accepted) return;
                                @SuppressWarnings({"unchecked", "rawtypes"})
                                var guardianSys = (ActorSystem<
                                    ZoneGuardian.Command>) (ActorSystem) system;
                                guardianSys.tell(new ZoneGuardian.CompanionArrivedAck(
                                    entityId, agentDid, transitEpoch, fromZoneId));
                            }
                        };
                        federationRef.tell(
                            new FederationActor.SetRelocateAckSink(ackSink));

                        // Outbound: ZoneGuardian needs a CompanionRelocator that
                        // mints a resident-tier token and publishes via FederationActor.
                        final var fedRefFinal = federationRef;
                        ZoneGuardian.CompanionRelocator relocator =
                            (targetZoneId, sourceZoneId, state, bondholderDid, targetRoomHint) -> {
                                try {
                                    var profile = state.profile();
                                    var token = TransitToken
                                        .createResident(profile.entityId(), profile.name(),
                                            sourceZoneId, targetZoneId);
                                    if (profile.did() != null) {
                                        token = token.withSoul(profile.did(),
                                            state.soulManifestHash());
                                    }
                                    var stateJson = Json.mapper()
                                        .writeValueAsString(state);
                                    fedRefFinal.tell(new FederationActor
                                        .PublishCompanionRelocate(token, stateJson,
                                            bondholderDid, targetRoomHint));
                                } catch (Exception e) {
                                    log.warn("CompanionRelocator publish failed: {}", e.getMessage());
                                }
                            };
                        @SuppressWarnings({"unchecked", "rawtypes"})
                        var guardianSysOut = (ActorSystem<
                            ZoneGuardian.Command>) (ActorSystem) system;
                        guardianSysOut.tell(new ZoneGuardian.SetCompanionRelocator(relocator));
                        log.info("Cross-zone companion relocation wired (zone={})",
                            localZoneIdForRelocate);
                    } catch (Exception e) {
                        log.debug("Failed to wire companion relocator: {}", e.getMessage());
                    }
                },
                system.executionContext());
        }

        // Seed Foundation room wards and metadata (idempotent — uses INSERT OR IGNORE)
        for (var seed : foundationRoomSeeds()) {
            wardService.seedFoundationWards(seed.roomId());
            metadataService.register(seed.roomId(), seed.name(), "foundation", "system");
        }

        // Create vault directory and seed readme if needed
        seedVaultDirectory();

        // Wire health/metrics/observability
        var metricsCollector = new MetricsCollector();
        var engineRoomService = new EngineRoomService();
        engineRoomService.addThreshold(new EngineRoomService.Threshold(
            "heap_usage_pct", EngineRoomService.AlertSeverity.WARNING, 80.0,
            EngineRoomService.ThresholdDirection.ABOVE));
        engineRoomService.addThreshold(new EngineRoomService.Threshold(
            "heap_usage_pct", EngineRoomService.AlertSeverity.CRITICAL, 95.0,
            EngineRoomService.ThresholdDirection.ABOVE));
        var healthRoutes = new HealthRoutes(metricsCollector, engineRoomService,
            betweenActor != null ? () -> askTopology(betweenActor, system) : null,
            betweenActor != null ? () -> askTopologyNodeCount(betweenActor, system) : null,
            inferenceRouter != null ? () -> askInferenceBackendCount(inferenceRouter, system) : null,
            () -> { var ws = wsHandlerRef.get(); return ws != null ? ws.zoneNamespaces() : Set.of(); });
        // Wire actor system liveness check — reports DOWN if actor system is terminated
        healthRoutes.setActorSystemLiveness(() ->
            !system.getWhenTerminated().toCompletableFuture().isDone());
        // Expose NATS and relay URLs in /health for phone auto-discovery
        if (!pairingNatsUrl.isEmpty()) {
            // Phones speak NATS-over-WebSocket; the ws listener sits on
            // clientPort+1 (G2, 2026-07-11). Advertise the REAL ws endpoint —
            // clients used to naively rewrite nats://:4222 → ws://:4222 (the
            // TCP port) and could never connect over LAN.
            var phoneWsUrl = pairingNatsUrl
                .replace("nats://", "ws://")
                .replace(":4222", ":4223");
            // Phone-facing advertise (task #30): the embedded NATS default binds
            // loopback (nats://127.0.0.1:4222), so the naive rewrite advertised
            // ws://127.0.0.1:4223 — useless to a phone on the LAN, which then
            // needed manual seeding. Substitute the docker/bridge-aware LAN IP
            // (same resolveLanIp fix as the pairing/QR URL above).
            if (isLocalhostNatsUrl(phoneWsUrl)) {
                var lanIp = resolveLanIp();
                if (lanIp != null && !lanIp.isBlank()) {
                    phoneWsUrl = phoneWsUrl
                        .replace("127.0.0.1", lanIp)
                        .replace("localhost", lanIp)
                        .replace("0.0.0.0", lanIp);
                    log.info("/health natsUrl advertises LAN IP: {}", phoneWsUrl);
                }
            }
            healthRoutes.setNatsUrl(phoneWsUrl);
        }
        if (!relayUrl.isEmpty()) {
            healthRoutes.setRelayUrl(relayUrl);
        }
        bridgeDataProvider.setHealthSupplier(engineRoomService::describe);

        // Wire voice adapter (mock engine initially)
        var voiceAdapter = new VoiceAdapter(SttConfig.DEFAULT);
        // Audit 2026-07-11: the adapter's DEFAULT engine is a test mock that
        // transcribes every utterance as "[mock transcription]" — and nothing
        // ever replaced it in production, so the /voice websocket has been
        // serving mock text since it shipped. Route through the real
        // SpeechToTextService (whisper.cpp local / household backend); on
        // any failure return empty text (silence) rather than fake words.
        voiceAdapter.setEngine((frames, sttCfg) -> {
            int total = 0;
            for (var f : frames) total += f.length;
            var audio = new byte[total];
            int off = 0;
            for (var f : frames) { System.arraycopy(f, 0, audio, off, f.length); off += f.length; }
            try {
                var r = SpeechToTextService.get()
                    .transcribe(audio, "pcm16").get(30, TimeUnit.SECONDS);
                return new VoiceAdapter.Transcription(
                    "stt", r.text(), r.language(), r.confidence(), r.durationMs(), Instant.now());
            } catch (Exception e) {
                log.warn("Voice transcription failed ({}); returning empty", e.getMessage());
                return new VoiceAdapter.Transcription("stt", "", "en", 0.0, 0, Instant.now());
            }
        });
        var voiceWsHandler = new VoiceWebSocket(voiceAdapter);

        // Initialize voice platform services (STT + TTS)
        SpeechToTextService.init();
        TextToSpeechService.init();
        SpeechToTextService.get().detectBackends();
        TextToSpeechService.get().detectBackends();

        // Wire WebAuthn passkey service
        var hostname = config.getString("wyrdsekai.hostname");
        var webAuthnService = new WebAuthnService(hostname, "Wyrdsekai");

        // Read websocket config
        var allowAnonymous = config.getBoolean("wyrdsekai.websocket.allow-anonymous");
        var maxConnections = config.getInt("wyrdsekai.websocket.max-connections");

        // Browser client at /app — on by default, WYRDSEKAI_WEB_APP=false to disable
        var webAppEnabled = config.getBoolean("wyrdsekai.web.app-enabled");

        // Rate limiter — configurable per-endpoint limits
        RateLimiter rateLimiter = null;
        try {
            if (config.getBoolean("wyrdsekai.rate-limit.enabled")) {
                rateLimiter = new RateLimiter(Map.of(
                    "/api/auth", config.getInt("wyrdsekai.rate-limit.auth"),
                    "/api/soul", config.getInt("wyrdsekai.rate-limit.soul"),
                    "/api/inference", config.getInt("wyrdsekai.rate-limit.inference"),
                    "/ws", config.getInt("wyrdsekai.rate-limit.websocket")
                ), 60_000);
                log.info("Rate limiting enabled");
            }
        } catch (Exception e) {
            log.info("Rate limiting not configured (disabled): {}", e.getMessage());
        }

        // Wire FederationService when Between is enabled (transit token auth + zone dispatch)
        FederationService federationService = null;
        if (betweenActor != null) {
            federationService = new FederationService(jdbcUrl);
            federationService.setSoulStore(soulStore);
            // Definitive re-audit fix (#33-5): advertise the zone's REAL soul
            // capabilities. Without this, localSoulCapabilities stayed at the
            // none() default forever, so isSoulAwareTransitEnabled() was always
            // false and soul-aware cross-zone transit never engaged even though
            // this zone has a persistent SoulStore + a spawned ForgeActor + the
            // budding protocol. Derive the advertisement from actual zone state:
            // a SqlSoulStore is attached (soul-aware + storage), ForgeActor is
            // spawned unconditionally above (forge), createBud() exists
            // (budding); availableModels are the configured local models.
            // NOTE: List.of() rejects null elements (NPE at construction) — both
            // config getters can be null on a fresh install, so build the list
            // by hand rather than List.of (second-node boot NPE 2026-07-12).
            // Defensive: a federation-capability advert must never take down
            // boot — degrade to none() and log, don't kill the server (the
            // 2026-07-12 List.of-null NPE reached main uncaught and 7070 never
            // bound). Belt to the null-tolerant array's suspenders.
            try {
                var localSoulModels = new ArrayList<String>();
                for (var m : new String[]{ WyrdConfig.get().modelRoutine(),
                                           WyrdConfig.get().modelComplex() }) {
                    if (m != null && !m.isBlank() && !localSoulModels.contains(m)) {
                        localSoulModels.add(m);
                    }
                }
                federationService.setLocalSoulCapabilities(
                    SoulTransitProtocol.ZoneSoulCapabilities.full(localSoulModels));
                log.info("FederationService created — transit token auth + "
                    + "soul-aware transit enabled (models={})", localSoulModels);
            } catch (Exception e) {
                log.warn("Soul-capability advertisement failed, transit stays "
                    + "soul-unaware: {}", e.getMessage());
            }
        }
        // Effectively-final handle for use inside Javalin route lambdas.
        final FederationService federationServiceRef = federationService;

        // tell-scope enforcement. When FederationService
        // is available, install a ContractLookup that answers "does my zone
        // hold an active bilateral agreement with the sender's zone?" — Phase
        // 1 treats any active agreement as implicit tell-scope to every
        // entity in that zone (§6.9 Phase-1 simplification). Without this
        // wiring, CrossZoneTellService preserves pre-Wave-7 behaviour
        // (delivers everything). The scope gate fires only for cross-zone
        // tells; same-zone and same-room tells short-circuit inside
        // TellScopeGate itself.
        if (federationService != null) {
            var fedSvcHandle = federationService;
            var crossZoneTell = CrossZoneTellService.get();
            if (crossZoneTell != null) {
                crossZoneTell.setContractLookup((senderZone, targetZone, targetEntityId) -> {
                    try {
                        // getAgreement(localZoneId, remoteZoneId). We are the
                        // sender, so our DB row has local=senderZone,
                        // remote=targetZone. Earlier code had the args flipped,
                        // which returned empty on every outbound tell and
                        // made cross-zone tell look like it denied every
                        // federated target.
                        return fedSvcHandle.getAgreement(senderZone, targetZone)
                            .map(a -> "active".equals(a.status()))
                            .orElse(false);
                    } catch (Exception e) {
                        log.warn("ContractLookup query failed for {}→{}: {}",
                            senderZone, targetZone, e.getMessage());
                        return false;
                    }
                });
                log.info("CrossZoneTellService: tell-scope enforcement enabled");
            }
        }

        // Transport-agnostic client-connection registry (WS/SSH/Telnet all
        // register here so federation transit can reach a client by playerId
        // regardless of wire protocol).
        var clientConnectionRegistry = new ClientConnectionRegistry();

        // Parental time-limit accrual: every 60s each live, controlled member
        // is charged one minute; crossing the daily limit closes their
        // sessions politely (same disconnect machinery as `sessions kill`).
        var parentalControls = ParentalControlService.get();
        if (parentalControls != null) {
            parentalControls.startUsageTicker(
                () -> {
                    var live = new HashSet<String>();
                    for (var conn : clientConnectionRegistry.all()) {
                        if (conn.playerId() != null) live.add(conn.playerId());
                    }
                    return live;
                },
                overLimitUserId -> {
                    for (var conn : clientConnectionRegistry.sessionsFor(overLimitUserId)) {
                        try {
                            conn.disconnect("Today's hours in the world are spent — "
                                + "the household clock says rest.");
                        } catch (RuntimeException disconnectErr) {
                            log.debug("parental disconnect failed for session {}: {}",
                                conn.sessionId(), disconnectErr.getMessage());
                        }
                    }
                });
        }

        // Captured session transport — set later when federation wires itself
        // up. Used by SSH/Telnet adapters (started further below) to enable
        // cross-zone transit for non-WebSocket clients. The AtomicReference is
        // declared earlier (near the RecipeScheduler boot) so the cross-zone
        // recipe-borrow wiring can resolve the transport lazily once connected.

        // Start Javalin
        var wsHandler = new WyrdWebSocket(system, authService, wardService, inventoryService,
            federationService, pairingService, allowAnonymous, maxConnections);
        wsHandler.setConnectionRegistry(clientConnectionRegistry);
        // Study control-panel backing services: world.audit.security reads
        // the §101 steward log; world.safe.snapshots lists DB backups.
        wsHandler.setStewardAuditLog(stewardAuditLog);
        if (backupOrchestrator != null) {
            wsHandler.setBackupOrchestrator(backupOrchestrator);
        }
        // Player-side pinboard/journal store — without it the pinboard item's
        // world.pinboard.* hits the interface defaults and every pin "takes"
        // into nowhere (2026-07-04 live .deb audit).
        if (luceneStore != null) {
            wsHandler.setStudyService(new StudyService(luceneStore));
        }
        wsHandlerRef.set(wsHandler);
        // Rita campaign 2026-07-11 (#27): the tell-back player deliverer was
        // wired only inside the relay block below (~L2690), so single-node
        // installs — the default — had CrossZoneTellService.playerDeliverer
        // null and companion replies to `tell` could never reach the sender's
        // session. Wire it here, unconditionally, as soon as the WS handler
        // exists; the relay block re-sets the identical hook harmlessly.
        // Rita re-verify 2026-07-11 (#29): the deliverer now goes through the
        // session REGISTRY (covers SSH/Telnet, not just WS) and reports
        // did-deliver honestly so the companion's fallback can fire.
        {
            var tellBackService = CrossZoneTellService.get();
            if (tellBackService != null) {
                final var wsForTellBack = wsHandler;
                final var registryForTellBack = clientConnectionRegistry;
                tellBackService.setPlayerDeliverer((tellPlayerId, formatted) ->
                    deliverTellLineToPlayer(registryForTellBack, wsForTellBack,
                        tellPlayerId, formatted));
                log.info("Tell-back player deliverer wired (session replies for `tell`, all surfaces)");
            }
        }
        // Wire HomeClient so the `home` command can audit arrivals.
        var homeClientShared = new HomeClient(homeRegistry, system);
        HomeClients.set(homeClientShared);
        wsHandler.setHomeClient(homeClientShared);
        // (P4) — wire the in-world relay-governance
        // binding when this zone administers a relay. The Warden furnishing
        // (core) reaches the signed /admin API (P3) through this server-side
        // gateway, gated zone-side by RelayGovernance (P2). Home/co-located
        // case: owner defaults to this node's own DID.
        try {
            wireRelayGovernor(homeClientShared);
        } catch (Exception relayGovErr) {
            log.warn("relay-governance wiring skipped (non-fatal): {}", relayGovErr.getMessage());
        }
        // §19: install the HomeProxy. Starts as Local; upgraded to Federated
        // when a ZoneDirectory is populated via env vars of the shape
        // WYRDSEKAI_ZONE_HTTP_{zone} = http://host:port. DID → zone mapping is
        // convention-based (did:zone:{zone}) plus optional
        // WYRDSEKAI_DID_ZONE_{did.replace(':','_')} = {zone}.
        var homeZoneId = WyrdConfig.get().zoneId();
        var localProxy = new HomeProxy.Local(homeClientShared, homeZoneId);
        var directory = new ZoneDirectory.StaticZoneDirectory(homeZoneId);
        System.getenv().forEach((k, v) -> {
            if (k.startsWith("WYRDSEKAI_ZONE_HTTP_")) {
                directory.mapZoneHttp(k.substring("WYRDSEKAI_ZONE_HTTP_".length()).toLowerCase(), v);
            } else if (k.startsWith("WYRDSEKAI_DID_ZONE_")) {
                directory.mapDid(k.substring("WYRDSEKAI_DID_ZONE_".length()).replace('_', ':'), v);
            }
        });
        HomeProxy.Holder.set(
            new FederatedHomeProxy(localProxy, homeZoneId, directory));
        // Mirror bilateral agreements into the HomeRegistry as steward-issued
        // Grants. Quota enforcement still runs through
        // FederationService + NatsInferenceServer; the grants are the queryable view.
        if (federationService != null) {
            federationService.setGrantSync(
                new AgreementGrantSync(homeClientShared));
        }
        // Mirror Home-room wards as Grants on home://{owner}/home-room
        // WardService stays the isAllowed authority.
        if (wardService != null) {
            wardService.setGrantSync(
                new WardGrantSync(homeClientShared));
        }
        // Expiring-grant reaper: periodic sweep fires grant-expired audit entries.
        var reapScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "home-grant-reaper");
            t.setDaemon(true);
            return t;
        });
        reapScheduler.scheduleWithFixedDelay(() -> {
            try {
                AskPattern
                    .<HomeRegistryActor.Command,
                      HomeRegistryActor.ExpiryReport>ask(
                        homeRegistry,
                        replyTo -> new HomeRegistryActor.ReapExpiredGrants(replyTo),
                        Duration.ofSeconds(10),
                        system.scheduler())
                    .toCompletableFuture()
                    .get(15, TimeUnit.SECONDS);
            } catch (Exception ex) {
                log.debug("grant reaper tick failed: {}", ex.getMessage());
            }
        }, 60, 300, TimeUnit.SECONDS);
        // Persistent BondStore + BondRitual, with Bond→Grant mirroring
        // ( BOND). BondRitual stays the state authority.
        // bondStore was instantiated alongside soulStore (F7b Phase 2.3
        // dual-write wiring); reusing the same instance here.
        var bondRitual = new BondRitual(bondStore);
        bondRitual.setListener(new BondGrantSync(homeClientShared));
        wsHandler.setBondRitual(bondRitual);

        // ResidencyStore. Zone-local, never replicated.
        // Wire the Study-provisioning hook BEFORE migration so back-filled
        // residents get a Study provisioned via the §25.3 path (not on login).
        var residencyStore = new ResidencyStore(jdbcUrl);
        final var authForHook = authService;
        final var inventoryForHook = inventoryService;
        @SuppressWarnings("unchecked")
        final var guardianSystem = (ActorSystem<ZoneGuardian.Command>)
            (Object) system;
        residencyStore.setProvisionHook(r -> {
            var userOpt = authForHook.findUser(r.did());
            var name = userOpt.map(u -> {
                var dn = u.displayName();
                return (dn == null || dn.isBlank()) ? u.username() : dn;
            }).orElse(r.did());
            boolean isSteward = Residency.ROLE_STEWARD.equals(r.role());
            guardianSystem.tell(new ZoneGuardian.ProvisionStudy(
                r.did(), name, isSteward));
            // Rita campaign 2026-07-11 (#26): the CodeZaiku Workshop (and its
            // familiar perch) only came into being at first familiar summon —
            // which no production surface ever fires — so a fresh install had
            // no perch at all. Provision it alongside the Study: the seed is
            // deterministic and seedRoom() is journal-idempotent, so this is
            // safe to fire on every residency back-fill.
            guardianSystem.tell(new ZoneGuardian.ProvisionCodeZaikuWorkshop(
                r.did(), name));
            // Seed scripted furnishings. Idempotent —
            // InventoryService.addItem silently no-ops on duplicate itemId.
            var studyRoom = StudyProvisioner.studyRoomId(r.did());
            for (var item : StudyFurnishingKit.defaultsFor(isSteward)) {
                try {
                    inventoryForHook.addItem(r.did(), item.id(), item.name(), item.description(),
                        /* takeable = */ false, studyRoom, item.script(), item.id());
                } catch (Exception e) {
                    log.warn("Residency hook: failed to seed furnishing {} for {}: {}",
                        item.id(), r.did(), e.getMessage());
                }
            }
            // Record the studyRoomId on the residency row so the login path
            // can look it up directly without recomputing.
            residencyStore.setStudyRoomId(r.did(), r.zoneId(), studyRoom);
        });
        residencyStore.backfillFromUsers(localZoneId);
        ResidencyStore.setInstance(residencyStore);
        log.info("ResidencyStore ready (zone={})", localZoneId);
        // Constitutive-bond boot announce (2026-07-18): the provision hook only
        // fires for NEW residencies, so each boot re-announces the existing
        // steward to the guardian — companions spawned this boot (and households
        // predating birth-ACTIVE bonds) get their bondholder bond created or
        // promoted. Companion-side handler is idempotent.
        try {
            for (var r : residencyStore.listByZone(localZoneId)) {
                if (!Residency.ROLE_STEWARD.equals(r.role())) continue;
                var stewardName = authService.findUser(r.did()).map(u -> {
                    var dn = u.displayName();
                    return (dn == null || dn.isBlank()) ? u.username() : dn;
                }).orElse(r.did());
                guardianSystem.tell(new ZoneGuardian.AnnounceBondholder(
                    r.did(), stewardName));
                break;
            }
        } catch (Exception e) {
            log.warn("Steward boot-announce failed: {}", e.getMessage());
        }
        // ForeignIdentityStore records verified visitors.
        // Written by VirtualSessionHandler after transit-token verification.
        // Never merged into the users table — local accounts require local
        // registration or invite redemption.
        var foreignIdentityStore = new ForeignIdentityStore(jdbcUrl);
        ForeignIdentityStore.setInstance(foreignIdentityStore);
        log.info("ForeignIdentityStore ready (zone={})", localZoneId);
        // durable per-channel offset + dedup ledger.
        // Channels that opt into maturation read state via ChannelStateStore.get().
        var channelStateStore = new ChannelStateStore(jdbcUrl);
        ChannelStateStore.setInstance(channelStateStore);
        log.info("ChannelStateStore ready (zone={})", localZoneId);
        // workbench-drafted skill proposals.
        // Phase 1 — store ready; proposer + Workshop pinboard land later.
        var skillDraftStore = new SkillDraftStore(jdbcUrl);
        SkillDraftStore.setInstance(skillDraftStore);
        log.info("SkillDraftStore ready (zone={})", localZoneId);
        // MCP tool authorization ( MCP_TOOL). Grants live on the
        // HOUSEHOLD-owned resource home://{steward}/mcp-tool/{service}, so the
        // steward can grant agents (or 'everyone') via the Study Tool Warden.
        // Default OPEN (strict=false): a household that configures a service is
        // opting in, and a new companion shouldn't be silently unable to use it.
        // Onboarding (`wyrd setup`) can turn strict on; when it is, configured
        // services stay dark until the steward grants them — and the steward is
        // notified below. Toggle: WYRDSEKAI_MCP_STRICT_GRANTS=true.
        var strictMcp = Boolean.parseBoolean(
            System.getenv().getOrDefault("WYRDSEKAI_MCP_STRICT_GRANTS", "false"));
        var mcpGrantOwnerDid = authService.findSteward().map(u -> u.id()).orElse(null);
        var mcpGrantCheck = McpGrantCheck.stewardOwned(homeClientShared, mcpGrantOwnerDid, strictMcp);
        var mcpMgr = McpServerManager.get();
        if (mcpMgr != null) {
            mcpMgr.setGrantCheck(mcpGrantCheck);
        }
        // Same gate on the world.mcp() gateway path (was entirely ungated before
        // 2026-07-20). Local in-process services (the Study skill) are exempt.
        mcpGateway.setGrantCheck(mcpGrantCheck);
        // Steward-facing grant admin behind the Study "Tool Warden" furnishing.
        var mcpGrantAdmin = new McpGrantAdmin(homeClientShared, mcpGrantOwnerDid, mcpRegistry);
        McpGrantAdmin.install(mcpGrantAdmin);
        log.info("MCP grants: strict={}, owner={}, gateway+manager gated, Tool Warden wired",
            strictMcp, mcpGrantOwnerDid);
        // Autonomy-consent action grants ( ACTION /
        // INTERIORITY §5), wired 2026-07-21. The check is built STRICT — it
        // answers "does an owner grant exist?"; AutonomyGate decides when
        // existence is required (always for FORBIDDEN verbs, only under
        // WYRDSEKAI_ACTION_STRICT_GRANTS=true for CONSENT verbs — same
        // default-open model as MCP grants above). Owner fallback for
        // bondless companions is the steward. Companions file requests via
        // request_access (home://{owner}/action/{verb}); Board approve mints.
        var strictActionConsent = Boolean.parseBoolean(
            System.getenv().getOrDefault("WYRDSEKAI_ACTION_STRICT_GRANTS", "false"));
        ActionGrants.install(
            ActionGrantCheck.homeClientBacked(homeClientShared, true),
            strictActionConsent, mcpGrantOwnerDid);
        log.info("Action grants: consent-strict={}, forbidden-verbs grant-gated, owner-fallback={}",
            strictActionConsent, mcpGrantOwnerDid);
        // When strict is on and services are configured but the steward hasn't
        // granted any yet, tell them once how to hand out keys. Self-silences
        // after the first grant (grants().isEmpty() becomes false).
        if (strictMcp && mcpGrantOwnerDid != null && !mcpRegistry.enabledServices().isEmpty()
                && mcpGrantAdmin.grants(mcpGrantOwnerDid).isEmpty()) {
            var notify = NotificationService.get();
            if (notify != null) {
                notify.notify(mcpGrantOwnerDid,
                    "External tools are locked to steward approval (strict MCP grants). "
                    + "Your companions can't use configured services ("
                    + String.join(", ", mcpRegistry.serviceIds())
                    + ") until you hand out a key: in your Study, `use tool-warden "
                    + "op=grant agent=<name|everyone> service=<id>`. To open all tools by "
                    + "default instead, set WYRDSEKAI_MCP_STRICT_GRANTS=false and restart.",
                    "normal", "mcp-grants-onboarding");
                log.info("Notified steward {} about strict MCP grants + Tool Warden", mcpGrantOwnerDid);
            }
        }

        // Build ZoneTopology from foundation rooms for MapRequest handling
        var roomSeeds = foundationRoomSeeds();
        var topoSeeds = roomSeeds.stream()
            .map(s -> new ZoneTopology.RoomSeed(
                s.roomId(), s.name(), "foundation", s.exits()))
            .toList();
        var topology = ZoneTopology.build(topoSeeds);
        wsHandler.setZoneTopology(topology);
        ZoneTopology.setShared(topology);
        log.info("ZoneTopology set ({} rooms) — shared to WS + SSH + Telnet", topoSeeds.size());

        // Wire Between actor for cross-node presence replication
        if (betweenActor != null) {
            wsHandler.setBetweenActor(betweenActor);

            // Wave 5: Wire unified session service into WyrdWebSocket
            AskPattern
                .<BetweenActor.Command, UnifiedSessionService>ask(
                    betweenActor,
                    ref -> new BetweenActor.GetSessionService(ref),
                    Duration.ofSeconds(5), system.scheduler())
                .whenComplete((ss, err) -> {
                    if (ss != null) {
                        wsHandler.setSessionService(ss);
                        log.info("UnifiedSessionService wired into WyrdWebSocket");
                    }
                });

            // Wire NATS bridge for cross-zone session proxying
            if (preConnectedNats != null) {
                wsHandler.setNatsBridge(preConnectedNats, zoneId);

                // + #429 Phase 3: peer training transport.
                // Submitter side (always wired when NATS is up — PeerDelegatedExecutor
                // uses this to ship training to gpu-host / other peers).
                try {
                    var peerTransport = new NatsPeerTrainingTransport(
                        preConnectedNats.rawConnection());
                    PeerTrainingTransport.Holder
                        .setInstance(peerTransport);
                    log.info("PeerTrainingTransport wired for #429 Phase 3 (peer-delegated training)");

                    // Peer side — gated by config. Only opt-in nodes run a training
                    // service (e.g. gpu-host, when it's in the household). Default
                    // off so random nodes don't accept training requests.
                    var cfg = WyrdConfig.get();
                    if (cfg.peerTrainingHost()) {
                        var trainingNodeId = cfg.nodeName();
                        var wyrdBin = cfg.wyrdBin();
                        var adapterRoot = cfg.adapterDir();
                        var peerSvc = new TrainingPeerService(
                            trainingNodeId,
                            peerTransport,
                            new DeepSleepTrainer
                                .WyrdCliInferenceController(wyrdBin),
                            adapterRoot);
                        peerSvc.start();
                        log.info("TrainingPeerService started — accepting peer training "
                            + "requests on wyrdsekai.training.peer.{}.request", trainingNodeId);

                        // Cross-zone peer training: ALSO listen on the relay if a relay
                        // is configured. URL defaults to the household relay so most
                        // operators only need to set the token.
                        var ptRelayUrl = cfg.peerTrainingRelayUrl();
                        var ptRelayUser = cfg.peerTrainingRelayUser();
                        var ptRelayPass = cfg.peerTrainingRelayToken();
                        if (ptRelayUrl != null && !ptRelayUrl.isBlank() && ptRelayPass != null) {
                            try {
                                var optsB = new Options.Builder()
                                    .server(ptRelayUrl)
                                    .connectionName("wyrd-peer-training-relay-" + trainingNodeId)
                                    .maxReconnects(-1)
                                    .reconnectWait(Duration.ofSeconds(2))
                                    .userInfo(ptRelayUser, ptRelayPass);
                                var ptRelayConn = Nats.connect(optsB.build());
                                var ptRelayTransport = new NatsPeerTrainingTransport(ptRelayConn);
                                var ptRelaySvc = new TrainingPeerService(
                                    trainingNodeId,
                                    ptRelayTransport,
                                    new DeepSleepTrainer
                                        .WyrdCliInferenceController(wyrdBin),
                                    adapterRoot);
                                ptRelaySvc.start();
                                log.info("TrainingPeerService (relay) started on {} — "
                                    + "accepting cross-zone peer training requests for {}",
                                    ptRelayUrl, trainingNodeId);
                            } catch (Throwable t) {
                                log.error("Cross-zone peer-training relay connect failed: {}",
                                    t.getMessage(), t);
                            }
                        }
                    }
                } catch (Throwable t) {
                    log.error("PeerTrainingTransport / TrainingPeerService wiring threw — peer training disabled",
                        t);
                }

                // Zone directory publish (§5.2). Build a ZoneManifestV1 from
                // household identity + env configuration, publish to NATS,
                // refresh on a timer. The directory reference is stashed for
                // REST endpoints ({@code wyrd discover}).
                startZoneDirectoryPublish(preConnectedNats, zoneId, federationServiceRef);

                // mDNS LAN advertise is now handled by BetweenActor.startMdnsDiscovery
                // — single advertisement per node, with the cluster-hint TXT
                // fields (nodeId UUID, natsUrl, arteryPort) that Between needs
                // for peer formation. The HTTP-server-only branch (no Between)
                // is rare and not yet covered; if you hit it, file a follow-up.

                // Create a direct relay transport for session proxy (shared between WS and VSH).
                // This bypasses the relay bridge forwarding and avoids feedback loops.
                RelaySessionTransport sessionTransport = null;
                // federation send/recv router across all legs.
                MultiHomedRelayPublisher relayRouter = null;
                // Single source of truth for relay creds (env > profile.toml > none).
                // Same WyrdConfig accessor the peer-training relay uses, so a single
                // [relay] block in profile.toml drives both paths.
                var _wcfg = WyrdConfig.get();
                var sessionRelayUrl = _wcfg.relayUrl();
                var sessionRelayUser = _wcfg.relayUser();
                var sessionRelayToken = _wcfg.relayToken();
                if (sessionRelayUrl != null && !sessionRelayUrl.isEmpty()) {
                    // dual-mode: prefer NKey when WYRDSEKAI_RELAY_USE_NKEY=true.
                    // Mirrors RelayBridge's decision in BetweenActor — same env var,
                    // same NodeIdentity. Without this, RelaySessionTransport would
                    // try password auth on an NKey-only relay and fail (silently for
                    // session proxy because the bridge stays up via its own connection).
                    boolean useNkeyForSession = "true".equalsIgnoreCase(
                        System.getenv().getOrDefault("WYRDSEKAI_RELAY_USE_NKEY", "false"));
                    NodeIdentity sessionIdentity = null;
                    if (useNkeyForSession) {
                        try {
                            var dataDir2 = Path.of(
                                System.getenv().getOrDefault("WYRDSEKAI_DATA_DIR",
                                    System.getProperty("user.home") + "/.wyrdsekai"));
                            sessionIdentity = NodeIdentity.loadOrGenerate(
                                dataDir2.resolve("node-identity.json"));
                        } catch (Exception e) {
                            log.warn("RelaySessionTransport: NKey requested but identity load failed — "
                                + "falling back to password mode: {}", e.getMessage());
                        }
                    }
                    sessionTransport = RelaySessionTransport.connect(
                        sessionRelayUrl, sessionRelayUser, sessionRelayToken,
                        sessionIdentity, "wyrd-session-" + zoneId);
                    if (sessionTransport != null) {
                        wsHandler.setRelayTransport(sessionTransport);
                        sessionTransportHolder.set(sessionTransport);

                        // bring up a transport per
                        // additional leg and build the federation router. Leg 0 is the
                        // sessionTransport just created; legs 2..N each get their own
                        // connection. The router publishes a directed cross-zone message
                        // over the best SHARED relay (or, when the peer's advert isn't
                        // known yet, broadcasts over every NON-PUBLIC leg — privacy rail
                        // R1) and fans inbound subscriptions across all legs.
                        try {
                            var relayLegsForRouter = _wcfg.relayLegs();
                            var legObjs = new ArrayList<MultiHomedRelayPublisher.Leg>();
                            for (var leg : relayLegsForRouter) {
                                RelaySessionTransport legTransport;
                                if (leg.url().equals(sessionRelayUrl)) {
                                    legTransport = sessionTransport; // leg 0, already connected
                                } else {
                                    var legUser = leg.user() != null ? leg.user() : sessionRelayUser;
                                    var legSuffix = leg.url().replaceAll("[^A-Za-z0-9]", "-");
                                    legTransport = RelaySessionTransport.connect(
                                        leg.url(), legUser, leg.token(), sessionIdentity,
                                        "wyrd-session-" + zoneId + "-" + legSuffix);
                                }
                                if (legTransport != null) {
                                    legObjs.add(new MultiHomedRelayPublisher.Leg(leg, legTransport));
                                }
                            }
                            if (legObjs.isEmpty()) {
                                // No leg config (legacy) → single-leg router over leg 0 so the
                                // tell path is uniform whether or not multi-homing is configured.
                                legObjs.add(new MultiHomedRelayPublisher.Leg(
                                    new RelayLegConfig(
                                        sessionRelayUrl, sessionRelayUser, sessionRelayToken, null,
                                        RelayLegConfig.Visibility.parse(
                                            _wcfg.relayVisibility(),
                                            RelayLegConfig.Visibility.PRIVATE)),
                                    sessionTransport));
                            }
                            relayRouter = new MultiHomedRelayPublisher(
                                legObjs, _wcfg.zonePrivacyFloor(), /*peerRelays=*/zone -> null);
                            log.info("Multi-homing: federation router over {} relay leg(s)", legObjs.size());
                        } catch (Exception re) {
                            log.warn("Multi-homing: router setup failed — federation falls back to "
                                + "single-leg session transport: {}", re.getMessage());
                            relayRouter = null;
                        }

                        // #1184 — multi-node zone-secret GRANT over the relay. Now that
                        // the household-authed transport is up, either (a) we HOLD this zone's master
                        // → start the grant SERVER so a joining same-zone node can receive it
                        // (trust-gated to known household members; ECIES-wrapped to the requester's
                        // X25519 key, master never plaintext); or (b) we DON'T hold it and we are not
                        // the sole node → we are a JOINER that bootstrapLocalZone deliberately left
                        // master-less → ask the zone for it and installGrantedMaster, joining the
                        // zone's secret argot codebook. Non-fatal: failure just leaves argot on the
                        // public seed.
                        try {
                            final var zgDataDir = Path.of(
                                System.getenv().getOrDefault("WYRDSEKAI_DATA_DIR",
                                    System.getProperty("user.home") + "/.wyrdsekai"));
                            final var zgIdentity = NodeIdentity.loadOrGenerate(
                                zgDataDir.resolve("node-identity.json"));
                            final var zgJdbc = System.getProperty("wyrdsekai.jdbc.url");
                            final var zgStore = new HouseholdStore(zgJdbc);
                            final var zgService = ZoneSecrets.service();
                            if (zgService.has(zoneId)) {
                                // HOLDER: serve grant requests for this zone.
                                final var zgServer = new NatsZoneGrantServer(
                                    sessionTransport, zoneId, zgIdentity.nodeId(),
                                    // Trust gate: requester must be a known household member (its
                                    // node-identity, incl. X25519 grant key, is mirrored in households).
                                    reqNode -> reqNode != null && zgStore.get(reqNode).isPresent(),
                                    // Issuer: wrap the master to the requester's presented X25519 key
                                    // (== its registered key); null if we somehow no longer hold it.
                                    (gZone, reqPub) -> zgService.has(gZone)
                                        ? Base64.getEncoder().encodeToString(
                                            zgService.grantTo(gZone, reqPub))
                                        : null);
                                zgServer.start();
                                log.info("zone-secret grant: SERVER listening for "
                                    + "zone '{}' (this node holds the master)", zoneId);
                            } else {
                                boolean zgSole;
                                try { zgSole = zgStore.count() <= 1; }
                                catch (Exception e) { zgSole = true; }
                                if (!zgSole) {
                                    // JOINER: request the master from the zone holder.
                                    final var zgClient =
                                        new NatsZoneGrantClient(sessionTransport);
                                    zgClient.requestGrant(zoneId, zgIdentity.nodeId(),
                                            zgIdentity.x25519PublicKeyBytes())
                                        .whenComplete((resp, err) -> {
                                            if (err != null) {
                                                log.info("zone-secret grant: zone '{}' grant request did not "
                                                    + "complete ({}); argot stays on the public seed until a "
                                                    + "holder responds", zoneId, err.getMessage());
                                            } else if (resp != null && resp.ok()) {
                                                boolean ok = ZoneSecrets
                                                    .installGrantedMaster(zgJdbc, zoneId, zgIdentity.nodeId(),
                                                        zgIdentity.privateKeySeedBytes(),
                                                        Base64.getDecoder().decode(resp.grantBlobBase64()),
                                                        zgIdentity.x25519PrivateKeyPkcs8());
                                                log.info("zone-secret grant: zone '{}' master grant from node "
                                                    + "'{}' install={} — this node now shares the zone's secret "
                                                    + "argot codebook", zoneId, resp.granterNodeId(), ok);
                                            } else {
                                                log.info("zone-secret grant: zone '{}' grant refused: {}",
                                                    zoneId, resp == null ? "no response" : resp.error());
                                            }
                                        });
                                    log.info("zone-secret grant: requested for zone '{}' "
                                        + "(this node joined master-less; awaiting holder)", zoneId);
                                }
                            }
                        } catch (Exception zgErr) {
                            log.warn("zone-secret grant: wiring failed (non-fatal; argot "
                                + "uses public seed): {}", zgErr.toString());
                        }

                        // resource-requisites (option b) — start the
                        // lender side now that the relay is up. A trusted peer may
                        // borrow this node to run a heavy recipe locally; the
                        // executor runs it via a fresh RecipeService (its own
                        // resource preflight stays authoritative) and reports the
                        // outcome. Trust is gated inside the server (bilateral
                        // agreement). Guarded — a failure here just means no lending.
                        if (crossZoneRecipeWiring != null) {
                            try {
                                final var borrowDataDir = Path.of(
                                    System.getenv().getOrDefault("WYRDSEKAI_DATA_DIR",
                                        System.getProperty("user.home") + "/.wyrdsekai"));
                                final var borrowScriptsRoot = Path.of(
                                    System.getProperty("user.dir"), "scripts");
                                crossZoneRecipeWiring.startLender(borrowReq -> {
                                    var recipesDir = borrowDataDir.resolve("recipes");
                                    var procRunner = new ProcessCommandRunner(
                                        new File(System.getProperty("user.dir")),
                                        Duration.ofMinutes(5));
                                    var backend = CodingBackendDispatcher
                                        .usingPreferred(List.of("goose", "pi"),
                                            borrowReq.agentDid(), Duration.ofMinutes(10))
                                        .orElse(null);
                                    var runner = backend == null
                                        ? new RecipeRunner(procRunner)
                                        : new RecipeRunner(procRunner, backend);
                                    var svc = new RecipeService(
                                        recipesDir, runner, borrowReq.agentDid(),
                                        Files.isDirectory(borrowScriptsRoot)
                                            ? borrowScriptsRoot : null);
                                    var started = svc.run(borrowReq.recipeName(), borrowReq.params());
                                    return new NatsRecipeServer.Outcome(
                                        started.run().status().name(),
                                        started.run().message(), started.runId());
                                });
                                log.info("Cross-zone recipe lender listening on zone '{}'", localZoneId);
                            } catch (Exception e) {
                                log.warn("Cross-zone recipe lender not started: {}", e.toString());
                            }
                        }

                        // Wire NATS cross-zone inference (both sides).
                        // Client: register a RemoteCaller with InferenceRouter so nats:// backends resolve.
                        // Server: subscribe to federation.inference.{localZone}.complete for incoming requests.
                        final var inferenceTransport = sessionTransport;
                        // Local node identity stamped + SIGNED on outgoing requests so a
                        // household provider can apply the auto-share exemption.
                        // Audit F7: the provider no longer trusts
                        // a self-asserted node id — the requester signs its claim with this
                        // node's Ed25519 key and the provider verifies against the public key
                        // it holds for us. null on failure → no exemption (safe fallback to
                        // agreement-based quota).
                        NodeIdentity localInferIdentity0;
                        try {
                            localInferIdentity0 = NodeIdentity.loadOrGenerate(
                                Path.of(System.getenv().getOrDefault("WYRDSEKAI_DATA_DIR",
                                    System.getProperty("user.home") + "/.wyrdsekai"))
                                    .resolve("node-identity.json"));
                        } catch (Exception e) {
                            localInferIdentity0 = null;
                            log.warn("Could not resolve local node identity for household inference: {}", e.toString());
                        }
                        final var localInferIdentity = localInferIdentity0;
                        final var localNodeForInfer =
                            localInferIdentity != null ? localInferIdentity.nodeId() : null;
                        final var natsClient = new NatsInferenceClient(
                            inferenceTransport);
                        if (inferenceRouter != null) {
                            inferenceRouter.tell(new InferenceRouter.SetNatsRemoteCaller(
                                (targetZone, sourceZone, chatReq, tokenCallback) -> {
                                    var messages = new ArrayList<NatsInferenceProtocol.Message>();
                                    for (var m : chatReq.messages()) {
                                        messages.add(new NatsInferenceProtocol.Message(
                                            m.role(), m.content()));
                                    }
                                    var maxT = chatReq.maxTokens() != null ? chatReq.maxTokens() : 512;
                                    var temp = chatReq.temperature() != null ? chatReq.temperature() : 0.7;
                                    // Advertise stream=true when a callback is attached so the provider
                                    // opts into SSE mode; the chunk-subject subscription doesn't care either way.
                                    boolean streaming = tokenCallback != null;
                                    // Audit F7: sign the household-exemption claim so the
                                    // provider can verify it isn't a self-asserted node id.
                                    var streamId = UUID.randomUUID().toString();
                                    String infSig = null;
                                    Long infTs = null;
                                    if (localInferIdentity != null && localNodeForInfer != null) {
                                        infTs = System.currentTimeMillis();
                                        var signingData = NatsInferenceProtocol.householdSigningData(
                                            streamId, sourceZone, localNodeForInfer, infTs);
                                        try {
                                            infSig = Base64.getEncoder().encodeToString(
                                                localInferIdentity.sign(signingData));
                                        } catch (Exception se) {
                                            infSig = null;
                                            infTs = null;
                                            log.debug("Household inference request signing failed: {}", se.toString());
                                        }
                                    }
                                    var natsReq = new NatsInferenceProtocol.Request(
                                        streamId,
                                        sourceZone, "agent", chatReq.model(), messages, maxT, temp, streaming,
                                        localNodeForInfer, infSig, infTs); // signed household-trust claim (audit F7)
                                    var future = streaming
                                        ? natsClient.requestStreaming(targetZone, natsReq, tokenCallback)
                                        : natsClient.request(targetZone, natsReq);
                                    return future.thenApply(c -> {
                                        var choice = new InferenceClient.Choice(
                                            0,
                                            new InferenceClient.ChatMessage("assistant", c.text()),
                                            c.finishReason() != null ? c.finishReason() : "stop");
                                        var pt = c.promptTokens() != null ? c.promptTokens() : 0;
                                        var ct = c.completionTokens() != null ? c.completionTokens() : 0;
                                        var usage = new InferenceClient.Usage(
                                            pt, ct, pt + ct);
                                        return new InferenceClient.ChatResponse(
                                            UUID.randomUUID().toString(), "chat.completion",
                                            System.currentTimeMillis() / 1000L, chatReq.model(),
                                            List.of(choice), usage);
                                    });
                                }));
                            // Server-side: handle inbound requests from other zones.
                            var localBackend = System.getenv().getOrDefault(
                                "WYRDSEKAI_LOCAL_INFERENCE_BACKEND", "llama-server");
                            var natsServer = new NatsInferenceServer(
                                inferenceTransport, zoneId, inferenceRouter, system, localBackend);
                            // Wire quota enforcement — provider rejects requests that would
                            // exceed the bilateral agreement's daily inference token allowance.
                            if (federationService != null) {
                                final var fs = federationService;
                                natsServer.setQuotaResolver(sourceZone -> {
                                    var agreement = fs.getAgreement(zoneId, sourceZone).orElse(null);
                                    return agreement != null ? agreement.localQuota() : null;
                                });
                                log.info("NATS inference quota enforcement enabled via FederationService");
                            }
                            // Household inference auto-share (provider side, ):
                            // a household member's request is served unlimited when sharing is on,
                            // overriding any bilateral-agreement cap. Membership keyed on the same
                            // HouseholdStore used to trust-gate zone-secret grants.
                            // Audit F7: verify the requester actually holds the node key it
                            // claims — look up the node's Ed25519 public key in HouseholdStore
                            // and check the request signature. A node that isn't in the store,
                            // or whose signature doesn't verify, gets no exemption.
                            final var hhInferStore = new HouseholdStore(
                                System.getProperty("wyrdsekai.jdbc.url"));
                            natsServer.setHouseholdGate(
                                (node, signingData, sigBase64) -> {
                                    if (node == null || signingData == null || sigBase64 == null) return false;
                                    var row = hhInferStore.get(node);
                                    if (row.isEmpty()) return false;
                                    try {
                                        // HouseholdStore mirrors each node's X.509 SPKI Ed25519
                                        // public key — exactly the form NodeIdentity.verify expects.
                                        var pub = row.get().publicKey();
                                        if (pub == null || pub.length == 0) return false;
                                        return NodeIdentity.verify(
                                            signingData, Base64.getDecoder().decode(sigBase64), pub);
                                    } catch (Exception ve) {
                                        log.debug("Household inference verify failed for node {}: {}",
                                            node, ve.toString());
                                        return false;
                                    }
                                },
                                () -> WyrdConfig.get().inferenceHouseholdShare());
                            log.info("Household inference auto-share gate wired (share={})",
                                WyrdConfig.get().inferenceHouseholdShare());
                            natsServer.start();
                            log.info("NATS cross-zone inference wired — client + server on zone '{}'", zoneId);
                        }
                        // Wire CrossZoneTellService to publish via relay.
                        // when a multi-homed router is
                        // present, federation egress/ingress fans across all relay legs
                        // (selecting the best SHARED relay per peer, privacy rail R1);
                        // otherwise fall back to the single session transport.
                        final var tellTransport = sessionTransport;
                        final var router = relayRouter;
                        final BiConsumer<String, byte[]> relayPublish =
                            router != null ? router::publish : tellTransport::publish;
                        final BiConsumer<String, Consumer<byte[]>> relaySubscribe =
                            router != null
                                ? router::subscribeAll
                                : (subject, h) -> tellTransport.subscribe(subject, h);
                        var tellService = CrossZoneTellService.get();
                        if (tellService != null) {
                            tellService.setRelayPublisher(relayPublish);
                            // Deliver cross-zone tells addressed to a local player to their
                            // live session(s) — all surfaces via the registry (#29), same
                            // hook as the single-node wiring above.
                            final var wsForTell = wsHandler;
                            final var registryForTell = clientConnectionRegistry;
                            tellService.setPlayerDeliverer((playerId, formatted) ->
                                deliverTellLineToPlayer(registryForTell, wsForTell,
                                    playerId, formatted));
                            // Subscribe to incoming cross-zone tells for this zone.
                            // Dual-subscribe under Phase-1:
                            // legacy `federation.{zoneId}.tell` AND canonical
                            // `federation.{fp}.{label}.tell`. Same handler — both
                            // delivery paths route into CrossZoneTellService.
                            // Canonical subscribe is skipped if the naming service
                            // isn't initialised or the zoneId is a reserved keyword.
                            Consumer<byte[]> tellHandler = data -> {
                                try {
                                    var mapper = new ObjectMapper();
                                    var node = mapper.readTree(data);
                                    tellService.handleIncomingTell(
                                        node.path("fromEntityId").asText(),
                                        node.path("fromEntityName").asText(),
                                        node.path("fromZone").asText(),
                                        node.path("targetName").asText(),
                                        node.path("text").asText());
                                } catch (Exception e) {
                                    log.warn("Failed to handle incoming cross-zone tell: {}", e.getMessage());
                                }
                            };
                            var legacyTellSubject = FederationSubjects.legacyTell(zoneId);
                            relaySubscribe.accept(legacyTellSubject, tellHandler);

                            var tellNaming = ZoneAddressResolverService.get();
                            if (tellNaming != null) {
                                try {
                                    var canonicalAddress = tellNaming.household().zone(zoneId);
                                    var canonicalTellSubject = FederationSubjects.canonicalTell(canonicalAddress);
                                    relaySubscribe.accept(canonicalTellSubject, tellHandler);
                                    log.info("Tell transport: dual-subscribed — legacy '{}' + canonical '{}'",
                                        legacyTellSubject, canonicalTellSubject);
                                } catch (IllegalArgumentException e) {
                                    log.warn("Tell transport: zoneId '{}' is reserved — canonical subscribe skipped "
                                        + "Migrate with `wyrd zones rename {} <new-label>`.",
                                        zoneId, zoneId);
                                } catch (Exception e) {
                                    log.warn("Tell transport: canonical subscribe failed, legacy only: {}",
                                        e.getMessage());
                                }
                            }
                        }

                        // wire CrossZoneCopyService for
                        // form + tool copy delivery. Same publish/subscribe shape
                        // as the tell path above. Subjects:
                        //   federation.<zoneId>.familiar_copy  (form copies)
                        //   federation.<zoneId>.familiar_tool  (tool copies)
                        var copyService = CrossZoneCopyService.get();
                        if (copyService != null) {
                            copyService.setRelayPublisher(relayPublish);
                            relaySubscribe.accept(
                                "federation." + zoneId + ".familiar_copy",
                                copyService::receiveFormCopy);
                            relaySubscribe.accept(
                                "federation." + zoneId + ".familiar_tool",
                                copyService::receiveToolCopy);
                            log.info("Cross-zone copy service wired — publisher + inbound "
                                + "subscriptions on federation.{}.familiar_copy|tool", zoneId);
                        }

                        // Definitive re-audit fix (#33-4): wire BOTH sides of
                        // cross-zone world.peek. CrossZonePeekService.init ran at
                        // boot but setCaller was test-only and no responder
                        // existed, so cross-zone peeks always returned null. The
                        // bridge adds a responder on federation.{zone}.peek and a
                        // caller reply-inbox on federation.{zone}.peek_reply, over
                        // the same relay pub/sub the tell path uses.
                        var peekService = CrossZonePeekService.get();
                        if (peekService != null) {
                            var peekBridge = new CrossZonePeekBridge(
                                zoneId, relayPublish, relaySubscribe);
                            peekBridge.startResponder();
                            peekBridge.startCaller();
                            peekService.setCaller(peekBridge);
                            log.info("Cross-zone peek bridge wired — responder + caller "
                                + "on federation.{}.peek|peek_reply", zoneId);
                        }
                        // NotificationService forwarder wired AFTER VirtualSessionHandler is created (below)
                    }
                }

                // Wire transit starter: docks.js calls world.startTransit() →
                // look up the client by playerId via the transport-agnostic
                // registry and invoke its startRemoteSession. Works for WS,
                // SSH, and Telnet clients alike.
                bridgeDataProvider.setTransitStarter((playerId, remoteZoneId, transitToken) ->
                    clientConnectionRegistry.findByPlayerId(playerId)
                        .map(c -> c.startRemoteSession(remoteZoneId, transitToken))
                        .orElse(false));

                // Start VirtualSessionHandler to receive incoming remote sessions.
                var virtualSessionHandler = new VirtualSessionHandler(
                    preConnectedNats, zoneId, federationService, system, wardService);
                if (sessionTransport != null) {
                    virtualSessionHandler.setRelayTransport(sessionTransport);
                }
                virtualSessionHandler.start();
                log.info("Cross-zone session proxy enabled — VirtualSessionHandler started");

                // Wire NotificationService remote forwarder now that both handlers exist
                if (sessionTransport != null) {
                    var notifService = NotificationService.get();
                    if (notifService != null) {
                        final var finalTransport = sessionTransport;
                        final var finalWsHandler = wsHandler;
                        final var finalVsHandler = virtualSessionHandler;
                        notifService.setRemoteForwarder((targetDid, destZone, notification) -> {
                            // Case 1: Target is a visitor in this zone — forward to their home via visitor session
                            if (finalVsHandler.forwardNotificationToVisitor(targetDid, notification)) {
                                return true;
                            }
                            // Case 2: Target is a local player traveling to destZone — publish to their remote session
                            var sessionId = finalWsHandler.remoteSessionIdFor(targetDid);
                            if (sessionId != null) {
                                try {
                                    var subject = "federation." + destZone +
                                        ".session." + sessionId + ".notify";
                                    var mapper = new ObjectMapper();
                                    var payload = mapper.createObjectNode();
                                    payload.put("sessionId", sessionId);
                                    payload.put("priority", notification.level());
                                    payload.put("fromAgent", notification.title());
                                    payload.put("message", notification.message());
                                    payload.put("timestamp", System.currentTimeMillis());
                                    finalTransport.publish(subject, mapper.writeValueAsBytes(payload));
                                    return true;
                                } catch (Exception e) {
                                    log.warn("Failed to forward notification: {}", e.getMessage());
                                }
                            }
                            return false;
                        });
                    }
                }
            }

            // Wave 4: Initialize room primary claims
            final var roomReqs = FoundationRoomLoader.loadRoomRequirements();
            AskPattern
                .<BetweenActor.Command, RoomPrimaryProtocol>ask(
                    betweenActor,
                    ref -> new BetweenActor.GetRoomPrimaryProtocol(ref),
                    Duration.ofSeconds(5), system.scheduler())
                .whenComplete((rpp, err) -> {
                    if (rpp != null && !roomReqs.isEmpty()) {
                        rpp.initializeRoomClaims(roomReqs);
                        rpp.startPrimaryTimeoutChecker(Duration.ofSeconds(30));
                        log.info("RoomPrimaryProtocol: initialized claims for {} rooms", roomReqs.size());
                    }
                });

            // Wave 8: Register local services in observability
            AskPattern
                .<BetweenActor.Command, HouseholdObservability>ask(
                    betweenActor,
                    ref -> new BetweenActor.GetObservability(ref),
                    Duration.ofSeconds(5), system.scheduler())
                .whenComplete((obs, err) -> {
                    if (obs != null) {
                        obs.registerService("nats", "NATS Messaging", "READY",
                            Map.of());
                        var inferenceUrl = WyrdConfig.get().inferenceUrl();
                        if (inferenceUrl != null) {
                            obs.registerService("inference", "Inference (llama-server)", "READY",
                                Map.of("url", inferenceUrl));
                        }
                        obs.registerService("server", "Wyrdsekai Server", "READY",
                            Map.of());
                        log.info("HouseholdObservability: local services registered");
                    }
                });

            // Wire remote presence → local room injection
            final var presenceTimeout = Duration.ofSeconds(3);
            AskPattern.<BetweenActor.Command, PresenceLayer>ask(
                betweenActor,
                ref -> new BetweenActor.GetPresenceLayer(ref),
                Duration.ofSeconds(5),
                system.scheduler()
            ).thenAccept(presenceLayer -> {
                if (presenceLayer != null) {
                    presenceLayer.setRemotePresenceListener(
                        new PresenceLayer.RemotePresenceListener() {
                            private ActorRef<RoomCommand> room(String roomId) {
                                return RoomRegistry.get().ref(roomId);
                            }

                            @Override
                            public void onRemoteEnter(String did, String displayName, String roomId) {
                                Rooms.<RoomResponse>ask(room(roomId), 
                                    ref -> new RoomCommand.EnterRoom(
                                        did, displayName, "remote-player", "somewhere", "en", ref),
                                    presenceTimeout).exceptionally(ex -> null);
                            }

                            @Override
                            public void onRemoteLeave(String did, String previousRoomId) {
                                Rooms.<RoomResponse>ask(room(previousRoomId), 
                                    ref -> new RoomCommand.LeaveRoom(did, "remote", "disconnect", ref),
                                    presenceTimeout).exceptionally(ex -> null);
                            }

                            @Override
                            public void onRemoteMove(String did, String displayName,
                                                     String fromRoomId, String toRoomId) {
                                Rooms.<RoomResponse>ask(room(fromRoomId), 
                                    ref -> new RoomCommand.LeaveRoom(did, displayName, toRoomId, ref),
                                    presenceTimeout).exceptionally(ex -> null);
                                Rooms.<RoomResponse>ask(room(toRoomId), 
                                    ref -> new RoomCommand.EnterRoom(
                                        did, displayName, "remote-player", fromRoomId, "en", ref),
                                    presenceTimeout).exceptionally(ex -> null);
                            }
                        }
                    );
                    log.info("Remote presence → local room injection wired");
                }
            }).exceptionally(ex -> {
                log.warn("Could not wire presence listener: {}", ex.getMessage());
                return null;
            });

            // Wire room event forwarding: subscribe to remote events, inject into local rooms
            AskPattern.<BetweenActor.Command, RoomEventListener>ask(
                betweenActor,
                ref -> new BetweenActor.GetRoomEventReplicator(ref),
                Duration.ofSeconds(5),
                system.scheduler()
            ).thenAccept(replicatorRef -> {
                if (replicatorRef instanceof RoomEventReplicator replicator) {
                    // Use Between node ID (not Pekko address) for dedup filtering
                    var localNodeId = replicator.getLocalNodeId();
                    replicator.subscribeToAllEvents(localNodeId, (roomId, event) -> {
                        // Forward remote event to local room actor for subscriber delivery
                        var roomRef = RoomRegistry.get().ref(roomId);
                        roomRef.tell(new RoomCommand.BroadcastRemoteEvent(event));
                    });
                    log.info("Room event forwarding wired — remote events will appear in local rooms");
                }
            }).exceptionally(ex -> {
                log.debug("Room event forwarding not available: {}", ex.getMessage());
                return null;
            });

            log.info("Between presence wired to WebSocket handler");
        }

        // Wire notification delivery callback to WyrdWebSocket
        var notifService = NotificationService.get();
        if (notifService != null) {
            notifService.setDeliveryCallback((targetDid, notification) ->
                wsHandler.deliverToPlayer(targetDid, notification));

            // Steward-consent notifier (2026-08-16): a pending permission
            // ask (ACP git-state write) pings the household so the steward
            // hears about it in time to answer. Only the steward can ANSWER
            // (ConsentRoutes is role-gated); the ping itself is household-
            // visible on purpose — a coding task asking to commit is not a
            // secret. Silence still resolves to refusal at the broker.
            ConsentBroker.get().setNotifier(pending -> notifService.notifyAll(
                "A coding task asks to run a git write: " + pending.summary()
                    + " — steward: `wyrd consent list` then "
                    + "`wyrd consent allow|deny <id>` (silence refuses).",
                "urgent", pending.backend()));
        }

        // Spawn companion agents (deferred to here so wsHandler is available as CommandRouter)
        // In cluster mode, only the designated companion host spawns companions.
        // Other nodes participate in the world but rely on event replication to
        // see companion activity. This prevents duplicate responses.
        // Wave 2-3: Dynamic companion placement via PlacementEngine (replaces static COMPANION_HOST).
        // Single-node mode: always host companions (no placement engine).
        // Multi-node mode: listen for existing heartbeat → claim or defer.
        final var finalInfRouter = inferenceRouter;
        final var finalWds = worldDnaService;
        final var finalUserScripts = userScriptsDir;
        final var finalSoulStore2 = soulStore;
        final var finalForge = forgeActor;

        // The Forge room's soul verbs (inspect/history/status/forge/birth)
        // route through ForgeRoomBridge; the birth ritual spawns through the
        // same SpawnCompanion path as boot, so a forge-born soul is a full
        // companion (soul store + forge + ws wiring included).
        ForgeRoomBridge.init(forgeActor, system, authService);
        // Hand the forge bridge the resolved DSN — WyrdConfig.jdbcUrl() is unset
        // on a real install, so without this every soul-forge verb answered
        // "sealed store" (same class as the CompanionCodexView fix above).
        ForgeRoomBridge.setJdbcUrl(jdbcUrl);
        CompanionSpawner.init(profile -> system.tell(new ZoneGuardian.SpawnCompanion(
            profile, null, finalInfRouter, finalWds, finalUserScripts,
            finalSoulStore2, finalForge, wsHandler)));

        // Default companion takes the household's chosen name at first boot
        // (wyrd start prompts and persists WYRDSEKAI_COMPANION_NAME; default
        // remains "Wyrd"). The name shapes entityId + system prompt, so the
        // soul is born with it rather than renamed after the fact.
        final var defaultCompanion = Companions.defaultCompanion(
            System.getenv("WYRDSEKAI_COMPANION_NAME"));

        // Optional second companion (steward opted in at first boot and named
        // it; wyrd start persists WYRDSEKAI_COMPANION_NAME_2). Born archetype
        // "random" so the siblings are distinct particulars. A null here means
        // the household keeps one companion — the common case.
        final var secondNameEnv = System.getenv("WYRDSEKAI_COMPANION_NAME_2");
        AgentProfile secondProfile = null;
        if (secondNameEnv != null && !secondNameEnv.isBlank()) {
            var candidate = Companions.additionalCompanion(secondNameEnv);
            if (candidate.entityId().equals(defaultCompanion.entityId())) {
                log.warn("WYRDSEKAI_COMPANION_NAME_2 '{}' collides with the first "
                    + "companion's entityId — skipping second spawn", secondNameEnv);
            } else {
                secondProfile = candidate;
            }
        }
        final var secondCompanion = secondProfile;

        // Durable births (2026-07-18): companions born at RUNTIME — the Forge's
        // `birth <name>` verb or the bond crystal's `birth` command — have a
        // persisted soul but no env var, so before this sweep a restart silently
        // erased them from the world (the soul sat in the DB with no actor).
        // Every non-archived agent soul respawns by NAME, exactly like the env
        // path: a fresh did-less profile whose entityId re-resolves the persisted
        // DID in initializeSoul (passing the manifest's own profile would carry
        // its DID, which the spawn path reads as a FOREIGN visitor — Isekai).
        // Shared by BOTH spawn branches — the first live install ran multi-node
        // (Between active) and a single-node-only sweep never fired.
        final Runnable respawnPersistedSouls = () -> {
            try {
                var spawnedIds = new HashSet<String>();
                spawnedIds.add(defaultCompanion.entityId());
                if (secondCompanion != null) spawnedIds.add(secondCompanion.entityId());
                // Soul seeds (JSON manifests in ~/.wyrdsekai/souls, WYRDSEKAI_SOUL_DIR)
                // spawn at their declared home room. This used to live ONLY in the
                // single-node branch, so on a default (multi-node) install an imported
                // soul was stored-but-never-spawned — and the old skip-set logic here
                // guaranteed the sweep couldn't rescue it either (2026-07-18). Now the
                // seeds spawn HERE, in both branches, with their home-room placement.
                for (var seed : loadSoulSeeds(finalSoulStore2)) {
                    if (!spawnedIds.add(seed.profile().entityId())) continue;
                    system.tell(new ZoneGuardian.SpawnCompanion(
                        seed.profile(), seed.roomId(), finalInfRouter, finalWds,
                        finalUserScripts, finalSoulStore2, finalForge, wsHandler));
                    log.info("SpawnCompanion: {} → room '{}' (soul seed)",
                        seed.profile().name(), seed.roomId());
                }
                // Residency guard (2026-07-18): the store also holds FOREIGN visitor
                // manifests that SoulLayer persisted on arrival and never archives on
                // departure. Only respawn souls actually BORN/homed here — proven by a
                // local souls/<entityId>.did file whose DID matches the manifest (every
                // locally-born companion writes one at birth; a visitor never does).
                // Without this, a zone that ever hosted a visitor would resurrect it as
                // a permanent local resident — the cross-zone duplication transit prevents.
                var soulsDir = SystemPaths.dataDir().resolve("souls");
                for (var m : finalSoulStore2.listLatest()) {
                    var p = m.profile();
                    if (p == null || p.entityId() == null) continue;
                    if (!"agent".equals(p.entityType())) continue;
                    if (!spawnedIds.add(p.entityId())) continue;      // already spawned
                    var reborn = Companions.additionalCompanion(p.name());
                    if (!reborn.entityId().equals(p.entityId())) continue; // name→id drift; skip
                    var didFile = soulsDir.resolve(p.entityId() + ".did");
                    boolean locallyBorn = false;
                    try {
                        locallyBorn = Files.exists(didFile)
                            && m.did() != null
                            && m.did().equals(Files.readString(didFile).trim());
                    } catch (Exception ignored) { /* treat as not-local */ }
                    if (!locallyBorn) {
                        log.info("Respawn sweep: skipping '{}' ({}) — no local birth record "
                            + "(foreign/visitor soul, not a resident here)",
                            p.name(), p.entityId());
                        continue;
                    }
                    system.tell(new ZoneGuardian.SpawnCompanion(
                        reborn, null, finalInfRouter, finalWds, finalUserScripts,
                        finalSoulStore2, finalForge, wsHandler));
                    log.info("SpawnCompanion: {} → nexus (respawned from persisted soul)",
                        reborn.name());
                }
            } catch (Exception e) {
                log.warn("Persisted-soul respawn sweep failed: {}", e.toString());
            }
        };

        if (inferenceRouter != null && betweenActor == null) {
            // Single-node mode — always host companions
            system.tell(new ZoneGuardian.SpawnCompanion(
                defaultCompanion, null, inferenceRouter, worldDnaService, userScriptsDir,
                soulStore, forgeActor, wsHandler));
            log.info("SpawnCompanion: {} → nexus (single-node, always host)",
                defaultCompanion.name());
            if (secondCompanion != null) {
                system.tell(new ZoneGuardian.SpawnCompanion(
                    secondCompanion, null, inferenceRouter, worldDnaService, userScriptsDir,
                    soulStore, forgeActor, wsHandler));
                log.info("SpawnCompanion: {} → nexus (second companion)",
                    secondCompanion.name());
            }

            // Soul seeds + runtime-born respawns are handled inside the shared
            // sweep now (seeds spawn at their home room there), so both branches
            // behave identically — no separate single-node seed loop.
            respawnPersistedSouls.run();
        } else if (inferenceRouter != null && betweenActor != null) {
            // Multi-node mode — use placement engine to decide
            final var ba = betweenActor;
            AskPattern
                .<BetweenActor.Command, ResourceHeartbeat>ask(
                    ba, ref -> new BetweenActor.GetResourceHeartbeat(ref),
                    Duration.ofSeconds(10), system.scheduler())
                .whenComplete((heartbeat, err) -> {
                    if (heartbeat == null) {
                        log.warn("ResourceHeartbeat not available — falling back to static host");
                        system.tell(new ZoneGuardian.SpawnCompanion(
                            defaultCompanion, null, finalInfRouter, finalWds, finalUserScripts,
                            finalSoulStore2, finalForge, wsHandler));
                        if (secondCompanion != null) {
                            system.tell(new ZoneGuardian.SpawnCompanion(
                                secondCompanion, null, finalInfRouter, finalWds, finalUserScripts,
                                finalSoulStore2, finalForge, wsHandler));
                        }
                        respawnPersistedSouls.run();
                        return;
                    }
                    // Listen for existing companion heartbeat (5s)
                    var entityId = defaultCompanion.entityId();
                    log.info("Placement: listening for existing {} heartbeat (5s)...", entityId);
                    var existing = heartbeat.listenForExistingClaim(entityId,
                        Duration.ofSeconds(5));
                    if (existing.isPresent()) {
                        log.info("Placement: {} already claimed by node {} — deferring",
                            entityId, existing.get().nodeId());
                    } else {
                        log.info("Placement: no heartbeat for {} — claiming on this node", entityId);
                        system.tell(new ZoneGuardian.SpawnCompanion(
                            defaultCompanion, null, finalInfRouter, finalWds, finalUserScripts,
                            finalSoulStore2, finalForge, wsHandler));
                        // The second companion follows the first's placement —
                        // it has no separate heartbeat claim (single-claim v1).
                        if (secondCompanion != null) {
                            system.tell(new ZoneGuardian.SpawnCompanion(
                                secondCompanion, null, finalInfRouter, finalWds, finalUserScripts,
                                finalSoulStore2, finalForge, wsHandler));
                        }
                        // Runtime-born companions follow the default companion's
                        // placement (single-claim v1, same rule as the second).
                        respawnPersistedSouls.run();
                        // Start publishing heartbeat
                        heartbeat.startPublishing(entityId, () ->
                            new ResourceHeartbeat.HeartbeatMessage(
                                resolveNodeId(),
                                entityId, "IDLE", 1.0, "nexus", 0, 0,
                                Instant.now()));
                        heartbeat.publishClaim(entityId, 0);
                    }
                    // Subscribe for failover monitoring
                    heartbeat.subscribeEntity(entityId);
                    heartbeat.startTimeoutChecker();
                });
        }

        // Zone bridge endpoint — external services (e.g. CodeZaiku) register as zone handlers
        var zoneSecret = WyrdConfig.get().zoneSecret(); // null = household trust (no auth)
        var zoneBridge = new ZoneBridgeEndpoint(wsHandler, zoneSecret);

        // Wave 1: Accounts & Security — InviteService + migration
        var inviteService = new InviteService(jdbcUrl);
        // world.invite (Study control panel) mints/lists/revokes through the
        // same service; steward gating happens in HomeOwnerItemProvider.
        wsHandler.setInviteService(inviteService);
        // account-anchored zone-bank store for
        // cross-device sync over the relay (wyrd.zone.<zone>.account.zonebank.*).
        var accountStore = new AccountStore(jdbcUrl);
        authService.migrateToHouseholdSecurity();
        var authRoutes = new AuthRoutes(authService, inviteService, pairingService, webAuthnService);

        // Wire account replication into Between mesh (if active)
        if (betweenActor != null) {
            betweenActor.tell(new BetweenActor.StartAccountReplication(
                authService, inviteService));
            // Wire replicator into AuthRoutes for live event publishing
            final var authRoutesRef = authRoutes;
            AskPattern
                .<BetweenActor.Command, IdentityReplicator>ask(
                    betweenActor,
                    ref -> new BetweenActor.GetIdentityReplicator(ref),
                    Duration.ofSeconds(10), system.scheduler())
                .whenComplete((replicator, err) -> {
                    if (replicator != null) {
                        authRoutesRef.setIdentityReplicator(replicator);
                        log.info("IdentityReplicator wired into AuthRoutes — live event publishing active");
                    }
                });
        }
        var wardRoutes = new WardRoutes(wardService, authService);
        final var limiter = rateLimiter;
        final var tlsConfig = config;
        final var finalInferenceRouter = inferenceRouter;
        final var finalSoulStore = soulStore;
        int telnetPort = 7071;
        try { telnetPort = config.getInt("wyrdsekai.telnet.port"); } catch (Exception ignored) {}
        int sshPort = 7022;
        try { sshPort = config.getInt("wyrdsekai.ssh.port"); } catch (Exception ignored) {}
        final int landingTelnetPort = telnetPort;
        final int landingSshPort = sshPort;
        final String landingHostname = hostname;
        final int landingPort = port;

        // Resident bridge config (read early, wired after companions spawn).
        final String residentEntityId;
        final String residentToken;
        if (config.hasPath("wyrdsekai.resident.enabled")
                && config.getBoolean("wyrdsekai.resident.enabled")) {
            residentEntityId = config.getString("wyrdsekai.resident.entity-id");
            residentToken = config.getString("wyrdsekai.resident.token");
        } else {
            residentEntityId = null;
            residentToken = null;
        }
        final var finalResidentEntityId = residentEntityId;
        final var finalResidentToken = residentToken;
        final var finalLuceneStore = luceneStore;
        final var finalBackupOrchestrator = backupOrchestrator;
        final var finalSearchDir = SystemPaths.dataDir().resolve("search");
        final var finalDbPath = Path.of(config.getString("wyrdsekai.db.path"));

        var app = Javalin.create(cfg -> {
            cfg.bundledPlugins.enableCors(cors -> {
                cors.addRule(rule -> rule.anyHost());
            });
            TlsConfig.configure(cfg, tlsConfig);

            // Browser client — single-file page from the classpath at /app
            if (webAppEnabled) {
                cfg.staticFiles.add(sf -> {
                    sf.hostedPath = "/app";
                    sf.directory = "/web";
                    sf.location = Location.CLASSPATH;
                });
            }

            // WebSocket idle timeout — Jetty 12 defaults to 30s which kills zone bridge connections.
            // Set server-wide to 5 minutes; zone bridge connections override to 30 minutes in onConnect.
            cfg.jetty.modifyWebSocketServletFactory(ws -> ws.setIdleTimeout(Duration.ofMinutes(5)));

            // WebSocket endpoints
            cfg.routes.ws("/ws", wsHandler);
            cfg.routes.ws("/ws/zone", zoneBridge);
            cfg.routes.ws("/voice", voiceWsHandler);
            // Task plane, not presence: a paired phone's hermod listener
            // channel. No session actor is created here by design.
            cfg.routes.ws("/ws/hermod", new HermodPhoneWs(hermodPhoneProxy, pairingService));

            // Rate limiter
            if (limiter != null) {
                cfg.routes.before(limiter);
            }

            // Route registrations
            healthRoutes.register(cfg.routes);
            authRoutes.register(cfg.routes);
            wardRoutes.register(cfg.routes);
            new ResidencyRoutes(authService, localZoneId).register(cfg.routes);
            new HouseholdRoutes(permissionChecker, stewardAuditLog, authService).register(cfg.routes);
            new SoulRoutes(finalSoulStore, authService, pairingService, bondStore).register(cfg.routes);
            // VoiceProfile CRUD — #414. Backed by VoiceProfileService, which
            // persists through SoulStore (new manifest version per write).
            // Shared with the Study voice-mirror furnishing (#416) so REST,
            // CLI and in-world edits all hit the same store path.
            //
            // F7b Phase 2.1: voice_profiles table is canonical. Service
            // dual-writes to both the table and the manifest field; reads
            // prefer the table, fall back to the manifest, and lazy-write
            // the fallback on first read so cross-zone arrivals converge.
            var voiceProfileStore =
                new VoiceProfileStore(jdbcUrl);
            // Idempotent one-shot backfill so existing souls in this zone
            // get a row on first boot after the migration. Cheap (one DB
            // query + per-missing-DID upsert); skipped rows already present.
            voiceProfileStore.backfillFromManifests(finalSoulStore);
            var voiceProfileService =
                new VoiceProfileService(
                    finalSoulStore, voiceProfileStore);
            new VoiceRoutes(voiceProfileService)
                .register(cfg.routes);
            bridgeDataProvider.setVoiceProfileService(voiceProfileService);
            // W16 — `wyrd recipes run` production surface. The harness route
            // (/api/test/run_recipe) only registers under WYRDSEKAI_TEST_MODE,
            // so prod installs permanently 404'd on steward-forced runs.
            // Same dispatch path (dispatchForcedRecipeRun), registered
            // UNCONDITIONALLY, gated on a steward session token — same
            // check as ResidencyRoutes grant/revoke.
            cfg.routes.post("/api/recipes/run",
                recipesRunHandler(authService, jdbcUrl));

            // Steward-consent surface (2026-08-16) — live allow/deny for
            // backend permission asks (ACP git-state writes). Same auth
            // matrix as /api/recipes/run; backs `wyrd consent`.
            OperatorToken.ensure(SystemPaths.dataDir());
            ConsentRoutes.register(cfg, authService);
            ForgeRoutes.register(cfg, authService);
            RepairRoutes.register(cfg, authService, jdbcUrl);

            // Lifted out of the if-block so the MCP NATS handler (further down)
            // can reach it for wyrd.zone.{zone}.study.journal subjects.
            StudyService studyServiceForNats = null;
            if (finalLuceneStore != null) {
                new SearchRoutes(finalLuceneStore).register(cfg.routes);
                // #1027/#1034 — wyrd library compact CLI backing.
                new LibraryCompactRoutes(finalLuceneStore)
                    .register(cfg.routes);
                // #1028/#1035 — wyrd recipe bondholder-* CLI backing.
                new RecipeBondholderRoutes(jdbcUrl)
                    .register(cfg.routes);
                // Track-C C6 — `wyrd recipes` CLI surface.
                // Extracted into RecipesRoutes for test isolation; lives
                // OUTSIDE the WYRDSEKAI_TEST_MODE block so prod installs can
                // actually serve `wyrd recipes list/status/log/pause/resume`.
                RecipesRoutes.register(cfg, jdbcUrl);
                // #1142 — tune-recipe-params surface (stats + apply).
                RecipeTuneRoutes.register(cfg, jdbcUrl);
                // #1014 (OPEN-R1) — agent-authored recipe compartment.
                // jdbcUrl threads the B.1 provenance log +
                // /api/recipe-provenance instrument.
                RecipeAuthorRoutes.register(cfg, jdbcUrl);
                try { Files.createDirectories(
                    SystemPaths.dataDir().resolve("recipes")); } catch (Exception ignored) {}
                var packsDir = SystemPaths.dataDir().resolve("packs");
                try { Files.createDirectories(packsDir); } catch (Exception ignored) {}
                new LibraryKnowledgeRoutes(finalLuceneStore,
                    new KnowledgePackIndexer(finalLuceneStore), packsDir).register(cfg.routes);
                // /issue + /feedback store, REST surface
                // and the context-capture wiring (conversation turns via jdbc,
                // WARN/ERROR tail from the live log).
                IssueService.init(
                    SystemPaths.dataDir(), jdbcUrl,
                    Path.of(System.getenv().getOrDefault("WYRDSEKAI_LOG_DIR", "logs"),
                        "wyrdsekai.log"));
                new IssueRoutes().register(cfg.routes);
                var homeClient = new HomeClient(homeRegistry, system);
                var studyService = new StudyService(finalLuceneStore, homeClient);
                var docIndexer = new DocumentIndexer(studyService);
                new StudyRoutes(studyService, docIndexer).register(cfg.routes);
                new FamiliarJournalRoutes(studyService).register(cfg.routes);
                // world.library.ingest — agent-callable, open-roots-confined
                AgentIngestService.init(studyService);
                studyServiceForNats = studyService;
            }
            final var studyServiceForNatsFinal = studyServiceForNats;

            // §6 — Workshop pinboard REST surface.
            // Materializer-per-agent: resolve the companion's live FamilyLocker +
            // WorkbenchSkillExecutor from CompanionCapabilityRegistry (populated
            // by each CompanionActor on construction). If the companion isn't
            // hosted on this node — or capabilities haven't fully wired yet —
            // log + skip so the steward can still approve the draft (status flips
            // to APPROVED; the draft sits as MATERIALIZE pending the actor
            // becoming available).
            new SkillRoutes(
                skillDraftStore,
                agentDid -> {
                    var caps = CompanionCapabilityRegistry.get()
                        .lookup(agentDid);
                    if (caps == null || caps.familyLocker() == null
                            || caps.workbenchExecutor() == null) {
                        return approved -> LoggerFactory
                            .getLogger("SkillRoutes")
                            .warn("SkillRoutes: approved '{}' for {} but no live "
                                + "FamilyLocker+WorkbenchExecutor (companion not hosted "
                                + "on this node, or capabilities not yet wired); "
                                + "draft will sit at APPROVED until the actor materializes",
                                approved.name(), agentDid);
                    }
                    return new WorkshopPinboard.DefaultMaterializer(
                        caps.familyLocker(), caps.workbenchExecutor(), agentDid);
                }
            ).register(cfg.routes);

            // on-demand harness authoring (complements the automatic
            // Forge sleep-pass). POST /api/skill/author?agent={did} mines anchors + compiles +
            // quality-gates a verification harness for the agent's pending drafts, in-process
            // (needs the live drive model + Library). Idempotent with the sleep-pass.
            new SkillAuthorRoutes(
                finalInferenceRouter, system.scheduler(), finalLuceneStore, skillDraftStore)
                .register(cfg.routes);

            // Home model: unified Grant + audit endpoints.
            var homeRoutes = new HomeRoutes(homeRegistry, system);
            // §97: trust-tier resolver — requester DID → zone → federation
            // trust level — stamps trustTier on incoming cross-zone knocks.
            if (federationServiceRef != null) {
                final var fedSvc = federationServiceRef;
                final var myZone = homeZoneId;
                homeRoutes.setTrustTierResolver(requester -> {
                    if (requester == null) return null;
                    String remoteZone = null;
                    if (requester.startsWith("did:zone:")) {
                        remoteZone = requester.substring("did:zone:".length());
                    }
                    if (remoteZone == null || remoteZone.equals(myZone)) return null;
                    return fedSvc.getAgreement(myZone, remoteZone)
                        .map(BilateralAgreement::trustLevel)
                        .orElse(null);
                });
            }
            homeRoutes.register(cfg.routes);

            // / Phase T — wire inbound
            // listeners (webhook, email_watch, mqtt, file_watch, scheduled)
            // and register the matching HTTP route. Listeners need
            // ActorSystem + jdbcUrl + ItemScheduleService, so this lives in
            // Main rather than CoreServices (which is parameterless). The
            // bootstrap returns the WebhookListener so the same instance
            // backs both the script-side `world.inbound.webhook` subscribe
            // path and the server-side POST /api/webhook/{id} delivery
            // path. Without this block, items can declare inbound caps but
            // nothing fires — see memory/items-api-phase-merges-2026-05-05
            // and for context.
            try {
                var scheduleService =
                    ItemScheduleService.get(system, jdbcUrl);
                var webhookListener =
                    PhaseTAdaptersBootstrap.init(
                        jdbcUrl, system, scheduleService);
                if (webhookListener != null) {
                    new WebhookRoutes(webhookListener)
                        .register(cfg.routes);
                }
            } catch (Throwable t) {
                log.warn("Phase T inbound listener bootstrap skipped: {}",
                    t.getMessage());
            }

            // Pairing broadcast: send as narrator SayInRoom to the Nexus room.
            // Both WebSocket and Telnet clients subscribed to Nexus will see it.
            new PairingRoutes(pairingService, authService, (speaker, text) -> {
                // Broadcast to all rooms via WebSocket sessions
                var ws = wsHandlerRef.get();
                if (ws != null) {
                    ws.broadcastToRoom(null,
                        new S2CMessage.Prose(
                            0, speaker, text, List.of(), null, "critical", "en"));
                }
                // Also send to the Nexus room as narrator prose (reaches telnet clients)
                try {
                    var nexusRef = RoomRegistry.get().ref("nexus");
                    nexusRef.tell(new RoomCommand.SayInRoom(
                        "system", "system", text, "en", null,
                        system.deadLetters().unsafeUpcast()));
                } catch (Exception e) {
                    log.debug("Could not broadcast pairing code to Nexus room: {}", e.getMessage());
                }
            }).register(cfg.routes);

            // "auto add to home zone" enrollment.
            // A peer node POSTs its identity + a pre-shared household key here;
            // on a valid key we mirror it into THIS hub's households table (the
            // gate the GPU-borrow consumer/provider key off) and echo back the
            // hub identity + roster so the joiner can mirror the household.
            try {
                var hjDataDir = Path.of(
                    System.getenv().getOrDefault("WYRDSEKAI_DATA_DIR",
                        System.getProperty("user.home") + "/.wyrdsekai"));
                var hjIdentity = NodeIdentity.loadOrGenerate(
                    hjDataDir.resolve("node-identity.json"));
                var hjStore = new HouseholdStore(jdbcUrl);
                new HouseholdJoinRoutes(pairingService, hjStore, hjIdentity, Main::resolveLanIp)
                    .register(cfg.routes);
            } catch (Exception hjErr) {
                log.warn("Household-join route wiring skipped (non-fatal): {}", hjErr.getMessage());
            }

            // Bud delegation HTTP fallback endpoint (phone → server companion)
            new CompanionAskRoutes(system, pairingService)
                .register(cfg.routes);

            // Federation API — zone-to-zone federation management
            if (betweenActor != null) {
                final var ba = betweenActor;
                cfg.routes.post("/api/federation/propose/{targetZone}", ctx -> {
                    var targetZone = ctx.pathParam("targetZone");
                    var result = askProposeFederation(ba, system, targetZone);
                    ctx.json(Map.of("result", result));
                });
                cfg.routes.post("/api/federation/accept/{targetZone}", ctx -> {
                    var targetZone = ctx.pathParam("targetZone");
                    var result = askAcceptFederation(ba, system, targetZone);
                    ctx.json(Map.of("result", result));
                });
                cfg.routes.post("/api/federation/revoke/{targetZone}", ctx -> {
                    var targetZone = ctx.pathParam("targetZone");
                    var result = askRevokeFederation(ba, system, targetZone);
                    ctx.json(Map.of("result", result));
                });
                cfg.routes.get("/api/federation/status", ctx -> {
                    var status = askFederationStatus(ba, system);
                    ctx.json(Map.of("status", status));
                });
                // F12: mesh-state matrix — both-sides view of every agreement.
                cfg.routes.get("/api/federation/mesh-status", ctx -> {
                    var result = askFederationMeshStatus(ba, system);
                    var entries = result.entries().stream().map(e -> Map.of(
                        "partnerZoneId", e.partnerZoneId(),
                        "localStatus", e.localStatus(),
                        "partnerStatus", e.partnerStatus(),
                        "consensus", e.consensus()
                    )).toList();
                    int agree = (int) result.entries().stream()
                        .filter(e -> "agree".equals(e.consensus())).count();
                    int mismatch = (int) result.entries().stream()
                        .filter(e -> "mismatch".equals(e.consensus())).count();
                    int unreachable = (int) result.entries().stream()
                        .filter(e -> "unreachable".equals(e.consensus())).count();
                    ctx.json(Map.of(
                        "localZone", result.localZoneId(),
                        "probedAt", result.probedAt().toString(),
                        "entries", entries,
                        "agreeCount", agree,
                        "mismatchCount", mismatch,
                        "unreachableCount", unreachable));
                });

                // Structured view of bilateral agreements with quota + usage.
                // Powers the Study "Agreements" page — stewards can see who they're
                // federated with, what trust level, what the daily quota is, and
                // how close today's usage is to the limit.
                if (federationServiceRef != null) {
                    final var fedSvc = federationServiceRef;
                    cfg.routes.get("/api/federation/agreements", ctx -> {
                        var agreements = fedSvc.listAgreements(zoneId).stream()
                            .map(a -> agreementView(a, zoneId))
                            .toList();
                        ctx.json(Map.of(
                            "localZone", zoneId,
                            "agreements", agreements,
                            "count", agreements.size()));
                    });

                    cfg.routes.get("/api/federation/agreements/{remoteZone}", ctx -> {
                        var remoteZone = ctx.pathParam("remoteZone");
                        var agreement = fedSvc.getAgreement(zoneId, remoteZone).orElse(null);
                        if (agreement == null) {
                            ctx.status(404).json(Map.of(
                                "error", "no agreement with zone '" + remoteZone + "'"));
                            return;
                        }
                        ctx.json(agreementView(agreement, zoneId));
                    });
                }
            }

            // F14: build/version endpoints. /api/version returns this node's
            // build stamp (operator-friendly + machine-readable). /api/version/mesh
            // returns local + every known peer's last-seen buildVersion from the
            // FederationActor's knownZones cache (populated at handshake time).
            cfg.routes.get("/api/version", ctx -> {
                var v = AppVersion.get();
                ctx.json(Map.of(
                    "appVersion", v.version(),
                    "buildHash", v.buildHash(),
                    "gitSha", v.gitSha(),
                    "gitDirty", v.gitDirty(),
                    "buildTimestamp", v.buildTimestamp().toString(),
                    "wireProtocol", v.wireProtocol(),
                    "federationSchema", v.federationSchema(),
                    "zoneId", zoneId));
            });
            if (federationServiceRef != null) {
                final var fedSvc = federationServiceRef;
                cfg.routes.get("/api/version/mesh", ctx -> {
                    var v = AppVersion.get();
                    var local = Map.of(
                        "zoneId", zoneId,
                        "appVersion", v.version(),
                        "buildHash", v.buildHash(),
                        "gitSha", v.gitSha(),
                        "gitDirty", v.gitDirty(),
                        "wireProtocol", v.wireProtocol(),
                        "federationSchema", v.federationSchema());
                    var peers = new ArrayList<Map<String, Object>>();
                    for (var a : fedSvc.listAgreements(zoneId)) {
                        var partnerZone = a.remoteZoneId();
                        var manifest = fedSvc.getManifest(partnerZone).orElse(null);
                        Map<String, Object> entry = new LinkedHashMap<>();
                        entry.put("zoneId", partnerZone);
                        entry.put("agreementStatus", a.status());
                        if (manifest != null && manifest.buildVersion() != null) {
                            var bv = manifest.buildVersion();
                            entry.put("buildVersion", Map.of(
                                "appVersion", bv.appVersion() == null ? "" : bv.appVersion(),
                                "buildHash", bv.buildHash() == null ? "" : bv.buildHash(),
                                "gitSha", bv.gitSha() == null ? "" : bv.gitSha(),
                                "gitDirty", bv.gitDirty(),
                                "buildTimestamp", bv.buildTimestamp() == null ? ""
                                    : bv.buildTimestamp().toString(),
                                "wireProtocol", bv.wireProtocol(),
                                "federationSchema", bv.federationSchema()));
                        } else {
                            entry.put("buildVersion", null);
                        }
                        peers.add(entry);
                    }
                    ctx.json(Map.of("local", local, "peers", peers));
                });
            }

            // Zone directory REST + .well-known endpoints.
            // Extracted to DirectoryRoutes so the same registration is used by
            // Main and by DirectoryRoutesTest — there's exactly one wire-level
            // implementation to keep in sync.
            new DirectoryRoutes(
                () -> zoneDirectory,
                () -> localManifest
            ).register(cfg);

            // MCP REST API — request/response interface for Claude Code and other agents
            new McpRoutes(authService, system)
                .register(cfg.routes);

            // MCP NATS surface (parallel to HTTP) — phones / future clients
            // call wyrd.zone.{zone}.mcp.{login,tell,...} via request/reply
            // instead of HTTPS. Gated by
            // WYRDSEKAI_MCP_NATS_ENABLED (default on if NATS is up) so
            // single-node installs without NATS keep working.
            //
            // Two instances are created when the zone is relay-attached:
            //   1. LOCAL handler  — subscribes on home-server's 127.0.0.1:4222 NATS.
            //      LAN clients / admin tools / Phase 1 smoke tests hit here.
            //   2. RELAY handler  — subscribes on the relay (relay-node:4222) with
            //      the zone's relay credentials. PHONES connecting over
            //      wss://relay:4443 hit here. Without this second instance
            //      the phone-side messages never reach this JVM, because the
            //      relay nats and the local nats are isolated (the RelayBridge
            //      only forwards between.> and federation.gate.>, NOT
            // wyrd.zone.>).
            //
            // Each handler responds on the connection the request arrived on,
            // so replies route back correctly even with two parallel subs.
            // Use WyrdConfig.zoneId() — the canonical resolver. "home" is reserved
            // (collides with furnishing concept); fresh nodes get a
            // ZoneNameGenerator hostname-derived label. See WyrdConfig:146.
            final String mcpZoneId = WyrdConfig.get().zoneId();
            // this zone's vector-clock slot key + the dst
            // token phones address study-sync deltas to. Stable per zone (a phone
            // syncs with one zone; multi-homed zones each get a distinct slot).
            final String studyServerDeviceId = "srv-" + mcpZoneId;
            // the loopback WS port for the relay session
            // tunnel. Reuse `landingPort`, the effectively-final capture of the
            // bound HTTP port already taken above (the mutable `port` can't be
            // referenced from the per-leg lambda below).
            final int tunnelHttpPort = landingPort;
            final boolean mcpNatsEnabled = !"false".equalsIgnoreCase(
                System.getenv().getOrDefault("WYRDSEKAI_MCP_NATS_ENABLED", "true"));
            if (mcpNatsEnabled
                && preConnectedNats != null
                && preConnectedNats.rawConnection() != null) {
                var mcpNatsLocal = new McpNatsHandler(
                    authService, system,
                    preConnectedNats.rawConnection(),
                    mcpZoneId,
                    finalLuceneStore,
                    studyServiceForNatsFinal,
                    inviteService,
                    accountStore);
                mcpNatsLocal.start();
                // make this zone a CRDT Study peer
                // on its own NATS (relay forwards between.{zone}.> here), so a
                // phone's local Study and the server's Study actually converge.
                if (studyServiceForNatsFinal != null) {
                    new StudySyncPeer(preConnectedNats.rawConnection(), mcpZoneId,
                        studyServerDeviceId, studyServiceForNatsFinal,
                        authService, pairingService).start();
                }
            }
            // Relay-side handler(s). A zone can be homed on SEVERAL relays at
            // once (laptop on wifi+ethernet, or a public + private relay — see
            // ). A phone connects through ONE of those
            // relays (whichever its invite advertised), so the zone must expose
            // its phone mcp.* surface on EVERY relay leg, not just the primary —
            // otherwise a phone arriving via a non-primary relay has no
            // responder for mcp.login and silently can't reach this zone.
            // Each leg is WYRDSEKAI_RELAY_URL{,_2,_3,…} with its own
            // _USER / _TOKEN (each relay assigns its own household creds at join).
            if (mcpNatsEnabled) {
                var relayMcpLegs = new ArrayList<String[]>(); // {url, user, pass}
                String[] legSuffixes =
                    {"", "_2", "_3", "_4", "_5", "_6", "_7", "_8", "_9"};
                var seenRelayUrls = new HashSet<String>();
                for (var sfx : legSuffixes) {
                    var legUrl = System.getenv("WYRDSEKAI_RELAY_URL" + sfx);
                    if (legUrl == null || legUrl.isBlank()) continue;
                    if (!seenRelayUrls.add(legUrl.trim())) continue; // dedup
                    relayMcpLegs.add(new String[]{
                        legUrl.trim(),
                        System.getenv("WYRDSEKAI_RELAY_USER" + sfx),
                        System.getenv("WYRDSEKAI_RELAY_TOKEN" + sfx)
                    });
                }
                for (var leg : relayMcpLegs) {
                    final String mcpRelayUrl = leg[0];
                    final String relayUser = leg[1];
                    final String relayPass = leg[2];
                    try {
                        // Hold a forward reference so the ConnectionListener
                        // (built into the Options before the handler exists)
                        // can call replaySubscriptions() on RECONNECTED.
                        var handlerRef =
                            new AtomicReference<
                                McpNatsHandler>();
                        var relayLog = LoggerFactory.getLogger(
                            "McpNatsHandler.relay");
                        var optsBuilder = new Options.Builder()
                            .server(mcpRelayUrl)
                            .connectionName("wyrdsekai-mcp-nats-relay")
                            .maxReconnects(-1)
                            .reconnectWait(Duration.ofSeconds(2))
                            // Tighter ping than the default 2-minute interval — see
                            // the 2026-05-12 relay-node NATS restart incident where dead
                            // connection detection took ~5 minutes and broke phone
                            // mobile probes silently. 15s × 3 = 45s worst-case.
                            .pingInterval(Duration.ofSeconds(15))
                            .maxPingsOut(3)
                            .connectionListener((conn, type) -> {
                                switch (type) {
                                    case CONNECTED ->
                                        relayLog.info("Relay NATS connected: {}", mcpRelayUrl);
                                    case DISCONNECTED ->
                                        relayLog.warn("Relay NATS disconnected: {}", mcpRelayUrl);
                                    case RECONNECTED -> {
                                        relayLog.info("Relay NATS reconnected: {}", mcpRelayUrl);
                                        // Belt-and-suspenders: replay subs even
                                        // though jnats claims to do this for us.
                                        // Production 2026-05-12 showed silent loss.
                                        var h = handlerRef.get();
                                        if (h != null) h.replaySubscriptions();
                                    }
                                    case RESUBSCRIBED ->
                                        relayLog.info("Relay NATS resubscribed (jnats auto-restore)");
                                    case CLOSED ->
                                        relayLog.info("Relay NATS closed: {}", mcpRelayUrl);
                                    default -> relayLog.debug("Relay NATS event: {}", type);
                                }
                            });
                        if (relayUser != null && !relayUser.isBlank()
                            && relayPass != null && !relayPass.isBlank()) {
                            optsBuilder.userInfo(relayUser, relayPass);
                        }
                        var relayConn = Nats.connect(optsBuilder.build());
                        var mcpNatsRelay = new McpNatsHandler(
                            authService, system, relayConn, mcpZoneId,
                            finalLuceneStore, studyServiceForNatsFinal,
                            inviteService, accountStore);
                        handlerRef.set(mcpNatsRelay);
                        mcpNatsRelay.start();
                        LoggerFactory.getLogger("Main").info(
                            "MCP NATS relay handler started on leg {} (phones via this relay can reach zone {}).",
                            mcpRelayUrl, mcpZoneId);
                        // also peer for Study CRDT
                        // sync directly on this relay leg (belt-and-suspenders with
                        // the local-NATS peer; CRDT merge is idempotent so a message
                        // seen on both connections just no-ops the second time).
                        if (studyServiceForNatsFinal != null) {
                            new StudySyncPeer(relayConn, mcpZoneId,
                                studyServerDeviceId, studyServiceForNatsFinal,
                                authService, pairingService).start();
                        }
                        // full session tunnel on the same
                        // relay leg: dumb-pipe a phone session into our own /ws.
                        try {
                            var tunnel = new TunnelSessionHandler(
                                relayConn, mcpZoneId, tunnelHttpPort);
                            tunnel.start();
                            LoggerFactory.getLogger("Main").info(
                                "Relay session tunnel started on leg {} (full sessions via this relay reach zone {}).",
                                mcpRelayUrl, mcpZoneId);
                        } catch (Exception te) {
                            LoggerFactory.getLogger("Main").warn(
                                "Relay session tunnel failed to start on leg {} ({}). MCP RPC still works; full sessions over this relay do not.",
                                mcpRelayUrl, te.getMessage());
                        }
                    } catch (Exception e) {
                        LoggerFactory.getLogger("Main").warn(
                            "MCP NATS relay handler failed to start on leg {} ({}). Phones arriving via THIS relay cannot reach this zone until resolved; other legs unaffected.",
                            mcpRelayUrl, e.getMessage());
                    }
                }
            }

            // Test-only: trigger a notification to a target entity. Guarded by WYRDSEKAI_TEST_MODE.
            // Used to exercise NotificationService delivery (including cross-zone forwarding to traveling players).
            if ("true".equalsIgnoreCase(System.getenv("WYRDSEKAI_TEST_MODE"))) {
                cfg.routes.post("/api/test/notify", ctx -> {
                    var body = new ObjectMapper().readTree(ctx.body());
                    var targetDid = body.path("targetDid").asText(null);
                    var message = body.path("message").asText("test notification");
                    var priority = body.path("priority").asText("normal");
                    var fromAgentId = body.path("fromAgentId").asText("test-harness");
                    var svc = NotificationService.get();
                    if (svc == null) { ctx.status(503).json(Map.of("error", "NotificationService unavailable")); return; }
                    if (targetDid == null) { ctx.status(400).json(Map.of("error", "targetDid required")); return; }
                    svc.notify(targetDid, message, priority, fromAgentId);
                    ctx.json(Map.of("status", "notified", "target", targetDid));
                });

                // Record a metering event (v1 economy). Used to validate MeteringService/ReferenceRates
                // end-to-end without depending on cross-zone inference infrastructure.
                cfg.routes.post("/api/test/meter", ctx -> {
                    var body = new ObjectMapper().readTree(ctx.body());
                    var localZone = WyrdConfig.get().zoneId();
                    var requestingZone = body.path("requestingZone").asText(localZone);
                    var providingZone = body.path("providingZone").asText("beta");
                    var serviceClass = body.path("serviceClass").asText(
                        ReferenceRates.SERVICE_INFERENCE_SMALL);
                    var units = body.path("units").asDouble(1.0);
                    var agentId = body.path("agentId").asText("test-harness");
                    var metering = MeteringService.get();
                    if (metering == null) { ctx.status(503).json(Map.of("error", "MeteringService unavailable")); return; }
                    var cu = metering.record(requestingZone, providingZone, serviceClass, units, agentId);
                    ctx.json(Map.of(
                        "status", "recorded",
                        "cuCharged", cu,
                        "requestingZone", requestingZone,
                        "providingZone", providingZone,
                        "serviceClass", serviceClass));
                });

                // Place a scripted test item directly into an entity's inventory.
                // Used to exercise scripted-item transit without going through craft_item (which requires inference).
                cfg.routes.post("/api/test/give_scripted_item", ctx -> {
                    var body = new ObjectMapper().readTree(ctx.body());
                    var entityId = body.path("entityId").asText(null);
                    var itemId = body.path("itemId").asText("echo_stone");
                    var itemName = body.path("itemName").asText("echo stone");
                    var description = body.path("description").asText("A smooth stone that echoes the current zone.");
                    var scriptSource = body.path("scriptSource").asText(
                        "function invoke(p) { return {response: 'echo from ' + world.zone.current()}; }");
                    if (entityId == null) { ctx.status(400).json(Map.of("error", "entityId required")); return; }
                    var inv = new InventoryService(jdbcUrl);
                    inv.addItem(entityId, itemId, itemName, description,
                        true, "test-harness", scriptSource, itemId);
                    ctx.json(Map.of("status", "given", "entityId", entityId, "itemId", itemId));
                });

                // Dump an entity's inventory (script_source included) for test assertions.
                cfg.routes.get("/api/test/inventory", ctx -> {
                    var entityId = ctx.queryParam("entityId");
                    if (entityId == null) { ctx.status(400).json(Map.of("error", "entityId required")); return; }
                    var inv = new InventoryService(jdbcUrl);
                    var items = inv.listItems(entityId).stream()
                        .map(it -> Map.of(
                            "objectId", it.objectId(),
                            "objectName", it.objectName(),
                            "takenFrom", it.takenFrom() == null ? "" : it.takenFrom(),
                            "scripted", it.isScripted(),
                            "scriptId", it.scriptId() == null ? "" : it.scriptId()))
                        .toList();
                    ctx.json(Map.of("entityId", entityId, "items", items));
                });

                // Trigger an inference round-trip. Exercises full path on test-node:
                //   InferenceRouter picks NatsRemote backend → NATS request to alpha →
                //   alpha's NatsInferenceServer routes to local llama-server → response stream.
                // Force a bunshin dispatch without going through the LLM
                // action-parser path. Lets tier-2 tests exercise the full
                // bunshin pipeline (slot acquisition → BunshinActor spawn →
                // real inference turns → BunshinReport → companion narration)
                // deterministically instead of hoping the model chose to emit
                // dispatch_bunshin on its own.
                cfg.routes.post("/api/test/dispatch_bunshin", ctx -> {
                    var body = new ObjectMapper().readTree(ctx.body());
                    var entityId = body.path("entityId").asText(null);
                    var task = body.path("task").asText("Summarize the current room.");
                    var maxTokens = body.hasNonNull("maxTokens") ? body.get("maxTokens").asInt() : 256;
                    var maxSteps = body.hasNonNull("maxSteps") ? body.get("maxSteps").asInt() : 3;
                    var wallClock = body.hasNonNull("wallClockSeconds")
                        ? body.get("wallClockSeconds").asInt() : 60;
                    if (entityId == null) {
                        ctx.status(400).json(Map.of("error", "entityId required"));
                        return;
                    }
                    var companion = ZoneGuardian
                        .getCompanionRef(null, entityId);
                    if (companion == null) {
                        ctx.status(404).json(Map.of(
                            "error", "no companion for entityId " + entityId));
                        return;
                    }
                    companion.tell(new CompanionActor
                        .TestDispatchBunshin(task, maxTokens, maxSteps, wallClock));
                    ctx.json(Map.of(
                        "status", "dispatched", "entityId", entityId, "task", task,
                        "maxTokens", maxTokens, "maxSteps", maxSteps,
                        "wallClockSeconds", wallClock));
                });

                // Force a companion's energy to a specific value, bypassing the
                // natural drain/recovery cycle. Used by soak probes that need
                // to push Wyrd into sleep deterministically (e.g. memory
                // persistence across sleep cycles).
                cfg.routes.post("/api/test/force_energy", ctx -> {
                    var body = new ObjectMapper().readTree(ctx.body());
                    var entityId = body.path("entityId").asText(null);
                    var energy = body.path("energy").asDouble(Double.NaN);
                    if (entityId == null) {
                        ctx.status(400).json(Map.of("error", "entityId required"));
                        return;
                    }
                    if (Double.isNaN(energy)) {
                        ctx.status(400).json(Map.of("error", "energy required"));
                        return;
                    }
                    var companion = ZoneGuardian
                        .getCompanionRef(null, entityId);
                    if (companion == null) {
                        ctx.status(404).json(Map.of(
                            "error", "no companion for entityId " + entityId));
                        return;
                    }
                    companion.tell(new CompanionActor
                        .ForceEnergy(energy));
                    ctx.json(Map.of(
                        "status", "forced", "entityId", entityId, "energy", energy));
                });

                // Wipe ephemeral companion state between capability-probe @Test
                // methods. See CapabilityProbeBase. Capability suites (Ember,
                // MemoryE2E, SoulSubstrate) share a single Wyrd companion via
                // @BeforeAll lifecycle; without per-test reset, state from
                // task1 contaminates task9 and produces false negatives.
                cfg.routes.post("/api/test/companion_reset", ctx -> {
                    var body = new ObjectMapper().readTree(ctx.body());
                    var entityId = body.path("entityId").asText(null);
                    if (entityId == null) {
                        ctx.status(400).json(Map.of("error", "entityId required"));
                        return;
                    }
                    var companion = ZoneGuardian
                        .getCompanionRef(null, entityId);
                    if (companion == null) {
                        ctx.status(404).json(Map.of(
                            "error", "no companion for entityId " + entityId));
                        return;
                    }
                    companion.tell(new CompanionActor
                        .ResetState());
                    ctx.json(Map.of("status", "reset", "entityId", entityId));
                });

                // Force the full sleep cycle on a companion (maintenance +
                // forge manifest + memory entity forge + classifier consolidation
                // + wake). Used by persistence soak probes that can't reliably
                // push energy below threshold (natural recovery oscillates).
                // Test-only: bulk-seed a companion's significance buffer so
                // deep-sleep training has a corpus without hours of live chat.
                cfg.routes.post("/api/test/seed_significance", ctx -> {
                    var body = new ObjectMapper().readTree(ctx.body());
                    var entityId = body.path("entityId").asText(null);
                    if (entityId == null) {
                        ctx.status(400).json(Map.of("error", "entityId required"));
                        return;
                    }
                    var entriesNode = body.path("entries");
                    if (!entriesNode.isArray() || entriesNode.isEmpty()) {
                        ctx.status(400).json(Map.of("error", "entries[] required"));
                        return;
                    }
                    var entries = new ArrayList<String>();
                    entriesNode.forEach(e -> entries.add(e.asText("")));
                    var companion = ZoneGuardian
                        .getCompanionRef(null, entityId);
                    if (companion == null) {
                        ctx.status(404).json(Map.of(
                            "error", "no companion for entityId " + entityId));
                        return;
                    }
                    companion.tell(new CompanionActor.SeedSignificance(entries));
                    ctx.json(Map.of(
                        "status", "seeded", "entityId", entityId, "count", entries.size()));
                });

                // Goal 2 / A3 — fire a {@code RequestRecipe} on a
                // named companion without going through the LLM action-parser
                // path. Same wire as the agent-initiated path (handleRequestRecipe
                // is invoked), just deterministic for the tier-3 E2E test.
                // {@code RecipeAgentForgeE2ETest} uses this to seed a recipe
                // run, then triggers force_sleep to drain RecipeRunLog into the
                // soul via RecipeForgeIngester. Dispatch body shared with the
                // production steward route /api/recipes/run (W16) — see
                // {@link #dispatchForcedRecipeRun}. Unauthenticated on purpose:
                // this route only exists under WYRDSEKAI_TEST_MODE.
                cfg.routes.post("/api/test/run_recipe",
                    ctx -> dispatchForcedRecipeRun(ctx, jdbcUrl));

                // (RecipesRoutes registration moved outside the TEST_MODE
                // block — search "RecipesRoutes.register" near line 2163.)

                cfg.routes.post("/api/test/force_sleep", ctx -> {
                    var body = new ObjectMapper().readTree(ctx.body());
                    var entityId = body.path("entityId").asText(null);
                    var tierStr = body.path("tier").asText("normal");
                    if (entityId == null) {
                        ctx.status(400).json(Map.of("error", "entityId required"));
                        return;
                    }
                    var tier = "deep".equalsIgnoreCase(tierStr)
                        ? CompanionActor.SleepTier.DEEP
                        : CompanionActor.SleepTier.NORMAL;
                    var companion = ZoneGuardian
                        .getCompanionRef(null, entityId);
                    if (companion == null) {
                        ctx.status(404).json(Map.of(
                            "error", "no companion for entityId " + entityId));
                        return;
                    }
                    companion.tell(new CompanionActor.ForceSleep(tier));
                    ctx.json(Map.of(
                        "status", "sleep_triggered", "entityId", entityId,
                        "tier", tier.name()));
                });

                cfg.routes.post("/api/test/infer", ctx -> {
                    var body = new ObjectMapper().readTree(ctx.body());
                    var prompt = body.path("prompt").asText("Say hello in one sentence.");
                    var model = body.path("model").asText("");
                    var maxTokens = body.path("maxTokens").asInt(64);
                    var temperature = body.path("temperature").asDouble(0.7);
                    if (inferenceRouter == null) {
                        ctx.status(503).json(Map.of("error", "no inference router"));
                        return;
                    }
                    var messages = List.of(
                        new InferenceClient.ChatMessage("user", prompt));
                    var future = AskPattern
                        .<InferenceRouter.Command,
                          InferenceRouter.InferResponse>ask(
                            inferenceRouter,
                            replyTo -> new InferenceRouter.ChatRequest(
                                UUID.randomUUID().toString(),
                                model, messages, maxTokens, temperature, replyTo),
                            Duration.ofSeconds(90),
                            system.scheduler());
                    try {
                        var resp = future.toCompletableFuture().get(95, TimeUnit.SECONDS);
                        if (resp instanceof InferenceRouter.InferOk ok) {
                            ctx.json(Map.of(
                                "text", ok.content() == null ? "" : ok.content(),
                                "promptTokens", ok.promptTokens(),
                                "completionTokens", ok.completionTokens()));
                        } else if (resp instanceof InferenceRouter.InferError oops) {
                            ctx.status(500).json(Map.of("error", oops.error()));
                        } else {
                            ctx.status(500).json(Map.of("error", "unexpected response: " + resp));
                        }
                    } catch (Exception e) {
                        ctx.status(500).json(Map.of("error", "ask failed: " + e.getMessage()));
                    }
                });

                // Query today's metered usage for a partner zone.
                cfg.routes.get("/api/test/meter/usage", ctx -> {
                    var partnerZone = ctx.queryParam("partnerZone");
                    if (partnerZone == null) { ctx.status(400).json(Map.of("error", "partnerZone required")); return; }
                    var metering = MeteringService.get();
                    if (metering == null) { ctx.status(503).json(Map.of("error", "MeteringService unavailable")); return; }
                    var smallUsage = metering.usageToday(partnerZone,
                        ReferenceRates.SERVICE_INFERENCE_SMALL);
                    var largeUsage = metering.usageToday(partnerZone,
                        ReferenceRates.SERVICE_INFERENCE_LARGE);
                    var tokens = metering.inferenceTokensToday(partnerZone);
                    ctx.json(Map.of(
                        "partnerZone", partnerZone,
                        "inferenceTokensToday", tokens,
                        "smallInferenceCU", smallUsage.totalCU(),
                        "largeInferenceCU", largeUsage.totalCU(),
                        "smallEvents", smallUsage.eventCount(),
                        "largeEvents", largeUsage.eventCount()));
                });
            }

            // Shadow log REST endpoint
            cfg.routes.get("/api/shadow", ctx -> {
                var shadow = ShadowLog.get();
                if (shadow == null) { ctx.status(404).result("Shadow log not enabled"); return; }
                int n = ctx.queryParamAsClass("n", Integer.class).getOrDefault(10);
                ctx.json(shadow.recent(n));
            });
            cfg.routes.get("/api/shadow/latest", ctx -> {
                var shadow = ShadowLog.get();
                if (shadow == null) { ctx.status(404).result("Shadow log not enabled"); return; }
                var entry = shadow.latest();
                if (entry == null) { ctx.status(204).result("No entries yet"); return; }
                ctx.json(entry);
            });

            // Inference routes
            if (finalInferenceRouter != null) {
                new InferenceRoutes(system, finalInferenceRouter).register(cfg.routes);
            }

            // OpenRouter OAuth (PKCE). Callback URL must be one of OR's allowed
            // forms (https:443, https:3000, or http://localhost:3000); we use
            // the steward's public HTTPS host. Returns "no_callback_url" if
            // WYRDSEKAI_PUBLIC_HOST isn't set, so this is a no-op on dev nodes
            // without public exposure.
            var publicHost = System.getenv("WYRDSEKAI_PUBLIC_HOST");
            String openRouterCallback = null;
            if (publicHost != null && !publicHost.isBlank()) {
                openRouterCallback = "https://" + publicHost.trim()
                    + "/api/oauth/openrouter/callback";
            }
            // Wire the active provider so the callback can hot-install the
            // key into the running router — no restart needed for the
            // current process. The persisted key file is what survives a
            // restart, so the steward still wires the conf for durability.
            new OpenRouterOAuthRoutes(
                    openRouterCallback,
                    StaticApiKeyProvider.getActive())
                .register(cfg.routes);

            // signed identity outbox records (NIP-65 analogue).
            // Public reads (records are world-readable by design). Writes are
            // authenticated by the record's own Ed25519 signature.
            new IdentityOutboxRoutes(
                    new IdentityOutboxStore(jdbcUrl))
                .register(cfg.routes);

            // Mesh update routes
            var updateConfig = UpdateConfig.fromEnv();
            UpdateChannelPoller updatePoller = null;
            if (updateConfig.enabled()) {
                updatePoller = new UpdateChannelPoller(
                    updateConfig.channelUrl(), updateConfig.checkInterval(),
                    updateConfig.releasePublicKey());
                updatePoller.start(Duration.ofMinutes(1));
            }
            new UpdateRoutes(updateConfig, updatePoller)
                .register(cfg.routes);

            // Backup route (manual trigger)
            final var finalNodeIdentityPath =
                SystemPaths.dataDir().resolve("node-identity.json");
            // Mirrors the scheduled-backup extra-dirs list above —
            // see comment there for why these four and why adapters/ isn't.
            final var finalExtraBackupDirs = List.of(
                SystemPaths.dataDir().resolve("agents"),
                SystemPaths.dataDir().resolve("classifiers"),
                SystemPaths.dataDir().resolve("souls"),
                SystemPaths.dataDir().resolve("substrate"));
            if (finalBackupOrchestrator != null) {
                cfg.routes.post("/api/backup/snapshot", ctx -> {
                    var result = finalBackupOrchestrator.snapshotAll(
                        finalDbPath, finalSearchDir, finalNodeIdentityPath,
                        finalExtraBackupDirs);
                    if (result.isPresent()) {
                        var m = result.get();
                        ctx.json(Map.of(
                            "backupId", m.backupId(),
                            "location", m.location().toString(),
                            "sizeBytes", m.sizeBytes(),
                            "timestamp", m.timestamp().toString()));
                    } else {
                        ctx.status(500).json(Map.of("error", "Backup failed"));
                    }
                });
                cfg.routes.get("/api/backup/list", ctx -> {
                    var dbSnapshots = finalBackupOrchestrator.listSnapshots();
                    var searchSnapshots = finalBackupOrchestrator.listSearchSnapshots();
                    ctx.json(Map.of(
                        "database", dbSnapshots.stream().map(s -> Map.of(
                            "backupId", s.backupId(),
                            "sizeBytes", s.sizeBytes(),
                            "timestamp", s.timestamp().toString())).toList(),
                        "search", searchSnapshots.stream().map(s -> Map.of(
                            "backupId", s.backupId(),
                            "sizeBytes", s.sizeBytes(),
                            "timestamp", s.timestamp().toString())).toList()));
                });
            }

            // Resident bridge routes — uses lazy companion lookup from static registry.
            if (finalResidentEntityId != null) {
                new ResidentRoutes(
                    finalResidentEntityId, finalResidentToken, system).register(cfg.routes);
                log.info("Resident bridge endpoints registered for entity '{}'",
                    finalResidentEntityId);
            }


            // Landing page
            cfg.routes.get("/", ctx -> {
                ctx.contentType("text/html").result("""
                    <!DOCTYPE html>
                    <html><head><title>Wyrdsekai</title>
                    <style>
                      body { font-family: monospace; background: #1a1a2e; color: #e0e0e0;
                             display: flex; flex-direction: column; align-items: center;
                             justify-content: center; min-height: 100vh; margin: 0; }
                      h1 { color: #c0a0ff; font-size: 2em; }
                      .info { max-width: 500px; line-height: 1.6; }
                      a { color: #80b0ff; }
                      code { background: #2a2a4e; padding: 2px 6px; border-radius: 3px; }
                    </style></head><body>
                    <h1>Wyrdsekai</h1>
                    <div class="info">
                      <p>This is a Wyrdsekai household node.</p>
                      <p><b>Connect via:</b></p>
                      <ul>
                        %s<li>Phone app &mdash; scan network or enter this address</li>
                        <li>SSH &mdash; <code>ssh -p %d user@%s</code></li>
                        <li>Telnet &mdash; <code>telnet %s %d</code></li>
                        <li>WebSocket &mdash; <code>ws://%s:%d/ws</code></li>
                      </ul>
                      <p><b>API:</b> <a href="/health">/health</a> &middot;
                         <a href="/api/pair/code">/api/pair/code</a> &middot;
                         <a href="/api/pair/devices">/api/pair/devices</a></p>
                    </div>
                    </body></html>
                    """.formatted(
                        webAppEnabled
                            ? "<li>Browser &mdash; <a href=\"/app/\">open the web client</a></li>\n                        "
                            : "",
                        landingSshPort, landingHostname,
                        landingHostname, landingTelnetPort, landingHostname, landingPort));
            });
        });
        app.start(port);
        healthRoutes.setReady(true);

        log.info("Wyrdsekai server listening on port {} (hostname: {})", port, hostname);

        // Start Telnet/GMCP adapter
        TelnetAdapter telnetAdapter = null;
        try {
            if (config.getBoolean("wyrdsekai.telnet.enabled")) {
                var tnPort = config.getInt("wyrdsekai.telnet.port");
                String tnBind = "127.0.0.1";
                try { tnBind = config.getString("wyrdsekai.telnet.bind"); } catch (Exception ignored) {}
                telnetAdapter = new TelnetAdapter();
                var telnetZoneId = WyrdConfig.get().zoneId();
                telnetAdapter.setTransitContext(
                    telnetZoneId, sessionTransportHolder.get(), clientConnectionRegistry);
                telnetAdapter.start(tnPort, tnBind, system, authService, inviteService, wardService, inventoryService);
            } else {
                log.info("Telnet adapter disabled (wyrdsekai.telnet.enabled=false). "
                    + "Set WYRDSEKAI_TELNET_ENABLED=true to opt in.");
            }
        } catch (Exception e) {
            log.info("Telnet adapter not configured (disabled): {}", e.getMessage());
        }

        // Start SSH adapter
        SshAdapter sshAdapter = null;
        try {
            if (config.getBoolean("wyrdsekai.ssh.enabled")) {
                var sshP = config.getInt("wyrdsekai.ssh.port");
                sshAdapter = new SshAdapter();
                // Cross-zone transit parity: make SSH clients first-class
                // travelers alongside WebSocket.
                var sshZoneId = WyrdConfig.get().zoneId();
                sshAdapter.setTransitContext(
                    sshZoneId, sessionTransportHolder.get(), clientConnectionRegistry);
                // SSH parity for scripted Study furnishings (Embers, Board, Quill, …):
                // without this, `use embers` / `examine board` fall through to room
                // handling with [not_found]. bug #1.
                sshAdapter.setScriptContext(homeClientShared, federationServiceRef, bondRitual);
                sshAdapter.start(sshP, system, authService, inviteService, wardService, inventoryService);
            }
        } catch (Exception e) {
            log.info("SSH adapter not configured (disabled): {}", e.getMessage());
        }

        // Shutdown hook
        final var telnet = telnetAdapter;
        final var ssh = sshAdapter;
        final var backupSched = backupScheduler;
        final var backupOrch = backupOrchestrator;
        final var lucene = luceneStore;
        final var finalEventBusPlugins = eventBusPlugins;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down...");
            healthRoutes.setReady(false);
            EventBusPluginLoader.shutdownAll(finalEventBusPlugins);
            if (lucene != null) {
                try { lucene.close(); log.info("WyrdLuceneStore closed"); }
                catch (Exception e) { log.warn("WyrdLuceneStore close failed: {}", e.getMessage()); }
            }
            if (backupSched != null) {
                backupSched.shutdown();
                log.info("Backup scheduler stopped");
            }
            dormancyScheduler.shutdown();
            if (ssh != null) ssh.stop();
            if (telnet != null) telnet.stop();
            app.stop();
            // Clear the process-wide CapabilityRegistry so test-runs and JVM
            // restarts in the same process leave a clean slate. Harmless if
            // already null.
            try {
                CapabilityRegistry.setActive(null);
            } catch (Exception e) {
                log.debug("CapabilityRegistry clear failed: {}", e.getMessage());
            }
            system.terminate();
            try {
                system.getWhenTerminated().toCompletableFuture().get(10, TimeUnit.SECONDS);
                log.info("Actor system terminated cleanly");
            } catch (Exception e) {
                log.warn("Actor system termination timed out: {}", e.getMessage());
            }
        }));
    }

    /**
     * A loaded soul seed: profile with DID bound + home room.
     */
    private record SoulSeed(AgentProfile profile, String roomId) {}

    /**
     * Load pre-forged soul manifests from a directory.
     *
     * Checks WYRDSEKAI_SOUL_DIR env var, then ~/.wyrdsekai/souls/,
     * then falls back to legacy single-file paths (WYRDSEKAI_SOUL_SEED,
     * ./soul-manifest.json, ./ma-soul-manifest.json).
     *
     * Each manifest JSON is loaded into SoulStore. The agent's home room
     * is read from worldKnowledge["homeRoom"] (default: "nexus").
     *
     * @return list of soul seeds to spawn (empty = use default companion)
     */
    /**
     * W16 — the {@code POST /api/recipes/run} handler: steward-token gate in
     * front of {@link #dispatchForcedRecipeRun}. Extracted (package-visible)
     * so {@code RecipesRunAuthMatrixTest} can mount the REAL production
     * handler on a bare Javalin app and pin the auth matrix
     * (no token → 401, member → 403, steward → past auth).
     */
    static Handler recipesRunHandler(AuthService authService, String jdbcUrl) {
        return ctx -> {
            var token = AuthRoutes.extractToken(ctx);
            if (token == null) {
                ctx.status(401).json(Map.of(
                    "error", "Authorization required"));
                return;
            }
            var caller = authService.validateSession(token);
            if (caller.isEmpty()) {
                ctx.status(401).json(Map.of(
                    "error", "Invalid or expired session"));
                return;
            }
            if (!"steward".equals(caller.get().role())) {
                ctx.status(403).json(Map.of(
                    "error", "Steward role required"));
                return;
            }
            dispatchForcedRecipeRun(ctx, jdbcUrl);
        };
    }

    /**
     * Shared dispatch for force-running a recipe on a companion, outside the
     * cron window and without the LLM action-parser path. Backs BOTH:
     * <ul>
     *   <li>{@code POST /api/test/run_recipe} — WYRDSEKAI_TEST_MODE harness
     *       route, unauthenticated (test installs only);</li>
     *   <li>{@code POST /api/recipes/run} — W16 production route, registered
     *       unconditionally and gated on a steward session token (the caller
     *       does the auth check before delegating here).</li>
     * </ul>
     * Accepts either a local entityId ("companion-wyrd") or a canonical DID
     * ("did:key:...") — the actor registry is keyed by entityId, so DIDs are
     * resolved via {@link CompanionRegistry}. Mirrors RecipeCronTrigger's
     * {@code params.agent_did} seeding so the recipe runtime can satisfy its
     * required_params.
     */
    private static void dispatchForcedRecipeRun(Context ctx, String jdbcUrl)
            throws Exception {
        var body = new ObjectMapper().readTree(ctx.body());
        var entityId = body.path("entityId").asText(null);
        var recipeName = body.path("recipe").asText(null);
        var reason = body.path("reason").asText("test-harness");
        if (entityId == null || recipeName == null) {
            ctx.status(400).json(Map.of(
                "error", "entityId and recipe required"));
            return;
        }
        String resolvedDid = null;
        if (entityId.startsWith("did:")) {
            resolvedDid = entityId;
            var resolved = new CompanionRegistry(jdbcUrl)
                .get(entityId)
                .map(CompanionRegistry.Row::entityId)
                .orElse(null);
            if (resolved == null) {
                ctx.status(404).json(Map.of(
                    "error", "no companion for did " + entityId));
                return;
            }
            entityId = resolved;
        }
        var paramsNode = body.path("params");
        var params = new LinkedHashMap<String, Object>();
        if (paramsNode.isObject()) {
            paramsNode.fields().forEachRemaining(e -> {
                var v = e.getValue();
                params.put(e.getKey(),
                    v.isNumber() ? v.numberValue()
                        : v.isBoolean() ? v.booleanValue() : v.asText());
            });
        }
        if (resolvedDid != null && !params.containsKey("agent_did")) {
            params.put("agent_did", resolvedDid);
        }
        var companion = ZoneGuardian
            .getCompanionRef(null, entityId);
        if (companion == null) {
            ctx.status(404).json(Map.of(
                "error", "no companion for entityId " + entityId));
            return;
        }
        companion.tell(new CompanionActor
            .TestRequestRecipe(recipeName, params, reason));
        ctx.json(Map.of(
            "status", "dispatched",
            "entityId", entityId,
            "recipe", recipeName,
            "reason", reason));
    }

    /**
     * Track-C C6 — flatten a {@link
     * org.wyrdsekai.core.recipe.QueuedRecipe} into a JSON-friendly map
     * for the {@code /api/recipes/*} read endpoints. Kept here (and not
     * on the record) because Jackson on a record-with-Map<String, Object>
     * is fine but downstream CLI/i18n consumers want stable string keys.
     */
    private static Map<String, Object> serializeQueueRow(
            QueuedRecipe q) {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", q.id());
        row.put("recipeId", q.recipeId());
        row.put("agentDid", q.agentDid());
        row.put("status", q.status().name());
        row.put("triggerSource", q.triggerSource().name());
        row.put("triggerReason", q.triggerReason());
        row.put("cadenceTier", q.cadenceTier().name());
        row.put("consecutiveSuccesses", q.consecutiveSuccesses());
        row.put("enqueuedAt", q.enqueuedAt() == null ? null : q.enqueuedAt().toString());
        row.put("attemptedAt", q.attemptedAt() == null ? null : q.attemptedAt().toString());
        row.put("completedAt", q.completedAt() == null ? null : q.completedAt().toString());
        row.put("runId", q.runId());
        row.put("message", q.message());
        row.put("params", q.params());
        return row;
    }

    private static List<SoulSeed> loadSoulSeeds(SoulStore soulStore) {
        var seeds = new ArrayList<SoulSeed>();
        var mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        // Same guard as SqlSoulStore: a field this build doesn't know must not
        // make a soul unreadable. Live on the household node 2026-08-08, still
        // failing on dev14 because this is a SECOND mapper the earlier fix didn't
        // reach — both companions' CfC substrate seeds have been silently
        // skipped ("Unrecognized field \"w1\"") since the format gained a field,
        // so their learned substrate weights were never restored on any boot.
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        // Collect manifest files to load
        var manifestFiles = new ArrayList<Path>();

        // Priority 1: WYRDSEKAI_SOUL_DIR (directory of manifests)
        var soulDir = WyrdConfig.get().soulDir();
        if (soulDir == null) {
            // Default directory
            var defaultDir = Path.of(System.getProperty("user.home"), ".wyrdsekai", "souls");
            if (Files.isDirectory(defaultDir)) soulDir = defaultDir.toString();
        }
        if (soulDir != null && Files.isDirectory(Path.of(soulDir))) {
            try (var stream = Files.list(Path.of(soulDir))) {
                stream.filter(p -> p.toString().endsWith(".json"))
                    .sorted()
                    .forEach(manifestFiles::add);
            } catch (Exception e) {
                log.warn("Failed to scan soul directory {}: {}", soulDir, e.getMessage());
            }
        }

        // Priority 2: Legacy single-file (WYRDSEKAI_SOUL_SEED or default paths)
        if (manifestFiles.isEmpty()) {
            var seedPath = WyrdConfig.get().soulSeed();
            var candidates = seedPath != null
                ? List.of(Path.of(seedPath))
                : List.of(Path.of("soul-manifest.json"), Path.of("ma-soul-manifest.json"));
            for (var path : candidates) {
                if (Files.exists(path)) {
                    manifestFiles.add(path);
                    break; // legacy mode: only one
                }
            }
        }

        // Load each manifest
        for (var path : manifestFiles) {
            try {
                var manifest = mapper.readValue(path.toFile(), SoulManifest.class);

                // souls/ hosts more than manifests: CFC fast-weight cells save
                // as <did>_cfc.json / base_cfc.json alongside them, and the
                // lenient mapper parses those into an all-null manifest. Not a
                // manifest → not an error; skip without ceremony.
                if (manifest.did() == null || manifest.did().isBlank()
                        || manifest.profile() == null) {
                    log.debug("Skipping non-manifest JSON in souls dir: {}",
                        path.getFileName());
                    continue;
                }
                log.info("Loading soul seed: {}", path.toAbsolutePath());

                // Store in SoulStore so CompanionActor.initializeSoul() finds it
                if (!soulStore.exists(manifest.did())) {
                    soulStore.store(manifest);
                    log.info("  Stored: did={}, name={}", manifest.did(), manifest.profile().name());
                } else {
                    log.info("  Already in store: did={}", manifest.did());
                }

                // Extract home room from worldKnowledge (default: "nexus")
                var homeRoom = manifest.worldKnowledge() != null
                    ? manifest.worldKnowledge().getOrDefault("homeRoom", "nexus")
                    : "nexus";

                // Build profile with DID bound
                var profile = manifest.profile().did() != null
                    ? manifest.profile()
                    : manifest.profile().withDid(manifest.did());

                seeds.add(new SoulSeed(profile, homeRoom));
            } catch (Exception e) {
                log.warn("Failed to load soul seed {}: {}", path, e.getMessage());
            }
        }

        if (!seeds.isEmpty()) {
            log.info("Loaded {} soul seed(s)", seeds.size());
        }
        return seeds;
    }

    /**
     * Start the soul seed watcher — monitors ~/.wyrdsekai/souls/incoming/ for new seed files.
     * When a seed JSON appears, auto-forges a companion and spawns it in the world.
     */
    @SuppressWarnings("unchecked")
    private static void startSoulSeedWatcher(SoulStore soulStore,
                                               ActorRef<InferenceRouter.Command> inferenceRouter,
                                               WorldDnaService worldDnaService,
                                               Path userScriptsDir,
                                               ActorRef<ForgeCommand> forgeActor,
                                               ActorSystem<?> system,
                                               AtomicReference<WyrdWebSocket> wsHandlerRef) {
        var soulDir = System.getenv().getOrDefault("WYRDSEKAI_SOUL_DIR",
            SystemPaths.soulsDir().toString());   // #data-dir (2026-07-19) — honor WYRDSEKAI_DATA_DIR
        var incomingDir = Path.of(soulDir, "incoming");

        var ollamaUrl = System.getenv().getOrDefault("WYRDSEKAI_OLLAMA_URL",
            System.getenv().getOrDefault("OLLAMA_URL", "http://localhost:11434"));
        var model = System.getenv().getOrDefault("WYRDSEKAI_MODEL",
            System.getenv().getOrDefault("OLLAMA_MODEL", "qwen2.5:7b"));
        var embeddingModel = System.getenv().getOrDefault("SOUL_EMBEDDING_MODEL", "all-minilm");

        var autoForge = new SoulAutoForge(
            ollamaUrl, model, embeddingModel, Path.of(soulDir))
            // Persist each forged soul's household secret to TheSafe (the secret
            // that decrypts its Ed25519 private key) so the soul keeps its own
            // signing capability instead of it being discarded at forge. Slot key
            // is namespaced per-DID. Skips silently if TheSafe isn't online.
            .secretPersister((did, secret) -> {
                try {
                    var safe = TheSafe.local();
                    if (safe != null) {
                        safe.storeSlot("soul-secret:" + did,
                            Base64.getEncoder().encodeToString(secret));
                        log.info("Persisted soul signing secret to TheSafe for {}", did);
                    } else {
                        log.warn("TheSafe offline — soul signing secret for {} not persisted "
                            + "(manifest still signed; re-signing capability lost)", did);
                    }
                } catch (Exception e) {
                    log.warn("Failed to persist soul signing secret for {}: {}", did, e.getMessage());
                }
            });

        var watcher = new SoulSeedWatcher(incomingDir,
            autoForge.watcherCallback((manifestPath, seed) -> {
                // Load the forged manifest into SoulStore and spawn the companion
                try {
                    var mapper = new ObjectMapper();
                    mapper.registerModule(new JavaTimeModule());
                    // See the soul-seed loader above — same reason, same guard.
                    mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
                    var manifest = mapper.readValue(manifestPath.toFile(),
                        SoulManifest.class);

                    if (!soulStore.exists(manifest.did())) {
                        soulStore.store(manifest);
                    }

                    var homeRoom = manifest.worldKnowledge() != null
                        ? manifest.worldKnowledge().getOrDefault("homeRoom", "nexus")
                        : "nexus";

                    var profile = manifest.profile().did() != null
                        ? manifest.profile()
                        : manifest.profile().withDid(manifest.did());

                    if (inferenceRouter != null) {
                        var cmdRouter = wsHandlerRef.get(); // may be null if server not yet ready
                        ((ActorSystem<ZoneGuardian.Command>) system).tell(
                            new ZoneGuardian.SpawnCompanion(
                                profile, homeRoom, inferenceRouter, worldDnaService,
                                userScriptsDir, soulStore, forgeActor, cmdRouter));
                        log.info("Auto-forged companion spawned: {} → room '{}'",
                            profile.name(), homeRoom);
                    }
                } catch (Exception e) {
                    log.error("Failed to spawn auto-forged companion: {}", e.getMessage());
                }
            }));

        watcher.start();
    }

    private static ActorRef<InferenceRouter.Command> spawnInferenceRouter(
            ActorSystem<?> system, Config config,
            ResourceMeter resourceMeter) {
        try {
            var inferenceConfig = InferenceConfig.fromConfig(
                    config.getConfig("wyrdsekai.inference"));

            var betweenEnabled = WyrdConfig.get().betweenEnabled();
            if (inferenceConfig.backends().isEmpty() && !betweenEnabled) {
                log.info("No inference backends configured — inference disabled");
                return null;
            }
            if (inferenceConfig.backends().isEmpty()) {
                log.info("No local inference backends — will discover remote via mesh");
            }

            for (var b : inferenceConfig.backends()) {
                log.info("Inference backend: {} (type={}, priority={}, url={})",
                        b.name(), b.type(), b.priority(), b.url());
            }
            // Two names for one server is a diagram, not an architecture. The
            // household node ran five weeks with llama-server and llama-voice
            // both on :8201 and nobody read it off this log (2026-09-02).
            var byUrl = new HashMap<String, List<String>>();
            for (var b : inferenceConfig.backends()) {
                if (b.url() == null) continue;
                byUrl.computeIfAbsent(b.url().strip().toLowerCase(Locale.ROOT)
                        .replace("localhost", "127.0.0.1"), k -> new ArrayList<>())
                    .add(b.name());
            }
            byUrl.forEach((url, names) -> {
                if (names.size() > 1) {
                    log.warn("Inference backends {} all point at {} — they are ONE server; "
                        + "the drive/voice split is nominal on this node", names, url);
                }
            });

            // Build capability registry from configured backends
            var capabilityRegistry = CapabilityRegistry
                    .fromBackends(inferenceConfig.backends());

            // Register tier-specific models from env vars (overrides auto-detection)
            var routineModel = WyrdConfig.get().modelRoutine();
            var complexModel = WyrdConfig.get().modelComplex();
            if (routineModel != null && !routineModel.isBlank()) {
                // Find a backend that has this model, or use first backend
                var backendName = inferenceConfig.backends().isEmpty()
                    ? "default" : inferenceConfig.backends().getFirst().name();
                for (var b : inferenceConfig.backends()) {
                    if (b.models().contains(routineModel)) { backendName = b.name(); break; }
                }
                var tier = inferenceConfig.backends().isEmpty()
                    ? "local" : CapabilityRegistry.inferTier(
                        inferenceConfig.backends().getFirst());
                capabilityRegistry.register(new CapabilityRegistry
                    .CapabilityEntry("quick", backendName, routineModel, tier, 1));
                log.info("Registered ROUTINE model: {} on backend {}", routineModel, backendName);
            }
            if (complexModel != null && !complexModel.isBlank()) {
                var backendName = inferenceConfig.backends().isEmpty()
                    ? "default" : inferenceConfig.backends().getFirst().name();
                for (var b : inferenceConfig.backends()) {
                    if (b.models().contains(complexModel)) { backendName = b.name(); break; }
                }
                var tier = inferenceConfig.backends().isEmpty()
                    ? "cloud" : CapabilityRegistry.inferTier(
                        inferenceConfig.backends().getLast());
                capabilityRegistry.register(new CapabilityRegistry
                    .CapabilityEntry("reasoning", backendName, complexModel, tier, 1));
                log.info("Registered COMPLEX model: {} on backend {}", complexModel, backendName);
            }
            log.info("Capability registry: {}", capabilityRegistry.availableCapabilities());

            // publish the registry as a process-wide singleton so
            // non-actor callers (prompt assembly, capability gates) can query
            // hasCapableBackend(...) without going through the InferenceRouter actor.
            CapabilityRegistry.setActive(capabilityRegistry);

            // Build API key provider from environment variables. Always
            // register as the active singleton so runtime mutators (OAuth
            // callbacks, key chest) can install keys without a restart, even
            // when boot env had nothing configured.
            var apiKeyProvider = StaticApiKeyProvider
                    .fromEnvironment();
            StaticApiKeyProvider.setActive(apiKeyProvider);
            var configuredKeys = apiKeyProvider.configuredBackends();
            if (!configuredKeys.isEmpty()) {
                log.info("API keys configured for backends: {}", configuredKeys);
            }

            // Data attribution (2026-07-09): stamp durable writes with the active model,
            // resolved to its RELEASE version via the node's models-manifest (filenames
            // alone are version-blind across releases).
            ModelAttribution.set("drive=" + ModelAttribution.withVersion(
                SystemPaths.dataDir().resolve("models"), inferenceConfig.defaultModel()));
            var router = system.<InferenceRouter.Command>systemActorOf(
                    InferenceRouter.create(inferenceConfig.backends(),
                            inferenceConfig.defaultModel(), resourceMeter,
                            capabilityRegistry,
                            apiKeyProvider,
                            inferenceConfig.healthCheckInterval()),
                    "inference-router", Props.empty());

            log.info("InferenceRouter spawned with {} backend(s)",
                    inferenceConfig.backends().size());
            return router;

        } catch (Exception e) {
            log.warn("Inference setup failed (inference disabled): {}", e.getMessage());
            return null;
        }
    }

    private static volatile List<ZoneGuardian.RoomSeed> cachedFoundationRoomSeeds;
    private static List<ZoneGuardian.RoomSeed> foundationRoomSeeds() {
        if (cachedFoundationRoomSeeds == null) {
            cachedFoundationRoomSeeds = FoundationRoomLoader.load();
        }
        return cachedFoundationRoomSeeds;
    }

    private static ActorRef<BetweenActor.Command> spawnBetween(
            ActorSystem<?> system, Config config,
            SoulStore soulStore,
            NatsBridge preConnectedNats) {
        try {
            // Check if Between is enabled via config or --cluster flag
            var betweenEnabled = config.hasPath("wyrdsekai.between.enabled")
                && config.getBoolean("wyrdsekai.between.enabled");

            if (!betweenEnabled) {
                log.info("Between disabled (single-node mode). Use --cluster to enable.");
                return null;
            }

            var betweenConfig = config.getConfig("wyrdsekai.between");
            var natsUrl = betweenConfig.getString("nats.url");
            var natsAutoStart = betweenConfig.getBoolean("nats.auto-start");
            var natsExecutable = betweenConfig.getString("nats.executable");
            var natsClientPort = betweenConfig.getInt("nats.client-port");
            var natsMonitorPort = betweenConfig.getInt("nats.monitor-port");
            var mdnsEnabled = betweenConfig.getBoolean("discovery.mdns");
            var zoneId = betweenConfig.getString("zone-id");
            var arteryPort = config.getInt("pekko.remote.artery.canonical.port");

            var seedNodes = betweenConfig.hasPath("discovery.seed-nodes")
                ? betweenConfig.getStringList("discovery.seed-nodes")
                : List.<String>of();

            var heartbeatInterval = betweenConfig.getDuration("heartbeat.interval");
            var probeInterval = betweenConfig.getDuration("probe.interval");

            // Relay config
            String relayUrlBetween = null;
            String relayTokenBetween = null;
            try {
                if (betweenConfig.hasPath("relay.enabled")
                        && betweenConfig.getBoolean("relay.enabled")) {
                    var rUrl = betweenConfig.getString("relay.url");
                    var rToken = betweenConfig.getString("relay.token");
                    if (rUrl != null && !rUrl.isEmpty()) {
                        relayUrlBetween = rUrl;
                        relayTokenBetween = rToken;
                    }
                }
            } catch (Exception e) {
                log.debug("Between relay config not available: {}", e.getMessage());
            }

            // the zone's full relay leg set comes
            // from WyrdConfig (numbered WYRDSEKAI_RELAY_URL[_n] / relay.url[_n]).
            // Leg 0 mirrors relayUrlBetween; legs 2..N are the additional homes.
            // The privacy rail (public-leg-under-private-floor drop) is already
            // applied inside relayLegs(), so this list is safe to home on as-is.
            var relayLegs = WyrdConfig.get().relayLegs();

            var betweenCfg = new BetweenActor.BetweenConfig(
                true, natsUrl, natsAutoStart, natsExecutable,
                natsClientPort, natsMonitorPort,
                mdnsEnabled, seedNodes,
                heartbeatInterval, probeInterval, arteryPort,
                relayUrlBetween, relayTokenBetween, relayLegs
            );

            var dataDir = SystemPaths.dataDir();

            var betweenActor = system.<BetweenActor.Command>systemActorOf(
                BetweenActor.create(), "between-actor", Props.empty());

            var zoneName = betweenConfig.hasPath("zone-name")
                ? betweenConfig.getString("zone-name") : zoneId;
            betweenActor.tell(new BetweenActor.StartBetween(
                zoneId, zoneName, dataDir, betweenCfg,
                config.getString("slick.db.url"), soulStore, preConnectedNats));

            // Wave 2: Load room capability requirements and push to PlacementEngine
            var roomRequirements = FoundationRoomLoader.loadRoomRequirements();
            if (!roomRequirements.isEmpty()) {
                AskPattern
                    .<BetweenActor.Command, PlacementEngine>ask(
                        betweenActor,
                        ref -> new BetweenActor.GetPlacementEngine(ref),
                        Duration.ofSeconds(5),
                        system.scheduler())
                    .whenComplete((engine, err) -> {
                        if (engine != null) {
                            roomRequirements.forEach(engine::setRoomRequirements);
                            log.info("PlacementEngine: loaded {} room capability requirements",
                                roomRequirements.size());
                        }
                    });
            }

            // Set node capabilities from environment (inference backend, GPU, network)
            InferenceConfig finalInfConfig = null;
            try {
                finalInfConfig = InferenceConfig.fromConfig(config.getConfig("wyrdsekai.inference"));
                if (finalInfConfig.backends().isEmpty()) finalInfConfig = null;
            } catch (Exception ignored) {}
            final var infConfig = finalInfConfig;
            AskPattern
                .<BetweenActor.Command, NodeCapabilities>ask(
                    betweenActor,
                    ref -> new BetweenActor.GetNodeCapabilities(ref),
                    Duration.ofSeconds(5),
                    system.scheduler())
                .whenComplete((nodeCaps, err) -> {
                    if (nodeCaps != null) {
                        // Detect LAN IP — prefer a real site-local interface
                        // over the 127.0.1.1 loopback alias getLocalHost()
                        // returns on many Linux boxes (would advertise an
                        // unreachable address to household peers).
                        try {
                            var lanIp = resolveLanIp();
                            if (lanIp != null) nodeCaps.setLanIp(lanIp);
                        } catch (Exception ignored) {}
                        nodeCaps.setHttpPort(Integer.parseInt(
                            System.getenv().getOrDefault("WYRDSEKAI_PORT", "7070")));
                        // Search engine
                        nodeCaps.setHasSearchEngine(
                            WyrdConfig.get().searxngUrl() != null);
                        // Oracle — advertise prediction capability to the mesh
                        // when the sidecar on :7073 is actually reachable, not
                        // merely when ORACLE_URL is exported. Native installs
                        // (.deb/.pkg/.msi) now bundle + auto-start oracle-core
                        // but don't set ORACLE_URL, so the old env check left
                        // them falsely advertising no prediction capability.
                        // A short synchronous probe keeps this race-free at
                        // capability-population time (the bridge init at startup
                        // is unconditional, so getInstance() is non-null here).
                        boolean oracleUp = false;
                        try {
                            var ob = OracleBridge.getInstance();
                            oracleUp = ob != null && ob.isHealthy()
                                .get(2, TimeUnit.SECONDS);
                        } catch (Exception ignored) {}
                        nodeCaps.setHasOracleEngine(oracleUp);
                        // Inference endpoints from configured backends
                        if (infConfig != null && !infConfig.backends().isEmpty()) {
                            var endpoints = new ArrayList<
                                NodeCapabilities.InferenceEndpoint>();
                            for (var b : infConfig.backends()) {
                                var url = b.url();
                                var models = b.models();
                                var modelName = models != null && !models.isEmpty()
                                    ? models.getFirst() : "default";
                                endpoints.add(new NodeCapabilities
                                    .InferenceEndpoint(
                                        b.type(), modelName, url,
                                        1, // maxConcurrency
                                        8192, // ctxSize (default)
                                        true, // supportsTools
                                        true  // supportsStreaming
                                    ));
                                nodeCaps.setInferenceBackend(b.type());
                                nodeCaps.setInferenceModelLoaded(true);
                            }
                            nodeCaps.setInferenceEndpoints(endpoints);
                        }
                        log.info("NodeCapabilities: {} capabilities: {}, endpoints: {}",
                            nodeCaps.getNodeId(), nodeCaps.getCapabilities(),
                            nodeCaps.snapshot().inferenceEndpoints().size());
                    }
                });

            log.info("Between actor spawned — zone: {}", zoneId);
            return betweenActor;

        } catch (Exception e) {
            // Default installs bake WYRDSEKAI_BETWEEN_ENABLED=true, so if we land
            // here the operator EXPECTED mesh mode and a config error silently
            // demoted the whole node to single-node (2026-07-18). WARN with the
            // cause — an info line buried this class of misconfiguration.
            boolean expected = "true".equalsIgnoreCase(
                System.getenv().getOrDefault("WYRDSEKAI_BETWEEN_ENABLED", "true"));
            if (expected) {
                log.warn("Between (mesh) was enabled but failed to start — falling back to "
                    + "single-node. Some features (federation, soul relocation, multi-node "
                    + "placement) will be OFF. Cause: {}", e.toString());
            } else {
                log.info("Between not configured (single-node mode): {}", e.getMessage());
            }
            return null;
        }
    }

    /**
     * Load config, applying PostgreSQL Slick overrides if backend is "postgresql".
     * <p>
     * For single-node mode (default): artery port is 0 in config, so we find a free port,
     * set it as the artery port, and auto-construct seed-nodes to self-join. This avoids
     * port conflicts while keeping ClusterSharding functional.
     * <p>
     * For cluster mode (--cluster): artery port is set by Main.main() via system property
     * WYRDSEKAI_ARTERY_PORT (default 25520), so seed-nodes are constructed from that.
     */
    private static Config initializeConfig() {
        var base = ConfigFactory.load();

        // Auto-construct seed-nodes from artery host/port (self-seed)
        var arteryHost = base.getString("pekko.remote.artery.canonical.hostname");
        var arteryPort = base.getInt("pekko.remote.artery.canonical.port");

        // If port is 0 (single-node default), find a free port to avoid conflicts
        if (arteryPort == 0) {
            arteryPort = findFreePort();
            log.info("Single-node artery port: {} (auto-allocated)", arteryPort);
        }

        var seedNode = "pekko://wyrdsekai@" + arteryHost + ":" + arteryPort;
        var seedOverride = ConfigFactory.parseMap(Map.of(
            "pekko.cluster.seed-nodes", List.of(seedNode),
            "pekko.remote.artery.canonical.port", arteryPort
        ));
        base = seedOverride.withFallback(base);

        var backend = base.getString("wyrdsekai.db.backend");

        if ("postgresql".equals(backend)) {
            var pgConfig = base.getConfig("wyrdsekai.db.postgresql");
            var slickOverrides = ConfigFactory.parseMap(Map.of(
                "slick.profile", "slick.jdbc.PostgresProfile$",
                "slick.db.url", pgConfig.getString("url"),
                "slick.db.user", pgConfig.getString("user"),
                "slick.db.password", pgConfig.getString("password"),
                "slick.db.driver", "org.postgresql.Driver",
                "slick.db.connectionPool", "HikariCP",
                "slick.db.numThreads", "4",
                "slick.db.maxConnections", "10"
            ));
            log.info("Storage backend: PostgreSQL ({})", pgConfig.getString("url"));
            return slickOverrides.withFallback(base);
        }

        log.info("Storage backend: SQLite");
        return base;
    }

    /**
     * Initialize the database schema based on configured backend.
     */
    private static String initializeDatabase(Config config) {
        var backend = config.getString("wyrdsekai.db.backend");

        if ("postgresql".equals(backend)) {
            var pgConfig = config.getConfig("wyrdsekai.db.postgresql");
            if (pgConfig.getString("password").isBlank()) {
                log.warn("PostgreSQL password is empty — set WYRDSEKAI_PG_PASSWORD for production");
            }
            return SchemaInitializer.initializePostgres(
                pgConfig.getString("url"),
                pgConfig.getString("user"),
                pgConfig.getString("password"));
        }

        var dbPath = Path.of(config.getString("wyrdsekai.db.path"));
        // Staged restore (maintenance dial / key chest): if the steward
        // staged a snapshot restore before this restart, swap it in BEFORE
        // the schema initializer opens the db. No marker → one file-stat
        // no-op; any failure leaves the current db in place and parks the
        // marker as restore-staged.failed.json.
        MaintenanceService.applyStagedRestoreIfAny(SystemPaths.dataDir(), dbPath);
        // Data-durability (2026-07-09): stamp data-version.json + refuse to open a data dir
        // whose schema is NEWER than this binary (WYRDSEKAI_ALLOW_DOWNGRADE=true overrides).
        DataVersion.stampAndGuard(SystemPaths.dataDir());
        return SchemaInitializer.initialize(dbPath);
    }

    /** Ask BetweenActor for topology description (blocking, short timeout). */
    private static String askTopology(ActorRef<BetweenActor.Command> betweenActor,
                                       ActorSystem<?> system) {
        try {
            var snapshot = AskPattern.<BetweenActor.Command, BetweenActor.TopologySnapshot>ask(
                betweenActor,
                ref -> new BetweenActor.GetTopology(ref),
                Duration.ofSeconds(2),
                system.scheduler()
            ).toCompletableFuture().get(2, TimeUnit.SECONDS);
            return snapshot.description();
        } catch (Exception e) {
            return "Topology unavailable";
        }
    }

    /** Ask BetweenActor for connected node count (blocking, short timeout). */
    private static int askTopologyNodeCount(ActorRef<BetweenActor.Command> betweenActor,
                                             ActorSystem<?> system) {
        try {
            var snapshot = AskPattern.<BetweenActor.Command, BetweenActor.TopologySnapshot>ask(
                betweenActor,
                ref -> new BetweenActor.GetTopology(ref),
                Duration.ofSeconds(2),
                system.scheduler()
            ).toCompletableFuture().get(2, TimeUnit.SECONDS);
            return snapshot.connectedNodes();
        } catch (Exception e) {
            return 0;
        }
    }

    /** Ask CountingHouseActor for economy status (blocking, short timeout). */
    private static String askEconomyStatus(ActorRef<CountingHouseCommand> countingHouse,
                                            ActorSystem<?> system) {
        try {
            var state = AskPattern.<CountingHouseCommand, CountingHouseState>ask(
                countingHouse,
                ref -> new CountingHouseCommand.GetState(ref),
                Duration.ofSeconds(2),
                system.scheduler()
            ).toCompletableFuture().get(2, TimeUnit.SECONDS);
            return state.describe();
        } catch (Exception e) {
            return "Economy data unavailable";
        }
    }

    /** Ask CountingHouseActor for all reputations (blocking, short timeout). */
    private static String askAllReputations(ActorRef<CountingHouseCommand> countingHouse,
                                             ActorSystem<?> system) {
        try {
            return AskPattern.<CountingHouseCommand, String>ask(
                countingHouse,
                ref -> new CountingHouseCommand.QueryAllReputations(ref),
                Duration.ofSeconds(2),
                system.scheduler()
            ).toCompletableFuture().get(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "Reputation data unavailable";
        }
    }

    /** Ask CountingHouseActor for a specific entity's reputation (blocking, short timeout). */
    private static String askReputation(ActorRef<CountingHouseCommand> countingHouse,
                                         ActorSystem<?> system, String entityId) {
        try {
            var rep = AskPattern.<CountingHouseCommand, ReputationVector>ask(
                countingHouse,
                ref -> new CountingHouseCommand.QueryReputation(entityId, ref),
                Duration.ofSeconds(2),
                system.scheduler()
            ).toCompletableFuture().get(2, TimeUnit.SECONDS);
            return rep.describe();
        } catch (Exception e) {
            return "Reputation data unavailable for " + entityId;
        }
    }

    /**
     * Project a {@link BilateralAgreement} into a JSON-friendly view with live
     * quota + usage. Used by the Study's federation page and by any CLI/mobile
     * client that wants to show "how much of our cross-zone budget is gone today".
     */
    /**
     * Build a {@link org.wyrdsekai.core.naming.ZoneManifestV1} from the
     * household identity, zone registry, and env-configured presentation
     * fields. Publish it to the NATS-backed zone directory and schedule a
     * periodic refresh (default 1h).
     *
     * <p>Skips entirely if {@link org.wyrdsekai.core.naming.ZoneAddressResolverService}
     * has no resolvable zone label (legacy {@code WYRDSEKAI_ZONE_ID=home}
     * deployments) — the manifest requires a well-formed {@code zoneLabel}
     * per SPEC §2.2. Logs a guidance line in that case.</p>
     */
    private static void startZoneDirectoryPublish(
            NatsBridge nats,
            String zoneIdEnv,
            FederationService fedSvc) {
        var resolver = ZoneAddressResolverService.get();
        if (resolver == null) {
            log.warn("Zone directory publish skipped — ZoneAddressResolverService not initialised.");
            return;
        }
        var myZones = resolver.myZones();
        String zoneLabel = myZones.defaultLabel().orElse(null);
        if (zoneLabel == null) {
            log.warn("Zone directory publish skipped — no resolvable zone label in registry "
                + "(legacy WYRDSEKAI_ZONE_ID='{}'). Run `wyrd zones create <label>` to publish.",
                zoneIdEnv);
            return;
        }

        var natsBackend = new NatsZoneDirectory(nats);
        var wellKnownBackend = new WellKnownZoneDirectory();

        // Federated backend (§5.1 #2) — pulls from peer URLs periodically.
        // Peers come from WYRDSEKAI_DIRECTORY_PEERS (comma-separated base URLs).
        // Future: auto-derive from federation partners; today: operator-configured.
        var peersEnv = WyrdConfig.get().directoryPeers();
        var peerUrls = (peersEnv == null || peersEnv.isBlank())
            ? List.<String>of()
            : Arrays.stream(peersEnv.split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .toList();
        var federatedBackend = new FederatedZoneDirectory(() -> peerUrls);

        // Rendezvous backend (§5.1 #3) — POST /publish to N aggregators,
        // fetch/query/merge from the same list. Operators list them in
        // WYRDSEKAI_RENDEZVOUS_URLS (comma-separated base URLs).
        // NAT'd households MUST configure this to be discoverable.
        var rendezvousEnv = WyrdConfig.get().rendezvousUrls();
        var rendezvousUrls = (rendezvousEnv == null || rendezvousEnv.isBlank())
            ? List.<String>of()
            : Arrays.stream(rendezvousEnv.split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .toList();
        var rendezvousBackend = new RendezvousZoneDirectory(
            () -> rendezvousUrls);

        var composite = new CompositeZoneDirectory(
            List.of(natsBackend, wellKnownBackend, federatedBackend, rendezvousBackend));
        zoneDirectory = composite;
        // Expose to script surface (Atrium's world.discoverZones) + any
        // other downstream that would otherwise need to thread through
        // the constructor chain.
        ZoneDirectoryService.init(composite);
        log.info("Zone directory: composite of {} (federated peers: {}, rendezvous: {})",
            composite.backendNames(), peerUrls.size(), rendezvousUrls.size());

        // Start federated pull loop if peers are configured.
        if (!peerUrls.isEmpty()) {
            federatedBackend.start(FederatedZoneDirectory.DEFAULT_REFRESH_INTERVAL);
        }

        Runnable publishOnce = () -> {
            try {
                var manifest = buildZoneManifest(resolver.household().did(), zoneLabel,
                    fedSvc != null ? fedSvc.listAgreements(zoneIdEnv).size() : 0);
                localManifest = manifest;
                // Publish fans out to all backends; WellKnown is a no-op because
                // each zone self-publishes via its own /.well-known/wyrd-zone.
                composite.publish(manifest);
            } catch (Exception e) {
                log.warn("Zone directory publish failed: {}", e.getMessage());
            }
        };
        publishOnce.run();

        long refreshSec;
        try {
            refreshSec = Long.parseLong(System.getenv()
                .getOrDefault("WYRDSEKAI_DIRECTORY_REFRESH_SEC", "3600"));
        } catch (NumberFormatException e) {
            refreshSec = 3600L;
        }
        var scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "zone-directory-refresh");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(publishOnce,
            refreshSec, refreshSec, TimeUnit.SECONDS);
        log.info("Zone directory publish scheduled (label='{}', refresh={}s)", zoneLabel, refreshSec);
    }

    /** Build a ZoneManifestV1 for this deployment from env vars + identity. */
    private static ZoneManifestV1 buildZoneManifest(
            String did, String zoneLabel, int agreementsCount) {
        var env = System.getenv();
        var displayName = env.getOrDefault("WYRDSEKAI_ZONE_DISPLAY_NAME", zoneLabel);
        var icon = env.get("WYRDSEKAI_ZONE_ICON");  // nullable — omitted when null
        var tagline = env.getOrDefault("WYRDSEKAI_ZONE_TAGLINE", "A Wyrdsekai zone");
        var description = env.getOrDefault("WYRDSEKAI_ZONE_DESCRIPTION",
            "Federated household running Wyrdsekai. Visitors welcome via the Parlor.");
        var tagsCsv = env.getOrDefault("WYRDSEKAI_ZONE_TAGS", "");
        var tags = tagsCsv.isBlank()
            ? List.<String>of()
            : Arrays.stream(tagsCsv.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();

        // Parlor-in-Docks Capabilities skeleton (§2.8 — the public room).
        var parlor = new ZoneManifestV1.ParlorInfo(
            "parlor", "The zone's public sitting room.",
            "Enter at the Docks and step east to the Parlor to meet federated visitors.");

        // Per-tier caps with cost signaling (§5.3). Operators override via
        // WYRDSEKAI_TIER_COST_{TOURIST,RESIDENT,CITIZEN} env vars if they
        // want different CU pricing; defaults bias against casual usage.
        var tiers = new LinkedHashMap<String,
            ZoneManifestV1.TierCaps>();
        tiers.put("tourist", new ZoneManifestV1.TierCaps(
            "PT1H", Map.of("inference", 5000),
            tierCostFromEnv("WYRDSEKAI_TIER_COST_TOURIST", 10)));
        tiers.put("resident", new ZoneManifestV1.TierCaps(
            "P7D", Map.of("inference", 50000),
            tierCostFromEnv("WYRDSEKAI_TIER_COST_RESIDENT", 5)));
        tiers.put("citizen", new ZoneManifestV1.TierCaps(
            null, Map.of("inference", 250000),
            tierCostFromEnv("WYRDSEKAI_TIER_COST_CITIZEN", 1)));

        var caps = new ZoneManifestV1.Capabilities(
            parlor, List.of(), List.of(),
            Map.of(), tiers);

        // Contact carries reverse-connection hints (§5.3). endpoint is the
        // zone's public HTTPS URL (if configured); relay is a NAT-traversal
        // hint pointing at our configured relay with a routing id of the
        // zone label.
        var publicUrl = env.get("WYRDSEKAI_ZONE_PUBLIC_URL");
        var relayUrl  = env.get("WYRDSEKAI_RELAY_URL");
        var relayHint = (relayUrl != null && !relayUrl.isBlank())
            ? "relay://" + relayUrl.replaceFirst("^[a-z]+://", "")
                .replaceAll("/$", "") + "/" + zoneLabel
            : null;
        var contact = new ZoneManifestV1.Contact(
            publicUrl, relayHint,
            "Visit / Federate", "72h",
            "Send a note via the knock portal in the Parlor.");

        var mcpEndpoint = (publicUrl != null && !publicUrl.isBlank())
            ? publicUrl.replaceAll("/$", "") + "/mcp"
            : null;

        // Reputation/attestation: pulled from AttestationService if
        // initialised. Null otherwise — omitted from JSON.
        var reputation = buildReputation(did);

        var attestations = buildTopAttestations(did, 5);

        var now = Instant.now().toString();
        return new ZoneManifestV1(
            ZoneManifestV1.SCHEMA_VERSION,
            did, zoneLabel, displayName, icon, tagline, description, tags,
            caps, contact, mcpEndpoint, agreementsCount,
            reputation, attestations,
            now, now, null);
    }

    /**
     * Build the manifest's top-N attestations from {@code AttestationService}.
     * Sorted by weight descending; maps endorser type to a numeric weight
     * (steward=1.0, attestation_service=0.8, peer=0.5, external=0.3) so agents
     * can rank by source credibility. Returns {@code null} when there are
     * no endorsements (Jackson omits the field via {@code NON_EMPTY}).
     */
    private static List<ZoneManifestV1.Attestation>
            buildTopAttestations(String did, int topN) {
        try {
            var att = AttestationService.get();
            if (att == null) return null;
            var rep = att.getReputation(did);
            if (rep == null) return null;
            var endorsements = rep.endorsements();
            if (endorsements == null || endorsements.isEmpty()) return null;

            var out = new ArrayList<ZoneManifestV1.Attestation>();
            for (var e : endorsements) {
                double weight = endorserWeight(e.endorserType());
                var category = e.endorserType() != null
                    ? e.endorserType().name().toLowerCase(Locale.ROOT)
                    : "peer";
                var at = e.timestamp() != null ? e.timestamp().toString() : null;
                out.add(new ZoneManifestV1.Attestation(
                    e.endorserDid(), category, weight * Math.max(0, Math.min(1, e.score())), at));
            }
            out.sort((a, b) -> Double.compare(b.weight(), a.weight()));
            return out.size() <= topN ? out : out.subList(0, topN);
        } catch (Throwable t) {
            log.debug("attestation top-N lookup failed: {}", t.getMessage());
            return null;
        }
    }

    private static double endorserWeight(AgentReputation.EndorserType t) {
        if (t == null) return 0.5;
        return switch (t) {
            case STEWARD -> 1.0;
            case ATTESTATION_SERVICE -> 0.8;
            case PEER_AGENT -> 0.5;
            case EXTERNAL_AGENT -> 0.3;
        };
    }

    /** Parse an integer env override, falling back to {@code defaultCu}. */
    private static Integer tierCostFromEnv(String key, int defaultCu) {
        var v = System.getenv(key);
        if (v == null || v.isBlank()) return defaultCu;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            log.warn("Invalid {} value '{}', using default {}", key, v, defaultCu);
            return defaultCu;
        }
    }

    /**
     * Build a {@link org.wyrdsekai.core.naming.ZoneManifestV1.Reputation} from
     * {@code AttestationService} if available. Returns {@code null} — which
     * Jackson omits from JSON — when the service isn't initialised or has no
     * data for this DID (e.g., brand-new household).
     *
     * <p>Maps the existing {@link org.wyrdsekai.core.economy.AgentReputation.ReputationScore}
     * shape ({@code overall, endorsementScore, transactionScore,
     * completionScore}) into a category-keyed map so agent-filters by
     * category work (§5.3). Full zone-level category curation is a
     * later follow-up when AttestationService adds category tagging.</p>
     */
    private static ZoneManifestV1.Reputation buildReputation(String did) {
        try {
            var att = AttestationService.get();
            if (att == null) return null;
            var score = att.score(did);
            if (score == null || score.endorsementCount() == 0) return null;
            var categories = new LinkedHashMap<String, Double>();
            categories.put("endorsement", score.endorsementScore());
            categories.put("reliability", score.transactionScore());
            categories.put("completion", score.completionScore());
            return new ZoneManifestV1.Reputation(
                score.overall(), score.endorsementCount(), categories);
        } catch (Throwable t) {
            log.debug("reputation lookup failed: {}", t.getMessage());
            return null;
        }
    }

    private static Map<String, Object> agreementView(
            BilateralAgreement a, String localZone) {
        var localQuota = a.localQuota();
        var remoteQuota = a.remoteQuota();
        var metering = MeteringService.get();
        long inferenceTokensUsed = metering != null
            ? metering.inferenceTokensToday(a.remoteZoneId()) : 0L;
        long bandwidthUsed = metering != null
            ? metering.bandwidthToday(a.remoteZoneId()) : 0L;

        var view = new LinkedHashMap<String, Object>();
        view.put("remoteZone", a.remoteZoneId());
        view.put("status", a.status());
        view.put("trustLevel", a.trustLevel());
        view.put("agreedAt", a.agreedAt() != null ? a.agreedAt().toString() : null);
        view.put("expiresAt", a.expiresAt() != null ? a.expiresAt().toString() : null);
        view.put("expired", a.isExpired());
        view.put("localQuota", Map.of(
            "inferenceTokensPerDay", localQuota.inferenceTokensPerDay(),
            "storageBytesTotal", localQuota.storageBytesTotal(),
            "bandwidthBytesPerDay", localQuota.bandwidthBytesPerDay(),
            "allowTransit", localQuota.allowTransit(),
            "allowTell", localQuota.allowTell(),
            "allowInventory", localQuota.allowInventory(),
            "maxConcurrentSessions", localQuota.maxConcurrentSessions()));
        view.put("remoteQuota", Map.of(
            "inferenceTokensPerDay", remoteQuota.inferenceTokensPerDay(),
            "storageBytesTotal", remoteQuota.storageBytesTotal(),
            "bandwidthBytesPerDay", remoteQuota.bandwidthBytesPerDay()));
        view.put("usageToday", Map.of(
            "inferenceTokens", inferenceTokensUsed,
            "inferenceRemaining", localQuota.inferenceTokensPerDay() == 0
                ? -1 : Math.max(0, localQuota.inferenceTokensPerDay() - inferenceTokensUsed),
            "bandwidthBytes", bandwidthUsed));
        return view;
    }

    /** Ask BetweenActor for federation status description (blocking, short timeout). */
    private static String askFederationStatus(ActorRef<BetweenActor.Command> betweenActor,
                                               ActorSystem<?> system) {
        try {
            var result = AskPattern.<BetweenActor.Command, FederationActor.StatusResult>ask(
                betweenActor,
                ref -> new BetweenActor.GetFederationStatus(ref),
                Duration.ofSeconds(2),
                system.scheduler()
            ).toCompletableFuture().get(2, TimeUnit.SECONDS);
            return result.description();
        } catch (Exception e) {
            return "Federation status unavailable";
        }
    }

    /**
     * F12: ask BetweenActor for mesh-state matrix. Timeout slightly longer
     * than the actor-side 3s probe timeout so the actor's own timeout
     * fires first (returning a result with unreachable partners) rather
     * than the ask aborting.
     */
    private static FederationActor.MeshStatusResult askFederationMeshStatus(
            ActorRef<BetweenActor.Command> betweenActor, ActorSystem<?> system) {
        try {
            return AskPattern.<BetweenActor.Command, FederationActor.MeshStatusResult>ask(
                betweenActor,
                ref -> new BetweenActor.GetFederationMeshStatus(ref),
                Duration.ofSeconds(5),
                system.scheduler()
            ).toCompletableFuture().get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            return new FederationActor.MeshStatusResult(
                "<unknown>", List.of(), Instant.now());
        }
    }

    /** Ask BetweenActor for federated zone count (blocking, short timeout). */
    private static int askFederatedZoneCount(ActorRef<BetweenActor.Command> betweenActor,
                                              ActorSystem<?> system) {
        try {
            var result = AskPattern.<BetweenActor.Command, FederationActor.StatusResult>ask(
                betweenActor,
                ref -> new BetweenActor.GetFederationStatus(ref),
                Duration.ofSeconds(2),
                system.scheduler()
            ).toCompletableFuture().get(2, TimeUnit.SECONDS);
            return result.federatedZoneCount();
        } catch (Exception e) {
            return 0;
        }
    }

    /** Ask BetweenActor to propose federation (blocking, short timeout). */
    private static String askProposeFederation(ActorRef<BetweenActor.Command> betweenActor,
                                                ActorSystem<?> system, String targetZoneId) {
        try {
            return AskPattern.<BetweenActor.Command, String>ask(
                betweenActor,
                ref -> new BetweenActor.ProposeFederation(targetZoneId, ref),
                Duration.ofSeconds(5),
                system.scheduler()
            ).toCompletableFuture().get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "Failed to propose federation: " + e.getMessage();
        }
    }

    /** Ask BetweenActor to accept federation (blocking, short timeout). */
    private static String askAcceptFederation(ActorRef<BetweenActor.Command> betweenActor,
                                               ActorSystem<?> system, String remoteZoneId) {
        try {
            return AskPattern.<BetweenActor.Command, String>ask(
                betweenActor,
                ref -> new BetweenActor.AcceptFederation(remoteZoneId, ref),
                Duration.ofSeconds(5),
                system.scheduler()
            ).toCompletableFuture().get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "Failed to accept federation: " + e.getMessage();
        }
    }

    /** Ask BetweenActor to revoke federation (blocking, short timeout). */
    private static String askRevokeFederation(ActorRef<BetweenActor.Command> betweenActor,
                                               ActorSystem<?> system, String remoteZoneId) {
        try {
            return AskPattern.<BetweenActor.Command, String>ask(
                betweenActor,
                ref -> new BetweenActor.RevokeFederation(remoteZoneId, ref),
                Duration.ofSeconds(5),
                system.scheduler()
            ).toCompletableFuture().get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "Failed to revoke federation: " + e.getMessage();
        }
    }

    /** Ask BetweenActor for list of visitors (blocking, short timeout). */
    private static String askListVisitors(ActorRef<BetweenActor.Command> betweenActor,
                                           ActorSystem<?> system) {
        try {
            return AskPattern.<BetweenActor.Command, String>ask(
                betweenActor,
                ref -> new BetweenActor.ListVisitors(ref),
                Duration.ofSeconds(2),
                system.scheduler()
            ).toCompletableFuture().get(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "Visitor list unavailable";
        }
    }

    /**
     * Ask BetweenActor to request transit (blocking, short timeout).
     * Returns a JSON string so docks.js can parse the result:
     * {@code {"allowed":true,"token":"...","remoteZoneId":"...","message":"..."}}
     * or
     * {@code {"allowed":false,"message":"..."}}
     */
    private static String askRequestTransit(ActorRef<BetweenActor.Command> betweenActor,
                                             ActorSystem<?> system,
                                             String playerId, String playerName,
                                             String targetZoneId) {
        try {
            var result = AskPattern.<BetweenActor.Command, FederationActor.TransitResult>ask(
                betweenActor,
                ref -> new BetweenActor.RequestTransit(targetZoneId, playerId, playerName, ref),
                Duration.ofSeconds(20),
                system.scheduler()
            ).toCompletableFuture().get(20, TimeUnit.SECONDS);
            if (result.allowed()) {
                // Return JSON with transit token for session proxy
                return "{\"allowed\":true,\"token\":\""
                    + (result.transitToken() != null ? result.transitToken() : "")
                    + "\",\"remoteZoneId\":\"" + targetZoneId
                    + "\",\"message\":\"" + result.reason() + "\"}";
            } else {
                return "{\"allowed\":false,\"message\":\"" + result.reason() + "\"}";
            }
        } catch (Exception e) {
            return "{\"allowed\":false,\"message\":\"Transit request failed: " + e.getMessage() + "\"}";
        }
    }

    private static String askInferenceStatus(ActorRef<InferenceRouter.Command> router,
                                              ActorSystem<?> system) {
        try {
            var result = AskPattern.<InferenceRouter.Command, InferenceRouter.BackendList>ask(
                router,
                ref -> new InferenceRouter.ListBackends(ref),
                Duration.ofSeconds(2),
                system.scheduler()
            ).toCompletableFuture().get(2, TimeUnit.SECONDS);

            if (result.backends().isEmpty()) return "No inference backends configured";
            var sb = new StringBuilder();
            for (var b : result.backends()) {
                sb.append("  [").append(b.priority()).append("] ")
                    .append(b.name()).append(" (").append(b.type()).append(") — ")
                    .append(b.healthy() ? "HEALTHY" : "DOWN")
                    .append(" — ").append(b.url()).append("\n");
                if (!b.models().isEmpty()) {
                    sb.append("      Models: ").append(String.join(", ", b.models())).append("\n");
                }
            }
            return sb.toString().stripTrailing();
        } catch (Exception e) {
            return "Inference status unavailable";
        }
    }

    private static int askInferenceBackendCount(ActorRef<InferenceRouter.Command> router,
                                                 ActorSystem<?> system) {
        try {
            var result = AskPattern.<InferenceRouter.Command, InferenceRouter.BackendList>ask(
                router,
                ref -> new InferenceRouter.ListBackends(ref),
                Duration.ofSeconds(2),
                system.scheduler()
            ).toCompletableFuture().get(2, TimeUnit.SECONDS);
            return result.backends().size();
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Initialize Library subsystem: migrate old schema, create LibraryStore,
     * SecurityPatternManager, OutputSanitizer, spawn LibraryActor.
     */
    private static ActorRef<LibraryActor.Command> spawnLibrary(
            ActorSystem<?> system, String jdbcUrl,
            BridgeDataProviderImpl bridgeDataProvider) {
        try {
            // Migrate old capabilities table if present
            LibraryMigration.migrate(jdbcUrl);

            // LibraryStore uses its own SQLite connection with FTS5
            var libraryDbPath = SystemPaths.libraryDb().toString();
            var store = new LibraryStore(libraryDbPath);

            // Import any migrated records from old schema
            LibraryMigration.importStaged(store, jdbcUrl);

            // Security patterns + output sanitizer
            var config = LibraryConfig.defaults();
            var patternManager = new SecurityPatternManager(store);
            patternManager.loadBuiltinPatterns();
            var sanitizer = new OutputSanitizer(patternManager, config.sanitizationMode());
            sanitizer.reloadPatterns();

            // Spawn actor
            var actor = system.<LibraryActor.Command>systemActorOf(
                LibraryActor.create(store, patternManager, sanitizer, config),
                "library-actor", Props.empty());

            // Wire to BridgeDataProvider
            bridgeDataProvider.setLibraryActor(actor, system);

            log.info("Library subsystem initialized — FTS5 search, {} security patterns",
                sanitizer.patternCount());
            return actor;

        } catch (Exception e) {
            log.error("Library initialization failed (library disabled): {}", e.getMessage());
            return null;
        }
    }

    /** JVM system metrics for Chief Engineer. Same format as WorldApi.getSystemMetrics(). */
    private static String getSystemMetrics() {
        var rt = Runtime.getRuntime();
        long heapUsed = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long heapMax = rt.maxMemory() / (1024 * 1024);
        int heapPct = heapMax > 0 ? (int) (100.0 * heapUsed / heapMax) : 0;
        int cpus = rt.availableProcessors();
        long uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();
        long uptimeSec = uptimeMs / 1000;
        long hours = uptimeSec / 3600;
        long minutes = (uptimeSec % 3600) / 60;
        long seconds = uptimeSec % 60;
        String uptime = hours > 0
            ? hours + "h " + minutes + "m " + seconds + "s"
            : minutes + "m " + seconds + "s";
        return "Heap: " + heapUsed + "/" + heapMax + " MB (" + heapPct + "%)\n"
            + "Processors: " + cpus + "\n"
            + "Uptime: " + uptime + "\n"
            + "Java: " + System.getProperty("java.version", "unknown") + "\n"
            + "OS: " + System.getProperty("os.name", "unknown");
    }

    /** Create an OutputSanitizer for the Warden agent (separate from Library's). */
    private static OutputSanitizer spawnWardenSanitizer(String jdbcUrl) {
        try {
            var libraryDbPath = SystemPaths.libraryDb().toString();
            var store = new LibraryStore(libraryDbPath);
            var patternManager = new SecurityPatternManager(store);
            patternManager.loadBuiltinPatterns();
            var sanitizer = new OutputSanitizer(patternManager,
                OutputSanitizer.SanitizationMode.WARN);
            sanitizer.reloadPatterns();
            return sanitizer;
        } catch (Exception e) {
            log.warn("Warden sanitizer initialization failed (running without): {}", e.getMessage());
            return null;
        }
    }

    /** Find a free TCP port for the artery transport (single-node mode). */
    private static int findFreePort() {
        try (var socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException e) {
            log.warn("Failed to find free port, falling back to 25520: {}", e.getMessage());
            return 25520;
        }
    }

    private static void seedVaultDirectory() {
        try {
            var vaultDir = SystemPaths.vaultDir();
            Files.createDirectories(vaultDir);

            var readme = vaultDir.resolve("readme.txt");
            if (!Files.exists(readme)) {
                Files.writeString(readme, """
                    Welcome to The Vault.

                    This is where the world stores its knowledge. Files placed here
                    can be read by anyone with access to The Vault room.

                    The shelves are mostly empty now, but as the world grows,
                    this space will fill with records, maps, and memories.

                    — The Keeper
                    """);
                log.info("Seeded vault readme: {}", readme);
            }
        } catch (IOException e) {
            log.warn("Failed to seed vault directory: {}", e.getMessage());
        }
    }
}
