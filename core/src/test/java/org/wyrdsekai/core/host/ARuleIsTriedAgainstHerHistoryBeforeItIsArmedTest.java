package org.wyrdsekai.core.host;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.body.BodyMap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * History is the world a rule is tried in before it acts. Everything her hands do and are
 * not cut for is remembered; a candidate rule set is replayed over that, and one that would
 * have cut her ordinary work is refused, as is any rule when there is not yet enough history
 * to say. The same question is asked at every start, of whatever rules the node carries.
 * On the first live day there was no such gate, and her coding tool was killed twice for
 * opening a file in her own home.
 */
class ARuleIsTriedAgainstHerHistoryBeforeItIsArmedTest {

    private static final String MIRA = "did:key:z6MkMira";
    private static final int UID = 62007;

    @AfterEach
    void tearDown() { BodyMap.resetForTests(); }

    private static ToolHooks hooks(Path data, List<String> cut) {
        return new ToolHooks(data, uid -> uid == UID ? MIRA : null, did -> { cut.add(did); return true; });
    }

    private static ToolHooks.Event open(String path) { return new ToolHooks.Event("open", UID, 1, "java", path); }

    /** A few days of her ordinary coding work. */
    private static void ordinaryWork(ToolHooks h, Path data, Instant from, int days) {
        var hers = Principals.slug(MIRA);
        for (int d = 0; d <= days; d++) {
            var t = from.plus(Duration.ofDays(d));
            h.history().note(open(data + "/coding-cli-bundle/codezaiku/lib/core.jar"), t);
            h.history().note(open(data + "/coding-workspaces/task-" + d + "/main.py"), t);
            h.history().note(open(data + "/beings/" + hers + "/home/.codezaiku/ocean/library"), t);
            h.history().note(new ToolHooks.Event("exec", UID, 1, "sh", "/usr/bin/git"), t);
            h.history().note(new ToolHooks.Event("connect", UID, 1, "java", "127.0.0.1:8200"), t);
        }
    }

    @Test
    @DisplayName("what she is not cut for is remembered, folded, and survives a restart; what she was cut for is not normal")
    void theHistory(@TempDir Path dir) throws Exception {
        var data = dir.resolve("data");
        var h = hooks(data, new ArrayList<>());
        var map = BodyMap.inMemory();
        h.handle(open(data + "/coding-workspaces/aaa/main.py"), map);
        h.handle(open(data + "/coding-workspaces/bbb/main.py"), map);
        h.handle(open("/proc/4242/status"), map);
        h.handle(open(data + "/world.db"), map);
        assertEquals(2, h.history().distinct(), "two tasks' files fold into one row; the cut never enters");
        assertEquals(3, h.history().events());
        assertTrue(h.history().rows().stream().anyMatch(r -> r.arg().endsWith("/coding-workspaces/*/main.py") && r.count() == 2));
        assertTrue(h.history().rows().stream().noneMatch(r -> r.arg().contains("world.db")));
        h.stop();
        var again = new HookHistory(data.resolve("brainstem").resolve("hooks-seen.jsonl"));
        again.load();
        assertEquals(3, again.events());
    }

    @Test
    @DisplayName("a rule that would cut her ordinary work is refused, and the replay says exactly what it would have cut")
    void refusedWhenItWouldCutHer(@TempDir Path dir) throws Exception {
        var data = dir.resolve("data");
        var h = hooks(data, new ArrayList<>());
        var map = BodyMap.inMemory();
        ordinaryWork(h, data, Instant.parse("2026-09-10T09:00:00Z"), 5);
        assertTrue(h.replay(null).clean(), "the rules she lives under cut nothing she normally does");

        // The 09-17 mistake, as a candidate: every being's home, hers included, plus the coding bundle.
        var careless = HookRules.parse("{\"cut\":[\"${data}/world.db\",\"${data}/coding-cli-bundle/\"],\"closed\":[],\"cutExecs\":[\"git\"]}");
        var replay = h.replay(careless);
        assertFalse(replay.clean());
        assertEquals(2, replay.wouldCut().size(), "the bundle's jar and git");
        assertEquals(12, replay.wouldCutEvents(), "six days of each");
        var armed = h.arm(careless, false, Duration.ofDays(3), "the steward", map);
        assertFalse(armed.ok());
        assertTrue(armed.why().contains("would have cut 2 thing(s)"), armed.why());
        assertEquals(HookRules.defaults(), h.rules(), "nothing changed");
        assertTrue(Files.notExists(data.resolve("brainstem").resolve("hook-rules.json")));
    }

