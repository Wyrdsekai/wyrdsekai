package org.wyrdsekai.core.net;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/**
 * the keyfile-convention credential resolver. A key-ref
 * resolves to an existing 0600 keyfile under the data dir; a missing key is
 * empty (→ deny:no-credential), and traversal in the ref is neutralized.
 */
final class NetworkWiringTest {

    @Test
    void household_ref_resolves_to_convention_path(@TempDir Path dataDir) throws Exception {
        var keyDir = dataDir.resolve("net-keys").resolve("household");
        Files.createDirectories(keyDir);
        var key = keyDir.resolve("second-node");
        Files.writeString(key, "PRIVATE KEY");

        var resolver = NetworkWiring.keyfileResolver(dataDir);
        var got = resolver.resolveKeyfile("household:second-node");
        assertTrue(got.isPresent());
        assertEquals(key.toAbsolutePath().toString(), got.get());
    }

    @Test
    void chest_ref_resolves_under_net_keys(@TempDir Path dataDir) throws Exception {
        var keyDir = dataDir.resolve("net-keys");
        Files.createDirectories(keyDir);
        Files.writeString(keyDir.resolve("backup-key"), "K");
        var resolver = NetworkWiring.keyfileResolver(dataDir);
        assertTrue(resolver.resolveKeyfile("chest:backup-key").isPresent());
    }

    @Test
    void absolute_path_ref_used_verbatim(@TempDir Path dataDir) throws Exception {
        var key = dataDir.resolve("somewhere.id");
        Files.writeString(key, "K");
        var resolver = NetworkWiring.keyfileResolver(dataDir);
        assertEquals(key.toAbsolutePath().toString(),
            resolver.resolveKeyfile(key.toAbsolutePath().toString()).orElseThrow());
        assertEquals(key.toAbsolutePath().toString(),
            resolver.resolveKeyfile("file:" + key).orElseThrow());
    }

    @Test
    void missing_key_is_empty() {
        var resolver = NetworkWiring.keyfileResolver(Path.of("/nonexistent-datadir-xyz"));
        assertTrue(resolver.resolveKeyfile("household:ghost").isEmpty());
        assertTrue(resolver.resolveKeyfile(null).isEmpty());
        assertTrue(resolver.resolveKeyfile("").isEmpty());
    }

    @Test
    void path_traversal_in_ref_is_neutralized(@TempDir Path dataDir) throws Exception {
        // A ref trying to escape the net-keys dir must not resolve to /etc/passwd etc.
        var resolver = NetworkWiring.keyfileResolver(dataDir);
        var got = resolver.resolveKeyfile("chest:../../etc/passwd");
        // Sanitized to a literal filename under net-keys/ which won't exist.
        assertTrue(got.isEmpty(), "traversal must be neutralized, not resolve outside net-keys");
    }
}
