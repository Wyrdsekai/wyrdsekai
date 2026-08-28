package org.wyrdsekai.scripting.sandbox;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which spellings of {@code invoke} this runtime will actually call — and which it won't.
 *
 * <p>The items-as-tools contract already speaks the module idiom: every item declares
 * itself with {@code exports.manifest = &#123;...&#125;}, and {@code createContext}
 * polyfills {@code exports}/{@code module} so that line evaluates. But entrypoint
 * resolution read only the global bindings, while
 * {@code ScriptedItemLoader.hasEntrypoint} — the gate in front of registration — accepts
 * the string {@code "exports.invoke ="}. An item written that way therefore passed every
 * check, registered, was placed, picked up, and answered "has no invoke() or execute()
 * function" the first time anyone used it.
 *
 * <p>And the shape that must still fail: an {@code invoke} sealed inside an IIFE. That is
 * what arrived live on 2026-08-21. Rescuing it is not possible and pretending otherwise
 * would put an unrunnable item in someone's hands — it is refused before placement now
 * (see {@code ItemContractCheck}), and this pins that the runtime genuinely cannot reach
 * it, which is why.
 */
class AnEntrypointTheRuntimeCanCallTest {

    private ItemScriptExecutor executor;
    private ItemScriptExecutorTest.MockItemWorldApiProvider provider;

    @BeforeEach
    void setUp() {
        executor = new ItemScriptExecutor();
        provider = new ItemScriptExecutorTest.MockItemWorldApiProvider();
    }

    @AfterEach
    void tearDown() {
        executor.close();
    }

    @Test
    void a_top_level_invoke_runs() {
        var out = executor.execute("top", """
            function invoke(params) { return { ok: true, saw: params.args }; }
            """, Map.of("args", "salt almanac"), provider);
        assertThat(out.get("ok")).isEqualTo(true);
        assertThat(out.get("saw")).isEqualTo("salt almanac");
    }

    @Test
    void an_exported_invoke_runs() {
        var out = executor.execute("exported", """
            exports.invoke = function (params) { return { ok: true, saw: params.args }; };
            """, Map.of("args", "salt almanac"), provider);
        assertThat(out.get("error")).isNull();
        assertThat(out.get("saw")).isEqualTo("salt almanac");
    }

    @Test
    void a_module_exported_invoke_runs() {
        var out = executor.execute("module-exported", """
            module.exports.invoke = function (params) { return { ok: true, saw: params.args }; };
            """, Map.of("args", "salt almanac"), provider);
        assertThat(out.get("error")).isNull();
        assertThat(out.get("saw")).isEqualTo("salt almanac");
    }

    @Test
    void an_exported_execute_runs() {
        var out = executor.execute("exported-execute", """
            exports.execute = function (params) { return { ok: true }; };
            """, Map.of(), provider);
        assertThat(out.get("error")).isNull();
        assertThat(out.get("ok")).isEqualTo(true);
    }

    /** The live shape. Unreachable by design of JavaScript, not by our choice. */
    @Test
    void an_invoke_sealed_in_a_closure_is_unreachable() {
        var out = executor.execute("wrapped", """
            (function (exports) {
              function invoke(params) { return { ok: true }; }
              exports.manifest = { name: "wrapped" };
            })(exports);
            """, Map.of(), provider);
        assertThat(String.valueOf(out.get("error")))
            .contains("has no invoke() or execute() function");
    }

    /** The probe the contract check asks, on the same three shapes. */
    @Test
    void the_probe_agrees_with_what_actually_runs() {
        assertThat(executor.entrypointProblem("top",
            "function invoke(p) { return {}; }", provider)).isEmpty();
        assertThat(executor.entrypointProblem("exported",
            "exports.invoke = function (p) { return {}; };", provider)).isEmpty();
        assertThat(executor.entrypointProblem("wrapped", """
            (function (exports) {
              function invoke(params) { return { ok: true }; }
              exports.manifest = { name: "wrapped" };
            })(exports);
            """, provider)).isPresent();
    }

    /** A file that does not even parse is named as such, not silently passed. */
    @Test
    void a_file_that_will_not_evaluate_is_named() {
        var problem = executor.entrypointProblem("broken",
            "function invoke(params) { return { ok: true }", provider);
        assertThat(problem).isPresent();
        assertThat(problem.get()).contains("does not evaluate");
    }
}
