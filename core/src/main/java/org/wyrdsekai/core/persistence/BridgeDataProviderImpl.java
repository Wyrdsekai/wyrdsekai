package org.wyrdsekai.core.persistence;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.javadsl.AskPattern;
import org.wyrdsekai.core.library.ArrivalTable;
import org.wyrdsekai.core.library.CapabilityRecord;
import org.wyrdsekai.core.library.KnowledgePackIndexer;
import org.wyrdsekai.core.library.KnowledgePackRegistry;
import org.wyrdsekai.core.library.LibraryActor;
import org.wyrdsekai.core.library.LibraryServices;
import org.wyrdsekai.core.library.LibraryStore;
import org.wyrdsekai.core.library.PackIngester;
import org.wyrdsekai.core.library.ProposedPack;
import org.wyrdsekai.core.library.StudyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.wyrdsekai.core.naming.ZoneAddressResolverService;
import org.wyrdsekai.core.net.NetworkAllowStore;
import org.wyrdsekai.core.net.NetworkWiring;
import org.wyrdsekai.core.naming.ZoneDirectoryService;
import org.wyrdsekai.core.naming.ZoneResolveJson;
import org.wyrdsekai.core.search.WyrdLuceneStore;
import org.wyrdsekai.core.soul.VoiceProfileService;
import org.wyrdsekai.scripting.api.BridgeDataProvider;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Bridge data provider backed by WardService, RoomMetadataService, AuthService,
 * plus optional topology, economy, federation, and library data suppliers.
 * Formats data for display in room scripts (Bridge, Boiler Room, Counting House, Library).
 */
public class BridgeDataProviderImpl implements BridgeDataProvider {

    private final WardService wardService;
    private final RoomMetadataService roomMetadataService;
    private final AuthService authService;

    // Volatile suppliers — set after construction when Between/CountingHouse are available
    private volatile Supplier<String> topologySupplier;
    private volatile Supplier<Integer> connectedNodeSupplier;
    private volatile Supplier<String> economySupplier;
    private volatile Supplier<String> federationStatusSupplier;
    private volatile Supplier<Integer> federatedZoneCountSupplier;
    private volatile Function<String, String> proposeFederationFunc;
    private volatile Function<String, String> acceptFederationFunc;
    private volatile Function<String, String> revokeFederationFunc;
    private volatile TransitRequester requestTransitFunc;
    private volatile Supplier<String> transitAgentsSupplier;
    private volatile TransitStarter transitStarterFunc;

    /** Functional interface for transit request (playerId, playerName, targetZoneId) → result. */
    @FunctionalInterface
    public interface TransitRequester {
        String request(String playerId, String playerName, String targetZoneId);
    }

    /** Functional interface for starting a proxied transit session. */
    @FunctionalInterface
    public interface TransitStarter {
        boolean start(String playerId, String remoteZoneId, String transitToken);
    }

    // Library actor — set after LibraryActor is spawned
    private volatile ActorRef<LibraryActor.Command> libraryActor;
    private volatile ActorSystem<?> system;

    // Inference data — set after InferenceRouter is spawned
    private volatile Supplier<String> inferenceStatusSupplier;
    private volatile Supplier<Integer> inferenceBackendCountSupplier;

    // Knowledge base (The Stacks) — set after WyrdLuceneStore is initialized
    private volatile WyrdLuceneStore luceneStore;
    private volatile Path packsDir;

    // Study service — set after WyrdLuceneStore is initialized
    private volatile StudyService studyService;

    // Voice profile service — set after SoulStore is initialized (#416 Study furnishing)
    private volatile VoiceProfileService voiceProfileService;

    // Health / Engine Room data — set after EngineRoomService is created
    private volatile Supplier<String> healthStatusSupplier;

    // Reputation data — set after CountingHouseActor/MutualCreditLedger are available
    private volatile Supplier<String> reputationSummarySupplier;
    private volatile Function<String, String> reputationEntityFunc;

    private static final Duration ASK_TIMEOUT = Duration.ofSeconds(2);

    public BridgeDataProviderImpl(WardService wardService,
                                   RoomMetadataService roomMetadataService,
                                   AuthService authService) {
        this.wardService = wardService;
        this.roomMetadataService = roomMetadataService;
        this.authService = authService;
    }

    /** Set topology data suppliers (called from Main after BetweenActor is spawned). */
    public void setTopologySuppliers(Supplier<String> description, Supplier<Integer> nodeCount) {
        this.topologySupplier = description;
        this.connectedNodeSupplier = nodeCount;
    }

    /** Set economy data supplier (called from Main after CountingHouse is spawned). */
    public void setEconomySupplier(Supplier<String> economy) {
        this.economySupplier = economy;
    }

    /** Set library actor (called from Main after LibraryActor is spawned). */
    public void setLibraryActor(ActorRef<LibraryActor.Command> actor, ActorSystem<?> system) {
        this.libraryActor = actor;
        this.system = system;
    }

