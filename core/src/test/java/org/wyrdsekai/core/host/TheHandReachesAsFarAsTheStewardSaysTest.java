package org.wyrdsekai.core.host;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.body.BodyMap;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The host hand: fixed verbs, argv only, and the steward's rung as the only guard. A verb above
 * the rung is refused with the rung named; a guarded upgrade halts on a driver or a kernel and
 * writes to the steward instead of running; everything that changes the host leaves a mark in
 * the steward's ledger, never a line in her own felt sense.
 */
class TheHandReachesAsFarAsTheStewardSaysTest {

    private final List<List<String>> ran = new ArrayList<>();
    private final List<String> stdins = new ArrayList<>();
    private String simulateOutput = "";

    @BeforeEach
    void setUp() {
        BodyMap.inMemory();
        HostHand.setExecForTests((argv, stdin, timeout) -> {
            ran.add(argv);
            stdins.add(stdin);
            if (argv.contains("-s")) return new HostHand.ExecResult(0, simulateOutput, "", false);
            return new HostHand.ExecResult(0, "ok: " + String.join(" ", argv) + "\n", "", false);
        }, "Linux");
        HostHand.setRungForTests(HostHand.Rung.OBSERVE);
    }

    @AfterEach
    void tearDown() {
        HostHand.setExecForTests(null, null);
        HostHand.setRungForTests(null);
        BodyMap.resetForTests();
    }

    @Test
    @DisplayName("observe reads the gauges; nothing above it runs")
    void observeReadsOnly() {
        var disk = HostHand.run("disk", "", "companion-mia");
        assertEquals(true, disk.get("ok"));
        assertEquals("df", ran.get(0).get(0));

        var say = HostHand.run("say", "moo", "companion-mia");
        assertEquals(false, say.get("ok"));
        assertTrue(String.valueOf(say.get("error")).contains("this needs guarded"), String.valueOf(say.get("error")));
        assertEquals(1, ran.size(), "refused before anything ran");

        var log = HostHand.run("log", "10", "companion-mia");
        assertTrue(String.valueOf(log.get("error")).contains("this needs localize"));
        assertTrue(BodyMap.get().recentMarks(5).isEmpty(), "reading leaves no mark");
    }

    @Test
    @DisplayName("the arguments are checked, never passed through")
    void argumentsAreChecked() {
        HostHand.setRungForTests(HostHand.Rung.LOCALIZE);
        var bad = HostHand.run("logs", "evil; rm -rf /", "companion-mia");
        assertEquals(false, bad.get("ok"));
        assertTrue(ran.isEmpty());

        var ok = HostHand.run("logs", "wyrdsekai-llama 5", "companion-mia");
        assertEquals(true, ok.get("ok"));
        assertEquals(List.of("docker", "logs", "--tail", "5", "wyrdsekai-llama"), ran.get(0));

        var big = HostHand.run("log", "99999", "companion-mia");
        assertEquals(true, big.get("ok"));
        assertTrue(ran.get(1).contains("200"), "capped: " + ran.get(1));
    }

    @Test
    @DisplayName("propose runs nothing and writes to the steward's ledger")
    void proposeWritesDown() {
        HostHand.setRungForTests(HostHand.Rung.PROPOSE);
        var r = HostHand.run("propose", "restart the voice brain tonight", "companion-mia");
        assertEquals(true, r.get("ok"));
        assertTrue(ran.isEmpty(), "a proposal runs nothing");
        var mark = BodyMap.get().recentMarks(1).get(0);
        assertEquals("hand", mark.kind());
        assertEquals("steward", mark.audience(), "the steward's ledger, not her felt line");
        assertTrue(mark.text().contains("I proposed to the steward: restart the voice brain tonight"));
        assertTrue(BodyMap.get().unreadFor("companion-mia").isEmpty(), "she is not told what she did herself");
    }

