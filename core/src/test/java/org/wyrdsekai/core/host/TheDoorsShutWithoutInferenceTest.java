package org.wyrdsekai.core.host;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.body.BodyKind;
import org.wyrdsekai.core.body.BodyMap;
import org.wyrdsekai.core.body.FeltWeight;
import org.wyrdsekai.core.body.LimbDescriptor;
import org.wyrdsekai.core.body.ReflexArena;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A door on the map can be shut without inference: its addresses go into a firewall set the
 * output chain rejects. The helper is a fixed script; the JVM only names the door. She is told
 * afterwards by a mark, and a door nobody can resolve is refused rather than guessed.
 */
class TheDoorsShutWithoutInferenceTest {

    private static final Path SCRIPT = Path.of("../packaging/brainstem/wyrdsekai-doors").toAbsolutePath().normalize();

    @AfterEach
    void tearDown() {
        HostDoors.resetForTests();
        BodyMap.resetForTests();
    }

    @Test
    @DisplayName("the JVM names the door; the helper gets a fixed argv; she reads a mark")
    void closeAndOpenThroughTheHelper() {
        var calls = new ArrayList<List<String>>();
        HostDoors.setExecForTests((argv, t) -> { calls.add(argv); return new HostHand.ExecResult(0, "closed door:relay: 203.0.113.5", "", false); },
            "Linux", Path.of("/opt/wyrdsekai/bin/wyrdsekai-doors"));
        HostDoors.setResolver(id -> id.equals("door:relay") ? List.of("relay.example.org") : List.of());
        var map = BodyMap.inMemory();
        map.attach(new LimbDescriptor("door:relay", BodyKind.DOOR, "relay door", "household", null,
            Duration.ofSeconds(30), FeltWeight.PRESENT, "nobody outside can reach me", "first"));

        var r = HostDoors.close("door:relay", "the steward");
        assertTrue(Boolean.TRUE.equals(r.get("ok")), r.toString());
        assertEquals(List.of("/opt/wyrdsekai/bin/wyrdsekai-doors", "close", "door:relay", "relay.example.org"), calls.get(0));
        var mark = map.recentMarks(1).get(0);
        assertEquals("I shut the door to relay door; nothing passes through it until it opens.", mark.text());
        assertEquals("door", mark.kind());

        var o = HostDoors.open("door:relay", "the steward");
        assertTrue(Boolean.TRUE.equals(o.get("ok")));
        assertEquals(List.of("/opt/wyrdsekai/bin/wyrdsekai-doors", "open", "door:relay"), calls.get(1));
        assertEquals("The door to relay door is open again.", map.recentMarks(1).get(0).text());
    }

    @Test
    @DisplayName("a door with no known address is refused; a non-Linux host is refused; nothing runs")
    void refusals() {
        var calls = new ArrayList<List<String>>();
        HostDoors.setExecForTests((argv, t) -> { calls.add(argv); return new HostHand.ExecResult(0, "", "", false); }, "Linux", Path.of("x"));
        var r = HostDoors.close("door:librarian", "the steward");
        assertFalse(Boolean.TRUE.equals(r.get("ok")));
        assertTrue(r.get("error").toString().contains("do not know"), r.toString());
        assertTrue(calls.isEmpty());
        assertFalse(Boolean.TRUE.equals(HostDoors.close("door:relay; rm -rf /", "x").get("ok")));
        HostDoors.setExecForTests((argv, t) -> { calls.add(argv); return new HostHand.ExecResult(0, "", "", false); }, "Mac OS X", Path.of("x"));
        HostDoors.setResolver(id -> List.of("relay.example.org"));
        assertTrue(HostDoors.close("door:relay", "x").get("error").toString().contains("Linux only"));
        assertTrue(calls.isEmpty());

        HostDoors.setExecForTests((argv, t) -> { calls.add(argv); return new HostHand.ExecResult(0, "", "", false); }, "Linux", Path.of("x"));
        HostDoors.setResolver(id -> List.of("127.0.0.1"));
        var map = BodyMap.inMemory();
        var self = HostDoors.close("door:librarian", "the steward");
        assertTrue(self.get("error").toString().contains("cut her off from her own brains"), self.toString());
        assertTrue(calls.isEmpty(), "the helper is not even asked");
        var proposal = map.recentMarks(1).get(0);
        assertEquals("immune", proposal.kind());
        assertEquals("steward", proposal.audience());
        assertTrue(proposal.text().contains("close door:librarian"), "the refusal is written as a proposal: " + proposal.text());
        assertTrue(HostDoors.isSelf("localhost") && HostDoors.isSelf("::1") && HostDoors.isSelf("169.254.10.1"));
        assertFalse(HostDoors.isSelf("relay.example.org") || HostDoors.isSelf("203.0.113.5"));
    }