    /** Set inference data suppliers (called from Main after InferenceRouter is spawned). */
    public void setInferenceSuppliers(Supplier<String> status, Supplier<Integer> backendCount) {
        this.inferenceStatusSupplier = status;
        this.inferenceBackendCountSupplier = backendCount;
    }

    public void setLuceneStore(WyrdLuceneStore store) {
        this.luceneStore = store;
        this.studyService = new StudyService(store);
    }

    /** Where downloaded knowledge packs live (Main wires data/packs). Enables in-world installs. */
    public void setPacksDir(Path packsDir) {
        this.packsDir = packsDir;
    }

    /**
     * Wire the {@link org.wyrdsekai.core.soul.VoiceProfileService} so the Study
     * voice-mirror furnishing can read / edit the steward's voice profile from
     * within the world. #416 — same instance shared with VoiceRoutes.
     */
    public void setVoiceProfileService(VoiceProfileService service) {
        this.voiceProfileService = service;
    }

    /** Set health status supplier (called from Main after EngineRoomService is created). */
    public void setHealthSupplier(Supplier<String> healthStatus) {
        this.healthStatusSupplier = healthStatus;
    }

    /** Set reputation data suppliers (called from Main after economy is initialized). */
    public void setReputationSuppliers(Supplier<String> summary, Function<String, String> entity) {
        this.reputationSummarySupplier = summary;
        this.reputationEntityFunc = entity;
    }

    /** Set federation data suppliers (called from Main after BetweenActor is spawned). */
    public void setFederationSuppliers(Supplier<String> status,
                                        Supplier<Integer> zoneCount,
                                        Function<String, String> propose,
                                        Function<String, String> accept,
                                        Function<String, String> revoke,
                                        Supplier<String> transitAgents) {
        this.federationStatusSupplier = status;
        this.federatedZoneCountSupplier = zoneCount;
        this.proposeFederationFunc = propose;
        this.acceptFederationFunc = accept;
        this.revokeFederationFunc = revoke;
        this.transitAgentsSupplier = transitAgents;
    }

    /** Set transit request handler (called from Main after BetweenActor is spawned). */
    public void setTransitRequester(TransitRequester requester) {
        this.requestTransitFunc = requester;
    }

    public void setTransitStarter(TransitStarter starter) {
        this.transitStarterFunc = starter;
    }

    @Override
    public String formatRoomList() {
        var rooms = roomMetadataService.listRooms();
        if (rooms.isEmpty()) return "[No rooms registered]";
        var sb = new StringBuilder();
        for (var room : rooms) {
            sb.append("  ").append(room.name())
                .append(" (").append(room.roomId()).append(")")
                .append(" — zone: ").append(room.zone())
                .append("\n");
        }
        return sb.toString().stripTrailing();
    }

    @Override
    public String formatWards(String roomId) {
        var wards = wardService.listWards(roomId);
        if (wards.isEmpty()) return "  No wards set — room is open (all operations allowed)";
        var sb = new StringBuilder();
        for (var ward : wards) {
            sb.append("  ").append(ward.principal())
                .append(" → ").append(ward.permission())
                .append(" (granted by ").append(ward.grantedBy()).append(")")
                .append("\n");
        }
        return sb.toString().stripTrailing();
    }

    @Override
    public String formatGrant(String roomId, String principal, String permission) {
        boolean created = wardService.grant(roomId, principal, permission, "bridge-admin");
        return created
            ? "Ward granted: " + principal + " can " + permission + " in " + roomId
            : "Ward already exists: " + principal + " already has " + permission + " in " + roomId;
    }

    @Override
    public String formatRevoke(String roomId, String principal, String permission) {
        boolean removed = wardService.revoke(roomId, principal, permission);
        return removed
            ? "Ward revoked: " + principal + " lost " + permission + " in " + roomId
            : "Ward not found: " + principal + " did not have " + permission + " in " + roomId;
    }

    @Override
    public int roomCount() {
        return roomMetadataService.countRooms();
    }

    @Override
    public int userCount() {
        return authService.countUsers();
    }

    @Override
    public int wardCount() {
        return wardService.countWards();
    }

    @Override
    public String formatTopology() {
        if (topologySupplier != null) return topologySupplier.get();
        return "Standalone node (no cluster)";
    }

    @Override
    public int connectedNodeCount() {
        if (connectedNodeSupplier != null) return connectedNodeSupplier.get();
        return 0;
    }

    @Override
    public String formatEconomy() {
        if (economySupplier != null) return economySupplier.get();
        return "No economy data available";
    }

    @Override
    public String formatFederationStatus() {
        if (federationStatusSupplier != null) return federationStatusSupplier.get();
        return "No federations established";
    }

    @Override
    public int federatedZoneCount() {
        if (federatedZoneCountSupplier != null) return federatedZoneCountSupplier.get();
        return 0;
    }

    @Override
    public String proposeFederation(String zoneId) {
        if (proposeFederationFunc != null) return proposeFederationFunc.apply(zoneId);
        return "Federation not available (single-node mode)";
    }

    @Override
    public String acceptFederation(String zoneId) {
        if (acceptFederationFunc != null) return acceptFederationFunc.apply(zoneId);
        return "Federation not available (single-node mode)";
    }

