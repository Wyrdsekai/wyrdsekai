package org.wyrdsekai.core.library;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.lucene.store.AlreadyClosedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.search.WyrdLuceneStore;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Indexes knowledge pack chunks into WyrdLuceneStore.
 * Handles both pre-embedded chunks (with vectors) and text-only chunks
 * (which will be indexed for BM25 text search without vector similarity).
 *
 * Usage:
 * <pre>
 *   var indexer = new KnowledgePackIndexer(luceneStore);
 *   var result = indexer.indexPack(packDir);
 *   // result.chunksIndexed() == 224000
 * </pre>
 */
public final class KnowledgePackIndexer {

    private static final Logger log = LoggerFactory.getLogger(KnowledgePackIndexer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /**
     * Inserts per commit. Inserts use the store's BULK path (no per-document
     * searcher refresh, no interleaved commit) — through the normal path every
     * chunk forces an NRT segment flush, which held the household node's
     * 13.7M-chunk shelf publish to ~170 chunks/s. Each batch boundary commits
     * AND refreshes ({@code commitAll}), so search visibility lags a running
     * index by at most one batch.
     */
    private static final int COMMIT_BATCH_SIZE = 5000;
    /**
     * Pause after each commit batch. A fresh-boot index of 400k+ bundled chunks (jmdict ~217k)
     * otherwise saturates the CPU and holds the Lucene write lock for minutes, leaving the node
     * unresponsive (second-node 2026-07-08: research turns hung ~15 min on first boot). On a virtual
     * thread this sleep unmounts the carrier — freeing CPU for the actor/inference — and releases
     * the write-lock window so library reads can proceed. Cost: a modest increase in total index
     * time for a strictly-background task. Tunable via WYRDSEKAI_PACK_INDEX_PAUSE_MS.
     */
    private static final long BATCH_PAUSE_MS = resolvePauseMs();

    private static long resolvePauseMs() {
        var env = System.getenv("WYRDSEKAI_PACK_INDEX_PAUSE_MS");
        if (env != null && !env.isBlank()) {
            try { return Math.max(0, Long.parseLong(env.trim())); } catch (NumberFormatException ignored) {}
        }
        return 200L;
    }

    private static void throttle() {
        if (BATCH_PAUSE_MS <= 0) return;
        try { Thread.sleep(BATCH_PAUSE_MS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private final WyrdLuceneStore luceneStore;

    public KnowledgePackIndexer(WyrdLuceneStore luceneStore) {
        this.luceneStore = luceneStore;
    }

    public record IndexResult(String packName, int chunksIndexed, int errors, long elapsedMs) {
        public boolean success() { return errors == 0 || chunksIndexed > errors; }
    }

    /**
     * Index a knowledge pack from a directory.
     * Expects: pack.json + chunks/*.jsonl (one KnowledgeChunk per line).
     *
     * @param packDir   Directory containing pack.json and chunks/
     * @param progress  Optional callback for progress updates (called every COMMIT_BATCH_SIZE chunks)
     * @return IndexResult with stats
     */
    public IndexResult indexPack(Path packDir, Consumer<Integer> progress) throws IOException {
        var packFile = packDir.resolve("pack.json");
        if (!Files.exists(packFile)) {
            throw new IOException("No pack.json found in " + packDir);
        }

        var pack = MAPPER.readValue(packFile.toFile(), KnowledgePack.class);
        log.info("[Library] Indexing pack '{}' ({})", pack.name(), pack.title());

        var chunksDir = packDir.resolve("chunks");
        if (!Files.isDirectory(chunksDir)) {
            throw new IOException("No chunks/ directory found in " + packDir);
        }

        long start = System.currentTimeMillis();
        var indexed = new AtomicInteger(0);
        var errors = new AtomicInteger(0);
        boolean aborted = false;

        // Process all JSONL files in chunks/
        try (var chunkFiles = Files.list(chunksDir)) {
            chunkFiles
                .filter(f -> f.toString().endsWith(".jsonl"))
                .sorted()
                .forEach(jsonlFile -> {
                    try {
                        indexJsonlFile(jsonlFile, pack.name(), indexed, errors, progress);
                    } catch (IOException e) {
                        log.warn("[Library] Error reading {}: {}", jsonlFile.getFileName(), e.getMessage());
                        errors.incrementAndGet();
                    }
                });
        } catch (AlreadyClosedException e) {
            // The node is shutting down under us. Stop — do not narrate the
            // remaining millions of chunks one WARN at a time. What committed
            // stays committed; a re-run replaces the pack from the top.
            aborted = true;
            log.warn("[Library] Pack '{}' indexing ABORTED at {} chunks — index closed (shutting down). "
                + "Re-run the share/install to finish.", pack.name(), indexed.get());
        }

        if (!aborted) luceneStore.commitAll();

        long elapsed = System.currentTimeMillis() - start;
        log.info("[Library] Pack '{}' indexed: {} chunks, {} errors, {}s",
            pack.name(), indexed.get(), errors.get(), elapsed / 1000);

        return new IndexResult(pack.name(), indexed.get(), errors.get(), elapsed);
    }

    /** Index without progress callback. */
    public IndexResult indexPack(Path packDir) throws IOException {
        return indexPack(packDir, null);
    }

    /**
     * Index chunks from a JSONL file (one KnowledgeChunk per line).
     */
    private void indexJsonlFile(Path jsonlFile, String packName,
                                 AtomicInteger indexed, AtomicInteger errors,
                                 Consumer<Integer> progress) throws IOException {
        try (var reader = new BufferedReader(new FileReader(jsonlFile.toFile()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                try {
                    var chunk = MAPPER.readValue(line, KnowledgeChunk.class);
                    var id = chunk.id() != null ? chunk.id() : packName + ":" + indexed.get();

                    // Convert float[] embedding to List<Float> if present
                    List<Float> embedding = null;
                    if (chunk.embedding() != null) {
                        embedding = new ArrayList<>(chunk.embedding().length);
                        for (float f : chunk.embedding()) {
                            embedding.add(f);
                        }
                    }

                    // Subject terms as pipe-delimited string for Lucene TextField.
                    // provenance backfill — when no
                    // explicit provenance is on the chunk, inject the
                    // pack-default trust tier so downstream search/citation
                    // can render trust info without a schema migration.
                    String subject = chunk.subject() != null
                        ? String.join("|", chunk.subject()) : null;
                    Provenance provenance = chunk.provenance();
                    if (provenance == null) {
                        var tier = PackProvenanceDefaults.infer(packName);
                        subject = PackProvenanceDefaults.subjectWithTier(tier, subject);
                        provenance = new Provenance(null, tier, null, null, null,
                            null, null, "bundled-pack-default", null);
                    }

                    luceneStore.insertKnowledgeBulk(
                        id, packName,
                        chunk.title() != null ? chunk.title() : "",
                        chunk.content(),
                        chunk.source() != null ? chunk.source() : "",
                        subject, embedding, provenance);

                    int count = indexed.incrementAndGet();
                    if (count % COMMIT_BATCH_SIZE == 0) {
                        luceneStore.commitAll();
                        throttle();
                        if (progress != null) progress.accept(count);
                        log.info("[Library] Pack '{}': indexed {} chunks...", packName, count);
                    }
                } catch (AlreadyClosedException e) {
                    throw e;   // shutdown — not a bad chunk; let indexPack stop the run
                } catch (Exception e) {
                    errors.incrementAndGet();
                    if (errors.get() <= 10) {
                        log.debug("[Library] Error parsing chunk: {}", e.getMessage());
                    }
                }
            }
        }
    }

    /**
     * Index a list of pre-built chunks directly (for programmatic pack creation).
     */
    public IndexResult indexChunks(String packName, List<KnowledgeChunk> chunks,
                                    Consumer<Integer> progress) {
        long start = System.currentTimeMillis();
        var indexed = new AtomicInteger(0);
        var errors = new AtomicInteger(0);

        for (var chunk : chunks) {
            try {
                var id = chunk.id() != null ? chunk.id() : packName + ":" + indexed.get();

                List<Float> embedding = null;
                if (chunk.embedding() != null) {
                    embedding = new ArrayList<>(chunk.embedding().length);
                    for (float f : chunk.embedding()) {
                        embedding.add(f);
                    }
                }

                String subject = chunk.subject() != null ? String.join("|", chunk.subject()) : null;
                Provenance provenance = chunk.provenance();
                if (provenance == null) {
                    var tier = PackProvenanceDefaults.infer(packName);
                    subject = PackProvenanceDefaults.subjectWithTier(tier, subject);
                    provenance = new Provenance(null, tier, null, null, null,
                        null, null, "bundled-pack-default", null);
                }

                luceneStore.insertKnowledgeBulk(id, packName,
                    chunk.title() != null ? chunk.title() : "",
                    chunk.content(),
                    chunk.source() != null ? chunk.source() : "",
                    subject, embedding, provenance);

                int count = indexed.incrementAndGet();
                if (count % COMMIT_BATCH_SIZE == 0) {
                    luceneStore.commitAll();
                    throttle();
                    if (progress != null) progress.accept(count);
                }
            } catch (AlreadyClosedException e) {
                log.warn("[Library] Pack '{}' indexing ABORTED at {} chunks — index closed (shutting down).",
                    packName, indexed.get());
                return new IndexResult(packName, indexed.get(), errors.get(),
                    System.currentTimeMillis() - start);
            } catch (Exception e) {
                errors.incrementAndGet();
            }
        }

        luceneStore.commitAll();
        long elapsed = System.currentTimeMillis() - start;

        log.info("[Library] Indexed {} chunks for pack '{}' in {}s",
            indexed.get(), packName, elapsed / 1000);
        return new IndexResult(packName, indexed.get(), errors.get(), elapsed);
    }

    /**
     * Remove all chunks for a pack from the knowledge index.
     */
    public long removePack(String packName) {
        long deleted = luceneStore.deleteKnowledgeByPack(packName);
        log.info("[Library] Removed pack '{}': {} chunks deleted", packName, deleted);
        return deleted;
    }

    /**
     * Get chunk count for a specific pack.
     */
    public long packSize(String packName) {
        return luceneStore.countKnowledgeByPack(packName);
    }

    /**
     * Get total chunk count across all packs.
     */
    public long totalSize() {
        return luceneStore.countKnowledge();
    }
}
