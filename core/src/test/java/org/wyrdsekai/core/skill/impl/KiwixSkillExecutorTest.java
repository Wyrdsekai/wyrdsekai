package org.wyrdsekai.core.skill.impl;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.skill.SkillContext;
import org.wyrdsekai.core.skill.SkillTier;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class KiwixSkillExecutorTest {

    private final KiwixSkillExecutor executor = new KiwixSkillExecutor("http://localhost:9999");

    @Nested
    class Registration {

        @Test
        void registers_four_skills() {
            var skills = executor.availableSkills();
            assertEquals(4, skills.size());
            var ids = skills.stream().map(s -> s.id()).toList();
            assertTrue(ids.contains("library.kiwix.search"));
            assertTrue(ids.contains("library.kiwix.read"));
            assertTrue(ids.contains("library.kiwix.suggest"));
            assertTrue(ids.contains("library.kiwix.random"));
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
        void blank_base_url_returns_not_configured() {
            var blank = new KiwixSkillExecutor("");
            var result = blank.execute("library.kiwix.search", Map.of("query", "test"), ctx);
            assertFalse(result.success());
        }

        @Test
        void missing_query_param_returns_error() {
            var result = executor.execute("library.kiwix.search", Map.of(), ctx);
            assertFalse(result.success());
            assertNotNull(result.output());
        }

        @Test
        void missing_path_param_returns_error() {
            var result = executor.execute("library.kiwix.read", Map.of(), ctx);
            assertFalse(result.success());
        }
    }
}
