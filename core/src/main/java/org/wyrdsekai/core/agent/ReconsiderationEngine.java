package org.wyrdsekai.core.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Checks whether active plans need adjustment when new information arrives.
 *
 * <p>The missing cognitive pattern from classical architectures: reconsideration.
 * When new info arrives (Oracle prediction, event, tell), re-evaluate whether
 * the current plan still makes sense.</p>
 */
public final class ReconsiderationEngine {

    private static final Logger log = LoggerFactory.getLogger(ReconsiderationEngine.class);

    private ReconsiderationEngine() {}

    public record ReconsiderationTrigger(
        String triggerType,      // "oracle_prediction", "new_tell", "event", "room_change"
        String condition,        // human-readable condition
        double urgency           // 0.0-1.0
    ) {}

    /**
     * Check if any triggers match the active plan.
     *
     * @param plan     the active task plan
     * @param triggers recent triggers to check
     * @return list of plan impacts (may be empty)
     */
    public static List<String> check(TaskPlan plan, List<ReconsiderationTrigger> triggers) {
        if (plan == null || !plan.isActive() || triggers == null) {
            return List.of();
        }

        var impacts = new ArrayList<String>();

        for (var trigger : triggers) {
            var decision = GoalExecutor.checkReconsideration(plan, trigger.condition());
            if (decision instanceof GoalExecutor.Decision.Replan replan) {
                impacts.add("[" + trigger.triggerType() + "] " + replan.reason());
                log.info("Reconsideration trigger matched: {} → plan '{}'",
                    trigger.condition(), plan.description());
            }
        }

        return impacts;
    }

    /**
     * Create a trigger from an Oracle prediction arrival.
     */
    public static ReconsiderationTrigger fromOraclePrediction(int count, double maxConfidence,
                                                                boolean hasActionable) {
        var condition = count + " predictions (confidence " + String.format("%.2f", maxConfidence) + ")";
        if (hasActionable) condition += " with actionable items";
        return new ReconsiderationTrigger("oracle_prediction", condition,
            hasActionable ? 0.8 : 0.4);
    }

    /**
     * Create a trigger from a direct tell message.
     */
    public static ReconsiderationTrigger fromTell(String fromName, String message) {
        return new ReconsiderationTrigger("new_tell",
            fromName + " says: " + message, 0.7);
    }

    /**
     * Create a trigger from a room change event.
     */
    public static ReconsiderationTrigger fromRoomChange(String roomName, String detail) {
        return new ReconsiderationTrigger("room_change",
            "Room '" + roomName + "': " + detail, 0.5);
    }
}
