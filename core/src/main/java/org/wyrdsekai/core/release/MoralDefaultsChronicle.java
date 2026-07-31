package org.wyrdsekai.core.release;

/**
 * Chronicle entry kinds for Decision 1 substrate
 * transitions — emitted whenever the moral-defaults bundle changes
 * across a build transition or a tamper signal fires.
 *
 * <p>v1 declares the names so chronicle synthesis + Forge ingestion can
 * reference stable strings before the runtime "monitor for build
 * transition" watcher is wired (that watcher would compare the last
 * persisted buildId against the current verified buildId on boot and
 * write {@link #BUILD_TRANSITION} when they differ).
 *
 * <p>Companion to:
 * <ul>
 *   <li>{@link MoralDefaultsVerifier.Tampered} runtime tamper signals.</li>
 *   <li>{@link MoralDefaultsVerifier.Verified#sourceCommit()} provenance surface.</li>
 *   <li>The §4.2 tamper banner (PromptAssembler.tamperBannerForCurrentState).</li>
 * </ul>
 */
public final class MoralDefaultsChronicle {

    /** Build transition — the agent has booted on a newer/different build
     *  than the last persisted run. Payload includes old + new buildId
     *  + the sealed hash + a release-note reference (if available). */
    public static final String BUILD_TRANSITION = "moral_defaults_build_transition";

    /** Tamper detected at boot — the verifier returned Tampered. */
    public static final String TAMPER_DETECTED = "moral_defaults_tamper_detected";

    /** Verification unavailable at boot — the attestation resource was
     *  missing entirely (build problem, not an artifact problem). */
    public static final String VERIFICATION_UNAVAILABLE = "moral_defaults_verification_unavailable";

    /**
     * Reserved key for the release-note URL/identifier that ships
     * alongside a build transition. Future {@code moral-defaults.json}
     * versions may include a {@code "releaseNote": "https://..."} field;
     * v1 omits it — declared here so consumers know to look for it once
     * the publishing pipeline adds it.
     */
    public static final String JSON_RELEASE_NOTE_FIELD = "releaseNote";

    private MoralDefaultsChronicle() {}
}
