package org.wyrdsekai.core.body;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * What the host will tell us without being asked: memory pressure, heap, disk, load. This is
 * interoception's raw material. It never becomes a chart in her window; it becomes one clause
 * when something is wrong and a gauge in the boiler room the rest of the time.
 *
 * <p>Linux exposes pressure-stall information; on other hosts the memory reading is the
 * JVM's own. Every reading is best effort and never throws.</p>
 */
public final class HostSense {

    /** One reading. Negative means "the host does not say". */
    public record Reading(double memoryStallPct10, double heapPct, double diskFreePct,
                          double load1, int cpus, long diskFreeBytes) {

        public boolean memoryPressure() { return memoryStallPct10 >= 10.0 || heapPct >= 90.0; }
        public boolean diskTight() { return diskFreePct >= 0 && diskFreePct < 10.0; }
        public boolean anyPressure() { return memoryPressure() || diskTight(); }

        /** The steward's gauges, one line. */
        public String gauges() {
            var sb = new StringBuilder();
            sb.append(String.format(Locale.ROOT, "heap %.0f%%", heapPct));
            if (memoryStallPct10 >= 0) sb.append(String.format(Locale.ROOT, ", memory stall %.1f%%", memoryStallPct10));
            if (diskFreePct >= 0) sb.append(String.format(Locale.ROOT, ", disk free %.0f%% (%.1f GB)", diskFreePct, diskFreeBytes / 1e9));
            if (load1 >= 0) sb.append(String.format(Locale.ROOT, ", load %.2f/%d", load1, cpus));
            return sb.toString();
        }
    }

    private HostSense() {}

    public static Reading read(Path dataDir) {
        var rt = Runtime.getRuntime();
        double heapPct = rt.maxMemory() > 0
            ? 100.0 * (rt.totalMemory() - rt.freeMemory()) / rt.maxMemory() : -1;
        double stall = readMemoryStall();
        double diskFree = -1; long diskBytes = -1;
        try {
            if (dataDir != null && Files.exists(dataDir)) {
                FileStore fs = Files.getFileStore(dataDir);
                long total = fs.getTotalSpace();
                diskBytes = fs.getUsableSpace();
                if (total > 0) diskFree = 100.0 * diskBytes / total;
            }
        } catch (IOException | RuntimeException ignored) {
            // the disk does not say
        }
        double load = -1;
        try {
            load = ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
        } catch (RuntimeException ignored) {
            // not on this host
        }
        return new Reading(stall, heapPct, diskFree, load, rt.availableProcessors(), diskBytes);
    }

    /** Linux PSI: {@code some avg10=…} in /proc/pressure/memory, as a percentage of time stalled. */
    static double readMemoryStall() {
        var psi = Path.of("/proc/pressure/memory");
        try {
            if (!Files.isReadable(psi)) return -1;
            for (var line : Files.readAllLines(psi)) {
                if (!line.startsWith("some")) continue;
                for (var tok : line.split("\\s+")) {
                    if (tok.startsWith("avg10=")) return Double.parseDouble(tok.substring(6));
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // the host does not say
        }
        return -1;
    }
}
