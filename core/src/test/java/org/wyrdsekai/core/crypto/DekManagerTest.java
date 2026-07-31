package org.wyrdsekai.core.crypto;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DekManagerTest {

    private DekManager manager;

    @BeforeEach void setUp() {
        manager = DekManager.withRandomMasterKey();
    }

    @Test void generateDek_creates_active_key() {
        var entry = manager.generateDek("key-1", DekManager.DataClassification.INTERNAL, "alice");
        assertThat(entry.active()).isTrue();
        assertThat(entry.keyId()).isEqualTo("key-1");
        assertThat(manager.activeKeyCount()).isEqualTo(1);
    }

    @Test void unwrapDek_returns_key() {
        manager.generateDek("key-1", DekManager.DataClassification.INTERNAL, "alice");
        var key = manager.unwrapDek("key-1");
        assertThat(key).isPresent();
        assertThat(key.get().getAlgorithm()).isEqualTo("AES");
    }

    @Test void encrypt_decrypt_roundtrip() {
        manager.generateDek("key-1", DekManager.DataClassification.CONFIDENTIAL, "alice");
        var plaintext = "secret data".getBytes();

        var encrypted = manager.encrypt("key-1", plaintext);
        assertThat(encrypted).isNotEqualTo(plaintext);

        var decrypted = manager.decrypt("key-1", encrypted);
        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test void destroyDek_makes_key_inactive() {
        manager.generateDek("key-1", DekManager.DataClassification.INTERNAL, "alice");
        assertThat(manager.destroyDek("key-1")).isTrue();

        assertThat(manager.unwrapDek("key-1")).isEmpty();
        assertThat(manager.activeKeyCount()).isEqualTo(0);
    }

    @Test void destroyed_key_cannot_encrypt() {
        manager.generateDek("key-1", DekManager.DataClassification.INTERNAL, "alice");
        manager.destroyDek("key-1");

        assertThatThrownBy(() -> manager.encrypt("key-1", "data".getBytes()))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test void keyCount_tracks_all() {
        manager.generateDek("key-1", DekManager.DataClassification.INTERNAL, "alice");
        manager.generateDek("key-2", DekManager.DataClassification.CONFIDENTIAL, "bob");
        manager.destroyDek("key-1");

        assertThat(manager.keyCount()).isEqualTo(2);
        assertThat(manager.activeKeyCount()).isEqualTo(1);
    }
}
