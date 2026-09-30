package org.wyrdsekai.cli;

import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The CLI's relay certificate check (security review 2026-09-28). It used to accept any chain that
 * contained the pinned CA. The CA certificate is public (every handshake sends it), so a man in the middle
 * presenting his own leaf beside the real CA passed. Fixtures come from the relay's own
 * deploy/relay/certinit/gen-cert.sh: a real CA + leaf for relay.example.test / 192.0.2.10, and a forged leaf
 * with the same names from another CA.
 */
class RelayTunnelPinnedTrustTest {

    private static final Date DURING = Date.from(Instant.parse("2026-10-15T00:00:00Z"));

    private static X509Certificate cert(String name) throws Exception {
        try (var in = RelayTunnelPinnedTrustTest.class.getResourceAsStream("/relaytunnel/" + name)) {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
    }

    private static String fp(X509Certificate c) throws Exception {
        return HexFormat.ofDelimiter(":").withUpperCase()
            .formatHex(MessageDigest.getInstance("SHA-256").digest(c.getEncoded()));
    }

    @Test
    void theRealRelayValidatesToThePinnedCa() throws Exception {
        var leaf = cert("real-leaf.crt"); var ca = cert("real-ca.crt");
        var chain = new X509Certificate[]{leaf, ca};
        assertThatCode(() -> RelayTunnelConnection.pinnedTrust(fp(ca), "relay.example.test", DURING)
            .checkServerTrusted(chain, "RSA")).doesNotThrowAnyException();
        assertThatCode(() -> RelayTunnelConnection.pinnedTrust(fp(ca), "192.0.2.10", DURING)
            .checkServerTrusted(chain, "RSA")).doesNotThrowAnyException();
    }

    @Test
    void aForgedLeafBesideTheRealCaIsRefused() throws Exception {
        var forged = cert("forged-leaf.crt"); var realCa = cert("real-ca.crt");
        assertThatThrownBy(() -> RelayTunnelConnection.pinnedTrust(fp(realCa), "relay.example.test", DURING)
            .checkServerTrusted(new X509Certificate[]{forged, realCa}, "RSA"))
            .isInstanceOf(CertificateException.class)
            .hasMessageContaining("does not validate to the pinned CA");
    }

    @Test
    void aCertificateForAnotherHostIsRefused() throws Exception {
        var leaf = cert("real-leaf.crt"); var ca = cert("real-ca.crt");
        assertThatThrownBy(() -> RelayTunnelConnection.pinnedTrust(fp(ca), "relay.attacker.test", DURING)
            .checkServerTrusted(new X509Certificate[]{leaf, ca}, "RSA"))
            .isInstanceOf(CertificateException.class).hasMessageContaining("not for relay.attacker.test");
    }

    @Test
    void anExpiredChainIsRefused() throws Exception {
        var leaf = cert("real-leaf.crt"); var ca = cert("real-ca.crt");
        var later = Date.from(Instant.parse("2031-01-01T00:00:00Z"));
        assertThatThrownBy(() -> RelayTunnelConnection.pinnedTrust(fp(ca), "relay.example.test", later)
            .checkServerTrusted(new X509Certificate[]{leaf, ca}, "RSA"))
            .isInstanceOf(CertificateException.class);
    }

    @Test
    void aChainWithoutThePinIsRefused() throws Exception {
        var leaf = cert("real-leaf.crt"); var ca = cert("real-ca.crt");
        assertThatThrownBy(() -> RelayTunnelConnection.pinnedTrust("00".repeat(32), "relay.example.test", DURING)
            .checkServerTrusted(new X509Certificate[]{leaf, ca}, "RSA"))
            .isInstanceOf(CertificateException.class).hasMessageContaining("Fingerprint mismatch");
    }

    @Test
    void aPinnedLeafIsAcceptedForItsHost() throws Exception {
        var leaf = cert("real-leaf.crt"); var ca = cert("real-ca.crt");
        assertThatCode(() -> RelayTunnelConnection.pinnedTrust(fp(leaf), "relay.example.test", DURING)
            .checkServerTrusted(new X509Certificate[]{leaf, ca}, "RSA")).doesNotThrowAnyException();
    }
}
