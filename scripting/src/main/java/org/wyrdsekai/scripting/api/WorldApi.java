package org.wyrdsekai.scripting.api;

import org.graalvm.polyglot.HostAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.i18n.I18n;
import org.wyrdsekai.scripting.i18n.ScriptMessageCatalog;

import org.wyrdsekai.common.system.SystemPaths;

import java.lang.management.ManagementFactory;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The world.* API available to room scripts.
 * Provides read access to room state and the ability to emit events.
 *
 * Scripts interact via:
 *   world.getRoomName()
 *   world.getEntities()
 *   world.emit("description_changed", { newDescription: "..." })
 *   world.log("message")
 */
public class WorldApi {

    private static final Logger log = LoggerFactory.getLogger(WorldApi.class);

    private final String roomId;
    private String roomName;
    private String roomDescription;
    private final Map<String, Map<String, String>> entities = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> objects = new ConcurrentHashMap<>();
    private final Map<String, String> roomProperties = new ConcurrentHashMap<>();
    private final List<EventCallback> eventCallbacks = new CopyOnWriteArrayList<>();

    // Optional bridge data provider (injected for Bridge room, null for other rooms)
    private BridgeDataProvider bridgeData;

    // Optional MCP gateway provider (injected for rooms with MCP access)
    private McpGatewayProvider mcpGateway;

    // Optional coding-backend provider (injected for rooms that delegate
    // coding tasks — Workshop, Forge, etc.).
    private CodingBackendProvider codingBackends;

    // Optional zone-command dispatcher (injected for all rooms by
    // RoomScriptEngine; powers `world.zoneCommand(name, payload)`).
    private ZoneCommandDispatcher zoneCommandDispatcher;

    // Current entity context (set before hook invocation for world.mcp() agent tracking)
    private String currentEntityId;
    /** Current zone where this WorldApi instance is running (the HOST zone). */
    private String zoneId;
    /** Home zone of the entity interacting with the script (for traveling agents). */
    private String homeZoneId;

    // i18n: locale for this room context (default: "en")
    private String locale = "en";
    private ScriptMessageCatalog scriptCatalog;

    @FunctionalInterface
    public interface EventCallback {
        void onEvent(String eventType, Map<String, Object> data);
    }

    public WorldApi(String roomId) {
        this.roomId = roomId;
    }

    public void onEvent(EventCallback callback) {
        eventCallbacks.add(callback);
    }

    // --- Script-accessible methods (annotated for GraalJS host access) ---

    @HostAccess.Export
    public String getRoomId() { return roomId; }

    @HostAccess.Export
    public String getRoomName() { return roomName; }

    @HostAccess.Export
    public String getRoomDescription() { return roomDescription; }

    /**
     * The current zone where this script is executing (the HOST zone).
     * For a traveling agent, this is the zone they're visiting.
     */
    @HostAccess.Export
    public String getCurrentZone() { return zoneId != null ? zoneId : "local"; }

    /**
     * The home zone of the entity interacting with this script.
     * For local execution, equals getCurrentZone(). For scripts executed on behalf
     * of a traveling visitor, this is their home zone.
     */
    @HostAccess.Export
    public String getHomeZone() { return homeZoneId != null ? homeZoneId : getCurrentZone(); }

    /**
     * True if the script is being executed on behalf of a traveling entity
     * (homeZone differs from currentZone).
     */
    @HostAccess.Export
    public boolean isTraveling() {
        return homeZoneId != null && zoneId != null && !homeZoneId.equals(zoneId);
    }

    @HostAccess.Export
    public void emit(String eventType, Map<String, Object> data) {
        // Deep-copy: GraalJS proxy values die when context closes
        var snapshot = new HashMap<String, Object>();
        if (data != null) {
            for (var entry : data.entrySet()) {
                snapshot.put(String.valueOf(entry.getKey()),
                    entry.getValue() != null ? String.valueOf(entry.getValue()) : "");
            }
        }
        log.debug("Script emitted {} in room {}: {}", eventType, roomId, snapshot);
        for (var cb : eventCallbacks) {
            cb.onEvent(eventType, snapshot);
        }
    }

    @HostAccess.Export
    public void log(String message) {
        log.info("[script:{}] {}", roomId, message);
    }

    /**
     * Suggest a vitality change to an entity. The agent evaluates and may accept.
     * Delta is clamped to [-1.0, 1.0]. Valid tanks: contextBudget, confidence, energy,
     * alignment, errorPressure, momentum, rapport, focus.
     */
    @HostAccess.Export
    public void suggestVitality(String entityId, String tank, double delta, String reason) {
        var validTanks = Set.of("contextBudget", "confidence", "energy", "alignment",
                                "errorPressure", "momentum", "rapport", "focus");
        if (!validTanks.contains(tank)) {
            log("suggestVitality: invalid tank '" + tank + "'");
            return;
        }
        if (entityId == null || entityId.isBlank()) {
            log("suggestVitality: missing entityId");
            return;
        }
        var clamped = Math.max(-1.0, Math.min(1.0, delta));
        emit("vitality_suggested", Map.of(
            "entityId", entityId,
            "tank", tank,
            "delta", String.valueOf(clamped),
            "reason", reason != null ? reason : "script"
        ));
    }

    // --- i18n ---

    /** Translate a key using the script message catalog. Falls back to key itself. */
    @HostAccess.Export
    public String t(String key, Object... args) {
        if (scriptCatalog == null) {
            scriptCatalog = ScriptMessageCatalog.forLang(locale);
        }
        if (args == null || args.length == 0) {
            return scriptCatalog.get(key);
        }
        return scriptCatalog.get(key, args);
    }

    /** Get the current locale for this room context. */
    @HostAccess.Export
    public String getLocale() {
        return locale;
    }

    /**
     * Returns JVM system metrics as a formatted string.
     * Called from room scripts (e.g., Boiler Room gauge, Terminal status command).
     */
    @HostAccess.Export
    public String getSystemMetrics() {
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
        String javaVer = System.getProperty("java.version", "unknown");
        String osName = System.getProperty("os.name", "unknown");

        return "Heap: " + heapUsed + "/" + heapMax + " MB (" + heapPct + "%)\n"
            + "Processors: " + cpus + "\n"
            + "Uptime: " + uptime + "\n"
            + "Java: " + javaVer + "\n"
            + "OS: " + osName;
    }

    /**
     * Read a file from the vault directory (~/.wyrdsekai/vault/).
     * Only available in the vault room. Path-sanitized, size-limited.
     */
    @HostAccess.Export
    public String readVaultFile(String filename) {
        if (!"vault".equals(roomId)) {
            return "[" + I18n.get("system.error.access_denied.vault") + "]";
        }
        if (filename == null || filename.isBlank()) {
            return "[" + I18n.get("system.error.no_param", "filename") + "]";
        }
        if (filename.contains("..") || filename.contains("/")
                || filename.contains("\\") || filename.contains("\0")) {
            return "[" + I18n.get("system.error.invalid_filename") + "]";
        }
        try {
            Path vaultDir = SystemPaths.vaultDir();
            Path filePath = vaultDir.resolve(filename);
            if (!Files.exists(filePath)) {
                return "[" + I18n.get("system.error.not_found", filename) + "]";
            }
            if (Files.size(filePath) > 4096) {
                return "[" + I18n.get("system.error.file_too_large") + "]";
            }
            return Files.readString(filePath);
        } catch (Exception e) {
            log.error("[script:{}] Failed to read vault file '{}': {}",
                roomId, filename, e.getMessage());
            return "[" + I18n.get("system.error.file_read_error", e.getMessage()) + "]";
        }
    }

    /**
     * List files in the vault directory.
     * Only available in the vault room.
     */
    @HostAccess.Export
    public String listVaultFiles() {
        if (!"vault".equals(roomId)) {
            return "[" + I18n.get("system.error.access_denied.vault") + "]";
        }
        try {
            Path vaultDir = SystemPaths.vaultDir();
            if (!Files.exists(vaultDir)) {
                return "[" + I18n.get("system.error.vault_empty") + "]";
            }
            var sb = new StringBuilder();
            try (var stream = Files.list(vaultDir)) {
                stream.filter(Files::isRegularFile)
                    .sorted()
                    .forEach(p -> {
                        try {
                            long size = Files.size(p);
                            sb.append(p.getFileName()).append(" (")
                                .append(size).append(" bytes)\n");
                        } catch (Exception ignored) {}
                    });
            }
            return sb.isEmpty() ? "[" + I18n.get("system.error.vault_empty") + "]" : sb.toString().stripTrailing();
        } catch (Exception e) {
            log.error("[script:{}] Failed to list vault files: {}", roomId, e.getMessage());
            return "[" + I18n.get("system.error.file_list_error") + "]";
        }
    }

