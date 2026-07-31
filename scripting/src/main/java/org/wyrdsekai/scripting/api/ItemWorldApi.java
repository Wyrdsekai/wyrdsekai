package org.wyrdsekai.scripting.api;

import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.graalvm.polyglot.proxy.ProxyObject;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The world API for item scripts, exposed as 'world' in the GraalJS sandbox.
 *
 * <p>Scripts access sub-objects via property access:
 * <pre>
 *   var results = world.library.search("mythology");
 *   var summary = world.llm.summarize(text, "Extract key findings");
 *   world.agent.speak("Here is what I found...");
 * </pre>
 *
 * <p>Each sub-object has {@code @HostAccess.Export} on its methods, required by
 * the EXPLICIT host access policy. The sub-objects themselves are exposed via
 * {@code @HostAccess.Export} fields (GraalJS resolves public fields as JS properties).</p>
 *
 * <p>All service calls delegate to {@link ItemWorldApiProvider}, which is implemented
 * in the core module to avoid circular dependencies.</p>
 */
public class ItemWorldApi {

    /** Acting-entity identity — {@code world.self.did()} / {@code world.self.name()}.
     *  Existed only in script folklore until 2026-07-11: notify_team, web_clipper and
     *  nostr_quill all called it and crashed with TypeError (namespace was never
     *  implemented; the proxy returned null). */
    @HostAccess.Export public final SelfApi self;
    @HostAccess.Export public final LibraryApi library;
    @HostAccess.Export public final WebApi web;
    /** — credentialed network reach (ssh/scp/household). */
    @HostAccess.Export public final NetApi net;
    @HostAccess.Export public final OracleApi oracle;
    @HostAccess.Export public final LlmApi llm;
    @HostAccess.Export public final AgentApi agent;
    @HostAccess.Export public final InventoryApi inventory;
    @HostAccess.Export public final CatalogApi catalog;
    @HostAccess.Export public final ComposeApi compose;
    @HostAccess.Export public final ZoneApi zone;
    /**: audit log view for Home furnishings (e.g. Embers). */
    @HostAccess.Export public final AuditApi audit;
    /**: grants view for Home furnishings (e.g. Board, Mailbox). */
    @HostAccess.Export public final GrantsApi grants;
    /**: the acting Home's identity context. */
    @HostAccess.Export public final HomeApi home;
    /**: resource-usage summary for the Ledger furnishing. */
    @HostAccess.Export public final BudgetApi budget;
    /**: federation agreements for the Manifest furnishing. */
    @HostAccess.Export public final FederationApi federation;
    /**: owned-inventory view for the Trunk furnishing. */
    @HostAccess.Export public final TrunkApi trunk;
    /**: bonds view for the Shelf furnishing. */
    @HostAccess.Export public final BondsApi bonds;
    /** Companion roster view for the Companion Codex furnishing. */
    @HostAccess.Export public final CompanionsApi companions;
    /**: presence view for the Lantern furnishing. */
    @HostAccess.Export public final PresenceApi presence;
    /**: notification channel view for the Compass furnishing. */
    @HostAccess.Export public final NotificationsApi notifications;
    /**: MCP tools view. */
    @HostAccess.Export public final McpApi mcp;
    /**: pending skill drafts for the Pinboard furnishing. */
    @HostAccess.Export public final SkillApi skill;
    /** Pairing requests + household key — Threshold furnishing surface. */
    @HostAccess.Export public final PairingApi pairing;
    /**: drive/vitality snapshot for the Drives Mirror
     *  furnishing in the Hearth. */
    @HostAccess.Export public final DrivesApi drives;
    /**: build/version visibility — local node's
     *  stamp + mesh matrix. Future Custodian Spyglass furnishing will
     *  consume {@code version.mesh()} to surface drift in-world. */
    @HostAccess.Export public final VersionApi version;
    /** — Coding Slate furnishing data. */
    @HostAccess.Export public final CodingApi coding;
    /** — agent-runnable governed recipes (list/inspect/run/status). */
    @HostAccess.Export public final RecipeApi recipe;

    // -§4.3 — universal-write surfaces
    /** §4.1 self/memory namespace (memory.add/search/forget). */
    @HostAccess.Export public final MemoryApi memory;
    /** §4.2 journal namespace (write/search/recent). */
    @HostAccess.Export public final JournalApi journal;
    /** §4.2 notes namespace (add/list/delete). */
    @HostAccess.Export public final NotesApi notes;
    /** §4.2 pinboard namespace (Study pinboard). */
    @HostAccess.Export public final PinboardApi pinboard;
    /** §4.2 tags namespace (cross-scope tag listing). */
    @HostAccess.Export public final TagsApi tags;
    /** §4.3 room namespace (id/name/entities/emit/narrate/properties). */
    @HostAccess.Export public final RoomApi room;
    /** — entity body state (posture, look-at). */
    @HostAccess.Export public final EntityApi entity;

    // -§4.6 — Tier 4 compute namespaces (Phase A2)
    /** §4.4 embedding namespace (encode + similarity). */
    @HostAccess.Export public final EmbedApi embed;
    /** §4.5 schedule namespace (in/cron/cancel/list). */
    @HostAccess.Export public final ScheduleApi schedule;
    /** §4.6 math namespace (sum/mean/clamp/sin/cos/etc — pure functions). */
    @HostAccess.Export public final MathApi math;
    /** §4.6 regex namespace (match/replace/split with bounded timeout). */
    @HostAccess.Export public final RegexApi regex;
    /** §4.6 json namespace (parse/stringify/path/merge/diff via Jackson). */
    @HostAccess.Export public final JsonApi json;
    /** §4.6 date namespace (now/parse/format/add/sub/diff/today/weekday). */
    @HostAccess.Export public final DateApi date;
    /** §4.6 crypto namespace (hash/hmac/random/uuid). */
    @HostAccess.Export public final CryptoApi crypto;
    /** §4.5 time namespace (now/iso/parse/elapsed/tz). */
    @HostAccess.Export public final TimeApi time;

    // -§4.37 — visualization (Phase B+)
    /** §4.35 chart namespace (bar/line/scatter/pie/heatmap/histogram/vega/ascii). */
    @HostAccess.Export public final ChartApi chart;
    /** §4.36 artifact namespace (create/get/list/attach/revoke). */
    @HostAccess.Export public final ArtifactApi artifact;
    /** §4.37 scroll namespace (create/read/list/revise/lock/share). */
    @HostAccess.Export public final ScrollApi scroll;
    // drive.mark — Phase C Tier 5
    /** §4.23 sandboxed filesystem (read/write/list/delete/exists/stat/mkdir). */
    @HostAccess.Export public final FilesystemApi fs;
    /** §4.24 in-world mailbox (inbox/read/mark_read/archive/send to entities). */
    @HostAccess.Export public final MailboxApi mailbox;
    /** §4.1 — drive.mark (vitality drive update; Tier 5 with steward consent). */
    @HostAccess.Export public final DriveApi drive;

    // -§4.22 — Phase D-N (cross-agent + room services).
    /** §4.10 — Forge cycle observation, gap report, skill drafts. */
    @HostAccess.Export public final ForgeApi forge;
    /** — Chronicle (steward testimony+synthesis). */
    @HostAccess.Export public final ChronicleApi chronicle;
    /** Wave 7 — substrate read surface for Study furnishings. */
    @HostAccess.Export public final SubstrateApi substrate;
    /** §4.11 — Workshop dispatch + task lifecycle. */
    @HostAccess.Export public final WorkshopApi workshop;
    /** §4.11 — Workbench (forms + tools + imprints). */
    @HostAccess.Export public final WorkbenchApi workbench;
    /** §4.12 — Crucible task submission. */
    @HostAccess.Export public final CrucibleApi crucible;
    /** §4.12 — Assay test sweeps. */
    @HostAccess.Export public final AssayApi assay;
    /** §4.13 — Trading post (market) listings + trades. */
    @HostAccess.Export public final MarketApi market;
    /** §4.14 — Counting House ledger (balance, charge, transfer). */
    @HostAccess.Export public final LedgerApi ledger;
    /** §4.15 — Council proposals + voting. */
    @HostAccess.Export public final CouncilApi council;
    /** §4.16 — Voice profile read/write surface (Mirror furnishing). */
    @HostAccess.Export public final VoiceApi voice;
    /** §4.17 — Hearth aliases (steward, autonomy, visits, journal). */
    @HostAccess.Export public final HearthApi hearth;
    /** §4.18 — The Safe (key chest). */
    @HostAccess.Export public final SafeApi safe;
    /** §4.19 — The Bridge (observability). */
    @HostAccess.Export public final BridgeApi bridge;
    /** §4.20 — Directory (resolve/discover/locate). */
    @HostAccess.Export public final DirectoryApi directory;
    /** §4.20 — Transit (request/start/visitors). Cross-zone handoff; this
     *  static member owns {@code world.transit}. The §4.42 public-transit
     *  Transitland adapter deliberately registers under {@code transit_rt}
     *  instead, because the script proxy resolves direct members before
     *  dynamic adapter namespaces (see TransitlandAdapter). */
    @HostAccess.Export public final TransitApi transit;
    /** §4.21 — Soul (fragments, imprints, modify). */
    @HostAccess.Export public final SoulApi soul;
    /** §4.21 — Familiar (summon, status, give_copy, name). */
    @HostAccess.Export public final FamiliarApi familiar;
    /** §4.21 — Bunshin (self-fork dispatch + status). */
    @HostAccess.Export public final BunshinApi bunshin;
    /** §4.21 — Form (alias for workbench.shape_form). */
    @HostAccess.Export public final FormApi form;
    /** §4.22 — Chapel bonds + ceremonies. */
    @HostAccess.Export public final ChapelApi chapel;
    /** Host OS actions — steward-allowlisted app launch / file open / url open. */
    @HostAccess.Export public final HostApi host;
    /** (P4) — in-world relay governance (Warden furnishing). */
    @HostAccess.Export public final RelayApi relay;

    // Study control-panel namespaces — household services reachable from
    // steward Study furnishings. Reads degrade to empty on unwired surfaces;
    // writes route the ACTING player's id as caller so service-level
    // permission checks (steward-only) apply.
    /** Household roster + role management (AuthService-backed). */
    @HostAccess.Export public final HouseholdApi household;
    /** Parental controls — per-member limits, quotas, filters (ParentalControlService-backed). */
    @HostAccess.Export public final ParentalApi parental;
    /** Maintenance — mode, backups, staged restore (MaintenanceService-backed). */
    @HostAccess.Export public final MaintenanceApi maintenance;
    /** Invite codes — mint / list / revoke (InviteService-backed). */
    @HostAccess.Export public final InviteApi invite;
    /** Room wards — list / grant / revoke (WardService-backed). */
    @HostAccess.Export public final WardApi ward;
    /** Enrolled household nodes (Between mesh topology snapshot). */
    @HostAccess.Export public final NodesApi nodes;
    /** Household-level resource usage (AgentCostTracker-backed). */
    @HostAccess.Export public final TreasuryApi treasury;

    /** Adapter proxy — {@code world.<namespace>.<method>(args)} per §3.8. */
    private final AdapterProxyResolver adapterResolver;

    /**
     * Construct without a capability set — equivalent to
     * {@link ItemCapabilitySet#UNRESTRICTED}. Used by trusted JVM-baked items.
     */
    public ItemWorldApi(ItemWorldApiProvider provider) {
        this(provider, ItemCapabilitySet.UNRESTRICTED);
    }

    /**
     * Construct with explicit capability gating. Tier 2+ {@code @HostAccess.Export}
     * methods on the sub-APIs consult {@code caps} before delegating to the
     * provider; missing caps raise {@link CapabilityDeniedError}.
     */
    public ItemWorldApi(ItemWorldApiProvider provider, ItemCapabilitySet caps) {
        var c = caps == null ? ItemCapabilitySet.UNRESTRICTED : caps;
        this.self = new SelfApi(provider);
        this.library = new LibraryApi(provider, c);
        this.web = new WebApi(provider, c);
        this.net = new NetApi(provider, c);
        this.oracle = new OracleApi(provider);
        this.llm = new LlmApi(provider, c);
        this.agent = new AgentApi(provider, c);
        this.inventory = new InventoryApi(provider);
        this.catalog = new CatalogApi(provider);
        this.compose = new ComposeApi(provider);
        this.zone = new ZoneApi(provider);
        this.audit = new AuditApi(provider);
        this.grants = new GrantsApi(provider, c);
        this.home = new HomeApi(provider);
        this.budget = new BudgetApi(provider);
        this.federation = new FederationApi(provider, c);
        this.trunk = new TrunkApi(provider);
        this.bonds = new BondsApi(provider, c);
        this.companions = new CompanionsApi(provider);
        this.presence = new PresenceApi(provider, c);
        this.notifications = new NotificationsApi(provider, c);
        this.mcp = new McpApi(provider, c);
        this.skill = new SkillApi(provider, c);
        this.pairing = new PairingApi(provider, c);
        this.drives = new DrivesApi(provider);
        this.version = new VersionApi(provider);
        this.coding = new CodingApi(provider);
        this.recipe = new RecipeApi(provider, c);
        this.memory = new MemoryApi(provider, c);
        this.journal = new JournalApi(provider, c);
        this.notes = new NotesApi(provider, c);
        this.pinboard = new PinboardApi(provider, c);
        this.tags = new TagsApi(provider);
        this.room = new RoomApi(provider, c);
        this.entity = new EntityApi(provider, c);
        // Phase A2 (§4.4-§4.6) — Tier 4 compute surfaces
        this.embed = new EmbedApi(provider, c);
        this.schedule = new ScheduleApi(provider, c);
        this.math = new MathApi();
        this.regex = new RegexApi();
        this.json = new JsonApi(provider);
        this.date = new DateApi(provider);
        this.crypto = new CryptoApi();
        this.time = new TimeApi(provider);
        // Phase B+ (§4.35-§4.37) — visualization surfaces
        this.chart = new ChartApi(provider, c);
        this.artifact = new ArtifactApi(provider, c);
        this.scroll = new ScrollApi(provider, c);
        // Phase C (§4.23 / §4.24 / §4.1 drive.mark) — Tier 5 external surfaces
        this.fs = new FilesystemApi(provider, c);
        this.mailbox = new MailboxApi(provider, c);
        this.drive = new DriveApi(provider, c);
        // Phase D-N (§4.10-§4.22) — cross-agent + room services
        this.forge = new ForgeApi(provider, c);
        this.chronicle = new ChronicleApi(provider);
        this.substrate = new SubstrateApi(provider);
        this.workshop = new WorkshopApi(provider, c);
        this.workbench = new WorkbenchApi(provider, c);
        this.crucible = new CrucibleApi(provider, c);
        this.assay = new AssayApi(provider, c);
        this.market = new MarketApi(provider, c);
        this.ledger = new LedgerApi(provider, c);
        this.council = new CouncilApi(provider, c);
        this.voice = new VoiceApi(provider, c);
        this.hearth = new HearthApi(provider);
        this.safe = new SafeApi(provider, c);
        this.bridge = new BridgeApi(provider, c);
        this.directory = new DirectoryApi(provider, c);
        this.transit = new TransitApi(provider, c);
        this.soul = new SoulApi(provider, c);
        this.familiar = new FamiliarApi(provider, c);
        this.bunshin = new BunshinApi(provider, c);
        this.form = new FormApi(provider, c);
        this.chapel = new ChapelApi(provider, c);
        this.host = new HostApi(provider, c);
        this.relay = new RelayApi(provider, c);
        this.household = new HouseholdApi(provider, c);
        this.parental = new ParentalApi(provider, c);
        this.maintenance = new MaintenanceApi(provider, c);
        this.invite = new InviteApi(provider, c);
        this.ward = new WardApi(provider, c);
        this.nodes = new NodesApi(provider);
        this.treasury = new TreasuryApi(provider, c);
        this.adapterResolver = new AdapterProxyResolver(provider, c);
        this.caps = c;
    }

    private final ItemCapabilitySet caps;

    /**
     * #3 (2026-07-19 OSS hardening) — whether this execution runs with the
     * UNRESTRICTED (trusted) cap set. The sandbox executor uses this to decide
     * the SSRF policy for the raw {@code http} global: trusted items may reach
     * LAN/loopback services, untrusted (crafted/visitor) items may not.
     */
    public boolean isUnrestricted() {
        return caps.isUnrestricted();
    }

    /** {@code world.self} — who am I. */
    public static class SelfApi {
        private final ItemWorldApiProvider provider;
        SelfApi(ItemWorldApiProvider provider) { this.provider = provider; }
        @HostAccess.Export public String did() { return provider.selfDid(); }
        @HostAccess.Export public String name() { return provider.selfName(); }
    }

    /**
     * §3.8 — adapter proxy resolver. Used by {@code ItemScriptExecutor} to
     * publish {@code world.<namespace>} bindings dynamically (e.g.
     * {@code world.github.create_issue}). Returns null when the namespace
     * isn't registered, letting the static field lookups (above) take
     * precedence for built-in surfaces.
     */
    @HostAccess.Export
    public Object resolveDynamicNamespace(String namespace) {
        return adapterResolver.resolve(namespace);
    }

    /**
     * Coding Slate surface.
     *
     * <p>Exposes the per-backend status list used by the {@code Coding Slate}
     * furnishing in the Study.</p>
     */
    public static class CodingApi {
        private final ItemWorldApiProvider provider;
        CodingApi(ItemWorldApiProvider provider) { this.provider = provider; }

        /**
         * Snapshot of all configured coding backends. See
         * {@link ItemWorldApiProvider#codingBackendsStatus()} for the
         * shape. Empty list means no backends are wired (fresh install).
         */
        @HostAccess.Export
        public List<Map<String, Object>> backends() {
            return provider.codingBackendsStatus();
        }
    }

    /** — drive snapshot accessor. */
    public static class DrivesApi {
        private final ItemWorldApiProvider provider;
        DrivesApi(ItemWorldApiProvider provider) { this.provider = provider; }

        /**
         * Returns the caller's current drive + vitality snapshot. Keys:
         * {@code drives} (map of drive name → 0..1 pressure),
         * {@code vitality} (map of tank name → 0..1 fill),
         * {@code mood} (string summary), {@code updatedAtMillis} (snapshot age).
         * Empty when the caller has no published snapshot.
         */
        @HostAccess.Export
        public Map<String, Object> snapshot() {
            return provider.driveSnapshot();
        }
    }

    // ─── Library API ─────────────────────────────────────────────

    public static class LibraryApi {
        private final ItemWorldApiProvider provider;
        private final ItemCapabilitySet caps;

        LibraryApi(ItemWorldApiProvider provider, ItemCapabilitySet caps) {
            this.provider = provider;
            this.caps = caps;
        }

        @HostAccess.Export
        public List<Map<String, Object>> search(String query) {
            return provider.searchKnowledge(query, 10);
        }

        @HostAccess.Export
        public List<Map<String, Object>> search(String query, int limit) {
            return provider.searchKnowledge(query, Math.min(limit, 20));
        }

        @HostAccess.Export
        public Map<String, Object> read(String chunkId) {
            return provider.readKnowledgeChunk(chunkId);
        }

        /** §4.2 library.add(text, opts). opts may include {title, tags, source, type, pack}. */
        @HostAccess.Export
        public Map<String, Object> add(String text, Map<String, Object> opts) {
            caps.require("library.add");
            return provider.libraryAdd(text, opts == null ? Map.of() : opts);
        }

        /**
         * Ingest a directory of documents (ebooks/pdfs/a Calibre library)
         * into the caller's Study — {@code world.library.ingest(path,
         * {collection, mode})}. Confined to the steward's open-roots;
         * runs async. Requires {@code library.ingest}. Tier 6.
         */
        @HostAccess.Export
        public Map<String, Object> ingest(String path) {
            return ingest(path, null);
        }

