package org.wyrdsekai.core.library;

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.time.Duration;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipInputStream;

/**
 * Downloads knowledge pack data from any URL. Handles:
 * - .tar.gz / .tgz → extract to pack directory
 * - .zip → extract to pack directory
 * - .jsonl / .jsonl.gz → place directly in chunks/
 * - .json → place as pack.json or in chunks/
 * - .parquet → download (caller converts)
 * - directories (file:// URLs) → copy
 *
 * No HuggingFace API dependency. Just HTTP GET + extract.
 */
public final class PackDownloader {

    private static final Logger log = LoggerFactory.getLogger(PackDownloader.class);
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    private PackDownloader() {}

    /**
     * Download a pack from a URL into the target directory.
     * Auto-detects format from URL extension or content-type.
     *
     * @param url       Source URL (http/https/file)
     * @param targetDir Target directory (e.g., ~/.wyrdsekai/packs/my-pack/)
     * @param progress  Optional progress callback
     */
    public static void download(String url, Path targetDir, Consumer<String> progress) throws IOException {
        Files.createDirectories(targetDir);
        var chunksDir = targetDir.resolve("chunks");
        Files.createDirectories(chunksDir);

        if (url.startsWith("file://") || url.startsWith("/")) {
            // Local file or directory
            var source = Path.of(url.startsWith("file://") ? URI.create(url) : URI.create("file://" + url));
            if (Files.isDirectory(source)) {
                copyDirectory(source, targetDir, progress);
            } else {
                handleFile(source, source.getFileName().toString(), targetDir, chunksDir, progress);
            }
            return;
        }

        // HTTP download
        if (progress != null) progress.accept("Downloading " + url + "...");
        log.info("[PackDownloader] Downloading {}", url);

        var request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            // Wikimedia (and increasingly others) 403 generic client UAs;
            // their policy asks for an identifying agent with contact.
            // Measured on second-node 2026-08-30: Java-http-client/25 -> 403,
            // identified UA -> 200. Every boot since install had failed.
            .setHeader("User-Agent", WikimediaCirrusResolver.userAgent())
            .timeout(Duration.ofMinutes(10))
            .GET()
            .build();

        var tempFile = Files.createTempFile("pack-download-", detectExtension(url));
        try {
            var response = sendWithRetries(request, tempFile, url, progress);

            long size = Files.size(tempFile);
            if (progress != null) progress.accept("Downloaded " + formatSize(size));
            log.info("[PackDownloader] Downloaded {} ({})", url, formatSize(size));

            handleFile(tempFile, urlBasename(url), targetDir, chunksDir, progress);
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    /** Basename of a URL path (query stripped) — the name format routing keys on. */
    static String urlBasename(String url) {
        var path = url.contains("?") ? url.substring(0, url.indexOf("?")) : url;
        int slash = path.lastIndexOf('/');
        var base = slash >= 0 ? path.substring(slash + 1) : path;
        return base.isBlank() ? "download" : base;
    }

    /**
     * Handle a downloaded file based on its (original) name. {@code originalName} is the URL
     * basename / source filename — the temp file's own name is meaningless for routing.
     */
    private static void handleFile(Path file, String originalName, Path targetDir, Path chunksDir,
                                    Consumer<String> progress) throws IOException {
        var name = originalName.toLowerCase();

        if (name.endsWith(".tar.gz") || name.endsWith(".tgz")) {
            extractTarGz(file, targetDir, progress);
        } else if (name.endsWith(".tar.bz2")) {
            extractTarBz2(file, targetDir, progress);
        } else if (name.endsWith(".tar.xz")) {
            extractTarXz(file, targetDir, progress);
        } else if (name.endsWith(".bz2")) {
            // bz2-compressed single file (Wikimedia cirrus shards are .json.bz2) — decompress
            // into the pack dir keeping the inner name so convertIfNeeded can route it.
            var inner = originalName.substring(0, originalName.length() - ".bz2".length());
            var target = targetDir.resolve(inner);
            try (var bis = new BZip2CompressorInputStream(
                     new BufferedInputStream(new FileInputStream(file.toFile())));
                 var out = new FileOutputStream(target.toFile())) {
                bis.transferTo(out);
            }
            if (progress != null) progress.accept("Decompressed " + formatSize(Files.size(target)));
        } else if (name.endsWith(".zip")) {
            extractZip(file, targetDir, progress);
        } else if (name.endsWith(".jsonl.gz")) {
            // Decompress GZIP'd JSONL into chunks/
            var target = chunksDir.resolve("data.jsonl");
            try (var gis = new GZIPInputStream(new FileInputStream(file.toFile()));
                 var out = new FileOutputStream(target.toFile())) {
                gis.transferTo(out);
            }
            if (progress != null) progress.accept("Extracted " + formatSize(Files.size(target)));
        } else if (name.endsWith(".jsonl")) {
            // Copy JSONL directly into chunks/
            Files.copy(file, chunksDir.resolve("data.jsonl"), StandardCopyOption.REPLACE_EXISTING);
        } else if (name.endsWith(".json") && name.contains("pack")) {
            // Pack metadata
            Files.copy(file, targetDir.resolve("pack.json"), StandardCopyOption.REPLACE_EXISTING);
        } else if (name.endsWith(".json")) {
            // Generic JSON — could be chunks or metadata; keep original name so format
            // detection (e.g. cirrus shards) can route it
            Files.copy(file, targetDir.resolve(originalName), StandardCopyOption.REPLACE_EXISTING);
        } else if (name.endsWith(".parquet")) {
            // Parquet — convert to JSONL using Java-native reader
            var outputFile = chunksDir.resolve(originalName.replace(".parquet", ".jsonl"));
            try {
                int rows = FormatConverters.convertParquet(file, outputFile, "pack", progress);
                if (progress != null) progress.accept("Converted parquet: " + rows + " rows");
            } catch (Exception e) {
                log.error("[PackDownloader] Parquet conversion failed for {}: {}", originalName, e.getMessage(), e);
                // Fallback: save raw parquet for manual conversion
                Files.copy(file, targetDir.resolve(originalName), StandardCopyOption.REPLACE_EXISTING);
                if (progress != null) progress.accept("Parquet conversion failed: " + e.getMessage());
            }
        } else if (name.endsWith(".7z")) {
            // 7z archive — extract contents (StackExchange dumps from archive.org)
            FormatConverters.extract7z(file, targetDir, progress);
        } else if (name.endsWith(".epub")) {
            // EPUB — convert to JSONL
            var outputFile = chunksDir.resolve(originalName.replace(".epub", ".jsonl"));
            int chunks = FormatConverters.convertEpub(file, outputFile, "pack", progress);
        } else if (name.endsWith(".gz") && !name.endsWith(".tar.gz")) {
            // gzip'd single file (e.g. JMdict_e.gz XML, Gutenberg .txt.gz) — decompress
            // into the pack dir keeping the inner name for format routing.
            var inner = originalName.substring(0, originalName.length() - ".gz".length());
            if (!inner.contains(".")) inner = inner + ".xml"; // JMdict_e.gz → JMdict_e.xml-ish marker kept harmless
            var target = targetDir.resolve(inner);
            try (var gis = new GZIPInputStream(new FileInputStream(file.toFile()));
                 var out = new FileOutputStream(target.toFile())) {
                gis.transferTo(out);
            }
            if (progress != null) progress.accept("Decompressed " + formatSize(Files.size(target)));
        } else {
            // Unknown — save as-is under the original name
            Files.copy(file, targetDir.resolve(originalName), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Extract a .tar.gz archive into the target directory.
     */
    private static void extractTarGz(Path archive, Path targetDir,
                                       Consumer<String> progress) throws IOException {
        if (progress != null) progress.accept("Extracting tar.gz...");
        // Use system tar for simplicity (available on macOS and Linux)
        try {
            var pb = new ProcessBuilder("tar", "xzf", archive.toAbsolutePath().toString(),
                "-C", targetDir.toAbsolutePath().toString())
                .redirectErrorStream(true);
            var proc = pb.start();
            var output = new String(proc.getInputStream().readAllBytes());
            int exit = proc.waitFor();
            if (exit != 0) {
                throw new IOException("tar extraction failed (exit " + exit + "): " + output);
            }
            if (progress != null) progress.accept("Extracted tar.gz");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("tar extraction interrupted", e);
        }
    }

    /**
     * Extract a .tar.bz2 archive into the target directory (e.g. python-docs-text).
     */
    private static void extractTarBz2(Path archive, Path targetDir,
                                        Consumer<String> progress) throws IOException {
        if (progress != null) progress.accept("Extracting tar.bz2...");
        try {
            var pb = new ProcessBuilder("tar", "xjf", archive.toAbsolutePath().toString(),
                "-C", targetDir.toAbsolutePath().toString())
                .redirectErrorStream(true);
            var proc = pb.start();
            var output = new String(proc.getInputStream().readAllBytes());
            int exit = proc.waitFor();
            if (exit != 0) {
                throw new IOException("tar.bz2 extraction failed (exit " + exit + "): " + output);
            }
            if (progress != null) progress.accept("Extracted tar.bz2");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("tar.bz2 extraction interrupted", e);
        }
    }

    /**
     * Extract a .tar.xz archive into the target directory (FreeDict TEI sources).
     */
    private static void extractTarXz(Path archive, Path targetDir,
                                       Consumer<String> progress) throws IOException {
        if (progress != null) progress.accept("Extracting tar.xz...");
        try {
            var pb = new ProcessBuilder("tar", "xJf", archive.toAbsolutePath().toString(),
                "-C", targetDir.toAbsolutePath().toString())
                .redirectErrorStream(true);
            var proc = pb.start();
            var output = new String(proc.getInputStream().readAllBytes());
            int exit = proc.waitFor();
            if (exit != 0) {
                throw new IOException("tar.xz extraction failed (exit " + exit + "): " + output);
            }
            if (progress != null) progress.accept("Extracted tar.xz");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("tar.xz extraction interrupted", e);
        }
    }

    /**
     * Extract a .zip archive into the target directory.
     */
    private static void extractZip(Path archive, Path targetDir,
                                     Consumer<String> progress) throws IOException {
        if (progress != null) progress.accept("Extracting zip...");
        try (var zis = new ZipInputStream(new FileInputStream(archive.toFile()))) {
            var entry = zis.getNextEntry();
            int count = 0;
            while (entry != null) {
                var target = targetDir.resolve(entry.getName()).normalize();
                // Security: prevent zip slip
                if (!target.startsWith(targetDir)) {
                    throw new IOException("Zip entry outside target: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(zis, target, StandardCopyOption.REPLACE_EXISTING);
                    count++;
                }
                entry = zis.getNextEntry();
            }
            if (progress != null) progress.accept("Extracted " + count + " files");
        }
    }

    /**
     * Copy a local directory into the target.
     */
    private static void copyDirectory(Path source, Path target, Consumer<String> progress) throws IOException {
        if (progress != null) progress.accept("Copying from " + source + "...");
        try (var walk = Files.walk(source)) {
            walk.forEach(src -> {
                try {
                    var dest = target.resolve(source.relativize(src));
                    if (Files.isDirectory(src)) {
                        Files.createDirectories(dest);
                    } else {
                        Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }

    /**
     * How many times to ask before giving up on a download.
     *
     * <p>Not arbitrary. Measured against archive.org on 2026-08-21, which is where the
     * bundled knowledge packs live: the download URL redirects to one of several storage
     * nodes, and an individual node intermittently answers
     * {@code 500 Internal Server Error} from nginx while its siblings serve the same
     * file fine. Nine consecutive requests produced two 500s, both from the same node;
     * every retry that landed elsewhere succeeded.
     *
     * <p>Since each attempt is redirected afresh, a retry is not merely hope — it is a
     * decent chance of a different, healthy node.
     */
    private static final int MAX_ATTEMPTS = 4;

    /** Grows 1s, 2s, 4s: long enough to move on, short enough that nobody walks away. */
    private static final Duration RETRY_BASE = Duration.ofSeconds(1);

    /**
     * Fetch, retrying the failures that are worth retrying.
     *
     * <h2>Why this exists</h2>
     * A single attempt meant one bad node aborted a whole pack install with
     * {@code "HTTP 500 downloading …"} — a dead end for a person who did nothing wrong
     * and whose next move would have been to try again by hand. Found because the live
     * test failed the suite on 2026-08-21; the test was the symptom, this is the defect.
     *
     * <p>Retries a transient server condition (5xx, 408, 429) and a broken connection.
     * Does NOT retry a 404 or a 403 — those do not get better by asking again, and
     * hiding them behind three more attempts would just make the real answer slower.
     */
    private static HttpResponse<Path> sendWithRetries(HttpRequest request, Path tempFile,
            String url, Consumer<String> progress) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                var response = HTTP.send(request,
                    HttpResponse.BodyHandlers.ofFile(tempFile,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING));
                if (response.statusCode() == 200) return response;
                if (!worthRetrying(response.statusCode()) || attempt == MAX_ATTEMPTS) {
                    throw new IOException("HTTP " + response.statusCode()
                        + " downloading " + url
                        + (attempt > 1 ? " (after " + attempt + " attempts)" : ""));
                }
                last = new IOException("HTTP " + response.statusCode());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Download interrupted", e);
            } catch (IOException e) {
                if (attempt == MAX_ATTEMPTS) throw e;
                last = e;
            }
            var wait = RETRY_BASE.multipliedBy(1L << (attempt - 1));
            var note = "Download failed (" + last.getMessage() + ") — retrying in "
                + wait.toSeconds() + "s (attempt " + (attempt + 1) + "/" + MAX_ATTEMPTS + ")";
            log.warn("[PackDownloader] {} for {}", note, url);
            if (progress != null) progress.accept(note);
            try {
                Thread.sleep(wait.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Download interrupted", e);
            }
        }
        throw last != null ? last : new IOException("Download failed: " + url);
    }

    /** Transient server-side conditions. A 404 is an answer, not a hiccup. */
    static boolean worthRetrying(int statusCode) {
        return statusCode >= 500 || statusCode == 408 || statusCode == 429;
    }

    private static String detectExtension(String url) {
        var path = url.contains("?") ? url.substring(0, url.indexOf("?")) : url;
        if (path.endsWith(".tar.gz")) return ".tar.gz";
        if (path.endsWith(".tgz")) return ".tgz";
        if (path.endsWith(".tar.bz2")) return ".tar.bz2";
        if (path.endsWith(".json.bz2")) return ".json.bz2";
        if (path.endsWith(".bz2")) return ".bz2";
        if (path.endsWith(".7z")) return ".7z";
        if (path.endsWith(".zip")) return ".zip";
        if (path.endsWith(".jsonl.gz")) return ".jsonl.gz";
        if (path.endsWith(".gz")) return ".gz";
        if (path.endsWith(".jsonl")) return ".jsonl";
        if (path.endsWith(".json")) return ".json";
        if (path.endsWith(".parquet")) return ".parquet";
        return ".download";
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)) + " MB";
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
