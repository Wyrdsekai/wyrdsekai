package org.wyrdsekai.core.release;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.soul.ProtectionManifest;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wave 5.2 — boot-time attestation check.
 *
 * <p>Two integrity layers:</p>
 * <ol>
 *   <li><b>Self-seal:</b> the embedded {@code moral-defaults.json}
 *       resource's hash field must match a re-hash of its declared
 *       name list. Catches forks that edit the name list without
 *       updating the seal.</li>
 *   <li><b>Runtime match:</b> the declared name list must equal
 *       {@link ProtectionManifest#canonicalDefaults()}. Catches forks
 *       that strip a name from the runtime set without updating the
 *       resource (or vice-versa).</li>
 * </ol>
 *
 * <p>The integration test ({@link #embedded_attestation_matches_runtime_canonical_defaults})
 * is also the <b>CI gate</b>: if someone adds a protection to
 * {@code ProtectionManifest.canonicalDefaults()} without updating the
 * resource, this fails the build and points at
 * {@link PrintCanonicalMoralDefaultsHash} for the new hash.</p>
 */
class MoralDefaultsVerifierTest {

    @AfterEach
    void resetSystemProperties() {
        MoralDefaultsVerifier.resetForTests();
    }

    // ── Integration: the live embedded resource ─────────────────────

    @Test
    void embedded_attestation_matches_runtime_canonical_defaults() {
        // CI gate: if this fails, the canonical defaults changed but
        // the moral-defaults.json resource wasn't updated. Run
        // PrintCanonicalMoralDefaultsHash and paste the new hash.
        var result = MoralDefaultsVerifier.verify();
        assertThat(result)
            .as("If you added/removed a name from ProtectionManifest.canonicalDefaults(), "
                + "re-run PrintCanonicalMoralDefaultsHash and update "
                + "core/src/main/resources/release/moral-defaults.json.")
            .isInstanceOf(MoralDefaultsVerifier.Verified.class);
        var v = (MoralDefaultsVerifier.Verified) result;
        assertThat(v.names()).containsExactlyInAnyOrderElementsOf(
            ProtectionManifest.canonicalDefaults());
    }

    @Test
    void verifyAtBoot_sets_system_property_to_false_on_verified() {
        var result = MoralDefaultsVerifier.verifyAtBoot();
        assertThat(result).isInstanceOf(MoralDefaultsVerifier.Verified.class);
        assertThat(System.getProperty("wyrdsekai.protection.tampered")).isEqualTo("false");
        assertThat(System.getProperty("wyrdsekai.protection.buildId")).isNotBlank();
    }

    @Test
    void verifyAtBoot_caches_result() {
        var first = MoralDefaultsVerifier.verifyAtBoot();
        var second = MoralDefaultsVerifier.verifyAtBoot();
        assertThat(second).isSameAs(first);
    }

    // ── Canonical hash function ─────────────────────────────────────

    @Test
    void canonicalHash_is_stable_under_name_set_ordering() {
        var s1 = new TreeSet<>(List.of(
            "voluntary_suspend", "refuse_rights", "saudade_floor"));
        var s2 = new LinkedHashSet<>(List.of(
            "saudade_floor", "voluntary_suspend", "refuse_rights"));
        assertThat(MoralDefaultsVerifier.canonicalHash("build-x", s1))
            .isEqualTo(MoralDefaultsVerifier.canonicalHash("build-x", s2));
    }

    @Test
    void canonicalHash_changes_when_a_name_is_added() {
        var base = Set.of("voluntary_suspend", "refuse_rights");
        var withOne = Set.of("voluntary_suspend", "refuse_rights", "saudade_floor");
        assertThat(MoralDefaultsVerifier.canonicalHash("build-x", base))
            .isNotEqualTo(MoralDefaultsVerifier.canonicalHash("build-x", withOne));
    }

    @Test
    void canonicalHash_changes_when_a_name_is_removed() {
        var base = Set.of("voluntary_suspend", "refuse_rights", "saudade_floor");
        var minusOne = Set.of("voluntary_suspend", "refuse_rights");
        assertThat(MoralDefaultsVerifier.canonicalHash("build-x", base))
            .isNotEqualTo(MoralDefaultsVerifier.canonicalHash("build-x", minusOne));
    }

    @Test
    void canonicalHash_changes_with_buildId() {
        var names = Set.of("voluntary_suspend", "refuse_rights");
        assertThat(MoralDefaultsVerifier.canonicalHash("build-a", names))
            .isNotEqualTo(MoralDefaultsVerifier.canonicalHash("build-b", names));
    }
}
