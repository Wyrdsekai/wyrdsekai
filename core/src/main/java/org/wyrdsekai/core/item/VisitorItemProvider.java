package org.wyrdsekai.core.item;

import org.wyrdsekai.core.agent.TranslationPrompts;
import org.wyrdsekai.core.room.TheSafe;
import org.wyrdsekai.scripting.api.ItemWorldApiProvider;

import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.Map;

/**
 * Minimal ItemWorldApiProvider for visitor-carried scripted items when no full
 * host provider is available. Returns safe defaults for all services but
 * reports the visitor's home zone and current host zone accurately.
 *
 * <p>Use this as a fallback when a visitor uses a carried scripted item in a
 * remote zone that doesn't have a full ItemWorldApiProvider wired for visitors.
 * Scripts get zone info via {@code world.zone.*}; other services return
 * empty/default values.</p>
 *
 * <p>For full cross-zone item functionality, wrap a real host-zone provider with
 * {@link TransitItemProvider} instead.</p>
 */
public class VisitorItemProvider implements ItemWorldApiProvider {

    private final String currentZoneId;
    private final String homeZoneId;

    /**
     * Template catalog for {@code world.catalog.*} — the host zone's
     * StandardItemLibrary. Rita campaign 2026-07-11 (#26): the player-side
     * provider (this class, via WyrdWebSocket.buildPlayerProvider →
     * ItemProviderRegistry → RoomActor furnishing invocations) never bound
     * the item library, so `use template catalog` on the Workshop surface
     * always answered "the item library isn't bound on this surface".
     * Nullable — unwired surfaces keep the safe empty-list defaults.
     */
    private volatile StandardItemLibrary itemLibrary;

    /**
     * The zone's Safe for {@code world.safe.list/has} — same 4-surfaces bug
     * class as the catalog above (#31 item 1, post-restart verify 2137ea49):
     * the companion-side provider got {@code setSafe(TheSafe.local())} and
     * the catalog got all four surfaces, but the PLAYER provider (this class
     * and HomeOwnerItemProvider via WyrdWebSocket.buildPlayerProvider) never
     * did, so player-invoked items saw an empty safe even with slots stored.
     * Nullable — unwired surfaces keep the empty/deny defaults.
     */
    private volatile TheSafe safe;

    public VisitorItemProvider(String currentZoneId, String homeZoneId) {
        this.currentZoneId = currentZoneId;
        this.homeZoneId = homeZoneId;
    }

    /** Bind the host zone's template catalog (read-only; safe for visitors). */
    public VisitorItemProvider withCatalog(StandardItemLibrary library) {
        this.itemLibrary = library;
        return this;
    }

    /** Wire the zone's Safe (mirrors {@link ItemWorldApiProviderImpl#setSafe}). */
    public void setSafe(TheSafe s) {
        this.safe = s;
    }

    @Override public String currentZone() { return currentZoneId; }
    @Override public String homeZone() { return homeZoneId; }

    // ─── Catalog / Standard Library (mirrors ItemWorldApiProviderImpl) ───

    @Override
    public List<Map<String, Object>> catalogSearch(String query) {
        var lib = itemLibrary;
        if (lib == null) return List.of();
        return lib.search(query).stream()
            .map(ItemWorldApiProviderImpl::templateToMap)
            .toList();
    }

    @Override
    public List<Map<String, Object>> catalogByCategory(String category) {
        var lib = itemLibrary;
        if (lib == null) return List.of();
        return lib.byCategory(category).stream()
            .map(ItemWorldApiProviderImpl::templateToMap)
            .toList();
    }

    @Override
    public Map<String, Object> catalogTemplateInfo(String templateName) {
        var lib = itemLibrary;
        if (lib == null) return null;
        var template = lib.get(templateName);
        if (template == null) return null;
        return ItemWorldApiProviderImpl.templateInfoToMap(template);
    }

    // All service calls return safe defaults. Scripts that need these should
    // check world.zone.isTraveling() and adapt.

    /**
     * The household's real library / model / web, when this provider is serving someone
     * who is actually AT HOME. Null means what it always meant: a foreign zone, where
     * these surfaces genuinely are not ours to offer.
     *
     * <p>Set per invocation via {@link #withHouseholdContent}; see
     * {@link HouseholdItemContent} for why a person holding an item was reading
     * foreign-zone stubs inside their own house.
     */
    private volatile ItemWorldApiProvider householdContent;