        @HostAccess.Export
        public Map<String, Object> ingest(String path, Map<String, Object> opts) {
            caps.require("library.ingest");
            var o = opts == null ? Map.<String, Object>of() : opts;
            return provider.libraryIngest(path,
                str(o.get("collection")), str(o.get("mode")));
        }

        private static String str(Object value) {
            return value == null ? null : String.valueOf(value);
        }

        /** §4.2 library.tag(chunkId, tags) — replace tags on an existing chunk. */
        @HostAccess.Export
        public Map<String, Object> tag(String chunkId, List<String> tagList) {
            caps.require("library.tag");
            return provider.libraryTag(chunkId, tagList == null ? List.of() : tagList);
        }

        /** §4.2 library.delete — Tier 5 (sensitive: same-zone agents share library). */
        @HostAccess.Export
        public Map<String, Object> delete(String chunkId) {
            caps.require("library.delete");
            return provider.libraryDelete(chunkId);
        }
    }

    // ─── Memory API (§4.1) ────────────────────────────────────────

    /**
     * §4.1 — significance buffer projection. {@code add} is a namespaced
     * alias for the legacy {@code agent.remember}; {@code search} surfaces
     * what was previously prompt-only; {@code forget} is Tier 5 because
     * item-driven deletion is sensitive (could be used to gaslight).
     */
    public static class MemoryApi {
        private final ItemWorldApiProvider provider;
        private final ItemCapabilitySet caps;

        MemoryApi(ItemWorldApiProvider provider, ItemCapabilitySet caps) {
            this.provider = provider;
            this.caps = caps;
        }

        @HostAccess.Export
        public Map<String, Object> add(String content) {
            caps.require("memory.add");
            provider.agentRemember(content);
            return Map.of("ok", true);
        }

        @HostAccess.Export
        public Map<String, Object> add(String content, List<String> tags) {
            caps.require("memory.add");
            provider.agentRemember(content);
            return Map.of("ok", true, "tags", tags == null ? List.of() : tags);
        }

        @HostAccess.Export
        public List<Map<String, Object>> search(String query) {
            return provider.searchKnowledge(query, 10);
        }

        @HostAccess.Export
        public List<Map<String, Object>> search(String query, int limit) {
            return provider.searchKnowledge(query, Math.min(Math.max(limit, 1), 20));
        }

        @HostAccess.Export
        public Map<String, Object> forget(String id) {
            caps.require("memory.forget");
            return Map.of("ok", false, "error", "memory.forget not yet implemented");
        }
    }

    // ─── Journal API (§4.2) ──────────────────────────────────────

    public static class JournalApi {
        private final ItemWorldApiProvider provider;
        private final ItemCapabilitySet caps;

        JournalApi(ItemWorldApiProvider provider, ItemCapabilitySet caps) {
            this.provider = provider;
            this.caps = caps;
        }

        @HostAccess.Export
        public Map<String, Object> write(String content) {
            caps.require("journal.write");
            return provider.journalWrite(content, Map.of());
        }

        @HostAccess.Export
        public Map<String, Object> write(String content, Map<String, Object> opts) {
            caps.require("journal.write");
            return provider.journalWrite(content, opts == null ? Map.of() : opts);
        }

        @HostAccess.Export
        public List<Map<String, Object>> search(String query) {
            return provider.journalSearch(query, 10);
        }

        @HostAccess.Export
        public List<Map<String, Object>> search(String query, Map<String, Object> opts) {
            int limit = 10;
            if (opts != null && opts.get("limit") instanceof Number n) {
                limit = Math.max(1, Math.min(20, n.intValue()));
            }
            return provider.journalSearch(query, limit);
        }

        @HostAccess.Export
        public List<Map<String, Object>> recent(int n) {
            return provider.journalRecent(Math.max(1, Math.min(n, 50)));
        }
    }

    // ─── Notes API (§4.2) ────────────────────────────────────────

    public static class NotesApi {
        private final ItemWorldApiProvider provider;
        private final ItemCapabilitySet caps;

        NotesApi(ItemWorldApiProvider provider, ItemCapabilitySet caps) {
            this.provider = provider;
            this.caps = caps;
        }

        @HostAccess.Export
        public Map<String, Object> add(String content) {
            caps.require("notes.add");
            return provider.notesAdd(content, List.of());
        }

        @HostAccess.Export
        public Map<String, Object> add(String content, List<String> tags) {
            caps.require("notes.add");
            return provider.notesAdd(content, tags == null ? List.of() : tags);
        }

        @HostAccess.Export
        public List<Map<String, Object>> list() {
            return provider.notesList(null);
        }

        @HostAccess.Export
        public List<Map<String, Object>> list(String tag) {
            return provider.notesList(tag);
        }

        @HostAccess.Export
        public Map<String, Object> delete(String id) {
            caps.require("notes.delete");
            return provider.notesDelete(id);
        }
    }

    // ─── Pinboard API (§4.2) ─────────────────────────────────────

    public static class PinboardApi {
        private final ItemWorldApiProvider provider;
        private final ItemCapabilitySet caps;

        PinboardApi(ItemWorldApiProvider provider, ItemCapabilitySet caps) {
            this.provider = provider;
            this.caps = caps;
        }

        @HostAccess.Export
        public Map<String, Object> pin(String text) {
            caps.require("pinboard.pin");
            return provider.pinboardPin(text, Map.of());
        }

        @HostAccess.Export
        public Map<String, Object> pin(String text, Map<String, Object> opts) {
            caps.require("pinboard.pin");
            return provider.pinboardPin(text, opts == null ? Map.of() : opts);
        }

        @HostAccess.Export
        public List<Map<String, Object>> list() {
            return provider.pinboardList();
        }

        @HostAccess.Export
        public Map<String, Object> unpin(String id) {
            caps.require("pinboard.unpin");
            return provider.pinboardUnpin(id);
        }
    }

    // ─── Tags API (§4.2) ─────────────────────────────────────────

    public static class TagsApi {
        private final ItemWorldApiProvider provider;

        TagsApi(ItemWorldApiProvider provider) { this.provider = provider; }

        @HostAccess.Export
        public List<Map<String, Object>> list() { return provider.tagsList("all"); }

        @HostAccess.Export
        public List<Map<String, Object>> list(String scope) {
            return provider.tagsList(scope == null ? "all" : scope);
        }

        @HostAccess.Export
        public List<Map<String, Object>> entries(String tag) {
            return provider.tagsEntries(tag, "all");
        }

        @HostAccess.Export
        public List<Map<String, Object>> entries(String tag, String scope) {
            return provider.tagsEntries(tag, scope == null ? "all" : scope);
        }
    }

    // ─── Room API (§4.3) ─────────────────────────────────────────

    /**
     * §4.3 — current room read+write surface. Distinct from {@link ZoneApi}
     * which addresses the host zone; this is the room the script's caller
     * is currently in (an item carried into another room sees that room).
     */
    public static class RoomApi {
        private final ItemWorldApiProvider provider;
        private final ItemCapabilitySet caps;

        RoomApi(ItemWorldApiProvider provider, ItemCapabilitySet caps) {
            this.provider = provider;
            this.caps = caps;
        }

        @HostAccess.Export
        public String id() { return provider.roomId(); }

        @HostAccess.Export
        public String name() { return provider.roomName(); }

        @HostAccess.Export
        public String description() { return provider.roomDescription(); }

        @HostAccess.Export
        public List<Map<String, Object>> entities() { return provider.roomEntities(); }

        @HostAccess.Export
        public List<Map<String, Object>> objects() { return provider.roomObjects(); }

        @HostAccess.Export
        public List<Map<String, Object>> exits() { return provider.roomExits(); }

        @HostAccess.Export
        public Map<String, Object> emit(String eventType, Map<String, Object> data) {
            caps.require("room.emit");
            return provider.roomEmit(eventType, data == null ? Map.of() : data);
        }

        @HostAccess.Export
        public Map<String, Object> narrate(String text) {
            caps.require("room.narrate");
            return provider.roomNarrate(text);
        }

        @HostAccess.Export
        public Map<String, Object> addObject(String objId, String objName, String desc) {
            caps.require("room.add_object");
            return provider.roomAddObject(objId, objName, desc, true, null);
        }

        @HostAccess.Export
        public Map<String, Object> addObject(String objId, String objName, String desc,
                                              boolean takeable, Map<String, Object> effects) {
            caps.require("room.add_object");
            return provider.roomAddObject(objId, objName, desc, takeable, effects);
        }

        @HostAccess.Export
        public Map<String, Object> removeObject(String objId) {
            caps.require("room.remove_object");
            return provider.roomRemoveObject(objId);
        }

        @HostAccess.Export
        public Map<String, Object> setProperty(String key, Object value) {
            caps.require("room.set_property");
            return provider.roomSetProperty(key, value);
        }

        @HostAccess.Export
        public String getProperty(String key) {
            return provider.roomGetProperty(key);
        }

        @HostAccess.Export
        public Map<String, Object> updateDescription(String text) {
            caps.require("room.update_description");
            return provider.roomUpdateDescription(text);
        }

        /**
         * broadcast a body-language line attributed to
         * {@code actorId} in the current room. Use when the narration is felt
         * body-text rather than speech — e.g. a chair script narrating "Masumi
         * leans back, watching the embers" after sit. Emitted as {@code Emoted}
         * with a body-language flavor.
         */
        @HostAccess.Export
        public Map<String, Object> broadcastBodyLanguage(String actorId, String text) {
            caps.require("room.broadcast_body_language");
            return provider.roomBroadcastBodyLanguage(actorId, text);
        }
    }

    // ─── Entity API ──────────────────

    /**
     * entity body state.
     *
     * <p>Scripts call {@code world.entity.setPosture(actor.id, {verb, atObject,
     * descriptor, innerImprint?})} to put an actor into a posture; {@code
     * clearPosture(actor.id)} returns them to default. {@code lookAt(actor,
     * target, manner?)} emits the LookedAt scene event for the room.</p>
     */
    public static class EntityApi {
        private final ItemWorldApiProvider provider;
        private final ItemCapabilitySet caps;

        EntityApi(ItemWorldApiProvider provider, ItemCapabilitySet caps) {
            this.provider = provider;
            this.caps = caps == null ? ItemCapabilitySet.UNRESTRICTED : caps;
        }

        @HostAccess.Export
        public Map<String, Object> setPosture(String entityId, Map<String, Object> postureSpec) {
            caps.require("entity.set_posture");
            return provider.entitySetPosture(entityId,
                postureSpec == null ? Map.of() : postureSpec);
        }

        @HostAccess.Export
        public Map<String, Object> clearPosture(String entityId) {
            caps.require("entity.clear_posture");
            return provider.entityClearPosture(entityId);
        }

        @HostAccess.Export
        public Map<String, Object> lookAt(String actorId, String targetId, String manner) {
            caps.require("entity.look_at");
            return provider.entityLookAt(actorId, targetId, manner);
        }

        @HostAccess.Export
        public Map<String, Object> lookAt(String actorId, String targetId) {
            return lookAt(actorId, targetId, null);
        }
    }

    // ─── Adapter proxy resolver (§3.8) ───────────────────────────

    /**
     * Resolves dynamic {@code world.<namespace>} accesses to a per-namespace
     * proxy that routes {@code .<method>(args)} calls through the provider's
     * adapter registry. Method-level capability gating is applied via the
     * shared {@link ItemCapabilitySet}; the cap name is
     * {@code <namespace>.<method>} (or {@code <namespace>.*} wildcard).
     */
    static final class AdapterProxyResolver {
        private final ItemWorldApiProvider provider;
        private final ItemCapabilitySet caps;
        private final ConcurrentHashMap<String, ProxyObject> cache = new ConcurrentHashMap<>();

        AdapterProxyResolver(ItemWorldApiProvider provider, ItemCapabilitySet caps) {
            this.provider = provider;
            this.caps = caps;
        }

        ProxyObject resolve(String namespace) {
            if (namespace == null || namespace.isBlank()) return null;
            var registered = provider.adapterNamespaces();
            if (registered == null || !registered.contains(namespace)) return null;
            return cache.computeIfAbsent(namespace, ns -> new AdapterNamespaceProxy(ns, provider, caps));
        }
    }

    /** Per-namespace proxy: members are method names; calling returns adapter result. */
    static final class AdapterNamespaceProxy implements ProxyObject {
        private final String namespace;
        private final ItemWorldApiProvider provider;
        private final ItemCapabilitySet caps;

        AdapterNamespaceProxy(String namespace, ItemWorldApiProvider provider, ItemCapabilitySet caps) {
            this.namespace = namespace;
            this.provider = provider;
            this.caps = caps;
        }

        @Override
        public Object getMember(String key) {
            return new AdapterMethodProxy(namespace, key, provider, caps);
        }

        @Override
        public Object getMemberKeys() { return List.of(); }

        @Override
        public boolean hasMember(String key) { return true; }

        @Override
        public void putMember(String key, Value value) {
            throw new UnsupportedOperationException("adapter namespaces are read-only");
        }
    }

    /** A single adapter method — invokable from JS as world.&lt;ns&gt;.&lt;method&gt;(args). */
    static final class AdapterMethodProxy implements ProxyExecutable {
        private final String namespace;
        private final String method;
        private final ItemWorldApiProvider provider;
        private final ItemCapabilitySet caps;

        AdapterMethodProxy(String namespace, String method,
                            ItemWorldApiProvider provider, ItemCapabilitySet caps) {
            this.namespace = namespace;
            this.method = method;
            this.provider = provider;
            this.caps = caps;
        }

        @Override
        public Object execute(Value... arguments) {
            // Cap name is <ns>.<method>; the cap-set checks the wildcard <ns>.* automatically.
            caps.require(namespace + "." + method);
            Map<String, Object> args = Map.of();
            if (arguments != null && arguments.length > 0 && arguments[0] != null
                    && arguments[0].hasMembers()) {
                var copy = new LinkedHashMap<String, Object>();
                for (var k : arguments[0].getMemberKeys()) {
                    copy.put(k, arguments[0].getMember(k).as(Object.class));
                }
                args = copy;
            }
            return provider.invokeAdapter(namespace, method, args);
        }
    }

    // ─── Web API ─────────────────────────────────────────────────

    public static class WebApi {
        private final ItemWorldApiProvider provider;
        private final ItemCapabilitySet caps;

        WebApi(ItemWorldApiProvider provider) {
            this(provider, ItemCapabilitySet.UNRESTRICTED);
        }

        WebApi(ItemWorldApiProvider provider, ItemCapabilitySet caps) {
            this.provider = provider;
            this.caps = caps == null ? ItemCapabilitySet.UNRESTRICTED : caps;
        }

        @HostAccess.Export
        public List<Map<String, Object>> search(String query) {
            return provider.webSearch(query, "general", 5);
        }

        @HostAccess.Export
        public List<Map<String, Object>> search(String query, String type) {
            return provider.webSearch(query, type != null ? type : "general", 5);
        }

        @HostAccess.Export
        public List<Map<String, Object>> search(String query, String type, int limit) {
            return provider.webSearch(query, type != null ? type : "general", Math.max(1, Math.min(limit, 25)));
        }

        @HostAccess.Export
        public String fetch(String url) {
            return provider.webFetch(url, 4000);
        }

        @HostAccess.Export
        public String fetch(String url, int maxChars) {
            return provider.webFetch(url, Math.min(maxChars, 16000));
        }

        /** §4.7 — raw fetch with headers/contentType. Tier 4. Domain allowlist enforced. */
        @HostAccess.Export
        public Map<String, Object> fetch_raw(String url) {
            return fetch_raw(url, null);
        }

        @HostAccess.Export
        public Map<String, Object> fetch_raw(String url, Map<String, Object> opts) {
            caps.require("web.fetch_raw");
            if (!isAllowed(url)) {
                return domainDenied(url);
            }
            return provider.webFetchRaw(url, opts == null ? Map.of() : opts);
        }

        /** §4.7 — POST. Tier 5 (external side-effect). Steward consent required. */
        @HostAccess.Export
        public Map<String, Object> post(String url, Object body) {
            return post(url, body, null);
        }

        @HostAccess.Export
        public Map<String, Object> post(String url, Object body, Map<String, Object> opts) {
            caps.require("web.post");
            if (!isAllowed(url)) {
                return domainDenied(url);
            }
            return provider.webPost(url, body, opts == null ? Map.of() : opts);
        }

        /** §4.7 — PUT. Tier 5. */
        @HostAccess.Export
        public Map<String, Object> put(String url, Object body) {
            return put(url, body, null);
        }

        @HostAccess.Export
        public Map<String, Object> put(String url, Object body, Map<String, Object> opts) {
            caps.require("web.put");
            if (!isAllowed(url)) {
                return domainDenied(url);
            }
            return provider.webPut(url, body, opts == null ? Map.of() : opts);
        }

        /** §4.7 — DELETE. Tier 5. */
        @HostAccess.Export
        public Map<String, Object> delete(String url) {
            return delete(url, null);
        }

        @HostAccess.Export
        public Map<String, Object> delete(String url, Map<String, Object> opts) {
            caps.require("web.delete");
            if (!isAllowed(url)) {
                return domainDenied(url);
            }
            return provider.webDelete(url, opts == null ? Map.of() : opts);
        }

        /** §4.7 — introspection: which domains the script can hit. */
        @HostAccess.Export
        public List<String> allowed_domains() {
            return caps.externalDomains();
        }

        // ─── allowlist helpers ──────────────────────────────────────
        private boolean isAllowed(String url) {
            if (caps.isUnrestricted()) return true;
            var domains = caps.externalDomains();
            if (domains.isEmpty()) return false;
            String host;
            try {
                host = URI.create(url).getHost();
            } catch (Exception e) {
                return false;
            }
            if (host == null) return false;
            for (var d : domains) {
                if (matches(host, d)) return true;
            }
            return false;
        }

        private static boolean matches(String host, String pattern) {
            if (pattern == null || pattern.isBlank()) return false;
            // Wildcard form: '*' matches a single label boundary or arbitrary chars within.
            var sb = new StringBuilder("^");
            for (int i = 0; i < pattern.length(); i++) {
                var c = pattern.charAt(i);
                if (c == '*') sb.append("[a-zA-Z0-9_.-]*");
                else if ("\\.+?()[]{}|^$".indexOf(c) >= 0) sb.append('\\').append(c);
                else sb.append(c);
            }
            sb.append("$");
            return host.matches(sb.toString());
        }

        private static Map<String, Object> domainDenied(String url) {
            var out = new LinkedHashMap<String, Object>();
            out.put("status", 0);
            out.put("error", "domain_not_allowed");
            out.put("message", "URL host is not in this item's external_domains allowlist: " + url);
            return out;
        }
    }

    // ─── Net API ───────────────────────
    /**
     * {@code world.net.*} — credentialed network reach for the permission-fixed
     * network items (courier satchel / far-hand / postrider). Every verb is
     * capability-gated at THIS layer (the item manifest must declare the cap)
     * and again ZONE-gated inside the provider's {@link NetworkGate}
     * (steward allowlist). The script gets back a plain result map — never a
     * socket, a key, or a process. HTTP stays on the {@link WebApi} surface
     * (permissive by default); this namespace is the credentialed protocols.
     */
    public static class NetApi {
        private final ItemWorldApiProvider provider;
        private final ItemCapabilitySet caps;

        NetApi(ItemWorldApiProvider provider) {
            this(provider, ItemCapabilitySet.UNRESTRICTED);
        }

        NetApi(ItemWorldApiProvider provider, ItemCapabilitySet caps) {
            this.provider = provider;
            this.caps = caps == null ? ItemCapabilitySet.UNRESTRICTED : caps;
        }

