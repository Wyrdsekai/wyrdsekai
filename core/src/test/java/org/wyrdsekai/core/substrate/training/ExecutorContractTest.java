package org.wyrdsekai.core.substrate.training;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.substrate.DeepSleepTrainer;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Direct executor-level contracts. These do NOT exercise DeepSleepTrainer's
 * env-gated preflight — they probe each executor in isolation via the
 * factory dispatch so a regression in one variant doesn't hide behind the
 * trainer's other guards.
 *
 * <p>Phase 2-4 executors (LocalParallel/PeerDelegated/CloudDistilled) all
 * stub to SKIPPED_NO_BACKEND today. Once they grow real implementations
 * those tests should be replaced; for now we just lock in the "harmless
 * stub" contract so a future selector that picks one of them never
 * silently misbehaves.</p>
 */
class ExecutorContractTest {

    private static final List<Map<String, String>> CORPUS = List.of(
        Map.of("system", "s", "user", "u", "assistant", "hello"));

    private static TrainingExecutor.Context noopCtx(Path tmp) {
        return new TrainingExecutor.Context(
            new DeepSleepTrainer.NoOpInferenceController(), tmp);
    }

    // ── Skip: policy/no-strategy path ───────────────────────────────────

    @Test
    void skip_executor_returns_skipped_with_reason(@TempDir Path tmp) {
        var executor = TrainingExecutor.Factory.forStrategy(
            new TrainingStrategy.Skip("test-reason"), noopCtx(tmp));
        var result = executor.execute("did:wyrd:x", "X", "/no/model", tmp, CORPUS);

        assertThat(result.outcome()).isEqualTo(DeepSleepTrainer.Outcome.SKIPPED_NO_BACKEND);
        assertThat(result.detail()).contains("test-reason");
        assertThat(result.adapterPath()).isNull();
    }

    @Test
    void skip_executor_does_not_touch_inference_controller(@TempDir Path tmp) {
        // The selector picked Skip; the executor must NOT call pause/resume.
        // Otherwise a DISABLED policy would still bounce inference for nothing.
        var ctl = new DeepSleepTrainer.NoOpInferenceController();
        var executor = TrainingExecutor.Factory.forStrategy(
            new TrainingStrategy.Skip("dont-call-anything"),
            new TrainingExecutor.Context(ctl, tmp));
        executor.execute("did:wyrd:x", "X", "/no/model", tmp, CORPUS);

        assertThat(ctl.pauseCalled).isFalse();
        assertThat(ctl.resumeCalled).isFalse();
    }

    // ── LocalSerial: fail-closed when pause fails ───────────────────────

    @Test
    void local_serial_aborts_when_pause_fails(@TempDir Path tmp) {
        // Production behavior: if we can't free VRAM, we MUST NOT proceed
        // to training — that path OOMs the trainer on a 16GB card. Aborting
        // with FAILED("inference-pause-failed") keeps the agent's voice on
        // the previous adapter rather than crashing.
        var refusingCtl = new DeepSleepTrainer.InferenceController() {
            @Override public boolean pause() { return false; }
            @Override public boolean resume(Path adapterPath) { return true; }
        };
        var executor = TrainingExecutor.Factory.forStrategy(
            new TrainingStrategy.LocalSerial(List.of("wyrdsekai-llama")),
            new TrainingExecutor.Context(refusingCtl, tmp));

        var result = executor.execute(
            "did:wyrd:x", "X", "/path/to/model", tmp, CORPUS);

        assertThat(result.outcome()).isEqualTo(DeepSleepTrainer.Outcome.FAILED);
        assertThat(result.detail()).isEqualTo("inference-pause-failed");
        assertThat(result.adapterPath()).isNull();
    }

    @Test
    void local_serial_skips_when_no_model_path(@TempDir Path tmp) {
        // Selector handed us LocalSerial but the trainer didn't resolve a
        // base model — return SKIPPED_NO_MODEL without paging inference.
        var ctl = new DeepSleepTrainer.NoOpInferenceController();
        var executor = TrainingExecutor.Factory.forStrategy(
            new TrainingStrategy.LocalSerial(List.of()),
            new TrainingExecutor.Context(ctl, tmp));

        var result = executor.execute("did:wyrd:x", "X", "", tmp, CORPUS);

        assertThat(result.outcome()).isEqualTo(DeepSleepTrainer.Outcome.SKIPPED_NO_MODEL);
        assertThat(ctl.pauseCalled)
            .as("pre-flight skip must not bounce inference")
            .isFalse();
    }

    // ── Phase 2-4 stubs: guard against silent misbehavior ───────────────

    @Test
    void local_parallel_stub_returns_skipped_no_backend(@TempDir Path tmp) {
        // Phase 2 placeholder. If selector promotes LocalParallel before the
        // executor lands, we want a clear SKIPPED, not a half-baked train.
        var executor = TrainingExecutor.Factory.forStrategy(
            new TrainingStrategy.LocalParallel(), noopCtx(tmp));
        var result = executor.execute("did:wyrd:x", "X", "/m", tmp, CORPUS);

        assertThat(result.outcome()).isEqualTo(DeepSleepTrainer.Outcome.SKIPPED_NO_BACKEND);
        assertThat(result.detail()).contains("phase 2");
    }

    @Test
    void peer_delegated_skips_when_no_transport_in_context(@TempDir Path tmp) {
        // PeerDelegatedExecutor needs a PeerTrainingTransport in the Context.
        // The default-arity Context constructor leaves it null — executor must
        // skip cleanly rather than NPE.
        var executor = TrainingExecutor.Factory.forStrategy(
            new TrainingStrategy.PeerDelegated("gpu-host", "alpha"), noopCtx(tmp));
        var result = executor.execute("did:wyrd:x", "X", "/m", tmp, CORPUS);

        assertThat(result.outcome()).isEqualTo(DeepSleepTrainer.Outcome.SKIPPED_NO_BACKEND);
        assertThat(result.detail()).contains("no transport");
    }

    @Test
    void cloud_distilled_stub_returns_skipped_no_backend(@TempDir Path tmp) {
        var executor = TrainingExecutor.Factory.forStrategy(
            new TrainingStrategy.CloudDistilled("anthropic"), noopCtx(tmp));
        var result = executor.execute("did:wyrd:x", "X", "/m", tmp, CORPUS);

        assertThat(result.outcome()).isEqualTo(DeepSleepTrainer.Outcome.SKIPPED_NO_BACKEND);
        assertThat(result.detail()).contains("phase 4");
    }
}
