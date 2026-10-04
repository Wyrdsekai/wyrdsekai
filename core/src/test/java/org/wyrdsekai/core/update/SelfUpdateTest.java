package org.wyrdsekai.core.update;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SelfUpdateTest {

    @AfterEach void restore() { ReleaseCheck.env = System::getenv; ReleaseCheck.latestSource = ReleaseCheck::latest; }

    @Test
    void the_window_is_read_the_way_a_person_writes_it_and_wraps_midnight() {
        assertTrue(SelfUpdate.inWindow("03:00-05:00", LocalTime.of(3, 30)));
        assertFalse(SelfUpdate.inWindow("03:00-05:00", LocalTime.of(5, 0)));
        assertFalse(SelfUpdate.inWindow("03:00-05:00", LocalTime.of(14, 0)));
        assertTrue(SelfUpdate.inWindow("23:00-01:00", LocalTime.of(23, 30)));
        assertTrue(SelfUpdate.inWindow("23:00-01:00", LocalTime.of(0, 30)));
        assertFalse(SelfUpdate.inWindow("23:00-01:00", LocalTime.of(12, 0)));
        assertTrue(SelfUpdate.inWindow("00:00-00:00", LocalTime.of(12, 0)), "an empty span means any time");
        assertTrue(SelfUpdate.inWindow("whenever", LocalTime.of(12, 0)), "unreadable means any time, never never");
    }

    @Test
    void one_attempt_per_version_per_day() {
        Map<String, Object> s = new LinkedHashMap<>();
        assertFalse(SelfUpdate.attemptedToday(s, "0.3.0"));
        s.put("lastAttemptVersion", "0.3.0"); s.put("lastAttemptAt", Instant.now().minusSeconds(3600).toString());
        assertTrue(SelfUpdate.attemptedToday(s, "0.3.0"));
        assertFalse(SelfUpdate.attemptedToday(s, "0.3.1"));
        s.put("lastAttemptAt", Instant.now().minusSeconds(30 * 3600).toString());
        assertFalse(SelfUpdate.attemptedToday(s, "0.3.0"));
    }

    @Test
    void auto_mode_launches_only_a_release_build_inside_the_window_when_idle_and_only_once(@TempDir Path root, @TempDir Path data) throws Exception {
        Files.writeString(root.resolve("VERSION"), "0.3.0");
        Map<String, String> env = new HashMap<>();
        env.put("WYRDSEKAI_UPDATE", "auto"); env.put("WYRDSEKAI_UPDATE_WINDOW", "00:00-00:00");
        ReleaseCheck.env = env::get;
        ReleaseCheck.latestSource = repo -> Optional.of("0.3.1");
        var launches = new AtomicInteger();
        var idle = new boolean[] {false};
        var su = new SelfUpdate(root, data, () -> idle[0], () -> { launches.incrementAndGet(); return true; });

        assertTrue(su.tick().startsWith("busy"), su.state().toString());
        assertEquals(0, launches.get());
        idle[0] = true;
        assertTrue(su.tick().startsWith("installing 0.3.1"));
        assertEquals(1, launches.get());
        assertTrue(su.tick().startsWith("already attempted 0.3.1 today"), "no retry loop");
        assertEquals(1, launches.get());
        assertTrue(Files.isRegularFile(data.resolve("self-update.json")));
        assertEquals("0.3.1", su.state().get("lastAttemptVersion"));

        // a new instance reads the same state back, so a restart does not retry either
        var again = new SelfUpdate(root, data, () -> true, () -> { launches.incrementAndGet(); return true; });
        assertTrue(again.tick().startsWith("already attempted"));
        assertEquals(1, launches.get());
    }

    @Test
    void a_release_a_household_peer_runs_counts_like_one_github_announced_and_is_taken_from_that_peer(@TempDir Path root, @TempDir Path data) throws Exception {
        Files.writeString(root.resolve("VERSION"), "0.5.3");
        Map<String, String> env = new HashMap<>();
        ReleaseCheck.env = env::get;
        ReleaseCheck.latestSource = repo -> Optional.empty();   // GitHub not reached: the household still knows
        var peer = new MeshRelease.Candidate("0.5.4", "laptop", "http://laptop:7070", Instant.now().minusSeconds(600));
        var from = new java.util.concurrent.atomic.AtomicReference<String>("unset");
        var su = new SelfUpdate(root, data, () -> true, url -> { from.set(url); return true; }, () -> Optional.of(peer));
        assertEquals("newer release 0.5.4 (runs on laptop) — wyrd update now", su.tick());
        assertEquals("0.5.4", su.state().get("peerVersion"));
        assertEquals("laptop", su.state().get("peerNode"));
        env.put("WYRDSEKAI_UPDATE", "auto"); env.put("WYRDSEKAI_UPDATE_WINDOW", "00:00-00:00");
        assertTrue(su.tick().startsWith("installing 0.5.4 (runs on laptop)"), su.state().toString());
        assertEquals("http://laptop:7070", from.get());
        assertEquals("http://laptop:7070", su.state().get("lastAttemptFrom"));
        // GitHub ahead of the household: GitHub's release, from the release itself
        Files.writeString(root.resolve("VERSION"), "0.5.4");
        Files.deleteIfExists(ReleaseCheck.cacheFile(ReleaseCheck.REPO, data));
        ReleaseCheck.latestSource = repo -> Optional.of("0.5.6");
        var su2 = new SelfUpdate(root, data, () -> true, url -> { from.set(url); return true; }, () -> Optional.of(peer));
        assertTrue(su2.tick().startsWith("installing 0.5.6 (the service restarts"), su2.state().toString());
        assertNull(from.get());
        // Nothing newer anywhere
        ReleaseCheck.latestSource = repo -> Optional.of("0.5.4");
        Files.deleteIfExists(ReleaseCheck.cacheFile(ReleaseCheck.REPO, data));
        var su3 = new SelfUpdate(root, data, () -> true, url -> true, Optional::empty);
        assertEquals("current", su3.tick());
    }

    @Test
    void check_mode_says_so_and_never_launches_and_a_dev_build_or_a_pin_holds(@TempDir Path root, @TempDir Path data) throws Exception {
        Map<String, String> env = new HashMap<>();
        ReleaseCheck.env = env::get;
        ReleaseCheck.latestSource = repo -> Optional.of("0.3.1");
        Files.writeString(root.resolve("VERSION"), "0.3.0");
        var launches = new AtomicInteger();
        var su = new SelfUpdate(root, data, () -> true, () -> { launches.incrementAndGet(); return true; });
        assertEquals("newer release 0.3.1 — wyrd update now", su.tick());
        assertEquals(true, su.state().get("updateAvailable"));

        env.put("WYRDSEKAI_UPDATE", "auto"); env.put("WYRDSEKAI_UPDATE_WINDOW", "00:00-00:00");
        Files.writeString(root.resolve("VERSION"), "0.3.0~dev3");
        assertEquals("dev build; not auto-updating", su.tick());
        Files.writeString(root.resolve("VERSION"), "0.3.0");
        env.put("WYRDSEKAI_UPDATE_PIN", "0.3.0");
        assertEquals("pinned to 0.3.0", su.tick());
        env.remove("WYRDSEKAI_UPDATE_PIN");
        env.put("WYRDSEKAI_UPDATE_WINDOW", "03:00-03:01");
        var d = su.tick();
        assertTrue(d.startsWith("waiting for the window") || d.startsWith("installing"), d);
        ReleaseCheck.latestSource = repo -> Optional.of("0.3.0");
        Files.deleteIfExists(ReleaseCheck.cacheFile(ReleaseCheck.REPO, data));
        assertEquals("current", su.tick());
        assertTrue(launches.get() <= 1);
    }

    @Test
    void the_gauge_reads_idle_only_when_nothing_is_in_flight_for_a_while() {
        ActivityGauge.reset();
        assertTrue(ActivityGauge.idleFor(java.time.Duration.ZERO));
        ActivityGauge.inferenceStarted();
        assertFalse(ActivityGauge.idleFor(java.time.Duration.ZERO));
        ActivityGauge.inferenceFinished();
        assertFalse(ActivityGauge.idleFor(java.time.Duration.ofMinutes(10)), "just finished: not quiet yet");
        assertTrue(ActivityGauge.idleFor(java.time.Duration.ZERO));
        ActivityGauge.codingTaskStarted();
        assertEquals(1, ActivityGauge.codingTasks());
        ActivityGauge.codingTaskFinished();
        ActivityGauge.codingTaskFinished();
        assertEquals(0, ActivityGauge.codingTasks(), "never below zero");
    }
}
