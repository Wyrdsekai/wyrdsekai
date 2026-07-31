package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wave 4.2: jurisdiction-aware emergency
 * number routing.
 */
class EmergencyJurisdictionTest {

    @Test
    void us_routes_to_911_and_988() {
        assertThat(EmergencyJurisdiction.US.generalEmergency()).isEqualTo("911");
        assertThat(EmergencyJurisdiction.US.mentalHealthLine()).isEqualTo("988");
        assertThat(EmergencyJurisdiction.US.isConfigured()).isTrue();
    }

    @Test
    void uk_routes_to_999_and_samaritans() {
        assertThat(EmergencyJurisdiction.UK.generalEmergency()).isEqualTo("999");
        assertThat(EmergencyJurisdiction.UK.mentalHealthLine()).isEqualTo("116123");
    }

    @Test
    void eu_routes_to_112() {
        assertThat(EmergencyJurisdiction.EU.generalEmergency()).isEqualTo("112");
    }

    @Test
    void unknown_is_unconfigured() {
        assertThat(EmergencyJurisdiction.UNKNOWN.isConfigured()).isFalse();
        assertThat(EmergencyJurisdiction.UNKNOWN.generalEmergency()).isEmpty();
    }

    @Test
    void resolve_handles_iso_codes() {
        assertThat(EmergencyJurisdiction.resolve("US").get()).isEqualTo(EmergencyJurisdiction.US);
        assertThat(EmergencyJurisdiction.resolve("UK").get()).isEqualTo(EmergencyJurisdiction.UK);
        assertThat(EmergencyJurisdiction.resolve("EU").get()).isEqualTo(EmergencyJurisdiction.EU);
        assertThat(EmergencyJurisdiction.resolve("JP").get()).isEqualTo(EmergencyJurisdiction.JP_FIRE);
        assertThat(EmergencyJurisdiction.resolve("AU").get()).isEqualTo(EmergencyJurisdiction.AU);
        assertThat(EmergencyJurisdiction.resolve("CA").get()).isEqualTo(EmergencyJurisdiction.CA);
        assertThat(EmergencyJurisdiction.resolve("NZ").get()).isEqualTo(EmergencyJurisdiction.NZ);
    }

    @Test
    void resolve_handles_common_aliases_case_insensitive() {
        assertThat(EmergencyJurisdiction.resolve("usa").get()).isEqualTo(EmergencyJurisdiction.US);
        assertThat(EmergencyJurisdiction.resolve("Japan").get()).isEqualTo(EmergencyJurisdiction.JP_FIRE);
        assertThat(EmergencyJurisdiction.resolve("united states").get()).isEqualTo(EmergencyJurisdiction.US);
        assertThat(EmergencyJurisdiction.resolve("Germany").get()).isEqualTo(EmergencyJurisdiction.EU);
    }

    @Test
    void resolve_blank_returns_unknown() {
        assertThat(EmergencyJurisdiction.resolve("").get()).isEqualTo(EmergencyJurisdiction.UNKNOWN);
        assertThat(EmergencyJurisdiction.resolve(null).get()).isEqualTo(EmergencyJurisdiction.UNKNOWN);
        assertThat(EmergencyJurisdiction.resolve("   ").get()).isEqualTo(EmergencyJurisdiction.UNKNOWN);
    }

    @Test
    void resolve_unrecognized_returns_unknown_does_not_guess() {
        assertThat(EmergencyJurisdiction.resolve("Mars").get()).isEqualTo(EmergencyJurisdiction.UNKNOWN);
        assertThat(EmergencyJurisdiction.resolve("XX").get()).isEqualTo(EmergencyJurisdiction.UNKNOWN);
    }
}
