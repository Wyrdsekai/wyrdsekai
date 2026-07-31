package org.wyrdsekai.core.substrate.training;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.substrate.DeepSleepTrainer;
import org.wyrdsekai.core.substrate.VoiceAligner;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Pause local inference, run training in the freed VRAM, resume.
 *
 * <p>This is the strategy that historically lived inline in
 * {@link DeepSleepTrainer}. Extracted into an executor so it sits
 * symmetrically alongside the other strategies — and so the trainer
 * can dispatch by selection rather than hardcoded behavior.</p>
 *
 * <p>Behavior preserved from the prior inline implementation:</p>
 * <ul>
 *   <li>Pause via {@link DeepSleepTrainer.InferenceController#pause()};
 *       abort if pause fails (avoids OOM contention).</li>
 *   <li>Run alignment via {@link VoiceAligner#align}.</li>
 *   <li>Resume via {@link DeepSleepTrainer.InferenceController#resume(Path)}
 *       with the adapter path.</li>
 *   <li>{@code resumedInTry} guard prevents the finally-block's
 *       crash-recovery resume from misfiring on the success path (#418).</li>
 *   <li>Failure at any stage returns a structured {@code Result} —
 *       never throws.</li>
 * </ul>
 *
 * <p>The {@code containersToPause} hint from the strategy is informational
 * only at this layer — the {@link DeepSleepTrainer.InferenceController}
 * implementation decides which containers it manages. Future selectors
 * could pass a more granular hint and a different controller variant
 * could honor it.</p>
 */
final class LocalSerialExecutor implements TrainingExecutor {
    private static final Logger log = LoggerFactory.getLogger(LocalSerialExecutor.class);

    private final Context ctx;
    private final TrainingStrategy.LocalSerial strategy;
    /** Optional override of VoiceAligner training iters; null → backend default (2500). */
    private final Integer maxIters;

    LocalSerialExecutor(Context ctx, TrainingStrategy.LocalSerial strategy) {
        this(ctx, strategy, null);
    }

    LocalSerialExecutor(Context ctx, TrainingStrategy.LocalSerial strategy, Integer maxIters) {
        this.ctx = ctx;
        this.strategy = strategy;
        this.maxIters = maxIters;
    }

    @Override
    public DeepSleepTrainer.Result execute(
            String agentId, String agentName,
            String baseModelPath, Path workDir,
            List<Map<String, String>> corpus) {

        if (baseModelPath == null || baseModelPath.isBlank()) {
            log.warn("LocalSerial: no base model path — skipping for '{}'", agentName);
            return new DeepSleepTrainer.Result(
                DeepSleepTrainer.Outcome.SKIPPED_NO_MODEL, "no-model-path", null);
        }

        log.info("LocalSerial: pausing inference for '{}' (corpus={} turns, hint={} containers)",
                agentName, corpus.size(), strategy.containersToPause().size());
        var paused = ctx.inferenceController().pause();
        if (!paused) {
            log.warn("LocalSerial: failed to pause inference — aborting to avoid OOM contention");
            return new DeepSleepTrainer.Result(
                DeepSleepTrainer.Outcome.FAILED, "inference-pause-failed", null);
        }

        var resumedInTry = false;
        try {
            var aligner = new VoiceAligner(ctx.adapterRoot());
            var adapterPath = aligner.align(agentId, agentName, baseModelPath, corpus, maxIters);

            if (adapterPath == null) {
                log.warn("LocalSerial: VoiceAligner returned null — no adapter produced for '{}'",
                        agentName);
                return new DeepSleepTrainer.Result(
                    DeepSleepTrainer.Outcome.SKIPPED_NO_BACKEND,
                    "aligner-returned-null", null);
            }

            var resumed = ctx.inferenceController().resume(adapterPath);
            resumedInTry = resumed;
            if (!resumed) {
                log.warn("LocalSerial: adapter at {} produced but resume failed for '{}'",
                        adapterPath, agentName);
                return new DeepSleepTrainer.Result(
                    DeepSleepTrainer.Outcome.FAILED, "resume-failed", adapterPath);
            }

            log.info("LocalSerial: complete for '{}' — adapter {}", agentName, adapterPath);
            return new DeepSleepTrainer.Result(
                DeepSleepTrainer.Outcome.COMPLETED, "ok", adapterPath);
        } finally {
            // #418 — only fire crash-recovery resume if the success path didn't.
            if (paused && !resumedInTry && !ctx.inferenceController().resume(null)) {
                log.error("LocalSerial: failed to resume inference after alignment "
                        + "for '{}' — manual `wyrd inference local` required",
                        agentName);
            }
        }
    }
}
