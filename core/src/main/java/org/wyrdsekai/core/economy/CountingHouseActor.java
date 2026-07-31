package org.wyrdsekai.core.economy;

import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.persistence.typed.ReplicaId;
import org.apache.pekko.persistence.typed.ReplicationId;
import org.apache.pekko.persistence.typed.javadsl.CommandHandler;
import org.apache.pekko.persistence.typed.javadsl.Effect;
import org.apache.pekko.persistence.typed.javadsl.EventHandler;
import org.apache.pekko.persistence.typed.javadsl.ReplicatedEventSourcedBehavior;
import org.apache.pekko.persistence.typed.javadsl.ReplicatedEventSourcing;
import org.apache.pekko.persistence.typed.javadsl.ReplicationContext;
import org.apache.pekko.persistence.typed.javadsl.RetentionCriteria;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.agent.AgentBudget;

import java.util.Set;

/**
 * ReplicatedEventSourcedBehavior tracking resource usage for the Counting House economy.
 * Supports offline merge via Pekko Replicated Event Sourcing (§82).
 * Persists inference token usage, survives restarts, and provides summaries
 * for the Counting House Foundation room.
 *
 * Entity type: "CountingHouse", entity ID: "singleton" (one per zone).
 */
public class CountingHouseActor extends ReplicatedEventSourcedBehavior<
        CountingHouseCommand, CountingHouseEvent, CountingHouseState> {

    private static final Logger log = LoggerFactory.getLogger(CountingHouseActor.class);

    /** Default replica ID for single-node deployment. */
    public static final ReplicaId DEFAULT_REPLICA = new ReplicaId("local");
    public static final Set<ReplicaId> DEFAULT_REPLICAS = Set.of(DEFAULT_REPLICA);
    public static final String DEFAULT_QUERY_PLUGIN = "jdbc-read-journal";

    private final MutualCreditLedger creditLedger;
    private final ReputationService reputationService;
    private final AgentBudget agentBudget = new AgentBudget();

    private CountingHouseActor(ReplicationContext replicationContext, LedgerPersistence persistence) {
        super(replicationContext);
        this.creditLedger = new MutualCreditLedger(persistence);
        this.reputationService = new ReputationService(creditLedger);
    }

    /** Create with no ledger persistence (in-memory only). */
    public static Behavior<CountingHouseCommand> create() {
        return create((LedgerPersistence) null);
    }

    /** Create with optional JDBC ledger persistence. */
    public static Behavior<CountingHouseCommand> create(LedgerPersistence persistence) {
        return create(DEFAULT_REPLICA, DEFAULT_REPLICAS, DEFAULT_QUERY_PLUGIN, persistence);
    }

    public static Behavior<CountingHouseCommand> create(
            ReplicaId selfReplica, Set<ReplicaId> allReplicas, String queryPluginId) {
        return create(selfReplica, allReplicas, queryPluginId, null);
    }

    public static Behavior<CountingHouseCommand> create(
            ReplicaId selfReplica, Set<ReplicaId> allReplicas, String queryPluginId,
            LedgerPersistence persistence) {
        return ReplicatedEventSourcing.commonJournalConfig(
            new ReplicationId("CountingHouse", "singleton", selfReplica),
            allReplicas,
            queryPluginId,
            repCtx -> {
                log.info("CountingHouseActor starting — replicated persistence, ledger={}",
                    persistence != null ? "JDBC" : "in-memory");
                return new CountingHouseActor(repCtx, persistence);
            }
        );
    }

    @Override
    public CountingHouseState emptyState() {
        return CountingHouseState.empty();
    }

    @Override
    public CommandHandler<CountingHouseCommand, CountingHouseEvent, CountingHouseState> commandHandler() {
        return newCommandHandlerBuilder()
            .forAnyState()
            .onCommand(CountingHouseCommand.RecordUsage.class, this::onRecordUsage)
            .onCommand(CountingHouseCommand.GetState.class, this::onGetState)
            .onCommand(CountingHouseCommand.Transfer.class, this::onTransfer)
            .onCommand(CountingHouseCommand.QueryBalance.class, this::onQueryBalance)
            .onCommand(CountingHouseCommand.SetCreditLimit.class, this::onSetCreditLimit)
            .onCommand(CountingHouseCommand.QueryReputation.class, this::onQueryReputation)
            .onCommand(CountingHouseCommand.QueryAllReputations.class, this::onQueryAllReputations)
            .onCommand(CountingHouseCommand.ConfigureAgentBudget.class, this::onConfigureAgentBudget)
            .build();
    }

    @Override
    public EventHandler<CountingHouseState, CountingHouseEvent> eventHandler() {
        return newEventHandlerBuilder()
            .forAnyState()
            .onEvent(CountingHouseEvent.UsageRecorded.class,
                (state, event) -> {
                    if (getReplicationContext().concurrent()) {
                        log.debug("Concurrent usage event from replica {}",
                            getReplicationContext().origin());
                    }
                    return state.apply(event);
                })
            .build();
    }

    @Override
    public RetentionCriteria retentionCriteria() {
        // Snapshot every 50 events, keep last 2
        return RetentionCriteria.snapshotEvery(50, 2);
    }

    // --- Command handlers ---

    private Effect<CountingHouseEvent, CountingHouseState> onRecordUsage(
            CountingHouseState state, CountingHouseCommand.RecordUsage cmd) {
        var event = new CountingHouseEvent.UsageRecorded(cmd.usage());
        return Effect().persist(event)
            .thenRun(newState -> {
                if (newState.totalRequests() % 100 == 0) {
                    log.info("CountingHouse: {} total requests, {} total tokens",
                        newState.totalRequests(), newState.totalTokens());
                }
            });
    }

    private Effect<CountingHouseEvent, CountingHouseState> onGetState(
            CountingHouseState state, CountingHouseCommand.GetState cmd) {
        cmd.replyTo().tell(state);
        return Effect().none();
    }

    private Effect<CountingHouseEvent, CountingHouseState> onTransfer(
            CountingHouseState state, CountingHouseCommand.Transfer cmd) {
        // Check agent budget cap before allowing transfer
        var budgetCheck = agentBudget.checkCredits(cmd.fromEntity(), cmd.amount());
        if (!budgetCheck.allowed()) {
            cmd.replyTo().tell("Transfer denied (budget): " + budgetCheck.reason());
            log.warn("Budget denied transfer: {} → {} ({} credits): {}",
                cmd.fromEntity(), cmd.toEntity(), cmd.amount(), budgetCheck.reason());
            return Effect().none();
        }

        var result = creditLedger.transfer(
            cmd.fromEntity(), cmd.toEntity(), cmd.amount(), cmd.description());
        if (result.isPresent()) {
            var tx = result.get();
            agentBudget.recordCreditSpend(cmd.fromEntity(), cmd.amount());
            cmd.replyTo().tell("Transfer complete: " + tx.amount() + " credits "
                + tx.fromEntity() + " → " + tx.toEntity() + " [" + tx.id() + "]");
            log.info("Credit transfer: {} → {} ({} credits)", cmd.fromEntity(), cmd.toEntity(), cmd.amount());
        } else {
            cmd.replyTo().tell("Transfer failed: insufficient credit or invalid parameters");
        }
        return Effect().none();
    }

    private Effect<CountingHouseEvent, CountingHouseState> onConfigureAgentBudget(
            CountingHouseState state, CountingHouseCommand.ConfigureAgentBudget cmd) {
        agentBudget.configure(cmd.config());
        log.info("Agent budget configured: {} (max {}/day, max {}/tx)",
            cmd.config().agentId(), cmd.config().maxCreditsPerDay(), cmd.config().maxCreditsPerTx());
        return Effect().none();
    }

    private Effect<CountingHouseEvent, CountingHouseState> onQueryBalance(
            CountingHouseState state, CountingHouseCommand.QueryBalance cmd) {
        cmd.replyTo().tell(creditLedger.getBalance(cmd.entityId()));
        return Effect().none();
    }

    private Effect<CountingHouseEvent, CountingHouseState> onSetCreditLimit(
            CountingHouseState state, CountingHouseCommand.SetCreditLimit cmd) {
        creditLedger.setCreditLimit(cmd.entityId(), cmd.newLimit());
        log.info("Credit limit set: {} → {}", cmd.entityId(), cmd.newLimit());
        return Effect().none();
    }

    private Effect<CountingHouseEvent, CountingHouseState> onQueryReputation(
            CountingHouseState state, CountingHouseCommand.QueryReputation cmd) {
        cmd.replyTo().tell(reputationService.computeReputation(cmd.entityId()));
        return Effect().none();
    }

    private Effect<CountingHouseEvent, CountingHouseState> onQueryAllReputations(
            CountingHouseState state, CountingHouseCommand.QueryAllReputations cmd) {
        cmd.replyTo().tell(reputationService.describe());
        return Effect().none();
    }
}