    @Override
    public String revokeFederation(String zoneId) {
        if (revokeFederationFunc != null) return revokeFederationFunc.apply(zoneId);
        return "Federation not available (single-node mode)";
    }

    @Override
    public String requestTransit(String playerId, String playerName, String targetZoneId) {
        if (requestTransitFunc != null) return requestTransitFunc.request(playerId, playerName, targetZoneId);
        return "Transit not available (single-node mode)";
    }

    @Override
    public boolean startTransit(String playerId, String remoteZoneId, String transitToken) {
        if (transitStarterFunc != null) return transitStarterFunc.start(playerId, remoteZoneId, transitToken);
        return false;
    }

    @Override
    public String formatTransitAgents() {
        if (transitAgentsSupplier != null) return transitAgentsSupplier.get();
        return "No visitors";
    }

    @Override
    public String resolveZone(String input) {
        return ZoneResolveJson.fromService(
            ZoneAddressResolverService.get(), input);
    }

    @Override
    public String discoverZones(String mode, String arg) {
        return ZoneDirectoryService.renderDiscover(mode, arg);
    }

    // --- Library methods (backed by LibraryActor via AskPattern) ---

    @Override
    public String searchLibrary(String query) {
        if (libraryActor == null) return "Library not available";
        try {
            var result = AskPattern.<LibraryActor.Command, LibraryActor.SearchResult>ask(
                libraryActor,
                ref -> new LibraryActor.Search(query, ref),
                ASK_TIMEOUT, system.scheduler()
            ).toCompletableFuture().get(2, TimeUnit.SECONDS);

            if (result.results().isEmpty()) return "No capabilities found matching '" + query + "'";
            var sb = new StringBuilder();
            for (var cap : result.results()) {
                sb.append("  ").append(cap.summarize()).append("\n");
                if (cap.description() != null && !cap.description().isBlank()) {
                    sb.append("    ").append(cap.description()).append("\n");
                }
            }
            return sb.toString().stripTrailing();
        } catch (Exception e) {
            return "Library search unavailable";
        }
    }

    @Override
    public String browseLibrary(String category) {
        if (libraryActor == null) return "Library not available";
        try {
            // Category can be a cognitive layer name or a tag
            var result = AskPattern.<LibraryActor.Command, LibraryActor.ListResult>ask(
                libraryActor,
                ref -> new LibraryActor.ListByTag(category, ref),
                ASK_TIMEOUT, system.scheduler()
            ).toCompletableFuture().get(2, TimeUnit.SECONDS);

            if (result.results().isEmpty()) {
                // Try as cognitive layer
                var layerResult = AskPattern.<LibraryActor.Command, LibraryActor.ListResult>ask(
                    libraryActor,
                    ref -> new LibraryActor.ListByLayer(category, ref),
                    ASK_TIMEOUT, system.scheduler()
                ).toCompletableFuture().get(2, TimeUnit.SECONDS);

                if (layerResult.results().isEmpty()) {
                    return "No capabilities found for '" + category + "'";
                }
                return formatCapabilityList(layerResult.results());
            }
            return formatCapabilityList(result.results());
        } catch (Exception e) {
            return "Library browse unavailable";
        }
    }

    @Override
    public String listLibrary() {
        if (libraryActor == null) return "Library not available";
        try {
            var result = AskPattern.<LibraryActor.Command, LibraryActor.ListResult>ask(
                libraryActor,
                ref -> new LibraryActor.ListAll(ref),
                ASK_TIMEOUT, system.scheduler()
            ).toCompletableFuture().get(2, TimeUnit.SECONDS);

            if (result.results().isEmpty()) {
                return "The shelves are empty. No capabilities registered yet.";
            }

            // Group by cognitive layer
            var sb = new StringBuilder();
            var byLayer = result.results().stream()
                .collect(Collectors.groupingBy(
                    cap -> cap.cognitiveLayer() != null
                        ? cap.cognitiveLayer().name() : "UNCATEGORIZED"));

            for (var entry : byLayer.entrySet()) {
                sb.append("\n  [").append(entry.getKey()).append("]\n");
                for (var cap : entry.getValue()) {
                    sb.append("    ").append(cap.summarize()).append("\n");
                }
            }
            return sb.toString().stripTrailing();
        } catch (Exception e) {
            return "Library listing unavailable";
        }
    }

    @Override
    public String inspectCapability(String capabilityId) {
        if (libraryActor == null) return "Library not available";
        try {
            var result = AskPattern.<LibraryActor.Command, LibraryActor.LookupResult>ask(
                libraryActor,
                ref -> new LibraryActor.Lookup(capabilityId, ref),
                ASK_TIMEOUT, system.scheduler()
            ).toCompletableFuture().get(2, TimeUnit.SECONDS);

            if (result.record() == null) {
                return "No capability found with ID or name '" + capabilityId + "'";
            }
            return result.record().describe();
        } catch (Exception e) {
            return "Library inspection unavailable";
        }
    }

