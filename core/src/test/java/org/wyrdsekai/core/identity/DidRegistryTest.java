package org.wyrdsekai.core.identity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DidRegistryTest {

    private DidRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new DidRegistry();
    }

    @Test void register_and_resolve() {
        var did = DidWyrd.fromPublicKey("foundation", "key1".getBytes());
        registry.register(did, "key1".getBytes(), "human");

        var doc = registry.resolve(did.toUri());
        assertThat(doc).isPresent();
        assertThat(doc.get().entityType()).isEqualTo("human");
        assertThat(doc.get().active()).isTrue();
    }

    @Test void register_duplicate_fails() {
        var did = DidWyrd.fromPublicKey("foundation", "key1".getBytes());
        assertThat(registry.register(did, "key1".getBytes(), "human")).isTrue();
        assertThat(registry.register(did, "key1".getBytes(), "human")).isFalse();
    }

    @Test void resolve_not_found() {
        assertThat(registry.resolve("did:wyrd:zone:nonexistent")).isEmpty();
    }

    @Test void resolve_public_key() {
        var did = DidWyrd.fromPublicKey("zone", "mykey".getBytes());
        registry.register(did, "mykey".getBytes(), "agent");

        var key = registry.resolvePublicKey(did.toUri());
        assertThat(key).isPresent();
        assertThat(new String(key.get())).isEqualTo("mykey");
    }

    @Test void deactivate() {
        var did = DidWyrd.fromPublicKey("zone", "key".getBytes());
        registry.register(did, "key".getBytes(), "human");
        registry.deactivate(did.toUri());

        assertThat(registry.resolve(did.toUri())).isEmpty();
        assertThat(registry.activeCount()).isEqualTo(0);
        assertThat(registry.size()).isEqualTo(1); // still tracked
    }

    @Test void list_active() {
        var did1 = DidWyrd.fromPublicKey("zone", "key1".getBytes());
        var did2 = DidWyrd.fromPublicKey("zone", "key2".getBytes());
        registry.register(did1, "key1".getBytes(), "human");
        registry.register(did2, "key2".getBytes(), "agent");

        assertThat(registry.listActive()).hasSize(2);
    }
}
