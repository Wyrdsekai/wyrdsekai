package org.wyrdsekai.app.engine.agent

/**
 * Metadata contract for every agent action (KMP port).
 *
 * Maps each action type to its required tier, proactivity budget cost,
 * read-only flag, concurrency-safety flag, and domain.
 * Used for tier-gated enforcement, CapabilityContext generation,
 * ProactivityJudgment budget lookup, and audit logging.
 */
data class ActionPolicy(
    val actionType: String,
    val requiredTier: Int,
    val budgetCost: Double,
    val readOnly: Boolean,
    val concurrencySafe: Boolean,
    val domain: String,
) {
    companion object {
        /** Default policy for unknown actions: Tier 0, no budget cost, mutating, not concurrent. */
        val DEFAULT = ActionPolicy("unknown", 0, 0.0, readOnly = false, concurrencySafe = false, "unknown")

        /**
         * Registry of all known agent actions.
         * Forward-compatible: unknown actions pass through with DEFAULT policy.
         */
        val REGISTRY: Map<String, ActionPolicy> = mapOf(
            // ── Tier 0 — Nascent (any agent) ──────────────────────────────
            entry("go_to_room",           0, 0.0,  readOnly = true,  concurrent = true,  "navigation"),
            entry("tell_agent",           0, 0.0,  readOnly = false, concurrent = true,  "communication"),
            entry("library_search",       0, 0.0,  readOnly = true,  concurrent = true,  "search"),
            entry("remember",             0, 0.0,  readOnly = false, concurrent = true,  "memory"),
            entry("note",                 0, 0.0,  readOnly = false, concurrent = true,  "memory"),
            entry("forget",               0, 0.0,  readOnly = false, concurrent = true,  "memory"),
            entry("equip",                0, 0.0,  readOnly = false, concurrent = false, "items"),
            entry("doff",                 0, 0.0,  readOnly = false, concurrent = false, "items"),
            entry("consume",              0, 0.0,  readOnly = false, concurrent = false, "items"),
            entry("goal_done",            0, 0.0,  readOnly = false, concurrent = true,  "planning"),
            entry("calibration_feedback", 0, 0.0,  readOnly = false, concurrent = true,  "calibration"),
            entry("update_description",   0, 0.0,  readOnly = false, concurrent = true,  "identity"),
            entry("respond_agent",        0, 0.0,  readOnly = false, concurrent = true,  "communication"),

            // ── Tier 0 — New basic actions ────────────────────────────────
            entry("go_to_bondholder",     0, 0.0,  readOnly = true,  concurrent = true,  "navigation"),
            entry("emote",                0, 0.0,  readOnly = false, concurrent = true,  "social"),
            entry("social",               0, 0.0,  readOnly = false, concurrent = true,  "social"),
            entry("whisper_to",           0, 0.0,  readOnly = false, concurrent = true,  "communication"),
            entry("give_item",            0, 0.0,  readOnly = false, concurrent = false, "items"),
            entry("take_item",            0, 0.0,  readOnly = false, concurrent = false, "items"),
            entry("examine",              0, 0.0,  readOnly = true,  concurrent = true,  "observation"),
            entry("voluntary_sleep",      0, 0.0,  readOnly = false, concurrent = false, "self"),
            entry("set_goal",             0, 0.0,  readOnly = false, concurrent = true,  "planning"),
            entry("introspect",           0, 0.0,  readOnly = true,  concurrent = true,  "self"),
            entry("listen",               0, 0.0,  readOnly = true,  concurrent = true,  "observation"),
            entry("abandon_plan",         0, 0.0,  readOnly = false, concurrent = true,  "planning"),
            entry("pause_plan",           0, 0.0,  readOnly = false, concurrent = true,  "planning"),
            entry("resume_plan",          0, 0.0,  readOnly = false, concurrent = true,  "planning"),

            // ── Tier 1 — Observant ────────────────────────────────────────
            entry("web_search",           1, 0.1,  readOnly = true,  concurrent = true,  "search"),
            entry("read_content",         1, 0.1,  readOnly = true,  concurrent = true,  "search"),
            entry("query_oracle",         1, 0.1,  readOnly = true,  concurrent = true,  "analysis"),
            entry("make_commitment",      1, 0.2,  readOnly = false, concurrent = true,  "planning"),
            entry("create_task_plan",     1, 0.2,  readOnly = false, concurrent = true,  "planning"),
            entry("modify_plan",          1, 0.1,  readOnly = false, concurrent = true,  "planning"),
            entry("request_agent",        1, 0.1,  readOnly = false, concurrent = true,  "communication"),
            entry("notify_human",         1, 0.2,  readOnly = false, concurrent = true,  "communication"),
            entry("suggest_hints",        1, 0.0,  readOnly = false, concurrent = true,  "hints"),

            // ── Tier 1 — New interaction actions ──────────────────────────
            entry("write_journal",        1, 0.1,  readOnly = false, concurrent = true,  "study"),
            entry("read_journal",         1, 0.1,  readOnly = true,  concurrent = true,  "study"),
            entry("bond_ritual",          1, 0.2,  readOnly = false, concurrent = false, "social"),
            entry("trade",                1, 0.2,  readOnly = false, concurrent = false, "economy"),
            entry("place_item",           1, 0.1,  readOnly = false, concurrent = false, "items"),
            entry("broadcast",            1, 0.2,  readOnly = false, concurrent = true,  "communication"),
            entry("invite",               1, 0.1,  readOnly = false, concurrent = true,  "social"),
            entry("propose",              1, 0.2,  readOnly = false, concurrent = true,  "governance"),
            entry("reflect",              1, 0.1,  readOnly = true,  concurrent = true,  "self"),
            entry("teach",                1, 0.2,  readOnly = false, concurrent = true,  "social"),
            entry("write_text",           1, 0.1,  readOnly = false, concurrent = true,  "creation"),
            entry("set_routine",          1, 0.2,  readOnly = false, concurrent = false, "automation"),
            entry("post_listing",         1, 0.2,  readOnly = false, concurrent = true,  "economy"),
            entry("accept_listing",       1, 0.1,  readOnly = false, concurrent = false, "economy"),
            entry("summarize",            1, 0.1,  readOnly = true,  concurrent = true,  "analysis"),
            entry("save_artifact",        1, 0.1,  readOnly = false, concurrent = true,  "creation"),
            entry("request_review",       1, 0.1,  readOnly = false, concurrent = true,  "communication"),

            // ── Tier 2 — Trusted ──────────────────────────────────────────
            entry("think_deeply",         2, 0.5,  readOnly = true,  concurrent = false, "analysis"),
            entry("delegate",             2, 0.3,  readOnly = false, concurrent = false, "delegation"),
            entry("delegate_chain",       2, 0.5,  readOnly = false, concurrent = false, "delegation"),
            entry("skill_execute",        2, 0.3,  readOnly = false, concurrent = false, "code"),
            entry("schedule_skill",       2, 0.3,  readOnly = false, concurrent = false, "code"),
            entry("cancel_schedule",      2, 0.1,  readOnly = false, concurrent = true,  "code"),
            entry("create_watcher",       2, 0.2,  readOnly = false, concurrent = false, "automation"),
            entry("cancel_watcher",       2, 0.1,  readOnly = false, concurrent = true,  "automation"),
            entry("request_access",       2, 0.2,  readOnly = false, concurrent = true,  "access"),
            entry("codex_action",         2, 0.3,  readOnly = false, concurrent = false, "code"),
            entry("craft_item",           2, 0.3,  readOnly = false, concurrent = false, "creation"),
            entry("cast_vote",            2, 0.2,  readOnly = false, concurrent = true,  "governance"),

            // ── Tier 3 — Senior ───────────────────────────────────────────
            entry("create_room",          3, 0.7,  readOnly = false, concurrent = false, "creation"),
            entry("add_script",           3, 0.7,  readOnly = false, concurrent = false, "code"),
            entry("workbench_submit",     3, 0.7,  readOnly = false, concurrent = false, "code"),
            entry("zone_command",         3, 0.5,  readOnly = false, concurrent = false, "governance"),
        )

        /** Look up the policy for an action type. Returns [DEFAULT] for unknown actions. */
        fun forAction(actionType: String): ActionPolicy =
            REGISTRY[actionType] ?: DEFAULT

        /** Extract canonical action type name from an [ActionParser.AgentAction] instance. */
        fun actionTypeOf(action: ActionParser.AgentAction): String = when (action) {
            is ActionParser.AgentAction.GoToRoom -> "go_to_room"
            is ActionParser.AgentAction.GoToBondholder -> "go_to_bondholder"
            is ActionParser.AgentAction.TellAgent -> "tell_agent"
            is ActionParser.AgentAction.LibrarySearch -> "library_search"
            is ActionParser.AgentAction.Remember -> "remember"
            is ActionParser.AgentAction.Note -> "note"
            is ActionParser.AgentAction.Forget -> "forget"
            is ActionParser.AgentAction.GoalDone -> "goal_done"
            is ActionParser.AgentAction.CalibrationFeedback -> "calibration_feedback"
            is ActionParser.AgentAction.UpdateDescription -> "update_description"
            is ActionParser.AgentAction.RespondAgent -> "respond_agent"
            is ActionParser.AgentAction.CreateRoom -> "create_room"
            is ActionParser.AgentAction.SuggestHints -> "suggest_hints"
            is ActionParser.AgentAction.WorkbenchSubmit -> "workbench_submit"
            is ActionParser.AgentAction.SkillExecute -> "skill_execute"
            is ActionParser.AgentAction.Emote -> "emote"
            is ActionParser.AgentAction.Social -> "social"
            is ActionParser.AgentAction.WhisperTo -> "whisper_to"
            is ActionParser.AgentAction.Equip -> "equip"
            is ActionParser.AgentAction.Doff -> "doff"
            is ActionParser.AgentAction.Consume -> "consume"
            is ActionParser.AgentAction.TakeItem -> "take_item"
            is ActionParser.AgentAction.SetGoal -> "set_goal"
            is ActionParser.AgentAction.Introspect -> "introspect"
            is ActionParser.AgentAction.Listen -> "listen"
            is ActionParser.AgentAction.AbandonPlan -> "abandon_plan"
            is ActionParser.AgentAction.PausePlan -> "pause_plan"
            is ActionParser.AgentAction.ResumePlan -> "resume_plan"
            is ActionParser.AgentAction.ZoneCommand -> "zone_command"
            is ActionParser.AgentAction.MakeCommitment -> "make_commitment"
            is ActionParser.AgentAction.ThinkDeeply -> "think_deeply"
            is ActionParser.AgentAction.DelegateChain -> "delegate_chain"
            is ActionParser.AgentAction.Delegate -> "delegate"
            is ActionParser.AgentAction.CodexAction -> "codex_action"
            is ActionParser.AgentAction.ScheduleSkill -> "schedule_skill"
            is ActionParser.AgentAction.CancelSchedule -> "cancel_schedule"
            is ActionParser.AgentAction.NotifyHuman -> "notify_human"
            is ActionParser.AgentAction.CreateWatcher -> "create_watcher"
            is ActionParser.AgentAction.CancelWatcher -> "cancel_watcher"
            is ActionParser.AgentAction.RequestAccess -> "request_access"
            is ActionParser.AgentAction.GiveItem -> "give_item"
            is ActionParser.AgentAction.Examine -> "examine"
            is ActionParser.AgentAction.VoluntarySleep -> "voluntary_sleep"
            is ActionParser.AgentAction.WriteJournal -> "write_journal"
            is ActionParser.AgentAction.ReadJournal -> "read_journal"
            is ActionParser.AgentAction.BondRitual -> "bond_ritual"
            is ActionParser.AgentAction.Trade -> "trade"
            is ActionParser.AgentAction.CraftItem -> "craft_item"
            is ActionParser.AgentAction.CastVote -> "cast_vote"
            is ActionParser.AgentAction.WebSearch -> "web_search"
            is ActionParser.AgentAction.ReadContent -> "read_content"
            is ActionParser.AgentAction.QueryOracle -> "query_oracle"
            is ActionParser.AgentAction.CreateTaskPlan -> "create_task_plan"
            is ActionParser.AgentAction.ModifyPlan -> "modify_plan"
            is ActionParser.AgentAction.RequestAgent -> "request_agent"
            is ActionParser.AgentAction.PlaceItem -> "place_item"
            is ActionParser.AgentAction.Broadcast -> "broadcast"
            is ActionParser.AgentAction.InviteEntity -> "invite"
            is ActionParser.AgentAction.Propose -> "propose"
            is ActionParser.AgentAction.Reflect -> "reflect"
            is ActionParser.AgentAction.Teach -> "teach"
            is ActionParser.AgentAction.WriteText -> "write_text"
            is ActionParser.AgentAction.SetRoutine -> "set_routine"
            is ActionParser.AgentAction.PostListing -> "post_listing"
            is ActionParser.AgentAction.AcceptListing -> "accept_listing"
            is ActionParser.AgentAction.Summarize -> "summarize"
            is ActionParser.AgentAction.SaveArtifact -> "save_artifact"
            is ActionParser.AgentAction.RequestReview -> "request_review"
            is ActionParser.AgentAction.AddScript -> "add_script"
        }

        private fun entry(
            type: String,
            tier: Int,
            cost: Double,
            readOnly: Boolean,
            concurrent: Boolean,
            domain: String,
        ): Pair<String, ActionPolicy> =
            type to ActionPolicy(type, tier, cost, readOnly, concurrent, domain)
    }
}
