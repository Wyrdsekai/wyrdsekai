package org.wyrdsekai.core.familiar;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.agent.AgentProfile;
import org.wyrdsekai.core.soul.BehavioralFingerprint;
import org.wyrdsekai.core.soul.CompactedMemory;
import org.wyrdsekai.core.soul.FamilyLocker;
import org.wyrdsekai.core.soul.GenomeProfile;
import org.wyrdsekai.core.soul.SoulBud;
import org.wyrdsekai.core.soul.SoulFragment;
import org.wyrdsekai.core.soul.SoulManifest;
import org.wyrdsekai.core.soul.VitalitySnapshot;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers round-trip persistence of FamilyLocker (thought forms + named
 * familiars), imprints, and summon keys.
 */
class FamiliarPersistenceStoreTest {

    private static final String DID = "did:wyrd:zA:wyrd-persist-test";

    private FamilyLocker newLocker() {
        var bud = SoulBud.original(DID, "pk", "family-test",
            "locker://test", "test-node", "qwen2.5:7b");
        return FamilyLocker.create("family-test", "locker://test", bud);
    }

    private SoulManifest manifest() {
        var profile = new AgentProfile("Test", "entity-1", "agent",
            "t", "sys", 4096, 512, 0.7, DID);
        return SoulManifest.forge(
            DID, "z6MkTest", List.of(), null, 1,
            profile, "I am testing.",
            List.of(SoulFragment.unembedded("id", "personality", "Core", "x")),
            3, "",
            GenomeProfile.defaults(), List.of(),
            CompactedMemory.empty(), List.of(), List.of(), Map.of(),
            VitalitySnapshot.defaults(), BehavioralFingerprint.empty());
    }

    // ── FamilyLocker round-trip ────────────────────────────────────────────

    @Test
    void family_locker_forms_round_trip(@TempDir Path tmp) {
        var store = new FamiliarPersistenceStore(DID, tmp);
        var src = newLocker();

        var form = ThoughtForm.author(DID, "researcher",
            "Research carefully.", Set.of("web_search"), "Cite 3.");
        src.shapeThoughtForm(form, DID);
        src.recordFormSummon(form.id(), DID);
        src.recordFormOutcome(form.id(), true, DID);

        store.saveFamilyLocker(src);

        // New fresh locker, load from disk
        var dst = newLocker();
        store.loadFamilyLocker(dst);

        var loaded = dst.thoughtFormByName("researcher", DID).orElseThrow();
        assertEquals(form.id(), loaded.id());
        assertEquals("researcher", loaded.name());
        assertEquals(DID, loaded.provenance().originalAuthor());
        assertEquals(1, loaded.summonCount());
        assertEquals(1, loaded.successCount());
        assertTrue(loaded.toolSurface().contains("web_search"));
    }

    @Test
    void family_locker_named_familiars_round_trip(@TempDir Path tmp) {
        var store = new FamiliarPersistenceStore(DID, tmp);
        var src = newLocker();

        var form = ThoughtForm.author(DID, "gardener", "Water plants.", Set.of(), "");
        src.shapeThoughtForm(form, DID);
        src.nameFamiliar("ada", DID, form.id(), "tends carefully", DID);
        src.recordNamedSummon("ada", "water the rose", DID);
        src.recordNamedOutcome("ada", Familiar.Status.DONE, 3, "Done.", DID);

        store.saveFamilyLocker(src);

        var dst = newLocker();
        store.loadFamilyLocker(dst);

        var named = dst.namedFamiliar("ada", DID).orElseThrow();
        assertEquals("ada", named.name());
        assertEquals(1, named.summonCount());
        assertEquals(1, named.successCount());
        assertTrue(named.selfContext().contains("tends carefully")
            || named.selfContext().contains("Done"),
            "self-context should carry over");
    }

    @Test
    void family_locker_retired_forms_round_trip(@TempDir Path tmp) {
        var store = new FamiliarPersistenceStore(DID, tmp);
        var src = newLocker();

        var form = ThoughtForm.author(DID, "oldcoder", "x", Set.of(), "");
        src.shapeThoughtForm(form, DID);
        src.retireThoughtForm(form.id(), DID, "no longer needed");

        store.saveFamilyLocker(src);
        var dst = newLocker();
        store.loadFamilyLocker(dst);

        assertTrue(dst.thoughtFormByName("oldcoder", DID).isEmpty(),
            "retired form should stay filtered after reload");
        assertEquals(1, dst.retiredThoughtForms().size());
    }

