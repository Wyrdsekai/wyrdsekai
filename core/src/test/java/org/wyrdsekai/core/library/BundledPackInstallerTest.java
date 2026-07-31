package org.wyrdsekai.core.library;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.search.WyrdLuceneStore;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Installer-payload dictionary install — the
 * boot path that indexes {@code <installRoot>/share/library-bundle/<pack>/}
 * on first start. Fixture layout mirrors what {@code wyrd library bundle}
 * produces and {@code packaging/build-dist.sh} stages.
 */
class BundledPackInstallerTest {

    @TempDir
    Path tmp;

    private WyrdLuceneStore store;
    private Path bundleDir;

    @BeforeEach
    void setUp() throws Exception {
        store = new WyrdLuceneStore(tmp.resolve("lucene"), 384);
        bundleDir = tmp.resolve("library-bundle");
        Files.createDirectories(bundleDir);
    }

    @AfterEach
    void tearDown() throws Exception {
        store.close();
    }

    private void writeFixturePack(String name, String... chunkContents) throws Exception {
        var packDir = bundleDir.resolve(name);
        Files.createDirectories(packDir.resolve("chunks"));
        Files.writeString(packDir.resolve("pack.json"),
            "{\"name\":\"" + name + "\",\"title\":\"" + name + "\"}");
        var sb = new StringBuilder();
        for (int i = 0; i < chunkContents.length; i++) {
            sb.append("{\"id\":\"").append(name).append(":").append(i)
              .append("\",\"packName\":\"").append(name)
              .append("\",\"title\":\"entry ").append(i)
              .append("\",\"content\":\"").append(chunkContents[i])
              .append("\",\"source\":\"test\",\"license\":\"test\"}\n");
        }
        Files.writeString(packDir.resolve("chunks").resolve("data.jsonl"), sb.toString());
    }

    @Test
    void indexesBundledPackAndBecomesSearchable() throws Exception {
        writeFixturePack("test-dict", "gato — cat; jack", "perro — dog");

        int installed = BundledPackInstaller.installBundledSync(store, bundleDir);

        assertEquals(1, installed);
        var hits = store.searchKnowledgeText("gato", 5);
        assertFalse(hits.isEmpty(), "bundled chunk should be searchable after boot install");
    }

    @Test
    void secondPassIsIdempotentNoop() throws Exception {
        writeFixturePack("test-dict", "gato — cat");
        assertEquals(1, BundledPackInstaller.installBundledSync(store, bundleDir));
        assertEquals(0, BundledPackInstaller.installBundledSync(store, bundleDir),
            "already-installed pack must be skipped (packSize > 0)");
    }

    @Test
    void ignoresDirsWithoutPreparedChunks() throws Exception {
        // Dir without chunks/, and dir with only a zero-byte chunk file.
        Files.createDirectories(bundleDir.resolve("not-a-pack"));
        Files.createDirectories(bundleDir.resolve("empty-pack").resolve("chunks"));
        Files.createFile(bundleDir.resolve("empty-pack").resolve("chunks").resolve("data.jsonl"));

        assertEquals(0, BundledPackInstaller.installBundledSync(store, bundleDir));
        assertTrue(BundledPackInstaller.findBundledPacks(bundleDir).isEmpty());
    }

    @Test
    void oneBadPackDoesNotSinkTheRest() throws Exception {
        // Malformed JSONL in one pack; a good pack alongside it.
        var bad = bundleDir.resolve("bad-pack");
        Files.createDirectories(bad.resolve("chunks"));
        Files.writeString(bad.resolve("chunks").resolve("data.jsonl"), "this is not json\n");
        writeFixturePack("good-pack", "tsuru — crane");

        // Must not throw; the good pack must land regardless of the bad one's fate.
        BundledPackInstaller.installBundledSync(store, bundleDir);
        assertFalse(store.searchKnowledgeText("tsuru", 5).isEmpty());
    }
}
