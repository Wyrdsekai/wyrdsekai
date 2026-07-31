package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DelegationChainStateTest {

    private static DelegationChainState.ChainStep step(String skill) {
        return new DelegationChainState.ChainStep(skill, Map.of(), skill + " desc");
    }

    @Test void create_initializes_executing_state() {
        var chain = DelegationChainState.create("c1", "Test goal",
            List.of(step("a"), step("b")), 0.8);
        assertThat(chain.status()).isEqualTo(DelegationChainState.ChainStatus.EXECUTING);
        assertThat(chain.currentStepIndex()).isEqualTo(0);
        assertThat(chain.completedResults()).isEmpty();
        assertThat(chain.isActive()).isTrue();
        assertThat(chain.isComplete()).isFalse();
    }

    @Test void currentStep_returns_first_step() {
        var chain = DelegationChainState.create("c1", "goal",
            List.of(step("a"), step("b")), 0.8);
        assertThat(chain.currentStep().skillName()).isEqualTo("a");
    }

    @Test void advanceStep_moves_to_next() {
        var chain = DelegationChainState.create("c1", "goal",
            List.of(step("a"), step("b"), step("c")), 0.8);
        var result = new DelegationChainState.StepResult(0, "a", true, "ok", 100);
        var next = chain.advanceStep(result);

        assertThat(next.currentStepIndex()).isEqualTo(1);
        assertThat(next.completedResults()).hasSize(1);
        assertThat(next.currentStep().skillName()).isEqualTo("b");
        assertThat(next.status()).isEqualTo(DelegationChainState.ChainStatus.EXECUTING);
    }

    @Test void advanceStep_to_completion() {
        var chain = DelegationChainState.create("c1", "goal",
            List.of(step("a")), 0.8);
        var result = new DelegationChainState.StepResult(0, "a", true, "done", 50);
        var next = chain.advanceStep(result);

        assertThat(next.isComplete()).isTrue();
        assertThat(next.status()).isEqualTo(DelegationChainState.ChainStatus.COMPLETED);
        assertThat(next.currentStep()).isNull();
    }

    @Test void pause_sets_status() {
        var chain = DelegationChainState.create("c1", "goal",
            List.of(step("a")), 0.8);
        var paused = chain.pause();
        assertThat(paused.status()).isEqualTo(DelegationChainState.ChainStatus.PAUSED);
        assertThat(paused.isActive()).isFalse();
    }

    @Test void abort_sets_status() {
        var chain = DelegationChainState.create("c1", "goal",
            List.of(step("a")), 0.8);
        assertThat(chain.abort().status()).isEqualTo(DelegationChainState.ChainStatus.ABORTED);
    }

    @Test void fail_sets_status() {
        var chain = DelegationChainState.create("c1", "goal",
            List.of(step("a")), 0.8);
        assertThat(chain.fail().status()).isEqualTo(DelegationChainState.ChainStatus.FAILED);
    }

    @Test void remainingSteps() {
        var chain = DelegationChainState.create("c1", "goal",
            List.of(step("a"), step("b"), step("c")), 0.8);
        assertThat(chain.remainingSteps()).isEqualTo(3);

        var next = chain.advanceStep(
            new DelegationChainState.StepResult(0, "a", true, "", 0));
        assertThat(next.remainingSteps()).isEqualTo(2);
    }

    @Test void hasEnergyBudget() {
        var chain = DelegationChainState.create("c1", "goal",
            List.of(step("a")), 0.8);
        assertThat(chain.hasEnergyBudget(0.5)).isTrue();
        assertThat(chain.hasEnergyBudget(0.16)).isFalse();
    }

    @Test void buildContextSection_shows_progress() {
        var chain = DelegationChainState.create("c1", "Plan dinner",
            List.of(step("search"), step("calendar"), step("email")), 0.8);
        var advanced = chain.advanceStep(
            new DelegationChainState.StepResult(0, "search", true, "Found recipes", 200));

        var section = advanced.buildContextSection();
        assertThat(section).contains("Plan dinner");
        assertThat(section).contains("step 2 of 3");
        assertThat(section).contains("search");
        assertThat(section).contains("Found recipes");
    }
}
