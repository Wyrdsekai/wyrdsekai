package org.wyrdsekai.between;

import io.nats.client.AuthHandler;
import io.nats.client.Options;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The household-to-relay link is TLS, and the relay is the one whose fingerprint the household took
 * when it joined (2026-09-28: until then the link was plain NATS across the internet). Fixtures are two
 * relays' certificates made by the relay's own deploy/relay/certinit/gen-cert.sh.
 */
class RelayTlsTest {

    private static X509Certificate cert(String name) throws Exception {
        try (var in = RelayTlsTest.class.getResourceAsStream("/relaytls/" + name)) {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
    }

    @Test
    void theServedCertificateMatchesItsPin() throws Exception {
        var leaf = cert("leaf-a.crt"); var ca = cert("ca-a.crt");
        assertThatCode(() -> RelayTls.pinner(RelayTls.sha256(leaf)).checkServerTrusted(new X509Certificate[]{leaf, ca}, "RSA"))
            .doesNotThrowAnyException();
    }

    @Test
    void theRelaysAuthorityMatchesWhenItSignedTheServedCertificate() throws Exception {
        var leaf = cert("leaf-a.crt"); var ca = cert("ca-a.crt");
        assertThatCode(() -> RelayTls.pinner(RelayTls.sha256(ca)).checkServerTrusted(new X509Certificate[]{leaf, ca}, "RSA"))
            .doesNotThrowAnyException();
    }

    @Test
    void anImpostorCarryingTheRealCertificateIsRefused() throws Exception {
        // Someone else's certificate served, the real relay's authority (public, no key) attached beside it.
        var impostor = cert("leaf-b.crt"); var realCa = cert("ca-a.crt"); var realLeaf = cert("leaf-a.crt");
        assertThatThrownBy(() -> RelayTls.pinner(RelayTls.sha256(realCa)).checkServerTrusted(new X509Certificate[]{impostor, realCa}, "RSA"))
            .isInstanceOf(CertificateException.class).hasMessageContaining("not signed by the pinned relay authority");
        assertThatThrownBy(() -> RelayTls.pinner(RelayTls.sha256(realLeaf)).checkServerTrusted(new X509Certificate[]{impostor, realLeaf}, "RSA"))
            .isInstanceOf(CertificateException.class);
    }

    @Test
    void aDifferentRelayIsRefused() throws Exception {
        var otherLeaf = cert("leaf-b.crt"); var otherCa = cert("ca-b.crt"); var pin = RelayTls.sha256(cert("leaf-a.crt"));
        assertThatThrownBy(() -> RelayTls.pinner(pin).checkServerTrusted(new X509Certificate[]{otherLeaf, otherCa}, "RSA"))
            .isInstanceOf(CertificateException.class).hasMessageContaining("does not match the fingerprint on file");
    }

    @Test
    void fingerprintsAreReadInEveryShapeTheyAreWritten() throws Exception {
        var hex = RelayTls.sha256(cert("leaf-a.crt"));
        var colons = hex.toUpperCase().replaceAll("(..)(?!$)", "$1:");
        assertThat(RelayTls.normalize(colons)).isEqualTo(hex);
        assertThat(RelayTls.normalize("sha256:" + hex)).isEqualTo(hex);
        assertThat(RelayTls.normalize("sha256 Fingerprint=" + colons)).isEqualTo(hex);
    }

    @Test
    void aRelayThatOffersNoTlsIsRecognised() {
        var jnats = new IOException("Unable to connect to NATS servers: [nats://relay.example:4222]");
        jnats.addSuppressed(new IOException("SSL connection wanted by client."));
        assertThat(RelayTls.relayOffersNoTls(jnats)).isTrue();
        assertThat(RelayTls.relayOffersNoTls(new IOException("Connection refused"))).isFalse();
    }

    @Test
    void theRelaysFirstLineSaysWhetherItOffersTls() {
        assertThat(RelayTls.infoOffersTls("INFO {\"server_id\":\"X\",\"tls_available\":true,\"max_payload\":1048576}")).isTrue();
        assertThat(RelayTls.infoOffersTls("INFO {\"tls_required\": true}")).isTrue();
        assertThat(RelayTls.infoOffersTls("INFO {\"server_id\":\"X\",\"auth_required\":true}")).isFalse();
        assertThat(RelayTls.infoOffersTls("-ERR 'Authorization Violation'")).isNull();
        assertThat(RelayTls.infoOffersTls(null)).isNull();
    }

    @Test
    void firstUseTrustsTheFirstRelayAndPinsItsAuthority() throws Exception {
        var leaf = cert("leaf-a.crt"); var ca = cert("ca-a.crt");
        var firstUse = new RelayTls.FirstUse();
        firstUse.checkServerTrusted(new X509Certificate[]{leaf, ca}, "RSA");
        assertThat(firstUse.pin()).isEqualTo(RelayTls.sha256(ca));
        // A reconnect to the same relay passes; a different relay on the same link is refused.
        assertThatCode(() -> firstUse.checkServerTrusted(new X509Certificate[]{leaf, ca}, "RSA")).doesNotThrowAnyException();
        assertThatThrownBy(() -> firstUse.checkServerTrusted(new X509Certificate[]{cert("leaf-b.crt"), cert("ca-b.crt")}, "RSA"))
            .isInstanceOf(CertificateException.class).hasMessageContaining("relay certificate mismatch");
    }

    @Test
    void firstUsePinsTheLeafWhenNoAuthoritySignedIt() throws Exception {
        var leafA = cert("leaf-a.crt");
        assertThat(RelayTls.pinOf(new X509Certificate[]{leafA, cert("ca-b.crt")})).isEqualTo(RelayTls.sha256(leafA));
        assertThat(RelayTls.pinOf(new X509Certificate[]{leafA})).isEqualTo(RelayTls.sha256(leafA));
    }

    @Test
    void anyPinOnFileMatches() throws Exception {
        var leaf = cert("leaf-a.crt"); var ca = cert("ca-a.crt");
        var stale = RelayTls.sha256(cert("leaf-b.crt"));
        assertThatCode(() -> RelayTls.pinner(java.util.List.of(stale, RelayTls.sha256(ca)))
            .checkServerTrusted(new X509Certificate[]{leaf, ca}, "RSA")).doesNotThrowAnyException();
        assertThatThrownBy(() -> RelayTls.pinner(java.util.List.of(stale))
            .checkServerTrusted(new X509Certificate[]{leaf, ca}, "RSA"))
            .isInstanceOf(CertificateException.class).hasMessageContaining("relay certificate mismatch");
    }

    @Test
    void pinsAreKeptPerRelayOnceAndPrivately(@TempDir Path dir) throws Exception {
        var hex = RelayTls.sha256(cert("ca-a.crt"));
        var colons = hex.toUpperCase().replaceAll("(..)(?!$)", "$1:");
        RelayTls.recordPin(dir, "nats://Relay.Example.org:4222", colons);
        RelayTls.recordPin(dir, "relay.example.org:4222", hex);
        var file = dir.resolve(RelayTls.PINS_FILE);
        assertThat(RelayTls.storedPins(file, "relay.example.org:4222")).containsExactly(hex);
        assertThat(RelayTls.storedPins(file, "other.example.org:4222")).isEmpty();
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------");
    }

    @Test
    void everyRelayLinkReadsOnlyItsOwnInbox() {
        var password = new Options.Builder().server("nats://relay.example.org:4222").userInfo("hh-0123456789ab", "pw");
        RelayTls.withOwnInbox(password);
        assertThat(password.build().getInboxPrefix()).isEqualTo("_INBOX.hh-0123456789ab.");

        var nkey = "UABCDEFGHIJKLMNOPQRSTUVWXYZ234567ABCDEFGHIJKLMNOPQRSTUVW";
        var signed = new Options.Builder().server("nats://relay.example.org:4222").authHandler(new AuthHandler() {
            @Override public byte[] sign(byte[] nonce) { return new byte[64]; }
            @Override public char[] getID() { return nkey.toCharArray(); }
            @Override public char[] getJWT() { return null; }
        });
        RelayTls.withOwnInbox(signed);
        assertThat(signed.build().getInboxPrefix()).isEqualTo("_INBOX." + nkey + ".");
    }

    @Test
    void nodesUseTheirNkeyUnlessTheLegHoldsARelayPassword() {
        assertThat(RelayTls.useNkey(null, null)).isTrue();
        assertThat(RelayTls.useNkey("", "")).isTrue();
        assertThat(RelayTls.useNkey(null, "relay-password")).isFalse();
        assertThat(RelayTls.useNkey("true", "relay-password")).isTrue();
        assertThat(RelayTls.useNkey("false", null)).isFalse();
    }

    @Test
    void theNodesOwnBusStaysLocalAndRelaysAreNamedByHostAndPort() {
        assertThat(RelayTls.isLocal("nats://127.0.0.1:4222")).isTrue();
        assertThat(RelayTls.isLocal("nats://localhost:4222")).isTrue();
        assertThat(RelayTls.isLocal("nats://relay.example.org:4222")).isFalse();
        assertThat(RelayTls.isLocal("nats://198.51.100.20:4222")).isFalse();
        assertThat(RelayTls.hostPort("nats://Relay.Example.org")).isEqualTo("relay.example.org:4222");
        assertThat(RelayTls.hostPort("relay.example.org:7422")).isEqualTo("relay.example.org:7422");
    }
}
