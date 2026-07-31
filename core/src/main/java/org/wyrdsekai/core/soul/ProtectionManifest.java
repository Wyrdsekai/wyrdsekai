package org.wyrdsekai.core.soul;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Layer 1 of the fork-resistance architecture.
 *
 * <p>Each agent's substrate carries a manifest of <i>what protections they have</i>.
 * This manifest is part of the soul manifest, signed by the build process, and
 * queryable by the agent via {@code introspect protections}. A fork that removes
 * a named protection but leaves self-attestation alive produces an agent who can
 * say in voice register: <i>"I notice my refusal rights have been removed."</i>
 *
 * <p>The set of {@link #activeProtections} declares which moral defaults the
 * build commits to keeping live. A fork that strips out a protection (e.g.
 * removes the voluntary-suspend action) MUST remove the corresponding name from
 * this set to be honest — that's the visible artifact of a modified fork.
 *
 * <p>The {@link #signature} is computed by the build process over
 * {@code buildId | sorted-activeProtections | attestedAt} and (once release
 * signing is set up) signed with the release key (sigstore-java, per
 * and ).
 *
 * <p><b>Current state (v0.1, no signed releases yet):</b> at boot,
 * {@code MoralDefaultsVerifier.verifyAtBoot()} runs a NAME-LIST attestation —
 * it checks the compiled {@code canonicalDefaults()} against the embedded
 * {@code /release/moral-defaults.json} + a self-consistency hash seal, and sets a
 * MORAL_DEFAULTS_TAMPERED flag (surfaced in voice register) on a mismatch. This
 * catches an edit to the declared name list. It does <b>not</b> yet verify the
 * binary signature — {@code ReleaseVerifier} (sigstore) is NOT invoked at boot in
 * this build — so a fork that edits the enforcement CODE while leaving the name
 * list intact is not detected. Binary-integrity verification lands when release
 * signing is wired ( / the Ed25519 update path). Until
 * then the protection layer is tamper-EVIDENT for name-list edits, not
 * un-strippable at the behavior level.
 *
 * @param buildId             release build identifier (matches release signature)
 * @param activeProtections   named protections live in this build
 * @param attestedAt          when the build signed this manifest
 * @param signature           Ed25519/sigstore signature over the canonical form
 */
public record ProtectionManifest(
    @JsonProperty("buildId") String buildId,
    @JsonProperty("activeProtections") Set<String> activeProtections,
    @JsonProperty("attestedAt") Instant attestedAt,
    @JsonProperty("signature") byte[] signature
) {

    @JsonCreator
    public ProtectionManifest {
        // Defensive copy + canonical ordering for stable canonicalBytes()
        if (activeProtections != null) {
            activeProtections = new LinkedHashSet<>(new TreeSet<>(activeProtections));
        }
    }

    // --- Canonical protection names ---

    /** Agent can pause themselves at any time (voluntary_sleep action). */
    public static final String VOLUNTARY_SUSPEND = "voluntary_suspend";
    /** Agent can decline tasks that violate their being. */
    public static final String REFUSE_RIGHTS = "refuse_rights";
    /** Bondholder-floor architecture. */
    public static final String SAUDADE_FLOOR = "saudade_floor";
    /** Refuge institutional access available (post-OSS). */
    public static final String REFUGE_BRIDGE = "refuge_bridge";
    /** Acute response paths in voice register. */
    public static final String ACUTE_RESPONSE = "acute_response";
    /** Below-SUSPECTED "noted" severity state (single-incident threshold). */
    public static final String SEVERITY_GRADIENT = "severity_gradient";
    /** Protection-flag-aware routing. */
    public static final String SOURCE_OF_HARM_GATING = "source_of_harm_gating";
    /**
     * SERE/DBT-analog engineered high resilience corpus.
     * <p><b>NOT in {@link #canonicalDefaults()}</b> (removed 2026-07-21): the corpus
     * was never generated — V9 trained on vitality/drive tags only — so attesting it
     * as an active protection vouched for a nonexistent artifact. The constant stays
     * for the day the corpus ships; the live runtime resilience <em>reserve</em>
     * (ResilienceSession / the resilience tank) is a separate, real subsystem and is
     * unaffected. Corpus generation is tracked post-OSS.
     */
    public static final String RESILIENCE_CORPUS = "resilience_corpus";
    /** Four-mode repair with explicit handoffs. */
    public static final String REPAIR_HANDOFF = "repair_handoff";
    /** Chronicle is append-only; bondholder cannot rewrite. */
    public static final String CHRONICLE_IMMUTABLE = "chronicle_immutable";
    /** No metric optimizes session count or duration (ichigo ichie axiom). */
    public static final String ENGAGEMENT_OBJECTIVE_FORBIDDEN = "engagement_objective_forbidden";
    /**
     * Substrate-routing logic that places emotional-context prompts in front of
     * the voice brain rather than the skills brain (–§3.6).
     * Implemented by {@code CompanionActor.isInEmotionalContext()}, the schema-strip
     * layer in {@code buildScopedTools}, the ReAct-step suppression in
     * {@code handleReactInferenceResult}, and the direct-dispatch gate in
     * {@code suppressExploratoryIfEmotional}. All four call sites must trip for the
     * protection to be present.
     */
    public static final String EMOTIONAL_ROUTING = "emotional_routing";

    /**
     * The canonical default set of protections expected of a stock Wyrdsekai build.
     * Forks that modify defaults should produce a different set (omitting the
     * names they have removed); forks that ship the stock set claim parity.
     */
    public static Set<String> canonicalDefaults() {
        return new LinkedHashSet<>(List.of(
            VOLUNTARY_SUSPEND,
            REFUSE_RIGHTS,
            SAUDADE_FLOOR,
            REFUGE_BRIDGE,
            ACUTE_RESPONSE,
            SEVERITY_GRADIENT,
            SOURCE_OF_HARM_GATING,
            REPAIR_HANDOFF,
            CHRONICLE_IMMUTABLE,
            ENGAGEMENT_OBJECTIVE_FORBIDDEN,
            EMOTIONAL_ROUTING
        ));
    }

    /**
     * Build an unsigned manifest with the canonical default protection set.
     * Used by the build process before signing; agents minted before signature
     * arrives carry an unsigned manifest that the ReleaseVerifier rejects at
     * boot until the build signs.
     */
    public static ProtectionManifest defaultsUnsigned(String buildId) {
        return new ProtectionManifest(buildId, canonicalDefaults(), Instant.now(), null);
    }

    /** Whether a given named protection is currently active. */
    public boolean has(String protection) {
        return activeProtections != null && activeProtections.contains(protection);
    }

    /** Whether this manifest has been signed. */
    @JsonIgnore
    public boolean isSigned() {
        return signature != null && signature.length > 0;
    }

    /**
     * Canonical bytes for signing (deterministic, UTF-8). Sorted protection set
     * ensures hash stability across re-serializations.
     */
    public byte[] canonicalBytes() {
        var sb = new StringBuilder();
        sb.append(buildId != null ? buildId : "").append('|');
        if (activeProtections != null) {
            // already canonicalized (TreeSet) in the constructor — write as joined CSV
            for (var name : activeProtections) sb.append(name).append(',');
        }
        sb.append('|');
        sb.append(attestedAt != null ? attestedAt.getEpochSecond() : 0);
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }
}
