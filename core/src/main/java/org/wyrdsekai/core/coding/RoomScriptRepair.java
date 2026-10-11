package org.wyrdsekai.core.coding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.scripting.api.WorldApi;
import org.wyrdsekai.scripting.sandbox.ItemScriptExecutor;
import org.wyrdsekai.scripting.sandbox.ScriptSandbox;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The workshop's mend for a room script a companion wrote, the way {@link ItemContractRepair}
 * mends a placed item: on a copy, through the coding helper, bounded rounds, never a worse
 * result, the file replaced only when whole, the old version kept.
 *
 * <p>What "whole" means for a room script: it parses, it reaches the world only through
 * {@code world.…}, and the hook that failed in the room no longer fails the same way when run in
 * the sandbox. The last of these is the one the room itself saw ("emit is not defined",
 * "getProperty is not a function"), so a mend is judged by the error she actually hit, not by a
 * guess at what the room's state would be.</p>
 */
public final class RoomScriptRepair {

    private static final Logger log = LoggerFactory.getLogger(RoomScriptRepair.class);

    static final int MAX_ROUNDS = 2;

    /** What became of one room script handed to {@link #repair}. */
    public record Repair(String roomId, boolean fixed, List<String> before, List<String> after, String note) {}

    /** The hooks a room script may define, with the arguments the room passes them. */
    static final String[][] HOOKS = {
        {"onEnter", "entityId", "entityName", "fromDirection"},
        {"onLeave", "entityId", "entityName", "direction"},
        {"onSay", "entityId", "entityName", "text"},
        {"onEmote", "entityId", "entityName", "text"},
        {"onUse", "entityId", "objectName", "remainder", "entityName"},
        {"onExamine", "entityId", "objectId", "objectName"},
        {"onTake", "entityId", "objectName", "objectId"},
        {"onDrop", "entityId", "objectName", "objectId"},
        {"onTimer", "timerName"},
        {"onActivate"},
        {"getHints"},
        {"getToolDefinitions"},
    };

    /** A world call made bare: {@code emit(...)} where a room script says {@code world.emit(...)}. */
    private static final Pattern BARE_WORLD_CALL = Pattern.compile(
        "(?<![\\w$.])(emit|narrate|getProperty|setProperty|scheduleTimer|cancelTimer|t|broadcast|getState)\\s*\\(");
    private static final Pattern FAILED_HOOK = Pattern.compile("^([A-Za-z]+): ");

    private RoomScriptRepair() {}

    /**
     * What is wrong with the script, in order: it does not parse; it calls the world bare; the hook
     * the room saw fail still fails the same way in the sandbox. Empty when it is whole.
     *
     * @param lastError what the room recorded, "hook: message" (null when nothing was recorded)
     */
    public static List<String> problems(String roomId, String script, String lastError) {
        var out = new ArrayList<String>();
        var parse = ItemScriptExecutor.parseProblem(script);
        if (parse != null) {
            out.add("it does not parse: " + parse);
            return out;
        }
        var bare = BARE_WORLD_CALL.matcher(script);
        var seen = new ArrayList<String>();
        while (bare.find()) {
            var name = bare.group(1);
            if (seen.contains(name)) continue;
            // A function the script defines itself by that name is not a bare world call.
            if (Pattern.compile("function\\s+" + name + "\\s*\\(").matcher(script).find()) continue;
            seen.add(name);
            out.add("it calls " + name + "(...) on its own; a room script reaches the world through world." + name + "(...)");
        }
        // The hook the room saw fail is run only when nothing above names the cause: a bare call
        // found is the cause, and the smoke run after a mend is what confirms it is gone.
        var failed = failedHook(lastError);
        if (failed != null && out.isEmpty()) {
            var again = smokeRun(roomId, script, failed);
            if (again != null && sameFailure(again, lastError)) {
                out.add(failed + " still fails as it did in the room: " + again);
            }
        }
        return out;
    }

    /** The hook named at the head of a recorded error ("onSay: TypeError…"), or null. */
    static String failedHook(String lastError) {
        if (lastError == null) return null;
        var m = FAILED_HOOK.matcher(lastError);
        if (!m.find()) return null;
        for (var h : HOOKS) if (h[0].equals(m.group(1))) return h[0];
        return null;
    }

    /** Whether a failure in the sandbox is the one the room recorded, by its message's heart. */
    static boolean sameFailure(String again, String lastError) {
        return heart(again).equals(heart(lastError.replaceFirst("^[A-Za-z]+: ", "")));
    }

