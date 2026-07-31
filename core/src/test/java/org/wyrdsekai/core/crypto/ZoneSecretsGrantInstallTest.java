package org.wyrdsekai.core.crypto;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #1184 — the gate-lift: a joining node receives the zone master via grant, persists it
 * and is promoted from the public seed to the secret-derived codebook; and a multi-node node that
 * does NOT hold the master must NOT originate a divergent one (the load-bearing safety rule).
 *
 * <p>{@link ZoneSecrets} uses a process-wide singleton service, so these exercise it as "this node"
 * (the grantee/joiner) while a local {@link ZoneSecretService} instance stands in for the holder.
 */
class ZoneSecretsGrantInstallTest {

    private static String tempJdbc() throws Exception {
        Path db = Files.createTempFile("zone-secret-grant-", ".db");
        db.toFile().deleteOnExit();
        var url = "jdbc:sqlite:" + db.toAbsolutePath();
        try (var c = DriverManager.getConnection(url); var st = c.createStatement()) {
            st.execute("CREATE TABLE zone_wrapped_secrets(zone_id TEXT NOT NULL, "
                + "node_id TEXT NOT NULL, wrapped_secret BLOB NOT NULL, "
                + "created_at INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (zone_id, node_id))");
        }
        return url;
    }

    private static byte[] seed(int b) {
        byte[] s = new byte[32];
        Arrays.fill(s, (byte) b);
        return s;
    }

    @Test
    void granteeInstallsMasterPersistsAndDerivesSameArgotKeyAsHolder() throws Exception {
        String jdbc = tempJdbc();
        String zone = "zone-grant-a";
        String nodeId = "node-grantee";

        // Holder originates the master locally and mints a grant for the grantee's X25519 key.
        var holder = new ZoneSecretService();
        holder.generate(zone);
        var granteeKeys = ZoneSecretService.generateNodeEcdhKeyPair();
        byte[] grant = holder.grantTo(zone, granteeKeys.getPublic().getEncoded());

        // This node (the singleton) accepts + persists + is promoted to the secret codebook.
        boolean ok = ZoneSecrets.installGrantedMaster(
            jdbc, zone, nodeId, seed(7), grant, granteeKeys.getPrivate().getEncoded());
        assertTrue(ok, "grant install succeeds");
        assertTrue(ZoneSecrets.service().has(zone), "this node now holds the zone master");
        assertArrayEquals(holder.derive(zone, "argot-v1"),
            ZoneSecrets.service().derive(zone, "argot-v1"),
            "grantee derives the IDENTICAL argot key → same codebook → cross-node argot decodes");

        // Persisted wrapped → a subsequent boot loads it and re-installs the secret provider even
        // though this is no longer the sole node (it holds the agreed master = proof of agreement).
        ZoneSecrets.service().forget(zone);
        assertFalse(ZoneSecrets.service().has(zone));
        ZoneSecrets.bootstrapLocalZone(jdbc, zone, nodeId, seed(7), /*soleNode=*/false);
        assertTrue(ZoneSecrets.service().has(zone), "reboot loads the granted master from disk");
        assertArrayEquals(holder.derive(zone, "argot-v1"),
            ZoneSecrets.service().derive(zone, "argot-v1"));
    }

    @Test
    void multiNodeJoinerWithoutMasterDoesNotOriginate() throws Exception {
        String jdbc = tempJdbc();
        String zone = "zone-no-originate";
        ZoneSecrets.service().forget(zone);

        // Multi-node (soleNode=false) + no persisted master → must NOT originate a divergent master.
        ZoneSecrets.bootstrapLocalZone(jdbc, zone, "node-joiner", seed(9), /*soleNode=*/false);
        assertFalse(ZoneSecrets.service().has(zone),
            "a multi-node joiner without a grant stays master-less (public seed) — no divergence");
    }

    @Test
    void soleNodeOriginatesAndHoldsMaster() throws Exception {
        String jdbc = tempJdbc();
        String zone = "zone-sole-originate";
        ZoneSecrets.service().forget(zone);

        ZoneSecrets.bootstrapLocalZone(jdbc, zone, "node-sole", seed(11), /*soleNode=*/true);
        assertTrue(ZoneSecrets.service().has(zone), "sole node originates its own master");
    }
}
