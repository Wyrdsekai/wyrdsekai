package org.wyrdsekai.core.gpu;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Detects available GPUs by invoking nvidia-smi (NVIDIA), rocm-smi/amd-smi (AMD),
 * or system_profiler (Apple Silicon Metal).
 * Returns an empty list gracefully when no GPU tools are available (CPU-only systems).
 * Also provides VRAM estimation, tensor parallelism suggestions, and parallel slot calculation.
 */
public final class GpuProbe {

    private static final Logger log = LoggerFactory.getLogger(GpuProbe.class);

    /** GPU hardware vendor. */
    public enum GpuVendor { NVIDIA, AMD, APPLE, NONE }

    /**
     * Information about a single GPU.
     */
    public record GpuInfo(
        int index,
        String name,
        long totalVramMB,
        long freeVramMB,
        long usedVramMB,
        int utilizationPercent,
        GpuVendor vendor
    ) {
        /** Convenience constructor for NVIDIA GPUs (backward compat). */
        public GpuInfo(int index, String name, long totalVramMB, long freeVramMB,
                       long usedVramMB, int utilizationPercent) {
            this(index, name, totalVramMB, freeVramMB, usedVramMB, utilizationPercent, GpuVendor.NVIDIA);
        }

        /** VRAM utilization as a ratio (0.0 - 1.0). */
        public double vramUtilization() {
            return totalVramMB > 0 ? (double) usedVramMB / totalVramMB : 0.0;
        }
    }

    /**
     * Detect all available GPUs. Tries NVIDIA first, then AMD ROCm, then Apple Silicon.
     * Returns empty list if no GPUs are detected.
     */
    public static List<GpuInfo> detect() {
        // Try NVIDIA first
        var nvidiaGpus = detectNvidia();
        if (!nvidiaGpus.isEmpty()) return nvidiaGpus;

        // Try AMD ROCm
        var amdGpus = detectAmd();
        if (!amdGpus.isEmpty()) return amdGpus;

        // Try Apple Silicon (Metal)
        var appleGpus = detectApple();
        if (!appleGpus.isEmpty()) return appleGpus;

        return List.of();
    }

    /**
     * Return the vendor of the first detected GPU, or NONE.
     */
    public static GpuVendor detectVendor() {
        var gpus = detect();
        return gpus.isEmpty() ? GpuVendor.NONE : gpus.getFirst().vendor();
    }

    private static List<GpuInfo> detectNvidia() {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "nvidia-smi",
                "--query-gpu=index,name,memory.total,memory.free,memory.used,utilization.gpu",
                "--format=csv,noheader,nounits"
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();

            List<GpuInfo> gpus = new ArrayList<>();
            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    GpuInfo gpu = parseNvidiaLine(line.trim());
                    if (gpu != null) gpus.add(gpu);
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                log.debug("nvidia-smi exited with code {}", exitCode);
                return List.of();
            }

