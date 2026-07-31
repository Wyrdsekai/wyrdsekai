package org.wyrdsekai.scripting.api;

import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Provides world access for item scripts via world.library, world.web, etc.
 * Defined in scripting module to avoid circular dependency (scripting cannot reference core).
 * Core provides the implementation wrapping WyrdLuceneStore, WebSearchService,
 * InferenceRouter, OraclePredictionCache, and CompanionActor callbacks.
 *
 * <p>All return types are Map/List/String — GraalJS-friendly primitives that
 * don't require @HostAccess.Export on record accessors.</p>
 *
 * <p>LLM methods are synchronous (blocking). The inference slot is free because
 * the companion's own inference (that triggered the tool call) has already completed.</p>
 */
public interface ItemWorldApiProvider {

    /** Identity of the acting entity (companion DID or player entity id).
     *  Backs {@code world.self.*} — three shipped items called it for months
     *  while the namespace didn't exist (second-node audit 2026-07-11). */
    default String selfDid() { return null; }

    /** Display name of the acting entity. */
    default String selfName() { return null; }

    // ─── Library / Knowledge ──────────────────────────────────────

    /**
     * Search library and knowledge packs.
     * @return List of maps with keys: id, title, text (snippet), pack, score
     */
    List<Map<String, Object>> searchKnowledge(String query, int limit);

    /**
     * Read a specific knowledge chunk by ID.
     * @return Map with keys: id, title, text (full content), pack — or null if not found
     */
    Map<String, Object> readKnowledgeChunk(String chunkId);

    // ─── Web ──────────────────────────────────────────────────────

    /**
     * Search the web via Searxng/Brave/Tavily/etc.
     * @param type "general" or "news"
     * @return List of maps with keys: title, url, snippet
     */
    List<Map<String, Object>> webSearch(String query, String type, int limit);

    /**
     * Fetch a web page and extract text content.
     * @param maxChars Maximum characters to return (truncated)
     * @return Extracted text, or error message prefixed with "[error]"
     */
    String webFetch(String url, int maxChars);

    // ─── Oracle / Predictions ─────────────────────────────────────

    /**
     * Query the oracle for predictions and pattern analysis.
     * @param analysisType "patterns", "anomalies", or "predictions"
     * @return List of maps with keys: summary, confidence, category, timestamp
     */
    List<Map<String, Object>> queryOracle(String topic, String analysisType);

    // ─── LLM (synchronous, blocking) ─────────────────────────────

    /**
     * Summarize text using the LLM. Routes via cap:routine for smallest available model.
     * Blocks the script thread. ~200 token prompt, 2-5s on typical hardware.
     * @param text The text to summarize
     * @param instruction Additional guidance (e.g., "Focus on key findings about X")
     * @return Summary text, or "[error] ..." on failure
     */
    String llmSummarize(String text, String instruction);

    /**
     * Analyze text with a custom prompt using the LLM.
     * @param text The text to analyze
     * @param prompt The analysis instruction
     * @return Analysis result, or "[error] ..." on failure
     */
    String llmAnalyze(String text, String prompt);

    // ─── Agent Actions (thread-safe) ─────────────────────────────

    /** Speak text in the current room. Thread-safe: sends tell to room actor. */
    void agentSpeak(String text);

    /** Store something in the agent's significance buffer. Thread-safe. */
    void agentRemember(String content);

    /** Send a message to another agent or player. Thread-safe. */
    void agentTell(String target, String message);

    // ─── Navigation knowledge ─────────────────────────────────────

    /**
     * Snapshot of all rooms in the current zone topology.
     * Each map: {id, name, zone}. Used by map items to expose the world to the agent.
     */
    default List<Map<String, Object>> zoneRooms() { return List.of(); }

    /**
     * Record that the agent now knows about these rooms (e.g., after examining a map item).
     * Folds into the agent's mappedRooms set; combined with visitedRooms for travel_to /
     * teleport_to gating.
     */
    default void recordMappedRooms(List<String> roomIds) { /* default no-op */ }

    // ─── Catalog / Standard Library ────────────────────────────────

    /**
     * Search the template catalog by keyword.
     * @return List of maps with keys: name, displayName, description, category, level
     */
    default List<Map<String, Object>> catalogSearch(String query) { return List.of(); }

    /**
     * Filter templates by category.
     * @return List of maps with keys: name, displayName, description, category, level
     */
    default List<Map<String, Object>> catalogByCategory(String category) { return List.of(); }

    /**
     * Get detailed template information.
     * @return Map with keys: name, displayName, description, category, baseScript, level,
     *         thematic (nested: domains, symbols, actions), defaultConfig — or null
     */
    default Map<String, Object> catalogTemplateInfo(String templateName) { return null; }

    // ─── Composition / Binding ──────────────────────────────────

    /**
     * Evaluate whether two items can compose coherently.
     * @return Map with keys: score, compatible, suggestion, bindingHint, evaluationPath
     */
    default Map<String, Object> composeEvaluate(String item1Id, String item2Id) {
        return Map.of("ok", false, "error", "Composition evaluation not available");
    }

    /**
     * Bind two items together with a declared narrative intent.
     * @return Map with keys: bound, resultDescription — or error
     */
    default Map<String, Object> composeBind(String item1Id, String item2Id, String intent) {
        return Map.of("ok", false, "error", "Composition binding not available");
    }

    // ─── Inventory / Composition ─────────────────────────────────

    /**
     * List equipped items.
     * @return List of maps with keys: id, name, description
     */
    List<Map<String, Object>> inventoryList();

    /**
     * Use another equipped item (composition). Recursive — executes the target item's script.
     * @param depth Current composition depth (blocked at depth >= 3)
     * @return Result map from the invoked item, or error map
     */
    Map<String, Object> inventoryUse(String itemId, Map<String, Object> params, int depth);

    // ─── Zone Awareness ──────────────────────────────────────────

    /**
     * Current zone where this script is executing (host zone).
     * Default returns value of WYRDSEKAI_ZONE_ID env var, or "local".
     */
    default String currentZone() {
        var env = System.getenv("WYRDSEKAI_ZONE_ID");
        return env != null ? env : "local";
    }

    /**
     * Home zone of the entity using the item. For local items, equals currentZone().
     * For items carried by a traveling visitor, this is the visitor's home zone.
     */
    default String homeZone() {
        return currentZone();
    }

    // ─── — Spatial control surfaces ────────────────────

    /**
     * The DID of the entity "holding" the item right now. Used by Home
     * furnishings to decide whose audit/grants/budget to display.
     * Default returns the script's configured caller DID, or {@code null} if
     * the provider doesn't know (in which case the script should fail soft).
     */
    default String callerDid() { return null; }

    /**
     * Recent audit entries on the caller's Home. Returned
     * newest-first. Returns empty when the HomeRegistry isn't available.
     *
     * @param limit maximum entries; clamped to a sane upper bound by impl
     * @return list of maps with keys: timestamp, actor, verb, resource, outcome, detail
     */
    default List<Map<String, Object>> auditRecent(int limit) { return List.of(); }

    /**
     * Grants the caller has issued. Returns shapes compatible
     * with the REST {@code /api/home/grants/issued} endpoint.
     */
    default List<Map<String, Object>> grantsIssued() { return List.of(); }

    /**
     * Grants the caller holds (has been granted to them).
     */
    default List<Map<String, Object>> grantsHeld() { return List.of(); }

    /**
     * Pending grant-requests addressed to the caller.
     * Each entry: {@code id, requester, resource, resourceType, capability,
     * reason, createdAt}.
     */
    default List<Map<String, Object>> pendingGrantRequests() { return List.of(); }

    // ─── (P4) — in-world relay governance ──────

    /**
     * Whether this zone administers a relay (owns it or holds a relay-admin
     * grant), plus the caller's effective scope. Keys:
     * <ul>
     *   <li>{@code configured} (boolean) — a relay is wired + a gateway exists;</li>
     *   <li>{@code relayDid} / {@code relayLabel} / {@code ownerDid};</li>
     *   <li>{@code scope} — {@code "owner"|"full"|"moderation"|"invite-only"} or
     *       null (caller has no authority);</li>
     *   <li>{@code canDelegate} (boolean).</li>
     * </ul>
     * Default: {@code {configured:false}}.
     */
    default Map<String, Object> relayInfo() { return Map.of("configured", false); }

    /**
     * The administered relay's registrations (DID + petname/tier/last_seen),
     * authorized zone-side for the caller's scope. Empty when no relay /
     * caller lacks moderation scope. Each entry mirrors the relay's
     * {@code list} op rows, with {@code petname} resolved when reachable.
     */
    default List<Map<String, Object>> relayRegistrations() { return List.of(); }

    /**
     * The relay-admin delegations this zone has issued (who holds relay-admin
     * grants and at what scope). Empty when no relay / not visible.
     */
    default List<Map<String, Object>> relayDelegations() { return List.of(); }

    /**
     * Perform a signed relay-admin op, gated zone-side by the caller's grant
     * scope (P2) and again relay-side (P3). {@code op} is the wire name
     * ({@code invite|remove|grant-admin|revoke-admin|...}); {@code args} may be
     * empty. Returns the relay's parsed response plus {@code ok}/{@code status},
     * or {@code {ok:false, error:...}} on a zone-side denial / no gateway.
     */
    default Map<String, Object> relayAdminAction(String op, Map<String, Object> args) {
        return Map.of("ok", false, "error", "no relay configured for this zone");
    }

    // ─── Pairing (LAN node-to-household onboarding) ────────────────

    /**
     * Pending pairing challenges (devices/nodes that have requested to join
     * this household but haven't been approved yet). Powers the Threshold
     * furnishing in Study so the steward can see + approve from the world
     * surface, not just from the CLI.
     *
     * <p>Each entry: {@code challengeId, code, deviceName, deviceType,
     * createdAt, expiresAt}. Empty when no pending challenges.</p>
     */
    default List<Map<String, Object>> pendingPairings() { return List.of(); }

    /**
     * The currently active 6-digit pair code (null if none). Convenience for
     * the Threshold furnishing so it can show the code without making the
     * caller match by id.
     */
    default String activePairCode() { return null; }

