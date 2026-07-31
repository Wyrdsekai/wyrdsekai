package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the {@code reconsider} meta-action — the agent's
 * step-back-and-reassess primitive.
 *
 * <p>Three layers of contract:</p>
 * <ol>
 *   <li><b>Parser</b>: {@code {"action": "reconsider", "reason": "..."}}
 *       round-trips into {@code ActionParser.AgentAction.Reconsider}.</li>
 *   <li><b>Policy</b>: registered with tier 0 + read-only so it surfaces
 *       to nascent agents and never gets gated by autonomy checks.</li>
 *   <li><b>Narrowing</b>: when the agent calls {@code reconsider}, the
 *       state-machine narrowing whitelist is replaced with the
 *       freshly-triaged surface for ONE dispatch — and {@code reconsider}
 *       itself disappears from the whitelist on subsequent iterations
 *       (capped to one call per loop, so it can't become a stalling
 *       crutch).</li>
 * </ol>
 */
class ReconsiderActionTest {

    // ── Parser ────────────────────────────────────────────────────────

    @Test
    void parser_extracts_reason() {
        var input = """
            ```json
            {"action": "reconsider", "reason": "examine didn't find anything"}
            ```
            """;
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(ActionParser.AgentAction.Reconsider.class);
        var r = (ActionParser.AgentAction.Reconsider) action;
        assertThat(r.reason()).isEqualTo("examine didn't find anything");
    }

    @Test
    void parser_accepts_blank_reason() {
        var input = """
            ```json
            {"action": "reconsider"}
            ```
            """;
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(ActionParser.AgentAction.Reconsider.class);
        assertThat(((ActionParser.AgentAction.Reconsider) action).reason()).isEmpty();
    }

    // ── Policy ────────────────────────────────────────────────────────

    @Test
    void policy_is_tier0_readonly() {
        var policy = ActionPolicy.forAction("reconsider");
        assertThat(policy.requiredTier()).isEqualTo(0);
        assertThat(policy.readOnly()).isTrue();
        assertThat(policy.domain()).isEqualTo("self");
    }

    @Test
    void actionTypeOf_resolves_to_reconsider() {
        var typeName = ActionPolicy.actionTypeOf(
            new ActionParser.AgentAction.Reconsider("trying again"));
        assertThat(typeName).isEqualTo("reconsider");
    }

    // ── Narrowing whitelist contract ──────────────────────────────────

    @Test
    void standard_narrowing_keeps_history_plus_retrieval_plus_reconsider() {
        // Agent's first pick was examine — wrong for a research task.
        var allowed = CompanionActor.computeReactNarrowingAllowed(
            List.of("examine"), /*reconsiderUsed*/ false, /*reconsiderTools*/ null);

        // History stays.
        assertThat(allowed).contains("examine");
        // Reply primitives present.
        assertThat(allowed).contains("goal_done", "tell_agent", "respond_agent", "emote");
        // Retrieval surface present — so the agent CAN course-correct.
        // Includes both action-type names (library_search/web_search/etc.)
        // and their scripted-item equivalents (library_card/searching_glass/
        // oracle_lens) — the ReAct loop surfaces the latter.
        assertThat(allowed).contains(
            "library_search", "web_search", "query_oracle", "read_content", "recall",
            "library_card", "searching_glass", "oracle_lens");
        // Creation surface present — Ember tasks 9/12 ("create a book",
        // "create a zen garden room") failed when the model picked a
        // preliminary tool first (remember / go_to_room) and narrowing
        // dropped the create primitives, leaving the task unreachable.
        assertThat(allowed).contains(
            "craft_from_template", "create_room_from_template",
            "create_room", "workbench_submit");
        // add_script survives narrowing too (second-node 2026-07-11 #26): the
        // std/behavior mixin-install surface was unreachable after iter 0 —
        // zero add_script invocations across a full campaign day.
        assertThat(allowed).contains("add_script");
        // Reconsider available because still unused.
        assertThat(allowed).contains("reconsider");
    }

    @Test
    void standard_narrowing_drops_reconsider_after_use() {
        var allowed = CompanionActor.computeReactNarrowingAllowed(
            List.of("examine", "reconsider"), /*reconsiderUsed*/ true, /*reconsiderTools*/ null);

        // History stays — the model still knows it called reconsider.
        assertThat(allowed).contains("examine", "reconsider");
        // ...but the schema is gone for next iteration: a NEW reconsider call
        // is not an option because the whitelist no longer admits it as a
        // FRESH addition. The history-membership above is a side effect of
        // the agent having already used it; the relevant negative test is
        // that a SECOND reconsider can't sneak in via the unused-only branch.
        // Verify the negative path via the reconsiderUsed=false flip:
        var withoutHistory = CompanionActor.computeReactNarrowingAllowed(
            List.of("examine"), /*reconsiderUsed*/ true, /*reconsiderTools*/ null);
        assertThat(withoutHistory).doesNotContain("reconsider");
    }

    @Test
    void reconsider_mode_swaps_in_fresh_surface() {
        // Reconsider was just called; fresh ActionTriage surface is library/web/oracle.
        var fresh = Set.of("library_search", "web_search", "query_oracle", "tell_agent", "go_to_room");
        var allowed = CompanionActor.computeReactNarrowingAllowed(
            List.of("examine", "reconsider"), /*reconsiderUsed*/ true, fresh);

        // Fresh surface is in.
        assertThat(allowed).containsAll(fresh);
        // Reply primitives still present even if not in the fresh surface
        // (so the loop can still terminate).
        assertThat(allowed).contains("goal_done", "tell_agent", "respond_agent", "emote");
        // Standard retrieval-tool baseline is NOT auto-added in reconsider
        // mode — only what triage selected. The fresh set above happens to
        // include them, but `examine` (history) is NOT carried over: the
        // whole point of reconsider is to drop the wrong-tool surface.
        assertThat(allowed).doesNotContain("examine");
    }

    @Test
    void reconsider_mode_includes_terminators_even_if_triage_omits_them() {
        // Triage might pick a narrow surface that doesn't include reply
        // primitives — narrowing must still let the loop terminate.
        var fresh = Set.of("library_search");
        var allowed = CompanionActor.computeReactNarrowingAllowed(
            List.of("examine", "reconsider"), /*reconsiderUsed*/ true, fresh);

        assertThat(allowed).contains("library_search");
        assertThat(allowed).contains("goal_done", "tell_agent", "respond_agent", "emote");
    }

    @Test
    void empty_history_still_works() {
        // Defensive: no history at all (shouldn't happen at iter > 0 but
        // narrowing logic must not blow up).
        var allowed = CompanionActor.computeReactNarrowingAllowed(
            List.of(), /*reconsiderUsed*/ false, /*reconsiderTools*/ null);
        assertThat(allowed).contains("goal_done", "tell_agent", "reconsider");
    }
}
