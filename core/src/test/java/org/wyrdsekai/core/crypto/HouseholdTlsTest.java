package org.wyrdsekai.core.crypto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** W2: the household CA and this machine's leaf. */
class HouseholdTlsTest {

    private static final Set<String> NAMES = new LinkedHashSet<>(List.of(
        "localhost", "127.0.0.1", "second-node", "second-node.local", "198.51.100.65", "fd00::65"));

    @Test
    void makesCaAndLeafWithEveryNameAndAddress(@TempDir Path data) throws Exception {
        var now = Instant.now();
        var m = HouseholdTls.ensure(data, NAMES, now);

        assertThat(m.caFingerprint()).matches("[0-9a-f]{64}");
        assertThat(m.caFingerprint()).isEqualTo(HouseholdTls.fingerprint(HouseholdTls.readCa(data)));
        assertThat(m.ca().getBasicConstraints()).isGreaterThanOrEqualTo(0);
        assertThat(m.leaf().getBasicConstraints()).isEqualTo(-1);
        m.leaf().verify(m.ca().getPublicKey());
        assertThat(m.leaf().getExtendedKeyUsage()).contains("1.3.6.1.5.5.7.3.1");
        assertThat(HouseholdTls.sanNames(m.leaf()))
            .contains("localhost", "second-node", "second-node.local", "127.0.0.1", "198.51.100.65")
            .anyMatch(n -> n.startsWith("fd00:"));
        var life = Duration.between(m.leaf().getNotBefore().toInstant(), m.leaf().getNotAfter().toInstant());
        assertThat(life).isLessThanOrEqualTo(Duration.ofDays(825));

        // The chain file serves leaf then CA (nats-server and Jetty send both).
        var chain = HouseholdTls.readCertificates(Files.readString(m.leafChainPem()));
        assertThat(chain).hasSize(2);
        assertThat(chain.get(1)).isEqualTo(m.ca());
    }

    @Test
    void keysArePrivateAndTheKeystorePasswordIsPerInstall(@TempDir Path data) throws Exception {
        var m = HouseholdTls.ensure(data, NAMES, Instant.now());
        var pass = Files.readString(m.dir().resolve(HouseholdTls.KEYSTORE_PASS)).trim();
        assertThat(pass).isNotEqualTo("wyrdsekai").hasSizeGreaterThanOrEqualTo(40);

        var ks = KeyStore.getInstance(m.keystore().toFile(), pass.toCharArray());
        assertThat(ks.getCertificateChain(HouseholdTls.KEYSTORE_ALIAS)).hasSize(2);
        assertThatThrownBy(() -> KeyStore.getInstance(m.keystore().toFile(), "wyrdsekai".toCharArray()))
            .isInstanceOf(Exception.class);

        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        for (var f : List.of(HouseholdTls.CA_KEY, HouseholdTls.KEYSTORE_PASS, HouseholdTls.KEYSTORE)) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(m.dir().resolve(f))))
                .as(f).isEqualTo("rw-------");
        }
        // 0600, or 0640 to the standalone bus service's group where it exists.
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(m.leafKeyPem())))
            .isIn("rw-------", "rw-r-----");
    }

    @Test
    void keepsCaAndLeafAcrossBoots(@TempDir Path data) throws Exception {
        var first = HouseholdTls.ensure(data, NAMES, Instant.now());
        var second = HouseholdTls.ensure(data, NAMES, Instant.now());
        assertThat(second.caFingerprint()).isEqualTo(first.caFingerprint());
        assertThat(second.leaf().getSerialNumber()).isEqualTo(first.leaf().getSerialNumber());
    }

    @Test
    void renewsTheLeafNearExpiryButKeepsTheCa(@TempDir Path data) throws Exception {
        var first = HouseholdTls.ensure(data, NAMES, Instant.now());
        var nearExpiry = first.leaf().getNotAfter().toInstant().minus(Duration.ofDays(10));
        var renewed = HouseholdTls.ensure(data, NAMES, nearExpiry);
        assertThat(renewed.caFingerprint()).isEqualTo(first.caFingerprint());
        assertThat(renewed.leaf().getSerialNumber()).isNotEqualTo(first.leaf().getSerialNumber());
        assertThat(renewed.leaf().getNotAfter()).isAfter(first.leaf().getNotAfter());
        // The keystore the web server loads follows the new leaf.
        var pass = Files.readString(data.resolve("tls").resolve(HouseholdTls.KEYSTORE_PASS)).trim().toCharArray();
        var ks = KeyStore.getInstance(renewed.keystore().toFile(), pass);
        assertThat(((X509Certificate) ks.getCertificate(HouseholdTls.KEYSTORE_ALIAS)).getSerialNumber())
            .isEqualTo(renewed.leaf().getSerialNumber());
    }

    @Test
    void issuesAgainWhenANewAddressAppears(@TempDir Path data) throws Exception {
        var first = HouseholdTls.ensure(data, NAMES, Instant.now());
        var moved = new LinkedHashSet<>(NAMES);
        moved.add("192.0.2.7");
        var second = HouseholdTls.ensure(data, moved, Instant.now());
        assertThat(second.caFingerprint()).isEqualTo(first.caFingerprint());
        assertThat(HouseholdTls.sanNames(second.leaf())).contains("192.0.2.7");
        assertThat(second.leaf().getSerialNumber()).isNotEqualTo(first.leaf().getSerialNumber());
    }

    @Test
    void aLeafFromAnotherCaIsReplaced(@TempDir Path a, @TempDir Path b) throws Exception {
        var mine = HouseholdTls.ensure(a, NAMES, Instant.now());
        var other = HouseholdTls.ensure(b, NAMES, Instant.now());
        assertThat(HouseholdTls.staleReason(other.leaf(), mine.ca(), NAMES, Instant.now()))
            .contains("not signed");
        assertThat(HouseholdTls.staleReason(mine.leaf(), mine.ca(), NAMES, Instant.now())).isNull();
    }

    @Test
    void localNamesCarryLoopbackAndTheHostName() {
        var names = HouseholdTls.localNames();
        assertThat(names).contains("localhost", "127.0.0.1");
        assertThat(names).noneMatch(n -> n.startsWith("fe80"));
    }

    @Test
    void inviteFieldsCarryTheFingerprintAndTheHttpsAddress(@TempDir Path data) throws Exception {
        assertThat(HouseholdTls.inviteFields(data, false, 7443)).doesNotContainKey("home_ca_fp");
        var m = HouseholdTls.ensure(data, NAMES, Instant.now());
        var fields = HouseholdTls.inviteFields(data, true, 7443);
        assertThat(fields.get("home_ca_fp")).isEqualTo(m.caFingerprint());
        if (fields.containsKey("lan_https")) {
            assertThat(fields.get("lan_https")).startsWith("https://").endsWith(":7443");
        }
        assertThat(HouseholdTls.inviteFields(data, false, 7443)).doesNotContainKey("lan_https");
    }
}
