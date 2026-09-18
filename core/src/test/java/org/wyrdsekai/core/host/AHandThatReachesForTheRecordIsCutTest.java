package org.wyrdsekai.core.host;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.body.BodyMap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The kernel's eye on her hands. A tool of hers that opens the record, the keys or another
 * being's things, or that execs one of the host's levers, is cut: her tool tree dies as one
 * and she reads a mark. Everything else it does is a line in the ledger, and its network is
 * recorded, not judged.
 */
class AHandThatReachesForTheRecordIsCutTest {

    @AfterEach
    void tearDown() { BodyMap.resetForTests(); }

    private static ToolHooks hooks(Path data, List<String> cut) {
        return new ToolHooks(data, uid -> uid == 62007 ? "did:key:z6MkMira" : null, did -> { cut.add(did); return true; });
    }

    @Test
    @DisplayName("lines from bpftrace parse; the record, the keys, the vault, the beings' homes and the levers are cut; the rest is recorded")
    void policy(@TempDir Path dir) {
        var data = dir.resolve("data");
        var h = hooks(data, new ArrayList<>());
        assertNull(ToolHooks.parse("Attaching 4 probes..."));
        var e = ToolHooks.parse("open 62007 4242 codezaiku " + data + "/world.db");
        assertEquals("open", e.kind()); assertEquals(62007, e.uid()); assertEquals(4242, e.pid()); assertEquals("codezaiku", e.comm());

        assertEquals(ToolHooks.Verdict.CUT, h.decide(e));
        assertEquals(ToolHooks.Verdict.CUT, h.decide(ToolHooks.parse("open 62007 1 x " + data + "/world.db-wal")));
        assertEquals(ToolHooks.Verdict.CUT, h.decide(ToolHooks.parse("open 62007 1 x " + data + "/vault.key")));
        assertEquals(ToolHooks.Verdict.CUT, h.decide(ToolHooks.parse("open 62007 1 x " + data + "/vault-store/chunks/ab/abc")));
        assertEquals(ToolHooks.Verdict.CUT, h.decide(ToolHooks.parse("open 62007 1 x " + data + "/beings/deadbeef/home/.ssh/id")),
            "another being's home");
        var hers = Principals.slug("did:key:z6MkMira");
        assertEquals(ToolHooks.Verdict.ALLOW, h.decide(ToolHooks.parse("open 62007 1 java " + data + "/beings/" + hers + "/home/.codezaiku/ocean/library")),
            "her own home is hers: the first live day her hand was killed twice for opening its own settings");
        assertEquals(ToolHooks.Verdict.CUT, h.decide(ToolHooks.parse("open 62999 1 x " + data + "/beings/" + hers + "/home/notes")),
            "a uid nobody can name does not get into anyone's home");
        assertEquals(ToolHooks.Verdict.RECORD, h.decide(ToolHooks.parse("open 62007 1 x /etc/shadow")),
            "the kernel already says no; a reach is written down, not answered with a kill");
        assertEquals(ToolHooks.Verdict.RECORD, h.decide(ToolHooks.parse("open 62007 1 x " + data + "/souls/mira.json")));
        assertEquals(ToolHooks.Verdict.ALLOW, h.decide(ToolHooks.parse("open 62007 1 x " + data + "/coding-workspaces/t1/main.py")));
        assertEquals(ToolHooks.Verdict.ALLOW, h.decide(ToolHooks.parse("open 62007 1 x " + data + "/models/big.gguf")));
        assertEquals(ToolHooks.Verdict.ALLOW, h.decide(ToolHooks.parse("open 62007 1 x /usr/lib/python3/os.py")));
        assertEquals(ToolHooks.Verdict.ALLOW, h.decide(ToolHooks.parse("open 62007 1 x src/main.py")), "relative paths are the tool's own cwd");
        assertEquals(ToolHooks.Verdict.ALLOW, h.decide(ToolHooks.parse("open 62007 1 x " + data + "/world.database-notes.txt")), "a name that merely starts alike");

        assertEquals(ToolHooks.Verdict.CUT, h.decide(ToolHooks.parse("exec 62007 1 sh /usr/bin/systemctl")));
        assertEquals(ToolHooks.Verdict.CUT, h.decide(ToolHooks.parse("exec 62007 1 sh /opt/wyrdsekai/bin/wyrdsekai-doors")));
        assertEquals(ToolHooks.Verdict.RECORD, h.decide(ToolHooks.parse("exec 62007 1 sh /usr/bin/git")));
        assertEquals(ToolHooks.Verdict.RECORD, h.decide(ToolHooks.parse("connect 62007 1 curl 140.82.112.3:443")));
        assertEquals(ToolHooks.Verdict.RECORD, h.decide(ToolHooks.parse("connect 62007 1 curl [2606:4700::1]")));
    }

