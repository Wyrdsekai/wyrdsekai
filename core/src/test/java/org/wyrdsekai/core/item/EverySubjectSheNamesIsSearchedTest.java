package org.wyrdsekai.core.item;

import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A reading on "iaido and bushido relationship practice philosophy history" searched once, over
 * fourteen million chunks, and got a page of bushido; the library's own iaido article never made
 * the set, so the summary said the sources held nothing on iaido (2026-09-25). The library card
 * now searches each subject a query names on its own and keeps its best hits among the sources.
 */
class EverySubjectSheNamesIsSearchedTest {

    private static String script() throws Exception {
        Field f = ToolItemStarterKit.class.getDeclaredField("LIBRARY_CARD_SCRIPT");
        f.setAccessible(true);
        return (String) f.get(null);
    }

    /** A fake world: the combined query finds only bushido; "iaido" alone finds the article. */
    private static final String WORLD = """
        var searches = [];
        var analyzed = null;
        var world = {
          library: {
            search: function (q, limit) {
              searches.push(q);
              var ql = String(q).toLowerCase();
              if (ql === "iaido") return [{id: "wiki-iaido", score: 9.0, title: "Iaido"}];
              if (ql === "bushido") return [{id: "wiki-bushido", score: 9.0, title: "Bushido"}];
              if (ql.indexOf("bushido") >= 0) return [
                {id: "wiki-bushido", score: 12.0, title: "Bushido"},
                {id: "hagakure", score: 10.0, title: "Hagakure"},
                {id: "nitobe", score: 8.0, title: "Bushido: The Soul of Japan"}];
              return [];
            },
            read: function (id) {
              var t = {"wiki-iaido": "Iaido is a Japanese martial art that focuses on drawing a sword.",
                       "wiki-bushido": "Bushido is the samurai code of honour.",
                       "hagakure": "Hagakure is a practical guide for samurai.",
                       "nitobe": "Nitobe's 1905 book shaped how the West heard bushido."}[id];
              return t ? {title: id, text: t, pack: "simple-wikipedia"} : null;
            }
          },
          llm: { analyze: function (combined, instruction) { analyzed = {combined: combined, instruction: instruction}; return "summary"; } }
        };
        """;

    private record Run(String findings, List<String> sources, List<String> searches, String combined, String instruction) {}

    private static Run run(String query) throws Exception {
        try (var ctx = Context.newBuilder("js").allowAllAccess(true).build()) {
            ctx.eval("js", WORLD + script());
            var res = ctx.eval("js", "invoke({query: " + q(query) + "})");
            var sources = new ArrayList<String>();
            var sv = res.getMember("sources");
            for (long i = 0; i < sv.getArraySize(); i++) sources.add(sv.getArrayElement(i).asString());
            var searches = new ArrayList<String>();
            var ss = ctx.eval("js", "searches");
            for (long i = 0; i < ss.getArraySize(); i++) searches.add(ss.getArrayElement(i).asString());
            var an = ctx.eval("js", "analyzed");
            return new Run(res.getMember("findings").asString(), sources, searches,
                an.isNull() ? null : an.getMember("combined").asString(),
                an.isNull() ? null : an.getMember("instruction").asString());
        }
    }

    private static String q(String s) { return "\"" + s.replace("\"", "\\\"") + "\""; }

    @Test
    @DisplayName("each subject a query names is searched on its own, and its best hit reaches the summariser")
    void twoSubjects() throws Exception {
        var r = run("iaido and bushido relationship practice philosophy history");
        assertThat(r.searches()).contains("iaido", "bushido relationship practice philosophy history");
        assertThat(String.join(" ", r.sources())).contains("wiki-iaido").contains("wiki-bushido");
        assertThat(r.combined()).contains("drawing a sword");
        assertThat(r.instruction()).contains("more than one subject").contains("iaido");
    }

    @Test
    @DisplayName("a query of one subject is searched once, as before")
    void oneSubject() throws Exception {
        var r = run("bushido code of the samurai history origins meaning");
        assertThat(r.searches()).hasSize(1);
        assertThat(r.instruction()).doesNotContain("more than one subject");
        assertThat(String.join(" ", r.sources())).contains("wiki-bushido");
    }

    @Test
    @DisplayName("a subject with no hits takes nothing from the others")
    void emptySubject() throws Exception {
        var r = run("kendo and bushido");
        assertThat(r.searches()).contains("kendo", "bushido");
        assertThat(String.join(" ", r.sources())).contains("wiki-bushido");
        assertThat(r.findings()).isEqualTo("summary");
    }
}
