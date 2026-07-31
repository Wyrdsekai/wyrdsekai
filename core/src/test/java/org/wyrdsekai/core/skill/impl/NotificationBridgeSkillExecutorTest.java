package org.wyrdsekai.core.skill.impl;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.skill.SkillContext;
import org.wyrdsekai.core.skill.SkillResult;
import org.wyrdsekai.core.skill.SkillTier;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NotificationBridgeSkillExecutorTest {

    private final NotificationBridgeSkillExecutor executor = new NotificationBridgeSkillExecutor();

    @Test
    void registers_two_skills() {
        assertEquals(2, executor.availableSkills().size());
        assertTrue(executor.supports("hearth.notification.recent"));
        assertTrue(executor.supports("hearth.notification.dismiss"));
    }

    @Test
    void tier_is_native() {
        assertEquals(SkillTier.NATIVE, executor.tier());
    }

    @Test
    void dismiss_returns_phone_only_error() {
        var ctx = SkillContext.forAgent("did:test:agent1", "test-room", Map.of(), 1000);
        SkillResult result = executor.execute("hearth.notification.dismiss",
            Map.of("id", "notif-1"), ctx);
        assertFalse(result.success());
        assertTrue(result.output().toLowerCase().contains("phone"));
    }
}
