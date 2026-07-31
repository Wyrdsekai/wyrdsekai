package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.protocol.S2CMessage;
import org.wyrdsekai.core.agent.ActionParser.AgentAction;
import org.wyrdsekai.core.skill.ScheduledAction;
import org.wyrdsekai.core.skill.SchedulerService;
import org.wyrdsekai.core.skill.SkillContext;
import org.wyrdsekai.core.skill.SkillRegistry;
import org.wyrdsekai.core.skill.SkillResult;
import org.wyrdsekai.core.skill.SkillTier;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for Agent Services: verifies the full flow from parsed actions
 * through service execution to notification delivery.
 */
class AgentServicesIntegrationTest {

    private NotificationService notificationService;
    private WatcherService watcherService;
    private SchedulerService schedulerService;
    private List<String> deliveredMessages;
    private List<String> deliveredTargets;

    @BeforeEach void setUp() {
        notificationService = new NotificationService();
        deliveredMessages = new CopyOnWriteArrayList<>();
        deliveredTargets = new CopyOnWriteArrayList<>();
        notificationService.setDeliveryCallback((target, notif) -> {
            deliveredTargets.add(target);
            deliveredMessages.add(notif.message());
            return true;
        });

        watcherService = new WatcherService(notificationService, script -> {
            // Simple script evaluator: "true" returns true, "false" returns false
            return switch (script.strip()) {
                case "true" -> true;
                case "false" -> false;
                default -> script; // return as string
            };
        });

        // SchedulerService needs a SkillRegistry — use a minimal stub
        schedulerService = new SchedulerService(new StubSkillRegistry());
    }

    @Test void agent_outputs_schedule_action_creates_schedule() {
        // Simulate: agent LLM output contains a schedule action
        var llmOutput = """
            I'll monitor that API for you!
            ```json
            {"action": "schedule", "skill": "workbench.api-monitor", "interval": "1h",
             "params": {"url": "https://api.example.com/health"}}
            ```
            """;

        var action = ActionParser.parse(llmOutput);
        assertThat(action).isInstanceOf(AgentAction.ScheduleSkill.class);
        var schedule = (AgentAction.ScheduleSkill) action;

        // Simulate what CompanionActor.handleScheduleSkill does
        var duration = WatcherService.parseInterval(schedule.interval());
        assertThat(duration).isEqualTo(Duration.ofHours(1));

        var scheduledAction = new ScheduledAction(
            "test-id", "agent-did-1", schedule.skillId(),
            Map.copyOf(schedule.params()),
            new ScheduledAction.Schedule.Interval(duration, null),
            false, null, 0,
            ScheduledAction.ActionStatus.ACTIVE,
            Instant.now(), null, Instant.now().plus(duration));

        schedulerService.create(scheduledAction);

        // Verify the schedule exists
        var active = schedulerService.listForAgent("agent-did-1");
        assertThat(active).hasSize(1);
        assertThat(active.getFirst().skillId()).isEqualTo("workbench.api-monitor");
    }

    @Test void agent_outputs_notify_delivers_via_callback() {
        var llmOutput = """
            The build failed!
            ```json
            {"action": "notify", "message": "Build #42 failed: test errors", "priority": "critical",
             "target": "steward"}
            ```
            """;

        var action = ActionParser.parse(llmOutput);
        assertThat(action).isInstanceOf(AgentAction.NotifyHuman.class);
        var notify = (AgentAction.NotifyHuman) action;

        // Simulate handleNotifyHuman
        notificationService.notify(notify.target(), notify.message(),
            notify.priority(), "companion-1");

        assertThat(deliveredMessages).containsExactly("Build #42 failed: test errors");
        assertThat(deliveredTargets).containsExactly("steward");
    }

