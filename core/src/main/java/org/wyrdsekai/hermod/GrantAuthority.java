package org.wyrdsekai.hermod;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.function.Predicate;

/**
 * Ed25519 mint/verify for consent grants. Pure java.security — no
 * dependencies, extraction-safe. Signing bytes are a canonical,
 * length-prefixed encoding of every field except the signature, so a
 * grant cannot be re-scoped after minting. Deny-by-default: a door with
 * no authority key refuses every granted-domain task.
 */
public final class GrantAuthority {

    private static final byte[] PKCS8_ED25519_PREFIX = {
        0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06,
        0x03, 0x2b, 0x65, 0x70, 0x04, 0x22, 0x04, 0x20 };

    private GrantAuthority() {}

    public static byte[] signingBytes(String grantId, String householdId, String dataDomain,
                                      String grantedToDeviceClass, Instant issuedAt,
                                      Instant expiresAt, String policyVersion) {
        var buf = ByteBuffer.allocate(4096);
        for (var s : new String[]{grantId, householdId, dataDomain, grantedToDeviceClass,
                                  policyVersion}) {
            var b = s.getBytes(StandardCharsets.UTF_8);
            buf.putInt(b.length).put(b);
        }
        buf.putLong(issuedAt.toEpochMilli()).putLong(expiresAt.toEpochMilli());
        var out = new byte[buf.position()];
        buf.rewind();
        buf.get(out);
        return out;
    }

    /** Mint a grant with the household authority's Ed25519 private key. */
    public static SignedGrant mint(String grantId, String householdId, String dataDomain,
                                   String grantedToDeviceClass, Instant issuedAt,
                                   Instant expiresAt, String policyVersion,
                                   PrivateKey authorityKey) {
        try {
            var sig = Signature.getInstance("Ed25519");
            sig.initSign(authorityKey);
            sig.update(signingBytes(grantId, householdId, dataDomain, grantedToDeviceClass,
                issuedAt, expiresAt, policyVersion));
            return new SignedGrant(grantId, householdId, dataDomain, grantedToDeviceClass,
                issuedAt, expiresAt, policyVersion, sig.sign());
        } catch (Exception e) {
            throw new IllegalStateException("grant mint failed", e);
        }
    }

    /** Verifier for a door, from the authority's SPKI public key bytes. */
    public static Predicate<SignedGrant> verifier(byte[] authoritySpki) {
        if (authoritySpki == null || authoritySpki.length == 0) {
            return g -> false; // no authority key = no trust = deny
        }
        try {
            var pub = publicKeyFromSpki(authoritySpki);
            return g -> {
                try {
                    var sig = Signature.getInstance("Ed25519");
                    sig.initVerify(pub);
                    sig.update(signingBytes(g.grantId(), g.householdId(), g.dataDomain(),
                        g.grantedToDeviceClass(), g.issuedAt(), g.expiresAt(),
                        g.policyVersion()));
                    return sig.verify(g.authoritySignature());
                } catch (Exception e) {
                    return false;
                }
            };
        } catch (Exception e) {
            return g -> false;
        }
    }

    public static PublicKey publicKeyFromSpki(byte[] spki) throws Exception {
        return KeyFactory.getInstance("Ed25519")
            .generatePublic(new X509EncodedKeySpec(spki));
    }

    /** Ed25519 private key from a raw 32-byte seed (node-identity form). */
    public static PrivateKey privateKeyFromSeed(byte[] seed) throws Exception {
        var pkcs8 = ByteBuffer.allocate(PKCS8_ED25519_PREFIX.length + seed.length)
            .put(PKCS8_ED25519_PREFIX).put(seed).array();
        return KeyFactory.getInstance("Ed25519")
            .generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
    }
}
