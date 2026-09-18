package org.wyrdsekai.core.body;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The brainstem and the server watch each other. This side: the brainstem's heartbeat file is
 * a part on the map, and its event ledger becomes marks she reads afterwards. The script
 * itself is exercised through its test hooks: an active unit that does not answer is
 * restarted after the miss count, with a snapshot first; a stopped unit is left alone; the
 * grace period protects a JVM that is still starting.
 */
class TwoThingsWatchingEachOtherTest {

    private static final Path SCRIPT = Path.of("..", "packaging", "brainstem", "wyrdsekai-brainstem").toAbsolutePath().normalize();

    @AfterEach
    void tearDown() {
        BodyMap.resetForTests();
    }

    @Test
    @DisplayName("no heartbeat file, no part; a stale heartbeat is a numb brainstem")
    void heartbeatIsAPart(@TempDir Path data) throws Exception {
        var map = BodyMap.inMemory();
        var link = new BrainstemLink(data, Duration.ofSeconds(30));
        assertTrue(link.beats(map).isEmpty(), "a node without a brainstem carries no phantom");

        var dir = data.resolve("brainstem");
        Files.createDirectories(dir);
        var hb = dir.resolve("heartbeat");
        Files.writeString(hb, "");
        var beat = link.beats(map).get(0);
        assertEquals(BrainstemLink.PART, beat.descriptor().id());
        assertTrue(beat.alive());

        Files.setLastModifiedTime(hb, FileTime.from(Instant.now().minus(Duration.ofMinutes(5))));
        assertFalse(link.beats(map).get(0).alive());
    }

    @Test
    @DisplayName("events become marks, once, across restarts of the reader")
    void eventsBecomeMarks(@TempDir Path data) throws Exception {
        var map = BodyMap.inMemory();
        var dir = data.resolve("brainstem");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("heartbeat"), "");
        Files.writeString(dir.resolve("events.jsonl"),
            "{\"ts\":\"2026-09-16T03:00:00Z\",\"event\":\"restarted\",\"reason\":\"the server was up but did not answer for 60 s; restarted after a snapshot\",\"snapshot\":\"/var/lib/wyrdsekai/backups/brainstem.world.db.x.bak\"}\n"
            + "{\"ts\":\"2026-09-16T03:00:30Z\",\"event\":\"stopped\",\"reason\":\"the server unit was stopped\",\"snapshot\":\"\"}\n");
        var link = new BrainstemLink(data, Duration.ofSeconds(30));
        link.beats(map);
        var marks = map.recentMarks(5);
        assertEquals(1, marks.size(), "an intentional stop is quiesce's mark, not the brainstem's");
        assertEquals("brainstem", marks.get(0).kind());
        assertTrue(marks.get(0).text().startsWith("The brainstem restarted me: the server was up but did not answer for 60 s"), marks.get(0).text());
        assertTrue(marks.get(0).text().endsWith("The record was snapshotted first."));

        link.beats(map);
        assertEquals(1, map.recentMarks(5).size(), "told once");

