package org.wyrdsekai.core.coding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Run an artifact's entrypoint as a host subprocess, capturing
 * stdout / stderr / exit code into an {@link ExecResult}. Used by
 * backends whose workspaces materialise on disk (OpenHands,
 * OpenCode, CodePlane) —.
 *
 * <h2>Sandboxing</h2>
 *
 * Three levels, selected via the {@code WYRDSEKAI_SANDBOX_LEVEL}
 * env var (default: {@code 0} in tests, {@code 1} in production
 * once the L1 wrappers land):
 *
 * <ul>
 *   <li><b>L0</b> — bare {@link ProcessBuilder} with a curated env
 *       (PATH/HOME/LANG only). Acceptable for tests + dev. Networked
 *       and no fs sandbox; do NOT ship to prod with this.</li>
 *   <li><b>L1</b> — wrap the argv in {@code firejail --net=none
 *       --read-only=/etc} on Linux, {@code sandbox-exec -p
 *       '(version 1) (deny default) (allow file-read* file-write*
 *       (subpath WORKSPACE))'} on macOS. <b>Not yet implemented</b>
 *       — falls back to L0 with a WARN log and a one-shot reminder
 *       in the result's {@code stderr}.</li>
 *   <li><b>L2</b> — ephemeral container per invocation. Future work.</li>
 * </ul>
 *
 * <p>Wallclock cap is per-invocation; on miss, the process is force-killed and
 * the result carries {@code exitCode = -1, stderr += "[wallclock exceeded]"}.</p>
 */
public final class HostSubprocessRunner {

    private static final Logger log = LoggerFactory.getLogger(HostSubprocessRunner.class);

    /** Cap for captured stream size — anything larger is truncated. */
    private static final int MAX_CAPTURED_BYTES = 64 * 1024;

    private HostSubprocessRunner() {}

    /**
     * Run a command in a workspace directory. Always completes the
     * future (transport errors → ExecResult.success=false with the
     * reason in stderr), never raises.
     *
     * @param workspace      working directory (must exist)
     * @param argv           command + args to execute
     * @param extraArgs      argv appended after {@code argv}
     * @param env            extra environment variables; combined with
     *                       a minimal hygiene set (PATH, HOME, LANG)
     * @param wallclock      hard kill deadline
     */
    public static CompletableFuture<ExecResult> run(Path workspace,
                                                     List<String> argv,
                                                     List<String> extraArgs,
                                                     Map<String, String> env,
                                                     Duration wallclock) {
        if (workspace == null) {
            return CompletableFuture.completedFuture(new ExecResult(false, "", "",
                -1, Duration.ZERO, "", "no workspace path"));
        }
        if (argv == null || argv.isEmpty()) {
            return CompletableFuture.completedFuture(new ExecResult(false, "", "",
                -1, Duration.ZERO, "", "no entrypoint argv"));
        }
        var fullArgv = new ArrayList<>(argv);
        if (extraArgs != null) fullArgv.addAll(extraArgs);
        var cap = (wallclock == null || wallclock.isZero() || wallclock.isNegative())
            ? Duration.ofSeconds(30) : wallclock;

        return CompletableFuture.supplyAsync(() -> doRun(workspace, fullArgv, env, cap));
    }

    private static ExecResult doRun(Path workspace, List<String> argv,
                                    Map<String, String> env, Duration cap) {
        var entrypointStr = String.join(" ", argv);
        var started = Instant.now();
        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.directory(workspace.toFile());
        pb.redirectErrorStream(false);

        // Curated env hygiene: drop the parent process env entirely,
        // then overlay only the keys we know are safe + caller-provided
        // overrides. This is L0; L1+L2 add stronger isolation around
        // the same call site.
        var procEnv = pb.environment();
        procEnv.clear();
        var systemPath = System.getenv("PATH");
        if (systemPath != null && !systemPath.isBlank()) procEnv.put("PATH", systemPath);
        var home = System.getenv("HOME");
        if (home != null && !home.isBlank()) procEnv.put("HOME", home);
        procEnv.put("LANG", "C.UTF-8");
        if (env != null) {
            for (var e : env.entrySet()) {
                if (e.getKey() != null && !e.getKey().isBlank() && e.getValue() != null) {
                    procEnv.put(e.getKey(), e.getValue());
                }
            }
        }

        Process proc;
        try {
            proc = pb.start();
        } catch (IOException e) {
            return new ExecResult(false, "", "spawn failed: " + e.getMessage(),
                -1, Duration.between(started, Instant.now()), entrypointStr, null);
        }

        // Drain stdout + stderr concurrently so neither blocks the
        // process by filling its pipe buffer.
        var stdoutFuture = drainAsync(proc.getInputStream());
        var stderrFuture = drainAsync(proc.getErrorStream());

        boolean finished;
        try {
            finished = proc.waitFor(cap.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            proc.destroyForcibly();
            return new ExecResult(false, awaitOrEmpty(stdoutFuture),
                awaitOrEmpty(stderrFuture) + "\n[interrupted]",
                -1, Duration.between(started, Instant.now()), entrypointStr, null);
        }
        if (!finished) {
            proc.destroyForcibly();
            return new ExecResult(false, awaitOrEmpty(stdoutFuture),
                awaitOrEmpty(stderrFuture) + "\n[wallclock exceeded after "
                    + cap.toSeconds() + "s]",
                -1, Duration.between(started, Instant.now()), entrypointStr, null);
        }

        int exit = proc.exitValue();
        var duration = Duration.between(started, Instant.now());
        var stdout = awaitOrEmpty(stdoutFuture);
        var stderr = awaitOrEmpty(stderrFuture);

        log.debug("HostSubprocessRunner: '{}' exited {} after {}ms (stdout={}b, stderr={}b)",
            entrypointStr, exit, duration.toMillis(), stdout.length(), stderr.length());

        return new ExecResult(exit == 0, stdout, stderr, exit,
            duration, entrypointStr, null);
    }

    private static CompletableFuture<String> drainAsync(InputStream stream) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                var buf = new ByteArrayOutputStream();
                var chunk = new byte[4096];
                int read;
                int total = 0;
                while ((read = stream.read(chunk)) >= 0) {
                    if (total + read <= MAX_CAPTURED_BYTES) {
                        buf.write(chunk, 0, read);
                        total += read;
                    } else {
                        // Discard but keep reading so the child doesn't
                        // block on a full pipe.
                        total += read;
                    }
                }
                var s = buf.toString(StandardCharsets.UTF_8);
                if (total > MAX_CAPTURED_BYTES) {
                    s = s + "\n[…truncated " + (total - MAX_CAPTURED_BYTES) + "B]";
                }
                return s;
            } catch (IOException e) {
                return "[stream read failed: " + e.getMessage() + "]";
            }
        });
    }

    private static String awaitOrEmpty(CompletableFuture<String> f) {
        try {
            return f.get(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "";
        }
    }
}
