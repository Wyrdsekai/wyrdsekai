package org.wyrdsekai.core.skill.impl;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.skill.SkillContext;
import org.wyrdsekai.core.skill.SkillTier;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WeatherSkillExecutorTest {

    private final WeatherSkillExecutor executor = new WeatherSkillExecutor();

    @Nested
    class Registration {

        @Test
        void registers_three_skills() {
            var skills = executor.availableSkills();
            assertEquals(3, skills.size());
            var ids = skills.stream().map(s -> s.id()).toList();
            assertTrue(ids.contains("scrying.weather.current"));
            assertTrue(ids.contains("scrying.weather.forecast"));
            assertTrue(ids.contains("scrying.weather.alerts"));
        }

        @Test
        void supports_known_skill() {
            assertTrue(executor.supports("scrying.weather.current"));
        }

        @Test
        void does_not_support_unknown_skill() {
            assertFalse(executor.supports("scrying.weather.unknown"));
        }

        @Test
        void tier_is_native() {
            assertEquals(SkillTier.NATIVE, executor.tier());
        }
    }

    @Nested
    class Execution {

        private final SkillContext ctx = SkillContext.forAgent("did:test:agent1", "test-room", Map.of(), 1000);

        @Test
        void missing_lat_lon_returns_error() {
            var result = executor.execute("scrying.weather.current", Map.of(), ctx);
            assertFalse(result.success());
            assertNotNull(result.output());
        }

        @Test
        void missing_lon_returns_error() {
            var result = executor.execute("scrying.weather.current", Map.of("lat", "40.7"), ctx);
            assertFalse(result.success());
        }

        @Test
        void valid_params_against_dead_url_returns_connection_error() {
            var dead = new WeatherSkillExecutor("http://127.0.0.1:1");
            var result = dead.execute("scrying.weather.current",
                Map.of("lat", "40.7", "lon", "-74.0"), ctx);
            assertFalse(result.success());
            assertNotNull(result.output());
        }
    }
}
