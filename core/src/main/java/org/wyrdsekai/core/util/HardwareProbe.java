package org.wyrdsekai.core.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.search.EmbeddingModel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Lightweight host-capability probe — meant to run in milliseconds at install
 * time so {@code wyrd setup} can pick a sensible default embedding model.
 *
 * <p>Intentionally narrow: just RAM and platform. Anything heavier (GPU detection,
 * AVX-512 introspection, NUMA topology) belongs in the inference-backend recommender
 * — embedding inference is small, single-threaded, and runs anywhere.
 *
 * <p>Recommendation policy is deliberately conservative. The default
 * {@link EmbeddingModel#PARAPHRASE_L12} (384-d, ~120MB, multilingual) is what
 * a fresh install actually has on disk. Recommending a larger model means
 * "you should download X and migrate" — only do that when the host has
 * comfortable headroom.
 *
 * <h2>Phone detection</h2>
 *
 * <p>The phone clients (KMP/RN) live in their own modules and don't use this
 * class — they always pin to a small ONNX. The {@code "wyrdsekai.platform=phone"}
 * system property and {@code WYRDSEKAI_PLATFORM} env var are honored anyway,
 * so a server-side test harness simulating phone constraints gets the right
 * recommendation.
 */
public final class HardwareProbe {

    private static final Logger log = LoggerFactory.getLogger(HardwareProbe.class);

    private HardwareProbe() {}

    /**
     * Total host RAM in GB, integer-rounded. Reads {@code /proc/meminfo} on Linux,
     * {@code sysctl hw.memsize} on macOS, falls back to JVM {@code maxMemory}.
     *
     * <p>JVM fallback is a poor proxy (it reports the heap ceiling, not the host),
     * but it's better than zero — and on phones / CI runners it's roughly accurate.
     */
    public static long availableRamGB() {
        // Linux
        var meminfo = Path.of("/proc/meminfo");
        if (Files.isReadable(meminfo)) {
            try {
                for (var line : Files.readAllLines(meminfo)) {
                    if (line.startsWith("MemTotal:")) {
                        // "MemTotal:       16384000 kB"
                        var parts = line.trim().split("\\s+");
                        if (parts.length >= 2) {
                            try {
                                long kb = Long.parseLong(parts[1]);
                                return Math.max(1L, kb / 1024L / 1024L);
                            } catch (NumberFormatException ignored) {}
                        }
                    }
                }
            } catch (IOException e) {
                log.debug("/proc/meminfo unreadable: {}", e.getMessage());
            }
        }

        // macOS
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) {
            try {
                var pb = new ProcessBuilder("sysctl", "-n", "hw.memsize")
                    .redirectErrorStream(true);
                var p = pb.start();
                var out = new String(p.getInputStream().readAllBytes()).trim();
                p.waitFor();
                if (!out.isEmpty()) {
                    long bytes = Long.parseLong(out);
                    return Math.max(1L, bytes / 1024L / 1024L / 1024L);
                }
            } catch (Exception e) {
                log.debug("sysctl hw.memsize failed: {}", e.getMessage());
            }
        }

        // JVM fallback — reports heap ceiling, not host RAM. -Xmx defaults to
        // 1/4 of physical, so multiply back by 4 as a coarse estimate. Caller
        // should treat the result as a floor.
        long jvm = Runtime.getRuntime().maxMemory();
        long est = (jvm * 4L) / (1024L * 1024L * 1024L);
        return Math.max(1L, est);
    }

    /** True if running in a phone-class platform (system property or env var). */
    public static boolean isPhonePlatform() {
        var sys = System.getProperty("wyrdsekai.platform", "");
        if ("phone".equalsIgnoreCase(sys)) return true;
        var env = System.getenv("WYRDSEKAI_PLATFORM");
        return env != null && "phone".equalsIgnoreCase(env);
    }

    /**
     * Pick a default {@link EmbeddingModel} for the host.
     *
     * <ul>
     *   <li>Phone platform → {@link EmbeddingModel#E5_SMALL} (small, multilingual)</li>
     *   <li>≥ 8 GB RAM → {@link EmbeddingModel#BGE_M3} (best quality)</li>
     *   <li>4–8 GB RAM → {@link EmbeddingModel#E5_BASE}</li>
     *   <li>&lt; 4 GB RAM → {@link EmbeddingModel#E5_SMALL}</li>
     * </ul>
     *
     * <p>Note that this returns a <em>recommendation</em> — what the operator
     * <em>currently runs</em> is {@link EmbeddingModel#PARAPHRASE_L12} until they
     * download + switch. The setup wizard surfaces the diff.
     */
    public static EmbeddingModel recommendedEmbeddingModel() {
        return recommendedEmbeddingModelForRam(availableRamGB());
    }

    /** Pure-function version of {@link #recommendedEmbeddingModel()} — exposed for tests. */
    public static EmbeddingModel recommendedEmbeddingModelForRam(long ramGB) {
        if (isPhonePlatform()) return EmbeddingModel.E5_SMALL;
        if (ramGB >= 8)        return EmbeddingModel.BGE_M3;
        if (ramGB >= 4)        return EmbeddingModel.E5_BASE;
        return EmbeddingModel.E5_SMALL;
    }
}
