package org.wyrdsekai.core.vault;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.body.BodyMap;
import org.wyrdsekai.core.persistence.SchemaInitializer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The vault: the self is copied as content-addressed chunks from a consistent copy of the
 * record; an unchanged file adds no chunks; a copy rebuilds byte for byte and is verified; the
 * tiers keep what the plan says; the drill boots the newest copy and says what is in it;
 * classification is explicit so what is not vaulted is visible.
 */
class TheVaultKeepsTheSelfTest {

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
        Files.createDirectories(data.resolve("search"));
        Files.writeString(data.resolve("search").resolve("index.bin"), "derivable");
        Files.createDirectories(data.resolve("models"));
        Files.writeString(data.resolve("models").resolve("big.gguf"), "replaceable");
        Files.writeString(data.resolve("mystery.bin"), "nobody classified me");
        return data;
    }

    @Test
    @DisplayName("the knowledge and Study indexes are carried, an unchanged segment is reused unread, the drill verifies them without writing")
    void dearIndexes(@TempDir Path dir) throws Exception {
        var data = household(dir);
        var seg = data.resolve("search/search/knowledge");
        Files.createDirectories(seg);
        var bytes = new byte[3 * 1024 * 1024];
        new java.util.Random(7).nextBytes(bytes);
        Files.write(seg.resolve("_0.cfs"), bytes);
        Files.writeString(seg.resolve("segments_1"), "commit");
        Files.writeString(seg.resolve("write.lock"), "");
        var vault = new Vault(data, dir.resolve("vault"));
        var m1 = vault.snapshot("first", false).orElseThrow();
        var paths = m1.files().stream().map(Vault.Entry::path).toList();
        assertTrue(paths.contains("search/search/knowledge/_0.cfs") && paths.contains("search/search/knowledge/segments_1"), paths.toString());
        assertFalse(paths.contains("search/search/knowledge/write.lock"), "the lock is the server's, not the index's");
        assertEquals(List.of("search/search/knowledge"), m1.classes().get("dear"));
        long chunksBefore = countChunks(vault.dir());
        var m2 = vault.snapshot("second", false).orElseThrow();
        assertEquals(chunksBefore, countChunks(vault.dir()), "nothing changed, nothing written");
        var e1 = m1.files().stream().filter(e -> e.path().endsWith("_0.cfs")).findFirst().orElseThrow();
        var e2 = m2.files().stream().filter(e -> e.path().endsWith("_0.cfs")).findFirst().orElseThrow();
        assertEquals(e1, e2, "the unchanged segment is the previous copy's entry: size, mtime and chunks");
        Files.write(seg.resolve("_1.cfs"), new byte[1024 * 1024 + 17]);
        var m3 = vault.snapshot("third", false).orElseThrow();
        assertTrue(countChunks(vault.dir()) > chunksBefore, "a new segment is stored");
        assertTrue(m3.files().stream().anyMatch(e -> e.path().endsWith("_1.cfs")));
        var drill = dir.resolve("drill");
        assertTrue(vault.restoreTo(m3, drill, Vault.DEAR_DIRS).isEmpty(), "the drill verifies the index without writing it");
        assertFalse(Files.exists(drill.resolve("search/search/knowledge/_0.cfs")), "verified, not materialised");
        assertTrue(Files.exists(drill.resolve("souls/mia.did")), "the rest is rebuilt as before");
        var whole = dir.resolve("whole");
        assertTrue(vault.restoreTo(m3, whole).isEmpty(), "a real restore brings the index back");
        assertEquals(bytes.length, Files.size(whole.resolve("search/search/knowledge/_0.cfs")));
    }

    @Test
    @DisplayName("a copy holds the self, dedupes unchanged chunks, and rebuilds whole")
    void copyAndRebuild(@TempDir Path dir) throws Exception {
        var data = household(dir);
        var vault = new Vault(data, dir.resolve("vault"));
        var m1 = vault.snapshot("first", false).orElseThrow();
        var paths = m1.files().stream().map(Vault.Entry::path).toList();
        assertTrue(paths.contains("world.db") && paths.contains("node-identity.json") && paths.contains("souls/mia.did"), paths.toString());
        assertFalse(paths.stream().anyMatch(p -> p.startsWith("search/") || p.startsWith("models/")), "derivable and replaceable stay out");
        assertEquals(List.of("search"), m1.classes().get("derivable"));
        assertEquals(List.of("models"), m1.classes().get("replaceable"));
        assertEquals(List.of("mystery.bin"), m1.classes().get("unclassified"), "what nobody classified is named, not hidden");

        long chunksBefore = countChunks(vault.dir());
        var m2 = vault.snapshot("second", false).orElseThrow();
        assertEquals(chunksBefore, countChunks(vault.dir()), "nothing changed, nothing written");
        assertEquals(m1.files().stream().filter(e -> e.path().equals("souls/mia.did")).findFirst().orElseThrow().chunks(),
            m2.files().stream().filter(e -> e.path().equals("souls/mia.did")).findFirst().orElseThrow().chunks());

        var out = dir.resolve("out");
        assertTrue(vault.restoreTo(m2, out).isEmpty(), "rebuilt whole");
        assertEquals("did:key:z6MkMia", Files.readString(out.resolve("souls/mia.did")));
        try (var conn = DriverManager.getConnection("jdbc:sqlite:" + out.resolve("world.db"));
             var st = conn.createStatement(); var rs = st.executeQuery("SELECT username FROM users")) {
            assertTrue(rs.next());
            assertEquals("kaz", rs.getString(1), "the record came back with the household in it");
        }
    }

    @Test
    @DisplayName("an inserted page changes one chunk, not every chunk after it")
    void contentDefinedChunking(@TempDir Path dir) throws Exception {
        var data = household(dir);
        // A 30 MB file of deterministic noise stands in for a large record.
        var big = data.resolve("souls").resolve("big.bin");
        var rnd = new java.util.Random(7);
        var bytes = new byte[30 * 1024 * 1024];
        rnd.nextBytes(bytes);
        Files.write(big, bytes);
        var vault = new Vault(data, dir.resolve("vault"));
        var m1 = vault.snapshot("first", false).orElseThrow();
        var before = m1.files().stream().filter(e -> e.path().equals("souls/big.bin")).findFirst().orElseThrow().chunks();
        assertTrue(before.size() >= 4 && before.size() <= 20, "about 4 MiB apart: " + before.size());
        long stored = countChunks(vault.dir());

        // Insert one 4 KiB page a third of the way in: a fixed-offset cutter would rewrite
        // every chunk after it.
        var page = new byte[4096];
        rnd.nextBytes(page);
        var shifted = new byte[bytes.length + page.length];
        System.arraycopy(bytes, 0, shifted, 0, 10_000_000);
        System.arraycopy(page, 0, shifted, 10_000_000, page.length);
        System.arraycopy(bytes, 10_000_000, shifted, 10_000_000 + page.length, bytes.length - 10_000_000);
        Files.write(big, shifted);
        var m2 = vault.snapshot("second", false).orElseThrow();
        var after = m2.files().stream().filter(e -> e.path().equals("souls/big.bin")).findFirst().orElseThrow().chunks();
        long written = countChunks(vault.dir()) - stored;
        assertTrue(written <= 3, "only the chunks around the insertion are new: " + written);
        var shared = new java.util.HashSet<>(before); shared.retainAll(after);
        assertTrue(shared.size() >= before.size() - 3, "the rest is shared: " + shared.size() + " of " + before.size());
        assertTrue(vault.restoreTo(m2, dir.resolve("out")).isEmpty());
        assertTrue(java.util.Arrays.equals(shifted, Files.readAllBytes(dir.resolve("out").resolve("souls/big.bin"))));
    }

    @Test
    @DisplayName("the trail and what was rotated off it are copied once each, and an append stores only the tail")
    void theTrail(@TempDir Path dir) throws Exception {
        var data = household(dir);
        var sub = data.resolve("data");
        Files.createDirectories(sub.resolve("story"));
        // About 10 MB of day-by-day record, so it spans more than one chunk.
        var trail = sub.resolve("agent-activity.jsonl");
        var sb = new StringBuilder();
        for (int i = 0; sb.length() < 10 * 1024 * 1024; i++) {
            sb.append("{\"v\":1,\"ts\":\"").append(i).append("\",\"type\":\"speak\",\"agent\":\"mia\",\"text\":\"line ")
              .append(i * 7919L).append("\"}\n");
        }
        Files.writeString(trail, sb);
        Files.writeString(sub.resolve("drive-trace.jsonl"), "{\"kind\":\"event\"}\n");
        Files.writeString(sub.resolve("drive-trace.jsonl.1"), "{\"kind\":\"rotated\"}\n");
        Files.writeString(sub.resolve("agent-activity.jsonl.orig"), "a hand-made copy, not a rotation\n");
        var vault = new Vault(data, dir.resolve("vault"));
        var m1 = vault.snapshot("first", false).orElseThrow();
        var trails = m1.files().stream().map(Vault.Entry::path).filter(p -> p.startsWith("data/")).sorted().toList();
        assertEquals(List.of("data/agent-activity.jsonl", "data/drive-trace.jsonl", "data/drive-trace.jsonl.1"), trails,
            "each trail file once, and no hand-made copy");
        var chunks = m1.files().stream().filter(e -> e.path().equals("data/agent-activity.jsonl")).findFirst().orElseThrow().chunks();
        assertTrue(chunks.size() >= 2, "more than one chunk: " + chunks.size());
        assertEquals(List.of("mystery.bin"), m1.classes().get("unclassified"));

        long stored = countChunks(vault.dir());
        Files.writeString(trail, "{\"v\":1,\"type\":\"speak\",\"agent\":\"mia\",\"text\":\"one more\"}\n", StandardOpenOption.APPEND);
        var m2 = vault.snapshot("second", false).orElseThrow();
        long written = countChunks(vault.dir()) - stored;
        assertTrue(written <= 2, "an append stores the tail, not the trail again: " + written);

        var out = dir.resolve("out");
        assertTrue(vault.restoreTo(m2, out).isEmpty(), "rebuilt whole");
        assertEquals(Files.readString(trail), Files.readString(out.resolve("data/agent-activity.jsonl")));
        assertEquals("{\"kind\":\"rotated\"}\n", Files.readString(out.resolve("data/drive-trace.jsonl.1")));
    }

    @Test
    @DisplayName("the drill boots the newest copy and writes its verdict into the manifest and a mark")
    void theDrill(@TempDir Path dir) throws Exception {
        var data = household(dir);
        BodyMap.inMemory();
        var vault = new Vault(data, dir.resolve("vault"));
        vault.snapshot("first", false).orElseThrow();
        var d = vault.drill().orElseThrow();
        assertTrue(d.ok(), d.detail());
        assertTrue(d.detail().contains("integrity ok") && d.detail().contains("1 people") && d.detail().contains("identity present"), d.detail());
        assertTrue(vault.latest().orElseThrow().drill().ok(), "the verdict is on the manifest");
        assertTrue(BodyMap.get().recentMarks(1).get(0).text().startsWith("The vault was tested: the newest copy comes up"));
        assertFalse(Files.exists(vault.dir().resolve(".drill")), "scratch is cleaned");
        assertFalse(vault.drillDue(Duration.ofDays(30)));
    }

    @Test
    @DisplayName("a chunk that was tampered with fails the rebuild instead of passing quietly")
    void tamperedChunkFails(@TempDir Path dir) throws Exception {
        var data = household(dir);
        var vault = new Vault(data, dir.resolve("vault"));
        var m = vault.snapshot("first", false).orElseThrow();
        var sha = m.files().stream().filter(e -> e.path().equals("souls/mia.did")).findFirst().orElseThrow().chunks().get(0);
        Files.writeString(vault.dir().resolve("chunks").resolve(sha.substring(0, 2)).resolve(sha), "did:key:z6MkSomeoneElse");
        var failed = vault.restoreTo(m, dir.resolve("out"));
        assertEquals(1, failed.size());
        assertTrue(failed.get(0).startsWith("souls/mia.did"), failed.toString());
    }

    @Test
    @DisplayName("the tiers: two hours of everything, a day of hours, a week of days, five weeks of weeks, a year of months, kept copies forever")
    void retentionTiers(@TempDir Path dir) throws Exception {
        var data = household(dir);
        var vault = new Vault(data, dir.resolve("vault"));
        var now = Instant.parse("2026-09-16T12:00:00Z");
        // Write manifests by hand at chosen ages, all sharing one real copy's chunks.
        var real = vault.snapshot("seed", false).orElseThrow();
        var manifests = vault.dir().resolve("manifests");
        // The real manifest is sealed; the hand-written ones below are sealed with the same key
        // (a plain file in a sealed store is refused).
        var cipher = VaultCipher.load(data.resolve(Vault.KEY_FILE));
        var json = new String(cipher.open(Files.readAllBytes(manifests.resolve(real.id() + ".json"))), java.nio.charset.StandardCharsets.UTF_8);
        record Fake(String id, Instant at, boolean keep) {}
        var fakes = List.of(
            new Fake("m30", now.minus(Duration.ofMinutes(30)), false),
            new Fake("m45", now.minus(Duration.ofMinutes(45)), false),
            new Fake("h3a", now.minus(Duration.ofHours(3)).minus(Duration.ofMinutes(10)), false),   // 08:50
            new Fake("h3b", now.minus(Duration.ofHours(3)).minus(Duration.ofMinutes(25)), false),   // 08:35, same hour
            new Fake("h20", now.minus(Duration.ofHours(20)), false),
            new Fake("d3a", now.minus(Duration.ofDays(3)), false),
            new Fake("d3b", now.minus(Duration.ofDays(3)).minus(Duration.ofHours(2)), false),
            new Fake("w3", now.minus(Duration.ofDays(20)), false),
            new Fake("w3b", now.minus(Duration.ofDays(21)), false),
            new Fake("m6", now.minus(Duration.ofDays(180)), false),
            new Fake("old", now.minus(Duration.ofDays(500)), false),
            new Fake("kept", now.minus(Duration.ofDays(700)), true));
        for (var f : fakes) {
            var text = json.replace("\"id\" : \"" + real.id() + "\"", "\"id\" : \"" + f.id() + "\"")
                .replaceFirst("\"at\" : \"[^\"]*\"", "\"at\" : \"" + f.at() + "\"")
                .replaceFirst("\"keep\" : (true|false)", "\"keep\" : " + f.keep());
            Files.write(manifests.resolve(f.id() + ".json"),
                cipher.seal(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
        Files.delete(manifests.resolve(real.id() + ".json"));
        int removed = vault.prune(now);
        var left = vault.list().stream().map(Vault.Manifest::id).sorted().toList();
        assertEquals(List.of("d3a", "h20", "h3a", "kept", "m30", "m45", "m6", "w3"), left);
        assertEquals(4, removed);
        assertTrue(countChunks(vault.dir()) > 0, "chunks the kept copies reference survive the sweep");
    }

    private static long countChunks(Path vaultDir) throws Exception {
        var chunks = vaultDir.resolve("chunks");
        if (!Files.isDirectory(chunks)) return 0;
        try (var s = Files.walk(chunks)) { return s.filter(Files::isRegularFile).count(); }
    }
}
