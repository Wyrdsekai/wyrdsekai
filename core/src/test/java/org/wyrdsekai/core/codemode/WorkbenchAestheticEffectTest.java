package org.wyrdsekai.core.codemode;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.model.Entity;
import org.wyrdsekai.common.model.Exit;
import org.wyrdsekai.common.model.RoomObject;
import org.wyrdsekai.common.model.RoomSnapshot;
import org.wyrdsekai.core.agent.CompanionActor;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * workbench-as-studio drive shaping wiring.
 *
 * <p>Phase 1B-style reflective check (no full-server bootstrap) — verifies
 * the workbench transition + vitality overlay are wired:
 * <ul>
 *   <li>{@link CompanionActor} declares {@code applyWorkbenchTransition}
 *       (per-room entry/exit hook).</li>
 *   <li>{@link CompanionActor} declares {@code renderTodaysDraftSummary}
 *       (pinboard surfacing on entry).</li>
 *   <li>{@link CompanionActor} declares {@code inWorkbench} field
 *       (read by the per-tick drive overlay).</li>
 *   <li>{@link CompanionActor} declares {@code buildWorldPeekProvider},
 *       {@code buildMcpSearchProvider}, {@code buildMcpExecuteProvider}
 *       (Phase 2a namespace wiring).</li>
 * </ul>
 *
 * <p>The behavioral effect (FOCUS gain when in workshop, default rates
 * when not) is exercised at runtime by the wiring smoke in
 * {@code CodeModeNamespaceTest} + {@code WorkbenchTierTest}; this test
 * is the thin contract over the hook surface.</p>
 */
class WorkbenchAestheticEffectTest {

    @Test
    void apply_workbench_transition_method_exists() {
        var method = findDeclaredMethod(
            CompanionActor.class, "applyWorkbenchTransition");
        assertThat(method)
            .as("CompanionActor.applyWorkbenchTransition wires entry/exit effects")
            .isNotNull();
        assertThat(method.getParameterCount()).isEqualTo(2);
        assertThat(method.getParameterTypes()[0]).isEqualTo(String.class);
        assertThat(method.getParameterTypes()[1]).isEqualTo(String.class);
    }

    @Test
    void render_todays_draft_summary_method_exists() {
        var method = findDeclaredMethod(
            CompanionActor.class, "renderTodaysDraftSummary");
        assertThat(method)
            .as("CompanionActor.renderTodaysDraftSummary surfaces drafts on entry")
            .isNotNull();
        assertThat(method.getReturnType()).isEqualTo(String.class);
    }

    @Test
    void in_workbench_field_exists_and_is_boolean() throws Exception {
        var field = CompanionActor.class.getDeclaredField("inWorkbench");
        assertThat(field.getType()).isEqualTo(boolean.class);
    }

    @Test
    void world_peek_provider_builder_exists() {
        var method = findDeclaredMethod(
            CompanionActor.class, "buildWorldPeekProvider");
        assertThat(method).isNotNull();
        assertThat(method.getReturnType().getName())
            .contains("WorldPeekProvider");
    }

    @Test
    void mcp_search_provider_builder_exists() {
        var method = findDeclaredMethod(
            CompanionActor.class, "buildMcpSearchProvider");
        assertThat(method).isNotNull();
        assertThat(method.getReturnType().getName())
            .contains("McpSearchProvider");
    }

    @Test
    void mcp_execute_provider_builder_exists() {
        var method = findDeclaredMethod(
            CompanionActor.class, "buildMcpExecuteProvider");
        assertThat(method).isNotNull();
        assertThat(method.getReturnType().getName())
            .contains("McpExecuteProvider");
    }

    @Test
    void render_room_snapshot_helper_returns_spec_shape() throws Exception {
        // Static helper — exercised directly to verify the §8 peek shape.
        var method = CompanionActor.class.getDeclaredMethod(
            "renderRoomSnapshot",
            RoomSnapshot.class);
        method.setAccessible(true);

        var snap = new RoomSnapshot(
            "workshop", "The Workshop", "Tool racks line the walls.",
            "local", List.of(),
            List.of(new Exit("east", "terminal", "to terminal")),
            List.of(new Entity(
                "wyrd-1", "wyrd", "agent", "a companion")),
            List.of(new RoomObject(
                "workbench", "workbench", "a sturdy bench", false)),
            List.of());

        @SuppressWarnings("unchecked")
        var rendered = (Map<String, Object>) method.invoke(null, snap);
        assertThat(rendered).containsKeys("name", "description", "exits", "entities", "items");
        assertThat(rendered.get("name")).isEqualTo("The Workshop");
        @SuppressWarnings("unchecked")
        var exits = (List<String>) rendered.get("exits");
        assertThat(exits).contains("east");
        @SuppressWarnings("unchecked")
        var entities = (List<Map<String, Object>>) rendered.get("entities");
        assertThat(entities).hasSize(1);
        assertThat(entities.get(0).get("alias")).isEqualTo("wyrd");
        assertThat(entities.get(0).get("kind")).isEqualTo("agent");
        @SuppressWarnings("unchecked")
        var items = (List<Map<String, Object>>) rendered.get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("alias")).isEqualTo("workbench");
        assertThat(items.get(0).get("kind")).isEqualTo("fixture"); // not takeable → fixture
    }

    @Test
    void render_room_snapshot_handles_null() throws Exception {
        var method = CompanionActor.class.getDeclaredMethod(
            "renderRoomSnapshot",
            RoomSnapshot.class);
        method.setAccessible(true);
        assertThat(method.invoke(null, (Object) null)).isNull();
    }

    /** Find a declared method by name (any signature). */
    private static Method findDeclaredMethod(Class<?> cls, String name) {
        return Arrays.stream(cls.getDeclaredMethods())
            .filter(m -> m.getName().equals(name))
            .findFirst()
            .orElse(null);
    }
}
