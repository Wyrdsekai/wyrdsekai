package org.wyrdsekai.core.codemode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.scripting.codemode.CodeModeExecutor;
import org.wyrdsekai.scripting.codemode.CodeModeExecutor.WorkbenchTier;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Track A Phase 2b — integration smoke for the free-form
 * code-mode dispatch path.
 *
 * <p>The full handler ({@code CompanionActor.handleFreeFormCodeMode}) is
 * instance-bound and exercises ~14 collaborators (equipment service, lucene
 * store, inference router, scheduler, etc.). Spinning that up demands the
 * full actor harness which other tests reserve for end-to-end cases.
 *
 * <p>This test asserts the pieces compose correctly:
 * <ol>
 *   <li>Parser extracts a synthesized prose+```js response.</li>
 *   <li>The extracted script runs via {@link CodeModeExecutor} against a
 *       hand-built namespace shaped exactly like the production builder.</li>
 *   <li>The result observation has the same surface shape the production
 *       handler returns to the ReAct loop.</li>
 * </ol>
 *
 * <p>The journal-write behavior is covered by spec; the executor and
 * namespace by their own suites; the gating by
 * {@link FreeFormCodeModeGateTest}. This is the seam where they meet.
 */
class HandleFreeFormCodeModeTest {

    @BeforeEach
    void clearFlags() {
        System.clearProperty(CodeModeFeatureFlag.ENABLED_ENV);
        System.clearProperty(CodeModeFeatureFlag.IMPROV_ENV);
        System.clearProperty(CodeModeFeatureFlag.AUDIT_ONLY_ENV);
    }

    @AfterEach
    void resetFlags() {
        System.clearProperty(CodeModeFeatureFlag.ENABLED_ENV);
        System.clearProperty(CodeModeFeatureFlag.IMPROV_ENV);
        System.clearProperty(CodeModeFeatureFlag.AUDIT_ONLY_ENV);
    }

    @Test
    void parser_to_executor_roundtrip_runs_extracted_script() {
        // Synthesized "free-form" model output: prose framing + a ```js block
        // that calls a fake equipped item.
        var raw =
            "Let me look at both at once.\n"
            + "```js\n"
            + "const a = library_card.search('mythology');\n"
            + "const b = searching_glass.search('mythology');\n"
            + "console.log(`primary=${a.length} secondary=${b.length}`);\n"
            + "```\n"
            + "I found seven sources.";

        var parsed = FreeFormCodeModeParser.parse(raw);
        assertThat(parsed.hasScript()).isTrue();
        assertThat(parsed.script()).contains("library_card.search");
        assertThat(parsed.narration())
            .contains("Let me look at both at once")
            .contains("I found seven sources");

        // Build a namespace shaped like CodeModeNamespace.forActor's output —
        // the fake .search returns a list of stub results so the script can
        // execute end-to-end without a real ItemScriptExecutor.
        var libraryNs = new LinkedHashMap<String, Function<Object[], Object>>();
        libraryNs.put("search", args -> List.of(
            Map.of("title", "Edda", "summary", "Norse"),
            Map.of("title", "Theogony", "summary", "Greek")));
        var searchingGlassNs = new LinkedHashMap<String, Function<Object[], Object>>();
        searchingGlassNs.put("search", args -> List.of(
            Map.of("title", "ScholarSrc", "summary", "academic")));

        var bundle = new LinkedHashMap<String, Map<String, Function<Object[], Object>>>();
        bundle.put("library_card", libraryNs);
        bundle.put("searching_glass", searchingGlassNs);

        var result = CodeModeExecutor.run(parsed.script(), bundle, WorkbenchTier.IMPROVISATION);

        assertThat(result.success()).isTrue();
        assertThat(result.log()).hasSize(1);
        assertThat(result.log().get(0)).contains("primary=2 secondary=1");
    }

    @Test
    void parser_handles_fabricated_data_response_without_crashing() {
        // Spec §11 / Phase 2b — the prompt-block instruction is the soft
        // guard against fabrication. If the model nonetheless writes a script
        // that calls a tool that doesn't exist, the executor surfaces a
        // ReferenceError; the parser stays out of that — its only job is
        // extract+route. We assert that contract here.
        var raw =
            "Sure thing.\n"
            + "```js\n"
            + "const x = nonexistent_tool.search('foo');\n"
            + "console.log(x);\n"
            + "```";

        var parsed = FreeFormCodeModeParser.parse(raw);
        assertThat(parsed.hasScript()).isTrue();

        var result = CodeModeExecutor.run(parsed.script(), Map.of(), WorkbenchTier.IMPROVISATION);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).isNotNull();
    }

    @Test
    void parser_strips_extra_blocks_warns_on_extras() {
        // Phase 2b runs only the first block. The dispatcher warn-logs the
        // extras (asserted via the parser's extraBlocks() count) but doesn't
        // execute them.
        var raw =
            "Plan A:\n"
            + "```js\nconsole.log('plan-a');\n```\n"
            + "Plan B:\n"
            + "```js\nconsole.log('plan-b');\n```";

        var parsed = FreeFormCodeModeParser.parse(raw);

        assertThat(parsed.hasScript()).isTrue();
        assertThat(parsed.extraBlocks()).hasSize(1);

        var result = CodeModeExecutor.run(parsed.script(), Map.of(), WorkbenchTier.IMPROVISATION);
        assertThat(result.success()).isTrue();
        assertThat(result.log()).containsExactly("plan-a");
    }

    @Test
    void disabled_flags_never_route_to_executor() {
        // Belt-and-braces: even if a model emits a perfect ```js block, the
        // dispatcher must not invoke the executor when the master+improv
        // gate is closed. We assert the contract via the flag — the
        // dispatcher itself reads isImprovisationEnabled() before parsing.
        assertThat(CodeModeFeatureFlag.isImprovisationEnabled())
            .as("Default-off contract: free-form code-mode is silent unless explicitly opted in")
            .isFalse();

        // Just-master-on case: phase 1 surface (run_script tool) is on but
        // phase 2b free-form prompt-shape is not. Phase 1 stays the canonical
        // path until both flags are set.
        System.setProperty(CodeModeFeatureFlag.ENABLED_ENV, "true");
        assertThat(CodeModeFeatureFlag.isEnabled()).isTrue();
        assertThat(CodeModeFeatureFlag.isImprovisationEnabled()).isFalse();
    }
}
