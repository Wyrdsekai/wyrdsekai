package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.identity.AgentIdentity;
import org.wyrdsekai.core.identity.DidKey;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the exact signing/verification encoding contract that
 * {@code SoulAutoForge} (sign at forge) and {@code CompanionActor}'s load-time
 * soul-signature gate rely on:
 *
 * <ol>
 *   <li>Forge signs with {@code AgentIdentity.sign} → Base64 string, then stores
 *       {@code Base64.decode(sig)} bytes into {@code SoulManifest.signature()}.</li>
 *   <li>{@code SoulVerifier.verifySignature} re-Base64-encodes those bytes and
 *       calls {@code identity.verify} — so the round trip must be lossless.</li>
 *   <li>The load gate reconstructs a verify-only identity from the manifest's
 *       DID (public key only), exactly as {@code SoulVerifier.verifyInbound} does,
 *       and that must validate a genuine signature and reject tampered content.</li>
 * </ol>
 */
class SoulSignatureRoundTripTest {

    private static byte[] secret() throws Exception {
        var s = new byte[32];
        SecureRandom.getInstanceStrong().nextBytes(s);
        return s;
    }

    @Test
    void signDecodeReencodeVerify_roundTrips() throws Exception {
        var secret = secret();
        var identity = AgentIdentity.generate(secret);
        byte[] canonical = "did:key:zAbc|voluntary_suspend,refuse_rights,|1730000000"
            .getBytes();

        // Forge side: sign returns Base64; manifest stores the decoded bytes.
        String sigB64 = identity.sign(canonical, secret);
        byte[] storedInManifest = Base64.getDecoder().decode(sigB64);

        // Verify side (SoulVerifier.verifySignature): re-encode + identity.verify.
        String reEncoded = Base64.getEncoder().encodeToString(storedInManifest);
        assertTrue(identity.verify(canonical, reEncoded),
            "sign → decode → store → re-encode → verify must round-trip losslessly");
    }

    @Test
    void didReconstructedIdentity_validatesGenuineAndRejectsTamper() throws Exception {
        var secret = secret();
        var identity = AgentIdentity.generate(secret);
        byte[] canonical = "canonical-manifest-bytes-v1".getBytes();

        String sigB64 = identity.sign(canonical, secret);
        byte[] stored = Base64.getDecoder().decode(sigB64);
        String reEncoded = Base64.getEncoder().encodeToString(stored);

        // Reconstruct a verify-only identity from the DID alone (public key),
        // mirroring the load gate / SoulVerifier.verifyInbound.
        var multibase = identity.did().substring("did:key:".length());
        var pub = DidKey.rawPublicKeyFromMultibase(multibase);
        var verifyOnly = new AgentIdentity(
            identity.did(), pub, null, List.of(), Instant.now(), null, null);

        assertTrue(verifyOnly.verify(canonical, reEncoded),
            "DID-reconstructed (public-key-only) identity must validate a genuine signature");
        assertFalse(verifyOnly.verify("tampered-manifest-bytes".getBytes(), reEncoded),
            "tampered content must fail verification against the same signature");
    }
}
