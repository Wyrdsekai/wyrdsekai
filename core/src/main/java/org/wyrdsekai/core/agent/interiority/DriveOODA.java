package org.wyrdsekai.core.agent.interiority;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.agent.ActionPolicy;
import org.wyrdsekai.core.agent.ActivityLogger;
import org.wyrdsekai.core.agent.Want;
import org.wyrdsekai.core.agent.WantStore;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * the drive-OODA orchestrator.
 *
 * <p>Runs once per awake-consolidation tick (no new scheduler). Walks the
 * Observe → Orient → Decide → Act loop, persists chosen wants, and writes a
 * tick line to the activity log. Cadence for the *next* tick is derived from
 * this tick's state via {@link CadenceModulator}.
 *
 * <p>This class is the *pure orchestrator*. It holds no actor references and
 * does no inference itself — it expects an {@link OrientStep} callback to
 * generate candidate wants and a {@link DecideStep} callback to pick one, both
 * of which CompanionActor wires up to its inference path. That keeps the
 * tick-shape testable without booting an actor system.
 */
public final class DriveOODA {

    private static final Logger log = LoggerFactory.getLogger(DriveOODA.class);

    /** Callback into the agent's inference path: produce 0..N candidate wants. */
    @FunctionalInterface
    public interface OrientStep {
        List<CandidateWant> orient(AmbientObservation ambient,
                                   Map<String, List<String>> introspection,
                                   List<String> randomPulls);
    }

    /** Callback: pick one of the candidates (or null for "nothing chosen"). */
    @FunctionalInterface
    public interface DecideStep {
        Optional<CandidateWant> decide(List<CandidateWant> candidates,
                                       AmbientObservation ambient,
                                       List<Want> liveWants);
    }

    /** Callback: act on the chosen want. Returns {@code "ok"} / error string. */
    @FunctionalInterface
    public interface ActStep {
        String act(Want chosen, AmbientObservation ambient);
    }

    /** Per-agent tick state — visited timestamps + last action verb. */
    private final Map<String, AgentTickState> tickState = new ConcurrentHashMap<>();
    private final WantStore wantStore;

    public DriveOODA(WantStore wantStore) {
        this.wantStore = wantStore;
    }

