package org.wyrdsekai.core.skill.impl;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.skill.SkillContext;
import org.wyrdsekai.core.skill.SkillResult;
import org.wyrdsekai.core.skill.SkillTier;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GrocerySkillExecutorTest {

    private final GrocerySkillExecutor executor = new GrocerySkillExecutor();
    private final SkillContext ctx = SkillContext.forAgent("did:test:agent1", "test-room", Map.of(), 1000);

    @Nested
    class Registration {
        @Test
        void registers_four_skills() {
            assertEquals(4, executor.availableSkills().size());
        }

        @Test
        void tier_is_native() {
            assertEquals(SkillTier.NATIVE, executor.tier());
        }
    }

    @Nested
    class ListEmpty {
        @Test
        void list_on_empty_returns_empty_message() {
            SkillResult result = executor.execute("hearth.grocery.list", Map.of(), ctx);
            assertTrue(result.success());
            assertEquals(0, result.data().get("count"));
        }
    }

    @Nested
    class AddRemoveFlow {
        @Test
        void add_then_list_shows_item() {
            executor.execute("hearth.grocery.add", Map.of("item", "milk"), ctx);
            SkillResult list = executor.execute("hearth.grocery.list", Map.of(), ctx);
            assertTrue(list.success());
            assertEquals(1, list.data().get("count"));
        }

        @Test
        void add_then_remove_clears_item() {
            executor.execute("hearth.grocery.add", Map.of("item", "eggs"), ctx);
            executor.execute("hearth.grocery.remove", Map.of("item", "eggs"), ctx);
            SkillResult list = executor.execute("hearth.grocery.list", Map.of(), ctx);
            assertTrue(list.success());
            assertEquals(0, list.data().get("count"));
        }

        @Test
        void add_requires_item_param() {
            SkillResult result = executor.execute("hearth.grocery.add", Map.of(), ctx);
            assertFalse(result.success());
        }
    }
}
