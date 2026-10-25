package org.wyrdsekai.core.vault;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.body.BodyMap;
import org.wyrdsekai.core.persistence.SchemaInitializer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The household node's vault held 163 copies and 208 GB, with two weeks of disk left. 120 of the
 * copies were "kept": taken before a stop or an update and never expired, each pinning the 127 GB
 * search index exactly as it stood that minute. Her record in them is a few gigabytes
 * (2026-10-25). The vault is dense and forever about her, thin about what can be rebuilt.
 */
class TheVaultKeepsHerForeverAndTheIndexForAWhileTest {

    @AfterEach
    void tearDown() {
        Vault.resetForTests();
        BodyMap.resetForTests();
    }

    private static Path household(Path dir) throws Exception {
        var data = dir.resolve("data");
        Files.createDirectories(data);
        var jdbc = SchemaInitializer.initialize(data.resolve("world.db"));
        try (var conn = DriverManager.getConnection(jdbc); var st = conn.createStatement()) {
            st.execute("INSERT INTO users(id, username, password_hash, display_name, role, created_at) VALUES('u1','kaz','x','Kazuo','steward',0)");
        }
        Files.writeString(data.resolve("node-identity.json"),
            "{\"nodeId\":\"185b2756-0000-4000-8000-000000000000\",\"publicKey\":\"MCowBQYDK2VwAyEA…\",\"encryptedPrivateKey\":\"…\"}");
        Files.createDirectories(data.resolve("souls"));
        Files.writeString(data.resolve("souls").resolve("mia.did"), "did:key:z6MkMia");
        return data;
    }

    private static Path segment(Path data, String name, long seed) throws Exception {
        var seg = data.resolve("search/search/knowledge");
        Files.createDirectories(seg);
        var bytes = new byte[2 * 1024 * 1024];
        new Random(seed).nextBytes(bytes);
        var f = seg.resolve(name);
        Files.write(f, bytes);
        return f;
    }

    /** Rewrite a sealed manifest's id, age and keep flag, as the retention test does. */
    private static void ageCopy(Vault vault, Path data, String id, String newId, Instant at, boolean keep) throws Exception {
        var manifests = vault.dir().resolve("manifests");
        var cipher = VaultCipher.load(data.resolve(Vault.KEY_FILE));
        var json = new String(cipher.open(Files.readAllBytes(manifests.resolve(id + ".json"))), StandardCharsets.UTF_8);
        var text = json.replace("\"id\" : \"" + id + "\"", "\"id\" : \"" + newId + "\"")
            .replaceFirst("\"at\" : \"[^\"]*\"", "\"at\" : \"" + at + "\"")
            .replaceFirst("\"keep\" : (true|false)", "\"keep\" : " + keep);
        Files.write(manifests.resolve(newId + ".json"), cipher.seal(text.getBytes(StandardCharsets.UTF_8)));
        for (var side : List.of(".json", ".head.json", ".chunks", ".dear")) Files.deleteIfExists(manifests.resolve(id + side));
    }

    private static long chunkFiles(Path vaultDir) throws Exception {
        var chunks = vaultDir.resolve("chunks");
        if (!Files.isDirectory(chunks)) return 0;
        try (var s = Files.walk(chunks)) { return s.filter(Files::isRegularFile).count(); }
    }

