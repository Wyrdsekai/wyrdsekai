package org.wyrdsekai.core.coding;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.item.ScriptedItemLoader;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A function the runtime cannot reach is not an entrypoint.
 *
 * <h2>The live failure this pins</h2>
 * 2026-08-21, household node. A person asked for a library tool; goose wrote
 * {@code library_query.js}; the bridge refused it; a plain artifact was placed anyway,
 * carrying the manifest's own usage lines as its description. So the room told him:
 *
 * <pre>
 *   library_query
 *   Queries the library and speaks the result as a story directly to the room.
 *   Use it with — `use library_query` — Search the library and speak the result …
 * </pre>
 *
 * <p>He typed exactly that and got {@code Error [not_found]: No such object: library_query}.
 *
 * <p>The file is reproduced below verbatim in {@link #WRAPPED}. Every gate in front of it
 * passed: the manifest parsed, {@code embodiment} was present, {@code commands} had two
 * entries, and the entrypoint check — {@code script.contains("function invoke(")} — found
 * the substring, because it IS there. It is just sealed inside
 * {@code (function (exports) &#123; ... &#125;)(exports)} and never exported, so the
 * runtime, which resolves {@code invoke} from the evaluated bindings, found nothing to
 * call. The repair loop had nothing to complain about and shipped it.
 *
 * <p>Two checks answering the same question differently is the whole defect. The cheap
 * textual one still runs first (its message is better for a genuinely absent entrypoint);
 * the deciding one is now the runtime itself.
 */
class AnInvokeNobodyCanCallTest {

    /** Verbatim from /var/lib/wyrdsekai/coding-workspaces/c0854c10-…/library_query.js. */
    private static final String WRAPPED = """
        (function (exports) {
          function invoke(params) {
            if (!params || typeof params.args !== "string") {
              return { ok: false, summary: "This tool requires a query string." };
            }
            return { ok: true, summary: "Query result: " + params.args };
          }

          exports.manifest = {
            name: "library_query",
            version: "1.0.0",
            description: "Queries the library and speaks the result as a story.",
            author: "did:wyrd:openhands",
            capabilities: [],
            embodiment: {
              silent: false,
              emits: ["body_language"],
              descriptor_template: "{actor} works the tool with focused attention"
            },
            commands: [
              { label: "Search the library", args: "" },
              { label: "Search the library", args: "your-query-string" }
            ]
          };
        })(exports);
        """;

    private static final String TOP_LEVEL = """
        exports.manifest = {
          name: "library_query",
          version: "1.0.0",
          description: "Queries the library and speaks the result as a story.",
          author: "did:wyrd:goose",
          capabilities: [],
          embodiment: {
            silent: false,
            emits: ["body_language"],
            descriptor_template: "{actor} works the tool with focused attention"
          },
          commands: [ { label: "Search the library", args: "your-query-string" } ]
        };
        function invoke(params) { return { ok: true, summary: "" + params.args }; }
        """;

    /** The same item written the other legal way — as a module export. */
    private static final String EXPORTED = """
        exports.manifest = {
          name: "library_query",
          version: "1.0.0",
          description: "Queries the library and speaks the result as a story.",
          author: "did:wyrd:goose",
          capabilities: [],
          embodiment: {
            silent: false,
            emits: ["body_language"],
            descriptor_template: "{actor} works the tool with focused attention"
          },
          commands: [ { label: "Search the library", args: "your-query-string" } ]
        };
        exports.invoke = function (params) { return { ok: true, summary: "" + params.args }; };
        """;

    @Test
    void the_textual_check_is_fooled_by_the_wrapper() {
        // Not a complaint about hasEntrypoint — this is WHY the strict gate cannot be it.
        assertThat(ScriptedItemLoader.hasEntrypoint(WRAPPED)).isTrue();
    }

    @Test
    void a_closure_sealed_invoke_is_refused() {
        var problems = ItemContractCheck.problems(WRAPPED, "library_query.js");
        assertThat(problems).isNotEmpty();
        assertThat(String.join(" ", problems))
            .contains("no CALLABLE invoke()");
        assertThat(ItemContractCheck.isCompliant(WRAPPED, "library_query.js")).isFalse();
    }

    /** The complaint has to tell the backend what to actually change. */
    @Test
    void the_complaint_names_the_wrapper_and_the_fix() {
        var problem = ItemContractCheck.firstProblem(WRAPPED, "library_query.js")
            .orElseThrow();
        assertThat(problem).contains("(function (exports)");
        assertThat(problem).contains("TOP LEVEL");
        assertThat(problem).contains("exports.invoke");
    }

    @Test
    void a_top_level_invoke_passes() {
        assertThat(ItemContractCheck.problems(TOP_LEVEL, "library_query.js")).isEmpty();
    }

    /**
     * {@code exports.invoke} was accepted by every gate and then died on first use,
     * because the executor only ever read the global bindings. The contract already
     * speaks this idiom for {@code exports.manifest}; both halves have to work.
     */
    @Test
    void an_exported_invoke_passes() {
        assertThat(ItemContractCheck.problems(EXPORTED, "library_query.js")).isEmpty();
    }
}
