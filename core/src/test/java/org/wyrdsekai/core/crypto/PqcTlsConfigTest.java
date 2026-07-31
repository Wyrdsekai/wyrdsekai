package org.wyrdsekai.core.crypto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PqcTlsConfigTest {

    @Test void hybridParameters_uses_tls13() {
        var params = PqcTlsConfig.hybridParameters();
        assertThat(params.getProtocols()).contains("TLSv1.3");
    }

    @Test void hybridParameters_has_cipher_suites() {
        var params = PqcTlsConfig.hybridParameters();
        assertThat(params.getCipherSuites()).isNotEmpty();
    }

    @Test void availableNamedGroups_always_has_x25519() {
        var groups = PqcTlsConfig.availableNamedGroups();
        assertThat(groups).anyMatch(g -> g.jsseName().equals("x25519"));
    }

    @Test void availableNamedGroups_always_has_secp256r1() {
        var groups = PqcTlsConfig.availableNamedGroups();
        assertThat(groups).anyMatch(g -> g.jsseName().equals("secp256r1"));
    }

    @Test void createContext_succeeds() {
        var ctx = PqcTlsConfig.createContext();
        assertThat(ctx).isNotNull();
    }

    @Test void pekkoArteryTlsConfig_generates_hocon() {
        var config = PqcTlsConfig.pekkoArteryTlsConfig();
        assertThat(config).contains("pekko.remote.artery");
        assertThat(config).contains("transport = tls-tcp");
        assertThat(config).contains("TLSv1.3");
    }

    @Test void describe_shows_tls_info() {
        var desc = PqcTlsConfig.describe();
        assertThat(desc).contains("TLS Configuration");
        assertThat(desc).contains("TLS 1.3");
        assertThat(desc).contains("x25519");
    }
}