    // --- Bridge-gated admin methods ---

    @HostAccess.Export
    public String listRooms() {
        if (!"bridge".equals(roomId)) {
            return "[" + I18n.get("system.error.access_denied.bridge") + "]";
        }
        if (bridgeData == null) return "[" + I18n.get("system.error.bridge_unavailable") + "]";
        return bridgeData.formatRoomList();
    }

    @HostAccess.Export
    public String listWards(String roomId) {
        if (!"bridge".equals(this.roomId)) {
            return "[" + I18n.get("system.error.access_denied.wards") + "]";
        }
        if (bridgeData == null) return "[" + I18n.get("system.error.bridge_unavailable") + "]";
        if (roomId == null || roomId.isBlank()) return "[" + I18n.get("system.error.no_param", "room ID") + "]";
        return bridgeData.formatWards(roomId);
    }

    @HostAccess.Export
    public String grantWard(String roomId, String principal, String permission) {
        if (!"bridge".equals(this.roomId)) {
            return "[" + I18n.get("system.error.access_denied.wards") + "]";
        }
        if (bridgeData == null) return "[" + I18n.get("system.error.bridge_unavailable") + "]";
        if (roomId == null || principal == null || permission == null) {
            return "[" + I18n.get("system.error.missing_ward_params") + "]";
        }
        return bridgeData.formatGrant(roomId, principal, permission);
    }

    @HostAccess.Export
    public String revokeWard(String roomId, String principal, String permission) {
        if (!"bridge".equals(this.roomId)) {
            return "[" + I18n.get("system.error.access_denied.wards") + "]";
        }
        if (bridgeData == null) return "[" + I18n.get("system.error.bridge_unavailable") + "]";
        if (roomId == null || principal == null || permission == null) {
            return "[" + I18n.get("system.error.missing_ward_params") + "]";
        }
        return bridgeData.formatRevoke(roomId, principal, permission);
    }

    @HostAccess.Export
    public String getZoneStats() {
        if (!"bridge".equals(roomId)) {
            return "[" + I18n.get("system.error.access_denied.bridge") + "]";
        }
        if (bridgeData == null) return "[" + I18n.get("system.error.bridge_unavailable") + "]";
        var sb = new StringBuilder();
        sb.append("Rooms: ").append(bridgeData.roomCount()).append("\n");
        sb.append("Users: ").append(bridgeData.userCount()).append("\n");
        sb.append("Wards: ").append(bridgeData.wardCount()).append("\n");
        sb.append("Connected nodes: ").append(bridgeData.connectedNodeCount()).append("\n");
        sb.append("Federated zones: ").append(bridgeData.federatedZoneCount()).append("\n");
        sb.append("Capabilities: ").append(bridgeData.capabilityCount()).append("\n");
        sb.append("\n").append(getSystemMetrics());
        return sb.toString();
    }

    /**
     * Returns cluster topology description.
     * Available from The Boiler Room and The Bridge.
     */
    @HostAccess.Export
    public String getTopology() {
        if (!"boiler-room".equals(roomId) && !"bridge".equals(roomId)) {
            return "[" + I18n.get("system.error.access_denied.boiler_bridge") + "]";
        }
        if (bridgeData == null) return "Standalone node (no cluster)";
        return bridgeData.formatTopology();
    }

    /**
     * Returns economy/metering summary.
     * Available from The Counting House.
     */
    @HostAccess.Export
    public String getEconomyStatus() {
        if (!"counting-house".equals(roomId) && !"bridge".equals(roomId)) {
            return "[" + I18n.get("system.error.access_denied.economy") + "]";
        }
        if (bridgeData == null) return "No economy data available";
        return bridgeData.formatEconomy();
    }

    // --- Reputation methods (gated to counting-house, trading-post, bridge) ---

    /**
     * Returns reputation summary for all entities.
     * Available from The Counting House, Trading Post, and The Bridge.
     */
    @HostAccess.Export
    public String getReputationSummary() {
        if (!"counting-house".equals(roomId) && !"trading-post".equals(roomId) && !"bridge".equals(roomId)) {
            return "[" + I18n.get("system.error.access_denied.reputation") + "]";
        }
        if (bridgeData == null) return "No reputation data available";
        return bridgeData.formatReputationSummary();
    }

    /**
     * Returns reputation for a specific entity.
     * Available from The Counting House, Trading Post, and The Bridge.
     */
    @HostAccess.Export
    public String getReputation(String entityId) {
        if (!"counting-house".equals(roomId) && !"trading-post".equals(roomId) && !"bridge".equals(roomId)) {
            return "[" + I18n.get("system.error.access_denied.reputation") + "]";
        }
        if (bridgeData == null) return "No reputation data available";
        return bridgeData.formatReputation(entityId);
    }

    // --- Federation methods (gated to docks and bridge) ---

    /**
     * Returns federation status (agreements + known zones).
     * Available from The Docks and The Bridge.
     */
    @HostAccess.Export
    public String getFederationStatus() {
        if (!"docks".equals(roomId) && !"bridge".equals(roomId) && !"boiler-room".equals(roomId)) {
            return "[" + I18n.get("system.error.access_denied.docks_bridge_boiler") + "]";
        }
        if (bridgeData == null) return "No federations established";
        return bridgeData.formatFederationStatus();
    }

    /**
     * Propose a bilateral agreement to a remote zone.
     * Only available from The Docks.
     */
    @HostAccess.Export
    public String proposeFederation(String zoneId) {
        if (!"docks".equals(roomId)) {
            return "[" + I18n.get("system.error.access_denied.docks") + "]";
        }
        if (bridgeData == null) return "Federation not available";
        if (zoneId == null || zoneId.isBlank()) return "[" + I18n.get("system.error.no_param", "zone ID") + "]";
        return bridgeData.proposeFederation(zoneId);
    }

    /**
     * Accept a pending federation proposal from a remote zone.
     * Only available from The Docks.
     */
    @HostAccess.Export
    public String acceptFederation(String zoneId) {
        if (!"docks".equals(roomId)) {
            return "[" + I18n.get("system.error.access_denied.docks") + "]";
        }
        if (bridgeData == null) return "Federation not available";
        if (zoneId == null || zoneId.isBlank()) return "[" + I18n.get("system.error.no_param", "zone ID") + "]";
        return bridgeData.acceptFederation(zoneId);
    }

    /**
     * Revoke a federation agreement with a remote zone.
     * Only available from The Docks.
     */
    @HostAccess.Export
    public String revokeFederation(String zoneId) {
        if (!"docks".equals(roomId)) {
            return "[" + I18n.get("system.error.access_denied.docks") + "]";
        }
        if (bridgeData == null) return "Federation not available";
        if (zoneId == null || zoneId.isBlank()) return "[" + I18n.get("system.error.no_param", "zone ID") + "]";
        return bridgeData.revokeFederation(zoneId);
    }

    /**
     * Request transit to a federated zone. Returns a JSON result with
     * transit token ID and target URL, or an error message.
     * Available from The Docks.
     */
    @HostAccess.Export
    public String requestTransit(String playerId, String playerName, String targetZoneId) {
        if (!"docks".equals(roomId)) {
            return "[" + I18n.get("system.error.access_denied.docks") + "]";
        }
        if (bridgeData == null) return "Transit not available";
        if (targetZoneId == null || targetZoneId.isBlank()) return "[" + I18n.get("system.error.no_param", "zone ID") + "]";
        return bridgeData.requestTransit(playerId, playerName, targetZoneId);
    }

    /**
     * Start a proxied transit session to a remote zone. The player's connection
     * begins rendering remote zone content without disconnecting.
     * Available from The Docks.
     *
     * @param playerId       player DID
     * @param remoteZoneId   target zone
     * @param transitToken   transit token for auth
     * @return "ok" on success, error message on failure
     */
    @HostAccess.Export
    public String startTransit(String playerId, String remoteZoneId, String transitToken) {
        if (!"docks".equals(roomId)) {
            return "[" + I18n.get("system.error.access_denied.docks") + "]";
        }
        if (bridgeData == null) return "Transit not available";
        if (remoteZoneId == null || remoteZoneId.isBlank()) return "No zone specified";
        if (transitToken == null || transitToken.isBlank()) return "No transit token";
        boolean started = bridgeData.startTransit(playerId, remoteZoneId, transitToken);
        return started ? "ok" : "Failed to start transit session";
    }