    /**
     * The household content bound to THIS caller.
     *
     * <p>Every surface below forwards through here rather than holding one
     * shared instance. That instance used to be built with the placeholder
     * identity {@code "household"}, and more than twenty of these forwards
     * read the identity internally — so a person's note ownership, filesystem
     * audit, study reach and inference spend were all evaluated as a
     * placeholder. Resolving per caller is what makes those answers belong to
     * whoever is actually holding the item.</p>
     */
    protected ItemWorldApiProvider content() {
        var explicit = householdContent;
        var perCaller = HouseholdItemContent.forCaller(actingDid());
        // An explicitly-set provider wins only when no factory is wired (tests,
        // bare boots); a live node always answers as the caller.
        return perCaller != null ? perCaller : explicit;
    }

    /** Serve content surfaces from the household. Returns {@code this} for chaining. */
    public VisitorItemProvider withHouseholdContent(ItemWorldApiProvider content) {
        this.householdContent = content;
        return this;
    }

    // ── The steward's granted directories ──────────────────────────────────
    // Forwarded like every other household surface. Without these, an item in a person's
    // HANDS got the interface default ("host_not_wired") while the same item run from the
    // floor worked — the same split that made `agent.speak` a no-op for carried items.

    @Override
    public List<String> hostRoots() {
        var home = content();
        return home != null ? home.hostRoots() : List.of();
    }

    @Override
    public Map<String, Object> hostFind(String pattern, int maxResults) {
        var home = content();
        return home != null ? home.hostFind(pattern, maxResults)
            : Map.of("ok", false, "error", "host_not_wired");
    }

    @Override
    public Map<String, Object> hostMove(String from, String to) {
        var home = content();
        return home != null ? home.hostMove(from, to)
            : Map.of("ok", false, "error", "host_not_wired");
    }

    @Override
    public Map<String, Object> hostMkdir(String path) {
        var home = content();
        return home != null ? home.hostMkdir(path)
            : Map.of("ok", false, "error", "host_not_wired");
    }

    /**
     * The acting person, when one is known. Set by whoever builds this provider
     * for a specific human; {@code null} for a genuinely anonymous surface.
     *
     * <p>This is the field whose absence lost the steward his own library: the
     * search was forwarded to the shared household provider, which asked "whose
     * shelves may I read?" of its own placeholder identity and answered
     * "nobody's". Identity-dependent decisions belong on the object that knows
     * the caller — this one.</p>
     */
    private volatile String callerDid;

    /** Name the person acting through this provider. Returns {@code this}. */
    public VisitorItemProvider withCaller(String did) {
        this.callerDid = did;
        return this;
    }

    /** The acting person's DID, or {@code null}. */
    protected String actingDid() {
        return callerDid;
    }

    /**
     * How far this caller may read into private shelves. A person reads their
     * own Study without needing a grant from themselves, and may additionally
     * hold grants on the zone owner's collections. With no known caller the
     * reach is honestly {@link StudyReach#NONE} — pack results only.
     */
    protected StudyReach studyReach() {
        var did = actingDid();
        if (did == null || did.isBlank()) return StudyReach.NONE;
        return PersonStudyReach.forPerson(did);
    }

    @Override
    public List<Map<String, Object>> searchKnowledge(String query, int limit) {
        // Forward like every other surface — but to content(), which is bound
        // to THIS caller, so the study leg inside it resolves the right person.
        // One implementation of the merge (KnowledgeSearch), reached the same
        // way by companion and person alike.
        var home = content();
        if (home != null) return home.searchKnowledge(query, limit);
        // No content provider, but the household index is here: run the search
        // ourselves with this caller's reach rather than answering "unavailable".
        if (HouseholdResources.lucene() != null) {
            return KnowledgeSearch.search(HouseholdResources.lucene(), query, limit,
                studyReach(), actingDid());
        }
        // A failure reported as DATA is worse than nothing: a script checking
        // `results.length === 0` sees one result and tells the person it found something.
        // Kept for genuine foreign zones, where the message is the honest answer.
        return List.of(Map.of("error", "Knowledge search unavailable — visiting foreign zone"));
    }

