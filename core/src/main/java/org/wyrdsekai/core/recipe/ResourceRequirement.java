package org.wyrdsekai.core.recipe;

/**
 * A single declared resource need of a recipe (the {@code requires:} block).
 *
 * <p>Why this exists: heavy recipes (GRPO/RFT, SFT, steering-vector extraction)
 * consume real hardware for real wall-clock — emit-RFT (the V6 run) was ~17h on
 * 2×48GB GPUs. Before this, those needs lived only as prose comments in the YAML,
 * invisible to the runner. An agent could launch a 17-hour 2-GPU job on a box that
 * can't satisfy it and either thrash or silently monopolize the household's GPU.
 *
 * <p>Now each requirement is machine-readable and preflight-checked by
 * {@link ResourceRequisiteGate} before step 1. The {@code hard} flag is the
 * enforcement lever: an unmet HARD requirement blocks the run and surfaces a
 * structured resource-request (steward-ask, or — Phase 2 — borrow a peer zone)
 * instead of running; an unmet SOFT requirement only warns. A recipe is "heavy"
 * precisely by declaring HARD hardware needs; light recipes declare none (or soft
 * hints), so they stay frictionless — no paternalism ladder on cheap maintenance.
 *
 * @param kind   what is needed
 * @param amount numeric magnitude; semantics per {@link Kind} (GB, count, minutes); 0 if N/A
 * @param target string target for non-numeric kinds (env-var name, file path); null otherwise
 * @param hard   true = block the run if unmet (→ resource-request); false = warn and proceed
 * @param note   human-readable rationale, surfaced in the denial / request reason
 */
public record ResourceRequirement(Kind kind, double amount, String target, boolean hard, String note) {

    public enum Kind {
        /** Minimum number of GPUs that must be present. amount = count. */
        GPU_COUNT,
        /** Minimum per-GPU VRAM. amount = GB. (Checked against each present GPU.) */
        GPU_VRAM_GB,
        /** Minimum free disk on the data volume. amount = GB. */
        DISK_FREE_GB,
        /** Minimum free system RAM. amount = GB. */
        RAM_GB,
        /** Estimated wall-clock — ALWAYS advisory (informs consent, never blocks). amount = minutes. */
        WALL_CLOCK_MIN,
        /** A cloud API key must be present in the environment. target = env-var name. */
        CLOUD_KEY,
        /** A data file must exist (corpus, rollout bank, reward fn). target = repo-relative path. */
        DATA_FILE
    }

    public ResourceRequirement {
        if (amount < 0) amount = 0;
        if (note == null) note = "";
        // WALL_CLOCK is an estimate for consent/scheduling — it can never hard-block.
        if (kind == Kind.WALL_CLOCK_MIN) hard = false;
    }

    /** Convenience: a hard numeric requirement with no string target. */
    public static ResourceRequirement hard(Kind kind, double amount, String note) {
        return new ResourceRequirement(kind, amount, null, true, note);
    }

    /** Convenience: a soft (advisory) numeric requirement. */
    public static ResourceRequirement soft(Kind kind, double amount, String note) {
        return new ResourceRequirement(kind, amount, null, false, note);
    }

    /** One-line render for denials / logs, e.g. "GPU_VRAM_GB≥48 (hard)". */
    public String describe() {
        String body = switch (kind) {
            case GPU_COUNT -> "GPU_COUNT≥" + (int) amount;
            case GPU_VRAM_GB -> "GPU_VRAM_GB≥" + (int) amount;
            case DISK_FREE_GB -> "DISK_FREE_GB≥" + (int) amount;
            case RAM_GB -> "RAM_GB≥" + (int) amount;
            case WALL_CLOCK_MIN -> "~" + (int) amount + "min";
            case CLOUD_KEY -> "CLOUD_KEY=" + target;
            case DATA_FILE -> "DATA_FILE=" + target;
        };
        return body + " (" + (hard ? "hard" : "soft") + ")";
    }
}
