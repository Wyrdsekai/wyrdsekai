package org.wyrdsekai.core.substrate.training;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.substrate.DeepSleepTrainer;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * No-op executor. Returns {@code SKIPPED_NO_BACKEND} immediately with the
 * selector's reason as the detail string. Used when no viable strategy is
 * available (no GPU, no peer, no cloud, or {@code DISABLED} policy).
 */
final class SkipExecutor implements TrainingExecutor {
    private static final Logger log = LoggerFactory.getLogger(SkipExecutor.class);

    private final String reason;

    SkipExecutor(String reason) {
        this.reason = reason;
    }

    @Override
    public DeepSleepTrainer.Result execute(
            String agentId, String agentName,
            String baseModelPath, Path workDir,
            List<Map<String, String>> corpus) {
        log.info("Training SKIPPED for '{}' — {}", agentName, reason);
        return new DeepSleepTrainer.Result(
            DeepSleepTrainer.Outcome.SKIPPED_NO_BACKEND,
            "skip: " + reason,
            null);
    }
}
