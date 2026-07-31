package org.wyrdsekai.core.release;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sigstore.KeylessVerificationException;
import dev.sigstore.KeylessVerifier;
import dev.sigstore.TrustedRootProvider;
import dev.sigstore.VerificationOptions;
import dev.sigstore.bundle.Bundle;
import dev.sigstore.bundle.BundleParseException;
import dev.sigstore.strings.StringMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Verifies a release-binary's Sigstore bundle. Returns a sealed
 * {@link Result} that's either {@link Verified} (with the parsed
 * provenance metadata for display) or {@link Failed} (with a typed
 * reason and human-readable message).
 *
 * <p>This is the user-facing entry point for {@code wyrd verify-release}
 * and is the runtime cousin of {@link BuiltinReleaseTrust}. The trust
 * boundary is the bundled Sigstore trusted root + the bundled workflow
 * identity regex — neither is read from network or env at runtime
 * (override env vars expand the regex but cannot replace the trusted
 * root).
 *
 * <p><b>Current scope (Phase 1.5 — LANDED 2026-05-09).</b> Bundle-shape
 * parsing, artifact-hash comparison, workflow-identity regex match,
 * OIDC-issuer pinning, AND full cryptographic chain validation against
 * the embedded Sigstore trusted root (Fulcio cert chain → Rekor inclusion
 * proof → SAN URI matcher → OIDC issuer). Returns {@link Verified} only
 * after the entire chain validates against the binary's pinned trust
 * root. {@link Failed.Reason#CHAIN_VALIDATION_FAILED} covers any
 * cryptographic verification failure (cert expired, chain broken,
 * Rekor entry not in log, etc.). The structural Phase-1 gates run
 * BEFORE the crypto step as a fast-path reject for malformed bundles
 * — sigstore-java would catch those too, but our typed errors give the
 * CLI a richer "your wyrdsekai download is suspect because X" message.
 *
 * <p>A complete authentic flow looks like:
 * <pre>
 *   var verifier = new ReleaseVerifier();
 *   var result = verifier.verify(Path.of("wyrdsekai_0.2.0_amd64.deb"),
 *                                Path.of("wyrdsekai_0.2.0_amd64.deb.sigstore.json"));
 *   switch (result) {
 *     case Verified v -&gt; System.out.println("OK: " + v.workflowIdentity());
 *     case Failed f -&gt; { System.err.println("FAIL: " + f.message()); System.exit(2); }
 *   }
 * </pre>
 */
public final class ReleaseVerifier {

    private static final Logger log = LoggerFactory.getLogger(ReleaseVerifier.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Sealed result type — either Verified (good) or Failed (bad, with reason). */
    public sealed interface Result permits Verified, Failed {}

    /**
     * Successful verification. Carries the provenance metadata so the CLI
     * can print "this came from {@code workflow X} at {@code commit Y}
     * tagged {@code v1.2.3}, signed at {@code timestamp}".
     */
    public record Verified(
        String workflowIdentity,
        String oidcIssuer,
        String tagRef,
        String commitSha,
        String artifactSha256
    ) implements Result {}

    /**
     * Verification failed. {@link #reason} is the typed enum; message is
     * the human-readable surface (suitable for a CLI error). Detail is
     * an optional structured field (often the offending value) for
     * machine-readable consumers.
     */
    public record Failed(
        Reason reason,
        String message,
        String detail
    ) implements Result {

        public enum Reason {
            /** Bundle file missing, unreadable, or wrong format. */
            BUNDLE_INVALID,
            /** Artifact file missing or unreadable. */
            ARTIFACT_INVALID,
            /** sha256(artifact) does not match the digest signed by the bundle. */
            ARTIFACT_HASH_MISMATCH,
            /** Bundle's certificate SAN URI doesn't match expected workflow regex. */
            WORKFLOW_IDENTITY_MISMATCH,
            /** Bundle's OIDC issuer extension doesn't match the pinned issuer. */
            OIDC_ISSUER_MISMATCH,
            /**
             * Embedded Sigstore trusted root resource is missing from the binary.
             * Indicates a build problem (the {@code updateTrustedRoot} Gradle
             * task didn't run) — NOT an artifact problem. The CLI maps this
             * to exit code 3 (partial) so automation can distinguish "your
             * binary is incomplete" from "this artifact is suspect."
             */
            TRUSTED_ROOT_UNAVAILABLE,
            /**
             * Cryptographic chain validation failed: cert chain didn't
             * verify against the embedded Sigstore root, Rekor inclusion
             * proof was bad, cert was expired/revoked, or the certificate
             * matchers (SAN URI / OIDC issuer) didn't pass. Detail carries
             * the underlying sigstore-java exception message.
             */
            CHAIN_VALIDATION_FAILED,
        }
    }

    /**
     * Verify a downloaded artifact against its sibling Sigstore bundle.
     *
     * @param artifact path to the downloaded release artifact (e.g.
     *     {@code wyrdsekai_0.2.0_amd64.deb})
     * @param bundle path to the bundle (e.g.
     *     {@code wyrdsekai_0.2.0_amd64.deb.sigstore.json})
     * @return {@link Verified} on success, {@link Failed} on any
     *     verification gate failure (typed by {@link Failed.Reason}).
     */
    public Result verify(Path artifact, Path bundle) {
        // 1. Bundle parse
        if (!Files.isReadable(bundle)) {
            return new Failed(Failed.Reason.BUNDLE_INVALID,
                "Bundle file not found or unreadable: " + bundle, bundle.toString());
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(bundle.toFile());
        } catch (IOException e) {
            return new Failed(Failed.Reason.BUNDLE_INVALID,
                "Bundle is not valid JSON: " + e.getMessage(), bundle.toString());
        }

        // 2. Artifact present + readable
        if (!Files.isReadable(artifact)) {
            return new Failed(Failed.Reason.ARTIFACT_INVALID,
                "Artifact file not found or unreadable: " + artifact, artifact.toString());
        }

        // 3. Hash artifact, compare to digest in bundle subject
        String actualHash;
        try {
            actualHash = sha256(artifact);
        } catch (IOException | NoSuchAlgorithmException e) {
            return new Failed(Failed.Reason.ARTIFACT_INVALID,
                "Failed to hash artifact: " + e.getMessage(), artifact.toString());
        }
        var expectedHashOpt = extractSubjectDigest(root);
        if (expectedHashOpt.isEmpty()) {
            return new Failed(Failed.Reason.BUNDLE_INVALID,
                "Bundle does not contain a parseable subject digest", null);
        }
        var expectedHash = expectedHashOpt.get();
        if (!expectedHash.equalsIgnoreCase(actualHash)) {
            return new Failed(Failed.Reason.ARTIFACT_HASH_MISMATCH,
                "Artifact hash does not match bundle's signed digest. "
                    + "This artifact has been modified since signing OR you have the wrong bundle.",
                "expected=" + expectedHash + " actual=" + actualHash);
        }

        // 4. Workflow identity (cert SAN URI) check
        var identityOpt = extractWorkflowIdentity(root);
        if (identityOpt.isEmpty()) {
            return new Failed(Failed.Reason.BUNDLE_INVALID,
                "Bundle's certificate does not carry a workflow-identity SAN URI", null);
        }
        var identity = identityOpt.get();
        if (!BuiltinReleaseTrust.matchesWorkflowIdentity(identity)) {
            return new Failed(Failed.Reason.WORKFLOW_IDENTITY_MISMATCH,
                "Bundle was signed by '" + identity + "' which does NOT match the "
                    + "expected Wyrdsekai release workflow. This artifact did not come "
                    + "from the official release pipeline. DO NOT INSTALL.",
                identity);
        }

        // 5. OIDC issuer check
        var issuerOpt = extractOidcIssuer(root);
        if (issuerOpt.isPresent()
                && !BuiltinReleaseTrust.OIDC_ISSUER.equals(issuerOpt.get())) {
            return new Failed(Failed.Reason.OIDC_ISSUER_MISMATCH,
                "Bundle's OIDC issuer is '" + issuerOpt.get() + "' but expected '"
                    + BuiltinReleaseTrust.OIDC_ISSUER + "'.",
                issuerOpt.get());
        }

        // 6. Cryptographic chain validation via sigstore-java (Phase 1.5).
        // Delegates to dev.sigstore.KeylessVerifier with the binary's
        // embedded trusted root — that walks the Fulcio cert chain to the
        // pinned root CA, verifies the Rekor inclusion proof, checks the
        // CT log signature, and runs the certificate matchers below
        // against the leaf cert's SAN + OIDC-issuer extension.
        try (var trustedRootStream = ReleaseVerifier.class.getResourceAsStream(
                BuiltinReleaseTrust.TRUSTED_ROOT_RESOURCE)) {
            if (trustedRootStream == null) {
                return new Failed(Failed.Reason.TRUSTED_ROOT_UNAVAILABLE,
                    "Embedded Sigstore trusted root resource is missing from this wyrd binary. "
                        + "Structural checks PASS but cryptographic chain validation cannot run. "
                        + "This is a BUILD problem (the updateTrustedRoot Gradle task didn't run), "
                        + "not an artifact problem. Treat as 'cannot verify' rather than 'verified'.",
                    "missing resource: " + BuiltinReleaseTrust.TRUSTED_ROOT_RESOURCE);
            }
            // sigstore-java's TrustedRootProvider.from(Path) reads from disk.
            // Spool the classpath bytes to a temp file (cached at process scope
            // would also work but the cost here is one ~7KB write per verify).
            var trustedRootTmp = Files.createTempFile("wyrd-trusted-root-", ".json");
            trustedRootTmp.toFile().deleteOnExit();
            Files.copy(trustedRootStream, trustedRootTmp, StandardCopyOption.REPLACE_EXISTING);

            var verifier = KeylessVerifier.builder()
                .trustedRootProvider(TrustedRootProvider.from(trustedRootTmp))
                .build();

            // Use the same regex as BuiltinReleaseTrust.WORKFLOW_IDENTITY_REGEX
            // (env-override branches don't apply to the sigstore-java path —
            // step 4 above already handled override semantics; here we pin
            // to the bundled regex to match what was structurally checked).
            var options = VerificationOptions.builder()
                .addCertificateMatchers(
                    VerificationOptions.CertificateMatcher.fulcio()
                        .subjectAlternativeName(
                            StringMatcher.regex(
                                BuiltinReleaseTrust.WORKFLOW_IDENTITY_REGEX))
                        .issuer(
                            StringMatcher.string(
                                BuiltinReleaseTrust.OIDC_ISSUER))
                        .build())
                .build();

            var sigstoreBundle = Bundle.from(bundle, StandardCharsets.UTF_8);
            verifier.verify(artifact, sigstoreBundle, options);
            // verify(...) is void — throws on any failure. If we reach here,
            // the entire chain (cert → root, Rekor entry, SAN/issuer matchers)
            // is valid.
        } catch (KeylessVerificationException e) {
            return new Failed(Failed.Reason.CHAIN_VALIDATION_FAILED,
                "Cryptographic chain validation FAILED. This artifact's signature does not "
                    + "verify against the official Wyrdsekai release pipeline's trust root. "
                    + "DO NOT INSTALL. Detail: " + e.getMessage(),
                e.getMessage());
        } catch (BundleParseException e) {
            return new Failed(Failed.Reason.BUNDLE_INVALID,
                "sigstore-java could not parse the bundle: " + e.getMessage(),
                e.getMessage());
        } catch (Exception e) {
            // Catch-all for other sigstore-java init failures (e.g. trusted
            // root JSON malformed, missing keys, JCE provider issues). These
            // are build/environment problems, not artifact problems — but
            // we surface as CHAIN_VALIDATION_FAILED rather than swallowing,
            // because we cannot prove authenticity if the verifier itself
            // can't initialize.
            return new Failed(Failed.Reason.CHAIN_VALIDATION_FAILED,
                "Sigstore verifier failed to initialize or run: " + e.getMessage(),
                e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        return new Verified(
            identity,
            issuerOpt.orElse(BuiltinReleaseTrust.OIDC_ISSUER),
            extractTagRef(identity),
            extractCommitSha(root).orElse("unknown"),
            actualHash);
    }

    /** SHA-256 hex of the artifact bytes. */
    private static String sha256(Path artifact) throws IOException, NoSuchAlgorithmException {
        var md = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(artifact)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        return HexFormat.of().formatHex(md.digest());
    }

    /**
     * Pull the signed subject's sha256 digest from a v3 Sigstore bundle.
     * Bundle shape:
     * <pre>
     *   { "dsseEnvelope": { "payload": "&lt;base64(in-toto-statement)&gt;", ... } }
     * </pre>
     * The in-toto statement has {@code subject[].digest.sha256}.
     */
    static Optional<String> extractSubjectDigest(JsonNode bundleRoot) {
        var payloadB64 = bundleRoot.path("dsseEnvelope").path("payload").asText(null);
        if (payloadB64 == null || payloadB64.isBlank()) return Optional.empty();
        try {
            var payload = Base64.getDecoder().decode(payloadB64);
            var stmt = MAPPER.readTree(payload);
            var subjects = stmt.path("subject");
            if (!subjects.isArray() || subjects.isEmpty()) return Optional.empty();
            var sha = subjects.get(0).path("digest").path("sha256").asText(null);
            if (sha == null || sha.isBlank()) return Optional.empty();
            return Optional.of(sha);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Extract the workflow identity (SAN URI) from the bundle's leaf cert.
     * The cert is in {@code verificationMaterial.certificate.rawBytes} as
     * base64 DER.
     */
    static Optional<String> extractWorkflowIdentity(JsonNode bundleRoot) {
        var certB64 = bundleRoot.path("verificationMaterial")
            .path("certificate").path("rawBytes").asText(null);
        if (certB64 == null || certB64.isBlank()) return Optional.empty();
        try {
            var der = Base64.getDecoder().decode(certB64);
            var factory = CertificateFactory.getInstance("X.509");
            var cert = (X509Certificate)
                factory.generateCertificate(new ByteArrayInputStream(der));
            // Subject Alternative Names — type 6 is URI per RFC 5280.
            var sans = cert.getSubjectAlternativeNames();
            if (sans == null) return Optional.empty();
            for (var entry : sans) {
                if (entry.size() >= 2
                        && Integer.valueOf(6).equals(entry.get(0))
                        && entry.get(1) instanceof String uri) {
                    return Optional.of(uri);
                }
            }
            return Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * OID 1.3.6.1.4.1.57264.1.1 is Sigstore's OIDC issuer extension.
     * Older Fulcio certs include it; newer ones use the v0.3 extension
     * shape (1.3.6.1.4.1.57264.1.8). Try both.
     */
    static Optional<String> extractOidcIssuer(JsonNode bundleRoot) {
        var certB64 = bundleRoot.path("verificationMaterial")
            .path("certificate").path("rawBytes").asText(null);
        if (certB64 == null || certB64.isBlank()) return Optional.empty();
        try {
            var der = Base64.getDecoder().decode(certB64);
            var factory = CertificateFactory.getInstance("X.509");
            var cert = (X509Certificate)
                factory.generateCertificate(new ByteArrayInputStream(der));
            for (var oid : List.of("1.3.6.1.4.1.57264.1.1", "1.3.6.1.4.1.57264.1.8")) {
                var raw = cert.getExtensionValue(oid);
                if (raw == null) continue;
                // The extension value is DER-wrapped twice (OCTET STRING containing
                // OCTET STRING containing the actual UTF-8 string). For the legacy
                // 1.1 form the inner is a plain UTF-8 string; for 1.8 it's also a
                // plain UTF-8 string but inside the outer OCTET wrapper. Both forms
                // can be unwrapped by skipping the leading two-byte DER tag+length
                // prefix iteratively until we hit printable text.
                String s = decodeDerWrappedString(raw);
                if (s != null && !s.isBlank()) return Optional.of(s);
            }
            return Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Extract the {@code v*.*.*} tag from the workflow identity URL.
     * Returns "unknown" if the regex doesn't match (shouldn't happen
     * post-{@link BuiltinReleaseTrust#matchesWorkflowIdentity} success
     * but defensive).
     */
    static String extractTagRef(String workflowIdentity) {
        var idx = workflowIdentity.lastIndexOf("@refs/tags/");
        if (idx < 0) return "unknown";
        return workflowIdentity.substring(idx + "@refs/tags/".length());
    }

    /**
     * Try to extract the build's commit SHA from the in-toto statement's
     * {@code predicate.buildDefinition.resolvedDependencies} or similar.
     * Best-effort: returns empty if shape doesn't match a path we know.
     */
    static Optional<String> extractCommitSha(JsonNode bundleRoot) {
        try {
            var payloadB64 = bundleRoot.path("dsseEnvelope").path("payload").asText(null);
            if (payloadB64 == null) return Optional.empty();
            var payload = Base64.getDecoder().decode(payloadB64);
            var stmt = MAPPER.readTree(payload);
            // SLSA v1 provenance shape — buildDefinition.resolvedDependencies[0].digest.gitCommit
            var deps = stmt.path("predicate").path("buildDefinition").path("resolvedDependencies");
            if (deps.isArray() && !deps.isEmpty()) {
                var sha = deps.get(0).path("digest").path("gitCommit").asText(null);
                if (sha != null && !sha.isBlank()) return Optional.of(sha);
            }
            return Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Best-effort DER unwrap to a printable UTF-8 string. Sigstore cert
     * extension values are wrapped in OCTET STRING; the inner content
     * may itself be DER-encoded. Walk the outer two-byte tag+length
     * prefixes until what remains looks printable.
     */
    private static String decodeDerWrappedString(byte[] der) {
        if (der == null || der.length < 2) return null;
        byte[] cur = der;
        // Try up to 3 levels of DER unwrap.
        for (int i = 0; i < 3 && cur.length >= 2; i++) {
            int len = cur[1] & 0xFF;
            int start = 2;
            // Long-form length encoding (high bit set)
            if ((cur[1] & 0x80) != 0) {
                int lenBytes = cur[1] & 0x7F;
                if (lenBytes <= 0 || lenBytes > 4 || cur.length < 2 + lenBytes) return null;
                len = 0;
                for (int b = 0; b < lenBytes; b++) {
                    len = (len << 8) | (cur[2 + b] & 0xFF);
                }
                start = 2 + lenBytes;
            }
            if (start + len > cur.length) return null;
            byte[] inner = new byte[len];
            System.arraycopy(cur, start, inner, 0, len);
            // Heuristic: if it looks printable now, return it.
            if (isPrintableAscii(inner)) {
                return new String(inner, StandardCharsets.UTF_8);
            }
            cur = inner;
        }
        return null;
    }

    private static boolean isPrintableAscii(byte[] b) {
        if (b.length == 0) return false;
        for (byte c : b) {
            if ((c & 0xFF) < 0x20 || (c & 0xFF) > 0x7E) return false;
        }
        return true;
    }
}
