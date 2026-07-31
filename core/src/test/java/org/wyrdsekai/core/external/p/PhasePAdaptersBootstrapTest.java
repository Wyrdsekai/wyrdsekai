package org.wyrdsekai.core.external.p;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.external.ExternalAdapterRegistry;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase P bootstrap test — verifies the bootstrap registers all 9 expected
 * adapter namespaces, is idempotent, and the registry exposes them through
 * {@link ExternalAdapterRegistry#namespaces()}.
 */
class PhasePAdaptersBootstrapTest {

    @BeforeAll
    static void cleanRegistry() {
        ExternalAdapterRegistry.get().clearForTests();
        PhasePAdaptersBootstrap.resetForTests();
    }

    @AfterAll
    static void restore() {
        ExternalAdapterRegistry.get().clearForTests();
        PhasePAdaptersBootstrap.resetForTests();
    }

    @Test
    void bootstrap_registers_all_nine_namespaces() {
        PhasePAdaptersBootstrap.init();
        var ns = ExternalAdapterRegistry.get().namespaces();
        assertThat(ns).contains(
            "mastodon", "reddit", "bluesky", "x", "hn",
            "github", "gitlab", "npm", "pypi");
    }

    @Test
    void bootstrap_is_idempotent() {
        PhasePAdaptersBootstrap.init();
        PhasePAdaptersBootstrap.init();
        // Still 9 namespaces, no exceptions.
        var ns = ExternalAdapterRegistry.get().namespaces();
        assertThat(ns).contains("github", "hn");
        assertThat(PhasePAdaptersBootstrap.isInitialised()).isTrue();
    }

    @Test
    void each_adapter_advertises_credential_slot() {
        PhasePAdaptersBootstrap.init();
        var registry = ExternalAdapterRegistry.get();
        assertThat(registry.lookup("mastodon").orElseThrow().credentialSlot())
            .isEqualTo("mastodon.access_token");
        assertThat(registry.lookup("reddit").orElseThrow().credentialSlot())
            .isEqualTo("reddit.refresh_token");
        assertThat(registry.lookup("bluesky").orElseThrow().credentialSlot())
            .isEqualTo("bluesky.app_password");
        assertThat(registry.lookup("x").orElseThrow().credentialSlot())
            .isEqualTo("x.bearer_token");
        assertThat(registry.lookup("github").orElseThrow().credentialSlot())
            .isEqualTo("github.token");
        assertThat(registry.lookup("gitlab").orElseThrow().credentialSlot())
            .isEqualTo("gitlab.token");
    }
}
