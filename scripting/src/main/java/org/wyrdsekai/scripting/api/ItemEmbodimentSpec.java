package org.wyrdsekai.scripting.api;

import java.time.Instant;
import java.util.List;

/**
 * authoring obligation for scripted items.
 *
 * <p>Every item manifest MUST declare an embodiment block. Silence must be a
 * <i>declared</i> choice, not a default. In the physical world all things
 * have resident attributes that can be observed; if we make an item silent
 * in Wyrdsekai it has to be intentional and the reason has to be written.
 *
 * <p>Two valid shapes:
 * <pre>
 *   "embodiment": { "silent": true, "reason": "&lt;brief justification&gt;" }
 *   "embodiment": { "emits": ["posture_change", "body_language"],
 *                   "descriptor_template": "..." }
 * </pre>
 *
 * <p>{@link #migration} is non-null on legacy items the boot pass tagged
 * automatically. Such manifests load NOW so deploy doesn't fail, but they
 * are listed in {@code data/manifest_audit.json} for review, and the next
 * manual touch of the manifest MUST replace the shim with a real declaration.
 *
 * @param silent              when true, item emits no body events.
 * @param reason              required on silent=true and on migration shims.
 * @param emits               event kinds the item produces (e.g. "posture_change").
 * @param descriptorTemplate  optional template for narration ("{actor} settles…").
 * @param migration           non-null when this is a v1-default boot shim.
 */
public record ItemEmbodimentSpec(
    boolean silent,
    String reason,
    List<String> emits,
    String descriptorTemplate,
    MigrationShim migration
) {

    /** Stable id for the auto-migration version. */
    public static final String MIGRATION_VERSION = "embodiment-v1";

    /** Reason text stamped on migration-shimmed manifests. */
    public static final String MIGRATION_REASON = "v1-default-pending-author-review";

    /**
     * Stamp recorded on items tagged by the boot migration pass. Presence
     * means: "this manifest was loaded under the v1 default; replace before
     * editing." Audit JSON enumerates all migration-shimmed items so the
     * steward can plan the fixup.
     */
    public record MigrationShim(String version, Instant at) {
        public static MigrationShim now() {
            return new MigrationShim(MIGRATION_VERSION, Instant.now());
        }
    }

    public ItemEmbodimentSpec {
        emits = emits == null ? List.of() : List.copyOf(emits);
    }

    /** Construct the migration shim shape (silent=true with the v1 reason). */
    public static ItemEmbodimentSpec migrationShim() {
        return new ItemEmbodimentSpec(
            true, MIGRATION_REASON, List.of(), null, MigrationShim.now());
    }

    /** Construct a silent declaration (no events; reason required). */
    public static ItemEmbodimentSpec silent(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("silent embodiment requires a reason");
        }
        return new ItemEmbodimentSpec(true, reason, List.of(), null, null);
    }

    /** Construct an emitting declaration. */
    public static ItemEmbodimentSpec emits(List<String> emits, String descriptorTemplate) {
        if (emits == null || emits.isEmpty()) {
            throw new IllegalArgumentException("emitting embodiment requires a non-empty emits list");
        }
        return new ItemEmbodimentSpec(false, null, emits, descriptorTemplate, null);
    }

    /** Whether this spec was produced by the boot-time auto-migration pass. */
    public boolean isMigrated() {
        return migration != null;
    }

    /** Whether this declaration is structurally valid per §18. */
    public boolean isValid() {
        if (silent) return reason != null && !reason.isBlank();
        return emits != null && !emits.isEmpty();
    }
}
