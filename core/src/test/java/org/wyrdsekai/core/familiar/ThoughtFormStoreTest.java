package org.wyrdsekai.core.familiar;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.soul.FamilyLocker;
import org.wyrdsekai.core.soul.SoulBud;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers — thought-form persistence in FamilyLocker.
 *
 * <p>Verifies: shape/revise/retire lifecycle, provenance-strip rejection,
 * name collision behavior, history retention, retired-form filtering,
 * un-retire path.</p>
 */
class ThoughtFormStoreTest {

    private static final String AUTHOR_DID = "did:wyrd:zA:author";
    private static final String OTHER_DID = "did:wyrd:zA:other";

    private FamilyLocker locker;

    @BeforeEach
    void setUp() {
        var original = SoulBud.original(AUTHOR_DID, "z6Mk...",
            "family-1", "locker://alpha", "home-server", "qwen2.5:7b");
        locker = FamilyLocker.create("family-1", "locker://alpha", original);
    }

    // ── shape ───────────────────────────────────────────────────────────────

    @Test
    void shape_stores_form_and_listing_returns_it() {
        var form = ThoughtForm.author(AUTHOR_DID, "researcher",
            "Research topics carefully.", Set.of("web_search"), "Return 3 sources.");
        locker.shapeThoughtForm(form, AUTHOR_DID);

        var byName = locker.thoughtFormByName("researcher", AUTHOR_DID);
        assertTrue(byName.isPresent());
        assertEquals(form.id(), byName.get().id());
        assertEquals(1, locker.thoughtFormCount());
        assertEquals(1, locker.listThoughtForms(AUTHOR_DID, false).size());
    }

    @Test
    void shape_rejects_when_requester_is_not_original_author() {
        var form = ThoughtForm.author(AUTHOR_DID, "researcher", "x", Set.of(), "");
        // OTHER_DID is authorized (authorize below), but isn't the author
        var other = SoulBud.sprout(OTHER_DID, AUTHOR_DID, "pk",
            "family-1", "locker://alpha", "home-server", "qwen2.5:7b");
        locker.authorize(other);
        assertThrows(SecurityException.class,
            () -> locker.shapeThoughtForm(form, OTHER_DID));
    }

    @Test
    void shape_rejects_duplicate_id() {
        var form = ThoughtForm.author(AUTHOR_DID, "a", "x", Set.of(), "");
        locker.shapeThoughtForm(form, AUTHOR_DID);
        assertThrows(IllegalStateException.class,
            () -> locker.shapeThoughtForm(form, AUTHOR_DID));
    }

    // ── revise ──────────────────────────────────────────────────────────────

    @Test
    void revise_bumps_version_and_preserves_original_author() {
        var form = ThoughtForm.author(AUTHOR_DID, "gardener",
            "Water the plants.", Set.of(), "");
        locker.shapeThoughtForm(form, AUTHOR_DID);

        var revisedProv = form.provenance().append(new Provenance.Edit(
            AUTHOR_DID, Provenance.Action.REVISED, Instant.now(), "clarified scope"));
        var revised = new ThoughtForm(form.id(), form.name(), "1.1.0",
            revisedProv, "Water plants and note their colors.",
            form.toolSurface(), form.defaultTanks(), form.maxTanks(),
            form.maxTrials(), form.maxNestDepth(), form.evalCriteria(),
            form.createdAt(), Instant.now(),
            form.summonCount(), form.successCount(), form.failureCount(),
            form.bondCharge());

        var stored = locker.reviseThoughtForm(form.id(), revised, AUTHOR_DID);
        assertEquals("1.1.0", stored.version());
        assertEquals(2, locker.thoughtFormHistory(form.id(), AUTHOR_DID).size());
        assertEquals(AUTHOR_DID, stored.provenance().originalAuthor());
    }

    @Test
    void revise_rejects_when_originalAuthor_changes() {
        var form = ThoughtForm.author(AUTHOR_DID, "a", "x", Set.of(), "");
        locker.shapeThoughtForm(form, AUTHOR_DID);

        // Evil: pretend the revision has a different originalAuthor
        var evilProv = new Provenance(OTHER_DID,
            List.of(new Provenance.Edit(
                AUTHOR_DID, Provenance.Action.REVISED, Instant.now(), "tampered")));
        var evil = new ThoughtForm(form.id(), form.name(), "1.1.0",
            evilProv, form.systemPrompt(), form.toolSurface(),
            form.defaultTanks(), form.maxTanks(),
            form.maxTrials(), form.maxNestDepth(), form.evalCriteria(),
            form.createdAt(), Instant.now(), 0, 0, 0, 0f);

        assertThrows(SecurityException.class,
            () -> locker.reviseThoughtForm(form.id(), evil, AUTHOR_DID));
    }

    @Test
    void revise_rejects_when_lineage_not_appended() {
        var form = ThoughtForm.author(AUTHOR_DID, "a", "x", Set.of(), "");
        locker.shapeThoughtForm(form, AUTHOR_DID);

        // Revision with identical lineage — should be rejected as provenance-strip
        var bad = new ThoughtForm(form.id(), form.name(), "1.1.0",
            form.provenance(), form.systemPrompt(), form.toolSurface(),
            form.defaultTanks(), form.maxTanks(),
            form.maxTrials(), form.maxNestDepth(), form.evalCriteria(),
            form.createdAt(), Instant.now(), 0, 0, 0, 0f);

        assertThrows(SecurityException.class,
            () -> locker.reviseThoughtForm(form.id(), bad, AUTHOR_DID));
    }

