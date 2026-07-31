package org.wyrdsekai.scripting.api;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KNOWN_CAPABILITIES additions plus the
 * existing tier-5 / allowlist enforcement rules applied to the new caps
 * (web.post family, mcp.invoke, fs writes, drive.mark, mailbox.send).
 */
class ItemManifestValidatorPhaseCTest {

    @Test
    void new_phase_c_caps_are_known() {
        for (var cap : List.of(
                "web.post", "web.put", "web.delete", "web.fetch_raw",
                "mcp.invoke", "mcp.list_servers", "mcp.list_tools",
                "mcp.resources.read", "mcp.prompts", "mcp.subscribe",
                "fs.read", "fs.write", "fs.delete", "fs.mkdir",
                "fs.list", "fs.exists", "fs.stat",
                "agent.mailbox.send", "agent.mailbox.archive",
                "agent.mailbox.read", "mailbox.mark_read",
                "drive.mark")) {
            assertThat(ItemManifestValidator.isKnownCapability(cap))
                .as("cap %s should be known", cap).isTrue();
        }
    }

    @Test
    void web_post_requires_external_domains_allowlist() {
        var m = new ItemManifest("clipper", "1.0.0", "Clips.", "did:wyrd:x",
            List.of("web.post"),
            Map.of("web.post", new ItemManifest.RateLimit(10, 60, 200)),
            "low", List.of(),
            List.of(), // empty domains
            List.of(), List.of(),
            null, null, null, null, null);
        var res = ItemManifestValidator.validate(m);
        assertThat(res.valid()).isFalse();
        assertThat(res.errors()).anyMatch(e -> e.contains("external_domains"));
    }

    @Test
    void web_post_with_domain_allowlist_validates() {
        var m = new ItemManifest("clipper", "1.0.0", "Clips.", "did:wyrd:x",
            List.of("web.post"),
            Map.of("web.post", new ItemManifest.RateLimit(10, 60, 200)),
            "low", List.of(),
            List.of("example.com"),
            List.of(), List.of(),
            null, null, null, null, null);
        var res = ItemManifestValidator.validate(m);
        assertThat(res.valid()).isTrue();
    }

    @Test
    void mcp_invoke_requires_mcp_servers_allowlist() {
        var m = new ItemManifest("inv", "1.0.0", "Inv.", "did:wyrd:x",
            List.of("mcp.invoke"),
            Map.of("mcp.invoke", new ItemManifest.RateLimit(5, 30, 100)),
            "low", List.of(),
            List.of(), List.of(), // no servers
            List.of(),
            null, null, null, null, null);
        var res = ItemManifestValidator.validate(m);
        assertThat(res.valid()).isFalse();
        assertThat(res.errors()).anyMatch(e -> e.contains("mcp_servers"));
    }

    @Test
    void drive_mark_is_tier_5() {
        assertThat(ItemManifestValidator.tierFor("drive.mark")).isEqualTo(5);
    }

    @Test
    void fs_write_is_tier_4() {
        assertThat(ItemManifestValidator.tierFor("fs.write")).isEqualTo(4);
        assertThat(ItemManifestValidator.tierFor("fs.read")).isEqualTo(4);
        assertThat(ItemManifestValidator.tierFor("fs.delete")).isEqualTo(4);
    }

    @Test
    void fs_list_exists_stat_are_tier_1() {
        assertThat(ItemManifestValidator.tierFor("fs.list")).isEqualTo(1);
        assertThat(ItemManifestValidator.tierFor("fs.exists")).isEqualTo(1);
        assertThat(ItemManifestValidator.tierFor("fs.stat")).isEqualTo(1);
    }

    @Test
    void mailbox_send_is_tier_5() {
        assertThat(ItemManifestValidator.tierFor("agent.mailbox.send")).isEqualTo(5);
    }

    @Test
    void mcp_invoke_is_tier_5() {
        assertThat(ItemManifestValidator.tierFor("mcp.invoke")).isEqualTo(5);
    }
}
