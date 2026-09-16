package org.wyrdsekai.core.item;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.persistence.InventoryService;
import org.wyrdsekai.core.persistence.SchemaInitializer;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Study seeded before 2026-09-15 holds a furnishing with the object id {@code mailbox}
 * that reads grants, not mail. Renaming the kit alone leaves that item in place forever —
 * and the word stays taken on exactly the nodes that already exist. The seed path retires
 * it, and only it: a crafted item that happens to share the id is left alone.
 */
class RetiredFurnishingIsRemovedTest {

    private static final String OWNER = "u-kaz";

    @Test
    @DisplayName("the old grants furnishing is removed; a crafted mailbox of the same id is not")
    void retiresOnlyOurs(@TempDir Path dir) {
        var inv = new InventoryService(SchemaInitializer.initialize(dir.resolve("inv.db")));
        inv.addItem(OWNER, "mailbox", "Mailbox", "brass-hinged", false, "study-1",
            "function invoke(params) { var grants = world.grants.held(); return { text: '' }; }", "mailbox");
        inv.addItem("u-mei", "mailbox", "Mailbox", "a box they built", true, "study-2",
            "function invoke(params) { return { text: 'a box' }; }", "mailbox");

        StudyFurnishingKit.retireRenamedFurnishings(inv, OWNER);
        StudyFurnishingKit.retireRenamedFurnishings(inv, "u-mei");

        assertFalse(inv.hasItem(OWNER, "mailbox"), "the grants furnishing is retired");
        assertTrue(inv.hasItem("u-mei", "mailbox"), "someone's own crafted box is untouched");

        StudyFurnishingKit.retireRenamedFurnishings(inv, OWNER);
        assertFalse(inv.hasItem(OWNER, "mailbox"), "idempotent");
    }

    @Test
    @DisplayName("the kit ships a grant case and no mailbox")
    void kitHasNoMailbox() {
        for (var item : StudyFurnishingKit.defaults()) {
            assertFalse("mailbox".equals(item.id()), "the word is free for real mail");
        }
        assertTrue(StudyFurnishingKit.defaults().stream().anyMatch(i -> "grant-case".equals(i.id())));
    }
}
