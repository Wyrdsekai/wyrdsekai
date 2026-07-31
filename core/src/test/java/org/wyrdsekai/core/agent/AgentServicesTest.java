package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.protocol.S2CMessage;
import org.wyrdsekai.core.agent.ActionParser.AgentAction;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the three Agent Services: Scheduler actions, NotificationService, WatcherService.
 */
class AgentServicesTest {

    // --- Part 1: ActionParser tests ---

    @Nested
    class ActionParserTests {

        @Test void parse_schedule_action() {
            var input = """
                I'll set that up for you!
                ```json
                {"action": "schedule", "skill": "workbench.api-monitor", "interval": "1h",
                 "params": {"url": "https://api.example.com/health"}}
                ```
                """;
            var action = ActionParser.parse(input);
            assertThat(action).isInstanceOf(AgentAction.ScheduleSkill.class);
            var schedule = (AgentAction.ScheduleSkill) action;
            assertThat(schedule.skillId()).isEqualTo("workbench.api-monitor");
            assertThat(schedule.interval()).isEqualTo("1h");
            assertThat(schedule.params()).containsEntry("url", "https://api.example.com/health");
        }

        @Test void parse_cancel_schedule_action() {
            var input = """
                I'll cancel that schedule.
                ```json
                {"action": "cancel_schedule", "schedule_id": "sched-abc-123"}
                ```
                """;
            var action = ActionParser.parse(input);
            assertThat(action).isInstanceOf(AgentAction.CancelSchedule.class);
            var cancel = (AgentAction.CancelSchedule) action;
            assertThat(cancel.scheduleId()).isEqualTo("sched-abc-123");
        }

        @Test void parse_notify_action_with_priority() {
            var input = """
                The API is down!
                ```json
                {"action": "notify", "message": "API health check failed!", "priority": "critical",
                 "target": "steward"}
                ```
                """;
            var action = ActionParser.parse(input);
            assertThat(action).isInstanceOf(AgentAction.NotifyHuman.class);
            var notify = (AgentAction.NotifyHuman) action;
            assertThat(notify.message()).isEqualTo("API health check failed!");
            assertThat(notify.priority()).isEqualTo("critical");
            assertThat(notify.target()).isEqualTo("steward");
        }

        @Test void parse_watch_action() {
            var input = """
                I'll watch that for you.
                ```json
                {"action": "watch", "name": "api-health",
                 "check": "http.get('https://api.example.com/health').includes('ok')",
                 "interval": "5m", "alert_on": "failure",
                 "message": "API health check failed!", "priority": "critical"}
                ```
                """;
            var action = ActionParser.parse(input);
            assertThat(action).isInstanceOf(AgentAction.CreateWatcher.class);
            var watch = (AgentAction.CreateWatcher) action;
            assertThat(watch.name()).isEqualTo("api-health");
            assertThat(watch.checkScript()).contains("http.get");
            assertThat(watch.interval()).isEqualTo("5m");
            assertThat(watch.alertOn()).isEqualTo("failure");
            assertThat(watch.message()).isEqualTo("API health check failed!");
            assertThat(watch.priority()).isEqualTo("critical");
        }

        @Test void parse_cancel_watch_action() {
            var input = """
                I'll stop watching.
                ```json
                {"action": "cancel_watch", "watcher_id": "watch-xyz-789"}
                ```
                """;
            var action = ActionParser.parse(input);
            assertThat(action).isInstanceOf(AgentAction.CancelWatcher.class);
            var cancel = (AgentAction.CancelWatcher) action;
            assertThat(cancel.watcherId()).isEqualTo("watch-xyz-789");
        }

        @Test void parse_schedule_with_defaults() {
            var input = """
                ```json
                {"action": "schedule", "skill": "ping"}
                ```
                """;
            var action = ActionParser.parse(input);
            assertThat(action).isInstanceOf(AgentAction.ScheduleSkill.class);
            var schedule = (AgentAction.ScheduleSkill) action;
            assertThat(schedule.skillId()).isEqualTo("ping");
            assertThat(schedule.interval()).isEqualTo("1h");
            assertThat(schedule.params()).isEmpty();
        }

        @Test void parse_notify_with_defaults() {
            var input = """
                ```json
                {"action": "notify", "message": "Something happened"}
                ```
                """;
            var action = ActionParser.parse(input);
            assertThat(action).isInstanceOf(AgentAction.NotifyHuman.class);
            var notify = (AgentAction.NotifyHuman) action;
            assertThat(notify.priority()).isEqualTo("normal");
            assertThat(notify.target()).isEqualTo("steward");
        }

