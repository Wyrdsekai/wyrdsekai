package org.wyrdsekai.core.coding;

import org.junit.jupiter.api.Test;

import org.wyrdsekai.core.agent.AgentEvent;
import org.wyrdsekai.core.agent.AgentEventStream;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A finished coding task has to announce itself, whichever door it came through.
 *
 * <p>{@link CodingTaskItemBridge} listens for a terminal {@code ZoneBroadcast}, translates
 * it through the backend's {@link BackendAdapter}, runs {@link ItemInvokeSmoke} against a
 * no-side-effect stub, and places the artifact in the originating room. That whole
 * apparatus — bridge plus eleven adapters — depends on somebody publishing the event.
 *
 * <p>Two paths reach a backend. A zone command typed in the Workshop went through
 * {@link CodingNamespaceHandler}, which published. A companion's {@code dispatch_task}
 * called {@code backend.submit()} directly and published nothing. On the household node
 * the bridge logged its subscription on every boot and received nothing, ever, while Goose
 * completed tasks touching one and two files (2026-08-19).
 *
 * <p>What that cost was not just a missing feature. Asked to build an in-world item, the
 * companion chose to delegate it to the coding backend — the right instinct, and a
 * supported design — and the work ran and the result was dropped on the floor. She then
 * had to tell the steward she could not do it.
 */
class BothDoorsRingTheBellTest {

    @Test
    void the_publisher_is_shared_so_the_two_paths_cannot_drift() {
        // One implementation, reachable from both. A second copy of the payload-building
        // would drift, and a broadcast under the wrong namespace is dropped in silence.
        assertThat(Arrays.stream(CodingTaskBroadcast.class.getMethods())
                .map(Method::getName))
            .contains("publishTerminal");
    }

    @Test
    void the_companion_path_announces_completion() throws Exception {
        // The half that was missing: the dispatch_task completion handler must publish.
        var actor = Class.forName("org.wyrdsekai.core.agent.CompanionActor");
        assertThat(Arrays.stream(actor.getDeclaredMethods()).map(Method::getName))
            .as("dispatch_task completion has to ring the bell too")
            .contains("publishCodingTerminal");
    }

    @Test
    void the_zone_command_path_still_announces_completion() {
        // Guard the regression the extraction could have caused: the door that always
        // worked must keep working.
        assertThat(Arrays.stream(CodingNamespaceHandler.class.getDeclaredMethods())
                .map(Method::getName))
            .contains("publishTerminal");
    }

    @Test
    void what_the_publisher_emits_is_what_the_bridge_accepts() throws Exception {
        // THE SEAM THAT BROKE. Every existing bridge test constructs a ZoneBroadcast by
        // hand and calls bridge.accept(event) — they all start downstream of the publish,
        // so "does anything actually publish, and in a shape the bridge recognises?" had
        // no coverage. That is how a bridge plus eleven adapters sat starving while the
        // work ran and the results were dropped.
        //
        // This joins the two halves: publish through the production publisher, and assert
        // the bridge takes it. A namespace mismatch here is silent in production.
        var registry = new BackendRegistry();
        var placements = new CopyOnWriteArrayList<CodingTaskItemBridge.RoomItemPlacement>();
        var bridge = new CodingTaskItemBridge(registry, placements::add);

        // Our OWN stream, not the global one. AgentEventStream.init() replaces the global
        // instance outright, so any other test initialising between our subscribe and our
        // publish would orphan the subscriber and this would fail for a reason that has
        // nothing to do with the seam it guards (seen 2026-08-20, once five unrelated
        // tests changed the class ordering). The publisher takes the stream so this is
        // deterministic; production still resolves the global.
        var seen = new CopyOnWriteArrayList<AgentEvent>();
        var stream = new AgentEventStream();
        stream.subscribe("test-observer", seen::add);

        CodingTaskBroadcast.publishTerminal(
            GooseBackend.NAME, "workshop", "task-1",
            new TaskResult(UUID.randomUUID(), GooseBackend.NAME, TaskStatus.SUCCEEDED,
                "done", List.of(), 0L, 0L),
            null, stream);

        // AgentEventStream delivers through a QUEUE. Reading straight after publishing
        // races, and under full-suite load it loses — this test read empty on 2026-08-20
        // while passing in isolation. Every assertion on this stream has to poll; that is
        // now three separate tests bitten by the same thing.
        awaitEvent(seen);
        var broadcasts = seen.stream()
            .filter(e -> e instanceof AgentEvent.ZoneBroadcast)
            .map(e -> (AgentEvent.ZoneBroadcast) e)
            .toList();
        assertThat(broadcasts)
            .as("the publisher must actually emit something")
            .isNotEmpty();
        assertThat(broadcasts.getLast().namespace())
            .as("and under the backend's own name, which is what the bridge matches on")
            .isEqualTo(GooseBackend.NAME);
    }

    @Test
    void a_task_with_nowhere_to_land_is_not_announced() {
        // An artifact with no room is not an artifact. Must not throw, must not publish.
        CodingTaskBroadcast.publishTerminal("goose", null, "task-1", null, null);
        CodingTaskBroadcast.publishTerminal("goose", "  ", "task-1", null, null);
    }

    @Test
    void a_missing_result_is_survivable() {
        CodingTaskBroadcast.publishTerminal("goose", "nexus", "task-1", null, null);
    }

    /** Wait for queued delivery — this stream is asynchronous. */
    private static void awaitEvent(java.util.List<AgentEvent> seen) throws InterruptedException {
        for (int i = 0; i < 100 && seen.isEmpty(); i++) {
            Thread.sleep(50);
        }
    }
}
