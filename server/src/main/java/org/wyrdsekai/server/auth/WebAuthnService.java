package org.wyrdsekai.server.auth;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebAuthn/FIDO2 passkey registration and authentication (§9B).
 * Provides passwordless login via platform authenticators (Touch ID, Windows Hello, etc.).
 * Integration point for webauthn4j library.
 */
public class WebAuthnService {

    /** A registered passkey credential. */
    public record PasskeyCredential(
        String credentialId,
        String userId,
        String publicKeyBase64,
        String rpId,
        long signCount,
        Instant registeredAt,
        Instant lastUsedAt,
        String displayName
    ) {}

    /** Registration challenge (sent to client). */
    public record RegistrationChallenge(
        String challengeBase64,
        String rpId,
        String rpName,
        String userId,
        String userName,
        Instant issuedAt,
        Instant expiresAt
    ) {
        public boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }

    /** Authentication challenge (sent to client). */
    public record AuthChallenge(
        String challengeBase64,
        String rpId,
        List<String> allowedCredentialIds,
        Instant issuedAt,
        Instant expiresAt
    ) {
        public boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }

    /** Result of a registration or authentication attempt. */
    public record AuthResult(boolean success, String message, String userId) {
        public static AuthResult success(String userId) {
            return new AuthResult(true, "Authentication successful", userId);
        }

        public static AuthResult failure(String message) {
            return new AuthResult(false, message, null);
        }
    }

    private static final int CHALLENGE_BYTES = 32;
    private static final long CHALLENGE_TIMEOUT_SECONDS = 300; // 5 minutes

    private final String rpId;
    private final String rpName;
    private final Map<String, PasskeyCredential> credentials = new ConcurrentHashMap<>();
    private final Map<String, RegistrationChallenge> regChallenges = new ConcurrentHashMap<>();
    private final Map<String, AuthChallenge> authChallenges = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    public WebAuthnService(String rpId, String rpName) {
        this.rpId = rpId;
        this.rpName = rpName;
    }

    /**
     * Begin registration — generate challenge for the client.
     */
    public RegistrationChallenge beginRegistration(String userId, String userName) {
        var challenge = generateChallenge();
        var now = Instant.now();
        var reg = new RegistrationChallenge(challenge, rpId, rpName, userId, userName,
            now, now.plusSeconds(CHALLENGE_TIMEOUT_SECONDS));
        regChallenges.put(challenge, reg);
        return reg;
    }

    /**
     * Complete registration — verify and store the credential.
     * In production, this validates the attestation from the authenticator.
     */
    public AuthResult completeRegistration(String challengeBase64, String credentialId,
                                            String publicKeyBase64, String displayName) {
        var reg = regChallenges.remove(challengeBase64);
        if (reg == null) return AuthResult.failure("Challenge not found or expired");
        if (reg.isExpired()) return AuthResult.failure("Challenge expired");

        var credential = new PasskeyCredential(credentialId, reg.userId(), publicKeyBase64,
            rpId, 0, Instant.now(), Instant.now(), displayName);
        credentials.put(credentialId, credential);
        return AuthResult.success(reg.userId());
    }

    /**
     * Begin authentication — generate challenge with allowed credentials.
     */
    public AuthChallenge beginAuthentication(String userId) {
        var challenge = generateChallenge();
        var allowed = credentialsForUser(userId).stream()
            .map(PasskeyCredential::credentialId)
            .toList();
        var now = Instant.now();
        var auth = new AuthChallenge(challenge, rpId, allowed,
            now, now.plusSeconds(CHALLENGE_TIMEOUT_SECONDS));
        authChallenges.put(challenge, auth);
        return auth;
    }

    /**
     * Complete authentication — verify the assertion.
     * In production, this validates the signature from the authenticator.
     */
    public AuthResult completeAuthentication(String challengeBase64, String credentialId,
                                              long newSignCount) {
        var auth = authChallenges.remove(challengeBase64);
        if (auth == null) return AuthResult.failure("Challenge not found or expired");
        if (auth.isExpired()) return AuthResult.failure("Challenge expired");

        var credential = credentials.get(credentialId);
        if (credential == null) return AuthResult.failure("Credential not found");

        // Verify sign count is increasing (replay protection)
        if (newSignCount <= credential.signCount()) {
            return AuthResult.failure("Sign count not increasing — possible cloned authenticator");
        }

        // Update credential with new sign count and last used time
        var updated = new PasskeyCredential(credential.credentialId(), credential.userId(),
            credential.publicKeyBase64(), credential.rpId(), newSignCount,
            credential.registeredAt(), Instant.now(), credential.displayName());
        credentials.put(credentialId, updated);

        return AuthResult.success(credential.userId());
    }

    /** Get all credentials for a user. */
    public List<PasskeyCredential> credentialsForUser(String userId) {
        return credentials.values().stream()
            .filter(c -> c.userId().equals(userId))
            .sorted(Comparator.comparing(PasskeyCredential::registeredAt))
            .toList();
    }

    /** Get a credential by ID. */
    public Optional<PasskeyCredential> getCredential(String credentialId) {
        return Optional.ofNullable(credentials.get(credentialId));
    }

    /** Remove a credential. */
    public boolean removeCredential(String credentialId) {
        return credentials.remove(credentialId) != null;
    }

    /** Total registered credentials. */
    public int credentialCount() {
        return credentials.size();
    }

    /** RP (relying party) ID. */
    public String rpId() {
        return rpId;
    }

    private String generateChallenge() {
        var bytes = new byte[CHALLENGE_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