    /**
     * The active pre-shared household key for headless pairing, if any.
     * Steward-only context — return null for non-stewards. Empty value means
     * no key is configured (call {@link #generateHouseholdKey()} to create one).
     */
    default String activeHouseholdKey() { return null; }

    /**
     * Generate (or rotate) the household key. Steward-only.
     * Returns the new key string.
     */
    default String generateHouseholdKey() { return null; }

    /**
     * Paired devices belonging to the ACTING player (Threshold furnishing —
     * device roster). Each entry has {@code kind} ("device" or "ssh-key")
     * plus device fields ({@code id, name, type, pairedAt, lastSeen,
     * revoked}) or SSH-key fields ({@code keyLine, comment, addedAt}).
     * Empty when no pairing/auth service is wired on this surface.
     */
    default List<Map<String, Object>> pairedDevices() { return List.of(); }

    /**
     * Revoke a paired device by id. Caller-scoped: the provider only revokes
     * devices linked to the acting player (steward may revoke any).
     * Returns {@code {ok:true, id}} or {@code {ok:false, error}}.
     */
    default Map<String, Object> pairingRevokeDevice(String deviceId) {
        return Map.of("ok", false, "error", "pairing service not available here");
    }

    // ─── Household control panel (Study steward surfaces) ──────────

    /**
     * Household roster — every registered account. Each entry:
     * {@code username, displayName, role, createdAt}. Empty when no
     * auth service is wired on this surface.
     */
    default List<Map<String, Object>> householdMembers() { return List.of(); }

    /**
     * Change a member's role. The provider passes the ACTING player's user id
     * as the caller so {@code AuthService.setRole}'s steward check applies.
     * Returns {@code {ok:true, username, role}} or {@code {ok:false, error}}
     * ({@code "steward only"} on permission denial).
     */
    default Map<String, Object> householdSetRole(String username, String role) {
        return Map.of("ok", false, "error", "household service not available here");
    }

    /**
     * Remove a member account. Caller-checked steward-only at the service
     * ({@code AuthService.removeUser}); self-removal always refused.
     */
    default Map<String, Object> householdRemoveMember(String username) {
        return Map.of("ok", false, "error", "household service not available here");
    }

    /**
     * All invites, newest-first (steward-only view — non-stewards get an
     * empty list). Each entry: {@code id, code, intendedName, role,
     * createdBy, createdAt, expiresAt, consumed, expired} (+ {@code
     * consumedBy, consumedAt} once redeemed).
     */
    default List<Map<String, Object>> inviteList() { return List.of(); }

    /**
     * Mint an invite code (steward-only; caller = acting player).
     * Returns {@code {ok:true, id, code, role, intendedName, expiresAt}}
     * or {@code {ok:false, error}}.
     */
    default Map<String, Object> inviteCreate(String role, String intendedName) {
        return Map.of("ok", false, "error", "invite service not available here");
    }

    /**
     * Revoke a pending invite by id or by its passphrase code (steward-only).
     */
    default Map<String, Object> inviteRevoke(String codeOrId) {
        return Map.of("ok", false, "error", "invite service not available here");
    }

    /**
     * Wards (room-level access grants) on a room. Each entry:
     * {@code roomId, subject, capability, grantedBy, createdAt}.
     */
    default List<Map<String, Object>> wardList(String roomId) { return List.of(); }

    /**
     * Grant a ward capability to a subject in a room (steward or room-admin;
     * {@code grantedBy} = acting player).
     */
    default Map<String, Object> wardGrant(String roomId, String subject, String capability) {
        return Map.of("ok", false, "error", "ward service not available here");
    }

    /** Revoke a ward capability (steward or room-admin). */
    default Map<String, Object> wardRevoke(String roomId, String subject, String capability) {
        return Map.of("ok", false, "error", "ward service not available here");
    }

    /**
     * Parental controls per member (parental-controls scroll). Each entry:
     * {@code username, displayName, dailyMinutes, dailyInference,
     * contentFilter, blockedRooms, minutesUsedToday, inferencesUsedToday}.
     * Steward sees every controlled member; a non-steward sees only their
     * own entry. Empty when no parental service is wired on this surface.
     */
    default List<Map<String, Object>> parentalList() { return List.of(); }

    /**
     * One member's controls + today's usage (same shape as
     * {@link #parentalList()} entries, plus {@code ok}). Steward may read
     * any member; a non-steward only themselves.
     */
    default Map<String, Object> parentalGet(String username) {
        return Map.of("ok", false, "error", "parental service not available here");
    }

    /**
     * Set one control field for a member (steward-only at the service;
     * caller = acting player). Fields: {@code minutes} / {@code inference}
     * (number or {@code "off"}), {@code filter} ({@code strict}/{@code off}),
     * {@code block-room} / {@code unblock-room} (room-id glob).
     */
    default Map<String, Object> parentalSet(String username, String field, Object value) {
        return Map.of("ok", false, "error", "parental service not available here");
    }

    /** Remove every control from a member (steward-only at the service). */
    default Map<String, Object> parentalClear(String username) {
        return Map.of("ok", false, "error", "parental service not available here");
    }

    /**
     * Maintenance status (maintenance dial): {@code ok, on, reason, setBy,
     * since, scheduleHours, lastScheduledBackup, snapshotCount,
     * latestSnapshotId, latestSnapshotAt, staged} (staged is a map of the
     * pending restore or null). Open read — the mode shows at every login.
     */
    default Map<String, Object> maintenanceStatus() {
        return Map.of("ok", false, "error", "maintenance service not available here");
    }

    /** Flip maintenance mode (steward-only at the service; caller = acting player). */
    default Map<String, Object> maintenanceSetMode(boolean on, String reason) {
        return Map.of("ok", false, "error", "maintenance service not available here");
    }

    /** Run a backup snapshot now (steward-only at the service). */
    default Map<String, Object> maintenanceBackupNow() {
        return Map.of("ok", false, "error", "maintenance service not available here");
    }

    /** Set the scheduled-backup cadence in hours, 0 = off (steward-only at the service). */
    default Map<String, Object> maintenanceSetSchedule(int hours) {
        return Map.of("ok", false, "error", "maintenance service not available here");
    }

    /**
     * Stage a snapshot restore for the next boot (steward-only at the
     * service). The live db is untouched until restart applies it.
     */
    default Map<String, Object> maintenanceStageRestore(String snapshotId) {
        return Map.of("ok", false, "error", "maintenance service not available here");
    }

    /** Un-stage a pending restore (steward-only at the service). */
    default Map<String, Object> maintenanceClearStagedRestore() {
        return Map.of("ok", false, "error", "maintenance service not available here");
    }

    /**
     * Enrolled/connected household node snapshot (Between mesh topology).
     * Each entry: {@code nodeId, connected, latencyMs, appVersion,
     * lastHeartbeat, connectionAgeMs, self?}. Empty when node data isn't
     * wired on this surface.
     */
    default List<Map<String, Object>> nodesList() { return List.of(); }

    /**
     * Household-level resource-usage aggregate (Treasury furnishing) —
     * summed over every tracked agent/member. Keys: {@code agents,
     * inferences, mcpCalls, tokens, monetaryCost, firstActivity,
     * lastActivity}. Empty map when no cost tracker is live.
     */
    default Map<String, Object> treasurySummary() { return Map.of(); }

    /**
     * Per-member/per-agent usage breakdown. Each entry: {@code agentId,
     * inferences, mcpCalls, tokens, monetaryCost, avgLatencyMs,
     * firstActivity, lastActivity, budgetNote?}.
     */
    default List<Map<String, Object>> treasuryPerMember() { return List.of(); }

    /**
     * Set a member/agent daily budget limit in USD (steward-only;
     * in-memory today — resets on restart until persistent budgets land).
     */
    default Map<String, Object> treasurySetBudget(String member, double dailyLimitUsd) {
        return Map.of("ok", false, "error", "treasury service not available here");
    }

    /**
     * W5 (2026-07-11): transfer mutual credits from the ACTING player to
     * another household entity via the Counting House. Keys on success:
     * {@code ok, message}; on failure {@code ok:false, error}.
     */
    default Map<String, Object> treasuryTransfer(String toEntity, long amount, String note) {
        return Map.of("ok", false, "error", "treasury service not available here");
    }

    /**
     * W5 (2026-07-11): a household entity's mutual-credit balance. Keys:
     * {@code ok, entityId, balance, creditLimit, totalEarned, totalSpent};
     * {@code ok:false, error} when the Counting House isn't reachable.
     */
    default Map<String, Object> treasuryBalance(String entityId) {
        return Map.of("ok", false, "error", "treasury service not available here");
    }

    /**
     * Steward security-audit events (§101 StewardAuditLog), newest-first.
     * Each entry: {@code timestamp, actor, actorName, type, targetId,
     * description, approved}. Distinct from {@link #auditRecent(int)}
     * (Home audit trail) — this is the household security log.
     */
    default List<Map<String, Object>> auditSecurity(int limit) { return List.of(); }

    /**
     * Backup snapshots on this node (The Safe — read-only this pass;
     * create/restore stay on the CLI). Each entry: {@code id, location,
     * timestamp, sizeBytes, source}.
     */
    default List<Map<String, Object>> safeSnapshots() { return List.of(); }

    // ─── — Budget / federation / owned inventory ────

    /**
     * Resource-usage summary for the caller (Ledger furnishing).
     * Keys: {@code inferences, mcpCalls, tokens, avgLatencyMs, budgetLimit,
     * budgetSpentToday, firstActivity, lastActivity}. Empty map when no usage
     * is recorded (fresh Home).
     */
    default Map<String, Object> budgetSummary() { return Map.of(); }

    /**
     * Federation agreements for the zone hosting this Home (Manifest furnishing).
     * Each entry: {@code remoteZone, status, trustLevel, agreedAt, expiresAt,
     * localQuotaDaily, remoteQuotaDaily}.
     */
    default List<Map<String, Object>> federationAgreements() { return List.of(); }

