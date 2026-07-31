package org.wyrdsekai.core.identity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DidWyrdTest {

    @Test void toUri() {
        var did = new DidWyrd("foundation", "abc123");
        assertThat(did.toUri()).isEqualTo("did:wyrd:foundation:abc123");
    }

    @Test void parse_valid() {
        var did = DidWyrd.parse("did:wyrd:foundation:abc123");
        assertThat(did).isNotNull();
        assertThat(did.zone()).isEqualTo("foundation");
        assertThat(did.entityHash()).isEqualTo("abc123");
    }

    @Test void parse_invalid_method() {
        assertThat(DidWyrd.parse("did:key:abc123")).isNull();
    }

    @Test void parse_null() {
        assertThat(DidWyrd.parse(null)).isNull();
    }

    @Test void parse_wrong_parts() {
        assertThat(DidWyrd.parse("did:wyrd:only")).isNull();
        assertThat(DidWyrd.parse("did:wyrd:a:b:c")).isNull();
    }

    @Test void fromPublicKey() {
        var key = "test-public-key".getBytes();
        var did = DidWyrd.fromPublicKey("foundation", key);
        assertThat(did.zone()).isEqualTo("foundation");
        assertThat(did.entityHash()).hasSize(32); // 16 bytes = 32 hex chars
    }

    @Test void fromPublicKey_deterministic() {
        var key = "deterministic-key".getBytes();
        var did1 = DidWyrd.fromPublicKey("zone1", key);
        var did2 = DidWyrd.fromPublicKey("zone1", key);
        assertThat(did1.entityHash()).isEqualTo(did2.entityHash());
    }

    @Test void isValid() {
        assertThat(DidWyrd.isValid("did:wyrd:zone:hash")).isTrue();
        assertThat(DidWyrd.isValid("did:key:abc")).isFalse();
        assertThat(DidWyrd.isValid(null)).isFalse();
    }

    @Test void roundtrip() {
        var original = new DidWyrd("testzone", "deadbeef");
        var parsed = DidWyrd.parse(original.toUri());
        assertThat(parsed).isEqualTo(original);
    }
}
