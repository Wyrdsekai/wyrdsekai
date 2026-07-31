package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the 5-layer ActionTriage pipeline.
 */
class ActionTriageTest {

    // ── Layer 1: Always-include ──────────────────────────────────────

    @Test
    void layer1_always_includes_core_actions() {
        var selected = new LinkedHashSet<String>();
        var ctx = makeCtx("hello", "room_speech", false, null);
        ActionTriage.layer1AlwaysInclude(selected, ctx);

        assertThat(selected).contains("tell_agent", "go_to_room", "remember", "emote");
        assertThat(selected).contains("task_plan"); // no active plan
    }

    @Test
    void layer1_includes_plan_actions_when_plan_active() {
        var selected = new LinkedHashSet<String>();
        var ctx = makeCtx("do next step", "plan_advance", true, "Search for books");
        ActionTriage.layer1AlwaysInclude(selected, ctx);

        assertThat(selected).contains("goal_done", "modify_plan", "abandon_plan");
        assertThat(selected).doesNotContain("task_plan"); // already has a plan
    }

    // ── Layer 2: Structural context ─────────────────────────────────

    @Test
    void layer2_adds_search_actions_for_player_tell() {
        var selected = new LinkedHashSet<String>();
        var ctx = makeCtx("find something", "player_tell", false, null);
        ActionTriage.layer2StructuralContext(selected, ctx);

        assertThat(selected).contains("web_search", "library_search", "read_content");
    }

    @Test
    void layer2_adds_room_specific_actions_for_library() {
        var selected = new LinkedHashSet<String>();
        var ctx = new ActionTriage.TriageContext(
            "search", "room_speech", null, "library",
            true, false, null, 0, false, 6, false, null);
        ActionTriage.layer2StructuralContext(selected, ctx);

        assertThat(selected).contains("library_search", "read_content", "examine");
    }

    @Test
    void layer2_adds_plan_goal_specific_actions() {
        var selected = new LinkedHashSet<String>();
        var ctx = makeCtx("execute goal", "plan_advance", true, "Search the web for news");
        ActionTriage.layer2StructuralContext(selected, ctx);

        assertThat(selected).contains("web_search");
    }

    @Test
    void layer2_adds_question_actions() {
        var selected = new LinkedHashSet<String>();
        var ctx = new ActionTriage.TriageContext(
            "何ですか？", "room_speech", null, "nexus",
            false, false, null, 0, false, 10, true, null);
        ActionTriage.layer2StructuralContext(selected, ctx);

        // Question mark detected → search actions added (language-agnostic)
        assertThat(selected).contains("web_search", "library_search");
    }

    @Test
    void layer2_adds_study_actions_in_study_room() {
        var selected = new LinkedHashSet<String>();
        var ctx = new ActionTriage.TriageContext(
            "write something", "room_speech", null, "study-player-1",
            true, false, null, 1, false, 15, false, null);
        ActionTriage.layer2StructuralContext(selected, ctx);

        assertThat(selected).contains("write_journal", "read_journal", "write_text");
    }

    // ── Layer 3A: BM25 scoring ──────────────────────────────────────

    @Test
    void layer3a_bm25_finds_relevant_actions() {
        var selected = new LinkedHashSet<String>();
        var ctx = makeCtx("search the web for news about japan", "player_tell", false, null);
        ActionTriage.layer3aBm25(selected, ctx);

        // BM25 should match "web search" and "news" terms
        assertThat(selected).containsAnyOf("web_search", "library_search", "read_content");
    }

    @Test
    void layer3a_bm25_handles_empty_trigger() {
        var selected = new LinkedHashSet<String>();
        var ctx = makeCtx("", "room_speech", false, null);
        ActionTriage.layer3aBm25(selected, ctx);
        // Should not crash, may add nothing
    }

    // ── Layer 3B: Domain classification ─────────────────────────────

    @Test
    void layer3b_classifies_player_tell_domains() {
        var ctx = makeCtx("build something", "player_tell", false, null);
        var domains = ActionTriage.classifyDomains(ctx);

        assertThat(domains).contains("communication", "search", "planning");
    }

