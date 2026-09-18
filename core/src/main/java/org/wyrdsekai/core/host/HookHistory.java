package org.wyrdsekai.core.host;

import org.wyrdsekai.common.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * What her hands normally do: every open, exec and connect her tools were seen to make and
 * were not cut for, kept as a table of distinct things with a count and a first and last
 * time. It is the world a candidate rule is tried in before it is armed: a rule that would
 * have cut something in this table would have cut her doing her ordinary work.
 *
 * <p>Distinct, not exhaustive: per-task paths are folded ({@code coding-workspaces/*},
 * {@code /proc/*}, {@code /tmp/*}), rows are capped, and the table is rewritten whole every
 * minute to {@code <data>/brainstem/hooks-seen.jsonl}. Events that were cut at the time are
 * not normal behaviour and never enter it.</p>
 */
public final class HookHistory {

    public static final int MAX_ROWS = 20_000;
    private static final Pattern WORKSPACE = Pattern.compile("/coding-workspaces/[^/]+");
    private static final Pattern PROC = Pattern.compile("^/proc/\\d+");
    private static final Pattern TMP = Pattern.compile("^/tmp/[^/]+");

    /** One distinct thing her hands did. */
    public record Row(String kind, int uid, String comm, String arg, long count, Instant first, Instant last) {
        ToolHooks.Event asEvent() { return new ToolHooks.Event(kind, uid, 0, comm, arg); }
    }

    private final Path file;
    private final String dataRoot;
    private final Map<String, Row> rows = new ConcurrentHashMap<>();
    private volatile long overflow;
    private volatile boolean dirty;

    public HookHistory(Path file) {
        this(file, null);
    }

    /** {@code dataRoot}: paths under the data directory keep their prefix whole, so a rule's prefix still matches them. */
    public HookHistory(Path file, String dataRoot) {
        this.file = file;
        this.dataRoot = dataRoot;
    }

    /** Fold per-task and per-process parts of a path so distinct stays small. */
    static String fold(String kind, String arg) { return fold(kind, arg, null); }

    static String fold(String kind, String arg, String dataRoot) {
        if (arg == null) return "";
        var a = arg.length() > 200 ? arg.substring(0, 200) : arg;
        if (!kind.equals("open")) return a;
        a = WORKSPACE.matcher(a).replaceFirst("/coding-workspaces/*");
        if (dataRoot != null && a.startsWith(dataRoot + "/")) return a;   // a rule's prefix must still match it
        a = PROC.matcher(a).replaceFirst("/proc/*");
        a = TMP.matcher(a).replaceFirst("/tmp/*");
        return a;
    }

    public void note(ToolHooks.Event e, Instant now) {
        var arg = fold(e.kind(), e.arg(), dataRoot);
        var key = e.uid() + "|" + e.kind() + "|" + e.comm() + "|" + arg;
        var old = rows.get(key);
        if (old == null && rows.size() >= MAX_ROWS) { overflow++; return; }
        rows.merge(key, new Row(e.kind(), e.uid(), e.comm(), arg, 1, now, now),
            (a, b) -> new Row(a.kind(), a.uid(), a.comm(), a.arg(), a.count() + 1, a.first(), now));
        dirty = true;
    }

    public List<Row> rows() {
        var out = new ArrayList<>(rows.values());
        out.sort(Comparator.comparingLong(Row::count).reversed());
        return out;
    }

    public long events() { return rows.values().stream().mapToLong(Row::count).sum(); }
    public int distinct() { return rows.size(); }
    public long overflow() { return overflow; }

    /** From the first thing ever seen to the last: how much of her life the table covers. */
    public Duration span() {
        Instant first = null, last = null;
        for (var r : rows.values()) {
            if (first == null || r.first().isBefore(first)) first = r.first();
            if (last == null || r.last().isAfter(last)) last = r.last();
        }
        return first == null ? Duration.ZERO : Duration.between(first, last);
    }

    public synchronized void load() {
        if (!Files.isRegularFile(file)) return;
        try {
            for (var line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                var n = Json.mapper().readTree(line);
                var r = new Row(n.get("kind").asText(), n.get("uid").asInt(), n.get("comm").asText(), n.get("arg").asText(),
                    n.get("count").asLong(), Instant.parse(n.get("first").asText()), Instant.parse(n.get("last").asText()));
                rows.put(r.uid() + "|" + r.kind() + "|" + r.comm() + "|" + r.arg(), r);
            }
        } catch (IOException | RuntimeException e) {
            // a damaged table is rebuilt from what her hands do next
        }
    }

    /** Rewrite the table if anything changed. Never throws. */
    public synchronized void flush() {
        if (!dirty) return;
        try {
            Files.createDirectories(file.getParent());
            var sb = new StringBuilder();
            for (var r : rows()) {
                var m = new LinkedHashMap<String, Object>();
                m.put("kind", r.kind()); m.put("uid", r.uid()); m.put("comm", r.comm()); m.put("arg", r.arg());
                m.put("count", r.count()); m.put("first", r.first().toString()); m.put("last", r.last().toString());
                sb.append(Json.mapper().writeValueAsString(m)).append('\n');
            }
            var tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            dirty = false;
        } catch (IOException | RuntimeException e) {
            // the next flush tries again
        }
    }
}
