package org.wyrdsekai.core.nostr;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.nostr.NostrAdapter.SeedResolver;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class CompositeSeedResolverTest {

    private static byte[] fill(byte b) {
        var out = new byte[32];
        Arrays.fill(out, b);
        return out;
    }

    @Test void first_non_null_wins() {
        SeedResolver a = did -> null;
        SeedResolver b = did -> fill((byte) 0xAA);
        SeedResolver c = did -> fill((byte) 0xCC);
        var composite = CompositeSeedResolver.of(a, b, c);

        var seed = composite.seedForDid("did:key:zX");
        assertThat(seed).isNotNull();
        assertThat(seed[0]).isEqualTo((byte) 0xAA);  // b wins, c never consulted
    }

    @Test void all_null_returns_null() {
        var composite = CompositeSeedResolver.of(
            did -> null,
            did -> null,
            did -> null);
        assertThat(composite.seedForDid("did:key:zNone")).isNull();
    }

    @Test void null_resolver_in_chain_is_skipped() {
        var composite = CompositeSeedResolver.of(
            null,
            did -> fill((byte) 0x77));
        assertThat(composite.seedForDid("did:key:zSkip")).isNotNull();
    }

    @Test void exception_in_one_resolver_falls_through() {
        SeedResolver throws_ = did -> { throw new RuntimeException("boom"); };
        SeedResolver succeeds = did -> fill((byte) 0x33);
        var composite = CompositeSeedResolver.of(throws_, succeeds);
        assertThat(composite.seedForDid("did:key:zErr")).isNotNull();
    }

    @Test void short_seed_is_treated_as_null() {
        SeedResolver tooShort = did -> new byte[16];
        SeedResolver good = did -> fill((byte) 0x55);
        var composite = CompositeSeedResolver.of(tooShort, good);
        // Composite skips the 16-byte response and returns good's 32-byte
        var result = composite.seedForDid("did:key:zShort");
        assertThat(result).hasSize(32);
        assertThat(result[0]).isEqualTo((byte) 0x55);
    }

    @Test void empty_chain_returns_null() {
        var composite = CompositeSeedResolver.of();
        assertThat(composite.seedForDid("did:key:zAny")).isNull();
        assertThat(composite.size()).isZero();
    }

    @Test void node_first_then_companion_pattern_works() {
        // Realistic usage: companion resolver tried first, node fallback last.
        // For an unknown DID, both return null.
        var companionResolver = (SeedResolver) (did ->
            "did:key:zCompanion1".equals(did) ? fill((byte) 0xC1) : null);
        var nodeResolver = (SeedResolver) (did ->
            "did:key:zNode".equals(did) ? fill((byte) 0x0E) : null);
        var composite = CompositeSeedResolver.of(companionResolver, nodeResolver);

        assertThat(composite.seedForDid("did:key:zCompanion1")[0]).isEqualTo((byte) 0xC1);
        assertThat(composite.seedForDid("did:key:zNode")[0]).isEqualTo((byte) 0x0E);
        assertThat(composite.seedForDid("did:key:zUnknown")).isNull();
    }
}
