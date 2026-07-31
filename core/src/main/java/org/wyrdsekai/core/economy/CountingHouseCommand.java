package org.wyrdsekai.core.economy;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.apache.pekko.actor.typed.ActorRef;
import org.wyrdsekai.core.agent.AgentBudget;

/**
 * Commands for the Counting House actor.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = CountingHouseCommand.RecordUsage.class, name = "RecordUsage"),
    @JsonSubTypes.Type(value = CountingHouseCommand.GetState.class, name = "GetState"),
    @JsonSubTypes.Type(value = CountingHouseCommand.Transfer.class, name = "Transfer"),
    @JsonSubTypes.Type(value = CountingHouseCommand.QueryBalance.class, name = "QueryBalance"),
    @JsonSubTypes.Type(value = CountingHouseCommand.SetCreditLimit.class, name = "SetCreditLimit"),
    @JsonSubTypes.Type(value = CountingHouseCommand.QueryReputation.class, name = "QueryReputation"),
    @JsonSubTypes.Type(value = CountingHouseCommand.QueryAllReputations.class, name = "QueryAllReputations"),
    @JsonSubTypes.Type(value = CountingHouseCommand.ConfigureAgentBudget.class, name = "ConfigureAgentBudget"),
})
public sealed interface CountingHouseCommand {

    /** Record a resource usage event. */
    record RecordUsage(ResourceUsage usage) implements CountingHouseCommand {}

    /** Query current state. */
    record GetState(ActorRef<CountingHouseState> replyTo) implements CountingHouseCommand {}

    /** Transfer credits between entities (§17, §68). */
    record Transfer(String fromEntity, String toEntity, long amount,
                    String description, ActorRef<String> replyTo) implements CountingHouseCommand {}

    /** Query an entity's credit balance (§17, §68). */
    record QueryBalance(String entityId, ActorRef<CreditBalance> replyTo) implements CountingHouseCommand {}

    /** Set credit limit for an entity (§17, §68). */
    record SetCreditLimit(String entityId, long newLimit) implements CountingHouseCommand {}

    /** Query reputation for a specific entity (§17). */
    record QueryReputation(String entityId, ActorRef<ReputationVector> replyTo) implements CountingHouseCommand {}

    /** Query reputation for all entities (§17). */
    record QueryAllReputations(ActorRef<String> replyTo) implements CountingHouseCommand {}

    /** Configure agent budget cap (§68). */
    record ConfigureAgentBudget(AgentBudget.BudgetConfig config) implements CountingHouseCommand {}
}
