package org.wyrdsekai.core.privacy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class CryptoShreddingTest {

    private CryptoShredding shredding;

    @BeforeEach
    void setUp() {
        shredding = new CryptoShredding();
    }

    @Test void generate_key() {
        var info = shredding.generateKey("entity-1");
        assertThat(info.entityId()).isEqualTo("entity-1");
        assertThat(info.key()).hasSize(32); // AES-256 = 32 bytes
        assertThat(info.active()).isTrue();
    }

    @Test void encrypt_decrypt_roundtrip() {
        shredding.generateKey("entity-1");
        var plaintext = "personal data".getBytes(StandardCharsets.UTF_8);

        var encrypted = shredding.encrypt("entity-1", plaintext);
        assertThat(encrypted).isPresent();

        var decrypted = shredding.decrypt("entity-1", encrypted.get());
        assertThat(decrypted).isPresent();
        assertThat(new String(decrypted.get(), StandardCharsets.UTF_8)).isEqualTo("personal data");
    }

    @Test void encrypt_without_key_fails() {
        var encrypted = shredding.encrypt("no-key", "data".getBytes());
        assertThat(encrypted).isEmpty();
    }

    @Test void shred_destroys_key() {
        shredding.generateKey("entity-1");
        var encrypted = shredding.encrypt("entity-1", "secret".getBytes()).orElseThrow();

        assertThat(shredding.shred("entity-1")).isTrue();

        // Cannot decrypt after shredding
        var decrypted = shredding.decrypt("entity-1", encrypted);
        assertThat(decrypted).isEmpty();
    }

    @Test void shredded_entity_tracked() {
        shredding.generateKey("entity-1");
        shredding.shred("entity-1");

        assertThat(shredding.isShredded("entity-1")).isTrue();
        assertThat(shredding.hasKey("entity-1")).isFalse();
    }

    @Test void active_key_count() {
        shredding.generateKey("entity-1");
        shredding.generateKey("entity-2");
        assertThat(shredding.activeKeyCount()).isEqualTo(2);

        shredding.shred("entity-1");
        assertThat(shredding.activeKeyCount()).isEqualTo(1);
    }

    @Test void shredded_count() {
        shredding.generateKey("entity-1");
        shredding.generateKey("entity-2");
        shredding.shred("entity-1");
        shredding.shred("entity-2");
        assertThat(shredding.shreddedCount()).isEqualTo(2);
    }

    @Test void different_entities_different_keys() {
        var key1 = shredding.generateKey("entity-1");
        var key2 = shredding.generateKey("entity-2");
        assertThat(key1.key()).isNotEqualTo(key2.key());
    }

    @Test void encrypted_data_differs_each_time() {
        shredding.generateKey("entity-1");
        var data = "same data".getBytes();
        var enc1 = shredding.encrypt("entity-1", data).orElseThrow();
        var enc2 = shredding.encrypt("entity-1", data).orElseThrow();
        // Different IVs should produce different ciphertext
        assertThat(enc1.iv()).isNotEqualTo(enc2.iv());
    }
}
