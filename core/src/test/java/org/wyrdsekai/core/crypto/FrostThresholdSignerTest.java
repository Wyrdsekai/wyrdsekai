package org.wyrdsekai.core.crypto;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for FROST Ed25519 threshold signing (§73.C, §74).
 * Verifies that threshold signatures work without reconstructing the private key.
 */
class FrostThresholdSignerTest {

    private ThresholdSigner signer;

    @BeforeEach void setUp() {
        signer = ThresholdSigner.frostBased();
    }

    @Test void frost_combine_and_verify_threshold_2_of_3() {
        var shares = signer.generateKeyShares(3, 2);
        var message = "FROST threshold signature test".getBytes();

        var partials = new ArrayList<ThresholdSigner.PartialSignature>();
        partials.add(signer.partialSign(0, shares.get(0), message));
        partials.add(signer.partialSign(1, shares.get(1), message));

        var combined = signer.combine(partials, message);
        assertThat(combined.valid()).isTrue();
        assertThat(combined.signatureBytes()).hasSize(64);

        // Verify with the group public key
        assertThat(signer.verify(combined.signatureBytes(), message,
            partials.getFirst().publicKeyBytes())).isTrue();
    }

    @Test void frost_combine_and_verify_threshold_3_of_5() {
        var shares = signer.generateKeyShares(5, 3);
        var message = "K-of-N test".getBytes();

        // Use shares 1, 3, 4 (not sequential)
        var partials = new ArrayList<ThresholdSigner.PartialSignature>();
        partials.add(signer.partialSign(0, shares.get(0), message));
        partials.add(signer.partialSign(2, shares.get(2), message));
        partials.add(signer.partialSign(4, shares.get(4), message));

        var combined = signer.combine(partials, message);
        assertThat(combined.valid()).isTrue();

        assertThat(signer.verify(combined.signatureBytes(), message,
            partials.getFirst().publicKeyBytes())).isTrue();
    }

    @Test void frost_fails_with_insufficient_shares() {
        var shares = signer.generateKeyShares(5, 3);
        var message = "not enough shares".getBytes();

        var partials = new ArrayList<ThresholdSigner.PartialSignature>();
        partials.add(signer.partialSign(0, shares.get(0), message));

        var combined = signer.combine(partials, message);
        assertThat(combined.valid()).isFalse();
    }

    @Test void frost_signature_invalid_for_wrong_message() {
        var shares = signer.generateKeyShares(3, 2);
        var message = "original message".getBytes();

        var partials = new ArrayList<ThresholdSigner.PartialSignature>();
        partials.add(signer.partialSign(0, shares.get(0), message));
        partials.add(signer.partialSign(1, shares.get(1), message));

        var combined = signer.combine(partials, message);
        assertThat(combined.valid()).isTrue();

        // Verify against wrong message
        assertThat(signer.verify(combined.signatureBytes(),
            "tampered message".getBytes(),
            partials.getFirst().publicKeyBytes())).isFalse();
    }
}
