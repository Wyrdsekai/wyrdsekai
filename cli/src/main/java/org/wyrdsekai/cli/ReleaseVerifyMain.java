package org.wyrdsekai.cli;

import org.wyrdsekai.core.release.ReleaseVerifier;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * CLI entry for {@code wyrd verify-release}. Resolves the bundle next
 * to the artifact (or accepts an explicit {@code --bundle} flag),
 * delegates to {@link ReleaseVerifier}, and prints a verdict + exit
 * code suitable for automation.
 *
 * <p>Exit codes:
 * <ul>
 *   <li>0 — verification passed (cryptographically authentic)</li>
 *   <li>1 — verification FAILED — do not install</li>
 *   <li>2 — usage error (missing args, bad flags)</li>
 *   <li>3 — partial: this wyrd binary cannot verify (build problem,
 *       NOT an artifact problem). Distinct from exit 1 so automation
 *       can tell "your installer is incomplete" apart from "this
 *       download is suspect."</li>
 * </ul>
 *
 * <p>Phase 1.5 (LANDED 2026-05-09): full cryptographic chain validation
 * via embedded sigstore-java. Structural checks (artifact hash, workflow
 * identity, OIDC issuer) run first as a fast-path reject, then the
 * Fulcio cert chain + Rekor inclusion proof + cert matchers validate
 * end-to-end against the binary's pinned trusted root. See
 * §5.
 */
public final class ReleaseVerifyMain {

    public static void main(String[] args) {
        Path artifact = null;
        Path bundle = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--bundle":
                    if (i + 1 >= args.length) {
                        System.err.println("--bundle requires a path argument");
                        System.exit(2);
                    }
                    bundle = Path.of(args[++i]);
                    break;
                case "--help":
                case "-h":
                    printUsage();
                    System.exit(0);
                    return;
                default:
                    if (artifact == null) {
                        artifact = Path.of(args[i]);
                    } else {
                        System.err.println("Unexpected argument: " + args[i]);
                        printUsage();
                        System.exit(2);
                    }
            }
        }
        if (artifact == null) {
            printUsage();
            System.exit(2);
        }
        if (bundle == null) {
            // Convention: <artifact>.sigstore.json sits next to the artifact.
            bundle = Path.of(artifact + ".sigstore.json");
        }

        if (!Files.isReadable(artifact)) {
            System.err.println("ERROR: artifact not found or unreadable: " + artifact);
            System.exit(1);
        }
        if (!Files.isReadable(bundle)) {
            System.err.println("ERROR: bundle not found at " + bundle);
            System.err.println("       (expected sibling file: <artifact>.sigstore.json)");
            System.err.println("       Pass --bundle <path> to override.");
            System.exit(1);
        }

        var result = new ReleaseVerifier().verify(artifact, bundle);
        switch (result) {
            case ReleaseVerifier.Verified v -> {
                System.out.println("VERIFIED: " + artifact.getFileName());
                System.out.println("  workflow:   " + v.workflowIdentity());
                System.out.println("  tag:        " + v.tagRef());
                System.out.println("  commit:     " + v.commitSha());
                System.out.println("  oidc:       " + v.oidcIssuer());
                System.out.println("  sha256:     " + v.artifactSha256());
                System.exit(0);
            }
            case ReleaseVerifier.Failed f -> {
                if (f.reason() == ReleaseVerifier.Failed.Reason.TRUSTED_ROOT_UNAVAILABLE) {
                    // Distinct exit code (3) so automation can tell "this
                    // wyrd binary can't verify because its embedded trust
                    // root is missing" (build problem) apart from a real
                    // verification failure (1, "this artifact is suspect").
                    System.out.println("PARTIAL: " + artifact.getFileName());
                    System.out.println("  reason:  " + f.reason());
                    System.out.println("  note:    " + f.message());
                    System.out.println();
                    System.out.println("This wyrd binary is missing the embedded Sigstore"
                        + " trusted root — re-install from a release that includes it.");
                    System.exit(3);
                }
                System.err.println("VERIFICATION FAILED: " + artifact.getFileName());
                System.err.println("  reason:   " + f.reason());
                System.err.println("  message:  " + f.message());
                if (f.detail() != null && !f.detail().isBlank()) {
                    System.err.println("  detail:   " + f.detail());
                }
                System.err.println();
                System.err.println("DO NOT INSTALL this artifact unless you can explain "
                    + "the failure (e.g. you intentionally downloaded a fork build).");
                System.exit(1);
            }
        }
    }

    private static void printUsage() {
        System.err.println("Usage: wyrd verify-release <artifact> [--bundle <path>]");
        System.err.println();
        System.err.println("Verifies a release-signing bundle against the embedded trust root.");
        System.err.println();
        System.err.println("Options:");
        System.err.println("  --bundle <path>   Path to the .sigstore.json bundle");
        System.err.println("                    (default: <artifact>.sigstore.json next to the artifact)");
        System.err.println("  -h, --help        Show this help");
        System.err.println();
        System.err.println("Exit codes:");
        System.err.println("  0  verified (cryptographically authentic)");
        System.err.println("  1  verification FAILED — do not install");
        System.err.println("  2  usage error");
        System.err.println("  3  partial: this wyrd binary cannot verify (build problem)");
    }
}
