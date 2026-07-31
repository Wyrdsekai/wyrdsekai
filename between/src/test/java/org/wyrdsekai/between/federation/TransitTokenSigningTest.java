package org.wyrdsekai.between.federation;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TransitTokenSigningTest {

    private static KeyPair keyPair;
    private static KeyPair wrongKeyPair;

    @BeforeAll
    static void generateKeys() throws NoSuchAlgorithmException {
        var gen = KeyPairGenerator.getInstance("Ed25519");
        keyPair = gen.generateKeyPair();
        wrongKeyPair = gen.generateKeyPair();
    }

    // --- Backward compatibility (unsigned) ---

    @Test void unsigned_tourist_has_null_signature() {
        var token = TransitToken.createTourist("agent-1", "Wyrd", "zone-a", "zone-b");
        assertThat(token.signature()).isNull();
        assertThat(token.trustLevel()).isEqualTo("tourist");
        assertThat(token.isValid()).isTrue();
    }

    @Test void unsigned_resident_has_null_signature() {
        var token = TransitToken.createResident("agent-1", "Wyrd", "zone-a", "zone-b");
        assertThat(token.signature()).isNull();
        assertThat(token.trustLevel()).isEqualTo("resident");
    }

    @Test void unsigned_citizen_has_null_signature() {
        var token = TransitToken.createCitizen("agent-1", "Wyrd", "zone-a", "zone-b");
        assertThat(token.signature()).isNull();
        assertThat(token.trustLevel()).isEqualTo("citizen");
    }

    // --- Signed creation + verification ---

    @Test void signed_tourist_verifies_with_correct_key() {
        var token = TransitToken.createTourist(
            "agent-1", "Wyrd", "zone-a", "zone-b", keyPair.getPrivate());
        assertThat(token.signature()).isNotNull();
        assertThat(token.verify(keyPair.getPublic())).isTrue();
    }

    @Test void signed_resident_verifies_with_correct_key() {
        var token = TransitToken.createResident(
            "agent-1", "Wyrd", "zone-a", "zone-b", keyPair.getPrivate());
        assertThat(token.signature()).isNotNull();
        assertThat(token.trustLevel()).isEqualTo("resident");
        assertThat(token.verify(keyPair.getPublic())).isTrue();
    }

    @Test void signed_citizen_verifies_with_correct_key() {
        var token = TransitToken.createCitizen(
            "agent-1", "Wyrd", "zone-a", "zone-b", keyPair.getPrivate());
        assertThat(token.signature()).isNotNull();
        assertThat(token.trustLevel()).isEqualTo("citizen");
        assertThat(token.verify(keyPair.getPublic())).isTrue();
    }

    @Test void verify_rejects_wrong_key() {
        var token = TransitToken.createTourist(
            "agent-1", "Wyrd", "zone-a", "zone-b", keyPair.getPrivate());
        assertThat(token.verify(wrongKeyPair.getPublic())).isFalse();
    }

    @Test void verify_returns_false_for_unsigned_token() {
        var token = TransitToken.createTourist("agent-1", "Wyrd", "zone-a", "zone-b");
        assertThat(token.verify(keyPair.getPublic())).isFalse();
    }

    // --- Duration checks ---

    @Test void tourist_duration_is_one_hour() {
        var token = TransitToken.createTourist("agent-1", "Wyrd", "zone-a", "zone-b");
        var duration = Duration.between(token.issuedAt(), token.expiresAt());
        assertThat(duration).isEqualTo(Duration.ofHours(1));
    }

    @Test void resident_duration_is_24_hours() {
        var token = TransitToken.createResident("agent-1", "Wyrd", "zone-a", "zone-b");
        var duration = Duration.between(token.issuedAt(), token.expiresAt());
        assertThat(duration).isEqualTo(Duration.ofHours(24));
    }

    @Test void citizen_duration_is_7_days() {
        var token = TransitToken.createCitizen("agent-1", "Wyrd", "zone-a", "zone-b");
        var duration = Duration.between(token.issuedAt(), token.expiresAt());
        assertThat(duration).isEqualTo(Duration.ofDays(7));
    }

    // --- Legacy 8-arg constructor still works ---

    @Test void legacy_constructor_backward_compat() {
        var now = Instant.now();
        var token = new TransitToken(
            "tok-1", "agent-1", "Wyrd", "zone-a", "zone-b",
            "tourist", now, now.plusSeconds(3600));
        assertThat(token.signature()).isNull();
        assertThat(token.isValid()).isTrue();
    }
}
