package org.wyrdsekai.core.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.util.Json;
import org.wyrdsekai.core.companion.PersonalProjectStore;
import org.wyrdsekai.core.soul.FamilyLocker;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Renders the Workshop's "workbench draft pinboard" furnishing and
 * carries out steward decisions (approve / reject) on
 * {@link SkillDraft}s. –§6.
 *
 * <p>Pure utility — actor / route layers call into here; the heavy
 * choreography (FamilyLocker storage, WorkbenchSkillExecutor
 * registration) is delegated to {@link Materializer} so callers can
 * inject the same code path the {@code workbench_submit} action uses
 * today (§6 — "no new bypass").</p>
 */
public final class WorkshopPinboard {

    private static final Logger log = LoggerFactory.getLogger(WorkshopPinboard.class);

    private final SkillDraftStore store;
    private final SkillGate gate;

    public WorkshopPinboard(SkillDraftStore store) {
        this(store, null);
    }

    /**
     * With a verification gate run in {@link #approve} before
     * materialization. A {@code null} gate is the pre-verifier behaviour.
     */
    public WorkshopPinboard(SkillDraftStore store, SkillGate gate) {
        this.store = store;
        this.gate = gate;
    }

    /** Convenience static accessor backed by the store singleton. */
    public static WorkshopPinboard get() {
        var s = SkillDraftStore.get();
        return s == null ? null : new WorkshopPinboard(s);
    }

    /** List pending drafts for the steward at this workbench. */
    public List<SkillDraft> pending(String agentDid) {
        return store.byAgentAndStatus(agentDid, SkillDraft.Status.PENDING);
    }

    /** "look at draft pinboard" — overview of pending drafts. */
    public String renderLook(String agentDid, String stewardName) {
        var drafts = pending(agentDid);
        if (drafts.isEmpty()) {
            return "The pinboard is empty. No drafts await your decision.";
        }
        var sb = new StringBuilder();
        var who = stewardName != null && !stewardName.isBlank() ? stewardName : "the workbench";
        sb.append("The pinboard holds ").append(drafts.size())
          .append(drafts.size() == 1 ? " pending draft" : " pending drafts")
          .append(" pinned by ").append(who).append(":\n\n");
        for (int i = 0; i < drafts.size(); i++) {
            var d = drafts.get(i);
            sb.append("  ").append(i + 1).append(". ").append(d.name())
              .append(" (proposed ").append(humanAge(d.proposedAt())).append(")\n");
            if (d.description() != null && !d.description().isBlank()) {
                sb.append("     \"").append(d.description()).append("\"\n");
            }
            if (d.rationale() != null && !d.rationale().isBlank()) {
                sb.append("     Why: ").append(d.rationale()).append("\n");
            }
            // v1.5 — surface embodiment as a first-class
            // line so the steward catches v1-shim drafts and the agent
            // sees that this field matters at review time. Cannot be
            // silently elided from the pinboard.
            sb.append("     Embodiment: ").append(renderEmbodimentSummary(d)).append("\n");
        }
        return sb.toString().stripTrailing();
    }

    /**
     * v1.5 — one-line embodiment summary for the
     * pinboard. Flags v1-draft shims so the steward (and the agent
     * reading her own pinboard) notice the omission.
     */
    public static String renderEmbodimentSummary(SkillDraft draft) {
        if (draft == null || draft.embodiment() == null) {
            return "(none declared — an embodiment block is required; this draft "
                + "should not have been accepted)";
        }
        var emb = draft.embodiment();
        if (draft.carriesEmbodimentShim()) {
            return "silent (v1-draft shim: \"" + emb.reason()
                + "\" — replace before materialize)";
        }
        if (emb.silent()) {
            return "silent — reason: \"" + emb.reason() + "\"";
        }
        var emits = emb.emits() == null || emb.emits().isEmpty()
            ? "(none)" : String.join(", ", emb.emits());
        var template = emb.descriptorTemplate();
        if (template == null || template.isBlank()) {
            return "emits [" + emits + "] (no descriptor template)";
        }
        return "emits [" + emits + "] — \"" + template + "\"";
    }

