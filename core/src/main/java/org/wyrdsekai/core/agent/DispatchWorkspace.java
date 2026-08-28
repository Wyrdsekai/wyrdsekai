package org.wyrdsekai.core.agent;

import java.util.List;

/**
 * What to do with the {@code workspace} a model put on a {@code dispatch_task} call.
 *
 * <h2>The failure this decides</h2>
 * {@code workspace} is optional. A model shown an optional directory field supplies a
 * plausible token, and the handler used to validate whatever arrived against the
 * steward's open-roots and REFUSE THE WHOLE BUILD on a miss. Live 2026-08-21, twice in
 * one day, on two different nodes:
 *
 * <pre>
 *   {"action":"dispatch_task", ..., "workspace":"core"}
 *   {"action":"dispatch_task", ..., "workspace":"stewards_study:librarian_toolbox"}
 * </pre>
 *
 * <p>Neither is a path. Both ended the build before any backend ran, and she told the
 * steward to widen {@code host.open_roots} for a directory nobody had asked for. The
 * routing in front of it — build-first force, {@code tool_choice=required}, a correct
 * call — had worked perfectly every time.
 *
 * <p>So: a value that is not an absolute path is NOISE — logged, dropped, and the build
 * goes to its own per-task scratch directory, which is where an item belongs anyway. An
 * absolute path is a real claim about the host and still faces the open-roots gate with
 * full teeth; that is the case the gate exists for.
 *
 * <p>Pure, so the decision can be tested without an actor.
 */
public final class DispatchWorkspace {

    private DispatchWorkspace() {}

    /**
     * @param workspace    the directory to hand the backend, or "" for its own scratch
     * @param refused      true when an absolute path fell outside the open roots
     * @param ignoredNoise the non-path token that was dropped, or null
     */
    public record Decision(String workspace, boolean refused, String ignoredNoise) {}

    public static Decision decide(String requested, List<String> openRoots) {
        var ws = requested == null ? "" : requested.trim();
        if (ws.isBlank()) return new Decision("", false, null);
        if (!ws.startsWith("/")) return new Decision("", false, ws);
        var roots = openRoots == null ? List.<String>of() : openRoots;
        var allowed = roots.stream().anyMatch(root ->
            ws.equals(root) || ws.startsWith(root.endsWith("/") ? root : root + "/"));
        return new Decision(ws, !allowed, null);
    }
}
