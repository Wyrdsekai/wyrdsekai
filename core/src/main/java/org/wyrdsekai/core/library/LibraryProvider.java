package org.wyrdsekai.core.library;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.search.WyrdLuceneStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Her library serving LIBRARY_PROTOCOL.md to an outside patron — the provider side.
 *
 * <p>Transport-agnostic: every tool is a method taking a plain map and returning a plain
 * map (or throwing {@link ProtocolError} with a stable code). A transport (the inbound MCP
 * door, a mesh subject) does the JSON.
 *
 * <p><b>The license gate lives here, on the sending side.</b> An outside patron sees only
 * pack chunks whose pack is licensed to travel, and only ACCEPTED findings every one of
 * whose sources is such a chunk or an external locator. Anything stamped {@code private}
 * — the steward's shelves, study shares, findings that cite them — never answers an outside
 * patron, whatever the patron says about itself. Grants can later widen this per subject;
 * nothing narrows it.
 */
public final class LibraryProvider {

    private static final Logger log = LoggerFactory.getLogger(LibraryProvider.class);
    private static final ObjectMapper M = new ObjectMapper();
    public static final String CONTRACT = "1.0";

    /** A refusal with a stable code the patron can act on and a sentence it can say. */
    public static final class ProtocolError extends RuntimeException {
        public final String code;
        public ProtocolError(String code, String message) { super(message); this.code = code; }
    }

    /** Whether a pack may be read by an outside patron — the sender's license rule. */
    @FunctionalInterface
    public interface LicenseGate {
        boolean mayTravel(String packName);
    }

    private final WyrdLuceneStore store;
    private final String libraryId;
    private final String libraryName;
    /** Whose findings this library serves — the companions who live here, read live. */
    private final java.util.function.Supplier<List<String>> owners;
    private final LicenseGate gate;

    /** Single owner (or none): the shape tests use. */
    public LibraryProvider(WyrdLuceneStore store, String libraryId, String libraryName,
                           String ownerDid, LicenseGate gate) {
        this(store, libraryId, libraryName,
            () -> ownerDid == null || ownerDid.isBlank() ? List.of() : List.of(ownerDid), gate);
    }

    /**
     * The findings served are those of {@code owners} — on a household node, every unarchived
     * companion on the roster, read at call time so a companion born tomorrow is served
     * tomorrow. The license gate still decides which of their findings may leave.
     */
    public LibraryProvider(WyrdLuceneStore store, String libraryId, String libraryName,
                           java.util.function.Supplier<List<String>> owners, LicenseGate gate) {
        this.store = store;
        this.libraryId = libraryId;
        this.libraryName = libraryName;
        this.owners = owners == null ? List::of : owners;
        this.gate = gate == null ? p -> false : gate;
    }

    private List<String> owners() {
        try {
            var o = owners.get();
            return o == null ? List.of() : o;
        } catch (RuntimeException e) {
            log.debug("[library] owner roster unavailable: {}", e.toString());
            return List.of();
        }
    }

    // ── the license gate over installed packs ───────────────────────────────

    /**
     * Reads {@code <packsDir>/<pack>/pack.json} once per pack: {@code rights}/{@code license}/
     * {@code copyright} of {@code private}, {@code household} or {@code none}, or a
     * {@code noFederate} flag, means the pack stays home. An unreadable pack stays home too.
     */
    public static LicenseGate packJsonGate(Path packsDir) {
        Map<String, Boolean> cache = new ConcurrentHashMap<>();
        return pack -> {
            if (pack == null || pack.isBlank() || packsDir == null) return false;
            return cache.computeIfAbsent(pack, p -> {
                try {
                    var f = packsDir.resolve(p).resolve("pack.json");
                    if (!Files.isRegularFile(f)) return false;
                    var n = M.readTree(Files.readString(f));
                    if (n.path("noFederate").asBoolean(false)) return false;
                    for (var k : List.of("rights", "license", "copyright")) {
                        var v = n.path(k).asText("").trim().toLowerCase(Locale.ROOT);
                        if (v.equals("private") || v.equals("household") || v.equals("none")) return false;
                    }
                    var src = n.path("source").asText("");
                    return !"study-share".equals(src);
                } catch (Exception e) {
                    return false;
                }
            });
        };
    }

    // ── tools ───────────────────────────────────────────────────────────────