    /**
     * Run one full tick. Caller is responsible for the next-tick scheduling —
     * this method returns the {@link TickOutcome} carrying the suggested delay.
     *
     * @param agentDid          companion DID
     * @param agentName         display name for logging
     * @param baseInterval      configured base tick interval
     * @param ambient           the freshly-built ambient observation
     * @param introspection     pre-pulled introspection (may be empty)
     * @param randomPulls       results of random memory pulls (may be empty)
     * @param orient            inference callback for wants
     * @param decide            inference callback for picking one
     * @param act               action callback (executes via existing action surface)
     * @param driveThreshold    over-threshold cutoff (typically 0.7)
     */
    public TickOutcome run(String agentDid,
                           String agentName,
                           Duration baseInterval,
                           AmbientObservation ambient,
                           Map<String, List<String>> introspection,
                           List<String> randomPulls,
                           OrientStep orient,
                           DecideStep decide,
                           ActStep act,
                           double driveThreshold) {
        var started = Instant.now();
        var state = tickState.computeIfAbsent(agentDid, k -> new AgentTickState());

        var liveWants = wantStore != null ? wantStore.loadLive(agentDid) : List.<Want>of();
        var rec = new ActivityLogger.TickRecord();
        rec.agentName = agentName;
        rec.agentId = agentDid;
        rec.driveSnapshot = ambient.driveLevels();
        rec.energy = ambient.energy();
        rec.capacity = ambient.capacity();
        rec.ambientObserve = ambient.recentEvents();
        rec.memoryPulls = randomPulls;
        rec.gateOutcome = "acted";

        // ── ORIENT ────────────────────────────────────────────────────────
        List<CandidateWant> candidates;
        try {
            candidates = orient.orient(ambient, introspection, randomPulls);
            if (candidates == null) candidates = List.of();
        } catch (Exception e) {
            log.warn("DriveOODA.orient({}) threw: {}", agentDid, e.getMessage());
            candidates = List.of();
        }

        if (candidates.isEmpty()) {
            rec.gateOutcome = "no_wants";
            return finalize(rec, started, baseInterval, ambient, liveWants.size(), driveThreshold);
        }
        rec.candidateWants = new ArrayList<>();
        for (var c : candidates) rec.candidateWants.add(c.text());

        // ── DECIDE ────────────────────────────────────────────────────────
        Optional<CandidateWant> chosen;
        try {
            chosen = decide.decide(candidates, ambient, liveWants);
        } catch (Exception e) {
            log.warn("DriveOODA.decide({}) threw: {}", agentDid, e.getMessage());
            chosen = Optional.empty();
        }

        if (chosen.isEmpty() || chosen.get().isRest()) {
            rec.gateOutcome = "chose_rest";
            return finalize(rec, started, baseInterval, ambient, liveWants.size(), driveThreshold);
        }

        // ── Persist as Want ───────────────────────────────────────────────
        var pick = chosen.get();
        // Reconcile: if any existing live want matches by text, revisit it
        // rather than create a duplicate. Visit count drives the DEEPENED
        // transition (lifecycle in Want.visited()).
        Want want = matchExistingByText(liveWants, pick.text())
            .map(Want::visited)
            .orElseGet(() -> Want.active(agentDid, pick.text(),
                pick.driveResonance(), pick.feltWeight(), null));
        if (wantStore != null) wantStore.upsert(want);
        rec.chosenWantId = want.wantId();
        rec.chosenWantText = want.text();

        // ── ACT ──────────────────────────────────────────────────────────
        String result;
        try {
            result = act.act(want, ambient);
        } catch (Exception e) {
            log.warn("DriveOODA.act({}, {}) threw: {}", agentDid, want.text(), e.getMessage());
            result = "error:" + e.getClass().getSimpleName();
        }
        rec.actionResult = result;
        rec.actionVerb = state.lastActionVerb;
        rec.actionDetail = state.lastActionDetail;
        rec.gateOutcome = "acted";

        return finalize(rec, started, baseInterval, ambient, liveWants.size(), driveThreshold);
    }

    /**
     * Convenience: cheap pre-gate that the caller invokes before assembling the
     * full Ambient observation. Returns true if the tick is worth running.
     */
    public boolean shouldRunFullPass(String agentDid,
                                     Map<String, Double> driveLevels,
                                     double driveThreshold,
                                     boolean bondholderStateChanged) {
        var state = tickState.get(agentDid);
        long minutesSince = state == null ? Long.MAX_VALUE
            : Duration.between(state.lastTickAt, Instant.now()).toMinutes();
        int liveWants = wantStore == null ? 0 : wantStore.countLive(agentDid);
        return CadenceModulator.shouldRunFullPass(
            driveLevels, driveThreshold, liveWants, bondholderStateChanged, minutesSince);
    }

    /**
     * Test/instrumentation seam: record what action verb the agent just
     * performed so the *next* tick can read it as the "prior action" modulator
     * for {@link MemoryPullPolicy}.
     */
    public void recordPriorAction(String agentDid, String verb, String detail) {
        var state = tickState.computeIfAbsent(agentDid, k -> new AgentTickState());
        state.lastActionVerb = verb;
        state.lastActionDetail = detail;
        state.lastActionLabel = labelForVerb(verb);
    }

    /**
     * Caller invokes this when starting a tick to know whether it's been a
     * while or the agent just woke up. Mostly informational; the cadence
     * modulator handles the math.
     */
    public Optional<Duration> sinceLastTick(String agentDid) {
        var state = tickState.get(agentDid);
        if (state == null) return Optional.empty();
        return Optional.of(Duration.between(state.lastTickAt, Instant.now()));
    }

