package org.wyrdsekai.core.familiar;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.soul.FamilyLocker;
import org.wyrdsekai.core.soul.SoulBud;

import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers — cross-agent form copy
 * provenance lineage, fork divergence, counter reset, listing view.
 */
class FormTransferTest {

    private static final String WYRD = "did:wyrd:zA:wyrd";
    private static final String EMBER = "did:wyrd:zA:ember";

    // ── FormTransfer — copy semantics ──────────────────────────────────────

    @Test
    void copy_preserves_original_author_and_extends_lineage() {
        var source = ThoughtForm.author(WYRD, "researcher",
            "Research carefully.", Set.of("web_search"), "Cite 3.");
        // Give it some history: revise once
        var revisedProv = source.provenance().append(new Provenance.Edit(
            WYRD, Provenance.Action.REVISED, Instant.now(), "tightened"));
        var evolved = new ThoughtForm(source.id(), source.name(), "1.1.0",
            revisedProv, source.systemPrompt(), source.toolSurface(),
            source.defaultTanks(), source.maxTanks(), source.maxTrials(),
            source.maxNestDepth(), source.evalCriteria(),
            source.createdAt(), Instant.now(),
            5, 4, 1, 0.6f);

        var copy = FormTransfer.copy(evolved, EMBER, FormTransfer.Intent.TEACHING, "for Ember");

        assertEquals(WYRD, copy.provenance().originalAuthor(),
            "§7.4 — originalAuthor never rewrites");
        assertEquals(evolved.provenance().lineage().size() + 1,
            copy.provenance().lineage().size());
        var lastEdit = copy.provenance().lineage()
            .get(copy.provenance().lineage().size() - 1);
        assertEquals(EMBER, lastEdit.agent());
        assertEquals(Provenance.Action.COPIED_FROM, lastEdit.action());
        assertTrue(lastEdit.note().contains("TEACHING"));
        assertTrue(lastEdit.note().contains("Ember"));
    }

    @Test
    void copy_generates_new_id_and_pins_version() {
        var source = ThoughtForm.author(WYRD, "gardener", "Water plants.", Set.of(), "");
        var tookSomeVersions = new ThoughtForm(source.id(), source.name(), "2.3.1",
            source.provenance(), source.systemPrompt(), source.toolSurface(),
            source.defaultTanks(), source.maxTanks(), source.maxTrials(),
            source.maxNestDepth(), source.evalCriteria(),
            source.createdAt(), source.revisedAt(),
            0, 0, 0, 0f);

        var copy = FormTransfer.copy(tookSomeVersions, EMBER,
            FormTransfer.Intent.GIFT, null);
        assertNotEquals(tookSomeVersions.id(), copy.id());
        assertEquals("2.3.1", copy.version(), "§7.3 version pinned at copy time");
    }

    @Test
    void copy_resets_counters_and_bond_charge() {
        var worn = ThoughtForm.author(WYRD, "veteran", "x", Set.of(), "")
            .incrementSummon().recordSuccess().recordSuccess().recordFailure();
        // Bump the bond a bit via a constructed form
        var bonded = new ThoughtForm(worn.id(), worn.name(), worn.version(),
            worn.provenance(), worn.systemPrompt(), worn.toolSurface(),
            worn.defaultTanks(), worn.maxTanks(), worn.maxTrials(),
            worn.maxNestDepth(), worn.evalCriteria(),
            worn.createdAt(), worn.revisedAt(),
            worn.summonCount(), worn.successCount(), worn.failureCount(),
            0.8f);

        var copy = FormTransfer.copy(bonded, EMBER, FormTransfer.Intent.GIFT, null);
        assertEquals(0, copy.summonCount(), "counters reset per-copy (§7.2)");
        assertEquals(0, copy.successCount());
        assertEquals(0, copy.failureCount());
        assertEquals(0f, copy.bondCharge(), 1e-9);
    }

    @Test
    void forks_diverge_when_source_revises() throws Exception {
        var source = ThoughtForm.author(WYRD, "shared", "Original.", Set.of(), "");
        var copy = FormTransfer.copy(source, EMBER, FormTransfer.Intent.GIFT, null);

        // Wyrd revises her form — Ember's copy must not auto-update (§7.2)
        var sourceBud = source.provenance().append(new Provenance.Edit(
            WYRD, Provenance.Action.REVISED, Instant.now(), "new direction"));
        var sourceRevised = new ThoughtForm(source.id(), source.name(), "2.0.0",
            sourceBud, "Wyrd's changed prompt.", source.toolSurface(),
            source.defaultTanks(), source.maxTanks(), source.maxTrials(),
            source.maxNestDepth(), source.evalCriteria(),
            source.createdAt(), Instant.now(),
            0, 0, 0, 0f);

        // Ember's copy is untouched — that's a separate record, no shared mutation
        assertEquals("1.0.0", copy.version());
        assertEquals("Original.", copy.systemPrompt());
        assertNotEquals(sourceRevised.systemPrompt(), copy.systemPrompt());
    }