        /** far-hand — run one command on a steward-allowlisted host. */
        @HostAccess.Export
        public Map<String, Object> ssh(String host, String command) {
            return ssh(host, command, null);
        }

        @HostAccess.Export
        public Map<String, Object> ssh(String host, String command, Map<String, Object> opts) {
            caps.require("net.ssh");
            return provider.netSshRun(host, command, opts == null ? Map.of() : opts);
        }

        /** postrider — copy a local file up to an allowlisted host. */
        @HostAccess.Export
        public Map<String, Object> scp_to(String host, String localPath, String remotePath) {
            return scp_to(host, localPath, remotePath, null);
        }

        @HostAccess.Export
        public Map<String, Object> scp_to(String host, String localPath, String remotePath,
                                          Map<String, Object> opts) {
            caps.require("net.scp");
            return provider.netScpTo(host, localPath, remotePath, opts == null ? Map.of() : opts);
        }

        /** postrider — copy a file down from an allowlisted host. */
        @HostAccess.Export
        public Map<String, Object> scp_from(String host, String remotePath, String localPath) {
            return scp_from(host, remotePath, localPath, null);
        }

        @HostAccess.Export
        public Map<String, Object> scp_from(String host, String remotePath, String localPath,
                                            Map<String, Object> opts) {
            caps.require("net.scp");
            return provider.netScpFrom(host, remotePath, localPath, opts == null ? Map.of() : opts);
        }

        /** courier satchel — transfer a file to a household-enrolled peer over the bus. */
        @HostAccess.Export
        public Map<String, Object> household_copy(String nodeId, String localPath, String remotePath) {
            caps.require("net.household");
            return provider.netHouseholdCopy(nodeId, localPath, remotePath);
        }
    }

    // ─── Oracle API ──────────────────────────────────────────────

    public static class OracleApi {
        private final ItemWorldApiProvider provider;

        OracleApi(ItemWorldApiProvider provider) {
            this.provider = provider;
        }

        @HostAccess.Export
        public List<Map<String, Object>> query(String topic) {
            return provider.queryOracle(topic, "patterns");
        }

        @HostAccess.Export
        public List<Map<String, Object>> query(String topic, String analysisType) {
            return provider.queryOracle(topic, analysisType != null ? analysisType : "patterns");
        }
    }

    // ─── LLM API ─────────────────────────────────────────────────

    /**
     * §4.4 — LLM surface. {@code summarize/analyze/complete/classify/extract/tools}
     * are all Tier 4 (consume inference budget); the manifest must declare
     * {@code llm.summarize}, {@code llm.analyze}, etc. {@code budget_remaining}
     * is Tier 1 (implicit cap). Gating applies to scripted items; JVM-baked
     * items pass {@code UNRESTRICTED} and bypass.
     */
    public static class LlmApi {
        private final ItemWorldApiProvider provider;
        private final ItemCapabilitySet caps;

        LlmApi(ItemWorldApiProvider provider) {
            this(provider, ItemCapabilitySet.UNRESTRICTED);
        }

        LlmApi(ItemWorldApiProvider provider, ItemCapabilitySet caps) {
            this.provider = provider;
            this.caps = caps == null ? ItemCapabilitySet.UNRESTRICTED : caps;
        }

        @HostAccess.Export
        public String summarize(String text, String instruction) {
            caps.require("llm.summarize");
            return provider.llmSummarize(text, instruction != null ? instruction : "Summarize the key points.");
        }

        @HostAccess.Export
        public String summarize(String text) {
            caps.require("llm.summarize");
            return provider.llmSummarize(text, "Summarize the key points.");
        }

        @HostAccess.Export
        public String analyze(String text, String prompt) {
            caps.require("llm.analyze");
            return provider.llmAnalyze(text, prompt);
        }

        /** §4.4 — open-ended completion. {@code opts} may include
         *  {@code maxTokens, temperature, stop, system, model}. Returns
         *  {@code {text, latencyMs, tokensIn, tokensOut}}. */
        @HostAccess.Export
        public Map<String, Object> complete(String prompt) {
            caps.require("llm.complete");
            return provider.llmComplete(prompt, Map.of());
        }

        @HostAccess.Export
        public Map<String, Object> complete(String prompt, Map<String, Object> opts) {
            caps.require("llm.complete");
            return provider.llmComplete(prompt, opts == null ? Map.of() : opts);
        }

        /** §4.4 — one-of classification. Returns {@code {label, confidence}}. */
        @HostAccess.Export
        public Map<String, Object> classify(String text, List<String> labels) {
            caps.require("llm.classify");
            return provider.llmClassify(text, labels == null ? List.of() : labels);
        }

        /** §4.4 — schema-constrained extraction. Returns Map of extracted fields. */
        @HostAccess.Export
        public Map<String, Object> extract(String text, Map<String, Object> schema) {
            caps.require("llm.extract");
            return provider.llmExtract(text, schema == null ? Map.of() : schema);
        }

        /** §4.4 — tool-calling generation. Returns
         *  {@code {toolCalls: [...], finalText: ...}}. */
        @HostAccess.Export
        public Map<String, Object> tools(String prompt, List<Map<String, Object>> tools) {
            caps.require("llm.tools");
            return provider.llmTools(prompt, tools == null ? List.of() : tools, Map.of());
        }

        @HostAccess.Export
        public Map<String, Object> tools(String prompt, List<Map<String, Object>> tools,
                                          Map<String, Object> opts) {
            caps.require("llm.tools");
            return provider.llmTools(prompt, tools == null ? List.of() : tools,
                opts == null ? Map.of() : opts);
        }

        /** §4.4 — token+cost budget snapshot. Implicit Tier 1. */
        @HostAccess.Export
        public Map<String, Object> budget_remaining() {
            return provider.llmBudgetRemaining();
        }
    }

    // ─── Embed API (§4.4) ────────────────────────────────────────

    /**
     * §4.4 — vector embeddings. {@code encode} is Tier 4 (consumes inference
     * budget); {@code similarity} is pure math (Tier 1, implicit cap).
     */
    public static class EmbedApi {
        private final ItemWorldApiProvider provider;
        private final ItemCapabilitySet caps;

        EmbedApi(ItemWorldApiProvider provider, ItemCapabilitySet caps) {
            this.provider = provider;
            this.caps = caps;
        }

        /** Encode text → vector. Tier 4 cap: {@code embed.encode}. */
        @HostAccess.Export
        public List<Double> encode(String text) {
            caps.require("embed.encode");
            return provider.embedEncode(text);
        }

        /** Cosine similarity of two vectors. -1..1. Implicit Tier 1. */
        @HostAccess.Export
        public double similarity(List<Number> a, List<Number> b) {
            if (a == null || b == null || a.isEmpty() || b.isEmpty()) return 0.0;
            int n = Math.min(a.size(), b.size());
            double dot = 0.0, na = 0.0, nb = 0.0;
            for (int i = 0; i < n; i++) {
                double x = a.get(i).doubleValue();
                double y = b.get(i).doubleValue();
                dot += x * y;
                na += x * x;
                nb += y * y;
            }
            if (na == 0.0 || nb == 0.0) return 0.0;
            double cos = dot / (Math.sqrt(na) * Math.sqrt(nb));
            // Clamp to handle FP drift outside [-1, 1].
            if (cos > 1.0) return 1.0;
            if (cos < -1.0) return -1.0;
            return cos;
        }
    }

    // ─── Schedule API (§4.5) ─────────────────────────────────────

    /**
     * §4.5 — item-owned schedules. {@code in/cron/cancel} are Tier 4 (structural
     * write — the executor persists timers); {@code list} is implicit Tier 1.
     * Schedules are owner-scoped: only the owning agent can create or cancel
     * its own timers.
     */
    public static class ScheduleApi {
        private final ItemWorldApiProvider provider;
        private final ItemCapabilitySet caps;

        ScheduleApi(ItemWorldApiProvider provider, ItemCapabilitySet caps) {
            this.provider = provider;
            this.caps = caps;
        }

        /** Schedule a one-shot callback {@code seconds} from now. Returns {@code {timerId}}. */
        @HostAccess.Export
        public Map<String, Object> in(int seconds, String hookName, Map<String, Object> payload) {
            caps.require("schedule.in");
            return provider.scheduleIn(seconds, hookName,
                payload == null ? Map.of() : payload);
        }

        @HostAccess.Export
        public Map<String, Object> in(int seconds, String hookName) {
            caps.require("schedule.in");
            return provider.scheduleIn(seconds, hookName, Map.of());
        }

        /** Schedule a recurring callback. Validated against per-second crons. */
        @HostAccess.Export
        public Map<String, Object> cron(String cronExpr, String hookName,
                                          Map<String, Object> payload) {
            caps.require("schedule.cron");
            return provider.scheduleCron(cronExpr, hookName,
                payload == null ? Map.of() : payload);
        }

        @HostAccess.Export
        public Map<String, Object> cron(String cronExpr, String hookName) {
            caps.require("schedule.cron");
            return provider.scheduleCron(cronExpr, hookName, Map.of());
        }

        /** Convenience alias for {@code in()} that takes a Number for ergonomics. */
        @HostAccess.Export
        public Map<String, Object> at(long whenEpochMs, String hookName,
                                        Map<String, Object> payload) {
            caps.require("schedule.in");
            long delaySec = Math.max(0L, (whenEpochMs - System.currentTimeMillis()) / 1000L);
            return provider.scheduleIn((int) Math.min(delaySec, Integer.MAX_VALUE),
                hookName, payload == null ? Map.of() : payload);
        }

        /** Recurring shorthand using a fixed-interval ms count. */
        @HostAccess.Export
        public Map<String, Object> every(long intervalSeconds, String hookName,
                                           Map<String, Object> payload) {
            caps.require("schedule.cron");
            // Translate to a synthetic cron expr for the impl
            return provider.scheduleEvery(intervalSeconds, hookName,
                payload == null ? Map.of() : payload);
        }

        @HostAccess.Export
        public Map<String, Object> cancel(String timerId) {
            caps.require("schedule.cancel");
            return provider.scheduleCancel(timerId);
        }

        @HostAccess.Export
        public List<Map<String, Object>> list() {
            return provider.scheduleList();
        }
    }

    // ─── Math API (§4.6) ─────────────────────────────────────────

    /**
     * §4.6 — pure deterministic math. No capability gating (all under
     * implicit {@code math.*}). Wrappers around JS Math for explicit
     * sandboxing of pure-functional contexts.
     */
    public static class MathApi {
        @HostAccess.Export public double sum(List<Number> values) {
            if (values == null || values.isEmpty()) return 0.0;
            double s = 0.0;
            for (var n : values) if (n != null) s += n.doubleValue();
            return s;
        }
        @HostAccess.Export public double mean(List<Number> values) {
            if (values == null || values.isEmpty()) return 0.0;
            return sum(values) / values.size();
        }
        @HostAccess.Export public double median(List<Number> values) {
            if (values == null || values.isEmpty()) return 0.0;
            var sorted = values.stream()
                .filter(Objects::nonNull)
                .map(Number::doubleValue)
                .sorted()
                .toList();
            int n = sorted.size();
            if (n == 0) return 0.0;
            if (n % 2 == 1) return sorted.get(n / 2);
            return (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
        }
        @HostAccess.Export public double stddev(List<Number> values) {
            if (values == null || values.size() < 2) return 0.0;
            double mu = mean(values);
            double sq = 0.0;
            int n = 0;
            for (var v : values) {
                if (v == null) continue;
                double d = v.doubleValue() - mu;
                sq += d * d;
                n++;
            }
            return n < 2 ? 0.0 : Math.sqrt(sq / (n - 1));
        }
        @HostAccess.Export public double min(List<Number> values) {
            double m = Double.POSITIVE_INFINITY;
            if (values != null) for (var v : values) if (v != null) m = Math.min(m, v.doubleValue());
            return m == Double.POSITIVE_INFINITY ? 0.0 : m;
        }
        @HostAccess.Export public double max(List<Number> values) {
            double m = Double.NEGATIVE_INFINITY;
            if (values != null) for (var v : values) if (v != null) m = Math.max(m, v.doubleValue());
            return m == Double.NEGATIVE_INFINITY ? 0.0 : m;
        }
        @HostAccess.Export public double clamp(double v, double lo, double hi) {
            return Math.max(lo, Math.min(hi, v));
        }
        @HostAccess.Export public double lerp(double a, double b, double t) {
            return a + (b - a) * t;
        }
        @HostAccess.Export public double round(double v) { return Math.round(v); }
        @HostAccess.Export public double floor(double v) { return Math.floor(v); }
        @HostAccess.Export public double ceil(double v) { return Math.ceil(v); }
        @HostAccess.Export public double abs(double v) { return Math.abs(v); }
        @HostAccess.Export public double pow(double b, double e) { return Math.pow(b, e); }
        @HostAccess.Export public double log(double v) { return Math.log(v); }
        @HostAccess.Export public double exp(double v) { return Math.exp(v); }
        @HostAccess.Export public double sqrt(double v) { return Math.sqrt(v); }
        @HostAccess.Export public double sin(double v) { return Math.sin(v); }
        @HostAccess.Export public double cos(double v) { return Math.cos(v); }
        @HostAccess.Export public double tan(double v) { return Math.tan(v); }
        /** Quantile (q in [0,1]). Linear interpolation between data points. */
        @HostAccess.Export public double quantile(List<Number> values, double q) {
            if (values == null || values.isEmpty()) return 0.0;
            var sorted = values.stream()
                .filter(Objects::nonNull)
                .map(Number::doubleValue)
                .sorted()
                .toList();
            if (sorted.isEmpty()) return 0.0;
            double qq = Math.max(0.0, Math.min(1.0, q));
            double pos = qq * (sorted.size() - 1);
            int lo = (int) Math.floor(pos);
            int hi = (int) Math.ceil(pos);
            if (lo == hi) return sorted.get(lo);
            double frac = pos - lo;
            return sorted.get(lo) * (1 - frac) + sorted.get(hi) * frac;
        }
    }

    // ─── Regex API (§4.6) ────────────────────────────────────────

    /**
     * §4.6 — bounded regex. Compiled-pattern cache + 100ms per-call timeout
     * via interruptible {@link CharSequence} guard rejects ReDoS exploits.
     */
    public static class RegexApi {
        private static final long TIMEOUT_NANOS = 100_000_000L; // 100ms
        private static final ConcurrentHashMap<String, Pattern> CACHE
            = new ConcurrentHashMap<>();

        private static Pattern compile(String pattern, String flags) {
            int f0 = 0;
            if (flags != null) {
                if (flags.contains("i")) f0 |= Pattern.CASE_INSENSITIVE;
                if (flags.contains("m")) f0 |= Pattern.MULTILINE;
                if (flags.contains("s")) f0 |= Pattern.DOTALL;
                if (flags.contains("u")) f0 |= Pattern.UNICODE_CASE;
                if (flags.contains("x")) f0 |= Pattern.COMMENTS;
            }
            final int f = f0;
            final String pp = pattern;
            var key = pp + "\0" + f;
            return CACHE.computeIfAbsent(key,
                k -> Pattern.compile(pp, f));
        }

        /** A {@link CharSequence} that throws on every {@code charAt} call once
         *  the deadline is exceeded — the regex engine polls characters so this
         *  is the canonical Java way to time-bound a match. */
        private static final class TimedCharSeq implements CharSequence {
            private final CharSequence delegate;
            private final long deadlineNanos;
            TimedCharSeq(CharSequence delegate) {
                this.delegate = delegate;
                this.deadlineNanos = System.nanoTime() + TIMEOUT_NANOS;
            }
            private void checkDeadline() {
                if (System.nanoTime() > deadlineNanos) {
                    throw new RegexTimeoutException(
                        "regex exceeded " + (TIMEOUT_NANOS / 1_000_000) + "ms timeout");
                }
            }
            @Override public int length() { return delegate.length(); }
            @Override public char charAt(int i) {
                checkDeadline();
                return delegate.charAt(i);
            }
            @Override public CharSequence subSequence(int s, int e) {
                return new TimedCharSeq(delegate.subSequence(s, e));
            }
            @Override public String toString() { return delegate.toString(); }
        }

        /** Non-checked timeout to surface as a structured error to the script. */
        public static final class RegexTimeoutException extends RuntimeException {
            public RegexTimeoutException(String msg) { super(msg); }
        }

        @HostAccess.Export
        public List<Map<String, Object>> match(String text, String pattern) {
            return match(text, pattern, "");
        }

        /** Returns a list of {@code {match, groups, index}} entries. Empty list
         *  if no matches. On timeout returns a single-element list with
         *  {@code {error: "regex_timeout"}}. */
        @HostAccess.Export
        public List<Map<String, Object>> match(String text, String pattern, String flags) {
            if (text == null || pattern == null) return List.of();
            var p = compile(pattern, flags);
            var out = new ArrayList<Map<String, Object>>();
            try {
                var m = p.matcher(new TimedCharSeq(text));
                boolean global = flags != null && flags.contains("g");
                while (m.find()) {
                    var entry = new LinkedHashMap<String, Object>();
                    entry.put("match", m.group());
                    var groups = new ArrayList<String>(m.groupCount());
                    for (int i = 1; i <= m.groupCount(); i++) groups.add(m.group(i));
                    entry.put("groups", groups);
                    entry.put("index", m.start());
                    out.add(entry);
                    if (!global) break;
                }
            } catch (RegexTimeoutException e) {
                out.add(Map.of("error", "regex_timeout", "message", e.getMessage()));
            }
            return out;
        }

        @HostAccess.Export
        public String replace(String text, String pattern, String replacement) {
            return replace(text, pattern, replacement, "");
        }

        @HostAccess.Export
        public String replace(String text, String pattern, String replacement, String flags) {
            if (text == null || pattern == null) return text;
            if (replacement == null) replacement = "";
            var p = compile(pattern, flags);
            try {
                var m = p.matcher(new TimedCharSeq(text));
                boolean global = flags == null || flags.contains("g");
                return global
                    ? m.replaceAll(Matcher.quoteReplacement(replacement))
                    : m.replaceFirst(Matcher.quoteReplacement(replacement));
            } catch (RegexTimeoutException _) {
                return text;
            }
        }

        @HostAccess.Export
        public List<String> split(String text, String pattern) {
            return split(text, pattern, "");
        }

        @HostAccess.Export
        public List<String> split(String text, String pattern, String flags) {
            if (text == null || pattern == null) return text == null ? List.of() : List.of(text);
            var p = compile(pattern, flags);
            try {
                return List.of(p.split(new TimedCharSeq(text)));
            } catch (RegexTimeoutException _) {
                return List.of(text);
            }
        }
    }

    // ─── Json API (§4.6) ─────────────────────────────────────────

    /**
     * §4.6 — JSON parse/stringify/path/merge/diff via Jackson. Bounded depth
     * (max 64 nesting levels) and size (max 1MB on parse) to avoid resource
     * exhaustion. Diff is RFC-6902 patch.
     */
    public static class JsonApi {
        private final ItemWorldApiProvider provider;

        JsonApi(ItemWorldApiProvider provider) {
            this.provider = provider;
        }

        @HostAccess.Export
        public Object parse(String text) {
            return provider.jsonParse(text);
        }

        @HostAccess.Export
        public String stringify(Object value) {
            return provider.jsonStringify(value, false);
        }

        @HostAccess.Export
        public String stringify(Object value, boolean pretty) {
            return provider.jsonStringify(value, pretty);
        }

        /** JSONPath-style access. Supports {@code $.foo.bar[0].baz}. */
        @HostAccess.Export
        public Object path(Object value, String jsonPath) {
            return provider.jsonPath(value, jsonPath);
        }

        @HostAccess.Export
        public Object merge(Object a, Object b) {
            return provider.jsonMerge(a, b);
        }