    /**
     * c — render a "today's drafts" section listing
     * {@code craft.script_draft} entries from the last 24h. Pulls from a
     * {@link org.wyrdsekai.core.companion.PersonalProjectStore} (passed in
     * to keep this method actor-thread-safe — the store is already
     * companion-scoped).
     *
     * <p>Returns an empty string if no draft project exists, no entries
     * fall in the last 24h, or the store is null. Intended as an
     * append-to-{@link #renderLook} surface so the model sees recurring
     * improvisations alongside pending skill drafts.
     */
    public String renderTodaysDrafts(
            PersonalProjectStore store) {
        if (store == null) return "";
        try {
            var draftProject = store.list().stream()
                .filter(p -> p.tags() != null
                    && p.tags().contains("craft.script_draft"))
                .findFirst()
                .orElse(null);
            if (draftProject == null || draftProject.entries() == null
                    || draftProject.entries().isEmpty()) {
                return "";
            }
            var cutoff = Instant.now().minus(Duration.ofHours(24));
            var recent = draftProject.entries().stream()
                .filter(e -> e.at() != null && e.at().isAfter(cutoff))
                .toList();
            if (recent.isEmpty()) return "";

            var sb = new StringBuilder();
            sb.append("Today's improvised drafts (last 24h, ")
              .append(recent.size())
              .append(recent.size() == 1 ? " entry" : " entries")
              .append("):\n");
            for (int i = 0; i < recent.size(); i++) {
                var e = recent.get(i);
                var summary = summariseEntry(e.text());
                sb.append("  • ").append(humanAge(e.at()));
                if (summary != null && !summary.isBlank()) {
                    sb.append(" — ").append(summary);
                }
                sb.append('\n');
            }
            return sb.toString().stripTrailing();
        } catch (Exception ex) {
            log.debug("renderTodaysDrafts failed: {}", ex.getMessage());
            return "";
        }
    }

    /**
     * Best-effort one-line summary of a journalled draft entry. Entries are
     * JSON blobs (see CompanionActor.journalScriptDraft) — pick out the
     * fields most useful at a glance: tier, ok flag, summary, error.
     */
    static String summariseEntry(String entryText) {
        if (entryText == null || entryText.isBlank()) return "";
        try {
            var node = Json.mapper().readTree(entryText);
            var sb = new StringBuilder();
            if (node.has("tier")) {
                sb.append('[').append(node.get("tier").asText()).append("] ");
            }
            if (node.has("summary")) {
                sb.append(node.get("summary").asText());
            } else if (node.has("error")) {
                sb.append("err: ").append(node.get("error").asText());
            }
            return sb.toString().trim();
        } catch (Exception e) {
            // Not JSON or unparseable — return a truncated raw form.
            return entryText.length() > 80 ? entryText.substring(0, 80) + "…" : entryText;
        }
    }

    /**
     * "examine draft N" (1-based). Returns the full code + rationale
     * or a {@code null} optional if the index is out of range.
     */
    public Optional<String> renderExamine(String agentDid, int oneBasedIndex) {
        var drafts = pending(agentDid);
        if (oneBasedIndex < 1 || oneBasedIndex > drafts.size()) return Optional.empty();
        var d = drafts.get(oneBasedIndex - 1);
        var sb = new StringBuilder();
        sb.append("Draft #").append(oneBasedIndex).append(": ").append(d.name()).append("\n");
        sb.append("Description: ").append(d.description()).append("\n");
        sb.append("Why: ").append(d.rationale()).append("\n");
        if (d.replaces() != null) sb.append("Replaces: ").append(d.replaces()).append("\n");
        if (d.closesGaps() != null && !d.closesGaps().isEmpty()) {
            sb.append("Closes gaps:\n");
            for (var g : d.closesGaps()) sb.append("  - ").append(g).append("\n");
        }
        sb.append("Runtime: ").append(d.runtime()).append("\n");
        sb.append("Proposed by: ").append(d.proposedByModel()).append("\n");
        // v1.5 — verbose embodiment block on examine
        // surfaced before the code so the reviewer reads the contract
        // before the implementation. Cannot be silently elided.
        sb.append("Embodiment: ").append(renderEmbodimentSummary(d)).append("\n");
        sb.append("\n--- code ---\n").append(d.code()).append("\n");
        return Optional.of(sb.toString());
    }

