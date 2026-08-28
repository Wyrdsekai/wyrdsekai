package org.wyrdsekai.core.item;

import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.javadsl.AskPattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.home.Grant;
import org.wyrdsekai.common.home.RelayAdminOp;
import org.wyrdsekai.core.agent.AgentCostTracker;
import org.wyrdsekai.core.hermod.HermodGrantStore;
import org.wyrdsekai.core.home.RelayGovernor;
import org.wyrdsekai.core.home.RelayGovernance;
import org.wyrdsekai.core.economy.CountingHouseGateway;
import org.wyrdsekai.core.home.HomeClient;
import org.wyrdsekai.core.library.StudyService;
import org.wyrdsekai.core.home.HomeRegistryActor;
import org.wyrdsekai.core.household.MaintenanceService;
import org.wyrdsekai.core.household.ParentalControlService;
import org.wyrdsekai.core.household.StewardAuditLog;
import org.wyrdsekai.core.persistence.AuthService;
import org.wyrdsekai.core.room.ZoneGuardian;
import org.wyrdsekai.core.soul.ForgeRoomBridge;
import org.wyrdsekai.core.persistence.BackupOrchestrator;
import org.wyrdsekai.core.persistence.InviteService;
import org.wyrdsekai.core.persistence.PairingService;
import org.wyrdsekai.core.persistence.WardService;
import org.wyrdsekai.core.skill.SkillDraft;
import org.wyrdsekai.core.skill.SkillDraftStore;
import org.wyrdsekai.scripting.api.ItemWorldApiProvider;

import java.time.Duration;
import java.time.Instant;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * item-script provider for a player invoking a scripted Home
 * furnishing (Embers, Board, ...) from inside their own Study.
 *
 * <p>Extends {@link VisitorItemProvider}'s minimal surface with real wiring to
 * the HomeRegistry so {@code world.home.callerDid()}, {@code world.audit.recent},
 * {@code world.grants.issued/held} return live data. Library / web / LLM stay
 * on their visitor-safe defaults — Home furnishings don't need those.</p>
 */
public final class HomeOwnerItemProvider extends VisitorItemProvider {

    private static final Logger log = LoggerFactory.getLogger(HomeOwnerItemProvider.class);
    private static final Duration ASK_TIMEOUT = Duration.ofSeconds(5);

    private final String ownerDid;
    private final HomeClient homeClient;
    private final ActorSystem<?> system;

    // Optional supplier hooks wired by the server layer. Keep nullable so
    // tests + minimal setups render graceful placeholders.
    // Player-side pinboard backing (StudyService keyed by ownerDid). The
    // interface defaults return {error:"not wired"} — which pinboard.js once
    // rendered as SUCCESS (fake pin, bare cork on re-read; found in the
    // 2026-07-04 live .deb audit). Nullable: unwired surfaces stay honest.
    private StudyService study;

    private Supplier<List<Map<String, Object>>> agreementsSupplier;
    private Supplier<Map<String, Object>> meshStatusSupplier;
    private Supplier<List<Map<String, Object>>> inventorySupplier;
    private Supplier<List<Map<String, Object>>> bondsSupplier;
    private Supplier<List<Map<String, Object>>> companionsSupplier;
    private Supplier<List<Map<String, Object>>> presenceSupplier;
    private Supplier<List<Map<String, Object>>> notificationsSupplier;
    private Supplier<List<Map<String, Object>>> mcpToolsSupplier;
    private Supplier<List<Map<String, Object>>> pendingPairingsSupplier;
    private Supplier<String> activePairCodeSupplier;
    private Supplier<String> activeHouseholdKeySupplier;
    private Supplier<String> generateHouseholdKeySupplier;
    // Study control-panel services — wired by the server layer so household
    // reads/writes reach the real stores. All nullable; unwired surfaces
    // degrade to the interface's safe defaults.
    private AuthService authService;
    private InviteService inviteService;
    private WardService wardService;
    private HermodGrantStore hermodGrantStore;
    private ParentalControlService parentalService;
    private MaintenanceService maintenanceService;
    private StewardAuditLog securityAuditLog;
    private BackupOrchestrator backupOrchestrator;
    private Supplier<List<Map<String, Object>>> nodesSupplier;

    /** Roles a Study control-panel write may assign / mint invites for. */
    private static final Set<String> KNOWN_ROLES =
        Set.of("steward", "member", "guest", "child");

    /** Ward capabilities the control panel may grant/revoke. */
    private static final Set<String> WARD_CAPABILITIES =
        Set.of("enter", "speak", "take", "drop", "use", "build", "admin");

    public HomeOwnerItemProvider(String currentZoneId, String homeZoneId,
                                  String ownerDid, HomeClient homeClient,
                                  ActorSystem<?> system) {
        super(currentZoneId, homeZoneId);
        this.ownerDid = ownerDid;
        this.homeClient = homeClient;
        this.system = system;
    }

    /** Wire federation agreement view (Manifest). */
    public HomeOwnerItemProvider withAgreements(Supplier<List<Map<String, Object>>> s) {
        this.agreementsSupplier = s;
        return this;
    }

    /** F12: wire mesh-state matrix view (Manifest mesh extension). */
    public HomeOwnerItemProvider withMeshStatus(Supplier<Map<String, Object>> s) {
        this.meshStatusSupplier = s;
        return this;
    }

    /** Wire owned-inventory view (Trunk). */
    public HomeOwnerItemProvider withInventory(Supplier<List<Map<String, Object>>> s) {
        this.inventorySupplier = s;
        return this;
    }

    /** Wire bonds view (Shelf). */
    public HomeOwnerItemProvider withBonds(Supplier<List<Map<String, Object>>> s) {
        this.bondsSupplier = s;
        return this;
    }

    /** Wire companion-roster view (Companion Codex). Optional — defaults to
     *  {@link CompanionCodexView#list()}, which is self-contained. */
    public HomeOwnerItemProvider withCompanions(Supplier<List<Map<String, Object>>> s) {
        this.companionsSupplier = s;
        return this;
    }

    /** Wire presence-in-Home view (Lantern). */
    public HomeOwnerItemProvider withPresence(Supplier<List<Map<String, Object>>> s) {
        this.presenceSupplier = s;
        return this;
    }

    /** Wire notification-channel view (Compass). */
    public HomeOwnerItemProvider withNotifications(Supplier<List<Map<String, Object>>> s) {
        this.notificationsSupplier = s;
        return this;
    }

    /** Wire MCP-tools view. */
    public HomeOwnerItemProvider withMcpTools(Supplier<List<Map<String, Object>>> s) {
        this.mcpToolsSupplier = s;
        return this;
    }

    /** Wire pending-pairings view (Threshold). */
    public HomeOwnerItemProvider withPendingPairings(Supplier<List<Map<String, Object>>> s) {
        this.pendingPairingsSupplier = s;
        return this;
    }

    /** Wire active pair-code view (Threshold). */
    public HomeOwnerItemProvider withActivePairCode(Supplier<String> s) {
        this.activePairCodeSupplier = s;
        return this;
    }

    /** Wire active household-key view (Threshold). */
    public HomeOwnerItemProvider withActiveHouseholdKey(Supplier<String> s) {
        this.activeHouseholdKeySupplier = s;
        return this;
    }

    /** Wire household-key generator (Threshold key=rotate). */
    /**
     * The acting person. A home-owner provider is built FOR someone — its
     * {@code ownerDid} is that someone — so identity is available even if no
     * one calls {@link #withCaller}. Belt and braces: the surface that needs a
     * caller should never depend on a second wiring step remembering to happen.
     */
    @Override
    protected String actingDid() {
        var explicit = super.actingDid();
        return explicit != null && !explicit.isBlank() ? explicit : ownerDid;
    }

    public HomeOwnerItemProvider withStudy(StudyService studyService) {
        this.study = studyService;
        return this;
    }

    public HomeOwnerItemProvider withGenerateHouseholdKey(Supplier<String> s) {
        this.generateHouseholdKeySupplier = s;
        return this;
    }

    /** Wire the auth service (world.household roster/roles + SSH-key device view). */
    public HomeOwnerItemProvider withAuth(AuthService s) {
        this.authService = s;
        return this;
    }

    /** Wire the invite service (world.invite mint/list/revoke). */
    public HomeOwnerItemProvider withInvites(InviteService s) {
        this.inviteService = s;
        return this;
    }