    public Map<String, Object> status() {
        var out = envelope();
        var counts = new LinkedHashMap<String, Object>();
        try {
            var packs = store.listKnowledgePacks();
            long travelling = 0;
            int openPacks = 0;
            for (var e : packs.entrySet()) {
                if (gate.mayTravel(e.getKey())) { openPacks++; travelling += e.getValue(); }
            }
            counts.put("packs", openPacks);
            counts.put("chunks", travelling);
        } catch (RuntimeException e) {
            counts.put("packs", 0);
            counts.put("chunks", 0);
        }
        int accepted = 0;
        for (var owner : owners()) {
            for (var f : FindingsLedger.list(store, owner, FindingsLedger.State.ACCEPTED, 500)) {
                if (travels(f)) accepted++;
            }
        }
        counts.put("findings_accepted", accepted);
        out.put("counts", counts);
        out.put("last_updated", Instant.now().toString());
        return out;
    }

    public Map<String, Object> ask(Map<String, Object> args) {
        var question = str(args, "question");
        if (question.isBlank()) throw new ProtocolError("invalid_args", "Ask something: 'question' is empty.");
        int k = intOf(args, "k", 6);
        var out = envelope();
        var entries = new ArrayList<Map<String, Object>>();
        for (var owner : owners()) {
            for (var f : FindingsLedger.established(store, owner, question, k)) {
                if (f.state() == FindingsLedger.State.ACCEPTED && travels(f)) entries.add(finding(f));
                if (entries.size() >= k) break;
            }
            if (entries.size() >= k) break;
        }
        if (entries.size() < k) {
            for (var r : store.searchKnowledgeText(question, k * 2)) {
                if (!gate.mayTravel(packOf(r))) continue;
                entries.add(rawEntry(r));
                if (entries.size() >= k) break;
            }
        }
        out.put("entries", entries);
        if (entries.isEmpty()) out.put("holds_nothing", true);
        return out;
    }

    public Map<String, Object> search(Map<String, Object> args) {
        var query = str(args, "query");
        if (query.isBlank()) throw new ProtocolError("invalid_args", "Search for something: 'query' is empty.");
        int k = intOf(args, "k", 10);
        var out = envelope();
        var hits = new ArrayList<Map<String, Object>>();
        for (var owner : owners()) {
            for (var f : FindingsLedger.search(store, owner, query, k)) {
                if (f.state() != FindingsLedger.State.ACCEPTED || !travels(f)) continue;
                var h = new LinkedHashMap<String, Object>();
                h.put("id", f.id()); h.put("kind", "finding"); h.put("title", f.title());
                h.put("snippet", snippet(f.claim())); h.put("score", 1.0);
                h.put("state", f.state().key()); h.put("subjects", List.of());
                hits.add(h);
            }
        }
        for (var r : store.searchKnowledgeText(query, k * 2)) {
            if (!gate.mayTravel(packOf(r))) continue;
            var meta = r.metadata();
            var h = new LinkedHashMap<String, Object>();
            h.put("id", r.id()); h.put("kind", "raw");
            h.put("title", meta == null ? r.id() : String.valueOf(meta.getOrDefault("title", r.id())));
            h.put("snippet", snippet(r.content())); h.put("score", (double) r.score());
            h.put("state", "accepted"); h.put("subjects", List.of());
            hits.add(h);
            if (hits.size() >= k) break;
        }
        out.put("hits", hits.size() > k ? hits.subList(0, k) : hits);
        out.put("next_cursor", null);
        return out;
    }

    public Map<String, Object> get(Map<String, Object> args) {
        var id = str(args, "id");
        if (id.isBlank()) throw new ProtocolError("invalid_args", "'id' is required.");
        var out = envelope();
        if (id.startsWith("finding:")) {
            var f = FindingsLedger.get(store, id).orElse(null);
            if (f == null || f.state() != FindingsLedger.State.ACCEPTED || !travels(f)
                    || !owners().contains(f.ownerDid())) {
                throw new ProtocolError("not_found", "No entry " + id + " is available to you.");
            }
            out.put("entry", finding(f));
            return out;
        }
        var r = store.getById("knowledge", id);
        if (r == null || !gate.mayTravel(packOf(r))) {
            throw new ProtocolError("not_found", "No entry " + id + " is available to you.");
        }
        out.put("entry", rawEntry(r));
        return out;
    }