    @Override
    public String registerCapability(String name, String description, String category, String version) {
        if (libraryActor == null) return "Library not available";
        try {
            var result = AskPattern.<LibraryActor.Command, LibraryActor.RegisterResult>ask(
                libraryActor,
                ref -> new LibraryActor.Register(name, description, null, "MANUAL",
                    null, "system", version,
                    category != null ? List.of(category) : List.of(), 0, ref),
                ASK_TIMEOUT, system.scheduler()
            ).toCompletableFuture().get(2, TimeUnit.SECONDS);

            if (result.capId() != null) {
                return "Capability registered: " + name + " (ID: " + result.capId().substring(0, 8) + ")";
            }
            return result.error() != null ? result.error() : "Failed to register capability";
        } catch (Exception e) {
            return "Library registration unavailable";
        }
    }

    @Override
    public String formatLibraryStatus() {
        if (libraryActor == null) return "No capabilities registered";
        try {
            var result = AskPattern.<LibraryActor.Command, LibraryActor.StatusResult>ask(
                libraryActor,
                ref -> new LibraryActor.Status(ref),
                ASK_TIMEOUT, system.scheduler()
            ).toCompletableFuture().get(2, TimeUnit.SECONDS);

            if (result.total() == 0) return "No capabilities registered";
            return result.total() + " capabilities registered ("
                + result.verified() + " verified, "
                + result.unverified() + " unverified, "
                + result.quarantined() + " quarantined, "
                + result.banned() + " banned)\n"
                + "Security patterns: " + result.patternCount();
        } catch (Exception e) {
            return "Library status unavailable";
        }
    }

    @Override
    public int capabilityCount() {
        if (libraryActor == null) return 0;
        try {
            var result = AskPattern.<LibraryActor.Command, LibraryActor.StatusResult>ask(
                libraryActor,
                ref -> new LibraryActor.Status(ref),
                ASK_TIMEOUT, system.scheduler()
            ).toCompletableFuture().get(2, TimeUnit.SECONDS);
            return result.total();
        } catch (Exception e) {
            return 0;
        }
    }

    // --- New library methods (block, unblock, audit) ---

    @Override
    public String blockCapability(String name, String reason) {
        if (libraryActor == null) return "Library not available";
        try {
            var result = AskPattern.<LibraryActor.Command, LibraryActor.StringResult>ask(
                libraryActor,
                ref -> new LibraryActor.Block(name, reason, ref),
                ASK_TIMEOUT, system.scheduler()
            ).toCompletableFuture().get(2, TimeUnit.SECONDS);
            return result.message();
        } catch (Exception e) {
            return "Block operation unavailable";
        }
    }

    @Override
    public String unblockCapability(String name) {
        if (libraryActor == null) return "Library not available";
        try {
            var result = AskPattern.<LibraryActor.Command, LibraryActor.StringResult>ask(
                libraryActor,
                ref -> new LibraryActor.Unblock(name, ref),
                ASK_TIMEOUT, system.scheduler()
            ).toCompletableFuture().get(2, TimeUnit.SECONDS);
            return result.message();
        } catch (Exception e) {
            return "Unblock operation unavailable";
        }
    }

    @Override
    public String auditCapability(String capabilityId) {
        if (libraryActor == null) return "Library not available";
        try {
            var result = AskPattern.<LibraryActor.Command, LibraryActor.AuditResult>ask(
                libraryActor,
                ref -> new LibraryActor.Audit(capabilityId, ref),
                ASK_TIMEOUT, system.scheduler()
            ).toCompletableFuture().get(2, TimeUnit.SECONDS);

            if (result.entries().isEmpty()) {
                return "No audit entries for '" + capabilityId + "'";
            }
            var sb = new StringBuilder();
            for (var entry : result.entries()) {
                sb.append("  ").append(entry.timestamp())
                    .append(" ").append(entry.type())
                    .append(entry.capabilityName() != null ? " — " + entry.capabilityName() : "")
                    .append(entry.details() != null ? ": " + entry.details() : "")
                    .append("\n");
            }
            return sb.toString().stripTrailing();
        } catch (Exception e) {
            return "Audit unavailable";
        }
    }

    // --- Knowledge base (The Stacks) ---

    @Override
    public String searchKnowledge(String query) {
        if (luceneStore == null) return "Knowledge base not available";
        var results = luceneStore.searchKnowledgeText(query, 7);
        if (results.isEmpty()) return "No results found for '" + query + "'";

        var sb = new StringBuilder();
        sb.append("Knowledge base — ").append(results.size()).append(" results for \"").append(query).append("\":\n\n");
        for (int i = 0; i < results.size(); i++) {
            var r = results.get(i);
            var meta = r.metadata();
            String pack = meta != null ? (String) meta.getOrDefault("pack", "unknown") : "unknown";
            String title = meta != null ? (String) meta.getOrDefault("title", "") : "";
            String snippet = r.content() != null && r.content().length() > 150
                ? r.content().substring(0, 150) + "..."
                : (r.content() != null ? r.content() : "");

            sb.append("  ").append(i + 1).append(". ");
            if (!title.isBlank()) sb.append("[").append(title).append("] ");
            sb.append("(").append(pack).append(", relevance: ")
              .append(String.format("%.2f", r.score())).append(")\n");
            sb.append("     ").append(snippet).append("\n");
            sb.append("     ID: ").append(r.id()).append("\n\n");
        }
        sb.append("Use 'read <ID>' to read a full article.");
        return sb.toString().stripTrailing();
    }