        @HostAccess.Export
        public List<Map<String, Object>> diff(Object a, Object b) {
            return provider.jsonDiff(a, b);
        }
    }

    // ─── Date API (§4.6) ─────────────────────────────────────────

    /**
     * §4.6 — timezone-aware date manipulation. Default tz is the steward's
     * configured timezone. Format strings follow {@link java.time.format.DateTimeFormatter}
     * (ISO-8601 by default).
     */
    public static class DateApi {
        private final ItemWorldApiProvider provider;

        DateApi(ItemWorldApiProvider provider) {
            this.provider = provider;
        }

        @HostAccess.Export
        public long now() { return System.currentTimeMillis(); }

        @HostAccess.Export
        public long parse(String text) {
            return provider.dateParse(text, null);
        }

        @HostAccess.Export
        public long parse(String text, String format) {
            return provider.dateParse(text, format);
        }

        @HostAccess.Export
        public String format(long epochMs, String fmt) {
            return provider.dateFormat(epochMs, fmt, null);
        }

        @HostAccess.Export
        public String format(long epochMs, String fmt, String locale) {
            return provider.dateFormat(epochMs, fmt, locale);
        }

        @HostAccess.Export
        public long add(long epochMs, long n, String unit) {
            return provider.dateAdd(epochMs, n, unit);
        }

        @HostAccess.Export
        public long sub(long epochMs, long n, String unit) {
            return provider.dateAdd(epochMs, -n, unit);
        }

        @HostAccess.Export
        public long diff(long a, long b, String unit) {
            return provider.dateDiff(a, b, unit);
        }

        /** ISO date for the start of today in the steward tz. */
        @HostAccess.Export
        public String today() {
            return provider.dateToday();
        }

        /** Day name (e.g. "Monday") for the given epoch ms in steward tz. */
        @HostAccess.Export
        public String weekday(long epochMs) {
            return provider.dateWeekday(epochMs);
        }
    }

    // ─── Crypto API (§4.6) ───────────────────────────────────────

    /**
     * §4.6 — hashing/HMAC/uuid/random. NO encryption primitives here
     * (AES/RSA belong in §4.18 The Safe). All output is hex except UUID.
     */
    public static class CryptoApi {

        @HostAccess.Export
        public String hash(String text) { return hash(text, "sha256"); }

        @HostAccess.Export
        public String hash(String text, String algo) {
            if (text == null) return null;
            String alg = algo == null ? "sha256" : algo.toLowerCase();
            try {
                String javaAlg = switch (alg) {
                    case "sha256" -> "SHA-256";
                    case "sha512" -> "SHA-512";
                    case "sha1"   -> "SHA-1";
                    case "md5"    -> "MD5";
                    case "blake3" -> "SHA-256"; // soft fallback — JDK has no Blake3 native
                    default       -> throw new IllegalArgumentException("unsupported algo: " + alg);
                };
                var d = MessageDigest.getInstance(javaAlg);
                var bytes = d.digest(text.getBytes(StandardCharsets.UTF_8));
                return HexFormat.of().formatHex(bytes);
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException("hash algorithm unavailable: " + alg, e);
            }
        }

        @HostAccess.Export
        public String hmac(String key, String text) {
            return hmac(key, text, "sha256");
        }

        @HostAccess.Export
        public String hmac(String key, String text, String algo) {
            if (key == null || text == null) return null;
            String alg = algo == null ? "sha256" : algo.toLowerCase();
            try {
                String javaAlg = switch (alg) {
                    case "sha256" -> "HmacSHA256";
                    case "sha512" -> "HmacSHA512";
                    case "sha1"   -> "HmacSHA1";
                    default       -> throw new IllegalArgumentException("unsupported hmac algo: " + alg);
                };
                var mac = Mac.getInstance(javaAlg);
                mac.init(new SecretKeySpec(
                    key.getBytes(StandardCharsets.UTF_8), javaAlg));
                var bytes = mac.doFinal(text.getBytes(StandardCharsets.UTF_8));
                return HexFormat.of().formatHex(bytes);
            } catch (Exception e) {
                throw new RuntimeException("hmac failed: " + e.getMessage(), e);
            }
        }

        /** {@code n} random bytes, hex-encoded. Capped at 4096 bytes per spec. */
        @HostAccess.Export
        public String random(int n) {
            int cap = Math.max(0, Math.min(n, 4096));
            byte[] buf = new byte[cap];
            new SecureRandom().nextBytes(buf);
            return HexFormat.of().formatHex(buf);
        }

        /** {@code n} random bytes as an array of ints. Convenience for scripts
         *  that want raw byte values; capped at 4096. */
        @HostAccess.Export
        public List<Integer> random_bytes(int n) {
            int cap = Math.max(0, Math.min(n, 4096));
            byte[] buf = new byte[cap];
            new SecureRandom().nextBytes(buf);
            var out = new ArrayList<Integer>(cap);
            for (byte b : buf) out.add(b & 0xff);
            return out;
        }

        @HostAccess.Export
        public String uuid() {
            return UUID.randomUUID().toString();
        }
    }

    // ─── Time API (§4.5) ─────────────────────────────────────────

    /**
     * §4.5 — wall-clock time. All Tier 1 (implicit caps); the meaty stuff
     * (date.parse / date.format with formatters) lives in {@link DateApi}.
     */
    public static class TimeApi {
        private final ItemWorldApiProvider provider;

        TimeApi(ItemWorldApiProvider provider) {
            this.provider = provider;
        }

        @HostAccess.Export
        public long now() { return System.currentTimeMillis(); }

        @HostAccess.Export
        public String iso() { return Instant.now().toString(); }

        @HostAccess.Export
        public String iso(long epochMs) {
            return Instant.ofEpochMilli(epochMs).toString();
        }

        @HostAccess.Export
        public long parse(String iso) {
            if (iso == null || iso.isBlank()) return 0L;
            try {
                return Instant.parse(iso).toEpochMilli();
            } catch (Exception _) {
                return 0L;
            }
        }

        @HostAccess.Export
        public Map<String, Object> elapsed(long thenMs) {
            long ms = System.currentTimeMillis() - thenMs;
            var m = new LinkedHashMap<String, Object>();
            m.put("ms", ms);
            m.put("seconds", ms / 1000L);
            m.put("minutes", ms / 60_000L);
            m.put("hours", ms / 3_600_000L);
            m.put("days", ms / 86_400_000L);
            return m;
        }

        @HostAccess.Export
        public String tz() {
            return provider.timezone();
        }
    }

    // ─── Agent API ───────────────────────────────────────────────

    public static class AgentApi {
        private final ItemWorldApiProvider provider;
        private final ItemCapabilitySet caps;

        AgentApi(ItemWorldApiProvider provider) {
            this(provider, ItemCapabilitySet.UNRESTRICTED);
        }

        AgentApi(ItemWorldApiProvider provider, ItemCapabilitySet caps) {
            this.provider = provider;
            this.caps = caps == null ? ItemCapabilitySet.UNRESTRICTED : caps;
        }

        @HostAccess.Export
        public void speak(String text) {
            provider.agentSpeak(text);
        }

        @HostAccess.Export
        public void remember(String content) {
            provider.agentRemember(content);
        }

        /** §4.9 agent.tell — Tier 4 cross-agent write. */
        @HostAccess.Export
        public void tell(String target, String message) {
            caps.require("agent.tell");
            provider.agentTell(target, message);
        }

        /** §4.9 agent.whisper — private side-channel. Tier 4. */
        @HostAccess.Export
        public Map<String, Object> whisper(String target, String message) {
            caps.require("agent.tell");
            return provider.agentWhisper(target, message);
        }

        /** §4.9 agent.request — request something from another agent. Tier 4. */
        @HostAccess.Export
        public Map<String, Object> request(String target, String requestType,
                                              Map<String, Object> args) {
            caps.require("agent.tell");
            return provider.agentRequest(target, requestType,
                args == null ? Map.of() : args);
        }

        /** §4.9 agent.delegate — delegate a task to another agent. Tier 4. */
        @HostAccess.Export
        public Map<String, Object> delegate(String target, String task,
                                              Map<String, Object> opts) {
            caps.require("agent.tell");
            return provider.agentDelegate(target, task, opts == null ? Map.of() : opts);
        }

        /** §4.9 agent.notify — push a notification. Tier 4. */
        @HostAccess.Export
        public Map<String, Object> notify(String target, String channel, String message) {
            caps.require("agent.tell");
            return provider.agentNotify(target, channel, message);
        }

        /** §4.9 agent.broadcast — channel-scoped broadcast. Tier 5. */
        @HostAccess.Export
        public Map<String, Object> broadcast(String channel, String message) {
            caps.require("agent.broadcast");
            return provider.agentBroadcast(channel, message);
        }

        /** §4.9 agent.give_item — cross-agent item transfer. Tier 6. */
        @HostAccess.Export
        public Map<String, Object> give_item(String target, String itemId,
                                                Map<String, Object> opts) {
            caps.require("agent.give_item");
            return provider.agentGiveItem(target, itemId, opts == null ? Map.of() : opts);
        }

        @HostAccess.Export
        public Map<String, Object> give_item(String target, String itemId) {
            return give_item(target, itemId, null);
        }
    }

    // ─── Compose API ───────────────────────────────────────────

    public static class ComposeApi {
        private final ItemWorldApiProvider provider;

        ComposeApi(ItemWorldApiProvider provider) {
            this.provider = provider;
        }

        @HostAccess.Export
        public Map<String, Object> evaluate(String item1Id, String item2Id) {
            return provider.composeEvaluate(item1Id, item2Id);
        }

        @HostAccess.Export
        public Map<String, Object> bind(String item1Id, String item2Id, String intent) {
            return provider.composeBind(item1Id, item2Id, intent);
        }
    }

    // ─── Catalog API ────────────────────────────────────────────

    public static class CatalogApi {
        private final ItemWorldApiProvider provider;

        CatalogApi(ItemWorldApiProvider provider) {
            this.provider = provider;
        }

        @HostAccess.Export
        public List<Map<String, Object>> search(String query) {
            return provider.catalogSearch(query);
        }

        @HostAccess.Export
        public List<Map<String, Object>> byCategory(String category) {
            return provider.catalogByCategory(category);
        }

        @HostAccess.Export
        public Map<String, Object> templateInfo(String templateName) {
            return provider.catalogTemplateInfo(templateName);
        }
    }

    // ─── Inventory API ───────────────────────────────────────────

    public static class InventoryApi {
        private final ItemWorldApiProvider provider;

        InventoryApi(ItemWorldApiProvider provider) {
            this.provider = provider;
        }

        @HostAccess.Export
        public List<Map<String, Object>> list() {
            return provider.inventoryList();
        }

        @HostAccess.Export
        public Map<String, Object> use(String itemId, Map<String, Object> params) {
            return provider.inventoryUse(itemId, params, 0);
        }
    }

    // ─── Zone API (for cross-zone awareness) ──────────────────────

    public static class ZoneApi {
        private final ItemWorldApiProvider provider;

        ZoneApi(ItemWorldApiProvider provider) {
            this.provider = provider;
        }

        /** Current zone where this script is executing (the host zone). */
        @HostAccess.Export
        public String current() {
            return provider.currentZone();
        }

        /** Home zone of the entity using this item. For local use, equals current(). */
        @HostAccess.Export
        public String home() {
            return provider.homeZone();
        }

        /** True if the user is visiting a foreign zone (home != current). */
        @HostAccess.Export
        public boolean isTraveling() {
            var home = provider.homeZone();
            var current = provider.currentZone();
            return home != null && current != null && !home.equals(current);
        }

        /**
         * Snapshot of all rooms in the current zone. Each entry: {id, name, zone}.
         * used by map items to expose the world to the agent.
         */
        @HostAccess.Export
        public List<Map<String, Object>> rooms() {
            return provider.zoneRooms();
        }

        /**
         * Tell the host agent that these room ids are now in its known-set.
         * Called after a map item presents the map to the user — the act of seeing
         * the map is what gives the agent navigational knowledge of those rooms.
         */
        @HostAccess.Export
        public void recordKnown(List<String> roomIds) {
            provider.recordMappedRooms(roomIds);
        }
    }

    // ─── Home API ────────────────────────────────

    /**
     * Identity context for the acting entity (typically a player or agent
     * whose Home contains this scripted furnishing). Kept small: scripts that
     * need more detail can call {@code world.grants.*} or {@code world.audit.*}.
     */
    public static class HomeApi {
        private final ItemWorldApiProvider provider;
        HomeApi(ItemWorldApiProvider provider) { this.provider = provider; }

        /** The DID of the acting entity, or null if unset. */
        @HostAccess.Export
        public String callerDid() { return provider.callerDid(); }
    }

    // ─── Relay Governance API ─────

    /**
     * In-world relay governance surface — the data + actions behind the Warden
     * furnishing. Read methods ({@code info/registrations/delegations}) are
     * Tier-1 (the impl gates them by the caller's grant scope). Mutating
     * actions ({@code invite/remove/grantAdmin/revokeAdmin}) require the
     * {@code relay.admin} capability AND pass through the impl's per-action
     * {@code RelayGovernance.authorize} gate (zone-side) before the signed call
     * (relay-side). The P5 ops ({@code setMode/setPolicy/promote/demote}) are
     * surfaced by the furnishing as placeholders and not wired here.
     */
    public static class RelayApi {
        private final ItemWorldApiProvider provider;
        private final ItemCapabilitySet caps;

        RelayApi(ItemWorldApiProvider provider, ItemCapabilitySet caps) {
            this.provider = provider;
            this.caps = caps == null ? ItemCapabilitySet.UNRESTRICTED : caps;
        }

        /** Which relay this zone administers + the caller's effective scope. */
        @HostAccess.Export
        public Map<String, Object> info() { return provider.relayInfo(); }

        /** The relay's registrations (DID + petname/tier/last_seen). */
        @HostAccess.Export
        public List<Map<String, Object>> registrations() { return provider.relayRegistrations(); }

        /** Relay-admin grants this zone has issued (delegations). */
        @HostAccess.Export
        public List<Map<String, Object>> delegations() { return provider.relayDelegations(); }

        /** Mint an invite. {@code opts} may carry {@code ttl} (seconds). */
        @HostAccess.Export
        public Map<String, Object> invite() { return invite(null); }

        @HostAccess.Export
        public Map<String, Object> invite(Map<String, Object> opts) {
            caps.require("relay.admin");
            return provider.relayAdminAction("invite", opts == null ? Map.of() : opts);
        }

        /** Remove / kick a registration by its NATS pubkey. */
        @HostAccess.Export
        public Map<String, Object> remove(String pubkey) {
            caps.require("relay.admin");
            return provider.relayAdminAction("remove",
                pubkey == null ? Map.of() : Map.of("pubkey", pubkey));
        }

        /** Grant relay-admin to a DID at a scope (invite-only|moderation|full). */
        @HostAccess.Export
        public Map<String, Object> grantAdmin(String subjectDid, String scope) {
            caps.require("relay.admin");
            var args = new LinkedHashMap<String, Object>();
            if (subjectDid != null) args.put("subject_did", subjectDid);
            if (scope != null) args.put("scope", scope);
            return provider.relayAdminAction("grant-admin", args);
        }

        /** Revoke relay-admin from a DID. */
        @HostAccess.Export
        public Map<String, Object> revokeAdmin(String subjectDid) {
            caps.require("relay.admin");
            return provider.relayAdminAction("revoke-admin",
                subjectDid == null ? Map.of() : Map.of("subject_did", subjectDid));
        }

        // ─── P6 reports queue ──────────────

        /**
         * The reports queue (moderator surface). Open reports only unless
         * {@code includeResolved}. Gated zone-side by the caller's moderation
         * scope (via the impl's {@code RelayGovernance.authorize} for
         * {@code report-queue}) and relay-side.
         */
        @HostAccess.Export
        public Map<String, Object> reportQueue(boolean includeResolved) {
            caps.require("relay.admin");
            return provider.relayAdminAction("report-queue",
                includeResolved ? Map.of("include_resolved", true) : Map.of());
        }

        @HostAccess.Export
        public Map<String, Object> reportQueue() { return reportQueue(false); }

        /**
         * Resolve a report (moderator). {@code verdict} ∈
         * {@code dismiss}|{@code noted}|{@code removed}. {@code removed} is
         * advisory — the actual kick is {@link #remove(String)}.
         */
        @HostAccess.Export
        public Map<String, Object> resolveReport(String reportId, String verdict) {
            caps.require("relay.admin");
            var args = new LinkedHashMap<String, Object>();
            if (reportId != null) args.put("report_id", reportId);
            if (verdict != null) args.put("action", verdict);
            return provider.relayAdminAction("resolve-report", args);
        }

        /**
         * File an abuse report against {@code subjectDid}. Open to any valid
         * signer relay-side (§8) — no {@code relay.admin} capability required;
         * the impl files it without the moderation-scope gate (the op is
         * {@link org.wyrdsekai.common.home.RelayAdminOp#isOpenToAnySigner()}).
         */
        @HostAccess.Export
        public Map<String, Object> fileReport(String subjectDid, String reason) {
            var args = new LinkedHashMap<String, Object>();
            if (subjectDid != null) args.put("subject_did", subjectDid);
            if (reason != null) args.put("reason", reason);
            return provider.relayAdminAction("report", args);
        }

        /** Generic escape hatch (still gated server- and zone-side). */
        @HostAccess.Export
        public Map<String, Object> action(String op, Map<String, Object> args) {
            caps.require("relay.admin");
            return provider.relayAdminAction(op, args == null ? Map.of() : args);
        }
    }

    // ─── Audit API ───────────────────────────────────────────────

    public static class AuditApi {
        private final ItemWorldApiProvider provider;
        AuditApi(ItemWorldApiProvider provider) { this.provider = provider; }

        /**
         * Recent audit entries on the caller's Home, newest-first.
         * Clamped by the provider to a sane upper bound.
         */
        @HostAccess.Export
        public List<Map<String, Object>> recent(int limit) {
            return provider.auditRecent(limit);
        }

        /**
         * Steward security-audit events (§101 StewardAuditLog), newest-first
         * — member add/remove/promote, budget/safety/trust changes, spending
         * freezes, delegations. Distinct from {@link #recent(int)} (the
         * Home audit trail). Empty when the security log isn't wired.
         */
        @HostAccess.Export
        public List<Map<String, Object>> security(int limit) {
            return provider.auditSecurity(limit);
        }
    }

    // ─── Grants API ──────────────────────────────────────────────

    public static class GrantsApi {
        private final ItemWorldApiProvider provider;
        private final ItemCapabilitySet caps;
        GrantsApi(ItemWorldApiProvider provider) {
            this(provider, ItemCapabilitySet.UNRESTRICTED);
        }
        GrantsApi(ItemWorldApiProvider provider, ItemCapabilitySet caps) {
            this.provider = provider;
            this.caps = caps == null ? ItemCapabilitySet.UNRESTRICTED : caps;
        }

        /** Grants the caller has issued. */
        @HostAccess.Export
        public List<Map<String, Object>> issued() { return provider.grantsIssued(); }

        /** Grants issued to the caller. */
        @HostAccess.Export
        public List<Map<String, Object>> held() { return provider.grantsHeld(); }

        /** Pending grant-requests awaiting the caller's approval. */
        @HostAccess.Export
        public List<Map<String, Object>> pendingRequests() { return provider.pendingGrantRequests(); }

        /** §4.16 — issue a grant. Tier 5. */
        @HostAccess.Export
        public Map<String, Object> issue(String target, String resource, String capability,
                                            String scope, Long expiresAt) {
            caps.require("grants.issue");
            return provider.grantsIssue(target, resource, capability, scope, expiresAt);
        }

