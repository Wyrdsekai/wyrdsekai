package org.wyrdsekai.core.skill;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.persistence.SchemaInitializer;

import java.time.Instant;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** –§6 — pinboard look/examine/approve/reject/edit. */
class WorkshopPinboardTest {

    private SkillDraftStore store;
    private WorkshopPinboard board;

    @BeforeEach
    void setup(@TempDir Path tmp) {
        var jdbc = SchemaInitializer.initialize(tmp.resolve("drafts.db"));
        store = new SkillDraftStore(jdbc);
        board = new WorkshopPinboard(store);
    }

    @AfterEach
    void cleanup() { SkillDraftStore.resetForTests(); }

    private SkillDraft seed(String name) {
        var d = SkillDraft.pending(
            UUID.randomUUID().toString(), "did:wyrd:wyrd",
            name,
            "Compress a directory into .tar.gz.",
            "Steward asked 5x and the agent failed.",
            "function execute(p) { return p; }",
            "graaljs",
            List.of("couldn't compress folder"),
            null,
            "9b@rev-12");
        store.upsert(d);
        return d;
    }

    @Test
    void renderLook_lists_pending_drafts_with_index() {
        seed("compress_archive");
        seed("split_pdf");
        var text = board.renderLook("did:wyrd:wyrd", "Wyrd");
        assertThat(text).contains("2 pending drafts pinned by Wyrd");
        assertThat(text).contains("compress_archive");
        assertThat(text).contains("split_pdf");
        assertThat(text).contains("1.").contains("2.");
    }

    @Test
    void renderLook_returns_empty_message_when_nothing_pending() {
        var text = board.renderLook("did:wyrd:wyrd", null);
        assertThat(text).contains("empty");
    }

    @Test
    void renderExamine_shows_full_code_and_rationale() {
        seed("compress_archive");
        var examined = board.renderExamine("did:wyrd:wyrd", 1).orElseThrow();
        assertThat(examined).contains("compress_archive");
        assertThat(examined).contains("function execute");
        assertThat(examined).contains("Steward asked 5x");
        assertThat(examined).contains("Closes gaps:");
    }

    @Test
    void renderExamine_returns_empty_for_out_of_range_index() {
        seed("only_one");
        assertThat(board.renderExamine("did:wyrd:wyrd", 0)).isEmpty();
        assertThat(board.renderExamine("did:wyrd:wyrd", 2)).isEmpty();
    }

    @Test
    void approve_runs_materializer_and_flips_status_to_materialized() {
        var d = seed("compress_archive");
        var seen = new ArrayList<SkillDraft>();
        WorkshopPinboard.Materializer mat = approved -> seen.add(approved);

        var decision = board.approve("did:wyrd:wyrd", 1, "looks good", mat);

        assertThat(decision.ok()).isTrue();
        assertThat(seen).hasSize(1);
        assertThat(seen.get(0).status()).isEqualTo(SkillDraft.Status.APPROVED);
        // Stored draft has flipped to MATERIALIZED.
        var stored = store.get(d.draftId()).orElseThrow();
        assertThat(stored.status()).isEqualTo(SkillDraft.Status.MATERIALIZED);
    }

    @Test
    void approve_failure_in_materializer_is_surfaced_in_decision() {
        var d = seed("compress_archive");
        WorkshopPinboard.Materializer mat = approved -> {
            throw new RuntimeException("locker offline");
        };

        var decision = board.approve("did:wyrd:wyrd", 1, "ship", mat);
        assertThat(decision.ok()).isFalse();
        assertThat(decision.message()).contains("locker offline");
        // Draft stays at APPROVED so the steward can retry once the locker is back.
        var stored = store.get(d.draftId()).orElseThrow();
        assertThat(stored.status()).isEqualTo(SkillDraft.Status.APPROVED);
    }

    @Test
    void approve_blocks_re_validation_failures() {
        // Bypass the parser-side validation by writing an invalid draft directly.
        var bad = new SkillDraft(
            UUID.randomUUID().toString(), "did:wyrd:wyrd", SkillDraft.Status.PENDING,
            "bad_skill", "x", "x",
            "var x = 1;", // No `function execute` — fails validator.
            "graaljs", List.of("x"), null,
            Instant.now(), "test", null, null);
        store.upsert(bad);

        var ran = new AtomicReference<Boolean>(false);
        WorkshopPinboard.Materializer mat = approved -> ran.set(true);

        var decision = board.approve("did:wyrd:wyrd", 1, "ship", mat);
        assertThat(decision.ok()).isFalse();
        assertThat(decision.message()).contains("re-validation");
        assertThat(ran.get()).isFalse();
        // Untouched.
        var stored = store.get(bad.draftId()).orElseThrow();
        assertThat(stored.status()).isEqualTo(SkillDraft.Status.PENDING);
    }

    @Test
    void reject_persists_decision_note() {
        var d = seed("compress_archive");
        var decision = board.reject("did:wyrd:wyrd", 1, "uses unsafe network calls");
        assertThat(decision.ok()).isTrue();
        var stored = store.get(d.draftId()).orElseThrow();
        assertThat(stored.status()).isEqualTo(SkillDraft.Status.REJECTED);
        assertThat(stored.decisionNote()).contains("unsafe network");
    }

    @Test
    void editAndApprove_supersedes_prior_and_creates_new_approved() {
        var prior = seed("compress_archive");
        var seen = new ArrayList<SkillDraft>();
        WorkshopPinboard.Materializer mat = approved -> seen.add(approved);

        var decision = board.editAndApprove("did:wyrd:wyrd", 1,
            "function execute(p) { return 'edited'; }",
            "tightened error handling",
            mat);

        assertThat(decision.ok()).isTrue();
        // Prior is SUPERSEDED.
        var priorRead = store.get(prior.draftId()).orElseThrow();
        assertThat(priorRead.status()).isEqualTo(SkillDraft.Status.SUPERSEDED);
        // Materializer ran with the edited code.
        assertThat(seen).hasSize(1);
        assertThat(seen.get(0).code()).contains("edited");
    }

    @Test
    void editAndApprove_rejects_bad_edited_code() {
        seed("compress_archive");
        var decision = board.editAndApprove("did:wyrd:wyrd", 1,
            "var x = 1;", "broken edit", drafts -> {});
        assertThat(decision.ok()).isFalse();
        assertThat(decision.message()).contains("rejected");
    }

    @Test
    void singleton_get_returns_null_when_store_unset() {
        SkillDraftStore.resetForTests();
        assertThat(WorkshopPinboard.get()).isNull();
    }
}
