package org.wyrdsekai.core.body;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryType;
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
                          double load1, int cpus, long diskFreeBytes, double hottestC) {
        /** A reading with no temperature (the host does not say). */
        public Reading(double memoryStallPct10, double heapPct, double diskFreePct,
                       double load1, int cpus, long diskFreeBytes) {
            this(memoryStallPct10, heapPct, diskFreePct, load1, cpus, diskFreeBytes, -1);
        }
        /** The one-minute load as a share of the cores; negative when the host does not say. */
        public double loadPerCore() { return load1 < 0 || cpus <= 0 ? -1 : load1 / cpus; }
        /** The processor is spoken for: more runnable work than cores for the last minute. */
        public boolean pinned() { return loadPerCore() >= 0.9; }
        /** A thermal zone reads at or past 90 °C. */
        public boolean hot() { return hottestC >= 90.0; }

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
            if (hottestC >= 0) sb.append(String.format(Locale.ROOT, ", hottest zone %.0f °C", hottestC));
            return sb.toString();
        }
    }

    private HostSense() {}

    /**
     * The heap as the live set, not the fill line. Used-over-max from the runtime touches 95%
     * every few seconds on a healthy JVM, right before each collection: the heap-full reflex
     * fired on that three times in a row on the household node and paused her for three
     * minutes. The number that means something is what survived the last collection, which
     * the memory pools report as collection usage. Falls back to used-over-max where a pool
     * cannot say.
     */
    static double heapAfterGcPct(Runtime rt) {
        if (rt.maxMemory() <= 0) return -1;
        long live = 0; boolean any = false;
        try {
            for (var pool : ManagementFactory.getMemoryPoolMXBeans()) {
                if (pool.getType() != MemoryType.HEAP || !pool.isCollectionUsageThresholdSupported()) continue;
                var cu = pool.getCollectionUsage();
                if (cu == null) continue;
                live += cu.getUsed();
                any = true;
            }
        } catch (RuntimeException ignored) {
            any = false;
        }
        if (!any) return 100.0 * (rt.totalMemory() - rt.freeMemory()) / rt.maxMemory();
        return 100.0 * live / rt.maxMemory();
    }

    public static Reading read(Path dataDir) {
        var rt = Runtime.getRuntime();
        double heapPct = heapAfterGcPct(rt);
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
        return new Reading(stall, heapPct, diskFree, load, rt.availableProcessors(), diskBytes, readHottestZoneC());
    }

    /**
     * The hottest thermal zone the kernel reports, in °C; -1 when there is none. A household
     * box ran its companions' model on the processor until the chip's own thermal limit reset
     * it (2026-10-05); the reading is what lets her own time slow before that.
     */
    static double readHottestZoneC() {
        return readHottestZoneC(Path.of("/sys/class/thermal"));
    }

    public static double readHottestZoneC(Path thermal) {
        double hottest = -1;
        try {
            if (!Files.isDirectory(thermal)) return -1;
            try (var zones = Files.list(thermal)) {
                for (var zone : (Iterable<Path>) zones::iterator) {
                    if (!zone.getFileName().toString().startsWith("thermal_zone")) continue;
                    var temp = zone.resolve("temp");
                    if (!Files.isReadable(temp)) continue;
                    try {
                        double c = Double.parseDouble(Files.readString(temp).strip()) / 1000.0;
                        if (c > 0 && c < 150 && c > hottest) hottest = c;   // -273 and 0 are "not wired"
                    } catch (IOException | RuntimeException ignored) {
                        // this zone does not say
                    }
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // the host does not say
        }
        return hottest;
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
