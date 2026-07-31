package org.wyrdsekai.core.codemode;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.scripting.codemode.CodeModeExecutor;
import org.wyrdsekai.scripting.codemode.CodeModeExecutor.WorkbenchTier;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * b — workbench tier flag controls timeout + byte cap.
 *
 * <p>Asserts:
 * <ul>
 *   <li>IMPROVISATION tier: 5s timeout, 4KB cap.</li>
 *   <li>REFINEMENT tier (workbench): 30s timeout, 16KB cap.</li>
 *   <li>Improvisation rejects scripts &gt; 4KB with a clean error.</li>
 *   <li>Refinement accepts scripts up to 16KB.</li>
 *   <li>Both reject scripts &gt; their tier's cap.</li>
 * </ul>
 */
class WorkbenchTierTest {

    @Test
    void improvisation_tier_caps_at_4KB_and_5s() {
        assertThat(WorkbenchTier.IMPROVISATION.timeoutMs()).isEqualTo(5_000);
        assertThat(WorkbenchTier.IMPROVISATION.maxScriptBytes()).isEqualTo(4 * 1024);
        assertThat(WorkbenchTier.IMPROVISATION.journalLabel()).isEqualTo("improvisation");
    }

    @Test
    void refinement_tier_caps_at_16KB_and_30s() {
        assertThat(WorkbenchTier.REFINEMENT.timeoutMs()).isEqualTo(30_000);
        assertThat(WorkbenchTier.REFINEMENT.maxScriptBytes()).isEqualTo(16 * 1024);
        assertThat(WorkbenchTier.REFINEMENT.journalLabel()).isEqualTo("refinement");
    }

    @Test
    void improvisation_rejects_oversized_script() {
        // 5KB — exceeds the 4KB improvisation cap.
        var script = "/* " + "x".repeat(5 * 1024) + " */";
        var result = CodeModeExecutor.run(script, Map.of(), WorkbenchTier.IMPROVISATION);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("script too large");
        assertThat(result.error()).contains("4096");
    }

    @Test
    void refinement_accepts_8KB_script_that_improvisation_rejects() {
        // 8KB — well within the 16KB refinement cap, but over the 4KB improv cap.
        var pad = "x".repeat(8 * 1024 - 50);
        var script = "/* " + pad + " */ console.log('ok');";

        var refinementResult = CodeModeExecutor.run(script, Map.of(), WorkbenchTier.REFINEMENT);
        assertThat(refinementResult.success()).isTrue();
        assertThat(refinementResult.log()).contains("ok");

        var improvResult = CodeModeExecutor.run(script, Map.of(), WorkbenchTier.IMPROVISATION);
        assertThat(improvResult.success()).isFalse();
        assertThat(improvResult.error()).contains("script too large");
    }

    @Test
    void refinement_rejects_oversized_script() {
        var script = "/* " + "x".repeat(20 * 1024) + " */";
        var result = CodeModeExecutor.run(script, Map.of(), WorkbenchTier.REFINEMENT);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("script too large");
        assertThat(result.error()).contains("16384");
    }

    @Test
    void null_tier_defaults_to_improvisation() {
        // 5KB — over improv cap, would pass refinement.
        var script = "/* " + "x".repeat(5 * 1024) + " */";
        var result = CodeModeExecutor.run(script, Map.of(), (WorkbenchTier) null);
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("script too large");
    }

    @Test
    void backward_compat_3arg_run_imposes_no_byte_cap() {
        // Pre-Phase-2a callers can still pass any size; only timeout matters.
        var script = "console.log('hello');";
        var result = CodeModeExecutor.run(script, Map.of(), 1_000L);
        assertThat(result.success()).isTrue();
    }
}
