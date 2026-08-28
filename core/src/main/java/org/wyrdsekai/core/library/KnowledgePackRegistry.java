package org.wyrdsekai.core.library;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Registry of known knowledge packs, loaded from a JSON configuration file.
 * Ships with a built-in registry (knowledge-packs.json on classpath) and
 * can be extended with user-defined registries at runtime.
 *
 * No URLs are hardcoded in Java. All pack definitions live in JSON config.
 *
 * Download pipeline:
 * 1. Resolve pack name → download URL(s) from registry
 * 2. Download via PackDownloader (handles any format)
 * 3. Convert to JSONL chunks (auto-detected by format)
 * 4. Index via KnowledgePackIndexer
 */
public final class KnowledgePackRegistry {

    private static final Logger log = LoggerFactory.getLogger(KnowledgePackRegistry.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A known pack in the registry ( tier model). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PackInfo(
        String name,
        String title,
        String description,
        List<String> downloadUrls,
        String copyright,
        String contentRating,
        String license,
        List<String> subjects,
        String estimatedSize,
        Boolean essential,      // DEPRECATED — kept for back-compat; read as tier <= 1
        Integer tier,           // 0=First Shelf, 1=starter download, 2=steward's shelves
        String shelf,           // tier-2 grouping: "qa" | "reference" | "coding"
        Boolean recommended,    // tier-2 packs highlighted in the default offer
        List<String> language,  // ISO codes the pack serves (e.g. ["ja","en"])
        Boolean noFederate,     // license forbids redistribution → never share via OPDS-K, never bundle
        String urlResolver,     // resolve URLs at install time (e.g. "wikimedia-cirrus") instead of static
        Map<String, String> resolverArgs
    ) {
        /** Back-compat ctor matching the pre-tier shape. */
        public PackInfo(String name, String title, String description, List<String> downloadUrls,
                        String copyright, String contentRating, String license, List<String> subjects,
                        String estimatedSize, Boolean essential) {
            this(name, title, description, downloadUrls, copyright, contentRating, license, subjects,
                estimatedSize, essential, null, null, null, null, null, null, null);
        }

        /** Effective tier: explicit {@code tier}, else essential→1, else 2. */
        public int effectiveTier() {
            if (tier != null) return tier;
            return essential != null && essential ? 1 : 2;
        }

        /** True when this pack must never be re-shared via federation or bundled (NC licenses). */
        public boolean isNoFederate() { return noFederate != null && noFederate; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RegistryFile(String version, List<PackInfo> packs) {}

    private static volatile List<PackInfo> catalog;

    /** Load the catalog (lazy, thread-safe). */
    private static List<PackInfo> getCatalog() {
        if (catalog != null) return catalog;
        synchronized (KnowledgePackRegistry.class) {
            if (catalog != null) return catalog;
            catalog = loadCatalog();
            return catalog;
        }
    }

    private static List<PackInfo> loadCatalog() {
        var packs = new ArrayList<PackInfo>();

        // 1. Load built-in registry from classpath
        try (var is = KnowledgePackRegistry.class.getResourceAsStream("/knowledge-packs.json")) {
            if (is != null) {
                var registry = MAPPER.readValue(is, RegistryFile.class);
                packs.addAll(registry.packs());
                log.info("[Library] Loaded built-in registry v{}: {} packs", registry.version(), packs.size());
            }
        } catch (Exception e) {
            log.warn("[Library] Failed to load built-in registry: {}", e.getMessage());
        }

        // 2. Load user registry override if exists (~/.wyrdsekai/knowledge-packs.json)
        var userRegistry = Path.of(System.getProperty("user.home"), ".wyrdsekai", "knowledge-packs.json");
        if (Files.exists(userRegistry)) {
            try {
                var registry = MAPPER.readValue(userRegistry.toFile(), RegistryFile.class);
                // User packs override built-in by name
                var userNames = new HashSet<String>();
                for (var p : registry.packs()) {
                    userNames.add(p.name());
                    // Remove any built-in with same name
                    packs.removeIf(bp -> bp.name().equals(p.name()));
                }
                packs.addAll(registry.packs());
                log.info("[Library] Loaded user registry: {} packs (overrides: {})",
                    registry.packs().size(), userNames);
            } catch (Exception e) {
                log.warn("[Library] Failed to load user registry: {}", e.getMessage());
            }
        }

        return List.copyOf(packs);
    }

    /** Force reload of the catalog (after user edits the config). */
    public static void reload() {
        synchronized (KnowledgePackRegistry.class) {
            catalog = null;
        }
    }

    /** List all known packs. */
    public static List<PackInfo> listAvailable() {
        return getCatalog();
    }

    /** List essential packs (recommended for first install). Back-compat: essential flag OR tier ≤ 1. */
    public static List<PackInfo> listEssential() {
        return getCatalog().stream()
            .filter(p -> (p.essential() != null && p.essential()) || p.effectiveTier() <= 1)
            .toList();
    }

    /** List packs at exactly the given tier. */
    public static List<PackInfo> listTier(int tier) {
        return getCatalog().stream()
            .filter(p -> p.effectiveTier() == tier)
            .toList();
    }

    /**
     * Packs that are LOOKUP surfaces, not browsing corpora — anything whose
     * subjects include "Dictionaries". Found live 2026-08-24: the bundled
     * FreeDict (68k rows) and JMdict (217k rows) sat in the same index the
     * companion's library search reads, and under BM25 a three-word headword
     * gloss beats a book passage for every short query — so "glass tide"
     * answered with Spanish crash-glossary rows and the fairy tales were
     * about vocabulary. Default knowledge search excludes these; the
     * pack-scoped search door stays open for the translation tooling that
     * will one day actually want them.
     */
    public static List<String> lookupPackNames() {
        return listAvailable().stream()
            .filter(p -> p.subjects() != null && p.subjects().stream()
                .anyMatch("dictionaries"::equalsIgnoreCase))
            .map(PackInfo::name)
            .toList();
    }

    /** List tier-2 packs on a shelf ("qa" | "reference" | "coding"). */
    public static List<PackInfo> listShelf(String shelf) {
        return getCatalog().stream()
            .filter(p -> p.effectiveTier() == 2 && shelf.equalsIgnoreCase(p.shelf()))
            .toList();
    }

    /** Find a pack by name. */
    public static Optional<PackInfo> find(String name) {
        return getCatalog().stream()
            .filter(p -> p.name().equalsIgnoreCase(name))
            .findFirst();
    }

    /**
     * Download and install a pack by name.
     */
    public static CompletableFuture<KnowledgePackIndexer.IndexResult> install(
            String name, Path packsDir, KnowledgePackIndexer indexer,
            Consumer<String> progress) {

        return CompletableFuture.supplyAsync(() -> {
            var pack = find(name).orElseThrow(() ->
                new IllegalArgumentException("Unknown pack: '" + name + "'. Available: " +
                    getCatalog().stream().map(PackInfo::name).toList()));

            try {
                var packDir = packsDir.resolve(name);
                Files.createDirectories(packDir.resolve("chunks"));

                // Resolve URLs at install time when the source rotates (e.g. Wikimedia weekly dumps)
                var urls = resolveUrls(pack, progress);

                // Download all URLs
                for (var url : urls) {
                    if (progress != null) progress.accept("Downloading: " + url);
                    PackDownloader.download(url, packDir, progress);
                }

                // Generate pack.json if not present in download
                if (!Files.exists(packDir.resolve("pack.json"))) {
                    if (progress != null) progress.accept("Generating pack metadata...");
                    writePack(pack, packDir);
                }

                // Auto-convert downloaded formats to JSONL
                convertIfNeeded(packDir, name, progress);

                // Index into Lucene
                if (progress != null) progress.accept("Indexing into knowledge base...");
                var result = indexer.indexPack(packDir, count -> {
                    if (progress != null) progress.accept("Indexed " + count + " chunks...");
                });

                if (progress != null) progress.accept("Done! " + result.chunksIndexed() + " chunks indexed.");
                return result;

            } catch (Exception e) {
                log.error("[Library] Failed to install pack '{}': {}", name, e.getMessage(), e);
                throw new RuntimeException("Pack install failed: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Download and prepare a pack WITHOUT indexing — the air-gap path
     * (, {@code wyrd library bundle}).
     * Resolves URLs, downloads, writes pack.json, and runs format conversion so
     * the resulting directory is ready for an offline
     * {@code wyrd library install <name> --from-dir <dir>/<name>}.
     * Synchronous; idempotent via a non-empty chunks/ skip. Returns the pack dir.
     */
    public static Path downloadOnly(String name, Path destRoot, Consumer<String> progress)
            throws IOException {
        var pack = find(name).orElseThrow(() ->
            new IllegalArgumentException("Unknown pack: '" + name + "'. Available: " +
                getCatalog().stream().map(PackInfo::name).toList()));

        var packDir = destRoot.resolve(pack.name());
        var chunksDir = packDir.resolve("chunks");
        Files.createDirectories(chunksDir);

        try (var existing = Files.list(chunksDir)) {
            if (existing.findAny().isPresent()) {
                if (progress != null) progress.accept("'" + pack.name() + "' already in bundle — skipping");
                return packDir;
            }
        }

        var urls = resolveUrls(pack, progress);
        for (var url : urls) {
            if (progress != null) progress.accept("Downloading: " + url);
            PackDownloader.download(url, packDir, progress);
        }
        if (!Files.exists(packDir.resolve("pack.json"))) {
            writePack(pack, packDir);
        }
        convertIfNeeded(packDir, pack.name(), progress);
        return packDir;
    }

    /**
     * Resolve a pack's download URLs: static {@code downloadUrls} unless the entry names a
     * {@code urlResolver} (sources whose URLs rotate and must be looked up at install time).
     */
    private static List<String> resolveUrls(PackInfo pack, Consumer<String> progress) throws IOException {
        if (pack.urlResolver() == null || pack.urlResolver().isBlank()) {
            return pack.downloadUrls() != null ? pack.downloadUrls() : List.of();
        }
        return switch (pack.urlResolver()) {
            case "wikimedia-cirrus" -> {
                var index = pack.resolverArgs() != null ? pack.resolverArgs().get("index") : null;
                if (index == null || index.isBlank()) {
                    throw new IOException("Pack '" + pack.name() + "' uses wikimedia-cirrus resolver but has no resolverArgs.index");
                }
                if (progress != null) progress.accept("Resolving latest Wikimedia dump for " + index + "...");
                yield WikimediaCirrusResolver.resolve(index);
            }
            default -> throw new IOException("Unknown urlResolver '" + pack.urlResolver() + "' for pack '" + pack.name() + "'");
        };
    }

    /**
     * Install a pack from any URL (not in registry).
     */
    public static CompletableFuture<KnowledgePackIndexer.IndexResult> installFromUrl(
            String name, String url, Path packsDir, KnowledgePackIndexer indexer,
            Consumer<String> progress) {

        return CompletableFuture.supplyAsync(() -> {
            try {
                var packDir = packsDir.resolve(name);
                Files.createDirectories(packDir.resolve("chunks"));

                PackDownloader.download(url, packDir, progress);

                if (!Files.exists(packDir.resolve("pack.json"))) {
                    var packJson = new KnowledgePack(
                        name, name, "Downloaded from " + url,
                        List.of(), "Pack from " + url, null, null,
                        "en", "unknown", "unknown", "general",
                        Map.of(), null, Map.of(),
                        null, null, List.of("knowledge"), List.of(), url);
                    MAPPER.writerWithDefaultPrettyPrinter()
                        .writeValue(packDir.resolve("pack.json").toFile(), packJson);
                }

                convertIfNeeded(packDir, name, progress);

                if (progress != null) progress.accept("Indexing...");
                return indexer.indexPack(packDir, count -> {
                    if (progress != null) progress.accept("Indexed " + count + " chunks...");
                });
            } catch (Exception e) {
                log.error("[Library] Failed to install from URL '{}': {}", url, e.getMessage(), e);
                throw new RuntimeException("Install from URL failed: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Auto-detect and convert downloaded files to JSONL chunks.
     */
    private static void convertIfNeeded(Path packDir, String packName,
                                          Consumer<String> progress) throws IOException {
        // Parquet files → JSONL
        try (var files = Files.list(packDir)) {
            for (var pf : files.filter(f -> f.toString().endsWith(".parquet")).toList()) {
                var output = packDir.resolve("chunks/" + pf.getFileName().toString()
                    .replace(".parquet", ".jsonl"));
                try {
                    FormatConverters.convertParquet(pf, output, packName, progress);
                    Files.deleteIfExists(pf);
                } catch (Exception e) {
                    log.warn("[Library] Parquet conversion failed: {}", e.getMessage());
                }
            }
        }

        // CirrusSearch JSON (Wikimedia dumps) → JSONL. Matches both the legacy
        // "<wiki>-cirrussearch-content.json" naming and the 2026 sharded
        // "<index>_content-YYYYMMDD-NNNNN.json" naming from cirrus_search_index/.
        try (var files = Files.list(packDir)) {
            for (var f : files.filter(p -> {
                var n = p.getFileName().toString();
                return n.contains("cirrussearch")
                    || n.matches(".*_(content|general)-\\d{8}-\\d+\\.json");
            }).toList()) {
                var output = packDir.resolve("chunks/wikipedia.jsonl");
                FormatConverters.convertWikipediaCirrus(f, output, packName, progress);
                Files.deleteIfExists(f);
            }
        }

        // JMdict XML (EDRDG Japanese–English dictionary) → JSONL
        try (var files = Files.list(packDir)) {
            for (var f : files.filter(p -> p.getFileName().toString().startsWith("JMdict")).toList()) {
                var output = packDir.resolve("chunks/jmdict.jsonl");
                FormatConverters.convertJmdictXml(f, output, packName, progress);
                Files.deleteIfExists(f);
            }
        }

        // FreeDict TEI dictionaries (tar.xz extracts into a subdir, e.g. spa-eng/spa-eng.tei)
        try (var files = Files.walk(packDir)) {
            for (var f : files.filter(p -> p.getFileName().toString().endsWith(".tei")).toList()) {
                var output = packDir.resolve("chunks/"
                    + f.getFileName().toString().replace(".tei", "") + ".jsonl");
                FormatConverters.convertFreedictTei(f, output, packName, progress);
            }
        }

        // 7z archives (StackExchange) → extract, then convert Posts.xml
        try (var files = Files.list(packDir)) {
            for (var f : files.filter(p -> p.toString().endsWith(".7z")).toList()) {
                FormatConverters.extract7z(f, packDir, progress);
                Files.deleteIfExists(f);
            }
        }

        // StackExchange Posts.xml → JSONL
        try (var files = Files.walk(packDir)) {
            for (var f : files.filter(p -> p.getFileName().toString().equals("Posts.xml")).toList()) {
                var output = packDir.resolve("chunks/posts.jsonl");
                FormatConverters.convertStackExchangeXml(f, output, packName, progress);
            }
        }

        // MedQuAD-style XML corpora → JSONL. The QA files ship as thousands of
        // per-question .xml nested under category dirs — and a GitHub zip download
        // adds a "<repo>-master/" wrapper, so the .xml land *two* levels below
        // packDir. Detect them with a recursive walk: an immediate-subdir check
        // misses the wrapper dir entirely (this is why a fresh medquad install used
        // to index only the readme/license as 7 text chunks). Gated on chunksEmpty +
        // a meaningful .xml count so an incidental schema .xml (e.g. FreeDict's TEI
        // doc) never triggers a spurious empty medquad.jsonl.
        if (chunksEmpty(packDir) && countXmlFiles(packDir, 3) >= 3) {
            var output = packDir.resolve("chunks/medquad.jsonl");
            FormatConverters.convertMedQuadXml(packDir, output, packName, progress);
        }

        // Gutenberg plain text files → JSONL
        try (var files = Files.list(packDir)) {
            for (var f : files.filter(p -> {
                var name = p.getFileName().toString();
                return name.startsWith("pg") && name.endsWith(".txt");
            }).toList()) {
                var bookName = f.getFileName().toString().replace(".txt", "");
                var output = packDir.resolve("chunks/" + bookName + ".jsonl");
                FormatConverters.convertGutenbergText(f, output, packName, bookName, progress);
            }
        }

        // Last resort: plain-text/markdown documentation trees (e.g. python-docs-text tarballs)
        // — chunk every .txt/.md under the pack dir.
        boolean empty = chunksEmpty(packDir);
        if (empty) {
            int converted = FormatConverters.convertPlainTextTree(
                packDir, packDir.resolve("chunks"), packName, progress);
            if (converted > 0) empty = false;
        }

        if (empty) {
            log.warn("[Library] Pack '{}': no chunks generated after conversion", packName);
        }
    }

    /** Count {@code .xml} files anywhere under {@code root}, short-circuiting once {@code cap} are seen. */
    private static long countXmlFiles(Path root, int cap) throws IOException {
        try (var walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                .filter(f -> f.getFileName().toString().endsWith(".xml"))
                .limit(cap)
                .count();
        }
    }

    /** True when chunks/ is missing or contains no non-empty file. */
    private static boolean chunksEmpty(Path packDir) throws IOException {
        var chunksDir = packDir.resolve("chunks");
        if (!Files.isDirectory(chunksDir)) return true;
        try (var c = Files.list(chunksDir)) {
            for (var f : c.toList()) {
                if (Files.isRegularFile(f) && Files.size(f) > 0) return false;
            }
        }
        return true;
    }

    private static void writePack(PackInfo pack, Path packDir) throws IOException {
        var packJson = new KnowledgePack(
            pack.name(), pack.title(), pack.name(),
            pack.subjects(), pack.description(), null, null,
            "en", pack.license(), pack.copyright(), pack.contentRating(),
            Map.of(), null, Map.of("download", pack.estimatedSize()),
            null, null, List.of("knowledge"), List.of(),
            pack.downloadUrls().isEmpty() ? "" : pack.downloadUrls().getFirst()
        );
        MAPPER.writerWithDefaultPrettyPrinter()
            .writeValue(packDir.resolve("pack.json").toFile(), packJson);
    }
}