    /** Wire the ward service (world.ward list/grant/revoke). */
    public HomeOwnerItemProvider withWards(WardService s) {
        this.wardService = s;
        return this;
    }

    /** Wire hermod data-domain grants (world.hermod grants/revoke). */
    public HomeOwnerItemProvider withHermodGrants(HermodGrantStore s) {
        this.hermodGrantStore = s;
        return this;
    }

    /** Wire parental controls (world.parental list/get/set/clear). */
    public HomeOwnerItemProvider withParental(ParentalControlService s) {
        this.parentalService = s;
        return this;
    }

    /** Wire maintenance (world.maintenance status/setMode/backupNow/schedule/restore). */
    public HomeOwnerItemProvider withMaintenance(MaintenanceService s) {
        this.maintenanceService = s;
        return this;
    }

    /** Wire the steward security-audit log (world.audit.security). */
    public HomeOwnerItemProvider withSecurityAudit(StewardAuditLog log) {
        this.securityAuditLog = log;
        return this;
    }

    /** Wire the backup orchestrator (world.safe.snapshots). */
    public HomeOwnerItemProvider withBackups(BackupOrchestrator orchestrator) {
        this.backupOrchestrator = orchestrator;
        return this;
    }

    /** Wire the enrolled-node snapshot view (world.nodes.list). */
    public HomeOwnerItemProvider withNodes(Supplier<List<Map<String, Object>>> s) {
        this.nodesSupplier = s;
        return this;
    }

    @Override public String callerDid() { return ownerDid; }

    @Override
    public List<Map<String, Object>> auditRecent(int limit) {
        if (homeClient == null || ownerDid == null) return List.of();
        try {
            var stage = AskPattern.<HomeRegistryActor.Command, HomeRegistryActor.AuditList>ask(
                homeClient.registry(),
                replyTo -> new HomeRegistryActor.QueryAudit(
                    ownerDid, null, Math.max(1, Math.min(limit, 200)), replyTo),
                ASK_TIMEOUT, system.scheduler());
            var list = stage.toCompletableFuture().get(6, TimeUnit.SECONDS).entries();
            var out = new ArrayList<Map<String, Object>>(list.size());
            for (var e : list) {
                var m = new HashMap<String, Object>();
                m.put("timestamp", e.timestamp().toString());
                m.put("actor", e.actor());
                m.put("verb", e.verb());
                m.put("resource", e.resource());
                m.put("outcome", e.outcome().name());
                m.put("detail", e.detail());
                out.add(m);
            }
            return out;
        } catch (Exception e) {
            log.warn("auditRecent({}): {}", ownerDid, e.getMessage());
            return List.of();
        }
    }

    @Override
    public List<Map<String, Object>> grantsIssued() {
        if (homeClient == null || ownerDid == null) return List.of();
        try {
            return grantsView(homeClient.listIssuedBy(ownerDid));
        } catch (Exception e) {
            log.warn("grantsIssued({}): {}", ownerDid, e.getMessage());
            return List.of();
        }
    }

    @Override
    public List<Map<String, Object>> grantsHeld() {
        if (homeClient == null || ownerDid == null) return List.of();
        try {
            return grantsView(homeClient.listHeldBy(ownerDid));
        } catch (Exception e) {
            log.warn("grantsHeld({}): {}", ownerDid, e.getMessage());
            return List.of();
        }
    }

    @Override
    public List<Map<String, Object>> pendingGrantRequests() {
        if (homeClient == null || ownerDid == null) return List.of();
        try {
            var list = homeClient.pendingForOwner(ownerDid);
            var out = new ArrayList<Map<String, Object>>(list.size());
            for (var r : list) {
                var m = new HashMap<String, Object>();
                m.put("id", r.id());
                m.put("requester", r.requester());
                m.put("resource", r.resource().toString());
                m.put("resourceType", r.resource().type());
                m.put("capability", r.capability().name());
                if (r.reason() != null) m.put("reason", r.reason());
                m.put("createdAt", r.createdAt().toString());
                if (r.scope() != null && !r.scope().isEmpty()) m.put("scope", r.scope());
                out.add(m);
            }
            return out;
        } catch (Exception e) {
            log.warn("pendingGrantRequests({}): {}", ownerDid, e.getMessage());
            return List.of();
        }
    }

    @Override
    public Map<String, Object> budgetSummary() {
        if (ownerDid == null) return Map.of();
        var tracker = AgentCostTracker.get();
        if (tracker == null) return Map.of();
        try {
            var maybe = tracker.summary(ownerDid);
            if (maybe.isEmpty()) return Map.of();
            var s = maybe.get();
            var m = new HashMap<String, Object>();
            m.put("inferences", s.totalInferences());
            m.put("mcpCalls", s.totalMcpCalls());
            m.put("tokens", s.totalTokens());
            m.put("avgLatencyMs", s.avgLatencyMs());
            m.put("monetaryCost", s.totalMonetaryCost());
            if (s.firstActivity() != null) m.put("firstActivity", s.firstActivity().toString());
            if (s.lastActivity() != null) m.put("lastActivity", s.lastActivity().toString());
            // Budget check string (null when within limits or no limit set).
            var budgetNote = tracker.checkBudget(ownerDid);
            if (budgetNote != null) m.put("budgetNote", budgetNote);
            return m;
        } catch (Exception e) {
            log.warn("budgetSummary({}): {}", ownerDid, e.getMessage());
            return Map.of();
        }
    }

    @Override
    public List<Map<String, Object>> federationAgreements() {
        if (agreementsSupplier == null) return List.of();
        try {
            var v = agreementsSupplier.get();
            return v != null ? v : List.of();
        } catch (Exception e) {
            log.warn("federationAgreements({}): {}", ownerDid, e.getMessage());
            return List.of();
        }
    }

    @Override
    public Map<String, Object> federationMeshStatus() {
        if (meshStatusSupplier == null) return Map.of();
        try {
            var v = meshStatusSupplier.get();
            return v != null ? v : Map.of();
        } catch (Exception e) {
            log.warn("federationMeshStatus({}): {}", ownerDid, e.getMessage());
            return Map.of();
        }
    }

    @Override
    public List<Map<String, Object>> inventoryOwned() {
        return callSupplier(inventorySupplier, "inventoryOwned");
    }

    @Override
    public List<Map<String, Object>> bondsList() {
        return callSupplier(bondsSupplier, "bondsList");
    }

    @Override
    public Map<String, Object> bondsTransfer(String targetUsername) {
        if (authService == null) {
            return Map.of("ok", false,
                "error", "household auth isn't wired on this surface");
        }
        var caller = authService.findUser(ownerDid).orElse(null);
        if (caller == null || !"steward".equals(caller.role())) {
            return Map.of("ok", false,
                "error", "only the steward can hand over the bond");
        }
        if (targetUsername == null || targetUsername.isBlank()) {
            return Map.of("ok", false,
                "error", "name the member who receives the bond: transfer <username>");
        }
        var target = authService.findUserByUsername(targetUsername.trim()).orElse(null);
        if (target == null) {
            return Map.of("ok", false,
                "error", "no household member named '" + targetUsername.trim() + "'");
        }
        if (target.id().equals(ownerDid)) {
            return Map.of("ok", false, "error", "you already hold the bond");
        }
        var display = target.displayName() == null || target.displayName().isBlank()
            ? target.username() : target.displayName();
        // The announce IS the transfer: the guardian re-remembers the bondholder and
        // every companion re-types the old bond to MEMBER (depth and history kept)
        // and promotes the new person's bond to BONDHOLDER. Role moves; nothing is
        // erased.
        @SuppressWarnings("unchecked")
        var guardian = (ActorSystem<Object>) system;
        guardian.tell(new ZoneGuardian.AnnounceBondholder(target.id(), display));
        log.info("Bondholder handover: {} → {} ('{}') by steward '{}'",
            ownerDid, target.id(), display, caller.username());
        return Map.of("ok", true, "to", display,
            "summary", "The bond passes to " + display
                + ". Every companion keeps the shared history — the role moves, "
                + "the relationship stays.");
    }

    @Override
    public Map<String, Object> companionsBirth(String name) {
        return ForgeRoomBridge.stewardBirth(name, ownerDid);
    }

    @Override
    public List<Map<String, Object>> companionsList() {
        if (companionsSupplier != null) {
            return callSupplier(companionsSupplier, "companionsList");
        }
        return CompanionCodexView.list();
    }

