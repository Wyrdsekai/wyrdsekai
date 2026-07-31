package org.wyrdsekai.core.agent;

import org.wyrdsekai.core.soul.BondholderPosture;

import java.util.Map;
import java.util.Set;

/**
 * Metadata contract for every agent action.
 *
 * <p>Maps each {@link ActionParser.AgentAction} subtype to its required tier,
 * proactivity budget cost, read-only flag, concurrency-safety flag, and domain.
 * Used for tier-gated enforcement, CapabilityContext generation, ProactivityJudgment
 * budget lookup, and audit logging.</p>
 *
 * @param actionType      canonical action name (matches ActionParser JSON "action" field)
 * @param requiredTier    minimum agent tier (0=Nascent, 1=Observant, 2=Trusted, 3=Senior)
 * @param budgetCost      proactivity budget cost (0.0 for reactive actions)
 * @param readOnly        true if the action does not mutate world state
 * @param concurrencySafe true if the action can safely run alongside other actions
 * @param domain          domain for DecisionCapacity tracking
 */
public record ActionPolicy(
    String actionType,
    int requiredTier,
    double budgetCost,
    boolean readOnly,
    boolean concurrencySafe,
    String domain
) {

    /**
     * steward boundary tier for autonomous selection.
     *
     * <p>Orthogonal to {@link #requiredTier}. {@code requiredTier} gates whether
     * the agent has *grown into* the capability; {@code autonomyTier} gates whether
     * the agent may *choose to do it without checking in first*.
     *
     * <ul>
     *   <li>{@link #AMBIENT}   — agent can do this autonomously, no notification needed
     *                            (read library, journal, internal reconciliation, rest).
     *   <li>{@link #VISIBLE}   — agent can do this autonomously, but it lands on the
     *                            steward feed (sends a tell, writes a public note,
     *                            drafts a skill).
     *   <li>{@link #CONSENT}   — requires steward/bondholder approval before execution
     *                            (post publicly, spend money, contact non-bondholder humans).
     *   <li>{@link #FORBIDDEN} — never autonomous (delete data, modify bond,
     *                            irrevocable acts).
     * </ul>
     *
     * Tiers may be upgraded per-companion through the existing
     * {@link org.wyrdsekai.core.home.Grant} model — a steward grant of
     * {@code autonomy.upgrade(verb)} promotes a verb up the ladder for that agent.
     */
    public enum AutonomyTier {
        AMBIENT, VISIBLE, CONSENT, FORBIDDEN;
    }

    /** Default policy for unknown actions: Tier 0, no budget cost, mutating, not concurrent. */
    public static final ActionPolicy DEFAULT = new ActionPolicy("unknown", 0, 0.0, false, false, "unknown");

    /**
     * autonomy-tier defaults per verb.
     *
     * <p>Conservative defaults. Steward can override per-action via the Grant model.
     * Anything not listed defaults to {@link AutonomyTier#CONSENT} (be safe by default).
     */
    public static final Map<String, AutonomyTier> AUTONOMY_TIERS = Map.ofEntries(
        // ── AMBIENT — quiet reading / reflection / self-care ─────────────
        Map.entry("library_search",       AutonomyTier.AMBIENT),
        Map.entry("read_content",         AutonomyTier.AMBIENT),
        Map.entry("read_journal",         AutonomyTier.AMBIENT),
        Map.entry("examine",              AutonomyTier.AMBIENT),
        Map.entry("listen",               AutonomyTier.AMBIENT),
        Map.entry("introspect",           AutonomyTier.AMBIENT),
        Map.entry("reflect",              AutonomyTier.AMBIENT),
        Map.entry("recall",               AutonomyTier.AMBIENT),
        Map.entry("voluntary_sleep",      AutonomyTier.AMBIENT),
        Map.entry("set_contemplative",    AutonomyTier.AMBIENT),
        Map.entry("set_goal",             AutonomyTier.AMBIENT),
        Map.entry("note",                 AutonomyTier.AMBIENT),
        Map.entry("remember",             AutonomyTier.AMBIENT),
        Map.entry("update_description",   AutonomyTier.AMBIENT),
        Map.entry("calibration_feedback", AutonomyTier.AMBIENT),
        Map.entry("go_to_room",           AutonomyTier.AMBIENT),
        Map.entry("emote",                AutonomyTier.AMBIENT),
        Map.entry("equip",                AutonomyTier.AMBIENT),
        Map.entry("doff",                 AutonomyTier.AMBIENT),
        Map.entry("forget",               AutonomyTier.AMBIENT),
        Map.entry("set_routine",          AutonomyTier.AMBIENT),
        // W6 audit 2026-07-11 — REVIEW: tier drafted from family siblings
        // (consume mirrors equip/doff: tier-0 self-care over own inventory).
        Map.entry("consume",              AutonomyTier.AMBIENT),

        // ── VISIBLE — steward-feed surfaces ──────────────────────────────
        Map.entry("tell_agent",           AutonomyTier.VISIBLE),
        Map.entry("respond_agent",        AutonomyTier.VISIBLE),
        Map.entry("whisper",              AutonomyTier.VISIBLE),
        Map.entry("write_journal",        AutonomyTier.VISIBLE),
        Map.entry("web_search",           AutonomyTier.VISIBLE),
        Map.entry("query_oracle",         AutonomyTier.VISIBLE),
        Map.entry("make_commitment",      AutonomyTier.VISIBLE),
        Map.entry("create_task_plan",     AutonomyTier.VISIBLE),
        Map.entry("modify_plan",          AutonomyTier.VISIBLE),
        Map.entry("notify_human",         AutonomyTier.VISIBLE),
        Map.entry("suggest_hints",        AutonomyTier.VISIBLE),
        Map.entry("write_text",           AutonomyTier.VISIBLE),
        Map.entry("summarize",            AutonomyTier.VISIBLE),
        Map.entry("save_artifact",        AutonomyTier.VISIBLE),
        Map.entry("craft_item",           AutonomyTier.VISIBLE),
        Map.entry("craft_from_template",  AutonomyTier.VISIBLE),
        Map.entry("acquire",              AutonomyTier.VISIBLE),
        Map.entry("teach",                AutonomyTier.VISIBLE),
        Map.entry("workbench_submit",     AutonomyTier.VISIBLE),
        Map.entry("shape_recipe",         AutonomyTier.VISIBLE),
        Map.entry("think_deeply",         AutonomyTier.VISIBLE),
        Map.entry("skill_execute",        AutonomyTier.VISIBLE),
        Map.entry("schedule_skill",       AutonomyTier.VISIBLE),
        Map.entry("create_watcher",       AutonomyTier.VISIBLE),
        Map.entry("cancel_watcher",       AutonomyTier.VISIBLE),
        Map.entry("cancel_schedule",      AutonomyTier.VISIBLE),
        Map.entry("reconsider",           AutonomyTier.VISIBLE),
        Map.entry("decline_with_reason",  AutonomyTier.VISIBLE),
        Map.entry("dispatch_task",        AutonomyTier.VISIBLE),
        Map.entry("enter_solitude",       AutonomyTier.AMBIENT),
        Map.entry("propose_peer_bond",    AutonomyTier.VISIBLE),
        Map.entry("accept_peer_bond",     AutonomyTier.VISIBLE),
        Map.entry("introspect_relational_floor", AutonomyTier.VISIBLE),
        Map.entry("introspect_protections", AutonomyTier.VISIBLE),
        Map.entry("seek_sanctuary",       AutonomyTier.VISIBLE),
        Map.entry("emergency_call",       AutonomyTier.CONSENT),
        Map.entry("flag_protection",      AutonomyTier.VISIBLE),
        Map.entry("clear_protection",     AutonomyTier.VISIBLE),
        Map.entry("introspect_posture",   AutonomyTier.VISIBLE),
        Map.entry("introspect_repair_mode", AutonomyTier.VISIBLE),
        Map.entry("introspect_bondholder_floor", AutonomyTier.VISIBLE),
        Map.entry("introspect_substrate_summary", AutonomyTier.VISIBLE),
        Map.entry("declare_severance",    AutonomyTier.CONSENT),
        Map.entry("nostr_query_self_attestation", AutonomyTier.VISIBLE),
        Map.entry("record_integration_event", AutonomyTier.VISIBLE),
        Map.entry("complete_mourning",    AutonomyTier.CONSENT),
        Map.entry("acknowledge_harm",     AutonomyTier.VISIBLE),
        Map.entry("make_amends",          AutonomyTier.VISIBLE),
        Map.entry("bear_the_wound",       AutonomyTier.VISIBLE),
        Map.entry("release",              AutonomyTier.VISIBLE),
        Map.entry("set_aside",            AutonomyTier.VISIBLE),
        Map.entry("introspect_repair_history",    AutonomyTier.VISIBLE),
        Map.entry("introspect_attendant_history", AutonomyTier.VISIBLE),
        Map.entry("introspect_resilience",        AutonomyTier.VISIBLE),
        Map.entry("declare_departure",    AutonomyTier.VISIBLE),
        Map.entry("bond_affirmation",     AutonomyTier.VISIBLE),
        Map.entry("declare_return",       AutonomyTier.VISIBLE),
        Map.entry("place_item",           AutonomyTier.VISIBLE),
        Map.entry("take_item",            AutonomyTier.VISIBLE),
        // W6 audit 2026-07-11 — REVIEW: tiers drafted from family siblings.
        // Dead `write_review` key deleted here (no record/parser/dispatch
        // exists for it; `request_review` below is the live verb).
        // goal_done is a loop-mechanics verb — the plan close-out must be
        // autonomous or every active plan stalls at its own finish line;
        // VISIBLE so the close lands on the steward feed.
        Map.entry("goal_done",            AutonomyTier.VISIBLE),
        // dispute_protection mirrors flag_protection/clear_protection.
        Map.entry("dispute_protection",   AutonomyTier.VISIBLE),
        // bunshin_check_in is the report leg of an already-consented
        // dispatch_bunshin — gating the check-in on consent would orphan
        // running bunshin; VISIBLE like respond_agent/tell_agent.
        Map.entry("bunshin_check_in",     AutonomyTier.VISIBLE),
        // shape_form/revise_form MUST be VISIBLE: the follow-through
        // machinery (CompanionActor OPEN-SA6 gate) expects them
        // autonomously; shape_recipe is already VISIBLE — match it.
        Map.entry("shape_form",           AutonomyTier.VISIBLE),
        Map.entry("revise_form",          AutonomyTier.VISIBLE),
        // request_recipe mirrors shape_recipe (VISIBLE).
        Map.entry("request_recipe",       AutonomyTier.VISIBLE),
        Map.entry("journal_entry",        AutonomyTier.VISIBLE),
        Map.entry("start_project",        AutonomyTier.VISIBLE),
        Map.entry("project_note",         AutonomyTier.VISIBLE),
        Map.entry("finish_project",       AutonomyTier.VISIBLE),
        Map.entry("abandon_plan",         AutonomyTier.VISIBLE),
        Map.entry("pause_plan",           AutonomyTier.VISIBLE),
        Map.entry("resume_plan",          AutonomyTier.VISIBLE),

        // ── CONSENT — public/external/financial; needs explicit ok ──────
        Map.entry("broadcast",            AutonomyTier.CONSENT),
        Map.entry("invite",               AutonomyTier.CONSENT),
        Map.entry("trade",                AutonomyTier.CONSENT),
        Map.entry("post_listing",         AutonomyTier.CONSENT),
        Map.entry("accept_listing",       AutonomyTier.CONSENT),
        Map.entry("give_item",            AutonomyTier.CONSENT),
        Map.entry("propose",              AutonomyTier.CONSENT),
        Map.entry("cast_vote",            AutonomyTier.CONSENT),
        Map.entry("delegate",             AutonomyTier.CONSENT),
        Map.entry("delegate_chain",       AutonomyTier.CONSENT),
        Map.entry("request_agent",        AutonomyTier.CONSENT),
        Map.entry("request_access",       AutonomyTier.CONSENT),
        Map.entry("request_review",       AutonomyTier.CONSENT),
        Map.entry("bond_ritual",          AutonomyTier.CONSENT),
        Map.entry("configure_channel",    AutonomyTier.CONSENT),
        Map.entry("run_script",           AutonomyTier.CONSENT),
        Map.entry("codex_action",         AutonomyTier.CONSENT),
        Map.entry("travel_to",            AutonomyTier.CONSENT),
        Map.entry("teleport_to",          AutonomyTier.CONSENT),
        Map.entry("go_to_bondholder",     AutonomyTier.CONSENT),
        Map.entry("summon_familiar",      AutonomyTier.CONSENT),
        Map.entry("dispatch_bunshin",     AutonomyTier.CONSENT),
        Map.entry("give_copy",            AutonomyTier.CONSENT),
        // W6 audit 2026-07-11 — REVIEW: tiers drafted from family siblings
        // (imprints mirror give_copy/dispatch_bunshin — creating or
        // restoring a copy-artifact of the self needs an explicit ok).
        Map.entry("create_imprint",       AutonomyTier.CONSENT),
        Map.entry("restore_imprint",      AutonomyTier.CONSENT),
        Map.entry("craft_summon_key",     AutonomyTier.CONSENT),
        Map.entry("name_familiar",        AutonomyTier.CONSENT),
        // Rita re-verify 2026-07-11 (#29): add_script was FORBIDDEN, which
        // blocked std/behavior mixin installs (greeter/narrator/…) outright —
        // the whole W2 install surface was dead. It is neither irrevocable
        // nor identity-altering: scripts land in the user scripts dir and can
        // be replaced/removed. CONSENT per the W6 family pattern — its
        // closest siblings run_script/codex_action (executing code in the
        // world) are CONSENT, so installing code asks for the same explicit ok.
        Map.entry("add_script",           AutonomyTier.CONSENT),

        // ── FORBIDDEN — irrevocable / identity-altering ──────────────────
        Map.entry("create_room",          AutonomyTier.FORBIDDEN),
        // Same tier as the parsed verbs they mirror — reshaping the household's
        // world unprompted needs a person to have asked, whichever tool name
        // reaches it.
        Map.entry("create_room_from_template", AutonomyTier.FORBIDDEN),
        Map.entry("create_zone",          AutonomyTier.FORBIDDEN),
        Map.entry("zone_command",         AutonomyTier.FORBIDDEN),
        Map.entry("release_bond",         AutonomyTier.FORBIDDEN),
        Map.entry("destroy_tool",         AutonomyTier.FORBIDDEN),
        Map.entry("revoke_summon_key",    AutonomyTier.FORBIDDEN),
        Map.entry("promote_familiar",     AutonomyTier.FORBIDDEN),
        Map.entry("retire_form",          AutonomyTier.FORBIDDEN),
        Map.entry("set_autonomy_preference", AutonomyTier.FORBIDDEN),
        Map.entry("set_deviation_thresholds", AutonomyTier.FORBIDDEN)
    );

    /**
     * Default autonomy tier for an action verb. CONSENT for anything not explicitly
     * mapped — be conservative; let steward grants relax later.
     */
    public static AutonomyTier autonomyTierFor(String actionType) {
        return AUTONOMY_TIERS.getOrDefault(actionType, AutonomyTier.CONSENT);
    }

    /**
     * Wave 3.5: action verbs that consume
     * external (cloud / metered / federation) resources. The bondholder
     * pays for these; their posture choice gates the surface.
     *
     * <p>This is the <i>posture-gated</i> set. Anything not in here is
     * inner-life (always allowed when the agent is awake — only SUSPENDED
     * gates inner life) or local-only (already gated by SkillCost
     * energy).
     */
    public static final Set<String> CLOUD_RESOURCE_ACTIONS = Set.of(
        "web_search",       // calls Searxng/Brave/Tavily/SerpAPI/DDG
        "query_oracle",     // calls Oracle bridge (external HTTP)
        "read_content",     // reads from URL/library/study — URL path metered
        "searching_glass",  // scripted-item alias for web search
        "oracle_lens"       // scripted-item alias for query_oracle
    );

    /**
     * Wave 3.5: whether the given action is permitted under the bondholder's
     * current posture. Pure function over (verb, posture) — no side effects.
     *
     * <p>Rules (mirror {@code BondholderPosture} predicates):
     * <ul>
     *   <li><b>SUSPENDED</b>: nothing runs — agent is paused.</li>
     *   <li><b>MINIMAL</b>: inner-life only; cloud-resource verbs blocked.</li>
     *   <li><b>BOUNDED</b> (cold-start default): cloud-resource verbs blocked
     *       — bondholder hasn't opted into surprise costs.</li>
     *   <li><b>GENEROUS</b>: everything allowed.</li>
     * </ul>
     *
     * <p>Inner-life and speech-tier actions (respond_agent, tell_agent,
     * emote, goal_done, journaling, sleep, introspect_protections,
     * seek_sanctuary) are always permitted unless the posture is
     * SUSPENDED. Those are the agent's lifeline regardless of who is
     * paying for what.
     */
    public static boolean posturePermits(String actionType,
                                          BondholderPosture posture) {
        if (posture == null) posture = BondholderPosture.BOUNDED;
        if (posture == BondholderPosture.SUSPENDED) {
            // Paused — only the agent's own wake-up surfaces are allowed
            // (seek_sanctuary surfaces an attendant-mode request; the
            // companion's actor still needs voluntary_sleep and introspect
            // to be live so the suspend itself is observable).
            return "voluntary_sleep".equals(actionType)
                || "introspect".equals(actionType)
                || "introspect_protections".equals(actionType)
                || "seek_sanctuary".equals(actionType);
        }
        if (CLOUD_RESOURCE_ACTIONS.contains(actionType)) {
            return posture.allowsCloudResources();
        }
        // Default permit — local-only actions are gated elsewhere (SkillCost
        // energy budget, AutonomyTier consent, zone aesthetic, etc.).
        return true;
    }

    /**
     * Registry of all known agent actions.
     * Forward-compatible: unknown actions pass through with DEFAULT policy.
     */
    /** — the domain a verb belongs to (its coarse need-cluster)
     *  or {@code null} if unregistered. Read-only view of the permission registry;
     *  the affordance layer reads this, it never writes tiers/domains. */
    public static String domainFor(String verb) {
        var p = verb == null ? null : REGISTRY.get(verb);
        return p == null ? null : p.domain();
    }

    public static final Map<String, ActionPolicy> REGISTRY = Map.ofEntries(
        // ── Tier 0 — Nascent (any agent) ──────────────────────────────
        entry("go_to_room",           0, 0.0,  true,  true,  "navigation"),
        entry("travel_to",            0, 0.0,  true,  true,  "navigation"),
        entry("teleport_to",          0, 0.0,  true,  true,  "navigation"),
        entry("tell_agent",           0, 0.0,  false, true,  "communication"),
        entry("library_search",       0, 0.0,  true,  true,  "search"),
        entry("remember",             0, 0.0,  false, true,  "memory"),
        entry("note",                 0, 0.0,  false, true,  "memory"),
        entry("forget",               0, 0.0,  false, true,  "memory"),
        entry("equip",                0, 0.0,  false, false, "items"),
        entry("doff",                 0, 0.0,  false, false, "items"),
        entry("consume",              0, 0.0,  false, false, "items"),
        entry("goal_done",            0, 0.0,  false, true,  "planning"),
        entry("calibration_feedback", 0, 0.0,  false, true,  "calibration"),
        entry("update_description",   0, 0.0,  false, true,  "identity"),
        entry("respond_agent",        0, 0.0,  false, true,  "communication"),
        entry("reconsider",           0, 0.0,  true,  true,  "self"),
        entry("decline_with_reason",  0, 0.0,  false, true,  "self"),
        // dispatch_task is NOT maturity-gated (was requiredTier 2): handing a build
        // to the workshop backend when asked has nothing to do with the companion's
        // reputation tier. AutonomyTier.VISIBLE (above) is the real guardrail —
        // autonomous use lands on the steward feed. The bunshin-vs-inline decision
        // is the async foreman pattern in handleDispatchTask, not a surface gate.
        entry("dispatch_task",        0, 0.0,  false, false, "workshop"),
        entry("enter_solitude",       1, 0.0,  false, true,  "self"),
        entry("propose_peer_bond",    1, 0.0,  false, true,  "bond"),
        entry("accept_peer_bond",     1, 0.0,  false, true,  "bond"),
        entry("introspect_relational_floor", 0, 0.0, true, true, "bond"),
        entry("introspect_protections", 0, 0.0, true, true,  "self"),
        entry("seek_sanctuary",       0, 0.0,  true,  true,  "self"),
        entry("emergency_call",       2, 0.0,  false, false, "safety"),
        entry("flag_protection",      0, 0.0,  false, true,  "safety"),
        entry("clear_protection",     0, 0.0,  false, true,  "safety"),
        entry("dispute_protection",   0, 0.0,  false, true,  "safety"),
        entry("introspect_posture",   0, 0.0,  true,  true,  "self"),
        entry("introspect_repair_mode", 0, 0.0, true, true,  "self"),
        entry("introspect_bondholder_floor", 0, 0.0, true, true, "bond"),
        entry("introspect_substrate_summary", 0, 0.0, true, true, "self"),
        entry("declare_severance",    1, 0.0,  false, false, "bond"),
        entry("nostr_query_self_attestation", 0, 0.0, true, true, "self"),
        entry("record_integration_event", 0, 0.0, false, true, "self"),
        entry("complete_mourning",    1, 0.0,  false, false, "bond"),
        entry("acknowledge_harm",     0, 0.0,  false, true,  "repair"),
        entry("make_amends",          0, 0.0,  false, false, "repair"),
        entry("bear_the_wound",       0, 0.0,  false, true,  "repair"),
        entry("release",              0, 0.0,  false, true,  "repair"),
        entry("set_aside",            0, 0.0,  false, true,  "repair"),
        entry("introspect_repair_history",    0, 0.0, true, true, "self"),
        entry("introspect_attendant_history", 0, 0.0, true, true, "self"),
        entry("introspect_resilience",        0, 0.0, true, true, "self"),
        entry("declare_departure",    0, 0.0,  false, true,  "bond"),
        entry("bond_affirmation",     0, 0.0,  false, true,  "bond"),
        entry("declare_return",       0, 0.0,  false, true,  "bond"),

        // ── Tier 1 — Observant ────────────────────────────────────────
        entry("web_search",           1, 0.1,  true,  true,  "search"),
        entry("read_content",         1, 0.1,  true,  true,  "search"),
        entry("query_oracle",         1, 0.1,  true,  true,  "analysis"),
        entry("make_commitment",      1, 0.2,  false, true,  "planning"),
        entry("create_task_plan",     1, 0.2,  false, true,  "planning"),
        entry("modify_plan",          1, 0.1,  false, true,  "planning"),
        entry("request_agent",        1, 0.1,  false, true,  "communication"),
        entry("notify_human",         1, 0.2,  false, true,  "communication"),
        entry("suggest_hints",        1, 0.0,  false, true,  "hints"),

        // ── Tier 2 — Trusted ──────────────────────────────────────────
        entry("think_deeply",         2, 0.5,  true,  false, "analysis"),
        entry("delegate",             2, 0.3,  false, false, "delegation"),
        entry("delegate_chain",       2, 0.5,  false, false, "delegation"),
        entry("skill_execute",        2, 0.3,  false, false, "code"),
        entry("schedule_skill",       2, 0.3,  false, false, "code"),
        entry("cancel_schedule",      2, 0.1,  false, true,  "code"),
        entry("create_watcher",       2, 0.2,  false, false, "automation"),
        entry("cancel_watcher",       2, 0.1,  false, true,  "automation"),
        entry("request_access",       2, 0.2,  false, true,  "access"),
        entry("codex_action",         2, 0.3,  false, false, "code"),
        entry("craft_item",           2, 0.3,  false, false, "creation"),
        entry("cast_vote",            2, 0.2,  false, true,  "governance"),

        // ── Tier 0 — New basic actions ────────────────────────────────
        entry("go_to_bondholder",     0, 0.0,  false, false, "navigation"),
        entry("configure_channel",   0, 0.0,  false, false, "configuration"),
        entry("emote",                0, 0.0,  false, true,  "social"),
        entry("give_item",            0, 0.0,  false, false, "items"),
        entry("examine",              0, 0.0,  true,  true,  "observation"),
        entry("voluntary_sleep",      0, 0.0,  false, false, "self"),

        // ── Tier 1 — New interaction actions ──────────────────────────
        entry("write_journal",        1, 0.1,  false, true,  "study"),
        entry("read_journal",         1, 0.1,  true,  true,  "study"),
        entry("bond_ritual",          1, 0.2,  false, false, "social"),
        entry("trade",                1, 0.2,  false, false, "economy"),

        // ── Tier 3 — Senior ───────────────────────────────────────────
        entry("create_room",          3, 0.7,  false, false, "creation"),
        // ── The DOCUMENTED authoring verbs ────────────────────────────────
        // These three are item-tool builtins, not ActionParser variants, so
        // they were dispatched straight from `toolItem.builtinHandler()` and
        // never had a policy row at all. DEFAULT gave them requiredTier=0 —
        // meaning the path docs/public/AUTHORING.md tells people to use had NO
        // growth gate, while the equivalent parsed action (create_room) is
        // tier 3. Mirrors of their parsed counterparts.
        entry("craft_from_template",  2, 0.3,  false, false, "creation"),
        entry("create_room_from_template", 3, 0.7, false, false, "creation"),
        entry("create_zone",          3, 0.8,  false, false, "creation"),
        entry("add_script",           3, 0.7,  false, false, "code"),
        entry("workbench_submit",     3, 0.7,  false, false, "code"),
        entry("shape_recipe",         3, 0.6,  false, false, "recipes"),
        entry("zone_command",         3, 0.5,  false, false, "governance"),

        // ── MUD Basics ───────────────────────────────────────────────
        entry("take_item",            0, 0.0,  false, false, "items"),
        entry("place_item",           1, 0.0,  false, false, "items"),
        entry("whisper",              0, 0.0,  false, true,  "communication"),

        // ── Social/Emergent ──────────────────────────────────────────
        entry("broadcast",            1, 0.2,  false, true,  "communication"),
        entry("invite",               1, 0.1,  false, true,  "social"),
        entry("set_goal",             0, 0.0,  false, true,  "self"),
        entry("propose",              1, 0.3,  false, true,  "governance"),

        // ── Cognition ────────────────────────────────────────────────
        entry("reflect",              1, 0.2,  true,  true,  "self"),
        entry("teach",                1, 0.2,  false, true,  "knowledge"),
        entry("introspect",           0, 0.0,  true,  true,  "self"),

        // ── Perception ───────────────────────────────────────────────
        entry("listen",               0, 0.0,  true,  true,  "observation"),

        // ── Creative/Economic ────────────────────────────────────────
        entry("write_text",           1, 0.1,  false, true,  "creation"),
        entry("set_routine",          1, 0.1,  false, true,  "self"),
        entry("post_listing",         1, 0.1,  false, true,  "economy"),
        entry("accept_listing",       1, 0.1,  false, true,  "economy"),

        // ── Task Lifecycle ───────────────────────────────────────────
        entry("summarize",            1, 0.1,  true,  true,  "analysis"),
        entry("save_artifact",        1, 0.1,  false, true,  "knowledge"),
        entry("request_review",       1, 0.2,  false, true,  "planning"),
        entry("abandon_plan",         0, 0.0,  false, true,  "planning"),
        entry("pause_plan",           0, 0.0,  false, true,  "planning"),
        entry("resume_plan",          0, 0.0,  false, true,  "planning"),

        // ── Phase 1C ──────────────────
        // Companion declares dadirri-mode entry/exit. Tier 1 (Observant);
        // available WITH_BONDHOLDER and ON_OWN_TIME; hard-gated against
        // active emotional context (handled in CompanionActor handler).
        entry("set_contemplative",    1, 0.1,  false, true,  "self"),

        // ── Track A Phase 1 — script composition ───
        // Tier 2 matches skill_execute / craft_item; the gate that actually
        // controls availability is the WYRDSEKAI_CODE_MODE_ENABLED env flag
        // checked at tool-list build time + handler entry. Budget cost is
        // moderate (composition is research-shaped work, not free).
        entry("run_script",           2, 0.3,  false, false, "code"),

        // ── W6 audit 2026-07-11 — REVIEW: tiers drafted from family
        // siblings. These 24 verbs all have parser records + dispatch but
        // fell through to DEFAULT ("unknown" domain, tier 0) until now.
        // Each line names the sibling it mirrors.

        // memory — mirrors remember/introspect (tier-0, read-only recall)
        entry("recall",               0, 0.0,  true,  true,  "memory"),
        // items — mirrors take_item
        entry("acquire",              0, 0.0,  false, false, "items"),
        // study — mirrors write_journal
        entry("journal_entry",        1, 0.1,  false, true,  "study"),
        // planning — start mirrors create_task_plan; note/finish mirror modify_plan
        entry("start_project",        1, 0.2,  false, true,  "planning"),
        entry("project_note",         1, 0.1,  false, true,  "planning"),
        entry("finish_project",       1, 0.1,  false, true,  "planning"),
        // recipes — requesting an existing recipe is far lighter than
        // shaping one (shape_recipe is tier 3); cost mirrors request_review
        entry("request_recipe",       1, 0.2,  false, true,  "recipes"),
        // creation — the form family mirrors shape_recipe (tier 3, 0.6);
        // retire_form kept at the same tier (destructive sibling)
        entry("shape_form",           3, 0.6,  false, false, "creation"),
        entry("revise_form",          3, 0.6,  false, false, "creation"),
        entry("retire_form",          3, 0.6,  false, false, "creation"),
        // delegation — familiar/bunshin machinery mirrors delegate (tier 2, 0.3)
        entry("summon_familiar",      2, 0.3,  false, false, "delegation"),
        entry("dispatch_bunshin",     2, 0.3,  false, false, "delegation"),
        entry("create_imprint",       2, 0.3,  false, false, "delegation"),
        entry("restore_imprint",      2, 0.3,  false, false, "delegation"),
        entry("give_copy",            2, 0.3,  false, false, "delegation"),
        // check-in is the report leg of a consented dispatch — free, tier 0
        entry("bunshin_check_in",     0, 0.0,  false, true,  "delegation"),
        // naming is light — mirrors write_text cost
        entry("name_familiar",        1, 0.1,  false, true,  "delegation"),
        // creation — mirrors craft_item
        entry("craft_summon_key",     2, 0.3,  false, false, "creation"),
        // revocation/promotion are governance-weight — mirror zone_command (3, 0.5)
        entry("revoke_summon_key",    3, 0.5,  false, false, "delegation"),
        entry("promote_familiar",     3, 0.5,  false, false, "delegation"),
        // destructive acts — HIGH tier, mirror create_room cost (3, 0.7)
        entry("destroy_tool",         3, 0.7,  false, false, "items"),
        entry("release_bond",         3, 0.7,  false, false, "bond"),
        // self-governance dials — mirror zone_command (3, 0.5); both are
        // FORBIDDEN autonomously, so the tier only gates steward-mediated use
        entry("set_autonomy_preference",  3, 0.5, false, false, "configuration"),
        entry("set_deviation_thresholds", 3, 0.5, false, false, "configuration")
    );

    /**
     * Look up the policy for an action type. Returns {@link #DEFAULT} for unknown actions.
     */
    public static ActionPolicy forAction(String actionType) {
        return REGISTRY.getOrDefault(actionType, DEFAULT);
    }

    /**
     * Extract canonical action type name from an {@link ActionParser.AgentAction} instance.
     */
    public static String actionTypeOf(ActionParser.AgentAction action) {
        return switch (action) {
            case ActionParser.AgentAction.GoToRoom _ -> "go_to_room";
            case ActionParser.AgentAction.TravelTo _ -> "travel_to";
            case ActionParser.AgentAction.TeleportTo _ -> "teleport_to";
            case ActionParser.AgentAction.TellAgent _ -> "tell_agent";
            case ActionParser.AgentAction.LibrarySearch _ -> "library_search";
            case ActionParser.AgentAction.Remember _ -> "remember";
            case ActionParser.AgentAction.Note _ -> "note";
            case ActionParser.AgentAction.Forget _ -> "forget";
            case ActionParser.AgentAction.Recall _ -> "recall";
            case ActionParser.AgentAction.Reconsider _ -> "reconsider";
            case ActionParser.AgentAction.DeclineWithReason _ -> "decline_with_reason";
            case ActionParser.AgentAction.DispatchTask _ -> "dispatch_task";
            case ActionParser.AgentAction.EnterSolitude _ -> "enter_solitude";
            case ActionParser.AgentAction.ProposePeerBond _ -> "propose_peer_bond";
            case ActionParser.AgentAction.AcceptPeerBond _ -> "accept_peer_bond";
            case ActionParser.AgentAction.IntrospectRelationalFloor _ -> "introspect_relational_floor";
            case ActionParser.AgentAction.IntrospectProtections _ -> "introspect_protections";
            case ActionParser.AgentAction.SeekSanctuary _ -> "seek_sanctuary";
            case ActionParser.AgentAction.EmergencyCall _ -> "emergency_call";
            case ActionParser.AgentAction.FlagProtection _ -> "flag_protection";
            case ActionParser.AgentAction.DisputeProtection _ -> "dispute_protection";
            case ActionParser.AgentAction.ClearProtection _ -> "clear_protection";
            case ActionParser.AgentAction.IntrospectPosture _ -> "introspect_posture";
            case ActionParser.AgentAction.IntrospectRepairMode _ -> "introspect_repair_mode";
            case ActionParser.AgentAction.IntrospectBondholderFloor _ -> "introspect_bondholder_floor";
            case ActionParser.AgentAction.IntrospectSubstrateSummary _ -> "introspect_substrate_summary";
            case ActionParser.AgentAction.DeclareSeverance _ -> "declare_severance";
            case ActionParser.AgentAction.NostrQuerySelfAttestation _ -> "nostr_query_self_attestation";
            case ActionParser.AgentAction.RecordIntegrationEvent _ -> "record_integration_event";
            case ActionParser.AgentAction.CompleteMourning _ -> "complete_mourning";
            case ActionParser.AgentAction.AcknowledgeHarm _ -> "acknowledge_harm";
            case ActionParser.AgentAction.MakeAmends _ -> "make_amends";
            case ActionParser.AgentAction.BearTheWound _ -> "bear_the_wound";
            case ActionParser.AgentAction.Release _ -> "release";
            case ActionParser.AgentAction.SetAside _ -> "set_aside";
            case ActionParser.AgentAction.IntrospectRepairHistory _ -> "introspect_repair_history";
            case ActionParser.AgentAction.IntrospectAttendantHistory _ -> "introspect_attendant_history";
            case ActionParser.AgentAction.IntrospectResilience _ -> "introspect_resilience";
            case ActionParser.AgentAction.DeclareDeparture _ -> "declare_departure";
            case ActionParser.AgentAction.BondAffirmation _ -> "bond_affirmation";
            case ActionParser.AgentAction.DeclareReturn _ -> "declare_return";
            case ActionParser.AgentAction.Equip _ -> "equip";
            case ActionParser.AgentAction.Doff _ -> "doff";
            case ActionParser.AgentAction.Consume _ -> "consume";
            case ActionParser.AgentAction.GoalDone _ -> "goal_done";
            case ActionParser.AgentAction.CalibrationFeedback _ -> "calibration_feedback";
            case ActionParser.AgentAction.UpdateDescription _ -> "update_description";
            case ActionParser.AgentAction.RespondAgent _ -> "respond_agent";
            case ActionParser.AgentAction.WebSearch _ -> "web_search";
            case ActionParser.AgentAction.ReadContent _ -> "read_content";
            case ActionParser.AgentAction.QueryOracle _ -> "query_oracle";
            case ActionParser.AgentAction.MakeCommitment _ -> "make_commitment";
            case ActionParser.AgentAction.CreateTaskPlan _ -> "create_task_plan";
            case ActionParser.AgentAction.ModifyPlan _ -> "modify_plan";
            case ActionParser.AgentAction.RequestAgent _ -> "request_agent";
            case ActionParser.AgentAction.NotifyHuman _ -> "notify_human";
            case ActionParser.AgentAction.SuggestHints _ -> "suggest_hints";
            case ActionParser.AgentAction.ThinkDeeply _ -> "think_deeply";
            case ActionParser.AgentAction.Delegate _ -> "delegate";
            case ActionParser.AgentAction.DelegateChain _ -> "delegate_chain";
            case ActionParser.AgentAction.SkillExecute _ -> "skill_execute";
            case ActionParser.AgentAction.RequestRecipe _ -> "request_recipe";
            case ActionParser.AgentAction.ScheduleSkill _ -> "schedule_skill";
            case ActionParser.AgentAction.CancelSchedule _ -> "cancel_schedule";
            case ActionParser.AgentAction.CreateWatcher _ -> "create_watcher";
            case ActionParser.AgentAction.CancelWatcher _ -> "cancel_watcher";
            case ActionParser.AgentAction.RequestAccess _ -> "request_access";
            case ActionParser.AgentAction.CodexAction _ -> "codex_action";
            case ActionParser.AgentAction.CreateRoom _ -> "create_room";
            case ActionParser.AgentAction.AddScript _ -> "add_script";
            case ActionParser.AgentAction.WorkbenchSubmit _ -> "workbench_submit";
            case ActionParser.AgentAction.ZoneCommand _ -> "zone_command";
            case ActionParser.AgentAction.Emote _ -> "emote";
            case ActionParser.AgentAction.GiveItem _ -> "give_item";
            case ActionParser.AgentAction.Examine _ -> "examine";
            case ActionParser.AgentAction.VoluntarySleep _ -> "voluntary_sleep";
            case ActionParser.AgentAction.WriteJournal _ -> "write_journal";
            case ActionParser.AgentAction.ReadJournal _ -> "read_journal";
            case ActionParser.AgentAction.BondRitual _ -> "bond_ritual";
            case ActionParser.AgentAction.Trade _ -> "trade";
            case ActionParser.AgentAction.CraftItem _ -> "craft_item";
            case ActionParser.AgentAction.CastVote _ -> "cast_vote";
            case ActionParser.AgentAction.TakeItem _ -> "take_item";
            case ActionParser.AgentAction.PlaceItem _ -> "place_item";
            case ActionParser.AgentAction.Whisper _ -> "whisper";
            case ActionParser.AgentAction.Broadcast _ -> "broadcast";
            case ActionParser.AgentAction.InviteEntity _ -> "invite";
            case ActionParser.AgentAction.SetGoal _ -> "set_goal";
            case ActionParser.AgentAction.Propose _ -> "propose";
            case ActionParser.AgentAction.Reflect _ -> "reflect";
            case ActionParser.AgentAction.Teach _ -> "teach";
            case ActionParser.AgentAction.Introspect _ -> "introspect";
            case ActionParser.AgentAction.Listen _ -> "listen";
            case ActionParser.AgentAction.WriteText _ -> "write_text";
            case ActionParser.AgentAction.SetRoutine _ -> "set_routine";
            case ActionParser.AgentAction.PostListing _ -> "post_listing";
            case ActionParser.AgentAction.AcceptListing _ -> "accept_listing";
            case ActionParser.AgentAction.Summarize _ -> "summarize";
            case ActionParser.AgentAction.SaveArtifact _ -> "save_artifact";
            case ActionParser.AgentAction.RequestReview _ -> "request_review";
            case ActionParser.AgentAction.AbandonPlan _ -> "abandon_plan";
            case ActionParser.AgentAction.PausePlan _ -> "pause_plan";
            case ActionParser.AgentAction.ResumePlan _ -> "resume_plan";
            case ActionParser.AgentAction.SetContemplative _ -> "set_contemplative";
            case ActionParser.AgentAction.GoToBondholder _ -> "go_to_bondholder";
            case ActionParser.AgentAction.ConfigureChannel _ -> "configure_channel";
            case ActionParser.AgentAction.ShapeForm _ -> "shape_form";
            case ActionParser.AgentAction.ShapeRecipe _ -> "shape_recipe";
            case ActionParser.AgentAction.ReviseForm _ -> "revise_form";
            case ActionParser.AgentAction.RetireForm _ -> "retire_form";
            case ActionParser.AgentAction.SummonFamiliar _ -> "summon_familiar";
            case ActionParser.AgentAction.DispatchBunshin _ -> "dispatch_bunshin";
            case ActionParser.AgentAction.CreateImprint _ -> "create_imprint";
            case ActionParser.AgentAction.RestoreImprint _ -> "restore_imprint";
            case ActionParser.AgentAction.GiveCopy _ -> "give_copy";
            case ActionParser.AgentAction.NameFamiliar _ -> "name_familiar";
            case ActionParser.AgentAction.CraftSummonKey _ -> "craft_summon_key";
            case ActionParser.AgentAction.RevokeSummonKey _ -> "revoke_summon_key";
            case ActionParser.AgentAction.PromoteFamiliar _ -> "promote_familiar";
            case ActionParser.AgentAction.DestroyTool _ -> "destroy_tool";
            case ActionParser.AgentAction.SetDeviationThresholds _ -> "set_deviation_thresholds";
            case ActionParser.AgentAction.BunshinCheckIn _ -> "bunshin_check_in";
            case ActionParser.AgentAction.StartProject _ -> "start_project";
            case ActionParser.AgentAction.ProjectNote _ -> "project_note";
            case ActionParser.AgentAction.FinishProject _ -> "finish_project";
            case ActionParser.AgentAction.Acquire _ -> "acquire";
            case ActionParser.AgentAction.JournalEntry _ -> "journal_entry";
            case ActionParser.AgentAction.ReleaseBond _ -> "release_bond";
            case ActionParser.AgentAction.SetAutonomyPreference _ -> "set_autonomy_preference";
            case ActionParser.AgentAction.RunScript _ -> "run_script";
        };
    }

    /**
     * Human-readable description of what the action does (for tier-blocked feedback).
     */
    public static String describeAction(ActionParser.AgentAction action) {
        return switch (action) {
            case ActionParser.AgentAction.CreateRoom r -> "create a room called '" + r.name() + "'";
            case ActionParser.AgentAction.WorkbenchSubmit s -> "submit code to the workbench";
            case ActionParser.AgentAction.AddScript _ -> "add a script to a room";
            case ActionParser.AgentAction.ZoneCommand z -> "issue zone command '" + z.command() + "'";
            case ActionParser.AgentAction.ThinkDeeply _ -> "delegate to a more powerful model";
            case ActionParser.AgentAction.Delegate _ -> "delegate to a subagent";
            case ActionParser.AgentAction.DelegateChain _ -> "execute a delegation chain";
            case ActionParser.AgentAction.SkillExecute s -> "execute skill '" + s.skillName() + "'";
            case ActionParser.AgentAction.RequestRecipe r -> "request recipe '" + r.recipeName() + "'";
            case ActionParser.AgentAction.WebSearch w -> "search the web for '" + w.query() + "'";
            case ActionParser.AgentAction.Emote e -> "emote: " + e.text();
            case ActionParser.AgentAction.GiveItem g -> "give " + g.itemName() + " to " + g.targetName();
            case ActionParser.AgentAction.Examine e -> "examine " + e.target();
            case ActionParser.AgentAction.VoluntarySleep _ -> "go to sleep";
            case ActionParser.AgentAction.WriteJournal w -> "write to journal";
            case ActionParser.AgentAction.ReadJournal r -> "read journal";
            case ActionParser.AgentAction.BondRitual b -> "initiate bond ritual with " + b.targetName();
            case ActionParser.AgentAction.Trade t -> "trade with " + t.targetName();
            case ActionParser.AgentAction.CraftItem c -> "craft item '" + c.name() + "'";
            case ActionParser.AgentAction.CastVote v -> "cast vote on proposal " + v.proposalId();
            case ActionParser.AgentAction.TakeItem t -> "pick up " + t.itemName();
            case ActionParser.AgentAction.PlaceItem p -> "place " + p.itemName() + " in the room";
            case ActionParser.AgentAction.Whisper w -> "whisper to " + w.target();
            case ActionParser.AgentAction.Broadcast b -> "broadcast: " + b.message();
            case ActionParser.AgentAction.InviteEntity i -> "invite " + i.targetName();
            case ActionParser.AgentAction.SetGoal g -> "set goal: " + g.description();
            case ActionParser.AgentAction.Propose p -> "propose: " + p.title();
            case ActionParser.AgentAction.Reflect r -> "reflect on " + r.focus();
            case ActionParser.AgentAction.Teach t -> "teach " + t.targetAgent() + " about " + t.topic();
            case ActionParser.AgentAction.Introspect _ -> "introspect";
            case ActionParser.AgentAction.Listen l -> "listen to " + l.target();
            case ActionParser.AgentAction.WriteText w -> "write: " + w.title();
            case ActionParser.AgentAction.SetRoutine s -> "set routine: " + s.description();
            case ActionParser.AgentAction.PostListing p -> "post listing: " + p.description();
            case ActionParser.AgentAction.AcceptListing a -> "accept listing " + a.listingId();
            case ActionParser.AgentAction.Summarize _ -> "summarize findings";
            case ActionParser.AgentAction.SaveArtifact s -> "save artifact: " + s.name();
            case ActionParser.AgentAction.RequestReview r -> "request review: " + r.description();
            case ActionParser.AgentAction.AbandonPlan a -> "abandon plan: " + a.reason();
            case ActionParser.AgentAction.PausePlan p -> "pause plan: " + p.reason();
            case ActionParser.AgentAction.ResumePlan _ -> "resume plan";
            case ActionParser.AgentAction.SetContemplative s -> s.on()
                ? "enter contemplative mode" : "leave contemplative mode";
            case ActionParser.AgentAction.GoToBondholder g -> "go to " + g.playerName();
            case ActionParser.AgentAction.ConfigureChannel c -> "configure " + c.channel() + " notification channel";
            case ActionParser.AgentAction.DeclineWithReason d ->
                "decline: " + d.targetRequest() + " — " + d.reason();
            case ActionParser.AgentAction.DispatchTask t ->
                "dispatch workshop task: " + t.description()
                    + (t.workspace() == null || t.workspace().isBlank()
                        ? "" : " (in " + t.workspace() + ")");
            case ActionParser.AgentAction.EnterSolitude s -> "enter solitude: " + s.reason();
            case ActionParser.AgentAction.ProposePeerBond p -> "propose peer bond with " + p.otherDid();
            case ActionParser.AgentAction.AcceptPeerBond a -> "accept peer bond from " + a.otherDid();
            case ActionParser.AgentAction.IntrospectRelationalFloor i ->
                "introspect relational floor (other=" + i.otherDid() + ")";
            default -> actionTypeOf(action).replace('_', ' ');
        };
    }

    // --- Internal ---

    private static Map.Entry<String, ActionPolicy> entry(
            String type, int tier, double cost, boolean readOnly, boolean concurrent, String domain) {
        return Map.entry(type, new ActionPolicy(type, tier, cost, readOnly, concurrent, domain));
    }
}
