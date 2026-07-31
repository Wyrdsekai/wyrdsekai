package org.wyrdsekai.core.economy;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.persistence.testkit.javadsl.EventSourcedBehaviorTestKit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.agent.AgentBudget;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that AgentBudget enforcement is wired into CountingHouseActor.
 */
@Tag("integration")
class CountingHouseAgentBudgetTest {

    private static final ActorTestKit testKit = ActorTestKit.create(
        ConfigFactory.parseString("""
            pekko.actor.serialization-bindings {
              "org.wyrdsekai.core.economy.CountingHouseCommand" = jackson-json
              "org.wyrdsekai.core.economy.CountingHouseEvent" = jackson-json
              "org.wyrdsekai.core.economy.CountingHouseState" = jackson-json
            }
            """).withFallback(EventSourcedBehaviorTestKit.config()));

    private ActorRef<CountingHouseCommand> actor;
    private TestProbe<String> replyProbe;

    @BeforeEach
    void setUp() {
        actor = testKit.spawn(CountingHouseActor.create());
        replyProbe = testKit.createTestProbe();
    }

    @AfterAll
    static void tearDown() {
        testKit.shutdownTestKit();
    }

    @Test
    void transfer_without_budget_follows_credit_rules() {
        // No budget configured — should follow normal credit limit rules
        actor.tell(new CountingHouseCommand.SetCreditLimit("player-1", 500));
        actor.tell(new CountingHouseCommand.Transfer("player-1", "player-2", 10, "test", replyProbe.ref()));
        var msg = replyProbe.receiveMessage();
        // May fail on insufficient credit, but NOT due to budget
        assertThat(msg).doesNotContain("budget");
    }

    @Test
    void transfer_over_per_tx_limit_denied_by_budget() {
        // Configure budget with low per-tx limit
        var config = new AgentBudget.BudgetConfig("agent-tx", "human-1",
            1000, 5, 100.0, Instant.now());
        actor.tell(new CountingHouseCommand.ConfigureAgentBudget(config));

        // Transfer exceeding per-tx limit should be denied by budget
        actor.tell(new CountingHouseCommand.Transfer("agent-tx", "agent-2", 10, "big spend", replyProbe.ref()));
        var msg = replyProbe.receiveMessage();
        assertThat(msg).containsIgnoringCase("budget").containsIgnoringCase("denied");
    }

    @Test
    void configure_agent_budget_accepted() {
        var config = new AgentBudget.BudgetConfig("agent-safe", "human-2",
            500, 100, 50.0, Instant.now());
        actor.tell(new CountingHouseCommand.ConfigureAgentBudget(config));
        // Should not crash — verify by querying state
        var stateProbe = testKit.createTestProbe(CountingHouseState.class);
        actor.tell(new CountingHouseCommand.GetState(stateProbe.ref()));
        var state = stateProbe.receiveMessage();
        assertThat(state).isNotNull();
    }

    @Test
    void small_transfer_within_budget_allowed() {
        // Configure generous budget
        var config = new AgentBudget.BudgetConfig("agent-gen", "human-3",
            10000, 500, 100.0, Instant.now());
        actor.tell(new CountingHouseCommand.ConfigureAgentBudget(config));

        // Small transfer within budget — should NOT mention budget
        actor.tell(new CountingHouseCommand.Transfer("agent-gen", "agent-2", 5, "small", replyProbe.ref()));
        var msg = replyProbe.receiveMessage();
        assertThat(msg).doesNotContain("budget");
    }
}
