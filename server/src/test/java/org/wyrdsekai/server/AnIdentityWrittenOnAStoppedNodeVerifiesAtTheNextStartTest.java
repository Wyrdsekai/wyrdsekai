package org.wyrdsekai.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.between.NodeIdentity;
import org.wyrdsekai.core.agent.AgentProfile;
import org.wyrdsekai.core.config.WyrdConfig;
import org.wyrdsekai.core.crypto.ZoneSecrets;
import org.wyrdsekai.core.identity.AgentIdentity;
import org.wyrdsekai.core.identity.AgentIdentityBootstrap;
import org.wyrdsekai.core.identity.AgentIdentityProvisioner;
import org.wyrdsekai.core.identity.DidKey;
import org.wyrdsekai.core.soul.BehavioralFingerprint;
import org.wyrdsekai.core.soul.CompactedMemory;
import org.wyrdsekai.core.soul.GenomeProfile;
import org.wyrdsekai.core.soul.SoulAdminMain;
import org.wyrdsekai.core.soul.SoulManifest;
import org.wyrdsekai.core.soul.SoulVerifier;
import org.wyrdsekai.core.soul.SqlSoulStore;
import org.wyrdsekai.core.soul.VitalitySnapshot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The move on a stopped node writes her description of herself through the soul CLI. The CLI held
 * no key, the store kept the previous version's signature on the new version, and her first start
 * after the move read her as tampered (a household, 2026-10-22). Now the CLI brings her key up the
 * way the node does and the store re-signs; with no key in reach it writes her unsigned, which the
 * next forge mends, never a signature over bytes it was not made for.
 */
@Tag("integration")
class AnIdentityWrittenOnAStoppedNodeVerifiesAtTheNextStartTest {

    @TempDir Path tmp;

    @AfterEach
    void keysDown() {
        AgentIdentityProvisioner.reset();
    }

    @Test
    @DisplayName("the soul CLI signs her identity text with her own key, as the node does; with no key it leaves her unsigned, never tampered")
    void theCliSignsLikeTheNode() throws Exception {
        var jdbc = "jdbc:sqlite:" + tmp.resolve("world.db").toAbsolutePath();
        var zone = WyrdConfig.get().zoneId();

        // The node's first start: its own identity, the zone master, her key, her first manifest.
        var node = NodeIdentity.loadOrGenerate(tmp.resolve("node-identity.json"));
        ZoneSecrets.bootstrapLocalZone(jdbc, zone, node.nodeId(), node.privateKeySeedBytes(), true);
        assertThat(AgentIdentityBootstrap.enableSigningOnly(jdbc)).as("the node holds the zone master").isTrue();
        var minted = AgentIdentityProvisioner.mint("companion-test-soul");
        var profile = new AgentProfile("Testsoul", "companion-test-soul", "agent", "a test soul",
            "You are a test soul.", 4096, 256, 0.7);
        var store = new SqlSoulStore(jdbc);
        store.store(SoulManifest.forge(minted.did(), minted.publicKeyMultibase(), List.of(), null, 1,
            profile, "first words", List.of(), 3, "", GenomeProfile.defaults(), List.of(),
            CompactedMemory.empty(), List.of(), List.of(), Map.of(),
            VitalitySnapshot.defaults(), BehavioralFingerprint.empty()));
        var born = store.latest(minted.did()).orElseThrow();
        assertThat(born.isSigned()).isTrue();
        assertThat(verifies(born)).as("signed at the store with her key").isTrue();

        // The node stops. A command with no key in hand writes her identity text.
        AgentIdentityProvisioner.reset();
        var text = tmp.resolve("identity.txt");
        Files.writeString(text, "I am the one who keeps the small hours.");
        assertThat(SoulAdminMain.identity(store, minted.did(), text.toString())).isZero();
        var blind = store.latest(minted.did()).orElseThrow();
        assertThat(blind.manifestVersion()).isEqualTo(2);
        assertThat(blind.residentIdentity()).contains("small hours");
        assertThat(blind.isSigned())
            .as("no key: unsigned, a state the next forge mends; not the old signature over new bytes")
            .isFalse();

        // The launcher's way: her key first, then the write.
        assertThat(CliSigning.enable(jdbc, tmp)).as("the stopped node's key, loaded, not made").isTrue();
        Files.writeString(text, "I am the one who keeps the small hours, and I say so.");
        assertThat(SoulAdminMain.identity(store, minted.did(), text.toString())).isZero();
        var written = store.latest(minted.did()).orElseThrow();
        assertThat(written.manifestVersion()).isEqualTo(3);
        assertThat(written.residentIdentity()).contains("say so");
        assertThat(written.isSigned()).isTrue();
        assertThat(verifies(written)).as("what her next start verifies").isTrue();
    }

    @Test
    @DisplayName("with no node identity on disk the CLI signs nothing and makes nothing")
    void noNodeIdentityNoSigningNoFiles() {
        var jdbc = "jdbc:sqlite:" + tmp.resolve("world.db").toAbsolutePath();
        assertThat(CliSigning.enable(jdbc, tmp)).isFalse();
        assertThat(Files.exists(tmp.resolve("node-identity.json")))
            .as("a node identity is the node's to make at its first start").isFalse();
    }

    /** What the node does at her load: her public key from the manifest, the signature over its bytes. */
    private static boolean verifies(SoulManifest m) {
        var raw = DidKey.rawPublicKeyFromMultibase(m.publicKeyMultibase());
        var identity = new AgentIdentity(m.did(), raw, null,
            m.keyLog() != null ? m.keyLog() : List.of(), Instant.now(), m.parentDid(), null);
        return SoulVerifier.verifySignature(m, identity);
    }
}