            log.info("Detected {} NVIDIA GPU(s): {}", gpus.size(),
                gpus.stream().map(g -> g.name() + " (" + g.totalVramMB() + "MB)").toList());
            return Collections.unmodifiableList(gpus);
        } catch (Exception e) {
            log.debug("nvidia-smi not available: {}", e.getMessage());
            return List.of();
        }
    }

    private static List<GpuInfo> detectAmd() {
        // Try rocm-smi first (legacy, widely installed)
        var gpus = detectAmdVia("rocm-smi", "--showallinfo", "--json");
        if (!gpus.isEmpty()) return gpus;

        // Try amd-smi (successor to rocm-smi, ROCm 6.3+)
        gpus = detectAmdVia("amd-smi", "static", "--json");
        return gpus;
    }

    private static List<GpuInfo> detectAmdVia(String... command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            String output;
            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                var sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                output = sb.toString();
            }

            int exitCode = process.waitFor();
            if (exitCode != 0 || output.isBlank()) {
                log.debug("{} exited with code {} or empty output", command[0], exitCode);
                return List.of();
            }

            List<GpuInfo> gpus = parseRocmJson(output);
            if (!gpus.isEmpty()) {
                log.info("Detected {} AMD GPU(s) via {}: {}", gpus.size(), command[0],
                    gpus.stream().map(g -> g.name() + " (" + g.totalVramMB() + "MB)").toList());
            }
            return Collections.unmodifiableList(gpus);
        } catch (Exception e) {
            log.debug("{} not available: {}", command[0], e.getMessage());
            return List.of();
        }
    }

    private static List<GpuInfo> detectApple() {
        // Apple Silicon detection: macOS + aarch64 (ARM JVM on M-series chip)
        String osName = System.getProperty("os.name", "").toLowerCase();
        String osArch = System.getProperty("os.arch", "");
        if (!(osName.contains("mac") || osName.contains("darwin")) || !"aarch64".equals(osArch)) {
            return List.of();
        }

        try {
            // Use system_profiler for GPU name; unified memory = total system RAM
            ProcessBuilder pb = new ProcessBuilder("system_profiler", "SPDisplaysDataType");
            pb.redirectErrorStream(true);
            Process process = pb.start();

            String gpuName = "Apple Silicon GPU";
            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("Chipset Model:") || trimmed.startsWith("Chip:")) {
                        gpuName = trimmed.substring(trimmed.indexOf(':') + 1).trim();
                        break;
                    }
                }
            }
            process.waitFor();

            // Unified memory: total system RAM is shared with GPU
            long totalRamMB = 16_384; // conservative default
            try {
                var ramProc = new ProcessBuilder("sysctl", "-n", "hw.memsize").start();
                String output = new String(ramProc.getInputStream().readAllBytes()).trim();
                ramProc.waitFor();
                totalRamMB = Long.parseLong(output) / (1024 * 1024);
            } catch (Exception ignored) {}

            // Apple Silicon shares all RAM with GPU — report ~75% as GPU-available
            long gpuVramMB = totalRamMB * 3 / 4;
            var gpu = new GpuInfo(0, gpuName, gpuVramMB, gpuVramMB, 0, 0, GpuVendor.APPLE);
            log.info("Detected Apple Silicon GPU: {} ({}MB unified memory, ~{}MB GPU-available)",
                gpuName, totalRamMB, gpuVramMB);
            return List.of(gpu);
        } catch (Exception e) {
            log.debug("Apple Silicon detection failed: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Estimate VRAM needed for a model in MB.
     *
     * @param parameterCountBillions model size in billions of parameters (e.g., 8.0 for 8B)
     * @param quantization           quantization format: "fp16", "fp8", "q4", "q4_k_m", "awq"
     * @return estimated VRAM in MB (includes ~1.5 GB overhead for KV cache at 16K context)
     */
    public static long estimateVramForModel(double parameterCountBillions, String quantization) {
        double bytesPerParam = switch (quantization != null ? quantization.toLowerCase() : "fp16") {
            case "fp16", "bf16" -> 2.0;
            case "fp8" -> 1.0;
            case "q4", "q4_k_m", "awq", "gptq" -> 0.5;
            case "q8", "q8_0" -> 1.0;
            case "fp32" -> 4.0;
            default -> 2.0; // conservative default
        };
        long modelMB = (long) (parameterCountBillions * 1_000 * bytesPerParam);
        long kvCacheOverheadMB = 1_536; // ~1.5 GB for 16K context
        return modelMB + kvCacheOverheadMB;
    }

    /**
     * Suggest optimal tensor parallelism size for a model across available GPUs.
     *
     * @param parameterCountBillions model size in billions
     * @param quantization           quantization format
     * @param availableGpus          list of available GPUs
     * @return recommended TP size (1, 2, or 4), or 0 if model cannot fit
     */
    public static int suggestTpSize(double parameterCountBillions, String quantization,
                                     List<GpuInfo> availableGpus) {
        if (availableGpus.isEmpty()) return 0;

        long totalVramNeeded = estimateVramForModel(parameterCountBillions, quantization);

        for (int tp : new int[]{1, 2, 4, 8}) {
            if (tp > availableGpus.size()) break;
            long perGpuNeeded = totalVramNeeded / tp;
            boolean fits = true;
            for (int i = 0; i < tp; i++) {
                if (availableGpus.get(i).freeVramMB() < perGpuNeeded) {
                    fits = false;
                    break;
                }
            }
            if (fits) return tp;
        }
        return 0; // cannot fit
    }

    /**
     * Calculate safe --parallel value for llama-server.
     * Each parallel slot needs KV cache memory proportional to context size.
     * Conservative: model VRAM + ~1.5GB per slot for KV cache at 16K context.
     *
     * @param paramBillions model parameter count in billions
     * @param quant         quantization format
     * @param freeVramMB    available VRAM in MB
     * @param contextSize   context window size
     * @return recommended --parallel value (always >= 1)
     */
    public static int suggestParallelSlots(double paramBillions, String quant,
                                             long freeVramMB, int contextSize) {
        long modelVram = estimateVramForModel(paramBillions, quant);
        long remaining = freeVramMB - modelVram;
        if (remaining <= 0) return 1;
        // ~1.5GB per slot at 16K context, scale linearly
        long perSlotMB = (long) (1536.0 * contextSize / 16384.0);
        return Math.max(1, (int) (remaining / Math.max(perSlotMB, 256)));
    }

    /**
     * Parse rocm-smi JSON output. Keys vary by rocm-smi version, so we try common patterns.
     * Expected structure: {"card0": {"GPU ID": "...", "VRAM Total Memory (B)": "...", ...}, ...}
     */
    public static List<GpuInfo> parseRocmJson(String json) {
        List<GpuInfo> gpus = new ArrayList<>();
        int index = 0;

        int pos = 0;
        while (true) {
            int cardStart = json.indexOf("\"card" + index + "\"", pos);
            if (cardStart < 0) break;

            int blockStart = json.indexOf('{', cardStart);
            if (blockStart < 0) break;
            String block = extractJsonBlock(json, blockStart);
            if (block == null) break;

            String name = extractJsonString(block, "GPU ID");
            if (name == null) name = extractJsonString(block, "Card series");
            if (name == null) name = "AMD GPU " + index;

            long totalVramBytes = extractJsonLong(block, "VRAM Total Memory (B)");
            long usedVramBytes = extractJsonLong(block, "VRAM Total Used Memory (B)");
            long freeVramBytes = totalVramBytes - usedVramBytes;
            long totalVramMB = totalVramBytes / (1024 * 1024);
            long usedVramMB = usedVramBytes / (1024 * 1024);
            long freeVramMB = freeVramBytes / (1024 * 1024);

            int utilization = 0;
            String gpuUse = extractJsonString(block, "GPU use (%)");
            if (gpuUse != null) {
                try { utilization = Integer.parseInt(gpuUse.replaceAll("[^0-9]", "")); }
                catch (NumberFormatException ignored) {}
            }

            gpus.add(new GpuInfo(index, name, totalVramMB, freeVramMB, usedVramMB, utilization, GpuVendor.AMD));
            index++;
            pos = cardStart + 1;
        }

        return gpus;
    }

    /** Extract a JSON string value for a given key. */
    private static String extractJsonString(String json, String key) {
        String search = "\"" + key + "\"";
        int keyPos = json.indexOf(search);
        if (keyPos < 0) return null;
        int colonPos = json.indexOf(':', keyPos + search.length());
        if (colonPos < 0) return null;
        int quoteStart = json.indexOf('"', colonPos + 1);
        if (quoteStart < 0) return null;
        int quoteEnd = json.indexOf('"', quoteStart + 1);
        if (quoteEnd < 0) return null;
        return json.substring(quoteStart + 1, quoteEnd);
    }

    /** Extract a JSON long value for a given key. */
    private static long extractJsonLong(String json, String key) {
        String val = extractJsonString(json, key);
        if (val == null) return 0;
        try { return Long.parseLong(val.replaceAll("[^0-9]", "")); }
        catch (NumberFormatException e) { return 0; }
    }

    /** Extract a balanced {} block starting at the given position. */
    private static String extractJsonBlock(String json, int start) {
        if (start >= json.length() || json.charAt(start) != '{') return null;
        int depth = 0;
        for (int i = start; i < json.length(); i++) {
            if (json.charAt(i) == '{') depth++;
            else if (json.charAt(i) == '}') {
                depth--;
                if (depth == 0) return json.substring(start, i + 1);
            }
        }
        return null;
    }

    // Package-private for testing
    static GpuInfo parseNvidiaLine(String line) {
        if (line.isEmpty()) return null;
        String[] parts = line.split(",\\s*");
        if (parts.length < 6) return null;
        try {
            return new GpuInfo(
                Integer.parseInt(parts[0].trim()),
                parts[1].trim(),
                Long.parseLong(parts[2].trim()),
                Long.parseLong(parts[3].trim()),
                Long.parseLong(parts[4].trim()),
                Integer.parseInt(parts[5].trim())
            );
        } catch (NumberFormatException e) {
            log.debug("Failed to parse nvidia-smi line: {}", line);
            return null;
        }
    }
}
