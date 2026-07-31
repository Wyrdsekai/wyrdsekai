package org.wyrdsekai.core.skill.impl;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.skill.SkillContext;
import org.wyrdsekai.core.skill.SkillTier;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CalDavSkillExecutorTest {

    private final CalDavSkillExecutor executor = new CalDavSkillExecutor("http://localhost:5232");

    @Nested
    class Registration {

        @Test
        void registers_four_skills() {
            var skills = executor.availableSkills();
            assertEquals(4, skills.size());
            var ids = skills.stream().map(s -> s.id()).toList();
            assertTrue(ids.contains("scriptorium.calendar.events"));
            assertTrue(ids.contains("scriptorium.calendar.create"));
            assertTrue(ids.contains("scriptorium.calendar.freebusy"));
            assertTrue(ids.contains("scriptorium.calendar.reminders"));
        }

        @Test
        void tier_is_native() {
            assertEquals(SkillTier.NATIVE, executor.tier());
        }
    }

    @Nested
    class Configuration {

        private final SkillContext ctx = SkillContext.forAgent("did:test:agent1", "test-room", Map.of(), 1000);

        @Test
        void blank_base_url_returns_not_configured() {
            var blank = new CalDavSkillExecutor("");
            var result = blank.execute("scriptorium.calendar.events", Map.of(), ctx);
            assertFalse(result.success());
        }

        @Test
        void missing_caldav_credentials_returns_not_configured() {
            var result = executor.execute("scriptorium.calendar.events", Map.of(), ctx);
            assertFalse(result.success());
        }
    }
}
