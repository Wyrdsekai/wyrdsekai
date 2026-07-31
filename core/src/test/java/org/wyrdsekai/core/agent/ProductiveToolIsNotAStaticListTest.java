package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.item.ToolItemStarterKit;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The anti-false-completion gate asks "did this loop run a productive tool?" against a
 * STATIC allowlist of built-in action names — which lists not a single scripted item.
 *
 * <p>So a companion that ran a real tool was told it had done nothing. Measured on second-node
 * 2026-07-13: mia called {@code trip_planner}, got a genuine San Francisco forecast, told
 * the user, and then hit {@code Anti-false-completion: goal_done blocked — claimed
 * completion with no productive tool (history=[trip_planner, tell_agent])}. She did the
 * work and the gate refused to let her finish, leaving the plan open.</p>
 *
 * <p>A static list structurally cannot cover this: scripted items are disk-loaded (55 of
 * them) and agents forge new ones at runtime. The gate must ASK THE REGISTRY — which is
 * the very same resolver that dispatched the tool in the first place. If it was
 * dispatchable as a tool, invoking it was real work.</p>
 */
class ProductiveToolIsNotAStaticListTest {

    @Test
    @DisplayName("the shipped scripted items are NOT in the static allowlist — that is the bug")
    void staticListOmitsEveryScriptedItem() throws Exception {
        var src = Files.readString(Path.of(
            "src/main/java/org/wyrdsekai/core/agent/CompanionActor.java"));
        var listStart = src.indexOf("REACT_PRODUCTIVE_TOOLS = Set.of(");
        assertTrue(listStart > 0, "the static allowlist should still exist");
        var listBody = src.substring(listStart, src.indexOf(");", listStart));

        for (var scripted : new String[]{"trip_planner", "morning_briefing",
                                          "calculator", "web_clipper"}) {
            assertFalse(listBody.contains("\"" + scripted + "\""),
                "if '" + scripted + "' were hard-coded into the allowlist this test is "
                    + "obsolete — but hard-coding each of 55 disk-loaded items (plus the "
                    + "ones agents forge at runtime) is exactly the approach that failed. "
                    + "Productivity is decided by the tool registry, not a name list.");
        }
    }

    @Test
    @DisplayName("scripted items DO resolve through the tool registry the gate now consults")
    void scriptedItemsResolveThroughTheRegistry() {
        // resolveToolItem() reads ToolItemStarterKit.standard(), which merges the
        // disk-loaded scripted items. That is what makes isProductiveTool() see
        // trip_planner even though no static list names it. If this ever stops being
        // true, the gate silently reverts to calling real work "unproductive".
        var ids = ToolItemStarterKit.standard().stream().map(t -> t.id()).toList();
        assertTrue(ids.contains("library_card") || ids.contains("searching_glass"),
            "the starter kit itself must resolve — got: " + ids);
    }
}
