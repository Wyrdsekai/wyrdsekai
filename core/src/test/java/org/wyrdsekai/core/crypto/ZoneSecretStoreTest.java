package org.wyrdsekai.core.crypto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;

/** foundation — the wrapped-secret store round-trips per (zone, node) and upserts. */
class ZoneSecretStoreTest {

    private static String schemaUrl(Path dir) throws Exception {
        var url = "jdbc:sqlite:" + dir.resolve("zonesecrets.db");
        try (var c = DriverManager.getConnection(url); var st = c.createStatement()) {
            st.execute("CREATE TABLE zone_wrapped_secrets("
                + "zone_id TEXT NOT NULL, node_id TEXT NOT NULL, wrapped_secret BLOB NOT NULL, "
                + "created_at INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (zone_id, node_id))");
        }
        return url;
    }

    @Test
    void wrappedSecretRoundTripsAndUpserts(@TempDir Path dir) throws Exception {
        var store = new ZoneSecretStore(schemaUrl(dir));
        assertFalse(store.has("zone-a", "node-1"));
        assertTrue(store.get("zone-a", "node-1").isEmpty());

        var wrapped = new byte[]{1, 2, 3, 4, 5, 6, 7, 8};
        store.put("zone-a", "node-1", wrapped);
        assertTrue(store.has("zone-a", "node-1"));
        assertArrayEquals(wrapped, store.get("zone-a", "node-1").orElseThrow());

        // Upsert replaces the wrapped blob (e.g. after a node-KEK rotation).
        store.put("zone-a", "node-1", new byte[]{9, 9, 9, 9});
        assertArrayEquals(new byte[]{9, 9, 9, 9}, store.get("zone-a", "node-1").orElseThrow());

        // Distinct (zone, node) rows are independent.
        store.put("zone-b", "node-1", new byte[]{2, 2});
        assertArrayEquals(new byte[]{9, 9, 9, 9}, store.get("zone-a", "node-1").orElseThrow());
        assertTrue(store.get("zone-a", "node-2").isEmpty());
    }

    @Test
    void endToEndWrapPersistUnwrapDerivesTheSameKey(@TempDir Path dir) throws Exception {
        // The real path: node originates a master, wraps under its KEK, persists; a reboot loads +
        // unwraps and derives the identical argot key.
        var store = new ZoneSecretStore(schemaUrl(dir));
        var kek = ZoneSecretService.nodeKek(new byte[]{4, 2, 4, 2, 4, 2, 4, 2});

        var boot1 = new ZoneSecretService();
        boot1.generate("zone-a");
        store.put("zone-a", "node-1", boot1.wrapForNode("zone-a", kek));
        var keyBefore = boot1.derive("zone-a", "argot-v1");

        var boot2 = new ZoneSecretService();
        boot2.installFromWrapped("zone-a", store.get("zone-a", "node-1").orElseThrow(), kek);
        assertArrayEquals(keyBefore, boot2.derive("zone-a", "argot-v1"),
            "persist→unwrap preserves the derived argot key across reboot");
    }
}
