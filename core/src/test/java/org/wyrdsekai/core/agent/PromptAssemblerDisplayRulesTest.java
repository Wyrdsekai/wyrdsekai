package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.model.Entity;
import org.wyrdsekai.common.model.Exit;
import org.wyrdsekai.common.model.RoomObject;
import org.wyrdsekai.common.model.RoomSnapshot;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * integration smoke for the
 * {@link DisplayRulesContext} → {@link PromptAssembler} (Layer 2.5)
 * composition. Verifies that when the active bondholder's preference
 * resolves to a non-empty cultural-register block, the assembled prompt
 * carries that block as a system message in the additional-context slot;
 * conversely, that the override path correctly suppresses guidance.
 *
 * <p>This is a smoke test on the assembler boundary — it doesn't drive the
 * live CompanionActor → AccountStore → DisplayRulesContext pipeline (that
 * full path is exercised in {@code CulturalRegisterPersistenceTest} for
 * persistence and {@code DisplayRulesContextTest} for resolution). Here
 * we verify the last leg: the resolved block lands in the prompt.</p>
 */
class PromptAssemblerDisplayRulesTest {

    private static final AgentProfile PROFILE = new AgentProfile(
        "Wyrd", "wyrd-companion", "agent", "A companion",
        "You are a helpful companion.", 4096, 512, 0.7
    );

    private static final RoomSnapshot NEXUS = new RoomSnapshot(
        "nexus", "The Nexus", "A shimmering hub of connections.", "foundation",
        List.of(new Exit("east", "terminal", "The Terminal")),
        List.of(new Entity("player-1", "Alice", "player", "")),
        List.of(new RoomObject("crystal", "Nexus Crystal", "A glowing crystal.", false)),
        List.of()
    );

    @Test void ja_jp_bondholder_no_override_emits_ja_block_in_assembled_prompt() {
        var block = DisplayRulesContext.forBondholder("ja-JP", null).orElseThrow();
        var messages = PromptAssembler.assemble(
            PROFILE, NEXUS, List.of(), null,
            VitalityState.initial(), List.of(),
            block, null);

        var systemContents = messages.stream()
            .filter(m -> "system".equals(m.role()))
            .map(m -> m.content())
            .toList();

        assertThat(systemContents)
            .anyMatch(s -> s.contains("Japanese-context")
                && s.contains("Honne/tatemae"));
    }

    @Test void anglo_override_suppresses_block_in_assembled_prompt() {
        // Override "anglo" returns empty → no block, so additionalContext is null
        // (the way CompanionActor's combineAdditionalContext handles a null).
        var block = DisplayRulesContext.forBondholder("ja-JP", "anglo").orElse(null);
        assertThat(block).as("override anglo must suppress JA guidance").isNull();

        var messages = PromptAssembler.assemble(
            PROFILE, NEXUS, List.of(), null,
            VitalityState.initial(), List.of(),
            block, null);

        var systemContents = messages.stream()
            .filter(m -> "system".equals(m.role()))
            .map(m -> m.content())
            .toList();

        // No system message should carry the JA-block markers.
        assertThat(systemContents)
            .noneMatch(s -> s.contains("Honne/tatemae"));
    }

    @Test void no_language_or_override_emits_no_block() {
        var block = DisplayRulesContext.forBondholder(null, null).orElse(null);
        assertThat(block).isNull();

        var messages = PromptAssembler.assemble(
            PROFILE, NEXUS, List.of(), null,
            VitalityState.initial(), List.of(),
            block, null);

        var systemContents = messages.stream()
            .filter(m -> "system".equals(m.role()))
            .map(m -> m.content())
            .toList();

        // None of the language-specific block markers should appear.
        for (var marker : new String[]{
                "Honne/tatemae", "Saudade-aware", "amorcito",
                "해요체/해체", "face-preservation"}) {
            assertThat(systemContents).noneMatch(s -> s.contains(marker));
        }
    }

    @Test void en_us_bondholder_with_japanese_formal_override_emits_ja_block() {
        // The reverse-kikokushijo path: account is en-US but the bondholder
        // explicitly asked for Japanese register. Override must win.
        var block = DisplayRulesContext.forBondholder("en-US", "japanese-formal").orElseThrow();
        var messages = PromptAssembler.assemble(
            PROFILE, NEXUS, List.of(), null,
            VitalityState.initial(), List.of(),
            block, null);

        var systemContents = messages.stream()
            .filter(m -> "system".equals(m.role()))
            .map(m -> m.content())
            .toList();

        assertThat(systemContents).anyMatch(s -> s.contains("Japanese-context"));
    }
}
