package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The turn that judges a tool's result must get a ranked menu, like every other turn.
 *
 * <p>The reactive path ranked its tools only when a PERSON asked; the synthetic
 * {@code [Tool completed]} trigger is spoken by the companion herself, so it fell through to
 * the flat list. Household node, 2026-09-12: 188 schemas ≈ 17k tokens, more than the 9B's
 * 16,384 window, six permanent overflows in six hours, her judgment after each tool result
 * never ran. The list had grown that morning — the librarian door went from 11 tools to 22 —
 * and it grows with every item she crafts.</p>
 *
 * <p>And the fallback that mapped an unknown template by substring is gone: {@code backup_vault}
 * became a workbench hammer through the word "create" the same day.</p>
 */
class ToolResultFollowUpIsRankedTest {

    private static final Path ACTOR =
        Path.of("src/main/java/org/wyrdsekai/core/agent/CompanionActor.java");

    @Test
    @DisplayName("the reactive turn ranks the menu for her own triggers, not only a person's")
    void ownTriggersAreRankedToo() throws IOException {
        var src = Files.readString(ACTOR);
        var human = src.indexOf("if (pendingTrigger != null && isHumanRequest(pendingTrigger)) {");
        assertTrue(human > 0, "the human-request ranking block was not found");
        var window = src.substring(human, Math.min(src.length(), human + 7000));
        var elseAt = window.indexOf("} else {");
        assertTrue(elseAt > 0, "the human-request block needs an else branch for her own triggers");
        var branch = window.substring(elseAt, Math.min(window.length(), elseAt + 1500));
        assertTrue(branch.contains("stripActorWrappers(pendingTrigger.text())"),
            "the else branch must rank by what the trigger says");
        assertTrue(branch.contains("allTools = surfaceByAffordance("),
            "the else branch must run the affordance ranker — that is the cap and the hatch");
        var single = window.indexOf("// Single-model architecture");
        assertTrue(single > elseAt, "the else branch belongs before the request is assembled");
    }

    @Test
    @DisplayName("an unknown template is resolved by intent, then by an existing item, never by substring")
    void unknownTemplatesResolveByIntent() throws IOException {
        var src = Files.readString(ACTOR);
        assertFalse(src.contains("bestTemplateByTokens("),
            "the token scorer that counted 'the', 'for' and 'and' as evidence is gone");
        assertFalse(src.contains("standardItemLibrary.search(itemName)"),
            "an item NAME is not searched by substring against template descriptions");
        var at = src.indexOf("var resolution = standardItemLibrary.resolveIntent(templateName, itemName);");
        assertTrue(at > 0, "the craft fallback asks the library what she meant");
        var after = src.substring(at, Math.min(src.length(), at + 4000));
        assertTrue(after.contains("existingItemReach(templateName, itemName)"),
            "before offering the list, the fallback looks for the thing that already exists");
        assertTrue(after.indexOf("existingItemReach(") < after.indexOf("is NOT a valid template name"),
            "the existing-item reach comes before the list message");
    }

    @Test
    @DisplayName("what a script emits is copied out of its context before it leaves the bridge")
    void emitCopiesOutOfTheContext() throws IOException {
        var src = Files.readString(ACTOR);
        var at = src.indexOf("public void emit(String eventType, Map<String, Object> data) {");
        assertTrue(at > 0, "the bridge emit was not found");
        var body = src.substring(at, Math.min(src.length(), at + 900));
        assertTrue(body.contains("ItemBridgeSubAction.Emit(eventType, PlainValues.deepCopy(data))"),
            "emit must hand the room a plain copy, not the live view of a JS object");
    }
}
