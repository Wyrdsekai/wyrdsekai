package org.wyrdsekai.core.crypto;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.NamedParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-zone shared secret with envelope encryption ( foundation — generalized at
 * Operator's direction: "we should be doing this for ALL secrets within a zone that is shared").
 *
 * <p>One 32-byte master secret per zone is the root of every zone-shared secret. It is generated
 * once at zone creation and lives in the clear only in memory; at rest and in transit it is
 * <b>wrapped per node</b> (AES Key Wrap under a per-node KEK), so the plaintext master is never
 * replicated unwrapped. The same envelope pattern as a prior e2e design (mDEK wrapped per device-KEK).
 *
 * <p>Consumers NEVER use the master directly — they {@link #derive} a purpose-specific key via
 * HKDF-SHA256, e.g. the argot token key = {@code derive(zone, "argot-v1")}, the join-token HMAC
 * key = {@code derive(zone, "join-token-v1")}. This keeps the consumers cryptographically
 * separated while sharing one root, and lets a single secret rotate them all.
 *
 * <p>Comprehension across a zone: every node that installs the same master derives the identical
 * purpose keys, so same-zone agents (on any node) compute the same argot codebook. An outsider
 * lacking the master cannot derive any of it — which is what makes argot opacity + forge-resistance
 * real against a source-having adversary (the public-seed scheme they replace could not).
 *
 * <p>This class is the crypto core; the persistence (a {@code zone_wrapped_secrets} table), the
 * node-KEK source ({@link #nodeKek} from the node identity seed), the zone-creation hook, and the
 * cross-node re-wrap on join are the integration layer that builds on it.
 */
public class ZoneSecretService {

    private static final int MASTER_LEN = 32;
    private final SecureRandom rng = new SecureRandom();
    /** Unwrapped master secrets, in memory only, keyed by zoneId. */
    private final Map<String, byte[]> masters = new ConcurrentHashMap<>();

    /** Generate and hold a fresh master secret for a new zone (called at zone creation). */
    public byte[] generate(String zoneId) {
        var master = new byte[MASTER_LEN];
        rng.nextBytes(master);
        masters.put(zoneId, master);
        return master.clone();
    }

    /** Install an already-known master (e.g. unwrapped from disk, or received on join). */
    public void install(String zoneId, byte[] master) {
        if (master == null || master.length != MASTER_LEN) {
            throw new IllegalArgumentException("zone master must be " + MASTER_LEN + " bytes");
        }
        masters.put(zoneId, master.clone());
    }

    /** Whether this node holds the (unwrapped) master for a zone. */
    public boolean has(String zoneId) { return masters.containsKey(zoneId); }

    /** Forget a zone's master (e.g. this node left the zone). */
    public void forget(String zoneId) {
        var m = masters.remove(zoneId);
        if (m != null) Arrays.fill(m, (byte) 0);
    }

    // ── Envelope (wrap/unwrap the master with a per-node KEK) ────────────────────────────────

    /** Wrap this zone's master under a node KEK for storage/transport. Never emits plaintext. */
    public byte[] wrapForNode(String zoneId, byte[] nodeKek) {
        var master = masters.get(zoneId);
        if (master == null) throw new IllegalStateException("no master held for zone " + zoneId);
        return aesWrap(master, nodeKek);
    }

    /** Unwrap a per-node-wrapped master with this node's KEK and hold it. */
    public void installFromWrapped(String zoneId, byte[] wrapped, byte[] nodeKek) {
        masters.put(zoneId, aesUnwrap(wrapped, nodeKek));
    }

    // ── Derivation (purpose keys; consumers use these, never the master) ─────────────────────

    /** Derive a purpose-specific key for a zone via HKDF-SHA256(master, salt=zoneId, info=purpose). */
    public byte[] derive(String zoneId, String purpose, int len) {
        var master = masters.get(zoneId);
        if (master == null) throw new IllegalStateException("no master held for zone " + zoneId);
        return hkdf(master, zoneId.getBytes(StandardCharsets.UTF_8),
            purpose.getBytes(StandardCharsets.UTF_8), len);
    }

    /** Convenience: a 32-byte purpose key. */
    public byte[] derive(String zoneId, String purpose) { return derive(zoneId, purpose, 32); }

    /** Derive a per-node KEK from the node identity seed (HKDF) — the wrapping key for this node. */
    public static byte[] nodeKek(byte[] nodeIdentitySeed) {
        return hkdf(nodeIdentitySeed, "wyrd-zone-secret-kek".getBytes(StandardCharsets.UTF_8),
            "node-kek-v1".getBytes(StandardCharsets.UTF_8), 32);
    }

    // ── Cross-node grant (join): X25519 ECIES, end-to-end vs the relay ───────────────────────
    // The federation channel is signed but not E2E-encrypted, so a node JOINING a zone receives
    // the master wrapped to its OWN X25519 public key — only it can unwrap, even if the relay
    // terminates transport TLS. Ephemeral-static ECIES: granter makes an ephemeral keypair, ECDH
    // with the joiner's static pubkey, HKDF → AES-wrap the master. Authenticity of the grant rides
    // on the already-signed federation envelope that carries it. Per-node X25519 keys are separate
    // from the Ed25519 SIGNING identity (no key reuse across signing + key-agreement).

    private static final String GRANT_INFO = "wyrd-zone-grant-v1";

    /** Generate a per-node X25519 key-agreement keypair (stored alongside the node's signing key). */
    public static KeyPair generateNodeEcdhKeyPair() {
        try {
            var kpg = KeyPairGenerator.getInstance("XDH");
            kpg.initialize(new NamedParameterSpec("X25519"));
            return kpg.generateKeyPair();
        } catch (Exception e) {
            throw new RuntimeException("X25519 keypair generation failed", e);
        }
    }

    /**
     * Grant a zone master to a joining node by ECIES to its X25519 public key (X.509 SPKI bytes).
     * Returns {@code len(ephPub) || ephPub || wrapped} — safe to send over the signed (but not
     * necessarily E2E-encrypted) federation channel; only the holder of the matching private key
     * can {@link #acceptGrant}.
     */
    public byte[] grantTo(String zoneId, byte[] recipientPubX509) {
        var master = masters.get(zoneId);
        if (master == null) throw new IllegalStateException("no master held for zone " + zoneId);
        try {
            var eph = generateNodeEcdhKeyPair();
            var recipPub = KeyFactory.getInstance("XDH")
                .generatePublic(new X509EncodedKeySpec(recipientPubX509));
            var wrapKey = hkdf(ecdh(eph.getPrivate(), recipPub), null,
                grantInfo(zoneId), 32);
            var wrapped = aesWrap(master, wrapKey);
            var ephPub = eph.getPublic().getEncoded();   // X.509 SPKI
            var out = ByteBuffer.allocate(2 + ephPub.length + wrapped.length);
            out.putShort((short) ephPub.length).put(ephPub).put(wrapped);
            return out.array();
        } catch (Exception e) {
            throw new RuntimeException("zone-secret grant failed", e);
        }
    }

    /** Accept a {@link #grantTo} envelope with this node's X25519 private key and hold the master. */
    public void acceptGrant(String zoneId, byte[] grant, PrivateKey myEcdhPriv) {
        try {
            var in = ByteBuffer.wrap(grant);
            int ephLen = in.getShort() & 0xFFFF;
            var ephPubBytes = new byte[ephLen];
            in.get(ephPubBytes);
            var wrapped = new byte[in.remaining()];
            in.get(wrapped);
            var ephPub = KeyFactory.getInstance("XDH")
                .generatePublic(new X509EncodedKeySpec(ephPubBytes));
            var wrapKey = hkdf(ecdh(myEcdhPriv, ephPub), null, grantInfo(zoneId), 32);
            masters.put(zoneId, aesUnwrap(wrapped, wrapKey));
        } catch (Exception e) {
            throw new RuntimeException("zone-secret grant accept failed (wrong key?)", e);
        }
    }

    /** HKDF info binds the wrap key to the grant context AND the zone id, so a blob minted for one
     *  zone cannot be replayed as another (GCM auth fails on a zone-id mismatch). */
    private static byte[] grantInfo(String zoneId) {
        return (GRANT_INFO + ":" + (zoneId == null ? "" : zoneId))
            .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] ecdh(PrivateKey priv, PublicKey pub) throws Exception {
        var ka = KeyAgreement.getInstance("XDH");
        ka.init(priv);
        ka.doPhase(pub, true);
        return ka.generateSecret();
    }

    // ── Primitives (Java stdlib only, mirrors an earlier DekManager) ─────────────────────────────

    private static byte[] aesWrap(byte[] key, byte[] kek) {
        try {
            var c = Cipher.getInstance("AESWrap");
            c.init(Cipher.WRAP_MODE, new SecretKeySpec(kek, "AES"));
            return c.wrap(new SecretKeySpec(key, "AES"));
        } catch (Exception e) {
            throw new RuntimeException("AES key wrap failed", e);
        }
    }

    private static byte[] aesUnwrap(byte[] wrapped, byte[] kek) {
        try {
            var c = Cipher.getInstance("AESWrap");
            c.init(Cipher.UNWRAP_MODE, new SecretKeySpec(kek, "AES"));
            return ((SecretKey) c.unwrap(wrapped, "AES", Cipher.SECRET_KEY)).getEncoded();
        } catch (Exception e) {
            throw new RuntimeException("AES key unwrap failed (wrong node KEK?)", e);
        }
    }

    /** HKDF-SHA256 (RFC 5869) — extract-then-expand. */
    static byte[] hkdf(byte[] ikm, byte[] salt, byte[] info, int len) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            if (salt == null || salt.length == 0) salt = new byte[32];
            mac.init(new SecretKeySpec(salt, "HmacSHA256"));
            var prk = mac.doFinal(ikm);                          // extract
            mac.init(new SecretKeySpec(prk, "HmacSHA256"));
            var okm = new byte[len];
            var t = new byte[0];
            int pos = 0;
            byte counter = 1;
            while (pos < len) {                                  // expand
                mac.reset();
                mac.update(t);
                mac.update(info);
                mac.update(counter);
                t = mac.doFinal();
                int n = Math.min(t.length, len - pos);
                System.arraycopy(t, 0, okm, pos, n);
                pos += n;
                counter++;
            }
            return okm;
        } catch (Exception e) {
            throw new RuntimeException("HKDF failed", e);
        }
    }
}
