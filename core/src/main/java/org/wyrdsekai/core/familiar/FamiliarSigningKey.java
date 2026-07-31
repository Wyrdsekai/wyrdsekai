package org.wyrdsekai.core.familiar;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Per-agent Ed25519 keypair used to sign {@link SummonKey}s.
 *
 * <p>an internal design note requires signed keys. The soul-level identity
 * (AgentIdentity) has its own keypair, but routing its private key through
 * CompanionActor would require passing the household secret — a plumbing
 * concern that's been deferred. This class provides a narrower-purpose
 * signing identity owned by the familiar system: one keypair per agent,
 * persisted alongside thought forms and imprints, used only to sign
 * summon keys.</p>
 *
 * <p>When the soul-level keystore integration lands, this class is the
 * migration point: replace the local keypair with a delegating signer
 * that calls {@code AgentIdentity.sign}, preserve the public key on
 * disk so already-issued keys still verify.</p>
 */
public final class FamiliarSigningKey {

    private static final Logger log = LoggerFactory.getLogger(FamiliarSigningKey.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final KeyPair keyPair;

    private FamiliarSigningKey(KeyPair keyPair) {
        this.keyPair = keyPair;
    }

    /** Generate a fresh signing key. */
    public static FamiliarSigningKey generate() {
        try {
            var gen = KeyPairGenerator.getInstance("Ed25519");
            return new FamiliarSigningKey(gen.generateKeyPair());
        } catch (Exception e) {
            throw new IllegalStateException("Ed25519 unavailable", e);
        }
    }

    /** Sign an arbitrary payload and return a Base64 signature. */
    public String sign(byte[] data) {
        try {
            var sig = Signature.getInstance("Ed25519");
            sig.initSign(keyPair.getPrivate());
            sig.update(data);
            return Base64.getEncoder().encodeToString(sig.sign());
        } catch (Exception e) {
            throw new IllegalStateException("signing failed", e);
        }
    }

    public PublicKey publicKey() { return keyPair.getPublic(); }

    public String publicKeyBase64() {
        return Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
    }

    public String privateKeyBase64() {
        return Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
    }

    // ── Disk persistence ────────────────────────────────────────────────────

    private record KeyFile(String publicKey, String privateKey) {}

    /**
     * Load a signing key from disk, or generate and save a fresh one if none
     * exists at the given path. The path should be a directory-local file
     * like {@code ~/.wyrdsekai/agents/<did-slug>/signing-key.json}.
     */
    public static FamiliarSigningKey loadOrGenerate(Path path) {
        if (path == null) throw new IllegalArgumentException("path required");
        try {
            if (Files.exists(path)) {
                var kf = MAPPER.readValue(path.toFile(), KeyFile.class);
                var pub = restorePublicKey(Base64.getDecoder().decode(kf.publicKey()));
                var prv = restorePrivateKey(Base64.getDecoder().decode(kf.privateKey()));
                return new FamiliarSigningKey(new KeyPair(pub, prv));
            }
        } catch (Exception e) {
            log.warn("FamiliarSigningKey: failed to load existing key at {} — generating fresh",
                path, e);
        }
        var fresh = generate();
        try {
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            var kf = new KeyFile(fresh.publicKeyBase64(), fresh.privateKeyBase64());
            Files.write(path, MAPPER.writerWithDefaultPrettyPrinter()
                .writeValueAsString(kf).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.warn("FamiliarSigningKey: failed to persist signing key to {} — in-memory only",
                path, e);
        }
        return fresh;
    }

    private static PublicKey restorePublicKey(byte[] encoded) throws Exception {
        return KeyFactory.getInstance("Ed25519")
            .generatePublic(new X509EncodedKeySpec(encoded));
    }

    private static PrivateKey restorePrivateKey(byte[] encoded) throws Exception {
        return KeyFactory.getInstance("Ed25519")
            .generatePrivate(new PKCS8EncodedKeySpec(encoded));
    }
}
