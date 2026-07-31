package org.wyrdsekai.scripting.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for ScriptCrypto — JDK-native crypto utilities for scripts.
 */
class ScriptCryptoTest {

    private final ScriptCrypto crypto = new ScriptCrypto();

    @Test
    void sha256_produces_correct_hash() {
        // Known SHA-256 of "hello"
        String hash = crypto.sha256("hello");
        assertThat(hash).isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
    }

    @Test
    void sha256_empty_string() {
        String hash = crypto.sha256("");
        assertThat(hash).isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test
    void hmac_produces_consistent_output() {
        String mac1 = crypto.hmac("secret", "data");
        String mac2 = crypto.hmac("secret", "data");
        assertThat(mac1).isEqualTo(mac2);
        assertThat(mac1).hasSize(64); // 32 bytes = 64 hex chars
    }

    @Test
    void hmac_different_keys_produce_different_output() {
        String mac1 = crypto.hmac("key1", "data");
        String mac2 = crypto.hmac("key2", "data");
        assertThat(mac1).isNotEqualTo(mac2);
    }

    @Test
    void base64_encode_decode_round_trip() {
        String original = "Hello, World! This is a test.";
        String encoded = crypto.base64Encode(original);
        String decoded = crypto.base64Decode(encoded);
        assertThat(decoded).isEqualTo(original);
    }

    @Test
    void base64_encode_known_value() {
        assertThat(crypto.base64Encode("hello")).isEqualTo("aGVsbG8=");
    }

    @Test
    void sha256_null_input_throws() {
        assertThatThrownBy(() -> crypto.sha256(null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void hmac_null_key_throws() {
        assertThatThrownBy(() -> crypto.hmac(null, "data"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void hmac_null_data_throws() {
        assertThatThrownBy(() -> crypto.hmac("key", null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void base64_decode_null_throws() {
        assertThatThrownBy(() -> crypto.base64Decode(null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void base64_encode_null_throws() {
        assertThatThrownBy(() -> crypto.base64Encode(null))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
