package org.wyrdsekai.core.skill.impl;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.skill.SkillContext;
import org.wyrdsekai.core.skill.SkillResult;
import org.wyrdsekai.core.skill.SkillTier;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RssSkillExecutorTest {

    private final RssSkillExecutor executor = new RssSkillExecutor();
    private final SkillContext ctx = SkillContext.forAgent("did:test:agent1", "test-room", Map.of(), 1000);

    @Nested
    class Registration {
        @Test
        void registers_four_skills() {
            assertEquals(4, executor.availableSkills().size());
            assertTrue(executor.supports("scrying.rss.feeds"));
            assertTrue(executor.supports("scrying.rss.latest"));
            assertTrue(executor.supports("scrying.rss.subscribe"));
            assertTrue(executor.supports("scrying.rss.summarize"));
        }

        @Test
        void tier_is_native() {
            assertEquals(SkillTier.NATIVE, executor.tier());
        }
    }

    @Nested
    class EmptyFeeds {
        @Test
        void list_when_empty_returns_no_feeds() {
            SkillResult result = executor.execute("scrying.rss.feeds", Map.of(), ctx);
            assertTrue(result.success());
            assertTrue(executor.getSubscriptions().isEmpty());
        }
    }
}
