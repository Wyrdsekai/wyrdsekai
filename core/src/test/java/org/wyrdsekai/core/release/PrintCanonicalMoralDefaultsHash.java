package org.wyrdsekai.core.release;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.soul.ProtectionManifest;

import java.util.TreeSet;

/**
 * Wave 5.2 build helper — prints the canonical hash of the current
 * {@link ProtectionManifest#canonicalDefaults()} set so the
 * {@code release/moral-defaults.json} resource can be hand-updated when
 * the canonical set changes. Run with:
 *
 * <pre>./gradlew :core:test --tests PrintCanonicalMoralDefaultsHash -i</pre>
 *
 * <p> (Decision 2026-05-29): the seal covers
 * only names + buildId. We no longer hash class-file bytecode — bytecode
 * is compiler/JDK-dependent (it tripped a false CLASS_HASH_MISMATCH on a
 * Windows JDK-25 rebuild) and the artifact signature already covers
 * integrity. Provenance granularity lives in the {@code sourceCommit}
 * field instead (audit-time {@code git diff} against the §4.1 file list).
 *
 * <p>This is intentionally NOT a CI gate — it's a developer utility. The
 * real integrity gate is {@link MoralDefaultsVerifierTest} which fails
 * the build if the attested set drifts away from the runtime set.
 */
class PrintCanonicalMoralDefaultsHash {

    @Test
    void print_canonical_hash() {
        var buildId = "stock-2026-07-21";
        var names = new TreeSet<>(ProtectionManifest.canonicalDefaults());
        var hash = MoralDefaultsVerifier.canonicalHash(buildId, names);

        System.out.println("==== Canonical moral-defaults attestation ====");
        System.out.println("buildId: " + buildId);
        System.out.println("hash:    " + hash);
        System.out.println("names:   " + names);
        System.out.println();
        System.out.println("Drop `hash` into release/moral-defaults.json and bump");
        System.out.println("`sourceCommit` to the commit this attestation describes.");
        System.out.println("==============================================");
    }
}
