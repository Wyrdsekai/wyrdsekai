package org.wyrdsekai.core.codemode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Track A Phase 2b — system-prompt hint block contract.
 *
 * <p>Verifies the static block content shape (~150 token target, namespace
 * surface, no-fabrication instruction). The decision logic — when the block
 * is admitted vs suppressed — lives in {@code CompanionActor
 * .maybeAppendFreeFormCodeModeBlock} and is exercised by
 * {@link FreeFormCodeModeGateTest} (gate combinations) and
 * {@link ImprovisationTriggerTest} (trigger heuristics).
 */
class FreeFormCodeModePromptTest {

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
    void block_text_is_stable_singleton() {
        // Same instance across calls — block is static.
        var a = FreeFormCodeModePromptBlock.text();
        var b = FreeFormCodeModePromptBlock.text();
        assertThat(a).isSameAs(b);
    }

    @Test
    void block_describes_namespace_surface() {
        var text = FreeFormCodeModePromptBlock.text();

        // Per spec §4.1 example, the block must teach equipped-item.<method>,
        // world.peek, world.listInventory, mcp.search, mcp.execute, console.log.
        assertThat(text).contains("equipped-item-alias");
        assertThat(text).contains("world.peek");
        assertThat(text).contains("world.listInventory");
        assertThat(text).contains("mcp.search");
        assertThat(text).contains("mcp.execute");
        assertThat(text).contains("console.log");
    }

    @Test
    void block_specifies_fence_format() {
        var text = FreeFormCodeModePromptBlock.text();
        assertThat(text).contains("```js");
    }

    @Test
    void block_carries_no_fabrication_instruction() {
        // Spec §11 / Phase 2b — soft guard against the JS-probe finding
        // (model "simulated" missing-API responses with fake data).
        var lower = FreeFormCodeModePromptBlock.text().toLowerCase();

        // The block must explicitly call out fabrication / simulation / faked data.
        assertThat(lower).contains("fabricate");
        // And it must condition that on missing-tool awareness (the model
        // needs to know that admitting a gap is the right move).
        assertThat(lower).containsAnyOf("don't have", "do not have", "missing");
    }

    @Test
    void block_advises_against_single_step_overuse() {
        // Spec §4.4 — code-mode is for composition, not single-tool calls.
        var text = FreeFormCodeModePromptBlock.text();
        assertThat(text.toLowerCase()).contains("single-tool");
    }

    @Test
    void block_is_under_token_target() {
        // Spec §4.2 sets the block at ~150 tokens. We use a 4-char-per-token
        // estimate (PromptAssembler.estimateTokens convention) and assert the
        // block stays well under 250 tokens — drift detection, not exact bound.
        var text = FreeFormCodeModePromptBlock.text();
        var approxTokens = text.length() / 4;
        assertThat(approxTokens).isLessThan(250);
    }

    @Test
    void improv_flag_defaults_off() {
        // Belt-and-braces: ensure the new flag doesn't accidentally enable
        // free-form when the master is off.
        assertThat(CodeModeFeatureFlag.isImprovisationEnabled()).isFalse();

        System.setProperty(CodeModeFeatureFlag.IMPROV_ENV, "true");
        // master is still off
        assertThat(CodeModeFeatureFlag.isImprovisationEnabled()).isFalse();
    }

    @Test
    void improv_flag_requires_both_master_and_improv() {
        System.setProperty(CodeModeFeatureFlag.ENABLED_ENV, "true");
        // improv off → not enabled
        assertThat(CodeModeFeatureFlag.isImprovisationEnabled()).isFalse();

        System.setProperty(CodeModeFeatureFlag.IMPROV_ENV, "true");
        assertThat(CodeModeFeatureFlag.isImprovisationEnabled()).isTrue();

        System.setProperty(CodeModeFeatureFlag.ENABLED_ENV, "false");
        assertThat(CodeModeFeatureFlag.isImprovisationEnabled()).isFalse();
    }
}