    // ── Imprint round-trip ─────────────────────────────────────────────────

    @Test
    void imprints_round_trip(@TempDir Path tmp) {
        var store = new FamiliarPersistenceStore(DID, tmp);
        var src = new ImprintManager(DID);
        src.imprint(Imprint.CreatedBy.USER_REQUEST, "before the storm", manifest());
        src.imprint(Imprint.CreatedBy.AUTO_MILESTONE, "first familiar", manifest());

        store.saveImprints(src);

        var dst = new ImprintManager(DID);
        store.loadImprints(dst);

        assertEquals(2, dst.count());
        var byLabel = dst.byLabel("before the storm");
        assertTrue(byLabel.isPresent());
        assertEquals(Imprint.CreatedBy.USER_REQUEST, byLabel.get().createdBy());
    }

    // ── SummonKey round-trip ───────────────────────────────────────────────

    @Test
    void summon_keys_round_trip(@TempDir Path tmp) {
        var store = new FamiliarPersistenceStore(DID, tmp);

        var issued = new ConcurrentHashMap<String, SummonKey>();
        var draft = SummonKey.draft("named:researcher", DID, "did:wyrd:peer",
            SummonKey.Scope.REVOCABLE, Optional.empty(),
            SummonKey.Restrictions.defaults());
        var key = draft.withSignature("FAKE_SIG");
        issued.put(key.id(), key);

        var srcRegistry = new SummonKeyRegistry();
        srcRegistry.recordUse(key.id());
        srcRegistry.recordUse(key.id());
        // also simulate a revoked key
        srcRegistry.loadRevoked("revoked-id-1");

        store.saveSummonKeys(issued, srcRegistry);

        var dstIssued = new ConcurrentHashMap<String, SummonKey>();
        var dstRegistry = new SummonKeyRegistry();
        store.loadSummonKeys(dstIssued, dstRegistry);

        assertEquals(1, dstIssued.size());
        assertEquals(key.id(), dstIssued.values().iterator().next().id());
        assertEquals(2, dstRegistry.usageCount(key.id()));
        assertTrue(dstRegistry.isRevoked("revoked-id-1"));
    }

    // ── save-all / load-all end-to-end ─────────────────────────────────────

    @Test
    void save_and_load_all_together(@TempDir Path tmp) {
        var store = new FamiliarPersistenceStore(DID, tmp);

        var locker = newLocker();
        var form = ThoughtForm.author(DID, "combined", "x", Set.of(), "");
        locker.shapeThoughtForm(form, DID);

        var imprints = new ImprintManager(DID);
        imprints.imprint(Imprint.CreatedBy.SELF, "snap1", manifest());

        var issued = new ConcurrentHashMap<String, SummonKey>();
        var registry = new SummonKeyRegistry();
        var key = SummonKey.draft("named:x", DID, "peer",
            SummonKey.Scope.PERMANENT, Optional.empty(),
            SummonKey.Restrictions.defaults()).withSignature("SIG");
        issued.put(key.id(), key);

        store.saveAll(locker, imprints, issued, registry);

        // Fresh receivers
        var locker2 = newLocker();
        var imprints2 = new ImprintManager(DID);
        var issued2 = new ConcurrentHashMap<String, SummonKey>();
        var registry2 = new SummonKeyRegistry();
        store.loadAll(locker2, imprints2, issued2, registry2);

        assertTrue(locker2.thoughtFormByName("combined", DID).isPresent());
        assertEquals(1, imprints2.count());
        assertEquals(1, issued2.size());
    }

    // ── defensive empty / missing ──────────────────────────────────────────

    @Test
    void load_from_empty_directory_is_noop(@TempDir Path tmp) {
        var store = new FamiliarPersistenceStore(DID, tmp);
        var locker = newLocker();
        store.loadFamilyLocker(locker);
        assertEquals(0, locker.thoughtFormCount());
        assertEquals(0, locker.namedFamiliarCount());
    }

    @Test
    void default_root_slugs_did() {
        var path = FamiliarPersistenceStore.defaultRoot("did:key:z6MkABC");
        assertTrue(path.toString().contains("did_key_z6MkABC"));
    }
}
