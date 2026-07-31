package org.wyrdsekai.core.agent;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-agent learned action cost matrix.
 *
 * <p>Each action has an energy cost that decreases with practice and increases
 * with disuse (perishable skills). The CfC/DriveEngine determines WANT (motivation),
 * the vitality tanks determine CAN (capacity), and this matrix determines COST —
 * how much energy an action actually consumes for THIS agent.
 *
 * <p>A new companion finds everything expensive. Through practice (tracked by
 * {@link org.wyrdsekai.core.soul.HeuristicExtractor}), frequently-used actions
 * become cheaper. During Forge sleep, costs are consolidated: practiced actions
 * decrease, unused actions drift back toward base cost.
 *
 * <p>Each action type has a FLOOR — an irreducible minimum cost reflecting the
 * nature of the action. Creation is always substantial. Speech is nearly free.
 * Movement is cheap when practiced.
 *
 * @see DriveEngine for motivation (WANT)
 * @see VitalityState for capacity (CAN)
 */
public class SkillCostMatrix {

    // ── Cost floors by action nature ────────────────────────────────────

    private static final Map<String, Double> FLOORS;
    static {
        var m = new HashMap<String, Double>();

        // Movement — nearly free when practiced
        m.put("go_to_room", 0.02);
        m.put("travel_to", 0.04);          // multi-hop walk; per-hop weighting handled in handler
        m.put("teleport_to", 0.20);        // skipping the world has a price
        m.put("go_to_bondholder", 0.02);

        // Speech — almost effortless
        m.put("tell_agent", 0.01);
        m.put("respond_agent", 0.01);
        m.put("emote", 0.01);
        m.put("whisper", 0.01);
        m.put("broadcast", 0.02);

        // Memory — commitment always costs something
        m.put("remember", 0.05);
        m.put("note", 0.03);
        m.put("forget", 0.03);

        // Research — requires attention
        m.put("library_card", 0.05);
        m.put("library_search", 0.05);
        m.put("searching_glass", 0.05);
        m.put("web_search", 0.05);
        m.put("read_content", 0.03);
        m.put("examine", 0.03);
        m.put("query_oracle", 0.05);

        // Planning — moderate effort
        m.put("create_task_plan", 0.08);
        m.put("modify_plan", 0.05);
        m.put("goal_done", 0.02);
        m.put("set_goal", 0.05);
        m.put("abandon_plan", 0.02);
        m.put("pause_plan", 0.01);
        m.put("resume_plan", 0.01);

        // Creation — always substantial
        m.put("workbench_submit", 0.20);
        m.put("create_room", 0.20);
        m.put("craft_item", 0.15);
        m.put("add_script", 0.15);
        m.put("write_text", 0.10);

        // Deep analysis — always expensive
        m.put("think_deeply", 0.15);
        m.put("reflect", 0.10);
        m.put("introspect", 0.05);
        m.put("summarize", 0.08);

        // Inventory — cheap physical actions
        m.put("equip", 0.02);
        m.put("doff", 0.02);
        m.put("take_item", 0.02);
        m.put("place_item", 0.02);
        m.put("give_item", 0.02);
        m.put("consume", 0.02);

        // Social — moderate
        m.put("invite", 0.03);
        m.put("propose", 0.05);
        m.put("teach", 0.08);
        m.put("bond_ritual", 0.08);
        m.put("trade", 0.05);

        // Delegation — moderate
        m.put("delegate", 0.08);
        m.put("delegate_chain", 0.10);
        m.put("request_agent", 0.05);
        m.put("skill_execute", 0.08);

        // Scheduling/automation — low once set up
        m.put("schedule_skill", 0.05);
        m.put("cancel_schedule", 0.02);
        m.put("create_watcher", 0.05);
        m.put("cancel_watcher", 0.02);
        m.put("set_routine", 0.05);

        // Administrative
        m.put("calibration_feedback", 0.02);
        m.put("update_description", 0.03);
        m.put("cast_vote", 0.03);
        m.put("request_access", 0.03);
        m.put("zone_command", 0.10);
        m.put("codex_action", 0.08);

        // Rest — nearly free to choose
        m.put("voluntary_sleep", 0.01);
        m.put("listen", 0.01);

        // Notifications
        m.put("notify_human", 0.03);
        m.put("suggest_hints", 0.03);

        // Journal
        m.put("write_journal", 0.05);
        m.put("read_journal", 0.03);
        m.put("save_artifact", 0.05);
        m.put("request_review", 0.05);
        m.put("post_listing", 0.05);
        m.put("accept_listing", 0.03);

        FLOORS = Collections.unmodifiableMap(m);
    }

    // ── Constants ────────────────────────────────────────────────────────

    /** Default cost for actions not in FLOORS (novel/unknown tools). */
    public static final double DEFAULT_NEW_COST = 0.40;

    /** Starting cost multiplier above floor for known actions. */
    private static final double INITIAL_ABOVE_FLOOR = 0.25;

    /** Cost reduction per successful Forge consolidation day. */
    private static final double PRACTICE_RATE = 0.02;

