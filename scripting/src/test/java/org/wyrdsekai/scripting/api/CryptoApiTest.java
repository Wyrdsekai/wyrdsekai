package org.wyrdsekai.scripting.api;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * hash/HMAC/uuid/random. NO encryption
 * primitives here; AES/RSA live in §4.18 (The Safe).
 */
class CryptoApiTest {

    private final ItemWorldApi.CryptoApi crypto = new ItemWorldApi.CryptoApi();

    @Test
    void sha256_default_matches_known_vector() {
        // SHA-256("") = e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
        assertThat(crypto.hash(""))
            .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test
    void sha256_explicit_algo_matches_known_vector() {
        assertThat(crypto.hash("hello", "sha256"))
            .isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
    }

    @Test
    void sha512_returns_128_hex_chars() {
        var h = crypto.hash("test", "sha512");
        assertThat(h).hasSize(128).matches("[0-9a-f]+");
    }

    @Test
    void blake3_falls_back_to_sha256() {
        // No native Blake3 in JDK — we soft-fall to SHA-256 to avoid
        // breaking scripts that prefer the modern algo.
        assertThat(crypto.hash("test", "blake3")).hasSize(64);
    }

    @Test
    void unsupported_algo_throws() {
        assertThatThrownBy(() -> crypto.hash("test", "rot13"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unsupported");
    }

    @Test
    void hmac_default_matches_known_vector() {
        // HMAC-SHA256("key", "data") known vector
        var hmac = crypto.hmac("key", "The quick brown fox jumps over the lazy dog");
        assertThat(hmac).isEqualTo("f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8");
    }

    @Test
    void hmac_with_sha512_returns_long_hex() {
        var h = crypto.hmac("key", "data", "sha512");
        assertThat(h).hasSize(128).matches("[0-9a-f]+");
    }

    @Test
    void random_returns_hex_of_capped_length() {
        var hex = crypto.random(16);
        assertThat(hex).hasSize(32).matches("[0-9a-f]+");
        // Cap at 4096 bytes
        assertThat(crypto.random(10_000)).hasSize(8192);
        // Negative → empty
        assertThat(crypto.random(-1)).isEmpty();
    }

    @Test
    void random_bytes_returns_int_array() {
        var bytes = crypto.random_bytes(8);
        assertThat(bytes).hasSize(8);
        for (var b : bytes) {
            assertThat(b).isBetween(0, 255);
        }
    }

    @Test
    void uuid_is_valid_v4() {
        var u = crypto.uuid();
        // Must be parseable
        var parsed = UUID.fromString(u);
        assertThat(parsed.version()).isEqualTo(4);
    }

    @Test
    void uuid_is_unique_across_calls() {
        var seen = new HashSet<String>();
        for (int i = 0; i < 100; i++) {
            seen.add(crypto.uuid());
        }
        assertThat(seen).hasSize(100);
    }

    @Test
    void null_inputs_handled_gracefully() {
        assertThat(crypto.hash(null)).isNull();
        assertThat(crypto.hmac(null, "data")).isNull();
        assertThat(crypto.hmac("key", null)).isNull();
    }

    @Test
    void unsupported_hmac_algo_throws() {
        assertThatThrownBy(() -> crypto.hmac("key", "data", "rot13"))
            .hasMessageContaining("unsupported");
    }
}
