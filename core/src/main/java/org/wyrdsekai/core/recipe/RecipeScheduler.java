package org.wyrdsekai.core.recipe;

import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;
import org.apache.pekko.actor.typed.javadsl.TimerScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Track-C C2 — periodic-polling scheduler.
 *
 * <p>One actor per zone, rooted in {@code ZoneGuardian}. Every
 * {@link Config#pollInterval()} it ticks: peeks the oldest PENDING row in
 * {@link SqlRecipeQueue}, marks it IN_PROGRESS (CAS — a concurrent
 * scheduler can't double-dispatch), hands the recipe to a
 * {@link RecipeService} keyed on the row's {@code agentDid} (so Forge
 * attribution lands on the right companion), and on completion writes
 * the terminal outcome + the {@link CadenceLadder}-computed next cadence
 * state back through {@link SqlRecipeQueue#markCompleted}.</p>
 *
 * <p>Welfare gates (#990 / C3) and trigger sources (#991 / C4) are
 * intentionally not in this actor — this is the minimum chain
 * (poll → dispatch → outcome → cadence). C3 inserts a gate before
 * {@link #dispatchOne}; C4 grows new producers that {@link Enqueue}.</p>
 *
 * <p>Dispatch is fire-and-forget on a virtual thread: {@link RecipeService#run}
 * can take seconds (SHELL/GATE) to minutes (BACKEND + real training). The
 * scheduler doesn't block the actor mailbox; the worker thread posts a
 * {@link CompletionInternal} message back when done.</p>
 */
public final class RecipeScheduler extends AbstractBehavior<RecipeScheduler.Command> {

    private static final Logger log = LoggerFactory.getLogger(RecipeScheduler.class);

    private static final String POLL_TIMER = "recipe-scheduler-poll";

    // ── command protocol ───────────────────────────────────────────────

    public sealed interface Command {}

    /** Enqueue a new run (used by triggers in C4 + tests). */
    public record Enqueue(QueuedRecipe entry) implements Command {}

    /** External request to drain the next pending row immediately (CLI/test). */
    public record PollNow() implements Command {}

    /** Stop the scheduler (test cleanup). */
    public record Stop() implements Command {}

    /**
     * Steward override (#990/C3): bypass welfare gates for the
     * (recipeId, agentDid) pair on the next dispatch, AND clear any
     * deploy-ceiling pause for the recipe. Wired by the CLI in C6;
     * tests use it to verify the override path works.
     */
    public record ForceFire(String recipeId, String agentDid) implements Command {}

    // Internal — virtual-thread completion → actor mailbox.
    record CompletionInternal(
            String queueId, String recipeId, String agentDid,
            CadenceTier priorTier, int priorConsecutive,
            CadenceLadder.Outcome outcome, String runId, String message,
            boolean neverRan)
        implements Command {

        CompletionInternal(String queueId, String recipeId, String agentDid,
                CadenceTier priorTier, int priorConsecutive,
                CadenceLadder.Outcome outcome, String runId, String message) {
            this(queueId, recipeId, agentDid, priorTier, priorConsecutive,
                outcome, runId, message, false);
        }
    }

    // Internal — periodic timer fire.
    record TickInternal() implements Command {}

    // ── config ─────────────────────────────────────────────────────────

    /**
     * Scheduler tuning.
     *
     * @param pollInterval  how often to poll the queue (production: 60min).
     *                      Tests use {@link Duration#ofMillis(long)}-scale values.
     * @param maxPerTick    cap on dispatches per tick to keep the actor responsive
     *                      when the queue is deep. {@link #pollNow()} also respects this.
     */
    public record Config(Duration pollInterval, int maxPerTick) {
        public Config {
            if (pollInterval == null || pollInterval.isNegative()
                    || pollInterval.isZero()) {
                throw new IllegalArgumentException("pollInterval must be positive");
            }
            if (maxPerTick < 1) maxPerTick = 1;
        }
        public static Config defaults() {
            return new Config(Duration.ofMinutes(60), 1);
        }
    }

    // ── dispatcher seam ────────────────────────────────────────────────

    /**
     * Strategy interface for actually running a recipe. Production wiring
     * builds a fresh {@link RecipeService} bound to the row's agent DID so
     * Forge attribution (RecipeRunLog) lands on the right companion;
     * tests pass a stub that returns canned {@link RecipeRunner.RecipeRun}s.
     * Decoupled from {@link RecipeService} because that class is {@code final}
     * (no test subclass) and the scheduler shouldn't care about ctor shape.
     */
    @FunctionalInterface
    public interface Dispatcher {
        /**
         * Run {@code recipeName} for {@code agentDid} with {@code params}.
         * Returns {@code null} to signal no service is available for this
         * DID — scheduler maps that to {@link CadenceLadder.Outcome#ERROR}.
         */
        RecipeService.StartedRun dispatch(String agentDid, String recipeName,
                Map<String, Object> params);
    }

    /**
     * Track-C C3 — welfare-gate input supplier. Called by the
     * scheduler just before {@link #dispatchOne} commits the CAS, given
     * the candidate row. Returns the {@link WelfareGate.Inputs} snapshot
     * to evaluate; the scheduler then calls
     * {@link WelfareGate#evaluate(WelfareGate.Inputs)}. Default supplier
     * returns {@code null} which means "allow" (gate fails open).
     *
     * <p>Decoupled from concrete trackers so the scheduler doesn't grow
     * a dependency tree across {@link org.wyrdsekai.core.soul.RepairModeTracker},
     * {@link RecipeBudgetTracker}, etc. Production wiring builds a
     * supplier that fans out the actual lookups; tests pass a static
     * snapshot or {@code _ -> null} to bypass.</p>
     */
    @FunctionalInterface
    public interface WelfareSupplier {
        WelfareGate.Inputs inputsFor(QueuedRecipe peeked);
    }

    /** Sentinel supplier — every gate fails open. Used when no supplier wired. */
    private static final WelfareSupplier OPEN_GATE = peeked -> null;

    /**
     * Track-C C4 — periodic cron-source ticker (wired
     * 2026-05-25 closing the G4 C4 audit gap, task #1016). Called at
     * the start of every poll tick; returns the list of recipes whose
     * cadence interval has elapsed since their last terminal run. The
     * scheduler enqueues each — with one important idempotency rule —
     * before draining the queue:
     *
     * <p>{@link SqlRecipeQueue#hasOpenForRecipe} skip-check: if a row
     * for the same (recipeId, agentDid) is already PENDING or
     * IN_PROGRESS, don't enqueue a second — the welfare gate may have
     * been deferring it. Without this, a recipe stuck behind repair-mode
     * would compound new rows on every tick until the gate cleared.</p>
     *
     * <p>Production wiring builds the ticker from
     * {@link RecipeEnrollmentStore#listEnabled} + a "last terminal"
     * lookup (typically {@link RecipeBudgetTracker#lastTerminalAt}) +
     * {@link RecipeCronTrigger#plan}. Tests pass {@link #NO_CRON} or a
     * static list.</p>
     */
    @FunctionalInterface
    public interface CronTicker {
        List<QueuedRecipe> plan(Instant now);
    }

    /** Sentinel ticker — no-op. Used in tests and when scheduler runs with cron disabled. */
    private static final CronTicker NO_CRON = now -> List.of();

    /**
     * Reports which of a recipe's REQUIRED parameters have no value — no default in
     * the manifest, and no stored override for this agent.
     *
     * <p>Exists because the scheduler cannot see manifests by design (it holds only a
     * {@link Dispatcher}), so it had no way to tell "this recipe just failed" from
     * "this recipe can never run as configured". It fired
     * {@code retrain-classifier-head} — {@code head} required, no default, nothing on
     * the scheduled path supplying it — every cadence tick, and three ERROR runs tripped
     * the consecutive-deploy-failure ceiling. The welfare mechanism that exists to stop
     * a recipe grinding an agent down was spent on a missing string, and the recipe sat
     * paused needing a steward to clear it (found live 2026-08-18, 14 failed runs).
     *
     * <p>Implementations must FAIL OPEN: a manifest that cannot be loaded returns empty,
     * so an unreadable recipe still gets dispatched and reports a real outcome rather
     * than being silently skipped forever.
     */
    @FunctionalInterface
    public interface RequiredParamCheck {
        /** Required param names with no value available; empty when the recipe can run. */
        List<String> unsatisfied(String agentDid, String recipeName,
                Map<String, Object> params);
    }

    /** Default check — assumes everything is satisfiable (pre-existing behaviour). */
    private static final RequiredParamCheck ALL_SATISFIED = (did, name, params) -> List.of();

    // ── factory ────────────────────────────────────────────────────────

    /**
     * Construct the scheduler with default (open) welfare gate. Same as
     * {@link #create(SqlRecipeQueue, Dispatcher, Config, WelfareSupplier)}
     * with the supplier set to a no-op — every dispatch is allowed.
     * Use this in tests that don't care about gating; production wiring
     * passes a real supplier.
     */
    public static Behavior<Command> create(SqlRecipeQueue queue,
            Dispatcher dispatcher, Config config) {
        return create(queue, dispatcher, config, OPEN_GATE);
    }

    /**
     * Construct the scheduler with welfare gate but no cron ticker.
     * Mostly historical — production wiring goes through the 5-arg
     * overload to get cron-source enqueues. Tests that don't care
     * about cron use this.
     */
    public static Behavior<Command> create(SqlRecipeQueue queue,
            Dispatcher dispatcher, Config config, WelfareSupplier welfare) {
        return create(queue, dispatcher, config, welfare, NO_CRON);
    }

    /**
     * Construct the scheduler.
     *
     * @param queue       persistent queue (C1).
     * @param dispatcher  recipe-run dispatch seam (see {@link Dispatcher}).
     * @param config      polling cadence + per-tick cap.
     * @param welfare     pre-dispatch welfare-gate input supplier
     *                    (see {@link WelfareSupplier}). Null treated as
     *                    {@link #OPEN_GATE}.
     * @param cron        per-tick cron-source enqueue planner
     *                    (see {@link CronTicker}). Null treated as
     *                    {@link #NO_CRON} (cron disabled).
     */
    public static Behavior<Command> create(SqlRecipeQueue queue,
            Dispatcher dispatcher, Config config, WelfareSupplier welfare,
            CronTicker cron) {
        return create(queue, dispatcher, config, welfare, cron, ALL_SATISFIED);
    }

    /**
     * Construct the scheduler with a required-param precheck.
     *
     * @param paramCheck see {@link RequiredParamCheck}. Null treated as
     *                   {@link #ALL_SATISFIED} (no precheck).
     */
    public static Behavior<Command> create(SqlRecipeQueue queue,
            Dispatcher dispatcher, Config config, WelfareSupplier welfare,
            CronTicker cron, RequiredParamCheck paramCheck) {
        var sup = welfare == null ? OPEN_GATE : welfare;
        var tick = cron == null ? NO_CRON : cron;
        var check = paramCheck == null ? ALL_SATISFIED : paramCheck;
        return Behaviors.withTimers(timers -> Behaviors.setup(ctx ->
            new RecipeScheduler(ctx, timers, queue, dispatcher,
                config == null ? Config.defaults() : config, sup, tick, check)));
    }

    // ── state ──────────────────────────────────────────────────────────

    private final TimerScheduler<Command> timers;
    private final SqlRecipeQueue queue;
    private final Dispatcher dispatcher;
    private final Config config;
    private final WelfareSupplier welfare;
    private final CronTicker cron;
    private final RequiredParamCheck paramCheck;
    /** Recipe IDs paused by deploy-ceiling — skipped until cleared by steward. */
    private final Set<String> pausedRecipes =
        ConcurrentHashMap.newKeySet();
    /** (recipeId, agentDid) pairs the steward has flagged for force-fire on next tick. */
    private final Set<String> forceFire =
        ConcurrentHashMap.newKeySet();

    private RecipeScheduler(ActorContext<Command> context,
            TimerScheduler<Command> timers, SqlRecipeQueue queue,
            Dispatcher dispatcher, Config config, WelfareSupplier welfare,
            CronTicker cron, RequiredParamCheck paramCheck) {
        super(context);
        this.paramCheck = paramCheck;
        this.timers = timers;
        this.queue = queue;
        this.dispatcher = dispatcher;
        this.config = config;
        this.welfare = welfare;
        this.cron = cron;
        // Schedule periodic polls. First tick fires after pollInterval — call
        // PollNow from outside if you want immediate work (test seam).
        timers.startTimerWithFixedDelay(POLL_TIMER, new TickInternal(),
            config.pollInterval());
        log.info("RecipeScheduler started (poll={}, maxPerTick={})",
            config.pollInterval(), config.maxPerTick());
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
            .onMessage(Enqueue.class, this::onEnqueue)
            .onMessage(PollNow.class, c -> drainUpTo(config.maxPerTick()))
            .onMessage(TickInternal.class, c -> drainUpTo(config.maxPerTick()))
            .onMessage(CompletionInternal.class, this::onCompletion)
            .onMessage(ForceFire.class, this::onForceFire)
            .onMessage(Stop.class, c -> {
                timers.cancel(POLL_TIMER);
                return Behaviors.stopped();
            })
            .build();
    }

    private Behavior<Command> onForceFire(ForceFire cmd) {
        if (cmd.recipeId() == null) return this;
        pausedRecipes.remove(cmd.recipeId());
        forceFire.add(forceFireKey(cmd.recipeId(), cmd.agentDid()));
        log.info("RecipeScheduler steward force-fire armed: recipe={} agent={}",
            cmd.recipeId(), cmd.agentDid());
        return this;
    }

    private static String forceFireKey(String recipeId, String agentDid) {
        return recipeId + "::" + (agentDid == null ? "" : agentDid);
    }

    /**
     * Track-C C3 — steward notification on deploy-ceiling.
     * v1 just logs a structured WARN; the steward Study furnishing (C7)
     * scrapes the log + paused-recipes set. C6 CLI surfaces it on
     * {@code wyrd recipes status}.
     *
     * <p>Wired as protected (default access on the class) so a future
     * NotificationService consumer can subclass to push a real notification
     * without changing the gate seam.</p>
     */
    void notifyStewardDeployCeiling(QueuedRecipe peeked, String detail) {
        log.warn("RecipeScheduler STEWARD-NOTIFY deploy-ceiling: recipe={} "
            + "agent={} paused=true detail=\"{}\" — steward must investigate "
            + "and clear via ForceFire to resume",
            peeked.recipeId(), peeked.agentDid(), detail);
    }

    /** Test/diagnostic: snapshot of recipes currently paused by deploy-ceiling. */
    public Set<String> pausedRecipesSnapshot() {
        return Set.copyOf(pausedRecipes);
    }

    // ── handlers ───────────────────────────────────────────────────────

    private Behavior<Command> onEnqueue(Enqueue cmd) {
        if (cmd.entry() == null) return this;
        queue.enqueue(cmd.entry());
        log.info("RecipeScheduler enqueued: recipe={} id={} agent={} tier={}",
            cmd.entry().recipeId(), cmd.entry().id(),
            cmd.entry().agentDid(), cmd.entry().cadenceTier());
        return this;
    }

    private Behavior<Command> drainUpTo(int maxThisTick) {
        // Track-C C4 — cron tick. Run BEFORE draining so the
        // planned enqueues become eligible immediately. The ticker is a
        // pure function; idempotency lives at the queue level via
        // hasOpenForRecipe — a row sitting PENDING (e.g. welfare-gate
        // deferred) won't be duplicated.
        runCronTick();
        for (int i = 0; i < maxThisTick; i++) {
            var dispatched = dispatchOne();
            if (!dispatched) break;   // queue empty for now
        }
        return this;
    }

    /**
     * Execute the configured {@link CronTicker} and enqueue planned rows
     * that aren't already PENDING/IN_PROGRESS for the same pair. Wrapped
     * in try/catch — a misbehaving ticker must never crash the scheduler
     * thread, so the drain proceeds even if planning blows up.
     */
    private void runCronTick() {
        if (cron == NO_CRON) return;
        try {
            var planned = cron.plan(Instant.now());
            if (planned == null || planned.isEmpty()) return;
            int enqueued = 0;
            int skipped = 0;
            for (var entry : planned) {
                if (entry == null) continue;
                if (queue.hasOpenForRecipe(entry.recipeId(), entry.agentDid())) {
                    skipped++;
                    continue;
                }
                queue.enqueue(entry);
                enqueued++;
                log.info("RecipeScheduler cron-enqueued: recipe={} id={} "
                    + "agent={} tier={} reason=\"{}\"",
                    entry.recipeId(), entry.id(), entry.agentDid(),
                    entry.cadenceTier(), entry.triggerReason());
            }
            if (enqueued > 0 || skipped > 0) {
                log.debug("RecipeScheduler cron tick: planned={} enqueued={} "
                    + "skipped(already-open)={}",
                    planned.size(), enqueued, skipped);
            }
        } catch (Exception e) {
            log.warn("RecipeScheduler cron tick threw — drain proceeds: {}",
                e.toString());
        }
    }

    /**
     * Atomic peek → CAS-mark IN_PROGRESS → fire on virtual thread.
     * Returns true if work was dispatched, false if the queue was empty
     * or someone else grabbed the row first.
     */
    private boolean dispatchOne() {
        var peeked = queue.peekNextPending().orElse(null);
        if (peeked == null) return false;

        // Track-C C3 — welfare gate. Runs BEFORE the CAS so a
        // denial leaves the row PENDING for the next tick to re-evaluate
        // (gate may clear as the day rolls over or repair-mode ends).
        // Steward force-fire bypasses both the gate and the
        // pausedRecipes guard. Paused recipes (deploy-ceiling hit) are
        // skipped silently until force-fire or external clear.
        var ffKey = forceFireKey(peeked.recipeId(), peeked.agentDid());
        boolean forced = forceFire.remove(ffKey);
        if (!forced) {
            // NOTE: a paused recipe is NOT short-circuited here. It used to be, which
            // meant the deploy-ceiling pause could never re-evaluate itself — the gate
            // that decides whether to try again sat behind the flag that said don't.
            // `pausedRecipes` now records only that the steward has already been told,
            // so the notification fires once rather than every tick; the circuit breaker
            // below decides whether this tick actually dispatches.
            var inputs = welfare.inputsFor(peeked);
            var decision = WelfareGate.evaluate(inputs);
            if (!decision.allow()) {
                log.info("RecipeScheduler welfare deny: recipe={} agent={} "
                    + "reason={} detail={}",
                    peeked.recipeId(), peeked.agentDid(),
                    decision.reason(), decision.detail());
                // Deploy-ceiling: pause the recipe + notify steward; other
                // denials just defer to the next tick.
                if (decision.reason() == WelfareGate.DenyReason.DEPLOY_CEILING_HIT) {
                    // A breaker, not a latch. Paused-until-a-human-notices makes silence
                    // the resting state of a self-improvement loop on an unattended node;
                    // after a cooldown, exactly one attempt goes through. Success closes
                    // it (the SUCCEEDED row breaks the failure streak); failure re-opens
                    // it with the cooldown doubled. One run a day at worst cannot grind
                    // her, and a transient cause heals without anyone going looking.
                    var breaker = RecipeCircuitBreaker.stateFor(
                        inputs.consecutiveDeployFailures(), WelfareGate.DEPLOY_CEILING,
                        inputs.lastTerminalAt(), inputs.now());
                    if (breaker == RecipeCircuitBreaker.State.HALF_OPEN) {
                        log.info("RecipeScheduler half-open probe: recipe={} agent={} — "
                            + "{} consecutive failures, cooldown elapsed, allowing ONE "
                            + "attempt. Success closes the breaker; failure doubles the "
                            + "wait (next {}).",
                            peeked.recipeId(), peeked.agentDid(),
                            inputs.consecutiveDeployFailures(),
                            RecipeCircuitBreaker.cooldownFor(
                                inputs.consecutiveDeployFailures() + 1,
                                WelfareGate.DEPLOY_CEILING));
                        pausedRecipes.remove(peeked.recipeId());
                        // fall through to dispatch this one attempt
                    } else {
                        if (pausedRecipes.add(peeked.recipeId())) {
                            notifyStewardDeployCeiling(peeked, decision.detail());
                        } else {
                            log.debug("RecipeScheduler: recipe {} still cooling down "
                                + "(next attempt after {})", peeked.recipeId(),
                                RecipeCircuitBreaker.cooldownFor(
                                    inputs.consecutiveDeployFailures(),
                                    WelfareGate.DEPLOY_CEILING));
                        }
                        return false;
                    }
                } else {
                    return false;
                }
            } else if (pausedRecipes.contains(peeked.recipeId())) {
                // Gate allowed it — a success or a cleared streak closed the breaker.
                pausedRecipes.remove(peeked.recipeId());
                log.info("RecipeScheduler breaker closed for recipe={} — resuming",
                    peeked.recipeId());
            }
        } else {
            log.info("RecipeScheduler force-fire active: recipe={} agent={} "
                + "(welfare gate bypassed)", peeked.recipeId(), peeked.agentDid());
        }

        // A recipe whose REQUIRED params cannot be satisfied is misconfigured, not
        // failing. Firing it anyway spent a welfare mechanism on a config error:
        // `retrain-classifier-head` declares `head` required with no default, nothing on
        // the scheduled path supplies it, and three ERROR runs tripped the deploy
        // ceiling — so the gate that exists to stop a recipe grinding an agent down was
        // consumed by a missing string, and the recipe sat paused awaiting a steward
        // (found live 2026-08-18, 14 failed runs). Runs BEFORE the CAS and retires the
        // row as SKIPPED: leaving it PENDING would block the queue head forever, since a
        // missing parameter never resolves itself.
        var unsatisfied = paramCheck.unsatisfied(peeked.agentDid(), peeked.recipeId(),
            peeked.params() == null ? Map.of() : peeked.params());
        if (!unsatisfied.isEmpty()) {
            var detail = "missing required param(s) with no default and no stored "
                + "override: " + String.join(", ", unsatisfied);
            queue.markSkipped(peeked.id(), Instant.now(), detail);
            log.warn("RecipeScheduler skipping recipe={} agent={} — {}. Set them with "
                + "`wyrd recipes set-param` or give the recipe a default; this is a "
                + "configuration gap, not a failing run, so it does not count toward "
                + "the deploy ceiling.", peeked.recipeId(), peeked.agentDid(), detail);
            return false;
        }

        var attempted = queue.markAttempted(peeked.id(), Instant.now());
        if (!attempted) {
            // Concurrent scheduler beat us OR status drifted. Skip this tick.
            log.debug("RecipeScheduler: row {} no longer PENDING — skipping",
                peeked.id());
            return false;
        }

        log.info("RecipeScheduler dispatching: recipe={} id={} agent={} tier={}",
            peeked.recipeId(), peeked.id(), peeked.agentDid(),
            peeked.cadenceTier());

        // Capture cadence inputs for the completion handler — the row may
        // be mutated by other writers between now and completion.
        final var qid = peeked.id();
        final var recipeId = peeked.recipeId();
        final var did = peeked.agentDid();
        final var priorTier = peeked.cadenceTier();
        final var priorCount = peeked.consecutiveSuccesses();
        final var params = peeked.params();

        var self = getContext().getSelf();
        Thread.ofVirtual().name("recipe-sched-" + qid).start(() -> {
            try {
                var started = dispatcher.dispatch(did, recipeId,
                    params == null ? Map.of() : params);
                if (started == null) {
                    self.tell(new CompletionInternal(qid, recipeId, did,
                        priorTier, priorCount, CadenceLadder.Outcome.ERROR,
                        null, "no RecipeService available for did=" + did));
                    return;
                }
                var run = started.run();
                var outcome = mapOutcome(run);
                self.tell(new CompletionInternal(qid, recipeId, did,
                    priorTier, priorCount, outcome,
                    started.runId(), run.message(), neverRan(run)));
            } catch (Exception e) {
                log.warn("RecipeScheduler worker for {} threw: {}", qid, e.toString());
                self.tell(new CompletionInternal(qid, recipeId, did,
                    priorTier, priorCount, CadenceLadder.Outcome.ERROR,
                    null, "worker exception: " + e.getMessage()));
            }
        });
        return true;
    }

    private Behavior<Command> onCompletion(CompletionInternal done) {
        // A run that never started leaves the cadence ladder exactly where it was:
        // it is neither progress nor a setback, and demoting on it would punish a
        // companion for her node lacking a backend.
        var nextState = done.neverRan()
            ? new CadenceLadder.State(done.priorTier(), done.priorConsecutive())
            : CadenceLadder.advance(
                done.priorTier(), done.priorConsecutive(), done.outcome());
        var terminal = done.neverRan()
            ? QueuedRecipe.Status.SKIPPED
            : done.outcome() == CadenceLadder.Outcome.SUCCESS
                ? QueuedRecipe.Status.SUCCEEDED
                : QueuedRecipe.Status.FAILED;
        var written = queue.markCompleted(done.queueId(), terminal,
            Instant.now(), nextState.tier(), nextState.consecutiveSuccesses(),
            done.runId(), done.message());
        if (!written) {
            log.warn("RecipeScheduler: markCompleted({}) returned no-update — "
                + "row gone or status mismatched", done.queueId());
        } else {
            log.info("RecipeScheduler completed: id={} recipe={} agent={} "
                + "outcome={} → tier={} consec={} run={}",
                done.queueId(), done.recipeId(), done.agentDid(),
                done.outcome(), nextState.tier(),
                nextState.consecutiveSuccesses(), done.runId());
        }
        // Fire any one-shot completion callback registered for this run (:
        // a successful rebake-argot marks the new codebook version baked + shreds the key).
        RecipeCompletionCallbacks.fireAndRemove(done.queueId(),
            terminal == QueuedRecipe.Status.SUCCEEDED);
        return this;
    }

    // ── outcome mapping ────────────────────────────────────────────────

    /**
     * Map a {@link RecipeRunner.RecipeRun} into a {@link CadenceLadder.Outcome}.
     * Detects {@code ROLLBACK_FIRED} by scanning the outcomes list for the
     * rollback step name {@code "rollback"} (matches what
     * {@code RecipeForgeIngester.rolledBack} keys off).
     */
    /**
     * True when the recipe never actually executed, as opposed to executing and coming
     * out badly.
     *
     * <p>{@code NEEDS_BACKEND} means this node has no familiar to do the work, and
     * {@code RESOURCE_DENIED} means the box could not satisfy the declared hardware
     * requirement. Neither is an attempt at self-improvement that went wrong — nothing
     * ran. Recording them as FAILED fed the consecutive-deploy-failure ceiling, so a
     * node without a coding backend would pause the recipe after three ticks; the
     * ship-default provisioner even documents that such a node "will just NEEDS_BACKEND
     * every run". Same principle as a missing required param: a welfare ceiling must
     * only count work that actually ran.
     *
     * <p>{@code GATE_FAILED} is deliberately NOT here — the work ran and did not clear
     * the bar, which is exactly what the ceiling exists to notice.
     */
    static boolean neverRan(RecipeRunner.RecipeRun run) {
        if (run == null || run.status() == null) return false;
        return run.status() == RecipeRunner.Status.NEEDS_BACKEND
            || run.status() == RecipeRunner.Status.RESOURCE_DENIED;
    }

    static CadenceLadder.Outcome mapOutcome(RecipeRunner.RecipeRun run) {
        if (run == null) return CadenceLadder.Outcome.ERROR;
        var status = run.status();
        if (status == RecipeRunner.Status.SUCCESS) return CadenceLadder.Outcome.SUCCESS;
        if (status == RecipeRunner.Status.GATE_FAILED) return CadenceLadder.Outcome.GATE_FAILED;
        if (status == RecipeRunner.Status.STEP_FAILED) {
            // STEP_FAILED + a rollback in the outcomes list = the
            // reversibility seam fired. Cadence ladder cares about that
            // distinction (both demote to WARMUP today, but the audit
            // trail keeps the categories separate for future policy).
            var rolledBack = run.outcomes() != null
                && run.outcomes().stream().anyMatch(o -> "rollback".equals(o.id()));
            return rolledBack
                ? CadenceLadder.Outcome.ROLLBACK_FIRED
                : CadenceLadder.Outcome.STEP_FAILED;
        }
        return CadenceLadder.Outcome.ERROR;
    }

    // ── convenience for triggers (C4) + test helpers ───────────────────

    /** Build a freshly-stamped QueuedRecipe ready to {@link Enqueue}. */
    public static QueuedRecipe newEnqueue(String recipeId, String agentDid,
            CadenceTier tier, int consecutive, QueuedRecipe.TriggerSource source,
            String reason, Map<String, Object> params) {
        return QueuedRecipe.newEntry(UUID.randomUUID().toString(),
            recipeId, params, reason, source, agentDid, tier, consecutive);
    }
}