    @Override
    public String searchKnowledgeByPack(String query, String packName) {
        if (luceneStore == null) return "Knowledge base not available";
        var results = luceneStore.searchKnowledgeByPack(query, null, packName, 7);
        if (results.isEmpty()) return "No results in '" + packName + "' for '" + query + "'";

        var sb = new StringBuilder();
        sb.append("Results from ").append(packName).append(":\n\n");
        for (int i = 0; i < results.size(); i++) {
            var r = results.get(i);
            var meta = r.metadata();
            String title = meta != null ? (String) meta.getOrDefault("title", "") : "";
            String snippet = r.content() != null && r.content().length() > 150
                ? r.content().substring(0, 150) + "..." : (r.content() != null ? r.content() : "");
            sb.append("  ").append(i + 1).append(". ").append(title).append("\n");
            sb.append("     ").append(snippet).append("\n\n");
        }
        return sb.toString().stripTrailing();
    }

    @Override
    public String listKnowledgePacks() {
        if (luceneStore == null) return "Knowledge base not available";
        // List known packs by counting chunks per pack
        // For now, report total — pack listing requires a pack registry (Phase L4)
        long total = luceneStore.countKnowledge();
        if (total == 0) return "No knowledge packs installed. Use 'wyrdsekai library install <pack>' to add knowledge.";
        return "Total knowledge chunks indexed: " + total;
    }

    @Override
    public String formatKnowledgeStatus() {
        if (luceneStore == null) return "Knowledge base not available";
        long total = luceneStore.countKnowledge();
        return "Knowledge base: " + total + " chunks indexed";
    }

    // --- Library stewardship ---

    @Override
    public String listAvailablePacks() {
        if (luceneStore == null) return "Pack registry not available";
        var installed = luceneStore.listKnowledgePacks();
        var sb = new StringBuilder("Knowledge packs (registry):\n");
        for (var pack : KnowledgePackRegistry.listAvailable()) {
            long chunks = installed.getOrDefault(pack.name(), 0L);
            sb.append("  ").append(chunks > 0 ? "[installed] " : "[available] ")
              .append(pack.name())
              .append("  tier ").append(pack.effectiveTier());
            if (pack.shelf() != null) sb.append("/").append(pack.shelf());
            if (Boolean.TRUE.equals(pack.recommended())) sb.append("  *recommended*");
            sb.append("  ").append(pack.estimatedSize() != null ? pack.estimatedSize() : "")
              .append("\n     ").append(pack.description()).append("\n");
        }
        sb.append("Install with: install <pack-name>");
        return sb.toString();
    }

    @Override
    public String installKnowledgePack(String packName) {
        if (luceneStore == null || packsDir == null) return "Pack install not available on this node";
        if (packName == null || packName.isBlank()) return "Which pack? See: available";
        var pack = KnowledgePackRegistry.find(packName.trim());
        if (pack.isEmpty()) {
            return "Unknown pack '" + packName + "'. See: available";
        }
        var indexer = new KnowledgePackIndexer(luceneStore);
        if (indexer.packSize(pack.get().name()) > 0) {
            return "'" + pack.get().name() + "' is already installed.";
        }
        KnowledgePackRegistry.install(
            pack.get().name(), packsDir, indexer, null);
        return "Installing '" + pack.get().name() + "' (" + pack.get().estimatedSize()
            + ") in the background — check back with: packs";
    }

    @Override
    public String listLibraryProposals() {
        var table = LibraryServices.arrivalTable();
        if (table == null) return "No pending Library proposals";
        var pending = table.pending();
        if (pending.isEmpty()) return "No pending Library proposals.";
        var sb = new StringBuilder("Pending Library acquisitions (approve <id> / reject <id>):\n");
        for (var p : pending) {
            sb.append("  [").append(p.id(), 0, Math.min(8, p.id().length())).append("] ")
              .append(p.topic())
              .append("  (").append(p.trigger()).append(", proposed by ").append(p.proposedBy()).append(")\n");
            if (p.whyRelevant() != null && !p.whyRelevant().isBlank()) {
                sb.append("        why: ").append(p.whyRelevant()).append("\n");
            }
        }
        return sb.toString().stripTrailing();
    }

