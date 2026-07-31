package org.wyrdsekai.core.familiar;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers — summon keys. Signature correctness
 * recipient enforcement (§20.4), revocation (§20.3), scope handling,
 * usage-cap tracking, forgery rejection (§20.5).
 */
class SummonKeyTest {

    private static final String ISSUER = "did:wyrd:zA:mom";
    private static final String RECIPIENT = "did:wyrd:zA:dad";
    private static final String INTRUDER = "did:wyrd:zA:bob";

    private KeyPair issuerKeyPair;
    private KeyPair otherKeyPair;
    private SummonKeyRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        var gen = KeyPairGenerator.getInstance("Ed25519");
        issuerKeyPair = gen.generateKeyPair();
        otherKeyPair = gen.generateKeyPair();
        registry = new SummonKeyRegistry();
    }

    private SummonKey signedKey(SummonKey draft) throws Exception {
        var sig = Signature.getInstance("Ed25519");
        sig.initSign(issuerKeyPair.getPrivate());
        sig.update(draft.canonicalBytes());
        return draft.withSignature(Base64.getEncoder().encodeToString(sig.sign()));
    }

    // ── record invariants ──────────────────────────────────────────────────

    @Test
    void until_date_scope_requires_expiry() {
        assertThrows(IllegalArgumentException.class, () -> new SummonKey(
            "k1", "named:researcher", ISSUER, RECIPIENT,
            SummonKey.Scope.UNTIL_DATE, Optional.empty(),
            SummonKey.Restrictions.defaults(), Instant.now(), null));
    }

    @Test
    void draft_is_unsigned_until_withSignature() {
        var draft = SummonKey.draft("named:researcher", ISSUER, RECIPIENT,
            SummonKey.Scope.REVOCABLE, Optional.empty(),
            SummonKey.Restrictions.defaults());
        assertFalse(draft.isSigned());
        var signed = draft.withSignature("fake-sig");
        assertTrue(signed.isSigned());
    }

    // ── signature verification ─────────────────────────────────────────────

    @Test
    void valid_key_passes_validation() throws Exception {
        var draft = SummonKey.draft("named:researcher", ISSUER, RECIPIENT,
            SummonKey.Scope.PERMANENT, Optional.empty(),
            SummonKey.Restrictions.defaults());
        var key = signedKey(draft);
        var r = registry.validate(key, RECIPIENT, issuerKeyPair.getPublic(), Instant.now());
        assertTrue(r.valid(), r.reason());
    }

    @Test
    void forged_signature_fails_verification() throws Exception {
        var draft = SummonKey.draft("named:researcher", ISSUER, RECIPIENT,
            SummonKey.Scope.PERMANENT, Optional.empty(),
            SummonKey.Restrictions.defaults());
        var key = signedKey(draft);
        // Validate against wrong public key — should fail
        var r = registry.validate(key, RECIPIENT, otherKeyPair.getPublic(), Instant.now());
        assertFalse(r.valid());
        assertTrue(r.reason().contains("signature"));
    }

    @Test
    void tampered_fields_fail_verification() throws Exception {
        var draft = SummonKey.draft("named:researcher", ISSUER, RECIPIENT,
            SummonKey.Scope.PERMANENT, Optional.empty(),
            SummonKey.Restrictions.defaults());
        var signed = signedKey(draft);
        // Swap the target field after signing — canonical bytes change,
        // signature no longer matches
        var tampered = new SummonKey(signed.id(), "named:different-thing",
            signed.issuedBy(), signed.issuedTo(), signed.scope(),
            signed.expiresAt(), signed.restrictions(), signed.issuedAt(),
            signed.signatureBase64());
        var r = registry.validate(tampered, RECIPIENT, issuerKeyPair.getPublic(), Instant.now());
        assertFalse(r.valid());
    }

    // ── recipient enforcement (§20.4) ──────────────────────────────────────

    @Test
    void wrong_caller_rejected_even_with_valid_signature() throws Exception {
        var draft = SummonKey.draft("named:researcher", ISSUER, RECIPIENT,
            SummonKey.Scope.PERMANENT, Optional.empty(),
            SummonKey.Restrictions.defaults());
        var key = signedKey(draft);
        var r = registry.validate(key, INTRUDER, issuerKeyPair.getPublic(), Instant.now());
        assertFalse(r.valid());
        assertTrue(r.reason().contains("§20.4"));
    }

    // ── scope + expiry ─────────────────────────────────────────────────────

    @Test
    void expired_until_date_key_rejected() throws Exception {
        var expiry = Instant.parse("2020-01-01T00:00:00Z");
        var draft = SummonKey.draft("named:researcher", ISSUER, RECIPIENT,
            SummonKey.Scope.UNTIL_DATE, Optional.of(expiry),
            SummonKey.Restrictions.defaults());
        var key = signedKey(draft);
        var r = registry.validate(key, RECIPIENT, issuerKeyPair.getPublic(), Instant.now());
        assertFalse(r.valid());
        assertTrue(r.reason().contains("expired"));
    }

    @Test
    void future_until_date_key_valid() throws Exception {
        var future = Instant.now().plusSeconds(3600);
        var draft = SummonKey.draft("named:researcher", ISSUER, RECIPIENT,
            SummonKey.Scope.UNTIL_DATE, Optional.of(future),
            SummonKey.Restrictions.defaults());
        var key = signedKey(draft);
        var r = registry.validate(key, RECIPIENT, issuerKeyPair.getPublic(), Instant.now());
        assertTrue(r.valid(), r.reason());
    }

    // ── ONCE + maxSummons ──────────────────────────────────────────────────

    @Test
    void once_scope_invalid_after_first_use() throws Exception {
        var draft = SummonKey.draft("named:researcher", ISSUER, RECIPIENT,
            SummonKey.Scope.ONCE, Optional.empty(),
            SummonKey.Restrictions.defaults());
        var key = signedKey(draft);
        assertTrue(registry.validate(key, RECIPIENT, issuerKeyPair.getPublic(), Instant.now()).valid());
        registry.recordUse(key.id());
        var r = registry.validate(key, RECIPIENT, issuerKeyPair.getPublic(), Instant.now());
        assertFalse(r.valid());
        assertTrue(r.reason().contains("single-use"));
    }

    @Test
    void maxSummons_enforced() throws Exception {
        var restrictions = new SummonKey.Restrictions(Tanks.defaults(), Optional.of(2));
        var draft = SummonKey.draft("named:researcher", ISSUER, RECIPIENT,
            SummonKey.Scope.PERMANENT, Optional.empty(), restrictions);
        var key = signedKey(draft);
        registry.recordUse(key.id());
        registry.recordUse(key.id());
        var r = registry.validate(key, RECIPIENT, issuerKeyPair.getPublic(), Instant.now());
        assertFalse(r.valid());
        assertTrue(r.reason().contains("maxSummons"));
    }

    // ── revocation (§20.3) ─────────────────────────────────────────────────

    @Test
    void issuer_revocation_invalidates_key() throws Exception {
        var draft = SummonKey.draft("named:researcher", ISSUER, RECIPIENT,
            SummonKey.Scope.REVOCABLE, Optional.empty(),
            SummonKey.Restrictions.defaults());
        var key = signedKey(draft);
        assertTrue(registry.revoke(key.id(), ISSUER, key));
        var r = registry.validate(key, RECIPIENT, issuerKeyPair.getPublic(), Instant.now());
        assertFalse(r.valid());
        assertTrue(r.reason().contains("revoked"));
    }

    @Test
    void non_issuer_cannot_revoke() throws Exception {
        var draft = SummonKey.draft("named:researcher", ISSUER, RECIPIENT,
            SummonKey.Scope.REVOCABLE, Optional.empty(),
            SummonKey.Restrictions.defaults());
        var key = signedKey(draft);
        assertFalse(registry.revoke(key.id(), INTRUDER, key));
        assertFalse(registry.isRevoked(key.id()));
    }

    // ── unsigned keys + other malformed inputs ─────────────────────────────

    @Test
    void unsigned_key_rejected() {
        var draft = SummonKey.draft("named:r", ISSUER, RECIPIENT,
            SummonKey.Scope.PERMANENT, Optional.empty(),
            SummonKey.Restrictions.defaults());
        var r = registry.validate(draft, RECIPIENT, issuerKeyPair.getPublic(), Instant.now());
        assertFalse(r.valid());
        assertTrue(r.reason().contains("unsigned"));
    }

    @Test
    void missing_issuer_key_rejected() throws Exception {
        var draft = SummonKey.draft("named:r", ISSUER, RECIPIENT,
            SummonKey.Scope.PERMANENT, Optional.empty(),
            SummonKey.Restrictions.defaults());
        var key = signedKey(draft);
        var r = registry.validate(key, RECIPIENT, null, Instant.now());
        assertFalse(r.valid());
    }
}
