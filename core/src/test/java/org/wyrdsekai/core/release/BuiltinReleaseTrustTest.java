package org.wyrdsekai.core.release;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Constants-and-regex contract for the bake-at-build trust root.
 *
 * <p>Failure here means the trust root drifted in a way that would
 * either accept attacker workflows or reject legitimate releases.
 * Both directions matter — a regex that's too loose is a security
 * failure; one that's too tight is a release-day fire-drill.
 */
class BuiltinReleaseTrustTest {

    @Test void production_workflow_identity_matches() {
        var ok = "https://github.com/wyrdsekai/wyrdsekai/.github/workflows/release.yml@refs/tags/v0.2.0";
        assertThat(BuiltinReleaseTrust.matchesWorkflowIdentity(ok))
            .as("baseline production identity must match")
            .isTrue();
    }

    @Test void canonical_github_casing_matches() {
        // The Fulcio cert SAN carries the repository's CANONICAL casing —
        // the public repo lives at github.com/Wyrdsekai/wyrdsekai. A
        // lowercase-only pin would reject every genuine release bundle.
        var ok = "https://github.com/Wyrdsekai/wyrdsekai/.github/workflows/release.yml@refs/tags/v0.1.5";
        assertThat(BuiltinReleaseTrust.matchesWorkflowIdentity(ok))
            .as("canonical capital-W org casing must match — this is what real certs carry")
            .isTrue();
    }

    @Test void prerelease_tag_suffix_is_accepted() {
        for (var tag : new String[] {
                "v0.2.0-rc1",
                "v0.2.0-alpha.2",
                "v1.0.0-beta",
                "v1.0.0-rc.10",
        }) {
            var id = "https://github.com/wyrdsekai/wyrdsekai/.github/workflows/release.yml"
                + "@refs/tags/" + tag;
            assertThat(BuiltinReleaseTrust.matchesWorkflowIdentity(id))
                .as("prerelease tag must match: " + tag)
                .isTrue();
        }
    }

    @Test void wrong_org_rejected() {
        var attacker = "https://github.com/attacker/wyrdsekai/.github/workflows/release.yml@refs/tags/v0.2.0";
        assertThat(BuiltinReleaseTrust.matchesWorkflowIdentity(attacker))
            .as("a workflow under a different GitHub org must NOT match — that's the entire point")
            .isFalse();
    }

    @Test void wrong_repo_rejected() {
        var different = "https://github.com/wyrdsekai/different-repo/.github/workflows/release.yml@refs/tags/v0.2.0";
        assertThat(BuiltinReleaseTrust.matchesWorkflowIdentity(different)).isFalse();
    }

    @Test void wrong_workflow_file_rejected() {
        var ci = "https://github.com/wyrdsekai/wyrdsekai/.github/workflows/ci.yml@refs/tags/v0.2.0";
        assertThat(BuiltinReleaseTrust.matchesWorkflowIdentity(ci))
            .as("a non-release workflow file (e.g. ci.yml) must NOT mint a release-trusted cert")
            .isFalse();
    }

    @Test void branch_ref_rejected() {
        var branch = "https://github.com/wyrdsekai/wyrdsekai/.github/workflows/release.yml@refs/heads/main";
        assertThat(BuiltinReleaseTrust.matchesWorkflowIdentity(branch))
            .as("running release.yml on a branch (no tag) must not produce a trusted release")
            .isFalse();
    }

    @Test void no_tag_rejected() {
        var noTag = "https://github.com/wyrdsekai/wyrdsekai/.github/workflows/release.yml";
        assertThat(BuiltinReleaseTrust.matchesWorkflowIdentity(noTag)).isFalse();
    }

    @Test void null_input_rejected() {
        assertThat(BuiltinReleaseTrust.matchesWorkflowIdentity(null)).isFalse();
    }

    @Test void oidc_issuer_pinned_to_github() {
        assertThat(BuiltinReleaseTrust.OIDC_ISSUER)
            .as("OIDC issuer must be GitHub Actions — drifting this would let any "
                + "OIDC provider mint Sigstore certs for our workflow identity")
            .isEqualTo("https://token.actions.githubusercontent.com");
    }

    @Test void trusted_root_resource_path_is_under_release_namespace() {
        // Resource layout convention: /release/* belongs to the release-signing
        // subsystem. Fixed path so the gradle task that fetches the Sigstore
        // trusted root has a stable destination.
        assertThat(BuiltinReleaseTrust.TRUSTED_ROOT_RESOURCE)
            .startsWith("/release/")
            .endsWith(".json");
    }
}
