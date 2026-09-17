package org.wyrdsekai.core.item;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Her Home had a mailbox by the door since birth that said no letters pass through this hearth,
 * while the real mail store sat behind {@code world.mailbox.*}. The Hearth kit now seeds the
 * household mailbox template into her inventory under the fixed id {@code mailbox}, so
 * {@code use mailbox} opens her own box.
 */
class HerHearthHasAMailboxTest {

    @Test
    @DisplayName("the hearth kit furnishes the real mailbox from the standard template")
    void theRealMailbox() {
        var scripts = Path.of("..", "scripts").toAbsolutePath().normalize();
        assumeTrue(Files.isRegularFile(scripts.resolve("std").resolve("mailbox.js")), "needs the scripts tree");
        var library = new StandardItemLibrary(scripts);

        var items = HearthFurnishingKit.defaults(library);
        var box = items.stream().filter(i -> "mailbox".equals(i.id())).findFirst().orElseThrow();
        assertEquals("Mailbox", box.name());
        assertTrue(box.script().contains("item._type = \"mailbox\""), "the household mailbox script, not a crate");
        assertTrue(box.description().contains("use mailbox read"), box.description());
        assertTrue(box.params().stream().anyMatch(p -> "action".equals(p.name())), "the template's parameters ride along");
    }

    @Test
    @DisplayName("without a library the kit stays as it was")
    void withoutALibrary() {
        assertTrue(HearthFurnishingKit.defaults(null).stream().noneMatch(i -> "mailbox".equals(i.id())));
        assertEquals(HearthFurnishingKit.defaults().size(), HearthFurnishingKit.defaults(null).size());
    }
}
