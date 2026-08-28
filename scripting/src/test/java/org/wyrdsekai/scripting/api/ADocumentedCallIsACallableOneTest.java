package org.wyrdsekai.scripting.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whatever a script passes for a list becomes a list.
 *
 * <h2>What went wrong</h2>
 * Ten exported methods took {@code List<String>}. A JS array does not reliably bind to
 * that overload; GraalJS answers
 * {@code TypeError: no applicable overload found (overloads: [Method[public java.util.Map
 * …MemoryApi.add(java.lang.String,java.util.List)] …])} — a message about Java signatures,
 * handed to whoever is holding the item. Live on staging 2026-08-22 an item called
 * {@code world.memory.add(content, tags)} exactly as the contract documents it and died
 * there. A documented call has to be a callable one.
 */
class ADocumentedCallIsACallableOneTest {

    @Test
    @DisplayName("a list, an array, a comma string and a bare value all become a list")
    void everyShapeCoerces() {
        assertThat(ItemWorldApi.strings(List.of("a", "b"))).containsExactly("a", "b");
        assertThat(ItemWorldApi.strings(new Object[]{"a", "b"})).containsExactly("a", "b");
        assertThat(ItemWorldApi.strings("a, b")).containsExactly("a", "b");
        assertThat(ItemWorldApi.strings("solo")).containsExactly("solo");
    }

    @Test
    @DisplayName("nothing becomes an empty list, never a crash and never a null")
    void nothingIsEmpty() {
        assertThat(ItemWorldApi.strings(null)).isEmpty();
        assertThat(ItemWorldApi.strings("")).isEmpty();
        assertThat(ItemWorldApi.strings(List.of())).isEmpty();
    }

    @Test
    @DisplayName("mixed contents are stringified rather than refused")
    void mixedIsStringified() {
        assertThat(ItemWorldApi.strings(List.of("a", 2, true)))
            .containsExactly("a", "2", "true");
    }
}
