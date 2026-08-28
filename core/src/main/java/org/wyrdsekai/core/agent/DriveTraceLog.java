package org.wyrdsekai.core.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.config.WyrdConfig;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;

/**
 * Append-only trace of (drive state × moment) — the training data for
 * affect-gated plasticity.
 *
 * <p>Path 3 of the dynamic-substrate program (
 * AUG2026 §3) gates weight updates by what the organism <i>feels</i> rather
 * than token statistics. Its guardrail is "never fake state": the gate must
 * be the real tank value at the real moment. Until 2026-08-11 (dev49) the
 * real tank values were erased at every restart, so this signal could not
 * honestly exist; the day persistence landed is the first day this trace is
 * worth collecting. Every line is one tuple: what was happening, and what
 * she felt as it happened. The outcome half of the training pair — what she
 * later returned to, journaled about, acted on — joins offline against her
 * biography and activity logs by timestamp.</p>
 *
 * <p>Deliberately dumb: one JSONL line per moment, flushed per write (a
 * crash must not eat the evening's feelings), single rotation at
 * {@link #MAX_BYTES} so it can never fill a household disk. At the measured
 * ~14-50 events/hr this is a few MB per month.</p>
 */
public final class DriveTraceLog implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DriveTraceLog.class);
    private static final long MAX_BYTES = 64L * 1024 * 1024;

    private final Path file;
    private BufferedWriter writer;
    private long approxSize;

    private DriveTraceLog(Path file) {
        this.file = file;
    }

    /** Open (creating if absent) the node's drive trace. Null on failure — never throws. */
    public static DriveTraceLog open() {
        var base = WyrdConfig.get().dataDir();
        if (base == null || base.isBlank()) return null;
        return openAt(Path.of(base, "data", "drive-trace.jsonl"));
    }

    /** Test seam (and the shared implementation): open a trace at an explicit path. */
    static DriveTraceLog openAt(Path file) {
        try {
            Files.createDirectories(file.getParent());
            var trace = new DriveTraceLog(file);
            trace.openWriter();
            return trace;
        } catch (IOException e) {
            log.warn("drive trace: failed to open: {}", e.getMessage());
            return null;
        }
    }

    private void openWriter() throws IOException {
        approxSize = Files.exists(file) ? Files.size(file) : 0;
        writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /**
     * Record one moment. {@code kind} is "event" (something happened to her)
     * or "action" (she did something); {@code label} names it; {@code drives}
     * and {@code tanks} are the REAL live values — the caller must never
     * synthesize them (manufactured gate signal is manufactured caring).
     */
    public synchronized void record(String agentId, String kind, String label,
                                    Map<String, Double> drives,
                                    Map<String, Double> tanks) {
        if (writer == null) return;
        try {
            var sb = new StringBuilder(256);
            sb.append("{\"ts\":\"").append(Instant.now()).append('"')
              .append(",\"agent\":\"").append(escape(agentId)).append('"')
              .append(",\"kind\":\"").append(escape(kind)).append('"')
              .append(",\"label\":\"").append(escape(label)).append('"');
            appendNumbers(sb, "drives", drives);
            appendNumbers(sb, "tanks", tanks);
            sb.append("}\n");
            var line = sb.toString();
            writer.write(line);
            writer.flush();
            approxSize += line.length();
            if (approxSize > MAX_BYTES) rotate();
        } catch (IOException e) {
            log.debug("drive trace: write failed: {}", e.getMessage());
        }
    }

    private static void appendNumbers(StringBuilder sb, String key, Map<String, Double> m) {
        sb.append(",\"").append(key).append("\":{");
        if (m != null) {
            boolean first = true;
            for (var e : m.entrySet()) {
                if (e.getValue() == null) continue;
                if (!first) sb.append(',');
                first = false;
                sb.append('"').append(escape(e.getKey())).append("\":")
                  .append(String.format(Locale.ROOT, "%.4f", e.getValue()));
            }
        }
        sb.append('}');
    }

    private static String escape(String s) {
        if (s == null) return "";
        var sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    private void rotate() throws IOException {
        writer.close();
        var old = file.resolveSibling(file.getFileName() + ".1");
        Files.move(file, old, StandardCopyOption.REPLACE_EXISTING);
        openWriter();
        log.info("drive trace: rotated at {} bytes", MAX_BYTES);
    }

    @Override
    public synchronized void close() {
        try {
            if (writer != null) writer.close();
        } catch (IOException e) {
            log.debug("drive trace: close failed: {}", e.getMessage());
        }
    }
}