    @Override
    public String approveLibraryProposal(String idPrefix, String reviewer) {
        var table = LibraryServices.arrivalTable();
        if (table == null) return "Proposals not available";
        var match = findPendingByPrefix(table, idPrefix);
        if (match == null) return "No pending proposal matching '" + idPrefix + "'. See: proposals";
        var approved = table.approve(match.id(), reviewer).orElse(null);
        if (approved == null) return "Could not approve '" + idPrefix + "'";
        if (luceneStore != null && !approved.sources().isEmpty()) {
            var store = luceneStore;
            Thread.ofVirtual().name("proposal-ingest").start(() -> {
                try {
                    var result = new PackIngester(store).ingest(approved);
                    if (result.ok()) table.markIngested(approved.id());
                } catch (Exception ignored) { /* next steward visit shows it still APPROVED */ }
            });
            return "Approved '" + approved.topic() + "' — gathering and shelving its sources now.";
        }
        return "Approved '" + approved.topic() + "' (no sources attached yet — the scout will enrich it).";
    }

    @Override
    public String rejectLibraryProposal(String idPrefix, String reviewer, String reason) {
        var table = LibraryServices.arrivalTable();
        if (table == null) return "Proposals not available";
        var match = findPendingByPrefix(table, idPrefix);
        if (match == null) return "No pending proposal matching '" + idPrefix + "'. See: proposals";
        var rejected = table.reject(match.id(), reviewer,
            reason == null || reason.isBlank() ? "declined by steward" : reason);
        return rejected.isPresent()
            ? "Rejected '" + match.topic() + "'."
            : "Could not reject '" + idPrefix + "'";
    }

    private static ProposedPack findPendingByPrefix(
            ArrivalTable table, String idPrefix) {
        if (idPrefix == null || idPrefix.isBlank()) return null;
        var prefix = idPrefix.trim();
        return table.pending().stream()
            .filter(p -> p.id().startsWith(prefix))
            .findFirst().orElse(null);
    }

    @Override
    public String libraryTopMisses() {
        var rl = LibraryServices.readingLog();
        if (rl == null) return "No reading log available";
        var top = rl.topRepeatedTerms(200, 2);
        if (top.isEmpty()) return "No repeated library-search misses recently — the Library is keeping up.";
        var sb = new StringBuilder("Topics the household keeps asking that the Library can't answer:\n");
        top.entrySet().stream()
            .sorted((a, b) -> b.getValue() - a.getValue())
            .limit(10)
            .forEach(e -> sb.append("  ").append(e.getKey())
                .append("  (missed ").append(e.getValue()).append("x)\n"));
        sb.append("Consider a pack (available) or an acquisition on these topics.");
        return sb.toString();
    }

    @Override
    public String readKnowledgeChunk(String chunkId) {
        if (luceneStore == null) return "Knowledge base not available";
        // Search by exact ID
        var results = luceneStore.searchKnowledgeText(chunkId, 1);
        if (results.isEmpty()) return "Chunk '" + chunkId + "' not found";
        var r = results.getFirst();
        var meta = r.metadata();
        String title = meta != null ? (String) meta.getOrDefault("title", "") : "";
        String source = meta != null ? (String) meta.getOrDefault("source", "") : "";

        var sb = new StringBuilder();
        if (!title.isBlank()) sb.append("## ").append(title).append("\n\n");
        sb.append(r.content() != null ? r.content() : "(no content)");
        if (!source.isBlank()) sb.append("\n\n— Source: ").append(source);
        return sb.toString();
    }

    // --- Study (private per-user content) ---

    @Override
    public String writeJournalEntry(String userDid, String content) {
        if (studyService == null) return "Study not available";
        var id = studyService.writeJournalEntry(userDid, content);
        return "Journal entry saved. (ID: " + id + ")";
    }

    @Override
    public String writePrivateJournalEntry(String userDid, String content) {
        if (studyService == null) return "Study not available";
        var id = studyService.writePrivateJournalEntry(userDid, content);
        return "Private journal entry saved. Your companion cannot read this.";
    }

    @Override
    public String searchJournal(String userDid, String query) {
        if (studyService == null) return "Study not available";
        var results = studyService.searchAllJournal(userDid, query, 5);
        if (results.isEmpty()) return "No journal entries found for '" + query + "'";

        var sb = new StringBuilder();
        sb.append("Journal — ").append(results.size()).append(" entries matching \"").append(query).append("\":\n\n");
        for (int i = 0; i < results.size(); i++) {
            var r = results.get(i);
            var meta = r.metadata();
            String title = meta != null ? (String) meta.getOrDefault("title", "") : "";
            String type = meta != null ? (String) meta.getOrDefault("item_type", "journal") : "journal";
            boolean isPrivate = "journal_private".equals(type);
            String snippet = r.content() != null && r.content().length() > 120
                ? r.content().substring(0, 120) + "..." : (r.content() != null ? r.content() : "");

            sb.append("  ").append(i + 1).append(". ");
            if (isPrivate) sb.append("[PRIVATE] ");
            sb.append(snippet).append("\n\n");
        }
        return sb.toString().stripTrailing();
    }

