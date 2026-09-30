package org.wyrdsekai.scripting.sandbox;

/**
 * Resource limits for GraalJS script sandboxes (§30).
 * Configurable per-room or per-zone.
 *
 * @param statementLimit    Maximum JS statements before termination (0 = unlimited)
 * @param cpuTimeoutMs      Maximum time in milliseconds (0 = unlimited). Room scripts count the CPU
 *                          time of the thread running the hook; item scripts count wall-clock time.
 * @param heapLimitBytes    Maximum heap allocation in bytes (0 = unlimited)
 * @param stackDepthLimit   Maximum call stack depth (0 = unlimited)
 */
public record ResourceLimits(
    long statementLimit,
    long cpuTimeoutMs,
    long heapLimitBytes,
    int stackDepthLimit
) {
    /** Default production limits. */
    public static final ResourceLimits DEFAULT = new ResourceLimits(
        10_000,        // 10K statements
        5_000,         // 5 seconds wall-clock
        16_777_216,    // 16 MB heap
        100            // 100-deep call stack
    );

    /** Relaxed limits for trusted rooms (foundation zone). */
    public static final ResourceLimits TRUSTED = new ResourceLimits(
        50_000,        // 50K statements
        10_000,        // 10 seconds
        33_554_432,    // 32 MB heap
        200
    );

    /** Strict limits for untrusted/user-created rooms. */
    public static final ResourceLimits STRICT = new ResourceLimits(
        5_000,         // 5K statements
        2_000,         // 2 seconds
        8_388_608,     // 8 MB heap
        50
    );

    /** Item script limits — generous timeout for LLM calls, moderate statement limit. */
    public static final ResourceLimits ITEM_SCRIPT = new ResourceLimits(
        25_000,        // 25K statements (scripts chain multiple service calls)
        120_000,       // 120 seconds wall-clock (LLM calls take 10-30s each on M4)
        33_554_432,    // 32 MB heap
        100
    );

    /**
     * Room hooks. Measured 2026-09-28 across every bundled room script (scripts/rooms, scripts/std/room),
     * every hook and every command word the scripts branch on: the heaviest took 256 statements and
     * 7 ms of CPU. A runaway loop reaches a million statements in well under a second, so the room
     * gets its thread back quickly; the CPU limit catches work that runs no statements (a runaway
     * regular expression) and host work done on the room's thread.
     */
    public static final ResourceLimits ROOM_SCRIPT = new ResourceLimits(
        1_000_000,     // 1M statements per hook call
        5_000,         // 5 seconds of CPU time per hook call
        0,             // heap limits need GraalVM EE
        0
    );

    /** No limits (testing only). */
    public static final ResourceLimits UNLIMITED = new ResourceLimits(0, 0, 0, 0);

    /** Check if statement limit is configured. */
    public boolean hasStatementLimit() { return statementLimit > 0; }

    /** Check if CPU timeout is configured. */
    public boolean hasCpuTimeout() { return cpuTimeoutMs > 0; }

    /** Check if heap limit is configured. */
    public boolean hasHeapLimit() { return heapLimitBytes > 0; }

    /** Human-readable summary. */
    public String describe() {
        var sb = new StringBuilder("ResourceLimits[");
        if (hasStatementLimit()) sb.append("stmts=").append(statementLimit).append(", ");
        else sb.append("stmts=unlimited, ");
        if (hasCpuTimeout()) sb.append("cpu=").append(cpuTimeoutMs).append("ms, ");
        else sb.append("cpu=unlimited, ");
        if (hasHeapLimit()) sb.append("heap=").append(heapLimitBytes / 1024).append("KB");
        else sb.append("heap=unlimited");
        sb.append("]");
        return sb.toString();
    }
}