        @Test void parse_schedule_blank_skill_returns_null() {
            var input = """
                ```json
                {"action": "schedule", "skill": "", "interval": "1h"}
                ```
                """;
            assertThat(ActionParser.parse(input)).isNull();
        }

        @Test void parse_watch_blank_name_returns_null() {
            var input = """
                ```json
                {"action": "watch", "name": "", "check": "true", "interval": "5m"}
                ```
                """;
            assertThat(ActionParser.parse(input)).isNull();
        }
    }

    // --- Part 2: NotificationService tests ---

    @Nested
    class NotificationServiceTests {

        private NotificationService service;

        @BeforeEach void setUp() {
            service = new NotificationService();
        }

        @Test void notify_delivers_via_callback() {
            var delivered = new AtomicReference<S2CMessage.Notification>();
            var deliveredTarget = new AtomicReference<String>();
            service.setDeliveryCallback((target, notif) -> {
                deliveredTarget.set(target);
                delivered.set(notif);
                return true;
            });

            service.notify("player-1", "Hello!", "normal", "agent-1");

            assertThat(delivered.get()).isNotNull();
            assertThat(delivered.get().message()).isEqualTo("Hello!");
            assertThat(delivered.get().level()).isEqualTo("normal");
            assertThat(delivered.get().title()).isEqualTo("agent-1");
            assertThat(deliveredTarget.get()).isEqualTo("player-1");
        }

        @Test void notify_with_null_callback_does_not_crash() {
            // No callback set — should not throw
            service.notify("player-1", "Hello!", "normal", "agent-1");
        }

        @Test void notifyAll_delivers_to_all() {
            var targets = new CopyOnWriteArrayList<String>();
            service.setDeliveryCallback((target, notif) -> targets.add(target));

            service.notifyAll("Broadcast!", "ambient", "agent-1");

            assertThat(targets).containsExactly("all");
        }

        @Test void notify_normalizes_priority() {
            var delivered = new AtomicReference<S2CMessage.Notification>();
            service.setDeliveryCallback((target, notif) -> { delivered.set(notif); return true; });

            service.notify("player-1", "Test", "CRITICAL", "agent-1");
            assertThat(delivered.get().level()).isEqualTo("critical");
        }

        @Test void notify_unknown_priority_defaults_to_normal() {
            var delivered = new AtomicReference<S2CMessage.Notification>();
            service.setDeliveryCallback((target, notif) -> { delivered.set(notif); return true; });

            service.notify("player-1", "Test", "high", "agent-1");
            assertThat(delivered.get().level()).isEqualTo("normal");
        }

        @Test void notify_blank_message_ignored() {
            var called = new AtomicBoolean(false);
            service.setDeliveryCallback((target, notif) -> { called.set(true); return true; });

            service.notify("player-1", "", "normal", "agent-1");
            assertThat(called.get()).isFalse();

            service.notify("player-1", "   ", "normal", "agent-1");
            assertThat(called.get()).isFalse();
        }

        @Test void recent_for_agent_tracks_deliveries() {
            service.setDeliveryCallback((target, notif) -> true);

            service.notify("p1", "msg1", "normal", "agent-1");
            service.notify("p1", "msg2", "critical", "agent-1");
            service.notify("p2", "msg3", "normal", "agent-2");

            var records = service.recentForAgent("agent-1", 10);
            assertThat(records).hasSize(2);
            // Most recent first
            assertThat(records.get(0).message()).isEqualTo("msg2");
            assertThat(records.get(1).message()).isEqualTo("msg1");
        }
    }

    // --- Part 3: WatcherService tests ---

    @Nested
    class WatcherServiceTests {

        private WatcherService watcherService;
        private NotificationService notificationService;
        private List<String> deliveredMessages;

        @BeforeEach void setUp() {
            notificationService = new NotificationService();
            deliveredMessages = new CopyOnWriteArrayList<>();
            notificationService.setDeliveryCallback((target, notif) ->
                deliveredMessages.add(notif.message()));

            // Default script evaluator: returns true
            watcherService = new WatcherService(notificationService, script -> true);
        }

        @Test void create_watcher_returns_id() {
            var id = watcherService.createWatcher("test-watch", "agent-1",
                "true", "5m", "failure", "Alert!", "normal");

            assertThat(id).isNotNull();
            assertThat(id).isNotBlank();
            assertThat(watcherService.size()).isEqualTo(1);
            assertThat(watcherService.activeCount()).isEqualTo(1);
        }