    @Test
    @DisplayName("a kept copy outside the tiers keeps her record forever and lets its index go; a restore from it brings her back")
    void keptCopiesKeepTheRecordAndReleaseTheIndex(@TempDir Path dir) throws Exception {
        var data = household(dir);
        var vault = new Vault(data, dir.resolve("vault"));
        var now = Instant.parse("2026-10-25T12:00:00Z");

        // Two "before an update" copies in one old month, with the index as it stood then. The
        // tiers keep the newest of a month for a year: that one keeps its index, the other
        // gives it up and keeps her record.
        segment(data, "_0.cfs", 1);
        var older = vault.snapshot("before an update", true).orElseThrow();
        ageCopy(vault, data, older.id(), "20260810-120000-keep", now.minus(Duration.ofDays(76)), true);
        var newerInMonth = vault.snapshot("before a stop", true).orElseThrow();
        ageCopy(vault, data, newerInMonth.id(), "20260825-120000-keep", now.minus(Duration.ofDays(61)), true);
        long withOldIndex = chunkFiles(vault.dir());

        // The index moved on: the old segment was merged away, a new one stands.
        Files.delete(data.resolve("search/search/knowledge/_0.cfs"));
        segment(data, "_1.cfs", 2);
        var fresh = vault.snapshot("continuous", false).orElseThrow();
        long afterFresh = chunkFiles(vault.dir());
        assertTrue(afterFresh > withOldIndex, "the new segment was stored");

        vault.prune(now);

        assertTrue(vault.isThin("20260810-120000-keep"), "the older kept copy holds the record only now");
        assertFalse(vault.isThin("20260825-120000-keep"), "the month's newest copy keeps its index by the tiers");
        assertFalse(vault.isThin(fresh.id()), "the newest copy is whole");
        assertEquals(afterFresh, chunkFiles(vault.dir()), "nothing swept: the old segment is still held by the month's copy");
        var heads = vault.heads().stream().map(Vault.Head::id).toList();
        assertTrue(heads.contains("20260810-120000-keep") && heads.contains("20260825-120000-keep"), "kept copies themselves stay");

        // A year on, the month's copy is outside the tiers too: its index goes, the record stays.
        vault.prune(now.plus(Duration.ofDays(400)));
        assertTrue(vault.isThin("20260825-120000-keep"));
        assertTrue(chunkFiles(vault.dir()) < afterFresh, "the old segment's chunks were swept once nothing in the tiers held them");

        // Her record comes back from the thin copy; the index is not a failure, it is absent.
        var thin = vault.find("20260810-120000-keep").orElseThrow();
        var out = dir.resolve("restored");
        assertTrue(vault.restoreTo(thin, out).isEmpty(), "no file of the record failed");
        assertTrue(Files.exists(out.resolve("world.db")) && Files.exists(out.resolve("souls/mia.did")));
        assertFalse(Files.exists(out.resolve("search/search/knowledge/_0.cfs")), "the index is rebuilt on first start, not restored");

        var status = vault.status();
        assertEquals(2L, status.get("thinKept"));
    }

    @Test
    @DisplayName("a copy from before the dear sidecar keeps everything until the sidecar is written, a few copies per pass")
    void olderCopiesLearnTheirIndexChunksGradually(@TempDir Path dir) throws Exception {
        var data = household(dir);
        var vault = new Vault(data, dir.resolve("vault"));
        var now = Instant.parse("2026-10-25T12:00:00Z");
        segment(data, "_0.cfs", 3);
        var old = vault.snapshot("before a stop", true).orElseThrow();
        // Older than every tier (the monthly one reaches a year back): only the keep flag holds it.
        ageCopy(vault, data, old.id(), "20250601-090000-keep", now.minus(Duration.ofDays(511)), true);
        // ageCopy removed every sidecar: this is a 0.5.10 copy, head and chunk list included.
        long before = chunkFiles(vault.dir());
        Files.delete(data.resolve("search/search/knowledge/_0.cfs"));
        vault.snapshot("continuous", false).orElseThrow();
        vault.prune(now);
        // The first pass gives the old copy its sidecars (one whole read) and already decides.
        assertTrue(vault.isThin("20250601-090000-keep"));
        assertTrue(chunkFiles(vault.dir()) < before);
    }

    @Test
    @DisplayName("the coding helper's environments and caches in her home are refills, not copied")
    void toolEnvironmentsInAHomeAreNotCopied(@TempDir Path dir) throws Exception {
        var data = household(dir);
        var home = data.resolve("beings/59e56939/home");
        Files.createDirectories(home.resolve(".codezaiku/envs/abc/lib"));
        Files.writeString(home.resolve(".codezaiku/envs/abc/lib/big.so"), "refill");
        Files.createDirectories(home.resolve("notes"));
        Files.writeString(home.resolve("notes/first.md"), "hers");
        Files.createDirectories(home.resolve("project/.venv/lib"));
        Files.writeString(home.resolve("project/.venv/lib/x.py"), "refill");
        Files.writeString(home.resolve("project/main.py"), "hers");
        Files.writeString(data.resolve("beings/59e56939/did"), "did:key:z6MkRose");
        var vault = new Vault(data, dir.resolve("vault"));
        var m = vault.snapshot("first", false).orElseThrow();
        var paths = m.files().stream().map(Vault.Entry::path).toList();
        assertTrue(paths.contains("beings/59e56939/home/notes/first.md"), paths.toString());
        assertTrue(paths.contains("beings/59e56939/home/project/main.py"), paths.toString());
        assertFalse(paths.stream().anyMatch(p -> p.contains(".codezaiku/envs")), "environments are refilled by the helper");
        assertFalse(paths.stream().anyMatch(p -> p.contains("/.venv/")), "a virtual environment is a refill");
    }