    /**
     * Approve a pending draft (status flips PENDING → APPROVED → MATERIALIZED
     * once the materializer succeeds). Returns the materialized draft or
     * an explanation in {@link Decision#message}.
     */
    public Decision approve(String agentDid, int oneBasedIndex, String note,
                             Materializer materializer) {
        var drafts = pending(agentDid);
        if (oneBasedIndex < 1 || oneBasedIndex > drafts.size()) {
            return Decision.failure("No pending draft at #" + oneBasedIndex);
        }
        var draft = drafts.get(oneBasedIndex - 1);

        // Re-validate: if the world changed (e.g. the validator got stricter),
        // bail rather than materialize broken code.
        var validation = WorkbenchValidator.validate(
            draft.name(), draft.runtime(), draft.code(), List.of());
        if (!validation.valid()) {
            return Decision.failure(
                "The workbench rejected this draft on re-validation: " + validation.summary());
        }

        // run the deterministic verification gate (if wired)
        // before adopting the draft. A blocked draft stays PENDING so the steward/
        // agent can revise rather than ship a skill that fails its anchors.
        if (gate != null) {
            var block = gate.check(draft);
            if (block.isPresent()) {
                log.info("WorkshopPinboard: verification gate blocked '{}' for {}: {}",
                    draft.name(), agentDid, block.get());
                return Decision.failure(
                    "Verification gate blocked '" + draft.name() + "': " + block.get());
            }
        }

        // Flip to APPROVED + persist before materialization so a crash leaves
        // a recoverable trail.
        var approved = draft.approved(note);
        store.upsert(approved);

        try {
            if (materializer != null) {
                materializer.materialize(approved);
            }
            store.upsert(approved.materialized());
            log.info("WorkshopPinboard: materialized draft '{}' for {}", draft.name(), agentDid);
            return Decision.success(approved.materialized(),
                "Forged '" + draft.name() + "'. It's ready to use.");
        } catch (Exception e) {
            log.warn("WorkshopPinboard: materialization failed for '{}': {}",
                draft.name(), e.getMessage());
            return Decision.failure(
                "The workbench accepted '" + draft.name() + "' but couldn't seat it: "
                + e.getMessage());
        }
    }

    /** Reject a pending draft. Reason gets fed back so the agent can learn. */
    public Decision reject(String agentDid, int oneBasedIndex, String reason) {
        var drafts = pending(agentDid);
        if (oneBasedIndex < 1 || oneBasedIndex > drafts.size()) {
            return Decision.failure("No pending draft at #" + oneBasedIndex);
        }
        var draft = drafts.get(oneBasedIndex - 1);
        var rejected = draft.rejected(reason == null ? "" : reason);
        store.upsert(rejected);
        return Decision.success(rejected, "Rejected '" + draft.name() + "'.");
    }