        @Test void cancel_watcher_removes_it() {
            var id = watcherService.createWatcher("test-watch", "agent-1",
                "true", "5m", "failure", "Alert!", "normal");

            var cancelled = watcherService.cancelWatcher(id);

            assertThat(cancelled).isTrue();
            assertThat(watcherService.activeCount()).isZero();
            // Still tracked but cancelled
            assertThat(watcherService.getWatcher(id).status())
                .isEqualTo(WatcherService.WatcherStatus.CANCELLED);
        }

        @Test void cancel_nonexistent_returns_false() {
            assertThat(watcherService.cancelWatcher("nope")).isFalse();
        }

        @Test void list_watchers_by_agent() {
            watcherService.createWatcher("w1", "agent-1", "true", "1m", "failure", "A1", "normal");
            watcherService.createWatcher("w2", "agent-1", "true", "5m", "failure", "A2", "normal");
            watcherService.createWatcher("w3", "agent-2", "true", "1h", "failure", "B1", "normal");

            assertThat(watcherService.listWatchers("agent-1")).hasSize(2);
            assertThat(watcherService.listWatchers("agent-2")).hasSize(1);
            assertThat(watcherService.listWatchers("agent-3")).isEmpty();
        }

        @Test void check_execution_true_result_does_not_alert_for_failure_condition() {
            // Script returns true → FAILURE condition not met → no alert
            var ws = new WatcherService(notificationService, script -> true);
            var id = ws.createWatcher("health", "agent-1",
                "checkHealth()", "5m", "failure", "Down!", "critical");

            ws.executeCheck(id);

            assertThat(deliveredMessages).isEmpty();
            var watcher = ws.getWatcher(id);
            assertThat(watcher.lastResult()).isEqualTo("true");
            assertThat(watcher.lastChecked()).isNotNull();
        }

        @Test void check_execution_false_triggers_notification_after_debounce() {
            // Script returns false → FAILURE condition met
            var ws = new WatcherService(notificationService, script -> false);
            var id = ws.createWatcher("health", "agent-1",
                "checkHealth()", "5m", "failure", "Down!", "critical");

            // First check — alert condition count = 1, below debounce threshold
            ws.executeCheck(id);
            assertThat(deliveredMessages).isEmpty();

            // Second check — alert condition count = 2 (DEFAULT_DEBOUNCE), now triggers
            ws.executeCheck(id);
            assertThat(deliveredMessages).containsExactly("Down!");
        }

        @Test void consecutive_failures_tracked() {
            // Script throws on every call
            var ws = new WatcherService(notificationService,
                script -> { throw new RuntimeException("connection refused"); });
            var id = ws.createWatcher("flaky", "agent-1",
                "checkSomething()", "5m", "failure", "Error!", "normal");

            // First two failures — still active
            ws.executeCheck(id);
            assertThat(ws.getWatcher(id).consecutiveFailures()).isEqualTo(1);
            assertThat(ws.getWatcher(id).status()).isEqualTo(WatcherService.WatcherStatus.ACTIVE);

            ws.executeCheck(id);
            assertThat(ws.getWatcher(id).consecutiveFailures()).isEqualTo(2);

            // Third failure — enters ERROR
            ws.executeCheck(id);
            assertThat(ws.getWatcher(id).status()).isEqualTo(WatcherService.WatcherStatus.ERROR);
            assertThat(ws.getWatcher(id).consecutiveFailures()).isEqualTo(3);
        }

        @Test void build_context_includes_active_watchers() {
            watcherService.createWatcher("api-check", "agent-1",
                "true", "5m", "failure", "Down!", "normal");

            var context = watcherService.buildContext("agent-1");

            assertThat(context).isNotNull();
            assertThat(context).contains("## Active Watchers");
            assertThat(context).contains("api-check");
            assertThat(context).contains("5m");
        }

        @Test void build_context_null_when_no_watchers() {
            assertThat(watcherService.buildContext("agent-1")).isNull();
        }

        @Test void interval_validation() {
            // Too short (below 30s minimum)
            assertThat(watcherService.createWatcher("short", "agent-1",
                "true", "5s", "failure", "X", "normal")).isNull();

            // Too long (above 7d max)
            assertThat(watcherService.createWatcher("long", "agent-1",
                "true", "10d", "failure", "X", "normal")).isNull();

            // Invalid format
            assertThat(watcherService.createWatcher("bad", "agent-1",
                "true", "abc", "failure", "X", "normal")).isNull();

            // Valid
            assertThat(watcherService.createWatcher("ok", "agent-1",
                "true", "30s", "failure", "X", "normal")).isNotNull();
        }

