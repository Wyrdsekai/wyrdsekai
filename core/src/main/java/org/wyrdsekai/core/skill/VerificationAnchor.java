package org.wyrdsekai.core.skill;

import org.wyrdsekai.core.library.Provenance;

/**
 * (authoring time, stage 1) — an independently-verifiable fact mined from
 * the open world, used to ground a {@link AnchorHarness} so the harness tests the skill against
 * <b>external evidence</b> rather than against the model's own (possibly wrong) belief.
 *
 * <p>This is OpenSkill's "verification anchor": a documented reference value, an I/O format, an
 * invariant, or a value cross-checked across sources. The <b>leakage barrier</b> is that every
 * anchor carries the {@link Provenance.Source} it was grounded in — an anchor with no source is
 * not an anchor, it is the model guessing, and {@code AnchorMiner} drops it.</p>
 *
 * <p>An anchor is NOT the answer to the task the skill will ultimately be judged on; it is the
 * independent ground truth a correct skill must already agree with (water boils at 100&deg;C,
 * a sort is monotonic, an ISO-8601 date matches a documented pattern).</p>
 */
public record VerificationAnchor(
    /** human-readable, independently-verifiable fact (e.g. "water boils at 100C = 212F"). */
    String fact,
    AnchorKind kind,
    /** where the fact came from — the leakage barrier. Never null for a kept anchor. */
    Provenance.Source source,
    /** trust tier of that source (drives whether the anchor is auto-trusted vs. flagged). */
    Provenance.TrustTier trustTier
) {

    /** What kind of ground truth this anchor expresses. */
    public enum AnchorKind {
        /** a documented numeric or string reference value (a physical constant, a known result). */
        REFERENCE_VALUE,
        /** a documented input/output shape or format (ISO-8601, RFC-4122 UUID, JSON envelope). */
        IO_FORMAT,
        /** a property the output must always satisfy (monotonic, non-empty, idempotent). */
        INVARIANT,
        /** a value cross-checked against a second independent source. */
        CROSS_VALIDATION
    }

    /** True when this anchor is grounded in a real source (passed the leakage barrier). */
    public boolean isGrounded() {
        return source != null && (source.url() != null || source.ref() != null || source.title() != null);
    }
}