    @Override
    public List<Map<String, Object>> presenceInHome() {
        return callSupplier(presenceSupplier, "presenceInHome");
    }

    @Override
    public List<Map<String, Object>> notificationChannels() {
        // The old notificationsSupplier was never wired (2026-07-18) — a steward
        // has no channels of their own, so the Compass showed empty. Aggregate the
        // household's companions' channels, tagged by companion.
        if (notificationsSupplier != null) {
            var wired = callSupplier(notificationsSupplier, "notificationChannels");
            if (wired != null && !wired.isEmpty()) return wired;
        }
        return HouseholdViews.notificationChannelsForHousehold();
    }

    @Override
    public List<Map<String, Object>> mcpTools() {
        return callSupplier(mcpToolsSupplier, "mcpTools");
    }

    // ─── Pairing (Threshold furnishing) ───────────────────────────────

    @Override
    public List<Map<String, Object>> pendingPairings() {
        if (pendingPairingsSupplier != null) {
            return callSupplier(pendingPairingsSupplier, "pendingPairings");
        }
        var svc = PairingService.get();
        if (svc == null) return List.of();
        try {
            var entries = svc.listPendingChallenges();
            var out = new ArrayList<Map<String, Object>>(entries.size());
            for (var p : entries) {
                var m = new HashMap<String, Object>();
                m.put("challengeId", p.challengeId());
                m.put("code", p.code());
                m.put("deviceName", p.deviceName());
                m.put("deviceType", p.deviceType());
                m.put("createdAt", p.createdAt() != null ? p.createdAt().toString() : null);
                m.put("expiresAt", p.expiresAt() != null ? p.expiresAt().toString() : null);
                out.add(m);
            }
            return out;
        } catch (Exception e) {
            log.warn("pendingPairings({}): {}", ownerDid, e.getMessage());
            return List.of();
        }
    }