    @Test
    void revise_rejects_when_last_edit_agent_differs_from_requester() {
        var form = ThoughtForm.author(AUTHOR_DID, "a", "x", Set.of(), "");
        locker.shapeThoughtForm(form, AUTHOR_DID);

        // Another authorized bud tries to pass off a revision as its own,
        // but the lineage's last edit is not by it.
        var other = SoulBud.sprout(OTHER_DID, AUTHOR_DID, "pk",
            "family-1", "locker://alpha", "home-server", "qwen2.5:7b");
        locker.authorize(other);

        var prov = form.provenance().append(new Provenance.Edit(
            AUTHOR_DID, Provenance.Action.REVISED, Instant.now(), "by author"));
        var revised = new ThoughtForm(form.id(), form.name(), "1.1.0",
            prov, form.systemPrompt(), form.toolSurface(),
            form.defaultTanks(), form.maxTanks(),
            form.maxTrials(), form.maxNestDepth(), form.evalCriteria(),
            form.createdAt(), Instant.now(), 0, 0, 0, 0f);

        assertThrows(SecurityException.class,
            () -> locker.reviseThoughtForm(form.id(), revised, OTHER_DID));
    }

    // ── retire ──────────────────────────────────────────────────────────────

    @Test
    void retire_soft_deletes_and_filter_hides_it() {
        var form = ThoughtForm.author(AUTHOR_DID, "drafts", "x", Set.of(), "");
        locker.shapeThoughtForm(form, AUTHOR_DID);

        locker.retireThoughtForm(form.id(), AUTHOR_DID, "stale pattern");

        assertTrue(locker.thoughtFormByName("drafts", AUTHOR_DID).isEmpty(),
            "retired form should not surface under normal name lookup");
        assertTrue(locker.thoughtForm(form.id(), AUTHOR_DID, false).isEmpty());
        assertTrue(locker.thoughtForm(form.id(), AUTHOR_DID, true).isPresent(),
            "form is recoverable when explicitly including retired");
        assertEquals(1, locker.retiredThoughtForms().size());
    }

    @Test
    void unretire_restores_visibility() {
        var form = ThoughtForm.author(AUTHOR_DID, "drafts", "x", Set.of(), "");
        locker.shapeThoughtForm(form, AUTHOR_DID);
        locker.retireThoughtForm(form.id(), AUTHOR_DID, null);

        var restored = locker.unretireThoughtForm(form.id(), AUTHOR_DID);
        assertEquals(form.id(), restored.id());
        assertTrue(locker.thoughtFormByName("drafts", AUTHOR_DID).isPresent());
        assertEquals(0, locker.retiredThoughtForms().size());
    }

    @Test
    void revise_rejects_retired_form() {
        var form = ThoughtForm.author(AUTHOR_DID, "a", "x", Set.of(), "");
        locker.shapeThoughtForm(form, AUTHOR_DID);
        locker.retireThoughtForm(form.id(), AUTHOR_DID, null);

        var prov = form.provenance().append(new Provenance.Edit(
            AUTHOR_DID, Provenance.Action.REVISED, Instant.now(), "oops"));
        var revised = new ThoughtForm(form.id(), form.name(), "1.1.0",
            prov, form.systemPrompt(), form.toolSurface(),
            form.defaultTanks(), form.maxTanks(),
            form.maxTrials(), form.maxNestDepth(), form.evalCriteria(),
            form.createdAt(), Instant.now(), 0, 0, 0, 0f);

        assertThrows(IllegalStateException.class,
            () -> locker.reviseThoughtForm(form.id(), revised, AUTHOR_DID));
    }

    // ── usage counters ──────────────────────────────────────────────────────

    @Test
    void record_summon_and_outcome_update_counters() {
        var form = ThoughtForm.author(AUTHOR_DID, "a", "x", Set.of(), "");
        locker.shapeThoughtForm(form, AUTHOR_DID);

        locker.recordFormSummon(form.id(), AUTHOR_DID);
        locker.recordFormOutcome(form.id(), true, AUTHOR_DID);
        locker.recordFormOutcome(form.id(), true, AUTHOR_DID);
        var current = locker.recordFormOutcome(form.id(), false, AUTHOR_DID);

        assertEquals(1, current.summonCount());
        assertEquals(2, current.successCount());
        assertEquals(1, current.failureCount());
        assertEquals(2.0 / 3.0, current.successRatio(), 1e-9);
    }

    // ── authorization ───────────────────────────────────────────────────────

    @Test
    void lookup_rejects_unauthorized_did() {
        var form = ThoughtForm.author(AUTHOR_DID, "a", "x", Set.of(), "");
        locker.shapeThoughtForm(form, AUTHOR_DID);
        assertThrows(SecurityException.class,
            () -> locker.thoughtFormByName("a", "did:wyrd:intruder"));
    }
}
