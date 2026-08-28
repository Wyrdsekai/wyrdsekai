package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * envelope contract + queue persistence.
 */
class ForgeDispatchEnvelopeTest {

    private static final String FAMILIAR = "did:wyrd:familiar:codezaiku:did:wyrd:user:operator";
    private static final String BONDHOLDER = "did:wyrd:user:operator";

    @Test void newDispatch_assigns_id_and_status() {
        var env = ForgeDispatchEnvelope.newDispatch(
            FAMILIAR, BONDHOLDER,
            ForgeDispatchEnvelope.TaskShape.DEEP_REFACTOR,
            "portal-a",
            new ForgeDispatchEnvelope.ScopeHint(List.of("a.java", "b.java"), "deep", 30),
            null, "ws-1", "ASSISTED");

        assertThat(env.dispatchId()).isNotBlank();
        assertThat(env.status()).isEqualTo(ForgeDispatchEnvelope.Status.QUEUED);
        assertThat(env.taskShape()).isEqualTo(ForgeDispatchEnvelope.TaskShape.DEEP_REFACTOR);
        assertThat(env.scopeHint().affectedFiles()).containsExactly("a.java", "b.java");
        assertThat(env.createdAt()).isNotNull();
    }

    @Test void expectedOutputKind_maps_each_task_shape() {
        // §17.7.3 routing per §17.6 taxonomy — load-bearing for the
        // coding-aware ingestion pass.
        assertThat(envelope(ForgeDispatchEnvelope.TaskShape.DEEP_REFACTOR).expectedOutputKind())
            .isEqualTo(FragmentKind.DEXTERITY);
        assertThat(envelope(ForgeDispatchEnvelope.TaskShape.CROSS_PROJECT_DISTILL).expectedOutputKind())
            .isEqualTo(FragmentKind.CONVENTION);
        assertThat(envelope(ForgeDispatchEnvelope.TaskShape.CORPUS_CANDIDATE).expectedOutputKind())
            .isEqualTo(FragmentKind.DEXTERITY);
        assertThat(envelope(ForgeDispatchEnvelope.TaskShape.SPEC_INGESTION).expectedOutputKind())
            .isEqualTo(FragmentKind.STRUCTURAL);
        assertThat(envelope(ForgeDispatchEnvelope.TaskShape.SEMANTIC_EQUIVALENCE).expectedOutputKind())
            .isEqualTo(FragmentKind.DEXTERITY);
    }

    @Test void isRepairMode_reflects_mode_lock_state() {
        assertThat(envelope(ForgeDispatchEnvelope.TaskShape.DEEP_REFACTOR).isRepairMode()).isFalse();
        var withRepair = ForgeDispatchEnvelope.newDispatch(
            FAMILIAR, BONDHOLDER, ForgeDispatchEnvelope.TaskShape.DEEP_REFACTOR,
            "portal-a", null,
            new ForgeDispatchEnvelope.ModeLockState(
                "Repair", Instant.now(), "BONDHOLDER_DECLARED", "portal-a"),
            "ws-1", "ASSISTED");
        assertThat(withRepair.isRepairMode()).isTrue();
    }

