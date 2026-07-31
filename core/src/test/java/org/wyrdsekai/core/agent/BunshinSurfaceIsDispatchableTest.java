package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every verb offered to a bunshin must be one the primary can actually carry out.
 *
 * <p>Live on home-server, 2026-07-29: the bunshin asked for {@code create_room},
 * {@code enactQueuedConsequential} had no branch for it, logged
 * "no queued-dispatch for CreateRoom" and did nothing — and the handler still
 * logged "Executed 'create_room'" and replied success. The greenhouse only
 * existed because the model happened to fall back to the
 * {@code create_room_from_template} builtin on its next step.</p>
 *
 * <p>31 of 34 offered verbs were in that state; 29 of them had working handlers
 * that were simply never wired in. Offering a tool that cannot run is worse than
 * not offering it: the model spends a step on it and is told it worked.</p>
 *
 * <p>This reads the dispatch chain as SOURCE because
 * {@code enactQueuedConsequential} is private and needs a live actor. The
 * property under test is structural — "the offered set is a subset of the
 * dispatched set" — so source is the right level. Same technique as
 * {@code UserRequestReachesTheToolTest}.</p>
 */
class BunshinSurfaceIsDispatchableTest {

    private static final Path ACTOR = Path.of(
        "src/main/java/org/wyrdsekai/core/agent/CompanionActor.java");
    private static final Path POLICY = Path.of(
        "src/main/java/org/wyrdsekai/core/agent/ActionPolicy.java");

    /** Mirrors CompanionActor.BUNSHIN_EXCLUDED. */
    private static final Set<String> EXCLUDED = Set.of(
        "dispatch_bunshin", "delegate", "voluntary_sleep",
        "emergency_call", "go_to_bondholder",
        "craft_from_template", "codex_action", "configure_channel");
    /** Mirrors CompanionActor.BUNSHIN_HUMAN_DIRECTED_VERBS. */
    private static final Set<String> HUMAN_DIRECTED = Set.of("create_room");

    /** verb -> AgentAction record name, read from the actionTypeOf switch. */
    private static Map<String, String> verbToRecord() throws IOException {
        var src = Files.readString(ACTOR) + Files.readString(POLICY);
        var m = Pattern.compile("AgentAction\\.(\\w+)\\s*_?\\s*->\\s*\"([a-z_]+)\"").matcher(src);
        var out = new LinkedHashMap<String, String>();
        while (m.find()) out.putIfAbsent(m.group(2), m.group(1));
        return out;
    }

    private static String dispatchChain() throws IOException {
        var src = Files.readString(ACTOR);
        int start = src.indexOf("private boolean enactQueuedConsequential");
        assertTrue(start > 0, "enactQueuedConsequential must exist and return boolean — "
            + "a void signature is what let a no-op be reported as success");
        return src.substring(start, src.indexOf("\n    }", start));
    }

    private static Set<String> offeredSurface() {
        var out = new TreeSet<String>();
        for (var pol : ActionPolicy.REGISTRY.values()) {
            var n = pol.actionType();
            if (pol.concurrencySafe() || EXCLUDED.contains(n)) continue;
            boolean forbidden =
                ActionPolicy.autonomyTierFor(n) == ActionPolicy.AutonomyTier.FORBIDDEN;
            if (!forbidden || HUMAN_DIRECTED.contains(n)) out.add(n);
        }
        return out;
    }

    @Test
    @DisplayName("every verb offered to a bunshin has a dispatch branch")
    void surfaceIsSubsetOfDispatchable() throws IOException {
        var chain = dispatchChain();
        var map = verbToRecord();
        var undispatchable = new TreeSet<String>();
        for (var verb : offeredSurface()) {
            var record = map.get(verb);
            if (record == null) {
                // No AgentAction record at all — ActionParser cannot produce it,
                // so it must not be advertised as a callable action.
                undispatchable.add(verb + " (no AgentAction record)");
                continue;
            }
            if (!chain.contains("AgentAction." + record + " x)")) {
                undispatchable.add(verb + " -> " + record);
            }
        }
        assertTrue(undispatchable.isEmpty(),
            "these verbs are offered to a bunshin but nothing dispatches them, so the "
            + "bunshin burns a step and is told it succeeded:\n  "
            + String.join("\n  ", undispatchable));
    }

    @Test
    @DisplayName("create_room specifically — the verb the docs promise")
    void createRoomIsDispatchable() throws IOException {
        assertTrue(offeredSurface().contains("create_room"),
            "AUTHORING.md §1 and ROOMS.md promise a person can ask for a room");
        assertTrue(dispatchChain().contains("AgentAction.CreateRoom x)"),
            "create_room is offered but has no dispatch branch — this is the exact "
            + "defect observed on home-server 2026-07-29");
    }