    /** The verbatim path: the chunk text behind a locator, if its pack may travel. */
    public Map<String, Object> read(Map<String, Object> args) {
        var locator = str(args, "locator");
        if (locator.isBlank()) throw new ProtocolError("invalid_args", "'locator' is required.");
        int max = intOf(args, "max_chars", 4000);
        var r = store.getById("knowledge", locator);
        if (r == null || !gate.mayTravel(packOf(r))) {
            throw new ProtocolError("forbidden", "That source is not held for outside readers.");
        }
        var out = envelope();
        var meta = r.metadata();
        out.put("locator", locator);
        out.put("title", meta == null ? r.id() : String.valueOf(meta.getOrDefault("title", r.id())));
        out.put("edition", packOf(r));
        out.put("captured_at", meta == null ? null : meta.get("timestamp"));
        var text = r.content() == null ? "" : r.content();
        out.put("text", text.length() > max ? text.substring(0, max) : text);
        return out;
    }

    public Map<String, Object> established(Map<String, Object> args) {
        var claim = str(args, "claim");
        if (claim.isBlank()) throw new ProtocolError("invalid_args", "'claim' is required.");
        var out = envelope();
        var entries = new ArrayList<Map<String, Object>>();
        var disputes = new ArrayList<Map<String, Object>>();
        for (var owner : owners()) {
            for (var f : FindingsLedger.search(store, owner, claim, 10)) {
                if (!travels(f)) continue;
                if (f.state() == FindingsLedger.State.ACCEPTED
                        && FindingsLedger.overlap(f.claim(), claim) >= 0.4) entries.add(finding(f));
                else if (f.state() == FindingsLedger.State.DISPUTED) disputes.add(finding(f));
            }
        }
        out.put("verdict", !entries.isEmpty() ? "established" : !disputes.isEmpty() ? "disputed" : "not_established");
        out.put("entries", entries);
        out.put("disputes", disputes);
        return out;
    }

    /** Submissions from outside enter as drafts attributed to the patron; never canon. */
    public Map<String, Object> submit(Map<String, Object> args) {
        var holders = owners();
        if (holders.isEmpty()) throw new ProtocolError("unavailable", "This library does not take submissions.");
        var ownerDid = holders.getFirst();   // the first companion on the roster keeps outside drafts
        var claim = str(args, "claim");
        if (claim.isBlank()) throw new ProtocolError("invalid_args", "'claim' is required.");
        var sources = new ArrayList<String>();
        if (args.get("sources") instanceof List<?> l) {
            for (var s : l) {
                if (s instanceof Map<?, ?> m) {
                    var loc = String.valueOf(m.get("locator"));
                    var ed = m.get("edition") == null ? null : String.valueOf(m.get("edition"));
                    sources.add(FindingsLedger.Source.external(loc, ed, loc).serialized());
                } else if (s != null) {
                    sources.add(String.valueOf(s));
                }
            }
        }
        if (sources.isEmpty()) throw new ProtocolError("no_sources", "A claim needs at least one source; this library does not take opinions.");
        var patron = patronLabel(args);
        var type = FindingsLedger.ClaimType.valueOf(str(args, "claim_type").isBlank()
            ? "SYNTHESIS" : str(args, "claim_type").toUpperCase(Locale.ROOT));
        var id = FindingsLedger.recordDraft(store, ownerDid, "", claim, sources, patron, type);
        if (id == null) throw new ProtocolError("unavailable", "The library could not record that just now.");
        var out = envelope();
        out.put("id", id);
        out.put("state", "draft");
        return out;
    }

    public Map<String, Object> subjects() {
        var out = envelope();
        out.put("subjects", List.of());   // a facet layer of her own is queued; nothing is faked here
        return out;
    }

    // ── shapes ──────────────────────────────────────────────────────────────

    private Map<String, Object> envelope() {
        var m = new LinkedHashMap<String, Object>();
        m.put("library_id", libraryId);
        m.put("library_name", libraryName);
        m.put("contract", CONTRACT);
        return m;
    }

