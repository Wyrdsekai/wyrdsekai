package org.wyrdsekai.core.recipe;

import java.time.Duration;

/**
 * Runs a shell command for a SHELL/rollback recipe step. Injected so
 * the runner is unit-testable with a stub and so the per-command wall-clock (§4 watchdog)
 * lives in one place.
 *
 * <p>#1012 — the runner passes a per-step timeout via {@link #run(String, Duration)};
 * impls that don't honour overrides fall back to the legacy {@link #run(String)} path,
 * so existing test stubs keep working unchanged.
 */
public interface CommandRunner {

    Result run(String command);

    /**
     * Per-call timeout override (preferred entry point — the recipe runner threads the
     * effective step timeout here so each step's wall-clock is bounded independently).
     * Default impl ignores the override so existing stub impls keep compiling.
     */
    default Result run(String command, Duration timeout) {
        return run(command);
    }

    /**
     * {@code stdout} is captured so a step can emit a JSON object that merges into the run context.
     * {@code transientFailure} flags an outcome where the failure was infrastructural rather than
     * logical (timeout, OOM-kill, start failure) — the runner consults this when deciding whether
     * to retry per the recipe's {@code retry_count} ( OPEN-R3 / #1012).
     */
    record Result(int exitCode, String stdout, String stderr, boolean transientFailure) {
        public Result(int exitCode, String stdout, String stderr) {
            this(exitCode, stdout, stderr, false);
        }
        public boolean ok() { return exitCode == 0; }
    }
}