    private static String heart(String msg) {
        var m = msg == null ? "" : msg.strip();
        // "TypeError: entity.getProperty is not a function" and the same without the class name.
        m = m.replaceFirst("^[A-Za-z]*Error: ", "");
        return m.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    /**
     * Run one hook of the script in a sandbox with plain arguments and a bare world. Returns the
     * failure's message, or null when the hook ran through (or is not defined).
     */
    static String smokeRun(String roomId, String script, String hook) {
        String[] args = {};
        for (var h : HOOKS) if (h[0].equals(hook)) { args = new String[h.length - 1]; System.arraycopy(h, 1, args, 0, args.length); }
        var failure = new String[1];
        try (var sandbox = new ScriptSandbox(roomId)) {
            sandbox.onFailure((what, message) -> failure[0] = message);
            sandbox.callHook(script, hook, new WorldApi(roomId), (Object[]) args);
        } catch (RuntimeException e) {
            return e.getMessage() == null ? e.toString() : e.getMessage();
        }
        return failure[0];
    }

    /**
     * Mend the script on a copy through the coding helper; the room's file is replaced only when
     * the copy is whole. The previous version is kept under {@code .repaired/}.
     */
    public static Repair repair(String roomId, Path file, String lastError) {
        var fileName = file.getFileName().toString();
        try {
            var original = Files.readString(file);
            var before = problems(roomId, original, lastError);
            if (before.isEmpty()) return new Repair(roomId, false, before, before, "nothing to repair");
            var run = ItemContractRepair.escalation();
            if (run == null) return new Repair(roomId, false, before, before, "no coding backend is registered to repair with");
            var stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.now());
            var ws = CodingWorkspace.forTask(null, "repair-room-" + roomId.replaceAll("[^A-Za-z0-9_.-]", "_") + "-" + stamp);
            if (ws == null) return new Repair(roomId, false, before, before, "no scratch workspace");
            var copy = ws.toPath().resolve(fileName);
            Files.writeString(copy, original);
            var current = before;
            for (int round = 1; round <= MAX_ROUNDS && !current.isEmpty(); round++) {
                var source = Files.readString(copy);
                log.info("[room-script] repair round {}/{} for {} — {}", round, MAX_ROUNDS, roomId, current);
                final var prompt = buildPrompt(fileName, current);
                final var done = new boolean[1];
                ItemContractRepair.withoutEscalation(() -> done[0] = run.rerun(ws.toPath(), prompt));
                if (!done[0]) break;
                var after = problems(roomId, Files.readString(copy), lastError);
                if (after.size() > current.size()) { Files.writeString(copy, source); break; }   // never worse
                current = after;
            }
            if (!current.isEmpty()) {
                return new Repair(roomId, false, before, current, "could not be made whole; the room's script is unchanged");
            }
            var kept = file.resolveSibling(".repaired");
            Files.createDirectories(kept);
            Files.writeString(kept.resolve(fileName + "." + stamp + ".bak"), original);
            var tmp = file.resolveSibling(fileName + ".tmp");
            Files.writeString(tmp, Files.readString(copy));
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            log.info("[room-script] {} repaired; the previous version is in {}", roomId, kept);
            return new Repair(roomId, true, before, List.of(), "repaired; the previous version is kept under .repaired/");
        } catch (Exception e) {
            log.warn("[room-script] repair of {} failed: {}", roomId, e.toString());
            return new Repair(roomId, false, List.of(), List.of(), "the repair did not run: " + e.getMessage());
        }
    }

    static String buildPrompt(String fileName, List<String> problems) {
        var sb = new StringBuilder();
        sb.append("EDIT ").append(fileName).append(" IN PLACE. Do not create a new file, do not rename it, ")
          .append("do not write a corrected copy alongside it — change this file.\n")
          .append("It is a room script for Wyrdsekai, one file of plain JavaScript (GraalJS), nothing else: ")
          .append("no project, no imports, no tests. The room calls the functions it defines; the only way ")
          .append("to reach the world is the `world` object, never a bare call:\n")
          .append("  world.emit('narrate', { text: '...' })   say something in the room\n")
          .append("  world.getProperty(key) / world.setProperty(key, value)   the room's own properties\n")
          .append("  world.scheduleTimer(name, ms)   then onTimer(name) is called\n")
          .append("  world.t(key)   a phrase in the household's language\n")
          .append("Hooks the room calls, with their arguments as plain strings: onEnter(entityId, entityName, ")
          .append("fromDirection), onLeave(entityId, entityName, direction), onSay(entityId, entityName, text), ")
          .append("onEmote(entityId, entityName, text), onUse(entityId, objectName, remainder, entityName), ")
          .append("onExamine(entityId, objectId, objectName), onTake(entityId, objectName, objectId), ")
          .append("onDrop(entityId, objectName, objectId), onTimer(timerName), getHints() returning ")
          .append("[{label, intent, action}], getToolDefinitions(). The arguments are strings: they have no methods.\n\n")
          .append("The script as it stands fails in the room. ")
          .append(problems.size() == 1 ? "The exact problem:" : "The exact problems:").append("\n\n");
        for (var p : problems) sb.append("  - ").append(p).append("\n");
        sb.append("\nEdit that file IN PLACE to fix all of the above and keep what the script does. ")
          .append("Keep every hook it defines. Do not create a new file, and do not explain — just make the edit.");
        return sb.toString();
    }

    /** For tests and the route: the list of hooks by name. */
    static List<String> hookNames() {
        var out = new ArrayList<String>();
        for (var h : HOOKS) out.add(h[0]);
        return out;
    }

    static Matcher bareCalls(String script) { return BARE_WORLD_CALL.matcher(script); }
}