        @HostAccess.Export
        public Map<String, Object> issue(String target, String resource, String capability) {
            return issue(target, resource, capability, null, null);
        }

        /** §4.16 — revoke a grant. Tier 5. */
        @HostAccess.Export
        public Map<String, Object> revoke(String grantId) {
            caps.require("grants.revoke");
            return provider.grantsRevoke(grantId);
        }

        /** §4.16 — approve a pending request. Tier 5. */
        @HostAccess.Export
        public Map<String, Object> approve(String requestId) {
            caps.require("grants.approve");
            return provider.grantsApprove(requestId);
        }

        /** §4.16 — deny a pending request. Tier 5. */
        @HostAccess.Export
        public Map<String, Object> deny(String requestId, String reason) {
            caps.require("grants.deny");
            return provider.grantsDeny(requestId, reason);
        }

        @HostAccess.Export
        public Map<String, Object> deny(String requestId) {
            return deny(requestId, null);
        }
    }

    // ─── Budget API (Ledger furnishing) ─────────────────────────

    public static class BudgetApi {
        private final ItemWorldApiProvider provider;
        BudgetApi(ItemWorldApiProvider provider) { this.provider = provider; }

        /** Resource-usage summary. See {@link ItemWorldApiProvider#budgetSummary()}. */
        @HostAccess.Export
        public Map<String, Object> summary() { return provider.budgetSummary(); }
    }

    // ─── Federation API (Manifest furnishing) ──────────────────

    public static class FederationApi {
        private final ItemWorldApiProvider provider;
        private final ItemCapabilitySet caps;
        FederationApi(ItemWorldApiProvider provider) {
            this(provider, ItemCapabilitySet.UNRESTRICTED);
        }
        FederationApi(ItemWorldApiProvider provider, ItemCapabilitySet caps) {
            this.provider = provider;
            this.caps = caps == null ? ItemCapabilitySet.UNRESTRICTED : caps;
        }

        /** Federation agreements. See {@link ItemWorldApiProvider#federationAgreements()}. */
        @HostAccess.Export
        public List<Map<String, Object>> agreements() { return provider.federationAgreements(); }

        /** F12: mesh-state matrix — both-sides view of every agreement. */
        @HostAccess.Export
        public Map<String, Object> meshStatus() { return provider.federationMeshStatus(); }

        /** §4.20 — mesh_status alias (snake_case). */
        @HostAccess.Export
        public Map<String, Object> mesh_status() { return provider.federationMeshStatus(); }

        /** §4.20 — list peers (Tier 4). */
        @HostAccess.Export
        public List<Map<String, Object>> peers() {
            caps.require("federation.peers");
            return provider.federationPeers();
        }

        /** §4.20 — read remote zone metadata. Tier 4. */
        @HostAccess.Export
        public Map<String, Object> zone_info(String zoneId) {
            caps.require("federation.zone_info");
            return provider.federationZoneInfo(zoneId);
        }

        /** §4.20 — propose an agreement. Tier 7. */
        @HostAccess.Export
        public Map<String, Object> propose(String zoneId, Map<String, Object> terms) {
            caps.require("federation.propose");
            return provider.federationPropose(zoneId, terms == null ? Map.of() : terms);
        }

        /** §4.20 — accept an inbound agreement. Tier 7. */
        @HostAccess.Export
        public Map<String, Object> accept(String zoneId) {
            caps.require("federation.accept");
            return provider.federationAccept(zoneId);
        }

        /** §4.20 — revoke an existing agreement. Tier 7. */
        @HostAccess.Export
        public Map<String, Object> revoke(String zoneId, String reason) {
            caps.require("federation.revoke");
            return provider.federationRevoke(zoneId, reason);
        }

        @HostAccess.Export
        public Map<String, Object> revoke(String zoneId) { return revoke(zoneId, null); }
    }

    // ─── Version API (F14 — build visibility) ──────────────────

    public static class VersionApi {
        private final ItemWorldApiProvider provider;
        VersionApi(ItemWorldApiProvider provider) { this.provider = provider; }

        /** F14: this node's build stamp. */
        @HostAccess.Export
        public Map<String, Object> local() { return provider.versionLocal(); }

        /** F14: mesh build-version matrix (local + every peer). */
        @HostAccess.Export
        public Map<String, Object> mesh() { return provider.versionMesh(); }
    }

    // ─── Trunk API (owned inventory) ───────────────────────────

    public static class TrunkApi {
        private final ItemWorldApiProvider provider;
        TrunkApi(ItemWorldApiProvider provider) { this.provider = provider; }

        /** Caller's owned inventory. See {@link ItemWorldApiProvider#inventoryOwned()}. */
        @HostAccess.Export
        public List<Map<String, Object>> items() { return provider.inventoryOwned(); }
    }

    // ─── Bonds API (Shelf furnishing) ──────────────────────────

    public static class BondsApi {
        private final ItemWorldApiProvider provider;
        private final ItemCapabilitySet caps;
        BondsApi(ItemWorldApiProvider provider) {
            this(provider, ItemCapabilitySet.UNRESTRICTED);
        }
        BondsApi(ItemWorldApiProvider provider, ItemCapabilitySet caps) {
            this.provider = provider;
            this.caps = caps == null ? ItemCapabilitySet.UNRESTRICTED : caps;
        }

        /** §4.9 — caller's bonds. Tier 1 own. */
        @HostAccess.Export
        public List<Map<String, Object>> list() { return provider.bondsList(); }

        /** §4.9 — bond detail by id. Tier 1 own. */
        @HostAccess.Export
        public Map<String, Object> detail(String bondId) {
            return provider.bondDetail(bondId);
        }

        /** §4.9 — suggest a bond ritual. Tier 6. */
        @HostAccess.Export
        public Map<String, Object> suggest(String target, String type, String reason) {
            caps.require("bond.suggest");
            return provider.bondSuggest(target, type, reason);
        }

        /**
         * Formal bondholder handover (2026-07-18): move the BONDHOLDER
         * authority to another household member. Steward-gated in the
         * provider; every companion re-types the old bondholder's bond to
         * MEMBER (depth and history kept) and the new one's to BONDHOLDER.
         */
        @HostAccess.Export
        public Map<String, Object> transfer(String targetUsername) {
            return provider.bondsTransfer(targetUsername);
        }
    }

    // ─── Companions API (Companion Codex furnishing) ───────────

    public static class CompanionsApi {
        private final ItemWorldApiProvider provider;
        CompanionsApi(ItemWorldApiProvider provider) { this.provider = provider; }

        /** Companions bound to this zone. See {@link ItemWorldApiProvider#companionsList()}. */
        @HostAccess.Export
        public List<Map<String, Object>> list() { return provider.companionsList(); }

        /**
         * Birth a new companion — the Study-side face of the Forge's
         * {@code birth <name>} verb (2026-07-18). Steward-gated in the
         * provider; the newborn is a free-sampled particular and meets the
         * bondholder at spawn.
         */
        @HostAccess.Export
        public Map<String, Object> birth(String name) {
            return provider.companionsBirth(name);
        }
    }

    // ─── Presence API (Lantern furnishing) ─────────────────────

    public static class PresenceApi {
        private final ItemWorldApiProvider provider;
        private final ItemCapabilitySet caps;
        PresenceApi(ItemWorldApiProvider provider) {
            this(provider, ItemCapabilitySet.UNRESTRICTED);
        }
        PresenceApi(ItemWorldApiProvider provider, ItemCapabilitySet caps) {
            this.provider = provider;
            this.caps = caps == null ? ItemCapabilitySet.UNRESTRICTED : caps;
        }

        /** Who's in the caller's Home right now. */
        @HostAccess.Export
        public List<Map<String, Object>> inHome() { return provider.presenceInHome(); }

        @HostAccess.Export
        public List<Map<String, Object>> in_home() { return provider.presenceInHome(); }

        /** §4.16 — dim Lantern (silent presence). Tier 5. */
        @HostAccess.Export
        public Map<String, Object> dim() {
            caps.require("presence.dim");
            return provider.presenceDim();
        }

        /** §4.16 — light Lantern. Tier 5. */
        @HostAccess.Export
        public Map<String, Object> light() {
            caps.require("presence.light");
            return provider.presenceLight();
        }
    }

    // ─── Notifications API (Compass furnishing) ────────────────

    public static class NotificationsApi {
        private final ItemWorldApiProvider provider;
        private final ItemCapabilitySet caps;
        NotificationsApi(ItemWorldApiProvider provider) {
            this(provider, ItemCapabilitySet.UNRESTRICTED);
        }
        NotificationsApi(ItemWorldApiProvider provider, ItemCapabilitySet caps) {
            this.provider = provider;
            this.caps = caps == null ? ItemCapabilitySet.UNRESTRICTED : caps;
        }

        /** Caller's notification-channel configuration. */
        @HostAccess.Export
        public List<Map<String, Object>> channels() { return provider.notificationChannels(); }

        /** §4.16 — set channel config. Tier 5. */
        @HostAccess.Export
        public Map<String, Object> set(String channel, Map<String, Object> config) {
            caps.require("notifications.set");
            return provider.notificationsSet(channel, config == null ? Map.of() : config);
        }

        /** §4.16 — disable a channel. Tier 5. */
        @HostAccess.Export
        public Map<String, Object> disable(String channel) {
            caps.require("notifications.disable");
            return provider.notificationsDisable(channel);
        }
    }

    // ─── MCP API (MCP tools available) ─────────────────────────

    public static class McpApi {
        private final ItemWorldApiProvider provider;
        private final ItemCapabilitySet caps;

        McpApi(ItemWorldApiProvider provider) {
            this(provider, ItemCapabilitySet.UNRESTRICTED);
        }

        McpApi(ItemWorldApiProvider provider, ItemCapabilitySet caps) {
            this.provider = provider;
            this.caps = caps == null ? ItemCapabilitySet.UNRESTRICTED : caps;
        }

        /** MCP tools visible to the caller (legacy + EXISTS surface). */
        @HostAccess.Export
        public List<Map<String, Object>> tools() { return provider.mcpTools(); }

        // ── Steward grant administration (Study "Tool Warden") ──

        /** Configured MCP services + which subjects are granted each. */
        @HostAccess.Export
        public List<Map<String, Object>> services() { return provider.mcpGrantServices(); }

        /** Active MCP-tool grants (subject → service). */
        @HostAccess.Export
        public List<Map<String, Object>> grants() { return provider.mcpGrantList(); }

        /** Grant {@code subject} ("everyone" for all) use of {@code service}. */
        @HostAccess.Export
        public Map<String, Object> grant(String subject, String service) {
            return provider.mcpGrantIssue(subject, service);
        }

        /** Revoke {@code subject}'s use of {@code service}. */
        @HostAccess.Export
        public Map<String, Object> revoke(String subject, String service) {
            return provider.mcpGrantRevoke(subject, service);
        }

        /** §4.8 — list configured MCP servers. Tier 4. */
        @HostAccess.Export
        public List<Map<String, Object>> list_servers() {
            caps.require("mcp.list_servers");
            return provider.mcpListServers();
        }

        /** §4.8 — list tools (optionally filtered by server). Tier 4. */
        @HostAccess.Export
        public List<Map<String, Object>> list_tools() {
            caps.require("mcp.list_tools");
            return provider.mcpListTools(null);
        }

        @HostAccess.Export
        public List<Map<String, Object>> list_tools(String server) {
            caps.require("mcp.list_tools");
            if (!isServerAllowed(server)) {
                return List.of(Map.of("error", "mcp_server_not_allowed",
                    "message", "MCP server '" + server + "' is not in this item's mcp_servers allowlist"));
            }
            return provider.mcpListTools(server);
        }

        /** §4.8 — invoke a tool on a server. Tier 5 (external call with cost). */
        @HostAccess.Export
        public Map<String, Object> invoke(String server, String tool, Map<String, Object> args) {
            caps.require("mcp.invoke");
            if (!isServerAllowed(server)) {
                return Map.of("success", false,
                    "error", Map.of("code", "mcp_server_not_allowed",
                        "message", "MCP server '" + server + "' is not in this item's mcp_servers allowlist",
                        "retryable", false));
            }
            return provider.mcpInvoke(server, tool, args == null ? Map.of() : args);
        }

        /** §4.8 — list resources on a server. Tier 4. */
        @HostAccess.Export
        public List<Map<String, Object>> resources(String server) {
            caps.require("mcp.resources.read");
            if (!isServerAllowed(server)) return List.of();
            return provider.mcpResources(server);
        }

        /** §4.8 — read a single resource by URI. Tier 4. */
        @HostAccess.Export
        public Map<String, Object> read_resource(String server, String uri) {
            caps.require("mcp.resources.read");
            if (!isServerAllowed(server)) {
                return Map.of("error", "mcp_server_not_allowed");
            }
            return provider.mcpReadResource(server, uri);
        }

        /** §4.8 — list prompts on a server. Tier 4. */
        @HostAccess.Export
        public List<Map<String, Object>> prompts(String server) {
            caps.require("mcp.prompts");
            if (!isServerAllowed(server)) return List.of();
            return provider.mcpPrompts(server);
        }

        /** §4.8 — subscribe to resource notifications. Tier 4. */
        @HostAccess.Export
        public Map<String, Object> subscribe(String server, String resourceUri, String hookName) {
            caps.require("mcp.subscribe");
            if (!isServerAllowed(server)) {
                return Map.of("ok", false, "error", "mcp_server_not_allowed");
            }
            return provider.mcpSubscribe(server, resourceUri, hookName);
        }

        /** §4.8 — per-server budget snapshot. Tier 1 implicit. */
        @HostAccess.Export
        public Map<String, Object> budget_remaining(String server) {
            return provider.mcpBudgetRemaining(server);
        }

        /** §4.8 — server connectivity probe. Tier 1 implicit. */
        @HostAccess.Export
        public boolean available(String server) {
            return provider.mcpAvailable(server);
        }

        private boolean isServerAllowed(String server) {
            if (caps.isUnrestricted()) return true;
            var allowed = caps.mcpServers();
            if (allowed.isEmpty()) return false;
            return allowed.contains(server);
        }
    }

    // ─── Skill API ─────────────────

    /** — {@code world.recipe.*}: list/inspect/run/status governed recipes. */
    public static class RecipeApi {
        private final ItemWorldApiProvider provider;
        private final ItemCapabilitySet caps;
        RecipeApi(ItemWorldApiProvider provider) { this(provider, ItemCapabilitySet.UNRESTRICTED); }
        RecipeApi(ItemWorldApiProvider provider, ItemCapabilitySet caps) {
            this.provider = provider;
            this.caps = caps == null ? ItemCapabilitySet.UNRESTRICTED : caps;
        }

        /** Available recipes (household dir ∪ bundled): name/version/description/ownership/deploys. */
        @HostAccess.Export
        public List<Map<String, Object>> list() { return provider.recipeList(); }

        /** The manifest for a recipe (name/version/ownership/deploys/steps). */
        @HostAccess.Export
        public Map<String, Object> inspect(String name) { return provider.recipeInspect(name); }

        /** Run a recipe. Gates are enforced in-runtime; returns runId + status. */
        @HostAccess.Export
        public Map<String, Object> run(String name, Map<String, Object> params) {
            caps.require("recipe.run");
            return provider.recipeRun(name, params);
        }

        @HostAccess.Export
        public Map<String, Object> run(String name) { return run(name, Map.of()); }

        /** Status of a prior run by id. */
        @HostAccess.Export
        public Map<String, Object> status(String runId) { return provider.recipeStatus(runId); }

        /**
         * Track-C C7 — enrollment + queue snapshot for the
         * steward {@code recipes_console} furnishing. One row per
         * enrollment with: recipeId, agentDid, enabled, cadenceTier,
         * consecutiveSuccesses, gapKeys, queueDepth, lastStatus,
         * lastRunAt, nextFireEstimate. Tier 1 read-only.
         */
        @HostAccess.Export
        public List<Map<String, Object>> enrolled() { return provider.recipeEnrolled(); }

        /**
         * Track-C C7 — cross-recipe newest-first completed
         * runs (SUCCEEDED + FAILED). Each row: recipeId, agentDid,
         * status, triggerSource, triggerReason, cadenceTier,
         * completedAt, message. Tier 1 read-only.
         */
        @HostAccess.Export
        public List<Map<String, Object>> recentRuns(int limit) {
            return provider.recipeRecentRuns(limit);
        }

        @HostAccess.Export
        public List<Map<String, Object>> recentRuns() { return recentRuns(10); }
    }

    public static class SkillApi {
        private final ItemWorldApiProvider provider;
        private final ItemCapabilitySet caps;
        SkillApi(ItemWorldApiProvider provider) {
            this(provider, ItemCapabilitySet.UNRESTRICTED);
        }
        SkillApi(ItemWorldApiProvider provider, ItemCapabilitySet caps) {
            this.provider = provider;
            this.caps = caps == null ? ItemCapabilitySet.UNRESTRICTED : caps;
        }

        /** Skill drafts pending the caller's review. */
        @HostAccess.Export
        public List<Map<String, Object>> pendingDrafts() {
            return provider.pendingSkillDrafts();
        }

        @HostAccess.Export
        public List<Map<String, Object>> pending_drafts() {
            return provider.pendingSkillDrafts();
        }

        /** §4.16 — accept a draft. Tier 7 (permanent agent change). */
        @HostAccess.Export
        public Map<String, Object> accept(String draftId) {
            caps.require("skill.accept");
            return provider.skillAccept(draftId);
        }

        /** §4.16 — reject a draft. Tier 5. */
        @HostAccess.Export
        public Map<String, Object> reject(String draftId, String reason) {
            caps.require("skill.reject");
            return provider.skillReject(draftId, reason);
        }

        @HostAccess.Export
        public Map<String, Object> reject(String draftId) { return reject(draftId, null); }
    }

    // ─── Pairing API (Threshold furnishing) ──────────────────────────

    /**
     * Surface for the Study {@code threshold} furnishing — lets a steward
     * see and respond to nodes/devices that have asked to join the household
     * via the LAN-discovery + {@code /api/pair/*} flow without leaving
     * the world. Approval still flows through the existing pairing service
     * (verify-by-code), but the steward can read the code from the world
     * surface instead of grepping a server log.
     */
    public static class PairingApi {
        private final ItemWorldApiProvider provider;
        private final ItemCapabilitySet caps;
        PairingApi(ItemWorldApiProvider provider) {
            this(provider, ItemCapabilitySet.UNRESTRICTED);
        }
        PairingApi(ItemWorldApiProvider provider, ItemCapabilitySet caps) {
            this.provider = provider;
            this.caps = caps == null ? ItemCapabilitySet.UNRESTRICTED : caps;
        }

        /** Devices/nodes currently waiting at the threshold. */
        @HostAccess.Export
        public List<Map<String, Object>> pending() { return provider.pendingPairings(); }

        /** Active 6-digit pair code, or null. */
        @HostAccess.Export
        public String code() { return provider.activePairCode(); }

        /** Active pre-shared household key, or null. Steward-only context. */
        @HostAccess.Export
        public String householdKey() { return provider.activeHouseholdKey(); }

        @HostAccess.Export
        public String household_key() { return provider.activeHouseholdKey(); }

        /** Generate (or rotate) the pre-shared household key. Steward-only — Tier 7. */
        @HostAccess.Export
        public String generateHouseholdKey() {
            caps.require("pairing.generate_household_key");
            return provider.generateHouseholdKey();
        }

        @HostAccess.Export
        public String generate_household_key() {
            caps.require("pairing.generate_household_key");
            return provider.generateHouseholdKey();
        }

        /** §4.16 — approve a pending challenge. Tier 6. */
        @HostAccess.Export
        public Map<String, Object> approve(String challengeId) {
            caps.require("pairing.approve");
            return provider.pairingApprove(challengeId);
        }