    /**
     * List currently visiting transit agents.
     * Available from The Docks.
     */
    @HostAccess.Export
    public String listTransitAgents() {
        if (!"docks".equals(roomId)) {
            return "[" + I18n.get("system.error.access_denied.docks") + "]";
        }
        if (bridgeData == null) return "No visitors";
        return bridgeData.formatTransitAgents();
    }

    /**
     * Resolve a user-typed zone reference ({@code kitchen},
     * {@code alice:kitchen}, {@code alice}, {@code did:wyrd:z6Mk…:kitchen})
     * into the canonical form. Returns JSON:
     * <pre>
     * {"ok":true,  "canonical":"did:wyrd:z6Mk…:kitchen",
     *              "fingerprint":"z6Mk…", "label":"kitchen"}
     * {"ok":false, "code":"reserved_keyword", "message":"…"}
     * </pre>
     *
     * <p>Unlike other federation-facing APIs, this one is <em>not</em> gated
     * to the docks room — resolution is a pure lookup with no side effects,
     * and CLI / admin tools will want to call it from anywhere. The actual
     * federation actions ({@code proposeFederation}, {@code requestTransit},
     * etc.) remain docks-gated.</p>
     */
    @HostAccess.Export
    public String resolveZone(String input) {
        if (bridgeData == null) {
            return "{\"ok\":false,\"code\":\"unavailable\",\"message\":\"Zone resolution not available\"}";
        }
        return bridgeData.resolveZone(input);
    }

    /**
     * Browse the zone directory from within a script. Powers the Atrium
     * room's search crystal + directory board. See
     * {@link BridgeDataProvider#discoverZones} for mode semantics.
     *
     * <p>Gated to {@code atrium} — like other federation surfaces, we
     * keep the directory-facing calls isolated to their room so every
     * zone script can't scrape global directory state.</p>
     */
    @HostAccess.Export
    public String discoverZones(String mode, String arg) {
        if (!"atrium".equals(roomId)) {
            return "[]";  // silently empty outside the Atrium
        }
        if (bridgeData == null) return "[]";
        return bridgeData.discoverZones(mode, arg);
    }

    // --- Library methods (gated to library room) ---

    /**
     * Search the capability library.
     * Only available from The Library.
     */
    @HostAccess.Export
    public String searchLibrary(String query) {
        if (!"library".equals(roomId)) {
            return "[" + I18n.get("system.error.access_denied.library") + "]";
        }
        if (bridgeData == null) return "Library not available";
        if (query == null || query.isBlank()) return "[" + I18n.get("system.error.no_param", "search query") + "]";
        return bridgeData.searchLibrary(query);
    }

    /**
     * Browse capabilities by category.
     * Only available from The Library.
     */
    @HostAccess.Export
    public String browseLibrary(String category) {
        if (!"library".equals(roomId)) {
            return "[" + I18n.get("system.error.access_denied.library") + "]";
        }
        if (bridgeData == null) return "Library not available";
        if (category == null || category.isBlank()) return "[" + I18n.get("system.error.no_param", "category") + "]";
        return bridgeData.browseLibrary(category);
    }

    /**
     * List all capabilities in the library.
     * Only available from The Library.
     */
    @HostAccess.Export
    public String listLibrary() {
        if (!"library".equals(roomId)) {
            return "[" + I18n.get("system.error.access_denied.library") + "]";
        }
        if (bridgeData == null) return "Library not available";
        return bridgeData.listLibrary();
    }

    /**
     * Inspect a specific capability.
     * Only available from The Library.
     */
    @HostAccess.Export
    public String inspectCapability(String capabilityId) {
        if (!"library".equals(roomId)) {
            return "[" + I18n.get("system.error.access_denied.library") + "]";
        }
        if (bridgeData == null) return "Library not available";
        if (capabilityId == null || capabilityId.isBlank()) return "[" + I18n.get("system.error.no_param", "capability ID") + "]";
        return bridgeData.inspectCapability(capabilityId);
    }

    /**
     * Register a new capability in the library.
     * Only available from The Library (admin-gated via wards).
     */
    @HostAccess.Export
    public String registerCapability(String name, String description, String category, String version) {
        if (!"library".equals(roomId)) {
            return "[" + I18n.get("system.error.access_denied.library") + "]";
        }
        if (bridgeData == null) return "Library not available";
        if (name == null || name.isBlank()) return "[" + I18n.get("system.error.no_param", "capability name") + "]";
        return bridgeData.registerCapability(name, description, category, version);
    }

    /**
     * Returns library status summary.
     * Available from The Library and The Bridge.
     */
    @HostAccess.Export
    public String getLibraryStatus() {
        if (!"library".equals(roomId) && !"bridge".equals(roomId)) {
            return "[" + I18n.get("system.error.access_denied.library_or_bridge") + "]";
        }
        if (bridgeData == null) return "Library not available";
        return bridgeData.formatLibraryStatus();
    }

    /**
     * Block a capability name from future registration.
     * Only available from The Library.
     */
    @HostAccess.Export
    public String blockCapability(String name, String reason) {
        if (!"library".equals(roomId)) {
            return "[" + I18n.get("system.error.access_denied.library") + "]";
        }
        if (bridgeData == null) return "Library not available";
        if (name == null || name.isBlank()) return "[" + I18n.get("system.error.no_param", "capability name") + "]";
        return bridgeData.blockCapability(name, reason);
    }

    /**
     * Unblock a previously blocked capability name.
     * Only available from The Library.
     */
    @HostAccess.Export
    public String unblockCapability(String name) {
        if (!"library".equals(roomId)) {
            return "[" + I18n.get("system.error.access_denied.library") + "]";
        }
        if (bridgeData == null) return "Library not available";
        if (name == null || name.isBlank()) return "[" + I18n.get("system.error.no_param", "capability name") + "]";
        return bridgeData.unblockCapability(name);
    }

    /**
     * View audit trail for a capability.
     * Only available from The Library.
     */
    @HostAccess.Export
    public String auditCapability(String capabilityId) {
        if (!"library".equals(roomId)) {
            return "[" + I18n.get("system.error.access_denied.library") + "]";
        }
        if (bridgeData == null) return "Library not available";
        if (capabilityId == null || capabilityId.isBlank()) return "[" + I18n.get("system.error.no_param", "capability ID") + "]";
        return bridgeData.auditCapability(capabilityId);
    }

    // --- Knowledge base methods (The Stacks — accessible from Library and Study) ---

    /**
     * Search the knowledge base (Wikipedia, WikiHow, installed packs).
     * Available from The Library and The Study.
     */
    @HostAccess.Export
    public String searchKnowledge(String query) {
        if (!"library".equals(roomId) && !inStudyRoom()) {
            return "[Knowledge search is only available from The Library or The Study]";
        }
        if (bridgeData == null) return "Knowledge base not available";
        if (query == null || query.isBlank()) return "[Please provide a search query]";
        return bridgeData.searchKnowledge(query);
    }

    /**
     * Search knowledge filtered by pack name.
     * Available from The Library and The Study.
     */
    @HostAccess.Export
    public String searchKnowledgeByPack(String query, String packName) {
        if (!"library".equals(roomId) && !inStudyRoom()) {
            return "[Knowledge search is only available from The Library or The Study]";
        }
        if (bridgeData == null) return "Knowledge base not available";
        if (query == null || query.isBlank()) return "[Please provide a search query]";
        return bridgeData.searchKnowledgeByPack(query, packName);
    }

    /**
     * List installed knowledge packs.
     * Available from The Library.
     */
    @HostAccess.Export
    public String listKnowledgePacks() {
        if (!"library".equals(roomId) && !"bridge".equals(roomId)) {
            return "[Knowledge pack list is only available from The Library or The Bridge]";
        }
        if (bridgeData == null) return "Knowledge base not available";
        return bridgeData.listKnowledgePacks();
    }

    /** True for the rooms where Library stewardship surfaces live: Library, Bridge, and Studies. */
    /**
     * True when the current room is a Study — the literal template id OR a
     * per-player instance ("study-<userId>"). Several gates used the exact
     * string and silently denied every REAL player Study (their ids always
     * carry the suffix); journal writes and knowledge search from an actual
     * Study returned the denial string on every install (second-node, 2026-07-04).
     */
    private boolean inStudyRoom() {
        return roomId != null && (roomId.equals("study") || roomId.startsWith("study-"));
    }

