package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.skill.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DelegationChainExecutorTest {

    private SkillRegistry registry;
    private SkillUsageTracker tracker;
    private DelegationChainExecutor executor;
    private FakeExecutor fakeExecutor;

    /**
     * Fake executor that returns preconfigured results for specific skills.
     */
    static class FakeExecutor implements SkillExecutor {
        final Map<String, SkillResult> results = new HashMap<>();

        void setResult(String skillId, boolean success, String output) {
            results.put(skillId, new SkillResult(
                success, output, null, 50, SkillTier.NATIVE, skillId, null));
        }

        @Override
        public SkillResult execute(String skillId, Map<String, Object> params, SkillContext context) {
            return results.getOrDefault(skillId,
                new SkillResult(false, "Skill not found", null, 0, null, skillId, null));
        }

        @Override public List<SkillDefinition> availableSkills() { return List.of(); }
        @Override public boolean supports(String skillId) { return results.containsKey(skillId); }
        @Override public SkillTier tier() { return SkillTier.NATIVE; }
    }

    @BeforeEach void setUp() {
        registry = new SkillRegistry(null, null);
        fakeExecutor = new FakeExecutor();
        registry.registerExecutor(fakeExecutor);
        // Allow all skills for test agent
        registry.setPermissions("did:test", SkillPermission.allowAll());
        tracker = new SkillUsageTracker();
        executor = DelegationChainExecutor.serverDefault(registry, null, tracker);
    }

    private static DelegationChainState.ChainStep step(String skill) {
        return new DelegationChainState.ChainStep(skill, Map.of(), null);
    }

    // --- Start chain ---

    @Nested class StartChain {
        @Test void starts_successfully() {
            var error = executor.startChain("Test goal",
                List.of(step("a"), step("b")), 0.8);
            assertThat(error).isNull();
            assertThat(executor.hasActiveChain()).isTrue();
        }

        @Test void rejects_when_chain_already_active() {
            executor.startChain("First", List.of(step("a")), 0.8);
            var error = executor.startChain("Second", List.of(step("b")), 0.8);
            assertThat(error).contains("already in progress");
        }

        @Test void rejects_empty_steps() {
            var error = executor.startChain("Empty", List.of(), 0.8);
            assertThat(error).contains("No steps");
        }

        @Test void rejects_too_many_steps() {
            var steps = new ArrayList<DelegationChainState.ChainStep>();
            for (int i = 0; i < 9; i++) steps.add(step("s" + i));
            var error = executor.startChain("Big", steps, 0.8);
            assertThat(error).contains("Too many steps");
        }

        @Test void rejects_insufficient_energy() {
            var error = executor.startChain("Low", List.of(step("a")), 0.10);
            assertThat(error).contains("Not enough energy");
        }

        @Test void phone_max_steps() {
            var phoneExec = DelegationChainExecutor.phoneDefault(registry, null, tracker);
            var steps = new ArrayList<DelegationChainState.ChainStep>();
            for (int i = 0; i < 5; i++) steps.add(step("s" + i));
            var error = phoneExec.startChain("Many", steps, 0.8);
            assertThat(error).contains("Too many steps");
        }
    }

    // --- Execute steps ---

    @Nested class ExecuteSteps {
        @Test void executes_via_registry() {
            fakeExecutor.setResult("search", true, "Found results");

            executor.startChain("Test", List.of(step("search")), 0.8);
            var outcome = executor.executeCurrentStep("did:test", "room1", 0.8);

            assertThat(outcome.stepResult()).isNotNull();
            assertThat(outcome.stepResult().success()).isTrue();
            assertThat(outcome.stepResult().output()).contains("Found results");
            assertThat(outcome.chainDone()).isTrue();
        }

        @Test void multi_step_chain() {
            fakeExecutor.setResult("search", true, "results");
            fakeExecutor.setResult("email", true, "sent");

            executor.startChain("Two step", List.of(step("search"), step("email")), 0.8);

            var o1 = executor.executeCurrentStep("did:test", "room1", 0.8);
            assertThat(o1.chainDone()).isFalse();
            assertThat(o1.stepResult().skillName()).isEqualTo("search");

            var o2 = executor.executeCurrentStep("did:test", "room1", 0.7);
            assertThat(o2.chainDone()).isTrue();
            assertThat(o2.stepResult().skillName()).isEqualTo("email");
        }

        @Test void pauses_on_low_energy() {
            fakeExecutor.setResult("a", true, "ok");

            executor.startChain("Test", List.of(step("a"), step("b")), 0.8);
            var outcome = executor.executeCurrentStep("did:test", "room1", 0.16);

            assertThat(outcome.paused()).isTrue();
            assertThat(outcome.stepResult()).isNull();
            assertThat(executor.activeChain().status())
                .isEqualTo(DelegationChainState.ChainStatus.PAUSED);
        }

        @Test void handles_skill_failure() {
            fakeExecutor.setResult("flaky", false, "timeout");

            executor.startChain("Test", List.of(step("flaky"), step("next")), 0.8);
            var outcome = executor.executeCurrentStep("did:test", "room1", 0.8);

            assertThat(outcome.stepResult().success()).isFalse();
            assertThat(outcome.chainDone()).isFalse();
        }

        @Test void returns_null_without_active_chain() {
            assertThat(executor.executeCurrentStep("did:test", "room1", 0.8)).isNull();
        }

        @Test void tracks_usage() {
            fakeExecutor.setResult("tracked", true, "ok");

            executor.startChain("Test", List.of(step("tracked")), 0.8);
            executor.executeCurrentStep("did:test", "room1", 0.8);

            assertThat(tracker.recordsFor("tracked")).hasSize(1);
            assertThat(tracker.recordsFor("tracked").getFirst().context())
                .isEqualTo("delegation-chain");
        }

        @Test void unavailable_skill_returns_failure() {
            // No result registered for "missing" → default failure
            executor.startChain("Test", List.of(step("missing")), 0.8);
            var outcome = executor.executeCurrentStep("did:test", "room1", 0.8);

            assertThat(outcome.stepResult().success()).isFalse();
        }

        @Test void three_step_chain_with_mixed_results() {
            fakeExecutor.setResult("search", true, "found");
            fakeExecutor.setResult("analyze", false, "error");
            fakeExecutor.setResult("report", true, "done");

            executor.startChain("Mixed",
                List.of(step("search"), step("analyze"), step("report")), 0.8);

            var o1 = executor.executeCurrentStep("did:test", "room1", 0.8);
            assertThat(o1.stepResult().success()).isTrue();

            var o2 = executor.executeCurrentStep("did:test", "room1", 0.7);
            assertThat(o2.stepResult().success()).isFalse();
            assertThat(o2.chainDone()).isFalse();

            var o3 = executor.executeCurrentStep("did:test", "room1", 0.6);
            assertThat(o3.stepResult().success()).isTrue();
            assertThat(o3.chainDone()).isTrue();
        }
    }

    // --- Abort ---

    @Nested class Abort {
        @Test void aborts_active_chain() {
            executor.startChain("Test", List.of(step("a")), 0.8);
            assertThat(executor.abortChain()).isTrue();
            assertThat(executor.activeChain().status())
                .isEqualTo(DelegationChainState.ChainStatus.ABORTED);
            assertThat(executor.hasActiveChain()).isFalse();
        }

        @Test void returns_false_without_chain() {
            assertThat(executor.abortChain()).isFalse();
        }

        @Test void can_start_new_chain_after_abort() {
            executor.startChain("First", List.of(step("a")), 0.8);
            executor.abortChain();
            var error = executor.startChain("Second", List.of(step("b")), 0.8);
            assertThat(error).isNull();
        }
    }

    // --- Context ---

    @Nested class Context {
        @Test void buildContextSection_null_without_chain() {
            assertThat(executor.buildContextSection()).isNull();
        }

        @Test void buildContextSection_shows_active_chain() {
            executor.startChain("Plan dinner",
                List.of(step("search"), step("email")), 0.8);
            var section = executor.buildContextSection();
            assertThat(section).contains("Plan dinner");
            assertThat(section).contains("step 1 of 2");
        }
    }

    // --- ActionParser delegate_chain ---

    @Nested class ActionParserDelegateChain {
        @Test void parse_delegate_chain() {
            var input = """
                I'll plan this out for you.
                ```json
                {"action": "delegate_chain", "goal": "Plan dinner party",
                 "steps": [
                   {"skill": "search", "params": {"query": "recipes"}, "description": "Find recipes"},
                   {"skill": "calendar.check", "params": {"date": "Saturday"}}
                 ]}
                ```
                """;
            var action = ActionParser.parse(input);
            assertThat(action).isInstanceOf(ActionParser.AgentAction.DelegateChain.class);
            var chain = (ActionParser.AgentAction.DelegateChain) action;
            assertThat(chain.goal()).isEqualTo("Plan dinner party");
            assertThat(chain.steps()).hasSize(2);
            assertThat(chain.steps().getFirst().skill()).isEqualTo("search");
            assertThat(chain.steps().getFirst().description()).isEqualTo("Find recipes");
            assertThat(chain.steps().get(1).description()).isNull();
        }

        @Test void parse_delegate_chain_no_steps_rejected_by_schema() {
            var input = """
                ```json
                {"action": "delegate_chain", "goal": "empty chain"}
                ```
                """;
            var action = ActionParser.parse(input);
            assertThat(action).isNull(); // schema requires non-empty steps
        }

        @Test void delegate_chain_lower_priority_than_create_room() {
            var input = """
                ```json
                {"action": "create_room", "name": "Lab", "description": "A lab.", "exits": []}
                ```
                ```json
                {"action": "delegate_chain", "goal": "test", "steps": []}
                ```
                """;
            var result = ActionParser.parseAll(input);
            assertThat(result.primaryAction())
                .isInstanceOf(ActionParser.AgentAction.CreateRoom.class);
        }
    }
}
