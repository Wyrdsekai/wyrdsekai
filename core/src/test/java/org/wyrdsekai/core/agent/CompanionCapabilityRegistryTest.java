package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CompanionCapabilityRegistryTest {

    @BeforeEach @AfterEach void clean() {
        CompanionCapabilityRegistry.get().clearForTests();
    }

    private static CompanionCapabilities sample() {
        // Empty but non-null bundle — registry treats null caps as a no-op anyway.
        return CompanionCapabilities.none();
    }

    @Test void register_and_lookup() {
        var caps = sample();
        var prev = CompanionCapabilityRegistry.get().register("did:key:zAlice", caps);
        assertThat(prev).isNull();
        assertThat(CompanionCapabilityRegistry.get().lookup("did:key:zAlice")).isSameAs(caps);
        assertThat(CompanionCapabilityRegistry.get().size()).isEqualTo(1);
    }

    @Test void unregister_clears_entry() {
        CompanionCapabilityRegistry.get().register("did:key:zBob", sample());
        CompanionCapabilityRegistry.get().unregister("did:key:zBob");
        assertThat(CompanionCapabilityRegistry.get().lookup("did:key:zBob")).isNull();
        assertThat(CompanionCapabilityRegistry.get().size()).isZero();
    }

    @Test void register_twice_replaces_and_returns_prior() {
        var first = sample();
        var second = sample();
        CompanionCapabilityRegistry.get().register("did:key:zCarol", first);
        var prev = CompanionCapabilityRegistry.get().register("did:key:zCarol", second);
        assertThat(prev).isSameAs(first);
        assertThat(CompanionCapabilityRegistry.get().lookup("did:key:zCarol")).isSameAs(second);
    }

    @Test void register_with_null_did_is_noop() {
        assertThat(CompanionCapabilityRegistry.get().register(null, sample())).isNull();
        assertThat(CompanionCapabilityRegistry.get().register("", sample())).isNull();
        assertThat(CompanionCapabilityRegistry.get().register("did:key:zD", null)).isNull();
        assertThat(CompanionCapabilityRegistry.get().size()).isZero();
    }

    @Test void lookup_missing_returns_null() {
        assertThat(CompanionCapabilityRegistry.get().lookup("did:key:zNoSuch")).isNull();
        assertThat(CompanionCapabilityRegistry.get().lookup(null)).isNull();
    }
}
