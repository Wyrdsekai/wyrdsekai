package org.wyrdsekai.core.net;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/**
 * the steward's persisted allowlist behind
 * {@code scroll net allow/list/revoke}. Round-trips entries, validates the
 * credential ref BEFORE persisting (no dangling key pointers), and hot-reloads
 * into the merged {@link NetworkGate} via {@link NetworkWiring#invalidate()}.
 */
final class NetworkAllowStoreTest {

    @AfterEach
    void clearOverrides() {
        System.clearProperty("wyrdsekai.dataDir");
        NetworkWiring.invalidate();
        NetworkAllowStore.resetForTest();
    }

    private static void placeKey(Path dataDir, String node) throws Exception {
        var dir = dataDir.resolve("net-keys").resolve("household");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(node), "PRIVATE KEY");
    }

    @Test
    void allow_persists_and_lists_round_trip(@TempDir Path dataDir) throws Exception {
        placeKey(dataDir, "second-node");
        var store = new NetworkAllowStore(dataDir);

        var outcome = store.allow("second-node", List.of("ssh", "scp"), "household:second-node", null);
        assertTrue(outcome.ok(), outcome.error());

        // A FRESH store over the same dir sees the entry — it's on disk, not in memory.
        var reread = new NetworkAllowStore(dataDir).entries();
        assertEquals(1, reread.size());
        assertEquals("second-node", reread.get(0).host());
        assertTrue(reread.get(0).grants("ssh"));
        assertTrue(reread.get(0).grants("scp"));
        assertEquals("household:second-node", reread.get(0).keyRef());
    }

    @Test
    void allow_replaces_same_host_and_revoke_removes(@TempDir Path dataDir) throws Exception {
        placeKey(dataDir, "second-node");
        var store = new NetworkAllowStore(dataDir);
        assertTrue(store.allow("second-node", List.of("ssh"), "household:second-node", null).ok());
        assertTrue(store.allow("second-node", List.of("scp"), "household:second-node", "backup").ok());

        var entries = store.entries();
        assertEquals(1, entries.size(), "same-host allow must replace, not accumulate");
        assertFalse(entries.get(0).grants("ssh"));
        assertTrue(entries.get(0).grants("scp"));
        assertEquals("backup", entries.get(0).commandPrefix());

        assertTrue(store.revoke("SECOND-NODE").ok(), "revoke is case-insensitive on host");
        assertTrue(store.entries().isEmpty());
        assertFalse(store.revoke("second-node").ok(), "revoking a missing entry reports honestly");
    }

    @Test
    void credentialed_kinds_require_a_resolving_key_ref(@TempDir Path dataDir) {
        var store = new NetworkAllowStore(dataDir);
        var missing = store.allow("second-node", List.of("ssh"), null, null);
        assertFalse(missing.ok());
        assertTrue(missing.error().contains("key ref"));

        var dangling = store.allow("second-node", List.of("ssh"), "household:second-node", null);
        assertFalse(dangling.ok(), "a key-ref with no keyfile on disk must be refused");
        assertTrue(dangling.error().contains("does not resolve"));

        // http needs no credential — restrict-mode entries persist without one.
        assertTrue(store.allow("api.example", List.of("http", "https"), null, null).ok());
    }

    @Test
    void garbage_host_and_kind_are_refused(@TempDir Path dataDir) {
        var store = new NetworkAllowStore(dataDir);
        assertFalse(store.allow("", List.of("ssh"), "x", null).ok());
        assertFalse(store.allow("second-node; rm -rf /", List.of("ssh"), "x", null).ok());
        assertFalse(store.allow("second-node", List.of("gopher"), "x", null).ok());
        assertFalse(store.allow("second-node", List.of(), "x", null).ok());
    }

    @Test
    void store_entries_merge_into_the_live_gate_and_hot_reload(@TempDir Path dataDir)
            throws Exception {
        placeKey(dataDir, "second-node");
        System.setProperty("wyrdsekai.dataDir", dataDir.toString());
        NetworkWiring.invalidate();

        // Closed before the steward opens the door.
        assertFalse(NetworkWiring.currentGate().check("ssh", "second-node", null).allowed());

        var store = new NetworkAllowStore(dataDir);
        assertTrue(store.allow("second-node", List.of("ssh"), "household:second-node", null).ok());
        // allow() invalidated the wiring — the NEXT gate read sees the entry.
        assertTrue(NetworkWiring.currentGate().check("ssh", "second-node", null).allowed(),
            "scroll net allow must take effect without a zone bounce");

        assertTrue(store.revoke("second-node").ok());
        assertFalse(NetworkWiring.currentGate().check("ssh", "second-node", null).allowed(),
            "scroll net revoke must close the door without a zone bounce");
    }
}
