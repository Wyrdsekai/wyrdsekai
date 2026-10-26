package org.wyrdsekai.core.vault;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.body.BodyMap;
import org.wyrdsekai.core.persistence.SchemaInitializer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Each vault copy stores only the database pages that changed since the last one. Before, every
 * copy of the household node's half-gigabyte database stored about 510 MB again: the copy was a
 * rebuild that renumbered its pages, and the vault cut it into 1-8 MB pieces that any scattered
 * change touched (2026-10-26).
 */
class ACopyStoresOnlyWhatChangedTest {

    @TempDir Path dir;

    @AfterEach
    void tearDown() {
        Vault.resetForTests();
        BodyMap.resetForTests();
    }

    @Test
    void a_second_copy_of_a_barely_changed_database_stores_a_sliver_of_it() throws Exception {
        var data = dir.resolve("data");
        Files.createDirectories(data);
        var jdbc = SchemaInitializer.initialize(data.resolve("world.db"));
        Files.writeString(data.resolve("node-identity.json"), "{\"nodeId\":\"185b2756-0000-4000-8000-000000000000\"}");
        var big = "m".repeat(150_000);   // rows in overflow pages, like a soul manifest
        try (var conn = DriverManager.getConnection(jdbc); var st = conn.createStatement()) {
            st.execute("CREATE TABLE manifests (id INTEGER PRIMARY KEY, body TEXT)");
            st.execute("CREATE TABLE events (id INTEGER PRIMARY KEY, line TEXT)");
            conn.setAutoCommit(false);
            for (int i = 0; i < 120; i++) st.execute("INSERT INTO manifests (body) VALUES ('" + big + i + "')");
            for (int i = 0; i < 20_000; i++) st.execute("INSERT INTO events (line) VALUES ('event " + i + "')");
            conn.commit();
        }
        long dbSize = Files.size(data.resolve("world.db"));
        var vault = new Vault(data, dir.resolve("vault"));

        vault.snapshot("first", false).orElseThrow();
        long afterFirst = storeBytes(vault.dir());

        try (var conn = DriverManager.getConnection(jdbc); var st = conn.createStatement()) {
            st.execute("INSERT INTO manifests (body) VALUES ('" + big + "the night')");   // one new version
            for (int i = 0; i < 30; i++) st.execute("UPDATE events SET line = 'seen' WHERE id = " + (i * 600 + 1));   // scattered
        }
        vault.snapshot("second", false).orElseThrow();
        long added = storeBytes(vault.dir()) - afterFirst;

        assertTrue(dbSize > 15_000_000, "the database is big enough to tell: " + dbSize);
        assertTrue(added < dbSize / 10,
            "the second copy stored " + added + " bytes of a " + dbSize + "-byte database; it should store what changed");
    }

    private static long storeBytes(Path vaultDir) throws IOException {
        try (Stream<Path> s = Files.walk(vaultDir.resolve("chunks"))) {
            return s.filter(Files::isRegularFile).mapToLong(p -> {
                try { return Files.size(p); } catch (IOException e) { return 0L; }
            }).sum();
        }
    }
}
