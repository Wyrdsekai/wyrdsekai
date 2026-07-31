package org.wyrdsekai.core.substrate.training;

import org.wyrdsekai.core.gpu.GpuProbe;

import java.util.ArrayList;
import java.util.List;

/**
 * Snapshot of this node's training-relevant capacity at a moment in time.
 *
 * <p>The {@link TrainingStrategySelector} consumes this to decide whether the
 * current host can run training locally, must offload to a peer, must use
 * cloud distillation, or should skip the cycle entirely.</p>
 *
 * <p>Constructed via {@link #detect()} which probes:
 * <ul>
 *   <li>Local GPU(s) via {@link org.wyrdsekai.core.gpu.GpuProbe}</li>
 *   <li>Active model footprints from running inference backends</li>
 *   <li>Estimated training peak VRAM from LoRA config (rank, batch, seq-len)</li>
 *   <li>Free system RAM (for CPU-class training fallback)</li>
 * </ul></p>
 *
 * <p>Pure record — no IO. {@link #detect()} is the side-effecting entry point.</p>
 *
 * @param freeGpuVramGb     Estimated free GPU VRAM right now (largest GPU if multi-GPU)
 * @param totalGpuVramGb    Total GPU VRAM (largest GPU)
 * @param activeModelsGb    Sum of currently-loaded model footprints (peak inference)
 * @param trainingEstimateGb Estimated peak VRAM the configured training run will need
 * @param freeRamGb         Free system RAM (for CPU-only training paths)
 * @param cpuCores          Available CPU cores
 * @param hasNvidiaGpu      True if at least one NVIDIA GPU is available
 * @param hasAppleSilicon   True if running on Apple Silicon (MLX-eligible)
 * @param gpuNames          Display names of detected GPUs (for logging)
 */
public record NodeCapacity(
    double freeGpuVramGb,
    double totalGpuVramGb,
    double activeModelsGb,
    double trainingEstimateGb,
    double freeRamGb,
    int cpuCores,
    boolean hasNvidiaGpu,
    boolean hasAppleSilicon,
    List<String> gpuNames
) {

    /** Convenience: would a parallel training fit alongside currently-active models? */
    public boolean canTrainInParallel() {
        return hasGpu() && freeGpuVramGb >= activeModelsGb + trainingEstimateGb;
    }

    /** Would training fit if we paused active models first? */
    public boolean canTrainAfterPause() {
        return hasGpu()
            && totalGpuVramGb >= trainingEstimateGb
            && freeGpuVramGb + activeModelsGb >= trainingEstimateGb;
    }

    public boolean hasGpu() {
        return hasNvidiaGpu || hasAppleSilicon;
    }

    /** Approximate hours a CPU-only training run would take. -1 if not applicable. */
    public double cpuTrainingHoursEstimate() {
        if (hasGpu() || cpuCores < 1) return -1;
        // ~5-15 tok/sec on modern x86 16-core; corpus of 50 turns × 200 tokens × 24 steps
        // ≈ 240k tokens of compute → 4-13 hours on 16-core, slower on small cores.
        var tokensPerSec = Math.max(1.0, cpuCores * 0.7);
        var totalTokens = 240_000;  // typical voice-align workload
        return totalTokens / tokensPerSec / 3600.0;
    }

    // ─── Detection ───────────────────────────────────────────────────────

    /**
     * Probe this host for capacity. Best-effort: missing tools degrade
     * gracefully (no nvidia-smi → hasNvidiaGpu=false, freeVram=0).
     *
     * @param activeModelsGb caller-supplied estimate of currently-running model
     *                       footprints (caller knows what it loaded)
     * @param trainingEstimateGb caller-supplied estimate of training peak
     *                           (depends on LoRA rank, batch, seq-len)
     */
    public static NodeCapacity detect(double activeModelsGb, double trainingEstimateGb) {
        double freeGpu = 0.0;
        double totalGpu = 0.0;
        boolean nvidia = false;
        boolean apple = false;
        var gpuNames = new ArrayList<String>();

        try {
            var gpus = GpuProbe.detect();
            for (var g : gpus) {
                // Track largest GPU (best candidate for training)
                if (g.totalVramMB() / 1024.0 > totalGpu) {
                    totalGpu = g.totalVramMB() / 1024.0;
                    freeGpu  = g.freeVramMB()  / 1024.0;
                }
                gpuNames.add(g.name());
                if (g.vendor() == GpuProbe.GpuVendor.NVIDIA) nvidia = true;
                if (g.vendor() == GpuProbe.GpuVendor.APPLE)  apple  = true;
            }
        } catch (Throwable t) {
            // GpuProbe unavailable or threw — degrade to no-GPU
        }

        var freeRam = probeFreeRamGb();
        var cpuCores = Runtime.getRuntime().availableProcessors();

        return new NodeCapacity(
            freeGpu, totalGpu, activeModelsGb, trainingEstimateGb,
            freeRam, cpuCores, nvidia, apple, List.copyOf(gpuNames)
        );
    }

    private static double probeFreeRamGb() {
        // Best-effort — JVM-level approximation. Real free RAM would need
        // /proc/meminfo on Linux or sysctl on macOS. Good enough for selector.
        var rt = Runtime.getRuntime();
        var freeBytes = rt.freeMemory() + (rt.maxMemory() - rt.totalMemory());
        return freeBytes / (1024.0 * 1024.0 * 1024.0);
    }
}
