package org.wyrdsekai.core.forge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.config.WyrdConfig;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The wire between what she felt and what sinks in: at sleep completion,
 * consolidate the day's felt-stamped moments into a micro-LoRA on the voice
 * brain, each moment's gradient weighted by her tank state at the instant
 * she spoke (scripts/training/sleepwrite/sleep_write.py — the script holds
 * the hard gates; a failed gate ships nothing).
 *
 * <p>ClassifierForge's contract, deliberately: env-gated
 * ({@code WYRDSEKAI_SLEEP_WRITE=1}), fire-and-forget, never blocks or fails
 * sleep completion. Single-flight — a sleep that fires while the previous
 * write still runs is skipped, not queued: each write is one night's
 * consolidation and the window catches up on its own.
 *
 * <p>The staged artifact is a detachable adapter
 * ({@code $DATA/adapters/sleepwrite/current.gguf}) served by the voice
 * container's existing {@code LLAMA_VOICE_ADAPTER} path on its next start.
 * Rollback is removing one file. The base weights never change; the soul
 * stays portable.
 */
public final class SleepWeightWrite {

    private static final Logger log = LoggerFactory.getLogger(SleepWeightWrite.class);

    public static final String ENABLE_ENV = "WYRDSEKAI_SLEEP_WRITE";
    public static final String VENV_ENV = "WYRDSEKAI_SLEEP_WRITE_VENV";
    /** Set to 0/false to leave staged adapters for a manual `wyrd sleepwrite apply`. */
    public static final String AUTO_APPLY_ENV = "WYRDSEKAI_SLEEP_WRITE_AUTO_APPLY";

    private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean(false);
    private static final long TIMEOUT_MINUTES = 30;

    private SleepWeightWrite() {}

    /** True when the household enabled the nightly weight-write. */
    public static boolean enabled() {
        var env = System.getenv(ENABLE_ENV);
        if (env == null) env = System.getProperty("wyrdsekai.sleep.write");
        return "1".equals(env) || "true".equalsIgnoreCase(env);
    }

    /**
     * Fire the night's write. Returns immediately; the subprocess runs on a
     * virtual thread and reports through the log. Never throws.
     */
    public static void fireAndForget(String agentName) {
        if (!enabled()) return;
        var script = resolveScript();
        if (script == null) {
            log.warn("Sleep weight-write enabled but "
                + "scripts/training/sleepwrite/sleep_write.py not found — skipped");
            return;
        }
        if (!IN_FLIGHT.compareAndSet(false, true)) {
            log.info("Sleep weight-write for '{}' skipped — previous write still "
                + "running (single-flight; the window catches up next sleep)", agentName);
            return;
        }
        Thread.ofVirtual().name("sleep-weight-write").start(() -> {
            try {
                run(agentName, script);
            } catch (Exception e) {
                log.warn("Sleep weight-write for '{}' errored: {}", agentName, e.toString());
            } finally {
                IN_FLIGHT.set(false);
            }
        });
    }