    @Test
    @DisplayName("under the floor, older copies give up their index first; still under it, a pass copies the record alone; the map is told")
    void theFloor(@TempDir Path dir) throws Exception {
        var data = household(dir);
        BodyMap.inMemory();
        var vault = new Vault(data, dir.resolve("vault")).withFloor(10_000_000_000L);
        segment(data, "_0.cfs", 4);
        vault.freeSpace = () -> 50_000_000_000L;
        var whole = vault.snapshot("continuous", false).orElseThrow();
        assertTrue(whole.files().stream().anyMatch(e -> e.path().endsWith("_0.cfs")), "room enough: the index is copied");

        vault.freeSpace = () -> 5_000_000_000L;   // the disk filled up
        var thin = vault.snapshot("continuous", false).orElseThrow();
        assertFalse(thin.files().stream().anyMatch(e -> e.path().endsWith("_0.cfs")), "no room: this copy holds the record alone");
        assertTrue(thin.files().stream().anyMatch(e -> e.path().equals("world.db")), "the record is always copied");
        assertTrue(vault.isThin(thin.id()));
        assertTrue(BodyMap.get().recentMarks(3).stream().anyMatch(mk -> mk.text().startsWith("The vault's disk is nearly full")));
        var status = vault.status();
        assertEquals(10_000_000_000L, status.get("floorBytes"));
        assertEquals(5_000_000_000L, status.get("freeBytes"));
    }

    @Test
    @DisplayName("the record is drilled every week without the index; the full drill keeps its own month")
    void theRecordDrill(@TempDir Path dir) throws Exception {
        var data = household(dir);
        BodyMap.inMemory();
        var vault = new Vault(data, dir.resolve("vault"));
        segment(data, "_0.cfs", 5);
        vault.snapshot("first", false).orElseThrow();
        assertTrue(vault.recordDrillDue(Duration.ofDays(7)));
        var d = vault.recordDrill().orElseThrow();
        assertTrue(d.ok(), d.detail());
        assertTrue(d.detail().startsWith("record: ") && d.detail().contains("integrity ok"), d.detail());
        assertFalse(vault.recordDrillDue(Duration.ofDays(7)), "the week is answered");
        assertTrue(vault.drillDue(Duration.ofDays(30)), "the index has not been checked yet this month");
        vault.drill().orElseThrow();
        assertFalse(vault.drillDue(Duration.ofDays(30)));
    }

    @Test
    @DisplayName("the status page names a store left behind where the vault was moved off; the vault itself is never one")
    void theOrphanStore(@TempDir Path dir) throws Exception {
        var data = household(dir);
        var vault = new Vault(data, dir.resolve("elsewhere"));
        vault.snapshot("first", false).orElseThrow();
        assertTrue(vault.orphanStore().isEmpty(), "no old store yet");
        Files.createDirectories(data.resolve("vault-store/manifests"));
        Files.createDirectories(data.resolve("vault-store/chunks"));
        Files.writeString(data.resolve("vault-store/manifests/20260927-105250.json"), "sealed-bytes");
        var orphan = vault.orphanStore().orElseThrow();
        assertEquals(1L, orphan.get("copies"));
        assertNotNull(vault.status().get("orphanStore"));

        var inPlace = new Vault(data, data.resolve("vault-store"));
        assertTrue(inPlace.orphanStore().isEmpty(), "the vault in use is not an orphan");
        assertNull(inPlace.status().get("orphanStore"));
    }

    @Test
    @DisplayName("a day of size notes gives a growth rate and the days left above the floor")
    void daysLeft(@TempDir Path dir) throws Exception {
        var data = household(dir);
        var vault = new Vault(data, dir.resolve("vault")).withFloor(10_000_000_000L);
        vault.freeSpace = () -> 70_000_000_000L;
        long now = Instant.now().toEpochMilli(), day = Duration.ofDays(1).toMillis();
        Files.writeString(vault.dir().resolve("size.log"),
            (now - 2 * day) + " 100000000000 74000000000\n" + (now - day) + " 102000000000 72000000000\n" + now + " 104000000000 70000000000\n");
        assertEquals(2_000_000_000L, vault.growthPerDay());
        var status = vault.status();
        assertEquals(30L, status.get("daysLeft"));
    }
}
