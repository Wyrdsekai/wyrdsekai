package org.wyrdsekai.core.substrate.training;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.substrate.DeepSleepTrainer;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Use a cloud LLM to distil voice-shaped (prompt, response) pairs from the
 * corpus, then train a small local LoRA on those pairs.
 *
 * <p>Phase 4 — for nodes that have a cloud key but no local GPU and no
 * reachable training peer. The bootstrap pattern from MEMORY.md:
 * cloud-as-teacher → local-as-student.</p>
 *
 * <p>Not yet implemented. Falls through to {@code SKIPPED_NO_BACKEND}.</p>
 */
final class CloudDistilledExecutor implements TrainingExecutor {
    private static final Logger log = LoggerFactory.getLogger(CloudDistilledExecutor.class);

    private final Context ctx;
    private final TrainingStrategy.CloudDistilled strategy;

    CloudDistilledExecutor(Context ctx, TrainingStrategy.CloudDistilled strategy) {
        this.ctx = ctx;
        this.strategy = strategy;
    }

    @Override
    public DeepSleepTrainer.Result execute(
            String agentId, String agentName,
            String baseModelPath, Path workDir,
            List<Map<String, String>> corpus) {
        log.warn("CloudDistilledExecutor not yet implemented (#429 phase 4) — "
                + "would have distilled corpus for '{}' via {}",
            agentName, strategy.cloudProvider());
        return new DeepSleepTrainer.Result(
            DeepSleepTrainer.Outcome.SKIPPED_NO_BACKEND,
            "cloud-distilled: executor not implemented (phase 4)",
            null);
    }
}