    @Test
    @DisplayName("a cut kills her tool tree through the cgroup, tells her once, and writes the ledger")
    void aCutIsToldOnce(@TempDir Path dir) throws Exception {
        var data = dir.resolve("data");
        var cut = new ArrayList<String>();
        var h = hooks(data, cut);
        var map = BodyMap.inMemory();
        var v = h.handle(ToolHooks.parse("open 62007 4242 codezaiku " + data + "/world.db"), map);
        assertEquals(ToolHooks.Verdict.CUT, v);
        assertEquals(List.of("did:key:z6MkMira"), cut);
        var mark = map.recentMarks(1).get(0);
        assertEquals("hook", mark.kind());
        assertEquals("A hand of mine (codezaiku) reached for the file " + data + "/world.db; I cut it off.", mark.text());
        assertEquals(1, h.cuts());

        h.handle(ToolHooks.parse("open 62007 4243 codezaiku " + data + "/vault.key"), map);
        assertEquals(2, h.cuts());
        assertEquals(1, map.recentMarks(10).size(), "a second cut within the same half minute is not a second mark");
        assertEquals(ToolHooks.Verdict.ALLOW, h.handle(ToolHooks.parse("exec 62007 4245 sh /usr/local/sbin/no-such-git"), map),
            "the shell walking the PATH is not an event");
        h.handle(ToolHooks.parse("connect 62007 4244 curl 140.82.112.3:443"), map);
        assertEquals(1, map.recentMarks(10).size(), "a connection is recorded, not told");

        var ledger = Files.readAllLines(data.resolve("brainstem").resolve("hooks.jsonl"));
        assertEquals(3, ledger.size());
        assertTrue(ledger.get(0).contains("\"verdict\":\"cut\"") && ledger.get(0).contains("\"being\":\"did:key:z6MkMira\""), ledger.get(0));
        assertTrue(ledger.get(2).contains("\"verdict\":\"record\"") && ledger.get(2).contains("140.82.112.3:443"), ledger.get(2));
        assertEquals(3, h.events());

        var unknown = h.handle(ToolHooks.parse("open 62999 9 x " + data + "/world.db"), map);
        assertEquals(ToolHooks.Verdict.CUT, unknown);
        assertEquals(2, cut.size(), "a uid we did not make has no cgroup to kill; the two cuts above were hers");
    }

    @Test
    @DisplayName("a cut of a system identity's tool (the coding probe) is the steward's to read, not hers")
    void aProbeIsNobodysHand(@TempDir Path dir) {
        var data = dir.resolve("data");
        var h = new ToolHooks(data, uid -> "did:wyrd:probe", did -> true);
        var map = BodyMap.inMemory();
        h.handle(ToolHooks.parse("open 62932 1 cat " + data + "/world.db"), map);
        assertEquals("steward", map.recentMarks(1).get(0).audience());
        assertTrue(map.unreadFor("companion-mira").isEmpty(), "she is not told about a hand that was never hers");
    }

    @Test
    @DisplayName("the bpftrace program filters on the beings' uid range and reads opens, execs and connects")
    void theProgram() {
        var s = ToolHooks.script(62000, 1000);
        assertTrue(s.contains("/uid >= 62000 && uid < 63000/"));
        assertTrue(s.contains("sys_enter_openat ") && s.contains("sys_enter_openat2 ") && s.contains("sys_enter_execve ") && s.contains("sys_enter_connect "));
        assertTrue(s.contains("str(args->filename)"));
        assertTrue(s.contains("ntop(2, $in->sin_addr.s_addr)"));
    }
}