        Files.writeString(dir.resolve("events.jsonl"),
            "{\"ts\":\"2026-09-16T03:02:00Z\",\"event\":\"recovered\",\"reason\":\"after 60 s without an answer\",\"snapshot\":\"\"}\n",
            java.nio.file.StandardOpenOption.APPEND);
        new BrainstemLink(data, Duration.ofSeconds(30)).beats(map);
        assertEquals(2, map.recentMarks(5).size(), "a fresh reader picks up where the last one stopped");
        assertTrue(map.recentMarks(1).get(0).text().startsWith("I am answering again"));
    }

    @Test
    @DisplayName("the script: an active unit that stops answering is snapshotted and restarted after the misses; a stopped unit is left alone")
    void theScriptRestartsAHang(@TempDir Path tmp) throws Exception {
        assumeTrue(Files.isExecutable(SCRIPT), "needs the packaging tree");
        var state = tmp.resolve("state");
        var db = tmp.resolve("world.db");
        Files.writeString(db, "not really a database");
        var restarted = tmp.resolve("restarted");
        var env = new HashMap<String, String>();
        env.put("BRAINSTEM_STATE_DIR", state.toString());
        env.put("BRAINSTEM_DB", db.toString());
        env.put("BRAINSTEM_BACKUPS_DIR", tmp.resolve("backups").toString());
        env.put("BRAINSTEM_HOOKS_DIR", tmp.resolve("no-hooks").toString());
        env.put("BRAINSTEM_MISSES", "3");
        env.put("BRAINSTEM_RESTART_CMD", "touch " + restarted);
        env.put("BRAINSTEM_FAKE_UNIT", "active");
        env.put("BRAINSTEM_FAKE_HEALTH", "down");
        env.put("BRAINSTEM_FAKE_UPTIME", "1000");
        env.put("WYRDSEKAI_CONFIG_FILE", tmp.resolve("absent.conf").toString());

        run(env); run(env);
        assertFalse(Files.exists(restarted), "two misses are not a hang");
        assertEquals("2", Files.readString(state.resolve("misses")).strip());
        assertEquals("closed", Files.readString(state.resolve("doors")).strip(), "doors close while it is down");

        run(env);
        assertTrue(Files.exists(restarted), "the third miss restarts");
        var events = Files.readString(state.resolve("events.jsonl"));
        assertTrue(events.contains("\"event\":\"restarted\""), events);
        assertTrue(events.contains("brainstem.world.db."), "a snapshot was taken first: " + events);
        assertTrue(Files.list(tmp.resolve("backups")).findAny().isPresent());
        assertEquals("0", Files.readString(state.resolve("misses")).strip());

        // Back up: recovered, doors open.
        env.put("BRAINSTEM_FAKE_HEALTH", "up");
        run(env);
        assertTrue(Files.readString(state.resolve("events.jsonl")).contains("\"event\":\"recovered\""));
        assertEquals("open", Files.readString(state.resolve("doors")).strip());

        // An intentional stop is not a hang, and coming back from it is not a recovery.
        Files.deleteIfExists(restarted);
        env.put("BRAINSTEM_FAKE_UNIT", "inactive");
        env.put("BRAINSTEM_FAKE_HEALTH", "down");
        for (int i = 0; i < 5; i++) run(env);
        assertFalse(Files.exists(restarted), "a stopped unit is left alone");
        assertTrue(Files.readString(state.resolve("events.jsonl")).contains("\"event\":\"stopped\""));
        long recoveredBefore = Files.readString(state.resolve("events.jsonl")).split("\"event\":\"recovered\"", -1).length - 1;
        env.put("BRAINSTEM_FAKE_UNIT", "active");
        env.put("BRAINSTEM_FAKE_HEALTH", "up");
        env.put("BRAINSTEM_FAKE_UPTIME", "1000");
        run(env);
        long recoveredAfter = Files.readString(state.resolve("events.jsonl")).split("\"event\":\"recovered\"", -1).length - 1;
        assertEquals(recoveredBefore, recoveredAfter, "no 'recovered' after a stop the household asked for");
        env.put("BRAINSTEM_FAKE_HEALTH", "down");

        // A JVM still starting is not a hang either.
        env.put("BRAINSTEM_FAKE_UNIT", "active");
        env.put("BRAINSTEM_FAKE_UPTIME", "20");
        for (int i = 0; i < 5; i++) run(env);
        assertFalse(Files.exists(restarted), "inside the grace period");
        assertTrue(Files.exists(state.resolve("heartbeat")), "and it heartbeats on every pass");
    }

    @Test
    @DisplayName("after its own restart the brainstem waits out the grace again, even where the supervisor cannot say when the unit started")
    void theGraceIsReArmedByARestart(@TempDir Path tmp) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(SCRIPT));
        var state = tmp.resolve("state");
        Files.createDirectories(state);
        var restarts = tmp.resolve("restarts");
        var env = new java.util.HashMap<String, String>();
        env.put("BRAINSTEM_STATE_DIR", state.toString());
        env.put("BRAINSTEM_DB", tmp.resolve("no.db").toString());
        env.put("BRAINSTEM_BACKUPS_DIR", tmp.resolve("backups").toString());
        env.put("BRAINSTEM_HOOKS_DIR", tmp.resolve("no-hooks").toString());
        env.put("BRAINSTEM_MISSES", "2");
        var restartScript = tmp.resolve("restart.sh");
        Files.writeString(restartScript, "#!/usr/bin/env bash\necho r >> \"" + restarts + "\"\n");
        restartScript.toFile().setExecutable(true);
        env.put("BRAINSTEM_RESTART_CMD", restartScript.toString());
        env.put("BRAINSTEM_FAKE_UNIT", "active");
        env.put("BRAINSTEM_FAKE_HEALTH", "down");
        // No fake uptime: the unit is not known to systemd here, so the watcher's own
        // active_since is the only clock, as on launchd. It says the unit has been up an hour.
        Files.writeString(state.resolve("unit"), "active");
        Files.writeString(state.resolve("active_since"), Long.toString(java.time.Instant.now().getEpochSecond() - 3600));
        run(env); run(env);
        assertEquals(1, Files.readAllLines(restarts).size(), "a hang past the grace is restarted");
        for (int i = 0; i < 6; i++) run(env);
        assertEquals(1, Files.readAllLines(restarts).size(), "and the booting server is left alone until the grace is over again");
    }

    private static void run(Map<String, String> env) throws Exception {
        var pb = new ProcessBuilder("bash", SCRIPT.toString(), "--once");
        pb.environment().putAll(env);
        pb.redirectErrorStream(true);
        var p = pb.start();
        var out = new String(p.getInputStream().readAllBytes());
        assertTrue(p.waitFor(30, TimeUnit.SECONDS), "the pass finished");
        assertEquals(0, p.exitValue(), out);
    }
}