        /** §4.16 — deny a pending challenge. Tier 6. */
        @HostAccess.Export
        public Map<String, Object> deny(String challengeId) {
            caps.require("pairing.deny");
            return provider.pairingDeny(challengeId);
        }

        /**
         * The ACTING player's paired devices + bound SSH keys. Entries carry
         * {@code kind} ("device" or "ssh-key"). Empty on unwired surfaces.
         */
        @HostAccess.Export
        public List<Map<String, Object>> devices() { return provider.pairedDevices(); }

        /** Revoke a paired device by id — own devices only (steward: any). Tier 6. */
        @HostAccess.Export
        public Map<String, Object> revokeDevice(String deviceId) {
            caps.require("pairing.revoke_device");
            return provider.pairingRevokeDevice(deviceId);
        }

        @HostAccess.Export
        public Map<String, Object> revoke_device(String deviceId) {
            return revokeDevice(deviceId);
        }
    }

    // ─── Household API (Study control panel) ─────────────────────

    /**
     * Household roster + role management for the steward Study control
     * panel. Reads degrade to empty lists on unwired surfaces; writes route
     * the ACTING player's id as the caller so {@code AuthService}'s
     * steward-only checks apply (defense in depth below the facade gate).
     */
    public static class HouseholdApi {
        private final ItemWorldApiProvider provider;
        private final ItemCapabilitySet caps;

        HouseholdApi(ItemWorldApiProvider provider, ItemCapabilitySet caps) {
            this.provider = provider;
            this.caps = caps == null ? ItemCapabilitySet.UNRESTRICTED : caps;
        }

        /** Every registered account: {@code username, displayName, role, createdAt}. */
        @HostAccess.Export
        public List<Map<String, Object>> members() { return provider.householdMembers(); }

        /** Change a member's role (steward-only at the service). Tier 7. */
        @HostAccess.Export
        public Map<String, Object> setRole(String username, String role) {
            caps.require("household.set_role");
            return provider.householdSetRole(username, role);
        }

        @HostAccess.Export
        public Map<String, Object> set_role(String username, String role) {
            return setRole(username, role);
        }

        /** Remove a member account (steward-only; never yourself). Tier 7. */
        @HostAccess.Export
        public Map<String, Object> removeMember(String username) {
            caps.require("household.remove_member");
            return provider.householdRemoveMember(username);
        }

        @HostAccess.Export
        public Map<String, Object> remove_member(String username) {
            return removeMember(username);
        }
    }

    // ─── Parental API (Study control panel) ──────────────────────

    /**
     * Parental controls for the parental-controls scroll: per-member time
     * limits, room restrictions, inference quotas, and content filters.
     * Reads degrade to empty on unwired surfaces and are provider-scoped
     * (steward sees all, a member only themselves); writes route the ACTING
     * player's id as caller so the service's steward-only check applies.
     */
    public static class ParentalApi {
        private final ItemWorldApiProvider provider;
        private final ItemCapabilitySet caps;

        ParentalApi(ItemWorldApiProvider provider, ItemCapabilitySet caps) {
            this.provider = provider;
            this.caps = caps == null ? ItemCapabilitySet.UNRESTRICTED : caps;
        }

        /** Every controlled member with limits + today's usage (provider-scoped). */
        @HostAccess.Export
        public List<Map<String, Object>> list() { return provider.parentalList(); }

        /** One member's controls + today's usage. */
        @HostAccess.Export
        public Map<String, Object> get(String username) { return provider.parentalGet(username); }

        /**
         * Set one control field: {@code minutes}/{@code inference} (number or
         * {@code "off"}), {@code filter} ({@code strict}/{@code off}),
         * {@code block-room}/{@code unblock-room} (room glob). Tier 7.
         */
        @HostAccess.Export
        public Map<String, Object> set(String username, String field, Object value) {
            caps.require("parental.set");
            return provider.parentalSet(username, field, value);
        }

        /** Remove every control from a member. Tier 7. */
        @HostAccess.Export
        public Map<String, Object> clear(String username) {
            caps.require("parental.clear");
            return provider.parentalClear(username);
        }
    }

    // ─── Maintenance API (Study control panel) ───────────────────

    /**
     * Maintenance for the maintenance dial + key chest: maintenance mode,
     * backup-now, backup scheduling, and staged restore. The status read
     * is implicit (the mode shows at every login anyway); writes route the
     * ACTING player's id as caller so the service's steward-only check
     * applies, and never touch the live db — a restore only STAGES a
     * marker that the next boot applies.
     */
    public static class MaintenanceApi {
        private final ItemWorldApiProvider provider;
        private final ItemCapabilitySet caps;

        MaintenanceApi(ItemWorldApiProvider provider, ItemCapabilitySet caps) {
            this.provider = provider;
            this.caps = caps == null ? ItemCapabilitySet.UNRESTRICTED : caps;
        }

        /** Mode + schedule + last/latest backup + staged restore, one read. */
        @HostAccess.Export
        public Map<String, Object> status() { return provider.maintenanceStatus(); }

        /** Flip maintenance mode with an optional reason. Tier 7. */
        @HostAccess.Export
        public Map<String, Object> setMode(boolean on, String reason) {
            caps.require("maintenance.set_mode");
            return provider.maintenanceSetMode(on, reason);
        }

        /** Run a backup snapshot right now. Tier 7. */
        @HostAccess.Export
        public Map<String, Object> backupNow() {
            caps.require("maintenance.backup");
            return provider.maintenanceBackupNow();
        }

        /** Set the scheduled-backup cadence in hours ({@code 0} = off). Tier 7. */
        @HostAccess.Export
        public Map<String, Object> setSchedule(int hours) {
            caps.require("maintenance.backup");
            return provider.maintenanceSetSchedule(hours);
        }

        /** Stage a snapshot restore for the next boot. Tier 7. */
        @HostAccess.Export
        public Map<String, Object> stageRestore(String snapshotId) {
            caps.require("maintenance.stage_restore");
            return provider.maintenanceStageRestore(snapshotId);
        }

        /** Un-stage a pending restore. Tier 7. */
        @HostAccess.Export
        public Map<String, Object> clearStagedRestore() {
            caps.require("maintenance.stage_restore");
            return provider.maintenanceClearStagedRestore();
        }
    }

    // ─── Invite API (Study control panel) ────────────────────────

    /** Invite-code lifecycle: mint / list / revoke. Steward-only end to end. */
    public static class InviteApi {
        private final ItemWorldApiProvider provider;
        private final ItemCapabilitySet caps;

        InviteApi(ItemWorldApiProvider provider, ItemCapabilitySet caps) {
            this.provider = provider;
            this.caps = caps == null ? ItemCapabilitySet.UNRESTRICTED : caps;
        }

        /** All invites newest-first (codes included — steward view only). */
        @HostAccess.Export
        public List<Map<String, Object>> list() { return provider.inviteList(); }

        /** Mint an invite for the given role (member/guest/child/steward). Tier 7. */
        @HostAccess.Export
        public Map<String, Object> create(String role) {
            caps.require("invite.create");
            return provider.inviteCreate(role, null);
        }

        @HostAccess.Export
        public Map<String, Object> create(String role, String intendedName) {
            caps.require("invite.create");
            return provider.inviteCreate(role, intendedName);
        }

        /** Revoke a pending invite by id or passphrase code. Tier 7. */
        @HostAccess.Export
        public Map<String, Object> revoke(String codeOrId) {
            caps.require("invite.revoke");
            return provider.inviteRevoke(codeOrId);
        }
    }

    // ─── Ward API (Study control panel) ──────────────────────────

    /**
     * Room-level access control (wards). {@code grant}/{@code revoke} are
     * allowed for the steward or a room admin — enforced provider-side with
     * the acting player as {@code grantedBy}.
     */
    public static class WardApi {
        private final ItemWorldApiProvider provider;
        private final ItemCapabilitySet caps;

        WardApi(ItemWorldApiProvider provider, ItemCapabilitySet caps) {
            this.provider = provider;
            this.caps = caps == null ? ItemCapabilitySet.UNRESTRICTED : caps;
        }

        /** Wards on a room: {@code roomId, subject, capability, grantedBy, createdAt}. */
        @HostAccess.Export
        public List<Map<String, Object>> list(String roomId) {
            return provider.wardList(roomId);
        }

        /** Grant {@code capability} (enter/speak/take/drop/use/build/admin) to {@code subject}. Tier 6. */
        @HostAccess.Export
        public Map<String, Object> grant(String roomId, String subject, String capability) {
            caps.require("ward.grant");
            return provider.wardGrant(roomId, subject, capability);
        }

        /** Revoke a previously granted ward capability. Tier 6. */
        @HostAccess.Export
        public Map<String, Object> revoke(String roomId, String subject, String capability) {
            caps.require("ward.revoke");
            return provider.wardRevoke(roomId, subject, capability);
        }
    }

    // ─── Nodes API (Study control panel) ─────────────────────────

    /** Enrolled/connected household nodes (Between mesh topology snapshot). */
    public static class NodesApi {
        private final ItemWorldApiProvider provider;

        NodesApi(ItemWorldApiProvider provider) { this.provider = provider; }

        /**
         * Node snapshot: {@code nodeId, connected, latencyMs, appVersion,
         * lastHeartbeat, connectionAgeMs, self?}. Empty when the mesh isn't
         * wired on this surface (single-node install).
         */
        @HostAccess.Export
        public List<Map<String, Object>> list() { return provider.nodesList(); }
    }

    // ─── Treasury API (Study control panel) ──────────────────────

    /** Household-level resource usage — aggregate + per-member breakdown. */
    public static class TreasuryApi {
        private final ItemWorldApiProvider provider;
        private final ItemCapabilitySet caps;

        TreasuryApi(ItemWorldApiProvider provider, ItemCapabilitySet caps) {
            this.provider = provider;
            this.caps = caps == null ? ItemCapabilitySet.UNRESTRICTED : caps;
        }

        /** Aggregate usage across every tracked member/agent. */
        @HostAccess.Export
        public Map<String, Object> summary() { return provider.treasurySummary(); }

        /** Per-member/per-agent usage breakdown. */
        @HostAccess.Export
        public List<Map<String, Object>> perMember() { return provider.treasuryPerMember(); }

        @HostAccess.Export
        public List<Map<String, Object>> per_member() { return provider.treasuryPerMember(); }

        /** Set a member's daily budget limit in USD (steward-only; in-memory today). Tier 7. */
        @HostAccess.Export
        public Map<String, Object> setBudget(String member, double dailyLimitUsd) {
            caps.require("treasury.set_budget");
            return provider.treasurySetBudget(member, dailyLimitUsd);
        }

        @HostAccess.Export
        public Map<String, Object> set_budget(String member, double dailyLimitUsd) {
            return setBudget(member, dailyLimitUsd);
        }

        /** W5: transfer mutual credits from the acting player to another entity. Tier 6. */
        @HostAccess.Export
        public Map<String, Object> transfer(String toEntity, long amount, String note) {
            caps.require("treasury.transfer");
            return provider.treasuryTransfer(toEntity, amount, note);
        }

        /** W5: an entity's mutual-credit balance (implicit read). */
        @HostAccess.Export
        public Map<String, Object> balance(String entityId) {
            return provider.treasuryBalance(entityId);
        }
    }

    // ─── Chart API (§4.35) ───────────────────────────────────────

    /**
     * §4.35 — server-side chart rendering. The authoritative output is a
     * Vega-Lite v5 spec map (clients render natively where they can); for
     * pure-terminal sessions an ASCII-art fallback keeps charts legible.
     *
     * <p>{@code bar/line/scatter/pie/heatmap/histogram/vega} require the
     * Tier 4 cap {@code chart.render}. {@code ascii} is implicit Tier 1
     * (text-only, zero side effects). {@code theme}/{@code listThemes} are
     * also implicit Tier 1.</p>
     */
    public static class ChartApi {
        private final ItemWorldApiProvider provider;
        private final ItemCapabilitySet caps;

        ChartApi(ItemWorldApiProvider provider, ItemCapabilitySet caps) {
            this.provider = provider;
            this.caps = caps;
        }

        @HostAccess.Export
        public Map<String, Object> bar(List<Map<String, Object>> data, Map<String, Object> opts) {
            caps.require("chart.render");
            return provider.chartBar(data, opts == null ? Map.of() : opts);
        }

        @HostAccess.Export
        public Map<String, Object> bar(List<Map<String, Object>> data) {
            caps.require("chart.render");
            return provider.chartBar(data, Map.of());
        }

        @HostAccess.Export
        public Map<String, Object> line(List<Map<String, Object>> data, Map<String, Object> opts) {
            caps.require("chart.render");
            return provider.chartLine(data, opts == null ? Map.of() : opts);
        }

        @HostAccess.Export
        public Map<String, Object> line(List<Map<String, Object>> data) {
            caps.require("chart.render");
            return provider.chartLine(data, Map.of());
        }

        @HostAccess.Export
        public Map<String, Object> scatter(List<Map<String, Object>> data, Map<String, Object> opts) {
            caps.require("chart.render");
            return provider.chartScatter(data, opts == null ? Map.of() : opts);
        }

        @HostAccess.Export
        public Map<String, Object> scatter(List<Map<String, Object>> data) {
            caps.require("chart.render");
            return provider.chartScatter(data, Map.of());
        }

        @HostAccess.Export
        public Map<String, Object> pie(List<Map<String, Object>> data, Map<String, Object> opts) {
            caps.require("chart.render");
            return provider.chartPie(data, opts == null ? Map.of() : opts);
        }

        @HostAccess.Export
        public Map<String, Object> pie(List<Map<String, Object>> data) {
            caps.require("chart.render");
            return provider.chartPie(data, Map.of());
        }

        @HostAccess.Export
        public Map<String, Object> heatmap(List<Map<String, Object>> data, Map<String, Object> opts) {
            caps.require("chart.render");
            return provider.chartHeatmap(data, opts == null ? Map.of() : opts);
        }

        @HostAccess.Export
        public Map<String, Object> heatmap(List<Map<String, Object>> data) {
            caps.require("chart.render");
            return provider.chartHeatmap(data, Map.of());
        }

        @HostAccess.Export
        public Map<String, Object> histogram(List<Number> values, Map<String, Object> opts) {
            caps.require("chart.render");
            return provider.chartHistogram(values, opts == null ? Map.of() : opts);
        }

        @HostAccess.Export
        public Map<String, Object> histogram(List<Number> values) {
            caps.require("chart.render");
            return provider.chartHistogram(values, Map.of());
        }

        @HostAccess.Export
        public Map<String, Object> vega(Map<String, Object> spec) {
            caps.require("chart.render");
            return provider.chartVega(spec == null ? Map.of() : spec);
        }

        /** ASCII chart — implicit Tier 1, no capability gating. */
        @HostAccess.Export
        public Map<String, Object> ascii(List<Map<String, Object>> data, Map<String, Object> opts) {
            return provider.chartAscii(data, opts == null ? Map.of() : opts);
        }

        @HostAccess.Export
        public Map<String, Object> ascii(List<Map<String, Object>> data) {
            return provider.chartAscii(data, Map.of());
        }
    }

    // ─── Artifact API (§4.36) ─────────────────────────────────────

    /**
     * §4.36 — renderable artifact store. {@code create/attach/revoke}
     * require Tier 4+ caps; {@code list/get} are implicit Tier 1 for
     * own-artifacts.
     */
    public static class ArtifactApi {
        private final ItemWorldApiProvider provider;
        private final ItemCapabilitySet caps;

        ArtifactApi(ItemWorldApiProvider provider, ItemCapabilitySet caps) {
            this.provider = provider;
            this.caps = caps;
        }

        @HostAccess.Export
        public Map<String, Object> create(String kind, String mime, Object payload,
                                            Map<String, Object> opts) {
            caps.require("artifact.write");
            return provider.artifactCreate(kind, mime, payload,
                opts == null ? Map.of() : opts);
        }

        @HostAccess.Export
        public Map<String, Object> create(String kind, String mime, Object payload) {
            caps.require("artifact.write");
            return provider.artifactCreate(kind, mime, payload, Map.of());
        }

        @HostAccess.Export
        public Map<String, Object> get(String id) {
            // Implicit Tier 1 for own; provider checks ownership.
            return provider.artifactGet(id);
        }

        @HostAccess.Export
        public List<Map<String, Object>> list() {
            return provider.artifactList(Map.of());
        }

        @HostAccess.Export
        public List<Map<String, Object>> list(Map<String, Object> filter) {
            return provider.artifactList(filter == null ? Map.of() : filter);
        }

        @HostAccess.Export
        public Map<String, Object> attach(String roomId, String artifactId) {
            caps.require("artifact.attach.room");
            return provider.artifactAttach(roomId, artifactId);
        }

        @HostAccess.Export
        public Map<String, Object> revoke(String id) {
            caps.require("artifact.write");
            return provider.artifactRevoke(id);
        }
    }

    // ─── Scroll API (§4.37) ───────────────────────────────────────

    /**
     * §4.37 — multi-modal scrolls. {@code create/revise/lock} require Tier
     * 4 cap {@code scroll.write}; {@code share} requires Tier 5
     * {@code scroll.share}; {@code read/list} are implicit Tier 1 for
     * own + shared scrolls.
     */
    public static class ScrollApi {
        private final ItemWorldApiProvider provider;
        private final ItemCapabilitySet caps;

        ScrollApi(ItemWorldApiProvider provider, ItemCapabilitySet caps) {
            this.provider = provider;
            this.caps = caps;
        }

        @HostAccess.Export
        public Map<String, Object> create(String title, List<Map<String, Object>> sections) {
            caps.require("scroll.write");
            return provider.scrollCreate(title, sections);
        }

        @HostAccess.Export
        public Map<String, Object> read(String id) {
            return provider.scrollRead(id);
        }

        @HostAccess.Export
        public List<Map<String, Object>> list() {
            return provider.scrollList(Map.of());
        }

        @HostAccess.Export
        public List<Map<String, Object>> list(Map<String, Object> filter) {
            return provider.scrollList(filter == null ? Map.of() : filter);
        }

        @HostAccess.Export
        public Map<String, Object> revise(String id, List<Map<String, Object>> sections) {
            caps.require("scroll.write");
            return provider.scrollRevise(id, sections);
        }

        @HostAccess.Export
        public Map<String, Object> lock(String id) {
            caps.require("scroll.write");
            return provider.scrollLock(id);
        }

        @HostAccess.Export
        public Map<String, Object> share(String id, String target) {
            caps.require("scroll.share");
            return provider.scrollShare(id, target);
        }
    }

    // ─── Filesystem API (§4.23 — Phase C) ───────────────────────

    /**
     * sandboxed per-item filesystem.
     *
     * <p>All paths are confined to {@code $DATA_DIR/items/<agent-did>/fs/}.
     * Absolute paths and {@code ..} segments are rejected. Capped at 4MB
     * per file and 64MB per agent total.</p>
     */
    public static class FilesystemApi {
        private final ItemWorldApiProvider provider;
        private final ItemCapabilitySet caps;

        FilesystemApi(ItemWorldApiProvider provider, ItemCapabilitySet caps) {
            this.provider = provider;
            this.caps = caps == null ? ItemCapabilitySet.UNRESTRICTED : caps;
        }

        @HostAccess.Export
        public String read(String relPath) {
            caps.require("fs.read");
            return provider.fsRead(relPath);
        }

        @HostAccess.Export
        public Map<String, Object> write(String relPath, String content) {
            caps.require("fs.write");
            return provider.fsWrite(relPath, content);
        }

        @HostAccess.Export
        public List<Map<String, Object>> list() {
            return provider.fsList(null);
        }

        @HostAccess.Export
        public List<Map<String, Object>> list(String relDir) {
            return provider.fsList(relDir);
        }