        @Test void change_condition_alerts_when_result_changes() {
            var results = new ArrayList<>(List.of("value-a", "value-a", "value-b", "value-b"));
            var index = new int[]{0};
            var ws = new WatcherService(notificationService, script -> results.get(index[0]++));
            var id = ws.createWatcher("monitor", "agent-1",
                "getStatus()", "5m", "change", "Status changed!", "normal");

            // Check 1: first result, no previous → no change → no alert
            ws.executeCheck(id);
            assertThat(deliveredMessages).isEmpty();

            // Check 2: same result → no change → no alert
            ws.executeCheck(id);
            assertThat(deliveredMessages).isEmpty();

            // Check 3: result changed! alert condition = 1
            ws.executeCheck(id);
            assertThat(deliveredMessages).isEmpty(); // debounce

            // Check 4: still different from previous lastResult (which is now "value-b")
            // but same as current → no alert condition
            ws.executeCheck(id);
            // After check 3 triggered alert condition, check 4 result == lastResult → no alert
            // Debounce means only fires after 2 consecutive change conditions
        }

        @Test void always_condition_alerts_after_debounce() {
            var ws = new WatcherService(notificationService, script -> "ok");
            var id = ws.createWatcher("logger", "agent-1",
                "getLog()", "5m", "always", "Check completed", "ambient");

            // First check — condition count = 1
            ws.executeCheck(id);
            assertThat(deliveredMessages).isEmpty();

            // Second check — condition count = 2 (DEFAULT_DEBOUNCE)
            ws.executeCheck(id);
            assertThat(deliveredMessages).containsExactly("Check completed");
        }

        @Test void pause_and_resume_watcher() {
            var id = watcherService.createWatcher("test", "agent-1",
                "true", "5m", "failure", "X", "normal");

            assertThat(watcherService.pauseWatcher(id)).isTrue();
            assertThat(watcherService.getWatcher(id).status())
                .isEqualTo(WatcherService.WatcherStatus.PAUSED);

            assertThat(watcherService.resumeWatcher(id)).isTrue();
            assertThat(watcherService.getWatcher(id).status())
                .isEqualTo(WatcherService.WatcherStatus.ACTIVE);
        }

        @Test void max_watchers_per_agent_enforced() {
            for (int i = 0; i < WatcherService.MAX_WATCHERS_PER_AGENT; i++) {
                assertThat(watcherService.createWatcher("w" + i, "agent-1",
                    "true", "1m", "failure", "X", "normal")).isNotNull();
            }
            // Next one should be rejected
            assertThat(watcherService.createWatcher("overflow", "agent-1",
                "true", "1m", "failure", "X", "normal")).isNull();
            // But another agent can still create
            assertThat(watcherService.createWatcher("other", "agent-2",
                "true", "1m", "failure", "X", "normal")).isNotNull();
        }
    }

    // --- WatcherService.parseInterval tests ---

    @Nested
    class IntervalParsingTests {

        @Test void parse_seconds() {
            assertThat(WatcherService.parseInterval("30s")).isEqualTo(Duration.ofSeconds(30));
        }

        @Test void parse_minutes() {
            assertThat(WatcherService.parseInterval("5m")).isEqualTo(Duration.ofMinutes(5));
        }

        @Test void parse_hours() {
            assertThat(WatcherService.parseInterval("1h")).isEqualTo(Duration.ofHours(1));
        }

        @Test void parse_days() {
            assertThat(WatcherService.parseInterval("1d")).isEqualTo(Duration.ofDays(1));
        }

        @Test void parse_null_returns_null() {
            assertThat(WatcherService.parseInterval(null)).isNull();
        }

        @Test void parse_blank_returns_null() {
            assertThat(WatcherService.parseInterval("")).isNull();
        }

        @Test void parse_invalid_returns_null() {
            assertThat(WatcherService.parseInterval("abc")).isNull();
        }
    }

    // --- WatcherService.isTruthy tests ---

    @Nested
    class TruthyTests {

        @Test void null_is_falsy() { assertThat(WatcherService.isTruthy(null)).isFalse(); }
        @Test void true_is_truthy() { assertThat(WatcherService.isTruthy(true)).isTrue(); }
        @Test void false_is_falsy() { assertThat(WatcherService.isTruthy(false)).isFalse(); }
        @Test void nonzero_number_is_truthy() { assertThat(WatcherService.isTruthy(1)).isTrue(); }
        @Test void zero_is_falsy() { assertThat(WatcherService.isTruthy(0)).isFalse(); }
        @Test void nonempty_string_is_truthy() { assertThat(WatcherService.isTruthy("ok")).isTrue(); }
        @Test void empty_string_is_falsy() { assertThat(WatcherService.isTruthy("")).isFalse(); }
        @Test void false_string_is_falsy() { assertThat(WatcherService.isTruthy("false")).isFalse(); }
    }
}