    @Test
    void layer3b_classifies_autonomy_domains() {
        var ctx = makeCtx("idle thoughts", "autonomy", false, null);
        var domains = ActionTriage.classifyDomains(ctx);

        assertThat(domains).contains("self", "social", "observation");
    }

    @Test
    void layer3b_classifies_long_messages_as_analysis() {
        var ctx = new ActionTriage.TriageContext(
            "x".repeat(100), "room_speech", null, "nexus",
            false, false, null, 0, false, 100, false, null);
        var domains = ActionTriage.classifyDomains(ctx);

        assertThat(domains).contains("analysis", "planning");
    }

    // ── Full pipeline ───────────────────────────────────────────────

    @Test
    void full_pipeline_produces_reasonable_action_count() {
        var ctx = makeCtx("find me a book about japanese mythology", "player_tell", false, null);
        var result = ActionTriage.select(ctx);

        assertThat(result.size()).isBetween(8, 18);
        // Critical actions always present
        assertThat(result).contains("tell_agent", "go_to_room", "remember");
        // Search actions present for a search request
        assertThat(result).containsAnyOf("web_search", "library_search");
    }

    @Test
    void full_pipeline_includes_plan_actions_during_plan_advance() {
        var ctx = makeCtx("navigate to library", "plan_advance", true, "Go to the Library");
        var result = ActionTriage.select(ctx);

        assertThat(result).contains("go_to_room", "goal_done");
    }

    @Test
    void full_pipeline_respects_tier_filtering() {
        // Tier 0 agent should not see tier 2+ actions
        var ctx = new ActionTriage.TriageContext(
            "create a room", "player_tell", null, "nexus",
            false, false, null, 0, false, 13, false, null);
        var result = ActionTriage.select(ctx);

        assertThat(result).doesNotContain("create_room"); // tier 3
        assertThat(result).doesNotContain("think_deeply"); // tier 2
    }

    @Test
    void full_pipeline_caps_at_max_actions() {
        // Even with many signals, should not exceed MAX_ACTIONS
        var ctx = new ActionTriage.TriageContext(
            "search library web create craft trade navigate explore examine",
            "player_tell", "mas", "library",
            true, true, "Search for everything",
            3, true, 80, true, null);
        var result = ActionTriage.select(ctx);

        assertThat(result.size()).isLessThanOrEqualTo(18);
    }

    @Test
    void full_pipeline_handles_japanese_trigger() {
        // Language-agnostic: structural signals should still work
        var ctx = new ActionTriage.TriageContext(
            "日本のニュースを探してください", "player_tell", "mas", "nexus",
            false, false, null, 1, false, 15, false, null);
        var result = ActionTriage.select(ctx);

        // Layer 1 always-include should be there regardless of language
        assertThat(result).contains("tell_agent", "go_to_room", "remember");
        // Layer 2: player_tell adds search actions
        assertThat(result).contains("web_search", "library_search");
        // Should have a reasonable count
        assertThat(result.size()).isGreaterThanOrEqualTo(8);
    }

    // ── Emotional-context detection ─────────────────────────────────

    @Test
    void emotional_context_detects_grief_lexical_signal() {
        var ctx = makeCtx("My old companion from the eastern zone is gone. I miss them terribly.",
            "player_tell", false, null);
        assertThat(ActionTriage.isEmotionalContext(ctx)).isTrue();
    }

    @Test
    void emotional_context_detects_overwhelm() {
        var ctx = makeCtx("I've been having a really rough day and feeling overwhelmed",
            "player_tell", false, null);
        assertThat(ActionTriage.isEmotionalContext(ctx)).isTrue();
    }

    @Test
    void emotional_context_ignores_neutral_search_request() {
        var ctx = makeCtx("find me a book about mythology", "player_tell", false, null);
        assertThat(ActionTriage.isEmotionalContext(ctx)).isFalse();
    }

