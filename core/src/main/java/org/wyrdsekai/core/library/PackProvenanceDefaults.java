package org.wyrdsekai.core.library;

import java.util.Map;

/**
 * Default trust tier per bundled pack name (
 * provenance backfill). Applied when a chunk is indexed without explicit
 * provenance — the pack's name is the strongest available signal in the
 * absence of richer metadata.
 *
 * <p>Used by {@link KnowledgePackIndexer} on the legacy ingest path: when
 * {@code chunk.provenance() == null}, the indexer composes a {@code subject}
 * tag {@code "<tier>|<pack-kind>"} so downstream search/citation paths can
 * render the trust level without a schema migration.</p>
 */
public final class PackProvenanceDefaults {

    /** Match by exact pack name (lowercase). Fallback if no exact match: {@link #infer(String)}. */
    private static final Map<String, Provenance.TrustTier> EXACT = Map.ofEntries(
        Map.entry("wikipedia-simple", Provenance.TrustTier.WIKI),
        Map.entry("simple-wikipedia", Provenance.TrustTier.WIKI),
        Map.entry("wikipedia", Provenance.TrustTier.WIKI),
        Map.entry("medquad", Provenance.TrustTier.PAPER),
        Map.entry("med-quad", Provenance.TrustTier.PAPER),
        Map.entry("stackexchange", Provenance.TrustTier.FORUM),
        Map.entry("stack-exchange", Provenance.TrustTier.FORUM),
        Map.entry("gutenberg", Provenance.TrustTier.BOOK),
        Map.entry("project-gutenberg", Provenance.TrustTier.BOOK),
        Map.entry("arxiv", Provenance.TrustTier.PAPER),
        Map.entry("pubmed", Provenance.TrustTier.PAPER));

    private PackProvenanceDefaults() {}

    /**
     * Infer a trust tier from a pack name. Lowercases first, tries exact
     * matches, then loose substring matches (so {@code "simple-wikipedia-en"}
     * still resolves to WIKI). Returns {@link Provenance.TrustTier#UNKNOWN}
     * when no signal matches — the operator can re-tier explicitly later.
     */
    public static Provenance.TrustTier infer(String packName) {
        if (packName == null || packName.isBlank()) return Provenance.TrustTier.UNKNOWN;
        var lower = packName.toLowerCase();
        var exact = EXACT.get(lower);
        if (exact != null) return exact;
        for (var entry : EXACT.entrySet()) {
            if (lower.contains(entry.getKey())) return entry.getValue();
        }
        // Heuristic fallback by keyword.
        if (lower.contains("wiki")) return Provenance.TrustTier.WIKI;
        if (lower.contains("paper") || lower.contains("journal")
                || lower.contains("arxiv")) return Provenance.TrustTier.PAPER;
        if (lower.contains("book") || lower.contains("ebook")
                || lower.contains("gutenberg")) return Provenance.TrustTier.BOOK;
        if (lower.contains("forum") || lower.contains("stack"))
            return Provenance.TrustTier.FORUM;
        if (lower.contains("blog") || lower.contains("medium")
                || lower.contains("substack")) return Provenance.TrustTier.BLOG;
        if (lower.contains("personal") || lower.contains("upload"))
            return Provenance.TrustTier.PERSONAL;
        return Provenance.TrustTier.UNKNOWN;
    }

    /**
     * Build a Lucene {@code subject} tag carrying the inferred tier alongside
     * existing subject terms. Format: {@code "<tier>|<existing>"} when
     * existing is non-empty, just {@code "<tier>"} otherwise. Idempotent —
     * skips prepending if a tier prefix is already present.
     */
    public static String subjectWithTier(Provenance.TrustTier tier, String existing) {
        var tag = tier == null ? Provenance.TrustTier.UNKNOWN.name().toLowerCase()
            : tier.name().toLowerCase();
        if (existing == null || existing.isBlank()) return tag;
        // Idempotency: if existing already begins with a known tier prefix, leave it.
        for (var t : Provenance.TrustTier.values()) {
            if (existing.toLowerCase().startsWith(t.name().toLowerCase() + "|")
                    || existing.equalsIgnoreCase(t.name())) {
                return existing;
            }
        }
        return tag + "|" + existing;
    }
}