    @Test
    @DisplayName("a reflex row can shut a door: no inference, and the door helper is what runs")
    void aReflexShutsADoor() {
        var calls = new ArrayList<List<String>>();
        HostDoors.setExecForTests((argv, t) -> { calls.add(argv); return new HostHand.ExecResult(0, "closed", "", false); }, "Linux", Path.of("doors"));
        HostDoors.setResolver(id -> List.of("198.51.100.7"));
        var map = BodyMap.inMemory();
        map.attach(new LimbDescriptor("door:zone:orchard", BodyKind.DOOR, "door to the orchard", "polity", null,
            Duration.ofSeconds(150), FeltWeight.QUIET, "closed", "first"));
        // One row: when the orchard's door goes quiet, shut it. The subject names the part the
        // condition watches and the door the action shuts; here they are the same door.
        var fired = new ReflexArena(List.of(new ReflexArena.Reflex("shut-orchard", ReflexArena.Input.PART_NUMB,
            "door:zone:orchard", 1.0, 1, ReflexArena.Action.CLOSE_DOOR, Duration.ofMinutes(5),
            "I shut the orchard's door because it had gone quiet."))).evaluate(null, numbDoor(map), Instant.now());
        assertEquals(1, fired.size());
        assertEquals(ReflexArena.Action.CLOSE_DOOR, fired.get(0).action());
        assertEquals(List.of("doors", "close", "door:zone:orchard", "198.51.100.7"), calls.get(0));
        assertTrue(map.recentMarks(3).stream().anyMatch(m -> m.text().startsWith("I shut the door to door to the orchard")));
        assertTrue(map.recentMarks(3).stream().anyMatch(m -> m.text().contains("gone quiet")), "the reflex's own mark");
    }

    private static BodyMap numbDoor(BodyMap map) {
        map.heartbeat("door:zone:orchard", false, "silent");
        return map;
    }

    @Test
    @DisplayName("the shipped helper: close resolves and adds elements, open removes exactly them, state survives between calls")
    void theHelperItself(@TempDir Path dir) throws Exception {
        assumeTrue(Files.isExecutable(SCRIPT), "helper present: " + SCRIPT);
        var fake = dir.resolve("nft");
        Files.writeString(fake, "#!/usr/bin/env bash\necho \"$*\" >> \"" + dir.resolve("nft.log") + "\"\n"
            + "case \"$1 $2\" in 'list table') [ -f \"" + dir.resolve("table") + "\" ] || exit 1;; 'add table') touch \"" + dir.resolve("table") + "\";; esac\nexit 0\n");
        fake.toFile().setExecutable(true);
        var state = dir.resolve("state");
        var run = (java.util.function.Function<List<String>, String>) args -> {
            try {
                var argv = new ArrayList<String>(List.of(SCRIPT.toString())); argv.addAll(args);
                var pb = new ProcessBuilder(argv).redirectErrorStream(true);
                pb.environment().put("DOORS_NFT", fake.toString());
                pb.environment().put("DOORS_STATE_DIR", state.toString());
                pb.environment().put("DOORS_OWN_ADDRS", "198.51.100.9 2001:db8::9");
                var p = pb.start();
                var out = new String(p.getInputStream().readAllBytes());
                assertTrue(p.waitFor(20, TimeUnit.SECONDS));
                return p.exitValue() + ":" + out.strip();
            } catch (Exception e) { throw new RuntimeException(e); }
        };
        assertEquals("0:every door is open", run.apply(List.of("list")));
        var closed = run.apply(List.of("close", "door:relay", "203.0.113.5", "2001:db8::7"));
        assertTrue(closed.startsWith("0:closed door:relay"), closed);
        var log = Files.readString(dir.resolve("nft.log"));
        assertTrue(log.contains("add table inet wyrdsekai"), log);
        assertTrue(log.contains("add rule inet wyrdsekai output ip daddr @closed4 reject"), log);
        assertTrue(log.contains("add element inet wyrdsekai closed4 { 203.0.113.5 }"), log);
        assertTrue(log.contains("add element inet wyrdsekai closed6 { 2001:db8::7 }"), log);
        assertEquals(List.of("2001:db8::7", "203.0.113.5"), Files.readAllLines(state.resolve("door:relay")));
        assertTrue(run.apply(List.of("list")).contains("door:relay: 2001:db8::7 203.0.113.5"));
        assertTrue(run.apply(List.of("close", "door:relay; rm", "1.2.3.4")).startsWith("1:"), "an unsafe door name is refused");
        var self = run.apply(List.of("close", "door:librarian", "127.0.0.1"));
        assertTrue(self.startsWith("1:") && self.contains("this host"), "loopback is never shut: " + self);
        assertFalse(Files.exists(state.resolve("door:librarian")), "a refused close leaves no state");
        assertFalse(Files.readString(dir.resolve("nft.log")).contains("127.0.0.1"), "and touches no set");
        var own = run.apply(List.of("close", "door:zone:x", "198.51.100.9"));
        assertTrue(own.startsWith("1:"), "one of the host's own addresses is never shut: " + own);

        assertEquals("0:opened door:relay", run.apply(List.of("open", "door:relay")));
        log = Files.readString(dir.resolve("nft.log"));
        assertTrue(log.contains("delete element inet wyrdsekai closed4 { 203.0.113.5 }"), log);
        assertTrue(log.contains("delete element inet wyrdsekai closed6 { 2001:db8::7 }"), log);
        assertFalse(Files.exists(state.resolve("door:relay")));
        assertEquals("0:door:relay was not shut", run.apply(List.of("open", "door:relay")));
        assertEquals("0:every door is open", run.apply(List.of("list")));
    }
}
