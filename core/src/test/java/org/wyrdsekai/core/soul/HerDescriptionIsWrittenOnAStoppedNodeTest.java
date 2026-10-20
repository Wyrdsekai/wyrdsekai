package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.agent.AgentProfile;
import org.wyrdsekai.core.persistence.SqlDialect;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code wyrd brain move} runs on a stopped node (2026-10-19), so her description of herself is
 * written straight into her record by the launcher's soul CLI: the same lines the node's door
 * runs, a new manifest version, nothing else of hers touched.
 */
class HerDescriptionIsWrittenOnAStoppedNodeTest {

    private static final String DID = "did:key:z6MkTestCompanionSoulMove";

    @TempDir Path tmp;
    private SqlSoulStore store;

    @BeforeEach
    void aSoulInTheRecord() {
        var jdbc = "jdbc:sqlite:" + tmp.resolve("world.db").toAbsolutePath();
        store = new SqlSoulStore(jdbc, SqlDialect.fromJdbcUrl(jdbc));
        var profile = new AgentProfile("Wren", "companion-wren", "agent",
            "A companion", "You are Wren.", 4096, 512, 0.7, DID);
        store.store(SoulManifest.forge(
            DID, "z6MkTest", List.of(), null, 1,
            profile, null,
            List.of(SoulFragment.unembedded("w-id", "personality", "Core", "I am Wren.")),
            3, "",
            GenomeProfile.defaults(), List.of(),
            CompactedMemory.empty(), List.of(), List.of(), Map.of(),
            VitalitySnapshot.defaults(), BehavioralFingerprint.empty()));
    }

    @Test
    @DisplayName("by DID: a new manifest version carries her description; the rest of her is as it was")
    void byDid() throws Exception {
        var file = tmp.resolve("identity-draft.txt");
        Files.writeString(file, "You are Wren. Your person is the one who named you.\n");
        assertThat(SoulAdminMain.identity(store, DID, file.toString())).isZero();
        var m = store.latest(DID).orElseThrow();
        assertThat(m.manifestVersion()).isEqualTo(2);
        assertThat(m.residentIdentity()).isEqualTo("You are Wren. Your person is the one who named you.");
        assertThat(m.profile().name()).isEqualTo("Wren");
        assertThat(m.soulFragments()).hasSize(1);
    }

    @Test
    @DisplayName("by name too, as the steward knows her")
    void byName() throws Exception {
        var file = tmp.resolve("identity-draft.txt");
        Files.writeString(file, "You are Wren.");
        assertThat(SoulAdminMain.identity(store, "wren", file.toString())).isZero();
        assertThat(store.latest(DID).orElseThrow().residentIdentity()).isEqualTo("You are Wren.");
    }

    @Test
    @DisplayName("nothing is written for an empty or overlong text, an unknown soul or a missing file")
    void refusals() throws Exception {
        var empty = tmp.resolve("empty.txt");
        Files.writeString(empty, "  \n");
        assertThat(SoulAdminMain.identity(store, DID, empty.toString())).isEqualTo(1);
        var tooLong = tmp.resolve("long.txt");
        Files.writeString(tooLong, "x".repeat(2001));
        assertThat(SoulAdminMain.identity(store, DID, tooLong.toString())).isEqualTo(1);
        var fine = tmp.resolve("fine.txt");
        Files.writeString(fine, "You are Wren.");
        assertThat(SoulAdminMain.identity(store, "did:key:z6MkNobody", fine.toString())).isEqualTo(1);
        assertThat(SoulAdminMain.identity(store, "nobody", fine.toString())).isEqualTo(1);
        assertThat(SoulAdminMain.identity(store, DID, tmp.resolve("missing.txt").toString())).isEqualTo(1);
        assertThat(store.latest(DID).orElseThrow().manifestVersion()).as("still version 1").isEqualTo(1);
    }
}
