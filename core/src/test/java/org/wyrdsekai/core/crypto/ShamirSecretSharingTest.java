package org.wyrdsekai.core.crypto;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShamirSecretSharingTest {

    @Test void split_creates_correct_number_of_shares() {
        var secret = "hello world".getBytes();
        var shares = ShamirSecretSharing.split(secret, 5, 3);
        assertThat(shares).hasSize(5);
        assertThat(shares.getFirst().y()).hasSize(secret.length);
    }

    @Test void reconstruct_with_threshold_shares() {
        var secret = "secret data 123".getBytes();
        var shares = ShamirSecretSharing.split(secret, 5, 3);

        // Use any 3 of 5 shares
        var subset = List.of(shares.get(0), shares.get(2), shares.get(4));
        var reconstructed = ShamirSecretSharing.reconstruct(subset);
        assertThat(reconstructed).isEqualTo(secret);
    }

    @Test void reconstruct_with_different_share_combination() {
        var secret = "another secret".getBytes();
        var shares = ShamirSecretSharing.split(secret, 5, 3);

        // Different 3 shares
        var subset = List.of(shares.get(1), shares.get(3), shares.get(4));
        var reconstructed = ShamirSecretSharing.reconstruct(subset);
        assertThat(reconstructed).isEqualTo(secret);
    }

    @Test void reconstruct_with_all_shares() {
        var secret = "full recovery".getBytes();
        var shares = ShamirSecretSharing.split(secret, 3, 2);
        var reconstructed = ShamirSecretSharing.reconstruct(shares);
        assertThat(reconstructed).isEqualTo(secret);
    }

    @Test void split_rejects_invalid_parameters() {
        assertThatThrownBy(() -> ShamirSecretSharing.split("x".getBytes(), 1, 2))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void gf256_multiplication_identity() {
        assertThat(ShamirSecretSharing.gf256Mul(1, 42)).isEqualTo(42);
        assertThat(ShamirSecretSharing.gf256Mul(42, 1)).isEqualTo(42);
    }

    @Test void gf256_inverse_roundtrip() {
        for (int a = 1; a < 256; a++) {
            int inv = ShamirSecretSharing.gf256Inv(a);
            assertThat(ShamirSecretSharing.gf256Mul(a, inv)).isEqualTo(1);
        }
    }
}
