package org.wyrdsekai.core.crypto;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class ThresholdSignerTest {

    private ThresholdSigner signer;

    @BeforeEach void setUp() {
        signer = ThresholdSigner.shamirBased();
    }

    @Test void generateKeyShares_creates_n_shares() {
        var shares = signer.generateKeyShares(5, 3);
        assertThat(shares).hasSize(5);
    }

    @Test void partialSign_creates_signature() {
        var shares = signer.generateKeyShares(3, 2);
        var partial = signer.partialSign(1, shares.get(0), "test message".getBytes());
        assertThat(partial.participantId()).isEqualTo(1);
        assertThat(partial.publicKeyBytes()).isNotEmpty();
    }

    @Test void combine_and_verify_with_threshold_shares() {
        var shares = signer.generateKeyShares(5, 3);
        var message = "sign this".getBytes();

        var partials = new ArrayList<ThresholdSigner.PartialSignature>();
        for (int i = 0; i < 3; i++) {
            partials.add(signer.partialSign(i, shares.get(i), message));
        }

        var combined = signer.combine(partials, message);
        assertThat(combined.valid()).isTrue();
        assertThat(combined.signatureBytes()).isNotEmpty();
    }

    @Test void combine_fails_with_insufficient_shares() {
        var shares = signer.generateKeyShares(5, 3);
        var message = "sign this".getBytes();

        var partials = new ArrayList<ThresholdSigner.PartialSignature>();
        partials.add(signer.partialSign(0, shares.get(0), message));

        var combined = signer.combine(partials, message);
        assertThat(combined.valid()).isFalse();
    }
}
