package org.wyrdsekai.core.naming;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Household blocklist.
 *
 * <p>File-backed at {@code ~/.wyrdsekai/blocks}. Blocks are per-household
 * and apply across every zone the household runs (§6.3). Two modes:</p>
 *
 * <ul>
 *   <li><b>Preemptive</b> — added before any agreement, silent-drop on every
 *       incoming envelope. "You've heard they're bad; don't let them in."</li>
 *   <li><b>Reactive</b> — added with the {@code revoke} flag, after an
 *       existing agreement turned sour. The caller is responsible for
 *       also publishing a revocation envelope; this class just tracks
 *       the block state.</li>
 * </ul>
 *
 * <h2>File format</h2>
 *
 * <pre>
 * # comment lines start with '#'
 * did:wyrd:z6Mk…  2026-04-19T12:00:00Z  [revoke]  [#<source>]  #&lt;note&gt;
 * </pre>
 *
 * <p>Fields are tab- or space-separated. The DID must be well-formed
 * ({@code did:wyrd:} prefix). The timestamp is ISO-8601 UTC. Optional
 * tokens: {@code revoke} (case-insensitive) marks a reactive block;
 * {@code #source} identifies the curator (§6.4) when imported from a
 * shared block list; anything after {@code #} is free-form note.</p>
 *
 * <h2>Concurrency</h2>
 *
 * <p>Not thread-safe. Expected use pattern: CLI edits (mutex at process
 * boundary) or single-threaded federation handler. Add synchronisation at
 * the call site if that assumption changes.</p>
 */
public final class BlockList {

    /**
     * One blocklist entry. {@code note} and {@code source} are free-form
     * annotations; {@code revoke} indicates the "reactive" block mode
     * (§6.5 — one explicit revocation envelope was sent, subsequent
     * traffic silently drops).
     */
    public record Entry(
        String did,
        Instant addedAt,
        boolean revoke,
        String source,   // curator DID if imported, or null for local
        String note      // nullable free-form
    ) {
        public Entry {
            Objects.requireNonNull(did, "did");
            Objects.requireNonNull(addedAt, "addedAt");
            if (!did.startsWith(HouseholdIdentity.DID_SCHEME)) {
                throw new IllegalArgumentException(
                    "did must start with '" + HouseholdIdentity.DID_SCHEME + "': " + did);
            }
        }

        /** Local block (not imported from a curator). */
        public boolean isLocal() {
            return source == null;
        }
    }

    private final Path file;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    private BlockList(Path file) {
        this.file = file;
    }

    /**
     * Load from disk. Missing file returns an empty list. Malformed lines
     * abort with the line number so a hand-edit can be repaired.
     */
    public static BlockList load(Path file) throws IOException {
        var list = new BlockList(file);
        if (!Files.isRegularFile(file)) return list;
        int lineNo = 0;
        for (var rawLine : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            lineNo++;
            var stripped = rawLine.strip();
            if (stripped.isEmpty() || stripped.startsWith("#")) continue;
            try {
                var entry = parseLine(stripped);
                list.entries.put(entry.did(), entry);
            } catch (IllegalArgumentException e) {
                throw new IOException(file + ":" + lineNo + ": " + e.getMessage(), e);
            }
        }
        return list;
    }

    /** In-memory constructor — for tests + programmatic use. */
    public static BlockList empty(Path file) {
        return new BlockList(file);
    }

    /**
     * Save to disk atomically (temp file + move). Never leaves a partial
     * write on crash — callers can rely on all-or-nothing semantics.
     */
    public void save() throws IOException {
        var parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);
        var tmp = file.resolveSibling(file.getFileName() + ".tmp");
        var sb = new StringBuilder();
        sb.append("# wyrdsekai blocks — did  <addedAt>  [revoke]  [#source:<did>]  [#<note>]\n");
        sb.append("# See docs/ZONES.md.\n");
        for (var e : entries.values()) sb.append(formatLine(e)).append('\n');
        Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
        Files.move(tmp, file,
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * Add a block for {@code did}. Overwrites any existing entry — caller
     * can read first to check before overwriting note/revoke semantics.
     *
     * @param revoke true for reactive block (§6.5) — signals the caller
     *               that they should also publish an explicit revocation
     *               envelope before subsequent traffic is silently dropped
     */
    public void add(String did, Instant now, boolean revoke, String note) {
        entries.put(did, new Entry(did, now, revoke, null, note));
    }

    /**
     * Import from a trusted curator (§6.4). Entry is tagged with the
     * curator DID so operators can see <i>why</i> a DID is blocked and
     * which curator said so. Local blocks (via {@link #add}) are untagged
     * and never overwritten by curator imports — the operator's own
     * decision wins over subscribed lists.
     */
    public void importFromCurator(String did, Instant now, boolean revoke,
                                    String curatorDid, String note) {
        Objects.requireNonNull(curatorDid, "curatorDid");
        // Don't shadow local entries — §6.4 "manual wyrd block entries are untouched".
        var existing = entries.get(did);
        if (existing != null && existing.isLocal()) return;
        entries.put(did, new Entry(did, now, revoke, curatorDid, note));
    }

    /**
     * @return true if {@code did} is blocked. Use this at the envelope
     *     intake path (§3.1) to silent-drop without processing.
     */
    public boolean contains(String did) {
        return entries.containsKey(did);
    }

    /** @return the entry for {@code did}, or empty. */
    public Optional<Entry> get(String did) {
        return Optional.ofNullable(entries.get(did));
    }

    /** @return true if the entry existed and was removed. */
    public boolean remove(String did) {
        return entries.remove(did) != null;
    }

    /**
     * Remove every entry imported from a particular curator (§6.4 —
     * unsubscribing rolls back the imports cleanly). Local entries are
     * untouched.
     */
    public int unsubscribeCurator(String curatorDid) {
        Objects.requireNonNull(curatorDid);
        int removed = 0;
        var it = entries.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            if (curatorDid.equals(e.getValue().source())) {
                it.remove();
                removed++;
            }
        }
        return removed;
    }

    /** @return all entries in insertion order, read-only. */
    public List<Entry> list() {
        return List.copyOf(entries.values());
    }

    /** @return just the blocked DIDs. */
    public Set<String> blockedDids() {
        return Collections.unmodifiableSet(entries.keySet());
    }

    /** @return number of entries. */
    public int size() {
        return entries.size();
    }

    // ── line format ───────────────────────────────────────────────────

    /**
     * Parse a single non-comment, non-blank line. Tolerates extra
     * whitespace; tokens are order-significant (did, timestamp) followed
     * by optional flags in any order.
     */
    static Entry parseLine(String line) {
        var parts = line.split("\\s+", 3);
        if (parts.length < 2) {
            throw new IllegalArgumentException(
                "expected '<did>  <addedAt>  [flags]', got: " + line);
        }
        var did = parts[0];
        Instant addedAt;
        try {
            addedAt = Instant.parse(parts[1]);
        } catch (Exception e) {
            throw new IllegalArgumentException("bad timestamp '" + parts[1] + "': " + e.getMessage());
        }
        boolean revoke = false;
        String source = null;
        String note = null;
        if (parts.length >= 3) {
            // Remainder may contain: `revoke`, `#source:<did>`, `#<note>`.
            var rest = parts[2];
            // Extract optional `revoke` token if present at the start.
            var tokens = rest.split("\\s+", 2);
            if (tokens[0].equalsIgnoreCase("revoke")) {
                revoke = true;
                rest = tokens.length > 1 ? tokens[1] : "";
            }
            // Parse #source:... and #<note>. Anything after a `#source:` until
            // whitespace is the curator DID; anything else after `#` is the note.
            if (!rest.isEmpty()) {
                if (rest.startsWith("#source:")) {
                    var srcParts = rest.substring("#source:".length()).split("\\s+", 2);
                    source = srcParts[0];
                    if (srcParts.length > 1 && srcParts[1].startsWith("#")) {
                        note = srcParts[1].substring(1).strip();
                    }
                } else if (rest.startsWith("#")) {
                    note = rest.substring(1).strip();
                }
            }
        }
        return new Entry(did, addedAt, revoke, source, note);
    }

    static String formatLine(Entry e) {
        var sb = new StringBuilder();
        sb.append(e.did()).append('\t').append(e.addedAt());
        if (e.revoke()) sb.append("\trevoke");
        if (e.source() != null) sb.append("\t#source:").append(e.source());
        if (e.note() != null && !e.note().isBlank()) {
            sb.append("\t#").append(e.note().replace('\n', ' '));
        }
        return sb.toString();
    }
}
