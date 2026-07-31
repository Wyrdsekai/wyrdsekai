package org.wyrdsekai.core.skill.impl;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.skill.SkillContext;
import org.wyrdsekai.core.skill.SkillResult;
import org.wyrdsekai.core.skill.SkillTier;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HealthBridgeSkillExecutorTest {

    private final HealthBridgeSkillExecutor executor = new HealthBridgeSkillExecutor();

    @Test
    void registers_four_skills() {
        assertEquals(4, executor.availableSkills().size());
        assertTrue(executor.supports("hearth.health.steps"));
        assertTrue(executor.supports("hearth.health.sleep"));
        assertTrue(executor.supports("hearth.health.heartrate"));
        assertTrue(executor.supports("hearth.health.summary"));
    }

    @Test
    void execute_returns_phone_only_error() {
        var ctx = SkillContext.forAgent("did:test:agent1", "test-room", Map.of(), 1000);
        SkillResult result = executor.execute("hearth.health.steps", Map.of(), ctx);
        assertFalse(result.success());
        assertTrue(result.output().toLowerCase().contains("phone"));
    }
}
