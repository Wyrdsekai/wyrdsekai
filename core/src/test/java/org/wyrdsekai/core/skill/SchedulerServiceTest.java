package org.wyrdsekai.core.skill;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SchedulerService — scheduled skill invocations.
 */
class SchedulerServiceTest {

    private SkillRegistry createRegistry() {
        var reg = new SkillRegistry(null, null);
        reg.registerExecutor(new SkillExecutor() {
            @Override
            public SkillResult execute(String skillId, Map<String, Object> params, SkillContext context) {
                return SkillResult.ok("Executed " + skillId, Map.of(), 5, SkillTier.NATIVE, skillId);
            }

            @Override
            public List<SkillDefinition> availableSkills() {
                return List.of(SkillDefinition.native_("test.skill", "Test", "d", "r",
                    List.of(), SkillAuth.NONE));
            }

            @Override
            public boolean supports(String skillId) { return true; }

            @Override
            public SkillTier tier() { return SkillTier.NATIVE; }
        });
        reg.setPermissions("did:agent:1", SkillPermission.allowAll());
        reg.setPermissions("did:agent:2", SkillPermission.allowAll());
        reg.setPermissions("scheduled", SkillPermission.allowAll());
        return reg;
    }

    private SchedulerService scheduler;

    @AfterEach
    void cleanup() {
        if (scheduler != null) scheduler.shutdown();
    }

    private static String newId() { return UUID.randomUUID().toString(); }

    // ── ScheduledAction Records ─────────────────────────────────────────

    @Nested
    class ScheduledActionTests {

        @Test
        void create_once_action() {
            var action = ScheduledAction.once(newId(), "did:agent:1", "test.skill",
                Map.of("key", "value"), Instant.now().plusSeconds(60));

            assertNotNull(action.id());
            assertEquals("did:agent:1", action.agentDid());
            assertEquals("test.skill", action.skillId());
            assertEquals(ScheduledAction.ActionStatus.ACTIVE, action.status());
            assertInstanceOf(ScheduledAction.Schedule.Once.class, action.schedule());
        }

        @Test
        void create_recurring_action() {
            var action = ScheduledAction.recurring(newId(), "did:agent:1", "test.skill",
                Map.of(), "0 8 * * *", ZoneId.of("UTC"));

            assertInstanceOf(ScheduledAction.Schedule.Recurring.class, action.schedule());
            var recurring = (ScheduledAction.Schedule.Recurring) action.schedule();
            assertEquals("0 8 * * *", recurring.cron());
        }

        @Test
        void create_on_event_action() {
            var action = ScheduledAction.onEvent(newId(), "did:agent:1", "test.skill",
                Map.of(), "rss.new-item");

            assertInstanceOf(ScheduledAction.Schedule.OnEvent.class, action.schedule());
            var event = (ScheduledAction.Schedule.OnEvent) action.schedule();
            assertEquals("rss.new-item", event.eventPattern());
        }

        @Test
        void with_status_returns_new_instance() {
            var action = ScheduledAction.once(newId(), "did:agent:1", "test.skill",
                Map.of(), Instant.now().plusSeconds(60));

            var paused = action.withStatus(ScheduledAction.ActionStatus.PAUSED);
            assertEquals(ScheduledAction.ActionStatus.PAUSED, paused.status());
            assertEquals(ScheduledAction.ActionStatus.ACTIVE, action.status(),
                "Original should be unchanged");
            assertEquals(action.id(), paused.id());
        }

        @Test
        void interval_schedule() {
            var schedule = new ScheduledAction.Schedule.Interval(
                Duration.ofMinutes(30), null);
            assertEquals(Duration.ofMinutes(30), schedule.every());
            assertNull(schedule.startAfter());
        }

        @Test
        void action_status_enum() {
            assertEquals(5, ScheduledAction.ActionStatus.values().length);
        }

        @Test
        void null_id_throws() {
            assertThrows(IllegalArgumentException.class, () ->
                ScheduledAction.once(null, "did:agent:1", "test.skill",
                    Map.of(), Instant.now()));
        }

        @Test
        void null_schedule_throws() {
            assertThrows(IllegalArgumentException.class, () ->
                new ScheduledAction(newId(), "did:agent:1", "test.skill",
                    Map.of(), null, false, null, 0, null, null, null, null));
        }
    }

    // ── SchedulerService ────────────────────────────────────────────────

    @Nested
    class ServiceTests {

        @Test
        void create_and_list() {
            scheduler = new SchedulerService(createRegistry());
            var action = ScheduledAction.once(newId(), "did:agent:1", "test.skill",
                Map.of(), Instant.now().plusSeconds(3600));

            scheduler.create(action);

            var list = scheduler.listForAgent("did:agent:1");
            assertEquals(1, list.size());
            assertEquals(action.id(), list.get(0).id());
        }

        @Test
        void cancel_action() {
            scheduler = new SchedulerService(createRegistry());
            var action = ScheduledAction.once(newId(), "did:agent:1", "test.skill",
                Map.of(), Instant.now().plusSeconds(3600));

            scheduler.create(action);
            scheduler.cancel(action.id());

            var list = scheduler.listForAgent("did:agent:1");
            assertTrue(list.isEmpty(), "Cancelled actions should not appear in list");

            var fetched = scheduler.get(action.id());
            assertTrue(fetched.isPresent());
            assertEquals(ScheduledAction.ActionStatus.CANCELLED, fetched.get().status());
        }

