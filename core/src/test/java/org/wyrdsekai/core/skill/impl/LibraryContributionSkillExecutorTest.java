package org.wyrdsekai.core.skill.impl;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.skill.SkillContext;
import org.wyrdsekai.core.skill.SkillResult;
import org.wyrdsekai.core.skill.SkillTier;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LibraryContributionSkillExecutorTest {

    private final LibraryContributionSkillExecutor executor = new LibraryContributionSkillExecutor();
    private final SkillContext ctx = SkillContext.forAgent("did:test:agent1", "test-room", Map.of(), 1000);

    @Nested
    class Registration {
        @Test
        void registers_two_skills() {
            assertEquals(2, executor.availableSkills().size());
            assertTrue(executor.supports("library.contribute.submit"));
            assertTrue(executor.supports("library.contribute.status"));
        }

        @Test
        void tier_is_native() {
            assertEquals(SkillTier.NATIVE, executor.tier());
        }
    }

    @Nested
    class SubmitAndStatus {
        @Test
        void submit_then_status_returns_pending() {
            SkillResult submit = executor.execute("library.contribute.submit",
                Map.of("title", "Test Knowledge", "content", "Some content"), ctx);
            assertTrue(submit.success());
            String id = (String) submit.data().get("id");
            assertNotNull(id);

            SkillResult status = executor.execute("library.contribute.status",
                Map.of("id", id), ctx);
            assertTrue(status.success());
            assertEquals("pending", status.data().get("status"));
        }
    }

    @Nested
    class MissingParams {
        @Test
        void submit_requires_title_and_content() {
            SkillResult result = executor.execute("library.contribute.submit", Map.of(), ctx);
            assertFalse(result.success());
        }

        @Test
        void status_requires_id() {
            SkillResult result = executor.execute("library.contribute.status", Map.of(), ctx);
            assertFalse(result.success());
        }
    }
}
