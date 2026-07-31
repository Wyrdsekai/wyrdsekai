package org.wyrdsekai.scripting.api;

/**
 * Provides formatted data for Bridge room admin commands, topology status,
 * and economy metrics.
 * Defined in scripting module to avoid circular dependency (scripting cannot reference core).
 * Core provides the implementation wrapping WardService, RoomMetadataService, AuthService.
 */
public interface BridgeDataProvider {
    String formatRoomList();
    String formatWards(String roomId);
    String formatGrant(String roomId, String principal, String permission);
    String formatRevoke(String roomId, String principal, String permission);
    int roomCount();
    int userCount();
    int wardCount();

    /** Topology description for Boiler Room and Bridge. */
    default String formatTopology() { return "Standalone node (no cluster)"; }

    /** Number of connected peer nodes. */
    default int connectedNodeCount() { return 0; }

    /** Economy/metering summary for the Counting House. */
    default String formatEconomy() { return "No economy data available"; }

    /** Federation status for the Docks and Bridge. */
    default String formatFederationStatus() { return "No federations established"; }

    /** Number of federated zones with active agreements. */
    default int federatedZoneCount() { return 0; }

    /** Propose a bilateral agreement to a remote zone. */
    default String proposeFederation(String zoneId) { return "Federation not available (single-node mode)"; }

    /** Accept a pending federation proposal. */
    default String acceptFederation(String zoneId) { return "Federation not available (single-node mode)"; }

    /** Revoke a federation agreement. */
    default String revokeFederation(String zoneId) { return "Federation not available (single-node mode)"; }

    /** Request transit to a federated zone. Returns transit token or error. */
    default String requestTransit(String playerId, String playerName, String targetZoneId) {
        return "Transit not available (single-node mode)";
    }

    /** List currently visiting transit agents. */
    default String formatTransitAgents() { return "No visitors"; }

    /**
     * Start a proxied session to a remote zone for a player.
     * The player's WebSocket session begins rendering remote zone content.
     *
     * @param playerId       player DID
     * @param remoteZoneId   target zone to proxy into
     * @param transitToken   transit token for authentication
     * @return true if the remote session was started
     */
    default boolean startTransit(String playerId, String remoteZoneId, String transitToken) {
        return false;
    }

    /**
     * Resolve a user-typed zone reference into a canonical address. Inputs:
     * {@code kitchen} (own label), {@code alice:kitchen} (contact + label),
     * {@code alice} (contact + default label), {@code did:wyrd:z6Mk…:kitchen}
     * (canonical).
     *
     * <p>Returns a JSON string that docks.js / CLI parse directly:</p>
     * <pre>
     * {"ok":true,  "canonical":"did:wyrd:z6Mk…:kitchen",
     *              "fingerprint":"z6Mk…", "label":"kitchen"}
     * {"ok":false, "code":"unknown_alias", "message":"No contact named 'charlie'. …"}
     * </pre>
     *
     * <p>Stays on this interface (not on a new one) because the scripting
     * module must not depend on core. The default returns a "not available"
     * error so nodes without the resolver wired (test fixtures, degraded
     * bootstrap) still behave predictably.</p>
     */
    default String resolveZone(String input) {
        return "{\"ok\":false,\"code\":\"unavailable\",\"message\":\"Zone resolution not available\"}";
    }

    /**
     * Browse the zone directory. Mode determines the
     * query shape; all return a JSON array of summarised manifest entries
     * ({@code did, zoneLabel, displayName, tagline, tags, capabilities, …})
     * suitable for the Atrium room script to render to players.
     *
     * <p>Supported modes:</p>
     * <ul>
     *   <li>{@code "recent"} — last N refreshed manifests (arg = limit as string)</li>
     *   <li>{@code "tag:<name>"} — DIDs tagged with {@code name}</li>
     *   <li>{@code "capability:<name>"} — DIDs advertising capability</li>
     *   <li>{@code "search:<text>"} — hybrid keyword+semantic search</li>
     * </ul>
     *
     * <p>Returns {@code "[]"} when the directory is unavailable — the
     * Atrium script checks for empty and renders a friendly explanation.</p>
     */
    default String discoverZones(String mode, String arg) {
        return "[]";
    }

    // --- Library methods ---

    /** Search library capabilities by keyword. */
    default String searchLibrary(String query) { return "Library not available"; }

    /** Browse library capabilities by category. */
    default String browseLibrary(String category) { return "Library not available"; }

    /** List all library capabilities. */
    default String listLibrary() { return "Library not available"; }

    /** Inspect a specific capability. */
    default String inspectCapability(String capabilityId) { return "Library not available"; }

    /** Register a new capability. */
    default String registerCapability(String name, String description, String category, String version) {
        return "Library not available";
    }

    /** Library status summary. */
    default String formatLibraryStatus() { return "No capabilities registered"; }

    /** Total capability count. */
    default int capabilityCount() { return 0; }

    /** Block a capability name from registration. */
    default String blockCapability(String name, String reason) { return "Library not available"; }

    /** Unblock a capability name. */
    default String unblockCapability(String name) { return "Library not available"; }

    /** Audit trail for a capability. */
    default String auditCapability(String capabilityId) { return "Library not available"; }

    // --- Knowledge (The Stacks / OPDS-K) ---

    /** Search knowledge base by query (returns formatted results). */
    default String searchKnowledge(String query) { return "Knowledge base not available"; }

    /** Search knowledge base filtered by pack. */
    default String searchKnowledgeByPack(String query, String packName) { return "Knowledge base not available"; }

    /** List installed knowledge packs. */
    default String listKnowledgePacks() { return "No knowledge packs installed"; }

