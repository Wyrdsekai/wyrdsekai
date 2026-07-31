package org.wyrdsekai.core.substrate.training;

import org.wyrdsekai.core.substrate.DeepSleepTrainer;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Runs a single training cycle for an agent under the strategy chosen by
 * {@link TrainingStrategySelector}.
 *
 * <p>One executor per {@link TrainingStrategy} variant. {@link DeepSleepTrainer}
 * picks an executor via {@link Factory#forStrategy} and delegates the
 * pause/train/resume choreography. The agent's {@link DeepSleepTrainer.Result}
 * is the unified output regardless of which strategy ran — caller code
 * doesn't branch on the strategy.</p>
 *
 * <p>The {@link Context} record carries cross-strategy dependencies. Each
 * executor uses what it needs and ignores the rest. This keeps the
 * factory dispatch simple — no branching to construct executors with
 * different argument lists.</p>
 */
public interface TrainingExecutor {

    /**
     * Run the training cycle.
     *
     * @param agentId       the agent identifier (companion-wyrd, etc.)
     * @param agentName     display name for logging
     * @param baseModelPath path to the base model in HF format
     * @param workDir       writable directory for adapter output
     * @param corpus        conversation turns to train on (system/user/assistant maps)
     * @return outcome — never throws; failures convert to {@code Result(FAILED, ...)}
     */
    DeepSleepTrainer.Result execute(
        String agentId,
        String agentName,
        String baseModelPath,
        Path workDir,
        List<Map<String, String>> corpus
    );

    /**
     * Cross-cutting dependencies needed by one or more executor variants.
     * Not all executors use all fields. Constructed once by
     * {@link DeepSleepTrainer} per agent and passed to the factory.
     *
     * @param inferenceController used by {@link LocalSerialExecutor} to pause/resume
     * @param adapterRoot         destination for produced adapters
     * @param peerTransport       NATS-shaped transport for {@link PeerDelegatedExecutor};
     *                            null disables peer delegation (executor returns SKIPPED)
     * @param localNodeId         this node's identifier (for protocol logging); null OK when no peer use
     */
    record Context(
        DeepSleepTrainer.InferenceController inferenceController,
        Path adapterRoot,
        PeerTrainingTransport peerTransport,
        String localNodeId
    ) {
        /** Compact constructor for executors that don't need peer transport. */
        public Context(
                DeepSleepTrainer.InferenceController inferenceController,
                Path adapterRoot) {
            this(inferenceController, adapterRoot, null, null);
        }
    }

    /**
     * Dispatcher: given a chosen strategy + context, return the executor
     * that will run it. Pattern-matches on the sealed strategy variants
     * so adding a new strategy requires updating this site (compiler will
     * surface the omission).
     */
    final class Factory {
        private Factory() {}

        public static TrainingExecutor forStrategy(TrainingStrategy strategy, Context ctx) {
            return switch (strategy) {
                case TrainingStrategy.LocalParallel  __ -> new LocalParallelExecutor(ctx);
                case TrainingStrategy.LocalSerial    s  -> new LocalSerialExecutor(ctx, s);
                case TrainingStrategy.PeerDelegated  p  -> new PeerDelegatedExecutor(ctx, p);
                case TrainingStrategy.CloudDistilled c  -> new CloudDistilledExecutor(ctx, c);
                case TrainingStrategy.Skip           s  -> new SkipExecutor(s.reason());
            };
        }
    }
}
