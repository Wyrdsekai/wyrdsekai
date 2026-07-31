package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProactivityCoordinatorTest {

    @BeforeEach void init() {
        ProactivityCoordinator.init();
    }

    @Test void no_duplicate_when_no_prior_action() {
        var coordinator = ProactivityCoordinator.get();
        assertThat(coordinator.isDuplicate("agent-1", "oracle")).isFalse();
    }

    @Test void duplicate_detected_from_another_agent() {
        var coordinator = ProactivityCoordinator.get();
        coordinator.recordAction("agent-1", "oracle", "Weekly pattern detected");
        assertThat(coordinator.isDuplicate("agent-2", "oracle")).isTrue();
    }

    @Test void own_action_does_not_suppress_self() {
        var coordinator = ProactivityCoordinator.get();
        coordinator.recordAction("agent-1", "oracle", "Pattern");
        assertThat(coordinator.isDuplicate("agent-1", "oracle")).isFalse();
    }

    @Test void different_category_not_duplicate() {
        var coordinator = ProactivityCoordinator.get();
        coordinator.recordAction("agent-1", "oracle", "Pattern");
        assertThat(coordinator.isDuplicate("agent-2", "care")).isFalse();
    }

    @Test void cooldown_active_after_action() {
        var coordinator = ProactivityCoordinator.get();
        coordinator.recordAction("agent-1", "oracle", "Pattern");
        assertThat(coordinator.isCooldownActive()).isTrue();
    }

    @Test void cooldown_inactive_initially() {
        var coordinator = ProactivityCoordinator.get();
        assertThat(coordinator.isCooldownActive()).isFalse();
    }
}
