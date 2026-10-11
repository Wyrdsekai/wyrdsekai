package org.wyrdsekai.core.room;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.util.Json;
import org.wyrdsekai.core.body.BodyMap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Room scripts a companion wrote that do not work. A scripted item that fails is told to her once,
 * mended when the house is quiet, and set aside after three tries ({@link org.wyrdsekai.core.item.BrokenItems});
 * a room script that failed was only logged, once per hook, every time anyone entered, spoke or
 * used something in the room — about twenty errors a day on the household node for three rooms the
 * companions had built (a syntax error, a bare {@code emit(...)}, a {@code getProperty} on a string;
 * 2026-10-09).
 *
 * <p>Now a failing script of hers is counted per file: she is told once, in plain words, what is
 * wrong with it; after {@value #MAX_FAILURES} failures the script is set aside — the room stays, its
 * script does not run, nothing is lost — and she is told that too. Writing the script again
 * (add_script) clears the record; so does any change to the file.</p>
 *
 * <p>Only scripts in the user scripts directory count: a built-in room script that fails is the
 * product's to fix, and is logged as before.</p>
 */
public final class BrokenRoomScripts {

    private static final Logger log = LoggerFactory.getLogger(BrokenRoomScripts.class);

    public static final int MAX_FAILURES = 3;
    /** The workshop's tries on one script as it stands; past this it rests until the file changes. */
    public static final int MAX_MEND_ATTEMPTS = 3;

    /** One room script that failed. */
    public record Entry(String roomId, Path file, int failures, String lastError, boolean setAside) {}

    private static final Map<Path, ObjectNode> STATES = new ConcurrentHashMap<>();
    private static final Pattern NOT_DEFINED = Pattern.compile("(?:ReferenceError: )?([A-Za-z_$][\\w$]*) is not defined");
    private static final Pattern NOT_A_FUNCTION = Pattern.compile("(\\S+) is not a function");
    private static final Pattern PARSE = Pattern.compile("SyntaxError|^Expected|^Unexpected|Unexpected token|Unterminated|:\\d+:\\d+");

    private BrokenRoomScripts() {}

    /**
     * A script of hers failed. Returns what she was told, or null when the script is not hers (no
     * file in the user scripts directory) or she had already been told about this failure.
     */
    public static synchronized String failed(String roomId, Path userScriptsDir, String what, String message) {
        var file = fileOf(roomId, userScriptsDir);
        if (file == null) return null;
        var state = load(userScriptsDir);
        var node = state.with(roomId);
        var mtime = mtime(file);
        if (node.path("mtime").asLong(-1) != mtime) {
            // A different file than the one counted: the count starts again.
            node.removeAll();
            node.put("mtime", mtime);
        }
        int failures = node.path("failures").asInt(0) + 1;
        node.put("failures", failures);
        node.put("lastError", shortMessage(what, message));
        String told = null;
        // The same error from another hook is the same news: told once per message, not per hook.
        var errorKey = Integer.toHexString(truncate(message == null ? "" : message.strip(), 160).hashCode());
        if (!errorKey.equals(node.path("told").asText(""))) {
            told = "The script in " + roomId + " does not work: " + plainly(what, message)
                + " I can write it again with add_script.";
            mark("room", roomId, told, what + ": " + message);
            node.put("told", errorKey);
        }
        if (failures >= MAX_FAILURES && !node.path("setAside").asBoolean(false)) {
            node.put("setAside", true);
            told = "The script in " + roomId + " failed " + MAX_FAILURES + " times, so it is set aside: the room stays, "
                + "its script does not run, and nothing is lost. I can write it again with add_script.";
            mark("room", roomId, told, what + ": " + message);
            log.warn("Room '{}' script set aside after {} failures ({}: {}); it is kept at {} and runs again once rewritten",
                roomId, failures, what, shortMessage(what, message), file);
        }
        save(userScriptsDir, state);
        return told;
    }

    /** True while this room's script is set aside: it failed {@value #MAX_FAILURES} times and has not changed since. */
    public static boolean isSetAside(String roomId, Path userScriptsDir) {
        if (roomId == null || userScriptsDir == null) return false;
        var state = STATES.get(userScriptsDir);
        if (state == null) state = load(userScriptsDir);
        var node = state.get(roomId);
        if (node == null || !node.path("setAside").asBoolean(false)) return false;
        var file = fileOf(roomId, userScriptsDir);
        if (file == null) return false;
        if (node.path("mtime").asLong(-1) != mtime(file)) return false;   // changed since: it runs again
        return true;
    }

    /** The script was written again: the record is cleared, and it runs. */
    public static synchronized void rewritten(String roomId, Path userScriptsDir) {
        if (roomId == null || userScriptsDir == null) return;
        var state = load(userScriptsDir);
        if (state.remove(roomId) != null) {
            save(userScriptsDir, state);
            log.info("Room '{}' script written again: its failures are forgotten and it runs", roomId);
        }
    }

    /**
     * What the workshop may try tonight: every script of hers that failed, as it stands, and not
     * already tried {@value #MAX_MEND_ATTEMPTS} times unchanged. Set aside or not — a script that
     * failed once is worth a mend before it fails twice more.
     */
    public static synchronized List<Entry> mendable(Path userScriptsDir) {
        var out = new ArrayList<Entry>();
        if (userScriptsDir == null) return out;
        var state = load(userScriptsDir);
        var names = state.fieldNames();
        while (names.hasNext()) {
            var roomId = names.next();
            var n = state.get(roomId);
            var file = fileOf(roomId, userScriptsDir);
            if (file == null || n.path("failures").asInt(0) < 1) continue;
            if (n.path("mtime").asLong(-1) != mtime(file)) continue;   // changed since: it runs again first
            if (n.path("mendAttempts").asInt(0) >= MAX_MEND_ATTEMPTS && n.path("mendMtime").asLong(-2) == mtime(file)) continue;
            out.add(new Entry(roomId, file, n.path("failures").asInt(0), n.path("lastError").asText(""),
                n.path("setAside").asBoolean(false)));
        }
        return out;
    }

    /** One mending attempt on the script as it was before the attempt; a mend clears the record. */
    public static synchronized void mendAttempted(Entry e, boolean fixed) {
        var dir = e.file().getParent();
        var state = load(dir);
        if (fixed) {
            state.remove(e.roomId());
            save(dir, state);
            log.info("Room '{}' script mended by the workshop: its failures are forgotten and it runs", e.roomId());
            return;
        }
        var node = state.with(e.roomId());
        long m = mtime(e.file());
        boolean same = node.path("mendMtime").asLong(-2) == m;
        node.put("mendAttempts", same ? node.path("mendAttempts").asInt(0) + 1 : 1);
        node.put("mendMtime", m);
        save(dir, state);
    }

    /** Whether the workshop has spent its tries on this script as it stands. */
    public static synchronized boolean triedOut(String roomId, Path userScriptsDir) {
        var file = fileOf(roomId, userScriptsDir);
        if (file == null) return false;
        var n = load(userScriptsDir).get(roomId);
        return n != null && n.path("mendAttempts").asInt(0) >= MAX_MEND_ATTEMPTS && n.path("mendMtime").asLong(-2) == mtime(file);
    }

    /** What she is told of a mend: once per outcome, through the body map. */
    public static void tellMended(Entry e, boolean fixed, String note, String when) {
        var room = e.roomId();
        if (fixed) {
            mark("room", room, "The workshop mended the script in " + room + " " + when + "; it runs again. "
                + "The version it replaced is kept.", "fixed");
        } else if (triedOut(room, e.file().getParent())) {
            mark("room", room, "The workshop tried to mend the script in " + room + " " + MAX_MEND_ATTEMPTS
                + " times and could not; it stays set aside until I write it again with add_script.", note);
        } else {
            mark("room", room, "The workshop tried to mend the script in " + room + " " + when
                + " and could not; it is as it was.", note);
        }
    }

    /** Every script set aside on this node, for the steward's doctor and the tests. */
    public static synchronized List<Entry> setAside(Path userScriptsDir) {
        var out = new ArrayList<Entry>();
        if (userScriptsDir == null) return out;
        var state = load(userScriptsDir);
        var names = state.fieldNames();
        while (names.hasNext()) {
            var roomId = names.next();
            if (isSetAside(roomId, userScriptsDir)) {
                var n = state.get(roomId);
                out.add(new Entry(roomId, userScriptsDir.resolve(roomId + ".js"), n.path("failures").asInt(0),
                    n.path("lastError").asText(""), true));
            }
        }
        return out;
    }

    /** What is wrong, as she would say it. The technical message stays in the detail and the log. */
    static String plainly(String what, String message) {
        var msg = message == null ? "" : message.strip();
        var nd = NOT_DEFINED.matcher(msg);
        if (nd.find()) {
            return "it calls " + nd.group(1) + " on its own, and a room script reaches the world through world." + nd.group(1) + "(...).";
        }
        var nf = NOT_A_FUNCTION.matcher(msg);
        if (nf.find()) {
            return "it calls " + nf.group(1) + ", which is not there.";
        }
        if (PARSE.matcher(msg).find()) {
            return "it does not parse (" + truncate(msg, 100) + ").";
        }
        return "it failed in " + (what == null ? "a hook" : what) + " (" + truncate(msg, 100) + ").";
    }

    private static String shortMessage(String what, String message) {
        return (what == null ? "" : what + ": ") + truncate(message == null ? "" : message.strip(), 160);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static Path fileOf(String roomId, Path userScriptsDir) {
        if (roomId == null || userScriptsDir == null) return null;
        var file = userScriptsDir.resolve(roomId + ".js");
        return Files.isRegularFile(file) ? file : null;
    }

    private static long mtime(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return -1;
        }
    }

    private static void mark(String kind, String roomId, String text, String detail) {
        try {
            var map = BodyMap.get();
            if (map != null) map.mark(kind, roomId, null, text, detail);
        } catch (RuntimeException e) {
            log.debug("broken-room-scripts: mark not written: {}", e.toString());
        }
    }

    private static Path stateFile(Path userScriptsDir) {
        return userScriptsDir.resolve(".broken").resolve("state.json");
    }

    private static ObjectNode load(Path userScriptsDir) {
        return STATES.computeIfAbsent(userScriptsDir, dir -> {
            var file = stateFile(dir);
            if (Files.isRegularFile(file)) {
                try {
                    var tree = Json.mapper().readTree(file.toFile());
                    if (tree instanceof ObjectNode o) return o;
                } catch (IOException | RuntimeException e) {
                    log.debug("broken-room-scripts: {} not read: {}", file, e.toString());
                }
            }
            return Json.mapper().createObjectNode();
        });
    }

    private static void save(Path userScriptsDir, ObjectNode state) {
        var file = stateFile(userScriptsDir);
        try {
            Files.createDirectories(file.getParent());
            var tmp = file.resolveSibling("state.json.tmp");
            Json.mapper().writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), state);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            log.debug("broken-room-scripts: {} not saved: {}", file, e.toString());
        }
    }

    /** Tests: forget what was read from disk. */
    public static void resetForTests() { STATES.clear(); }
}
