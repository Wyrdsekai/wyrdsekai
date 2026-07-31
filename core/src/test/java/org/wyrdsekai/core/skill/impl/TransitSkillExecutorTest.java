package org.wyrdsekai.core.skill.impl;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.skill.SkillContext;
import org.wyrdsekai.core.skill.SkillResult;
import org.wyrdsekai.core.skill.SkillTier;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TransitSkillExecutorTest {

    private final TransitSkillExecutor executor = new TransitSkillExecutor(Path.of("/nonexistent"));

    @Nested
    class Registration {
        @Test
        void registers_three_skills() {
            assertEquals(3, executor.availableSkills().size());
            assertTrue(executor.supports("scrying.transit.next"));
            assertTrue(executor.supports("scrying.transit.route"));
            assertTrue(executor.supports("scrying.transit.alerts"));
        }

        @Test
        void tier_is_native() {
            assertEquals(SkillTier.NATIVE, executor.tier());
        }
    }

    @Nested
    class MissingData {
        @Test
        void execute_with_no_gtfs_returns_error() {
            var ctx = SkillContext.forAgent("did:test:agent1", "test-room", Map.of(), 1000);
            SkillResult result = executor.execute("scrying.transit.next",
                Map.of("stop", "Main St"), ctx);
            assertFalse(result.success());
        }
    }
}
