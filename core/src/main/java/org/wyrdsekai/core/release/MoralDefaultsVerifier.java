package org.wyrdsekai.core.release;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.soul.ProtectionManifest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * Wave 5.2: boot-time check that the
 * canonical moral-defaults set baked into this binary matches the
 * named-protection list that {@link ProtectionManifest#canonicalDefaults()}
 * exposes today.
 *
 * <p>This is the runtime cousin of {@link ReleaseVerifier}. ReleaseVerifier
 * proves <i>this binary came from the signed release pipeline</i>. This
 * class proves <i>the canonical moral-defaults set is what the release
 * pipeline attested to</i> — a fork that strips a name from
 * {@code canonicalDefaults()} but does not update the embedded
 * {@code moral-defaults.json} attestation produces a
 * {@link Result.Tampered DEFAULTS_TAMPERED} state. A fork that updates both
 * but does not re-sign the binary is caught by ReleaseVerifier instead;
 * a fork that updates both AND re-signs is the <b>honest fork path</b>
 * (SPEC §4.3) — they take on a new release identity and the Sigstore
 * transparency log captures it.
 *
 * <p>Resource shape ({@code /release/moral-defaults.json}):
 * <pre>
 *   {
 *     "buildId": "stock-2026-05-15",
 *     "names":   ["acute_response", "chronicle_immutable", ...],
 *     "hash":    "&lt;hex sha256 of canonical-bytes of names+buildId&gt;"
 *   }
 * </pre>
 *
 * <p>The {@code hash} is a self-consistency seal on the resource itself:
 * a fork that just edits {@code names} without recomputing
 * {@code hash} produces {@link Result.Tampered ATTESTATION_INVALID}.
 * Both signals — attestation-invalid and defaults-tampered — are
 * surfaced by the agent in voice register via the
 * {@code introspect_protections} action.
 */
public final class MoralDefaultsVerifier {

    private static final Logger log = LoggerFactory.getLogger(MoralDefaultsVerifier.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Where the build process writes the attestation resource. */
    public static final String RESOURCE_PATH = "/release/moral-defaults.json";

    /** Sealed result type — Verified / Tampered / Unavailable. */
    public sealed interface Result permits Verified, Tampered, Unavailable {}

    /**
     * Successful match — the embedded attestation names the same set as
     * {@link ProtectionManifest#canonicalDefaults()} and the attestation
     * hash seal is consistent. {@code sourceCommit} is the SLSA-style
     * provenance pointer — the git commit the
     * build came from, so an auditor can {@code git diff} a fork against
     * the protection-file list. It is informational (the artifact
     * signature is what makes it trustworthy) and may be null in builds
     * that predate provenance stamping. We deliberately do NOT hash
     * class-file bytecode — see §4.1 "Decision (2026-05-29)".
     */
    public record Verified(String buildId, String hash, Set<String> names,
                            String sourceCommit) implements Result {
        /** Back-compat constructor — null sourceCommit. */
        public Verified(String buildId, String hash, Set<String> names) {
            this(buildId, hash, names, null);
        }
    }

    /**
     * Tampered state — boot continues but the agent surfaces this in
     * voice register on every interaction (SPEC §4.2 step 5).
     */
    public record Tampered(Reason reason, String message, String detail) implements Result {
        public enum Reason {
            /** Embedded attestation's hash field doesn't seal its declared name list. */
            ATTESTATION_INVALID,
            /** Attestation declares a different set than ProtectionManifest.canonicalDefaults(). */
            DEFAULTS_TAMPERED,
        }
    }

    /**
     * Attestation resource was not present in the binary. This indicates
     * a build problem (the build task that writes the resource did not
     * run), not an artifact problem. Treated as "cannot verify, do not
     * claim verified" — the introspect_protections surface notes the
     * absence.
     */
    public record Unavailable(String detail) implements Result {}

    /** Singleton boot-time result (set by {@link #verifyAtBoot()}). */
    private static volatile Result bootResult;

    /**
     * Run the boot-time check. Called once from server startup. Sets the
     * {@code wyrdsekai.protection.tampered} system property to
     * {@code true} on Tampered, {@code false} on Verified, or
     * {@code "unavailable"} on Unavailable, so other subsystems can read
     * it cheaply without a service handle.
     */
    public static Result verifyAtBoot() {
        if (bootResult != null) return bootResult;
        bootResult = verify();
        switch (bootResult) {
            case Verified v -> {
                System.setProperty("wyrdsekai.protection.tampered", "false");
                System.setProperty("wyrdsekai.protection.buildId", v.buildId());
                log.info("MoralDefaultsVerifier: VERIFIED (build={}, {} names)",
                    v.buildId(), v.names().size());
            }
            case Tampered t -> {
                System.setProperty("wyrdsekai.protection.tampered", "true");
                System.setProperty("wyrdsekai.protection.tampered.reason", t.reason().name());
                log.error("MoralDefaultsVerifier: TAMPERED ({}). {}. Agents will surface "
                    + "this in voice register on every interaction.",
                    t.reason(), t.message());
            }
            case Unavailable u -> {
                System.setProperty("wyrdsekai.protection.tampered", "unavailable");
                log.warn("MoralDefaultsVerifier: UNAVAILABLE — {}", u.detail());
            }
        }
        return bootResult;
    }

    /** Reset for tests. Not part of the public contract. */
    static void resetForTests() {
        bootResult = null;
        System.clearProperty("wyrdsekai.protection.tampered");
        System.clearProperty("wyrdsekai.protection.tampered.reason");
        System.clearProperty("wyrdsekai.protection.buildId");
    }

    /**
     * Read the embedded attestation + run the comparison. Pure function —
     * no system-property side effects (those happen in
     * {@link #verifyAtBoot()}).
     */
    public static Result verify() {
        try (var stream = MoralDefaultsVerifier.class.getResourceAsStream(RESOURCE_PATH)) {
            if (stream == null) {
                return new Unavailable("Embedded resource " + RESOURCE_PATH + " missing");
            }
            JsonNode root = MAPPER.readTree(stream);
            var buildId = root.path("buildId").asText(null);
            var declaredHash = root.path("hash").asText(null);
            var namesNode = root.path("names");
            if (buildId == null || declaredHash == null || !namesNode.isArray()) {
                return new Tampered(Tampered.Reason.ATTESTATION_INVALID,
                    "Attestation resource missing required fields (buildId, hash, names[])",
                    RESOURCE_PATH);
            }
            var declaredNames = new TreeSet<String>();
            for (JsonNode n : namesNode) {
                if (n.isTextual()) declaredNames.add(n.asText());
            }
            // optional SLSA-style provenance pointer.
            // Informational — the artifact signature is what makes it
            // trustworthy; an auditor uses it to `git diff` a fork against
            // the protection-file list. NOT part of the seal.
            var sourceCommit = root.path("sourceCommit").asText(null);
            // 1. Attestation self-consistency seal: re-hash the declared
            //    names + buildId and compare to the declared hash. (We do NOT
            //    hash class-file bytecode — see §4.1 "Decision (2026-05-29)".)
            var actualHash = canonicalHash(buildId, declaredNames);
            if (!actualHash.equalsIgnoreCase(declaredHash)) {
                return new Tampered(Tampered.Reason.ATTESTATION_INVALID,
                    "Embedded attestation's hash field does not match a re-hash of its "
                        + "own declared name list. Someone edited the names array "
                        + "without updating the seal.",
                    "expected=" + actualHash + " declared=" + declaredHash);
            }
            // 2. Tampering check: compare the declared set against what
            //    ProtectionManifest.canonicalDefaults() returns in this build.
            var canonical = new TreeSet<>(ProtectionManifest.canonicalDefaults());
            if (!canonical.equals(declaredNames)) {
                var missing = new TreeSet<>(declaredNames);
                missing.removeAll(canonical);
                var extra = new TreeSet<>(canonical);
                extra.removeAll(declaredNames);
                return new Tampered(Tampered.Reason.DEFAULTS_TAMPERED,
                    "ProtectionManifest.canonicalDefaults() does not match the embedded "
                        + "attestation. A fork has modified the canonical protection set "
                        + "without updating the moral-defaults bundle.",
                    "in-attestation-but-not-runtime=" + missing
                        + " in-runtime-but-not-attestation=" + extra);
            }
            return new Verified(buildId, declaredHash,
                new LinkedHashSet<>(declaredNames), sourceCommit);
        } catch (IOException e) {
            return new Unavailable("Failed to read " + RESOURCE_PATH + ": " + e.getMessage());
        }
    }

    /**
     * Canonical hash function used both by the build (to seal the
     * attestation) and by the verifier (to re-hash and compare). The
     * sorted name list is joined with {@code ,} after the buildId
     * (delimited by {@code |}), hashed with SHA-256, hex-encoded
     * lowercase: {@code <buildId>|<name>,<name>,...,|}.
     *
     * <p>The seal protects only the names + buildId — a fork that edits
     * the names array without re-sealing produces ATTESTATION_INVALID.
     * We deliberately do NOT fold in class-file bytecode hashes
     * ( "Decision (2026-05-29)"): bytecode is
     * compiler-dependent and the artifact signature already covers
     * integrity. Public so a small build-time main + tests can call it.
     */
    public static String canonicalHash(String buildId, Set<String> names) {
        var sb = new StringBuilder();
        sb.append(buildId != null ? buildId : "").append('|');
        for (var name : new TreeSet<>(names)) sb.append(name).append(',');
        sb.append('|');
        try {
            var md = MessageDigest.getInstance("SHA-256");
            md.update(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private MoralDefaultsVerifier() {}
}
