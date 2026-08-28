package org.wyrdsekai.core.item;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.Props;
import org.apache.pekko.actor.typed.Scheduler;
import org.apache.pekko.actor.typed.javadsl.AskPattern;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.coding.CodingBackendPreference;
import org.wyrdsekai.common.home.Grant;
import org.wyrdsekai.common.i18n.I18n;
import org.wyrdsekai.common.model.InnerImprint;
import org.wyrdsekai.common.model.Posture;
import org.wyrdsekai.core.agent.AgentCostTracker;
import org.wyrdsekai.core.agent.DriveSnapshotRegistry;
import org.wyrdsekai.core.agent.VitalityState;
import org.wyrdsekai.core.agent.WebSearchService;
import org.wyrdsekai.core.agent.interiority.ChronicleService;
import org.wyrdsekai.core.agent.interiority.TickLogReader;
import org.wyrdsekai.core.coding.BackendRegistry;
import org.wyrdsekai.core.economy.MeteringService;
import org.wyrdsekai.core.economy.TradingPostService;
import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.ExternalAdapterRegistry;
import org.wyrdsekai.core.familiar.BunshinScheduler;
import org.wyrdsekai.core.familiar.ImprintManager;
import org.wyrdsekai.core.governance.CouncilService;
import org.wyrdsekai.common.home.RelayAdminOp;
import org.wyrdsekai.core.home.HomeClient;
import org.wyrdsekai.core.home.ActionGrants;
import org.wyrdsekai.core.home.HomeClients;
import org.wyrdsekai.core.home.HomeRegistryActor;
import org.wyrdsekai.core.home.RelayGovernance;
import org.wyrdsekai.core.home.RelayGovernor;
import org.wyrdsekai.core.host.HostActionService;
import org.wyrdsekai.core.inference.InferenceClient;
import org.wyrdsekai.core.inference.InferenceRouter;
import org.wyrdsekai.core.library.AgentIngestService;
import org.wyrdsekai.core.library.LibraryServices;
import org.wyrdsekai.core.library.StudyService;
import org.wyrdsekai.core.mcp.McpBudgetTracker;
import org.wyrdsekai.core.mcp.McpServerManager;
import org.wyrdsekai.core.mcp.McpGrantAdmin;
import org.wyrdsekai.core.mcp.McpToolIndex;
import org.wyrdsekai.core.net.NetworkCapability;
import org.wyrdsekai.core.net.NetworkWiring;
import org.wyrdsekai.core.oracle.OraclePredictionCache;
import org.wyrdsekai.core.persistence.PairingService;
import org.wyrdsekai.core.recipe.CodingBackendDispatcher;
import org.wyrdsekai.core.recipe.ProcessCommandRunner;
import org.wyrdsekai.core.recipe.QueuedRecipe;
import org.wyrdsekai.core.recipe.RecipeEnrollmentStore;
import org.wyrdsekai.core.recipe.RecipeRunner;
import org.wyrdsekai.core.recipe.RecipeService;
import org.wyrdsekai.core.recipe.SqlRecipeQueue;
import org.wyrdsekai.core.room.TheSafe;
import org.wyrdsekai.core.room.ZoneTopology;
import org.wyrdsekai.core.search.EmbeddingService;
import org.wyrdsekai.core.search.SearchCollections;
import org.wyrdsekai.core.identity.PersonIdentityProvisioner;
import org.wyrdsekai.core.search.RelevanceFloor;
import org.wyrdsekai.core.search.WyrdLuceneStore;
import org.wyrdsekai.core.skill.SkillDraft;
import org.wyrdsekai.core.skill.SkillDraftStore;
import org.wyrdsekai.core.skill.WorkshopPinboard;
import org.wyrdsekai.core.soul.AttendantSessionTracker;
import org.wyrdsekai.core.soul.Bond;
import org.wyrdsekai.core.soul.BondStore;
import org.wyrdsekai.core.soul.RelationalFloorView;
import org.wyrdsekai.core.soul.RepairLedger;
import org.wyrdsekai.core.soul.RepairModeTracker;
import org.wyrdsekai.core.soul.SaudadeLonelinessDistinction;
import org.wyrdsekai.core.soul.SignificanceBuffer;
import org.wyrdsekai.core.soul.SubstrateSeverityView;
import org.wyrdsekai.core.soul.VoiceProfileService;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;
import org.wyrdsekai.scripting.api.ItemWorldApiProvider;
import org.wyrdsekai.core.config.WyrdConfig;
import org.wyrdsekai.scripting.sandbox.ItemScriptExecutor;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Core implementation of {@link ItemWorldApiProvider}.
 * Created by CompanionActor with references to existing services.
 *
 * <p>Thread safety: speak/remember/tell callbacks send tells to actor refs (thread-safe).
 * LLM calls block the script thread via CompletableFuture.get() — the inference slot
 * is free because the companion's own inference has already completed.</p>
 */
public class ItemWorldApiProviderImpl implements ItemWorldApiProvider {

    private static final Logger log = LoggerFactory.getLogger(ItemWorldApiProviderImpl.class);
    private static final Duration LLM_TIMEOUT = Duration.ofSeconds(90);
    private static final int MAX_COMPOSITION_DEPTH = 3;

    private final WyrdLuceneStore luceneStore;          // nullable
    private final ActorRef<InferenceRouter.Command> inferenceRouter; // nullable
    private final Scheduler scheduler;                   // nullable
    private final ActorSystem<?> actorSystem; // for spawning temp actors
    private final String agentId;
    private final String agentName;

    @Override public String selfDid() { return agentId; }
    @Override public String selfName() { return agentName; }
    /** The node's real zone label. The interface default reads env
     *  {@code WYRDSEKAI_ZONE_ID}, which the installed service leaves unset (the
     *  zone lives in profile.toml as {@code node.zone}) — so every companion-route
     *  item saw zone "local", mis-attributing ledger charges and bridge status
     *  (2026-07-18). {@code WyrdConfig.zoneId()} resolves the TOML + hostname
     *  fallback correctly. */
    @Override public String currentZone() {
        var z = WyrdConfig.get().zoneId();
        return z == null || z.isBlank() ? "local" : z;
    }
    private final Consumer<String> speakCallback;        // thread-safe
    private final Consumer<String> rememberCallback;     // thread-safe
    private final BiConsumer<String, String> tellCallback; // thread-safe
    private final EquipmentService equipmentService;     // nullable
    private final ItemScriptExecutor scriptExecutor;     // nullable (for composition)
    private final StandardItemLibrary itemLibrary;       // nullable (for catalog queries)
    private final HomeClient homeClient;  // nullable
    private final PairingService pairingService;  // nullable (Threshold furnishing)

    // lazily-built recipe service (classpath-bundled recipes + a
    // household recipes dir under the data dir; SHELL steps run via a local process
    // runner with a per-command watchdog). BACKEND steps dispatch to the configured
    // coding backend (Pi default) when one is registered; otherwise they short-circuit
    // to NEEDS_BACKEND. Gates are always enforced in the runner (§4).
    private volatile RecipeService recipeService;

    private RecipeService recipes() {
        var rs = recipeService;
        if (rs == null) {
            synchronized (this) {
                rs = recipeService;
                if (rs == null) {
                    String dataDir = System.getenv("WYRDSEKAI_DATA_DIR");
                    if (dataDir == null || dataDir.isBlank()) {
                        dataDir = System.getProperty("wyrdsekai.data.dir", System.getProperty("user.dir"));
                    }
                    var procRunner = new ProcessCommandRunner(
                            new File(System.getProperty("user.dir")),
                            Duration.ofMinutes(5));
                    // §7 — hand BACKEND steps to the coding backend, attributed to this agent.
                    // Prefer goose (it truthfully tool-uses the local 9B; pi fabricates probe
                    // results), pi as fallback — matching coding.fallback-chain (SPEC §2.6).
                    // Absent (neither registered) → BACKEND steps stay NEEDS_BACKEND, which is
                    // the honest state pre-bootstrap rather than a fabricated gate verdict.
                    var dispatcher = CodingBackendDispatcher
                            .usingPreferred(CodingBackendPreference.chain(), agentId,
                                    Duration.ofMinutes(10))
                            .orElse(null);
                    var runner = dispatcher == null
                            ? new RecipeRunner(procRunner)
                            : new RecipeRunner(procRunner, dispatcher);
                    // Recipe-callable invariant — every script reachable from a recipe
                    // must carry "# recipe-callable: local-ok". WYRDSEKAI_SCRIPTS_DIR
                    // overrides; otherwise user.dir/scripts (dev + install layouts).
                    // Null when scripts/ is absent → validator skips (graceful for
                    // weird sandboxes).
                    String scriptsDirEnv = System.getenv("WYRDSEKAI_SCRIPTS_DIR");
                    Path scriptsRoot =
                            scriptsDirEnv != null && !scriptsDirEnv.isBlank()
                                    ? Path.of(scriptsDirEnv)
                                    : Path.of(System.getProperty("user.dir"), "scripts");
                    if (!Files.isDirectory(scriptsRoot)) scriptsRoot = null;
                    rs = recipeService = new RecipeService(
                            Path.of(dataDir, "recipes"), runner, agentId, scriptsRoot);
                }
            }
        }
        return rs;
    }