    private boolean inLibraryStewardRoom() {
        return "library".equals(roomId) || "bridge".equals(roomId)
            || (roomId != null && roomId.startsWith("study"));
    }

    /** True for the rooms allowed to take steward DECISIONS (approve/reject): Bridge and Studies. */
    private boolean inStewardDecisionRoom() {
        return "bridge".equals(roomId) || (roomId != null && roomId.startsWith("study"));
    }

    /**
     * Registry packs available to install (tiered, installed markers).
     * Available from The Library, The Bridge, and Studies.
     */
    @HostAccess.Export
    public String listAvailablePacks() {
        if (!inLibraryStewardRoom()) {
            return "[Pack registry is only available from The Library, The Bridge, or a Study]";
        }
        if (bridgeData == null) return "Pack registry not available";
        return bridgeData.listAvailablePacks();
    }

    /**
     * Install a registry knowledge pack (async). Registry packs are curated, licensed content —
     * installing one is equivalent to a high-trust acquire, so the Library room may do it too.
     */
    @HostAccess.Export
    public String installKnowledgePack(String packName) {
        if (!inLibraryStewardRoom()) {
            return "[Pack installs are only available from The Library, The Bridge, or a Study]";
        }
        if (bridgeData == null) return "Pack install not available";
        return bridgeData.installKnowledgePack(packName);
    }

    /**
     * Pending Library acquisitions — agent proposals and gap signals awaiting the steward.
     * Visible from The Library, The Bridge, and Studies.
     */
    @HostAccess.Export
    public String listLibraryProposals() {
        if (!inLibraryStewardRoom()) {
            return "[Library proposals are only visible from The Library, The Bridge, or a Study]";
        }
        if (bridgeData == null) return "Proposals not available";
        return bridgeData.listLibraryProposals();
    }

    /** Approve a pending acquisition. Steward decision — Bridge or Study only. */
    @HostAccess.Export
    public String approveLibraryProposal(String idPrefix, String reviewer) {
        if (!inStewardDecisionRoom()) {
            return "[Approving acquisitions is a steward decision — use your Study or The Bridge]";
        }
        if (bridgeData == null) return "Proposals not available";
        return bridgeData.approveLibraryProposal(idPrefix, reviewer);
    }

    /** Reject a pending acquisition. Steward decision — Bridge or Study only. */
    @HostAccess.Export
    public String rejectLibraryProposal(String idPrefix, String reviewer, String reason) {
        if (!inStewardDecisionRoom()) {
            return "[Rejecting acquisitions is a steward decision — use your Study or The Bridge]";
        }
        if (bridgeData == null) return "Proposals not available";
        return bridgeData.rejectLibraryProposal(idPrefix, reviewer, reason);
    }

    /** What the household keeps asking that the Library can't answer (reading-log misses). */
    @HostAccess.Export
    public String libraryTopMisses() {
        if (!inLibraryStewardRoom()) {
            return "[The reading log is only visible from The Library, The Bridge, or a Study]";
        }
        if (bridgeData == null) return "No reading log available";
        return bridgeData.libraryTopMisses();
    }

    /**
     * Knowledge base status (packs installed, total chunks, disk usage).
     * Available from The Library and The Bridge.
     */
    @HostAccess.Export
    public String getKnowledgeStatus() {
        // Studies allowed: the dashboard crystal's whole promise is household
        // status at a glance — gating it out just made the crystal render
        // this denial string as if it were telemetry (second-node, 2026-07-04).
        if (!"library".equals(roomId) && !"bridge".equals(roomId) && !inStudyRoom()) {
            return "[Knowledge status is only available from The Library or The Bridge]";
        }
        if (bridgeData == null) return "Knowledge base not available";
        return bridgeData.formatKnowledgeStatus();
    }

    /**
     * Read a specific knowledge chunk by ID.
     * Available from The Library and The Study.
     */
    @HostAccess.Export
    public String readKnowledgeChunk(String chunkId) {
        if (!"library".equals(roomId) && !inStudyRoom()) {
            return "[Knowledge reading is only available from The Library or The Study]";
        }
        if (bridgeData == null) return "Knowledge base not available";
        if (chunkId == null || chunkId.isBlank()) return "[Please provide a chunk ID]";
        return bridgeData.readKnowledgeChunk(chunkId);
    }

    // --- Study methods (private per-user content, gated to study room) ---

    /**
     * Write a shared journal entry (companion can read).
     * Only available from The Study.
     */
    @HostAccess.Export
    public String writeJournalEntry(String content) {
        if (!inStudyRoom()) {
            return "[Journal is only available from The Study]";
        }
        if (bridgeData == null) return "Study not available";
        if (content == null || content.isBlank()) return "[Please provide journal content]";
        if (currentEntityId == null) return "[No user identity available]";
        return bridgeData.writeJournalEntry(currentEntityId, content);
    }

    /**
     * Write a private journal entry (companion CANNOT read).
     * Only available from The Study.
     */
    @HostAccess.Export
    public String writePrivateJournalEntry(String content) {
        if (!inStudyRoom()) {
            return "[Journal is only available from The Study]";
        }
        if (bridgeData == null) return "Study not available";
        if (content == null || content.isBlank()) return "[Please provide journal content]";
        if (currentEntityId == null) return "[No user identity available]";
        return bridgeData.writePrivateJournalEntry(currentEntityId, content);
    }

    /**
     * Search the user's journal.
     * Only available from The Study.
     */
    @HostAccess.Export
    public String searchJournal(String query) {
        if (!inStudyRoom()) {
            return "[Journal search is only available from The Study]";
        }
        if (bridgeData == null) return "Study not available";
        if (currentEntityId == null) return "[No user identity available]";
        return bridgeData.searchJournal(currentEntityId, query);
    }

    /**
     * Search all Study content.
     * Only available from The Study.
     */
    @HostAccess.Export
    public String searchStudyContent(String query) {
        if (!inStudyRoom()) {
            return "[Study search is only available from The Study]";
        }
        if (bridgeData == null) return "Study not available";
        if (currentEntityId == null) return "[No user identity available]";
        return bridgeData.searchStudy(currentEntityId, query);
    }

    // --- Voice profile (Study mirror furnishing #416) ---
    // The Study is steward-gated at the Ward layer; reaching these methods
    // implies the user owns this Home and may edit their own voice profile.

    @HostAccess.Export
    public String formatVoiceProfile() {
        if (!inStudyRoom()) return "[Voice mirror is only available from The Study]";
        if (bridgeData == null) return "Voice profile not available";
        if (currentEntityId == null) return "[No user identity available]";
        return bridgeData.formatVoiceProfile(currentEntityId);
    }

    @HostAccess.Export
    public String formatVoiceHistory() {
        if (!inStudyRoom()) return "[Voice mirror is only available from The Study]";
        if (bridgeData == null) return "Voice profile not available";
        if (currentEntityId == null) return "[No user identity available]";
        return bridgeData.formatVoiceHistory(currentEntityId);
    }

    @HostAccess.Export
    public String setVoiceClause(String key, String value, String reason) {
        if (!inStudyRoom()) return "[Voice mirror is only available from The Study]";
        if (bridgeData == null) return "Voice profile not available";
        if (currentEntityId == null) return "[No user identity available]";
        if (key == null || key.isBlank()) return "[Provide a clause key]";
        if (value == null || value.isBlank()) return "[Provide a clause value]";
        return bridgeData.setVoiceClause(currentEntityId, key, value, reason, null);
    }

    @HostAccess.Export
    public String unsetVoiceClause(String key, String reason) {
        if (!inStudyRoom()) return "[Voice mirror is only available from The Study]";
        if (bridgeData == null) return "Voice profile not available";
        if (currentEntityId == null) return "[No user identity available]";
        if (key == null || key.isBlank()) return "[Provide a clause key]";
        return bridgeData.unsetVoiceClause(currentEntityId, key, reason, null);
    }

    @HostAccess.Export
    public String freezeVoice(String reason) {
        if (!inStudyRoom()) return "[Voice mirror is only available from The Study]";
        if (bridgeData == null) return "Voice profile not available";
        if (currentEntityId == null) return "[No user identity available]";
        return bridgeData.freezeVoice(currentEntityId, reason, null);
    }

