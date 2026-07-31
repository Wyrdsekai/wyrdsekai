package org.wyrdsekai.core.identity;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VerifiableCredentialTest {

    @Test void role_credential() {
        var vc = VerifiableCredential.role("vc-1",
            "did:wyrd:zone:issuer", "did:wyrd:zone:subject",
            "wizard", Instant.now().plusSeconds(86400));

        assertThat(vc.type()).isEqualTo("role");
        assertThat(vc.getClaim("role")).isEqualTo("wizard");
        assertThat(vc.isValid()).isTrue();
    }

    @Test void membership_credential() {
        var vc = VerifiableCredential.membership("vc-2",
            "did:wyrd:zone:issuer", "did:wyrd:zone:subject",
            "foundation");

        assertThat(vc.type()).isEqualTo("membership");
        assertThat(vc.getClaim("zone")).isEqualTo("foundation");
        assertThat(vc.isValid()).isTrue();
    }

    @Test void expired_credential_invalid() {
        var vc = new VerifiableCredential("vc-3", "role",
            "did:wyrd:z:i", "did:wyrd:z:s",
            Map.of("role", "wizard"),
            Instant.now().minusSeconds(7200),
            Instant.now().minusSeconds(3600), // expired
            false);

        assertThat(vc.isValid()).isFalse();
    }

    @Test void revoked_credential_invalid() {
        var vc = VerifiableCredential.role("vc-4",
            "did:wyrd:z:i", "did:wyrd:z:s",
            "wizard", Instant.now().plusSeconds(86400));
        var revoked = vc.revoke();

        assertThat(revoked.isValid()).isFalse();
        assertThat(revoked.revoked()).isTrue();
    }

    @Test void has_claim() {
        var vc = VerifiableCredential.role("vc-5",
            "did:wyrd:z:i", "did:wyrd:z:s",
            "wizard", null);

        assertThat(vc.hasClaim("role")).isTrue();
        assertThat(vc.hasClaim("missing")).isFalse();
    }

    @Test void no_expiry_is_valid() {
        var vc = VerifiableCredential.membership("vc-6",
            "did:wyrd:z:i", "did:wyrd:z:s", "zone");

        assertThat(vc.expiresAt()).isNull();
        assertThat(vc.isValid()).isTrue();
    }
}
