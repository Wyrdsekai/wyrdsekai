package org.wyrdsekai.core.skill.impl;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.skill.SkillBootstrap;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SkillBootstrapTest {

    @Nested
    class DefaultConfig {

        @Test
        void empty_config_registers_always_on_executors() {
            var registry = SkillBootstrap.create(Map.of());
            var skills = registry.allSkills();
            // Always-on executors include Weather, Medication, Grocery, etc.
            assertFalse(skills.isEmpty());
            assertTrue(registry.hasSkill("scrying.weather.current"));
        }

        @Test
        void null_config_does_not_npe() {
            var registry = SkillBootstrap.create(null);
            assertNotNull(registry);
            assertFalse(registry.allSkills().isEmpty());
        }
    }

    @Nested
    class ConfigDependent {

        @Test
        void ha_url_registers_home_assistant_executor() {
            var registry = SkillBootstrap.create(Map.of("ha.url", "http://ha.local:8123"));
            assertTrue(registry.hasSkill("hearth.ha.state"));
            assertTrue(registry.hasSkill("hearth.ha.entities"));
        }

        @Test
        void kiwix_url_registers_kiwix_executor() {
            var registry = SkillBootstrap.create(Map.of("kiwix.url", "http://localhost:8888"));
            assertTrue(registry.hasSkill("library.kiwix.search"));
        }

        @Test
        void blank_ha_url_does_not_register_ha() {
            var registry = SkillBootstrap.create(Map.of("ha.url", "  "));
            assertFalse(registry.hasSkill("hearth.ha.state"));
        }
    }
}