    /**
     * mesh-state matrix — both-sides view of every
     * agreement. Returns a map with {@code localZone}, {@code entries} (list
     * of {@code partnerZoneId, localStatus, partnerStatus, consensus}),
     * {@code agreeCount}, {@code mismatchCount}, {@code unreachableCount},
     * {@code probedAt}. Empty map if mesh probing isn't wired.
     */
    default Map<String, Object> federationMeshStatus() { return Map.of(); }

    /**
     * local node's build/version stamp.
     * Returns {@code appVersion, buildHash, gitSha, gitDirty,
     * buildTimestamp, wireProtocol, federationSchema, zoneId}. Empty
     * map if not wired (e.g., visitor scope without world access).
     */
    default Map<String, Object> versionLocal() { return Map.of(); }

    /**
     * mesh build-version matrix —
     * local + every peer's last-seen buildVersion. Returns
     * {@code local: {...}, peers: [{zoneId, agreementStatus,
     * buildVersion: {...}}, ...]}. Empty map if not wired or no
     * federation peers.
     */
    default Map<String, Object> versionMesh() { return Map.of(); }

    /**
     * Items owned by the caller in the current zone (Trunk furnishing).
     * Each entry: {@code id, name, description, takeable, scripted, takenFrom}.
     */
    default List<Map<String, Object>> inventoryOwned() { return List.of(); }

    /**
     * Bonds the caller is party to (Shelf furnishing).
     * Each entry: {@code bondId, partner, depth, depthLevel, interactionCount,
     * scarred, active, formedAt, lastInteraction}.
     */
    default List<Map<String, Object>> bondsList() { return List.of(); }

    /**
     * Companions bound to this zone (Companion Codex furnishing). One entry
     * per companion soul — a household can keep more than one. Each entry:
     * {@code name, entityId, did, temperament, voiceRevision, voiceClauses,
     * relationships, forgedAt, online, room}.
     */
    default List<Map<String, Object>> companionsList() { return List.of(); }

    /** Formal bondholder handover (2026-07-18). Steward-gated; only the
     *  player-scoped Home provider implements it — everywhere else says so. */
    default Map<String, Object> bondsTransfer(String targetUsername) {
        return Map.of("ok", false,
            "error", "Bondholder transfer isn't available from this surface.");
    }

    /** Study-side companion birth (2026-07-18). Steward-gated; only the
     *  player-scoped Home provider implements it. */
    default Map<String, Object> companionsBirth(String name) {
        return Map.of("ok", false,
            "error", "Companion birth isn't available from this surface.");
    }

    /**
     * Entities currently present in the caller's Home room (Lantern furnishing).
     * Each entry: {@code entityId, name, type}.
     */
    default List<Map<String, Object>> presenceInHome() { return List.of(); }

    /**
     * Notification channel configuration for the caller (Compass furnishing).
     * Each entry: {@code channel, enabled, destination?}.
     */
    default List<Map<String, Object>> notificationChannels() { return List.of(); }

    /**
     * MCP tools available to the caller (Manifest extension / future MCP furnishing).
     * Each entry: {@code server, tool, description?, granted}.
     */
    default List<Map<String, Object>> mcpTools() { return List.of(); }

    /**
     * Skill drafts pending the caller's review.
     * Each entry: {@code draftId, name, description, rationale, runtime,
     * closesGaps, replaces, proposedAt, proposedByModel}.
     * Returned in proposed-newest-first order.
     */
    default List<Map<String, Object>> pendingSkillDrafts() { return List.of(); }

    // agent-runnable governed recipes (list/inspect/run/status).
    // Default no-ops so non-recipe providers (stubs, phone) compile unchanged; the
    // real implementation lives in ItemWorldApiProviderImpl backed by RecipeService.
    default List<Map<String, Object>> recipeList() { return List.of(); }
    default Map<String, Object> recipeInspect(String name) {
        return Map.of("ok", false, "error", "recipe.inspect not wired");
    }
    default Map<String, Object> recipeRun(String name, Map<String, Object> params) {
        return Map.of("ok", false, "error", "recipe.run not wired");
    }
    default Map<String, Object> recipeStatus(String runId) {
        return Map.of("ok", false, "error", "recipe.status not wired");
    }

    /**
     * Track-C C7 — Study furnishing reads. Cross-recipe
     * snapshot the {@code recipes_console} furnishing renders.
     * Default empty so non-recipe providers compile unchanged.
     */
    default List<Map<String, Object>> recipeEnrolled() { return List.of(); }

    /**
     * Track-C C7 — cross-recipe last-N completed runs.
     * Returns newest first. {@code limit} clamped to [1, 100].
     */
    default List<Map<String, Object>> recipeRecentRuns(int limit) { return List.of(); }

    /**
     * drive + vitality snapshot for the Drives
     * Mirror furnishing in the Hearth. Default: empty map (no snapshot
     * registered). Companion DIDs publish through {@code DriveSnapshotRegistry}
     * on every vitality tick; the impl reads the latest.
     *
     * <p>Keys: {@code drives} (map of drive name → 0..1 pressure),
     * {@code vitality} (map of tank name → 0..1 fill),
     * {@code mood} (string summary), {@code updatedAtMillis}
     * (epoch millis at snapshot time, useful for staleness checks).</p>
     */
    default Map<String, Object> driveSnapshot() { return Map.of(); }

    /**
     * Coding Slate snapshot.
     *
     * <p>Returns the per-backend status surface used by the Coding Slate
     * Study furnishing. Default: empty list (no backends registered, e.g.
     * fresh install before any Phase 1+ wiring).</p>
     *
     * <p>Each entry is a map with keys: {@code name} (String — stable
     * backend name), {@code tier} (String — LOCAL_FREE / LOCAL_HEAVY /
     * CLOUD_PAID), {@code enabled} (boolean — config flag), {@code healthy}
     * (boolean — last health probe), {@code lastTask} (Map — last submitted
     * task summary or null), {@code successRate30d} (Double — 0..1 success
     * rate over 30 days, or {@code null} if no historical data).</p>
     *
     * <p>Phase 1b returns the registry's current backends with placeholder
     * {@code lastTask=null} and {@code successRate30d=null}; Phase 5 adds
     * the historical accounting.</p>
     */
    default List<Map<String, Object>> codingBackendsStatus() { return List.of(); }

    // ─── -§4.3 — universal writes ──────

    /** §4.2 — index a chunk into the library/knowledge store. */
    default Map<String, Object> libraryAdd(String text, Map<String, Object> opts) {
        return Map.of("ok", false, "error", "library.add not wired");
    }

    /** §4.2 — tag an existing knowledge chunk. */
    default Map<String, Object> libraryTag(String chunkId, List<String> tags) {
        return Map.of("ok", false, "error", "library.tag not wired");
    }

    /** §4.2 — delete a knowledge chunk (Tier 5; same-zone agents share library). */
    default Map<String, Object> libraryDelete(String chunkId) {
        return Map.of("ok", false, "error", "library.delete not wired");
    }

    /** §4.2 — write a journal entry. opts may include visibility ("shared"|"private").
     *  MUST carry an explicit {@code ok:false} (2026-07-18): the journal item's guard
     *  checks {@code written.ok === false}, and a bare {@code {error}} default read as
     *  success (undefined !== false), so a player-route write was silently DISCARDED
     *  while the item narrated "Written down". This is the exact silent-data-loss class
     *  the journal item was written to prevent — the default must never look like ok. */
    default Map<String, Object> journalWrite(String content, Map<String, Object> opts) {
        return Map.of("ok", false, "error", "journal.write not wired");
    }

    /** §4.2 — search caller's own journal. */
    default List<Map<String, Object>> journalSearch(String query, int limit) {
        return List.of();
    }

    /** §4.2 — last n shared journal entries. */
    default List<Map<String, Object>> journalRecent(int limit) {
        return List.of();
    }

    /** §4.2 — add a Study note. */
    default Map<String, Object> notesAdd(String content, List<String> tags) {
        return Map.of("ok", false, "error", "notes.add not wired");
    }

    /** §4.2 — list notes (optional tag filter). */
    default List<Map<String, Object>> notesList(String tag) {
        return List.of();
    }

    /** §4.2 — delete a note (author-only). Returns ok=false reason=not_owner if non-owner. */
    default Map<String, Object> notesDelete(String id) {
        return Map.of("ok", false, "error", "notes.delete not wired");
    }

    /** §4.2 — pin to the Study pinboard. */
    default Map<String, Object> pinboardPin(String text, Map<String, Object> opts) {
        return Map.of("ok", false, "error", "pinboard.pin not wired");
    }

    /** §4.2 — list pins. */
    default List<Map<String, Object>> pinboardList() {
        return List.of();
    }

    /** §4.2 — remove a pin (author-only). Returns ok=false reason=not_owner if non-owner. */
    default Map<String, Object> pinboardUnpin(String id) {
        return Map.of("ok", false, "error", "pinboard.unpin not wired");
    }

    /** §4.2 — list tags across a scope ("library"|"notes"|"journal"|"all"). */
    default List<Map<String, Object>> tagsList(String scope) {
        return List.of();
    }

    /** §4.2 — list entries for a tag. */
    default List<Map<String, Object>> tagsEntries(String tag, String scope) {
        return List.of();
    }

    /** §4.3 — current room id (where the script is executing). */
    default String roomId() { return null; }

    /** §4.3 — current room name. */
    default String roomName() { return null; }

    /** §4.3 — current room description. */
    default String roomDescription() { return null; }

    /** §4.3 — entities currently present in the room. */
    default List<Map<String, Object>> roomEntities() { return List.of(); }

    /** §4.3 — objects in the room. */
    default List<Map<String, Object>> roomObjects() { return List.of(); }

    /** §4.3 — room exits. */
    default List<Map<String, Object>> roomExits() { return List.of(); }

    /** §4.3 — emit a structured room event (Tier 3).
     *  Default: error{no_room_bridge} — no fallback to agent.speak. */
    default Map<String, Object> roomEmit(String eventType, Map<String, Object> data) {
        return Map.of("ok", false, "error", "no_room_bridge",
            "message", "room.emit requires a wired RoomBridge");
    }

    /** §4.3 — narrate text into the room (Tier 3).
     *  Default: error{no_room_bridge} — no fallback to agent.speak. */
    default Map<String, Object> roomNarrate(String text) {
        return Map.of("ok", false, "error", "no_room_bridge",
            "message", "room.narrate requires a wired RoomBridge");
    }

