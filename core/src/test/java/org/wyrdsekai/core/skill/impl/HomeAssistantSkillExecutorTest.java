package org.wyrdsekai.core.skill.impl;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.skill.SkillContext;
import org.wyrdsekai.core.skill.SkillTier;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HomeAssistantSkillExecutorTest {

    private final HomeAssistantSkillExecutor executor =
        new HomeAssistantSkillExecutor("http://homeassistant.local:8123");

    @Nested
    class Registration {

        @Test
        void registers_five_skills() {
            var skills = executor.availableSkills();
            assertEquals(5, skills.size());
            var ids = skills.stream().map(s -> s.id()).toList();
            assertTrue(ids.contains("hearth.ha.state"));
            assertTrue(ids.contains("hearth.ha.service"));
            assertTrue(ids.contains("hearth.ha.entities"));
            assertTrue(ids.contains("hearth.ha.automation"));
            assertTrue(ids.contains("hearth.ha.history"));
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
            var blank = new HomeAssistantSkillExecutor("");
            var result = blank.execute("hearth.ha.state",
                Map.of("entity_id", "light.test"), ctx);
            assertFalse(result.success());
        }

        @Test
        void missing_ha_token_returns_not_configured() {
            var result = executor.execute("hearth.ha.state",
                Map.of("entity_id", "light.test"), ctx);
            assertFalse(result.success());
        }
    }

    @Nested
    class Execution {

        private final SkillContext ctx = SkillContext.forAgent(
            "did:test:agent1", "test-room",
            Map.of("ha_token", "test-token"), 1000);

        @Test
        void missing_entity_id_returns_error() {
            var result = executor.execute("hearth.ha.state", Map.of(), ctx);
            assertFalse(result.success());
            assertNotNull(result.output());
        }

        @Test
        void missing_service_params_returns_error() {
            var result = executor.execute("hearth.ha.service", Map.of(), ctx);
            assertFalse(result.success());
        }
    }
}
