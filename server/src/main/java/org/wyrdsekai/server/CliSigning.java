package org.wyrdsekai.server;

import org.wyrdsekai.between.NodeIdentity;
import org.wyrdsekai.core.config.WyrdConfig;
import org.wyrdsekai.core.crypto.ZoneSecrets;
import org.wyrdsekai.core.identity.AgentIdentityBootstrap;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Her signing key, in a command that runs while the node is down.
 *
 * <p>The node brings the key up at boot: the node's own identity unwraps the zone master, the
 * master derives the household secret, the secret opens her private key. A command run from the
 * launcher ({@code wyrd soul …}, {@code wyrd bond …}) did none of that, so what it stored carried
 * the previous version's signature and her next start read her as tampered (2026-10-22). This does
 * the same three steps, loading only: it never makes a node identity or a zone master, which are
 * the node's to make at its first start.
 */
public final class CliSigning {

    private CliSigning() {}

    /** @return true when this node holds her key and what the command stores will be re-signed */
    public static boolean enable(String jdbcUrl, Path dataDir) {
        try {
            if (jdbcUrl == null || dataDir == null) return false;
            var identityFile = dataDir.resolve("node-identity.json");
            if (!Files.exists(identityFile)) return false;
            var node = NodeIdentity.loadOrGenerate(identityFile);
            var zoneId = WyrdConfig.get().zoneId();
            // soleNode=false: a loader only. A node that never booted has no master and no souls to edit.
            ZoneSecrets.bootstrapLocalZone(jdbcUrl, zoneId, node.nodeId(), node.privateKeySeedBytes(), false);
            return AgentIdentityBootstrap.enableSigningOnly(jdbcUrl);
        } catch (Exception e) {
            return false;
        }
    }
}
