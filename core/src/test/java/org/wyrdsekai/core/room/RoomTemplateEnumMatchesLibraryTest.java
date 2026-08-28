package org.wyrdsekai.core.room;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.item.ToolItemStarterKit;

import java.nio.file.Path;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.List;
import org.wyrdsekai.common.util.Json;
import org.wyrdsekai.core.agent.ActionToolBuilder;
import org.wyrdsekai.core.item.StandardItemLibrary;

/**
 * The room-template names the TOOL offers must be the ones the LIBRARY registers.
 *
 * <p>Live on home-server, 2026-07-30: asked for a greenhouse, the companion called
 * {@code create_room_from_template} with {@code template:"greenhouse-template"} —
 * the room she was asked to build, not a template. The valid names were in the
 * tool's description prose (twice) but {@code ToolParam.enumValues} was null, so
 * nothing constrained the choice. The call was rejected, she fell back to plain
 * {@code create_room}, which carries no default objects, and the bondholder got a
 * room described as "filled with lush plants" containing
 * <b>{@code 0 objects}</b>.</p>
 *
 * <p>Prose is advice; an enum is a constraint. This test keeps the two lists from
 * drifting apart, which is the failure mode that would quietly restore the bug.</p>
 */
class RoomTemplateEnumMatchesLibraryTest {

    @Test
    @DisplayName("the constant matches what the library actually registers")
    void constantMatchesRegistry(@TempDir Path tmp) {
        var library = new StandardRoomLibrary(tmp);
        assertEquals(
            new TreeSet<>(library.templates().keySet()),
            new TreeSet<>(StandardRoomLibrary.TEMPLATE_NAMES),
            "TEMPLATE_NAMES has drifted from registerAllTemplates — the tool would "
            + "offer a template that does not exist, or hide one that does");
    }

    @Test
    @DisplayName("the tool constrains the template parameter with an enum")
    void toolParamCarriesTheEnum() {
        var tool = ToolItemStarterKit.createRoomFromTemplate();
        var param = tool.params().stream()
            .filter(p -> "template".equals(p.name()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no 'template' parameter"));

        assertFalse(param.enumValues() == null || param.enumValues().isEmpty(),
            "enumValues is null/empty — the model can invent a template name again, "
            + "which is exactly what produced an empty greenhouse");
        assertEquals(
            new TreeSet<>(StandardRoomLibrary.TEMPLATE_NAMES),
            new TreeSet<>(param.enumValues()),
            "the offered enum must be the library's template set");

        // The description must say what the field IS, because the model's error was
        // conceptual — it used the room's name as the template.
        assertTrue(param.description().toLowerCase().contains("not its name")
                || param.description().toLowerCase().contains("furnishing"),
            "the description should say the template is the furnishing, not the "
            + "room's name: " + param.description());
    }

    @Test
    @DisplayName("craft_from_template is constrained the same way")
    void craftToolCarriesTheEnum(@TempDir Path tmp) {
        var library = new StandardItemLibrary(tmp);
        assertEquals(
            new TreeSet<>(library.templates().keySet()),
            new TreeSet<>(StandardItemLibrary.TEMPLATE_NAMES),
            "item TEMPLATE_NAMES has drifted from the library");

        var param = ToolItemStarterKit.craftFromTemplate().params().stream()
            .filter(p -> "template".equals(p.name())).findFirst()
            .orElseThrow(() -> new AssertionError("no 'template' parameter"));
        assertFalse(param.enumValues() == null || param.enumValues().isEmpty(),
            "craft_from_template must constrain its template too "
            + "records the same template-not-found failure for crafting");
        assertEquals(
            new TreeSet<>(StandardItemLibrary.TEMPLATE_NAMES),
            new TreeSet<>(param.enumValues()));
    }

    @Test
    @DisplayName("the enum survives into the EMITTED schema, not just the record")
    void enumReachesTheWire() throws Exception {
        // The step that matters. Populating ToolParam.enumValues is worthless if
        // toToolDefinition() drops it — the model would never see the constraint
        // and a live run would "disprove" a fix that was never delivered. This is
        // the same shape as the ActionPolicy rows that were inert because nothing
        // read them.
        for (var tool : List.of(
                ToolItemStarterKit.createRoomFromTemplate(),
                ToolItemStarterKit.craftFromTemplate())) {
            var json = Json.mapper()
                .writeValueAsString(tool.toToolDefinition());
            assertTrue(json.contains("\"enum\""),
                tool.id() + " emits no enum into its schema — the constraint never "
                + "reaches the model:\n" + json);
        }

        var roomJson = Json.mapper()
            .writeValueAsString(ToolItemStarterKit.createRoomFromTemplate().toToolDefinition());
        for (var name : StandardRoomLibrary.TEMPLATE_NAMES) {
            assertTrue(roomJson.contains("\"" + name + "\""),
                "template '" + name + "' missing from the emitted schema");
        }
        // Control: the value she actually confabulated must NOT be offered.
        assertFalse(roomJson.contains("greenhouse-template"),
            "the emitted schema must not contain the confabulated name");
    }

    @Test
    @DisplayName("the PARSED create_room action carries the same enum as the builtin")
    void parsedActionSurfaceCarriesTheEnum() throws Exception {
        // The builtin got its enum first and the parsed action did not — so the
        // bunshin path (which offers the parsed action) still confabulated, and a
        // furnished-but-orphaned room shipped next to a reachable-but-empty one.
        // Two surfaces, one source of truth, or they drift.
        var tools = ActionToolBuilder
            .buildFromNames(List.of("create_room"));
        assertEquals(1, tools.size(), "create_room must build a tool definition");
        var json = Json.mapper().writeValueAsString(tools.get(0));
        assertTrue(json.contains("\"enum\""),
            "parsed create_room emits no enum — the bunshin surface is unconstrained:\n" + json);
        for (var name : StandardRoomLibrary.TEMPLATE_NAMES) {
            assertTrue(json.contains("\"" + name + "\""),
                "template '" + name + "' missing from the parsed surface");
        }
        assertTrue(json.toLowerCase().contains("not its name"),
            "the description must name the concept the model got wrong");
    }

    @Test
    @DisplayName("a confabulated name is not in the offered set")
    void theConfabulatedNameIsExcluded() {
        // Control: the assertion above would pass against any non-empty list, so
        // pin the actual observed failure.
        assertFalse(StandardRoomLibrary.TEMPLATE_NAMES.contains("greenhouse-template"),
            "the observed bad value must not be silently added to make this pass");
        assertFalse(StandardRoomLibrary.TEMPLATE_NAMES.contains("greenhouse"),
            "'greenhouse' is a room someone wants, not a template — 'garden' is the "
            + "closest real one");
    }
}
