package org.wyrdsekai.scripting.sandbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.scripting.api.WorldApi;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A room script that failed was logged once per hook and nothing else happened: the same error,
 * every time anyone entered or spoke (the household node, 2026-10-09, three rooms the companions
 * built). The sandbox now tells whoever asked — the hook and the message — so the failure can be
 * counted, told to her, and the script set aside.
 */
class AFailingRoomScriptIsReportedTest {

    private static List<String> run(String script, String hook) {
        var reported = new ArrayList<String>();
        try (var sandbox = new ScriptSandbox("a-room")) {
            sandbox.onFailure((what, message) -> reported.add(what + " :: " + message));
            sandbox.callHook(script, hook, new WorldApi("a-room"), "p1", "Pat", "north");
        }
        return reported;
    }

    @Test
    @DisplayName("a syntax error is reported with the hook it failed in")
    void syntaxError() {
        var reported = run("function onEnter(id, name, from) { world.emit('narrate', { text: 'hi' ) }", "onEnter");
        assertThat(reported).hasSize(1);
        assertThat(reported.get(0)).startsWith("onEnter :: ").containsIgnoringCase("SyntaxError");
    }

    @Test
    @DisplayName("a call to something that is not there is reported by name")
    void notDefined() {
        var reported = run("function onEnter(id, name, from) { emit('narrate', { text: 'hi' }); }", "onEnter");
        assertThat(reported).hasSize(1);
        assertThat(reported.get(0)).contains("emit is not defined");
        var reported2 = run("function onEnter(id, name, from) { id.getProperty('x'); }", "onEnter");
        assertThat(reported2).hasSize(1);
        assertThat(reported2.get(0)).contains("is not a function");
    }

    @Test
    @DisplayName("a script that works, or a hook it does not define, reports nothing")
    void nothingToReport() {
        assertThat(run("function onEnter(id, name, from) { world.emit('narrate', { text: 'hi' }); }", "onEnter")).isEmpty();
        assertThat(run("function onSay(id, name, text) {}", "onEnter")).isEmpty();
    }

    @Test
    @DisplayName("why a script does not parse is said in one line; a script that parses has no problem")
    void parseProblem() {
        assertThat(ItemScriptExecutor.parseProblem("function onEnter() { world.emit('narrate', { text: 'hi' ) }"))
            .isNotNull().containsIgnoringCase("SyntaxError").doesNotContain("\n");
        assertThat(ItemScriptExecutor.parseProblem("function onEnter() { world.emit('narrate', { text: 'hi' }); }")).isNull();
        assertThat(ItemScriptExecutor.parseProblem("   ")).isEqualTo("the script is empty");
    }
}