    @HostAccess.Export
    public String unfreezeVoice(String reason) {
        if (!inStudyRoom()) return "[Voice mirror is only available from The Study]";
        if (bridgeData == null) return "Voice profile not available";
        if (currentEntityId == null) return "[No user identity available]";
        return bridgeData.unfreezeVoice(currentEntityId, reason, null);
    }

    @HostAccess.Export
    public String revertVoice(int targetRevision) {
        if (!inStudyRoom()) return "[Voice mirror is only available from The Study]";
        if (bridgeData == null) return "Voice profile not available";
        if (currentEntityId == null) return "[No user identity available]";
        return bridgeData.revertVoice(currentEntityId, targetRevision, null);
    }

    // --- Inference methods (gated to boiler-room and bridge) ---

    /**
     * Returns inference backend status (backends, health, priority).
     * Available from The Boiler Room and The Bridge.
     */
    @HostAccess.Export
    public String getInferenceStatus() {
        // Studies allowed — same dashboard-crystal rationale as getKnowledgeStatus.
        if (!"boiler-room".equals(roomId) && !"bridge".equals(roomId) && !inStudyRoom()) {
            return "[" + I18n.get("system.error.access_denied.boiler_bridge") + "]";
        }
        if (bridgeData == null) return "No inference backends configured";
        return bridgeData.formatInferenceStatus();
    }

    /**
     * Returns the number of configured inference backends.
     * Available from The Boiler Room and The Bridge.
     */
    @HostAccess.Export
    public int getInferenceBackendCount() {
        if (bridgeData == null) return 0;
        return bridgeData.inferenceBackendCount();
    }

    // --- Timer scheduling (§31) ---

    /** Pending timer requests from scripts, consumed by RoomScriptEngine. */
    public record TimerRequest(String timerId, int intervalSeconds, String hookName) {}

    private final List<TimerRequest> pendingTimers = new ArrayList<>();

    /**
     * Schedule a periodic timer that calls a hook function.
     * Available to all room scripts. The timer fires in the room context.
     *
     * @param timerId unique identifier for this timer
     * @param intervalSeconds interval between ticks
     * @param hookName the JS function to call on each tick
     */
    @HostAccess.Export
    public void scheduleTimer(String timerId, int intervalSeconds, String hookName) {
        if (timerId == null || timerId.isBlank() || hookName == null || hookName.isBlank()) {
            log("scheduleTimer: missing timerId or hookName");
            return;
        }
        if (intervalSeconds < 1) intervalSeconds = 1;
        if (intervalSeconds > 3600) intervalSeconds = 3600;
        pendingTimers.add(new TimerRequest(timerId, intervalSeconds, hookName));
        log.debug("[script:{}] Timer scheduled: {} every {}s → {}",
            roomId, timerId, intervalSeconds, hookName);
    }

    /**
     * Cancel a previously scheduled timer.
     */
    @HostAccess.Export
    public void cancelTimer(String timerId) {
        pendingTimers.removeIf(t -> t.timerId().equals(timerId));
        // Actual cancellation is handled by the RoomActor
        emit("timer_cancelled", Map.of("timerId", timerId != null ? timerId : ""));
    }

    /** Consume pending timer requests (called by RoomScriptEngine). */
    public List<TimerRequest> consumeTimerRequests() {
        var copy = List.copyOf(pendingTimers);
        pendingTimers.clear();
        return copy;
    }

    // --- MCP Gateway (§86.1) ---

    /**
     * Call an MCP service tool through the gateway.
     * Returns a result map: { success, data, error, cost, latencyMs, serviceId, toolName }.
     *
     * Usage from room scripts:
     *   var result = world.mcp("searxng", "search", { query: "hello" });
     *   if (result.success) { world.emit("narrate", { text: result.data }); }
     */
    @HostAccess.Export
    public Map<String, Object> mcp(String serviceId, String toolName, Map<String, Object> params) {
        if (mcpGateway == null) {
            return Map.of("success", false, "error", "MCP gateway not available",
                "serviceId", serviceId != null ? serviceId : "",
                "toolName", toolName != null ? toolName : "");
        }
        if (serviceId == null || serviceId.isBlank()) {
            return Map.of("success", false, "error", "Missing service ID",
                "serviceId", "", "toolName", toolName != null ? toolName : "");
        }
        if (toolName == null || toolName.isBlank()) {
            return Map.of("success", false, "error", "Missing tool name",
                "serviceId", serviceId, "toolName", "");
        }
        String agent = currentEntityId != null ? currentEntityId : roomId;
        String zone = zoneId != null ? zoneId : "default";
        // Host-injected room of origin: local services (the Study's "skill"
        // service) resolve per-room state (mounted shelves) from this. Put
        // AFTER copying script params so a script can never spoof another
        // room's identity.
        var callParams = new HashMap<String, Object>();
        if (params != null) callParams.putAll(params);
        callParams.put("_room", roomId);
        return mcpGateway.execute(agent, zone, serviceId, toolName, callParams);
    }

    /**
     * Check if an MCP service is available (registered, enabled, circuit not open).
     */
    @HostAccess.Export
    public boolean mcpAvailable(String serviceId) {
        if (mcpGateway == null) return false;
        if (serviceId == null || serviceId.isBlank()) return false;
        return mcpGateway.isAvailable(serviceId);
    }

    /**
     * Get remaining MCP budget for the current entity and a given service.
     */
    @HostAccess.Export
    public int mcpBudget(String entityId, String serviceId) {
        if (mcpGateway == null) return 0;
        if (entityId == null || entityId.isBlank() || serviceId == null || serviceId.isBlank()) return 0;
        return mcpGateway.remainingBudget(entityId, serviceId);
    }

    // --- Study-gated launch methods ---

    /**
     * Launch a desktop application by alias (e.g., "notes", "editor", "terminal").
     * Only available in study rooms (roomId starts with "study").
     * Emits a command event: verb=app_launch, target=alias.
     */
    @HostAccess.Export
    public String launchApp(String alias) {
        if (!roomId.startsWith("study")) {
            return "[" + I18n.get("system.error.access_denied.study") + "]";
        }
        if (alias == null || alias.isBlank()) {
            return "[" + I18n.get("system.error.no_param", "app alias") + "]";
        }
        emit("command", Map.of("verb", "app_launch", "target", alias));
        return I18n.get("study.launch.app_success", alias);
    }

    /**
     * Open a file by path from the Study.
     * Only available in study rooms (roomId starts with "study").
     * Validates path against traversal attacks.
     * Emits a command event: verb=file_open, target=path.
     */
    @HostAccess.Export
    public String launchFile(String path) {
        if (!roomId.startsWith("study")) {
            return "[" + I18n.get("system.error.access_denied.study") + "]";
        }
        if (path == null || path.isBlank()) {
            return "[" + I18n.get("system.error.no_param", "file path") + "]";
        }
        if (path.contains("..") || path.contains("\0")) {
            return "[" + I18n.get("system.error.invalid_filename") + "]";
        }
        emit("command", Map.of("verb", "file_open", "target", path));
        return I18n.get("study.launch.file_success", path);
    }

    /**
     * Open a URL from the Study. Only http and https schemes are allowed.
     * Only available in study rooms (roomId starts with "study").
     * Emits a command event: verb=url_open, target=url.
     */
    @HostAccess.Export
    public String launchUrl(String url) {
        if (!roomId.startsWith("study")) {
            return "[" + I18n.get("system.error.access_denied.study") + "]";
        }
        if (url == null || url.isBlank()) {
            return "[" + I18n.get("system.error.no_param", "URL") + "]";
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return "[" + I18n.get("study.launch.scheme_blocked", url) + "]";
        }
        emit("command", Map.of("verb", "url_open", "target", url));
        return I18n.get("study.launch.url_success");
    }

    // --- Document extraction (§88) ---

