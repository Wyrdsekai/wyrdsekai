package org.wyrdsekai.core.substrate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The voice-training process running for each companion. A deep sleep that runs past its
 * deadline stops hers, so the trainer's cleanup brings her voice back at once; until
 * 2026-09-28 the watchdog woke her while training went on with the voice model stopped for up
 * to an hour. A run that exceeds its time limit is really stopped: the output was read to the
 * end before the timed wait began, so the limit never applied.
 */
public final class TrainingProcesses {

    private static final Logger log = LoggerFactory.getLogger(TrainingProcesses.class);
    private static final Map<String, Process> RUNNING = new ConcurrentHashMap<>();
    private static final Set<String> CANCELLED = ConcurrentHashMap.newKeySet();

    /** How a run ended: exited on its own (with its exit code), timed out, or stopped by a cancel. */
    public record Outcome(boolean finished, int exitCode, boolean cancelled) {
        public boolean ok() { return finished && !cancelled && exitCode == 0; }
    }

    private TrainingProcesses() {}

    private static String key(String agentId) {
        return agentId == null ? "" : agentId;
    }

    /** A new training cycle for this companion begins: forget an earlier cancel. */
    public static void begin(String agentId) {
        CANCELLED.remove(key(agentId));
    }

    public static boolean isCancelled(String agentId) {
        return CANCELLED.contains(key(agentId));
    }

    /** Stop this companion's training: the running process and anything it started. */
    public static boolean cancel(String agentId) {
        CANCELLED.add(key(agentId));
        var p = RUNNING.get(key(agentId));
        if (p == null) return false;
        destroyTree(p);
        return true;
    }

    /** Runs the process for this companion, streaming its output lines, waiting at most {@code limit}. */
    public static Outcome run(String agentId, ProcessBuilder pb, Duration limit, Consumer<String> lines)
            throws IOException, InterruptedException {
        var k = key(agentId);
        if (CANCELLED.contains(k)) return new Outcome(false, -1, true);
        pb.redirectErrorStream(true);
        var p = pb.start();
        RUNNING.put(k, p);
        try {
            // A cancel that landed between the check above and the start still stops it.
            if (CANCELLED.contains(k)) destroyTree(p);
            var pump = Thread.ofVirtual().start(() -> {
                try (var r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) lines.accept(line);
                } catch (IOException e) {
                    log.debug("Training output ended: {}", e.getMessage());
                }
            });
            boolean finished = p.waitFor(limit.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                destroyTree(p);
                p.waitFor(10, TimeUnit.SECONDS);
            }
            pump.join(Duration.ofSeconds(5));
            return new Outcome(finished, finished ? p.exitValue() : -1, CANCELLED.contains(k));
        } finally {
            RUNNING.remove(k, p);
        }
    }

    private static void destroyTree(Process p) {
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
    }
}
