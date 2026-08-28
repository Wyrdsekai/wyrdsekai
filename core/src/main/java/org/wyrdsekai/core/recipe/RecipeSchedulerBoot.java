package org.wyrdsekai.core.recipe;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.Props;
import org.apache.pekko.actor.typed.javadsl.AskPattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.coding.CodingBackendPreference;
import org.wyrdsekai.core.config.WyrdConfig;
import org.wyrdsekai.core.soul.RepairModeTracker;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Track-C C9 — one-call wire-up of the recipe scheduler
 * for production boot.
 *
 * <p>{@link Main} (or any equivalent boot scope) calls {@link
 * #bootIfEnabled(BootArgs)}; this class assembles the queue, budget
 * tracker, enrollment store, dispatcher, welfare supplier, spawns the
 * actor, registers it with {@link RecipeSchedulerRegistry}, and runs
 * the {@link ShipDefaultEnrollmentProvisioner}. Returns the ActorRef
 * or {@code null} when the steward has disabled the scheduler.</p>
 *
 * <p>Same posture as {@code CoreServices.init}: best-effort, exception-
 * safe, idempotent. Production boots happily on a fresh install
 * (provisioner runs once); subsequent restarts upsert the same rows.</p>
 */
public final class RecipeSchedulerBoot {

    private static final Logger log = LoggerFactory.getLogger(RecipeSchedulerBoot.class);

    private RecipeSchedulerBoot() {}

    /** Inputs to {@link #bootIfEnabled} — keyed-arg pattern so call sites stay legible. */
    public record BootArgs(
            ActorSystem<?> system,
            String jdbcUrl,
            Path dataDir,
            Path scriptsRoot,
            Path classifiersDir,
            Collection<String> companionDids,
            WyrdConfig config,
            UnaryOperator<RecipeScheduler.Dispatcher> dispatcherDecorator) {
        /** Back-compat ctor — no dispatcher decorator (local-only dispatch). */
        public BootArgs(ActorSystem<?> system, String jdbcUrl, Path dataDir, Path scriptsRoot,
                        Path classifiersDir, Collection<String> companionDids, WyrdConfig config) {
            this(system, jdbcUrl, dataDir, scriptsRoot, classifiersDir, companionDids, config, null);
        }
    }

    /**
     * Boots the scheduler if {@link WyrdConfig#schedulerEnabled()} is true.
     * On disabled, ensures any prior registry singleton is cleared and
     * returns {@code null} — fresh installs whose steward turned it off
     * should observe no actor.
     */
    @SuppressWarnings("unchecked")
    public static ActorRef<RecipeScheduler.Command> bootIfEnabled(BootArgs args) {
        if (args == null || args.system() == null || args.jdbcUrl() == null) {
            log.warn("RecipeSchedulerBoot: missing required args, scheduler not started");
            return null;
        }
        var cfg = args.config() != null ? args.config() : WyrdConfig.get();
        if (!cfg.schedulerEnabled()) {
            log.info("RecipeScheduler disabled by config — skipping boot");
            RecipeSchedulerRegistry.resetForTests();
            return null;
        }

        try {
            var queue = new SqlRecipeQueue(args.jdbcUrl());
            var budget = new RecipeBudgetTracker(args.jdbcUrl());
            var enrollments = new RecipeEnrollmentStore(args.jdbcUrl());

            // Welfare supplier wires the live trackers. The resilience
            // lookup is null at this layer because ResilienceSession is
            // per-companion-actor state, not zone-shared — the scheduler
            // gets substrate-pressure signal via repair-mode + chronicle
            // findings instead. This is the same trade-off the §23 floor
            // already accepted.
            var welfare = new SchedulerWelfareSupplier(
                budget,
                Duration.ofHours(cfg.schedulerGpuDailyHours()),
                cfg.schedulerMonthlyRunCap(),
                RepairModeTracker.get(),
                /* resilienceLookup */ null,
                ZoneId.systemDefault());

            // Dispatcher: goose first, fall back to local "pi" mode.
            // Each dispatched recipe runs in its own RecipeService instance
            // because RecipeService binds an agentDid for Forge attribution.
            var schedCfg = new RecipeScheduler.Config(
                Duration.ofMinutes(cfg.schedulerPollMinutes()),
                /* maxPerTick */ 1);

            RecipeScheduler.Dispatcher dispatcher = (did, recipeName, params) -> {
                try {
                    Path scriptsRoot = args.scriptsRoot() != null
                        && Files.isDirectory(args.scriptsRoot())
                        ? args.scriptsRoot() : null;
                    var procRunner = new ProcessCommandRunner(
                        new File(System.getProperty("user.dir")),
                        Duration.ofMinutes(5));
                    var backend = CodingBackendDispatcher
                        .usingPreferred(CodingBackendPreference.chain(), did,
                            Duration.ofMinutes(10))
                        .orElse(null);
                    var runner = backend == null
                        ? new RecipeRunner(procRunner)
                        : new RecipeRunner(procRunner, backend);
                    Path recipesDir = args.dataDir() != null
                        ? args.dataDir().resolve("recipes")
                        : Path.of(System.getProperty("user.dir"), "recipes");
                    // #1142 — apply stored param-override tunes UNDER the
                    // caller params, so a scheduled run picks up the tuned
                    // default while an explicit per-run param still wins.
                    var service = new RecipeService(recipesDir, runner, did, scriptsRoot)
                        .withParamOverrides(new SqlRecipeParamOverrides(args.jdbcUrl()));
                    var started = service.run(recipeName, params);
                    return started;
                } catch (Exception e) {
                    log.warn("Scheduler dispatcher failed for {}/{}: {}",
                        recipeName, did, e.toString());
                    return null;
                }
            };

            // Track-C C4 — cron ticker. Per-poll-interval scan
            // of enabled enrollments via RecipeCronTrigger.plan, using the
            // budget tracker's lastTerminalAt lookup. Closes the G4 C4
            // wire-up gap audited 2026-05-25 (task #1016): without this,
            // the WARMUP→SETTLING→MATURE cadence ladder couldn't advance
            // in production because nothing enqueued cron-source rows.
            // Scheduler.runCronTick already guards against ticker
            // exceptions, so a transient store failure here is just a
            // skipped tick — next poll retries.
            final RecipeEnrollmentStore enrollmentsRef = enrollments;
            final RecipeBudgetTracker budgetRef = budget;
            // #1023 — quiet-hours preference lookup. Loads the recipe
            // manifest (file-tree or classpath) and reads `prefers_hours`.
            // Best-effort: any load failure returns ANYTIME so a malformed
            // YAML can't paralyze the scheduler. The cron tick fires
            // hourly so even one failed lookup is just one skipped tick.
            Path recipesDirForLookup = args.dataDir() != null
                ? args.dataDir().resolve("recipes")
                : Path.of(System.getProperty("user.dir"), "recipes");
            var manifestLookup = new RecipeService(recipesDirForLookup, null);
            RecipeCronTrigger.PrefersHoursLookup prefersHoursLookup = recipeId -> {
                try {
                    return manifestLookup.inspect(recipeId).prefersHours();
                } catch (Exception ex) {
                    return List.of();
                }
            };
            // A recipe whose required params can never be satisfied on the scheduled
            // path is misconfigured, not failing — see RecipeScheduler.RequiredParamCheck.
            // Manifest defaults and stored per-agent overrides both count as satisfying;
            // anything unreadable fails OPEN so a bad lookup can't silently mute a recipe.
            var overridesForCheck = new SqlRecipeParamOverrides(args.jdbcUrl());
            RecipeScheduler.RequiredParamCheck paramCheck = (did, recipeName, params) -> {
                try {
                    var declared = manifestLookup.inspect(recipeName).params();
                    if (declared == null || declared.isEmpty()) return List.of();
                    var stored = overridesForCheck.effectiveFor(recipeName, did);
                    var missing = new ArrayList<String>();
                    for (var e : declared.entrySet()) {
                        if (!e.getValue().required()) continue;
                        if (e.getValue().defaultValue() != null) continue;
                        if (params != null && params.containsKey(e.getKey())) continue;
                        if (stored != null && stored.containsKey(e.getKey())) continue;
                        missing.add(e.getKey());
                    }
                    return missing;
                } catch (Exception ex) {
                    return List.of();
                }
            };

            // What a SCHEDULED run should work on. The gap path reads its head off the
            // gap key; cron has no equivalent signal, so a recipe declaring `cron_heads`
            // gets the stalest candidate — the one longest without a successful run.
            // Staleness, not "worst score": the recorded per-head accuracies are not
            // comparable (two heads report train-set accuracy with zero validation
            // examples), and the set is DECLARED because some heads must never be picked
            // automatically — retraining substrate_present regresses it, and request_type
            // sits below its own gate, so choosing either would produce genuine failures
            // that legitimately burn the deploy ceiling.
            RecipeCronTrigger.CronParamsLookup cronParamsLookup = (recipeId, agentDid) -> {
                try {
                    var declared = manifestLookup.inspect(recipeId).params();
                    if (declared == null || !declared.containsKey("cron_heads")) {
                        return Map.of();
                    }
                    var stored = overridesForCheck.effectiveFor(recipeId, agentDid);
                    Object raw = stored != null && stored.get("cron_heads") != null
                        ? stored.get("cron_heads")
                        : declared.get("cron_heads").defaultValue();
                    var candidates = CronHeadSelection.parseCandidates(raw);
                    if (candidates.isEmpty()) return Map.of();   // explicitly disabled
                    var lastByHead =
                        budgetRef.lastSuccessByParam(recipeId, agentDid, "head");
                    return CronHeadSelection.stalest(candidates, lastByHead)
                        .<Map<String, Object>>map(h -> Map.of("head", h))
                        .orElseGet(Map::of);
                } catch (Exception ex) {
                    log.warn("cron head selection for {} failed: {} — scheduled run will "
                        + "be skipped rather than guessed", recipeId, ex.toString());
                    return Map.of();
                }
            };

            // Use the household's local timezone (system default). Override
            // via -Duser.timezone= at zone server JVM start if needed.
            final Clock localClock = Clock.systemDefaultZone();
            RecipeScheduler.CronTicker cronTicker = now -> RecipeCronTrigger.plan(
                enrollmentsRef.listEnabled(),
                budgetRef::lastTerminalAt,
                prefersHoursLookup,
                localClock,
                now,
                cronParamsLookup);

            // resource-requisites (option b) — wrap the local
            // dispatcher with the cross-zone peer-borrow decorator when the
            // server layer supplied one. Local-first / peer-fallback: it only
            // borrows when a heavy recipe is RESOURCE_DENIED here. Null/default
            // = local-only (the OSS single-node case, and all tests).
            RecipeScheduler.Dispatcher effectiveDispatcher = dispatcher;
            if (args.dispatcherDecorator() != null) {
                try {
                    var wrapped = args.dispatcherDecorator().apply(dispatcher);
                    if (wrapped != null) {
                        effectiveDispatcher = wrapped;
                        log.info("RecipeScheduler dispatcher wrapped with cross-zone peer-borrow decorator");
                    }
                } catch (Exception e) {
                    log.warn("dispatcherDecorator failed, using local dispatcher: {}", e.toString());
                }
            }

            var behavior = RecipeScheduler.create(
                queue, effectiveDispatcher, schedCfg, welfare, cronTicker, paramCheck);
            @SuppressWarnings("rawtypes")
            var rawSystem = (ActorSystem) args.system();
            var ref = (ActorRef<RecipeScheduler.Command>)
                rawSystem.systemActorOf(behavior, "recipe-scheduler", Props.empty());
            RecipeSchedulerRegistry.setInstance(ref);
            log.info("RecipeScheduler spawned (poll={}min, gpu/day={}h, "
                + "runs/month={}, deploy-ceiling={})",
                cfg.schedulerPollMinutes(), cfg.schedulerGpuDailyHours(),
                cfg.schedulerMonthlyRunCap(), cfg.schedulerDeployCeiling());

            // Ship-default enrollment. Fires every boot; idempotent.
            // Also publishes the registry so companions spawned post-boot
            // (#1008) get enrolled at first soul-birth.
            try {
                Path pretrained = args.classifiersDir() != null
                    ? args.classifiersDir().resolve("pretrained") : null;
                // Publish the registry FIRST so anything that calls
                // ShipDefaultEnrollmentProvisioner.provisionForCompanion
                // racing with the boot-time bulk-provision is also covered.
                RecipeEnrollmentRegistry.setInstance(
                    new RecipeEnrollmentRegistry.Context(
                        enrollments, cfg.schedulerEnrolledHeads(), pretrained));
                var dids = args.companionDids() != null
                    ? args.companionDids() : List.<String>of();
                if (!dids.isEmpty()) {
                    var rows = ShipDefaultEnrollmentProvisioner.provision(
                        enrollments, cfg.schedulerEnrolledHeads(),
                        pretrained, dids, Instant.now());
                    log.info("Ship-default enrollment provisioned {} row(s) "
                        + "across {} companion(s)", rows.size(), dids.size());
                }
            } catch (Exception e) {
                log.warn("Ship-default enrollment failed: {}", e.toString());
            }

            return ref;
        } catch (Exception e) {
            log.error("RecipeScheduler boot failed: {}", e.toString(), e);
            return null;
        }
    }

    /** Test helper — boot a scheduler with explicit dispatcher (no goose). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static ActorRef<RecipeScheduler.Command> bootForTest(
            ActorSystem<?> system, String jdbcUrl,
            RecipeScheduler.Dispatcher dispatcher,
            RecipeScheduler.WelfareSupplier welfareSupplier,
            Duration pollInterval) {
        var queue = new SqlRecipeQueue(jdbcUrl);
        var cfg = new RecipeScheduler.Config(
            pollInterval != null ? pollInterval : Duration.ofSeconds(1), 1);
        var behavior = welfareSupplier != null
            ? RecipeScheduler.create(queue, dispatcher, cfg, welfareSupplier)
            : RecipeScheduler.create(queue, dispatcher, cfg);
        var ref = (ActorRef<RecipeScheduler.Command>)
            ((ActorSystem) system).systemActorOf(behavior,
                "recipe-scheduler-test-" + System.nanoTime(), Props.empty());
        RecipeSchedulerRegistry.setInstance(ref);
        return ref;
    }

    // Compile-time witness — kept here so unused-import linters don't strip the
    // AskPattern import when this class is extended with sync helpers later.
    @SuppressWarnings("unused")
    private static final Class<?> ASK_PATTERN = AskPattern.class;
}