    /** Cost increase per unused Forge consolidation day. */
    private static final double DECAY_RATE = 0.005;

    /** Maximum cost any action can reach. */
    private static final double COST_CEILING = 0.50;

    // ── Instance state ──────────────────────────────────────────────────

    private final ConcurrentHashMap<String, Double> costs;
    private final ConcurrentHashMap<String, Integer> sessionSuccesses = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> sessionFailures = new ConcurrentHashMap<>();

    private SkillCostMatrix(Map<String, Double> initial) {
        this.costs = new ConcurrentHashMap<>(initial);
    }

    /** New companion — all known actions at floor + INITIAL_ABOVE_FLOOR. */
    public static SkillCostMatrix newCompanion() {
        var initial = new HashMap<String, Double>();
        for (var entry : FLOORS.entrySet()) {
            initial.put(entry.getKey(), entry.getValue() + INITIAL_ABOVE_FLOOR);
        }
        return new SkillCostMatrix(initial);
    }

    /** Restore from genome (Forge persistence). */
    public static SkillCostMatrix fromGenome(Map<String, Double> genome) {
        return new SkillCostMatrix(genome != null ? genome : Map.of());
    }

    // ── Queries ─────────────────────────────────────────────────────────

    /** Current cost for an action. Unknown actions return DEFAULT_NEW_COST. */
    public double costFor(String action) {
        return costs.getOrDefault(action, DEFAULT_NEW_COST);
    }

    /** Can the agent afford this action at the given energy level? */
    public boolean canAfford(String action, double energy) {
        return energy >= costFor(action);
    }

    /** Get the floor for an action type. Unknown actions have no floor (returns 0). */
    public static double floorFor(String action) {
        return FLOORS.getOrDefault(action, 0.01);
    }

    /** All actions the agent can afford at the given energy. */
    public Set<String> affordableActions(Set<String> available, double energy) {
        var affordable = new HashSet<String>();
        for (var action : available) {
            if (canAfford(action, energy)) {
                affordable.add(action);
            }
        }
        return affordable;
    }

    /** All actions from the available set that are too expensive. */
    public Set<String> tooCostly(Set<String> available, double energy) {
        var costly = new HashSet<String>();
        for (var action : available) {
            if (!canAfford(action, energy)) {
                costly.add(action);
            }
        }
        return costly;
    }

    // ── Recording ───────────────────────────────────────────────────────

    /** Record a successful action execution (called after tool success). */
    public void recordSuccess(String action) {
        sessionSuccesses.merge(action, 1, Integer::sum);
        // Ensure action is in the cost map (first encounter of novel tool)
        costs.putIfAbsent(action, DEFAULT_NEW_COST);
    }

    /** Record a failed action execution. */
    public void recordFailure(String action) {
        sessionFailures.merge(action, 1, Integer::sum);
        costs.putIfAbsent(action, DEFAULT_NEW_COST);
    }

    // ── Forge consolidation ─────────────────────────────────────────────

    /**
     * Forge sleep consolidation. Called once per sleep cycle.
     *
     * <p>For each action:
     * <ul>
     *   <li>If practiced today: cost decreases by PRACTICE_RATE × success_ratio</li>
     *   <li>If not used today: cost drifts up by DECAY_RATE (perishable skill)</li>
     *   <li>Cost is clamped between floor and ceiling</li>
     * </ul>
     */
    public void forgeConsolidate() {
        var practiced = new HashSet<>(sessionSuccesses.keySet());
        practiced.addAll(sessionFailures.keySet());

        for (var entry : costs.entrySet()) {
            String action = entry.getKey();
            double current = entry.getValue();
            double floor = floorFor(action);

            if (practiced.contains(action)) {
                int successes = sessionSuccesses.getOrDefault(action, 0);
                int failures = sessionFailures.getOrDefault(action, 0);
                int total = successes + failures;
                double successRatio = total > 0 ? (double) successes / total : 0;

                // Reduce cost proportional to success ratio
                double reduction = PRACTICE_RATE * successRatio;
                current = Math.max(floor, current - reduction);
            } else {
                // Perishable: unused skills drift toward base cost
                double baseCost = floor + INITIAL_ABOVE_FLOOR;
                if (current < baseCost) {
                    current = Math.min(baseCost, current + DECAY_RATE);
                }
            }

            current = Math.min(COST_CEILING, current);
            entry.setValue(current);
        }

        // Clear session counters
        sessionSuccesses.clear();
        sessionFailures.clear();
    }

    // ── Persistence ─────────────────────────────────────────────────────

    /** Serialize to genome map for SoulManifest storage. */
    public Map<String, Double> toGenome() {
        return new HashMap<>(costs);
    }

    /** Number of tracked actions. */
    public int size() {
        return costs.size();
    }

    /** Current cost map (unmodifiable view). */
    public Map<String, Double> snapshot() {
        return Collections.unmodifiableMap(new HashMap<>(costs));
    }

    @Override
    public String toString() {
        return "SkillCostMatrix{actions=" + costs.size() + "}";
    }
}