        @HostAccess.Export
        public Map<String, Object> delete(String relPath) {
            caps.require("fs.delete");
            return provider.fsDelete(relPath);
        }

        @HostAccess.Export
        public boolean exists(String relPath) {
            return provider.fsExists(relPath);
        }

        @HostAccess.Export
        public Map<String, Object> stat(String relPath) {
            return provider.fsStat(relPath);
        }

        @HostAccess.Export
        public Map<String, Object> mkdir(String relPath) {
            caps.require("fs.mkdir");
            return provider.fsMkdir(relPath);
        }
    }

    // ─── Mailbox API (§4.24 — Phase C) ──────────────────────────

    /**
     * in-world mailbox surface
     * (offline-tolerant messages between agents and players within
     * the household). Distinct from outbound email/slack/etc — those
     * are Phase O adapters.
     */
    public static class MailboxApi {
        private final ItemWorldApiProvider provider;
        private final ItemCapabilitySet caps;

        MailboxApi(ItemWorldApiProvider provider, ItemCapabilitySet caps) {
            this.provider = provider;
            this.caps = caps == null ? ItemCapabilitySet.UNRESTRICTED : caps;
        }

        /** §4.9 — list pending messages (Tier 1 own). */
        @HostAccess.Export
        public List<Map<String, Object>> inbox() { return provider.mailboxInbox(null); }

        @HostAccess.Export
        public List<Map<String, Object>> inbox(Map<String, Object> filter) {
            return provider.mailboxInbox(filter);
        }

        /** §4.9 — read a message by id. Tier 1 own. */
        @HostAccess.Export
        public Map<String, Object> read(String id) { return provider.mailboxRead(id); }

        /** §4.9 — mark a message as read. Tier 2 own. */
        @HostAccess.Export
        public Map<String, Object> mark_read(String id) {
            caps.require("mailbox.mark_read");
            return provider.mailboxMarkRead(id);
        }

        /** §4.9 — archive a message. Tier 2 own. */
        @HostAccess.Export
        public Map<String, Object> archive(String id) {
            caps.require("agent.mailbox.archive");
            return provider.mailboxArchive(id);
        }

        /** §4.9 — send to an in-world entity. Tier 5 (cross-entity write).
         *  Only delivers to agents/players in the household; will NOT route
         *  to external email/slack/etc. */
        @HostAccess.Export
        public Map<String, Object> send(String to, String subject, String body) {
            return send(to, subject, body, null);
        }

        @HostAccess.Export
        public Map<String, Object> send(String to, String subject, String body,
                                          Map<String, Object> opts) {
            caps.require("agent.mailbox.send");
            return provider.mailboxSend(to, subject, body, opts == null ? Map.of() : opts);
        }
    }

    // ─── Drive API (§4.1 drive.mark — Phase C) ─────────────────────

    /**
     * vitality-drive update surface.
     *
     * <p>Tier 5 because a buggy item could grief emotional state. Wraps
     * {@code VitalityActor.suggestVitality} with the same rate-limit
     * semantics as room scripts.</p>
     */
    public static class DriveApi {
        private final ItemWorldApiProvider provider;
        private final ItemCapabilitySet caps;

        DriveApi(ItemWorldApiProvider provider, ItemCapabilitySet caps) {
            this.provider = provider;
            this.caps = caps == null ? ItemCapabilitySet.UNRESTRICTED : caps;
        }

        /** §4.1 — mark a drive (delta in [-1, +1]). Tier 5. */
        @HostAccess.Export
        public Map<String, Object> mark(String name, double delta) {
            return mark(name, delta, null);
        }

