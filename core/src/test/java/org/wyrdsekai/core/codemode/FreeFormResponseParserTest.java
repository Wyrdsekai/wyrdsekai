package org.wyrdsekai.core.codemode;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Track A Phase 2b — free-form response parser contract.
 *
 * <p>Asserts:
 * <ul>
 *   <li>```js fenced blocks extract correctly.</li>
 *   <li>```javascript fenced blocks extract correctly.</li>
 *   <li>Surrounding narration is captured separately.</li>
 *   <li>Multiple blocks: only the first runs; the rest land in
 *       {@code extraBlocks()} for warn-logging.</li>
 *   <li>No JS block → miss (so the JSON-action path stays in charge).</li>
 *   <li>Empty / null input → miss.</li>
 *   <li>Plain ``` (no language tag) → miss (we don't steal action-JSON traffic).</li>
 * </ul>
 */
class FreeFormResponseParserTest {

    @Test
    void extracts_js_fenced_block() {
        var input =
            "Let me check both at once.\n"
            + "```js\n"
            + "const a = library_card.search(\"x\");\n"
            + "console.log(a.length);\n"
            + "```\n"
            + "I found some sources.";

        var out = FreeFormCodeModeParser.parse(input);

        assertThat(out.hasScript()).isTrue();
        assertThat(out.script())
            .contains("library_card.search(\"x\")")
            .contains("console.log(a.length)");
        assertThat(out.narration())
            .contains("Let me check both at once")
            .contains("I found some sources");
        assertThat(out.extraBlocks()).isEmpty();
    }

    @Test
    void extracts_javascript_fenced_block() {
        var input =
            "comparing now\n"
            + "```javascript\n"
            + "const a = library_card.search(\"a\");\n"
            + "const b = searching_glass.search(\"a\");\n"
            + "console.log(`${a.length} vs ${b.length}`);\n"
            + "```";

        var out = FreeFormCodeModeParser.parse(input);

        assertThat(out.hasScript()).isTrue();
        assertThat(out.script()).contains("searching_glass.search");
    }

    @Test
    void extracts_uppercase_lang_tag() {
        var input = "```JS\nconsole.log('hi');\n```";

        var out = FreeFormCodeModeParser.parse(input);

        assertThat(out.hasScript()).isTrue();
        assertThat(out.script()).isEqualTo("console.log('hi');");
    }

    @Test
    void multiple_js_blocks_runs_first_warns_rest() {
        var input =
            "```js\nconsole.log('first');\n```\n"
            + "and a second one\n"
            + "```js\nconsole.log('second');\n```\n"
            + "and a third\n"
            + "```js\nconsole.log('third');\n```";

        var out = FreeFormCodeModeParser.parse(input);

        assertThat(out.hasScript()).isTrue();
        assertThat(out.script()).isEqualTo("console.log('first');");
        assertThat(out.extraBlocks()).hasSize(2);
        assertThat(out.extraBlocks().get(0)).isEqualTo("console.log('second');");
        assertThat(out.extraBlocks().get(1)).isEqualTo("console.log('third');");
    }

    @Test
    void no_js_block_returns_miss() {
        var out = FreeFormCodeModeParser.parse("Plain prose with no scripts at all.");

        assertThat(out.hasScript()).isFalse();
        assertThat(out.script()).isNull();
        assertThat(out.extraBlocks()).isEmpty();
    }

    @Test
    void plain_triple_backtick_is_not_extracted() {
        // Spec §A4 — only ```js / ```javascript blocks are stolen by free-form.
        // A plain ``` block could be JSON, prose, or markup, and the existing
        // JSON-action parser already handles those.
        var input = "```\n{\"action\":\"library_card\"}\n```";
        var out = FreeFormCodeModeParser.parse(input);

        assertThat(out.hasScript()).isFalse();
    }

    @Test
    void json_lang_tag_is_not_extracted() {
        var input = "```json\n{\"action\":\"library_card\"}\n```";
        var out = FreeFormCodeModeParser.parse(input);

        assertThat(out.hasScript()).isFalse();
    }

    @Test
    void empty_input_returns_miss() {
        assertThat(FreeFormCodeModeParser.parse("").hasScript()).isFalse();
        assertThat(FreeFormCodeModeParser.parse("   \n\n   ").hasScript()).isFalse();
    }

    @Test
    void null_input_returns_miss() {
        var out = FreeFormCodeModeParser.parse(null);
        assertThat(out.hasScript()).isFalse();
    }

    @Test
    void narration_strips_extra_block_bodies() {
        // If the narration after the first block contains a second block,
        // its source must NOT leak into the narration string (otherwise the
        // dispatcher would speak raw JS as prose).
        var input =
            "Let me try both approaches.\n"
            + "```js\nconsole.log('first');\n```\n"
            + "Now another:\n"
            + "```js\nconsole.log('second');\n```\n"
            + "Done.";

        var out = FreeFormCodeModeParser.parse(input);

        assertThat(out.narration())
            .contains("Let me try both approaches")
            .contains("Done")
            .doesNotContain("console.log('second')")
            .doesNotContain("```");
    }

    @Test
    void has_javascript_block_convenience_returns_boolean() {
        assertThat(FreeFormCodeModeParser.hasJavaScriptBlock("```js\ncode;\n```")).isTrue();
        assertThat(FreeFormCodeModeParser.hasJavaScriptBlock("plain prose")).isFalse();
        assertThat(FreeFormCodeModeParser.hasJavaScriptBlock(null)).isFalse();
    }

    @Test
    void empty_js_block_extracts_empty_script() {
        // Edge case: ```js\n``` with nothing inside. Parser should still
        // detect it (the dispatcher decides what to do — likely nothing).
        var out = FreeFormCodeModeParser.parse("```js\n\n```");

        assertThat(out.hasScript()).isTrue();
        // The extracted script should be empty after strip.
        assertThat(out.script()).isEmpty();
    }

    @Test
    void script_strip_removes_leading_trailing_whitespace() {
        var input = "```js\n   \n  console.log('x');  \n   \n```";
        var out = FreeFormCodeModeParser.parse(input);

        assertThat(out.hasScript()).isTrue();
        assertThat(out.script()).startsWith("console.log");
        assertThat(out.script()).endsWith(";");
    }
}
