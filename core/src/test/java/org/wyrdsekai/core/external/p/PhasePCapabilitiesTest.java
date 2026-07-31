package org.wyrdsekai.core.external.p;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.scripting.api.ItemManifestValidator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase P — verifies the additive {@link ItemManifestValidator#KNOWN_CAPABILITIES}
 * entries for §4.25 + §4.26 are present and tier-correct (reads = Tier 4,
 * writes = Tier 5).
 */
class PhasePCapabilitiesTest {

    @Test
    void social_read_caps_are_tier_four() {
        assertThat(ItemManifestValidator.tierFor("mastodon.read")).isEqualTo(4);
        assertThat(ItemManifestValidator.tierFor("reddit.read")).isEqualTo(4);
        assertThat(ItemManifestValidator.tierFor("bluesky.read")).isEqualTo(4);
        assertThat(ItemManifestValidator.tierFor("x.read")).isEqualTo(4);
        assertThat(ItemManifestValidator.tierFor("hn.read")).isEqualTo(4);
    }

    @Test
    void social_write_caps_are_tier_five() {
        assertThat(ItemManifestValidator.tierFor("mastodon.post")).isEqualTo(5);
        assertThat(ItemManifestValidator.tierFor("mastodon.follow")).isEqualTo(5);
        assertThat(ItemManifestValidator.tierFor("reddit.post")).isEqualTo(5);
        assertThat(ItemManifestValidator.tierFor("reddit.subscribe")).isEqualTo(5);
        assertThat(ItemManifestValidator.tierFor("bluesky.post")).isEqualTo(5);
        assertThat(ItemManifestValidator.tierFor("x.post")).isEqualTo(5);
        assertThat(ItemManifestValidator.tierFor("x.dm")).isEqualTo(5);
    }

    @Test
    void code_platform_read_caps_are_tier_four() {
        assertThat(ItemManifestValidator.tierFor("github.code.search")).isEqualTo(4);
        assertThat(ItemManifestValidator.tierFor("github.issue.read")).isEqualTo(4);
        assertThat(ItemManifestValidator.tierFor("github.pr.read")).isEqualTo(4);
        assertThat(ItemManifestValidator.tierFor("gitlab.issue.read")).isEqualTo(4);
        assertThat(ItemManifestValidator.tierFor("gitlab.mr.read")).isEqualTo(4);
        assertThat(ItemManifestValidator.tierFor("npm.read")).isEqualTo(4);
        assertThat(ItemManifestValidator.tierFor("pypi.read")).isEqualTo(4);
    }

    @Test
    void code_platform_write_caps_are_tier_five() {
        assertThat(ItemManifestValidator.tierFor("github.issue.write")).isEqualTo(5);
        assertThat(ItemManifestValidator.tierFor("github.pr.write")).isEqualTo(5);
        assertThat(ItemManifestValidator.tierFor("gitlab.issue.write")).isEqualTo(5);
    }

    @Test
    void all_concrete_caps_are_known() {
        var caps = new String[] {
            "mastodon.read", "mastodon.post", "mastodon.follow",
            "reddit.read", "reddit.post", "reddit.subscribe",
            "bluesky.read", "bluesky.post",
            "x.read", "x.post", "x.dm",
            "hn.read",
            "github.code.search", "github.issue.read", "github.issue.write",
            "github.pr.read", "github.pr.write",
            "gitlab.issue.read", "gitlab.issue.write", "gitlab.mr.read",
            "npm.read", "pypi.read"
        };
        for (var c : caps) {
            assertThat(ItemManifestValidator.isKnownCapability(c))
                .as("cap should be known: %s", c).isTrue();
        }
    }
}
