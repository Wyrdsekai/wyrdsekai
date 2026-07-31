package org.wyrdsekai.core.crypto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PqcConfigTest {

    @Test void ed25519_always_available() {
        var sigs = PqcConfig.availableSignatures();
        assertThat(sigs).contains(PqcConfig.SignatureAlgorithm.ED25519);
    }

    @Test void x25519_always_available() {
        var kex = PqcConfig.availableKeyExchanges();
        assertThat(kex).contains(PqcConfig.KeyExchange.X25519);
    }

    @Test void recommendedCipherSuites_not_empty() {
        assertThat(PqcConfig.recommendedCipherSuites()).isNotEmpty();
    }

    @Test void describe_contains_java_version() {
        var desc = PqcConfig.describe();
        assertThat(desc).contains("Java version");
        assertThat(desc).contains("Cryptographic Capabilities");
    }
}