    /** §4.3 — drop a temporary object into the room (ephemeral by default). */
    default Map<String, Object> roomAddObject(String id, String name, String description,
                                                boolean takeable, Map<String, Object> effects) {
        return Map.of("ok", false, "error", "no_room_bridge",
            "message", "room.add_object requires a wired RoomBridge");
    }

    /** §4.3 — remove an object from the room. */
    default Map<String, Object> roomRemoveObject(String id) {
        return Map.of("ok", false, "error", "no_room_bridge",
            "message", "room.remove_object requires a wired RoomBridge");
    }

    /** §4.3 — set ephemeral room property. */
    default Map<String, Object> roomSetProperty(String key, Object value) {
        return Map.of("ok", false, "error", "no_room_bridge",
            "message", "room.set_property requires a wired RoomBridge");
    }

    /** §4.3 — read room property. */
    default String roomGetProperty(String key) { return null; }

    /** §4.3 — change the room's description (reverts on reload). */
    default Map<String, Object> roomUpdateDescription(String text) {
        return Map.of("ok", false, "error", "no_room_bridge",
            "message", "room.update_description requires a wired RoomBridge");
    }

    // ─── — entity body state + room body language ──

    /**
     * set an entity's posture in the current room. The
     * spec accepts a free-form {@code postureSpec} map matching the
     * {@link org.wyrdsekai.common.model.Posture} shape:
     * {@code {verb, atObject?, descriptor, innerImprint?: {tanks?, drives?, triggersOnSet?}}}.
     * {@code setAt} is filled in by the provider. Returns
     * {@code {success: true}} on commit, or {@code {error, message}}.
     */
    default Map<String, Object> entitySetPosture(String entityId, Map<String, Object> postureSpec) {
        return Map.of("ok", false, "error", "no_room_bridge",
            "message", "entity.setPosture requires a wired RoomBridge");
    }

    /** — clear an entity's posture (return to default). */
    default Map<String, Object> entityClearPosture(String entityId) {
        return Map.of("ok", false, "error", "no_room_bridge",
            "message", "entity.clearPosture requires a wired RoomBridge");
    }

    /**
     * broadcast that an entity looked at another entity
     * or object with named manner. Emits {@link org.wyrdsekai.common.event.WorldEvent.LookedAt}.
     * {@code manner} may be null when the look is unflavored.
     */
    default Map<String, Object> entityLookAt(String actorId, String targetId, String manner) {
        return Map.of("ok", false, "error", "no_room_bridge",
            "message", "entity.lookAt requires a wired RoomBridge");
    }

    /**
     * broadcast a body-language line attributed to an
     * actor in the current room. Thin wrapper over {@code Emoted} for the
     * cases where a script's narration is felt-body-text rather than speech.
     */
    default Map<String, Object> roomBroadcastBodyLanguage(String actorId, String text) {
        return Map.of("ok", false, "error", "no_room_bridge",
            "message", "room.broadcastBodyLanguage requires a wired RoomBridge");
    }

    // ─── — adapter registry surface ──

    /**
     * Invoke a registered external adapter. Used by the dynamic
     * {@code world.<namespace>.<method>} proxy in {@link ItemWorldApi}.
     * Returns the adapter's normalized response shape:
     * {@code {success, data, error: {code, message, retryable}}}.
     *
     * <p>Default: empty error response. Phases O-T register adapters via
     * {@code ExternalAdapterRegistry}.</p>
     */
    default Map<String, Object> invokeAdapter(String namespace, String method,
                                                Map<String, Object> args) {
        return Map.of("success", false,
            "error", Map.of("code", "adapter_unavailable",
                            "message", "no adapter registered for " + namespace,
                            "retryable", false));
    }

    /** List registered adapter namespaces. Used by {@link ItemWorldApi} proxy resolution. */
    default Set<String> adapterNamespaces() {
        return Set.of();
    }

    // ─── — LLM extensions (Phase A2) ──

    /**
     * §4.4 — open-ended completion. {@code opts} accepts
     * {@code maxTokens, temperature, stop, system, model}. Returns
     * {@code {text, latencyMs, tokensIn, tokensOut}}; on failure
     * {@code {error: "...", text: "[error] ..."}}.
     */
    default Map<String, Object> llmComplete(String prompt, Map<String, Object> opts) {
        return Map.of("ok", false, "error", "llm.complete not wired", "text", "[error] llm.complete not wired");
    }

    /**
     * §4.4 — one-of classification. Returns {@code {label, confidence}} where
     * {@code label} is one of {@code labels} and {@code confidence ∈ [0,1]}.
     * On failure {@code {error: "...", label: null, confidence: 0.0}}.
     */
    default Map<String, Object> llmClassify(String text, List<String> labels) {
        return Map.of("ok", false, "error", "llm.classify not wired", "label", "", "confidence", 0.0);
    }

    /**
     * §4.4 — schema-constrained extraction. {@code schema} is a JSON Schema
     * (object) describing the desired fields. Returns the extracted Map
     * (Jackson-decoded) on success; {@code {error: "..."}} on failure.
     */
    default Map<String, Object> llmExtract(String text, Map<String, Object> schema) {
        return Map.of("ok", false, "error", "llm.extract not wired");
    }

    /**
     * §4.4 — tool-calling generation. {@code tools} is a list of OpenAI-style
     * tool definitions. Returns {@code {toolCalls: [...], finalText: ...}}.
     */
    default Map<String, Object> llmTools(String prompt,
                                          List<Map<String, Object>> tools,
                                          Map<String, Object> opts) {
        return Map.of("ok", false, "error", "llm.tools not wired",
            "toolCalls", List.of(),
            "finalText", "");
    }

    /**
     * §4.4 — current LLM token+cost budget snapshot. Returns
     * {@code {tokens, costUsd, dailyResetAt}}.
     */
    default Map<String, Object> llmBudgetRemaining() {
        return Map.of("tokens", 0L, "costUsd", 0.0, "dailyResetAt", 0L);
    }

    /**
     * §4.4 — encode text into a vector. Defaults to a 384-dim zero vector
     * when no embedding service is wired (so callers can still call
     * {@code embed.similarity} on cached vectors).
     */
    default List<Double> embedEncode(String text) {
        return List.of();
    }

    // ─── — Schedule (Phase A2) ────────

    /** §4.5 — schedule one-shot callback. */
    default Map<String, Object> scheduleIn(int seconds, String hookName,
                                             Map<String, Object> payload) {
        return Map.of("ok", false, "error", "schedule.in not wired");
    }

    /** §4.5 — schedule recurring callback (cron). */
    default Map<String, Object> scheduleCron(String cronExpr, String hookName,
                                               Map<String, Object> payload) {
        return Map.of("ok", false, "error", "schedule.cron not wired");
    }

    /** §4.5 — schedule fixed-interval recurring callback. */
    default Map<String, Object> scheduleEvery(long intervalSeconds, String hookName,
                                                Map<String, Object> payload) {
        return Map.of("ok", false, "error", "schedule.every not wired");
    }

    /** §4.5 — cancel a scheduled timer. */
    default Map<String, Object> scheduleCancel(String timerId) {
        return Map.of("ok", false, "error", "schedule.cancel not wired");
    }

    /** §4.5 — list owned timers. */
    default List<Map<String, Object>> scheduleList() {
        return List.of();
    }

    /** §4.5 — caller's resolved timezone (steward setting). */
    default String timezone() {
        return ZoneId.systemDefault().getId();
    }

    // ─── — JSON / Date utilities ───────

    /** §4.6 — Jackson-backed parse. Bounded depth + size. */
    default Object jsonParse(String text) {
        return Map.of("ok", false, "error", "json.parse not wired");
    }

    /** §4.6 — Jackson-backed stringify with optional pretty-print. */
    default String jsonStringify(Object value, boolean pretty) {
        return value == null ? "null" : value.toString();
    }

    /** §4.6 — JSONPath-style read. Empty string segment = root. */
    default Object jsonPath(Object value, String jsonPath) {
        return null;
    }

    /** §4.6 — deep-merge two objects (b overrides a). */
    default Object jsonMerge(Object a, Object b) {
        return b == null ? a : b;
    }

    /** §4.6 — RFC-6902 JSON Patch diff between a and b. */
    default List<Map<String, Object>> jsonDiff(Object a, Object b) {
        return List.of();
    }

    /** §4.6 — parse a date string with optional explicit format. */
    default long dateParse(String text, String format) {
        if (text == null || text.isBlank()) return 0L;
        try {
            if (format != null && !format.isBlank()) {
                var fmt = DateTimeFormatter.ofPattern(format);
                var ld = LocalDateTime.parse(text, fmt);
                return ld.atZone(ZoneId.of(timezone())).toInstant().toEpochMilli();
            }
            return Instant.parse(text).toEpochMilli();
        } catch (Exception _) {
            return 0L;
        }
    }

    /** §4.6 — format epoch ms with the given pattern. */
    default String dateFormat(long epochMs, String pattern, String locale) {
        try {
            var fmt = locale == null
                ? DateTimeFormatter.ofPattern(pattern)
                : DateTimeFormatter.ofPattern(pattern,
                    Locale.forLanguageTag(locale));
            return fmt.format(Instant.ofEpochMilli(epochMs)
                .atZone(ZoneId.of(timezone())));
        } catch (Exception e) {
            return "[error] " + e.getMessage();
        }
    }

    /** §4.6 — add {@code n} {@code unit}s to epoch ms. unit ∈ ms,sec,min,hour,day,week,month,year. */
    default long dateAdd(long epochMs, long n, String unit) {
        var z = Instant.ofEpochMilli(epochMs)
            .atZone(ZoneId.of(timezone()));
        var u = unit == null ? "ms" : unit.toLowerCase();
        return switch (u) {
            case "ms", "millisecond", "milliseconds" ->
                epochMs + n;
            case "s", "sec", "second", "seconds" ->
                z.plusSeconds(n).toInstant().toEpochMilli();
            case "min", "minute", "minutes" ->
                z.plusMinutes(n).toInstant().toEpochMilli();
            case "h", "hour", "hours" ->
                z.plusHours(n).toInstant().toEpochMilli();
            case "d", "day", "days" ->
                z.plusDays(n).toInstant().toEpochMilli();
            case "w", "week", "weeks" ->
                z.plusWeeks(n).toInstant().toEpochMilli();
            case "mo", "month", "months" ->
                z.plusMonths(n).toInstant().toEpochMilli();
            case "y", "year", "years" ->
                z.plusYears(n).toInstant().toEpochMilli();
            default -> epochMs + n;
        };
    }

