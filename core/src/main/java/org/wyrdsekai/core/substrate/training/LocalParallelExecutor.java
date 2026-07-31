package org.wyrdsekai.core.substrate.training;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.substrate.DeepSleepTrainer;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Train alongside running inference — no pause. Used when the host has
 * enough GPU headroom for the trainer's peak VRAM on top of currently
 * loaded models (≥24GB-class GPUs typically).
 *
 * <p>Phase 2 — not yet implemented. Selector will not return
 * {@link TrainingStrategy.LocalParallel} until {@link NodeCapacity#canTrainInParallel()}
 * is true on this host AND this executor is built. For now it falls through
 * to {@code SKIPPED_NO_BACKEND} so it can never silently misbehave.</p>
 */
final class LocalParallelExecutor implements TrainingExecutor {
    private static final Logger log = LoggerFactory.getLogger(LocalParallelExecutor.class);

    private final Context ctx;

    LocalParallelExecutor(Context ctx) {
        this.ctx = ctx;
    }

    @Override
    public DeepSleepTrainer.Result execute(
            String agentId, String agentName,
            String baseModelPath, Path workDir,
            List<Map<String, String>> corpus) {
        log.warn("LocalParallelExecutor not yet implemented (#429 phase 2) — "
                + "selector chose parallel for '{}' but executor falls back to SKIP",
            agentName);
        return new DeepSleepTrainer.Result(
            DeepSleepTrainer.Outcome.SKIPPED_NO_BACKEND,
            "local-parallel: executor not implemented (phase 2)",
            null);
    }
}