    @Override
    public Map<String, Object> readKnowledgeChunk(String chunkId) {
        var home = content();
        if (home != null) return home.readKnowledgeChunk(chunkId);
        return Map.of("error", "Read unavailable — visiting foreign zone");
    }

    @Override
    public List<Map<String, Object>> webSearch(String query, String type, int limit) {
        var home = content();
        return home != null ? home.webSearch(query, type, limit) : List.of();
    }

    @Override
    public String webFetch(String url, int maxChars) {
        var home = content();
        if (home != null) return home.webFetch(url, maxChars);
        return "[web fetch unavailable — visiting foreign zone]";
    }

    @Override
    public List<Map<String, Object>> queryOracle(String topic, String analysisType) {
        var home = content();
        return home != null ? home.queryOracle(topic, analysisType) : List.of();
    }

    // ── THE RUNTIME OWNS THE LANGUAGE DEFAULT ────────────────────────────
    // Live 2026-08-24: an English speaker's fairy-tale tool answered in
    // Spanish, because the library hits happened to be Spanish rows and the
    // item's llm prompt named no language. The preamble now TELLS authors to
    // name one — but an optional instruction is one a small model will not
    // fill (the house has learned this three separate times), so the default
    // cannot live in the item. It lives here: every prose surface appends a
    // yielding default in the language of the person this invocation serves.
    // An item whose prompt names a language still wins — the default is
    // conditional by its own wording. Null locale (no attach) = no injection.

    /** The person's language for THIS invocation; set via {@code withCallerLocale}. */
    private String callerLocale;

    public VisitorItemProvider withCallerLocale(String locale) {
        this.callerLocale = locale;
        return this;
    }

    /** The blunt line, when the item's own text names no language; null = inject nothing.
     *  Conditional in CODE, blunt in INSTRUCTION: the yielding phrasing ("If the
     *  request does not name a language…") lost to a page of Spanish source
     *  material twice on the home node (dev10, 2026-08-24 evening) — a 9B obeys
     *  a plain imperative and negotiates with an if. The code check keeps the
     *  yield: an item that names its language is left alone. */
    private String languageDefault(String... itemText) {
        if (callerLocale == null || callerLocale.isBlank()) return null;
        for (var t : itemText) {
            if (TranslationPrompts.namesALanguage(t)) return null;
        }
        return "Write your answer in "
            + TranslationPrompts.languageName(callerLocale) + ".";
    }

    @Override
    public String llmSummarize(String text, String instruction) {
        var home = content();
        if (home != null) {
            var dflt = languageDefault(instruction);
            var inst = dflt == null ? instruction
                : (instruction == null || instruction.isBlank()
                    ? dflt : instruction + " " + dflt);
            return home.llmSummarize(text, inst);
        }
        return "[LLM unavailable — visiting foreign zone]";
    }

    @Override
    public String llmAnalyze(String text, String prompt) {
        var home = content();
        if (home != null) {
            var dflt = languageDefault(prompt);
            return home.llmAnalyze(text, dflt == null ? prompt : prompt + "\n" + dflt);
        }
        return "[LLM unavailable — visiting foreign zone]";
    }

    /**
     * The external adapters — weather, geocoding, public data — the household has keys
     * for.
     *
     * <h2>Why this was invisible</h2>
     * {@code adapterNamespaces()} is implemented by exactly one class: the COMPANION's
     * provider. Everything else inherits the interface default, an empty set — and the
     * proxy resolver returns null for a namespace it cannot find, so
     * {@code world.openweather} was simply not a thing that existed for an item a person
     * was holding.
     *
     * <p>Live 2026-08-21: the steward asked for a weather tool. The household has an
     * OpenWeather key and a wired {@code OpenWeatherAdapter} with {@code current} and
     * {@code forecast}. goose could not see any of it — neither from the contract, which
     * never mentioned adapters, nor from the runtime — so it improvised with a web search
     * and a page fetch, which is what any reasonable author does when the real surface is
     * invisible. The item worked exactly as written and answered "no live weather data".
     *
     * <p>Same shape as the content surfaces above: it works for her and not for him, for
     * no reason anybody chose.
     */
    @Override
    public Set<String> adapterNamespaces() {
        var home = content();
        return home != null ? home.adapterNamespaces() : Set.of();
    }