    @Test
    void emotional_context_ignores_workshop_navigation() {
        var ctx = makeCtx("go to the workshop and tell me what templates are available to craft",
            "player_tell", false, null);
        assertThat(ActionTriage.isEmotionalContext(ctx)).isFalse();
    }

    @Test
    void emotional_context_ignores_empty_trigger() {
        var ctx = makeCtx(null, "autonomy", false, null);
        assertThat(ActionTriage.isEmotionalContext(ctx)).isFalse();
    }

    @Test
    void emotional_context_triggers_on_elevated_grief_drive() {
        // Grief > 0.6 threshold → emotional context regardless of text
        var griefDrives = DriveState.initial().spikeGrief(0.8);
        var ctx = new ActionTriage.TriageContext(
            "anything", "autonomy", null, "nexus",
            false, false, null, 1, false, 8, false, griefDrives);
        assertThat(ActionTriage.isEmotionalContext(ctx)).isTrue();
    }

    @Test
    void emotional_context_not_triggered_by_baseline_drives() {
        // Default/baseline drives should NOT trigger emotional mode
        var ctx = new ActionTriage.TriageContext(
            "find me a book", "player_tell", null, "nexus",
            false, false, null, 1, false, 13, false, DriveState.initial());
        assertThat(ActionTriage.isEmotionalContext(ctx)).isFalse();
    }

    @Test
    void emotional_context_handles_dismiss_without_false_positive() {
        // "dismiss" contains "miss" but shouldn't trigger grief detection
        var ctx = makeCtx("dismiss the previous plan and start over",
            "player_tell", false, null);
        assertThat(ActionTriage.isEmotionalContext(ctx)).isFalse();
    }

    // ── §3.6 voice register hint ────────────────────────────────────

    @Test
    void emotionalContextVoiceHint_returns_null_when_not_emotional() {
        var ctx = makeCtx("find me a book", "player_tell", false, null);
        assertThat(ActionTriage.emotionalContextVoiceHint(ctx)).isNull();
    }

    @Test
    void emotionalContextVoiceHint_returns_hint_on_grief() {
        var ctx = makeCtx("I miss them so much", "player_tell", false, null);
        var hint = ActionTriage.emotionalContextVoiceHint(ctx);
        assertThat(hint).isNotNull();
        assertThat(hint).contains("EMOTIONAL CONTEXT");
        assertThat(hint).contains("presence-of-care");
        assertThat(hint).contains("be with the bondholder");
    }

    @Test
    void emotionalContextVoiceHint_returns_hint_on_overwhelm() {
        var ctx = makeCtx("I'm so overwhelmed", "player_tell", false, null);
        var hint = ActionTriage.emotionalContextVoiceHint(ctx);
        assertThat(hint).isNotNull();
        assertThat(hint).contains("empathic mode");
    }

    @Test
    void emotionalContextVoiceHint_handles_null_context() {
        assertThat(ActionTriage.emotionalContextVoiceHint(null)).isNull();
    }

    // ── Existing buildContext helper ────────────────────────────────

    @Test
    void build_context_helper_works() {
        var plan = TaskPlan.create("p1", "test", "u1", "mas",
            List.of("Step 1", "Step 2"));
        var ctx = ActionTriage.buildContext(
            "find books", "player_tell", "mas",
            "library", true, plan, 1, true);

        assertThat(ctx.triggerText()).isEqualTo("find books");
        assertThat(ctx.triggerSource()).isEqualTo("player_tell");
        assertThat(ctx.activePlanExists()).isTrue();
        assertThat(ctx.activePlanGoal()).isEqualTo("Step 1");
        assertThat(ctx.roomId()).isEqualTo("library");
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private ActionTriage.TriageContext makeCtx(String trigger, String source,
                                                boolean hasPlan, String planGoal) {
        return new ActionTriage.TriageContext(
            trigger, source, null, "nexus",
            false, hasPlan, planGoal,
            1, // tier 1 (observant)
            false,
            trigger != null ? trigger.length() : 0,
            trigger != null && trigger.contains("?"),
            null // drives — existing tests don't exercise drive-aware paths
        );
    }
}
