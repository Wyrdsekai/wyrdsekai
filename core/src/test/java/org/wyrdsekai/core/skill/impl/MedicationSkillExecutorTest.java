package org.wyrdsekai.core.skill.impl;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.skill.SkillContext;
import org.wyrdsekai.core.skill.SkillResult;
import org.wyrdsekai.core.skill.SkillTier;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MedicationSkillExecutorTest {

    private final MedicationSkillExecutor executor = new MedicationSkillExecutor();
    private final SkillContext ctx = SkillContext.forAgent("did:test:agent1", "test-room", Map.of(), 1000);

    @Nested
    class Registration {
        @Test
        void registers_three_skills() {
            assertEquals(3, executor.availableSkills().size());
        }

        @Test
        void skill_ids_present() {
            assertTrue(executor.supports("hearth.medication.list"));
            assertTrue(executor.supports("hearth.medication.acknowledge"));
            assertTrue(executor.supports("hearth.medication.missed"));
        }
    }

    @Nested
    class Tier {
        @Test
        void tier_is_native() {
            assertEquals(SkillTier.NATIVE, executor.tier());
        }
    }

    @Nested
    class ListEmpty {
        @Test
        void list_on_empty_returns_success_with_zero_count() {
            SkillResult result = executor.execute("hearth.medication.list", Map.of(), ctx);
            assertTrue(result.success());
            assertEquals(0, result.data().get("count"));
        }
    }

    @Nested
    class Acknowledge {
        @Test
        void acknowledge_requires_name_param() {
            SkillResult result = executor.execute("hearth.medication.acknowledge", Map.of(), ctx);
            assertFalse(result.success());
        }

        @Test
        void acknowledge_known_medication() {
            executor.addMedication("Aspirin", "daily");
            SkillResult result = executor.execute("hearth.medication.acknowledge",
                Map.of("name", "Aspirin"), ctx);
            assertTrue(result.success());
            assertNotNull(result.data().get("takenAt"));
        }
    }
}