    @Override
    public String searchStudy(String userDid, String query) {
        if (studyService == null) return "Study not available";
        var results = studyService.searchAll(userDid, query, 7);
        if (results.isEmpty()) return "No results found in your Study for '" + query + "'";

        var sb = new StringBuilder();
        sb.append("Your Study — ").append(results.size()).append(" results for \"").append(query).append("\":\n\n");
        for (int i = 0; i < results.size(); i++) {
            var r = results.get(i);
            var meta = r.metadata();
            String title = meta != null ? (String) meta.getOrDefault("title", "") : "";
            String type = meta != null ? (String) meta.getOrDefault("item_type", "") : "";
            String snippet = r.content() != null && r.content().length() > 120
                ? r.content().substring(0, 120) + "..." : (r.content() != null ? r.content() : "");

            sb.append("  ").append(i + 1).append(". [").append(type).append("] ");
            if (!title.isBlank()) sb.append(title).append(": ");
            sb.append(snippet).append("\n\n");
        }
        return sb.toString().stripTrailing();
    }

    @Override
    public String formatStudyStatus(String userDid) {
        if (studyService == null) return "Study not available";
        var stats = studyService.getStats(userDid);
        return "Your Study: " + stats.get("totalItems") + " items indexed";
    }

    // --- Voice profile (Study furnishing #416) ---