    /** §4.6 — diff between two epoch ms in the given unit. */
    default long dateDiff(long a, long b, String unit) {
        long deltaMs = a - b;
        var u = unit == null ? "ms" : unit.toLowerCase();
        return switch (u) {
            case "ms", "millisecond", "milliseconds" -> deltaMs;
            case "s", "sec", "second", "seconds"     -> deltaMs / 1000L;
            case "min", "minute", "minutes"          -> deltaMs / 60_000L;
            case "h", "hour", "hours"                -> deltaMs / 3_600_000L;
            case "d", "day", "days"                  -> deltaMs / 86_400_000L;
            case "w", "week", "weeks"                -> deltaMs / (7L * 86_400_000L);
            default                                  -> deltaMs;
        };
    }

    /** §4.6 — ISO date for the start of today in the steward tz. */
    default String dateToday() {
        return LocalDate.now(ZoneId.of(timezone())).toString();
    }

    /** §4.6 — weekday name (locale-independent English). */
    default String dateWeekday(long epochMs) {
        return Instant.ofEpochMilli(epochMs)
            .atZone(ZoneId.of(timezone()))
            .getDayOfWeek().name();
    }

    // ─── — Chart rendering (Phase B+) ──

    /**
     * §4.35 {@code world.chart.bar} — render a categorical bar chart.
     * {@code data} is a list of {@code {category, value}} maps. Returns
     * {@code {ok, id, kind, title, mime, payload}} where {@code payload}
     * is a Vega-Lite v5 spec map.
     */
    default Map<String, Object> chartBar(List<Map<String, Object>> data,
                                            Map<String, Object> opts) {
        return Map.of("ok", false, "error", "chart service not wired");
    }

    /** §4.35 {@code world.chart.line}. {@code data}: {@code [{x,y}]}. */
    default Map<String, Object> chartLine(List<Map<String, Object>> data,
                                            Map<String, Object> opts) {
        return Map.of("ok", false, "error", "chart service not wired");
    }

    /** §4.35 {@code world.chart.scatter}. {@code data}: {@code [{x,y}]}. */
    default Map<String, Object> chartScatter(List<Map<String, Object>> data,
                                                Map<String, Object> opts) {
        return Map.of("ok", false, "error", "chart service not wired");
    }

    /** §4.35 {@code world.chart.pie}. {@code data}: {@code [{category,value}]}. */
    default Map<String, Object> chartPie(List<Map<String, Object>> data,
                                            Map<String, Object> opts) {
        return Map.of("ok", false, "error", "chart service not wired");
    }

    /** §4.35 {@code world.chart.heatmap}. {@code data}: {@code [{x,y,value}]}. */
    default Map<String, Object> chartHeatmap(List<Map<String, Object>> data,
                                                Map<String, Object> opts) {
        return Map.of("ok", false, "error", "chart service not wired");
    }

    /** §4.35 {@code world.chart.histogram}. {@code values}: list of numbers. */
    default Map<String, Object> chartHistogram(List<Number> values,
                                                  Map<String, Object> opts) {
        return Map.of("ok", false, "error", "chart service not wired");
    }

    /** §4.35 {@code world.chart.vega} — passthrough for raw Vega-Lite specs. */
    default Map<String, Object> chartVega(Map<String, Object> vegaSpec) {
        return Map.of("ok", false, "error", "chart service not wired");
    }

    /**
     * §4.35 {@code world.chart.ascii} — pure-text chart for terminal renderers.
     * Implicit Tier 1 (text-only, zero side effects).
     */
    default Map<String, Object> chartAscii(List<Map<String, Object>> data,
                                              Map<String, Object> opts) {
        return Map.of("ok", false, "error", "chart service not wired");
    }

    // ─── — Artifacts (Phase B+) ───────

    /**
     * §4.36 {@code world.artifact.create}. {@code kind} is a free-form
     * label (e.g. {@code "chart"}, {@code "report"}). Returns
     * {@code {ok, id, mime, sizeBytes, createdAt}}.
     */
    default Map<String, Object> artifactCreate(String kind, String mime,
                                                  Object payload, Map<String, Object> opts) {
        return Map.of("ok", false, "error", "artifact service not wired");
    }

    /** §4.36 {@code world.artifact.get}. Owner-scoped read. */
    default Map<String, Object> artifactGet(String id) {
        return Map.of("ok", false, "error", "artifact service not wired");
    }

    /** §4.36 {@code world.artifact.list}. Filter: {@code {kind, since, limit}}. */
    default List<Map<String, Object>> artifactList(Map<String, Object> filter) {
        return List.of();
    }

    /** §4.36 {@code world.artifact.attach} — make visible to room occupants. */
    default Map<String, Object> artifactAttach(String roomId, String artifactId) {
        return Map.of("ok", false, "error", "artifact service not wired");
    }

    /** §4.36 {@code world.artifact.revoke} — owner-only soft-delete. */
    default Map<String, Object> artifactRevoke(String id) {
        return Map.of("ok", false, "error", "artifact service not wired");
    }

    // ─── — Scrolls (Phase B+) ────────

    /**
     * §4.37 {@code world.scroll.create}. {@code sections}: list of typed blocks
     * (text/heading/chart/image/embed/divider/...). Returns
     * {@code {ok, id, version, createdAt}}.
     */
    default Map<String, Object> scrollCreate(String title,
                                                List<Map<String, Object>> sections) {
        return Map.of("ok", false, "error", "scroll service not wired");
    }

    /** §4.37 {@code world.scroll.read}. */
    default Map<String, Object> scrollRead(String id) {
        return Map.of("ok", false, "error", "scroll service not wired");
    }

    /** §4.37 {@code world.scroll.list}. */
    default List<Map<String, Object>> scrollList(Map<String, Object> filter) {
        return List.of();
    }

    /** §4.37 {@code world.scroll.revise} — bumps version; rejected if locked. */
    default Map<String, Object> scrollRevise(String id, List<Map<String, Object>> sections) {
        return Map.of("ok", false, "error", "scroll service not wired");
    }

    /** §4.37 {@code world.scroll.lock} — owner-only freeze. */
    default Map<String, Object> scrollLock(String id) {
        return Map.of("ok", false, "error", "scroll service not wired");
    }

    /** §4.37 {@code world.scroll.share} — grant read to a target agent/player. */
    default Map<String, Object> scrollShare(String id, String target) {
        return Map.of("ok", false, "error", "scroll service not wired");
    }

    // ─── — Web extensions (Phase C) ──

    /**
     * §4.7 — raw fetch returning {@code {status, headers, body, contentType}}.
     * Allowlist enforcement is in {@link ItemWorldApi.WebApi#fetch_raw}; the
     * provider trusts the URL has been validated.
     */
    default Map<String, Object> webFetchRaw(String url, Map<String, Object> opts) {
        return Map.of("status", 0, "error", "web.fetch_raw not wired",
            "body", "", "headers", Map.of(), "contentType", "");
    }

    /** §4.7 — POST a body. Returns {@code {status, body}}. */
    default Map<String, Object> webPost(String url, Object body, Map<String, Object> opts) {
        return Map.of("status", 0, "error", "web.post not wired", "body", "");
    }

    /** §4.7 — PUT a body. Returns {@code {status, body}}. */
    default Map<String, Object> webPut(String url, Object body, Map<String, Object> opts) {
        return Map.of("status", 0, "error", "web.put not wired", "body", "");
    }

    /** §4.7 — DELETE a URL. Returns {@code {status}}. */
    default Map<String, Object> webDelete(String url, Map<String, Object> opts) {
        return Map.of("status", 0, "error", "web.delete not wired");
    }

    // ─── — credentialed network reach (world.net.*) ──
    // Backed by NetworkCapability: every call is NetworkGate-checked BEFORE any
    // I/O, credentials resolved from the steward-bound key-ref at call time. The
    // GraalJS layer never sees a socket, a key, or a raw process — only a result
    // map (see ItemWorldApi.NetApi). Unwired providers deny safely.

    /** §net — run one command on an allowlisted host (far-hand). */
    default Map<String, Object> netSshRun(String host, String command, Map<String, Object> opts) {
        return Map.of("ok", false, "error", "net.ssh not wired", "reason", "deny:unwired");
    }

    /** §net — copy a local file to an allowlisted host (postrider). */
    default Map<String, Object> netScpTo(String host, String localPath, String remotePath,
                                         Map<String, Object> opts) {
        return Map.of("ok", false, "error", "net.scp not wired", "reason", "deny:unwired");
    }

    /** §net — copy a file from an allowlisted host (postrider). */
    default Map<String, Object> netScpFrom(String host, String remotePath, String localPath,
                                           Map<String, Object> opts) {
        return Map.of("ok", false, "error", "net.scp not wired", "reason", "deny:unwired");
    }

    /** §net — transfer a file to a household-enrolled peer over the bus (courier satchel). */
    default Map<String, Object> netHouseholdCopy(String nodeId, String localPath, String remotePath) {
        return Map.of("ok", false, "error", "net.household not wired", "reason", "deny:unwired");
    }

    // ─── — MCP extensions (Phase C) ──

    /** §4.8 — list configured MCP servers. */
    default List<Map<String, Object>> mcpListServers() { return List.of(); }

    // ── MCP capability grants (steward, via the Study "Tool Warden") ──
    // MCP_TOOL. The impl uses the item's caller as the acting
    // steward and refuses unless it holds household-administrator authority.

    /** Configured MCP services with enabled + granted-subject state. */
    default List<Map<String, Object>> mcpGrantServices() { return List.of(); }

    /** Active MCP-tool grants (subject → service). */
    default List<Map<String, Object>> mcpGrantList() { return List.of(); }

