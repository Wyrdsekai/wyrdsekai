package org.wyrdsekai.core.skill.impl;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.skill.SkillContext;
import org.wyrdsekai.core.skill.SkillResult;
import org.wyrdsekai.core.skill.SkillTier;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ScreenshotSkillExecutorTest {

    private final ScreenshotSkillExecutor executor = new ScreenshotSkillExecutor();

    @Test
    void registers_one_skill() {
        assertEquals(1, executor.availableSkills().size());
        assertTrue(executor.supports("bridge.screenshot.capture"));
    }

    @Test
    void execute_returns_phone_only_error() {
        var ctx = SkillContext.forAgent("did:test:agent1", "test-room", Map.of(), 1000);
        SkillResult result = executor.execute("bridge.screenshot.capture", Map.of(), ctx);
        assertFalse(result.success());
        assertTrue(result.output().toLowerCase().contains("phone"));
    }
}
