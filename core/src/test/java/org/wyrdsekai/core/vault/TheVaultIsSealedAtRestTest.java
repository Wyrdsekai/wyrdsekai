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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A copy of the store taken offsite is unreadable without the key file, which lives beside
 * the data and never inside the store. The store remembers which key sealed it, so a wrong key
 * is refused before it can write, and files from before sealing are sealed on the next pass.
 */
class TheVaultIsSealedAtRestTest {

    private static final String MARKER = "PROFILE-TEXT-MARKER-7b3f";

    @AfterEach
    void tearDown() {
        Vault.resetForTests();
        BodyMap.resetForTests();
    }

    private static Path household(Path dir) throws Exception {
        var data = dir.resolve("data");
        Files.createDirectories(data);
        SchemaInitializer.initialize(data.resolve("world.db"));
        Files.writeString(data.resolve("node-identity.json"), "{\"nodeId\":\"n1\",\"publicKey\":\"pk\"}");
        Files.writeString(data.resolve("profile.toml"), "name = \"" + MARKER + "\"\n");
        return data;
    }

    private static List<Path> storeFiles(Path store) throws Exception {
        try (var walk = Files.walk(store)) {
            return new ArrayList<>(walk.filter(Files::isRegularFile)
                .filter(p -> !p.getFileName().toString().equals("key.id")).toList());
        }
    }

    @Test
    @DisplayName("nothing in the store is plaintext, the key is not in it, and the right key rebuilds whole")
    void sealed(@TempDir Path dir) throws Exception {
        var data = household(dir);
        var store = dir.resolve("vault");
        var vault = new Vault(data, store);
        var key = data.resolve(Vault.KEY_FILE);
        assertTrue(Files.isRegularFile(key), "the key is made on first use");
        assertNull(vault.keyMismatch());
        var m = vault.snapshot("first", false).orElseThrow();

        var files = storeFiles(store);
        assertFalse(files.isEmpty());
        for (var f : files) {
            var bytes = Files.readAllBytes(f);
            assertTrue(VaultCipher.sealed(bytes), f + " is sealed");
            assertFalse(new String(bytes, StandardCharsets.ISO_8859_1).contains(MARKER), f + " leaks the profile");
            assertFalse(new String(bytes, StandardCharsets.ISO_8859_1).contains("profile.toml"), f + " leaks a file name");
        }
        assertTrue(storeFiles(store).stream().noneMatch(p -> p.getFileName().toString().equals(Vault.KEY_FILE)));
        assertFalse(m.classes().get("unclassified").contains(Vault.KEY_FILE), "the key is known, not unclassified");
        assertEquals(vault.keyId(), Files.readString(store.resolve("key.id")).strip());

        var out = dir.resolve("out");
        assertTrue(vault.restoreTo(m, out).isEmpty());
        assertTrue(Files.readString(out.resolve("profile.toml")).contains(MARKER));
        assertEquals(8, vault.keyId().length());
    }

    @Test
    @DisplayName("another key is refused: no copy is written, and a restore says whose key the store wants")
    void wrongKey(@TempDir Path dir) throws Exception {
        var data = household(dir);
        var store = dir.resolve("vault");
        var right = new Vault(data, store);
        var m = right.snapshot("first", false).orElseThrow();
        Vault.resetForTests();

        var other = dir.resolve("other.key");
        var wrong = new Vault(data, store, other);
        assertNotNull(wrong.keyMismatch());
        assertTrue(wrong.keyMismatch().contains(right.keyId()), wrong.keyMismatch());
        assertTrue(wrong.snapshot("second", false).isEmpty(), "nothing is written under the wrong key");
        assertEquals(1, right.list().size());
        var failed = wrong.restoreTo(m, dir.resolve("out"));
        assertFalse(failed.isEmpty());
        assertTrue(failed.get(0).contains("another key"), failed.get(0));
        assertEquals("", wrong.status().get("lastOk") == null ? "" : "x", "no copy ever succeeded");
        assertTrue(wrong.status().get("keyMismatch").toString().contains("sealed with key"));
    }

    @Test
    @DisplayName("files from before sealing are read as they are, and sealed in place on the next pass")
    void legacyPlainFilesAreResealed(@TempDir Path dir) throws Exception {
        var data = household(dir);
        var store = dir.resolve("vault");
        var vault = new Vault(data, store);
        var m = vault.snapshot("first", false).orElseThrow();
        // Undo the sealing by hand: what a store from 0.4.0 looks like.
        var cipher = VaultCipher.load(data.resolve(Vault.KEY_FILE));
        for (var f : storeFiles(store)) Files.write(f, cipher.open(Files.readAllBytes(f)));
        Files.delete(store.resolve("key.id"));
        Vault.resetForTests();

        var again = new Vault(data, store);
        assertNull(again.keyMismatch());
        assertEquals(m.id(), again.latest().orElseThrow().id(), "a plain manifest still lists");
        assertTrue(again.restoreTo(m, dir.resolve("out")).isEmpty(), "plain chunks still rebuild");
        again.snapshot("second", false).orElseThrow();
        for (var f : storeFiles(store)) assertTrue(VaultCipher.sealed(Files.readAllBytes(f)), f + " sealed after the pass");
        assertEquals(0, again.resealPlain(), "nothing left to seal");
        assertTrue(again.restoreTo(m, dir.resolve("out2")).isEmpty(), "the resealed copy is whole");
    }
}
