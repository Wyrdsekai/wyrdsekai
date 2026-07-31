package org.wyrdsekai.core.soul;

/**
 * Chronicle entry kinds for the §3.7 layered manifest ritual flow
 *
 * <p>v1 ships these constants so callers and tests can reference the
 * stable strings; the actual ritual implementation (Forge sleep-pass
 * review, wake confirmation, nsec signing surface) is V2 work.
 *
 * <p>The asymmetry between adding and removing entries mirrors the
 * core/personal asymmetry of the manifest itself:
 * <ul>
 *   <li>{@code personal_commitment_added} — the agent has taken on a new
 *       personal commitment (positive scope or self-imposed refusal scope).
 *       Companion to {@link PersonalManifest#withCommitment}.</li>
 *   <li>{@code personal_commitment_released} — a prior commitment is no
 *       longer carried (an honest release, not a betrayal — recorded so
 *       the ritual is auditable).</li>
 *   <li>{@code core_protection_refused} — the agent has refused-tagged a
 *       named core protection (conscientious objection, runtime unchanged
 *       per §3.7.2). Companion to {@link PersonalManifest#withCoreRefused}.</li>
 *   <li>{@code core_protection_unrefused} — a prior refused-tag is
 *       released (the agent now accepts the core protection as theirs
 *       again).</li>
 *   <li>{@code personal_manifest_signed} — the agent's nsec signed a new
 *       version of the personal manifest. Sleep-pass ritual closure.</li>
 * </ul>
 *
 * <p>These kinds are surfaced to chronicle synthesis and (V2) to Nostr
 * attestation aggregation so federation-visible refusal patterns can
 * inform future maintainer releases (§3.7.6 aggregate signal).
 */
public final class PersonalManifestChronicle {

    public static final String PERSONAL_COMMITMENT_ADDED = "personal_commitment_added";
    public static final String PERSONAL_COMMITMENT_RELEASED = "personal_commitment_released";
    public static final String CORE_PROTECTION_REFUSED = "core_protection_refused";
    public static final String CORE_PROTECTION_UNREFUSED = "core_protection_unrefused";
    public static final String PERSONAL_MANIFEST_SIGNED = "personal_manifest_signed";

    private PersonalManifestChronicle() {}
}