    private Map<String, Object> finding(FindingsLedger.Finding f) {
        var e = new LinkedHashMap<String, Object>();
        e.put("id", f.id()); e.put("kind", "finding"); e.put("state", f.state().key());
        e.put("claim_type", f.claimType().key()); e.put("confidence", f.confidence());
        e.put("writer", f.writer()); e.put("recorded_at", f.recordedAt() == null ? null : f.recordedAt().toString());
        e.put("title", f.title()); e.put("body", f.claim());
        var srcs = new ArrayList<Map<String, Object>>();
        for (var s : f.sources()) {
            var m = new LinkedHashMap<String, Object>();
            m.put("locator", s.locator() != null ? s.locator() : s.chunkId() != null ? s.chunkId() : s.line());
            m.put("edition", s.edition());
            srcs.add(m);
        }
        e.put("sources", srcs);
        return e;
    }

    private Map<String, Object> rawEntry(WyrdLuceneStore.SearchResult r) {
        var meta = r.metadata();
        var e = new LinkedHashMap<String, Object>();
        e.put("id", r.id()); e.put("kind", "raw"); e.put("state", "accepted");
        e.put("claim_type", "extraction"); e.put("confidence", null);
        e.put("writer", "pack:" + packOf(r)); e.put("recorded_at", null);
        e.put("title", meta == null ? r.id() : String.valueOf(meta.getOrDefault("title", r.id())));
        e.put("body", snippet(r.content()));
        e.put("sources", List.of(Map.of("locator", r.id(), "edition", packOf(r))));
        return e;
    }

    /** A finding travels only if every source does. */
    boolean travels(FindingsLedger.Finding f) {
        if (f.federated()) return false;    // not ours to re-serve
        for (var s : f.sources()) {
            if (s.external()) continue;
            if (s.chunkId() != null) {
                var r = store.getById("knowledge", s.chunkId());
                if (r == null || !gate.mayTravel(packOf(r))) return false;
                continue;
            }
            return false;   // a title-only receipt cannot be checked, so it stays home
        }
        return !f.sources().isEmpty();
    }

    /**
     * The pack a knowledge chunk belongs to. {@code SearchResult.source()} is a display
     * name (name → room → id), so for pack chunks it is the chunk id; the pack itself is in
     * the stored metadata, and failing that is the id's prefix ({@code pack:index}).
     */
    static String packOf(WyrdLuceneStore.SearchResult r) {
        if (r == null) return null;
        var meta = r.metadata();
        if (meta != null) {
            for (var k : List.of("pack", "pack_name", "packName")) {
                var v = meta.get(k);
                if (v != null && !String.valueOf(v).isBlank()) return String.valueOf(v);
            }
        }
        var id = r.id() == null ? "" : r.id();
        int c = id.indexOf(':');
        return c > 0 ? id.substring(0, c) : id;
    }

    private static String patronLabel(Map<String, Object> args) {
        if (args.get("patron") instanceof Map<?, ?> p && p.get("did") != null) return "patron:" + p.get("did");
        return "patron:anonymous";
    }

    private static String snippet(String s) {
        if (s == null) return "";
        var t = s.replaceAll("\\s+", " ").strip();
        return t.length() > 300 ? t.substring(0, 300) + "…" : t;
    }

    private static String str(Map<String, Object> args, String k) {
        var v = args == null ? null : args.get(k);
        return v == null ? "" : String.valueOf(v).strip();
    }

    private static int intOf(Map<String, Object> args, String k, int dflt) {
        var v = args == null ? null : args.get(k);
        if (v instanceof Number n) return Math.max(1, n.intValue());
        try { return v == null ? dflt : Math.max(1, Integer.parseInt(String.valueOf(v))); }
        catch (NumberFormatException e) { return dflt; }
    }

    /** Tool names this provider serves, for a transport to register. */
    public static Set<String> TOOLS = Set.of("library_status", "library_ask", "library_search",
        "library_get", "library_read", "library_established", "library_submit", "library_subjects");

    /** Dispatch by tool name — the one seam a transport needs. */
    public Map<String, Object> call(String tool, Map<String, Object> args) {
        return switch (tool) {
            case "library_status" -> status();
            case "library_ask" -> ask(args);
            case "library_search" -> search(args);
            case "library_get" -> get(args);
            case "library_read" -> read(args);
            case "library_established" -> established(args);
            case "library_submit" -> submit(args);
            case "library_subjects" -> subjects();
            default -> throw new ProtocolError("not_found", "No such tool: " + tool);
        };
    }
}