    @Override
    public Map<String, Object> invokeAdapter(String namespace, String method,
                                             Map<String, Object> args) {
        var home = content();
        if (home != null) return home.invokeAdapter(namespace, method, args);
        return Map.of("success", false,
            "error", Map.of("code", "adapter_unavailable",
                            "message", "no adapter registered for " + namespace,
                            "retryable", false));
    }

    /**
     * Speak into the room the item is being used in.
     *
     * <h2>Why this stopped being a no-op</h2>
     * {@code world.agent.speak} is what the items-as-tools preamble teaches an item to
     * call when it has something to say out loud, and the companion's own provider wires
     * it to her voice. A PLAYER-held item got this class, where it did nothing — so an
     * item whose entire job was to say something said it into the void.
     *
     * <p>Live 2026-08-21: the steward asked for a tool that queries the library and
     * "speaks out loud to the room a story based on what it found". goose built exactly
     * that, calling {@code world.agent.speak(story)}. He used it and the room stayed
     * silent — the thing worked and nobody could tell.
     *
     * <p>A player is not the companion, so this narrates rather than impersonating her:
     * the words land in the room for everyone present, attributed to the item's use. The
     * sink is set per invocation by whichever surface is running the item (see
     * {@code CarriedItemUse#attachRoomVoice}); with no sink it stays the old no-op, which
     * is correct for a genuinely foreign zone.
     */
    @Override
    public void agentSpeak(String text) {
        var sink = this.roomVoice;
        if (sink != null && text != null && !text.isBlank()) {
            sink.accept(text);
        }
    }



    // ── The rest of the household's CONTENT surfaces ────────────────────────────
    //
    // Generated from ItemWorldApiProvider, not hand-picked. The first pass delegated
    // seven methods I chose by hand; auditing what ItemWorldApiProviderImpl actually
    // implements found TWENTY more a player-held item could not reach. Live 2026-08-21:
    // an item called world.llm.complete and got "[error] llm.complete not wired" — the
    // interface default — while the same item's world.library.search worked, because
    // search was on my list and complete was not.
    //
    // A hand-picked forwarding list is the same rotting mirror as a hand-written API
    // doc. If the household implements it and it is CONTENT rather than household ADMIN,
    // it forwards; EveryHouseholdSurfaceReachesAPlayersItemTest holds that line.

    @Override
    public List<Double> embedEncode(String text) {
        var home = content();
        return home != null ? home.embedEncode(text) : List.of();
    }

    @Override
    public List<Map<String, Object>> journalRecent(int limit) {
        var home = content();
        return home != null ? home.journalRecent(limit) : List.of();
    }

    @Override
    public List<Map<String, Object>> journalSearch(String query, int limit) {
        var home = content();
        return home != null ? home.journalSearch(query, limit) : List.of();
    }

    @Override
    public Map<String, Object> journalWrite(String content, Map<String, Object> opts) {
        var home = content();
        return home != null ? home.journalWrite(content, opts) : Map.of();
    }

    @Override
    public Map<String, Object> libraryAdd(String text, Map<String, Object> opts) {
        var home = content();
        return home != null ? home.libraryAdd(text, opts) : Map.of();
    }

    @Override
    public Map<String, Object> libraryDelete(String chunkId) {
        var home = content();
        return home != null ? home.libraryDelete(chunkId) : Map.of();
    }

    @Override
    public Map<String, Object> libraryIngest(String path, String collection, String mode) {
        var home = content();
        return home != null ? home.libraryIngest(path, collection, mode) : Map.of();
    }

    @Override
    public Map<String, Object> libraryTag(String chunkId, List<String> tags) {
        var home = content();
        return home != null ? home.libraryTag(chunkId, tags) : Map.of();
    }

    @Override
    public Map<String, Object> llmBudgetRemaining() {
        var home = content();
        return home != null ? home.llmBudgetRemaining() : Map.of();
    }

    @Override
    public Map<String, Object> llmClassify(String text, List<String> labels) {
        var home = content();
        return home != null ? home.llmClassify(text, labels) : Map.of();
    }

