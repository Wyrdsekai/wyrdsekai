package org.wyrdsekai.scripting.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase T (§4.34) — capability + tier validation for inbound listeners. */
class ItemManifestValidatorPhaseTTest {

    @Test
    void inbound_webhook_is_tier_5() {
        assertThat(ItemManifestValidator.tierFor("inbound.webhook")).isEqualTo(5);
        assertThat(ItemManifestValidator.tierFor("inbound.mqtt")).isEqualTo(5);
        assertThat(ItemManifestValidator.tierFor("inbound.github_webhook")).isEqualTo(5);
    }

    @Test
    void inbound_list_is_tier_1_implicit() {
        assertThat(ItemManifestValidator.tierFor("inbound.list")).isEqualTo(1);
        assertThat(ItemCapabilitySet.IMPLICIT).contains("inbound.list");
    }

    @Test
    void inbound_email_watch_is_tier_4() {
        assertThat(ItemManifestValidator.tierFor("inbound.email_watch")).isEqualTo(4);
        assertThat(ItemManifestValidator.tierFor("inbound.file_watch")).isEqualTo(4);
        assertThat(ItemManifestValidator.tierFor("inbound.scheduled")).isEqualTo(4);
    }

    @Test
    void inbound_caps_are_known() {
        assertThat(ItemManifestValidator.isKnownCapability("inbound.webhook")).isTrue();
        assertThat(ItemManifestValidator.isKnownCapability("inbound.cancel")).isTrue();
        assertThat(ItemManifestValidator.isKnownCapability("inbound.pause")).isTrue();
        assertThat(ItemManifestValidator.isKnownCapability("inbound.resume")).isTrue();
        assertThat(ItemManifestValidator.isKnownCapability("inbound.frobnicate")).isFalse();
    }
}
