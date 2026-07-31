package org.wyrdsekai.core.identity;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilityTest {

    @Test void exact_command_match() {
        var parent = new Capability("/room/read");
        var child = new Capability("/room/read");
        assertThat(child.isSubsetOf(parent)).isTrue();
    }

    @Test void child_more_specific_path() {
        var parent = new Capability("/room");
        var child = new Capability("/room/read");
        assertThat(child.isSubsetOf(parent)).isTrue();
    }

    @Test void child_different_path_rejected() {
        var parent = new Capability("/room/read");
        var child = new Capability("/zone/transit");
        assertThat(child.isSubsetOf(parent)).isFalse();
    }

    @Test void wildcard_parent_grants_everything() {
        var parent = new Capability("/*");
        var child = new Capability("/room/read");
        assertThat(child.isSubsetOf(parent)).isTrue();
    }

    @Test void child_cannot_escalate_to_broader_path() {
        var parent = new Capability("/room/read");
        var child = new Capability("/room");
        assertThat(child.isSubsetOf(parent)).isFalse();
    }

    @Test void policy_subset_check() {
        var parent = new Capability("/room/read", Map.of("roomId", "room-1"));
        var child = new Capability("/room/read", Map.of("roomId", "room-1", "format", "text"));
        assertThat(child.isSubsetOf(parent)).isTrue(); // child adds constraints, fine
    }

    @Test void child_missing_parent_constraint_rejected() {
        var parent = new Capability("/room/read", Map.of("roomId", "room-1"));
        var child = new Capability("/room/read", Map.of()); // child is broader
        assertThat(child.isSubsetOf(parent)).isFalse();
    }

    @Test void conflicting_policy_rejected() {
        var parent = new Capability("/room/read", Map.of("roomId", "room-1"));
        var child = new Capability("/room/read", Map.of("roomId", "room-2"));
        assertThat(child.isSubsetOf(parent)).isFalse();
    }

    @Test void blank_command_rejected() {
        assertThatThrownBy(() -> new Capability(""))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void command_must_start_with_slash() {
        assertThatThrownBy(() -> new Capability("room/read"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void partial_path_match_not_a_subset() {
        // "/room/readonly" is NOT a sub-path of "/room/read"
        var parent = new Capability("/room/read");
        var child = new Capability("/room/readonly");
        assertThat(child.isSubsetOf(parent)).isFalse();
    }
}