    @Test
    @DisplayName("an unmatched action reports FAILURE, never silent success")
    void fallthroughReturnsFalse() throws IOException {
        var chain = dispatchChain();
        assertTrue(chain.contains("return false"),
            "the fallthrough must return false so the caller can tell the bunshin "
            + "the truth");
        // Control: the chain must also be capable of returning true, or the
        // "subset" test above would pass vacuously against a chain that dispatches
        // nothing at all.
        assertTrue(chain.contains("return true;"), "no branch reports success");

        var handler = Files.readString(ACTOR);
        int h = handler.indexOf("private Behavior<Command> onBunshinToolRequest");
        assertTrue(h > 0, "onBunshinToolRequest must exist");
        var body = handler.substring(h, handler.indexOf("\n    }", h));
        // Assert the INVARIANT, not one spelling of it: the result must be
        // captured and branched on. The literal `if (!enactQueuedConsequential(…))`
        // stopped matching when the call was wrapped in try/finally to restore the
        // speech sink — a test pinned to syntax fails on a refactor that preserves
        // the property, which teaches people to edit the test instead of reading it.
        assertTrue(body.contains("= enactQueuedConsequential(action)")
                || body.contains("if (!enactQueuedConsequential(action))"),
            "onBunshinToolRequest must capture the dispatch result");
        assertTrue(body.contains("if (!dispatched)")
                || body.contains("if (!enactQueuedConsequential(action))"),
            "onBunshinToolRequest must BRANCH on the dispatch result. Ignoring it is "
            + "what made it log \"Executed 'create_room'\" for a no-op.");
        assertTrue(body.contains("ToolResultCame(false"),
            "a verb nothing dispatched must be reported as a FAILURE to the bunshin");
    }

    @Test
    @DisplayName("a silent handler's outcome is reported UNCONFIRMED, not done")
    void silentHandlerDoesNotGetASynthesizedSuccess() throws IOException {
        var src = Files.readString(ACTOR);
        int h = src.indexOf("private Behavior<Command> onBunshinToolRequest");
        var body = src.substring(h, src.indexOf("\n    }", h));

        // Six handlers (create_watcher, make_amends, place_item, schedule_skill,
        // take_item, think_deeply) narrate nothing. Observed 2026-07-30, the tool
        // result for those was a synthesized "<verb> done." — completion asserted
        // with no evidence, which is the ORIGINAL defect's shape reintroduced one
        // level down. A handler that ran without accounting for itself is
        // unconfirmed, not successful.
        assertFalse(body.contains("name + \" done.\""),
            "onBunshinToolRequest must not synthesize a success claim for a handler "
            + "that reported nothing — that is how she narrates work nobody witnessed");
        assertTrue(body.contains("unconfirmed"),
            "a silent handler's outcome must be reported as unconfirmed");
    }

    @Test
    @DisplayName("human-directedness is judged by the turn IN FLIGHT, not the previous one")
    void humanDirectednessReadsThePendingTrigger() throws IOException {
        var src = Files.readString(ACTOR);
        int h = src.indexOf("private boolean bunshinWorkIsHumanDirected");
        assertTrue(h > 0, "bunshinWorkIsHumanDirected must exist");
        var body = src.substring(h, src.indexOf("\n    }", h));
        // lastReactTrigger is assigned only when the RESPONSE returns, so a check
        // that reads it alone judges the PREVIOUS turn: null after boot (first
        // request of a fresh install → rescue silently skipped, observed live
        // 2026-07-30), and stale the other way (an agent dispatch inheriting a
        // human trigger's FORBIDDEN bypass).
        // Pin the EXPRESSION, not word order — the method's own comment mentions
        // lastReactTrigger first and tripped an ordering assertion.
        assertTrue(body.contains("pendingTrigger != null ? pendingTrigger : lastReactTrigger"),
            "the in-flight trigger must be decisive, falling back to the last "
            + "completed turn only when no turn is in flight");
    }

    @Test
    @DisplayName("async room outcomes reach the RETURN REPORT, not the room")
    void asyncOutcomesReachTheReport() throws IOException {
        var src = Files.readString(ACTOR);
        // The last observed lie: the bunshin's summary said "connected back to
        // here" while the exit had failed — the failure arrived async, after the
        // speech sink cleared, and the report never learned of it.
        assertEquals(2, countOf(src, "final boolean forBunshin = bunshinSpeechSink != null"),
            "BOTH create handlers must capture forBunshin at ENTRY — the async "
            + "continuations run after the sink clears and cannot read it there");
        int h = src.indexOf("private Behavior<Command> onRoomCreationResult");
        var body = src.substring(h, src.indexOf("\n    }", h));
        assertTrue(body.contains("msg.forBunshin()") && body.contains("pendingBunshinOutcomes.add"),
            "bunshin-initiated async outcomes must be held for the report");
        int r = src.indexOf("private Behavior<Command> onBunshinReportReceived");
        var report = src.substring(r, src.indexOf("speakDirect(narration)", r));
        assertTrue(report.contains("pendingBunshinOutcomes"),
            "the return narration must append the recorded outcomes DETERMINISTICALLY");
        assertTrue(report.contains("pendingBunshinOutcomes.clear()"),
            "…and consume them, or a stale outcome haunts the next report");
    }

    private static int countOf(String haystack, String needle) {
        int n = 0, i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) { n++; i += needle.length(); }
        return n;
    }

    @Test
    @DisplayName("the offered set is not empty — the subset test must have work to do")
    void surfaceIsNotVacuous() {
        var surface = offeredSurface();
        assertTrue(surface.size() >= 20, "surface collapsed to " + surface.size()
            + "; the subset assertion would pass trivially");
        assertEquals(true, surface.contains("craft_item"), "expected a canonical maker verb");
    }
}