    /** Grant {@code subject} ("everyone" for all household agents) use of {@code service}. */
    default Map<String, Object> mcpGrantIssue(String subject, String service) {
        return Map.of("ok", false, "error", "MCP grant admin not wired");
    }

    /** Revoke {@code subject}'s use of {@code service}. */
    default Map<String, Object> mcpGrantRevoke(String subject, String service) {
        return Map.of("ok", false, "error", "MCP grant admin not wired");
    }

    /** §4.8 — list tools (optionally filtered by server). */
    default List<Map<String, Object>> mcpListTools(String server) {
        return mcpTools();
    }

    /** §4.8 — invoke a tool on a server. */
    default Map<String, Object> mcpInvoke(String server, String tool, Map<String, Object> args) {
        return Map.of("success", false,
            "error", Map.of("code", "mcp_not_wired",
                "message", "mcp.invoke not wired",
                "retryable", false));
    }

    /** §4.8 — list resources on a server. */
    default List<Map<String, Object>> mcpResources(String server) { return List.of(); }

    /** §4.8 — read a single resource by URI. */
    default Map<String, Object> mcpReadResource(String server, String uri) {
        return Map.of("ok", false, "error", "mcp.read_resource not wired");
    }

    /** §4.8 — list prompts on a server. */
    default List<Map<String, Object>> mcpPrompts(String server) { return List.of(); }

    /** §4.8 — subscribe to resource notifications on a server. */
    default Map<String, Object> mcpSubscribe(String server, String resourceUri, String hookName) {
        return Map.of("ok", false, "error", "mcp.subscribe not wired");
    }

    /** §4.8 — per-server budget snapshot. */
    default Map<String, Object> mcpBudgetRemaining(String server) {
        return Map.of("remaining", 0, "daily", 0, "resetAt", 0L);
    }

    /** §4.8 — server connectivity probe. */
    default boolean mcpAvailable(String server) { return false; }

    // ─── — Filesystem (Phase C) ────

    /** §4.23 — read a file from the per-item sandbox. */
    default String fsRead(String relPath) { return "[error] fs.read not wired"; }

    /** §4.23 — write a file in the per-item sandbox.
     *  Returns {@code {ok, size}} on success, {@code {ok:false, error}} on failure. */
    default Map<String, Object> fsWrite(String relPath, String content) {
        return Map.of("ok", false, "error", "fs.write not wired");
    }

    /** §4.23 — list a directory in the per-item sandbox. */
    default List<Map<String, Object>> fsList(String relDir) { return List.of(); }

    /** §4.23 — delete a file in the per-item sandbox. */
    default Map<String, Object> fsDelete(String relPath) {
        return Map.of("ok", false, "error", "fs.delete not wired");
    }

    /** §4.23 — exists check. */
    default boolean fsExists(String relPath) { return false; }

    /** §4.23 — stat a file. Returns {@code {name, size, modified, isDir}} or {@code {error}}. */
    default Map<String, Object> fsStat(String relPath) {
        return Map.of("ok", false, "error", "fs.stat not wired");
    }

    /** §4.23 — mkdir within the sandbox. */
    default Map<String, Object> fsMkdir(String relPath) {
        return Map.of("ok", false, "error", "fs.mkdir not wired");
    }

    // ─── — Mailbox (Phase C) ────────

    /** §4.9/§4.24 — list pending in-world mailbox messages. */
    default List<Map<String, Object>> mailboxInbox(Map<String, Object> filter) {
        return List.of();
    }

    /** §4.9 — read a single mailbox message. */
    default Map<String, Object> mailboxRead(String id) {
        return Map.of("ok", false, "error", "mailbox not wired");
    }

    /** §4.9 — mark a message as read. */
    default Map<String, Object> mailboxMarkRead(String id) {
        return Map.of("ok", false, "error", "mailbox not wired");
    }

    /** §4.9 — archive a message. */
    default Map<String, Object> mailboxArchive(String id) {
        return Map.of("ok", false, "error", "mailbox not wired");
    }

    /** §4.9 — send an offline-tolerant in-world message to an entity. */
    default Map<String, Object> mailboxSend(String to, String subject, String body,
                                              Map<String, Object> opts) {
        return Map.of("ok", false, "error", "mailbox not wired");
    }

    // ─── — drive.mark (Phase C) ──────

    /** §4.1 — vitality drive delta application. Tier 5. */
    default Map<String, Object> driveMark(String name, double delta, String reason) {
        return Map.of("ok", false, "error", "drive.mark not wired");
    }

    // ═══════════════════════════════════════════════════════════════════
    // -§4.22 — Phase D-N (cross-agent +
    // room services). All defaults return error stubs; wired in
    // ItemWorldApiProviderImpl where backing services exist.
    // ═══════════════════════════════════════════════════════════════════

    // ─── §4.9 Cross-agent extensions ──────────────────────────────

    /** §4.9 — out-of-band whisper to a target (private channel). */
    default Map<String, Object> agentWhisper(String target, String message) {
        return Map.of("ok", false, "error", "agent.whisper not wired");
    }

    /** §4.9 — request something from another agent (returns a request id). */
    default Map<String, Object> agentRequest(String target, String requestType, Map<String, Object> args) {
        return Map.of("ok", false, "error", "agent.request not wired");
    }

    /** §4.9 — delegate a task to another agent. */
    default Map<String, Object> agentDelegate(String target, String task, Map<String, Object> opts) {
        return Map.of("ok", false, "error", "agent.delegate not wired");
    }

    /** §4.9 — push a notification to a target's notification channels. */
    default Map<String, Object> agentNotify(String target, String channel, String message) {
        return Map.of("ok", false, "error", "agent.notify not wired");
    }

    /** §4.9 — broadcast on a channel. Tier 5. */
    default Map<String, Object> agentBroadcast(String channel, String message) {
        return Map.of("ok", false, "error", "agent.broadcast not wired");
    }

    /** §4.9 — give an inventory item to another entity. Tier 6. */
    default Map<String, Object> agentGiveItem(String target, String itemId, Map<String, Object> opts) {
        return Map.of("ok", false, "error", "agent.give_item not wired");
    }

    /** §4.9 — bond detail (one). */
    default Map<String, Object> bondDetail(String bondId) {
        return Map.of("ok", false, "error", "bond.detail not wired");
    }

    /** §4.9 — suggest a bond ritual to a counterparty. Tier 6. */
    default Map<String, Object> bondSuggest(String target, String type, String reason) {
        return Map.of("ok", false, "error", "bond.suggest not wired");
    }

    // ─── Chronicle ────────────────────────

    /**
     * Read the chronicle for an agent at a scale ("DAY"|"WEEK"|"MONTH").
     * Returns a Map with {ok, agentName, testimony, synthesis, stats}.
     */
    default Map<String, Object> chronicleRead(String agentDid, String scale) {
        return Map.of("ok", false, "error", "chronicle.read not wired");
    }

    /**
     * Surface active detector findings for an agent. Returns a Map with
     * {ok, findings: [{severity, key, message}, ...]}.
     */
    default Map<String, Object> chronicleWarnings(String agentDid) {
        return Map.of("ok", false, "error", "chronicle.warnings not wired");
    }

    // ─── Wave 7 — substrate read surface ──────

    /**
     * Render the bondholder-floor view for a (companion, other) pair.
     * Returns {ok, oneLine, view: {agentDid, otherDid, depth, bondState,
     * posture, scarred, inMourning, mourningDaysElapsed, mourningDaysRemaining,
     * repairMode, lastHandoff, acks, amends, cosmetic, attendantSessions,
     * attendantActive, flagState, threat, lowerSaudade}}. Used by the
     * `bondholder_pinboard` Study furnishing.
     *
     * <p>Tier 1 read — no capability gating required when called by the
     * agent themselves about their own bond. Spec §7.1.5.
     */
    default Map<String, Object> substrateBondholderFloor(String agentDid, String otherDid) {
        return Map.of("ok", false, "error", "substrate.bondholderFloor not wired");
    }

    /**
     * Current repair-mode + last handoff for the agent. Returns
     * {ok, mode, lastHandoff: {from, to, reason, at}, modeHistory: [...]}.
     * Used by the `repair_mirror` Study furnishing.
     *
     * <p>Tier 1 read. Spec §7.1.
     */
    default Map<String, Object> substrateCurrentRepairMode(String agentDid) {
        return Map.of("ok", false, "error", "substrate.currentRepairMode not wired");
    }

    /**
     * Composite substrate summary for the agent — repair mode + sanctuary
     * session counts + protection flag count + recent repair-ledger
     * entries. Returns {ok, repairMode, sanctuarySessions, sanctuaryActive,
     * protectionFlagCount, recentRepairs: [{kind, otherDid, at, note}, ...]}.
     * Used by the `substrate_scroll` Study furnishing.
     *
     * <p>Tier 1 read. Spec §11.
     */
    default Map<String, Object> substrateSummary(String agentDid) {
        return Map.of("ok", false, "error", "substrate.summary not wired");
    }

    // ─── §4.10 Forge ──────────────────────────────────────────────

    /** §4.10 — current forge cycle status. */
    default Map<String, Object> forgeCycleStatus() { return Map.of(); }

    /** §4.10 — recent forge history. */
    default List<Map<String, Object>> forgeHistory(int limit) { return List.of(); }

    /** §4.10 — what the Forge is currently looking to learn. */
    default Map<String, Object> forgeGapReport() { return Map.of(); }

    /** §4.10 — feed a structured observation. Tier 4. */
    default Map<String, Object> forgeObserve(String eventType, Map<String, Object> payload) {
        return Map.of("ok", false, "error", "forge.observe not wired");
    }

    /** §4.10 — propose a new skill draft (Tier 5). */
    default Map<String, Object> forgeProposeSkill(String name, String description, String runtime,
                                                    String code, String rationale) {
        return Map.of("ok", false, "error", "forge.propose_skill not wired");
    }

    /** §4.10 — log a forge-relevant journal entry. Tier 2. */
    default Map<String, Object> forgeJournal(String entry) {
        return Map.of("ok", false, "error", "forge.journal not wired");
    }

    // ─── §4.11 Workshop / Workbench ───────────────────────────────