    /**
     * Pure-functional label for the action verb — feeds {@link MemoryPullPolicy}.
     * "rest"|"reflect"|"intense"|"none".
     */
    public static String labelForVerb(String verb) {
        if (verb == null) return "none";
        return switch (verb) {
            case "voluntary_sleep", "set_contemplative" -> "rest";
            case "reflect", "introspect", "read_journal", "write_journal",
                 "summarize", "save_artifact", "examine", "listen" -> "reflect";
            case "tell_agent", "broadcast", "trade", "give_item", "craft_item",
                 "delegate", "delegate_chain", "skill_execute", "run_script",
                 "workbench_submit", "create_room", "add_script" -> "intense";
            default -> "none";
        };
    }

    private TickOutcome finalize(ActivityLogger.TickRecord rec,
                                 Instant started,
                                 Duration baseInterval,
                                 AmbientObservation ambient,
                                 int liveWantCount,
                                 double driveThreshold) {
        var nextDelay = CadenceModulator.nextDelay(
            baseInterval, ambient.driveLevels(), driveThreshold,
            ambient.energy(), liveWantCount, 0.10);
        rec.nextTickDelaySeconds = nextDelay.toSeconds();
        rec.tickDurationMs = Duration.between(started, Instant.now()).toMillis();
        // Update internal state.
        var state = tickState.computeIfAbsent(rec.agentId, k -> new AgentTickState());
        state.lastTickAt = Instant.now();
        // Write the log line.
        var alogger = ActivityLogger.get();
        if (alogger != null) alogger.tick(rec);
        return new TickOutcome(rec.gateOutcome, rec.chosenWantId, rec.actionResult, nextDelay);
    }

    private static Optional<Want> matchExistingByText(List<Want> wants, String text) {
        if (wants == null || text == null) return Optional.empty();
        for (var w : wants) if (text.equalsIgnoreCase(w.text())) return Optional.of(w);
        return Optional.empty();
    }

    /** Plain DTO returned to the caller — no actor surface in here. */
    public record TickOutcome(
        String gateOutcome,
        String chosenWantId,
        String actionResult,
        Duration nextTickDelay
    ) {}

    /** Mutable per-agent state held by the orchestrator across ticks. */
    private static final class AgentTickState {
        Instant lastTickAt = Instant.EPOCH;
        String lastActionVerb;
        String lastActionDetail;
        String lastActionLabel = "none";
    }

    /** Test seam: read the labelled prior action for an agent. */
    public String priorActionLabel(String agentDid) {
        var state = tickState.get(agentDid);
        return state == null ? "none" : state.lastActionLabel;
    }

    /** Test seam: clear state for an agent — used in tests. */
    public void resetForAgent(String agentDid) {
        tickState.remove(agentDid);
    }

    /**
     * Bridge for callers that want the recommended N memory pulls for *this*
     * agent on *this* tick. Reads prior action label, current drive levels,
     * and energy to make the call.
     */
    public int recommendedMemoryPullN(String agentDid,
                                       double energy,
                                       double capacity,
                                       Map<String, Double> drives,
                                       boolean preSleep) {
        var label = priorActionLabel(agentDid);
        return MemoryPullPolicy.decideN(energy, capacity, label, drives, preSleep);
    }

    /** Trivial helper for tests: how many agents have we seen at least one tick from? */
    public int knownAgentCount() {
        return tickState.size();
    }

    /** Convenience for tests: empty introspection map. */
    public static Map<String, List<String>> noIntrospection() {
        return new HashMap<>();
    }

    /**
     * Static helper: pick the FORBIDDEN/CONSENT actions out of a candidate set
     * so the Decide step never asks the agent to choose one autonomously.
     */
    public static boolean isAutonomouslyChoosable(String actionVerb) {
        var tier = ActionPolicy.autonomyTierFor(actionVerb);
        return tier == ActionPolicy.AutonomyTier.AMBIENT
            || tier == ActionPolicy.AutonomyTier.VISIBLE;
    }
}
