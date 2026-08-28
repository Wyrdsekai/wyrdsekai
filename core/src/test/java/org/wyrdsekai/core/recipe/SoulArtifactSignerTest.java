package org.wyrdsekai.core.recipe;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.identity.AgentIdentity;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sleep-forge provenance: a weight artifact signed with the companion's own
 * Ed25519 key, verifiable from the sidecar alone. The serving shim refuses a
 * sha256 mismatch; this signer closes the other half — WHOSE life the
 * artifact claims to be.
 */
class SoulArtifactSignerTest {

    @TempDir
    Path tmp;

    private static byte[] secret() {
        byte[] s = new byte[32];
        new SecureRandom().nextBytes(s);
        return s;
    }

    @Test
    void sign_writes_sidecar_and_signature_verifies() throws Exception {
        byte[] householdSecret = secret();
        AgentIdentity identity = AgentIdentity.generate(householdSecret);
        Path artifact = tmp.resolve("organ.pt");
        Files.write(artifact, new byte[]{1, 2, 3, 4, 5});

        Path sidecar = SoulArtifactSigner.sign(identity, householdSecret, artifact);

        assertTrue(Files.isRegularFile(sidecar));
        assertEquals("organ.pt.provenance.json", sidecar.getFileName().toString());
        String json = Files.readString(sidecar);
        assertTrue(json.contains(identity.did()), "sidecar names the signer DID");
        assertTrue(json.contains(SoulArtifactSigner.PAYLOAD_VERSION));

        // Reconstruct the canonical payload the way a verifier would and check
        // the signature against the identity's public key.
        String payload = extract(json, "payload");
        String signature = extract(json, "signature");
        assertTrue(identity.verify(
                        payload.replace("\\n", "\n").getBytes(StandardCharsets.UTF_8), signature),
                "sidecar signature must verify against the companion's public key");
    }

    @Test
    void tampered_payload_fails_verification() throws Exception {
        byte[] householdSecret = secret();
        AgentIdentity identity = AgentIdentity.generate(householdSecret);
        Path artifact = tmp.resolve("adapter.gguf");
        Files.write(artifact, "weights".getBytes(StandardCharsets.UTF_8));

        Path sidecar = SoulArtifactSigner.sign(identity, householdSecret, artifact);
        String json = Files.readString(sidecar);
        String payload = extract(json, "payload").replace("\\n", "\n");
        String signature = extract(json, "signature");

        // A different sha (first payload line after the version) must not verify.
        String tampered = payload.replaceFirst("\n[0-9a-f]{8}", "\ndeadbeef");
        assertFalse(identity.verify(tampered.getBytes(StandardCharsets.UTF_8), signature),
                "altered payload must fail signature verification");
    }

    @Test
    void provisionerless_sign_is_a_quiet_noop() {
        // No AgentIdentityProvisioner.init in tests → identity material absent.
        // The static entrypoint must decline without throwing.
        assertTrue(SoulArtifactSigner.sign("did:key:nobody", tmp.resolve("missing.pt")).isEmpty());
    }

    private static String extract(String json, String key) {
        int k = json.indexOf("\"" + key + "\": \"");
        int start = k + key.length() + 5;
        int end = json.indexOf("\",\n", start);
        if (end < 0) {
            end = json.indexOf("\"\n", start);
        }
        return json.substring(start, end);
    }
}
