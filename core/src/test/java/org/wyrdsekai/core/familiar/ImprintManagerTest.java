package org.wyrdsekai.core.familiar;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.agent.AgentProfile;
import org.wyrdsekai.core.soul.BehavioralFingerprint;
import org.wyrdsekai.core.soul.CompactedMemory;
import org.wyrdsekai.core.soul.GenomeProfile;
import org.wyrdsekai.core.soul.SoulFragment;
import org.wyrdsekai.core.soul.SoulManifest;
import org.wyrdsekai.core.soul.VitalitySnapshot;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers — imprints, retention, restore semantics.
 */
class ImprintManagerTest {

    private static final String DID = "did:wyrd:zA:wyrd-primary";

    private ImprintManager manager;

    @BeforeEach
    void setUp() {
        manager = new ImprintManager(DID);
    }

    private SoulManifest manifest(String residentIdentity) {
        return manifest(residentIdentity, DID);
    }

    private SoulManifest manifest(String residentIdentity, String did) {
        var profile = new AgentProfile("TestAgent", "entity-1", "agent",
            "Test", "System", 4096, 512, 0.7, did);
        return SoulManifest.forge(
            did, "z6Mk", List.of(), null, 1,
            profile, residentIdentity,
            List.of(SoulFragment.unembedded("id", "cat", "Core", "text")),
            3, "",
            GenomeProfile.defaults(), List.of(),
            CompactedMemory.empty(), List.of(), List.of(), Map.of(),
            VitalitySnapshot.defaults(), BehavioralFingerprint.empty()
        );
    }

    // ── create + restore round-trip ─────────────────────────────────────────

    @Test
    void create_and_restore_round_trip() {
        var m = manifest("I am a researcher.");
        var imprint = manager.imprint(Imprint.CreatedBy.SELF,
            "before forms existed", m);

        assertEquals(1, manager.count());
        assertEquals(DID, imprint.agentDid());
        assertTrue(imprint.size() > 0);

        var restored = manager.restore(imprint.id());
        assertEquals("I am a researcher.", restored.residentIdentity());
    }

    @Test
    void restore_nonexistent_throws() {
        assertThrows(NoSuchElementException.class,
            () -> manager.restore("bogus-id"));
    }

    @Test
    void manifest_did_must_match_manager_did() {
        var wrong = manifest("x", "did:wyrd:intruder");
        assertThrows(IllegalArgumentException.class,
            () -> manager.imprint(Imprint.CreatedBy.SELF, "wrong did", wrong));
    }

    // ── retention policy ────────────────────────────────────────────────────

    @Test
    void self_retention_caps_at_default() {
        // Create 12 SELF imprints — only 10 most-recent should remain
        for (int i = 0; i < 12; i++) {
            manager.imprint(Imprint.CreatedBy.SELF, "v" + i,
                manifest("identity-" + i));
        }
        var selfCount = manager.byCreator(Imprint.CreatedBy.SELF).size();
        assertEquals(ImprintManager.DEFAULT_SELF_RETENTION, selfCount);
        // The 2 oldest should be evicted — newest ("v11") preserved
        var newest = manager.byCreator(Imprint.CreatedBy.SELF).get(0);
        assertEquals("v11", newest.label());
    }

    @Test
    void auto_milestones_never_evicted() {
        // Add 5 AUTO_MILESTONE imprints, then flood with SELF imprints
        for (int i = 0; i < 5; i++) {
            manager.imprint(Imprint.CreatedBy.AUTO_MILESTONE,
                "milestone-" + i, manifest("m" + i));
        }
        for (int i = 0; i < 20; i++) {
            manager.imprint(Imprint.CreatedBy.SELF, "s" + i, manifest("s" + i));
        }
        assertEquals(5, manager.byCreator(Imprint.CreatedBy.AUTO_MILESTONE).size(),
            "AUTO_MILESTONE imprints must survive SELF flooding");
        assertEquals(ImprintManager.DEFAULT_SELF_RETENTION,
            manager.byCreator(Imprint.CreatedBy.SELF).size());
    }

    @Test
    void user_requests_never_evicted_automatically() {
        for (int i = 0; i < 3; i++) {
            manager.imprint(Imprint.CreatedBy.USER_REQUEST,
                "user-" + i, manifest("u" + i));
        }
        for (int i = 0; i < 15; i++) {
            manager.imprint(Imprint.CreatedBy.SELF, "s" + i, manifest("s" + i));
        }
        assertEquals(3, manager.byCreator(Imprint.CreatedBy.USER_REQUEST).size());
    }

    @Test
    void steward_interventions_never_evicted() {
        manager.imprint(Imprint.CreatedBy.STEWARD_INTERVENTION,
            "emergency reset 2026-04-21", manifest("pre-intervention"));
        for (int i = 0; i < 15; i++) {
            manager.imprint(Imprint.CreatedBy.SELF, "s" + i, manifest("s" + i));
        }
        assertEquals(1, manager.byCreator(Imprint.CreatedBy.STEWARD_INTERVENTION).size());
    }

    // ── manual delete ───────────────────────────────────────────────────────

    @Test
    void user_can_delete_their_own_imprint() {
        var imp = manager.imprint(Imprint.CreatedBy.USER_REQUEST,
            "save me", manifest("x"));
        assertTrue(manager.delete(imp.id()));
        assertFalse(manager.get(imp.id()).isPresent());
        assertFalse(manager.delete("nothing"));
    }

    // ── label + creator lookup ──────────────────────────────────────────────

    @Test
    void lookup_by_label_returns_most_recent_match() throws Exception {
        manager.imprint(Imprint.CreatedBy.SELF, "pre-bond", manifest("old"));
        Thread.sleep(10);
        manager.imprint(Imprint.CreatedBy.SELF, "pre-bond", manifest("new"));

        var byLabel = manager.byLabel("pre-bond").orElseThrow();
        assertEquals("new", byLabel.manifest().residentIdentity());
    }

    @Test
    void latest_by_creator_returns_most_recent() throws Exception {
        manager.imprint(Imprint.CreatedBy.AUTO_MILESTONE, "a", manifest("1"));
        Thread.sleep(10);
        manager.imprint(Imprint.CreatedBy.AUTO_MILESTONE, "b", manifest("2"));

        var latest = manager.latestByCreator(Imprint.CreatedBy.AUTO_MILESTONE)
            .orElseThrow();
        assertEquals("b", latest.label());
    }

    // ── custom retention cap ────────────────────────────────────────────────

    @Test
    void custom_retention_is_honored() {
        var tight = new ImprintManager(DID, 3);
        for (int i = 0; i < 5; i++) {
            tight.imprint(Imprint.CreatedBy.SELF, "v" + i, manifest("v" + i));
        }
        assertEquals(3, tight.byCreator(Imprint.CreatedBy.SELF).size());
    }

    // ── totals ──────────────────────────────────────────────────────────────

    @Test
    void total_size_sums_imprint_sizes() {
        var a = manager.imprint(Imprint.CreatedBy.SELF, "a", manifest("a"));
        var b = manager.imprint(Imprint.CreatedBy.SELF, "b", manifest("b"));
        assertEquals(a.size() + b.size(), manager.totalSize());
    }
}