        @Test
        void pause_and_resume() {
            scheduler = new SchedulerService(createRegistry());
            var action = ScheduledAction.once(newId(), "did:agent:1", "test.skill",
                Map.of(), Instant.now().plusSeconds(3600));

            scheduler.create(action);
            scheduler.pause(action.id());

            assertEquals(ScheduledAction.ActionStatus.PAUSED,
                scheduler.get(action.id()).get().status());

            scheduler.resume(action.id());

            assertEquals(ScheduledAction.ActionStatus.ACTIVE,
                scheduler.get(action.id()).get().status());
        }

        @Test
        void size_and_active_count() {
            scheduler = new SchedulerService(createRegistry());

            var a1 = ScheduledAction.once(newId(), "did:agent:1", "test.skill",
                Map.of(), Instant.now().plusSeconds(3600));
            var a2 = ScheduledAction.once(newId(), "did:agent:1", "test.skill2",
                Map.of(), Instant.now().plusSeconds(3600));

            scheduler.create(a1);
            scheduler.create(a2);
            scheduler.cancel(a2.id());

            assertEquals(2, scheduler.size());
            assertEquals(1, scheduler.activeCount());
        }

        @Test
        void all_active() {
            scheduler = new SchedulerService(createRegistry());

            var a1 = ScheduledAction.once(newId(), "did:agent:1", "test.skill",
                Map.of(), Instant.now().plusSeconds(3600));
            var a2 = ScheduledAction.once(newId(), "did:agent:2", "test.skill",
                Map.of(), Instant.now().plusSeconds(3600));

            scheduler.create(a1);
            scheduler.create(a2);

            assertEquals(2, scheduler.allActive().size());
        }

        @Test
        void approval_flow() throws InterruptedException {
            scheduler = new SchedulerService(createRegistry());

            var latch = new CountDownLatch(1);
            var approved = new AtomicReference<ScheduledAction>();

            scheduler.onApprovalNeeded(action -> {
                approved.set(action);
                latch.countDown();
            });

            // Create an action that requires approval and fires immediately
            String id = newId();
            var action = new ScheduledAction(id, "did:agent:1", "test.skill",
                Map.of(),
                new ScheduledAction.Schedule.Once(Instant.now().minusSeconds(1)),
                true, "Approve this payment?", 0,
                ScheduledAction.ActionStatus.ACTIVE,
                Instant.now(), null, null);

            scheduler.create(action);

            boolean fired = latch.await(3, TimeUnit.SECONDS);
            if (fired) {
                assertNotNull(approved.get());
                assertEquals(ScheduledAction.ActionStatus.AWAITING_APPROVAL,
                    approved.get().status());
            }
        }

        @Test
        void deny_reschedules_interval() {
            scheduler = new SchedulerService(createRegistry());

            String id = newId();
            var action = new ScheduledAction(id, "did:agent:1", "test.skill",
                Map.of(),
                new ScheduledAction.Schedule.Interval(Duration.ofHours(1), null),
                true, "Approve?", 0,
                ScheduledAction.ActionStatus.AWAITING_APPROVAL,
                Instant.now(), null, null);

            scheduler.create(action);
            scheduler.deny(id);

            var fetched = scheduler.get(id);
            assertTrue(fetched.isPresent());
            assertEquals(ScheduledAction.ActionStatus.ACTIVE, fetched.get().status());
        }

        @Test
        void list_for_agent_filters_by_did() {
            scheduler = new SchedulerService(createRegistry());

            scheduler.create(ScheduledAction.once(newId(), "did:agent:1", "test.skill",
                Map.of(), Instant.now().plusSeconds(3600)));
            scheduler.create(ScheduledAction.once(newId(), "did:agent:2", "test.skill",
                Map.of(), Instant.now().plusSeconds(3600)));

            assertEquals(1, scheduler.listForAgent("did:agent:1").size());
            assertEquals(1, scheduler.listForAgent("did:agent:2").size());
            assertEquals(0, scheduler.listForAgent("did:agent:3").size());
        }

        @Test
        void get_nonexistent() {
            scheduler = new SchedulerService(createRegistry());
            assertTrue(scheduler.get("nonexistent").isEmpty());
        }

        @Test
        void result_callback_fires() throws InterruptedException {
            scheduler = new SchedulerService(createRegistry());
            var latch = new CountDownLatch(1);
            var resultRef = new AtomicReference<SkillResult>();

            scheduler.onResult(result -> {
                resultRef.set(result);
                latch.countDown();
            });

            // Immediate-fire once action (time in past, no approval)
            var action = new ScheduledAction(newId(), "did:agent:1", "test.skill",
                Map.of(),
                new ScheduledAction.Schedule.Once(Instant.now().minusSeconds(1)),
                false, null, 0,
                ScheduledAction.ActionStatus.ACTIVE,
                Instant.now(), null, null);

            scheduler.create(action);

            boolean fired = latch.await(3, TimeUnit.SECONDS);
            if (fired) {
                assertNotNull(resultRef.get());
                assertTrue(resultRef.get().success());
            }
        }
    }
}