    @Override
    public String formatVoiceProfile(String userDid) {
        if (voiceProfileService == null) return "Voice profile not available";
        if (userDid == null || userDid.isBlank()) return "[No user identity available]";
        var profile = voiceProfileService.get(userDid);
        if (profile.isEmpty()) return "[No voice profile for this DID — sleep at least once first]";
        var p = profile.get();
        var sb = new StringBuilder();
        sb.append("Voice profile — revision ").append(p.revision());
        if (p.frozen()) sb.append(" (frozen)");
        sb.append("\n");
        if (p.clauses().isEmpty()) {
            sb.append("(no clauses set — Forge proposes one per deep-sleep cycle, or 'voice set <key> <value>')");
        } else {
            for (var entry : p.clauses().entrySet()) {
                sb.append("  • ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
            }
        }
        return sb.toString().stripTrailing();
    }

    @Override
    public String formatVoiceHistory(String userDid) {
        if (voiceProfileService == null) return "Voice profile not available";
        if (userDid == null || userDid.isBlank()) return "[No user identity available]";
        var profile = voiceProfileService.get(userDid);
        if (profile.isEmpty()) return "[No voice profile for this DID]";
        var history = profile.get().history();
        if (history.isEmpty()) return "(no revisions yet)";
        var sb = new StringBuilder("Voice profile revisions:\n");
        for (var rev : history) {
            sb.append("  ").append(rev.fromRevision()).append(" → ").append(rev.toRevision())
              .append("  by ").append(rev.author())
              .append("  — ").append(rev.reason())
              .append("  (").append(rev.at()).append(")\n");
        }
        return sb.toString().stripTrailing();
    }

    @Override
    public String setVoiceClause(String userDid, String key, String value, String reason, String author) {
        if (voiceProfileService == null) return "Voice profile not available";
        if (userDid == null || userDid.isBlank()) return "[No user identity available]";
        try {
            var updated = voiceProfileService.setClause(userDid, key, value,
                (reason == null || reason.isBlank()) ? "in-world edit" : reason,
                (author == null || author.isBlank()) ? defaultAuthor(userDid) : author);
            return "Set " + key + " (revision " + updated.revision() + ")";
        } catch (IllegalStateException frozen) {
            return "Voice profile is frozen — unfreeze first.";
        } catch (Exception e) {
            return "Could not set clause: " + e.getMessage();
        }
    }

    @Override
    public String unsetVoiceClause(String userDid, String key, String reason, String author) {
        if (voiceProfileService == null) return "Voice profile not available";
        if (userDid == null || userDid.isBlank()) return "[No user identity available]";
        try {
            var updated = voiceProfileService.unsetClause(userDid, key,
                (reason == null || reason.isBlank()) ? "in-world unset" : reason,
                (author == null || author.isBlank()) ? defaultAuthor(userDid) : author);
            return "Unset " + key + " (revision " + updated.revision() + ")";
        } catch (IllegalStateException frozen) {
            return "Voice profile is frozen — unfreeze first.";
        } catch (Exception e) {
            return "Could not unset clause: " + e.getMessage();
        }
    }

    @Override
    public String freezeVoice(String userDid, String reason, String author) {
        if (voiceProfileService == null) return "Voice profile not available";
        if (userDid == null || userDid.isBlank()) return "[No user identity available]";
        try {
            voiceProfileService.freeze(userDid,
                (reason == null || reason.isBlank()) ? "in-world freeze" : reason,
                (author == null || author.isBlank()) ? defaultAuthor(userDid) : author);
            return "Voice profile frozen.";
        } catch (Exception e) {
            return "Could not freeze: " + e.getMessage();
        }
    }

    @Override
    public String unfreezeVoice(String userDid, String reason, String author) {
        if (voiceProfileService == null) return "Voice profile not available";
        if (userDid == null || userDid.isBlank()) return "[No user identity available]";
        try {
            voiceProfileService.unfreeze(userDid,
                (reason == null || reason.isBlank()) ? "in-world unfreeze" : reason,
                (author == null || author.isBlank()) ? defaultAuthor(userDid) : author);
            return "Voice profile unfrozen.";
        } catch (Exception e) {
            return "Could not unfreeze: " + e.getMessage();
        }
    }

    @Override
    public String revertVoice(String userDid, int targetRevision, String author) {
        if (voiceProfileService == null) return "Voice profile not available";
        if (userDid == null || userDid.isBlank()) return "[No user identity available]";
        try {
            var updated = voiceProfileService.revertTo(userDid, targetRevision,
                (author == null || author.isBlank()) ? defaultAuthor(userDid) : author);
            return "Reverted to state at revision " + targetRevision +
                   " (now revision " + updated.revision() + ")";
        } catch (NoSuchElementException nse) {
            return "No revision " + targetRevision + " in history.";
        } catch (IllegalStateException frozen) {
            return "Voice profile is frozen — unfreeze first.";
        } catch (Exception e) {
            return "Could not revert: " + e.getMessage();
        }
    }

    private static String defaultAuthor(String userDid) {
        // In-world steward edits; collapse long DIDs to a short tag.
        if (userDid == null || userDid.length() < 12) return "study";
        return "study:" + userDid.substring(userDid.length() - 8);
    }

    // --- Health / Engine Room ---

    @Override
    public String formatHealthStatus() {
        if (healthStatusSupplier != null) return healthStatusSupplier.get();
        return "No health data available";
    }

    // --- Inference methods ---

    @Override
    public String formatInferenceStatus() {
        if (inferenceStatusSupplier != null) return inferenceStatusSupplier.get();
        return "No inference backends configured";
    }

    @Override
    public int inferenceBackendCount() {
        if (inferenceBackendCountSupplier != null) return inferenceBackendCountSupplier.get();
        return 0;
    }

    // --- Reputation methods (§17) ---

    @Override
    public String formatReputationSummary() {
        if (reputationSummarySupplier != null) return reputationSummarySupplier.get();
        return "No reputation data available";
    }

    @Override
    public String formatReputation(String entityId) {
        if (reputationEntityFunc != null) return reputationEntityFunc.apply(entityId);
        return "No reputation data for " + entityId;
    }

    // --- Room adjacency (§31) ---

    @Override
    public String formatAdjacentSummary(String roomId) {
        try {
            var rooms = roomMetadataService.listRooms();
            // Find exits for the requested room from metadata
            // In M0, we return a simple listing of all Foundation rooms as adjacent
            var sb = new StringBuilder("Adjacent rooms:\n");
            for (var room : rooms) {
                if (!room.roomId().equals(roomId)) {
                    sb.append("  ").append(room.name())
                        .append(" (").append(room.roomId()).append(")\n");
                }
            }
            return sb.toString().stripTrailing();
        } catch (Exception e) {
            return "Adjacent room data unavailable";
        }
    }

    // --- Network allowlist (, `scroll net …`) ---

    @Override
    public String netAllow(String host, String kindsCsv, String keyRef, String commandPrefix) {
        try {
            var kinds = kindsCsv == null ? List.<String>of()
                : List.of(kindsCsv.split("[,\\s]+"));
            var outcome = NetworkAllowStore.get().allow(host, kinds, keyRef, commandPrefix);
            var node = JSON.createObjectNode();
            node.put("ok", outcome.ok());
            if (outcome.ok()) {
                node.put("host", outcome.entry().host());
                var arr = node.putArray("kinds");
                for (var k : outcome.entry().kinds()) arr.add(k);
                if (outcome.entry().keyRef() != null) node.put("keyRef", outcome.entry().keyRef());
                if (outcome.entry().commandPrefix() != null) {
                    node.put("commandPrefix", outcome.entry().commandPrefix());
                }
            } else {
                node.put("error", outcome.error());
            }
            return node.toString();
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"" + String.valueOf(e.getMessage())
                .replace("\"", "'") + "\"}";
        }
    }

    @Override
    public String netRevoke(String host) {
        try {
            var outcome = NetworkAllowStore.get().revoke(host);
            var node = JSON.createObjectNode();
            node.put("ok", outcome.ok());
            if (outcome.ok()) node.put("host", outcome.entry().host());
            else node.put("error", outcome.error());
            return node.toString();
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"" + String.valueOf(e.getMessage())
                .replace("\"", "'") + "\"}";
        }
    }

    @Override
    public String netList() {
        try {
            var arr = JSON.createArrayNode();
            for (var e : NetworkWiring.currentGate().allowlist()) {
                var node = JSON.createObjectNode();
                node.put("host", e.host());
                var kindsArr = node.putArray("kinds");
                for (var k : e.kinds()) kindsArr.add(k);
                if (e.keyRef() != null) node.put("keyRef", e.keyRef());
                if (e.commandPrefix() != null) node.put("commandPrefix", e.commandPrefix());
                arr.add(node);
            }
            return arr.toString();
        } catch (Exception e) {
            return "[]";
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    // --- Helpers ---

    private String formatCapabilityList(List<CapabilityRecord> caps) {
        var sb = new StringBuilder();
        for (var cap : caps) {
            sb.append("  ").append(cap.summarize()).append("\n");
        }
        return sb.toString().stripTrailing();
    }
}