    @Test
    @DisplayName("guarded: say goes out through wall on stdin, and leaves a mark")
    void sayGoesThroughWall() {
        HostHand.setRungForTests(HostHand.Rung.GUARDED);
        var r = HostHand.run("say", "moo", "companion-mia");
        assertEquals(true, r.get("ok"));
        assertEquals(List.of("wall"), ran.get(0));
        assertEquals("moo\n", stdins.get(0));
        assertTrue(BodyMap.get().recentMarks(1).get(0).text().contains("I said on every terminal of the host: \"moo\""));
    }

    @Test
    @DisplayName("guarded: an upgrade that includes a driver or a kernel halts and writes to the steward")
    void upgradeHaltsOnTheDriver() {
        HostHand.setRungForTests(HostHand.Rung.GUARDED);
        simulateOutput = "Inst libc6 [2.39] (2.40)\nInst nvidia-driver-550 [550.1] (550.2)\nConf libc6\n";
        var r = HostHand.run("upgrade", "", "companion-mia");
        assertEquals(false, r.get("ok"));
        assertEquals(true, r.get("halted"));
        assertTrue(String.valueOf(r.get("reason")).contains("nvidia-driver-550"));
        assertEquals(1, ran.size(), "only the simulation ran");
        assertTrue(ran.get(0).contains("-s"));
        assertTrue(BodyMap.get().recentMarks(1).get(0).text().contains("I stopped before an upgrade"));
    }

    @Test
    @DisplayName("guarded: a plain upgrade runs, unattended, and is marked")
    void plainUpgradeRuns() {
        HostHand.setRungForTests(HostHand.Rung.GUARDED);
        simulateOutput = "Inst libc6 [2.39] (2.40)\nInst curl [8.5] (8.6)\n";
        var r = HostHand.run("upgrade", "", "companion-mia");
        assertEquals(true, r.get("ok"), String.valueOf(r));
        assertEquals(2, ran.size());
        assertEquals("apt-get", ran.get(1).get(0));
        assertTrue(ran.get(1).contains("upgrade") && !ran.get(1).contains("-s"));
        assertEquals(List.of("libc6", "curl"), r.get("packages"));
        assertTrue(BodyMap.get().recentMarks(1).get(0).text().contains("I upgraded 2 package(s)"));
    }

    @Test
    @DisplayName("reboot needs unattended; at unattended it holds still first, then asks the host")
    void rebootIsTheLastRung() {
        HostHand.setRungForTests(HostHand.Rung.GUARDED);
        assertEquals(false, HostHand.run("reboot", "", "companion-mia").get("ok"));
        assertTrue(ran.isEmpty());

        HostHand.setRungForTests(HostHand.Rung.UNATTENDED);
        var r = HostHand.run("reboot", "", "companion-mia");
        assertEquals(true, r.get("ok"));
        assertEquals(List.of("systemctl", "reboot"), ran.get(0));
        var kinds = BodyMap.get().recentMarks(3).stream().map(m -> m.kind()).toList();
        assertTrue(kinds.contains("hand") && kinds.contains("paused"), "marked and held still: " + kinds);
    }

    @Test
    @DisplayName("help says how far the hand reaches")
    void helpNamesTheRung() {
        var r = HostHand.run("", "", "companion-mia");
        assertEquals(true, r.get("ok"));
        assertTrue(String.valueOf(r.get("output")).startsWith("My hand reaches observe"));
        assertFalse(String.valueOf(r.get("output")).contains("upgrade"));
        assertEquals(Map.of().size(), ran.size());
    }

    @Test
    @DisplayName("the halt list is the steward's: drivers, kernels, boot, init, docker")
    void haltList() {
        for (var p : List.of("nvidia-driver-550", "libnvidia-compute-550", "linux-image-generic",
                "linux-headers-6.8.0-45", "grub-efi-amd64", "systemd", "docker-ce", "dkms", "cuda-toolkit-12-4")) {
            assertTrue(HostHand.HALT_PACKAGES.matcher(p).find(), p);
        }
        for (var p : List.of("libc6", "curl", "python3", "openssl", "wyrdsekai")) {
            assertFalse(HostHand.HALT_PACKAGES.matcher(p).find(), p);
        }
        assertEquals(Duration.ZERO, Duration.ZERO);
    }
}
