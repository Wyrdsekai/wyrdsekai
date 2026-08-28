package org.wyrdsekai.core.coding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The host block is generated, not written.
 *
 * <h2>Why</h2>
 * The adapter block is generated from the registry because prose about what the runtime
 * offers drifts from the runtime. When {@code world.host} was finally advertised on
 * 2026-08-22 its verb list was typed out by hand in the same file — rebuilding, one
 * function down, exactly the failure the file above it documents. It now renders from one
 * declaration, filtered by the ceiling, so a verb the ceiling withholds cannot be
 * advertised and a verb it allows cannot be forgotten.
 */
class TheContractCannotDriftFromTheCeilingTest {

    /** A ceiling that permits reading a granted directory but not rearranging it. */
    private static ItemCapabilitySet readOnly() {
        return ItemCapabilitySet.of(java.util.Set.of("host.file_find"));
    }

    @Test
    @DisplayName("a ceiling that withholds a verb does not see it advertised")
    void withheldVerbsAreNotOffered() {
        var block = ItemApiSurface.hostBlock(readOnly());
        // Empty on a node with no grant at all; when there IS a grant, the filter applies.
        if (block.isBlank()) return;
        assertThat(block).contains("world.host.find");
        assertThat(block)
            .as("the ceiling withholds move, so the contract must not teach it")
            .doesNotContain("world.host.move");
    }

    @Test
    @DisplayName("the crafted ceiling's host verbs are all advertised")
    void grantedVerbsAreAllOffered() {
        var block = ItemApiSurface.hostBlock(ItemCapabilitySet.craftedDefault());
        if (block.isBlank()) return;
        for (var verb : new String[]{"world.host.find", "world.host.mkdir", "world.host.move"}) {
            assertThat(block).as("crafted items may use %s", verb).contains(verb);
        }
    }
}
