package org.wyrdsekai.core.recipe;

import org.wyrdsekai.core.gpu.GpuProbe;
import org.wyrdsekai.core.util.HardwareProbe;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The live {@link RecipeRunner.ResourceProbe} implementation — snapshots the node's
 * actual hardware + resolves the recipe's declared DATA_FILE / CLOUD_KEY targets, so
 * {@link ResourceRequisiteGate} can preflight against reality.
 *
 * <p>Cheap (millisecond-scale): GpuProbe + HardwareProbe + a free-disk stat + a few
 * Files.exists / System.getenv lookups, only for targets the manifest actually declares.
 * GPU VRAM uses each card's TOTAL capacity (not free) — "needs a 48GB-class card" is a
 * capability question; transient free-VRAM from a loaded prod model shouldn't make the
 * box look incapable (the run can stop inference first).
 */
public final class ResourceProbes {

    private ResourceProbes() {}

    public static ResourceRequisiteGate.Snapshot detect(RecipeManifest manifest) {
        // Per-GPU total VRAM (GB).
        List<Double> gpuVramGb = new ArrayList<>();
        try {
            for (GpuProbe.GpuInfo g : GpuProbe.detect()) {
                if (g.totalVramMB() > 0) gpuVramGb.add(g.totalVramMB() / 1024.0);
            }
        } catch (RuntimeException ignore) { /* no GPU / probe tool missing → empty */ }

        double freeRamGb;
        try {
            freeRamGb = HardwareProbe.availableRamGB();
        } catch (RuntimeException e) {
            freeRamGb = 0;
        }

        // Free disk on the partition holding the working/data dir.
        double freeDiskGb = 0;
        try {
            long usable = new File(System.getProperty("user.dir", ".")).getUsableSpace();
            freeDiskGb = usable / (1024.0 * 1024.0 * 1024.0);
        } catch (RuntimeException ignore) { /* keep 0 → fails closed for hard disk reqs */ }

        // Resolve only the data/env targets this recipe declares.
        Set<String> presentFiles = new HashSet<>();
        Set<String> presentEnvKeys = new HashSet<>();
        if (manifest != null) {
            String cwd = System.getProperty("user.dir", ".");
            for (ResourceRequirement r : manifest.requires()) {
                if (r.target() == null || r.target().isBlank()) continue;
                switch (r.kind()) {
                    case DATA_FILE -> {
                        if (fileExists(r.target(), cwd)) presentFiles.add(r.target());
                    }
                    case CLOUD_KEY -> {
                        String v = System.getenv(r.target());
                        if (v != null && !v.isBlank()) presentEnvKeys.add(r.target());
                    }
                    default -> { }
                }
            }
        }

        return new ResourceRequisiteGate.Snapshot(gpuVramGb, freeRamGb, freeDiskGb,
                presentFiles, presentEnvKeys);
    }

    private static boolean fileExists(String target, String cwd) {
        try {
            if (Files.exists(Path.of(target))) return true;
            return Files.exists(Path.of(cwd, target));
        } catch (RuntimeException e) {
            return false;
        }
    }
}
