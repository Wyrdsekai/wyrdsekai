package org.wyrdsekai.core.economy;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Scheduler;
import org.apache.pekko.actor.typed.javadsl.AskPattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Static gateway to the zone's CountingHouseActor for non-actor callers
 * (item-script providers, CLI surfaces). W5 (audit 2026-07-11): the actor's
 * write API — {@code Transfer} / {@code QueryBalance} — was reachable only
 * from tests; economy v1 shipped read-only. Main installs the spawned actor
 * ref here, and the household_treasury item's provider methods call through.
 *
 * <p>Blocking helpers are intended for item-script execution contexts
 * (virtual threads); timeouts are short and failures resolve to
 * {@code Optional.empty()} / an error string rather than throwing into
 * scripts.</p>
 */
public final class CountingHouseGateway {

    private static final Logger log = LoggerFactory.getLogger(CountingHouseGateway.class);
    private static final Duration ASK_TIMEOUT = Duration.ofSeconds(5);

    private static volatile ActorRef<CountingHouseCommand> countingHouse;
    private static volatile Scheduler scheduler;

    private CountingHouseGateway() {}

    /** Install the spawned CountingHouseActor (called from Main at boot). */
    public static void install(ActorRef<CountingHouseCommand> actor, Scheduler sched) {
        countingHouse = actor;
        scheduler = sched;
        log.info("CountingHouseGateway installed — treasury Transfer/QueryBalance live");
    }

    /** Whether the gateway has been wired (false on partial boots/tests). */
    public static boolean available() {
        return countingHouse != null && scheduler != null;
    }

    /**
     * Transfer credits between entities. Returns the Counting House's outcome
     * line ("Transfer complete: …" / "Transfer failed: …" / "Transfer denied
     * (budget): …"), or empty when the service isn't wired or timed out.
     */
    public static Optional<String> transfer(String fromEntity, String toEntity,
                                            long amount, String description) {
        var actor = countingHouse;
        var sched = scheduler;
        if (actor == null || sched == null) return Optional.empty();
        try {
            var result = AskPattern.<CountingHouseCommand, String>ask(
                    actor,
                    replyTo -> new CountingHouseCommand.Transfer(
                        fromEntity, toEntity, amount, description, replyTo),
                    ASK_TIMEOUT, sched)
                .toCompletableFuture()
                .get(ASK_TIMEOUT.toSeconds() + 1, TimeUnit.SECONDS);
            return Optional.ofNullable(result);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            log.warn("CountingHouse transfer {}→{} failed: {}", fromEntity, toEntity,
                e.getMessage());
            return Optional.empty();
        }
    }

    /** Query an entity's credit balance; empty when unwired or timed out. */
    public static Optional<CreditBalance> balance(String entityId) {
        var actor = countingHouse;
        var sched = scheduler;
        if (actor == null || sched == null) return Optional.empty();
        try {
            var result = AskPattern.<CountingHouseCommand, CreditBalance>ask(
                    actor,
                    replyTo -> new CountingHouseCommand.QueryBalance(entityId, replyTo),
                    ASK_TIMEOUT, sched)
                .toCompletableFuture()
                .get(ASK_TIMEOUT.toSeconds() + 1, TimeUnit.SECONDS);
            return Optional.ofNullable(result);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            log.warn("CountingHouse balance query for {} failed: {}", entityId, e.getMessage());
            return Optional.empty();
        }
    }
}