    @Override
    public Map<String, Object> llmComplete(String prompt, Map<String, Object> opts) {
        var home = content();
        if (home == null) return Map.of();
        var sys = opts != null && opts.get("system") instanceof String s ? s : null;
        var dflt = languageDefault(prompt, sys);
        if (dflt != null) {
            // The TAIL of the user prompt, not the system prompt: the strongest
            // position for a small model. The system-side append shipped in the
            // first cut and the material's language still won.
            return home.llmComplete(prompt + "\n\n" + dflt, opts);
        }
        return home.llmComplete(prompt, opts);
    }

    @Override
    public Map<String, Object> llmExtract(String text, Map<String, Object> schema) {
        var home = content();
        return home != null ? home.llmExtract(text, schema) : Map.of();
    }

    @Override
    public Map<String, Object> notesAdd(String content, List<String> tags) {
        var home = content();
        return home != null ? home.notesAdd(content, tags) : Map.of();
    }

    @Override
    public Map<String, Object> notesDelete(String id) {
        var home = content();
        return home != null ? home.notesDelete(id) : Map.of();
    }

    @Override
    public List<Map<String, Object>> notesList(String tag) {
        var home = content();
        return home != null ? home.notesList(tag) : List.of();
    }

    @Override
    public List<Map<String, Object>> tagsList(String scope) {
        var home = content();
        return home != null ? home.tagsList(scope) : List.of();
    }

    @Override
    public Map<String, Object> webDelete(String url, Map<String, Object> opts) {
        var home = content();
        return home != null ? home.webDelete(url, opts) : Map.of();
    }

    @Override
    public Map<String, Object> webFetchRaw(String url, Map<String, Object> opts) {
        var home = content();
        return home != null ? home.webFetchRaw(url, opts) : Map.of();
    }

    @Override
    public Map<String, Object> webPost(String url, Object body, Map<String, Object> opts) {
        var home = content();
        return home != null ? home.webPost(url, body, opts) : Map.of();
    }

    @Override
    public Map<String, Object> webPut(String url, Object body, Map<String, Object> opts) {
        var home = content();
        return home != null ? home.webPut(url, body, opts) : Map.of();
    }

    @Override
    public List<Map<String, Object>> pinboardList() {
        var home = content();
        return home != null ? home.pinboardList() : List.of();
    }

    @Override
    public Map<String, Object> pinboardPin(String text, Map<String, Object> opts) {
        var home = content();
        return home != null ? home.pinboardPin(text, opts) : Map.of();
    }

    @Override
    public Map<String, Object> pinboardUnpin(String id) {
        var home = content();
        return home != null ? home.pinboardUnpin(id) : Map.of();
    }

    /** Where {@link #agentSpeak} lands for this invocation. Null = nowhere (visitor). */
    private volatile Consumer<String> roomVoice;

    /** Wire this invocation's speech into a room. Returns {@code this} for chaining. */
    public VisitorItemProvider withRoomVoice(Consumer<String> sink) {
        this.roomVoice = sink;
        return this;
    }

    @Override
    public void agentRemember(String content) {
        // No memory in foreign zone — no-op
    }

    @Override
    public void agentTell(String target, String message) {
        // Tell delegated to CrossZoneTellService at a higher layer — no-op here
    }

    // ─── §4.18 The Safe (mirrors ItemWorldApiProviderImpl) ───────

    @Override
    public List<String> safeListSlots() {
        var s = safe;
        if (s == null) return List.of();
        return s.listSecretIds();
    }

    @Override
    public boolean safeHas(String slot) {
        var s = safe;
        if (s == null || slot == null) return false;
        return s.hasSecret(slot);
    }

    @Override
    public Map<String, Object> safeSet(String slot, String value) {
        if (safe == null) return Map.of("ok", false, "error", "safe_not_wired");
        // Writes stay on the MCP keychest path — same policy as the full provider.
        return Map.of("ok", false, "error", "use_mcp_keychest_for_writes");
    }

    @Override
    public Map<String, Object> safeDelete(String slot) {
        if (safe == null) return Map.of("ok", false, "error", "safe_not_wired");
        return Map.of("ok", false, "error", "use_mcp_keychest_for_deletes");
    }

    @Override
    public List<Map<String, Object>> inventoryList() {
        return List.of();
    }

    @Override
    public Map<String, Object> inventoryUse(String itemId, Map<String, Object> params, int depth) {
        return Map.of("error", "Inventory use unavailable — visiting foreign zone");
    }
}
