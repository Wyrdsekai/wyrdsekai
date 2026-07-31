package org.wyrdsekai.core.governance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TaskClaimProtocolTest {

    private TaskClaimProtocol protocol;

    @BeforeEach void setUp() {
        protocol = new TaskClaimProtocol();
    }

    @Test void post_creates_open_task() {
        var task = protocol.post("Fix the pipe", "boiler-room", "engineer");
        assertThat(task.isOpen()).isTrue();
        assertThat(protocol.taskCount()).isEqualTo(1);
    }

    @Test void claim_succeeds_for_open_task() {
        var task = protocol.post("Fix the pipe", "boiler-room", "engineer");
        var claimed = protocol.claim(task.id(), "agent-1");
        assertThat(claimed).isPresent();
        assertThat(claimed.get().isClaimed()).isTrue();
        assertThat(claimed.get().claimedBy()).isEqualTo("agent-1");
    }

    @Test void claim_fails_for_already_claimed_task() {
        var task = protocol.post("Fix the pipe", "boiler-room", "engineer");
        protocol.claim(task.id(), "agent-1");

        var secondClaim = protocol.claim(task.id(), "agent-2");
        assertThat(secondClaim).isEmpty();
    }

    @Test void complete_succeeds_for_claimant() {
        var task = protocol.post("Fix the pipe", "boiler-room", "engineer");
        protocol.claim(task.id(), "agent-1");

        var completed = protocol.complete(task.id(), "agent-1");
        assertThat(completed).isPresent();
        assertThat(completed.get().state()).isEqualTo(BlackboardTask.TaskState.COMPLETED);
    }

    @Test void complete_fails_for_non_claimant() {
        var task = protocol.post("Fix the pipe", "boiler-room", "engineer");
        protocol.claim(task.id(), "agent-1");

        var completed = protocol.complete(task.id(), "agent-2");
        assertThat(completed).isEmpty();
    }

    @Test void cancel_by_poster() {
        var task = protocol.post("Fix the pipe", "boiler-room", "engineer");
        var cancelled = protocol.cancel(task.id(), "engineer");
        assertThat(cancelled).isPresent();
        assertThat(cancelled.get().state()).isEqualTo(BlackboardTask.TaskState.CANCELLED);
    }

    @Test void openTasks_filters_by_room() {
        protocol.post("Task A", "room-1", "alice");
        protocol.post("Task B", "room-2", "bob");
        protocol.post("Task C", "room-1", "carol");

        assertThat(protocol.openTasks("room-1")).hasSize(2);
        assertThat(protocol.openTasks("room-2")).hasSize(1);
    }

    @Test void openTaskCount_excludes_claimed() {
        var t1 = protocol.post("Task A", "room-1", "alice");
        protocol.post("Task B", "room-1", "bob");
        protocol.claim(t1.id(), "agent-1");

        assertThat(protocol.openTaskCount()).isEqualTo(1);
    }
}
