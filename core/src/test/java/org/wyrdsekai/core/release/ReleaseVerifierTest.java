package org.wyrdsekai.core.release;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.Base64;

/**
 * Phase 1 contract for the structural-checks portion of release verification.
 * Cryptographic chain validation (Phase 1.5) gets its own test class once
 * the trusted-root resource is embedded.
 *
 * <p>Each failure mode must produce a distinct {@link ReleaseVerifier.Failed.Reason}
 * so callers can branch on it (e.g. a CLI showing different remediation
 * advice for ARTIFACT_HASH_MISMATCH vs WORKFLOW_IDENTITY_MISMATCH).
 */
class ReleaseVerifierTest {

    private final ReleaseVerifier verifier = new ReleaseVerifier();

    @Test void missing_bundle_returns_typed_BUNDLE_INVALID(@TempDir Path dir) throws IOException {
        var artifact = dir.resolve("foo.deb");
        Files.writeString(artifact, "stub");
        var bundle = dir.resolve("foo.deb.sigstore.json");  // not created
        var result = verifier.verify(artifact, bundle);
        assertThat(result).isInstanceOf(ReleaseVerifier.Failed.class);
        var f = (ReleaseVerifier.Failed) result;
        assertThat(f.reason()).isEqualTo(ReleaseVerifier.Failed.Reason.BUNDLE_INVALID);
        assertThat(f.message()).contains("not found");
    }

    @Test void missing_artifact_returns_typed_ARTIFACT_INVALID(@TempDir Path dir) throws IOException {
        var bundle = dir.resolve("foo.deb.sigstore.json");
        Files.writeString(bundle, "{}");
        var artifact = dir.resolve("foo.deb");  // not created
        var result = verifier.verify(artifact, bundle);
        assertThat(result).isInstanceOf(ReleaseVerifier.Failed.class);
        assertThat(((ReleaseVerifier.Failed) result).reason())
            .isEqualTo(ReleaseVerifier.Failed.Reason.ARTIFACT_INVALID);
    }

    @Test void garbage_bundle_returns_typed_BUNDLE_INVALID(@TempDir Path dir) throws IOException {
        var artifact = dir.resolve("foo.deb");
        Files.writeString(artifact, "stub");
        var bundle = dir.resolve("foo.deb.sigstore.json");
        Files.writeString(bundle, "this is not json");
        var result = verifier.verify(artifact, bundle);
        var f = (ReleaseVerifier.Failed) result;
        assertThat(f.reason()).isEqualTo(ReleaseVerifier.Failed.Reason.BUNDLE_INVALID);
    }

    @Test void empty_bundle_no_subject_is_BUNDLE_INVALID(@TempDir Path dir) throws IOException {
        var artifact = dir.resolve("foo.deb");
        Files.writeString(artifact, "stub");
        var bundle = dir.resolve("foo.deb.sigstore.json");
        Files.writeString(bundle, "{}");
        var result = verifier.verify(artifact, bundle);
        var f = (ReleaseVerifier.Failed) result;
        assertThat(f.reason()).isEqualTo(ReleaseVerifier.Failed.Reason.BUNDLE_INVALID);
        assertThat(f.message()).containsIgnoringCase("subject");
    }

    @Test void hash_mismatch_returns_typed_ARTIFACT_HASH_MISMATCH(@TempDir Path dir) throws IOException {
        // Build a synthetic bundle whose subject digest is for "expected"
        // bytes, then verify against a file containing different bytes.
        var artifact = dir.resolve("foo.deb");
        Files.writeString(artifact, "this is the actual artifact");
        // sha256 of "different bytes that were signed":
        var differentSha = "1f8e90af3f5dca8e26ee79bcd9b5dadcc0e2adfb4ad24a83b2eea6a7c3b0a01b";  // arbitrary
        var bundleJson = """
            {
              "dsseEnvelope": {
                "payload": "%s"
              },
              "verificationMaterial": {
                "certificate": { "rawBytes": "" }
              }
            }
            """.formatted(Base64.getEncoder().encodeToString(
                ("""
                {"subject":[{"name":"foo.deb","digest":{"sha256":"%s"}}]}
                """.formatted(differentSha)).getBytes()
            ));
        var bundle = dir.resolve("foo.deb.sigstore.json");
        Files.writeString(bundle, bundleJson);
        var result = verifier.verify(artifact, bundle);
        var f = (ReleaseVerifier.Failed) result;
        assertThat(f.reason()).isEqualTo(ReleaseVerifier.Failed.Reason.ARTIFACT_HASH_MISMATCH);
        assertThat(f.message())
            .as("hash-mismatch error must clearly tell the user the artifact was modified or the wrong bundle is paired")
            .containsIgnoringCase("modified");
    }

    @Test void Verified_record_carries_provenance() {
        var v = new ReleaseVerifier.Verified(
            "https://github.com/wyrdsekai/wyrdsekai/.github/workflows/release.yml@refs/tags/v0.2.0",
            BuiltinReleaseTrust.OIDC_ISSUER,
            "v0.2.0",
            "abc123",
            "deadbeef");
        assertThat(v.workflowIdentity()).contains("wyrdsekai/wyrdsekai");
        assertThat(v.tagRef()).isEqualTo("v0.2.0");
        assertThat(v.commitSha()).isEqualTo("abc123");
        assertThat(v.artifactSha256()).isEqualTo("deadbeef");
    }

    @Test void extractTagRef_pulls_tag_from_workflow_identity() {
        var id = "https://github.com/wyrdsekai/wyrdsekai/.github/workflows/release.yml@refs/tags/v1.2.3-rc1";
        assertThat(ReleaseVerifier.extractTagRef(id)).isEqualTo("v1.2.3-rc1");
    }

    @Test void Failed_Reason_enum_covers_all_documented_cases() {
        // Lock down the typed-error contract: callers (CLI, tests, future
        // wyrd doctor) branch on these. Adding/removing a value is a
        // contract change. 2026-05-09 — CRYPTO_NOT_YET_IMPLEMENTED renamed
        // to CHAIN_VALIDATION_FAILED when Phase 1.5 (sigstore-java embed)
        // landed; the placeholder semantics are gone, the value now means
        // "we tried to verify cryptographically and it failed."
        var reasons = ReleaseVerifier.Failed.Reason.values();
        assertThat(reasons).extracting(Enum::name).containsExactlyInAnyOrder(
            "BUNDLE_INVALID",
            "ARTIFACT_INVALID",
            "ARTIFACT_HASH_MISMATCH",
            "WORKFLOW_IDENTITY_MISMATCH",
            "OIDC_ISSUER_MISMATCH",
            "TRUSTED_ROOT_UNAVAILABLE",
            "CHAIN_VALIDATION_FAILED");
    }
}
