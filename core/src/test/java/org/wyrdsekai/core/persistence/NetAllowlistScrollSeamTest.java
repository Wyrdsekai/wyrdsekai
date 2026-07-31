package org.wyrdsekai.core.persistence;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.net.NetworkAllowStore;
import org.wyrdsekai.core.net.NetworkWiring;

import static org.junit.jupiter.api.Assertions.*;

/**
 * the exact seam study.js's {@code scroll net}
 * verb calls: {@code world.netAllow/netList/netRevoke} → BridgeDataProvider.
 * Proves the JSON contract the room script parses, over the real store.
 */
final class NetAllowlistScrollSeamTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @AfterEach
    void clearOverrides() {
        System.clearProperty("wyrdsekai.dataDir");
        NetworkWiring.invalidate();
        NetworkAllowStore.resetForTest();
    }

    @Test
    void allow_list_revoke_json_contract(@TempDir Path dataDir) throws Exception {
        var keys = dataDir.resolve("net-keys").resolve("household");
        Files.createDirectories(keys);
        Files.writeString(keys.resolve("second-node"), "PRIVATE KEY");
        System.setProperty("wyrdsekai.dataDir", dataDir.toString());
        NetworkWiring.invalidate();
        NetworkAllowStore.resetForTest();

        var bridge = new BridgeDataProviderImpl(null, null, null);

        var allowed = JSON.readTree(
            bridge.netAllow("second-node", "ssh,scp", "household:second-node", null));
        assertTrue(allowed.path("ok").asBoolean(), allowed.toString());
        assertEquals("second-node", allowed.path("host").asText());

        var listed = JSON.readTree(bridge.netList());
        assertTrue(listed.isArray());
        boolean found = false;
        for (var e : listed) {
            if ("second-node".equals(e.path("host").asText())) {
                found = true;
                assertEquals("household:second-node", e.path("keyRef").asText());
            }
        }
        assertTrue(found, "netList must show the fresh entry (hot-reloaded gate)");

        var refused = JSON.readTree(
            bridge.netAllow("ghost", "ssh", "household:ghost", null));
        assertFalse(refused.path("ok").asBoolean(),
            "a dangling key ref must be refused at the scroll seam");
        assertTrue(refused.path("error").asText().contains("does not resolve"));

        var revoked = JSON.readTree(bridge.netRevoke("second-node"));
        assertTrue(revoked.path("ok").asBoolean());
        assertEquals(0, JSON.readTree(bridge.netList()).size());
    }
}
