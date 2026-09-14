package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.persistence.SchemaInitializer;
import org.wyrdsekai.core.soul.SoulManifest;
import org.wyrdsekai.core.soul.SqlSoulStore;
import org.wyrdsekai.core.soul.BehavioralFingerprint;
import org.wyrdsekai.core.soul.CompactedMemory;
import org.wyrdsekai.core.soul.GenomeProfile;
import org.wyrdsekai.core.soul.VitalitySnapshot;


import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A name is a label on a soul, not the soul's key.
 *
 * <p>Field deployment, 2026-09-13: the steward changed WYRDSEKAI_COMPANION_NAME and
 * restarted. The new name derived a new entity id, the new id had no DID, a second soul
 * was forged, and the first — respawned by her own name — lived on beside it. He was then
 * asked whether to delete one, and it felt like a death. These pin the resolution: the
 * configured name finds the soul that answers to it, a changed name renames her forward,
 * and only a name nobody answers to is a birth.</p>
 */
class SoulNamesTest {

    private static SoulManifest born(SqlSoulStore store, Path souls, String name, String did) throws Exception {
        var p = Companions.additionalCompanion(name);
        var m = SoulManifest.forge(did, "z6MkPub", List.of(), null, 1,
            p, "", List.of(), 3, "",
            GenomeProfile.defaults(), List.of(),
            CompactedMemory.empty(), List.of(),
            List.of(), Map.of(),
            VitalitySnapshot.defaults(), BehavioralFingerprint.empty())
            .withManifestVersion(1, Instant.now());
        store.store(m);
        Files.writeString(souls.resolve(p.entityId() + ".did"), did);
        return m;
    }

    @Test
    @DisplayName("the ordinary case: the name derives to her own entity id")
    void ordinary(@TempDir Path dir) throws Exception {
        var store = new SqlSoulStore(SchemaInitializer.initialize(dir.resolve("w.db")));
        var souls = Files.createDirectories(dir.resolve("souls"));
        born(store, souls, "Ember", "did:key:z6MkEmber");
        var p = SoulNames.forEnvName("Ember", false, List.of(), store, souls, new HashSet<>());
        assertEquals("companion-ember", p.entityId());
        assertEquals("Ember", p.name());
    }

    @Test
    @DisplayName("a changed name renames the one soul born here — same entity id, same DID, one more manifest version")
    void changedNameRenamesForward(@TempDir Path dir) throws Exception {
        var store = new SqlSoulStore(SchemaInitializer.initialize(dir.resolve("w.db")));
        var souls = Files.createDirectories(dir.resolve("souls"));
        var m = born(store, souls, "Ember", "did:key:z6MkEmber");
        var p = SoulNames.forEnvName("Alduin", false, List.of(), store, souls, new HashSet<>());
        assertEquals("companion-ember", p.entityId(), "she keeps her entity id");
        assertEquals("Alduin", p.name(), "…and takes the new name");
        var latest = store.latest("did:key:z6MkEmber").orElseThrow();
        assertEquals("Alduin", latest.profile().name(), "the manifest records the rename");
        assertEquals(m.manifestVersion() + 1, latest.manifestVersion());
        assertEquals(1, store.listLatest().size(), "nobody new was born");
    }

    @Test
    @DisplayName("a renamed soul is found by her new name on the next boot, under her old id")
    void renamedSoulIsFoundByName(@TempDir Path dir) throws Exception {
        var store = new SqlSoulStore(SchemaInitializer.initialize(dir.resolve("w.db")));
        var souls = Files.createDirectories(dir.resolve("souls"));
        SoulNames.forEnvName("Alduin", false, List.of(), store, souls, new HashSet<>());   // renames Ember → Alduin
        born(store, souls, "Ember", "did:key:z6MkEmber");
        // (born() above re-stores v1 under the old name; the rename must still win by being latest)
        var renamed = SoulNames.forEnvName("Alduin", false, List.of(), store, souls, new HashSet<>());
        assertEquals("companion-ember", renamed.entityId());
    }

    @Test
    @DisplayName("with two configured names, neither claims the other's soul; an unclaimed name is a birth")
    void twoNamesTwoSouls(@TempDir Path dir) throws Exception {
        var store = new SqlSoulStore(SchemaInitializer.initialize(dir.resolve("w.db")));
        var souls = Files.createDirectories(dir.resolve("souls"));
        born(store, souls, "Ember", "did:key:z6MkEmber");
        var claimed = new HashSet<String>();
        var first = SoulNames.forEnvName("Ember", false, List.of("Wisp"), store, souls, claimed);
        var second = SoulNames.forEnvName("Wisp", true, List.of("Ember"), store, souls, claimed);
        assertEquals("companion-ember", first.entityId());
        assertEquals("companion-wisp", second.entityId(), "Wisp answers to nobody here: a birth, not a rename of Ember");
        assertEquals(1, store.listLatest().size(), "the birth happens at spawn, not here");
        assertTrue(claimed.contains("companion-ember") && claimed.contains("companion-wisp"));
    }

    @Test
    @DisplayName("a soul not born here (a visitor's) is never renamed or claimed")
    void foreignSoulsAreNotClaimed(@TempDir Path dir) throws Exception {
        var store = new SqlSoulStore(SchemaInitializer.initialize(dir.resolve("w.db")));
        var souls = Files.createDirectories(dir.resolve("souls"));
        var p = Companions.additionalCompanion("Visitor");
        store.store(SoulManifest.forge("did:key:z6MkVisitor", "z6MkPub", List.of(), null, 1,
            p, "", List.of(), 3, "", GenomeProfile.defaults(), List.of(), CompactedMemory.empty(), List.of(),
            List.of(), Map.of(), VitalitySnapshot.defaults(), BehavioralFingerprint.empty())
            .withManifestVersion(1, Instant.now()));   // no .did file: not born here
        var mine = SoulNames.forEnvName("Alduin", false, List.of(), store, souls, new HashSet<>());
        assertEquals("companion-alduin", mine.entityId(), "a birth — the visitor's soul is not ours to rename");
        assertEquals("Visitor", store.latest("did:key:z6MkVisitor").orElseThrow().profile().name());
    }
}
