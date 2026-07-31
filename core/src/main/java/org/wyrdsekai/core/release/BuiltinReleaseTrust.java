package org.wyrdsekai.core.release;

import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Bake-at-build constants for verifying release-binary signatures.
 *
 * <p>Mirrors the {@code BuiltinAuthorities} pattern from F2.2 item 2: the
 * trust root for releases is compiled into the binary, not read from a
 * config file or env var. Mutation requires a code change shipped via a
 * signed release. (See §3 for the full
 * trust chain — wyrd binary ↔ Fulcio cert ↔ workflow OIDC identity.)
 *
 * <p>Two constants are load-bearing for verification:
 * <ul>
 *   <li>{@link #WORKFLOW_IDENTITY_REGEX} — the SAN-URI in a valid release
 *     cert must match this. Encodes "this artifact came from
 *     {@code github.com/wyrdsekai/wyrdsekai/.github/workflows/release.yml}
 *     at a {@code v*.*.*} tag." Anything else (a fork, a different repo,
 *     an attacker's workflow) fails closed.</li>
 *   <li>{@link #OIDC_ISSUER} — the {@code iss} claim that Fulcio binds
 *     into the cert. Pinning this to GitHub's OIDC issuer ensures we
 *     only trust certs minted in response to a GitHub Actions OIDC
 *     token — not, say, a Google or GitLab OIDC.</li>
 * </ul>
 *
 * <p>An env-var override exists ({@link #effectiveWorkflowRegex()}) for
 * dev-mode pre-release testing where the workflow lives on a fork or a
 * branch tag. The override is <i>additive</i> (it widens, never replaces)
 * unless explicit replace-mode is opted into — same shape as
 * {@code BuiltinAuthorities.effective()}.
 */
public final class BuiltinReleaseTrust {

    private BuiltinReleaseTrust() {}

    /**
     * Production workflow identity for release-signing.
     *
     * <p>The Sigstore certificate's {@code SubjectAlternativeName URI}
     * extension carries the GitHub Actions workflow identity in the form
     * {@code https://github.com/<org>/<repo>/.github/workflows/<file>.yml@refs/tags/<tag>}.
     *
     * <p>The trailing tag pattern accepts {@code vMAJOR.MINOR.PATCH} and
     * an optional pre-release suffix ({@code -rc1}, {@code -alpha.2}, etc).
     * Anything not matching this pattern (random branch tags, no tag, a
     * different repo, a different workflow file) fails verification —
     * which is the entire point.
     */
    public static final String WORKFLOW_IDENTITY_REGEX =
        "^https://github\\.com/wyrdsekai/wyrdsekai/"
        + "\\.github/workflows/release\\.yml"
        + "@refs/tags/v[0-9]+\\.[0-9]+\\.[0-9]+(?:-[a-z0-9.]+)?$";

    /** Compiled form. */
    public static final Pattern WORKFLOW_IDENTITY_PATTERN =
        Pattern.compile(WORKFLOW_IDENTITY_REGEX);

    /**
     * GitHub Actions OIDC issuer. Sigstore embeds this in the cert as
     * the OID {@code 1.3.6.1.4.1.57264.1.1} extension. Pinning it
     * prevents an attacker from minting a Sigstore cert via a non-GitHub
     * OIDC provider and trying to claim our workflow identity.
     */
    public static final String OIDC_ISSUER =
        "https://token.actions.githubusercontent.com";

    /**
     * Optional env var to override the workflow identity regex during
     * pre-release / dev testing. The override is <i>additive</i> by
     * default — if set, a successful match against either the bundled
     * regex or the override passes.
     *
     * <p>Replace-mode (drop the bundled regex entirely) requires an
     * explicit second var, mirroring {@code WYRDSEKAI_AUTHORITY_KEYS_REPLACE}:
     * <pre>
     *   WYRDSEKAI_RELEASE_WORKFLOW_REGEX="^https://github\\.com/myFork/.*"
     *   WYRDSEKAI_RELEASE_WORKFLOW_REPLACE=true
     * </pre>
     */
    public static final String OVERRIDE_REGEX_ENV =
        "WYRDSEKAI_RELEASE_WORKFLOW_REGEX";
    public static final String OVERRIDE_REPLACE_ENV =
        "WYRDSEKAI_RELEASE_WORKFLOW_REPLACE";

    /**
     * Returns the regex(es) to test against, applying any env override.
     * Production code calls this rather than touching {@link #WORKFLOW_IDENTITY_PATTERN}
     * directly so dev-mode override is honoured automatically.
     *
     * <p>Empty result is impossible: replace-mode with empty override
     * produces the bundled regex (we don't let the user lock themselves
     * out of all releases by typo'ing an env var).
     */
    public static List<Pattern> effectiveWorkflowRegex() {
        var bundled = WORKFLOW_IDENTITY_PATTERN;
        var override = System.getenv(OVERRIDE_REGEX_ENV);
        if (override == null || override.isBlank()) {
            return List.of(bundled);
        }
        Pattern overridePat;
        try {
            overridePat = Pattern.compile(override);
        } catch (PatternSyntaxException e) {
            // Bad regex from env — log via stderr and keep bundled only.
            System.err.println("[BuiltinReleaseTrust] WARN: bad regex in "
                + OVERRIDE_REGEX_ENV + ": " + e.getMessage()
                + " — falling back to bundled regex only");
            return List.of(bundled);
        }
        var replace = "true".equalsIgnoreCase(System.getenv(OVERRIDE_REPLACE_ENV));
        if (replace) {
            return List.of(overridePat);
        }
        return List.of(bundled, overridePat);
    }

    /**
     * @return true if the given workflow identity (cert SAN URI) matches
     *     any of the effective regexes. False if no match.
     */
    public static boolean matchesWorkflowIdentity(String identity) {
        if (identity == null) return false;
        for (var pat : effectiveWorkflowRegex()) {
            if (pat.matcher(identity).matches()) return true;
        }
        return false;
    }

    /**
     * Path under {@code core}'s resources where the Gradle build will
     * embed the Sigstore TUF-anchored trusted-root JSON (Fulcio root CA
     * + Rekor pubkeys). Read at startup by ReleaseVerifier; pinning
     * beats live-fetching because a network compromise can't swap a
     * trusted root that's already inside the binary.
     *
     * <p>The Gradle task that populates this file is added in a follow-up
     * Until then the
     * resource is absent and ReleaseVerifier returns
     * {@code TrustedRootUnavailable}.
     */
    public static final String TRUSTED_ROOT_RESOURCE =
        "/release/trusted-root.json";
}
