package org.wyrdsekai.core.recipe;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Pure-logic preflight gate for a recipe's declared {@code requires:} block
 * (sibling of {@link WelfareGate}). Evaluates each {@link ResourceRequirement}
 * against a hardware/data {@link Snapshot} the runner supplies — no side effects,
 * no probing here (kept testable). The {@link RecipeRunner} runs this before
 * step 1; an unmet HARD requirement blocks the run and the runner turns the
 * {@link Decision} into a structured resource-request (steward-ask, or — Phase 2 —
 * a peer-zone borrow). Unmet SOFT requirements only warn.
 *
 * <p>Semantics worth noting: {@code GPU_VRAM_GB≥V} is evaluated as "at least
 * {@code GPU_COUNT}-many GPUs each have ≥V VRAM" (GPU_COUNT defaults to 1 if not
 * declared) — so "2×48GB" is expressed as GPU_COUNT≥2 + GPU_VRAM_GB≥48 and only
 * passes on a box with two ≥48GB cards. {@code WALL_CLOCK_MIN} never blocks (it's
 * an estimate for consent/scheduling). DATA_FILE / CLOUD_KEY presence is resolved
 * by the runner into the snapshot's {@code presentFiles}/{@code presentEnvKeys} sets.
 */
public final class ResourceRequisiteGate {

    private ResourceRequisiteGate() {}

    /**
     * Current node capability + resolved data/env presence. The runner builds this
     * from GpuProbe + HardwareProbe + a free-disk check + Files.exists/System.getenv.
     * Defensive: nulls degrade to "nothing available" so a partial snapshot fails
     * closed for HARD reqs (correct — better to ask than to thrash) but a recipe
     * with no requires still runs (the gate returns ALLOW on an empty list).
     *
     * @param gpuVramGb      per-GPU VRAM (GB) of each present GPU; empty = no GPU
     * @param freeRamGb      free system RAM (GB)
     * @param freeDiskGb     free space (GB) on the data volume
     * @param presentFiles   repo-relative DATA_FILE targets that actually exist
     * @param presentEnvKeys CLOUD_KEY env-var names that are set + non-blank
     */
    public record Snapshot(List<Double> gpuVramGb, double freeRamGb, double freeDiskGb,
                           Set<String> presentFiles, Set<String> presentEnvKeys) {
        public Snapshot {
            gpuVramGb = (gpuVramGb == null) ? List.of() : List.copyOf(gpuVramGb);
            presentFiles = (presentFiles == null) ? Set.of() : Set.copyOf(presentFiles);
            presentEnvKeys = (presentEnvKeys == null) ? Set.of() : Set.copyOf(presentEnvKeys);
        }
        public int gpuCount() { return gpuVramGb.size(); }
        public long gpusWithAtLeast(double vramGb) {
            return gpuVramGb.stream().filter(v -> v != null && v >= vramGb).count();
        }
    }

    public enum DenyReason {
        ALLOW,
        GPU_COUNT_INSUFFICIENT,
        GPU_VRAM_INSUFFICIENT,
        RAM_INSUFFICIENT,
        DISK_INSUFFICIENT,
        DATA_FILE_MISSING,
        CLOUD_KEY_MISSING
    }

    /**
     * Result. {@code allow} is true iff no HARD requirement is unmet. {@code unmetHard}
     * drives the block + resource-request; {@code unmetSoft} drives advisory warnings.
     */
    public record Decision(boolean allow, DenyReason firstReason, String detail,
                           List<ResourceRequirement> unmetHard, List<ResourceRequirement> unmetSoft) {
        public Decision {
            unmetHard = (unmetHard == null) ? List.of() : List.copyOf(unmetHard);
            unmetSoft = (unmetSoft == null) ? List.of() : List.copyOf(unmetSoft);
        }
        public static Decision allowed(List<ResourceRequirement> unmetSoft) {
            return new Decision(true, DenyReason.ALLOW, null, List.of(), unmetSoft);
        }
        /** One-line render for the denial / log, e.g. "GPU_COUNT≥2 (hard): have 1". */
        public String summary() {
            if (allow) return "resource requisites met"
                    + (unmetSoft.isEmpty() ? "" : " (" + unmetSoft.size() + " soft advisory)");
            var sb = new StringBuilder("unmet hard requisites: ");
            for (int i = 0; i < unmetHard.size(); i++) {
                if (i > 0) sb.append("; ");
                sb.append(unmetHard.get(i).describe());
            }
            if (detail != null) sb.append(" — ").append(detail);
            return sb.toString();
        }
    }

