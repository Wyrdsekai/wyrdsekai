package org.wyrdsekai.core.skill.impl;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.skill.SkillContext;
import org.wyrdsekai.core.skill.SkillTier;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GCalSkillExecutorTest {

    private final GCalSkillExecutor executor = new GCalSkillExecutor();

    @Nested
    class Registration {

        @Test
        void registers_four_skills() {
            var skills = executor.availableSkills();
            assertEquals(4, skills.size());
            var ids = skills.stream().map(s -> s.id()).toList();
            assertTrue(ids.contains("scriptorium.gcal.events"));
            assertTrue(ids.contains("scriptorium.gcal.create"));
            assertTrue(ids.contains("scriptorium.gcal.freebusy"));
            assertTrue(ids.contains("scriptorium.gcal.update"));
        }

        @Test
        void tier_is_native() {
            assertEquals(SkillTier.NATIVE, executor.tier());
        }
    }

    @Nested
    class Configuration {

        @Test
        void missing_google_oauth_returns_not_configured() {
            var ctx = SkillContext.forAgent("did:test:agent1", "test-room", Map.of(), 1000);
            var result = executor.execute("scriptorium.gcal.events", Map.of(), ctx);
            assertFalse(result.success());
        }
    }

    @Nested
    class ParamValidation {

        private final SkillContext ctx = SkillContext.forAgent(
            "did:test:agent1", "test-room",
            Map.of("google_oauth", "test-token"), 1000);

        @Test
        void create_missing_params_returns_error() {
            var result = executor.execute("scriptorium.gcal.create",
                Map.of("summary", "Meeting"), ctx);
            assertFalse(result.success());
        }

        @Test
        void freebusy_missing_time_returns_error() {
            var result = executor.execute("scriptorium.gcal.freebusy", Map.of(), ctx);
            assertFalse(result.success());
        }

        @Test
        void update_missing_eventId_returns_error() {
            var result = executor.execute("scriptorium.gcal.update", Map.of(), ctx);
            assertFalse(result.success());
        }
    }
}
