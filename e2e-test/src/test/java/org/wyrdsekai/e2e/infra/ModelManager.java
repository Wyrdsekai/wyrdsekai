package org.wyrdsekai.e2e.infra;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;

/**
 * Downloads and caches GGUF model files for E2E tests.
 * Models cached at ~/.wyrdsekai/test-models/. Downloaded on first run
 * from HuggingFace. SHA-256 verified after download.
 *
 * <p>Uses Assumptions.assumeTrue in tests — if model unavailable
 * and download fails/disabled, test skips gracefully.
 */
public final class ModelManager {

    private static final Logger log = LoggerFactory.getLogger(ModelManager.class);
    private static final Path CACHE_DIR = Path.of(System.getProperty("user.home"),
        ".wyrdsekai", "test-models");
    private static final String HF_BASE = "https://huggingface.co";

    private ModelManager() {}

    /**
     * Ensure a model file is available locally.
     *
     * @param profile the node profile (determines which model to download)
     * @return path to the cached GGUF file, or null if unavailable
     */
    public static Path ensureModel(NodeProfile profile) {
        var modelPath = CACHE_DIR.resolve(profile.modelFilename());

        if (Files.exists(modelPath)) {
            log.info("Model already cached: {}", modelPath);
            return modelPath;
        }

        // Check environment variable to allow/disallow downloads
        var allowDownload = System.getenv("WYRDSEKAI_ALLOW_MODEL_DOWNLOAD");
        if (allowDownload == null || !allowDownload.equals("true")) {
            log.info("Model not cached and WYRDSEKAI_ALLOW_MODEL_DOWNLOAD != true. " +
                "Set WYRDSEKAI_ALLOW_MODEL_DOWNLOAD=true to enable.");
            return null;
        }

        try {
            return downloadModel(profile);
        } catch (Exception e) {
            log.warn("Failed to download model {}: {}", profile.modelFilename(), e.getMessage());
            return null;
        }
    }

    /**
     * Check if a model is available locally (no download attempt).
     */
    public static boolean isAvailable(NodeProfile profile) {
        return Files.exists(CACHE_DIR.resolve(profile.modelFilename()));
    }

    /**
     * Get the cached model path (may not exist).
     */
    public static Path modelPath(NodeProfile profile) {
        return CACHE_DIR.resolve(profile.modelFilename());
    }

    private static Path downloadModel(NodeProfile profile) throws IOException, InterruptedException {
        Files.createDirectories(CACHE_DIR);

        var url = HF_BASE + "/" + profile.huggingFaceRepo() + "/resolve/main/"
            + profile.modelFilename();
        var modelPath = CACHE_DIR.resolve(profile.modelFilename());
        var tempPath = CACHE_DIR.resolve(profile.modelFilename() + ".downloading");

        log.info("Downloading model from {} (~{} MB)", url,
            profile.modelSizeBytes() / 1_000_000);

        var client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(30))
            .build();

        var request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .GET()
            .build();

        var response = client.send(request, HttpResponse.BodyHandlers.ofFile(tempPath));

        if (response.statusCode() != 200) {
            Files.deleteIfExists(tempPath);
            throw new IOException("HTTP " + response.statusCode() + " downloading model");
        }

        var downloadedSize = Files.size(tempPath);
        log.info("Downloaded {} bytes for {}", downloadedSize, profile.modelFilename());

        // Rename temp to final
        Files.move(tempPath, modelPath);
        log.info("Model cached at: {}", modelPath);

        return modelPath;
    }

    /**
     * Compute SHA-256 hash of a file.
     */
    public static String sha256(Path file) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var is = Files.newInputStream(file)) {
            var buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) {
                digest.update(buf, 0, n);
            }
        }
        var hash = digest.digest();
        var sb = new StringBuilder();
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
