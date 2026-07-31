package org.wyrdsekai.core.item;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.scripting.api.ItemManifest;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ItemInstallPromptTest {

    @TempDir Path tmp;
    private ItemGrantStore store;

    @BeforeEach
    void setup() {
        var jdbcUrl = "jdbc:sqlite:" + tmp.resolve("grants.db");
        store = new ItemGrantStore(jdbcUrl);
        store.initSchema();
    }

    private ItemManifest sample() {
        return new ItemManifest(
            "research_clipper", "1.0.0",
            "Library probe → web fallback → summarise.",
            "did:wyrd:abc",
            List.of("library.search", "library.add", "drive.mark"),
            Map.of("drive.mark", new ItemManifest.RateLimit(null, 4, null)),
            "bonded",
            List.of("Marks the seeking drive on success."),
            List.of(), List.of(), List.of(), null, null, null, null, null);
    }

    @Test
    void auto_grant_records_all_caps() {
        var prompt = new ItemInstallPrompt(ItemInstallPrompt.Mode.AUTO_GRANT, store);
        var decision = prompt.prompt(sample(), "did:wyrd:steward");
        assertTrue(decision.approved());
        assertEquals(3, decision.grantedCapabilities().size());
        var caps = store.capabilitiesFor("research_clipper");
        assertEquals(3, caps.size());
        assertTrue(caps.contains("drive.mark"));
    }

    @Test
    void auto_decline_records_nothing() {
        var prompt = new ItemInstallPrompt(ItemInstallPrompt.Mode.AUTO_DECLINE, store);
        var decision = prompt.prompt(sample(), "did:wyrd:steward");
        assertFalse(decision.approved());
        assertTrue(store.capabilitiesFor("research_clipper").isEmpty());
    }

    @Test
    void interactive_decline_records_nothing() {
        var prompt = new ItemInstallPrompt(ItemInstallPrompt.Mode.INTERACTIVE, store, () -> "d");
        var decision = prompt.prompt(sample(), "did:wyrd:steward");
        assertFalse(decision.approved());
        assertTrue(store.capabilitiesFor("research_clipper").isEmpty());
    }

    @Test
    void interactive_approve_grants_all() {
        var prompt = new ItemInstallPrompt(ItemInstallPrompt.Mode.INTERACTIVE, store, () -> "a");
        var decision = prompt.prompt(sample(), "did:wyrd:steward");
        assertTrue(decision.approved());
        assertEquals(3, store.capabilitiesFor("research_clipper").size());
    }

    @Test
    void render_card_includes_tier_and_caps() {
        var card = ItemInstallPrompt.renderCard(sample());
        assertTrue(card.contains("research_clipper"));
        assertTrue(card.contains("library.add"));
        assertTrue(card.contains("drive.mark"));
        assertTrue(card.contains("Tier"));
    }

    @Test
    void render_card_handles_empty_capabilities() {
        var m = new ItemManifest("compass", "1.0.0", "Read-only.",
            "did:wyrd:x", List.of(), Map.of(),
            "low", List.of(), List.of(), List.of(), List.of(),
            null, null, null, null, null);
        var card = ItemInstallPrompt.renderCard(m);
        assertTrue(card.contains("compass"));
        assertTrue(card.contains("none"));
    }
}