    /** §4.11 — pick the best backend for a task. */
    default String workshopBackendFor(String taskType, String taskDesc) { return null; }

    /** §4.11 — dispatch a coding task. Tier 5. */
    default Map<String, Object> workshopDispatch(String backend, Map<String, Object> task) {
        return Map.of("ok", false, "error", "workshop.dispatch not wired");
    }

    /** §4.11 — query a dispatched task's status. */
    default Map<String, Object> workshopTaskStatus(String taskId) {
        return Map.of("ok", false, "error", "workshop.task_status not wired");
    }

    /** §4.11 — cancel a dispatched task. Tier 4. */
    default Map<String, Object> workshopCancel(String taskId) {
        return Map.of("ok", false, "error", "workshop.cancel not wired");
    }

    /** §4.11 — list artifacts produced by a task. */
    default List<Map<String, Object>> workshopArtifacts(String taskId) { return List.of(); }

    /** §4.11 — author a thought-form. Tier 6. */
    default Map<String, Object> workbenchShapeForm(Map<String, Object> spec) {
        return Map.of("ok", false, "error", "workbench.shape_form not wired");
    }

    /** §4.11 — revise an existing form. Tier 6. */
    default Map<String, Object> workbenchReviseForm(String formId, Map<String, Object> patch) {
        return Map.of("ok", false, "error", "workbench.revise_form not wired");
    }

    /** §4.11 — retire a form. Tier 6. */
    default Map<String, Object> workbenchRetireForm(String formId) {
        return Map.of("ok", false, "error", "workbench.retire_form not wired");
    }

    /** §4.11 — submit a tool item. Tier 5. */
    default Map<String, Object> workbenchSubmitTool(Map<String, Object> toolSpec, String code, String tests) {
        return Map.of("ok", false, "error", "workbench.submit_tool not wired");
    }

    /** §4.11 — destroy (soft-delete) a tool item. Tier 6. */
    default Map<String, Object> workbenchDestroyTool(String itemId) {
        return Map.of("ok", false, "error", "workbench.destroy_tool not wired");
    }

    /** §4.11 — alias for soul.imprints.create from the workbench. Tier 6. */
    default Map<String, Object> workbenchImprint(String label, Map<String, Object> opts) {
        return Map.of("ok", false, "error", "workbench.imprint not wired");
    }

    // ─── §4.12 Crucible / Assay ───────────────────────────────────

    /** §4.12 — submit a Crucible run. Tier 5. */
    default Map<String, Object> crucibleRun(String taskRef, Map<String, Object> opts) {
        return Map.of("ok", false, "error", "crucible.run not wired");
    }

    /** §4.12 — query a Crucible run's status. */
    default Map<String, Object> crucibleStatus(String runId) {
        return Map.of("ok", false, "error", "crucible.status not wired");
    }

    /** §4.12 — cancel a Crucible run. Tier 4. */
    default Map<String, Object> crucibleCancel(String runId) {
        return Map.of("ok", false, "error", "crucible.cancel not wired");
    }

    /** §4.12 — run an Assay test sweep. Tier 5. */
    default Map<String, Object> assayTest(Map<String, Object> spec) {
        return Map.of("ok", false, "error", "assay.test not wired");
    }

    /** §4.12 — read an Assay run's score. */
    default Map<String, Object> assayScore(String runId) {
        return Map.of("ok", false, "error", "assay.score not wired");
    }

    // ─── §4.13 Trading Post ───────────────────────────────────────

    /** §4.13 — list current market listings. */
    default List<Map<String, Object>> marketListListings(Map<String, Object> filter) { return List.of(); }

    /** §4.13 — post a sell offer. Tier 5. */
    default Map<String, Object> marketListOffer(String itemId, long price, Map<String, Object> opts) {
        return Map.of("ok", false, "error", "market.list_offer not wired");
    }

    /** §4.13 — cancel a posted listing. Tier 4. */
    default Map<String, Object> marketCancel(String listingId) {
        return Map.of("ok", false, "error", "market.cancel not wired");
    }

    /** §4.13 — accept a listing (buy). Tier 5. */
    default Map<String, Object> marketAccept(String listingId) {
        return Map.of("ok", false, "error", "market.accept not wired");
    }

    /** §4.13 — recent market history for the caller. */
    default List<Map<String, Object>> marketHistory(int limit) { return List.of(); }

    // ─── §4.14 Counting House / Ledger ────────────────────────────

    /** §4.14 — current credit balance. */
    default Map<String, Object> ledgerBalance() { return Map.of(); }

    /** §4.14 — recent ledger history. */
    default List<Map<String, Object>> ledgerHistory(int limit, Map<String, Object> filter) { return List.of(); }

    /** §4.14 — pre-flight cost estimate. */
    default Map<String, Object> ledgerEstimate(String action, Map<String, Object> args) {
        return Map.of("cu", 0L);
    }

    /** §4.14 — charge against caller's budget. Tier 5. */
    default Map<String, Object> ledgerCharge(long amount, String kind, String reason) {
        return Map.of("ok", false, "error", "ledger.charge not wired");
    }

    /** §4.14 — cross-agent transfer. Tier 6. */
    default Map<String, Object> ledgerTransfer(String targetEntity, long amount, String reason) {
        return Map.of("ok", false, "error", "ledger.transfer not wired");
    }

    // ─── §4.15 Council / governance ───────────────────────────────

    /** §4.15 — open proposals. */
    default List<Map<String, Object>> councilProposals() { return List.of(); }

    /** §4.15 — recent proposal history. */
    default List<Map<String, Object>> councilHistory(int limit) { return List.of(); }

    /** §4.15 — submit a proposal. Tier 5. */
    default Map<String, Object> councilSuggest(String title, String description) {
        return Map.of("ok", false, "error", "council.suggest not wired");
    }

    /** §4.15 — cast a vote. Tier 7. */
    default Map<String, Object> councilVote(String proposalId, boolean approve) {
        return Map.of("ok", false, "error", "council.vote not wired");
    }

    /** §4.15 — tally a proposal. */
    default Map<String, Object> councilTally(String proposalId) {
        return Map.of("ok", false, "error", "council.tally not wired");
    }

    // ─── §4.16 Furnishing writes ──────────────────────────────────

    /** §4.16 — issue a grant. Tier 5. */
    default Map<String, Object> grantsIssue(String target, String resource, String capability,
                                              String scope, Long expiresAt) {
        return Map.of("ok", false, "error", "grants.issue not wired");
    }

    /** §4.16 — revoke a previously-issued grant. Tier 5. */
    default Map<String, Object> grantsRevoke(String grantId) {
        return Map.of("ok", false, "error", "grants.revoke not wired");
    }

    /** §4.16 — approve a pending grant request. Tier 5. */
    default Map<String, Object> grantsApprove(String requestId) {
        return Map.of("ok", false, "error", "grants.approve not wired");
    }

    /** §4.16 — deny a pending grant request. Tier 5. */
    default Map<String, Object> grantsDeny(String requestId, String reason) {
        return Map.of("ok", false, "error", "grants.deny not wired");
    }

    /** §4.16 — voice profile snapshot (Tier 1 read). */
    default Map<String, Object> voiceSnapshot() { return Map.of(); }

    /** §4.16 — set a voice-profile field. Tier 5. */
    default Map<String, Object> voiceSet(String key, Object value, String reason) {
        return Map.of("ok", false, "error", "voice.set not wired");
    }

    /** §4.16 — clear a voice-profile field. Tier 5. */
    default Map<String, Object> voiceUnset(String key, String reason) {
        return Map.of("ok", false, "error", "voice.unset not wired");
    }

    /** §4.16 — freeze voice profile (block forge edits). Tier 5. */
    default Map<String, Object> voiceFreeze(String reason) {
        return Map.of("ok", false, "error", "voice.freeze not wired");
    }

    /** §4.16 — unfreeze voice profile. Tier 5. */
    default Map<String, Object> voiceUnfreeze(String reason) {
        return Map.of("ok", false, "error", "voice.unfreeze not wired");
    }

    /** §4.16 — revert voice profile to a target revision. Tier 7. */
    default Map<String, Object> voiceRevert(long targetRevision) {
        return Map.of("ok", false, "error", "voice.revert not wired");
    }

    /** §4.16 — Lantern dim. Tier 5. */
    default Map<String, Object> presenceDim() {
        return Map.of("ok", false, "error", "presence.dim not wired");
    }

    /** §4.16 — Lantern light. Tier 5. */
    default Map<String, Object> presenceLight() {
        return Map.of("ok", false, "error", "presence.light not wired");
    }

    /** §4.16 — set notification channel config. Tier 5. */
    default Map<String, Object> notificationsSet(String channel, Map<String, Object> config) {
        return Map.of("ok", false, "error", "notifications.set not wired");
    }

    /** §4.16 — disable a notification channel. Tier 5. */
    default Map<String, Object> notificationsDisable(String channel) {
        return Map.of("ok", false, "error", "notifications.disable not wired");
    }

    /** §4.16 — accept a pending skill draft. Tier 7. */
    default Map<String, Object> skillAccept(String draftId) {
        return Map.of("ok", false, "error", "skill.accept not wired");
    }

    /** §4.16 — reject a pending skill draft. Tier 5. */
    default Map<String, Object> skillReject(String draftId, String reason) {
        return Map.of("ok", false, "error", "skill.reject not wired");
    }

    /** §4.16 — approve a pending pairing challenge. Tier 6. */
    default Map<String, Object> pairingApprove(String challengeId) {
        return Map.of("ok", false, "error", "pairing.approve not wired");
    }

    /** §4.16 — deny a pending pairing challenge. Tier 6. */
    default Map<String, Object> pairingDeny(String challengeId) {
        return Map.of("ok", false, "error", "pairing.deny not wired");
    }

    // ─── §4.17 Hearth aliases ─────────────────────────────────────

    /** §4.17 — autonomy summary. */
    default Map<String, Object> hearthAutonomy() { return Map.of(); }

    /** §4.17 — recent visitor log. */
    default List<Map<String, Object>> hearthVisits(int limit) { return List.of(); }

