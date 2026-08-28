package org.wyrdsekai.core.recipe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.identity.AgentIdentity;
import org.wyrdsekai.core.identity.AgentIdentityProvisioner;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Signs sleep-forge weight artifacts (spine adapters, memory organs) with the
 * owning companion's Ed25519 identity, producing a {@code .provenance.json}
 * sidecar beside the artifact.
 *
 * <p>Why in-runtime and not a recipe step: recipe SHELL steps run as
 * subprocesses with no access to the household secret — and must never gain
 * it. The zone process holds the secret (via {@link AgentIdentityProvisioner}),
 * so signing happens here, after a successful run, on the {@code artifact_path}
 * the recipe left in its context. A companion signing her own soul-writes is
 * the point: the artifact that claims to be a week of her life carries her key,
 * and a foreign or tampered organ fails verification before it ever speaks as
 * her (the serving shim additionally refuses on sha256 mismatch).</p>
 *
 * <p>Payload is a fixed canonical string (version, sha256, DID, timestamp,
 * newline-joined) so verification never depends on JSON key order. Verify with
 * {@link AgentIdentityProvisioner#verify(String, byte[], String)} against the
 * sidecar's {@code payload} bytes.</p>
 *
 * <p>Best-effort by design: installs without identity provisioning (or a
 * companion whose key was never persisted) get the artifact without a sidecar,
 * logged at INFO. A signing failure never fails the recipe run — the gates
 * already passed; provenance is additive.</p>
 */
public final class SoulArtifactSigner {

    private static final Logger log = LoggerFactory.getLogger(SoulArtifactSigner.class);

    static final String PAYLOAD_VERSION = "wyrdsekai-soul-artifact-v1";

    private SoulArtifactSigner() {}

    /**
     * Sign {@code artifact} as {@code agentDid} using the runtime's provisioned
     * identity and household secret. Returns the sidecar path, or empty when
     * identity material is unavailable or signing failed (logged, never thrown).
     */
    public static Optional<Path> sign(String agentDid, Path artifact) {
        if (agentDid == null || artifact == null || !Files.isRegularFile(artifact)) {
            return Optional.empty();
        }
        var identity = AgentIdentityProvisioner.find(agentDid);
        var secret = AgentIdentityProvisioner.secret();
        if (identity.isEmpty() || secret.isEmpty()) {
            log.info("soul-artifact not signed ({}): identity or household secret unavailable", artifact);
            return Optional.empty();
        }
        try {
            return Optional.of(sign(identity.get(), secret.get(), artifact));
        } catch (Exception e) {
            log.warn("soul-artifact signing failed for {}: {}", artifact, e.toString());
            return Optional.empty();
        }
    }

    /**
     * Core signing path, testable without the provisioner's static state.
     * Writes {@code <artifact>.provenance.json} and returns its path.
     */
    public static Path sign(AgentIdentity identity, byte[] householdSecret, Path artifact)
            throws Exception {
        String sha256 = sha256Hex(artifact);
        String signedAt = Instant.now().toString();
        String payload = String.join("\n",
                PAYLOAD_VERSION, sha256, identity.did(), signedAt);
        String signature = identity.sign(payload.getBytes(StandardCharsets.UTF_8), householdSecret);

        String json = """
                {
                  "version": "%s",
                  "artifact": "%s",
                  "sha256": "%s",
                  "agentDid": "%s",
                  "signedAt": "%s",
                  "payload": %s,
                  "signature": "%s"
                }
                """.formatted(PAYLOAD_VERSION, artifact.getFileName(), sha256,
                identity.did(), signedAt, quote(payload), signature);
        Path sidecar = artifact.resolveSibling(artifact.getFileName() + ".provenance.json");
        Files.writeString(sidecar, json);
        log.info("soul-artifact signed: {} by {}", sidecar.getFileName(), identity.did());
        return sidecar;
    }

    /** Verify a sidecar's signature against the signer DID it names. */
    public static boolean verify(String payload, String signatureBase64, String agentDid) {
        return AgentIdentityProvisioner.verify(
                agentDid, payload.getBytes(StandardCharsets.UTF_8), signatureBase64);
    }

    private static String sha256Hex(Path file) throws Exception {
        var md = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(file)) {
            byte[] buf = new byte[1 << 20];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }

    private static String quote(String s) {
        return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + '"';
    }
}
