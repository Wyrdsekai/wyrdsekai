package org.wyrdsekai.core.body;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryType;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
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
        /**
         * This reading with the heat it shares with the previous one: the lower of the two, and
         * none when either does not say. A single-core boost pins a package sensor at 100 °C for
         * seconds; heat that paces her is heat that holds (2026-10-22).
         */
        public Reading settledWith(Reading previous) {
            double held = previous == null || previous.hottestC < 0 || hottestC < 0 ? -1 : Math.min(hottestC, previous.hottestC);
            return held == hottestC ? this
                : new Reading(memoryStallPct10, heapPct, diskFreePct, load1, cpus, diskFreeBytes, held);
        }

        public boolean memoryPressure() { return memoryStallPct10 >= 10.0 || heapPct >= 90.0; }
        public boolean diskTight() { return diskFreePct >= 0 && diskFreePct < 10.0; }
        public boolean anyPressure() { return memoryPressure() || diskTight(); }

        /** The steward's gauges, one line. */
        public String gauges() {
            var sb = new StringBuilder();
            sb.append(heapPct >= 0 ? String.format(Locale.ROOT, "heap %.0f%%", heapPct) : "heap: no recent full collection");
            if (memoryStallPct10 >= 0) sb.append(String.format(Locale.ROOT, ", memory stall %.1f%%", memoryStallPct10));
            if (diskFreePct >= 0) sb.append(String.format(Locale.ROOT, ", disk free %.0f%% (%.1f GB)", diskFreePct, diskFreeBytes / 1e9));
            if (load1 >= 0) sb.append(String.format(Locale.ROOT, ", load %.2f/%d", load1, cpus));
            if (hottestC >= 0) sb.append(String.format(Locale.ROOT, ", hottest zone %.0f °C", hottestC));
            return sb.toString();
        }
    }

    private HostSense() {}

    /**
     * The heap as the live set, not the fill line. Used-over-max touches 95% every few seconds on a
     * healthy JVM, right before each collection (the reflex fired on that, 2026-09). The pools'
     * collection usage was the next try, and under the default collector the old generation's
     * collection usage is its fill line between mixed collections, garbage included: on the
     * household node it read 92% with 180 MB alive and paused her turns four times in a row while
     * the steward was in the room (2026-10-22). What means "nearly full" is what survived a
     * collection of the old generation; until one has run, the heap does not say.
     */
    static double heapAfterGcPct(Runtime rt) {
        if (rt.maxMemory() <= 0) return -1;
        try {
            var heapPools = new HashSet<String>();
            for (var pool : ManagementFactory.getMemoryPoolMXBeans()) {
                if (pool.getType() == MemoryType.HEAP) heapPools.add(pool.getName());
            }
            var afterByCollector = new LinkedHashMap<String, Long>();
            long uptime = ManagementFactory.getRuntimeMXBean().getUptime();
            for (var gc : ManagementFactory.getGarbageCollectorMXBeans()) {
                if (!(gc instanceof com.sun.management.GarbageCollectorMXBean sun)) continue;
                var info = sun.getLastGcInfo();
                if (info == null || !recentEnough(uptime, info.getEndTime(), LIVE_SET_STALE_AFTER_MS)) continue;
                long used = 0;
                for (var e : info.getMemoryUsageAfterGc().entrySet()) {
                    if (heapPools.contains(e.getKey()) && e.getValue() != null) used += e.getValue().getUsed();
                }
                afterByCollector.put(gc.getName(), used);
            }
            return liveAfterOldCollectionPct(rt.maxMemory(), afterByCollector);
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    /**
     * The heap in use after the last collection of the old generation, as a share of the maximum;
     * -1 when no such collection has run. The young collectors say nothing about the live set.
     */
    static double liveAfterOldCollectionPct(long maxBytes, Map<String, Long> usedAfterLastGcByCollector) {
        if (maxBytes <= 0 || usedAfterLastGcByCollector == null) return -1;
        for (var e : usedAfterLastGcByCollector.entrySet()) {
            if (e.getKey() != null && collectsTheOldGeneration(e.getKey()) && e.getValue() != null && e.getValue() >= 0) {
                return 100.0 * e.getValue() / maxBytes;
            }
        }
        return -1;
    }

    /**
     * A collection older than this says nothing about now: a burst that filled the heap and ended
     * leaves its full collection's "after" reading behind (the household node read 87% for an hour
     * with 280 MB in use, 2026-10-22). Were the heap still that full, the collector would run again.
     */
    static final long LIVE_SET_STALE_AFTER_MS = 5 * 60 * 1000;

    /** Whether a collection that ended at {@code gcEndMs} (ms since the JVM started) is recent enough to speak for now. */
    static boolean recentEnough(long uptimeMs, long gcEndMs, long maxAgeMs) {
        return gcEndMs >= 0 && uptimeMs - gcEndMs <= maxAgeMs;
    }

    /** The collectors that clear the old generation, by the names the runtimes give them. */
    static boolean collectsTheOldGeneration(String collector) {
        var n = collector.toLowerCase(Locale.ROOT);
        return n.contains("old") || n.contains("full") || n.contains("marksweep") || n.contains("tenured") || n.contains("major");
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
        return Math.max(readHottestZoneC(Path.of("/sys/class/thermal")), readHottestChipC(Path.of("/sys/class/hwmon")));
    }

    /** The chips whose sensors under {@code /sys/class/hwmon} are the processor's or the GPU's own heat. */
    static final java.util.Set<String> HWMON_CHIPS =
        java.util.Set.of("k10temp", "zenpower", "coretemp", "amdgpu", "cpu_thermal", "soc_thermal");

    /**
     * The hottest sensor of a processor or GPU chip under hwmon, in °C; -1 when there is none. A
     * Ryzen AI MAX household box reports its package heat only here (k10temp, Tctl): its one
     * thermal zone had vanished, and the slowdown never fired while it sat at 100 °C (2026-10-19).
     * A drive's or a battery's sensor is not the chip's and is not read.
     */
    public static double readHottestChipC(Path hwmon) {
        double hottest = -1;
        try {
            if (!Files.isDirectory(hwmon)) return -1;
            try (var chips = Files.list(hwmon)) {
                for (var chip : (Iterable<Path>) chips::iterator) {
                    String name;
                    try { name = Files.readString(chip.resolve("name")).strip(); }
                    catch (IOException | RuntimeException e) { continue; }
                    if (!HWMON_CHIPS.contains(name)) continue;
                    // A chip that labels a die sensor (Tdie, Tccd…) is read there: the control sensor
                    // (Tctl) runs an offset above the die on desktop Ryzens. A chip that labels only
                    // Tctl, as a Ryzen AI MAX does, is read at Tctl.
                    double die = -1, any = -1;
                    try (var files = Files.list(chip)) {
                        for (var f : (Iterable<Path>) files::iterator) {
                            var fn = f.getFileName().toString();
                            if (!fn.startsWith("temp") || !fn.endsWith("_input")) continue;
                            try {
                                double c = Double.parseDouble(Files.readString(f).strip()) / 1000.0;
                                if (!(c > 0 && c < 150)) continue;
                                if (c > any) any = c;
                                var label = chip.resolve(fn.substring(0, fn.length() - "_input".length()) + "_label");
                                if (Files.isRegularFile(label)) {
                                    var l = Files.readString(label).strip();
                                    if ((l.startsWith("Tdie") || l.startsWith("Tccd")) && c > die) die = c;
                                }
                            } catch (IOException | RuntimeException ignored) {
                                // this sensor does not say
                            }
                        }
                    }
                    double chipC = die > 0 ? die : any;
                    if (chipC > hottest) hottest = chipC;
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // the host does not say
        }
        return hottest;
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
