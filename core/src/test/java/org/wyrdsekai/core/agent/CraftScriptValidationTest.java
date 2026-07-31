package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.scripting.sandbox.ItemScriptExecutor;

import static org.assertj.core.api.Assertions.assertThat;

/** Craft script parse-gate (second-node 2026-07-09) — prose in the `script` param must not
 *  produce an item that dies with SyntaxError on use. */
class CraftScriptValidationTest {

    @Test
    void real_js_passes() {
        assertThat(ItemScriptExecutor.isParseableJs(
            "function invoke(params) {\nreturn { ok: world.web.search(params.query) };\n}")).isTrue();
    }

    @Test
    void prose_fails() {
        assertThat(ItemScriptExecutor.isParseableJs(
            "function invoke(params) {\nWhen invoked, this item queries the web using a provided "
            + "query and returns a summary of current results.\n}")).isFalse();
    }

    @Test
    void blank_fails() {
        assertThat(ItemScriptExecutor.isParseableJs("")).isFalse();
        assertThat(ItemScriptExecutor.isParseableJs(null)).isFalse();
    }
}