    @Test void agent_outputs_watch_creates_watcher_check_fires_notification() {
        var llmOutput = """
            I'll watch that for you.
            ```json
            {"action": "watch", "name": "api-health",
             "check": "false",
             "interval": "5m", "alert_on": "failure",
             "message": "API is down!", "priority": "critical"}
            ```
            """;

        var action = ActionParser.parse(llmOutput);
        assertThat(action).isInstanceOf(AgentAction.CreateWatcher.class);
        var watch = (AgentAction.CreateWatcher) action;

        // Simulate handleCreateWatcher
        var watcherId = watcherService.createWatcher(
            watch.name(), "agent-1", watch.checkScript(),
            watch.interval(), watch.alertOn(), watch.message(), watch.priority());

        assertThat(watcherId).isNotNull();
        assertThat(watcherService.listWatchers("agent-1")).hasSize(1);

        // Manually fire checks (in production, the scheduler does this)
        watcherService.executeCheck(watcherId);
        assertThat(deliveredMessages).isEmpty(); // debounce: need 2 consecutive failures

        watcherService.executeCheck(watcherId);
        assertThat(deliveredMessages).containsExactly("API is down!");
    }

    @Test void watcher_context_appears_in_prompt() {
        watcherService.createWatcher("gpu-temp", "agent-1",
            "true", "1m", "failure", "GPU hot!", "critical");

        var context = watcherService.buildContext("agent-1");

        assertThat(context).contains("## Active Watchers");
        assertThat(context).contains("gpu-temp");
        assertThat(context).contains("1m");
    }

    @Test void cancel_watcher_removes_from_context() {
        var id = watcherService.createWatcher("test-watch", "agent-1",
            "true", "5m", "failure", "Alert!", "normal");

        watcherService.cancelWatcher(id);

        // Cancelled watchers are excluded from listWatchers (and thus from context)
        var context = watcherService.buildContext("agent-1");
        assertThat(context).isNull();
    }

    @Test void watch_with_failing_check_triggers_after_debounce() {
        // Script returns false → failure condition
        var ws = new WatcherService(notificationService, script -> false);
        var id = ws.createWatcher("flaky-api", "agent-1",
            "false", "1m", "failure", "API down!", "critical");

        // Check 1 — consecutive alert = 1, below debounce (2)
        ws.executeCheck(id);
        assertThat(deliveredMessages).isEmpty();

        // Check 2 — consecutive alert = 2, debounce threshold met → fires
        ws.executeCheck(id);
        assertThat(deliveredMessages).containsExactly("API down!");
    }

    @Test void schedule_persists_as_commitment_like() {
        // The agent creates a schedule → it becomes a tracked commitment
        var tracker = new CommitmentTracker();
        var duration = WatcherService.parseInterval("1h");

        // Simulate what CompanionActor does
        var commitment = tracker.add("Scheduled 'api-monitor' every 1h", "agent_action", null);

        assertThat(commitment).isNotNull();
        assertThat(tracker.getPending()).hasSize(1);
        assertThat(tracker.getPending().getFirst().description()).contains("api-monitor");
    }

    @Test void multiple_watchers_for_same_agent_tracked_independently() {
        var results = new CopyOnWriteArrayList<String>();
        // Evaluator: "check-a" returns false, "check-b" returns true
        var ws = new WatcherService(notificationService, script -> {
            results.add(script);
            return script.contains("true");
        });

        var id1 = ws.createWatcher("watch-a", "agent-1",
            "check-a-false", "1m", "failure", "A failed!", "normal");
        var id2 = ws.createWatcher("watch-b", "agent-1",
            "check-b-true", "1m", "failure", "B failed!", "normal");

        // Check both
        ws.executeCheck(id1);
        ws.executeCheck(id2);

        // watch-a: check returned false → alert condition 1
        assertThat(ws.getWatcher(id1).consecutiveAlertConditions()).isEqualTo(1);
        // watch-b: check returned true → no alert condition
        assertThat(ws.getWatcher(id2).consecutiveAlertConditions()).isEqualTo(0);

        // Second check for watch-a triggers notification
        ws.executeCheck(id1);
        assertThat(deliveredMessages).containsExactly("A failed!");

        // watch-b still fine
        ws.executeCheck(id2);
        assertThat(ws.getWatcher(id2).consecutiveAlertConditions()).isEqualTo(0);
    }

    // --- Stub SkillRegistry for SchedulerService ---

    private static class StubSkillRegistry extends SkillRegistry {
        StubSkillRegistry() { super(null, null); }

        @Override
        public SkillResult execute(String skillId, Map<String, Object> params, SkillContext context) {
            return SkillResult.ok("stub result", Map.of(), 0, SkillTier.NATIVE, skillId);
        }
    }
}
