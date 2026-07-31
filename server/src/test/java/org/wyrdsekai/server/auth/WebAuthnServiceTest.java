package org.wyrdsekai.server.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WebAuthnServiceTest {

    private WebAuthnService service;

    @BeforeEach
    void setUp() {
        service = new WebAuthnService("wyrdsekai.local", "Wyrdsekai");
    }

    @Test void begin_registration() {
        var challenge = service.beginRegistration("user-1", "alice");
        assertThat(challenge.challengeBase64()).isNotBlank();
        assertThat(challenge.rpId()).isEqualTo("wyrdsekai.local");
        assertThat(challenge.rpName()).isEqualTo("Wyrdsekai");
        assertThat(challenge.isExpired()).isFalse();
    }

    @Test void complete_registration() {
        var challenge = service.beginRegistration("user-1", "alice");
        var result = service.completeRegistration(challenge.challengeBase64(),
            "cred-1", "pubkey-base64", "Alice's Touch ID");
        assertThat(result.success()).isTrue();
        assertThat(result.userId()).isEqualTo("user-1");
        assertThat(service.credentialCount()).isEqualTo(1);
    }

    @Test void registration_with_invalid_challenge() {
        var result = service.completeRegistration("bogus-challenge",
            "cred-1", "pubkey", "name");
        assertThat(result.success()).isFalse();
    }

    @Test void begin_authentication() {
        // Register first
        var regChallenge = service.beginRegistration("user-1", "alice");
        service.completeRegistration(regChallenge.challengeBase64(),
            "cred-1", "pubkey", "Alice");

        var authChallenge = service.beginAuthentication("user-1");
        assertThat(authChallenge.challengeBase64()).isNotBlank();
        assertThat(authChallenge.allowedCredentialIds()).contains("cred-1");
    }

    @Test void complete_authentication() {
        // Register
        var reg = service.beginRegistration("user-1", "alice");
        service.completeRegistration(reg.challengeBase64(), "cred-1", "pubkey", "Alice");

        // Authenticate
        var auth = service.beginAuthentication("user-1");
        var result = service.completeAuthentication(auth.challengeBase64(), "cred-1", 1);
        assertThat(result.success()).isTrue();
        assertThat(result.userId()).isEqualTo("user-1");
    }

    @Test void authentication_replay_protection() {
        // Register
        var reg = service.beginRegistration("user-1", "alice");
        service.completeRegistration(reg.challengeBase64(), "cred-1", "pubkey", "Alice");

        // First auth (signCount = 1)
        var auth1 = service.beginAuthentication("user-1");
        service.completeAuthentication(auth1.challengeBase64(), "cred-1", 1);

        // Second auth with same signCount (replay) — should fail
        var auth2 = service.beginAuthentication("user-1");
        var result = service.completeAuthentication(auth2.challengeBase64(), "cred-1", 1);
        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("Sign count");
    }

    @Test void credentials_for_user() {
        var reg1 = service.beginRegistration("user-1", "alice");
        service.completeRegistration(reg1.challengeBase64(), "cred-1", "pk1", "Touch ID");
        var reg2 = service.beginRegistration("user-1", "alice");
        service.completeRegistration(reg2.challengeBase64(), "cred-2", "pk2", "Security Key");

        assertThat(service.credentialsForUser("user-1")).hasSize(2);
    }

    @Test void remove_credential() {
        var reg = service.beginRegistration("user-1", "alice");
        service.completeRegistration(reg.challengeBase64(), "cred-1", "pk", "Touch ID");

        assertThat(service.removeCredential("cred-1")).isTrue();
        assertThat(service.credentialCount()).isEqualTo(0);
    }

    @Test void get_credential() {
        var reg = service.beginRegistration("user-1", "alice");
        service.completeRegistration(reg.challengeBase64(), "cred-1", "pk", "Touch ID");

        assertThat(service.getCredential("cred-1")).isPresent();
        assertThat(service.getCredential("nonexistent")).isEmpty();
    }

    @Test void rp_id() {
        assertThat(service.rpId()).isEqualTo("wyrdsekai.local");
    }
}
