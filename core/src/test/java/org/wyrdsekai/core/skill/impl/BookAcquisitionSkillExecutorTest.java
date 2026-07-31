package org.wyrdsekai.core.skill.impl;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.skill.SkillContext;
import org.wyrdsekai.core.skill.SkillResult;
import org.wyrdsekai.core.skill.SkillTier;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BookAcquisitionSkillExecutorTest {

    private final BookAcquisitionSkillExecutor executor = new BookAcquisitionSkillExecutor();

    @Nested
    class Registration {
        @Test
        void registers_two_skills() {
            assertEquals(5, executor.availableSkills().size());
            assertTrue(executor.supports("library.books.search"));
            assertTrue(executor.supports("library.books.shelve"));
        }

        @Test
        void tier_is_native() {
            assertEquals(SkillTier.NATIVE, executor.tier());
        }
    }

    @Nested
    class MissingParams {
        @Test
        void search_requires_query_param() {
            var ctx = SkillContext.forAgent("did:test:agent1", "test-room", Map.of(), 1000);
            SkillResult result = executor.execute("library.books.search", Map.of(), ctx);
            assertFalse(result.success());
        }
    }
}
