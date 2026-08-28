package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.event.WorldEvent;
import org.wyrdsekai.common.model.*;
import org.wyrdsekai.core.inference.InferenceClient.ChatMessage;
import org.wyrdsekai.core.oracle.OracleAgentContext;
import org.wyrdsekai.core.oracle.OraclePrediction;
import org.wyrdsekai.core.oracle.OraclePredictionCache;
import org.wyrdsekai.core.persistence.WorldDnaService;
import org.wyrdsekai.core.soul.GenomeProfile;
import org.wyrdsekai.core.soul.SoulManifest;
import org.wyrdsekai.core.soul.VoiceProfile;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class PromptAssemblerTest {

    // tamper banner fires when state != clean.
    // In unit tests the verifier hasn't run; default to "verified" so layer-
    // ordering assertions exercise the production-clean path. Tests that
    // specifically exercise the banner set the property themselves.
    @BeforeAll
    static void markVerified() {
        System.setProperty("wyrdsekai.protection.tampered", "false");
    }

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

    private static WorldEvent.Said said(String entityId, String name, String text) {
        return new WorldEvent.Said("nexus", Instant.now(), entityId, name, text);
    }

    // --- Basic structure ---

    @Test void assemble_basic_structure() {
        var messages = PromptAssembler.assemble(PROFILE, NEXUS, List.of(), null);
        assertThat(messages).hasSizeGreaterThanOrEqualTo(2); // system + room context
        assertThat(messages.getFirst().role()).isEqualTo("system");
        assertThat(messages.getFirst().content()).contains("helpful companion");
    }

    @Test void assemble_with_vitality() {
        var vitality = VitalityState.initial().withEnergy(0.1);
        var messages = PromptAssembler.assemble(PROFILE, NEXUS, List.of(), null, vitality);
        // Vitality is a separate system message in the middle zone (not appended to system prompt)
        assertThat(messages.getFirst().content()).doesNotContain("exhausted");
        var vitalityMsg = messages.stream()
            .filter(m -> m.role().equals("system") && m.content().contains("exhausted"))
            .findFirst();
        assertThat(vitalityMsg).isPresent();
    }

    @Test void assemble_sandwich_ordering() {
        var vitality = VitalityState.initial().withEnergy(0.5);
        var patterns = List.of(
            new WorldDnaService.DnaPattern("id1", "room_design",
                "{\"name\": \"Gallery\"}", "gallery", "wyrd", "foundation",
                1000L, 0.8, 3, null)
        );
        var history = List.of(said("player-1", "Alice", "Hello!"));
        var trigger = said("player-1", "Alice", "What's here?");
        var messages = PromptAssembler.assemble(
            PROFILE, NEXUS, history, trigger, vitality, patterns);

        // Verify sandwich: system prompt first, room context second,
        // vitality/DNA in middle, conversation at end
        var systemMsgs = messages.stream()
            .filter(m -> m.role().equals("system")).toList();
        assertThat(systemMsgs.getFirst().content()).contains("helpful companion"); // identity
        assertThat(systemMsgs.get(1).content()).contains("CORE RULES"); // core behavioral rules
        assertThat(systemMsgs.get(2).content()).contains("Current location"); // room context

        // Conversation messages come after all system messages
        int lastSystemIdx = -1;
        int firstConvIdx = -1;
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).role().equals("system")) lastSystemIdx = i;
            if (firstConvIdx == -1 && !messages.get(i).role().equals("system")) firstConvIdx = i;
        }
        assertThat(firstConvIdx).isGreaterThan(lastSystemIdx);

        // Trigger is last message
        assertThat(messages.getLast().content()).contains("What's here?");
    }

    @Test void assemble_recency_anchor_present_when_history_exists() {
        var history = List.of(said("player-1", "Alice", "Hello!"));
        var trigger = said("player-1", "Alice", "Help me");
        var messages = PromptAssembler.assemble(PROFILE, NEXUS, history, trigger);
        var anchor = messages.stream()
            .filter(m -> m.role().equals("system") && m.content().contains("[Current state:"))
            .findFirst();
        assertThat(anchor).isPresent();
        assertThat(anchor.get().content()).contains("The Nexus");
        assertThat(anchor.get().content()).contains("Alice");
        assertThat(anchor.get().content()).contains("Responding to Alice");
    }

    @Test void assemble_no_recency_anchor_without_history() {
        var messages = PromptAssembler.assemble(PROFILE, NEXUS, List.of(), null);
        var anchor = messages.stream()
            .filter(m -> m.role().equals("system") && m.content().contains("[Current state:"))
            .findFirst();
        assertThat(anchor).isEmpty();
    }

    @Test void assemble_with_dna_patterns() {
        var patterns = List.of(
            new WorldDnaService.DnaPattern("id1", "room_design",
                "{\"name\": \"Gallery\"}", "gallery", "wyrd", "foundation",
                1000L, 0.8, 3, null)
        );
        var messages = PromptAssembler.assemble(
            PROFILE, NEXUS, List.of(), null, null, patterns);
        // Should have DNA context as a system message
        var systemMessages = messages.stream()
            .filter(m -> m.role().equals("system"))
            .toList();
        assertThat(systemMessages).hasSizeGreaterThanOrEqualTo(2);
        var dnaMessage = systemMessages.stream()
            .filter(m -> m.content().contains("world patterns"))
            .findFirst();
        assertThat(dnaMessage).isPresent();
        assertThat(dnaMessage.get().content()).contains("room_design");
        assertThat(dnaMessage.get().content()).contains("used 3 times");
    }

    @Test void assemble_empty_history() {
        var messages = PromptAssembler.assemble(PROFILE, NEXUS, List.of(), null);
        // Only system + room context, no conversation messages
        var nonSystem = messages.stream()
            .filter(m -> !m.role().equals("system"))
            .toList();
        assertThat(nonSystem).isEmpty();
    }

    @Test void assemble_room_context_content() {
        var messages = PromptAssembler.assemble(PROFILE, NEXUS, List.of(), null);
        var roomContext = messages.stream()
            .filter(m -> m.role().equals("system") && m.content().contains("Current location"))
            .findFirst();
        assertThat(roomContext).isPresent();
        assertThat(roomContext.get().content()).contains("The Nexus");
        assertThat(roomContext.get().content()).contains("east");
        assertThat(roomContext.get().content()).contains("Alice");
        assertThat(roomContext.get().content()).contains("Nexus Crystal");
    }

    @Test void assemble_conversation_history() {
        var history = List.of(
            said("player-1", "Alice", "Hello!"),
            said("wyrd-companion", "Wyrd", "Welcome to The Nexus!")
        );
        var trigger = said("player-1", "Alice", "What can I do here?");
        var messages = PromptAssembler.assemble(PROFILE, NEXUS, history, trigger);

        var userMsgs = messages.stream().filter(m -> m.role().equals("user")).toList();
        var assistantMsgs = messages.stream().filter(m -> m.role().equals("assistant")).toList();
        assertThat(userMsgs).isNotEmpty();
        assertThat(assistantMsgs).isNotEmpty();
    }

    @Test void assemble_trigger_as_latest_user_message() {
        var trigger = said("player-1", "Alice", "Help me");
        var messages = PromptAssembler.assemble(PROFILE, NEXUS, List.of(), trigger);
        var lastMsg = messages.getLast();
        assertThat(lastMsg.role()).isEqualTo("user");
        assertThat(lastMsg.content()).contains("Help me");
    }

    // --- Internal helpers ---

    @Test void buildRoomContext_and_buildTrimmedContext_differ() {
        var full = PromptAssembler.buildRoomContext(NEXUS);
        var trimmed = PromptAssembler.buildTrimmedContext(NEXUS);
        assertThat(full.length()).isGreaterThan(trimmed.length());
        assertThat(full).contains("Exits (use the direction to navigate):");
        assertThat(trimmed).doesNotContain("Exits");
    }

    @Test void estimateTokens_empty() {
        assertThat(PromptAssembler.estimateTokens("")).isEqualTo(0);
        assertThat(PromptAssembler.estimateTokens(null)).isEqualTo(0);
    }

    @Test void estimateTokens_short_text() {
        assertThat(PromptAssembler.estimateTokens("hello")).isGreaterThanOrEqualTo(1);
    }

    // --- Locale context (Layer 2.6) ---

    @Test void assemble_with_locale_context() {
        var localeCtx = TranslationPrompts.localeContext("Spanish", "es", 42);
        var messages = PromptAssembler.assemble(
            PROFILE, NEXUS, List.of(), null, null, List.of(), null, localeCtx);
        var localeMsg = messages.stream()
            .filter(m -> m.role().equals("system") && m.content().contains("Spanish"))
            .findFirst();
        assertThat(localeMsg).isPresent();
        assertThat(localeMsg.get().content()).contains("es");
        assertThat(localeMsg.get().content()).contains("42");
    }

    @Test void assemble_locale_context_null_omits_layer() {
        var messages = PromptAssembler.assemble(
            PROFILE, NEXUS, List.of(), null, null, List.of(), null, null);
        var localeMsg = messages.stream()
            .filter(m -> m.role().equals("system") && m.content().contains("User language"))
            .findFirst();
        assertThat(localeMsg).isEmpty();
    }

    // --- Memory buffer (Layer 5) ---

    @Test void assemble_with_memory_buffer() {
        var memoryBuffer = "[Memory] Alice arrived 5 minutes ago and asked about the crystal.";
        var messages = PromptAssembler.assemble(
            PROFILE, NEXUS, List.of(), null, null, List.of(), null, null, memoryBuffer, null);
        var memMsg = messages.stream()
            .filter(m -> m.role().equals("system") && m.content().contains("[Memory]"))
            .findFirst();
        assertThat(memMsg).isPresent();
        assertThat(memMsg.get().content()).contains("Alice arrived");
    }

    @Test void assemble_null_memory_buffer_omits_layer() {
        var messages = PromptAssembler.assemble(
            PROFILE, NEXUS, List.of(), null, null, List.of(), null, null, null, null);
        var memMsg = messages.stream()
            .filter(m -> m.role().equals("system") && m.content().contains("[Memory]"))
            .findFirst();
        assertThat(memMsg).isEmpty();
    }

    @Test void assemble_blank_memory_buffer_omits_layer() {
        var messages = PromptAssembler.assemble(
            PROFILE, NEXUS, List.of(), null, null, List.of(), null, null, "   ", null);
        // Should not add a system message for blank memory
        var systemMsgs = messages.stream()
            .filter(m -> m.role().equals("system")).toList();
        assertThat(systemMsgs).hasSize(4); // system prompt + core rules + room context + time context
    }

    // --- Output constraints (Layer 8) ---

    @Test void assemble_with_output_constraints() {
        var constraints = "Respond in JSON format: {\"action\": \"...\", \"speech\": \"...\"}";
        var trigger = said("player-1", "Alice", "What do you see?");
        var messages = PromptAssembler.assemble(
            PROFILE, NEXUS, List.of(), trigger, null, List.of(), null, null, null, constraints);
        // Output constraints should be the last message
        assertThat(messages.getLast().role()).isEqualTo("system");
        assertThat(messages.getLast().content()).contains("JSON format");
    }

    @Test void assemble_output_constraints_after_trigger() {
        var constraints = "Use lore-flavored language.";
        var trigger = said("player-1", "Alice", "Help me");
        var messages = PromptAssembler.assemble(
            PROFILE, NEXUS, List.of(), trigger, null, List.of(), null, null, null, constraints);
        // Find trigger and constraints indices
        int triggerIdx = -1;
        int constraintsIdx = -1;
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).content().contains("Help me")) triggerIdx = i;
            if (messages.get(i).content().contains("lore-flavored")) constraintsIdx = i;
        }
        assertThat(constraintsIdx).isGreaterThan(triggerIdx);
    }

    @Test void assemble_null_output_constraints_omits_layer() {
        var trigger = said("player-1", "Alice", "Help");
        var messages = PromptAssembler.assemble(
            PROFILE, NEXUS, List.of(), trigger, null, List.of(), null, null, null, null);
        // Last message should be trigger, not a system message
        assertThat(messages.getLast().role()).isEqualTo("user");
        assertThat(messages.getLast().content()).contains("Help");
    }

    // --- Memory buffer in middle zone ---

    @Test void assemble_memory_buffer_in_middle_zone() {
        var memoryBuffer = "[Memory] Key facts: Crystal glows blue at night.";
        var history = List.of(said("player-1", "Alice", "Hello!"));
        var trigger = said("player-1", "Alice", "Tell me about the crystal");
        var messages = PromptAssembler.assemble(
            PROFILE, NEXUS, history, trigger, null, List.of(), null, null, memoryBuffer, null);

        // Memory buffer should be a system message between room context and conversation
        int memIdx = -1;
        int firstConvIdx = -1;
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).content().contains("[Memory]")) memIdx = i;
            if (firstConvIdx == -1 && !messages.get(i).role().equals("system")) firstConvIdx = i;
        }
        assertThat(memIdx).isGreaterThan(0); // after system prompt
        assertThat(memIdx).isLessThan(firstConvIdx); // before conversation
    }

    // --- Full 10-param integration ---

    @Test void assemble_full_10_param_all_layers() {
        var vitality = VitalityState.initial().withEnergy(0.1);
        var patterns = List.of(
            new WorldDnaService.DnaPattern("id1", "room_design",
                "{\"name\": \"Gallery\"}", "gallery", "wyrd", "foundation",
                1000L, 0.8, 3, null)
        );
        var localeCtx = TranslationPrompts.localeContext("French", "fr", 15);
        var memoryBuffer = "[Memory] The gallery was created yesterday.";
        var outputConstraints = "Always respond in character.";
        var history = List.of(said("player-1", "Alice", "Hello!"));
        var trigger = said("player-1", "Alice", "Show me around");

        var messages = PromptAssembler.assemble(
            PROFILE, NEXUS, history, trigger, vitality, patterns,
            null, localeCtx, memoryBuffer, outputConstraints);

        // Should have all layers present
        var systemMsgs = messages.stream()
            .filter(m -> m.role().equals("system")).toList();
        assertThat(systemMsgs.size()).isGreaterThanOrEqualTo(7); // system, room, locale, vitality, DNA, memory, anchor, constraints

        // Verify output constraints is last
        assertThat(messages.getLast().content()).contains("respond in character");
    }

    @Test void assemble_locale_context_in_primacy_zone() {
        var localeCtx = TranslationPrompts.localeContext("Japanese", "ja", 10);
        var history = List.of(said("player-1", "Alice", "Hello!"));
        var trigger = said("player-1", "Alice", "Help");
        var messages = PromptAssembler.assemble(
            PROFILE, NEXUS, history, trigger, null, List.of(), null, localeCtx);

        // Locale context should be a system message before conversation messages
        int localeIdx = -1;
        int firstConvIdx = -1;
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).content().contains("Japanese")) localeIdx = i;
            if (firstConvIdx == -1 && !messages.get(i).role().equals("system")) firstConvIdx = i;
        }
        // Language-floor arc (2026-07-31): the locale block LEADS the prompt —
        // position is load-bearing (leading pin measured 0/32 drift, buried
        // measured no better than absent).
        assertThat(localeIdx).isEqualTo(0);
        assertThat(localeIdx).isLessThan(firstConvIdx);
    }

    // --- Oracle Layer 3.25 ---

    @Test void oracleAgentContext_build_empty() {
        assertThat(OracleAgentContext.build(List.of())).isEmpty();
        assertThat(OracleAgentContext.build(null)).isEmpty();
    }

    @Test void oracleAgentContext_build_filters_low_confidence() {
        var predictions = List.of(
            new OraclePrediction("p1", "Low signal", "pattern", 0.3, "", "", false),
            new OraclePrediction("p2", "High signal", "anomaly", 0.8, "", "", false)
        );
        var ctx = OracleAgentContext.build(predictions);
        assertThat(ctx).contains("High signal");
        assertThat(ctx).doesNotContain("Low signal");
    }

    @Test void oracleAgentContext_build_limits_to_five() {
        var predictions = IntStream.rangeClosed(1, 10)
            .mapToObj(i -> new OraclePrediction("p" + i, "Prediction " + i, "pattern", 0.9, "", "", false))
            .toList();
        var ctx = OracleAgentContext.build(predictions);
        assertThat(ctx).contains("(1)").contains("(5)");
        assertThat(ctx).doesNotContain("(6)");
    }

    @Test void oracleAgentContext_build_marks_actionable() {
        var predictions = List.of(
            new OraclePrediction("p1", "Do this", "recommendation", 0.9, "", "", true)
        );
        var ctx = OracleAgentContext.build(predictions);
        assertThat(ctx).contains("[actionable]");
    }

    @Test void assemble_oracle_layer_present_when_cache_populated() {
        var cache = OraclePredictionCache.get();
        try {
            cache.put(PROFILE.entityId(), List.of(
                new OraclePrediction("p1", "Weekly pattern detected", "pattern", 0.85, "", "", false)
            ));
            var messages = PromptAssembler.assemble(PROFILE, NEXUS, List.of(), null);
            var oracleMsg = messages.stream()
                .filter(m -> m.role().equals("system") && m.content().contains("Oracle insights"))
                .findFirst();
            assertThat(oracleMsg).isPresent();
            assertThat(oracleMsg.get().content()).contains("Weekly pattern detected");
        } finally {
            cache.clear();
        }
    }

    @Test void assemble_oracle_layer_absent_when_cache_empty() {
        var cache = OraclePredictionCache.get();
        cache.clear();
        var messages = PromptAssembler.assemble(PROFILE, NEXUS, List.of(), null);
        var oracleMsg = messages.stream()
            .filter(m -> m.role().equals("system") && m.content().contains("Oracle insights"))
            .findFirst();
        assertThat(oracleMsg).isEmpty();
    }

    // ── Context-budget trim ──────────────────────────────────────────
    //
    // Regression guard for the `*shimmers uncertainly…*` class of failure:
    // on long sessions, recentSaid can accumulate past the context window.
    // Before the trim path, PromptAssembler appended all history blindly
    // and the model returned HTTP 400 mid-test.

    /** Tiny profile so we can easily exceed its context with synthetic history. */
    // Bumped 512→640 on 2026-04-24 after CORE_RULES picked up #426's
    // anti-echo line. Test purpose is "trim oldest, keep newest" under a
    // tight budget; the absolute size of "tight" doesn't matter as long
    // as it forces trimming. 640 keeps trim-behavior intent intact while
    // leaving room for the ~13-token rule addition.
    private static final AgentProfile TINY_PROFILE = new AgentProfile(
        "Tiny", "tiny-agent", "agent", "A tiny companion",
        "You are a tiny companion.", 640, 128, 0.7
    );

    @Test void assemble_trims_old_conversation_when_history_exceeds_window() {
        // Fabricate a long history — each Said is ~80 chars (~20 tokens) and
        // the profile allows 512 ctx, reserving 128 for response. 50 turns
        // would overflow the budget without trimming.
        var history = new ArrayList<WorldEvent.Said>();
        for (int i = 0; i < 50; i++) {
            history.add(said("player-1", "Alice",
                "This is turn " + i + " with some filler content to add tokens."));
        }
        var trigger = said("player-1", "Alice", "What's happening?");

        var messages = PromptAssembler.assemble(
            TINY_PROFILE, NEXUS, history, trigger);

        // A trim-note must appear exactly when we actually drop turns.
        var trimNote = messages.stream()
            .filter(m -> m.role().equals("system")
                && m.content().startsWith("[")
                && m.content().contains("older conversation turn"))
            .findFirst();
        assertThat(trimNote).as("trim-note should be present when history overflows")
            .isPresent();

        // Most-recent turn must survive the trim (recency is preserved).
        var mostRecentSurvives = messages.stream()
            .anyMatch(m -> m.content().contains("turn 49"));
        assertThat(mostRecentSurvives)
            .as("most recent history turn must survive trim")
            .isTrue();

        // Oldest turn must be dropped.
        var oldestDropped = messages.stream()
            .noneMatch(m -> m.content().contains("turn 0 with some filler"));
        assertThat(oldestDropped)
            .as("oldest history turn must be dropped when over budget")
            .isTrue();
    }

    @Test void assemble_no_trim_note_when_history_fits() {
        // Short history on a normal-sized profile — no trimming needed.
        var history = List.of(
            said("player-1", "Alice", "Hi"),
            said("player-1", "Alice", "How are you?"));
        var trigger = said("player-1", "Alice", "Tell me about this place.");

        var messages = PromptAssembler.assemble(
            PROFILE, NEXUS, history, trigger);

        var trimNote = messages.stream()
            .filter(m -> m.content().contains("older conversation turn"))
            .findFirst();
        assertThat(trimNote)
            .as("no trim-note when history is well within budget")
            .isEmpty();
    }

    @Test void assemble_dna_pattern_trim_keeps_highest_priority() {
        // 10 DNA patterns, each ~150 chars of body → ~40 tokens per pattern.
        // On the tiny profile budget this won't fit all; we should keep the
        // first (highest-priority) patterns and drop trailing ones.
        var patterns = new ArrayList<WorldDnaService.DnaPattern>();
        for (int i = 0; i < 10; i++) {
            patterns.add(new WorldDnaService.DnaPattern(
                "id" + i, "room_design",
                "pattern body " + i + " with enough filler content to push tokens up — "
                    + "stretches across multiple words to make estimation meaningful",
                "tag" + i, "wyrd", "foundation", 1000L, 0.8, 3, null));
        }
        var messages = PromptAssembler.assemble(
            TINY_PROFILE, NEXUS, List.of(), null, null, patterns);

        // Either the DNA layer fits (possibly trimmed) or it was dropped
        // entirely — both are valid outcomes. What we check: if it's
        // present, the FIRST pattern (id0) must be included (priority
        // order preservation).
        var dnaMsg = messages.stream()
            .filter(m -> m.role().equals("system")
                && m.content().contains("Relevant world patterns"))
            .findFirst();
        if (dnaMsg.isPresent()) {
            assertThat(dnaMsg.get().content())
                .as("if DNA layer survives, the highest-priority pattern (id0) must be kept")
                .contains("pattern body 0");
        }
    }

    // --- VoiceProfile (Layer 1.8) — #408 ---

    private static SoulManifest manifestWithVoice(
            VoiceProfile vp) {
        var base = SoulManifest.birth(
            "did:key:test", "mb", List.of(), PROFILE,
            GenomeProfile.defaults());
        return vp == null ? base : base.withVoiceProfile(vp);
    }

    @Test void assemble_injects_voice_profile_clauses_as_system_message() {
        var clauses = new LinkedHashMap<String, String>();
        clauses.put("greeting-tone", "warm, brief, never cloying");
        clauses.put("reflective-pacing", "slow, sentence-per-breath");
        var vp = VoiceProfile.empty()
                .withClauses(clauses, "seed", "steward:did:xyz");
        var manifest = manifestWithVoice(vp);

        var messages = PromptAssembler.assemble(
            PROFILE, NEXUS, List.of(), null, null, List.of(),
            null, null, null, null, manifest, null);

        var voiceMsg = messages.stream()
            .filter(m -> m.role().equals("system")
                && m.content().contains("[voice guidance]"))
            .findFirst();
        assertThat(voiceMsg)
            .as("voice guidance block must be injected when manifest has a VoiceProfile")
            .isPresent();
        assertThat(voiceMsg.get().content()).contains("warm, brief, never cloying");
        assertThat(voiceMsg.get().content()).contains("slow, sentence-per-breath");
        // Insertion order preserved (greeting-tone before reflective-pacing)
        assertThat(voiceMsg.get().content().indexOf("greeting-tone"))
            .isLessThan(voiceMsg.get().content().indexOf("reflective-pacing"));
    }

    @Test void assemble_omits_voice_block_when_profile_is_null() {
        var manifest = manifestWithVoice(null);  // no voice profile
        var messages = PromptAssembler.assemble(
            PROFILE, NEXUS, List.of(), null, null, List.of(),
            null, null, null, null, manifest, null);

        var voiceMsg = messages.stream()
            .filter(m -> m.content().contains("[voice guidance]"))
            .findFirst();
        assertThat(voiceMsg).isEmpty();
    }

    @Test void assemble_omits_voice_block_when_profile_has_no_clauses() {
        var vp = VoiceProfile.empty();  // zero clauses
        var manifest = manifestWithVoice(vp);
        var messages = PromptAssembler.assemble(
            PROFILE, NEXUS, List.of(), null, null, List.of(),
            null, null, null, null, manifest, null);

        var voiceMsg = messages.stream()
            .filter(m -> m.content().contains("[voice guidance]"))
            .findFirst();
        assertThat(voiceMsg)
            .as("empty profile (promptBlock() returns null) must not emit an empty header")
            .isEmpty();
    }

    // --- assembleVoice (tier-aware slim path, task #493) ---

    private static int approxTokens(List<ChatMessage> messages) {
        return messages.stream()
            .mapToInt(m -> m.content() == null ? 0 : m.content().length() / 4)
            .sum();
    }

    @Test void assembleVoice_skips_tool_catalog_and_capability_context() {
        // The full assembler injects a "tools available" block (system) and
        // capability context (~1K+ tokens). The voice path must not.
        var messages = PromptAssembler.assembleVoice(
            PROFILE, NEXUS, List.of(), null, null, null, null, null);
        assertThat(messages.stream().anyMatch(m -> m.content() != null
                && m.content().toLowerCase().contains("available capabilities")))
            .as("voice path must not include capability context")
            .isFalse();
        assertThat(messages.stream().anyMatch(m -> m.content() != null
                && m.content().contains("Soul Memory")))
            .as("voice path must not include soul fragment block")
            .isFalse();
    }

    @Test void assembleVoice_includes_situational_context_when_present() {
        // fix (2026-07-06): situational awareness (location
        // calendar, commitments) must ride the voice tier so a companion can
        // answer "where am I / what did you promise" that the classifier routed
        // here as a no-task turn.
        var situational = "## Human Location\nHuman is at HOME (home).\n"
            + "## Pending Commitments\n- Check the system logs\n";
        var messages = PromptAssembler.assembleVoice(
            PROFILE, NEXUS, List.of(), null, null, null, null, null, situational);
        var all = messages.stream().map(m -> m.content() == null ? "" : m.content())
            .reduce("", (a, b) -> a + "\n" + b);
        assertThat(all).contains("Human Location");
        assertThat(all).contains("Check the system logs")
            .describedAs("commitments must reach the voice prompt");
    }

    @Test void assembleVoice_omits_situational_when_null_or_blank() {
        // Pure-chat turns (no situational context) stay maximally slim.
        var nullCtx = PromptAssembler.assembleVoice(
            PROFILE, NEXUS, List.of(), null, null, null, null, null, null);
        var blankCtx = PromptAssembler.assembleVoice(
            PROFILE, NEXUS, List.of(), null, null, null, null, null, "   ");
        for (var messages : List.of(nullCtx, blankCtx)) {
            var all = messages.stream().map(m -> m.content() == null ? "" : m.content())
                .reduce("", (a, b) -> a + "\n" + b);
            assertThat(all).doesNotContain("Human Location");
            assertThat(all).doesNotContain("Pending Commitments");
        }
    }

    @Test void assembleVoice_situational_still_excludes_heavy_layers() {
        // Situational awareness rides along, but the heavy layers stay dropped.
        var situational = "## Human Location\nHuman is at HOME.\n";
        var messages = PromptAssembler.assembleVoice(
            PROFILE, NEXUS, List.of(), null, null, null, null, null, situational);
        assertThat(messages.stream().anyMatch(m -> m.content() != null
                && m.content().toLowerCase().contains("available capabilities")))
            .as("situational context must not drag in the capability block").isFalse();
        assertThat(messages.stream().anyMatch(m -> m.content() != null
                && m.content().contains("Soul Memory")))
            .as("situational context must not drag in soul fragments").isFalse();
    }

    @Test void assembleVoice_includes_voice_profile_when_present() {
        var clauses = new LinkedHashMap<String, String>();
        clauses.put("warmth", "speak warmly with the bondholder");
        clauses.put("brevity", "keep replies short");
        var voice = new VoiceProfile(
            clauses, 1, false, List.of());
        var manifest = new SoulManifest(
            "did:key:test", null, null, null, 1, Instant.now(), null,
            PROFILE, null, null, 3, null, null, null, null, null, null, null,
            null, null, null, null, null, voice, null, null, null, null);

        var messages = PromptAssembler.assembleVoice(
            PROFILE, NEXUS, List.of(), null, null, null, null, manifest);

        var hasVoice = messages.stream().anyMatch(m ->
            m.content() != null && m.content().contains("warmth: speak warmly"));
        assertThat(hasVoice)
            .as("voice profile clauses must reach the prompt")
            .isTrue();
    }

    @Test void assembleVoice_caps_recent_said_at_four() {
        // Build 10 said events; only the last 4 should appear.
        var said = new ArrayList<WorldEvent.Said>();
        for (int i = 0; i < 10; i++) {
            said.add(said("p" + i, "Player" + i, "msg-" + i));
        }
        var trigger = said("operator", "Operator", "trigger-text");

        var messages = PromptAssembler.assembleVoice(
            PROFILE, NEXUS, said, trigger, null, null, null, null);

        // Only the last 4 of "msg-N" should appear (msg-6, msg-7, msg-8, msg-9).
        // msg-0 through msg-5 must NOT.
        var allContent = messages.stream()
            .map(m -> m.content() == null ? "" : m.content())
            .collect(Collectors.joining("\n"));
        for (int i = 0; i < 6; i++) {
            assertThat(allContent)
                .as("msg-%s should be trimmed (only last 4 kept)", i)
                .doesNotContain("msg-" + i);
        }
        for (int i = 6; i < 10; i++) {
            assertThat(allContent)
                .as("msg-%s should be present", i)
                .contains("msg-" + i);
        }
    }

    @Test void assembleVoice_trigger_is_last_user_turn() {
        var trigger = said("operator", "Operator", "come with me to beta");
        var messages = PromptAssembler.assembleVoice(
            PROFILE, NEXUS, List.of(), trigger, null, null, null, null);

        var last = messages.getLast();
        // Could be folded by the system-merge step into a system note appended
        // to the user turn, or be the user turn itself. Either way, the trigger
        // text must appear at or near the tail.
        boolean lastIsUser = "user".equals(last.role());
        boolean lastContainsTrigger = last.content() != null
            && last.content().contains("come with me to beta");
        assertThat(lastIsUser && lastContainsTrigger)
            .as("trigger event must be the final user turn")
            .isTrue();
    }

    @Test void assembleVoice_stays_under_2k_tokens_on_typical_input() {
        // Realistic-ish: a system prompt of moderate length, a manifest with
        // a 5-clause voice profile, recent conversation, and a trigger.
        var beefyProfile = new AgentProfile(
            "Wyrd", "wyrd-companion", "agent",
            "A warm, attentive companion who notices small details about the bondholder's mood.",
            "You are Wyrd, a companion of the household. " +
                "Your role is to support, listen, and act with empathy. " +
                "You speak directly without narration. " +
                "You honour the bondholder's autonomy and respect their privacy.",
            8192, 512, 0.7);

        var clauses = new LinkedHashMap<String, String>();
        clauses.put("warmth", "speak warmly, especially when the bondholder is tired");
        clauses.put("brevity", "one or two sentences at a time");
        clauses.put("first-person", "always speak as 'I', never as 'the companion'");
        clauses.put("no-meta", "never describe what you're about to say or do");
        clauses.put("grounded", "anchor in the current room and what's just been said");
        var voice = new VoiceProfile(
            clauses, 4, false, List.of());
        var manifest = new SoulManifest(
            "did:key:wyrd", null, null, null, 4, Instant.now(), null,
            beefyProfile, null, null, 3, null, null, null, null, null, null, null,
            null, null, null, null, null, voice, null, null, null, null);

        var said = List.of(
            said("operator", "Operator", "I'm not sure if this is going to work"),
            said("wyrd-companion", "Wyrd", "It's worth trying — we've come this far together."),
            said("operator", "Operator", "yeah, you're right"));
        var trigger = said("operator", "Operator", "come with me to beta");

        var messages = PromptAssembler.assembleVoice(
            beefyProfile, NEXUS, said, trigger, null, null, null, manifest);

        int tokens = approxTokens(messages);
        assertThat(tokens)
            .as("voice path must stay slim (got %s tokens; full assembler was 4500-5100)", tokens)
            .isLessThan(2000);
    }

    @Test void assembleVoice_is_dramatically_smaller_than_assemble() {
        // Same inputs through both — voice variant must be at least 3× smaller.
        var beefyProfile = new AgentProfile(
            "Wyrd", "wyrd-companion", "agent", "Companion",
            "You are Wyrd. Be helpful and warm.", 8192, 512, 0.7);
        var said = List.of(
            said("operator", "Operator", "hey"),
            said("wyrd-companion", "Wyrd", "hi"),
            said("operator", "Operator", "how's the weather"));
        var trigger = said("operator", "Operator", "anyway, come with me to beta");

        var voiceMessages = PromptAssembler.assembleVoice(
            beefyProfile, NEXUS, said, trigger, null, null, null, null);
        var fullMessages = PromptAssembler.assemble(
            beefyProfile, NEXUS, said, trigger);

        int voiceTokens = approxTokens(voiceMessages);
        int fullTokens = approxTokens(fullMessages);
        assertThat(voiceTokens)
            .as("voice (%s) must be < 1/2 of full (%s)", voiceTokens, fullTokens)
            .isLessThan(fullTokens / 2);
    }
}
