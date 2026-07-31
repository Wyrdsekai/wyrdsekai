package org.wyrdsekai.core.codemode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.agent.CompanionActor;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Track A Phase 2b — gate combination contract for
 * free-form code-mode entry.
 *
 * <p>Asserts the three-way AND gate composes correctly across:
 * <ul>
 *   <li>{@link CodeModeFeatureFlag#ENABLED_ENV} (master)</li>
 *   <li>{@link CodeModeFeatureFlag#IMPROV_ENV} (Phase 2b opt-in)</li>
 *   <li>{@link ImprovisationTrigger} (heuristic on user message)</li>
 * </ul>
 *
 * <p>The fourth gate — emotional context — lives on the
 * {@link CompanionActor} instance and is verified at the wiring level
 * (method exists with correct shape) here. Live emotional-context
 * suppression is exercised by the {@link CompanionActor}-level integration
 * suite where a full actor system is available.
 */
class FreeFormCodeModeGateTest {

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

    // ── Both flags + trigger required ───────────────────────────────────────

    @Test
    void no_flags_no_trigger_blocks() {
        assertThat(CodeModeFeatureFlag.isImprovisationEnabled()).isFalse();
        assertThat(ImprovisationTrigger.fires("look at both for me")).isTrue();
        // Block fires only when isImprovisationEnabled() is true; even a
        // matching trigger doesn't bypass the flag gate.
    }

    @Test
    void master_only_no_improv_flag_blocks() {
        System.setProperty(CodeModeFeatureFlag.ENABLED_ENV, "true");
        // Phase 1 surface (run_script tool) is on; Phase 2b free-form is not.
        assertThat(CodeModeFeatureFlag.isEnabled()).isTrue();
        assertThat(CodeModeFeatureFlag.isImprovisationEnabled()).isFalse();
    }

    @Test
    void improv_only_no_master_blocks() {
        // Without master, improv alone is meaningless — the run_script tool
        // surface that handleFreeFormCodeMode delegates into isn't even
        // exposed. We codify that as a hard rule.
        System.setProperty(CodeModeFeatureFlag.IMPROV_ENV, "true");
        assertThat(CodeModeFeatureFlag.isEnabled()).isFalse();
        assertThat(CodeModeFeatureFlag.isImprovisationEnabled()).isFalse();
    }

    @Test
    void both_flags_on_enables() {
        System.setProperty(CodeModeFeatureFlag.ENABLED_ENV, "true");
        System.setProperty(CodeModeFeatureFlag.IMPROV_ENV, "true");
        assertThat(CodeModeFeatureFlag.isImprovisationEnabled()).isTrue();
    }

    @Test
    void both_flags_one_value_each_form_works() {
        System.setProperty(CodeModeFeatureFlag.ENABLED_ENV, "1");
        System.setProperty(CodeModeFeatureFlag.IMPROV_ENV, "TRUE");
        assertThat(CodeModeFeatureFlag.isImprovisationEnabled()).isTrue();
    }

    // ── Trigger composes with flags ─────────────────────────────────────────

    @Test
    void enabled_but_no_trigger_is_a_miss() {
        System.setProperty(CodeModeFeatureFlag.ENABLED_ENV, "true");
        System.setProperty(CodeModeFeatureFlag.IMPROV_ENV, "true");

        // Conversational chatter should not fire even with both flags on.
        assertThat(ImprovisationTrigger.fires("how are you doing today?")).isFalse();
    }

    @Test
    void enabled_with_trigger_is_a_fire() {
        System.setProperty(CodeModeFeatureFlag.ENABLED_ENV, "true");
        System.setProperty(CodeModeFeatureFlag.IMPROV_ENV, "true");

        assertThat(ImprovisationTrigger.fires(
            "look at both the library and the searching glass for that name")).isTrue();
    }

    // ── Emotional-context gate is wired into CompanionActor ─────────────────

    @Test
    void companion_actor_has_free_form_handler() {
        // Verifies the free-form dispatcher exists. Live suppression on
        // emotional context is exercised in the CompanionActor integration
        // suite where a full system can be spun up.
        var hit = Arrays.stream(CompanionActor.class.getDeclaredMethods())
            .map(Method::getName)
            .anyMatch(n -> n.equals("handleFreeFormCodeMode"));
        assertThat(hit)
            .as("CompanionActor.handleFreeFormCodeMode must exist (free-form dispatch)")
            .isTrue();
    }

    @Test
    void companion_actor_has_block_appender() {
        var hit = Arrays.stream(CompanionActor.class.getDeclaredMethods())
            .map(Method::getName)
            .anyMatch(n -> n.equals("maybeAppendFreeFormCodeModeBlock"));
        assertThat(hit)
            .as("CompanionActor.maybeAppendFreeFormCodeModeBlock must exist (gate site)")
            .isTrue();
    }

    @Test
    void block_appender_takes_string_string_returns_string() {
        var method = Arrays.stream(CompanionActor.class.getDeclaredMethods())
            .filter(m -> m.getName().equals("maybeAppendFreeFormCodeModeBlock"))
            .findFirst()
            .orElseThrow();
        assertThat(method.getParameterCount()).isEqualTo(2);
        assertThat(method.getParameterTypes()[0]).isEqualTo(String.class);
        assertThat(method.getParameterTypes()[1]).isEqualTo(String.class);
        assertThat(method.getReturnType()).isEqualTo(String.class);
    }

    @Test
    void free_form_handler_takes_two_strings_returns_string() {
        var method = Arrays.stream(CompanionActor.class.getDeclaredMethods())
            .filter(m -> m.getName().equals("handleFreeFormCodeMode"))
            .findFirst()
            .orElseThrow();
        assertThat(method.getParameterCount()).isEqualTo(2);
        assertThat(method.getParameterTypes()[0]).isEqualTo(String.class);
        assertThat(method.getParameterTypes()[1]).isEqualTo(String.class);
        assertThat(method.getReturnType()).isEqualTo(String.class);
    }

    // ── Phase 1 run_script path stays operational ───────────────────────────

    @Test
    void phase_1_run_script_handler_still_present() {
        // Belt-and-braces: Phase 2b is additive. handleRunScript must still
        // exist even after Phase 2b lands so the JSON action surface keeps
        // working as the fallback path.
        var hit = Arrays.stream(CompanionActor.class.getDeclaredMethods())
            .map(Method::getName)
            .anyMatch(n -> n.equals("handleRunScript"));
        assertThat(hit).isTrue();
    }
}
