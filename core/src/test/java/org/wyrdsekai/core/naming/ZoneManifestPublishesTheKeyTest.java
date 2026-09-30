package org.wyrdsekai.core.naming;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A zone's manifest carries its public X25519 key (`zk`), so someone with no invite can seal a
 * knock to it (sealed requests v2, 2026-09-28). The key is covered by the manifest's signature.
 */
class ZoneManifestPublishesTheKeyTest {

    private static ZoneManifestV1 manifest(String zk) {
        return new ZoneManifestV1(ZoneManifestV1.SCHEMA_VERSION, "did:wyrd:hearthside", "hearthside", "Hearthside", null,
            "a home", "a home", List.of(), null, null, null, 0, null, null,
            "2026-09-28T00:00:00Z", "2026-09-28T00:00:00Z", null, zk);
    }

    @Test
    void theKeyRoundTripsAndIsSigned() {
        var m = manifest("3p7bfXt9wbTTW2HC7OQ1Nz-DQ8hbeGdNrfx-FG-IK08");
        var parsed = ZoneManifestV1.fromJsonString(new String(
            m.withSignature("ed25519:x").signingBytes(), StandardCharsets.UTF_8));
        assertThat(parsed.zk()).isEqualTo(m.zk());
        assertThat(new String(m.signingBytes(), StandardCharsets.UTF_8)).contains("\"zk\":\"3p7bfXt9");
        assertThat(m.signingBytes()).isNotEqualTo(manifest("AAAA").signingBytes());
    }

    @Test
    void aZoneWithoutAKeyPublishesNoField() {
        assertThat(new String(manifest(null).signingBytes(), StandardCharsets.UTF_8)).doesNotContain("\"zk\"");
    }
}
