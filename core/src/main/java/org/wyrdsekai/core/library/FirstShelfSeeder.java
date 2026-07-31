package org.wyrdsekai.core.library;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.search.WyrdLuceneStore;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * seeds the bundled Tier-0 "First Shelf" into the Library at zone
 * bootstrap: the Book of the World (the world's truthful account of itself, including the
 * {@code world.*} API for skill authors), the reference core (anchor-shaped facts: units,
 * kitchen safety, dates, constants), and the trilingual culture seed.
 *
 * <p>This exists because a fresh Library otherwise starts near-empty, and an empty Library is a
 * welfare problem: SEEKING probes against nothing are felt refusals. The First Shelf guarantees
 * every zone — including fully air-gapped ones — is born with something real to read and ground
 * on, before any pack download.</p>
 *
 * <p>Idempotent: {@link WyrdLuceneStore#insertKnowledge} upserts by chunk id, and a version
 * marker chunk (whose content carries a version-unique token) lets boot skip re-seeding until
 * the bundled content's {@code version=} line in {@code first-shelf/manifest.txt} is bumped.</p>
 */
public final class FirstShelfSeeder {

    private static final Logger log = LoggerFactory.getLogger(FirstShelfSeeder.class);

    static final String PACK = "first-shelf";
    private static final String RESOURCE_ROOT = "/first-shelf/";
    private static final String MARKER_ID = PACK + ":version-marker";

    private FirstShelfSeeder() {}

    /**
     * Seed the First Shelf if this bundle version isn't already present.
     *
     * @return number of chunks written this call (0 = already seeded at this version)
     */
    public static int seed(WyrdLuceneStore lucene) {
        if (lucene == null) return 0;
        try {
            var manifest = readManifest();
            if (manifest == null) {
                log.warn("[FirstShelf] no bundled manifest on classpath — skipping seed");
                return 0;
            }
            String markerToken = versionToken(manifest.version());
            if (alreadySeeded(lucene, markerToken)) {
                log.info("[FirstShelf] already seeded (version {})", manifest.version());
                return 0;
            }

            int written = 0;
            for (var entry : manifest.entries()) {
                String text = readResource(RESOURCE_ROOT + entry.path());
                if (text == null) {
                    log.warn("[FirstShelf] bundled file missing: {}", entry.path());
                    continue;
                }
                written += seedFile(lucene, entry, text);
            }

            // Version marker last, so an interrupted seed retries next boot.
            var markerProv = provenance("manifest", "First Shelf version marker");
            lucene.insertKnowledge(MARKER_ID, PACK, "First Shelf version marker",
                "Bundled First Shelf content marker " + markerToken,
                "First Shelf", "meta", null, markerProv);
            lucene.commitAll();
            log.info("[FirstShelf] seeded {} chunks from {} bundled files (version {})",
                written, manifest.entries().size(), manifest.version());
            return written;
        } catch (Exception e) {
            // Never block boot on the shelf — a zone without it is the old (bad) status quo, not broken.
            log.warn("[FirstShelf] seeding failed: {}", e.getMessage(), e);
            return 0;
        }
    }

    private static int seedFile(WyrdLuceneStore lucene, ManifestEntry entry, String text) {
        String title = firstHeading(text, entry.path());
        var segments = DocumentExtractor.chunkText(title, text);
        var prov = provenance(entry.path(), title);
        int n = 0;
        for (var segment : segments) {
            String partTitle = segments.size() > 1
                ? title + " (part " + (segment.chunkIndex() + 1) + "/" + segment.totalChunks() + ")"
                : title;
            lucene.insertKnowledge(
                PACK + ":" + entry.path() + ":" + segment.chunkIndex(),
                PACK, partTitle, segment.content(),
                "First Shelf", entry.category(), null, prov);
            n++;
        }
        return n;
    }

    private static Provenance provenance(String ref, String title) {
        return new Provenance(
            new Provenance.Source("first-shelf", ref, null, title, List.of(), 2026),
            Provenance.TrustTier.BOOK, "Apache-2.0", null, null, null, null, null, null);
    }

    /** Single-token version sentinel — survives any analyzer, searchable exactly. */
    static String versionToken(String version) {
        return "wyrdfirstshelfv" + version.replaceAll("[^0-9A-Za-z]", "");
    }

    private static boolean alreadySeeded(WyrdLuceneStore lucene, String markerToken) {
        try {
            var hits = lucene.searchKnowledgeText(markerToken, 1);
            return hits != null && !hits.isEmpty() && hits.get(0).content().contains(markerToken);
        } catch (Exception e) {
            return false;
        }
    }

    // ── bundled-resource reading ───────────────────────────────────────────

    record ManifestEntry(String path, String language, String category) {}
    record Manifest(String version, List<ManifestEntry> entries) {}

    static Manifest readManifest() throws IOException {
        String raw = readResource(RESOURCE_ROOT + "manifest.txt");
        if (raw == null) return null;
        String version = "0";
        var entries = new ArrayList<ManifestEntry>();
        for (var line : raw.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("version=")) {
                version = line.substring("version=".length()).trim();
                continue;
            }
            var parts = line.split("\\|");
            if (parts.length >= 3) {
                entries.add(new ManifestEntry(parts[0].trim(), parts[1].trim(), parts[2].trim()));
            }
        }
        return new Manifest(version, List.copyOf(entries));
    }

    private static String readResource(String path) throws IOException {
        try (InputStream is = FirstShelfSeeder.class.getResourceAsStream(path)) {
            if (is == null) return null;
            try (var reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                var sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line).append('\n');
                return sb.toString();
            }
        }
    }

    private static String firstHeading(String text, String fallbackPath) {
        for (var line : text.split("\n")) {
            line = line.trim();
            if (line.startsWith("#")) return line.replaceFirst("^#+\\s*", "");
        }
        var name = fallbackPath.substring(fallbackPath.lastIndexOf('/') + 1);
        return name.replaceAll("\\.md$", "").replace('-', ' ');
    }
}