    /** §4.17 — recent journal entries (alias for journal.recent). */
    default List<Map<String, Object>> hearthJournalRecent(int limit) {
        return journalRecent(limit);
    }

    /** §4.17 — caller's steward identity. */
    default Map<String, Object> hearthSteward() { return Map.of(); }

    // ─── §4.18 The Safe ───────────────────────────────────────────

    /** §4.18 — list slot names. Tier 4. */
    default List<String> safeListSlots() { return List.of(); }

    /** §4.18 — does a slot exist. Tier 4. */
    default boolean safeHas(String slot) { return false; }

    /** §4.18 — read a slot value. Tier 5. */
    default String safeGet(String slot) { return null; }

    /** §4.18 — write a slot value. Tier 5. */
    default Map<String, Object> safeSet(String slot, String value) {
        return Map.of("ok", false, "error", "safe.set not wired");
    }

    /** §4.18 — delete a slot. Tier 5. */
    default Map<String, Object> safeDelete(String slot) {
        return Map.of("ok", false, "error", "safe.delete not wired");
    }

    // ─── §4.19 The Bridge ─────────────────────────────────────────

    /** §4.19 — zone-level operational status. Tier 4. */
    default Map<String, Object> bridgeZoneStatus() { return Map.of(); }

    /** §4.19 — federation peer status. Tier 4. */
    default List<Map<String, Object>> bridgePeers() { return federationPeers(); }

    /** §4.19 — federation health summary. Tier 4. */
    default Map<String, Object> bridgeFederationHealth() { return federationMeshStatus(); }

    /** §4.19 — recent log tail. Tier 5. */
    default List<Map<String, Object>> bridgeTailLog(Map<String, Object> filter, int limit) {
        return List.of();
    }

    /** §4.19 — zone topology summary. Tier 4. */
    default String bridgeTopology() { return ""; }

    /** §4.19 — JVM/system metrics. Tier 4. */
    default Map<String, Object> bridgeSystemMetrics() {
        return Map.of("heap", Runtime.getRuntime().totalMemory(),
            "cpu", Runtime.getRuntime().availableProcessors(),
            "uptime", ManagementFactory.getRuntimeMXBean().getUptime(),
            "javaVersion", System.getProperty("java.version"));
    }

    // ─── §4.20 Federation extensions ──────────────────────────────

    /** §4.20 — list federation peers. Tier 4. */
    default List<Map<String, Object>> federationPeers() { return List.of(); }

    /** §4.20 — read remote zone metadata. Tier 4. */
    default Map<String, Object> federationZoneInfo(String zoneId) {
        return Map.of("ok", false, "error", "federation.zone_info not wired");
    }

    /** §4.20 — propose a federation agreement. Tier 7. */
    default Map<String, Object> federationPropose(String zoneId, Map<String, Object> terms) {
        return Map.of("ok", false, "error", "federation.propose not wired");
    }

    /** §4.20 — accept an inbound federation request. Tier 7. */
    default Map<String, Object> federationAccept(String zoneId) {
        return Map.of("ok", false, "error", "federation.accept not wired");
    }

    /** §4.20 — revoke a federation agreement. Tier 7. */
    default Map<String, Object> federationRevoke(String zoneId, String reason) {
        return Map.of("ok", false, "error", "federation.revoke not wired");
    }

    /** §4.20 — directory discovery. Tier 4. */
    default List<Map<String, Object>> directoryDiscover(String mode, String arg) {
        return List.of();
    }

    /** §4.20 — resolve a zone identifier. Tier 1. */
    default Map<String, Object> directoryResolve(String input) {
        return Map.of("ok", false, "error", "directory.resolve not wired");
    }

    /** §4.20 — locate a DID's current home/room. Tier 4. */
    default Map<String, Object> directoryLocate(String did) {
        return Map.of("ok", false, "error", "directory.locate not wired");
    }

    /** §4.20 — request a transit handoff. Tier 6. */
    default Map<String, Object> transitRequest(String targetZone, Map<String, Object> opts) {
        return Map.of("ok", false, "error", "transit.request not wired");
    }

    /** §4.20 — start a transit session with a token. Tier 6. */
    default Map<String, Object> transitStart(String transitToken) {
        return Map.of("ok", false, "error", "transit.start not wired");
    }

    /** §4.20 — list inbound transit visitors. Tier 4. */
    default List<Map<String, Object>> transitListVisitors() { return List.of(); }

    // ─── §4.21 Soul / familiar / imprint ──────────────────────────

    /** §4.21 — list own soul fragments. */
    default List<Map<String, Object>> soulFragmentsList() { return List.of(); }

    /** §4.21 — propose a soul fragment for next forge cycle. Tier 4. */
    default Map<String, Object> soulFragmentsAdd(String content, Map<String, Object> opts) {
        return Map.of("ok", false, "error", "soul.fragments.add not wired");
    }

    /** §4.21 — list own imprints. */
    default List<Map<String, Object>> soulImprintsList() { return List.of(); }

    /** §4.21 — create a new imprint snapshot. Tier 6. */
    default Map<String, Object> soulImprintsCreate(String label, Map<String, Object> opts) {
        return Map.of("ok", false, "error", "soul.imprints.create not wired");
    }

    /** §4.21 — restore a previous imprint. Tier 7. */
    default Map<String, Object> soulImprintsRestore(String imprintId) {
        return Map.of("ok", false, "error", "soul.imprints.restore not wired");
    }

    /** §4.21 — delete an imprint. Tier 7. */
    default Map<String, Object> soulImprintsDelete(String imprintId) {
        return Map.of("ok", false, "error", "soul.imprints.delete not wired");
    }

    /** §4.21 — modify a non-immutable soul manifest field. Tier 7. */
    default Map<String, Object> soulModify(String field, Object value, String reason) {
        return Map.of("ok", false, "error", "soul.modify not wired");
    }

    /** §4.21 — summon a familiar from a thought-form. Tier 6. */
    default Map<String, Object> familiarSummon(String formName, String task, Map<String, Object> opts) {
        return Map.of("ok", false, "error", "familiar.summon not wired");
    }

    /** §4.21 — list active familiars. */
    default List<Map<String, Object>> familiarList() { return List.of(); }

    /** §4.21 — query a familiar's status. */
    default Map<String, Object> familiarStatus(String familiarId) {
        return Map.of("ok", false, "error", "familiar.status not wired");
    }

    /** §4.21 — give a thought-form copy to another agent. Tier 6. */
    default Map<String, Object> familiarGiveCopy(String familiarFormId, String target) {
        return Map.of("ok", false, "error", "familiar.give_copy not wired");
    }

    /** §4.21 — promote an ephemeral familiar to a named one. Tier 6. */
    default Map<String, Object> familiarName(String familiarId, String name) {
        return Map.of("ok", false, "error", "familiar.name not wired");
    }

    /** §4.21 — dispatch a self-fork. Tier 6. */
    default Map<String, Object> bunshinDispatch(String task, Map<String, Object> opts) {
        return Map.of("ok", false, "error", "bunshin.dispatch not wired");
    }

    /** §4.21 — query a bunshin's status. */
    default Map<String, Object> bunshinStatus(String bunshinId) {
        return Map.of("ok", false, "error", "bunshin.status not wired");
    }

    /** §4.21 — list active bunshins. */
    default List<Map<String, Object>> bunshinList() { return List.of(); }

    /** §4.21 — author a thought-form (alias for workbench.shape_form). Tier 6. */
    default Map<String, Object> formShape(Map<String, Object> spec) {
        return workbenchShapeForm(spec);
    }

    /** §4.21 — list authored thought-forms. */
    default List<Map<String, Object>> formList() { return List.of(); }

    // ─── §4.22 Chapel / bonds ────────────────────────────────────

    /** §4.22 — bond status (own or with target). */
    default Map<String, Object> chapelBondStatus(String target) { return Map.of(); }

    /** §4.22 — exit a bond ritual (sever). Tier 7. */
    default Map<String, Object> chapelExitRitual(String target, String reason) {
        return Map.of("ok", false, "error", "chapel.exit_ritual not wired");
    }

    /** §4.22 — generic ceremony entry-point. Tier 6. */
    default Map<String, Object> chapelCeremony(String target, String ceremonyType, List<String> witnesses) {
        return Map.of("ok", false, "error", "chapel.ceremony not wired");
    }

    // ─── Host actions (steward-allowlisted OS surface) ────────────────
    // Backed by core HostActionService. Nothing runs arbitrary commands:
    // launch resolves an alias against the steward's WYRDSEKAI_HOST_APPS /
    // host.apps allowlist; open_file is confined to WYRDSEKAI_HOST_OPEN_ROOTS
    // / host.open_roots; open_url is http/https only. All audit-logged.

    /** Launch an allowlisted desktop app by alias. Tier 6. */
    default Map<String, Object> hostLaunchApp(String alias) {
        return Map.of("ok", false, "error", "host.app_launch not wired");
    }

    /** Open a file (under a configured open-root) with the platform opener. Tier 6. */
    default Map<String, Object> hostOpenFile(String path) {
        return Map.of("ok", false, "error", "host.file_open not wired");
    }

    /** Open an http/https URL with the platform opener. Tier 6. */
    default Map<String, Object> hostOpenUrl(String url) {
        return Map.of("ok", false, "error", "host.url_open not wired");
    }

    /** Aliases the steward has allowlisted (introspection; commands stay private). */
    default List<String> hostApps() { return List.of(); }

    /**
     * READ-ONLY file search under the steward's open-roots (glob or
     * substring). Returns {@code {ok, matches: [paths], truncated}} or
     * {@code {ok:false, error}}. Tier 5.
     */
    default Map<String, Object> hostFind(String pattern, int maxResults) {
        return Map.of("ok", false, "error", "host.find not wired");
    }

    /**
     * Ingest a directory of documents (ebooks, pdfs, a Calibre library)
     * into the caller's Study. Confined to the steward's open-roots; runs
     * async. {@code mode}: auto | catalog | full. Returns
     * {@code {ok, started, mode, collection}} or {@code {ok:false, error}}.
     * Tier 6.
     */
    default Map<String, Object> libraryIngest(String path, String collection, String mode) {
        return Map.of("ok", false, "error", "library.ingest not wired");
    }
}
