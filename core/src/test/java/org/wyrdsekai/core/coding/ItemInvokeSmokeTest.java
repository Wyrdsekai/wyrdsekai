package org.wyrdsekai.core.coding;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.scripting.api.ItemManifest;
import org.wyrdsekai.scripting.api.ItemManifestParser;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Invoke-once smoke: real scripts through the REAL sandbox + stub
 * provider — no mocked executor, because the whole point is proving the
 * code path executes where the manifest checks could not.
 */
class ItemInvokeSmokeTest {

    private static ItemManifest manifestOf(String script) {
        return ItemManifestParser.parse(script);
    }

    private static final String HEADER = """
        exports.manifest = {
          name: "smoke-test-item",
          version: "1.0",
          description: "test",
          commands: [{label: "use", args: ""}],
          embodiment: {form: "trinket", presence: "a small test trinket"},
          params: [{name: "sides", type: "number", description: "d", required: false}]
        };
        """;

    @Test
    void healthy_item_passes() {
        var script = HEADER + """
            function invoke(params) {
              var sides = params.sides || 6;
              return {result: "rolled a " + (1 + (sides - 1))};
            }
            """;
        var r = ItemInvokeSmoke.run("healthy", script, manifestOf(script));
        assertThat(r.verdict()).isEqualTo(ItemInvokeSmoke.Verdict.PASS);
    }

    @Test
    void own_code_crash_is_rejected() {
        // The class of bug the smoke exists for: loader-valid, manifest-valid,
        // dies the moment invoke() runs.
        var script = HEADER + """
            function invoke(params) {
              return {result: undefinedHelper(params.sides)};
            }
            """;
        var r = ItemInvokeSmoke.run("crasher", script, manifestOf(script));
        assertThat(r.verdict())
            .as("a ReferenceError on first touch must REJECT — registering it "
                + "guarantees breakage in a person's hands")
            .isEqualTo(ItemInvokeSmoke.Verdict.REJECT);
        assertThat(r.detail()).contains("undefinedHelper");
    }

    @Test
    void infinite_loop_is_rejected() {
        // The stub provider answers instantly, so a timeout can only mean
        // the item's own code never returns.
        var script = HEADER + """
            function invoke(params) {
              while (true) { var x = 1; }
            }
            """;
        var r = ItemInvokeSmoke.run("looper", script, manifestOf(script));
        assertThat(r.verdict()).isEqualTo(ItemInvokeSmoke.Verdict.REJECT);
    }

    @Test
    void world_api_calls_survive_the_stub() {
        // Items that reach into provider surfaces get harmless empties from
        // StubItemWorldApiProvider — a legitimate knowledge-searching item
        // must NOT be rejected just because the stub returns nothing.
        var script = HEADER + """
            function invoke(params) {
              var hits = world.knowledge.search("anything", 3);
              return {result: "found " + hits.length + " hits"};
            }
            """;
        var r = ItemInvokeSmoke.run("searcher", script, manifestOf(script));
        assertThat(r.verdict())
            .as("stub gives empty results, not errors — legitimate items pass")
            .isIn(ItemInvokeSmoke.Verdict.PASS, ItemInvokeSmoke.Verdict.INCONCLUSIVE);
        assertThat(r.verdict()).isNotEqualTo(ItemInvokeSmoke.Verdict.REJECT);
    }

    @Test
    void placeholder_params_follow_declared_types() {
        var script = """
            exports.manifest = {
              name: "typed", version: "1", description: "d",
              commands: [{label: "use", args: ""}],
              embodiment: {form: "trinket", presence: "p"},
              params: [
                {name: "count", type: "number", description: "d", required: true},
                {name: "label", type: "string", description: "d", required: true},
                {name: "loud", type: "boolean", description: "d", required: false}
              ]
            };
            function invoke(p) { return {ok: true}; }
            """;
        var params = ItemInvokeSmoke.placeholderParams(manifestOf(script));
        assertThat(params).containsEntry("count", 1)
            .containsEntry("label", "test")
            .containsEntry("loud", true);
    }

    @Test
    void classify_maps_executor_error_shapes() {
        assertThat(ItemInvokeSmoke.classify(Map.of("error", "Item script timed out")).verdict())
            .isEqualTo(ItemInvokeSmoke.Verdict.REJECT);
        assertThat(ItemInvokeSmoke.classify(Map.of("error", "Script error: TypeError: x")).verdict())
            .isEqualTo(ItemInvokeSmoke.Verdict.REJECT);
        assertThat(ItemInvokeSmoke.classify(
                Map.of("error", "cap denied", "capability_denied", "web")).verdict())
            .isEqualTo(ItemInvokeSmoke.Verdict.INCONCLUSIVE);
        assertThat(ItemInvokeSmoke.classify(Map.of("error", "Execution error: host oops")).verdict())
            .isEqualTo(ItemInvokeSmoke.Verdict.INCONCLUSIVE);
        assertThat(ItemInvokeSmoke.classify(Map.of("result", "ok")).verdict())
            .isEqualTo(ItemInvokeSmoke.Verdict.PASS);
    }
}
