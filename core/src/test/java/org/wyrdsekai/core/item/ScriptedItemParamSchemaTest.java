package org.wyrdsekai.core.item;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.scripting.api.ItemManifest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A tool the model cannot see the shape of is a tool the model cannot call.
 *
 * <p>Every scripted item used to be advertised with ONE optional, undescribed
 * {@code query} string. So the model guessed. It guessed empty for
 * {@code morning_briefing} — whose script hard-requires {@code address} — and the
 * weather tool failed on every call for as long as it has existed
 * ({@code Executing scripted tool item 'Morning Briefing' ... with params: {query=}}
 * → {@code address is required}). {@code web_clipper}, which reads {@code url} and
 * never reads {@code query}, could never have succeeded either.
 */
class ScriptedItemParamSchemaTest {

    private static ItemManifest manifestWith(List<ItemManifest.Param> params) {
        return new ItemManifest("weather", "1.0", "d", "a",
            List.of(), Map.of(), "low", List.of(), List.of(), List.of(),
            List.of(), null, null, null, "1.0", null, List.of(), null, params);
    }

    private static ScriptedItemDef defWith(ItemManifest m) {
        return new ScriptedItemDef("morning_briefing", "Morning Briefing",
            "Daily forecast", m, "function invoke(p){}", null);
    }

    @Test
    @DisplayName("declared params reach the model as a real, typed, required schema")
    void declaredParamsBecomeTheToolSchema() {
        var def = defWith(manifestWith(List.of(
            new ItemManifest.Param("address", "string", "The place to forecast", true),
            new ItemManifest.Param("day", "string", "today or tomorrow", false))));

        var params = def.toToolItem().params();
        assertEquals(2, params.size(), "the model must be shown BOTH declared slots");

        var address = params.getFirst();
        assertEquals("address", address.name());
        assertEquals("string", address.type());
        assertTrue(address.required(),
            "address is what the script hard-requires — the model must be TOLD it is required");
        assertTrue(address.description().contains("place"),
            "the description is the only thing telling the model what to put here");

        assertFalse(params.get(1).required(), "day is genuinely optional");
    }

    @Test
    @DisplayName("a script declaring nothing still gets the free-form query slot (no regression)")
    void undeclaredFallsBackToQuery() {
        var params = defWith(manifestWith(List.of())).toToolItem().params();
        assertEquals(1, params.size());
        assertEquals("query", params.getFirst().name());
        assertFalse(params.getFirst().required());
    }

    @Test
    @DisplayName("the shipped weather item declares the address its script requires")
    void morningBriefingDeclaresAddress() throws Exception {
        // Guards the actual regression: it is not enough that the mechanism exists —
        // the item that failed every call has to actually use it.
        var src = Files.readString(Path.of("../scripts/items/morning_briefing.js"));
        assertTrue(src.contains("params:"),
            "morning_briefing must declare a params schema");
        assertTrue(src.replaceAll("\\s+", " ")
                .matches("(?s).*name: \"address\".*required: true.*"),
            "morning_briefing must declare `address` as REQUIRED — its script fails without it");
    }

    @Test
    @DisplayName("web_clipper declares the url its script requires (it never reads `query`)")
    void webClipperDeclaresUrl() throws Exception {
        var src = Files.readString(Path.of("../scripts/items/web_clipper.js"));
        assertTrue(src.replaceAll("\\s+", " ")
                .matches("(?s).*name: \"url\".*required: true.*"),
            "web_clipper reads params.url and never params.query — under the old "
                + "single-`query` schema the model could not have called it successfully");
    }
}