    @Test
    @DisplayName("a clean rule is armed only when there is enough of her history to have tried it against")
    void needsEnoughHistory(@TempDir Path dir) throws Exception {
        var data = dir.resolve("data");
        var h = hooks(data, new ArrayList<>());
        var map = BodyMap.inMemory();
        var careful = HookRules.parse("{\"cut\":[\"${data}/world.db\",\"${data}/vault.key\",\"/etc/sudoers\"],\"closed\":[\"${data}/souls/\"],\"cutExecs\":[\"nft\"]}");

        ordinaryWork(h, data, Instant.parse("2026-09-17T09:00:00Z"), 0);
        var early = h.arm(careful, false, Duration.ofDays(3), "the steward", map);
        assertFalse(early.ok());
        assertTrue(early.why().contains("her history covers 0 h of the 72 h"), early.why());

        ordinaryWork(h, data, Instant.parse("2026-09-17T09:00:00Z"), 4);
        var later = h.arm(careful, false, Duration.ofDays(3), "the steward", map);
        assertTrue(later.ok(), later.why());
        assertEquals(careful, h.rules());
        assertEquals(careful, HookRules.load(data.resolve("brainstem").resolve("hook-rules.json")));
        assertEquals(ToolHooks.Verdict.CUT, h.decide(open("/etc/sudoers")), "the armed rules decide");
        var mark = map.recentMarks(1).get(0);
        assertEquals("steward", mark.audience());
        assertTrue(mark.text().startsWith("New hook rules armed by the steward after a clean replay"), mark.text());
    }

    @Test
    @DisplayName("force arms anyway, and the steward's ledger says what was overridden")
    void forcedIsWrittenDown(@TempDir Path dir) throws Exception {
        var data = dir.resolve("data");
        var h = hooks(data, new ArrayList<>());
        var map = BodyMap.inMemory();
        ordinaryWork(h, data, Instant.parse("2026-09-10T09:00:00Z"), 5);
        var careless = HookRules.parse("{\"cut\":[\"${data}/coding-cli-bundle/\"],\"closed\":[],\"cutExecs\":[]}");
        var armed = h.arm(careless, true, Duration.ofDays(3), "the steward", map);
        assertTrue(armed.ok());
        assertTrue(map.recentMarks(1).get(0).text().contains("FORCED by the steward although these rules would have cut 1 thing(s)"),
            map.recentMarks(1).get(0).text());
    }

    @Test
    @DisplayName("at every start the node's own rules face her history; rules that would cut her are not enforced")
    void demotedAtStart(@TempDir Path dir) throws Exception {
        var data = dir.resolve("data");
        var cut = new ArrayList<String>();
        var first = hooks(data, cut);
        ordinaryWork(first, data, Instant.parse("2026-09-10T09:00:00Z"), 5);
        first.stop();   // flushes her history
        // A release, or a hand edit, leaves rules on the node that would cut the coding bundle.
        Files.createDirectories(data.resolve("brainstem"));
        Files.writeString(data.resolve("brainstem").resolve("hook-rules.json"),
            "{\"cut\":[\"${data}/world.db\",\"${data}/coding-cli-bundle/\"],\"closed\":[],\"cutExecs\":[]}");

        var map = BodyMap.inMemory();
        var next = hooks(data, cut);
        next.boot(map, "enforce");
        assertEquals(ToolHooks.Mode.RECORD, next.mode());
        var mark = map.recentMarks(1).get(0);
        assertEquals("steward", mark.audience());
        assertTrue(mark.text().startsWith("The hooks are in record-only mode"), mark.text());

        assertEquals(ToolHooks.Verdict.CUT, next.handle(open(data + "/coding-cli-bundle/codezaiku/lib/core.jar"), map));
        assertTrue(cut.isEmpty(), "record-only: her hand is not touched");
        assertEquals(1, map.recentMarks(10).size(), "and she is told nothing, because nothing happened to her");
        var ledger = Files.readAllLines(data.resolve("brainstem").resolve("hooks.jsonl"));
        assertTrue(ledger.get(0).contains("\"verdict\":\"would-cut\""), ledger.get(0));

        var clean = hooks(dir.resolve("other"), new ArrayList<>());
        clean.boot(map, "enforce");
        assertEquals(ToolHooks.Mode.ENFORCE, clean.mode(), "no history and the built-in rules: enforced");
        var asked = hooks(dir.resolve("third"), new ArrayList<>());
        asked.boot(map, "record");
        assertEquals(ToolHooks.Mode.RECORD, asked.mode(), "the steward may also simply ask for record-only");
    }
}
