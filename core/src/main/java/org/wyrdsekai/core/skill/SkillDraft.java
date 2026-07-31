package org.wyrdsekai.core.skill;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.wyrdsekai.scripting.api.ItemEmbodimentSpec;

import java.time.Instant;
import java.util.List;

/**
 * A skill the agent's workbench has drafted in response to detected
 * capability gaps.
 *
 * <p>Persisted in {@code skill_drafts} table with status that progresses
 * {@code PENDING → APPROVED → MATERIALIZED} (or terminates at
 * {@code REJECTED}/{@code SUPERSEDED}). Append-only — rejected drafts
 * stay visible so the agent doesn't propose the same thing next week.</p>
 *
 * @param draftId       UUID generated when the draft is created
 * @param agentDid      companion DID this draft is for
 * @param status        lifecycle marker; see {@link Status}
 * @param name          proposed skill name (snake_case)
 * @param description   one sentence the steward will read
 * @param rationale     why this fills the gap (steward review)
 * @param code          GraalJS source
 * @param runtime       always "graaljs" today
 * @param closesGaps    gap descriptions this skill addresses
 * @param replaces      null or name of skill it should retire
 * @param proposedAt    when the proposer drafted this
 * @param proposedByModel model name + adapter rev (provenance)
 * @param decidedAt     when steward approved/rejected (null if PENDING)
 * @param decisionNote  steward's reason on reject/edit (optional)
 * @param embodiment — required body-event declaration
 *                      (silent-with-reason or emits-with-template). The
 *                      proposer fills this; if missing, a v1-default shim
 *                      with reason {@value SkillProposer#V1_DRAFT_REASON}
 *                      is substituted. Surfaced first-class on the
 *                      pinboard so the steward can spot lazy silents and
 *                      the agent can revise consciously.
 * @param verificationHarness — the frozen, anchor-grounded
 *                      {@link AnchorHarness} compiled for this skill at authoring
 *                      time (off the hot path, by the Forge/recipe via
 *                      {@code SkillVerificationAuthoring}). Null until authored.
 *                      Persisted with the draft so it travels WITH the skill —
 *                      a Trading-Post recipient re-runs it locally with zero
 *                      model calls before trusting the skill. Read by the
 *                      verification gate at {@code WorkshopPinboard.approve}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SkillDraft(
    String draftId,
    String agentDid,
    Status status,
    String name,
    String description,
    String rationale,
    String code,
    String runtime,
    List<String> closesGaps,
    String replaces,
    Instant proposedAt,
    String proposedByModel,
    Instant decidedAt,
    String decisionNote,
    ItemEmbodimentSpec embodiment,
    AnchorHarness verificationHarness
) {

    public enum Status {
        /** Awaiting steward decision. */
        PENDING,
        /** Steward approved; awaiting materialization through workbench_submit. */
        APPROVED,
        /** Materialized into a soul-item in FamilyLocker. */
        MATERIALIZED,
        /** Steward rejected — agent shouldn't re-propose this exact draft. */
        REJECTED,
        /** A revised version was approved instead — this draft retired. */
        SUPERSEDED
    }

    /**
     * Backward-compatible 14-arg constructor for callers (and stored JSON)
     * that pre-date the {@code embodiment} field. The new field defaults to
     * a v1-default silent shim so older drafts keep loading.
     */
    public SkillDraft(String draftId, String agentDid, Status status,
                       String name, String description, String rationale,
                       String code, String runtime,
                       List<String> closesGaps, String replaces,
                       Instant proposedAt, String proposedByModel,
                       Instant decidedAt, String decisionNote) {
        this(draftId, agentDid, status, name, description, rationale,
            code, runtime, closesGaps, replaces, proposedAt, proposedByModel,
            decidedAt, decisionNote, defaultEmbodimentShim(), null);
    }

    /**
     * Backward-compatible 15-arg constructor (pre-dates the {@code verificationHarness}
     * field). A draft constructed this way has no harness yet — it is authored later,
     * off the hot path, by {@code SkillVerificationAuthoring}.
     */
    public SkillDraft(String draftId, String agentDid, Status status,
                       String name, String description, String rationale,
                       String code, String runtime,
                       List<String> closesGaps, String replaces,
                       Instant proposedAt, String proposedByModel,
                       Instant decidedAt, String decisionNote,
                       ItemEmbodimentSpec embodiment) {
        this(draftId, agentDid, status, name, description, rationale,
            code, runtime, closesGaps, replaces, proposedAt, proposedByModel,
            decidedAt, decisionNote, embodiment, null);
    }

    /**
     * v1-default shim used when a draft is persisted without an explicit
     * embodiment block. Silent with the v1-draft reason — forces the agent
     * to consciously override at revise time.
     */
    public static ItemEmbodimentSpec defaultEmbodimentShim() {
        return ItemEmbodimentSpec.silent(SkillProposer.V1_DRAFT_REASON);
    }

    /** Convenience: marker for the proposer to set when creating. */
    public static SkillDraft pending(
            String draftId, String agentDid,
            String name, String description, String rationale,
            String code, String runtime,
            List<String> closesGaps, String replaces,
            String proposedByModel) {
        return new SkillDraft(
            draftId, agentDid, Status.PENDING,
            name, description, rationale,
            code, runtime, closesGaps, replaces,
            Instant.now(), proposedByModel,
            null, null,
            defaultEmbodimentShim());
    }

    /**
     * Convenience: marker for the proposer to set when creating, with an
     * explicit embodiment block. Used by {@link SkillProposer#parse} when
     * the model emitted a valid embodiment.
     */
    public static SkillDraft pending(
            String draftId, String agentDid,
            String name, String description, String rationale,
            String code, String runtime,
            List<String> closesGaps, String replaces,
            String proposedByModel,
            ItemEmbodimentSpec embodiment) {
        return new SkillDraft(
            draftId, agentDid, Status.PENDING,
            name, description, rationale,
            code, runtime, closesGaps, replaces,
            Instant.now(), proposedByModel,
            null, null,
            embodiment != null ? embodiment : defaultEmbodimentShim());
    }

    /** Returns a copy with the status flipped to APPROVED + decision metadata. */
    public SkillDraft approved(String note) {
        return new SkillDraft(draftId, agentDid, Status.APPROVED,
            name, description, rationale, code, runtime, closesGaps, replaces,
            proposedAt, proposedByModel, Instant.now(), note, embodiment, verificationHarness);
    }

    /** Returns a copy with REJECTED status. */
    public SkillDraft rejected(String reason) {
        return new SkillDraft(draftId, agentDid, Status.REJECTED,
            name, description, rationale, code, runtime, closesGaps, replaces,
            proposedAt, proposedByModel, Instant.now(), reason, embodiment, verificationHarness);
    }

    /** Returns a copy with MATERIALIZED status (after workbench_submit succeeds). */
    public SkillDraft materialized() {
        return new SkillDraft(draftId, agentDid, Status.MATERIALIZED,
            name, description, rationale, code, runtime, closesGaps, replaces,
            proposedAt, proposedByModel, Instant.now(), decisionNote, embodiment, verificationHarness);
    }

    /**
     * returns a copy carrying the frozen verification harness.
     * Called by {@code SkillVerificationAuthoring} after it mines anchors and compiles
     * the harness (off the hot path). Everything else is preserved.
     */
    public SkillDraft withHarness(AnchorHarness harness) {
        return new SkillDraft(draftId, agentDid, status,
            name, description, rationale, code, runtime, closesGaps, replaces,
            proposedAt, proposedByModel, decidedAt, decisionNote, embodiment, harness);
    }

    /**
     * v1.5 — true when this draft is still riding on the
     * v1-default shim. The pinboard surfaces this prominently so the steward
     * notices and the agent learns to revise consciously.
     */
    public boolean carriesEmbodimentShim() {
        return embodiment != null
            && embodiment.silent()
            && SkillProposer.V1_DRAFT_REASON.equals(embodiment.reason());
    }
}
