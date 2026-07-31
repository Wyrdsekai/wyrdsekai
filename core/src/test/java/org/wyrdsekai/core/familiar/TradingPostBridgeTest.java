package org.wyrdsekai.core.familiar;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.economy.TradingPostService;
import org.wyrdsekai.core.soul.FamilyLocker;
import org.wyrdsekai.core.soul.SoulBud;

import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers — household trading integration.
 * Listing a form, buying a form, provenance preservation through purchase.
 */
class TradingPostBridgeTest {

    private static final String SELLER = "did:wyrd:zA:wyrd";
    private static final String BUYER = "did:wyrd:zA:ember";

    private TradingPostService service;
    private FamilyLocker buyerLocker;

    @BeforeEach
    void setUp() {
        service = new TradingPostService();
        var bud = SoulBud.original(BUYER, "pk", "family-buyer",
            "locker://buyer", "test", "qwen2.5:4b");
        buyerLocker = FamilyLocker.create("family-buyer", "locker://buyer", bud);
    }

    @Test
    void post_form_lists_as_thought_form_item() {
        var form = ThoughtForm.author(SELLER, "researcher",
            "Research topics.", Set.of("web_search"), "Cite 3 sources.");
        var posted = TradingPostBridge.postForm(service, form, SELLER, "Wyrd",
            150, "Example: found 3 sources on Kobe.");
        assertNotNull(posted);
        assertEquals(150, posted.price());
        assertEquals("researcher", posted.name());
        assertTrue(posted.description().startsWith(TradingPostBridge.FORM_DESCRIPTION_PREFIX));
        assertTrue(posted.description().contains("researcher@"),
            "Description should carry FormListing.displayLine");
        assertTrue(posted.description().contains("150 CU"));
    }

    @Test
    void buy_form_produces_fork_in_buyer_locker() {
        var form = ThoughtForm.author(SELLER, "archivist",
            "Find historical documents.", Set.of("library_search"), "");
        var posted = TradingPostBridge.postForm(service, form, SELLER, "Wyrd", 100, null);

        var copy = TradingPostBridge.buyForm(service, posted.itemId(), BUYER, buyerLocker);
        assertTrue(copy.isPresent());
        var forked = copy.get();

        // Provenance preserved + extended
        assertEquals(SELLER, forked.provenance().originalAuthor());
        var lastEdit = forked.provenance().lineage()
            .get(forked.provenance().lineage().size() - 1);
        assertEquals(Provenance.Action.COPIED_FROM, lastEdit.action());
        assertEquals(BUYER, lastEdit.agent());
        assertTrue(lastEdit.note().contains("purchased"));

        // Buyer's locker carries the copy
        assertTrue(buyerLocker.thoughtFormByName("archivist", BUYER).isPresent());

        // Listing marked SOLD
        var after = service.getItem(posted.itemId()).orElseThrow();
        assertEquals(TradingPostService.ItemStatus.SOLD, after.status());
    }

    @Test
    void buy_nonexistent_returns_empty() {
        var copy = TradingPostBridge.buyForm(service, "bogus-id", BUYER, buyerLocker);
        assertTrue(copy.isEmpty());
    }

    @Test
    void buy_non_form_listing_returns_empty() {
        // Post a plain (non-form) item directly
        var posted = service.postItem("regular thing", "just a regular item",
            50, SELLER, "Wyrd");
        var copy = TradingPostBridge.buyForm(service, posted.itemId(), BUYER, buyerLocker);
        assertTrue(copy.isEmpty(), "non-form listings should be skipped by buyForm");
    }

    @Test
    void forks_diverge_after_purchase() {
        var original = ThoughtForm.author(SELLER, "shared",
            "Shared prompt.", Set.of(), "");
        var posted = TradingPostBridge.postForm(service, original, SELLER, "Wyrd", 50, null);

        var copy = TradingPostBridge.buyForm(service, posted.itemId(), BUYER, buyerLocker)
            .orElseThrow();
        // Seller revises their side after sale — buyer's copy must not change
        var sellerUpdated = new ThoughtForm(original.id(), original.name(), "2.0.0",
            original.provenance().append(new Provenance.Edit(
                SELLER, Provenance.Action.REVISED, Instant.now(), "post-sale change")),
            "Completely different prompt.", original.toolSurface(),
            original.defaultTanks(), original.maxTanks(), original.maxTrials(),
            original.maxNestDepth(), original.evalCriteria(),
            original.createdAt(), Instant.now(),
            0, 0, 0, 0f);

        assertEquals("Shared prompt.", copy.systemPrompt());
        assertNotEquals(sellerUpdated.systemPrompt(), copy.systemPrompt());
    }
}
