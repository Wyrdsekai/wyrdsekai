package org.wyrdsekai.core.recipe;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Default {@link CommandRunner} — runs {@code bash -c <command>} in a working directory with a
 * per-command wall-clock ( watchdog). On timeout the process tree is destroyed
 * and exit code 124 (GNU {@code timeout(1)} convention) is returned with {@code transientFailure=true},
 * so a hanging command can never stall a recipe indefinitely AND the runner can decide to retry.
 *
 * <p>#1012 — per-call timeout override via {@link #run(String, java.time.Duration)} takes precedence
 * over the constructor default; transient failures (timeout, start failure, SIGKILL/137) are tagged
 * for the runner's retry path. Logical non-zero exits stay non-transient — they don't get retried.
 */
public final class ProcessCommandRunner implements CommandRunner {

    private static final int EXIT_TIMEOUT = 124;   // GNU timeout(1) convention
    private static final int EXIT_START_FAIL = -1;
    private static final int EXIT_SIGKILL = 137;   // 128 + SIGKILL (9), typically OOM-killer

    private final File workingDir;
    private final Duration defaultTimeout;

    public ProcessCommandRunner(File workingDir, Duration timeout) {
        this.workingDir = workingDir;
        this.defaultTimeout = timeout == null ? Duration.ofMinutes(5) : timeout;
    }

    @Override
    public CommandRunner.Result run(String command) {
        return run(command, defaultTimeout);
    }

    @Override
    public CommandRunner.Result run(String command, Duration timeout) {
        Duration effective = timeout == null ? defaultTimeout : timeout;
        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", command)
                    .directory(workingDir)
                    .redirectErrorStream(false);
            // Prepend the recipe venv bin dir to PATH so bare `python`/`python3`
            // resolves to the recipe-bootstrapped interpreter (which has numpy,
            // sklearn, onnx, etc. preinstalled). Without this, .pkg/.deb daemons
            // run with the system PATH which on macOS has no `python` at all.
            // Discovered 2026-05-27 on mac-node — train step exited 127.
            String dataDir = System.getenv("WYRDSEKAI_DATA_DIR");
            if (dataDir != null && !dataDir.isBlank()) {
                File venvBin = new File(dataDir, ".venv-recipes/bin");
                if (venvBin.isDirectory()) {
                    var env = pb.environment();
                    String existing = env.getOrDefault("PATH", "");
                    env.put("PATH", venvBin.getAbsolutePath()
                            + (existing.isEmpty() ? "" : ":" + existing));
                }
            }
            Process p = pb.start();
            byte[] out = p.getInputStream().readAllBytes();
            byte[] err = p.getErrorStream().readAllBytes();
            boolean finished = p.waitFor(effective.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
                return new CommandRunner.Result(EXIT_TIMEOUT,
                        new String(out, StandardCharsets.UTF_8),
                        "recipe: command timed out after " + effective.toSeconds() + "s",
                        true);
            }
            int exit = p.exitValue();
            // SIGKILL/OOM is infra, not logic — let the runner retry it.
            boolean transient_ = (exit == EXIT_SIGKILL);
            return new CommandRunner.Result(exit,
                    new String(out, StandardCharsets.UTF_8),
                    new String(err, StandardCharsets.UTF_8),
                    transient_);
        } catch (Exception e) {
            return new CommandRunner.Result(EXIT_START_FAIL, "",
                    "recipe: command failed to start: " + e.getMessage(),
                    true);
        }
    }
}