    /** Knowledge base statistics. */
    default String formatKnowledgeStatus() { return "No knowledge base"; }

    /** Read a specific chunk by ID. */
    default String readKnowledgeChunk(String chunkId) { return "Chunk not found"; }

    // --- Library stewardship ---

    /** Registry packs available to install, grouped by tier/shelf, with installed markers. */
    default String listAvailablePacks() { return "Pack registry not available"; }

    /** Dispatch an async install of a registry pack. Returns a status line. */
    default String installKnowledgePack(String packName) { return "Pack install not available"; }

    /** Pending Library acquisitions (agent proposals + gap signals) awaiting the steward. */
    default String listLibraryProposals() { return "No pending Library proposals"; }

    /** Approve a pending proposal by id prefix; ingest runs in the background. */
    default String approveLibraryProposal(String idPrefix, String reviewer) { return "Proposals not available"; }

    /** Reject a pending proposal by id prefix with a reason. */
    default String rejectLibraryProposal(String idPrefix, String reviewer, String reason) { return "Proposals not available"; }

    /** Recent repeated library-search misses — what the household keeps asking that the Library can't answer. */
    default String libraryTopMisses() { return "No reading log available"; }

    // --- Study (private per-user content) ---

    /** Write a shared journal entry. Returns entry ID. */
    default String writeJournalEntry(String userDid, String content) { return "Study not available"; }

    /** Write a private journal entry (companion cannot read). Returns entry ID. */
    default String writePrivateJournalEntry(String userDid, String content) { return "Study not available"; }

    /** Search the user's journal entries. */
    default String searchJournal(String userDid, String query) { return "Study not available"; }

    /** Search all Study content for a user. */
    default String searchStudy(String userDid, String query) { return "Study not available"; }

    /** Get Study stats for a user. */
    default String formatStudyStatus(String userDid) { return "No Study data"; }

    // --- Voice profile (in-world steward edits, gated to The Study) ---

    /** Render the user's voice profile (clauses + revision + frozen) for in-world display. */
    default String formatVoiceProfile(String userDid) { return "Voice profile not available"; }

    /** Render the user's voice profile revision history for in-world display. */
    default String formatVoiceHistory(String userDid) { return "Voice profile not available"; }

    /** Set or update a clause in the user's voice profile. Returns a status line. */
    default String setVoiceClause(String userDid, String key, String value, String reason, String author) {
        return "Voice profile not available";
    }

    /** Unset a clause from the user's voice profile. Returns a status line. */
    default String unsetVoiceClause(String userDid, String key, String reason, String author) {
        return "Voice profile not available";
    }

    /** Freeze the user's voice profile. */
    default String freezeVoice(String userDid, String reason, String author) {
        return "Voice profile not available";
    }

    /** Unfreeze the user's voice profile. */
    default String unfreezeVoice(String userDid, String reason, String author) {
        return "Voice profile not available";
    }

    /** Revert the voice profile to a prior revision. Returns a status line. */
    default String revertVoice(String userDid, int targetRevision, String author) {
        return "Voice profile not available";
    }

    // --- Reputation (§17) ---

    /** Reputation summary for all entities. */
    default String formatReputationSummary() { return "No reputation data available"; }

    /** Reputation for a specific entity. */
    default String formatReputation(String entityId) { return "No reputation data for " + entityId; }

    // --- Room adjacency (§31) ---

    /** Summary of adjacent rooms (names and entity counts). */
    default String formatAdjacentSummary(String roomId) { return "No adjacent room data available"; }

    // --- Health / Engine Room ---

    /** Engine Room health summary with alerts and thresholds. */
    default String formatHealthStatus() { return "No health data available"; }

    // --- Inference methods ---

    /** Inference backend status for Boiler Room and Bridge. */
    default String formatInferenceStatus() { return "No inference backends configured"; }

    /** Number of configured inference backends. */
    default int inferenceBackendCount() { return 0; }

    // --- Governance (§98, CouncilService) ---

    /** List active proposals. */
    default String listProposals() { return "No active proposals"; }

    /** Submit a proposal. Returns proposal summary. */
    default String submitProposal(String proposerDid, String title, String description) {
        return "Governance not available";
    }

    /** Cast a vote on a proposal. */
    default String castVote(String proposalId, String voterDid, boolean approve) {
        return "Governance not available";
    }

    /** Tally votes for a proposal. */
    default String tallyVotes(String proposalId) { return "Governance not available"; }

    // --- Network allowlist (, `scroll net …`) ---

    /**
     * Add/replace a steward network-allowlist entry. {@code kindsCsv} like
     * {@code "ssh,scp"}; {@code keyRef} like {@code household:second-node} (required
     * for ssh/scp — validated to resolve before persisting). Returns JSON
     * {@code {"ok":true,"host":…}} or {@code {"ok":false,"error":…}}.
     */
    default String netAllow(String host, String kindsCsv, String keyRef, String commandPrefix) {
        return "{\"ok\":false,\"error\":\"network allowlist not available\"}";
    }

    /** Remove the allowlist entry for {@code host}. Same JSON result shape. */
    default String netRevoke(String host) {
        return "{\"ok\":false,\"error\":\"network allowlist not available\"}";
    }

    /**
     * The LIVE merged allowlist (steward store + config entries) as a JSON
     * array of {@code {host, kinds, keyRef, commandPrefix}}.
     */
    default String netList() { return "[]"; }

    // --- Soul/Forge (§85, Forge cycle data) ---

    /** Current soul manifest summary for an entity. */
    default String formatManifestSummary(String entityDid) { return "No manifest data"; }

    /** Forge cycle history (last N cycles). */
    default String formatForgeHistory(String entityDid, int count) { return "No forge history"; }
}