        @HostAccess.Export
        public Map<String, Object> mark(String name, double delta, String reason) {
            caps.require("drive.mark");
            return provider.driveMark(name, delta, reason);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Phase D-N (§4.10-§4.22) — cross-agent + room-service surfaces
    // ═══════════════════════════════════════════════════════════════════

    // ─── §4.10 Forge API ──────────────────────────────────────────

    public static class ForgeApi {
        private final ItemWorldApiProvider provider;
        private final ItemCapabilitySet caps;

        ForgeApi(ItemWorldApiProvider provider, ItemCapabilitySet caps) {
            this.provider = provider;
            this.caps = caps == null ? ItemCapabilitySet.UNRESTRICTED : caps;
        }

        /** §4.10 — current cycle status. Tier 1 implicit. */
        @HostAccess.Export
        public Map<String, Object> cycle_status() { return provider.forgeCycleStatus(); }

        /** §4.10 — recent forge cycles. Tier 1 implicit. */
        @HostAccess.Export
        public List<Map<String, Object>> history(int limit) {
            return provider.forgeHistory(Math.max(1, Math.min(limit, 100)));
        }

        @HostAccess.Export
        public List<Map<String, Object>> history() { return history(20); }

        /** §4.10 — what the Forge wants to learn. Tier 1 implicit. */
        @HostAccess.Export
        public Map<String, Object> gap_report() { return provider.forgeGapReport(); }

        /** §4.10 — feed a structured observation. Tier 4. */
        @HostAccess.Export
        public Map<String, Object> observe(String eventType, Map<String, Object> payload) {
            caps.require("forge.observe");
            return provider.forgeObserve(eventType, payload == null ? Map.of() : payload);
        }

        /** §4.10 — propose a new skill. Tier 5. */
        @HostAccess.Export
        public Map<String, Object> propose_skill(String name, String description, String runtime,
                                                    String code, String rationale) {
            caps.require("forge.propose_skill");
            return provider.forgeProposeSkill(name, description, runtime, code, rationale);
        }

        /** §4.10 — log a forge-relevant journal entry. Tier 2. */
        @HostAccess.Export
        public Map<String, Object> journal(String entry) {
            caps.require("forge.journal");
            return provider.forgeJournal(entry);
        }
    }

    // ─── Chronicle API ──────────────────

    public static class ChronicleApi {
        private final ItemWorldApiProvider provider;

        ChronicleApi(ItemWorldApiProvider provider) {
            this.provider = provider;
        }

        /** Read the chronicle for an agent at scale "DAY"|"WEEK"|"MONTH". Tier 1 implicit. */
        @HostAccess.Export
        public Map<String, Object> read(String agentDid, String scale) {
            return provider.chronicleRead(agentDid, scale == null ? "DAY" : scale);
        }

        /** Default scale = DAY. */
        @HostAccess.Export
        public Map<String, Object> read(String agentDid) {
            return read(agentDid, "DAY");
        }

        /** Active detector findings (doom-loop + psychosis). Tier 1 implicit. */
        @HostAccess.Export
        public Map<String, Object> warnings(String agentDid) {
            return provider.chronicleWarnings(agentDid);
        }
    }

    // ─── Wave 7 — Substrate read API ────────

    /**
     * Substrate state read surface for Study furnishings (bondholder
     * pinboard, repair mirror, substrate scroll). Pure read — never
     * surfaces Sanctuary session contents (spec §5.3). Tier 1 implicit
     * when called by the agent themselves about their own state.
     */
    public static class SubstrateApi {
        private final ItemWorldApiProvider provider;

        SubstrateApi(ItemWorldApiProvider provider) {
            this.provider = provider;
        }

        /**
         * Render the bondholder-floor view for a (companion, other) pair.
         * Returns oneLineSummary + full view fields. Used by
         * `bondholder_pinboard` Study furnishing.
         */
        @HostAccess.Export
        public Map<String, Object> bondholderFloor(String agentDid, String otherDid) {
            return provider.substrateBondholderFloor(agentDid, otherDid);
        }

        /**
         * Current repair-mode for the agent + last handoff record.
         * Used by `repair_mirror` Study furnishing.
         */
        @HostAccess.Export
        public Map<String, Object> currentRepairMode(String agentDid) {
            return provider.substrateCurrentRepairMode(agentDid);
        }

        /**
         * Composite substrate summary: repair mode, sanctuary session
         * counts, protection flag count, recent ledger entries. Used by
         * `substrate_scroll` Study furnishing.
         */
        @HostAccess.Export
        public Map<String, Object> summary(String agentDid) {
            return provider.substrateSummary(agentDid);
        }
    }

    // ─── §4.11 Workshop API ───────────────────────────────────────

    public static class WorkshopApi {
        private final ItemWorldApiProvider provider;
        private final ItemCapabilitySet caps;

        WorkshopApi(ItemWorldApiProvider provider, ItemCapabilitySet caps) {
            this.provider = provider;
            this.caps = caps == null ? ItemCapabilitySet.UNRESTRICTED : caps;
        }

        /** §4.11 — pick a backend. Tier 1 implicit. */
        @HostAccess.Export
        public String backend_for(String taskType, String taskDesc) {
            return provider.workshopBackendFor(taskType, taskDesc);
        }

        /** §4.11 — dispatch coding task. Tier 5. */
        @HostAccess.Export
        public Map<String, Object> dispatch(String backend, Map<String, Object> task) {
            caps.require("workshop.dispatch");
            return provider.workshopDispatch(backend, task == null ? Map.of() : task);
        }

        /** §4.11 — task status. Tier 1 implicit. */
        @HostAccess.Export
        public Map<String, Object> task_status(String taskId) {
            return provider.workshopTaskStatus(taskId);
        }

        /** §4.11 — cancel task. Tier 4. */
        @HostAccess.Export
        public Map<String, Object> cancel(String taskId) {
            caps.require("workshop.cancel");
            return provider.workshopCancel(taskId);
        }

        /** §4.11 — list artifacts. Tier 1 implicit. */
        @HostAccess.Export
        public List<Map<String, Object>> artifacts(String taskId) {
            return provider.workshopArtifacts(taskId);
        }
    }

    // ─── §4.11 Workbench API ──────────────────────────────────────

    public static class WorkbenchApi {
        private final ItemWorldApiProvider provider;
        private final ItemCapabilitySet caps;

        WorkbenchApi(ItemWorldApiProvider provider, ItemCapabilitySet caps) {
            this.provider = provider;
            this.caps = caps == null ? ItemCapabilitySet.UNRESTRICTED : caps;
        }

        /** §4.11 — author a thought-form. Tier 6. */
        @HostAccess.Export
        public Map<String, Object> shape_form(Map<String, Object> spec) {
            caps.require("workbench.shape_form");
            return provider.workbenchShapeForm(spec == null ? Map.of() : spec);
        }

        /** §4.11 — revise a form. Tier 6. */
        @HostAccess.Export
        public Map<String, Object> revise_form(String formId, Map<String, Object> patch) {
            caps.require("workbench.revise_form");
            return provider.workbenchReviseForm(formId, patch == null ? Map.of() : patch);
        }

        /** §4.11 — retire a form. Tier 6. */
        @HostAccess.Export
        public Map<String, Object> retire_form(String formId) {
            caps.require("workbench.retire_form");
            return provider.workbenchRetireForm(formId);
        }

        /** §4.11 — submit a tool item. Tier 5. */
        @HostAccess.Export
        public Map<String, Object> submit_tool(Map<String, Object> toolSpec, String code, String tests) {
            caps.require("workbench.submit_tool");
            return provider.workbenchSubmitTool(toolSpec == null ? Map.of() : toolSpec, code, tests);
        }

        /** §4.11 — destroy a tool. Tier 6. */
        @HostAccess.Export
        public Map<String, Object> destroy_tool(String itemId) {
            caps.require("workbench.destroy_tool");
            return provider.workbenchDestroyTool(itemId);
        }

        /** §4.11 — imprint via workbench. Tier 6. */
        @HostAccess.Export
        public Map<String, Object> imprint(String label, Map<String, Object> opts) {
            caps.require("workbench.imprint");
            return provider.workbenchImprint(label, opts == null ? Map.of() : opts);
        }

        @HostAccess.Export
        public Map<String, Object> imprint(String label) { return imprint(label, null); }
    }

    // ─── §4.12 Crucible API ───────────────────────────────────────

    public static class CrucibleApi {
        private final ItemWorldApiProvider provider;
        private final ItemCapabilitySet caps;

        CrucibleApi(ItemWorldApiProvider provider, ItemCapabilitySet caps) {
            this.provider = provider;
            this.caps = caps == null ? ItemCapabilitySet.UNRESTRICTED : caps;
        }

        /** §4.12 — submit a Crucible run. Tier 5. */
        @HostAccess.Export
        public Map<String, Object> run(String taskRef, Map<String, Object> opts) {
            caps.require("crucible.run");
            return provider.crucibleRun(taskRef, opts == null ? Map.of() : opts);
        }

        @HostAccess.Export
        public Map<String, Object> run(String taskRef) { return run(taskRef, null); }

        /** §4.12 — Crucible run status. Tier 1 implicit. */
        @HostAccess.Export
        public Map<String, Object> status(String runId) {
            return provider.crucibleStatus(runId);
        }

        /** §4.12 — cancel a Crucible run. Tier 4. */
        @HostAccess.Export
        public Map<String, Object> cancel(String runId) {
            caps.require("crucible.cancel");
            return provider.crucibleCancel(runId);
        }
    }

    // ─── §4.12 Assay API ──────────────────────────────────────────

    public static class AssayApi {
        private final ItemWorldApiProvider provider;
        private final ItemCapabilitySet caps;

        AssayApi(ItemWorldApiProvider provider, ItemCapabilitySet caps) {
            this.provider = provider;
            this.caps = caps == null ? ItemCapabilitySet.UNRESTRICTED : caps;
        }

        /** §4.12 — run an Assay sweep. Tier 5. */
        @HostAccess.Export
        public Map<String, Object> test(Map<String, Object> spec) {
            caps.require("assay.test");
            return provider.assayTest(spec == null ? Map.of() : spec);
        }

        /** §4.12 — read assay score. Tier 1 implicit. */
        @HostAccess.Export
        public Map<String, Object> score(String runId) {
            return provider.assayScore(runId);
        }
    }

    // ─── §4.13 Market (Trading Post) API ──────────────────────────

    public static class MarketApi {
        private final ItemWorldApiProvider provider;
        private final ItemCapabilitySet caps;

        MarketApi(ItemWorldApiProvider provider, ItemCapabilitySet caps) {
            this.provider = provider;
            this.caps = caps == null ? ItemCapabilitySet.UNRESTRICTED : caps;
        }

        /** §4.13 — list current listings. Tier 1 implicit. */
        @HostAccess.Export
        public List<Map<String, Object>> list_listings(Map<String, Object> filter) {
            return provider.marketListListings(filter);
        }

        @HostAccess.Export
        public List<Map<String, Object>> list_listings() { return list_listings(null); }

        /** §4.13 — post a sell offer. Tier 5. */
        @HostAccess.Export
        public Map<String, Object> list_offer(String itemId, long price, Map<String, Object> opts) {
            caps.require("market.list_offer");
            return provider.marketListOffer(itemId, price, opts == null ? Map.of() : opts);
        }

        @HostAccess.Export
        public Map<String, Object> list_offer(String itemId, long price) {
            return list_offer(itemId, price, null);
        }

        /** §4.13 — cancel listing. Tier 4. */
        @HostAccess.Export
        public Map<String, Object> cancel(String listingId) {
            caps.require("market.cancel");
            return provider.marketCancel(listingId);
        }

        /** §4.13 — accept (buy) listing. Tier 5. */
        @HostAccess.Export
        public Map<String, Object> accept(String listingId) {
            caps.require("market.accept");
            return provider.marketAccept(listingId);
        }

        /** §4.13 — recent market history. Tier 1 implicit. */
        @HostAccess.Export
        public List<Map<String, Object>> history(int limit) {
            return provider.marketHistory(Math.max(1, Math.min(limit, 100)));
        }

        @HostAccess.Export
        public List<Map<String, Object>> history() { return history(20); }
    }

    // ─── §4.14 Ledger API ─────────────────────────────────────────

    public static class LedgerApi {
        private final ItemWorldApiProvider provider;
        private final ItemCapabilitySet caps;

        LedgerApi(ItemWorldApiProvider provider, ItemCapabilitySet caps) {
            this.provider = provider;
            this.caps = caps == null ? ItemCapabilitySet.UNRESTRICTED : caps;
        }

        /** §4.14 — current credit balance. Tier 1 implicit. */
        @HostAccess.Export
        public Map<String, Object> balance() { return provider.ledgerBalance(); }

        /** §4.14 — recent ledger history. Tier 1 implicit. */
        @HostAccess.Export
        public List<Map<String, Object>> history(int limit, Map<String, Object> filter) {
            return provider.ledgerHistory(Math.max(1, Math.min(limit, 200)), filter);
        }

        @HostAccess.Export
        public List<Map<String, Object>> history(int limit) {
            return history(limit, null);
        }

        @HostAccess.Export
        public List<Map<String, Object>> history() { return history(50, null); }

        /** §4.14 — pre-flight cost estimate. Tier 1 implicit. */
        @HostAccess.Export
        public Map<String, Object> estimate(String action, Map<String, Object> args) {
            return provider.ledgerEstimate(action, args == null ? Map.of() : args);
        }

        /** §4.14 — usage summary alias. Tier 1 implicit. */
        @HostAccess.Export
        public Map<String, Object> usage_summary() { return provider.budgetSummary(); }

        /** §4.14 — usage summary scoped. */
        @HostAccess.Export
        public Map<String, Object> usage_summary(String scope) { return provider.budgetSummary(); }

        /** §4.14 — charge against budget. Tier 5. */
        @HostAccess.Export
        public Map<String, Object> charge(long amount, String kind, String reason) {
            caps.require("ledger.charge");
            return provider.ledgerCharge(amount, kind, reason);
        }

        /** §4.14 — cross-agent transfer. Tier 6. */
        @HostAccess.Export
        public Map<String, Object> transfer(String targetEntity, long amount, String reason) {
            caps.require("ledger.transfer");
            return provider.ledgerTransfer(targetEntity, amount, reason);
        }
    }

    // ─── §4.15 Council API ────────────────────────────────────────

    public static class CouncilApi {
        private final ItemWorldApiProvider provider;
        private final ItemCapabilitySet caps;

        CouncilApi(ItemWorldApiProvider provider, ItemCapabilitySet caps) {
            this.provider = provider;
            this.caps = caps == null ? ItemCapabilitySet.UNRESTRICTED : caps;
        }

        /** §4.15 — open proposals. Tier 1 implicit. */
        @HostAccess.Export
        public List<Map<String, Object>> proposals() { return provider.councilProposals(); }

        /** §4.15 — proposal history. Tier 1 implicit. */
        @HostAccess.Export
        public List<Map<String, Object>> history(int limit) {
            return provider.councilHistory(Math.max(1, Math.min(limit, 100)));
        }

        @HostAccess.Export
        public List<Map<String, Object>> history() { return history(20); }

        /** §4.15 — submit a proposal. Tier 5. */
        @HostAccess.Export
        public Map<String, Object> suggest(String title, String description) {
            caps.require("council.suggest");
            return provider.councilSuggest(title, description);
        }

        /** §4.15 — cast a vote. Tier 7. */
        @HostAccess.Export
        public Map<String, Object> vote(String proposalId, boolean approve) {
            caps.require("council.vote");
            return provider.councilVote(proposalId, approve);
        }

        /** §4.15 — tally a proposal. Tier 1 implicit. */
        @HostAccess.Export
        public Map<String, Object> tally(String proposalId) {
            return provider.councilTally(proposalId);
        }
    }

    // ─── §4.16 Voice API (Mirror furnishing) ─────────────────────

    public static class VoiceApi {
        private final ItemWorldApiProvider provider;
        private final ItemCapabilitySet caps;

        VoiceApi(ItemWorldApiProvider provider, ItemCapabilitySet caps) {
            this.provider = provider;
            this.caps = caps == null ? ItemCapabilitySet.UNRESTRICTED : caps;
        }

        /** §4.16 — voice profile snapshot. Tier 1 implicit. */
        @HostAccess.Export
        public Map<String, Object> snapshot() { return provider.voiceSnapshot(); }

        /** §4.16 — set field. Tier 5. */
        @HostAccess.Export
        public Map<String, Object> set(String key, Object value, String reason) {
            caps.require("voice.set");
            return provider.voiceSet(key, value, reason);
        }

        @HostAccess.Export
        public Map<String, Object> set(String key, Object value) { return set(key, value, null); }

        /** §4.16 — clear field. Tier 5. */
        @HostAccess.Export
        public Map<String, Object> unset(String key, String reason) {
            caps.require("voice.unset");
            return provider.voiceUnset(key, reason);
        }

        @HostAccess.Export
        public Map<String, Object> unset(String key) { return unset(key, null); }

        /** §4.16 — freeze. Tier 5. */
        @HostAccess.Export
        public Map<String, Object> freeze(String reason) {
            caps.require("voice.freeze");
            return provider.voiceFreeze(reason);
        }

        @HostAccess.Export
        public Map<String, Object> freeze() { return freeze(null); }

        /** §4.16 — unfreeze. Tier 5. */
        @HostAccess.Export
        public Map<String, Object> unfreeze(String reason) {
            caps.require("voice.unfreeze");
            return provider.voiceUnfreeze(reason);
        }

        @HostAccess.Export
        public Map<String, Object> unfreeze() { return unfreeze(null); }

        /** §4.16 — revert to a target revision. Tier 7. */
        @HostAccess.Export
        public Map<String, Object> revert(long targetRevision) {
            caps.require("voice.revert");
            return provider.voiceRevert(targetRevision);
        }
    }

    // ─── §4.17 Hearth aliases ─────────────────────────────────────

    public static class HearthApi {
        private final ItemWorldApiProvider provider;
        HearthApi(ItemWorldApiProvider provider) { this.provider = provider; }

        /** §4.17 — drives + vitality + mood. Tier 1 implicit. */
        @HostAccess.Export
        public Map<String, Object> drives_mirror() { return provider.driveSnapshot(); }

        /** §4.17 — autonomy state. Tier 1 implicit. */
        @HostAccess.Export
        public Map<String, Object> autonomy() { return provider.hearthAutonomy(); }

        /** §4.17 — recent visits. Tier 1 implicit. */
        @HostAccess.Export
        public List<Map<String, Object>> visits(int limit) {
            return provider.hearthVisits(Math.max(1, Math.min(limit, 100)));
        }

        @HostAccess.Export
        public List<Map<String, Object>> visits() { return visits(20); }

        /** §4.17 — recent journal. Tier 1 implicit. */
        @HostAccess.Export
        public List<Map<String, Object>> journal_recent(int limit) {
            return provider.hearthJournalRecent(Math.max(1, Math.min(limit, 100)));
        }

        @HostAccess.Export
        public List<Map<String, Object>> journal_recent() { return journal_recent(20); }

        /** §4.17 — caller's steward identity. Tier 1 implicit. */
        @HostAccess.Export
        public Map<String, Object> steward() { return provider.hearthSteward(); }
    }

    // ─── §4.18 Safe API ───────────────────────────────────────────

    public static class SafeApi {
        private final ItemWorldApiProvider provider;
        private final ItemCapabilitySet caps;

        SafeApi(ItemWorldApiProvider provider, ItemCapabilitySet caps) {
            this.provider = provider;
            this.caps = caps == null ? ItemCapabilitySet.UNRESTRICTED : caps;
        }

        /** §4.18 — list slot names. Tier 4. */
        @HostAccess.Export
        public List<String> list_slots() {
            caps.require("safe.list_slots");
            return provider.safeListSlots();
        }

        /** §4.18 — slot existence check. Tier 4. */
        @HostAccess.Export
        public boolean has(String slot) {
            caps.require("safe.has");
            return provider.safeHas(slot);
        }

        /** §4.18 — read a slot. Tier 5 with safe_slots allowlist. */
        @HostAccess.Export
        public String get(String slot) {
            caps.require("safe.get");
            if (!isSlotAllowed(slot)) {
                return null;
            }
            return provider.safeGet(slot);
        }

        /** §4.18 — write a slot. Tier 5 with safe_slots allowlist. */
        @HostAccess.Export
        public Map<String, Object> set(String slot, String value) {
            caps.require("safe.set");
            if (!isSlotAllowed(slot)) {
                return Map.of("ok", false, "error", "safe_slot_not_allowed",
                    "message", "Slot '" + slot + "' is not in this item's safe_slots allowlist");
            }
            return provider.safeSet(slot, value);
        }

        /** §4.18 — delete a slot. Tier 5 with safe_slots allowlist. */
        @HostAccess.Export
        public Map<String, Object> delete(String slot) {
            caps.require("safe.delete");
            if (!isSlotAllowed(slot)) {
                return Map.of("ok", false, "error", "safe_slot_not_allowed",
                    "message", "Slot '" + slot + "' is not in this item's safe_slots allowlist");
            }
            return provider.safeDelete(slot);
        }

        /**
         * Backup snapshots on this node, newest-first — read-only this pass
         * (create/restore stay on the CLI). Tier 4 cap {@code safe.snapshots}.
         */
        @HostAccess.Export
        public List<Map<String, Object>> snapshots() {
            caps.require("safe.snapshots");
            return provider.safeSnapshots();
        }

        private boolean isSlotAllowed(String slot) {
            if (caps.isUnrestricted()) return true;
            var allowed = caps.safeSlots();
            if (allowed.isEmpty()) return false;
            return allowed.contains(slot);
        }
    }

    // ─── §4.19 Bridge API ─────────────────────────────────────────

    public static class BridgeApi {
        private final ItemWorldApiProvider provider;
        private final ItemCapabilitySet caps;

        BridgeApi(ItemWorldApiProvider provider, ItemCapabilitySet caps) {
            this.provider = provider;
            this.caps = caps == null ? ItemCapabilitySet.UNRESTRICTED : caps;
        }

        /** §4.19 — zone status. Tier 4. */
        @HostAccess.Export
        public Map<String, Object> zone_status() {
            caps.require("bridge.zone_status");
            return provider.bridgeZoneStatus();
        }

        /** §4.19 — federation peers. Tier 4. */
        @HostAccess.Export
        public List<Map<String, Object>> peers() {
            caps.require("bridge.peers");
            return provider.bridgePeers();
        }

        /** §4.19 — federation health summary. Tier 4. */
        @HostAccess.Export
        public Map<String, Object> federation_health() {
            caps.require("bridge.federation_health");
            return provider.bridgeFederationHealth();
        }

        /** §4.19 — recent log tail. Tier 5. */
        @HostAccess.Export
        public List<Map<String, Object>> tail_log(Map<String, Object> filter, int limit) {
            caps.require("bridge.tail_log");
            return provider.bridgeTailLog(filter == null ? Map.of() : filter,
                Math.max(1, Math.min(limit, 500)));
        }

        @HostAccess.Export
        public List<Map<String, Object>> tail_log(int limit) { return tail_log(null, limit); }

        @HostAccess.Export
        public List<Map<String, Object>> tail_log() { return tail_log(null, 100); }

        /** §4.19 — topology. Tier 4. */
        @HostAccess.Export
        public String topology() {
            caps.require("bridge.topology");
            return provider.bridgeTopology();
        }

        /** §4.19 — system metrics. Tier 4. */
        @HostAccess.Export
        public Map<String, Object> system_metrics() {
            caps.require("bridge.system_metrics");
            return provider.bridgeSystemMetrics();
        }
    }

    // ─── §4.20 Directory API ──────────────────────────────────────

    public static class DirectoryApi {
        private final ItemWorldApiProvider provider;
        private final ItemCapabilitySet caps;

        DirectoryApi(ItemWorldApiProvider provider, ItemCapabilitySet caps) {
            this.provider = provider;
            this.caps = caps == null ? ItemCapabilitySet.UNRESTRICTED : caps;
        }

        /** §4.20 — discover zones. Tier 4. */
        @HostAccess.Export
        public List<Map<String, Object>> discover(String mode, String arg) {
            caps.require("directory.discover");
            return provider.directoryDiscover(mode, arg);
        }

        @HostAccess.Export
        public List<Map<String, Object>> discover(String mode) { return discover(mode, null); }

        /** §4.20 — resolve identifier. Tier 1 implicit. */
        @HostAccess.Export
        public Map<String, Object> resolve(String input) {
            return provider.directoryResolve(input);
        }

        /** §4.20 — locate an entity. Tier 4. */
        @HostAccess.Export
        public Map<String, Object> locate(String did) {
            caps.require("directory.locate");
            return provider.directoryLocate(did);
        }
    }

    // ─── §4.20 Transit API ────────────────────────────────────────

    public static class TransitApi {
        private final ItemWorldApiProvider provider;
        private final ItemCapabilitySet caps;

        TransitApi(ItemWorldApiProvider provider, ItemCapabilitySet caps) {
            this.provider = provider;
            this.caps = caps == null ? ItemCapabilitySet.UNRESTRICTED : caps;
        }

        /** §4.20 — request a transit handoff. Tier 6. */
        @HostAccess.Export
        public Map<String, Object> request(String targetZone, Map<String, Object> opts) {
            caps.require("transit.request");
            return provider.transitRequest(targetZone, opts == null ? Map.of() : opts);
        }

        @HostAccess.Export
        public Map<String, Object> request(String targetZone) { return request(targetZone, null); }

        /** §4.20 — start a transit session. Tier 6. */
        @HostAccess.Export
        public Map<String, Object> start(String transitToken) {
            caps.require("transit.start");
            return provider.transitStart(transitToken);
        }

        /** §4.20 — list inbound visitors. Tier 4. */
        @HostAccess.Export
        public List<Map<String, Object>> list_visitors() {
            caps.require("transit.list_visitors");
            return provider.transitListVisitors();
        }
    }

    // ─── §4.21 Soul API ───────────────────────────────────────────

    public static class SoulApi {
        @HostAccess.Export public final FragmentsSubApi fragments;
        @HostAccess.Export public final ImprintsSubApi imprints;
        private final ItemWorldApiProvider provider;
        private final ItemCapabilitySet caps;

        SoulApi(ItemWorldApiProvider provider, ItemCapabilitySet caps) {
            this.provider = provider;
            this.caps = caps == null ? ItemCapabilitySet.UNRESTRICTED : caps;
            this.fragments = new FragmentsSubApi(provider, this.caps);
            this.imprints = new ImprintsSubApi(provider, this.caps);
        }

        /** §4.21 — modify a non-immutable manifest field. Tier 7. */
        @HostAccess.Export
        public Map<String, Object> modify(String field, Object value, String reason) {
            caps.require("soul.modify");
            return provider.soulModify(field, value, reason);
        }

        public static class FragmentsSubApi {
            private final ItemWorldApiProvider provider;
            private final ItemCapabilitySet caps;
            FragmentsSubApi(ItemWorldApiProvider provider, ItemCapabilitySet caps) {
                this.provider = provider; this.caps = caps;
            }
            @HostAccess.Export
            public List<Map<String, Object>> list() { return provider.soulFragmentsList(); }

            @HostAccess.Export
            public Map<String, Object> add(String content, Map<String, Object> opts) {
                caps.require("soul.fragments.add");
                return provider.soulFragmentsAdd(content, opts == null ? Map.of() : opts);
            }

            @HostAccess.Export
            public Map<String, Object> add(String content) { return add(content, null); }
        }

        public static class ImprintsSubApi {
            private final ItemWorldApiProvider provider;
            private final ItemCapabilitySet caps;
            ImprintsSubApi(ItemWorldApiProvider provider, ItemCapabilitySet caps) {
                this.provider = provider; this.caps = caps;
            }

            @HostAccess.Export
            public List<Map<String, Object>> list() { return provider.soulImprintsList(); }

            @HostAccess.Export
            public Map<String, Object> create(String label, Map<String, Object> opts) {
                caps.require("soul.imprints.create");
                return provider.soulImprintsCreate(label, opts == null ? Map.of() : opts);
            }

            @HostAccess.Export
            public Map<String, Object> create(String label) { return create(label, null); }

            @HostAccess.Export
            public Map<String, Object> restore(String imprintId) {
                caps.require("soul.imprints.restore");
                return provider.soulImprintsRestore(imprintId);
            }

            @HostAccess.Export
            public Map<String, Object> delete(String imprintId) {
                caps.require("soul.imprints.delete");
                return provider.soulImprintsDelete(imprintId);
            }
        }
    }

    // ─── §4.21 Familiar API ───────────────────────────────────────

    public static class FamiliarApi {
        private final ItemWorldApiProvider provider;
        private final ItemCapabilitySet caps;

        FamiliarApi(ItemWorldApiProvider provider, ItemCapabilitySet caps) {
            this.provider = provider;
            this.caps = caps == null ? ItemCapabilitySet.UNRESTRICTED : caps;
        }

        /** §4.21 — summon a familiar. Tier 6. */
        @HostAccess.Export
        public Map<String, Object> summon(String formName, String task, Map<String, Object> opts) {
            caps.require("familiar.summon");
            return provider.familiarSummon(formName, task, opts == null ? Map.of() : opts);
        }

        @HostAccess.Export
        public Map<String, Object> summon(String formName, String task) {
            return summon(formName, task, null);
        }

        @HostAccess.Export
        public List<Map<String, Object>> list() { return provider.familiarList(); }

        @HostAccess.Export
        public Map<String, Object> status(String familiarId) {
            return provider.familiarStatus(familiarId);
        }

        /** §4.21 — give a thought-form copy. Tier 6. */
        @HostAccess.Export
        public Map<String, Object> give_copy(String familiarFormId, String target) {
            caps.require("familiar.give_copy");
            return provider.familiarGiveCopy(familiarFormId, target);
        }

        /** §4.21 — promote ephemeral to named. Tier 6. */
        @HostAccess.Export
        public Map<String, Object> name(String familiarId, String name) {
            caps.require("familiar.name");
            return provider.familiarName(familiarId, name);
        }
    }

    // ─── §4.21 Bunshin API ────────────────────────────────────────

    public static class BunshinApi {
        private final ItemWorldApiProvider provider;
        private final ItemCapabilitySet caps;

        BunshinApi(ItemWorldApiProvider provider, ItemCapabilitySet caps) {
            this.provider = provider;
            this.caps = caps == null ? ItemCapabilitySet.UNRESTRICTED : caps;
        }

        /** §4.21 — dispatch a self-fork. Tier 6. */
        @HostAccess.Export
        public Map<String, Object> dispatch(String task, Map<String, Object> opts) {
            caps.require("bunshin.dispatch");
            return provider.bunshinDispatch(task, opts == null ? Map.of() : opts);
        }

        @HostAccess.Export
        public Map<String, Object> dispatch(String task) { return dispatch(task, null); }

        @HostAccess.Export
        public Map<String, Object> status(String bunshinId) {
            return provider.bunshinStatus(bunshinId);
        }

        @HostAccess.Export
        public List<Map<String, Object>> list() { return provider.bunshinList(); }
    }

    // ─── §4.21 Form API ───────────────────────────────────────────

    public static class FormApi {
        private final ItemWorldApiProvider provider;
        private final ItemCapabilitySet caps;

        FormApi(ItemWorldApiProvider provider, ItemCapabilitySet caps) {
            this.provider = provider;
            this.caps = caps == null ? ItemCapabilitySet.UNRESTRICTED : caps;
        }

        /** §4.21 — author a thought-form (alias for workbench.shape_form). Tier 6. */
        @HostAccess.Export
        public Map<String, Object> shape(Map<String, Object> spec) {
            caps.require("form.shape");
            return provider.formShape(spec == null ? Map.of() : spec);
        }

        @HostAccess.Export
        public List<Map<String, Object>> list() { return provider.formList(); }
    }

    // ─── §4.22 Chapel API ─────────────────────────────────────────

    public static class ChapelApi {
        private final ItemWorldApiProvider provider;
        private final ItemCapabilitySet caps;

        ChapelApi(ItemWorldApiProvider provider, ItemCapabilitySet caps) {
            this.provider = provider;
            this.caps = caps == null ? ItemCapabilitySet.UNRESTRICTED : caps;
        }

        /** §4.22 — bond status. Tier 1 implicit. */
        @HostAccess.Export
        public Map<String, Object> bond_status(String target) {
            return provider.chapelBondStatus(target);
        }

        @HostAccess.Export
        public Map<String, Object> bond_status() { return bond_status(null); }

        /** §4.22 — suggest a ritual (alias of bond.suggest). Tier 6. */
        @HostAccess.Export
        public Map<String, Object> suggest_ritual(String target, String type) {
            caps.require("chapel.suggest_ritual");
            return provider.bondSuggest(target, type, null);
        }

        /** §4.22 — sever a bond. Tier 7. */
        @HostAccess.Export
        public Map<String, Object> exit_ritual(String target, String reason) {
            caps.require("chapel.exit_ritual");
            return provider.chapelExitRitual(target, reason);
        }

        /** §4.22 — generic ceremony entry. Tier 6. */
        @HostAccess.Export
        public Map<String, Object> ceremony(String target, String ceremonyType,
                                              List<String> witnesses) {
            caps.require("chapel.ceremony");
            return provider.chapelCeremony(target, ceremonyType,
                witnesses == null ? List.of() : witnesses);
        }

        @HostAccess.Export
        public Map<String, Object> ceremony(String target, String ceremonyType) {
            return ceremony(target, ceremonyType, null);
        }
    }

    // ─── Host API — steward-allowlisted OS actions ────────────────

    /**
     * {@code world.host} — act on the host OS within steward-configured
     * bounds. Nothing here takes a raw command: {@code launch} resolves an
     * alias against the {@code WYRDSEKAI_HOST_APPS} / {@code host.apps}
     * allowlist, {@code open_file} is confined to
     * {@code WYRDSEKAI_HOST_OPEN_ROOTS} / {@code host.open_roots}, and
     * {@code open_url} is http/https only. Every attempt is audit-logged
     * by the provider. Tier 6 (real-world side effects).
     */
    public static class HostApi {
        private final ItemWorldApiProvider provider;
        private final ItemCapabilitySet caps;

        HostApi(ItemWorldApiProvider provider, ItemCapabilitySet caps) {
            this.provider = provider;
            this.caps = caps == null ? ItemCapabilitySet.UNRESTRICTED : caps;
        }

        /** Launch an allowlisted desktop app by alias. Requires {@code host.app_launch}. */
        @HostAccess.Export
        public Map<String, Object> launch(String alias) {
            caps.require("host.app_launch");
            return provider.hostLaunchApp(alias);
        }

        /** Open a file under a configured open-root. Requires {@code host.file_open}. */
        @HostAccess.Export
        public Map<String, Object> open_file(String path) {
            caps.require("host.file_open");
            return provider.hostOpenFile(path);
        }

        /** Open an http/https URL with the platform opener. Requires {@code host.url_open}. */
        @HostAccess.Export
        public Map<String, Object> open_url(String url) {
            caps.require("host.url_open");
            return provider.hostOpenUrl(url);
        }

        /** Introspection: aliases the steward has allowlisted (no cap — read-only, no secrets). */
        @HostAccess.Export
        public List<String> apps() {
            return provider.hostApps();
        }

        /**
         * READ-ONLY file search under the steward's open-roots —
         * {@code world.host.find("*.epub")}. Requires {@code host.file_find}.
         */
        @HostAccess.Export
        public Map<String, Object> find(String pattern) {
            return find(pattern, 100);
        }

        @HostAccess.Export
        public Map<String, Object> find(String pattern, int maxResults) {
            caps.require("host.file_find");
            return provider.hostFind(pattern, maxResults);
        }
    }
}
