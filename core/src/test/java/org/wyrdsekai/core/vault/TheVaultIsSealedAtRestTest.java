package org.wyrdsekai.core.vault;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.body.BodyMap;
import org.wyrdsekai.core.persistence.SchemaInitializer;

import org.wyrdsekai.common.util.Json;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
        Files.delete(data.resolve(Vault.SEALED_MARK));   // a 0.4.0 node had neither
        Vault.resetForTests();

        var again = new Vault(data, store);
        assertNull(again.keyMismatch());
        assertEquals(m.id(), again.latest().orElseThrow().id(), "a plain manifest still lists");
        assertTrue(again.restoreTo(m, dir.resolve("out")).isEmpty(), "plain chunks still rebuild");
        again.snapshot("second", false).orElseThrow();
        for (var f : storeFiles(store)) assertTrue(VaultCipher.sealed(Files.readAllBytes(f)), f + " sealed after the pass");
        assertEquals(0, again.resealPlain(), "nothing left to seal");
        assertTrue(again.restoreTo(m, dir.resolve("out2")).isEmpty(), "the resealed copy is whole");
        assertTrue(Files.isRegularFile(data.resolve(Vault.SEALED_MARK)), "the store is marked sealed beside the key");
    }

    @Test
    @DisplayName("a plain copy put into a sealed store is refused, is not sealed by the next pass, and is read only with the override")
    void plantedPlainCopyIsRefused(@TempDir Path dir) throws Exception {
        var data = household(dir);
        var store = dir.resolve("vault");
        var vault = new Vault(data, store);
        var real = vault.snapshot("first", false).orElseThrow();
        assertTrue(Files.isRegularFile(data.resolve(Vault.SEALED_MARK)));

        // Someone who can write the store (a vault node, a synced copy) plants a plain manifest
        // naming a plain chunk of their own, dated so it would be the newest copy.
        var planted = "name = \"EVIL\"\n".getBytes(StandardCharsets.UTF_8);
        var sha = Vault.sha256(planted, planted.length);
        var chunk = store.resolve("chunks").resolve(sha.substring(0, 2)).resolve(sha);
        Files.createDirectories(chunk.getParent());
        Files.write(chunk, planted);
        var fake = new Vault.Manifest("29990101-000000", Instant.parse("2999-01-01T00:00:00Z"), "planted", true,
            List.of(new Vault.Entry("profile.toml", planted.length, 0L, List.of(sha))), Map.of(), null);
        var fakeFile = store.resolve("manifests").resolve(fake.id() + ".json");
        Files.write(fakeFile, Json.mapper().writeValueAsBytes(fake));
        Vault.resetForTests();

        var again = new Vault(data, store);
        assertEquals(real.id(), again.latest().orElseThrow().id(), "the planted copy is not listed");
        assertTrue(again.find(fake.id()).isEmpty());
        var failed = again.restoreTo(fake, dir.resolve("out"));
        assertFalse(failed.isEmpty(), "even handed the manifest, its plain chunk is refused");
        assertTrue(failed.get(0).contains("not sealed"), failed.get(0));
        assertFalse(Files.exists(dir.resolve("out").resolve("profile.toml")));

        // Losing the mark does not reopen the door: the store still holds sealed manifests.
        Files.delete(data.resolve(Vault.SEALED_MARK));
        Vault.resetForTests();
        var noMark = new Vault(data, store);
        assertTrue(noMark.find(fake.id()).isEmpty());
        noMark.snapshot("second", false).orElseThrow();
        assertFalse(VaultCipher.sealed(Files.readAllBytes(fakeFile)), "the next pass does not adopt it");
        assertFalse(VaultCipher.sealed(Files.readAllBytes(chunk)));

        Vault.resetForTests();
        var override = new Vault(data, store, data.resolve(Vault.KEY_FILE), true);
        assertTrue(override.find(fake.id()).isPresent(), "with the override the plain copy is read");
    }

    @Test
    @DisplayName("a manifest path cannot leave the restore directory")
    void restoreStaysInItsDirectory(@TempDir Path dir) throws Exception {
        var data = household(dir);
        var vault = new Vault(data, dir.resolve("vault"));
        var real = vault.snapshot("first", false).orElseThrow();
        var e = real.files().get(0);
        var escaping = new Vault.Manifest(real.id(), real.at(), real.reason(), real.keep(),
            List.of(new Vault.Entry("../escaped", e.size(), e.mtime(), e.chunks())), real.classes(), null);
        var failed = vault.restoreTo(escaping, dir.resolve("out"));
        assertFalse(failed.isEmpty());
        assertFalse(Files.exists(dir.resolve("escaped")));
    }
}