    private static void run(String agentName, Path script) throws Exception {
        var interpreter = interpreter();
        log.info("Sleep weight-write for '{}': {} {}", agentName, interpreter, script);
        var pb = new ProcessBuilder(interpreter, script.toString());
        pb.redirectErrorStream(true);
        var proc = pb.start();
        var tail = new ArrayDeque<String>(12);
        try (var r = new BufferedReader(new InputStreamReader(
                proc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (tail.size() == 12) tail.removeFirst();
                tail.addLast(line);
            }
        }
        if (!proc.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
            proc.destroyForcibly();
            log.warn("Sleep weight-write for '{}' timed out after {} min — killed; "
                + "nothing staged", agentName, TIMEOUT_MINUTES);
            return;
        }
        var summary = String.join(" | ", tail);
        switch (proc.exitValue()) {
            case 0 -> {
                log.info("Sleep weight-write for '{}' STAGED — the day sank in. {}",
                    agentName, summary);
                autoApply(agentName);
            }
            case 3 -> log.info("Sleep weight-write for '{}': quiet day, nothing to "
                + "consolidate. {}", agentName, summary);
            case 4 -> log.warn("Sleep weight-write for '{}': GATE FAILED — nothing "
                + "staged (adapter kept for autopsy). {}", agentName, summary);
            default -> log.warn("Sleep weight-write for '{}' failed (exit {}). {}",
                agentName, proc.exitValue(), summary);
        }
    }

    /**
     * Auto-apply-at-wake: a staged adapter should reach her voice without a
     * human remembering to run `apply`. Delegates to `wyrd sleepwrite apply
     * --if-idle`, which refuses to bounce the voice while a reply is in
     * flight (she may already be awake by the time the write finishes) and
     * leaves the adapter staged for the next restart instead. Disable with
     * WYRDSEKAI_SLEEP_WRITE_AUTO_APPLY=0.
     */
    private static void autoApply(String agentName) {
        var env = System.getenv(AUTO_APPLY_ENV);
        if (env == null) env = System.getProperty("wyrdsekai.sleep.write.auto.apply");
        if ("0".equals(env) || "false".equalsIgnoreCase(env)) {
            log.info("Sleep weight-write auto-apply disabled — adapter staged for "
                + "manual `wyrd sleepwrite apply`");
            return;
        }
        var wyrd = resolveWyrd();
        if (wyrd == null) {
            log.warn("Sleep weight-write: wyrd CLI not found — adapter staged, "
                + "applies at next voice restart");
            return;
        }
        try {
            var pb = new ProcessBuilder(wyrd.toString(), "sleepwrite", "apply", "--if-idle");
            pb.redirectErrorStream(true);
            var proc = pb.start();
            var out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            proc.waitFor(3, TimeUnit.MINUTES);
            log.info("Sleep weight-write auto-apply for '{}' (exit {}): {}",
                agentName, proc.isAlive() ? "timeout" : proc.exitValue(),
                out.replaceAll("\s+", " ").trim());
        } catch (Exception e) {
            log.warn("Sleep weight-write auto-apply failed for '{}': {} — adapter "
                + "staged, applies at next voice restart", agentName, e.toString());
        }
    }

    static Path resolveWyrd() {
        var candidates = new Path[] {
            Path.of("/opt/wyrdsekai/bin/wyrd"),
            Path.of("/usr/local/wyrdsekai/bin/wyrd"),
            Path.of("/usr/local/bin/wyrd"),
            Path.of("bin", "wyrd"),
            Path.of("..", "bin", "wyrd"),
        };
        for (var c : candidates) if (Files.isExecutable(c)) return c;
        return null;
    }

    private static String interpreter() {
        var venv = System.getenv(VENV_ENV);
        if (venv == null) venv = System.getProperty("wyrdsekai.sleep.write.venv");
        if (venv != null && !venv.isBlank()) {
            var p = Path.of(venv, "bin", "python");
            if (Files.isExecutable(p)) return p.toString();
        }
        return "python3";
    }

    /** Same search order as ClassifierForge.resolveScriptDir, sleepwrite subdir. */
    static Path resolveScript() {
        var envDir = WyrdConfig.get().scriptsDir();
        if (envDir != null && !envDir.isBlank()) {
            var p = Path.of(envDir, "training", "sleepwrite", "sleep_write.py");
            if (Files.isRegularFile(p)) return p;
        }
        var sysDir = System.getProperty("wyrdsekai.scripts");
        if (sysDir != null && !sysDir.isBlank()) {
            var p = Path.of(sysDir, "training", "sleepwrite", "sleep_write.py");
            if (Files.isRegularFile(p)) return p;
        }
        var candidates = new Path[] {
            Path.of("scripts", "training", "sleepwrite", "sleep_write.py"),
            Path.of("..", "scripts", "training", "sleepwrite", "sleep_write.py"),
            Path.of("/opt/wyrdsekai/scripts/training/sleepwrite/sleep_write.py"),
            Path.of(System.getProperty("user.home"),
                ".wyrdsekai", "scripts", "training", "sleepwrite", "sleep_write.py"),
        };
        for (var c : candidates) if (Files.isRegularFile(c)) return c;
        return null;
    }
}