    public static Decision evaluate(List<ResourceRequirement> requires, Snapshot snap) {
        if (requires == null || requires.isEmpty()) return Decision.allowed(List.of());
        if (snap == null) {
            snap = new Snapshot(List.of(), 0, 0, Set.of(), Set.of());
        }
        // The declared GPU count couples with the per-GPU VRAM check.
        int requiredGpuCount = 1;
        for (var r : requires) {
            if (r.kind() == ResourceRequirement.Kind.GPU_COUNT) {
                requiredGpuCount = Math.max(requiredGpuCount, (int) Math.ceil(r.amount()));
            }
        }

        var unmetHard = new ArrayList<ResourceRequirement>();
        var unmetSoft = new ArrayList<ResourceRequirement>();
        DenyReason firstReason = DenyReason.ALLOW;
        String firstDetail = null;

        for (var r : requires) {
            boolean met;
            String have;
            switch (r.kind()) {
                case GPU_COUNT -> {
                    met = snap.gpuCount() >= r.amount();
                    have = "have " + snap.gpuCount();
                }
                case GPU_VRAM_GB -> {
                    // Need `requiredGpuCount` GPUs each with ≥ amount VRAM.
                    met = snap.gpusWithAtLeast(r.amount()) >= requiredGpuCount;
                    have = "have " + snap.gpusWithAtLeast(r.amount()) + " of " + requiredGpuCount
                            + " GPU(s) ≥" + (int) r.amount() + "GB";
                }
                case DISK_FREE_GB -> {
                    met = snap.freeDiskGb() >= r.amount();
                    have = "have " + Math.round(snap.freeDiskGb()) + "GB free";
                }
                case RAM_GB -> {
                    met = snap.freeRamGb() >= r.amount();
                    have = "have " + Math.round(snap.freeRamGb()) + "GB free";
                }
                case WALL_CLOCK_MIN -> { met = true; have = null; }   // estimate only, never blocks
                case DATA_FILE -> {
                    met = r.target() != null && snap.presentFiles().contains(r.target());
                    have = met ? "present" : "missing";
                }
                case CLOUD_KEY -> {
                    met = r.target() != null && snap.presentEnvKeys().contains(r.target());
                    have = met ? "set" : "not set";
                }
                default -> { met = true; have = null; }
            }
            if (met) continue;
            if (r.hard()) {
                unmetHard.add(r);
                if (firstReason == DenyReason.ALLOW) {
                    firstReason = reasonFor(r.kind());
                    firstDetail = r.describe() + ": " + have
                            + (r.note().isBlank() ? "" : " — " + r.note());
                }
            } else {
                unmetSoft.add(r);
            }
        }

        if (unmetHard.isEmpty()) return Decision.allowed(unmetSoft);
        return new Decision(false, firstReason, firstDetail, unmetHard, unmetSoft);
    }

    private static DenyReason reasonFor(ResourceRequirement.Kind k) {
        return switch (k) {
            case GPU_COUNT -> DenyReason.GPU_COUNT_INSUFFICIENT;
            case GPU_VRAM_GB -> DenyReason.GPU_VRAM_INSUFFICIENT;
            case RAM_GB -> DenyReason.RAM_INSUFFICIENT;
            case DISK_FREE_GB -> DenyReason.DISK_INSUFFICIENT;
            case DATA_FILE -> DenyReason.DATA_FILE_MISSING;
            case CLOUD_KEY -> DenyReason.CLOUD_KEY_MISSING;
            case WALL_CLOCK_MIN -> DenyReason.ALLOW;
        };
    }
}
