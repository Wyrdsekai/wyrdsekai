package org.wyrdsekai.core.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ContentSafetyTest {

    private ContentSafety safety;

    @BeforeEach
    void setUp() {
        safety = new ContentSafety();
    }

    @Test void safe_content_passes() {
        var result = safety.checkContent("hello world".getBytes());
        assertThat(result.blocked()).isFalse();
        assertThat(result.reason()).isNull();
    }

    @Test void blocked_hash_detected() {
        var content = "known harmful content".getBytes();
        var hash = ContentSafety.sha256(content);
        safety.addBlockedHash(hash);

        var result = safety.checkContent(content);
        assertThat(result.blocked()).isTrue();
        assertThat(result.reason()).contains("harmful hash");
    }

    @Test void check_hash_directly() {
        safety.addBlockedHash("abc123");

        assertThat(safety.checkHash("abc123").blocked()).isTrue();
        assertThat(safety.checkHash("def456").blocked()).isFalse();
    }

    @Test void hash_comparison_case_insensitive() {
        safety.addBlockedHash("ABC123");

        assertThat(safety.checkHash("abc123").blocked()).isTrue();
        assertThat(safety.checkHash("ABC123").blocked()).isTrue();
    }

    @Test void remove_blocked_hash() {
        safety.addBlockedHash("abc123");
        assertThat(safety.blocklistSize()).isEqualTo(1);

        safety.removeBlockedHash("abc123");
        assertThat(safety.blocklistSize()).isEqualTo(0);
        assertThat(safety.checkHash("abc123").blocked()).isFalse();
    }

    @Test void blocklist_entry() {
        var entry = new ContentSafety.BlocklistEntry("abc123", "NCMEC", "csam");
        safety.addBlockedEntry(entry);

        assertThat(safety.checkHash("abc123").blocked()).isTrue();
        assertThat(safety.blocklistSize()).isEqualTo(1);
    }

    @Test void sha256_deterministic() {
        var content = "test content".getBytes();
        var hash1 = ContentSafety.sha256(content);
        var hash2 = ContentSafety.sha256(content);
        assertThat(hash1).isEqualTo(hash2);
        assertThat(hash1).hasSize(64); // SHA-256 = 32 bytes = 64 hex chars
    }

    @Test void empty_blocklist() {
        assertThat(safety.blocklistSize()).isEqualTo(0);
        assertThat(safety.checkContent(new byte[0]).blocked()).isFalse();
    }
}
