package org.wyrdsekai.core.item;

import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The person's words reach a search query inside a marked span (the library's cull keeps what is
 * inside it). When the web has nothing, the glass says what was asked without the marks: live
 * 2026-10-01 06:02 the line was "No web results found for: … ⟦mia look information ppl having
 * issues ubuntu upgrade⟧".
 */
class SearchingGlassSaysTheQuestionPlainlyTest {

    @Test
    void the_no_results_line_has_no_marks() {
        var script = ToolItemStarterKit.searchingGlass().script();
        try (var ctx = Context.newBuilder("js").allowAllAccess(false).build()) {
            ctx.eval("js", "var world = { web: { search: function () { return []; } } };");
            ctx.eval("js", script);
            var out = ctx.eval("js", "invoke({ query: 'Ubuntu 26.04 upgrade issues ⟦mia look information ubuntu upgrade⟧', type: 'news' }).findings");
            assertThat(out.asString()).isEqualTo(
                "No web results found for: Ubuntu 26.04 upgrade issues mia look information ubuntu upgrade");
        }
    }
}