    /**
     * Edit a pending draft in place (steward replaces the code). The
     * old draft is SUPERSEDED, a new APPROVED draft is created, and
     * materialization runs.
     */
    public Decision editAndApprove(String agentDid, int oneBasedIndex,
                                    String newCode, String editNote,
                                    Materializer materializer) {
        var drafts = pending(agentDid);
        if (oneBasedIndex < 1 || oneBasedIndex > drafts.size()) {
            return Decision.failure("No pending draft at #" + oneBasedIndex);
        }
        var prior = drafts.get(oneBasedIndex - 1);
        if (newCode == null || newCode.isBlank()) {
            return Decision.failure("Edit requires non-empty code.");
        }
        var validation = WorkbenchValidator.validate(
            prior.name(), prior.runtime(), newCode, List.of());
        if (!validation.valid()) {
            return Decision.failure("Edited code rejected: " + validation.summary());
        }

        var supersededPrior = new SkillDraft(
            prior.draftId(), prior.agentDid(), SkillDraft.Status.SUPERSEDED,
            prior.name(), prior.description(), prior.rationale(),
            prior.code(), prior.runtime(), prior.closesGaps(), prior.replaces(),
            prior.proposedAt(), prior.proposedByModel(),
            Instant.now(), "edited by steward: " + (editNote == null ? "" : editNote));
        store.upsert(supersededPrior);

        var revised = SkillDraft.pending(
            UUID.randomUUID().toString(),
            prior.agentDid(),
            prior.name(), prior.description(),
            "Edited by steward. " + (editNote == null ? "" : editNote),
            newCode, prior.runtime(),
            prior.closesGaps(), prior.replaces(),
            prior.proposedByModel());
        var approved = revised.approved(editNote);
        store.upsert(approved);

        try {
            if (materializer != null) materializer.materialize(approved);
            store.upsert(approved.materialized());
            return Decision.success(approved.materialized(),
                "Forged edited '" + approved.name() + "'.");
        } catch (Exception e) {
            return Decision.failure(
                "Edit accepted but materialization failed: " + e.getMessage());
        }
    }

    // ── Materialization SPI ──────────────────────────────────────────────

    /**
     * Plug-point for the host that knows how to seat an approved draft as
     * a soul-item. The default {@link DefaultMaterializer} mirrors the
     * existing {@code workbench_submit} code path in {@code CompanionActor}.
     */
    public interface Materializer {
        void materialize(SkillDraft approved) throws Exception;
    }

    /**
     * Default materializer — packages the draft via {@link SkillItemCodec},
     * tombstones any existing skill with the same label in FamilyLocker,
     * stores the new SoulItem, and registers with the executor. Mirrors
     * {@code CompanionActor.handleWorkbenchSubmit} so we go through the
     * same code path (§6 "no new bypass").
     */
    public static final class DefaultMaterializer implements Materializer {
        private final FamilyLocker locker;
        private final WorkbenchSkillExecutor executor;
        private final String requesterDid;

        public DefaultMaterializer(FamilyLocker locker,
                                    WorkbenchSkillExecutor executor,
                                    String requesterDid) {
            this.locker = locker;
            this.executor = executor;
            this.requesterDid = requesterDid;
        }

        @Override
        public void materialize(SkillDraft approved) {
            var def = SkillItemCodec.create(
                approved.runtime(), approved.code(), null,
                approved.description(), null, null);
            var item = SkillItemCodec.toSoulItem(approved.name(), def, requesterDid);

            if (locker != null && requesterDid != null) {
                try {
                    var existing = locker.byCategory("skill", requesterDid);
                    for (var e : existing) {
                        if (approved.name().equals(e.label())) {
                            locker.tombstone(e.hash(), requesterDid,
                                "superseded by materialized draft");
                        }
                    }
                    locker.store(item, requesterDid);
                } catch (Exception ex) {
                    log.warn("DefaultMaterializer: locker store failed: {}",
                        ex.getMessage());
                    throw ex;
                }
            }
            if (executor != null) {
                executor.register(approved.name(), item, def);
            }
        }
    }

    public record Decision(boolean ok, String message, SkillDraft draft) {
        public static Decision success(SkillDraft d, String msg) {
            return new Decision(true, msg, d);
        }
        public static Decision failure(String msg) {
            return new Decision(false, msg, null);
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────

    static String humanAge(Instant t) {
        if (t == null) return "?";
        var d = Duration.between(t, Instant.now());
        if (d.isNegative()) d = Duration.ZERO;
        long s = d.toSeconds();
        if (s < 60) return s + "s ago";
        if (s < 3600) return (s / 60) + "m ago";
        if (s < 86400) return (s / 3600) + "h ago";
        return (s / 86400) + "d ago";
    }
}