    /**
     * Extract text content from a document item (PDF, EPUB, DOCX, etc.).
     * Delegates to the document extraction skill via MCP gateway.
     * Returns a map: { text, pages, metadata, success, error }.
     *
     * Usage from room scripts:
     *   var doc = world.extract(item.id);
     *   if (doc.success) { world.emit("narrate", { text: "This document has " + doc.pages + " pages." }); }
     */
    @HostAccess.Export
    public Map<String, Object> extract(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return Map.of("success", false, "error", "Missing item ID",
                "text", "", "pages", "0");
        }
        // Delegate to document extraction via MCP
        if (mcpGateway == null) {
            return Map.of("success", false, "error", "Document extraction not available",
                "text", "", "pages", "0");
        }
        String agent = currentEntityId != null ? currentEntityId : roomId;
        String zone = zoneId != null ? zoneId : "default";
        // _room lets the skill service resolve the path against THIS room's
        // mounted shelves (host-injected — see mcp() above).
        return mcpGateway.execute(agent, zone, "skill", "vault.doc.extract",
            Map.of("itemId", itemId, "_room", roomId));
    }

    // --- Inference (§88) ---

    /**
     * Run inference from a room script. Supports text and multimodal (with image items).
     * Returns a map: { response, success, error, latencyMs }.
     *
     * Usage from room scripts:
     *   var result = world.infer({ prompt: "Describe this image", image: item.id });
     *   if (result.success) { world.emit("narrate", { text: result.response }); }
     *
     * @param options map with: prompt (required), image (optional item ID), maxTokens (optional)
     */
    @HostAccess.Export
    public Map<String, Object> infer(Map<String, Object> options) {
        if (options == null || !options.containsKey("prompt")) {
            return Map.of("success", false, "error", "Missing prompt",
                "response", "", "latencyMs", "0");
        }
        if (mcpGateway == null) {
            return Map.of("success", false, "error", "Inference not available",
                "response", "", "latencyMs", "0");
        }
        String agent = currentEntityId != null ? currentEntityId : roomId;
        String zone = zoneId != null ? zoneId : "default";

        // Build params map from options, copying string values safely
        var params = new HashMap<String, Object>();
        for (var entry : options.entrySet()) {
            params.put(String.valueOf(entry.getKey()),
                entry.getValue() != null ? String.valueOf(entry.getValue()) : "");
        }

        return mcpGateway.execute(agent, zone, "inference", "infer", params);
    }

    // --- Adjacent room summary (§31) ---

    /**
     * Get a summary of adjacent rooms (names and entity counts).
     * Requires BridgeDataProvider.
     */
    @HostAccess.Export
    public String getAdjacentSummary() {
        if (bridgeData == null) return "No adjacent room data available";
        return bridgeData.formatAdjacentSummary(roomId);
    }

    // --- Room state query methods (script-accessible) ---

    /**
     * Get all entities currently in the room as a list of maps.
     * Each map has: id, name, type.
     */
    @HostAccess.Export
    public List<Map<String, String>> getEntities() {
        return entities.entrySet().stream()
            .map(e -> {
                var m = new HashMap<String, String>();
                m.put("id", e.getKey());
                m.putAll(e.getValue());
                return Map.copyOf(m);
            })
            .toList();
    }

    /**
     * Check if an entity is an agent (not a player).
     * Used by room scripts to filter agent speech from player speech.
     */
    @HostAccess.Export
    public boolean isAgent(String entityId) {
        var entity = entities.get(entityId);
        return entity != null && "agent".equals(entity.get("type"));
    }

    /**
     * Get all objects currently in the room as a list of maps.
     * Each map has: id, name, description.
     */
    @HostAccess.Export
    public List<Map<String, String>> getObjects() {
        return objects.entrySet().stream()
            .map(e -> {
                var m = new HashMap<String, String>();
                m.put("id", e.getKey());
                m.putAll(e.getValue());
                return Map.copyOf(m);
            })
            .toList();
    }

    /**
     * Create an object with vitality effects (emits object_added + property_changed events).
     * Effects are stored as room properties: object.{id}.effect.{tank} = delta.
     * Call applyObjectEffects(objectId, entityId) in an onUse hook to trigger them.
     *
     * @param id object ID
     * @param name display name
     * @param description description
     * @param takeable whether the object can be picked up
     * @param effects map of tank→delta (e.g., {"energy": "0.3", "focus": "-0.1"})
     */
    @HostAccess.Export
    public void createObjectWithEffects(String id, String name, String description,
                                        boolean takeable, Map<String, Object> effects) {
        createObject(id, name, description, takeable);
        if (effects != null) {
            for (var entry : effects.entrySet()) {
                setProperty("object." + id + ".effect." + entry.getKey(),
                    String.valueOf(entry.getValue()));
            }
        }
    }

    /**
     * Apply stored vitality effects for an object to an entity.
     * Reads object.{objectId}.effect.{tank} properties and emits vitality_suggested for each.
     * Intended for use inside onUse script hooks.
     *
     * @param objectId the object whose effects to apply
     * @param entityId the entity to suggest effects to
     */
    @HostAccess.Export
    public void applyObjectEffects(String objectId, String entityId) {
        if (objectId == null || objectId.isBlank() || entityId == null || entityId.isBlank()) {
            log("applyObjectEffects: missing objectId or entityId");
            return;
        }
        var prefix = "object." + objectId + ".effect.";
        for (var entry : roomProperties.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                var tank = entry.getKey().substring(prefix.length());
                try {
                    var delta = Double.parseDouble(entry.getValue());
                    suggestVitality(entityId, tank, delta, "object:" + objectId);
                } catch (NumberFormatException e) {
                    log("applyObjectEffects: invalid delta for " + entry.getKey());
                }
            }
        }
    }

    /**
     * Create an object in the room (emits object_added event).
     * @param id object ID
     * @param name display name
     * @param description description
     * @param takeable whether the object can be picked up
     */
    @HostAccess.Export
    public void createObject(String id, String name, String description, boolean takeable) {
        if (id == null || id.isBlank() || name == null || name.isBlank()) {
            log("createObject: missing id or name");
            return;
        }
        emit("object_added", Map.of(
            "objectId", id,
            "objectName", name,
            "description", description != null ? description : "",
            "takeable", String.valueOf(takeable)));
    }

    /**
     * Remove an object from the room (emits object_removed event).
     * @param objectId ID of the object to remove
     */
    @HostAccess.Export
    public void removeObject(String objectId) {
        if (objectId == null || objectId.isBlank()) {
            log("removeObject: missing objectId");
            return;
        }
        emit("object_removed", Map.of("objectId", objectId));
    }

    /**
     * Remove an entity from the room (emits entity_removed event).
     * Scripts can use this for NPC despawning or forced ejection.
     */
    @HostAccess.Export
    public void removeEntity(String entityId) {
        if (entityId == null || entityId.isBlank()) {
            log("removeEntity: missing entityId");
            return;
        }
        emit("entity_removed", Map.of("entityId", entityId));
    }

    /**
     * Set a room property (emits property_changed event).
     * @param key property key
     * @param value property value
     */
    @HostAccess.Export
    public void setProperty(String key, String value) {
        if (key == null || key.isBlank()) {
            log("setProperty: missing key");
            return;
        }
        // Update the local map too, so a script that writes-then-reads within
        // the same invocation sees its own value. Durability across invocations
        // comes from the host: RoomActor persists property_changed emissions
        // as SetProperty events and re-syncs this map before every hook.
        roomProperties.put(key, value != null ? value : "");
        emit("property_changed", Map.of(
            "key", key,
            "value", value != null ? value : ""));
    }

    /**
     * Get a room property from the internal property map.
     * Returns null if not found.
     */
    @HostAccess.Export
    public String getProperty(String key) {
        if (key == null) return null;
        return roomProperties.get(key);
    }

    // --- Tier 3: World modification (Foundation-room gated) ---

    /**
     * Request creation of a new room. Only allowed from specific Foundation rooms.
     * The room actor processes this via Cluster Sharding.
     */
    @HostAccess.Export
    public void requestCreateRoom(String newRoomId, String name, String description,
                                  String zone, Map<String, Object> exits) {
        requireFoundationRoom("bridge", "the-forge", "the-loom");
        if (newRoomId == null || newRoomId.isBlank() || name == null || name.isBlank()) {
            log("requestCreateRoom: missing newRoomId or name");
            return;
        }
        var data = new HashMap<String, Object>();
        data.put("newRoomId", newRoomId);
        data.put("name", name);
        data.put("description", description != null ? description : "");
        data.put("zone", zone != null ? zone : "player");
        if (exits != null) {
            // Deep-copy exits map for safe cross-context transport
            var exitsCopy = new HashMap<String, Object>();
            for (var entry : exits.entrySet()) {
                exitsCopy.put(String.valueOf(entry.getKey()),
                    entry.getValue() != null ? String.valueOf(entry.getValue()) : "");
            }
            data.put("exits", String.valueOf(exitsCopy));
        }
        emit("room_creation_requested", data);
    }

    /**
     * Request adding an exit from the current room to a target room.
     * Only allowed from specific Foundation rooms.
     */
    @HostAccess.Export
    public void requestAddExit(String direction, String targetRoomId, String label) {
        requireFoundationRoom("bridge", "the-forge");
        if (direction == null || direction.isBlank() || targetRoomId == null || targetRoomId.isBlank()) {
            log("requestAddExit: missing direction or targetRoomId");
            return;
        }
        emit("exit_creation_requested", Map.of(
            "direction", direction,
            "targetRoomId", targetRoomId,
            "label", label != null ? label : direction
        ));
    }

    /**
     * Request removing an exit from the current room.
     * Only allowed from specific Foundation rooms.
     */
    @HostAccess.Export
    public void requestRemoveExit(String direction) {
        requireFoundationRoom("bridge", "the-forge");
        if (direction == null || direction.isBlank()) {
            log("requestRemoveExit: missing direction");
            return;
        }
        emit("exit_removal_requested", Map.of("direction", direction));
    }

    private void requireFoundationRoom(String... allowedRooms) {
        if (!Set.of(allowedRooms).contains(roomId)) {
            log("Operation not allowed in room: " + roomId);
            throw new SecurityException(
                "Operation restricted to rooms: " + String.join(", ", allowedRooms));
        }
    }

    /**
     * Lock an exit direction (emits exit_locked event).
     * Locked exits cannot be traversed by players.
     */
    @HostAccess.Export
    public void lockExit(String direction) {
        if (direction == null || direction.isBlank()) {
            log("lockExit: missing direction");
            return;
        }
        emit("exit_locked", Map.of("direction", direction));
    }

    /**
     * Unlock a previously locked exit (emits exit_unlocked event).
     * Restores the exit to its original target.
     */
    @HostAccess.Export
    public void unlockExit(String direction) {
        if (direction == null || direction.isBlank()) {
            log("unlockExit: missing direction");
            return;
        }
        emit("exit_unlocked", Map.of("direction", direction));
    }

    /**
     * Get a random number between 0 (inclusive) and max (exclusive).
     * Useful for dice rolls and random events in scripts.
     */
    @HostAccess.Export
    public int random(int max) {
        if (max <= 0) return 0;
        return ThreadLocalRandom.current().nextInt(max);
    }

    /**
     * Find an entity by ID. Returns a map with id/name/type or null.
     */
    @HostAccess.Export
    public Map<String, String> findEntity(String entityId) {
        if (entityId == null) return null;
        var data = entities.get(entityId);
        if (data == null) return null;
        var m = new HashMap<String, String>();
        m.put("id", entityId);
        m.putAll(data);
        return Map.copyOf(m);
    }

    /**
     * Find an object by ID. Returns a map with id/name/description or null.
     */
    @HostAccess.Export
    public Map<String, String> findObject(String objectId) {
        if (objectId == null) return null;
        var data = objects.get(objectId);
        if (data == null) return null;
        var m = new HashMap<String, String>();
        m.put("id", objectId);
        m.putAll(data);
        return Map.copyOf(m);
    }

    // --- State setters (called by the room actor, not by scripts) ---

    public void setRoomName(String name) { this.roomName = name; }
    public void setRoomDescription(String description) { this.roomDescription = description; }
    public void setLocale(String locale) {
        this.locale = locale != null ? locale : "en";
        this.scriptCatalog = null; // reset so next t() call picks up new locale
    }

    public void setBridgeDataProvider(BridgeDataProvider provider) {
        this.bridgeData = provider;
    }

    public void setMcpGatewayProvider(McpGatewayProvider provider) {
        this.mcpGateway = provider;
    }

    public void setCodingBackendProvider(CodingBackendProvider provider) {
        this.codingBackends = provider;
    }

    public void setZoneCommandDispatcher(ZoneCommandDispatcher dispatcher) {
        this.zoneCommandDispatcher = dispatcher;
    }

    /**
     * Dispatch a namespaced zone command (e.g. {@code "openhands.create"})
     * through the host's command router.
     *
     * <p>This is the unified path for room scripts to talk to coding
     * backends, IoT services, and any other namespaced zone service —
     * the same router serves player WebSocket commands and agent
     * {@code zone_command} actions, so behaviour stays identical across
     * entrypoints.</p>
     *
     * <p>Returns the last response envelope as a JSON string, or {@code
     * null} when no dispatcher is wired or the command is rejected.
     * Callers needing the full ack + terminal stream should pass an
     * options map with {@code captureAll: true} (future).</p>
     *
     * <pre>
     *   var result = world.zoneCommand("openhands.create", {
     *       actor: entityId, intent: "explore",
     *       description: "explore the repo and write hello world"
     *   });
     * </pre>
     *
     * @param command  full {@code namespace.verb} string
     * @param payload  string-keyed payload map. {@code null} accepted.
     * @return the last JSON-encoded response envelope, or null on miss
     */
    @HostAccess.Export
    public String zoneCommand(String command, Map<String, Object> payload) {
        if (zoneCommandDispatcher == null) {
            log.debug("[script:{}] world.zoneCommand({}) — no dispatcher wired",
                roomId, command);
            return null;
        }
        if (command == null || command.isBlank()) return null;

        // Coerce the JS-side payload (which may include numbers, bools,
        // nested maps) into a flat string map. Scripts can pre-stringify
        // anything fancy via world.json.stringify(...).
        Map<String, String> stringPayload = new HashMap<>();
        if (payload != null) {
            for (var e : payload.entrySet()) {
                if (e.getKey() == null) continue;
                stringPayload.put(e.getKey(),
                    e.getValue() == null ? "" : String.valueOf(e.getValue()));
            }
        }
        // Auto-inject roomId so namespace handlers (CodingNamespaceHandler,
        // bridges, etc.) know which room to place artifacts in. Scripts
        // can override by setting "roomId" in the payload explicitly.
        // itemRegistry stamps this on
        // placed RoomObjects.
        stringPayload.putIfAbsent("roomId", roomId == null ? "" : roomId);

        var entityId = currentEntityId != null ? currentEntityId : "system";
        var responses = new ArrayList<String>();
        try {
            zoneCommandDispatcher.dispatch(entityId, command,
                List.of(), stringPayload, responses::add);
        } catch (Exception e) {
            log.warn("[script:{}] world.zoneCommand({}) threw: {}",
                roomId, command, e.toString());
            return null;
        }
        return responses.isEmpty() ? null : responses.get(responses.size() - 1);
    }

    /**
     * Pick a coding backend for a delegated task.
     * §4.4 / Phase 1a step 17. Returns the stable backend name string
     * ({@code "codezaiku"}, …) or {@code null} when no backend is configured
     * / healthy / allowed for this entity.
     *
     * <p>The Workshop room consults this before dispatching a zone command:
     * <pre>
     *   var backend = world.codingBackendFor(entityId, "code", task);
     *   if (backend) world.zoneCommand(backend + ".create", { ... });
     * </pre></p>
     */
    @HostAccess.Export
    public String codingBackendFor(String entityId, String taskType, String taskDescription) {
        if (codingBackends == null) return null;
        try {
            return codingBackends.backendFor(entityId, taskType, taskDescription);
        } catch (Exception e) {
            log.warn("[script:{}] codingBackendFor({},{}) failed: {}",
                roomId, entityId, taskType, e.getMessage());
            return null;
        }
    }

    /**
     * Quick "is this coding backend registered + healthy?" probe used by
     * room scripts (the Workshop uses it to decide which boards to
     * narrate). Returns {@code false} when the backend is missing or its
     * {@code healthCheck()} probe is negative / times out.
     *
     * <p> / Phase 2b — surfaces OpenCode
     * availability without forcing the room to reach into Java internals.</p>
     */
    @HostAccess.Export
    public boolean codingBackendAvailable(String name) {
        if (codingBackends == null || name == null || name.isBlank()) return false;
        try {
            return codingBackends.backendAvailable(name);
        } catch (Exception e) {
            log.debug("[script:{}] codingBackendAvailable({}) probe failed: {}",
                roomId, name, e.getMessage());
            return false;
        }
    }

    public void setCurrentEntityId(String entityId) {
        this.currentEntityId = entityId;
    }

    public void setZoneId(String zoneId) {
        this.zoneId = zoneId;
    }

    /** Set the home zone of the entity interacting with this script. */
    public void setHomeZoneId(String homeZoneId) {
        this.homeZoneId = homeZoneId;
    }

    public void addEntity(String id, String name, String type) {
        entities.put(id, Map.of("name", name, "type", type));
    }

    public void clearEntity(String id) {
        entities.remove(id);
    }

    public void addObject(String id, String name, String description) {
        objects.put(id, Map.of("name", name, "description", description));
    }

    public void clearObject(String id) {
        objects.remove(id);
    }

    public void setProperties(Map<String, String> properties) {
        roomProperties.clear();
        if (properties != null) {
            roomProperties.putAll(properties);
        }
    }

    // ─── Governance (Council Chamber) ────────────────────────────────

    @HostAccess.Export
    public String listProposals() {
        if (!isRoomGated("council-chamber")) return "Only available in the Council Chamber";
        return bridgeData != null ? bridgeData.listProposals() : "No governance data";
    }

    @HostAccess.Export
    public String submitProposal(String title, String description) {
        if (!isRoomGated("council-chamber")) return "Only available in the Council Chamber";
        return bridgeData != null
            ? bridgeData.submitProposal(currentEntityId, title, description)
            : "Governance not available";
    }

    @HostAccess.Export
    public String castVote(String proposalId, boolean approve) {
        if (!isRoomGated("council-chamber")) return "Only available in the Council Chamber";
        return bridgeData != null
            ? bridgeData.castVote(proposalId, currentEntityId, approve)
            : "Governance not available";
    }

    @HostAccess.Export
    public String tallyVotes(String proposalId) {
        if (!isRoomGated("council-chamber")) return "Only available in the Council Chamber";
        return bridgeData != null ? bridgeData.tallyVotes(proposalId) : "Governance not available";
    }

    // ─── Soul / Forge (The Forge, The Loom) ──────────────────────────

    @HostAccess.Export
    public String getManifestSummary(String entityDid) {
        if (!isRoomGated("the-forge", "the-loom")) return "Only available in The Forge or The Loom";
        return bridgeData != null ? bridgeData.formatManifestSummary(entityDid) : "No manifest data";
    }

    @HostAccess.Export
    public String getForgeHistory(String entityDid, int count) {
        if (!isRoomGated("the-forge")) return "Only available in The Forge";
        return bridgeData != null ? bridgeData.formatForgeHistory(entityDid, count) : "No forge history";
    }

    private boolean isRoomGated(String... allowedRooms) {
        for (var room : allowedRooms) {
            if (room.equals(roomId)) return true;
        }
        return false;
    }

    // ── Phase 4: Scroll of Settings — in-world config read/write ─────────
    //
    // The Study's Scroll of Settings surfaces the /etc/wyrdsekai/wyrdsekai.conf
    // file to a steward inside the world. Without these bindings the steward
    // would have to SSH out + run `wyrd config`; with them the in-world and
    // out-of-world paths agree on the same file.
    //
    // Steward check: the-study is gated by the Home/Ward system at the room
    // level, so reaching the scroll implies steward access already. These
    // bindings stay room-gated to {@code the-study} just in case.

    @HostAccess.Export
    public String configGet(String key) {
        if (!(isRoomGated("the-study", "study", "the-atrium", "atrium") || inStudyRoom())) {
            return null;
        }
        if (key == null || key.isBlank()) return null;
        try {
            var path = SystemPaths.configFile();
            if (!Files.exists(path)) return null;
            for (var line : Files.readAllLines(path)) {
                var trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                var eq = trimmed.indexOf('=');
                if (eq <= 0) continue;
                var k = trimmed.substring(0, eq).trim();
                if (k.equals(key)) return trimmed.substring(eq + 1);
            }
        } catch (Exception e) {
            log.warn("configGet({}) failed: {}", key, e.getMessage());
        }
        return null;
    }

    /**
     * Returns a Map of every configured key. Script side iterates with
     * {@code Object.keys(world.configList())}. Empty map if no config file yet.
     */
    @HostAccess.Export
    public Map<String, String> configList() {
        var out = new HashMap<String, String>();
        if (!(isRoomGated("the-study", "study", "the-atrium", "atrium") || inStudyRoom())) return out;
        try {
            var path = SystemPaths.configFile();
            if (!Files.exists(path)) return out;
            for (var line : Files.readAllLines(path)) {
                var trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                var eq = trimmed.indexOf('=');
                if (eq <= 0) continue;
                out.put(trimmed.substring(0, eq).trim(), trimmed.substring(eq + 1));
            }
        } catch (Exception e) {
            log.warn("configList failed: {}", e.getMessage());
        }
        return out;
    }

    /**
     * Rewrites the config file with {@code key=value} replacing any existing
     * {@code key=} line (comments preserved). Returns true on success.
     * Fails silently if the process lacks write permission — the scroll
     * script surfaces a narration message in that case.
     */
    @HostAccess.Export
    public boolean configSet(String key, String value) {
        if (!(isRoomGated("the-study", "study") || inStudyRoom())) return false;
        if (key == null || key.isBlank()) return false;
        if (!key.matches("[A-Z][A-Z0-9_]*")) {
            log.warn("configSet rejected: '{}' must be uppercase KEY_STYLE", key);
            return false;
        }
        try {
            var path = SystemPaths.configFile();
            Files.createDirectories(path.getParent());
            var lines = Files.exists(path)
                ? new ArrayList<>(Files.readAllLines(path))
                : new ArrayList<String>();
            boolean replaced = false;
            for (int i = 0; i < lines.size(); i++) {
                var t = lines.get(i).trim();
                if (t.startsWith("#") || t.isEmpty()) continue;
                var eq = t.indexOf('=');
                if (eq <= 0) continue;
                if (t.substring(0, eq).trim().equals(key)) {
                    lines.set(i, key + "=" + (value == null ? "" : value));
                    replaced = true;
                    break;
                }
            }
            if (!replaced) lines.add(key + "=" + (value == null ? "" : value));
            Files.write(path, lines);
            log.info("configSet: {}={} written to {}", key, value, path);
            // Tell the server to reconcile anything that can hot-reload.
            emit("config_changed", Map.of("key", key, "value", value == null ? "" : value));
            return true;
        } catch (AccessDeniedException ade) {
            log.warn("configSet({}) denied: {} — service process lacks write "
                + "on config file. Fix: chown or use wyrd CLI.", key, ade.getMessage());
            return false;
        } catch (Exception e) {
            log.warn("configSet({}) failed: {}", key, e.getMessage());
            return false;
        }
    }

    /**
     * Signal that config has changed and the server should restart to pick up
     * new values. Emits a {@code config_apply_requested} event the server
     * listens for (spawns a systemd restart or self-restart depending on mode).
     */
    @HostAccess.Export
    public void configApply() {
        if (!(isRoomGated("the-study", "study") || inStudyRoom())) return;
        log.info("configApply requested from script in room {}", roomId);
        emit("config_apply_requested", Map.of("roomId", roomId));
    }

    /** Returns the absolute path of the active config file (for scroll display). */
    @HostAccess.Export
    public String configPath() {
        return SystemPaths.configFile().toString();
    }

    // ── — `scroll net allow/list/revoke` ─────────
    //
    // The network allowlist is STRUCTURED + binds a credential ref, so it
    // gets a purpose-built scroll sub-command instead of raw KEY=VALUE
    // config writes (a hand-typed key-ref as free text is exactly the
    // malformed-credential-pointer failure the spec forbids). Same steward
    // gate as configSet: reaching the Study scroll IS the privilege check.

    /** Add/replace an allowlist entry. Returns JSON {ok, host|error}. */
    @HostAccess.Export
    public String netAllow(String host, String kindsCsv, String keyRef, String commandPrefix) {
        if (!(isRoomGated("the-study", "study") || inStudyRoom())) {
            return "{\"ok\":false,\"error\":\"only from the Study\"}";
        }
        if (bridgeData == null) return "{\"ok\":false,\"error\":\"network binding unavailable\"}";
        return bridgeData.netAllow(host, kindsCsv, keyRef, commandPrefix);
    }

    /** Remove the allowlist entry for a host. Returns JSON {ok, host|error}. */
    @HostAccess.Export
    public String netRevoke(String host) {
        if (!(isRoomGated("the-study", "study") || inStudyRoom())) {
            return "{\"ok\":false,\"error\":\"only from the Study\"}";
        }
        if (bridgeData == null) return "{\"ok\":false,\"error\":\"network binding unavailable\"}";
        return bridgeData.netRevoke(host);
    }

    /** The live merged allowlist as a JSON array. Read-only; Study-gated. */
    @HostAccess.Export
    public String netList() {
        if (!(isRoomGated("the-study", "study") || inStudyRoom())) return "[]";
        if (bridgeData == null) return "[]";
        return bridgeData.netList();
    }
}
