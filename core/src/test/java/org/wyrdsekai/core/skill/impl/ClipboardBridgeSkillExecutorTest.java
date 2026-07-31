package org.wyrdsekai.core.skill.impl;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.skill.SkillContext;
import org.wyrdsekai.core.skill.SkillResult;
import org.wyrdsekai.core.skill.SkillTier;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ClipboardBridgeSkillExecutorTest {

    private final ClipboardBridgeSkillExecutor executor = new ClipboardBridgeSkillExecutor();
    private final SkillContext ctx = SkillContext.forAgent("did:test:agent1", "test-room", Map.of(), 1000);

    @Nested
    class Registration {
        @Test
        void registers_two_skills() {
            assertEquals(2, executor.availableSkills().size());
            assertTrue(executor.supports("between.clipboard.copy"));
            assertTrue(executor.supports("between.clipboard.paste"));
        }

        @Test
        void tier_is_native() {
            assertEquals(SkillTier.NATIVE, executor.tier());
        }
    }

    @Nested
    class CopyPaste {
        @Test
        void copy_then_paste_returns_same_content() {
            executor.execute("between.clipboard.copy", Map.of("text", "hello world"), ctx);
            SkillResult result = executor.execute("between.clipboard.paste", Map.of(), ctx);
            assertTrue(result.success());
            assertEquals("hello world", result.data().get("text"));
        }

        @Test
        void paste_on_empty_returns_empty_message() {
            SkillResult result = executor.execute("between.clipboard.paste", Map.of(), ctx);
            assertTrue(result.success());
            assertNull(result.data().get("text"));
        }
    }
}
