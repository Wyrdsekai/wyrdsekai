package org.wyrdsekai.core.library;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live integration test: download a small StackExchange dump,
 * extract 7z, convert Posts.xml to JSONL, verify chunks.
 *
 * <p>Uses pets.stackexchange.com (smallest at ~26MB). Tagged {@code live-network}
 * and skipped via {@link Assumptions#assumeTrue} when archive.org is unreachable
 * (offline runs, rate-limited CI, etc.) — a dependency on the public internet
 * shouldn't break the core suite.</p>
 */
@Tag("live-network")
class StackExchangeInstallTest {

    private static final String DUMP_URL =
        "https://archive.org/download/stackexchange/pets.stackexchange.com.7z";

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void download_extract_convert_stackexchange() throws Exception {
        // Skip gracefully if archive.org is unreachable — this test is a
        // live integration check, not a correctness regression gate. Mirrors
        // the pattern used by EmbeddingServiceTest / SpeechToTextServiceTest.
        Assumptions.assumeTrue(archiveOrgReachable(),
            "archive.org unreachable — skipping live-network StackExchange pack test");

        var tempDir = Files.createTempDirectory("se-live-test-");
        var packDir = tempDir.resolve("pets");
        Files.createDirectories(packDir.resolve("chunks"));

        // Step 1: Download
        //
        // A HEAD probe says the host is up; it does not say the GET will work. On
        // 2026-08-21 archive.org answered the probe and then returned HTTP 500 for the
        // body, so this test FAILED the whole suite for a third party's outage. A gate
        // that goes red for something nobody here can fix is a gate people learn to
        // ignore — and "red, but it's pre-existing" is precisely how a real regression
        // gets carried.
        //
        // So: failing to OBTAIN the input aborts (skips); failing to CONVERT it once
        // obtained is a real failure and still fails. Those are different facts and
        // deserve different outcomes.
        System.out.println("Downloading pets.stackexchange.com.7z...");
        try {
            PackDownloader.download(
                DUMP_URL,
                packDir, System.out::println);
        } catch (IOException e) {
            Assumptions.abort(
                "archive.org could not serve the dump (" + e.getMessage() + ") — "
                    + "skipping: this test cannot run without its input, and an upstream "
                    + "outage is not a regression in this repository");
        }

        // Step 2: Check extraction
        System.out.println("Files after download:");
        try (var walk = Files.walk(packDir)) {
            walk.filter(Files::isRegularFile).forEach(f ->
                System.out.println("  " + packDir.relativize(f) + " (" + f.toFile().length() / 1024 + " KB)"));
        }

        // Should have Posts.xml after 7z extraction
        var postsXml = findFile(packDir, "Posts.xml");
        assertNotNull(postsXml, "Should have Posts.xml after 7z extraction");
        assertTrue(Files.size(postsXml) > 1000, "Posts.xml should have content");

        // Step 3: Convert Posts.xml to JSONL
        var outputJsonl = packDir.resolve("chunks/posts.jsonl");
        int count = FormatConverters.convertStackExchangeXml(postsXml, outputJsonl, "pets-test", null);

        System.out.println("Converted " + count + " Q&A pairs");
        assertTrue(count > 100, "Should have more than 100 Q&A pairs from pets.stackexchange");
        assertTrue(Files.exists(outputJsonl));
        assertTrue(Files.size(outputJsonl) > 1000);

        // Step 4: Verify JSONL content
        var firstLine = Files.readAllLines(outputJsonl).getFirst();
        assertTrue(firstLine.contains("pets-test:"), "Chunk ID should use pack name");
        assertTrue(firstLine.contains("content"), "Should have content field");

        System.out.println("SUCCESS: " + count + " Q&A pairs from pets.stackexchange.com");
    }

    private Path findFile(Path dir, String name) throws Exception {
        try (var walk = Files.walk(dir)) {
            return walk.filter(f -> f.getFileName().toString().equals(name)).findFirst().orElse(null);
        }
    }

    /**
     * Quick HEAD request to the dump URL with a 5-second timeout. If the
     * archive responds (any 2xx/3xx), run the test; otherwise skip.
     * This catches: offline runs, DNS failures, rate-limit 503s, archive
     * maintenance windows, and any transient upstream flakes.
     */
    private static boolean archiveOrgReachable() {
        try {
            var conn = (HttpURLConnection) URI.create(DUMP_URL).toURL().openConnection();
            conn.setRequestMethod("HEAD");
            conn.setConnectTimeout(5_000);
            conn.setReadTimeout(5_000);
            conn.setInstanceFollowRedirects(true);
            var code = conn.getResponseCode();
            conn.disconnect();
            return code >= 200 && code < 400;
        } catch (Exception e) {
            return false;
        }
    }
}