    @Test void rejects_blank_required_fields() {
        assertThatThrownBy(() -> new ForgeDispatchEnvelope(
            "", FAMILIAR, BONDHOLDER, ForgeDispatchEnvelope.TaskShape.DEEP_REFACTOR,
            "p", null, null, "ws", "A", Instant.now(),
            ForgeDispatchEnvelope.Status.QUEUED, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("dispatchId");

        assertThatThrownBy(() -> new ForgeDispatchEnvelope(
            "id-1", "", BONDHOLDER, ForgeDispatchEnvelope.TaskShape.DEEP_REFACTOR,
            "p", null, null, "ws", "A", Instant.now(),
            ForgeDispatchEnvelope.Status.QUEUED, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("familiarDid");
    }

    @Test void queue_submit_and_drain(@TempDir Path tmp) throws IOException {
        var queue = new ForgeDispatchQueue(tmp);
        var first = queue.submit(envelope(ForgeDispatchEnvelope.TaskShape.DEEP_REFACTOR));
        Thread.yield(); // tiny ordering nudge for createdAt
        var second = queue.submit(envelope(ForgeDispatchEnvelope.TaskShape.CORPUS_CANDIDATE));

        var next = queue.nextQueued();
        assertThat(next).isPresent();
        // Earliest-created wins (the submission-order property the spec relies on).
        assertThat(next.get().dispatchId()).isEqualTo(first.dispatchId());

        // Mark running, next call returns the other one.
        queue.markRunning(first.dispatchId());
        var next2 = queue.nextQueued();
        assertThat(next2).isPresent();
        assertThat(next2.get().dispatchId()).isEqualTo(second.dispatchId());
    }

    @Test void queue_status_transitions_persist(@TempDir Path tmp) throws IOException {
        var queue = new ForgeDispatchQueue(tmp);
        var env = queue.submit(envelope(ForgeDispatchEnvelope.TaskShape.SPEC_INGESTION));
        queue.markRunning(env.dispatchId());
        queue.markCompleted(env.dispatchId());

        var fresh = new ForgeDispatchQueue(tmp);
        var loaded = fresh.get(env.dispatchId());
        assertThat(loaded).isPresent();
        assertThat(loaded.get().status()).isEqualTo(ForgeDispatchEnvelope.Status.COMPLETED);
    }

    @Test void queue_cancel_marks_envelope(@TempDir Path tmp) throws IOException {
        var queue = new ForgeDispatchQueue(tmp);
        var env = queue.submit(envelope(ForgeDispatchEnvelope.TaskShape.DEEP_REFACTOR));
        var cancelled = queue.cancel(env.dispatchId());
        assertThat(cancelled).isPresent();
        assertThat(cancelled.get().status()).isEqualTo(ForgeDispatchEnvelope.Status.CANCELLED);
        // A cancelled dispatch is NOT returned by nextQueued.
        assertThat(queue.nextQueued()).isEmpty();
    }

    @Test void queue_countByStatus_aggregates(@TempDir Path tmp) throws IOException {
        var queue = new ForgeDispatchQueue(tmp);
        var a = queue.submit(envelope(ForgeDispatchEnvelope.TaskShape.DEEP_REFACTOR));
        var b = queue.submit(envelope(ForgeDispatchEnvelope.TaskShape.CORPUS_CANDIDATE));
        var c = queue.submit(envelope(ForgeDispatchEnvelope.TaskShape.SPEC_INGESTION));
        queue.markRunning(a.dispatchId());
        queue.markCompleted(b.dispatchId());

        var counts = queue.countByStatus();
        assertThat(counts.get(ForgeDispatchEnvelope.Status.QUEUED)).isEqualTo(1); // c
        assertThat(counts.get(ForgeDispatchEnvelope.Status.RUNNING)).isEqualTo(1); // a
        assertThat(counts.get(ForgeDispatchEnvelope.Status.COMPLETED)).isEqualTo(1); // b
    }

    @Test void queue_get_unknown_dispatch_is_empty(@TempDir Path tmp) throws IOException {
        var queue = new ForgeDispatchQueue(tmp);
        assertThat(queue.get("not-a-real-id")).isEmpty();
    }

    @Test void scope_hint_affected_files_unmodifiable() {
        var hint = new ForgeDispatchEnvelope.ScopeHint(
            new ArrayList<>(List.of("a.java")), "deep", 30);
        assertThat(hint.affectedFiles()).containsExactly("a.java");
        org.assertj.core.api.Assertions
            .assertThatThrownBy(() -> hint.affectedFiles().add("b.java"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void queue_reloadFromDisk_refreshes(@TempDir Path tmp) throws IOException {
        var q1 = new ForgeDispatchQueue(tmp);
        var env = q1.submit(envelope(ForgeDispatchEnvelope.TaskShape.DEEP_REFACTOR));
        // Second instance points at the same path — sees the same envelope.
        var q2 = new ForgeDispatchQueue(tmp);
        assertThat(q2.get(env.dispatchId())).isPresent();
    }

    private static ForgeDispatchEnvelope envelope(ForgeDispatchEnvelope.TaskShape shape) {
        return ForgeDispatchEnvelope.newDispatch(
            FAMILIAR, BONDHOLDER, shape, "portal-a",
            new ForgeDispatchEnvelope.ScopeHint(List.of(), "moderate", 15),
            null, "ws-1", "ASSISTED");
    }
}