    @Override
    public String activePairCode() {
        if (activePairCodeSupplier != null) {
            try { return activePairCodeSupplier.get(); }
            catch (Exception e) { return null; }
        }
        var svc = PairingService.get();
        if (svc == null) return null;
        try {
            return svc.getPendingChallenge()
                .map(PairingService.PairingChallenge::code)
                .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public String activeHouseholdKey() {
        if (activeHouseholdKeySupplier != null) {
            try { return activeHouseholdKeySupplier.get(); }
            catch (Exception e) { return null; }
        }
        var svc = PairingService.get();
        if (svc == null) return null;
        try {
            return svc.getActiveHouseholdKey()
                .map(PairingService.HouseholdKey::key)
                .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public String generateHouseholdKey() {
        if (generateHouseholdKeySupplier != null) {
            try { return generateHouseholdKeySupplier.get(); }
            catch (Exception e) { return null; }
        }
        var svc = PairingService.get();
        if (svc == null) return null;
        try { return svc.generateHouseholdKey(); }
        catch (Exception e) { return null; }
    }

    /**
     * surface the steward's pending skill
     * drafts. Backed by {@code SkillDraftStore} singleton, so wiring is
     * automatic for any zone where Phase 1 is up.
     */
    @Override
    public List<Map<String, Object>> pendingSkillDrafts() {
        if (ownerDid == null) return List.of();
        var store = SkillDraftStore.get();
        if (store == null) return List.of();
        try {
            var drafts = store.byAgentAndStatus(
                ownerDid, SkillDraft.Status.PENDING);
            var out = new ArrayList<Map<String, Object>>(drafts.size());
            for (var d : drafts) {
                var m = new HashMap<String, Object>();
                m.put("draftId", d.draftId());
                m.put("name", d.name());
                m.put("description", d.description());
                m.put("rationale", d.rationale());
                m.put("runtime", d.runtime());
                m.put("closesGaps", d.closesGaps());
                if (d.replaces() != null) m.put("replaces", d.replaces());
                m.put("proposedAt", d.proposedAt() != null ? d.proposedAt().toString() : null);
                m.put("proposedByModel", d.proposedByModel());
                out.add(m);
            }
            return out;
        } catch (Exception e) {
            log.warn("pendingSkillDrafts({}): {}", ownerDid, e.getMessage());
            return List.of();
        }
    }

    // ─── Study control panel: household / invite / ward / nodes /
    //     treasury / devices / security-audit / safe-snapshots ─────────
    // Writes route ownerDid (the ACTING player's user id) as the caller so
    // service-level steward checks apply; where a service carries no caller
    // check (InviteService, WardService) the steward gate lives HERE.
    // Nothing below ever throws into the script.

    @Override
    public List<Map<String, Object>> householdMembers() {
        if (authService == null) return List.of();
        try {
            var users = authService.listUsers();
            var out = new ArrayList<Map<String, Object>>(users.size());
            for (var u : users) {
                var m = new LinkedHashMap<String, Object>();
                m.put("username", u.username());
                m.put("displayName", u.displayName());
                m.put("role", u.role());
                m.put("createdAt", u.createdAt() != null ? u.createdAt().toString() : null);
                out.add(m);
            }
            return out;
        } catch (Exception e) {
            log.warn("householdMembers({}): {}", ownerDid, e.getMessage());
            return List.of();
        }
    }

    @Override
    public Map<String, Object> householdSetRole(String username, String role) {
        if (authService == null || ownerDid == null) {
            return Map.of("ok", false, "error", "household service not available here");
        }
        try {
            var normalized = role == null ? "" : role.trim().toLowerCase();
            if (!KNOWN_ROLES.contains(normalized)) {
                return Map.of("ok", false,
                    "error", "unknown role '" + role + "' (steward/member/guest/child)");
            }
            var target = authService.findUserByUsername(username);
            if (target.isEmpty()) {
                return Map.of("ok", false, "error", "no such member: " + username);
            }
            // AuthService.setRole enforces caller-is-steward; ownerDid is the
            // acting player's user id on this surface.
            var ok = authService.setRole(ownerDid, target.get().id(), normalized);
            if (!ok) return Map.of("ok", false, "error", "steward only");
            return Map.of("ok", true, "username", username, "role", normalized);
        } catch (Exception e) {
            log.warn("householdSetRole({}, {}): {}", ownerDid, username, e.getMessage());
            return Map.of("ok", false, "error", "role change failed: " + e.getMessage());
        }
    }

    @Override
    public Map<String, Object> householdRemoveMember(String username) {
        if (authService == null || ownerDid == null) {
            return Map.of("ok", false, "error", "household service not available here");
        }
        try {
            var target = authService.findUserByUsername(username);
            if (target.isEmpty()) {
                return Map.of("ok", false, "error", "no such member: " + username);
            }
            if (ownerDid.equals(target.get().id())) {
                return Map.of("ok", false, "error", "you cannot remove yourself");
            }
            // AuthService.removeUser enforces caller-is-steward.
            var ok = authService.removeUser(ownerDid, target.get().id());
            if (!ok) return Map.of("ok", false, "error", "steward only");
            return Map.of("ok", true, "username", username);
        } catch (Exception e) {
            log.warn("householdRemoveMember({}, {}): {}", ownerDid, username, e.getMessage());
            return Map.of("ok", false, "error", "remove failed: " + e.getMessage());
        }
    }

    // ─── Parental controls (parental-controls scroll) ──────────────────

    @Override
    public List<Map<String, Object>> parentalList() {
        if (parentalService == null) return List.of();
        try {
            var steward = isSteward();
            var out = new ArrayList<Map<String, Object>>();
            for (var c : parentalService.listControls()) {
                // Non-steward: only your own entry — limits on others aren't
                // your business, but your own rules should be visible to you.
                if (!steward && !c.memberUserId().equals(ownerDid)) continue;
                out.add(parentalView(c));
            }
            return out;
        } catch (Exception e) {
            log.warn("parentalList({}): {}", ownerDid, e.getMessage());
            return List.of();
        }
    }

    @Override
    public Map<String, Object> parentalGet(String username) {
        if (parentalService == null || authService == null) {
            return Map.of("ok", false, "error", "parental service not available here");
        }
        try {
            var target = authService.findUserByUsername(username);
            if (target.isEmpty()) {
                return Map.of("ok", false, "error", "no such member: " + username);
            }
            if (!isSteward() && !target.get().id().equals(ownerDid)) {
                return Map.of("ok", false, "error", "steward only");
            }
            var controls = parentalService.controlsFor(target.get().id());
            if (controls.isEmpty()) {
                return Map.of("ok", true, "username", username, "controls", false);
            }
            var m = parentalView(controls.get());
            m.put("ok", true);
            m.put("controls", true);
            return m;
        } catch (Exception e) {
            log.warn("parentalGet({}, {}): {}", ownerDid, username, e.getMessage());
            return Map.of("ok", false, "error", "parental read failed: " + e.getMessage());
        }
    }

    @Override
    public Map<String, Object> parentalSet(String username, String field, Object value) {
        if (parentalService == null || authService == null || ownerDid == null) {
            return Map.of("ok", false, "error", "parental service not available here");
        }
        try {
            var target = authService.findUserByUsername(username);
            if (target.isEmpty()) {
                return Map.of("ok", false, "error", "no such member: " + username);
            }
            var targetId = target.get().id();
            var cur = parentalService.controlsFor(targetId).orElse(
                new ParentalControlService.Controls(targetId, null, List.of(), null,
                    ParentalControlService.FILTER_OFF, null, null));
            Integer minutes = cur.dailyMinutes();
            Integer inference = cur.dailyInference();
            var filter = cur.contentFilter();
            var rooms = new ArrayList<>(cur.blockedRooms());

            var f = field == null ? "" : field.trim().toLowerCase();
            switch (f) {
                case "minutes" -> {
                    var parsed = parseLimit(value);
                    if (parsed.isEmpty()) return badLimit(value);
                    minutes = parsed.get().orElse(null) == null ? null : parsed.get().get();
                }
                case "inference" -> {
                    var parsed = parseLimit(value);
                    if (parsed.isEmpty()) return badLimit(value);
                    inference = parsed.get().orElse(null) == null ? null : parsed.get().get();
                }
                case "filter" -> {
                    var v = value == null ? "" : String.valueOf(value).trim().toLowerCase();
                    if (!ParentalControlService.FILTER_STRICT.equals(v)
                            && !ParentalControlService.FILTER_OFF.equals(v)) {
                        return Map.of("ok", false, "error", "filter must be strict or off");
                    }
                    filter = v;
                }
                case "block-room" -> {
                    var glob = value == null ? "" : String.valueOf(value).trim();
                    if (glob.isBlank()) return Map.of("ok", false, "error", "room glob required");
                    if (!rooms.contains(glob)) rooms.add(glob);
                }
                case "unblock-room" -> {
                    var glob = value == null ? "" : String.valueOf(value).trim();
                    if (!rooms.remove(glob)) {
                        return Map.of("ok", false, "error", "no such room block: " + glob);
                    }
                }
                default -> {
                    return Map.of("ok", false, "error", "unknown field '" + field
                        + "' (minutes/inference/filter/block-room/unblock-room)");
                }
            }

            // ParentalControlService.setControls enforces caller-is-steward.
            var ok = parentalService.setControls(
                ownerDid, targetId, minutes, rooms, inference, filter);
            if (!ok) return Map.of("ok", false, "error", "steward only");
            var m = parentalView(parentalService.controlsFor(targetId).orElseThrow());
            m.put("ok", true);
            return m;
        } catch (Exception e) {
            log.warn("parentalSet({}, {}, {}): {}", ownerDid, username, field, e.getMessage());
            return Map.of("ok", false, "error", "parental change failed: " + e.getMessage());
        }
    }

    @Override
    public Map<String, Object> parentalClear(String username) {
        if (parentalService == null || authService == null || ownerDid == null) {
            return Map.of("ok", false, "error", "parental service not available here");
        }
        try {
            var target = authService.findUserByUsername(username);
            if (target.isEmpty()) {
                return Map.of("ok", false, "error", "no such member: " + username);
            }
            if (!isSteward()) return Map.of("ok", false, "error", "steward only");
            // clearControls re-checks steward; false here (steward verified
            // above) means no row existed for the member.
            var removed = parentalService.clearControls(ownerDid, target.get().id());
            if (!removed) {
                return Map.of("ok", false, "error", "no controls set for " + username);
            }
            return Map.of("ok", true, "username", username);
        } catch (Exception e) {
            log.warn("parentalClear({}, {}): {}", ownerDid, username, e.getMessage());
            return Map.of("ok", false, "error", "parental clear failed: " + e.getMessage());
        }
    }

    /** Controls + today's usage, username-resolved, for the scroll's table. */
    private Map<String, Object> parentalView(ParentalControlService.Controls c) {
        var m = new LinkedHashMap<String, Object>();
        var username = c.memberUserId();
        var displayName = username;
        if (authService != null) {
            var u = authService.findUser(c.memberUserId());
            if (u.isPresent()) {
                username = u.get().username();
                displayName = u.get().displayName();
            }
        }
        m.put("username", username);
        m.put("displayName", displayName);
        m.put("dailyMinutes", c.dailyMinutes());
        m.put("dailyInference", c.dailyInference());
        m.put("contentFilter", c.contentFilter());
        m.put("blockedRooms", List.copyOf(c.blockedRooms()));
        var usage = parentalService.usageToday(c.memberUserId());
        m.put("minutesUsedToday", usage.minutesUsed());
        m.put("inferencesUsedToday", usage.inferencesUsed());
        return m;
    }

    /**
     * Parse a limit value from a script: {@code "off"}/{@code "unlimited"}/
     * {@code "none"} → present-empty (unlimited), a non-negative number →
     * present-value; anything else → empty optional (invalid).
     */
    private static Optional<Optional<Integer>> parseLimit(Object value) {
        if (value == null) return Optional.empty();
        if (value instanceof Number n) {
            var v = n.intValue();
            return v < 0 ? Optional.empty() : Optional.of(Optional.of(v));
        }
        var s = String.valueOf(value).trim().toLowerCase();
        if (s.equals("off") || s.equals("unlimited") || s.equals("none") || s.equals("null")) {
            return Optional.of(Optional.empty());
        }
        try {
            var v = Integer.parseInt(s);
            return v < 0 ? Optional.empty() : Optional.of(Optional.of(v));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static Map<String, Object> badLimit(Object value) {
        return Map.of("ok", false,
            "error", "limit must be a non-negative number or 'off': " + value);
    }

    // ─── Maintenance (maintenance dial + key chest) ─────────────────────

    @Override
    public Map<String, Object> maintenanceStatus() {
        if (maintenanceService == null) {
            return Map.of("ok", false, "error", "maintenance service not available here");
        }
        try {
            var s = maintenanceService.status();
            var m = new LinkedHashMap<String, Object>();
            m.put("ok", true);
            m.put("on", s.mode().on());
            m.put("reason", s.mode().reason());
            m.put("setBy", resolveUsername(s.mode().setBy()));
            m.put("since", s.mode().since() != null ? s.mode().since().toString() : null);
            m.put("scheduleHours", s.backupScheduleHours());
            m.put("lastScheduledBackup", s.lastScheduledBackup() != null
                ? s.lastScheduledBackup().toString() : null);
            m.put("snapshotCount", s.snapshotCount());
            m.put("latestSnapshotId", s.latestSnapshotId());
            m.put("latestSnapshotAt", s.latestSnapshotAt() != null
                ? s.latestSnapshotAt().toString() : null);
            m.put("staged", s.stagedRestore().map(r -> {
                var sr = new LinkedHashMap<String, Object>();
                sr.put("snapshotId", r.snapshotId());
                sr.put("backupFile", r.backupFile());
                sr.put("stagedBy", resolveUsername(r.stagedBy()));
                sr.put("stagedAt", r.stagedAt() != null ? r.stagedAt().toString() : null);
                return (Object) sr;
            }).orElse(null));
            return m;
        } catch (Exception e) {
            log.warn("maintenanceStatus({}): {}", ownerDid, e.getMessage());
            return Map.of("ok", false, "error", "maintenance read failed: " + e.getMessage());
        }
    }

    @Override
    public Map<String, Object> maintenanceSetMode(boolean on, String reason) {
        if (maintenanceService == null || ownerDid == null) {
            return Map.of("ok", false, "error", "maintenance service not available here");
        }
        try {
            // MaintenanceService.setMaintenanceMode enforces caller-is-steward.
            var ok = maintenanceService.setMaintenanceMode(ownerDid, on, reason);
            if (!ok) return Map.of("ok", false, "error", "steward only");
            var m = maintenanceStatus();
            return m;
        } catch (Exception e) {
            log.warn("maintenanceSetMode({}, {}): {}", ownerDid, on, e.getMessage());
            return Map.of("ok", false, "error", "maintenance change failed: " + e.getMessage());
        }
    }

    @Override
    public Map<String, Object> maintenanceBackupNow() {
        if (maintenanceService == null || ownerDid == null) {
            return Map.of("ok", false, "error", "maintenance service not available here");
        }
        try {
            // MaintenanceService.backupNow enforces caller-is-steward.
            return maintenanceService.backupNow(ownerDid);
        } catch (Exception e) {
            log.warn("maintenanceBackupNow({}): {}", ownerDid, e.getMessage());
            return Map.of("ok", false, "error", "backup failed: " + e.getMessage());
        }
    }

    @Override
    public Map<String, Object> maintenanceSetSchedule(int hours) {
        if (maintenanceService == null || ownerDid == null) {
            return Map.of("ok", false, "error", "maintenance service not available here");
        }
        try {
            if (hours < 0) {
                return Map.of("ok", false, "error",
                    "schedule must be a non-negative number of hours (0 = off)");
            }
            // MaintenanceService.setBackupSchedule enforces caller-is-steward.
            var ok = maintenanceService.setBackupSchedule(ownerDid, hours);
            if (!ok) return Map.of("ok", false, "error", "steward only");
            return Map.of("ok", true, "scheduleHours", hours);
        } catch (Exception e) {
            log.warn("maintenanceSetSchedule({}, {}): {}", ownerDid, hours, e.getMessage());
            return Map.of("ok", false, "error", "schedule change failed: " + e.getMessage());
        }
    }

    @Override
    public Map<String, Object> maintenanceStageRestore(String snapshotId) {
        if (maintenanceService == null || ownerDid == null) {
            return Map.of("ok", false, "error", "maintenance service not available here");
        }
        try {
            // MaintenanceService.stageRestore enforces caller-is-steward and
            // validates the snapshot id; nothing touches the live db here.
            return maintenanceService.stageRestore(ownerDid, snapshotId);
        } catch (Exception e) {
            log.warn("maintenanceStageRestore({}, {}): {}", ownerDid, snapshotId, e.getMessage());
            return Map.of("ok", false, "error", "stage restore failed: " + e.getMessage());
        }
    }

    @Override
    public Map<String, Object> maintenanceClearStagedRestore() {
        if (maintenanceService == null || ownerDid == null) {
            return Map.of("ok", false, "error", "maintenance service not available here");
        }
        try {
            // MaintenanceService.clearStagedRestore enforces caller-is-steward.
            return maintenanceService.clearStagedRestore(ownerDid);
        } catch (Exception e) {
            log.warn("maintenanceClearStagedRestore({}): {}", ownerDid, e.getMessage());
            return Map.of("ok", false, "error", "clear staged restore failed: " + e.getMessage());
        }
    }

    /** Best-effort userId → username for display (falls back to the raw id). */
    private String resolveUsername(String userId) {
        if (userId == null || authService == null) return userId;
        try {
            return authService.findUser(userId)
                .map(AuthService.User::username)
                .orElse(userId);
        } catch (Exception e) {
            return userId;
        }
    }

    @Override
    public List<Map<String, Object>> inviteList() {
        // Invite codes are join secrets — steward-only view even though
        // steward-only objects are what get provisioned (defense in depth).
        if (inviteService == null || !isSteward()) return List.of();
        try {
            var invites = inviteService.listInvites();
            var out = new ArrayList<Map<String, Object>>(invites.size());
            for (var inv : invites) {
                var m = new LinkedHashMap<String, Object>();
                m.put("id", inv.id());
                m.put("code", inv.code());
                m.put("intendedName", inv.intendedName());
                m.put("role", inv.role());
                if (inv.createdBy() != null) m.put("createdBy", inv.createdBy());
                m.put("createdAt", inv.createdAt() != null ? inv.createdAt().toString() : null);
                m.put("expiresAt", inv.expiresAt() != null ? inv.expiresAt().toString() : null);
                m.put("consumed", inv.isConsumed());
                m.put("expired", inv.isExpired());
                if (inv.consumedBy() != null) m.put("consumedBy", inv.consumedBy());
                if (inv.consumedAt() != null) m.put("consumedAt", inv.consumedAt().toString());
                out.add(m);
            }
            return out;
        } catch (Exception e) {
            log.warn("inviteList({}): {}", ownerDid, e.getMessage());
            return List.of();
        }
    }

    @Override
    public Map<String, Object> inviteCreate(String role, String intendedName) {
        if (inviteService == null) {
            return Map.of("ok", false, "error", "invite service not available here");
        }
        // InviteService carries no caller check — enforce steward here.
        if (!isSteward()) return Map.of("ok", false, "error", "steward only");
        try {
            var normalized = role == null || role.isBlank()
                ? "member" : role.trim().toLowerCase();
            if (!KNOWN_ROLES.contains(normalized)) {
                return Map.of("ok", false,
                    "error", "unknown role '" + role + "' (steward/member/guest/child)");
            }
            var name = intendedName == null || intendedName.isBlank()
                ? "new " + normalized : intendedName.trim();
            var inv = inviteService.createInvite(name, normalized, ownerDid);
            var m = new LinkedHashMap<String, Object>();
            m.put("ok", true);
            m.put("id", inv.id());
            m.put("code", inv.code());
            m.put("role", inv.role());
            m.put("intendedName", inv.intendedName());
            m.put("expiresAt", inv.expiresAt() != null ? inv.expiresAt().toString() : null);
            return m;
        } catch (Exception e) {
            log.warn("inviteCreate({}): {}", ownerDid, e.getMessage());
            return Map.of("ok", false, "error", "invite creation failed: " + e.getMessage());
        }
    }

    @Override
    public Map<String, Object> inviteRevoke(String codeOrId) {
        if (inviteService == null) {
            return Map.of("ok", false, "error", "invite service not available here");
        }
        if (!isSteward()) return Map.of("ok", false, "error", "steward only");
        if (codeOrId == null || codeOrId.isBlank()) {
            return Map.of("ok", false, "error", "invite id or code required");
        }
        try {
            // Try direct id first, then match a pending invite by code.
            if (inviteService.revokeInvite(codeOrId)) {
                return Map.of("ok", true, "id", codeOrId);
            }
            var wanted = codeOrId.trim().toLowerCase();
            for (var inv : inviteService.listPendingInvites()) {
                if (wanted.equals(inv.code()) && inviteService.revokeInvite(inv.id())) {
                    return Map.of("ok", true, "id", inv.id());
                }
            }
            return Map.of("ok", false, "error", "no such pending invite");
        } catch (Exception e) {
            log.warn("inviteRevoke({}): {}", ownerDid, e.getMessage());
            return Map.of("ok", false, "error", "invite revoke failed: " + e.getMessage());
        }
    }

    @Override
    public List<Map<String, Object>> wardList(String roomId) {
        if (wardService == null || roomId == null || roomId.isBlank()) return List.of();
        try {
            var wards = wardService.listWards(roomId.trim());
            var out = new ArrayList<Map<String, Object>>(wards.size());
            for (var w : wards) {
                var m = new LinkedHashMap<String, Object>();
                m.put("roomId", w.roomId());
                m.put("subject", w.principal());
                m.put("capability", w.permission());
                m.put("grantedBy", w.grantedBy());
                m.put("createdAt", w.createdAt());
                out.add(m);
            }
            return out;
        } catch (Exception e) {
            log.warn("wardList({}, {}): {}", ownerDid, roomId, e.getMessage());
            return List.of();
        }
    }

    @Override
    public Map<String, Object> wardGrant(String roomId, String subject, String capability) {
        return wardMutation(roomId, subject, capability, true);
    }

    @Override
    public Map<String, Object> wardRevoke(String roomId, String subject, String capability) {
        return wardMutation(roomId, subject, capability, false);
    }

    private Map<String, Object> wardMutation(String roomId, String subject,
                                              String capability, boolean grant) {
        if (wardService == null || ownerDid == null) {
            return Map.of("ok", false, "error", "ward service not available here");
        }
        if (roomId == null || roomId.isBlank() || subject == null || subject.isBlank()) {
            return Map.of("ok", false, "error", "roomId and subject required");
        }
        var cap = capability == null ? "" : capability.trim().toLowerCase();
        if (!WARD_CAPABILITIES.contains(cap)) {
            return Map.of("ok", false, "error", "unknown ward capability '" + capability
                + "' (enter/speak/take/drop/use/build/admin)");
        }
        try {
            // WardService carries no caller check — steward or room-admin only.
            var room = roomId.trim();
            if (!isSteward() && !wardService.isAdmin(room, ownerDid)) {
                return Map.of("ok", false, "error", "steward only");
            }
            if (grant) {
                var created = wardService.grant(room, subject.trim(), cap, ownerDid);
                return Map.of("ok", true, "created", created,
                    "roomId", room, "subject", subject.trim(), "capability", cap);
            }
            var removed = wardService.revoke(room, subject.trim(), cap);
            if (!removed) return Map.of("ok", false, "error", "no such ward");
            return Map.of("ok", true,
                "roomId", room, "subject", subject.trim(), "capability", cap);
        } catch (Exception e) {
            log.warn("wardMutation({}, {}): {}", ownerDid, roomId, e.getMessage());
            return Map.of("ok", false, "error", "ward change failed: " + e.getMessage());
        }
    }

    @Override
    public List<Map<String, Object>> nodesList() {
        return callSupplier(nodesSupplier, "nodesList");
    }

    @Override
    public Map<String, Object> treasurySummary() {
        var tracker = AgentCostTracker.get();
        if (tracker == null) return Map.of();
        try {
            long inferences = 0, mcpCalls = 0, tokens = 0;
            double cost = 0.0;
            Instant first = null, last = null;
            var agents = tracker.trackedAgents();
            for (var agentId : agents) {
                var maybe = tracker.summary(agentId);
                if (maybe.isEmpty()) continue;
                var s = maybe.get();
                inferences += s.totalInferences();
                mcpCalls += s.totalMcpCalls();
                tokens += s.totalTokens();
                cost += s.totalMonetaryCost();
                if (s.firstActivity() != null
                        && (first == null || s.firstActivity().isBefore(first))) {
                    first = s.firstActivity();
                }
                if (s.lastActivity() != null
                        && (last == null || s.lastActivity().isAfter(last))) {
                    last = s.lastActivity();
                }
            }
            var m = new LinkedHashMap<String, Object>();
            m.put("agents", agents.size());
            m.put("inferences", inferences);
            m.put("mcpCalls", mcpCalls);
            m.put("tokens", tokens);
            m.put("monetaryCost", cost);
            if (first != null) m.put("firstActivity", first.toString());
            if (last != null) m.put("lastActivity", last.toString());
            return m;
        } catch (Exception e) {
            log.warn("treasurySummary({}): {}", ownerDid, e.getMessage());
            return Map.of();
        }
    }

    @Override
    public List<Map<String, Object>> treasuryPerMember() {
        var tracker = AgentCostTracker.get();
        if (tracker == null) return List.of();
        try {
            var out = new ArrayList<Map<String, Object>>();
            for (var agentId : tracker.trackedAgents()) {
                var maybe = tracker.summary(agentId);
                if (maybe.isEmpty()) continue;
                var s = maybe.get();
                var m = new LinkedHashMap<String, Object>();
                m.put("agentId", agentId);
                m.put("inferences", s.totalInferences());
                m.put("mcpCalls", s.totalMcpCalls());
                m.put("tokens", s.totalTokens());
                m.put("monetaryCost", s.totalMonetaryCost());
                m.put("avgLatencyMs", s.avgLatencyMs());
                if (s.firstActivity() != null) m.put("firstActivity", s.firstActivity().toString());
                if (s.lastActivity() != null) m.put("lastActivity", s.lastActivity().toString());
                var budgetNote = tracker.checkBudget(agentId);
                if (budgetNote != null) m.put("budgetNote", budgetNote);
                out.add(m);
            }
            out.sort((a, b) -> String.valueOf(a.get("agentId"))
                .compareTo(String.valueOf(b.get("agentId"))));
            return out;
        } catch (Exception e) {
            log.warn("treasuryPerMember({}): {}", ownerDid, e.getMessage());
            return List.of();
        }
    }

    @Override
    public Map<String, Object> treasurySetBudget(String member, double dailyLimitUsd) {
        var tracker = AgentCostTracker.get();
        if (tracker == null) {
            return Map.of("ok", false, "error", "treasury service not available here");
        }
        // AgentCostTracker carries no caller check — enforce steward here.
        if (!isSteward()) return Map.of("ok", false, "error", "steward only");
        if (member == null || member.isBlank()) {
            return Map.of("ok", false, "error", "member id required");
        }
        if (dailyLimitUsd < 0 || !Double.isFinite(dailyLimitUsd)) {
            return Map.of("ok", false, "error", "daily limit must be a non-negative number");
        }
        try {
            tracker.setBudget(member.trim(), dailyLimitUsd);
            return Map.of("ok", true, "member", member.trim(),
                "dailyLimitUsd", dailyLimitUsd,
                "note", "in-memory limit — resets on node restart");
        } catch (Exception e) {
            return Map.of("ok", false, "error", "budget change failed: " + e.getMessage());
        }
    }

    @Override
    public Map<String, Object> treasuryTransfer(String toEntity, long amount, String note) {
        // W5 (2026-07-11): Counting House Transfer, previously test-only.
        // The FROM side is always the ACTING player (ownerDid) — no
        // impersonated transfers from this surface.
        if (ownerDid == null) {
            return Map.of("ok", false, "error", "treasury service not available here");
        }
        if (!CountingHouseGateway.available()) {
            return Map.of("ok", false, "error", "the Counting House isn't reachable from this surface");
        }
        if (toEntity == null || toEntity.isBlank()) {
            return Map.of("ok", false, "error", "recipient required");
        }
        var to = toEntity.trim();
        if (to.equals(ownerDid)) {
            return Map.of("ok", false, "error", "cannot transfer credits to yourself");
        }
        if (amount <= 0) {
            return Map.of("ok", false, "error", "amount must be a positive whole number of credits");
        }
        try {
            var outcome = CountingHouseGateway.transfer(
                ownerDid, to, amount,
                note == null || note.isBlank() ? "treasury transfer" : note);
            if (outcome.isEmpty()) {
                return Map.of("ok", false, "error", "the Counting House did not answer");
            }
            var message = outcome.get();
            var ok = message.startsWith("Transfer complete");
            return ok ? Map.of("ok", true, "message", message)
                      : Map.of("ok", false, "error", message);
        } catch (Exception e) {
            log.warn("treasuryTransfer({} → {}): {}", ownerDid, to, e.getMessage());
            return Map.of("ok", false, "error", "transfer failed: " + e.getMessage());
        }
    }

    @Override
    public Map<String, Object> treasuryBalance(String entityId) {
        if (!CountingHouseGateway.available()) {
            return Map.of("ok", false, "error", "the Counting House isn't reachable from this surface");
        }
        var target = entityId == null || entityId.isBlank() ? ownerDid : entityId.trim();
        if (target == null) {
            return Map.of("ok", false, "error", "member id required");
        }
        try {
            var maybe = CountingHouseGateway.balance(target);
            if (maybe.isEmpty()) {
                return Map.of("ok", false, "error", "the Counting House did not answer");
            }
            var b = maybe.get();
            var m = new LinkedHashMap<String, Object>();
            m.put("ok", true);
            m.put("entityId", b.entityId());
            m.put("balance", b.balance());
            m.put("creditLimit", b.creditLimit());
            m.put("totalEarned", b.totalEarned());
            m.put("totalSpent", b.totalSpent());
            return m;
        } catch (Exception e) {
            log.warn("treasuryBalance({}): {}", target, e.getMessage());
            return Map.of("ok", false, "error", "balance query failed: " + e.getMessage());
        }
    }

    @Override
    public List<Map<String, Object>> pairedDevices() {
        var out = new ArrayList<Map<String, Object>>();
        var svc = PairingService.get();
        if (svc != null && ownerDid != null) {
            try {
                for (var d : svc.listDevices()) {
                    // Caller-scoped: only devices linked to the acting player.
                    if (!ownerDid.equals(d.userId())) continue;
                    var m = new LinkedHashMap<String, Object>();
                    m.put("kind", "device");
                    m.put("id", d.id());
                    m.put("name", d.name());
                    m.put("type", d.type());
                    m.put("pairedAt", d.pairedAt() != null ? d.pairedAt().toString() : null);
                    m.put("lastSeen", d.lastSeen() != null ? d.lastSeen().toString() : null);
                    m.put("revoked", d.revoked());
                    out.add(m);
                }
            } catch (Exception e) {
                log.warn("pairedDevices({}): {}", ownerDid, e.getMessage());
            }
        }
        if (authService != null && ownerDid != null) {
            try {
                for (var k : authService.listSshKeys(ownerDid)) {
                    var m = new LinkedHashMap<String, Object>();
                    m.put("kind", "ssh-key");
                    m.put("keyLine", k.keyLine());
                    m.put("comment", k.comment());
                    m.put("addedAt", k.addedAt() != null ? k.addedAt().toString() : null);
                    out.add(m);
                }
            } catch (Exception e) {
                log.warn("pairedDevices ssh-keys({}): {}", ownerDid, e.getMessage());
            }
        }
        return out;
    }

    @Override
    public Map<String, Object> pairingRevokeDevice(String deviceId) {
        var svc = PairingService.get();
        if (svc == null || ownerDid == null) {
            return Map.of("ok", false, "error", "pairing service not available here");
        }
        if (deviceId == null || deviceId.isBlank()) {
            return Map.of("ok", false, "error", "device id required");
        }
        try {
            var wanted = deviceId.trim();
            for (var d : svc.listDevices()) {
                if (!wanted.equals(d.id())) continue;
                // Own devices only; steward may revoke any household device.
                if (!ownerDid.equals(d.userId()) && !isSteward()) {
                    return Map.of("ok", false, "error", "steward only");
                }
                if (d.revoked()) {
                    return Map.of("ok", false, "error", "device already revoked");
                }
                svc.revokeDevice(wanted);
                return Map.of("ok", true, "id", wanted, "name", d.name());
            }
            return Map.of("ok", false, "error", "no such device: " + deviceId);
        } catch (Exception e) {
            log.warn("pairingRevokeDevice({}, {}): {}", ownerDid, deviceId, e.getMessage());
            return Map.of("ok", false, "error", "revoke failed: " + e.getMessage());
        }
    }

    @Override
    public List<Map<String, Object>> auditSecurity(int limit) {
        if (securityAuditLog == null) return List.of();
        try {
            var entries = securityAuditLog.recent(Math.max(1, Math.min(limit, 200)));
            var out = new ArrayList<Map<String, Object>>(entries.size());
            for (var a : entries) {
                var m = new LinkedHashMap<String, Object>();
                m.put("timestamp", a.timestamp() != null ? a.timestamp().toString() : null);
                m.put("actor", a.actorDid());
                m.put("actorName", a.actorName());
                m.put("type", a.type() != null ? a.type().name() : null);
                m.put("targetId", a.targetId());
                m.put("description", a.description());
                m.put("approved", a.approved());
                out.add(m);
            }
            return out;
        } catch (Exception e) {
            log.warn("auditSecurity({}): {}", ownerDid, e.getMessage());
            return List.of();
        }
    }

    @Override
    public List<Map<String, Object>> safeSnapshots() {
        if (backupOrchestrator == null) return List.of();
        try {
            var snaps = backupOrchestrator.listSnapshots();
            var out = new ArrayList<Map<String, Object>>(snaps.size());
            for (var s : snaps) {
                var m = new LinkedHashMap<String, Object>();
                m.put("id", s.backupId());
                m.put("location", s.location() != null ? s.location().toString() : null);
                m.put("timestamp", s.timestamp() != null ? s.timestamp().toString() : null);
                m.put("sizeBytes", s.sizeBytes());
                m.put("source", s.source());
                out.add(m);
            }
            return out;
        } catch (Exception e) {
            log.warn("safeSnapshots({}): {}", ownerDid, e.getMessage());
            return List.of();
        }
    }

    /**
     * hermod data-domain grants: reading is open to the household;
     * revocation is the steward's alone. HermodGrantStore carries no
     * caller check — the gate lives HERE, same discipline as wards.
     */
    @Override
    public List<Map<String, Object>> hermodGrantsList() {
        if (hermodGrantStore == null) return List.of();
        var out = new ArrayList<Map<String, Object>>();
        for (var v : hermodGrantStore.list()) {
            var row = new LinkedHashMap<String, Object>();
            if (v.grant() != null) {
                row.put("grantId", v.grant().grantId());
                row.put("dataDomain", v.grant().dataDomain());
                row.put("deviceClass", v.grant().grantedToDeviceClass());
                row.put("issuedAt", v.grant().issuedAt().toString());
                row.put("expiresAt", v.grant().expiresAt().toString());
            }
            row.put("status", v.status());
            row.put("file", v.fileName());
            out.add(row);
        }
        return out;
    }

    @Override
    public Map<String, Object> hermodGrantRevoke(String grantIdOrStem) {
        if (hermodGrantStore == null) {
            return Map.of("ok", false, "error", "hermod grants not available here");
        }
        if (!isSteward()) {
            return Map.of("ok", false, "error", "steward only");
        }
        var revoked = hermodGrantStore.revoke(grantIdOrStem);
        if (revoked == null) return Map.of("ok", false, "error", "no such grant");
        return Map.of("ok", true,
            "grantId", revoked.grant() != null ? revoked.grant().grantId() : "",
            "file", revoked.fileName(),
            // Signatures cannot be recalled — say so where the steward acts.
            "note", "household copy tombstoned; a copy already carried by a "
                + "device stays valid until its own expiry");
    }

    /** True when the acting player's account holds the steward role. */
    private boolean isSteward() {
        if (authService == null || ownerDid == null) return false;
        try {
            return authService.findUser(ownerDid)
                .map(u -> "steward".equals(u.role()))
                .orElse(false);
        } catch (Exception e) {
            return false;
        }
    }

    private List<Map<String, Object>> callSupplier(
            Supplier<List<Map<String, Object>>> s, String label) {
        if (s == null) return List.of();
        try {
            var v = s.get();
            return v != null ? v : List.of();
        } catch (Exception e) {
            log.warn("{}({}): {}", label, ownerDid, e.getMessage());
            return List.of();
        }
    }

    private static List<Map<String, Object>> grantsView(
            List<Grant> grants) {
        var now = Instant.now();
        var out = new ArrayList<Map<String, Object>>(grants.size());
        for (var g : grants) {
            var m = new HashMap<String, Object>();
            m.put("id", g.id());
            m.put("issuer", g.issuer());
            m.put("subject", g.subject());
            m.put("resource", g.resource().toString());
            m.put("resourceType", g.resource().type());
            m.put("capability", g.capability().name());
            m.put("active", g.isActive(now));
            m.put("issuedAt", g.issuedAt().toString());
            if (g.expiresAt() != null) m.put("expiresAt", g.expiresAt().toString());
            if (g.revokedAt() != null) m.put("revokedAt", g.revokedAt().toString());
            if (g.scope() != null && !g.scope().isEmpty()) m.put("scope", g.scope());
            out.add(m);
        }
        return out;
    }

    // ─── Pinboard (universal writes, player-scoped) ─────────────────

    @Override
    public Map<String, Object> pinboardPin(String text, Map<String, Object> opts) {
        if (study == null) return Map.of("ok", false, "error", "pinboard not wired on this surface");
        if (text == null || text.isBlank()) return Map.of("ok", false, "error", "blank text");
        var title = text.length() > 60 ? text.substring(0, 60) : text;
        var id = study.pin(ownerDid, title, "", text);
        return Map.of("ok", true, "id", id);
    }

    @Override
    public List<Map<String, Object>> pinboardList() {
        if (study == null || ownerDid == null) return List.of();
        var results = study.listPins(ownerDid, 100);
        var out = new ArrayList<Map<String, Object>>(results.size());
        for (var r : results) {
            var m = new HashMap<String, Object>();
            m.put("id", r.id());
            m.put("content", r.content());
            var meta = r.metadata();
            if (meta != null && meta.get("timestamp") != null) m.put("ts", meta.get("timestamp"));
            out.add(m);
        }
        return out;
    }

    @Override
    public Map<String, Object> pinboardUnpin(String id) {
        if (study == null) return Map.of("ok", false, "error", "pinboard not wired on this surface");
        return study.unpin(id, ownerDid);
    }

    // Council governance (player route) — these were companion-route-only, so the
    // Study agenda board rendered empty for the steward. Shared helper, drift-proof.
    @Override
    public List<Map<String, Object>> councilProposals() { return HouseholdViews.councilProposals(); }
    @Override
    public List<Map<String, Object>> councilHistory(int limit) { return HouseholdViews.councilHistory(limit); }

    // Recipes console + coding slate (player route) — process-global data (jdbc /
    // BackendRegistry singleton), companion-route-only before, so both shipped Study
    // furnishings rendered empty for the steward. Shared helper, drift-proof.
    @Override
    public List<Map<String, Object>> recipeEnrolled() { return HouseholdViews.recipeEnrolled(); }
    @Override
    public List<Map<String, Object>> recipeRecentRuns(int limit) { return HouseholdViews.recipeRecentRuns(limit); }
    @Override
    public List<Map<String, Object>> codingBackendsStatus() { return HouseholdViews.codingBackendsStatus(); }

    // Drives Mirror (player route) — a bondholder's glimpse into their
    // companion's inner state (2026-07-18). Resolves the household's primary
    // companion; the steward no longer has to read world.db + logs for this.
    @Override
    public Map<String, Object> driveSnapshot() {
        return HouseholdViews.primaryCompanionDriveSnapshot();
    }

    // ── Relay governance (player route) ─────────────────────────────────
    // 2026-07-18: the Warden furnishing (world.relay.*) was companion-route-only,
    // so a STEWARD administering their own relay was always told "this zone does
    // not administer any relay." The governor is per-zone and caller-agnostic
    // (RelayGovernors.forAgent ignores the id; the acting DID is used per-action
    // for scope), so the player route just needs the same surface, keyed by the
    // acting player's DID. Attached at the WS/SSH provider build sites.
    private volatile RelayGovernor relayGovernor;

    public HomeOwnerItemProvider withRelayGovernor(RelayGovernor g) {
        this.relayGovernor = g;
        return this;
    }

    @Override
    public Map<String, Object> relayInfo() {
        var g = relayGovernor;
        if (g == null) return Map.of("configured", false);
        var out = new LinkedHashMap<String, Object>();
        out.put("configured", true);
        out.put("relayDid", g.relayDid());
        out.put("relayLabel", g.relayLabel());
        out.put("ownerDid", g.ownerDid());
        out.put("scope", g.scopeOf(ownerDid));
        out.put("canDelegate", g.canDelegate(ownerDid));
        out.put("callerDid", ownerDid);
        return out;
    }

    @Override
    public List<Map<String, Object>> relayRegistrations() {
        var g = relayGovernor;
        if (g == null) return List.of();
        var rows = g.listRegistrations(ownerDid);
        if (rows == null || rows.isEmpty()) return List.of();
        var out = new ArrayList<Map<String, Object>>(rows.size());
        for (var r : rows) out.add(new LinkedHashMap<>(r));
        return out;
    }

    @Override
    public List<Map<String, Object>> relayDelegations() {
        var g = relayGovernor;
        if (g == null || homeClient == null || g.scopeOf(ownerDid) == null) return List.of();
        try {
            var resource = RelayGovernance.relayAdminResource(g.ownerDid(), g.relayDid()).toString();
            var out = new ArrayList<Map<String, Object>>();
            for (var grant : grantsView(homeClient.listIssuedBy(g.ownerDid()))) {
                if (resource.equals(grant.get("resource"))) out.add(grant);
            }
            return out;
        } catch (Exception e) {
            log.warn("relayDelegations failed for {}: {}", ownerDid, e.getMessage());
            return List.of();
        }
    }

    @Override
    public Map<String, Object> relayAdminAction(String op, Map<String, Object> args) {
        var g = relayGovernor;
        if (g == null) {
            return Map.of("ok", false, "status", 0, "error", "no relay configured for this zone");
        }
        var parsed = RelayAdminOp.parse(op);
        if (parsed == null) {
            return Map.of("ok", false, "status", 400, "error", "unknown relay op: " + op);
        }
        return g.authorizeAndCall(ownerDid, parsed, args == null ? Map.of() : args);
    }

    // ── Filesystem sandbox (player route) ───────────────────────────────
    // 2026-07-18: world.fs.* was UNWIRED on the player route entirely — a
    // steward-invoked item that wrote a file got "fs.write not wired" and the
    // write silently failed. Same per-agent sandbox the companion route uses,
    // rooted at {dataDir}/items/<ownerDid>/fs, lazily created.
    private volatile SandboxedFs fsSandbox;

    private SandboxedFs fs() {
        var s = fsSandbox;
        if (s != null) return s;
        synchronized (this) {
            if (fsSandbox == null) {
                var dataDir = System.getenv("WYRDSEKAI_DATA_DIR");
                if (dataDir == null || dataDir.isBlank()) {
                    dataDir = System.getProperty("wyrdsekai.data.dir",
                        System.getProperty("java.io.tmpdir") + "/wyrdsekai");
                }
                fsSandbox = new SandboxedFs(
                    Path.of(dataDir), ownerDid == null ? "steward" : ownerDid);
            }
            return fsSandbox;
        }
    }

    @Override
    public String fsRead(String relPath) {
        try { return fs().read(relPath); }
        catch (Exception e) { return "[error] " + (e.getMessage() == null ? "fs_read_failed" : e.getMessage()); }
    }

    @Override
    public Map<String, Object> fsWrite(String relPath, String content) {
        try { return fs().write(relPath, content); }
        catch (Exception e) { return Map.of("ok", false, "error", e.getMessage() == null ? "fs_write_failed" : e.getMessage()); }
    }

    @Override
    public List<Map<String, Object>> fsList(String relDir) {
        try { return fs().list(relDir); } catch (Exception e) { return List.of(); }
    }

    @Override
    public Map<String, Object> fsDelete(String relPath) {
        try { return fs().delete(relPath); }
        catch (Exception e) { return Map.of("ok", false, "error", e.getMessage() == null ? "fs_delete_failed" : e.getMessage()); }
    }

    @Override
    public boolean fsExists(String relPath) {
        try { return fs().exists(relPath); } catch (Exception e) { return false; }
    }

    @Override
    public Map<String, Object> fsStat(String relPath) {
        try { return fs().stat(relPath); }
        catch (Exception e) { return Map.of("error", e.getMessage() == null ? "stat_failed" : e.getMessage()); }
    }

    @Override
    public Map<String, Object> fsMkdir(String relPath) {
        try { return fs().mkdir(relPath); }
        catch (Exception e) { return Map.of("ok", false, "error", e.getMessage() == null ? "mkdir_failed" : e.getMessage()); }
    }

    // ── Journal (player route) ──────────────────────────────────────────
    // 2026-07-18: these were implemented only on the companion provider, so the
    // steward's `use journal <entry>` hit the interface default and silently
    // DISCARDED the entry (critical data loss). Same StudyService backing as the
    // pinboard above, keyed by ownerDid.

    @Override
    public Map<String, Object> journalWrite(String content, Map<String, Object> opts) {
        if (study == null) return Map.of("ok", false, "error", "journal not wired on this surface");
        if (content == null || content.isBlank()) return Map.of("ok", false, "error", "blank content");
        var visibility = opts == null ? "shared"
            : String.valueOf(opts.getOrDefault("visibility", "shared"));
        var who = ownerDid == null ? "steward" : ownerDid;
        var id = "private".equals(visibility)
            ? study.writePrivateJournalEntry(who, content)
            : study.writeJournalEntry(who, content);
        return Map.of("ok", true, "id", id, "visibility", visibility,
            "writtenAt", Instant.now().toEpochMilli());
    }

    @Override
    public List<Map<String, Object>> journalRecent(int limit) {
        if (study == null || ownerDid == null) return List.of();
        var results = study.recentJournal(ownerDid, Math.max(1, Math.min(limit, 50)));
        var out = new ArrayList<Map<String, Object>>(results.size());
        for (var r : results) {
            var m = new HashMap<String, Object>();
            m.put("id", r.id());
            m.put("content", r.content());
            var meta = r.metadata();
            if (meta != null && meta.get("timestamp") != null) m.put("ts", meta.get("timestamp"));
            out.add(m);
        }
        return out;
    }

    @Override
    public List<Map<String, Object>> journalSearch(String query, int limit) {
        if (study == null || ownerDid == null) return List.of();
        var results = study.searchJournal(ownerDid, query, Math.max(1, Math.min(limit, 50)));
        var out = new ArrayList<Map<String, Object>>(results.size());
        for (var r : results) {
            var m = new HashMap<String, Object>();
            m.put("id", r.id());
            m.put("content", r.content());
            out.add(m);
        }
        return out;
    }
}
