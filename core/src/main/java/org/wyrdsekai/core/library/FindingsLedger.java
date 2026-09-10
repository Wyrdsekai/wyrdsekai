package org.wyrdsekai.core.library;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.identity.StudyOwnerGuard;
import org.wyrdsekai.core.search.SearchCollections;
import org.wyrdsekai.core.search.WyrdLuceneStore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The findings tier — what she concluded from reading, kept as a cited, reviewable claim.
 *
 * <p>Until now a library read left three kinds of trace: a {@code [Tool result]} line in
 * working memory, a journal entry, or a pin. None of them is a CLAIM with sources and a
 * state, so nothing she established could be found again as "something I established".
 * Codezaiku's Librarian has this tier as its atomic unit (one claim per file, typed
 * frontmatter, {@code draft → accepted → superseded | disputed | retired}); this is the same
 * shape kept where her things live — a Study item of type {@value #ITEM_TYPE} under her own
 * DID — so grants, sync and search already apply to it.
 *
 * <p>Content is a small typed header, a {@code ---} line, then the claim in prose:
 * <pre>
 * FINDING
 * claim_type: synthesis
 * confidence: medium
 * state: draft
 * writer: companion:mia
 * query: what did the Librarian tell Kestan about vel-sharas
 * recorded_at: 2026-09-03T12:00:00Z
 * source: S1: Glass Tide (study-share-books) (doc:did:key:…:books:9f2)
 * content_hash: sha256:…
 * ---
 * The vel-shara of Adrun is described as a counter-program …
 * </pre>
 * The header fails closed on read: a finding without a claim or a state parses to empty.
 * Review staleness is by content hash (edit the claim and the acceptance no longer matches).
 */
public final class FindingsLedger {

    private static final Logger log = LoggerFactory.getLogger(FindingsLedger.class);

    public static final String ITEM_TYPE = "finding";
    public static final String COLLECTION = "findings";
    private static final String MAGIC = "FINDING";
    private static final java.util.concurrent.atomic.AtomicInteger SEQ = new java.util.concurrent.atomic.AtomicInteger();
    /** A trailing "(id)" receipt: anything with a colon in it — doc:…, pack:index, books:sc-9. */
    private static final Pattern TRAILING_ID = Pattern.compile("\\(([A-Za-z0-9_.-]+:[^)\\s]+)\\)\\s*$");

    private FindingsLedger() {}

    public enum State { DRAFT, ACCEPTED, SUPERSEDED, DISPUTED, RETIRED;
        static State of(String s) {
            if (s == null) return DRAFT;
            try { return State.valueOf(s.trim().toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException e) { return DRAFT; }
        }
        public String key() { return name().toLowerCase(Locale.ROOT); }
    }

    public enum ClaimType { EXTRACTION, SYNTHESIS, INTERPRETATION, SPECULATION;
        static ClaimType of(String s) {
            if (s == null) return SYNTHESIS;
            try { return ClaimType.valueOf(s.trim().toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException e) { return SYNTHESIS; }
        }
        public String key() { return name().toLowerCase(Locale.ROOT); }
    }

    /**
     * A cited source. {@code line} is the receipt as the tool gave it; {@code chunkId} the
     * local chunk if one was in it; {@code locator}/{@code edition} the protocol form
     * (LIBRARY_PROTOCOL.md) for a source held by another library — {@code locator} is
     * {@code <library_id>:<entry id>} there, and {@code edition} says which text.
     */
    public record Source(String line, String chunkId, String locator, String edition) {
        public Source(String line, String chunkId) { this(line, chunkId, null, null); }
        public static Source parse(String line) {
            if (line == null) return new Source("", null);
            var t = line.trim();
            // Protocol form: "locator=<...>; edition=<...>; line=<...>"
            if (t.startsWith("locator=")) {
                String loc = null, ed = null, ln = t;
                for (var part : t.split(";\\s*")) {
                    int eq = part.indexOf('=');
                    if (eq < 0) continue;
                    var k = part.substring(0, eq).trim();
                    var v = part.substring(eq + 1).trim();
                    switch (k) {
                        case "locator" -> loc = v;
                        case "edition" -> ed = v;
                        case "line" -> ln = v;
                        default -> { }
                    }
                }
                return new Source(ln, null, loc, ed);
            }
            var m = TRAILING_ID.matcher(t);
            return new Source(t, m.find() ? m.group(1) : null, null, null);
        }
        public static Source external(String locator, String edition, String title) {
            return new Source(title == null ? locator : title, null, locator, edition);
        }
        /** The serialized form: protocol sources keep their fields, local ones stay as given. */
        public String serialized() {
            if (locator == null) return line;
            return "locator=" + locator.replace(';', ',')
                + (edition == null ? "" : "; edition=" + edition.replace(';', ','))
                + "; line=" + line.replace(';', ',');
        }
        public boolean external() { return locator != null; }
        /** Title-ish text: the line minus a leading "S1:" key and a trailing id. */
        public String title() {
            var t = line.replaceFirst("^S\\d+:\\s*", "");
            t = TRAILING_ID.matcher(t).replaceFirst("").trim();
            // "(pack)" / "(part 3/9)" receipts are not part of the title either.
            for (int i = 0; i < 2; i++) t = t.replaceAll("\\s*\\([^()]*\\)\\s*$", "").trim();
            return t;
        }
    }

    public record Finding(String id, String ownerDid, String claim, ClaimType claimType,
                          String confidence, State state, String writer, String query,
                          Instant recordedAt, List<Source> sources, String reviewNote,
                          String supersededBy, int version, String contentHash,
                          String originLibrary, String originId) {
        /** Local finding, no origin elsewhere. */
        public Finding(String id, String ownerDid, String claim, ClaimType claimType,
                       String confidence, State state, String writer, String query,
                       Instant recordedAt, List<Source> sources, String reviewNote,
                       String supersededBy, int version, String contentHash) {
            this(id, ownerDid, claim, claimType, confidence, state, writer, query, recordedAt,
                sources, reviewNote, supersededBy, version, contentHash, null, null);
        }
        public boolean reviewStale() { return !hash(claim).equals(contentHash); }
        /** A finding that came from another library (LIBRARY_PROTOCOL.md): read-only here in spirit. */
        public boolean federated() { return originLibrary != null && !originLibrary.isBlank(); }
        public Finding withState(State s, String note, String by) {
            return new Finding(id, ownerDid, claim, claimType, confidence, s, writer, query,
                recordedAt, sources, note, by, version + 1, contentHash, originLibrary, originId);
        }
        /** Short title for listings. */
        public String title() {
            var c = claim.replaceAll("\\s+", " ").trim();
            return c.length() > 80 ? c.substring(0, 80) + "…" : c;
        }
    }

    // ── serialization (pure) ────────────────────────────────────────────────

    static String serialize(Finding f) {
        var sb = new StringBuilder(MAGIC).append('\n');
        sb.append("claim_type: ").append(f.claimType().key()).append('\n');
        sb.append("confidence: ").append(f.confidence() == null ? "medium" : f.confidence()).append('\n');
        sb.append("state: ").append(f.state().key()).append('\n');
        sb.append("writer: ").append(f.writer() == null ? "" : f.writer()).append('\n');
        sb.append("query: ").append(f.query() == null ? "" : f.query().replace('\n', ' ')).append('\n');
        sb.append("recorded_at: ").append(f.recordedAt() == null ? "" : f.recordedAt()).append('\n');
        for (var s : f.sources()) sb.append("source: ").append(s.serialized().replace('\n', ' ')).append('\n');
        if (f.originLibrary() != null && !f.originLibrary().isBlank()) {
            sb.append("origin: ").append(f.originLibrary()).append('\n');
            if (f.originId() != null) sb.append("origin_id: ").append(f.originId()).append('\n');
        }
        if (f.reviewNote() != null && !f.reviewNote().isBlank())
            sb.append("review_note: ").append(f.reviewNote().replace('\n', ' ')).append('\n');
        if (f.supersededBy() != null && !f.supersededBy().isBlank())
            sb.append("superseded_by: ").append(f.supersededBy()).append('\n');
        sb.append("content_hash: ").append(f.contentHash()).append('\n');
        sb.append("version: ").append(f.version()).append('\n');
        sb.append("---\n").append(f.claim().strip()).append('\n');
        return sb.toString();
    }

    /** Fails closed: anything that is not a well-formed finding parses to empty. */
    static Optional<Finding> parse(String id, String ownerDid, String content, int version) {
        if (content == null) return Optional.empty();
        var lines = content.split("\n");
        if (lines.length == 0 || !MAGIC.equals(lines[0].trim())) return Optional.empty();
        ClaimType type = ClaimType.SYNTHESIS;
        String confidence = "medium", writer = "", query = "", note = null, by = null, hash = null;
        String origin = null, originId = null;
        State state = null;
        Instant at = null;
        int ver = version;
        var sources = new ArrayList<Source>();
        int i = 1;
        for (; i < lines.length; i++) {
            var l = lines[i];
            if ("---".equals(l.trim())) { i++; break; }
            int c = l.indexOf(':');
            if (c < 0) continue;
            var k = l.substring(0, c).trim();
            var v = l.substring(c + 1).trim();
            switch (k) {
                case "claim_type" -> type = ClaimType.of(v);
                case "confidence" -> confidence = v;
                case "state" -> state = State.of(v);
                case "writer" -> writer = v;
                case "query" -> query = v;
                case "recorded_at" -> { try { at = Instant.parse(v); } catch (Exception e) { at = null; } }
                case "source" -> sources.add(Source.parse(v));
                case "review_note" -> note = v;
                case "superseded_by" -> by = v;
                case "content_hash" -> hash = v;
                case "version" -> { try { ver = Integer.parseInt(v); } catch (NumberFormatException e) { /* keep */ } }
                case "origin" -> origin = v;
                case "origin_id" -> originId = v;
                default -> { }
            }
        }
        var claim = new StringBuilder();
        for (; i < lines.length; i++) claim.append(lines[i]).append('\n');
        var claimText = claim.toString().strip();
        if (state == null || claimText.isEmpty()) return Optional.empty();
        return Optional.of(new Finding(id, ownerDid, claimText, type, confidence, state, writer,
            query, at, List.copyOf(sources), note, by, ver, hash == null ? hash(claimText) : hash,
            origin, originId));
    }

    public static String hash(String claim) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            var d = md.digest((claim == null ? "" : claim.strip()).getBytes(StandardCharsets.UTF_8));
            var sb = new StringBuilder("sha256:");
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            return "sha256:unavailable";
        }
    }

    // ── store ───────────────────────────────────────────────────────────────

    /**
     * Record a DRAFT finding under {@code ownerDid}. {@code sourceLines} are the tool's own
     * receipts ("S1: title (pack) (id)"); an empty list is refused — a finding without a
     * source is an opinion, and the ledger does not take those.
     *
     * @return the finding id, or null when refused
     */
    public static String recordDraft(WyrdLuceneStore store, String ownerDid, String query,
                                     String claim, List<String> sourceLines, String writer,
                                     ClaimType type) {
        if (store == null || claim == null || claim.isBlank()) return null;
        if (sourceLines == null || sourceLines.isEmpty()) {
            log.info("[findings] refused a claim with no sources ({} chars)", claim.length());
            return null;
        }
        var owner = StudyOwnerGuard.require(ownerDid);
        var now = Instant.now();
        var id = "finding:" + owner + ":" + now.toEpochMilli() + "-" + (SEQ.incrementAndGet() % 10000);
        var sources = sourceLines.stream().map(Source::parse).filter(s -> !s.line().isBlank()).toList();
        var f = new Finding(id, owner, claim.strip(), type == null ? ClaimType.SYNTHESIS : type,
            "medium", State.DRAFT, writer, query, now, sources, null, null, 1, hash(claim));
        store.insertStudyItem(id, owner, ITEM_TYPE, f.title(), serialize(f), COLLECTION,
            now.toEpochMilli(), 1, null);
        store.commitAll();
        log.info("[findings] draft recorded for {}: '{}' ({} source(s))", owner, f.title(), sources.size());
        return id;
    }

    /**
     * Record what she concluded from ANOTHER library's answer (LIBRARY_PROTOCOL.md): the
     * sources are that library's entries, cited as {@code <library_id>:<entry id>} with
     * edition, and the finding carries its origin. The sleep review will keep it a draft
     * as "held elsewhere" — verified there, not here.
     */
    public static String recordFromLibrary(WyrdLuceneStore store, String ownerDid, String query,
                                           String claim, String libraryId, List<Source> sources,
                                           String writer, ClaimType type) {
        if (store == null || claim == null || claim.isBlank() || libraryId == null) return null;
        if (sources == null || sources.isEmpty()) {
            log.info("[findings] refused a claim from library {} with no sources", libraryId);
            return null;
        }
        var owner = StudyOwnerGuard.require(ownerDid);
        var now = Instant.now();
        var id = "finding:" + owner + ":" + now.toEpochMilli() + "-" + (SEQ.incrementAndGet() % 10000);
        var f = new Finding(id, owner, claim.strip(), type == null ? ClaimType.SYNTHESIS : type,
            "medium", State.DRAFT, writer, query, now, List.copyOf(sources), null, null, 1, hash(claim),
            libraryId, entryId(libraryId, sources.getFirst().locator()));
        store.insertStudyItem(id, owner, ITEM_TYPE, f.title(), serialize(f), COLLECTION,
            now.toEpochMilli(), 1, null);
        store.commitAll();
        log.info("[findings] draft recorded for {} from library {}: '{}'", owner, libraryId, f.title());
        return id;
    }

    /** "lib-9f2c:F-0007-x" → "F-0007-x": the entry id as the library names it in notices. */
    static String entryId(String libraryId, String locator) {
        if (locator == null) return null;
        if (libraryId != null && locator.startsWith(libraryId + ":")) return locator.substring(libraryId.length() + 1);
        return locator;
    }

    public static Optional<Finding> get(WyrdLuceneStore store, String id) {
        if (store == null || id == null) return Optional.empty();
        var r = store.getById(SearchCollections.STUDY, id);
        return r == null ? Optional.empty() : fromResult(r);
    }

    private static Optional<Finding> fromResult(WyrdLuceneStore.SearchResult r) {
        var meta = r.metadata();
        var owner = meta == null ? "" : String.valueOf(meta.getOrDefault("user_did", ""));
        int version = 1;
        if (meta != null && meta.get("version") instanceof Number n) version = n.intValue();
        if (meta != null && "1".equals(String.valueOf(meta.get("deleted")))) return Optional.empty();
        return parse(r.id(), owner, r.content(), version);
    }

    /** Findings that match {@code query}, any state, best first. */
    public static List<Finding> search(WyrdLuceneStore store, String ownerDid, String query, int limit) {
        if (store == null || ownerDid == null || query == null || query.isBlank()) return List.of();
        try {
            var out = new ArrayList<Finding>();
            for (var r : store.searchStudyByType(ownerDid, ITEM_TYPE, query, Math.max(limit * 2, 6))) {
                fromResult(r).ifPresent(out::add);
                if (out.size() >= limit) break;
            }
            return out;
        } catch (RuntimeException e) {
            log.debug("[findings] search failed for '{}': {}", query, e.toString());
            return List.of();
        }
    }

    /** What she has established that bears on {@code query}: accepted first, then drafts. */
    public static List<Finding> established(WyrdLuceneStore store, String ownerDid, String query, int limit) {
        var all = search(store, ownerDid, query, limit * 3);
        var out = new ArrayList<Finding>();
        for (var f : all) if (f.state() == State.ACCEPTED) out.add(f);
        for (var f : all) if (f.state() == State.DRAFT || f.state() == State.DISPUTED) out.add(f);
        return out.size() > limit ? List.copyOf(out.subList(0, limit)) : List.copyOf(out);
    }

    /** All findings for an owner in {@code state} (null = any), newest first. */
    public static List<Finding> list(WyrdLuceneStore store, String ownerDid, State state, int limit) {
        if (store == null || ownerDid == null) return List.of();
        try {
            var out = new ArrayList<Finding>();
            // Enumeration is a match-all with a type filter, never a TEXT query: a
            // TEXT_ONLY "*" matches the literal token and returns nothing (the same
            // miss made every item's notes.list empty in production, 2026-09-03).
            for (var r : store.listStudyByTypeRecent(ownerDid, ITEM_TYPE, Math.max(limit * 4, 50))) {
                fromResult(r).ifPresent(f -> { if (state == null || f.state() == state) out.add(f); });
            }
            out.sort((a, b) -> {
                var x = a.recordedAt() == null ? Instant.EPOCH : a.recordedAt();
                var y = b.recordedAt() == null ? Instant.EPOCH : b.recordedAt();
                return y.compareTo(x);
            });
            return out.size() > limit ? List.copyOf(out.subList(0, limit)) : List.copyOf(out);
        } catch (RuntimeException e) {
            log.debug("[findings] list failed: {}", e.toString());
            return List.of();
        }
    }

    /** Move a finding to {@code state}; the old content is replaced, the id and history kept. */
    public static boolean setState(WyrdLuceneStore store, String id, State state, String note,
                                   String supersededBy) {
        var cur = get(store, id);
        if (cur.isEmpty()) return false;
        var f = cur.get().withState(state, note, supersededBy);
        store.insertStudyItem(f.id(), f.ownerDid(), ITEM_TYPE, f.title(), serialize(f), COLLECTION,
            f.recordedAt() == null ? Instant.now().toEpochMilli() : f.recordedAt().toEpochMilli(),
            f.version(), null);
        store.commitAll();
        log.info("[findings] {} → {}{}", id, state.key(), note == null ? "" : " (" + note + ")");
        return true;
    }

    // ── the review pass (sleep-time, mechanical) ─────────────────────────────

    public record Review(int reviewed, int accepted, int keptDraft, int retiredDuplicates, int disputed) {
        public Review(int reviewed, int accepted, int keptDraft, int retiredDuplicates) {
            this(reviewed, accepted, keptDraft, retiredDuplicates, 0);
        }
    }

    /**
     * Review every DRAFT: a source that cannot be found keeps it a draft (with the reason);
     * a claim that restates an accepted one retires it as a duplicate pointing at the
     * original; otherwise it is accepted. Mechanical evidence decides; a model judgment
     * pass (held out by context) is the follow-up, not this.
     */
    public static Review reviewDrafts(WyrdLuceneStore store, String ownerDid) {
        if (store == null || ownerDid == null) return new Review(0, 0, 0, 0);
        var drafts = list(store, ownerDid, State.DRAFT, 200);
        if (drafts.isEmpty()) return new Review(0, 0, 0, 0);
        var accepted = list(store, ownerDid, State.ACCEPTED, 500);
        int ok = 0, kept = 0, dup = 0, disputed = 0;
        for (var d : drafts) {
            var missing = unresolvedSources(store, d);
            if (!missing.isEmpty()) {
                var note = "source not found: " + missing.getFirst();
                if (!note.equals(d.reviewNote())) setState(store, d.id(), State.DRAFT, note, null);
                kept++;
                continue;
            }
            Finding twin = null;
            for (var a : accepted) {
                if (overlap(a.claim(), d.claim()) >= 0.6) { twin = a; break; }
            }
            if (twin != null) {
                // Same subject, different load-bearing token — a date, a count, a name, a
                // negation — is a contradiction, not a restatement. Word overlap alone
                // cannot tell "signed in 1648" from "signed in 1658", and retiring the
                // newer one as a duplicate would bury the sourced disagreement. Both
                // become disputed and point at each other; the disputes surface through
                // established() and the library door. (2026-09-05)
                var differs = loadBearingDifference(twin.claim(), d.claim());
                if (!differs.isEmpty()) {
                    var on = String.join(", ", differs);
                    setState(store, d.id(), State.DISPUTED, "disputes " + twin.id() + ": differs on " + on, twin.id());
                    setState(store, twin.id(), State.DISPUTED, "disputed by " + d.id() + ": differs on " + on, d.id());
                    var twinId = twin.id();
                    accepted = new ArrayList<>(accepted);
                    accepted.removeIf(a -> a.id().equals(twinId));
                    disputed++;
                    continue;
                }
                setState(store, d.id(), State.RETIRED, "restates " + twin.id(), twin.id());
                dup++;
                continue;
            }
            setState(store, d.id(), State.ACCEPTED, "sources resolve; no prior claim restated", null);
            accepted = new ArrayList<>(accepted);
            accepted.add(d.withState(State.ACCEPTED, null, null));
            ok++;
        }
        log.info("[findings] review for {}: {} draft(s) → {} accepted, {} kept draft, {} retired as duplicates, {} disputed",
            ownerDid, drafts.size(), ok, kept, dup, disputed);
        return new Review(drafts.size(), ok, kept, dup, disputed);
    }

    private static final Set<String> NEGATIONS = Set.of("not", "never", "no", "none", "without", "nor");
    private static final java.util.regex.Pattern PROPER = java.util.regex.Pattern.compile("\\b\\p{Lu}[\\p{L}\\p{N}'-]{2,}");

    /**
     * Tokens that decide a claim and appear in exactly one of two overlapping claims:
     * numbers, negations, and proper names that are not the sentence's first word.
     * Empty means the two say the same thing in different words.
     */
    static List<String> loadBearingDifference(String a, String b) {
        var out = new ArrayList<String>();
        var wa = words(a);
        var wb = words(b);
        for (var w : new java.util.TreeSet<>(symmetric(wa, wb))) {
            if (w.chars().allMatch(Character::isDigit) || NEGATIONS.contains(w)) out.add(w);
        }
        var pa = properNames(a);
        var pb = properNames(b);
        for (var n : new java.util.TreeSet<>(symmetric(pa, pb))) {
            var lower = n.toLowerCase(Locale.ROOT);
            if (!out.contains(lower)) out.add(n);
        }
        return out;
    }

    private static Set<String> symmetric(Set<String> x, Set<String> y) {
        var only = new HashSet<String>(x);
        only.removeAll(y);
        var other = new HashSet<String>(y);
        other.removeAll(x);
        only.addAll(other);
        return only;
    }

    /** Capitalized words after the first, lower-cased for comparison but reported as written. */
    private static Set<String> properNames(String s) {
        var out = new HashSet<String>();
        if (s == null) return out;
        var m = PROPER.matcher(s);
        while (m.find()) {
            if (m.start() == 0) continue;   // the sentence's first word is capitalized anyway
            out.add(m.group());
        }
        return out;
    }

    /** Sources the shelves cannot produce: by chunk id when there is one, by title otherwise. */
    static List<String> unresolvedSources(WyrdLuceneStore store, Finding f) {
        var missing = new ArrayList<String>();
        for (var s : f.sources()) {
            // Another library's answer, or a web page, is not on our shelves: it stays a
            // draft with that said, rather than being "verified" by a title that happens
            // to match something here.
            if (s.external()) {
                missing.add("held by another library: " + s.locator()
                    + (s.edition() == null ? "" : " (" + s.edition() + ")"));
                continue;
            }
            var line = s.line().toLowerCase(Locale.ROOT);
            if (line.startsWith("librarian:") || line.contains("http://") || line.contains("https://")) {
                missing.add("not on our shelves: " + s.line());
                continue;
            }
            boolean found = false;
            if (s.chunkId() != null) {
                found = store.getById(SearchCollections.KNOWLEDGE, s.chunkId()) != null
                    || store.getById(SearchCollections.STUDY, s.chunkId()) != null;
            }
            if (!found) {
                var title = s.title();
                if (!title.isBlank()) {
                    try {
                        var probe = title.length() > 120 ? title.substring(0, 120) : title;
                        found = !store.searchKnowledgeText(probe, 1).isEmpty();
                        if (!found) {
                            // The Study holds the findings themselves, whose source lines
                            // would match their own titles — a finding must not vouch for
                            // its own source. Only non-finding Study items count.
                            for (var r : store.searchStudy(f.ownerDid(), probe, 5)) {
                                var meta = r.metadata();
                                var type = meta == null ? "" : String.valueOf(meta.getOrDefault("item_type", ""));
                                if (!ITEM_TYPE.equals(type)) { found = true; break; }
                            }
                        }
                    } catch (RuntimeException e) {
                        found = false;
                    }
                }
            }
            if (!found) missing.add(s.line());
        }
        return missing;
    }

    /** Jaccard overlap of word sets, lowercased, words of 3+ letters. */
    static double overlap(String a, String b) {
        var x = words(a);
        var y = words(b);
        if (x.isEmpty() || y.isEmpty()) return 0;
        var inter = new HashSet<>(x);
        inter.retainAll(y);
        var union = new HashSet<>(x);
        union.addAll(y);
        return (double) inter.size() / union.size();
    }

    private static Set<String> words(String s) {
        var out = new HashSet<String>();
        if (s == null) return out;
        for (var w : s.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) if (w.length() >= 3) out.add(w);
        return out;
    }

    // ── rendering for her prompt ─────────────────────────────────────────────

    /** "What I established before", compact, with state and the first source. */
    public static String render(List<Finding> findings, int maxChars) {
        if (findings == null || findings.isEmpty()) return "";
        var sb = new StringBuilder();
        for (var f : findings) {
            var line = new StringBuilder("- [").append(f.state().key()).append(", ")
                .append(f.claimType().key()).append("] ").append(f.claim().replaceAll("\\s+", " ").strip());
            if (f.federated()) line.append(" — held by library ").append(f.originLibrary());
            else if (!f.sources().isEmpty()) line.append(" — from ").append(f.sources().getFirst().title());
            if (f.state() == State.DRAFT) line.append(" (not yet reviewed)");
            line.append('\n');
            if (sb.length() + line.length() > maxChars) break;
            sb.append(line);
        }
        return sb.toString().stripTrailing();
    }
}
