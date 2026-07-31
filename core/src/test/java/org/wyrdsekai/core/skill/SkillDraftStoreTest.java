package org.wyrdsekai.core.skill;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.persistence.SchemaInitializer;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behavior contracts for {@link SkillDraftStore}. Phase 1 of
 * rollout.
 */
class SkillDraftStoreTest {

    @AfterEach
    void cleanup() { SkillDraftStore.resetForTests(); }

    private static SkillDraftStore newStore(Path tmp) {
        var jdbc = SchemaInitializer.initialize(tmp.resolve("drafts.db"));
        return new SkillDraftStore(jdbc);
    }

    private static SkillDraft sampleDraft(String name, String agent) {
        return SkillDraft.pending(
            UUID.randomUUID().toString(),
            agent,
            name,
            "compresses a directory into .tar.gz",
            "user requested compression 5 times",
            "function compress(dir) { /* ... */ }",
            "graaljs",
            List.of("compress_archive failed", "tar not available"),
            null,
            "wyrdsekai-3.5-9b-drive@adapter-rev-12");
    }

    @Test
    void upsert_then_get_round_trips_full_draft(@TempDir Path tmp) {
        var store = newStore(tmp);
        var draft = sampleDraft("compress_archive", "did:wyrd:wyrd");

        store.upsert(draft);
        var read = store.get(draft.draftId());

        assertThat(read).isPresent();
        var d = read.get();
        assertThat(d.name()).isEqualTo("compress_archive");
        assertThat(d.status()).isEqualTo(SkillDraft.Status.PENDING);
        assertThat(d.closesGaps()).hasSize(2);
        assertThat(d.proposedByModel()).contains("9b-drive");
    }

    @Test
    void status_transition_pending_to_approved_persists(@TempDir Path tmp) {
        var store = newStore(tmp);
        var draft = sampleDraft("a", "did:wyrd:x");
        store.upsert(draft);

        var approved = draft.approved("looks good — ship it");
        store.upsert(approved);

        var read = store.get(draft.draftId()).orElseThrow();
        assertThat(read.status()).isEqualTo(SkillDraft.Status.APPROVED);
        assertThat(read.decisionNote()).isEqualTo("looks good — ship it");
        assertThat(read.decidedAt()).isNotNull();
    }

    @Test
    void rejected_status_keeps_decision_reason(@TempDir Path tmp) {
        var store = newStore(tmp);
        var draft = sampleDraft("a", "did:wyrd:x");
        store.upsert(draft);
        store.upsert(draft.rejected("uses unsafe network calls"));

        var read = store.get(draft.draftId()).orElseThrow();
        assertThat(read.status()).isEqualTo(SkillDraft.Status.REJECTED);
        assertThat(read.decisionNote()).contains("unsafe network");
    }

    @Test
    void byAgentAndStatus_filters_correctly(@TempDir Path tmp) {
        var store = newStore(tmp);
        var d1 = sampleDraft("alpha", "did:wyrd:a");
        var d2 = sampleDraft("beta",  "did:wyrd:a");
        var d3 = sampleDraft("gamma", "did:wyrd:b");
        store.upsert(d1);
        store.upsert(d2);
        store.upsert(d3);
        // Approve one of agent A's drafts.
        store.upsert(d2.approved("ok"));

        var pendingA = store.byAgentAndStatus("did:wyrd:a", SkillDraft.Status.PENDING);
        var approvedA = store.byAgentAndStatus("did:wyrd:a", SkillDraft.Status.APPROVED);
        var pendingB = store.byAgentAndStatus("did:wyrd:b", SkillDraft.Status.PENDING);

        assertThat(pendingA).hasSize(1);
        assertThat(pendingA.get(0).name()).isEqualTo("alpha");
        assertThat(approvedA).hasSize(1);
        assertThat(approvedA.get(0).name()).isEqualTo("beta");
        assertThat(pendingB).hasSize(1);
        assertThat(pendingB.get(0).name()).isEqualTo("gamma");
    }

    @Test
    void countPending_excludes_other_statuses(@TempDir Path tmp) {
        var store = newStore(tmp);
        var d1 = sampleDraft("p", "did:wyrd:a");
        var d2 = sampleDraft("q", "did:wyrd:a");
        var d3 = sampleDraft("r", "did:wyrd:a");
        store.upsert(d1);
        store.upsert(d2);
        store.upsert(d3);
        store.upsert(d2.approved("ok"));
        store.upsert(d3.rejected("nope"));

        assertThat(store.countPending("did:wyrd:a")).isEqualTo(1);
    }

    @Test
    void empty_closes_gaps_round_trips(@TempDir Path tmp) {
        var store = newStore(tmp);
        var draft = SkillDraft.pending(
            UUID.randomUUID().toString(), "did:wyrd:x",
            "noop", "no-op", "test draft",
            "function() {}", "graaljs",
            List.of(), null, "test-model");

        store.upsert(draft);
        var read = store.get(draft.draftId()).orElseThrow();
        assertThat(read.closesGaps()).isEmpty();
    }

    @Test
    void singleton_set_and_get(@TempDir Path tmp) {
        var store = newStore(tmp);
        SkillDraftStore.setInstance(store);
        try {
            assertThat(SkillDraftStore.get()).isSameAs(store);
        } finally {
            SkillDraftStore.resetForTests();
        }
        assertThat(SkillDraftStore.get()).isNull();
    }
}
