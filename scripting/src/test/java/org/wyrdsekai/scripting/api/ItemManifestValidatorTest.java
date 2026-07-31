package org.wyrdsekai.scripting.api;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ItemManifestValidatorTest {

    @Test
    void valid_minimal_manifest_passes() {
        var m = new ItemManifest("compass", "1.0.0", "Shows the time.",
            "did:wyrd:abc", List.of(), Map.of(),
            "low", List.of(), List.of(), List.of(), List.of(),
            null, null, null, null, null);
        var result = ItemManifestValidator.validate(m);
        assertTrue(result.valid(), "errors: " + result.errors());
    }

    @Test
    void rejects_unknown_capability() {
        var m = new ItemManifest("oddball", "1.0.0", "Demo.",
            "did:wyrd:x", List.of("library.invented_method"), Map.of(),
            "low", List.of(), List.of(), List.of(), List.of(),
            null, null, null, null, null);
        var result = ItemManifestValidator.validate(m);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("unknown capability")));
    }

    @Test
    void rejects_bad_name_pattern() {
        var m = new ItemManifest("BAD-NAME", "1.0.0", "Demo.",
            "did:wyrd:x", List.of(), Map.of(),
            "low", List.of(), List.of(), List.of(), List.of(),
            null, null, null, null, null);
        var result = ItemManifestValidator.validate(m);
        assertFalse(result.valid());
    }

    @Test
    void rejects_non_semver_version() {
        var m = new ItemManifest("compass", "v1", "Demo.",
            "did:wyrd:x", List.of(), Map.of(),
            "low", List.of(), List.of(), List.of(), List.of(),
            null, null, null, null, null);
        var result = ItemManifestValidator.validate(m);
        assertFalse(result.valid());
    }

    @Test
    void rejects_non_did_author() {
        var m = new ItemManifest("compass", "1.0.0", "Demo.",
            "alice@example.com", List.of(), Map.of(),
            "low", List.of(), List.of(), List.of(), List.of(),
            null, null, null, null, null);
        var result = ItemManifestValidator.validate(m);
        assertFalse(result.valid());
    }

    @Test
    void web_post_requires_rate_limits_and_domains() {
        var m = new ItemManifest("poster", "1.0.0", "Posts to a webhook.",
            "did:wyrd:x", List.of("web.post"), Map.of(),
            "low", List.of(), List.of(), List.of(), List.of(),
            null, null, null, null, null);
        var result = ItemManifestValidator.validate(m);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("rate_limits")));
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("external_domains")));
    }

    @Test
    void web_post_with_rate_limits_and_domains_passes() {
        var m = new ItemManifest("poster", "1.0.0", "Posts to a webhook.",
            "did:wyrd:x", List.of("web.post"),
            Map.of("web.post", new ItemManifest.RateLimit(10, null, 200)),
            "low", List.of(), List.of("api.example.com"), List.of(), List.of(),
            null, null, null, null, null);
        var result = ItemManifestValidator.validate(m);
        assertTrue(result.valid(), "errors: " + result.errors());
    }

    @Test
    void mcp_invoke_requires_mcp_servers_allowlist() {
        var m = new ItemManifest("mcp_user", "1.0.0", "Calls MCP.",
            "did:wyrd:x", List.of("mcp.invoke"),
            Map.of("mcp.invoke", new ItemManifest.RateLimit(null, null, 50)),
            "low", List.of(), List.of(), List.of(), List.of(),
            null, null, null, null, null);
        var result = ItemManifestValidator.validate(m);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("mcp_servers")));
    }

    @Test
    void tier_inference_for_known_caps() {
        assertEquals(1, ItemManifestValidator.tierFor("self.did"));
        assertEquals(1, ItemManifestValidator.tierFor("library.search"));
        assertEquals(2, ItemManifestValidator.tierFor("library.add"));
        assertEquals(3, ItemManifestValidator.tierFor("room.emit"));
        assertEquals(4, ItemManifestValidator.tierFor("llm.summarize"));
        assertEquals(5, ItemManifestValidator.tierFor("drive.mark"));
        assertEquals(6, ItemManifestValidator.tierFor("agent.give_item"));
        assertEquals(7, ItemManifestValidator.tierFor("council.vote"));
    }

    @Test
    void wildcard_capability_accepted() {
        var m = new ItemManifest("github_bot", "1.0.0", "Posts issues.",
            "did:wyrd:x", List.of("github.*"), Map.of(),
            "low", List.of(), List.of(), List.of(), List.of(),
            null, null, null, null, null);
        var result = ItemManifestValidator.validate(m);
        assertTrue(result.valid(), "wildcard caps accepted, got: " + result.errors());
    }

    @Test
    void max_tier_returns_highest() {
        var m = new ItemManifest("bond_assistant", "1.0.0", "Heavy item.",
            "did:wyrd:x",
            List.of("library.search", "library.add", "drive.mark"),
            Map.of("drive.mark", new ItemManifest.RateLimit(null, 4, null)),
            "low", List.of(), List.of(), List.of(), List.of(),
            null, null, null, null, null);
        assertEquals(5, ItemManifestValidator.maxTier(m));
    }

    @Test
    void description_too_long_rejects() {
        var longDesc = "x".repeat(600);
        var m = new ItemManifest("tooverbose", "1.0.0", longDesc,
            "did:wyrd:x", List.of(), Map.of(),
            "low", List.of(), List.of(), List.of(), List.of(),
            null, null, null, null, null);
        assertFalse(ItemManifestValidator.validate(m).valid());
    }

    @Test
    void low_sensitivity_with_tier5_caps_warns_not_errors() {
        var m = new ItemManifest("risky", "1.0.0", "Risky business.",
            "did:wyrd:x", List.of("drive.mark"),
            Map.of("drive.mark", new ItemManifest.RateLimit(null, 4, null)),
            "low", List.of(), List.of(), List.of(), List.of(),
            null, null, null, null, null);
        var result = ItemManifestValidator.validate(m);
        assertTrue(result.valid(), "tier 5 + sensitivity=low is a warning, not an error");
        assertFalse(result.warnings().isEmpty());
    }

    @Test
    void phase_b_plus_visualization_caps_are_known() {
        // §4.35 chart.render Tier 4
        assertTrue(ItemManifestValidator.isKnownCapability("chart.render"));
        assertEquals(4, ItemManifestValidator.tierFor("chart.render"));
        // §4.35 chart.ascii Tier 1 (implicit)
        assertTrue(ItemManifestValidator.isKnownCapability("chart.ascii"));
        assertEquals(1, ItemManifestValidator.tierFor("chart.ascii"));
        // §4.36 artifact.write Tier 4
        assertTrue(ItemManifestValidator.isKnownCapability("artifact.write"));
        assertEquals(4, ItemManifestValidator.tierFor("artifact.write"));
        assertEquals(5, ItemManifestValidator.tierFor("artifact.attach.room"));
        // §4.37 scroll.write Tier 4, scroll.share Tier 5
        assertEquals(4, ItemManifestValidator.tierFor("scroll.write"));
        assertEquals(5, ItemManifestValidator.tierFor("scroll.share"));
    }

    @Test
    void manifest_with_visualization_caps_validates() {
        var m = new ItemManifest("observation_chart", "1.0.0",
            "Renders a chart.", "did:wyrd:x",
            List.of("chart.render", "artifact.write"),
            Map.of(),
            "low", List.of(), List.of(), List.of(), List.of(),
            null, null, null, null, null);
        var result = ItemManifestValidator.validate(m);
        assertTrue(result.valid(), "errors: " + result.errors());
    }
}