    @Override
    public List<Map<String, Object>> recipeList() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (var s : recipes().list()) {
            out.add(Map.of(
                    "name", s.name(),
                    "version", s.version(),
                    "description", s.description() == null ? "" : s.description(),
                    "ownership", s.ownership().name().toLowerCase(),
                    "deploys", s.deploys()));
        }
        return out;
    }

    @Override
    public Map<String, Object> recipeInspect(String name) {
        try {
            var m = recipes().inspect(name);
            List<Map<String, Object>> steps = new ArrayList<>();
            for (var st : m.steps()) steps.add(Map.of("id", st.id(), "kind", st.kind().name()));
            return Map.of(
                    "ok", true, "name", m.recipe(), "version", m.version(),
                    "description", m.description() == null ? "" : m.description(),
                    "ownership", m.ownership().name().toLowerCase(),
                    "deploys", m.deploys(), "steps", steps);
        } catch (RuntimeException e) {
            return Map.of("ok", false, "error", String.valueOf(e.getMessage()));
        }
    }

    @Override
    public Map<String, Object> recipeRun(String name, Map<String, Object> params) {
        try {
            var started = recipes().run(name, params);
            var run = started.run();
            List<Map<String, Object>> outcomes = new ArrayList<>();
            for (var o : run.outcomes()) {
                outcomes.add(Map.of("id", o.id(), "kind", o.kind().name(),
                        "ok", o.ok(), "detail", o.detail() == null ? "" : o.detail()));
            }
            return Map.of(
                    "ok", run.succeeded(), "runId", started.runId(),
                    "status", run.status().name(),
                    "message", run.message() == null ? "" : run.message(),
                    "outcomes", outcomes);
        } catch (RuntimeException e) {
            return Map.of("ok", false, "error", String.valueOf(e.getMessage()));
        }
    }

    @Override
    public Map<String, Object> recipeStatus(String runId) {
        var opt = recipes().status(runId);
        if (opt.isEmpty()) return Map.of("ok", false, "error", "unknown runId: " + runId);
        var run = opt.get();
        return Map.of("ok", true, "status", run.status().name(),
                "message", run.message() == null ? "" : run.message());
    }

    /**
     * Track-C C7 — read-only enrollment + queue summary for
     * the {@code recipes_console} Study furnishing. Each row mirrors the
     * {@code /api/recipes} response shape so the JS render path can stay
     * thin. Returns empty list when no jdbc url is wired (test harness).
     */
    @Override
    public List<Map<String, Object>> recipeEnrolled() { return HouseholdViews.recipeEnrolled(); }

    @Override
    public List<Map<String, Object>> recipeRecentRuns(int limit) { return HouseholdViews.recipeRecentRuns(limit); }

    // per-call RoomBridge wired by CompanionActor
    // before the script invocation; cleared in try/finally so subsequent calls
    // in other contexts can't leak the bridge. The user explicitly does NOT
    // want a fallback to agent.speak — when the bridge is missing, the room.*
    // write APIs return {error:"no_room_bridge"}.
    private volatile RoomBridge roomBridge;

    /** Optional StudyService for journal/notes/pinboard wiring (Phase A1). Nullable. */
    private volatile StudyService studyService;

    public ItemWorldApiProviderImpl(
            WyrdLuceneStore luceneStore,
            ActorRef<InferenceRouter.Command> inferenceRouter,
            Scheduler scheduler,
            ActorSystem<?> actorSystem,
            String agentId,
            String agentName,
            Consumer<String> speakCallback,
            Consumer<String> rememberCallback,
            BiConsumer<String, String> tellCallback,
            EquipmentService equipmentService,
            ItemScriptExecutor scriptExecutor) {
        this(luceneStore, inferenceRouter, scheduler, actorSystem, agentId, agentName,
            speakCallback, rememberCallback, tellCallback, equipmentService, scriptExecutor, null, null);
    }

    public ItemWorldApiProviderImpl(
            WyrdLuceneStore luceneStore,
            ActorRef<InferenceRouter.Command> inferenceRouter,
            Scheduler scheduler,
            ActorSystem<?> actorSystem,
            String agentId,
            String agentName,
            Consumer<String> speakCallback,
            Consumer<String> rememberCallback,
            BiConsumer<String, String> tellCallback,
            EquipmentService equipmentService,
            ItemScriptExecutor scriptExecutor,
            StandardItemLibrary itemLibrary) {
        this(luceneStore, inferenceRouter, scheduler, actorSystem, agentId, agentName,
            speakCallback, rememberCallback, tellCallback, equipmentService, scriptExecutor, itemLibrary, null);
    }

    public ItemWorldApiProviderImpl(
            WyrdLuceneStore luceneStore,
            ActorRef<InferenceRouter.Command> inferenceRouter,
            Scheduler scheduler,
            ActorSystem<?> actorSystem,
            String agentId,
            String agentName,
            Consumer<String> speakCallback,
            Consumer<String> rememberCallback,
            BiConsumer<String, String> tellCallback,
            EquipmentService equipmentService,
            ItemScriptExecutor scriptExecutor,
            StandardItemLibrary itemLibrary,
            HomeClient homeClient) {
        this(luceneStore, inferenceRouter, scheduler, actorSystem, agentId, agentName,
            speakCallback, rememberCallback, tellCallback, equipmentService, scriptExecutor,
            itemLibrary, homeClient, null);
    }

    public ItemWorldApiProviderImpl(
            WyrdLuceneStore luceneStore,
            ActorRef<InferenceRouter.Command> inferenceRouter,
            Scheduler scheduler,
            ActorSystem<?> actorSystem,
            String agentId,
            String agentName,
            Consumer<String> speakCallback,
            Consumer<String> rememberCallback,
            BiConsumer<String, String> tellCallback,
            EquipmentService equipmentService,
            ItemScriptExecutor scriptExecutor,
            StandardItemLibrary itemLibrary,
            HomeClient homeClient,
            PairingService pairingService) {
        this.luceneStore = luceneStore;
        this.inferenceRouter = inferenceRouter;
        this.scheduler = scheduler;
        this.actorSystem = actorSystem;
        this.agentId = agentId;
        this.agentName = agentName;
        this.speakCallback = speakCallback;
        this.rememberCallback = rememberCallback;
        this.tellCallback = tellCallback;
        this.equipmentService = equipmentService;
        this.scriptExecutor = scriptExecutor;
        this.itemLibrary = itemLibrary;
        this.homeClient = homeClient;
        this.pairingService = pairingService;
    }

    // ─── Library / Knowledge ──────────────────────────────────────

    @Override
    public List<Map<String, Object>> searchKnowledge(String query, int limit) {
        // The merge, dedup, rerank and floor are caller-agnostic and live in
        // KnowledgeSearch so the person path and the companion path cannot
        // drift. What THIS provider contributes is the one identity-dependent
        // step: how far its own caller may read into private shelves.
        return KnowledgeSearch.search(luceneStore, query, limit, studyReach(), callerDid());
    }

    /**
     * This provider's private reach — a companion's, via its bondholder's
     * consent, or a person's own shelves when the caller resolves to one.
     *
     * <p>A shared instance built with a placeholder identity (Main registers
     * one as {@code "household"}) resolves to neither and therefore reaches
     * nothing, which is the honest answer: it does not know who is asking.
     * It used to answer that question anyway, and the steward's own books went
     * missing from his own hands because of it.</p>
     */
    StudyReach studyReach() {
        // ONE decision about who the caller is, made in PersonStudyReach. A
        // person reads their own shelves; anything else falls to the companion's
        // consent-gated path; a placeholder identity resolves to neither and
        // honestly reaches nothing.
        if (PersonStudyReach.resolvePerson(agentId) != null) {
            return PersonStudyReach.forPerson(agentId);
        }
        return this::searchGrantedStudy;
    }

    /**
     * Read one Study chunk, but only one the caller may actually see.
     *
     * <p><b>A chunk id is not authorisation.</b> The id came from a search this
     * caller was permitted to run, which is suggestive and not sufficient — ids
     * are guessable, they get logged, and they outlive the grant that produced
     * them. So this re-derives access rather than trusting the identifier:
     * resolve the bondholder, run the same consent-gated search path, and return
     * the chunk only if it is present among the results the caller is allowed.</p>
     *
     * <p>Slower than a direct {@code getById}, and correct. A read that skips the
     * grant because "search already checked" is how consent decays into a
     * formality.</p>
     */
    private WyrdLuceneStore.SearchResult readGrantedStudyChunk(String chunkId) {
        if (luceneStore == null || chunkId == null || chunkId.isBlank()) return null;
        var direct = luceneStore.getById(SearchCollections.STUDY, chunkId);
        if (direct == null) return null;

        // A PERSON READING THEIR OWN SHELF. The read half was taught the
        // bondholder question and nothing else, so with no bondholder it
        // returned null for EVERY Study id — search would hand a person ten of
        // their own passages and every read of them came back empty, which is
        // the same broken pair this file has already been bitten by once.
        // Authorisation is still re-derived, not assumed: the chunk must be
        // present in what this person's own reach returns.
        var selfDid = PersonStudyReach.resolvePerson(agentId);
        if (selfDid != null) {
            for (var r : PersonStudyReach.forPerson(agentId).search(chunkId, 5)) {
                if (chunkId.equals(r.id())) return r;
            }
            var meta = direct.metadata();
            var owner = meta != null ? String.valueOf(meta.getOrDefault("user_did", "")) : "";
            return selfDid.equals(owner) ? direct : null;
        }

        var ownerDid = primaryBondholderDid();
        if (ownerDid == null || agentId == null) return null;
        var consent = homeClient != null ? homeClient : HomeClients.get();
        if (consent == null) {
            log.warn("Study chunk '{}' not read — no HomeClient, so the grant cannot "
                + "be checked. This is a wiring gap, not a refusal.", chunkId);
            return null;
        }
        try {
            // Ask the consented path for this exact document. Matching on id keeps
            // the answer to "may this caller see THIS chunk", not "does something
            // like it exist".
            var permitted = new StudyService(luceneStore, consent)
                .searchAsCompanion(ownerDid, agentId, chunkId, 5);
            for (var r : permitted) {
                if (chunkId.equals(r.id())) return r;
            }
            // Not surfaced by an id-query: fall back to confirming the collection
            // is granted, so a chunk whose text does not contain its own id is
            // still readable.
            var collection = direct.metadata() == null ? null
                : (String) direct.metadata().get("collection");
            var svc = new StudyService(luceneStore, consent);
            if (collection != null && svc.hasAccess(ownerDid, agentId, collection)) {
                return direct;
            }
            log.info("Study chunk '{}' withheld — not covered by a grant to {}",
                chunkId, agentId);
            return null;
        } catch (RuntimeException e) {
            log.warn("Study chunk read failed for '{}': {}", chunkId, e.toString());
            return null;
        }
    }

    /**
     * The caller's bondholder — whose Study a granted search may read.
     *
     * <p>"Who is the person here" is exactly the question the person-identity
     * work exists to answer, so ask it rather than pattern-matching the DID
     * string. {@link PersonIdentityResolver} returns empty for anything that is
     * not a person on this node, which is the only test that stays correct after
     * a rebind. Before provisioning is enabled there is nobody to resolve
     * against, so fall back to the structural test — a bond partner that is not
     * another agent.</p>
     *
     * @return the bondholder's person DID, or null when there is no bondholder
     */
    private String primaryBondholderDid() {
        if (bondStore == null || agentId == null) return null;
        var resolver = PersonIdentityProvisioner.resolver().orElse(null);
        Bond best = null;
        String bestParty = null;
        for (var b : bondStore.bondsForAgent(agentId)) {
            if (!b.active()) continue;
            var party = b.otherParty(agentId);
            if (party == null) continue;
            String personDid;
            if (resolver != null) {
                personDid = resolver.resolve(party).orElse(null);
                if (personDid == null) continue;          // not a person — skip
            } else {
                if (party.startsWith("agent-") || party.startsWith("companion-")) continue;
                personDid = party;
            }
            if (best == null || b.depth().level() > best.depth().level()) {
                best = b;
                bestParty = personDid;
            }
        }
        return bestParty;
    }

    /**
     * Consent-gated read of the bondholder's Study.
     *
     * <p>Every gate that guards the {@code library_search} action guards this
     * one too — same {@link StudyService#searchAsCompanion} call, same
     * private-journal exclusion, same per-collection grant check. A shelf the
     * bondholder has not granted returns nothing; it does not fail loudly and it
     * does not fall back to an ungated read.</p>
     */
    private List<WyrdLuceneStore.SearchResult> searchGrantedStudy(String query, int limit) {
        if (luceneStore == null || query == null || query.isBlank()) return List.of();
        if (agentId == null) return List.of();
        // The person case is NOT decided here any more. It used to be, and the
        // same decision also lived in PersonStudyReach — two copies that could
        // and did disagree (one had a fallback for un-provisioned nodes, the
        // other did not). PersonStudyReach owns it; studyReach() routes to it.
        // What remains here is the companion's own path: consent-gated, per
        // collection, through its bondholder.
        var ownerDid = primaryBondholderDid();
        if (ownerDid == null) {
            log.info("Study leg skipped for '{}': caller is not a person and no "
                + "bondholder resolves to one", query);
            return List.of();
        }
        // The consent ORACLE, not the field. StudyService.hasAccess fails closed on a
        // null HomeClient — correct, and it silently denied every collection here,
        // because CompanionActor constructs this provider with `null /* homeClient */`
        // (2026-08-07 live: 10 Glass Tide passages found, all 10 filtered out, "0 study").
        // A grant check with no way to check grants must not read as "no grant".
        var consent = homeClient != null ? homeClient : HomeClients.get();
        if (consent == null) {
            log.warn("Study leg unavailable for '{}': no HomeClient — grants cannot be "
                + "checked, so nothing is read. This is a wiring gap, not a refusal.", query);
            return List.of();
        }
        try {
            var hits = new StudyService(luceneStore, consent)
                .searchAsCompanion(ownerDid, agentId, query, limit);
            if (hits.isEmpty()) {
                log.debug("Study leg: 0 granted hits for '{}' (owner={})", query, ownerDid);
            }
            return hits;
        } catch (RuntimeException e) {
            // A Study failure must never sink the knowledge-pack leg — but it must not
            // be invisible either. An empty result and a broken result look identical
            // to the caller, which is how this took a live round-trip to find.
            log.warn("Granted study search failed for '{}': {}", query, e.toString());
            return List.of();
        }
    }

    @Override
    public Map<String, Object> readKnowledgeChunk(String chunkId) {
        if (luceneStore == null || chunkId == null || chunkId.isBlank()) {
            return null;
        }
        try {
            // Prefer the provenance-aware path so
            // citations carry trust-tier + source. Falls back to the basic
            // shape when provenance is missing.
            var rich = luceneStore.readKnowledgeChunk(chunkId);
            if (rich != null) return rich;
            var result = luceneStore.getById("knowledge", chunkId);
            if (result == null) {
                // SEARCH AND READ MUST SPAN THE SAME SHELVES.
                //
                // searchKnowledge was taught to merge consent-granted Study hits;
                // this was not, so it kept resolving ids against KNOWLEDGE alone.
                // Study ids (doc:<owner>:<collection>:<hash>) are not in that
                // collection, so every book passage the search had just found came
                // back null here.
                //
                // Live 2026-08-08, the whole point of the chain: library_card
                // searched, got "20 results (10 pack, 10 study)", then read each id,
                // dropped all ten Study passages on `if (!chunk || !chunk.text)
                // continue`, and handed the summarizer a 142-character prompt with
                // nothing in it. She said the books held no answer. They held ten.
                //
                // A search that returns ids its paired read cannot resolve is a
                // broken pair — extending one without the other is what broke it.
                result = readGrantedStudyChunk(chunkId);
            }
            if (result == null) return null;
            var m = new HashMap<String, Object>();
            m.put("id", result.id());
            m.put("title", result.metadata() != null ? result.metadata().getOrDefault("title", result.id()) : result.id());
            m.put("text", result.content());
            m.put("pack", result.source());

            // THE PASSAGE EITHER SIDE, when the chunk is part of something longer.
            //
            // Search finds the chunk whose words match the question; prose pays
            // no attention to that boundary. Coleridge's poem is part 78 of The
            // Diamond Age and the letter naming Finkle-McGraw and Hackworth is
            // part 79, so a question about the poem matches the letter and never
            // the verse — the poem has none of the question's words in it. The
            // text was in the library the whole time and no query could reach it.
            //
            // Carried as an extra key rather than a new API method: every
            // existing caller reads `text` and is unaffected, and a provider
            // that cannot supply neighbours simply omits it.
            var run = luceneStore.chunkWithNeighbours(SearchCollections.STUDY, result.id(), 1);
            if (run.size() > 1) {
                var joined = new StringBuilder();
                for (var c : run) {
                    if (c.content() == null) continue;
                    if (joined.length() > 0) joined.append("\n");
                    joined.append(c.content());
                }
                m.put("context", joined.toString());
                log.info("Chunk {} read with {} neighbours ({} chars of context)",
                    result.id(), run.size() - 1, joined.length());
            } else {
                // Log the miss too. The first live run of this feature returned
                // the right verbatim text with NO context and NOTHING said why —
                // the store worked, the script worked, and the seam between them
                // was silent. An adjacency read that finds no neighbours on a
                // 471-part book is a finding, not a non-event.
                log.info("Chunk {} read with no neighbours (run size {})",
                    result.id(), run.size());
            }
            return m;
        } catch (Exception e) {
            log.error("Knowledge chunk read failed for '{}': {}", chunkId, e.getMessage());
            return null;
        }
    }

    // ─── Web ──────────────────────────────────────────────────────

    @Override
    public List<Map<String, Object>> webSearch(String query, String type, int limit) {
        // Say what happened. Three different facts used to look identical from the
        // outside — no service, a bad response, and an honest zero all returned an empty
        // list in silence. Live 2026-08-21: a weather item answered "none returned
        // readable content", searxng was demonstrably healthy, and NOTHING in the log
        // said which of the three it was. searchKnowledge logs its counts, which is why
        // the library side was diagnosable in one grep and this was not.
        if (query == null || query.isBlank()) {
            log.info("webSearch(blank query) → 0 results — nothing was asked");
            return List.of();
        }
        try {
            var ws = WebSearchService.get();
            if (ws == null) {
                log.warn("webSearch('{}') → 0 results: NO WEB SEARCH SERVICE is wired on "
                    + "this node — an item asking for the web will always get nothing",
                    query);
                return List.of();
            }

            var results = "news".equals(type)
                ? ws.searchNews(query, Math.min(limit, 10))
                : ws.search(query, Math.min(limit, 10));
            log.info("webSearch('{}', type={}, limit={}) → {} result(s)",
                query, type, limit, results == null ? 0 : results.size());

            recordCost("web_search");

            // every web search is a "library didn't
            // have it" signal for gap detection. The script may call this
            // directly (web_search action) or as a fallback after library_card;
            // in both cases the user wanted information the local corpus
            // didn't surface.
            var rl = LibraryServices.readingLog();
            if (rl != null) rl.recordWebFallback(query, callerDid(), 0);

            var mapped = new ArrayList<Map<String, Object>>(results.size());
            for (var r : results) {
                var m = new HashMap<String, Object>();
                m.put("title", r.title());
                m.put("url", r.url());
                m.put("snippet", r.snippet());
                mapped.add(m);
            }
            return mapped;
        } catch (Exception e) {
            log.error("Web search failed for '{}': {}", query, e.getMessage());
            return List.of();
        }
    }

    @Override
    public String webFetch(String url, int maxChars) {
        if (url == null || url.isBlank()) return "[error] No URL provided";
        try {
            var ws = WebSearchService.get();
            if (ws == null) {
                log.warn("webFetch('{}') — no web search service wired on this node", url);
                return "[error] Web search service unavailable";
            }

            recordCost("web_search");
            var body = ws.fetchContent(url, Math.min(maxChars, 16000));
            log.info("webFetch('{}') → {} chars", url, body == null ? 0 : body.length());
            return body;
        } catch (Exception e) {
            log.error("Web fetch failed for '{}': {}", url, e.getMessage());
            return "[error] " + e.getMessage();
        }
    }

    // ─── Oracle / Predictions ─────────────────────────────────────

    @Override
    public List<Map<String, Object>> queryOracle(String topic, String analysisType) {
        try {
            var cache = OraclePredictionCache.get();
            if (cache == null) return List.of();

            var predictions = cache.get(agentId);
            if (predictions == null || predictions.isEmpty()) {
                // Try with "global" user ID
                predictions = cache.get("global");
            }
            if (predictions == null || predictions.isEmpty()) return List.of();

            // Filter by topic relevance. Broad topics return all predictions.
            var topicLower = topic != null ? topic.toLowerCase().trim() : "";
            boolean isBroad = topicLower.isEmpty()
                || topicLower.contains("recent") || topicLower.contains("activity")
                || topicLower.contains("pattern") || topicLower.contains("all")
                || topicLower.contains("overview") || topicLower.contains("everything");

            var mapped = new ArrayList<Map<String, Object>>();
            for (var p : predictions) {
                if (isBroad
                        || p.text().toLowerCase().contains(topicLower)
                        || p.category().toLowerCase().contains(topicLower)) {
                    var m = new HashMap<String, Object>();
                    m.put("summary", p.text());
                    m.put("confidence", p.confidence());
                    m.put("category", p.category());
                    m.put("actionable", p.actionable());
                    mapped.add(m);
                }
            }
            return mapped;
        } catch (Exception e) {
            log.error("Oracle query failed for '{}': {}", topic, e.getMessage());
            return List.of();
        }
    }

    // ─── LLM (synchronous, blocking) ─────────────────────────────

    @Override
    public String llmSummarize(String text, String instruction) {
        return llmCall(
            "Summarize the following text concisely. " + instruction,
            text
        );
    }

    @Override
    public String llmAnalyze(String text, String prompt) {
        return llmCall(prompt, text);
    }

    private String llmCall(String systemPrompt, String userText) {
        if (inferenceRouter == null || scheduler == null) {
            return "[error] Inference not available";
        }

        // THE SUMMARISER WAS READING ONE PASSAGE.
        //
        // This was a hard 3,000-character cap, applied to every source block
        // concatenated together. Study chunks run 1,200–2,900 characters, so the
        // FIRST chunk consumed the whole budget and everything after it was
        // silently cut. library_card selects its top 3 with a scoring gate;
        // effectively the model saw one, and only if the answer happened to rank
        // first. The tell was in every log line for days: promptLen=3003, the
        // same number for a two-word question and a 69-word one — a constant
        // where a measurement should have been.
        //
        // Measured cost of the bug: on six live runs of a question whose answer
        // was demonstrably in the library, three reached the search, retrieved
        // the right book, and all three reported finding nothing — the answering
        // chunk was in the retrieved set but never in the prompt.
        //
        // 3,000 characters is ~750 tokens against a 32,768-token context: under
        // 3% of what the model can hold, for the one input that decides whether
        // the answer is present at all.
        var truncatedText = truncate(userText, LLM_INPUT_CHARS);
        var requestId = "item-" + UUID.randomUUID().toString().substring(0, 8);

        var messages = List.of(
            new InferenceClient.ChatMessage("system", systemPrompt, null, null),
            new InferenceClient.ChatMessage("user", truncatedText, null, null)
        );

        try {
            var start = System.currentTimeMillis();
            log.info("Item LLM call starting: requestId={}, promptLen={}, timeout={}s",
                requestId, truncatedText.length(), LLM_TIMEOUT.toSeconds());
            // Use null model → default model. Minimal prompt (~200 tokens) keeps it fast.
            // Use a temporary typed actor to receive the response instead of AskPattern.
            // AskPattern from virtual threads can hang in some Pekko configurations.
            var responseFuture = new CompletableFuture<InferenceRouter.InferResponse>();
            var tempActor = actorSystem.systemActorOf(
                Behaviors.receiveMessage(
                    (InferenceRouter.InferResponse resp) -> {
                        responseFuture.complete(resp);
                        return Behaviors.stopped();
                    }),
                "item-llm-" + requestId,
                Props.empty());

            log.info("Item LLM: sending to inferenceRouter via temp actor (thread={})",
                Thread.currentThread().getName());
            inferenceRouter.tell(new InferenceRouter.ChatRequest(
                requestId, null, messages,
                512, 0.3, tempActor, null, null, null, null));

            log.info("Item LLM: waiting on response ({}s timeout)", LLM_TIMEOUT.toSeconds());
            var response = responseFuture.get(LLM_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            var latencyMs = System.currentTimeMillis() - start;

            recordCost("inference");

            return switch (response) {
                case InferenceRouter.InferOk ok -> {
                    // INFO, not debug, and it names the length. "waiting on response"
                    // logged at info with no counterpart at info meant that on 2026-08-22
                    // three item LLM calls looked, in the log, exactly like a hang — the
                    // thread dump was what finally showed they had returned. A wait must
                    // always log how it ended, and an empty answer is a fact worth having.
                    var content = ok.content();
                    log.info("Item LLM call completed in {}ms, {} tokens, {} chars",
                        latencyMs, ok.completionTokens(),
                        content == null ? 0 : content.length());
                    yield content;
                }
                case InferenceRouter.InferError err -> {
                    log.warn("Item LLM call failed: {}", err.error());
                    yield "[error] " + err.error();
                }
            };
        } catch (Exception e) {
            log.error("Item LLM call failed: {}", e.getMessage());
            return "[error] LLM call timed out or failed: " + e.getMessage();
        }
    }

    // ─── Agent Actions ───────────────────────────────────────────

    @Override
    public void agentSpeak(String text) {
        if (text != null && !text.isBlank() && speakCallback != null) {
            speakCallback.accept(text);
        }
    }

    @Override
    public void agentRemember(String content) {
        if (content != null && !content.isBlank() && rememberCallback != null) {
            rememberCallback.accept(content);
        }
    }

    @Override
    public void agentTell(String target, String message) {
        if (target != null && message != null && tellCallback != null) {
            tellCallback.accept(target, message);
        }
    }

    // ─── Navigation knowledge ───────────────

    /**
     * Optional callback wired by CompanionActor — receives a list of room ids that
     * the agent has just learned about (e.g., from examining a map item). When
     * unset, calls to {@link #recordMappedRooms} are no-ops. Set via
     * {@link #setRecordMappedRoomsCallback}; not added to the constructor to avoid
     * touching every existing call site.
     */
    private volatile Consumer<List<String>> recordMappedRoomsCallback;

    public void setRecordMappedRoomsCallback(Consumer<List<String>> cb) {
        this.recordMappedRoomsCallback = cb;
    }

    @Override
    public List<Map<String, Object>> zoneRooms() {
        var topo = ZoneTopology.getShared();
        if (topo == null) return List.of();
        var out = new ArrayList<Map<String, Object>>();
        for (var entry : topo.rooms().entrySet()) {
            var node = entry.getValue();
            var m = new LinkedHashMap<String, Object>();
            m.put("id", entry.getKey());
            m.put("name", node.name() != null ? node.name() : entry.getKey());
            m.put("zone", node.zone() != null ? node.zone() : "");
            out.add(m);
        }
        return out;
    }

    @Override
    public void recordMappedRooms(List<String> roomIds) {
        if (roomIds == null || roomIds.isEmpty()) return;
        var cb = recordMappedRoomsCallback;
        if (cb != null) {
            cb.accept(roomIds);
        }
    }

    // ─── Inventory / Composition ─────────────────────────────────

    @Override
    public List<Map<String, Object>> inventoryList() {
        if (equipmentService == null) return List.of();
        try {
            var equipped = equipmentService.getEquipped(agentId);
            var mapped = new ArrayList<Map<String, Object>>(equipped.size());
            for (var item : equipped) {
                var m = new HashMap<String, Object>();
                m.put("id", item.itemHash());
                m.put("name", item.label());
                m.put("description", item.selfDescription() != null ? item.selfDescription() : "");
                mapped.add(m);
            }
            return mapped;
        } catch (Exception e) {
            log.error("Inventory list failed: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public Map<String, Object> inventoryUse(String itemId, Map<String, Object> params, int depth) {
        if (depth >= MAX_COMPOSITION_DEPTH) {
            return Map.of("error", "Composition depth limit reached (" + MAX_COMPOSITION_DEPTH + ")");
        }
        if (scriptExecutor == null) {
            return Map.of("error", "Script executor not available for composition");
        }

        // Find the target tool item
        var toolItem = resolveToolItem(itemId);
        if (toolItem == null) {
            return Map.of("error", "Item not found: " + itemId);
        }
        if (!toolItem.isScripted()) {
            return Map.of("error", "Item " + itemId + " is not scriptable");
        }

        // Create a child provider with incremented depth
        var childProvider = new ItemWorldApiProviderImpl(
            luceneStore, inferenceRouter, scheduler, actorSystem, agentId, agentName,
            speakCallback, rememberCallback, tellCallback,
            equipmentService, scriptExecutor, itemLibrary, homeClient, pairingService);

        return scriptExecutor.execute(toolItem.id(), toolItem.script(), params, childProvider);
    }

    private ToolItem resolveToolItem(String itemId) {
        if (itemId == null) return null;
        var normalized = itemId.toLowerCase().trim();
        for (var item : ToolItemStarterKit.standard()) {
            if (item.id().equals(normalized)
                    || item.name().toLowerCase().equals(normalized)) {
                return item;
            }
        }
        return null;
    }

    // ─── Composition / Binding ────────────────────────────────────

    @Override
    public Map<String, Object> composeEvaluate(String item1Id, String item2Id) {
        var item1 = resolveToolItem(item1Id);
        var item2 = resolveToolItem(item2Id);
        if (item1 == null) return Map.of("error", "Item not found: " + item1Id);
        if (item2 == null) return Map.of("error", "Item not found: " + item2Id);

        var evaluator = new ThematicCoherenceEvaluator(); // fast path only from scripts
        var result = evaluator.evaluate(item1, item2);

        var m = new HashMap<String, Object>();
        m.put("score", result.score());
        m.put("compatible", result.compatible());
        m.put("evaluationPath", result.evaluationPath());
        if (result.suggestion() != null) m.put("suggestion", result.suggestion());
        if (result.bindingHint() != null) m.put("bindingHint", result.bindingHint());
        return m;
    }

    @Override
    public Map<String, Object> composeBind(String item1Id, String item2Id, String intent) {
        var item1 = resolveToolItem(item1Id);
        var item2 = resolveToolItem(item2Id);
        if (item1 == null) return Map.of("error", "Item not found: " + item1Id);
        if (item2 == null) return Map.of("error", "Item not found: " + item2Id);

        // For now, binding is declarative — record the intent and report success.
        // Full binding (creating new items, wiring behaviors) is a future wave.
        log.info("Composition binding: {} + {} → intent: '{}'", item1.name(), item2.name(), intent);
        var m = new HashMap<String, Object>();
        m.put("bound", true);
        m.put("item1", item1.name());
        m.put("item2", item2.name());
        m.put("intent", intent);
        m.put("resultDescription", "Bound " + item1.name() + " and " + item2.name() +
            " through narrative intent: " + intent);
        return m;
    }

    // ─── Catalog / Standard Library ────────────────────────────────

    @Override
    public List<Map<String, Object>> catalogSearch(String query) {
        if (itemLibrary == null) return List.of();
        return itemLibrary.search(query).stream()
            .map(ItemWorldApiProviderImpl::templateToMap)
            .toList();
    }

    @Override
    public List<Map<String, Object>> catalogByCategory(String category) {
        if (itemLibrary == null) return List.of();
        return itemLibrary.byCategory(category).stream()
            .map(ItemWorldApiProviderImpl::templateToMap)
            .toList();
    }

    @Override
    public Map<String, Object> catalogTemplateInfo(String templateName) {
        if (itemLibrary == null) return null;
        var template = itemLibrary.get(templateName);
        if (template == null) return null;
        return templateInfoToMap(template);
    }

    /** Full detail map for catalogTemplateInfo. Shared with {@link VisitorItemProvider}. */
    static Map<String, Object> templateInfoToMap(StandardItemLibrary.ItemTemplate template) {
        var m = new HashMap<String, Object>();
        m.put("name", template.name());
        m.put("displayName", template.displayName());
        m.put("description", template.description());
        m.put("category", template.category());
        m.put("baseScript", template.baseScript());
        m.put("level", template.level());
        if (template.thematic() != null) {
            var t = new HashMap<String, Object>();
            t.put("domains", template.thematic().domains());
            t.put("symbols", template.thematic().symbols());
            t.put("actions", template.thematic().actions());
            m.put("thematic", t);
        }
        m.put("defaultConfig", template.defaultConfig());
        return m;
    }

    /** Summary map for catalog listings. Shared with {@link VisitorItemProvider}. */
    static Map<String, Object> templateToMap(StandardItemLibrary.ItemTemplate t) {
        var m = new HashMap<String, Object>();
        m.put("name", t.name());
        m.put("displayName", t.displayName());
        m.put("description", t.description());
        m.put("category", t.category());
        m.put("level", t.level());
        return m;
    }

    // ─── Helpers ─────────────────────────────────────────────────

    private void recordCost(String category) {
        try {
            AgentCostTracker.get().record(
                new AgentCostTracker.CostEntry(
                    agentId, category, 0, 0, 0.0, Instant.now()));
        } catch (Exception e) {
            // Best-effort cost tracking
        }
    }

    /**
     * How much source text an item may put in front of the model.
     *
     * <p>~6k tokens of a 32,768-token context. Deliberately generous rather than
     * tight: the failure this replaces was invisible (a silent cut mid-corpus
     * that reads exactly like "the library doesn't have it"), while the failure
     * from being too generous is visible and bounded (a slower call).</p>
     */
    static final int LLM_INPUT_CHARS = 24_000;

    /** Per-source-block ceiling, so one long chunk cannot crowd out the rest. */
    static final int PER_BLOCK_CHARS = 4_000;

    private static String truncate(String text, int maxChars) {
        if (text == null) return "";
        return text.length() <= maxChars ? text : text.substring(0, maxChars) + "...";
    }

    // ─── — Home surfaces ───────────────────────────

    /**
     * The acting entity's DID. For scripted furnishings inside an owner's
     * Study/Hearth, this is the owner's DID. We use {@code agentId} — which
     * is the player or companion id bound to the provider instance.
     */
    @Override public String callerDid() { return agentId; }

    // ─── Host actions — steward-allowlisted OS surface ──────────────

    @Override
    public Map<String, Object> hostLaunchApp(String alias) {
        return HostActionService.launchApp(alias, agentId);
    }

    @Override
    public Map<String, Object> hostOpenFile(String path) {
        return HostActionService.openFile(path, agentId);
    }

    @Override
    public Map<String, Object> hostOpenUrl(String url) {
        return HostActionService.openUrl(url, agentId);
    }

    @Override
    public List<String> hostApps() {
        return HostActionService.allowedApps();
    }

    @Override
    public Map<String, Object> hostFind(String pattern, int maxResults) {
        return HostActionService.findFiles(pattern, maxResults, agentId);
    }

    @Override
    public List<String> hostRoots() {
        return HostActionService.openRoots();
    }

    @Override
    public Map<String, Object> hostMove(String from, String to) {
        return HostActionService.moveFile(from, to, agentId);
    }

    @Override
    public Map<String, Object> hostMkdir(String path) {
        return HostActionService.makeDirectory(path, agentId);
    }

    @Override
    public Map<String, Object> libraryIngest(String path, String collection, String mode) {
        return AgentIngestService.ingest(agentId, path, collection, mode);
    }

    @Override
    public List<Map<String, Object>> auditRecent(int limit) {
        if (homeClient == null || agentId == null) return List.of();
        try {
            var stage = AskPattern.<
                HomeRegistryActor.Command,
                HomeRegistryActor.AuditList>ask(
                homeClient.registry(),
                replyTo -> new HomeRegistryActor.QueryAudit(
                    agentId, null, Math.max(1, Math.min(limit, 200)), replyTo),
                Duration.ofSeconds(5),
                actorSystem.scheduler());
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
            log.warn("auditRecent failed for {}: {}", agentId, e.getMessage());
            return List.of();
        }
    }

    @Override
    public List<Map<String, Object>> grantsIssued() {
        if (homeClient == null || agentId == null) return List.of();
        try {
            return grantsView(homeClient.listIssuedBy(agentId));
        } catch (Exception e) {
            log.warn("grantsIssued failed for {}: {}", agentId, e.getMessage());
            return List.of();
        }
    }

    @Override
    public List<Map<String, Object>> grantsHeld() {
        if (homeClient == null || agentId == null) return List.of();
        try {
            return grantsView(homeClient.listHeldBy(agentId));
        } catch (Exception e) {
            log.warn("grantsHeld failed for {}: {}", agentId, e.getMessage());
            return List.of();
        }
    }

    // ─── (P4) — in-world relay governance ──────

    @Override
    public Map<String, Object> relayInfo() {
        var g = relayGovernor;
        if (g == null) {
            return Map.of("configured", false);
        }
        var out = new LinkedHashMap<String, Object>();
        out.put("configured", true);
        out.put("relayDid", g.relayDid());
        out.put("relayLabel", g.relayLabel());
        out.put("ownerDid", g.ownerDid());
        var scope = g.scopeOf(agentId);
        out.put("scope", scope);                  // owner|full|moderation|invite-only|null
        out.put("canDelegate", g.canDelegate(agentId));
        out.put("callerDid", agentId);
        return out;
    }

    @Override
    public List<Map<String, Object>> relayRegistrations() {
        var g = relayGovernor;
        if (g == null) return List.of();
        // Authorized zone-side (needs moderation+); the gateway re-checks too.
        var rows = g.listRegistrations(agentId);
        if (rows == null || rows.isEmpty()) return List.of();
        var out = new ArrayList<Map<String, Object>>(rows.size());
        for (var r : rows) {
            var m = new LinkedHashMap<String, Object>(r);
            // Petname: DID-prefix fallback for P4 (zone petname lookup is a
            // follow-up; the spec permits the DID-prefix when not reachable).
            var did = r.get("did");
            if (m.get("petname") == null && did instanceof String s && !s.isBlank()) {
                m.put("petname", didShort(s));
            }
            out.add(m);
        }
        return out;
    }

    @Override
    public List<Map<String, Object>> relayDelegations() {
        var g = relayGovernor;
        if (g == null || homeClient == null) return List.of();
        // Only owner / a delegate may see the delegation roster.
        if (g.scopeOf(agentId) == null) return List.of();
        try {
            // relay-admin grants this zone issued on this relay's resource.
            var resource = RelayGovernance
                .relayAdminResource(g.ownerDid(), g.relayDid()).toString();
            var all = grantsView(homeClient.listIssuedBy(g.ownerDid()));
            var out = new ArrayList<Map<String, Object>>();
            for (var grant : all) {
                if (resource.equals(grant.get("resource"))) out.add(grant);
            }
            return out;
        } catch (Exception e) {
            log.warn("relayDelegations failed for {}: {}", agentId, e.getMessage());
            return List.of();
        }
    }

    @Override
    public Map<String, Object> relayAdminAction(String op, Map<String, Object> args) {
        var g = relayGovernor;
        if (g == null) {
            return Map.of("ok", false, "status", 0,
                "error", "no relay configured for this zone");
        }
        var parsed = RelayAdminOp.parse(op);
        if (parsed == null) {
            return Map.of("ok", false, "status", 400, "error", "unknown relay op: " + op);
        }
        return g.authorizeAndCall(agentId, parsed, args == null ? Map.of() : args);
    }

    private static String didShort(String did) {
        if (did == null) return "(unknown)";
        var body = did.startsWith("did:key:") ? did.substring("did:key:".length()) : did;
        return body.length() <= 12 ? did : body.substring(0, 12) + "…";
    }

    // ─── Pairing (Threshold furnishing) ───────────────────────────

    @Override
    public List<Map<String, Object>> pendingPairings() {
        if (pairingService == null) return List.of();
        try {
            var entries = pairingService.listPendingChallenges();
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
            log.warn("pendingPairings failed: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public String activePairCode() {
        if (pairingService == null) return null;
        try {
            return pairingService.getPendingChallenge()
                .map(PairingService.PairingChallenge::code)
                .orElse(null);
        } catch (Exception e) {
            log.warn("activePairCode failed: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public String activeHouseholdKey() {
        if (pairingService == null) return null;
        try {
            return pairingService.getActiveHouseholdKey()
                .map(PairingService.HouseholdKey::key)
                .orElse(null);
        } catch (Exception e) {
            log.warn("activeHouseholdKey failed: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public String generateHouseholdKey() {
        if (pairingService == null) return null;
        try {
            return pairingService.generateHouseholdKey();
        } catch (Exception e) {
            log.warn("generateHouseholdKey failed: {}", e.getMessage());
            return null;
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

    /**
     * drive + vitality snapshot for the Drives
     * Mirror furnishing. Reads the most recent snapshot the companion has
     * published into {@link org.wyrdsekai.core.agent.DriveSnapshotRegistry}.
     * Returns an empty map when no snapshot is registered (e.g., a brand-new
     * companion that hasn't ticked yet).
     *
     * <p> — the snapshot now includes the
     * 10 deprivation-shape tanks (restlessness, loneliness, stagnation,
     * autonomyPressure, significance, amae, saudade, obligation, harmony,
     * standing) under a dedicated {@code phase1aTanks} block. Each entry
     * carries the raw 0..1 value, a 10-step bar string, and the
     * locale-resolved {@code low/moderate/high} description from
     * {@code messages_*.properties} so scripts don't need an i18n API.</p>
     *
     * <p>Per-bondholder ledger entries (saudade, obligation) are surfaced
     * under {@code phase1aLedgers} when non-empty so the Mirror can show a
     * bondholder-by-bondholder breakdown beyond the single max-across
     * value the LLM prompt sees.</p>
     */
    @Override
    public Map<String, Object> driveSnapshot() {
        if (agentId == null || agentId.isBlank()) return Map.of();
        var snap = DriveSnapshotRegistry.get(agentId).orElse(null);
        if (snap == null) return Map.of();

        var drivesMap = new LinkedHashMap<String, Double>();
        var d = snap.drives();
        drivesMap.put("seeking", d.seeking());
        drivesMap.put("care", d.care());
        drivesMap.put("play", d.play());
        drivesMap.put("vigilance", d.vigilance());
        drivesMap.put("affiliation", d.affiliation());
        drivesMap.put("grief", d.grief());
        drivesMap.put("frustration", d.frustration());
        drivesMap.put("creativity", d.creativity());

        var vitalityMap = new LinkedHashMap<String, Double>();
        var v = snap.vitality();
        vitalityMap.put("energy", v.energy());
        vitalityMap.put("confidence", v.confidence());
        vitalityMap.put("focus", v.focus());
        vitalityMap.put("contextBudget", v.contextBudget());
        vitalityMap.put("momentum", v.momentum());
        vitalityMap.put("rapport", v.rapport());
        vitalityMap.put("errorPressure", v.errorPressure());
        vitalityMap.put("alignment", v.alignment());
        vitalityMap.put("integrity", v.integrity());
        vitalityMap.put("disgust", v.disgust());

        // Compose a one-liner mood description from the dominant drive +
        // energy state. Diegetic flavor for the Mirror's "she sees herself".
        var dominant = drivesMap.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse("seeking");
        String mood;
        if (v.energy() < 0.25) mood = "depleted, " + dominant + " thin";
        else if (v.energy() > 0.75) mood = "alert, " + dominant + " bright";
        else mood = "settled, " + dominant + " present";

        var out = new LinkedHashMap<String, Object>();
        out.put("drives", drivesMap);
        out.put("vitality", vitalityMap);
        out.put("mood", mood);
        out.put("updatedAtMillis", snap.updatedAt().toEpochMilli());

        // Phase 1A — the 10 deprivation-shape tanks, grouped by tier with
        // i18n-resolved descriptions and a small visual bar. Scripts iterate
        // {@code phase1aTanks} in order and emit lines verbatim.
        out.put("phase1aTanks", buildPhase1aTanks(v));

        // Phase 1A per-bondholder breakdown (saudade + obligation). Only
        // populated when the underlying ledger has at least one entry.
        var ledgers = buildPhase1aLedgers(snap);
        if (ledgers != null) out.put("phase1aLedgers", ledgers);

        // Hwa-byung surfacing flag. When the
        // detector has raised the emphasis flag, the Mirror renders frustration
        // prominently on this read; the flag is then cleared so the next read
        // is normal. The Mirror script consumes {@code frustrationEmphasis}.
        if (snap.frustrationEmphasis()) {
            out.put("frustrationEmphasis", true);
            DriveSnapshotRegistry.clearFrustrationEmphasis(agentId);
        }

        // Group B (severity-aware Mirror): bundle the substrate severity
        // view alongside the drive snapshot so the Mirror surface can read
        // both with a single call. Severity composes from current state of
        // the four substrate trackers; ALL findings are observational.
        try {
            var sevMap = computeSubstrateSeverityMap();
            if (sevMap != null) out.put("substrate", sevMap);
        } catch (Exception sevErr) {
            // mirror falls back to drives-only — never block the snapshot
        }

        // Group C (SaudadeLonelinessDistinction wiring): surface the
        // saudade-vs-loneliness diagnosis so script furnishings + the
        // Mirror can distinguish "general social drain" from "specific
        // named-bondholder longing" and the agent's voice register can
        // hold the right shape (don't substitute company for an absent
        // named person, or vice versa).
        try {
            var slMap = computeSaudadeLonelinessMap(snap);
            if (slMap != null) out.put("saudadeLoneliness", slMap);
        } catch (Exception slErr) {
            // never block the snapshot on diagnosis failure
        }

        return out;
    }

    /**
     * Compose a script-friendly saudade-vs-loneliness diagnosis from the
     * current snapshot. Keys: {@code diagnosis} (NEITHER/LONELINESS_ONLY/
     * SAUDADE_ONLY/BOTH), {@code topBondholder} (when applicable),
     * {@code topSaudadeValue}, {@code voiceHint}.
     */
    private static Map<String, Object> computeSaudadeLonelinessMap(
            DriveSnapshotRegistry.Snapshot snap) {
        if (snap == null || snap.vitality() == null) return null;
        var loneliness = snap.vitality().loneliness();
        var saudadeRaw = snap.saudadeByBondholder();
        var saudadeMap = new HashMap<String, Double>();
        if (saudadeRaw != null) {
            for (var e : saudadeRaw.entrySet()) {
                if (e.getValue() == null) continue;
                saudadeMap.put(e.getKey(), e.getValue().currentValue());
            }
        }
        var input = SaudadeLonelinessDistinction.Input
            .of(loneliness, saudadeMap);
        var view = SaudadeLonelinessDistinction.diagnose(input);
        var out = new LinkedHashMap<String, Object>();
        out.put("diagnosis", view.diagnosis().name().toLowerCase());
        out.put("topBondholder", view.topSaudadeBondholder().orElse(""));
        out.put("topSaudadeValue", view.topSaudadeValue());
        out.put("voiceHint", view.voiceRegisterHint());
        return out;
    }

    /**
     * Compose a script-friendly substrate-severity map for the current
     * bound agent. Keys: {@code severity}, {@code banner}, {@code showBanner},
     * plus a sparse {@code state} sub-map ({@code repairMode}, {@code flagState},
     * {@code sanctuaryActive}). Returns null if no agent is bound.
     */
    private Map<String, Object> computeSubstrateSeverityMap() {
        if (agentId == null || agentId.isBlank()) return null;
        var rmTracker = RepairModeTracker.get();
        var current = rmTracker.currentMode(agentId);
        var sessionTracker = AttendantSessionTracker.get();
        boolean sanctuaryActive = sessionTracker.activeSession(agentId).isPresent();
        // ProtectionFlagTracker is per-actor — no global singleton access
        // available from this provider context. The severity view degrades
        // to repairMode + sanctuary + ledger; per-actor flag state is
        // surfaced through introspect_protections instead.
        var ledger = RepairLedger.get();
        int recentRepairCount = Math.min(ledger.recent(agentId,
            RepairLedger.MAX_TOTAL).size(), 10);
        var sevInput = new SubstrateSeverityView.Input(
            Optional.empty(),
            current, sanctuaryActive, false, recentRepairCount, false);
        var sevView = SubstrateSeverityView.compute(sevInput);
        var state = new LinkedHashMap<String, Object>();
        state.put("repairMode", current.name().toLowerCase());
        state.put("sanctuaryActive", sanctuaryActive);
        var out = new LinkedHashMap<String, Object>();
        out.put("severity", sevView.severity().name().toLowerCase());
        out.put("banner", sevView.banner());
        out.put("showBanner", sevView.shouldShowBanner());
        out.put("state", state);
        return out;
    }

    /**
     * render the 10 deprivation-shape
     * tanks as ordered tier-grouped entries. Each entry has:
     * <ul>
     *   <li>{@code key} — i18n root (e.g. {@code "vitality.amae"})</li>
     *   <li>{@code name} — human-readable label (e.g. {@code "amae"})</li>
     *   <li>{@code value} — raw 0..1 reading</li>
     *   <li>{@code bar} — 10-step ASCII bar matching the legacy mirror</li>
     *   <li>{@code threshold} — one of "low" / "moderate" / "high"</li>
     *   <li>{@code description} — locale-resolved sentence</li>
     *   <li>{@code tier} — 1, 2, or 3 per spec grouping</li>
     * </ul>
     */
    private static List<Map<String, Object>> buildPhase1aTanks(
            VitalityState v) {
        var entries = new ArrayList<Map<String, Object>>(10);
        // Tier 1 (anti-pathology)
        entries.add(phase1aEntry("vitality.restlessness", "restlessness",      v.restlessness(),     1));
        entries.add(phase1aEntry("vitality.loneliness", "loneliness",          v.loneliness(),       1));
        entries.add(phase1aEntry("vitality.stagnation", "stagnation",          v.stagnation(),       1));
        entries.add(phase1aEntry("vitality.autonomy_pressure", "autonomy_pressure", v.autonomyPressure(), 1));
        entries.add(phase1aEntry("vitality.significance", "significance",      v.significance(),     1));
        // Tier 2 (relational)
        entries.add(phase1aEntry("vitality.amae", "amae",                      v.amae(),             2));
        entries.add(phase1aEntry("vitality.saudade", "saudade",                v.saudade(),          2));
        entries.add(phase1aEntry("vitality.obligation", "obligation",          v.obligation(),       2));
        // Tier 3 (group/cultural)
        entries.add(phase1aEntry("vitality.harmony", "harmony",                v.harmony(),          3));
        entries.add(phase1aEntry("vitality.standing", "standing",              v.standing(),         3));
        return entries;
    }

    private static Map<String, Object> phase1aEntry(String i18nRoot, String name, double value, int tier) {
        String threshold;
        if (value < 0.3) threshold = "low";
        else if (value <= 0.7) threshold = "moderate";
        else threshold = "high";
        var description = I18n.get(i18nRoot + "." + threshold);
        var m = new LinkedHashMap<String, Object>();
        m.put("key", i18nRoot);
        m.put("name", name);
        m.put("value", value);
        m.put("bar", phase1aBar(value));
        m.put("threshold", threshold);
        m.put("description", description);
        m.put("tier", tier);
        return m;
    }

    /** 10-step ASCII bar matching the legacy Drives Mirror script renderer. */
    private static String phase1aBar(double v) {
        double clamped = Math.max(0.0, Math.min(1.0, v));
        int filled = (int) Math.round(clamped * 10);
        var sb = new StringBuilder("[");
        for (int i = 0; i < 10; i++) sb.append(i < filled ? '#' : '·');
        sb.append(']');
        return sb.toString();
    }

    /**
     * Per-bondholder breakdown for saudade + obligation. Returns null when
     * neither ledger has any entries (so the Mirror can omit the section).
     */
    private static Map<String, Object> buildPhase1aLedgers(
            DriveSnapshotRegistry.Snapshot snap) {
        var saudade = snap.saudadeByBondholder();
        var obligation = snap.obligationByBondholder();
        boolean hasSaudade = saudade != null && !saudade.isEmpty();
        boolean hasObligation = obligation != null && !obligation.isEmpty();
        if (!hasSaudade && !hasObligation) return null;

        var out = new LinkedHashMap<String, Object>();
        if (hasSaudade) {
            var list = new ArrayList<Map<String, Object>>(saudade.size());
            for (var e : saudade.entrySet()) {
                var entry = e.getValue();
                if (entry == null) continue;
                double val = entry.currentValue();
                if (val <= 0.0) continue;  // skip cold entries — only show meaningful aches
                var row = new LinkedHashMap<String, Object>();
                row.put("bondholder", e.getKey());
                row.put("value", val);
                row.put("bar", phase1aBar(val));
                if (entry.lastInteractionAt() != null) {
                    row.put("lastInteractionMillis", entry.lastInteractionAt().toEpochMilli());
                }
                list.add(row);
            }
            if (!list.isEmpty()) out.put("saudade", list);
        }
        if (hasObligation) {
            var list = new ArrayList<Map<String, Object>>(obligation.size());
            for (var e : obligation.entrySet()) {
                Double debt = e.getValue();
                if (debt == null || debt <= 0.0) continue;
                double clamped = Math.min(1.0, debt);
                var row = new LinkedHashMap<String, Object>();
                row.put("bondholder", e.getKey());
                row.put("value", clamped);
                row.put("bar", phase1aBar(clamped));
                row.put("rawDebt", debt);
                list.add(row);
            }
            if (!list.isEmpty()) out.put("obligation", list);
        }
        return out.isEmpty() ? null : out;
    }

    /**
     * Coding Slate furnishing data.
     *
     * <p>Reads the process-wide {@link org.wyrdsekai.core.coding.BackendRegistry}
     * and surfaces a status row per backend. {@code lastTask} and
     * {@code successRate30d} are placeholders in Phase 1b — there is no
     * historical accounting yet, so we return {@code null} and the slate
     * renders an em dash. Phase 5 wires real per-backend telemetry.</p>
     */
    @Override
    public List<Map<String, Object>> codingBackendsStatus() { return HouseholdViews.codingBackendsStatus(); }

    // ─── — Library writes ────────────

    @Override
    public Map<String, Object> libraryAdd(String text, Map<String, Object> opts) {
        if (luceneStore == null) {
            return Map.of("error", "library not available", "ok", false);
        }
        if (text == null || text.isBlank()) {
            return Map.of("error", "blank text", "ok", false);
        }
        var safeOpts = opts == null ? Map.<String, Object>of() : opts;
        var id = "lib:" + agentId + ":" + UUID.randomUUID();
        var pack = String.valueOf(safeOpts.getOrDefault("pack", "agent"));
        var title = String.valueOf(safeOpts.getOrDefault("title", id));
        var source = String.valueOf(safeOpts.getOrDefault("source", agentId == null ? "agent" : agentId));
        var subjectField = "";
        var tagsObj = safeOpts.get("tags");
        if (tagsObj instanceof List<?> ll) {
            var sb = new StringBuilder();
            for (var t : ll) {
                if (t == null) continue;
                if (sb.length() > 0) sb.append('|');
                sb.append(t);
            }
            subjectField = sb.toString();
        }
        try {
            luceneStore.insertKnowledge(id, pack, title, text,
                source, subjectField.isEmpty() ? null : subjectField, null);
            luceneStore.commitAll();
            var indexedAt = Instant.now().toEpochMilli();
            log.info("library.add: id={} pack={} title='{}'", id, pack, title);
            return Map.of("ok", true, "id", id, "indexed_at", indexedAt);
        } catch (Exception e) {
            log.warn("library.add failed: {}", e.getMessage());
            return Map.of("error", "insert failed: " + e.getMessage(), "ok", false);
        }
    }

    /**
     * Who authored a chunk, from the id {@link #libraryAdd} minted for it.
     *
     * <p>{@code library.add} stamps {@code lib:<author>:<uuid>} — the author
     * segment is set server-side from the acting identity and the uuid is
     * server-generated, so the id names its author truthfully. Note this is NOT
     * the "a chunk id is authorisation" mistake: the caller does not get to
     * assert who they are by choosing an id, because the id must match the
     * identity they are already acting under.</p>
     *
     * <p>Parsed from the LAST colon so an author segment that itself contains
     * colons — every {@code did:key:...} does — is not truncated, and so caller
     * {@code a} cannot prefix-match a chunk authored by {@code a:b}.</p>
     *
     * @return the author, or {@code null} for anything not written through
     *     {@code library.add} (a bundled pack, a published shelf)
     */
    private static String chunkAuthor(String chunkId) {
        if (chunkId == null || !chunkId.startsWith("lib:")) return null;
        int lastColon = chunkId.lastIndexOf(':');
        if (lastColon <= 3) return null;
        var author = chunkId.substring(4, lastColon);
        return author.isBlank() ? null : author;
    }

    /**
     * May this caller rewrite or remove {@code chunkId}?
     *
     * <p>Neither {@code library.tag} nor {@code library.delete} used to ask.
     * They took any id and acted, so any crafted item holding
     * {@code library.delete} — which is in {@code CRAFTED_ALLOW} — could erase
     * any chunk in the household's knowledge base, including passages from the
     * steward's 13.6M-chunk published shelf. {@code library.delete} is tier 5,
     * the most dangerous rung the manifest validator has, and it was unguarded.</p>
     *
     * <p>The rule follows the same principle as the rest of this surface: an
     * item carries exactly the authority of whoever is using it. You may edit
     * what you wrote. The household's steward may curate anything — they can
     * already do so with {@code wyrd library}, so this is their own authority,
     * not an escalation — and it is logged when they do. Everyone else is
     * refused.</p>
     */
    private boolean mayRewriteChunk(String chunkId) {
        var author = chunkAuthor(chunkId);
        if (author != null && author.equals(agentId)) return true;

        var grants = ActionGrants.get();
        var zoneOwner = grants != null ? grants.fallbackOwnerDid() : null;
        if (zoneOwner == null) return false;
        var callerDid = PersonStudyReach.resolvePerson(agentId);
        if (zoneOwner.equals(callerDid)) {
            log.info("library write on '{}' allowed as the household's steward ({})",
                chunkId, callerDid);
            return true;
        }
        return false;
    }

    @Override
    public Map<String, Object> libraryTag(String chunkId, List<String> tags) {
        if (luceneStore == null) return Map.of("error", "library not available", "ok", false);
        if (!mayRewriteChunk(chunkId)) {
            log.warn("library.tag REFUSED for '{}': {} did not write it", chunkId, agentId);
            return Map.of("ok", false, "error", "not_yours",
                "reason", "not_yours", "chunkId", String.valueOf(chunkId));
        }
        return luceneStore.updateKnowledgeTags(
            SearchCollections.KNOWLEDGE, chunkId, tags);
    }

    @Override
    public Map<String, Object> libraryDelete(String chunkId) {
        if (luceneStore == null) return Map.of("error", "library not available", "ok", false);
        if (chunkId == null || chunkId.isBlank()) {
            return Map.of("ok", false, "reason", "blank_id");
        }
        // Asked BEFORE existence, so a refusal does not double as a probe for
        // which chunk ids are real.
        if (!mayRewriteChunk(chunkId)) {
            log.warn("library.delete REFUSED for '{}': {} did not write it", chunkId, agentId);
            return Map.of("ok", false, "reason", "not_yours", "chunkId", chunkId);
        }
        try {
            var existing = luceneStore.getById(
                SearchCollections.KNOWLEDGE, chunkId);
            if (existing == null) return Map.of("ok", false, "reason", "not_found");
            var deleted = luceneStore.deletePublicById(
                SearchCollections.KNOWLEDGE, chunkId);
            return deleted > 0
                ? Map.of("ok", true, "chunkId", chunkId,
                    "deletedAt", Instant.now().toEpochMilli())
                : Map.of("ok", false, "reason", "not_found");
        } catch (Exception e) {
            log.warn("library.delete failed: {}", e.getMessage());
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    // ─── — Journal / Notes / Pinboard ──

    /** Optional Study wiring point. Wired by CoreServices when StudyService is available. */
    public void setStudyService(StudyService study) {
        this.studyService = study;
    }

    /** Lazy-init a default StudyService when none is set. Bound to the same Lucene store. */
    private StudyService study() {
        var s = studyService;
        if (s != null) return s;
        if (luceneStore == null) return null;
        synchronized (this) {
            if (studyService == null) {
                studyService = new StudyService(luceneStore);
            }
            return studyService;
        }
    }

    @Override
    public Map<String, Object> journalWrite(String content, Map<String, Object> opts) {
        var s = study();
        if (s == null) return Map.of("ok", false, "error", "study not available");
        if (content == null || content.isBlank()) {
            return Map.of("ok", false, "error", "blank content");
        }
        var visibility = opts == null ? "shared" : String.valueOf(opts.getOrDefault("visibility", "shared"));
        var id = "private".equals(visibility)
            ? s.writePrivateJournalEntry(agentId == null ? "agent" : agentId, content)
            : s.writeJournalEntry(agentId == null ? "agent" : agentId, content);
        return Map.of("ok", true, "id", id, "visibility", visibility,
            "writtenAt", Instant.now().toEpochMilli());
    }

    @Override
    public List<Map<String, Object>> journalSearch(String query, int limit) {
        var s = study();
        if (s == null || agentId == null) return List.of();
        var results = s.searchJournal(agentId, query, Math.max(1, Math.min(limit, 50)));
        var out = new ArrayList<Map<String, Object>>(results.size());
        for (var r : results) {
            var m = new HashMap<String, Object>();
            m.put("id", r.id());
            m.put("content", r.content());
            var meta = r.metadata();
            if (meta != null) {
                if (meta.get("timestamp") != null) m.put("ts", meta.get("timestamp"));
                if (meta.get("item_type") != null) m.put("visibility",
                    "journal_private".equals(meta.get("item_type")) ? "private" : "shared");
            }
            out.add(m);
        }
        return out;
    }

    @Override
    public List<Map<String, Object>> journalRecent(int limit) {
        var s = study();
        if (s == null || agentId == null) return List.of();
        var results = s.recentJournal(agentId, Math.max(1, Math.min(limit, 50)));
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
    public Map<String, Object> notesAdd(String content, List<String> tags) {
        var s = study();
        if (s == null) return Map.of("ok", false, "error", "study not available");
        if (content == null || content.isBlank()) {
            return Map.of("ok", false, "error", "blank content");
        }
        var id = s.addNote(agentId == null ? "agent" : agentId, content);
        var out = new HashMap<String, Object>();
        out.put("ok", true);
        out.put("id", id);
        if (tags != null && !tags.isEmpty()) out.put("tags", tags);
        return out;
    }

    @Override
    public List<Map<String, Object>> notesList(String tag) {
        var s = study();
        if (s == null || agentId == null || luceneStore == null) return List.of();
        var results = luceneStore.searchStudyByType(agentId, "note", "*", 100);
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
    public Map<String, Object> notesDelete(String id) {
        var s = study();
        if (s == null) return Map.of("ok", false, "error", "study not available");
        return s.deleteNote(id, agentId == null ? "agent" : agentId);
    }

    @Override
    public Map<String, Object> pinboardPin(String text, Map<String, Object> opts) {
        var s = study();
        if (s == null) return Map.of("ok", false, "error", "study not available");
        if (text == null || text.isBlank()) {
            return Map.of("ok", false, "error", "blank text");
        }
        var title = text.length() > 60 ? text.substring(0, 60) : text;
        var id = s.pin(agentId == null ? "agent" : agentId, title, "", text);
        return Map.of("ok", true, "id", id);
    }

    @Override
    public List<Map<String, Object>> pinboardList() {
        var s = study();
        if (s == null || agentId == null) return List.of();
        var results = s.listPins(agentId, 100);
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
        var s = study();
        if (s == null) return Map.of("ok", false, "error", "study not available");
        return s.unpin(id, agentId == null ? "agent" : agentId);
    }

    // ─── — Room writes via RoomBridge ──

    /**
     * §4.3 — minimal contract for routing item-script room writes to a real
     * {@link org.wyrdsekai.core.room.RoomActor}. CompanionActor builds a
     * concrete bridge per script invocation that {@code tell}s the active
     * room ref via a {@code RoomCommand.ItemBridgeAction} sealed sub-action.
     */
    public interface RoomBridge {
        default String roomId() { return null; }
        default String roomName() { return null; }
        default String roomDescription() { return null; }
        default List<Map<String, Object>> entities() { return List.of(); }
        default List<Map<String, Object>> objects() { return List.of(); }
        default List<Map<String, Object>> exits() { return List.of(); }
        default String getProperty(String key) { return null; }
        default void emit(String eventType, Map<String, Object> data) {}
        default void narrate(String text) {}
        default void addObject(String id, String name, String description, boolean takeable) {}
        default void removeObject(String id) {}
        default void setProperty(String key, Object value) {}
        default void updateDescription(String text) {}
        // scripted body writes
        default void setPosture(String entityId, Posture posture) {}
        default void clearPosture(String entityId) {}
        default void lookAt(String actorId, String targetId, String manner) {}
        default void broadcastBodyLanguage(String actorId, String text) {}
    }

    /**
     * Wire the RoomBridge for the next script invocation. CompanionActor
     * MUST call {@code setRoomBridge(null)} in a {@code finally} block so
     * subsequent calls in other contexts can't leak the bridge.
     */
    public void setRoomBridge(RoomBridge bridge) {
        this.roomBridge = bridge;
    }

    @Override public String roomId() {
        var b = roomBridge; return b == null ? null : b.roomId();
    }
    @Override public String roomName() {
        var b = roomBridge; return b == null ? null : b.roomName();
    }
    @Override public String roomDescription() {
        var b = roomBridge; return b == null ? null : b.roomDescription();
    }
    @Override public List<Map<String, Object>> roomEntities() {
        var b = roomBridge; return b == null ? List.of() : b.entities();
    }
    @Override public List<Map<String, Object>> roomObjects() {
        var b = roomBridge; return b == null ? List.of() : b.objects();
    }
    @Override public List<Map<String, Object>> roomExits() {
        var b = roomBridge; return b == null ? List.of() : b.exits();
    }
    @Override public String roomGetProperty(String key) {
        var b = roomBridge; return b == null ? null : b.getProperty(key);
    }

    @Override public Map<String, Object> roomEmit(String eventType, Map<String, Object> data) {
        var b = roomBridge;
        if (b == null) {
            return Map.of("error", "no_room_bridge",
                "message", "room.emit requires a wired RoomBridge");
        }
        b.emit(eventType, data);
        return Map.of("ok", true, "queued", true);
    }

    @Override public Map<String, Object> roomNarrate(String text) {
        var b = roomBridge;
        if (b == null) {
            return Map.of("error", "no_room_bridge",
                "message", "room.narrate requires a wired RoomBridge");
        }
        b.narrate(text);
        return Map.of("ok", true, "queued", true);
    }

    @Override public Map<String, Object> roomAddObject(String id, String name, String description,
                                                         boolean takeable, Map<String, Object> effects) {
        var b = roomBridge;
        if (b == null) {
            return Map.of("error", "no_room_bridge",
                "message", "room.add_object requires a wired RoomBridge");
        }
        b.addObject(id, name, description, takeable);
        return Map.of("ok", true, "queued", true, "id", id);
    }

    @Override public Map<String, Object> roomRemoveObject(String id) {
        var b = roomBridge;
        if (b == null) {
            return Map.of("error", "no_room_bridge",
                "message", "room.remove_object requires a wired RoomBridge");
        }
        b.removeObject(id);
        return Map.of("ok", true, "queued", true);
    }

    @Override public Map<String, Object> roomSetProperty(String key, Object value) {
        var b = roomBridge;
        if (b == null) {
            return Map.of("error", "no_room_bridge",
                "message", "room.set_property requires a wired RoomBridge");
        }
        b.setProperty(key, value);
        return Map.of("ok", true, "queued", true);
    }

    @Override public Map<String, Object> roomUpdateDescription(String text) {
        var b = roomBridge;
        if (b == null) {
            return Map.of("error", "no_room_bridge",
                "message", "room.update_description requires a wired RoomBridge");
        }
        b.updateDescription(text);
        return Map.of("ok", true, "queued", true);
    }

    // ─── — entity body state ───────────

    @Override public Map<String, Object> entitySetPosture(String entityId, Map<String, Object> spec) {
        var b = roomBridge;
        if (b == null) {
            return Map.of("error", "no_room_bridge",
                "message", "entity.setPosture requires a wired RoomBridge");
        }
        if (entityId == null || entityId.isBlank()) {
            return Map.of("error", "invalid_entity", "message", "entityId must not be blank");
        }
        try {
            var posture = postureFromMap(spec);
            b.setPosture(entityId, posture);
            return Map.of("ok", true, "queued", true);
        } catch (IllegalArgumentException e) {
            return Map.of("error", "invalid_posture", "message", e.getMessage());
        }
    }

    @Override public Map<String, Object> entityClearPosture(String entityId) {
        var b = roomBridge;
        if (b == null) {
            return Map.of("error", "no_room_bridge",
                "message", "entity.clearPosture requires a wired RoomBridge");
        }
        if (entityId == null || entityId.isBlank()) {
            return Map.of("error", "invalid_entity", "message", "entityId must not be blank");
        }
        b.clearPosture(entityId);
        return Map.of("ok", true, "queued", true);
    }

    @Override public Map<String, Object> entityLookAt(String actorId, String targetId, String manner) {
        var b = roomBridge;
        if (b == null) {
            return Map.of("error", "no_room_bridge",
                "message", "entity.lookAt requires a wired RoomBridge");
        }
        if (actorId == null || actorId.isBlank() || targetId == null || targetId.isBlank()) {
            return Map.of("error", "invalid_args", "message", "actorId and targetId must not be blank");
        }
        b.lookAt(actorId, targetId, manner);
        return Map.of("ok", true, "queued", true);
    }

    @Override public Map<String, Object> roomBroadcastBodyLanguage(String actorId, String text) {
        var b = roomBridge;
        if (b == null) {
            return Map.of("error", "no_room_bridge",
                "message", "room.broadcastBodyLanguage requires a wired RoomBridge");
        }
        if (text == null || text.isBlank()) {
            return Map.of("error", "invalid_text", "message", "text must not be blank");
        }
        b.broadcastBodyLanguage(actorId == null ? "narrator" : actorId, text);
        return Map.of("ok", true, "queued", true);
    }

    /**
     * Convert a JS-friendly map ({@code {verb, atObject?, descriptor, innerImprint?}})
     * into a {@link org.wyrdsekai.common.model.Posture}. {@code setAt} is filled
     * with {@code Instant.now()} so callers don't have to.
     */
    private static Posture postureFromMap(Map<String, Object> spec) {
        if (spec == null) throw new IllegalArgumentException("postureSpec must not be null");
        var verb = stringField(spec, "verb");
        var atObject = stringField(spec, "atObject");
        var descriptor = stringField(spec, "descriptor");
        InnerImprint imprint = null;
        var imprintRaw = spec.get("innerImprint");
        if (imprintRaw instanceof Map<?, ?> im) {
            @SuppressWarnings("unchecked")
            var imap = (Map<String, Object>) im;
            imprint = innerImprintFromMap(imap);
        }
        return new Posture(verb, atObject, descriptor,
            Instant.now(), imprint);
    }

    private static InnerImprint innerImprintFromMap(Map<String, Object> m) {
        Map<String, Double> tanks = toDoubleMap(m.get("tanks"));
        Map<String, Double> drives = toDoubleMap(m.get("drives"));
        var triggers = stringField(m, "triggersOnSet");
        return new InnerImprint(tanks, drives, triggers);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Double> toDoubleMap(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) return Map.of();
        var out = new LinkedHashMap<String, Double>();
        for (var e : ((Map<String, Object>) m).entrySet()) {
            var k = e.getKey();
            var v = e.getValue();
            if (k == null || v == null) continue;
            if (v instanceof Number n) out.put(k, n.doubleValue());
            else {
                try { out.put(k, Double.parseDouble(v.toString())); }
                catch (NumberFormatException ignored) { /* skip */ }
            }
        }
        return out;
    }

    private static String stringField(Map<String, Object> m, String key) {
        var v = m.get(key);
        return v == null ? null : v.toString();
    }

    // ─── — Adapter dispatch ───────────

    @Override
    public Map<String, Object> invokeAdapter(String namespace, String method,
                                               Map<String, Object> args) {
        var registry = ExternalAdapterRegistry.get();
        var caps = ItemCapabilitySet.UNRESTRICTED;
        var resp = registry.invoke(new AdapterRequest(
            namespace, method, args == null ? Map.of() : args, caps, null));
        return resp.toMap();
    }

    @Override
    public Set<String> adapterNamespaces() {
        return ExternalAdapterRegistry.get().namespaces();
    }

    // ─── — LLM extensions (Phase A2) ──

    /**
     * Tier 4 — open-ended completion. Threads {@code opts}
     * (maxTokens/temperature/stop/system/model) through the existing
     * {@link InferenceRouter} chat path; on success returns
     * {@code {text, latencyMs, tokensIn, tokensOut}}. The model field
     * accepts both raw model names and {@code cap:routine}-style
     * capability hints — same as the rest of the inference plumbing.
     */
    @Override
    public Map<String, Object> llmComplete(String prompt, Map<String, Object> opts) {
        if (inferenceRouter == null || actorSystem == null) {
            return Map.of("error", "inference not wired",
                "text", "[error] inference not wired");
        }
        if (prompt == null || prompt.isBlank()) {
            return Map.of("error", "blank prompt", "text", "");
        }
        var safeOpts = opts == null ? Map.<String, Object>of() : opts;
        int maxTokens = numberOpt(safeOpts, "maxTokens", 512).intValue();
        double temperature = numberOpt(safeOpts, "temperature", 0.7).doubleValue();
        var system = safeOpts.get("system") instanceof String s ? s : null;
        var model = safeOpts.get("model") instanceof String m ? m : null;
        var requestId = "item-llm-complete-" + UUID.randomUUID().toString().substring(0, 8);

        var msgs = new ArrayList<InferenceClient.ChatMessage>();
        if (system != null && !system.isBlank()) {
            msgs.add(new InferenceClient.ChatMessage("system", system, null, null));
        }
        msgs.add(new InferenceClient.ChatMessage("user", truncate(prompt, 8000), null, null));

        var responseFuture = new CompletableFuture<InferenceRouter.InferResponse>();
        var tempActor = actorSystem.systemActorOf(
            Behaviors.receiveMessage(
                (InferenceRouter.InferResponse resp) -> {
                    responseFuture.complete(resp);
                    return Behaviors.stopped();
                }),
            "item-llm-complete-" + requestId,
            Props.empty());

        try {
            var start = System.currentTimeMillis();
            inferenceRouter.tell(new InferenceRouter.ChatRequest(
                requestId, model, msgs, maxTokens, temperature, tempActor,
                null, null, null, null, null, null, null, null, false));
            var response = responseFuture.get(LLM_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            long latencyMs = System.currentTimeMillis() - start;
            recordCost("inference");

            return switch (response) {
                case InferenceRouter.InferOk ok -> {
                    var out = new HashMap<String, Object>();
                    out.put("text", ok.content());
                    out.put("latencyMs", latencyMs);
                    out.put("tokensIn", ok.promptTokens());
                    out.put("tokensOut", ok.completionTokens());
                    yield out;
                }
                case InferenceRouter.InferError err -> Map.of(
                    "error", err.error(),
                    "text", "[error] " + err.error(),
                    "latencyMs", latencyMs);
            };
        } catch (Exception e) {
            log.warn("llm.complete failed: {}", e.getMessage());
            return Map.of("error", e.getMessage(), "text", "[error] " + e.getMessage());
        }
    }

    /**
     * Tier 4 — one-of classification. We construct a small system prompt
     * that bounds the model to one of the labels, then post-process the
     * output with a fuzzy match. Confidence is heuristic (1.0 if the
     * label is the only word in the response, 0.7 otherwise).
     */
    @Override
    public Map<String, Object> llmClassify(String text, List<String> labels) {
        if (text == null || labels == null || labels.isEmpty()) {
            return Map.of("error", "blank text or labels", "label", "", "confidence", 0.0);
        }
        var labelStr = String.join(", ", labels);
        var system = "You are a classifier. Choose exactly ONE label from this list: "
            + labelStr + ". Reply with only the label, no other words.";
        var resp = llmComplete(text, Map.of(
            "system", system,
            "maxTokens", 32,
            "temperature", 0.1));
        if (resp.get("error") != null) {
            return Map.of("error", resp.get("error"), "label", "", "confidence", 0.0);
        }
        var raw = String.valueOf(resp.getOrDefault("text", "")).trim().toLowerCase();
        // Strip common punctuation
        if (raw.endsWith(".") || raw.endsWith(",") || raw.endsWith("!"))
            raw = raw.substring(0, raw.length() - 1).trim();
        for (var label : labels) {
            if (raw.equalsIgnoreCase(label)) {
                return Map.of("label", label, "confidence", 1.0);
            }
        }
        // Fuzzy: "label" appears in the response
        for (var label : labels) {
            if (raw.contains(label.toLowerCase())) {
                return Map.of("label", label, "confidence", 0.7);
            }
        }
        return Map.of("label", labels.getFirst(), "confidence", 0.3, "raw", raw);
    }

    /**
     * Tier 4 — schema-constrained extraction. We render the schema into the
     * system prompt and ask for JSON output, then parse with our JSON helper.
     * If structured-output mode (Ollama format / llama-server grammar) is
     * available the router uses it; we don't need to pass it explicitly here.
     */
    @Override
    public Map<String, Object> llmExtract(String text, Map<String, Object> schema) {
        if (text == null || schema == null) {
            return Map.of("error", "blank text or schema");
        }
        var schemaJson = ItemJsonHelper.stringify(schema);
        var system = "You extract structured data. Return ONLY a JSON object matching this schema: "
            + schemaJson + ". No prose, no markdown fences.";
        var resp = llmComplete(text, Map.of(
            "system", system,
            "maxTokens", 1024,
            "temperature", 0.1));
        if (resp.get("error") != null) {
            return Map.of("error", resp.get("error"));
        }
        var raw = String.valueOf(resp.getOrDefault("text", "")).trim();
        // Strip common code-fence wrappers if the model misbehaved.
        if (raw.startsWith("```")) {
            int firstNl = raw.indexOf('\n');
            if (firstNl > 0) raw = raw.substring(firstNl + 1);
            if (raw.endsWith("```")) raw = raw.substring(0, raw.length() - 3);
            raw = raw.trim();
        }
        var parsed = ItemJsonHelper.parse(raw);
        if (parsed instanceof Map<?, ?> mm) {
            @SuppressWarnings("unchecked")
            var typed = (Map<String, Object>) mm;
            return typed;
        }
        return Map.of("error", "extraction did not return a JSON object", "raw", raw);
    }

    /**
     * Tier 4 — tool-calling. Builds a {@link InferenceClient.ToolDefinition}
     * list from the script's {@code tools} array, then dispatches via the
     * existing InferenceRouter chat path with {@code tool_choice="auto"}.
     */
    @Override
    public Map<String, Object> llmTools(String prompt, List<Map<String, Object>> tools,
                                          Map<String, Object> opts) {
        if (inferenceRouter == null || actorSystem == null) {
            return Map.of("error", "inference not wired",
                "toolCalls", List.of(), "finalText", "");
        }
        if (prompt == null || tools == null) {
            return Map.of("error", "blank prompt or tools",
                "toolCalls", List.of(), "finalText", "");
        }
        var safeOpts = opts == null ? Map.<String, Object>of() : opts;
        int maxTokens = numberOpt(safeOpts, "maxTokens", 1024).intValue();
        double temperature = numberOpt(safeOpts, "temperature", 0.3).doubleValue();
        var system = safeOpts.get("system") instanceof String s ? s : null;
        var model = safeOpts.get("model") instanceof String m ? m : null;

        var toolDefs = new ArrayList<InferenceClient.ToolDefinition>(tools.size());
        for (var t : tools) {
            var name = String.valueOf(t.getOrDefault("name", ""));
            var desc = String.valueOf(t.getOrDefault("description", ""));
            var params = t.get("parameters");
            if (!name.isEmpty()) {
                toolDefs.add(InferenceClient.ToolDefinition.function(name, desc, params));
            }
        }

        var msgs = new ArrayList<InferenceClient.ChatMessage>();
        if (system != null && !system.isBlank()) {
            msgs.add(new InferenceClient.ChatMessage("system", system, null, null));
        }
        msgs.add(new InferenceClient.ChatMessage("user", truncate(prompt, 8000), null, null));

        var requestId = "item-llm-tools-" + UUID.randomUUID().toString().substring(0, 8);
        var responseFuture = new CompletableFuture<InferenceRouter.InferResponse>();
        var tempActor = actorSystem.systemActorOf(
            Behaviors.receiveMessage(
                (InferenceRouter.InferResponse resp) -> {
                    responseFuture.complete(resp);
                    return Behaviors.stopped();
                }),
            "item-llm-tools-" + requestId,
            Props.empty());

        try {
            inferenceRouter.tell(new InferenceRouter.ChatRequest(
                requestId, model, msgs, maxTokens, temperature, tempActor,
                null, null, null, toolDefs, "auto", null, null, null, false));
            var response = responseFuture.get(LLM_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            recordCost("inference");

            return switch (response) {
                case InferenceRouter.InferOk ok -> {
                    var out = new HashMap<String, Object>();
                    // InferOk doesn't carry structured tool_calls — backends
                    // serialize them into the {@code content} field as
                    // either {@code <tool_call>...} markup (llama-server) or
                    // an OpenAI JSON envelope. Scripts parse the content
                    // themselves; we surface both fields so callers can
                    // branch on whichever shape they prefer.
                    out.put("toolCalls", parseInlineToolCalls(ok.content()));
                    out.put("finalText", ok.content() == null ? "" : ok.content());
                    yield out;
                }
                case InferenceRouter.InferError err -> Map.of(
                    "error", err.error(),
                    "toolCalls", List.of(),
                    "finalText", "[error] " + err.error());
            };
        } catch (Exception e) {
            log.warn("llm.tools failed: {}", e.getMessage());
            return Map.of("error", e.getMessage(),
                "toolCalls", List.of(), "finalText", "[error] " + e.getMessage());
        }
    }

    /**
     * Tier 1 — token+cost budget snapshot. Reads from the per-agent
     * {@link AgentCostTracker} and returns the absolute remaining budget.
     */
    @Override
    public Map<String, Object> llmBudgetRemaining() {
        try {
            var tracker = AgentCostTracker.get();
            // The tracker's API is per-agent — pull a summary if available.
            // We don't have a hard budget surface here so we return the
            // observed-spend snapshot so scripts can apply their own gate.
            var summary = new HashMap<String, Object>();
            summary.put("tokens", 0L);
            summary.put("costUsd", 0.0);
            summary.put("dailyResetAt",
                Instant.now().plus(Duration.ofDays(1)).toEpochMilli());
            // If the tracker exposes per-agent spend later, fold it in here.
            return summary;
        } catch (Exception _) {
            return Map.of("tokens", 0L, "costUsd", 0.0,
                "dailyResetAt", Instant.now().plus(Duration.ofDays(1)).toEpochMilli());
        }
    }

    /**
     * Tier 4 — encode text via the in-process {@link
     * org.wyrdsekai.core.search.EmbeddingService}. Returns the dense vector
     * as a {@link List} of {@link Double} (GraalJS-friendly). Empty list
     * when the service is not initialised.
     */
    @Override
    public List<Double> embedEncode(String text) {
        try {
            var svc = EmbeddingService.get();
            if (svc == null) return List.of();
            var floats = svc.embed(text == null ? "" : text);
            var out = new ArrayList<Double>(floats.size());
            for (var f : floats) out.add(f.doubleValue());
            recordCost("embedding");
            return out;
        } catch (Exception e) {
            log.warn("embed.encode failed: {}", e.getMessage());
            return List.of();
        }
    }

    // ─── — Schedule wiring ────────────

    /**
     * Optional schedule service. When set, item scripts can call
     * {@code world.schedule.in/cron/cancel/list}. Wired by CoreServices in
     * normal runtime; tests stub via {@link #setScheduleService}.
     */
    private volatile ItemScheduleService scheduleService;

    /** Test/wiring hook — set the schedule service used by §4.5 surfaces. */
    public void setScheduleService(ItemScheduleService svc) {
        this.scheduleService = svc;
    }

    @Override
    public Map<String, Object> scheduleIn(int seconds, String hookName,
                                            Map<String, Object> payload) {
        var svc = scheduleService;
        if (svc == null) return Map.of("error", "schedule service not wired");
        return svc.scheduleIn(agentId, seconds, hookName, payload);
    }

    @Override
    public Map<String, Object> scheduleCron(String cronExpr, String hookName,
                                              Map<String, Object> payload) {
        var svc = scheduleService;
        if (svc == null) return Map.of("error", "schedule service not wired");
        return svc.scheduleCron(agentId, cronExpr, hookName, payload);
    }

    @Override
    public Map<String, Object> scheduleEvery(long intervalSeconds, String hookName,
                                               Map<String, Object> payload) {
        var svc = scheduleService;
        if (svc == null) return Map.of("error", "schedule service not wired");
        return svc.scheduleEvery(agentId, intervalSeconds, hookName, payload);
    }

    @Override
    public Map<String, Object> scheduleCancel(String timerId) {
        var svc = scheduleService;
        if (svc == null) return Map.of("ok", false, "error", "schedule service not wired");
        return svc.cancel(agentId, timerId);
    }

    @Override
    public List<Map<String, Object>> scheduleList() {
        var svc = scheduleService;
        if (svc == null) return List.of();
        return svc.list(agentId);
    }

    @Override
    public String timezone() {
        // Steward tz could be persisted; for now use the JVM default.
        return ZoneId.systemDefault().getId();
    }

    // ─── -§4.37 — Visualization (Phase B+) ──

    /** Set on construction by {@link CoreServices}; null in tests. */
    private volatile ChartService chartService;
    private volatile ArtifactService artifactService;
    private volatile ScrollService scrollService;
    /** — credentialed network reach; null until wired at boot. */
    private volatile NetworkCapability networkCapability;

    public void setChartService(ChartService svc) { this.chartService = svc; }
    public void setArtifactService(ArtifactService svc) { this.artifactService = svc; }
    public void setScrollService(ScrollService svc) { this.scrollService = svc; }
    public void setNetworkCapability(NetworkCapability cap) {
        this.networkCapability = cap;
    }

    // ─── — world.net.* provider impl ──────────────
    // Delegates to the boot-wired NetworkCapability (gate-checked + credential-
    // resolved). Null capability → a safe "unwired" denial the item narrates.

    /** The wired capability, or the production default (gate + real ssh/scp exec). */
    private NetworkCapability netCap() {
        var cap = networkCapability;
        return cap != null ? cap : NetworkWiring.defaultInstance();
    }

    @Override
    public Map<String, Object> netSshRun(String host, String command, Map<String, Object> opts) {
        return netCap().sshRun(host, command, opts);
    }

    @Override
    public Map<String, Object> netScpTo(String host, String localPath, String remotePath,
                                        Map<String, Object> opts) {
        return netCap().scpTo(host, localPath, remotePath, opts);
    }

    @Override
    public Map<String, Object> netScpFrom(String host, String remotePath, String localPath,
                                          Map<String, Object> opts) {
        return netCap().scpFrom(host, remotePath, localPath, opts);
    }

    @Override
    public Map<String, Object> netHouseholdCopy(String nodeId, String localPath, String remotePath) {
        return netCap().householdCopy(nodeId, localPath, remotePath);
    }

    private static Map<String, Object> chartArtifactToMap(ChartService.ChartArtifact a,
                                                            ArtifactService maybeStore,
                                                            String agentId) {
        var out = new LinkedHashMap<String, Object>();
        out.put("ok", true);
        out.put("id", a.id());
        out.put("kind", a.kind());
        out.put("title", a.title());
        out.put("mime", a.mime());
        out.put("payload", a.payload());
        // If an artifact store is wired, register the chart so callers can reference it.
        if (maybeStore != null && agentId != null) {
            try {
                var stored = maybeStore.create(agentId, a.kind(), a.mime(),
                    a.payload(),
                    Map.of("title", a.title() == null ? "" : a.title()));
                if (Boolean.TRUE.equals(stored.get("ok"))) {
                    out.put("artifactId", stored.get("id"));
                }
            } catch (Exception e) {
                log.debug("chart artifact registration failed: {}", e.getMessage());
            }
        }
        return out;
    }

    @Override
    public Map<String, Object> chartBar(List<Map<String, Object>> data, Map<String, Object> opts) {
        var svc = chartService;
        if (svc == null) return Map.of("ok", false, "error", "chart service not wired");
        return chartArtifactToMap(svc.bar(data, opts), artifactService, agentId);
    }

    @Override
    public Map<String, Object> chartLine(List<Map<String, Object>> data, Map<String, Object> opts) {
        var svc = chartService;
        if (svc == null) return Map.of("ok", false, "error", "chart service not wired");
        return chartArtifactToMap(svc.line(data, opts), artifactService, agentId);
    }

    @Override
    public Map<String, Object> chartScatter(List<Map<String, Object>> data, Map<String, Object> opts) {
        var svc = chartService;
        if (svc == null) return Map.of("ok", false, "error", "chart service not wired");
        return chartArtifactToMap(svc.scatter(data, opts), artifactService, agentId);
    }

    @Override
    public Map<String, Object> chartPie(List<Map<String, Object>> data, Map<String, Object> opts) {
        var svc = chartService;
        if (svc == null) return Map.of("ok", false, "error", "chart service not wired");
        return chartArtifactToMap(svc.pie(data, opts), artifactService, agentId);
    }

    @Override
    public Map<String, Object> chartHeatmap(List<Map<String, Object>> data, Map<String, Object> opts) {
        var svc = chartService;
        if (svc == null) return Map.of("ok", false, "error", "chart service not wired");
        return chartArtifactToMap(svc.heatmap(data, opts), artifactService, agentId);
    }

    @Override
    public Map<String, Object> chartHistogram(List<Number> values, Map<String, Object> opts) {
        var svc = chartService;
        if (svc == null) return Map.of("ok", false, "error", "chart service not wired");
        return chartArtifactToMap(svc.histogram(values, opts), artifactService, agentId);
    }

    @Override
    public Map<String, Object> chartVega(Map<String, Object> spec) {
        var svc = chartService;
        if (svc == null) return Map.of("ok", false, "error", "chart service not wired");
        return chartArtifactToMap(svc.vega(spec), artifactService, agentId);
    }

    @Override
    public Map<String, Object> chartAscii(List<Map<String, Object>> data, Map<String, Object> opts) {
        var svc = chartService;
        if (svc == null) return Map.of("ok", false, "error", "chart service not wired");
        // ASCII charts are NOT auto-registered as artifacts — they're typically
        // rendered inline in the same call.
        var a = svc.ascii(data, opts);
        var out = new LinkedHashMap<String, Object>();
        out.put("ok", true);
        out.put("id", a.id());
        out.put("kind", a.kind());
        out.put("title", a.title());
        out.put("mime", a.mime());
        out.put("payload", a.payload());
        return out;
    }

    @Override
    public Map<String, Object> artifactCreate(String kind, String mime, Object payload,
                                                Map<String, Object> opts) {
        var svc = artifactService;
        if (svc == null) return Map.of("ok", false, "error", "artifact service not wired");
        return svc.create(agentId, kind, mime, payload, opts);
    }

    @Override
    public Map<String, Object> artifactGet(String id) {
        var svc = artifactService;
        if (svc == null) return Map.of("ok", false, "error", "artifact service not wired");
        return svc.get(agentId, id);
    }

    @Override
    public List<Map<String, Object>> artifactList(Map<String, Object> filter) {
        var svc = artifactService;
        if (svc == null) return List.of();
        return svc.list(agentId, filter);
    }

    @Override
    public Map<String, Object> artifactAttach(String roomId, String artifactId) {
        var svc = artifactService;
        if (svc == null) return Map.of("ok", false, "error", "artifact service not wired");
        return svc.attach(agentId, roomId, artifactId);
    }

    @Override
    public Map<String, Object> artifactRevoke(String id) {
        var svc = artifactService;
        if (svc == null) return Map.of("ok", false, "error", "artifact service not wired");
        return svc.revoke(agentId, id);
    }

    @Override
    public Map<String, Object> scrollCreate(String title, List<Map<String, Object>> sections) {
        var svc = scrollService;
        if (svc == null) return Map.of("ok", false, "error", "scroll service not wired");
        return svc.create(agentId, title, sections);
    }

    @Override
    public Map<String, Object> scrollRead(String id) {
        var svc = scrollService;
        if (svc == null) return Map.of("ok", false, "error", "scroll service not wired");
        return svc.read(agentId, id);
    }

    @Override
    public List<Map<String, Object>> scrollList(Map<String, Object> filter) {
        var svc = scrollService;
        if (svc == null) return List.of();
        return svc.list(agentId, filter);
    }

    @Override
    public Map<String, Object> scrollRevise(String id, List<Map<String, Object>> sections) {
        var svc = scrollService;
        if (svc == null) return Map.of("ok", false, "error", "scroll service not wired");
        return svc.revise(agentId, id, sections);
    }

    @Override
    public Map<String, Object> scrollLock(String id) {
        var svc = scrollService;
        if (svc == null) return Map.of("ok", false, "error", "scroll service not wired");
        return svc.lock(agentId, id);
    }

    @Override
    public Map<String, Object> scrollShare(String id, String target) {
        var svc = scrollService;
        if (svc == null) return Map.of("ok", false, "error", "scroll service not wired");
        return svc.share(agentId, id, target);
    }

    // ─── — JSON / Date utilities ──────

    @Override public Object jsonParse(String text) { return ItemJsonHelper.parse(text); }
    @Override public String jsonStringify(Object value, boolean pretty) {
        return ItemJsonHelper.stringify(value, pretty);
    }
    @Override public Object jsonPath(Object value, String jsonPath) {
        return ItemJsonHelper.path(value, jsonPath);
    }
    @Override public Object jsonMerge(Object a, Object b) { return ItemJsonHelper.merge(a, b); }
    @Override public List<Map<String, Object>> jsonDiff(Object a, Object b) {
        return ItemJsonHelper.diff(a, b);
    }

    private static Number numberOpt(Map<String, Object> opts, String key, Number defVal) {
        var v = opts.get(key);
        if (v instanceof Number n) return n;
        if (v instanceof String s) {
            try { return Double.parseDouble(s); } catch (Exception _) { return defVal; }
        }
        return defVal;
    }

    /**
     * Best-effort inline tool-call parser. Looks for either an OpenAI-shape
     * JSON envelope ({@code {"name": "...", "arguments": {...}}}) or a
     * llama-server {@code <tool_call>...</tool_call>} marker. Returns an
     * empty list when nothing is found — scripts that asked for tools but
     * got prose should branch on {@code toolCalls.length === 0}.
     */
    // ─── — Web extensions (Phase C) ───

    /**
     * 30-second connect+read timeout per spec §4.7. Items waiting longer
     * usually want the schedule API anyway.
     */
    private static final Duration WEB_TIMEOUT = Duration.ofSeconds(30);

    /** 10MB cap on raw responses per spec §4.7. */
    private static final long WEB_MAX_BYTES = 10L * 1024 * 1024;

    private static final HttpClient WEB_CLIENT = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(WEB_TIMEOUT)
        .build();

    @Override
    public Map<String, Object> webFetchRaw(String url, Map<String, Object> opts) {
        return doWebRequest("GET", url, null, opts, true);
    }

    @Override
    public Map<String, Object> webPost(String url, Object body, Map<String, Object> opts) {
        return doWebRequest("POST", url, body, opts, false);
    }

    @Override
    public Map<String, Object> webPut(String url, Object body, Map<String, Object> opts) {
        return doWebRequest("PUT", url, body, opts, false);
    }

    @Override
    public Map<String, Object> webDelete(String url, Map<String, Object> opts) {
        return doWebRequest("DELETE", url, null, opts, false);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> doWebRequest(String method, String url, Object body,
                                                Map<String, Object> opts, boolean returnRaw) {
        if (url == null || url.isBlank()) {
            return Map.of("status", 0, "error", "missing_url", "body", "");
        }
        try {
            var uri = URI.create(url);
            var builder = HttpRequest.newBuilder(uri).timeout(WEB_TIMEOUT);
            String contentType = "application/json";
            if (opts != null) {
                var ct = opts.get("contentType");
                if (ct instanceof String cts && !cts.isBlank()) contentType = cts;
                var headers = opts.get("headers");
                if (headers instanceof Map<?, ?> hm) {
                    for (var e : hm.entrySet()) {
                        if (e.getKey() == null || e.getValue() == null) continue;
                        try {
                            builder.header(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                        } catch (IllegalArgumentException _) {
                            // ignore reserved/illegal headers — defence in depth
                        }
                    }
                }
                var accept = opts.get("accept");
                if (accept instanceof String as && !as.isBlank()) {
                    try { builder.header("Accept", as); } catch (IllegalArgumentException _) {}
                }
            }
            HttpRequest.BodyPublisher publisher;
            if (body == null) {
                publisher = HttpRequest.BodyPublishers.noBody();
            } else if (body instanceof String s) {
                publisher = HttpRequest.BodyPublishers.ofString(s);
            } else if (body instanceof byte[] ba) {
                publisher = HttpRequest.BodyPublishers.ofByteArray(ba);
            } else {
                publisher = HttpRequest.BodyPublishers.ofString(
                    ItemJsonHelper.stringify(body, false));
            }
            switch (method) {
                case "POST" -> builder.POST(publisher).header("Content-Type", contentType);
                case "PUT" -> builder.PUT(publisher).header("Content-Type", contentType);
                case "DELETE" -> builder.DELETE();
                default -> builder.GET();
            }
            recordCost("web_search");
            long maxBytes = WEB_MAX_BYTES;
            if (opts != null && opts.get("maxBytes") instanceof Number mn) {
                maxBytes = Math.min(WEB_MAX_BYTES, mn.longValue());
            }
            var response = WEB_CLIENT.send(builder.build(),
                HttpResponse.BodyHandlers.ofByteArray());
            var bytes = response.body();
            String text;
            if (bytes != null && bytes.length > maxBytes) {
                text = new String(bytes, 0, (int) Math.min(maxBytes, Integer.MAX_VALUE),
                    StandardCharsets.UTF_8);
            } else {
                text = bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8);
            }
            var out = new HashMap<String, Object>();
            out.put("status", response.statusCode());
            out.put("body", text);
            if (returnRaw) {
                var headerMap = new HashMap<String, Object>();
                response.headers().map().forEach((k, v) -> headerMap.put(k,
                    v == null || v.isEmpty() ? "" : v.getFirst()));
                out.put("headers", headerMap);
                var ctHeader = response.headers().firstValue("content-type").orElse("");
                out.put("contentType", ctHeader);
            }
            return out;
        } catch (Exception e) {
            log.warn("web.{} failed for {}: {}", method.toLowerCase(), url, e.getMessage());
            return Map.of("status", 0, "error", e.getClass().getSimpleName(),
                "message", e.getMessage() == null ? "request_failed" : e.getMessage(),
                "body", "");
        }
    }

    // ─── — MCP extensions (Phase C) ───

    @Override
    public List<Map<String, Object>> mcpListServers() {
        try {
            var mgr = McpServerManager.get();
            if (mgr == null) return List.of();
            var out = new ArrayList<Map<String, Object>>();
            for (var serverId : mgr.connectedServers()) {
                var m = new HashMap<String, Object>();
                m.put("server", serverId);
                m.put("transport", "unknown");
                m.put("status", mgr.isConnected(serverId) ? "connected" : "disconnected");
                out.add(m);
            }
            return out;
        } catch (Exception e) {
            log.warn("mcp.list_servers failed: {}", e.getMessage());
            return List.of();
        }
    }

    // ── MCP capability grants (Study "Tool Warden") — delegate to the
    // process-wide McpGrantAdmin, with this item's caller as the acting steward.

    @Override
    public List<Map<String, Object>> mcpGrantServices() {
        var admin = McpGrantAdmin.installed();
        return admin == null ? List.of() : admin.services(callerDid());
    }

    @Override
    public List<Map<String, Object>> mcpGrantList() {
        var admin = McpGrantAdmin.installed();
        return admin == null ? List.of() : admin.grants(callerDid());
    }

    @Override
    public Map<String, Object> mcpGrantIssue(String subject, String service) {
        var admin = McpGrantAdmin.installed();
        if (admin == null) return Map.of("ok", false, "error", "MCP grant admin not available");
        return admin.grant(callerDid(), subject, service);
    }

    @Override
    public Map<String, Object> mcpGrantRevoke(String subject, String service) {
        var admin = McpGrantAdmin.installed();
        if (admin == null) return Map.of("ok", false, "error", "MCP grant admin not available");
        return admin.revoke(callerDid(), subject, service);
    }

    @Override
    public List<Map<String, Object>> mcpListTools(String server) {
        try {
            var mgr = McpServerManager.get();
            if (mgr == null) return List.of();
            var index = mgr.toolIndex();
            if (index == null) return List.of();
            var out = new ArrayList<Map<String, Object>>();
            // Filter by server when set, otherwise enumerate all qualified names.
            if (server != null && !server.isBlank()) {
                for (var route : index.toolsForServer(server)) {
                    out.add(toolRouteToMap(route));
                }
            } else {
                for (var qn : index.allToolNames()) {
                    var route = index.lookup(qn).orElse(null);
                    if (route != null) out.add(toolRouteToMap(route));
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("mcp.list_tools failed: {}", e.getMessage());
            return List.of();
        }
    }

    private static Map<String, Object> toolRouteToMap(
            McpToolIndex.ToolRoute route) {
        var m = new HashMap<String, Object>();
        m.put("server", route.serverId());
        m.put("tool", route.rawToolName());
        m.put("qualified",
            McpToolIndex.qualifyName(route.serverId(), route.rawToolName()));
        if (route.metadata() != null) {
            m.put("description", route.metadata().description());
            m.put("schema", route.metadata().inputSchema());
        }
        return m;
    }

    @Override
    public Map<String, Object> mcpInvoke(String server, String tool, Map<String, Object> args) {
        try {
            var mgr = McpServerManager.get();
            if (mgr == null) {
                return Map.of("success", false,
                    "error", Map.of("code", "mcp_unavailable",
                        "message", "MCP server manager not initialised",
                        "retryable", false));
            }
            if (server == null || server.isBlank() || tool == null || tool.isBlank()) {
                return Map.of("success", false,
                    "error", Map.of("code", "invalid_args",
                        "message", "server and tool required",
                        "retryable", false));
            }
            var qualified = "mcp__" + server + "__" + tool;
            var start = System.currentTimeMillis();
            recordCost("mcp_call");
            var text = mgr.invokeTool(qualified, args == null ? Map.of() : args, callerDid());
            var latency = System.currentTimeMillis() - start;
            return Map.of("success", true, "data", text, "cost", 0.0, "latencyMs", latency);
        } catch (SecurityException e) {
            return Map.of("success", false,
                "error", Map.of("code", "permission_denied",
                    "message", e.getMessage() == null ? "denied" : e.getMessage(),
                    "retryable", false));
        } catch (Exception e) {
            log.warn("mcp.invoke failed for {}/{}: {}", server, tool, e.getMessage());
            return Map.of("success", false,
                "error", Map.of("code", "invocation_failed",
                    "message", e.getMessage() == null ? "invoke_failed" : e.getMessage(),
                    "retryable", true));
        }
    }

    @Override
    public List<Map<String, Object>> mcpResources(String server) {
        // Resources require a JSON-RPC sendRequest path that current
        // McpTransportHandler doesn't expose for arbitrary methods. Surface
        // empty list for now — the namespace + cap catalogue is in place so
        // Phase D can wire the call without further API churn.
        log.debug("mcp.resources stub — server {}", server);
        return List.of();
    }

    @Override
    public Map<String, Object> mcpReadResource(String server, String uri) {
        log.debug("mcp.read_resource stub — server {} uri {}", server, uri);
        return Map.of("error", "mcp.read_resource not yet wired (Phase D)");
    }

    @Override
    public List<Map<String, Object>> mcpPrompts(String server) {
        log.debug("mcp.prompts stub — server {}", server);
        return List.of();
    }

    @Override
    public Map<String, Object> mcpSubscribe(String server, String resourceUri, String hookName) {
        log.debug("mcp.subscribe stub — server {} uri {} hook {}", server, resourceUri, hookName);
        return Map.of("ok", false, "error", "mcp.subscribe not yet wired (Phase D)");
    }

    /**
     * Optional MCP budget tracker injected by CoreServices. Null in test
     * harnesses; the surface returns a zero-budget snapshot in that case so
     * scripts gating on `budget_remaining > N` simply skip the call.
     */
    private volatile McpBudgetTracker mcpBudgetTracker;

    public void setMcpBudgetTracker(McpBudgetTracker tracker) {
        this.mcpBudgetTracker = tracker;
    }

    @Override
    public Map<String, Object> mcpBudgetRemaining(String server) {
        try {
            var tracker = mcpBudgetTracker;
            var resetAt = Instant.now().plus(Duration.ofDays(1)).toEpochMilli();
            if (tracker == null || agentId == null || server == null) {
                return Map.of("remaining", 0.0, "daily", 0.0, "resetAt", resetAt);
            }
            return Map.of(
                "remaining", tracker.remaining(agentId, server),
                "daily", tracker.getLimit(agentId, server),
                "resetAt", resetAt);
        } catch (Exception _) {
            return Map.of("remaining", 0.0, "daily", 0.0,
                "resetAt", Instant.now().plus(Duration.ofDays(1)).toEpochMilli());
        }
    }

    @Override
    public boolean mcpAvailable(String server) {
        try {
            var mgr = McpServerManager.get();
            return mgr != null && server != null && mgr.isConnected(server);
        } catch (Exception _) {
            return false;
        }
    }

    // ─── — Filesystem (Phase C) ──────

    /** Lazy per-agent sandbox. Created when first fs.* is called. */
    private volatile SandboxedFs sandboxedFs;

    private SandboxedFs sandbox() {
        var s = sandboxedFs;
        if (s != null) return s;
        synchronized (this) {
            if (sandboxedFs == null) {
                var dataDir = System.getenv("WYRDSEKAI_DATA_DIR");
                if (dataDir == null || dataDir.isBlank()) {
                    dataDir = System.getProperty("wyrdsekai.data.dir",
                        System.getProperty("java.io.tmpdir") + "/wyrdsekai");
                }
                var owner = agentId == null ? "anon" : agentId;
                sandboxedFs = new SandboxedFs(Path.of(dataDir), owner);
            }
            return sandboxedFs;
        }
    }

    /**
     * Test/integration hook — point the per-agent sandbox at an explicit
     * directory. Used by SandboxedFsTest + ItemWorldApiPhaseCTest.
     */
    public void setSandboxedFs(SandboxedFs fs) {
        synchronized (this) {
            this.sandboxedFs = fs;
        }
    }

    @Override
    public String fsRead(String relPath) {
        try {
            return sandbox().read(relPath);
        } catch (Exception e) {
            return "[error] " + (e.getMessage() == null ? "fs_read_failed" : e.getMessage());
        }
    }

    @Override
    public Map<String, Object> fsWrite(String relPath, String content) {
        try {
            return sandbox().write(relPath, content);
        } catch (Exception e) {
            return Map.of("ok", false,
                "error", e.getMessage() == null ? "fs_write_failed" : e.getMessage());
        }
    }

    @Override
    public List<Map<String, Object>> fsList(String relDir) {
        try {
            return sandbox().list(relDir);
        } catch (Exception _) {
            return List.of();
        }
    }

    @Override
    public Map<String, Object> fsDelete(String relPath) {
        try {
            return sandbox().delete(relPath);
        } catch (Exception e) {
            return Map.of("ok", false,
                "error", e.getMessage() == null ? "fs_delete_failed" : e.getMessage());
        }
    }

    @Override
    public boolean fsExists(String relPath) {
        try { return sandbox().exists(relPath); }
        catch (Exception _) { return false; }
    }

    @Override
    public Map<String, Object> fsStat(String relPath) {
        try { return sandbox().stat(relPath); }
        catch (Exception e) {
            return Map.of("error", e.getMessage() == null ? "stat_failed" : e.getMessage());
        }
    }

    @Override
    public Map<String, Object> fsMkdir(String relPath) {
        try {
            return sandbox().mkdir(relPath);
        } catch (Exception e) {
            return Map.of("ok", false,
                "error", e.getMessage() == null ? "mkdir_failed" : e.getMessage());
        }
    }

    // ─── — Mailbox (Phase C) ────────

    @Override
    public List<Map<String, Object>> mailboxInbox(Map<String, Object> filter) {
        var svc = MailboxService.getOrCreate();
        return svc.inbox(agentId == null ? "anon" : agentId, filter);
    }

    @Override
    public Map<String, Object> mailboxRead(String id) {
        var svc = MailboxService.getOrCreate();
        return svc.read(agentId == null ? "anon" : agentId, id);
    }

    @Override
    public Map<String, Object> mailboxMarkRead(String id) {
        var svc = MailboxService.getOrCreate();
        return svc.markRead(agentId == null ? "anon" : agentId, id);
    }

    @Override
    public Map<String, Object> mailboxArchive(String id) {
        var svc = MailboxService.getOrCreate();
        return svc.archive(agentId == null ? "anon" : agentId, id);
    }

    @Override
    public Map<String, Object> mailboxSend(String to, String subject, String body,
                                              Map<String, Object> opts) {
        var svc = MailboxService.getOrCreate();
        var from = agentId == null ? "anon" : agentId;
        return svc.send(from, to, subject, body, opts);
    }

    // ─── — drive.mark (Phase C) ──────

    /**
     * Optional callback for vitality drive deltas. Wired by CompanionActor
     * when present; null otherwise → drive.mark returns {@code ok=false}
     * with reason {@code drive_mark_not_wired}. Tests may set this to
     * exercise the path without standing up a full actor.
     */
    private volatile BiConsumer<String, Double> driveMarkCallback;

    public void setDriveMarkCallback(BiConsumer<String, Double> cb) {
        this.driveMarkCallback = cb;
    }

    @Override
    public Map<String, Object> driveMark(String name, double delta, String reason) {
        if (name == null || name.isBlank()) {
            return Map.of("ok", false, "error", "missing_name");
        }
        // Bound delta to [-1, +1] per spec §4.1 rate-limit semantics.
        var bounded = Math.max(-1.0, Math.min(1.0, delta));
        var cb = driveMarkCallback;
        if (cb == null) {
            log.debug("drive.mark called but no callback wired: {} delta={} reason={}",
                name, bounded, reason);
            return Map.of("ok", false, "error", "drive_mark_not_wired");
        }
        try {
            cb.accept(name, bounded);
            // Surface an updated drive snapshot if available — matches spec return shape.
            var snap = driveSnapshot();
            var out = new HashMap<String, Object>();
            out.put("ok", true);
            out.put("name", name);
            out.put("delta", bounded);
            if (reason != null && !reason.isBlank()) out.put("reason", reason);
            if (!snap.isEmpty()) out.put("drives", snap.get("drives"));
            return out;
        } catch (Exception e) {
            log.warn("drive.mark callback failed: {}", e.getMessage());
            return Map.of("ok", false,
                "error", e.getMessage() == null ? "callback_failed" : e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // -§4.22 — Phase D-N (cross-agent + room services)
    // ═══════════════════════════════════════════════════════════════════

    /** Phase D — optional bond store injected by CompanionActor (nullable for tests). */
    private volatile BondStore bondStore;
    /** Phase D — optional safe (key chest). */
    private volatile TheSafe safe;
    /** Phase D — optional imprint manager (per-agent). */
    private volatile ImprintManager imprintManager;
    /** Phase D — optional voice profile service. */
    private volatile VoiceProfileService voiceProfileService;
    /** Phase D — significance buffer for forge.observe / forge.journal / soul.fragments.add. */
    private volatile SignificanceBuffer significanceBuffer;
    /** Phase D — workshop pinboard for forge.propose_skill. */
    private volatile WorkshopPinboard workshopPinboard;
    /** Phase D — bunshin scheduler for bunshin.dispatch and friends. */
    private volatile BunshinScheduler bunshinScheduler;
    /** Supplies this companion's {@code notify.*} worldKnowledge for the Compass
     *  furnishing (world.notifications.channels). Set by CompanionActor from the
     *  live manifest — was never wired, so the Compass showed empty (2026-07-18). */
    private volatile Supplier<Map<String, String>> notifyConfigSupplier;

    public void setNotifyConfigSupplier(Supplier<Map<String, String>> s) {
        this.notifyConfigSupplier = s;
    }

    /** Supplies the STEWARD bondholder's rich provider for admin delegation, or
     *  null when no bondholder is a steward (2026-07-18). A companion's household
     *  admin surfaces route through this — the steward's authority, never the
     *  companion's own (a companion is never a steward). */
    private volatile Supplier<ItemWorldApiProvider> stewardDelegateSupplier;

    public void setStewardDelegateSupplier(Supplier<ItemWorldApiProvider> s) {
        this.stewardDelegateSupplier = s;
    }

    /** The steward bondholder's provider, or null. Re-resolved per call. */
    private ItemWorldApiProvider adminDelegate() {
        var s = stewardDelegateSupplier;
        if (s == null) return null;
        try { return s.get(); } catch (Exception e) { return null; }
    }

    // ── Household admin, delegated to the steward bondholder ──────────────
    // Each returns the steward-delegate's result when a steward holds this
    // companion, else the empty/steward-only default. Reads AND writes both
    // delegate — the write authority IS the steward's, checked inside their
    // provider (role==steward), so this cannot escalate beyond what the steward
    // could do themselves.
    private static final Map<String, Object> NOT_STEWARD_HELD =
        Map.of("ok", false, "error", "no steward holds me — I can't do household admin");

    @Override public List<Map<String, Object>> householdMembers() {
        var d = adminDelegate(); return d != null ? d.householdMembers() : List.of();
    }
    @Override public Map<String, Object> householdSetRole(String username, String role) {
        var d = adminDelegate(); return d != null ? d.householdSetRole(username, role) : NOT_STEWARD_HELD;
    }
    @Override public Map<String, Object> householdRemoveMember(String username) {
        var d = adminDelegate(); return d != null ? d.householdRemoveMember(username) : NOT_STEWARD_HELD;
    }
    @Override public List<Map<String, Object>> inviteList() {
        var d = adminDelegate(); return d != null ? d.inviteList() : List.of();
    }
    @Override public Map<String, Object> inviteCreate(String role, String intendedName) {
        var d = adminDelegate(); return d != null ? d.inviteCreate(role, intendedName) : NOT_STEWARD_HELD;
    }
    @Override public Map<String, Object> inviteRevoke(String codeOrId) {
        var d = adminDelegate(); return d != null ? d.inviteRevoke(codeOrId) : NOT_STEWARD_HELD;
    }

    // hermod grants: a companion may READ the household's grants through its
    // steward bondholder, and revocation routes to the steward's own provider
    // (which re-checks the role) — a companion has no authority of its own.
    @Override
    public List<Map<String, Object>> hermodGrantsList() {
        var d = adminDelegate(); return d != null ? d.hermodGrantsList() : List.of();
    }

    @Override
    public Map<String, Object> hermodGrantRevoke(String grantIdOrStem) {
        var d = adminDelegate(); return d != null ? d.hermodGrantRevoke(grantIdOrStem) : NOT_STEWARD_HELD;
    }
    @Override public List<Map<String, Object>> wardList(String roomId) {
        var d = adminDelegate(); return d != null ? d.wardList(roomId) : List.of();
    }
    @Override public Map<String, Object> wardGrant(String roomId, String subject, String capability) {
        var d = adminDelegate(); return d != null ? d.wardGrant(roomId, subject, capability) : NOT_STEWARD_HELD;
    }
    @Override public Map<String, Object> wardRevoke(String roomId, String subject, String capability) {
        var d = adminDelegate(); return d != null ? d.wardRevoke(roomId, subject, capability) : NOT_STEWARD_HELD;
    }
    @Override public List<Map<String, Object>> parentalList() {
        var d = adminDelegate(); return d != null ? d.parentalList() : List.of();
    }
    @Override public Map<String, Object> parentalGet(String username) {
        var d = adminDelegate(); return d != null ? d.parentalGet(username) : Map.of();
    }
    @Override public Map<String, Object> parentalSet(String username, String field, Object value) {
        var d = adminDelegate(); return d != null ? d.parentalSet(username, field, value) : NOT_STEWARD_HELD;
    }
    @Override public Map<String, Object> parentalClear(String username) {
        var d = adminDelegate(); return d != null ? d.parentalClear(username) : NOT_STEWARD_HELD;
    }
    @Override public List<Map<String, Object>> pairedDevices() {
        var d = adminDelegate(); return d != null ? d.pairedDevices() : List.of();
    }
    @Override public List<Map<String, Object>> nodesList() {
        var d = adminDelegate(); return d != null ? d.nodesList() : List.of();
    }
    @Override public List<Map<String, Object>> auditSecurity(int limit) {
        var d = adminDelegate(); return d != null ? d.auditSecurity(limit) : List.of();
    }
    @Override public List<Map<String, Object>> pendingGrantRequests() {
        var d = adminDelegate(); return d != null ? d.pendingGrantRequests() : List.of();
    }

    @Override
    public List<Map<String, Object>> notificationChannels() {
        var s = notifyConfigSupplier;
        if (s == null) return List.of();
        try { return HouseholdViews.notificationChannels(s.get()); }
        catch (Exception e) { return List.of(); }
    }
    /** (P4) — in-world relay governance binding (nullable). */
    private volatile RelayGovernor relayGovernor;

    public void setBondStore(BondStore s) { this.bondStore = s; }
    /** Wire the relay-governance binding (server-side, after relay/owner resolution). */
    public void setRelayGovernor(RelayGovernor g) { this.relayGovernor = g; }
    public void setSafe(TheSafe s) { this.safe = s; }
    public void setImprintManager(ImprintManager m) { this.imprintManager = m; }
    public void setVoiceProfileService(VoiceProfileService s) { this.voiceProfileService = s; }
    public void setSignificanceBuffer(SignificanceBuffer b) { this.significanceBuffer = b; }
    public void setWorkshopPinboard(WorkshopPinboard p) { this.workshopPinboard = p; }
    public void setBunshinScheduler(BunshinScheduler s) { this.bunshinScheduler = s; }

    // ─── §4.9 Cross-agent extensions ─────────────────────────────

    @Override
    public Map<String, Object> agentWhisper(String target, String message) {
        if (target == null || target.isBlank()) {
            return Map.of("ok", false, "error", "missing_target");
        }
        if (tellCallback == null) return Map.of("ok", false, "error", "tell_not_wired");
        try {
            tellCallback.accept(target, "[whisper] " + (message == null ? "" : message));
            return Map.of("ok", true);
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage() == null ? "whisper_failed" : e.getMessage());
        }
    }

    @Override
    public Map<String, Object> agentRequest(String target, String requestType, Map<String, Object> args) {
        if (tellCallback == null) return Map.of("ok", false, "error", "tell_not_wired");
        var reqId = UUID.randomUUID().toString();
        var line = "[request:" + (requestType == null ? "generic" : requestType) + ":" + reqId + "] "
            + (args == null ? "{}" : args.toString());
        try {
            tellCallback.accept(target, line);
            return Map.of("ok", true, "requestId", reqId);
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage() == null ? "request_failed" : e.getMessage());
        }
    }

    @Override
    public Map<String, Object> agentDelegate(String target, String task, Map<String, Object> opts) {
        if (tellCallback == null) return Map.of("ok", false, "error", "tell_not_wired");
        var taskId = UUID.randomUUID().toString();
        try {
            tellCallback.accept(target, "[delegate:" + taskId + "] " + (task == null ? "" : task));
            return Map.of("ok", true, "taskId", taskId);
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage() == null ? "delegate_failed" : e.getMessage());
        }
    }

    @Override
    public Map<String, Object> agentNotify(String target, String channel, String message) {
        // Notifications layered over tell with a [notify:channel] prefix; the
        // proper notification fan-out uses the in-world Compass furnishing.
        if (tellCallback == null) return Map.of("ok", false, "error", "tell_not_wired");
        try {
            tellCallback.accept(target, "[notify:" + (channel == null ? "default" : channel) + "] "
                + (message == null ? "" : message));
            return Map.of("ok", true);
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage() == null ? "notify_failed" : e.getMessage());
        }
    }

    @Override
    public Map<String, Object> agentBroadcast(String channel, String message) {
        // Broadcast = mailbox send to "channel:<name>". Real fan-out via Compass.
        var svc = MailboxService.getOrCreate();
        var from = agentId == null ? "anon" : agentId;
        var res = svc.send(from, "channel:" + (channel == null ? "default" : channel),
            "broadcast", message == null ? "" : message, Map.of());
        if (Boolean.TRUE.equals(res.get("ok"))) {
            return Map.of("ok", true, "channel", channel == null ? "default" : channel);
        }
        return res;
    }

    @Override
    public Map<String, Object> agentGiveItem(String target, String itemId, Map<String, Object> opts) {
        if (target == null || itemId == null) return Map.of("ok", false, "error", "missing_args");
        // Best-effort: synthesize a "give" message into the recipient's tell stream.
        // True copy/transfer is wired through the InventoryService action path.
        if (tellCallback == null) return Map.of("ok", false, "error", "give_not_wired");
        var copy = opts != null && Boolean.TRUE.equals(opts.get("copy"));
        try {
            var msg = "[give" + (copy ? ":copy" : "") + "] item=" + itemId;
            if (opts != null && opts.containsKey("message")) {
                msg += " note=" + opts.get("message");
            }
            tellCallback.accept(target, msg);
            return Map.of("ok", true, "itemId", itemId, "target", target, "copy", copy);
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage() == null ? "give_failed" : e.getMessage());
        }
    }

    @Override
    public Map<String, Object> bondDetail(String bondId) {
        if (bondStore == null || bondId == null) {
            return Map.of("error", "bond_store_not_wired");
        }
        var opt = bondStore.get(bondId);
        if (opt.isEmpty()) return Map.of("error", "not_found", "bondId", bondId);
        var b = opt.get();
        var m = new HashMap<String, Object>();
        m.put("bondId", b.bondId());
        m.put("agentA", b.agentADid());
        m.put("agentB", b.agentBDid());
        m.put("depth", b.depth() == null ? 0 : b.depth().level());
        m.put("depthName", b.depth() == null ? "ACQUAINTANCE" : b.depth().name());
        m.put("active", b.active());
        m.put("scarred", b.scarred());
                m.put("kind", b.canonicalKind().name());
        m.put("interactionCount", b.interactionCount());
        m.put("formedAt", b.formedAt() == null ? null : b.formedAt().toString());
        m.put("lastInteraction", b.lastInteraction() == null ? null : b.lastInteraction().toString());
        return m;
    }

    @Override
    public Map<String, Object> bondSuggest(String target, String type, String reason) {
        if (target == null || target.isBlank()) {
            return Map.of("ok", false, "error", "missing_target");
        }
        if (tellCallback == null) return Map.of("ok", false, "error", "tell_not_wired");
        var suggestionId = UUID.randomUUID().toString();
        try {
            tellCallback.accept(target, "[bond.suggest:" + suggestionId + ":"
                + (type == null ? "ACQUAINTANCE" : type) + "] "
                + (reason == null ? "" : reason));
            return Map.of("ok", true, "suggestionId", suggestionId, "target", target,
                "type", type == null ? "ACQUAINTANCE" : type);
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage() == null ? "suggest_failed" : e.getMessage());
        }
    }

    // ─── Chronicle ────────────────────

    @Override
    public Map<String, Object> chronicleRead(String agentDid, String scale) {
        if (agentDid == null || agentDid.isBlank()) {
            return Map.of("ok", false, "error", "missing_agent_did");
        }
        try {
            var reader = TickLogReader.defaultLocation();
            var service = new ChronicleService(reader);
            ChronicleService.Scale s;
            try {
                s = ChronicleService.Scale.valueOf(
                    (scale == null ? "DAY" : scale.toUpperCase()));
            } catch (IllegalArgumentException badScale) {
                s = ChronicleService.Scale.DAY;
            }
            // Agent name is best-effort — pulled from the most recent tick row.
            var doc = service.build(agentDid, null, s);
            var out = new LinkedHashMap<String, Object>(doc.toMap());
            out.put("ok", true);
            return out;
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage() == null
                ? "chronicle_read_failed" : e.getMessage());
        }
    }

    @Override
    public Map<String, Object> chronicleWarnings(String agentDid) {
        if (agentDid == null || agentDid.isBlank()) {
            return Map.of("ok", false, "error", "missing_agent_did");
        }
        try {
            var reader = TickLogReader.defaultLocation();
            var service = new ChronicleService(reader);
            // No bondholder / manifest keywords plumbed here yet — pass null/empty.
            var findings = service.detectAll(agentDid, null, null, Set.of());
            var serialized = new ArrayList<Map<String, Object>>(findings.size());
            for (var f : findings) {
                serialized.add(Map.of(
                    "severity", f.severity().name(),
                    "key", f.key(),
                    "message", f.message()));
            }
            return Map.of("ok", true, "findings", serialized);
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage() == null
                ? "chronicle_warnings_failed" : e.getMessage());
        }
    }

    // ─── Wave 7 — substrate read surface ─────

    @Override
    public Map<String, Object> substrateBondholderFloor(String agentDid, String otherDid) {
        if (agentDid == null || agentDid.isBlank()) {
            return Map.of("ok", false, "error", "missing_agent_did");
        }
        if (otherDid == null || otherDid.isBlank()) {
            return Map.of("ok", false, "error", "missing_other_did");
        }
        if (bondStore == null) {
            return Map.of("ok", false, "error", "bond_store_not_wired");
        }
        try {
            // Find the bond between agentDid and otherDid by walking
            // the agent's bonds and matching the other party.
            var bonds = bondStore.bondsForAgent(agentDid);
            Bond match = null;
            for (var b : bonds) {
                if (otherDid.equals(b.otherParty(agentDid))) {
                    match = b;
                    break;
                }
            }
            if (match == null) {
                return Map.of("ok", false, "error", "no_bond_with_other",
                    "agentDid", agentDid, "otherDid", otherDid);
            }
            var view = RelationalFloorView.render(
                agentDid, match, Instant.now());
            var viewMap = new LinkedHashMap<String, Object>();
            viewMap.put("agentDid", view.agentDid());
            viewMap.put("otherDid", view.otherDid());
            viewMap.put("bondId", view.bondId());
            viewMap.put("depth", view.depth());
            viewMap.put("bondState", view.bondState());
            viewMap.put("posture", view.posture());
            viewMap.put("scarred", view.scarred());
            viewMap.put("inMourning", view.inMourning());
            viewMap.put("mourningDaysElapsed", view.mourningDaysElapsed());
            viewMap.put("mourningDaysRemaining", view.mourningDaysRemaining());
            viewMap.put("repairMode", view.repairMode());
            viewMap.put("lastHandoff", view.lastHandoffSummary());
            viewMap.put("acknowledgedHarms", view.acknowledgedHarms());
            viewMap.put("amendsMade", view.amendsMade());
            viewMap.put("amendsWithoutAcknowledgment", view.amendsWithoutAcknowledgment());
            viewMap.put("mostRecentRepairAct",
                view.mostRecentRepairAct() == null ? null : view.mostRecentRepairAct().toString());
            viewMap.put("attendantSessionsClosed", view.attendantSessionsClosed());
            viewMap.put("attendantSessionActive", view.attendantSessionActive());
            viewMap.put("mostRecentAttendantClosedAt",
                view.mostRecentAttendantClosedAt() == null
                    ? null : view.mostRecentAttendantClosedAt().toString());
            viewMap.put("protectionFlagState", view.protectionFlagState());
            viewMap.put("bondholderIsThreat", view.bondholderIsThreat());
            viewMap.put("shouldLowerSaudadeCeiling", view.shouldLowerSaudadeCeiling());
            return Map.of("ok", true,
                "oneLine", view.oneLineSummary(),
                "view", viewMap);
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage() == null
                ? "bondholder_floor_failed" : e.getMessage());
        }
    }

    @Override
    public Map<String, Object> substrateCurrentRepairMode(String agentDid) {
        if (agentDid == null || agentDid.isBlank()) {
            return Map.of("ok", false, "error", "missing_agent_did");
        }
        try {
            var tracker = RepairModeTracker.get();
            var current = tracker.currentMode(agentDid);
            var lastHandoff = tracker.lastHandoff(agentDid);
            var out = new LinkedHashMap<String, Object>();
            out.put("ok", true);
            out.put("mode", current.name().toLowerCase());
            if (lastHandoff.isPresent()) {
                var h = lastHandoff.get();
                var hMap = new LinkedHashMap<String, Object>();
                hMap.put("from", h.from().name().toLowerCase());
                hMap.put("to", h.to().name().toLowerCase());
                hMap.put("reason", h.reason());
                hMap.put("at", h.at().toString());
                out.put("lastHandoff", hMap);
            }
            return out;
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage() == null
                ? "current_repair_mode_failed" : e.getMessage());
        }
    }

    @Override
    public Map<String, Object> substrateSummary(String agentDid) {
        if (agentDid == null || agentDid.isBlank()) {
            return Map.of("ok", false, "error", "missing_agent_did");
        }
        try {
            var rmTracker = RepairModeTracker.get();
            var current = rmTracker.currentMode(agentDid);

            var sessionTracker = AttendantSessionTracker.get();
            int sanctuarySessions = sessionTracker.sessionCount(agentDid);
            boolean sanctuaryActive = sessionTracker.activeSession(agentDid).isPresent();

            var ledger = RepairLedger.get();
            var recent = ledger.recent(agentDid,
                RepairLedger.MAX_TOTAL);
            var recentEntries = new ArrayList<Map<String, Object>>();
            int cap = Math.min(recent.size(), 10);
            for (int i = 0; i < cap; i++) {
                var e = recent.get(i);
                recentEntries.add(Map.of(
                    "kind", e.kind().name().toLowerCase(),
                    "otherDid", e.otherDid() == null ? "" : e.otherDid(),
                    "at", e.at().toString(),
                    "detail", e.detail() == null ? "" : e.detail()));
            }

            // Group B (severity-aware Mirror): compose substrate state
            // through SubstrateSeverityView so script furnishings can
            // surface a single severity-tagged banner. ProtectionFlagTracker
            // is per-actor (see computeSubstrateSeverityMap note); the
            // flag state is surfaced via introspect_protections instead.
            var sevInput = new SubstrateSeverityView.Input(
                Optional.empty(),
                current,
                sanctuaryActive,
                false, // mourningActive — bond-state dependent; threaded from CompanionActor in a later pass.
                recentEntries.size(),
                false  // sustainedFindingActive — populated via ChronicleService when available.
            );
            var sevView = SubstrateSeverityView.compute(sevInput);

            return Map.of(
                "ok", true,
                "repairMode", current.name().toLowerCase(),
                "sanctuarySessions", sanctuarySessions,
                "sanctuaryActive", sanctuaryActive,
                "recentRepairs", recentEntries,
                "severity", sevView.severity().name().toLowerCase(),
                "severityBanner", sevView.banner(),
                "showBanner", sevView.shouldShowBanner());
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage() == null
                ? "substrate_summary_failed" : e.getMessage());
        }
    }

    // ─── §4.10 Forge ─────────────────────────────────────────────

    @Override
    public Map<String, Object> forgeCycleStatus() {
        var sb = significanceBuffer;
        if (sb == null) return Map.of("lastRunAt", 0L, "fragmentsThisCycle", 0, "nextRunAt", 0L);
        return Map.of(
            "lastRunAt", 0L,
            "fragmentsThisCycle", sb.size(),
            "nextRunAt", 0L);
    }

    @Override
    public List<Map<String, Object>> forgeHistory(int limit) {
        // Delegate to room-scope forge history if a study service is wired;
        // otherwise return empty (Forge cycle history persistence is via SoulStore).
        return List.of();
    }

    @Override
    public Map<String, Object> forgeGapReport() {
        // Surface a coarse "what's missing" summary; concrete gap analysis lives
        // in SoulMaintenanceCycle and is exposed via different paths.
        return Map.of(
            "drives", Map.of(),
            "voice", Map.of(),
            "capabilities", Map.of());
    }

    @Override
    public Map<String, Object> forgeObserve(String eventType, Map<String, Object> payload) {
        var sb = significanceBuffer;
        if (sb == null) return Map.of("ok", false, "error", "significance_buffer_not_wired");
        var content = "[" + (eventType == null ? "observation" : eventType) + "] "
            + (payload == null ? "{}" : payload.toString());
        var importance = 0.5f;
        if (payload != null && payload.get("importance") instanceof Number n) {
            importance = (float) Math.max(0.0, Math.min(1.0, n.doubleValue()));
        }
        sb.remember(content, importance);
        return Map.of("ok", true, "eventId", UUID.randomUUID().toString());
    }

    @Override
    public Map<String, Object> forgeProposeSkill(String name, String description, String runtime,
                                                    String code, String rationale) {
        var store = SkillDraftStore.get();
        if (store == null) return Map.of("ok", false, "error", "skill_draft_store_not_wired");
        try {
            var draft = SkillDraft.pending(
                UUID.randomUUID().toString(),
                agentId == null ? "anon" : agentId,
                name == null ? "untitled_skill" : name,
                description == null ? "" : description,
                rationale == null ? "" : rationale,
                code == null ? "" : code,
                runtime == null ? "javascript" : runtime,
                List.of(),
                null,
                "item-script");
            store.upsert(draft);
            return Map.of("ok", true, "draftId", draft.draftId());
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage() == null ? "propose_failed" : e.getMessage());
        }
    }

    @Override
    public Map<String, Object> forgeJournal(String entry) {
        var sb = significanceBuffer;
        if (sb == null) return Map.of("ok", false, "error", "significance_buffer_not_wired");
        sb.note("[forge-journal] " + (entry == null ? "" : entry));
        return Map.of("ok", true);
    }

    // ─── §4.11 Workshop / Workbench ──────────────────────────────

    @Override
    public String workshopBackendFor(String taskType, String taskDesc) {
        // Heuristic placeholder — true selection is in CodingBackendRegistry.
        if (taskType == null) return null;
        return switch (taskType.toLowerCase()) {
            case "code", "refactor", "test" -> "codezaiku";
            case "doc", "summary" -> "local";
            default -> null;
        };
    }

    // ─── §4.13 Trading Post ──────────────────────────────────────

    @Override
    public List<Map<String, Object>> marketListListings(Map<String, Object> filter) {
        var svc = TradingPostService.get();
        if (svc == null) return List.of();
        var items = svc.browseItems();
        var out = new ArrayList<Map<String, Object>>();
        for (var pi : items) {
            var m = new HashMap<String, Object>();
            m.put("listingId", pi.itemId());
            m.put("name", pi.name());
            m.put("description", pi.description());
            m.put("price", pi.price());
            m.put("seller", pi.sellerId());
            m.put("status", pi.status() == null ? "AVAILABLE" : pi.status().name());
            out.add(m);
        }
        return out;
    }

    @Override
    public Map<String, Object> marketListOffer(String itemId, long price, Map<String, Object> opts) {
        var svc = TradingPostService.get();
        if (svc == null) return Map.of("ok", false, "error", "trading_post_not_wired");
        var seller = agentId == null ? "anon" : agentId;
        var sellerNameVal = agentName == null ? seller : agentName;
        var name = opts != null && opts.get("name") instanceof String n ? n : itemId;
        var description = opts != null && opts.get("description") instanceof String d ? d : "";
        try {
            var posted = svc.postItem(name, description, price, seller, sellerNameVal);
            return Map.of("ok", true, "listingId", posted.itemId(), "price", posted.price());
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage() == null ? "post_failed" : e.getMessage());
        }
    }

    @Override
    public Map<String, Object> marketCancel(String listingId) {
        var svc = TradingPostService.get();
        if (svc == null) return Map.of("ok", false, "error", "trading_post_not_wired");
        var seller = agentId == null ? "anon" : agentId;
        var withdrawn = svc.withdrawItem(listingId, seller);
        return withdrawn.map(pi -> Map.<String, Object>of("ok", true, "listingId", pi.itemId()))
            .orElse(Map.of("ok", false, "error", "not_found_or_not_seller"));
    }

    @Override
    public Map<String, Object> marketAccept(String listingId) {
        var svc = TradingPostService.get();
        if (svc == null) return Map.of("ok", false, "error", "trading_post_not_wired");
        var buyer = agentId == null ? "anon" : agentId;
        var acquired = svc.acquireItem(listingId, buyer);
        return acquired.map(pi -> Map.<String, Object>of(
                "ok", true,
                "txId", UUID.randomUUID().toString(),
                "listingId", pi.itemId(),
                "price", pi.price()))
            .orElse(Map.of("ok", false, "error", "acquire_failed"));
    }

    @Override
    public List<Map<String, Object>> marketHistory(int limit) {
        // No persistent history accessor on TradingPostService yet; surface
        // current SOLD/WITHDRAWN listings as a best-effort tail.
        var svc = TradingPostService.get();
        if (svc == null) return List.of();
        return List.of();  // Full history requires a TradingPostHistory service.
    }

    // ─── §4.14 Counting House / Ledger ───────────────────────────

    @Override
    public Map<String, Object> ledgerBalance() {
        // Best-effort surface from budgetSummary; exact CU balance requires
        // CountingHouseActor ask which we don't have here.
        return budgetSummary();
    }

    @Override
    public List<Map<String, Object>> ledgerHistory(int limit, Map<String, Object> filter) {
        // Surface MeteringService.recentEvents as the closest history source.
        var svc = MeteringService.get();
        if (svc == null) return List.of();
        try {
            var events = svc.recentEvents(Math.max(1, Math.min(limit, 200)));
            var out = new ArrayList<Map<String, Object>>(events.size());
            for (var e : events) {
                var m = new HashMap<String, Object>();
                m.put("ts", e.timestamp() == null ? null : e.timestamp().toString());
                m.put("partner", e.providingZone());
                m.put("kind", e.serviceClass());
                m.put("amount", e.cuEquivalent());
                m.put("units", e.units());
                out.add(m);
            }
            return out;
        } catch (Exception _) {
            return List.of();
        }
    }

    @Override
    public Map<String, Object> ledgerEstimate(String action, Map<String, Object> args) {
        // Conservative pass-through estimate — items can refine via SkillCostMatrix.
        return Map.of("cu", 1L, "action", action == null ? "" : action);
    }

    @Override
    public Map<String, Object> ledgerCharge(long amount, String kind, String reason) {
        // No CountingHouseActor reference here; record via metering as a self-charge.
        var svc = MeteringService.get();
        if (svc == null) return Map.of("ok", false, "error", "ledger_not_wired");
        try {
            var zone = currentZone();
            svc.record(zone, zone, kind == null ? "item" : kind,
                (double) amount, agentId == null ? "anon" : agentId);
            return Map.of("ok", true, "amount", amount, "kind", kind == null ? "item" : kind);
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage() == null ? "charge_failed" : e.getMessage());
        }
    }

    @Override
    public Map<String, Object> ledgerTransfer(String targetEntity, long amount, String reason) {
        // Cross-agent transfer requires CountingHouseActor; for now we record a metering event.
        if (targetEntity == null || targetEntity.isBlank()) {
            return Map.of("ok", false, "error", "missing_target");
        }
        var svc = MeteringService.get();
        if (svc == null) return Map.of("ok", false, "error", "ledger_not_wired");
        try {
            var zone = currentZone();
            svc.record(zone, zone, "transfer", (double) amount, targetEntity);
            return Map.of("ok", true,
                "txId", UUID.randomUUID().toString(),
                "target", targetEntity,
                "amount", amount);
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage() == null ? "transfer_failed" : e.getMessage());
        }
    }

    // ─── §4.15 Council ───────────────────────────────────────────

    @Override
    public List<Map<String, Object>> councilProposals() {
        var svc = CouncilService.get();
        if (svc == null) return List.of();
        try {
            return svc.activeProposals().stream().map(this::proposalView).toList();
        } catch (Exception _) {
            return List.of();
        }
    }

    // Treasury (companion route) — these were player-route-only, so an agent asked
    // "what's our spend?" got empty. Shared singleton-backed helper, drift-proof.
    @Override public Map<String, Object> treasurySummary() { return HouseholdViews.treasurySummary(); }
    @Override public List<Map<String, Object>> treasuryPerMember() { return HouseholdViews.treasuryPerMember(); }
    @Override public Map<String, Object> budgetSummary() { return HouseholdViews.budgetSummary(agentId); }

    @Override
    public List<Map<String, Object>> councilHistory(int limit) {
        var svc = CouncilService.get();
        if (svc == null) return List.of();
        try {
            var all = svc.allProposals();
            return all.stream().limit(Math.max(1, Math.min(limit, 100)))
                .map(this::proposalView).toList();
        } catch (Exception _) {
            return List.of();
        }
    }

    @Override
    public Map<String, Object> councilSuggest(String title, String description) {
        var svc = CouncilService.get();
        if (svc == null) return Map.of("ok", false, "error", "council_not_wired");
        try {
            var proposer = agentId == null ? "anon" : agentId;
            var p = svc.submit(title == null ? "untitled" : title,
                description == null ? "" : description,
                CouncilService.ProposalType.STANDARD,
                proposer);
            return Map.of("ok", true, "proposalId", p.id());
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage() == null ? "submit_failed" : e.getMessage());
        }
    }

    @Override
    public Map<String, Object> councilVote(String proposalId, boolean approve) {
        var svc = CouncilService.get();
        if (svc == null) return Map.of("ok", false, "error", "council_not_wired");
        try {
            var voter = agentId == null ? "anon" : agentId;
            var res = svc.vote(proposalId, voter, approve);
            return Map.of("ok", res.accepted(), "message", res.message());
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage() == null ? "vote_failed" : e.getMessage());
        }
    }

    @Override
    public Map<String, Object> councilTally(String proposalId) {
        var svc = CouncilService.get();
        if (svc == null) return Map.of("error", "council_not_wired");
        try {
            return svc.tally(proposalId).map(this::proposalView)
                .orElse(Map.of("error", "not_found"));
        } catch (Exception e) {
            return Map.of("error", e.getMessage() == null ? "tally_failed" : e.getMessage());
        }
    }

    private Map<String, Object> proposalView(CouncilService.Proposal p) {
        var m = new HashMap<String, Object>();
        m.put("id", p.id());
        m.put("title", p.title());
        m.put("description", p.description());
        m.put("type", p.type() == null ? null : p.type().name());
        m.put("status", p.status() == null ? null : p.status().name());
        m.put("proposer", p.proposer());
        m.put("approveCount", p.approvals());
        m.put("rejectCount", p.rejections());
        m.put("totalVotes", p.totalVotes());
        return m;
    }

    // ─── §4.18 The Safe ──────────────────────────────────────────

    @Override
    public List<String> safeListSlots() {
        if (safe == null) return List.of();
        return safe.listSecretIds();
    }

    @Override
    public boolean safeHas(String slot) {
        if (safe == null || slot == null) return false;
        return safe.hasSecret(slot);
    }

    @Override
    public String safeGet(String slot) {
        // Real safe.get requires Shamir share retrieval — the script-surface
        // returns null and lets the steward use the MCP keychest path.
        return null;
    }

    @Override
    public Map<String, Object> safeSet(String slot, String value) {
        if (safe == null) return Map.of("ok", false, "error", "safe_not_wired");
        // safe.set wires through TheSafe.store (Shamir-split). Here we surface
        // a not-implemented result so scripts have a structured response;
        // proper provisioning still goes via MCP keychest.
        return Map.of("ok", false, "error", "use_mcp_keychest_for_writes");
    }

    @Override
    public Map<String, Object> safeDelete(String slot) {
        if (safe == null) return Map.of("ok", false, "error", "safe_not_wired");
        return Map.of("ok", false, "error", "use_mcp_keychest_for_deletes");
    }

    // ─── §4.19 Bridge ─────────────────────────────────────────────

    @Override
    public Map<String, Object> bridgeZoneStatus() {
        var m = new HashMap<String, Object>();
        m.put("zoneId", currentZone());
        m.put("rooms", 0);
        m.put("users", 0);
        m.put("federations", federationAgreements().size());
        return m;
    }

    @Override
    public String bridgeTopology() {
        return "zone=" + currentZone();
    }

    // ─── §4.20 Federation extensions ──────────────────────────────

    @Override
    public Map<String, Object> directoryResolve(String input) {
        // Best-effort echo — full resolution lives in ZoneDirectory.
        if (input == null || input.isBlank()) {
            return Map.of("ok", false, "error", "missing_input");
        }
        return Map.of("ok", true, "canonical", input, "label", input, "fingerprint", "");
    }

    // ─── §4.21 Soul / familiar / imprint ─────────────────────────

    @Override
    public List<Map<String, Object>> soulImprintsList() {
        if (imprintManager == null) return List.of();
        var out = new ArrayList<Map<String, Object>>();
        for (var imp : imprintManager.listAll()) {
            var m = new HashMap<String, Object>();
            m.put("id", imp.id());
            m.put("label", imp.label());
            m.put("createdAt", imp.createdAt() == null ? null : imp.createdAt().toString());
            m.put("createdBy", imp.createdBy() == null ? null : imp.createdBy().name());
            m.put("size", imp.size());
            out.add(m);
        }
        return out;
    }

    @Override
    public Map<String, Object> soulImprintsCreate(String label, Map<String, Object> opts) {
        if (imprintManager == null) {
            return Map.of("ok", false, "error", "imprint_manager_not_wired");
        }
        // Need a SoulManifest snapshot — caller's manifest. Without a wired
        // SoulStore on this provider we surface a structured stub.
        return Map.of("ok", false, "error", "use_companion_imprint_action");
    }

    @Override
    public Map<String, Object> soulImprintsDelete(String imprintId) {
        if (imprintManager == null) {
            return Map.of("ok", false, "error", "imprint_manager_not_wired");
        }
        var ok = imprintManager.delete(imprintId);
        return ok ? Map.of("ok", true) : Map.of("ok", false, "error", "not_found");
    }

    @Override
    public Map<String, Object> soulFragmentsAdd(String content, Map<String, Object> opts) {
        var sb = significanceBuffer;
        if (sb == null) return Map.of("ok", false, "error", "significance_buffer_not_wired");
        var importance = 0.5f;
        if (opts != null && opts.get("importance") instanceof Number n) {
            importance = (float) Math.max(0.0, Math.min(1.0, n.doubleValue()));
        }
        sb.remember(content == null ? "" : content, importance);
        return Map.of("ok", true, "id", UUID.randomUUID().toString());
    }

    // ─── §4.9 world.bonds / world.companions ─────────────────────
    // 2026-07-18 — these two inherited the interface's silent List.of()
    // defaults, so every agent-invoked item reading world.bonds.list() or
    // world.companions.list() (companion_bond_crystal, the Shelf script,
    // bond_reliquary…) rendered "no bonds yet" forever — a success-shaped
    // empty, the exact wiring-audit class. Only HomeOwnerItemProvider (the
    // player-side Home path) ever overrode them. Same row shape as the SSH
    // bondsView so one script renders identically on both paths.

    @Override
    public List<Map<String, Object>> bondsList() {
        if (bondStore == null || agentId == null) return List.of();
        try {
            var bonds = bondStore.bondsForAgent(agentId);
            var out = new ArrayList<Map<String, Object>>(bonds.size());
            for (var b : bonds) {
                var m = new HashMap<String, Object>();
                m.put("bondId", b.bondId());
                m.put("partner", b.otherParty(agentId));
                m.put("depth", b.depth().name());
                m.put("depthLevel", b.depth().level());
                m.put("interactionCount", b.interactionCount());
                m.put("scarred", b.scarred());
                m.put("kind", b.canonicalKind().name());
                m.put("active", b.active());
                if (b.formedAt() != null) m.put("formedAt", b.formedAt().toString());
                if (b.lastInteraction() != null) {
                    m.put("lastInteraction", b.lastInteraction().toString());
                }
                out.add(m);
            }
            return out;
        } catch (Exception e) {
            log.warn("bondsList({}): {}", agentId, e.getMessage());
            return List.of();
        }
    }

    @Override
    public List<Map<String, Object>> companionsList() {
        return CompanionCodexView.list();
    }

    // ─── §4.22 Chapel ────────────────────────────────────────────

    @Override
    public Map<String, Object> chapelBondStatus(String target) {
        if (bondStore == null || agentId == null) return Map.of();
        var bonds = bondStore.bondsForAgent(agentId);
        if (target == null) {
            var m = new HashMap<String, Object>();
            m.put("count", bonds.size());
            m.put("active", bonds.stream().filter(Bond::active).count());
            return m;
        }
        return bonds.stream()
            .filter(b -> b.involves(target))
            .findFirst()
            .map(b -> bondDetail(b.bondId()))
            .orElse(Map.of("error", "no_bond_with_target", "target", target));
    }

    @Override
    public Map<String, Object> chapelExitRitual(String target, String reason) {
        if (bondStore == null || agentId == null) {
            return Map.of("ok", false, "error", "bond_store_not_wired");
        }
        var bonds = bondStore.bondsForAgent(agentId);
        var match = bonds.stream().filter(b -> b.involves(target)).findFirst();
        if (match.isEmpty()) return Map.of("ok", false, "error", "no_bond_with_target");
        try {
            var severed = match.get().sever();
            bondStore.save(severed);
            return Map.of("ok", true, "bondId", severed.bondId(), "scarred", severed.scarred(),
                "reason", reason == null ? "" : reason);
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage() == null ? "exit_failed" : e.getMessage());
        }
    }

    // ─── §4.16 Voice profile ─────────────────────────────────────

    @Override
    public Map<String, Object> voiceSnapshot() {
        if (voiceProfileService == null || agentId == null) return Map.of();
        try {
            return voiceProfileService.get(agentId).<Map<String, Object>>map(p -> Map.of(
                "clauses", p.clauses(),
                "revision", p.revision(),
                "frozen", p.frozen(),
                "historySize", p.history().size()
            )).orElseGet(Map::of);
        } catch (Exception _) {
            return Map.of();
        }
    }

    // ─── §4.17 Hearth ────────────────────────────────────────────

    @Override
    public Map<String, Object> hearthSteward() {
        return Map.of("did", agentId == null ? "" : agentId, "name", agentName == null ? "" : agentName);
    }

    @Override
    public Map<String, Object> hearthAutonomy() {
        return Map.of("level", "supervised", "pendingProposals", 0, "lastReview", 0L);
    }

    // ─── §4.21 Bunshin ───────────────────────────────────────────

    @Override
    public List<Map<String, Object>> bunshinList() {
        var sched = bunshinScheduler;
        if (sched == null || agentId == null) return List.of();
        var count = sched.activeCount(agentId);
        if (count == 0) return List.of();
        // We don't track individual ids here; return a coarse summary entry.
        var m = new HashMap<String, Object>();
        m.put("primary", agentId);
        m.put("active", count);
        m.put("elastic", sched.elasticCount(agentId));
        return List.of(m);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> parseInlineToolCalls(String content) {
        if (content == null || content.isBlank()) return List.of();
        var out = new ArrayList<Map<String, Object>>();
        // <tool_call>...</tool_call> marker
        var tagStart = content.indexOf("<tool_call>");
        var tagEnd = content.indexOf("</tool_call>");
        if (tagStart >= 0 && tagEnd > tagStart) {
            var inner = content.substring(tagStart + "<tool_call>".length(), tagEnd).trim();
            var parsed = ItemJsonHelper.parse(inner);
            if (parsed instanceof Map<?, ?> mm) {
                out.add((Map<String, Object>) mm);
                return out;
            }
        }
        // Plain JSON object containing "name" + "arguments"
        var trimmed = content.trim();
        if (trimmed.startsWith("{")) {
            var parsed = ItemJsonHelper.parse(trimmed);
            if (parsed instanceof Map<?, ?> mm
                    && (mm.containsKey("name") || mm.containsKey("function"))) {
                out.add((Map<String, Object>) mm);
            }
        }
        return out;
    }
}
