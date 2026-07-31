package org.wyrdsekai.core.library;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code wyrd library bundle} — network-free coverage: usage/arg handling and
 * the chunks/-present resume skip in {@link KnowledgePackRegistry#downloadOnly}.
 * The actual download path reuses the same resolveUrls + PackDownloader +
 * convertIfNeeded machinery exercised by the install tests.
 */
class LibraryBundleCliTest {

    @TempDir
    Path tmp;

    private record Run(int code, String out, String err) {}

    private Run run(String... args) {
        var outBuf = new ByteArrayOutputStream();
        var errBuf = new ByteArrayOutputStream();
        int code = new LibraryBundleCli(
            new PrintStream(outBuf, true, StandardCharsets.UTF_8),
            new PrintStream(errBuf, true, StandardCharsets.UTF_8)).run(args);
        return new Run(code,
            outBuf.toString(StandardCharsets.UTF_8),
            errBuf.toString(StandardCharsets.UTF_8));
    }

    @Test
    void helpExitsZero() {
        var r = run("--help");
        assertEquals(0, r.code());
        assertTrue(r.out().contains("--from-dir"), r.out());
    }

    @Test
    void unknownPackFails() {
        var r = run("--packs", "no-such-pack-xyz", "--dest", tmp.toString());
        assertEquals(1, r.code());
        assertTrue(r.err().contains("Unknown pack"), r.err());
    }

    @Test
    void unknownFlagFails() {
        assertEquals(2, run("--bogus").code());
    }

    @Test
    void alreadyBundledPackIsSkippedWithoutNetwork() throws Exception {
        // Pre-seed the bundle dir for a real registry pack — downloadOnly must
        // see the non-empty chunks/ and return without touching the network.
        var packName = KnowledgePackRegistry.listAvailable().get(0).name();
        Files.createDirectories(tmp.resolve(packName).resolve("chunks"));
        Files.writeString(tmp.resolve(packName).resolve("chunks").resolve("x.jsonl"), "{}");

        var r = run("--packs", packName, "--dest", tmp.toString());
        assertEquals(0, r.code(), r.err());
        assertTrue(r.out().contains("already in bundle"), r.out());
        assertTrue(r.out().contains("--from-dir"), "should print offline install hint:\n" + r.out());
    }
}
