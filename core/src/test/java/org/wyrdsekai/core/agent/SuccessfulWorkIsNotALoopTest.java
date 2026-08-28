package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.wyrdsekai.common.model.Exit;
import org.wyrdsekai.common.model.RoomObject;
import org.wyrdsekai.common.model.RoomSnapshot;
import java.util.List;

import org.wyrdsekai.common.model.Entity;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A loop is repeating something that did not work. Doing successful work twice is work.
 *
 * <h2>What went wrong</h2>
 * 2026-08-23 09:32, third build request of the morning on CodeZaiku: "WorldModel: blocking
 * dispatch_task — You've already tried dispatch_task recently." The two prior calls had
 * each built a working tool for a different ask. {@code dispatch_task} carries no target,
 * so by name every build is "the same action", and the guard counted successes. Build-first
 * had narrowed the surface to that one tool, so the block left nothing to call.
 */
class SuccessfulWorkIsNotALoopTest {

    @Test
    @DisplayName("two successful builds do not make a third one a loop")
    void successesDoNotCount() {
        var wm = new WorldModel();
        wm.recordTransition(room("s0", List.of("testwisp"), List.of(), List.of()), "dispatch_task", "", room("s1", List.of("testwisp"), List.of(), List.of()), true, "built library_tale");
        wm.recordTransition(room("s1", List.of("testwisp"), List.of(), List.of()), "dispatch_task", "", room("s2", List.of("testwisp"), List.of(), List.of()), true, "built location_weather_tool");
        assertThat(wm.isActionLoop("dispatch_task", ""))
            .as("three productive builds in a row are not a loop")
            .isFalse();
    }

    @Test
    @DisplayName("two failed attempts at the same thing still are")
    void failuresStillCount() {
        var wm = new WorldModel();
        wm.recordTransition(room("s0", List.of("testwisp"), List.of(), List.of()), "dispatch_task", "", null, false, "backend not installed");
        wm.recordTransition(room("s0", List.of("testwisp"), List.of(), List.of()), "dispatch_task", "", null, false, "backend not installed");
        assertThat(wm.isActionLoop("dispatch_task", "")).isTrue();
    }

    private static RoomSnapshot room(String id, List<String> entities, List<String> objects, List<String> exits) {
        return new RoomSnapshot(id, "Room " + id, "Description of " + id, "test",
            exits.stream().map(e -> {
                var parts = e.split("→");
                return new Exit(parts[0].trim(), parts[1].trim(), "Go " + parts[0].trim());
            }).toList(),
            entities.stream().map(n -> new Entity(n.toLowerCase(), n, "agent", "")).toList(),
            objects.stream().map(n -> new RoomObject(n, n, "An object.", true)).toList(),
            List.of());
    }

    /** dispatch_task's artifact lands minutes after the dispatch, so `changed` is always
     *  false at observation time — a successful dispatch must not count (17:11, third
     *  build blocked after two working ones, again). A failed one still does. */
    @org.junit.jupiter.api.Test
    void asyncEffectSuccessDoesNotCount() {
        var wm = new WorldModel();
        var here = room("s0", java.util.List.of("testwisp"), java.util.List.of(), java.util.List.of());
        wm.recordTransition(here, "dispatch_task", "", here, true, "built library_fairy_tale");
        wm.recordTransition(here, "dispatch_task", "", here, true, "built weather_check");
        org.assertj.core.api.Assertions.assertThat(wm.isActionLoop("dispatch_task", "")).isFalse();
        wm.recordTransition(here, "dispatch_task", "", here, false, "backend not installed");
        wm.recordTransition(here, "dispatch_task", "", here, false, "backend not installed");
        org.assertj.core.api.Assertions.assertThat(wm.isActionLoop("dispatch_task", "")).isTrue();
    }
}
