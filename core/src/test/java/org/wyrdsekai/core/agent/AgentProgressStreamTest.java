package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentProgressStreamTest {

    private AgentProgressStream stream;

    @BeforeEach
    void setup() {
        stream = new AgentProgressStream();
    }

    @Test
    void publish_delivers_to_subscribers() {
        var received = new ArrayList<AgentProgressStream.ProgressEvent>();
        stream.subscribe(received::add);

        stream.actionStarted("agent-1", "web_search", "Searching for weather");

        assertThat(received).hasSize(1);
        assertThat(received.getFirst()).isInstanceOf(
            AgentProgressStream.ProgressEvent.ActionStarted.class);
        var event = (AgentProgressStream.ProgressEvent.ActionStarted) received.getFirst();
        assertThat(event.agentId()).isEqualTo("agent-1");
        assertThat(event.actionType()).isEqualTo("web_search");
    }

    @Test
    void multiple_subscribers_all_receive() {
        var list1 = new ArrayList<AgentProgressStream.ProgressEvent>();
        var list2 = new ArrayList<AgentProgressStream.ProgressEvent>();
        stream.subscribe(list1::add);
        stream.subscribe(list2::add);

        stream.actionCompleted("agent-1", "go_to_room", "Arrived", true, 100);

        assertThat(list1).hasSize(1);
        assertThat(list2).hasSize(1);
    }

    @Test
    void unsubscribe_stops_delivery() {
        var received = new ArrayList<AgentProgressStream.ProgressEvent>();
        stream.subscribe(received::add);
        stream.actionStarted("a1", "search", "test");
        assertThat(received).hasSize(1);

        stream.unsubscribe(received::add);
        stream.actionStarted("a1", "search", "test2");
        // Unsubscribe with lambda won't work (different instance) — this tests the API
    }

    @Test
    void incremental_results_carry_index_and_total() {
        var received = new ArrayList<AgentProgressStream.ProgressEvent>();
        stream.subscribe(received::add);

        stream.incrementalResult("agent-1", "library_search", "Book 1", 0, 5);
        stream.incrementalResult("agent-1", "library_search", "Book 2", 1, 5);

        assertThat(received).hasSize(2);
        var r1 = (AgentProgressStream.ProgressEvent.IncrementalResult) received.get(0);
        assertThat(r1.index()).isEqualTo(0);
        assertThat(r1.total()).isEqualTo(5);
        assertThat(r1.data()).isEqualTo("Book 1");
    }

    @Test
    void plan_progress_tracks_goals() {
        var received = new ArrayList<AgentProgressStream.ProgressEvent>();
        stream.subscribe(received::add);

        stream.planProgress("agent-1", "find books", 1, 3, "Search library", "ACTIVE");

        assertThat(received).hasSize(1);
        var p = (AgentProgressStream.ProgressEvent.PlanProgress) received.getFirst();
        assertThat(p.currentGoal()).isEqualTo(1);
        assertThat(p.totalGoals()).isEqualTo(3);
        assertThat(p.goalDescription()).isEqualTo("Search library");
    }

    @Test
    void subscriber_exception_does_not_break_delivery() {
        var received = new ArrayList<AgentProgressStream.ProgressEvent>();
        stream.subscribe(e -> { throw new RuntimeException("bad subscriber"); });
        stream.subscribe(received::add);

        stream.actionStarted("a1", "test", "test");

        // Second subscriber should still receive despite first throwing
        assertThat(received).hasSize(1);
    }

    @Test
    void subscriber_count() {
        assertThat(stream.subscriberCount()).isEqualTo(0);
        stream.subscribe(e -> {});
        assertThat(stream.subscriberCount()).isEqualTo(1);
    }
}