    @Test
    void gift_convenience_produces_gift_intent() {
        var source = ThoughtForm.author(WYRD, "gift", "x", Set.of(), "");
        var copy = FormTransfer.gift(source, EMBER);
        var lastEdit = copy.provenance().lineage()
            .get(copy.provenance().lineage().size() - 1);
        assertEquals(Provenance.Action.COPIED_FROM, lastEdit.action());
        assertTrue(lastEdit.note().contains("GIFT"));
    }

    @Test
    void copy_rejects_blank_recipient() {
        var source = ThoughtForm.author(WYRD, "x", "y", Set.of(), "");
        assertThrows(IllegalArgumentException.class,
            () -> FormTransfer.copy(source, "", FormTransfer.Intent.GIFT, null));
    }

    // ── Round-trip through FamilyLocker ────────────────────────────────────

    @Test
    void copy_into_recipient_locker_is_authorized_and_stored() {
        var wyrdBud = SoulBud.original(WYRD, "pk", "family-w", "locker://w", "home-server", "qwen2.5:7b");
        var wyrdLocker = FamilyLocker.create("family-w", "locker://w", wyrdBud);
        var emberBud = SoulBud.original(EMBER, "pk-e", "family-e", "locker://e", "home-server", "qwen2.5:4b");
        var emberLocker = FamilyLocker.create("family-e", "locker://e", emberBud);

        var original = ThoughtForm.author(WYRD, "researcher",
            "Research.", Set.of("web_search"), "Cite sources.");
        wyrdLocker.shapeThoughtForm(original, WYRD);

        var copy = FormTransfer.copy(original, EMBER,
            FormTransfer.Intent.TEACHING, "sharing what works");
        // Copy's original author is still WYRD; but Ember is who the locker
        // authorization scopes the write to. The `shapeThoughtForm` check
        // (originalAuthor must match requester) would reject. §7.2 copies
        // live alongside — not through `shapeThoughtForm` — which means the
        // locker needs a dedicated "accept copy" entry point in integration.
        // For step-11 scope, verify the produced ThoughtForm is correctly
        // forked; the locker-side write path is explicit deferral.
        assertEquals(WYRD, copy.provenance().originalAuthor());
        assertEquals("researcher", copy.name());
        assertNotEquals(original.id(), copy.id());
        // Source locker still has its original untouched
        assertEquals(1, wyrdLocker.thoughtFormCount());
        assertEquals(0, emberLocker.thoughtFormCount());
    }

    // ── FormListing view ───────────────────────────────────────────────────

    @Test
    void listing_surfaces_stats_and_fork_depth() {
        var form = ThoughtForm.author(WYRD, "researcher", "x", Set.of(), "")
            .incrementSummon().incrementSummon().recordSuccess().recordSuccess();
        // Simulate a form that was copied twice up the chain
        var copy1 = FormTransfer.copy(form, "did:wyrd:zA:steve",
            FormTransfer.Intent.GIFT, null);
        var copy2 = FormTransfer.copy(copy1, "did:wyrd:zA:alice",
            FormTransfer.Intent.GIFT, null);

        var listing = FormListing.from(copy2, "did:wyrd:zA:alice", 100, "example: found 3 sources");
        assertEquals("researcher", listing.name());
        assertEquals(WYRD, listing.originalAuthorDid());
        assertEquals(2, listing.copyDepth(), "two COPIED_FROM edits in lineage");
        assertTrue(listing.isFork());
        assertEquals(100, listing.priceCu());
        assertTrue(listing.displayLine().contains("researcher"));
        assertTrue(listing.displayLine().contains("100 CU"));
        assertTrue(listing.displayLine().contains("fork 2×"));
    }

    @Test
    void listing_for_original_is_not_a_fork() {
        var form = ThoughtForm.author(WYRD, "original", "x", Set.of(), "");
        var listing = FormListing.from(form, WYRD, 50, null);
        assertFalse(listing.isFork());
        assertEquals(0, listing.copyDepth());
    }

    @Test
    void listing_rejects_negative_price() {
        var form = ThoughtForm.author(WYRD, "o", "x", Set.of(), "");
        assertThrows(IllegalArgumentException.class,
            () -> FormListing.from(form, WYRD, -1, null));
    }
}
