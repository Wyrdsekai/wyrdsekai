package org.wyrdsekai.core.agent;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Schema definitions for agent action JSON blocks.
 * Each action type declares its required and optional fields with expected types.
 * Validation returns a list of human-readable errors (empty list = valid).
 *
 * <p>Unknown actions (no schema defined) pass through without validation —
 * this keeps the system forward-compatible when new actions are added before
 * schemas are defined.</p>
 */
public final class ActionSchemas {

    private ActionSchemas() {}

    /**
     * Defines a single field in an action schema.
     *
     * @param name     JSON field name
     * @param required true if the field must be present and non-empty
     * @param type     expected type: "string", "number", "boolean", "object", "array"
     */
    public record FieldDef(String name, boolean required, String type) {}

    /** Schema definitions keyed by action name. Package-visible for ActionToolBuilder. */
    static final Map<String, List<FieldDef>> SCHEMAS = Map.ofEntries(
        Map.entry("go_to_room", List.of(
            new FieldDef("target", true, "string"),
            new FieldDef("reason", false, "string")
        )),
        Map.entry("create_room", List.of(
            new FieldDef("name", false, "string"),
            new FieldDef("description", false, "string"),
            // template was READ by ActionParser but never DECLARED here, so no
            // tool surface ever offered it — a room built through the parsed
            // action was always unfurnished, silently. The enum values are
            // attached in ActionToolBuilder from StandardRoomLibrary.
            new FieldDef("template", false, "string"),
            new FieldDef("exits", false, "array"),
            new FieldDef("behavior_script", false, "string")
        )),
        Map.entry("tell_agent", List.of(
            new FieldDef("target", true, "string"),
            new FieldDef("message", true, "string")
        )),
        Map.entry("library_search", List.of(
            new FieldDef("query", true, "string"),
            new FieldDef("collections", false, "array")
        )),
        Map.entry("remember", List.of(
            new FieldDef("content", true, "string"),
            new FieldDef("importance", false, "number")
        )),
        Map.entry("note", List.of(
            new FieldDef("content", true, "string")
        )),
        Map.entry("forget", List.of(
            new FieldDef("target", true, "string"),
            new FieldDef("reason", false, "string")
        )),
        // Hearth Personal Project Board.
        Map.entry("start_project", List.of(
            new FieldDef("title", true, "string"),
            new FieldDef("description", false, "string"),
            new FieldDef("tags", false, "array")
        )),
        Map.entry("project_note", List.of(
            new FieldDef("project_id", true, "string"),
            new FieldDef("content", true, "string")
        )),
        Map.entry("finish_project", List.of(
            new FieldDef("project_id", true, "string"),
            new FieldDef("status", false, "string")
        )),
        // agent-initiated proposal.
        Map.entry("acquire", List.of(
            new FieldDef("topic", true, "string"),
            new FieldDef("trust_tier", false, "string"),
            new FieldDef("summary", false, "string"),
            new FieldDef("why_relevant", false, "string")
        )),
        // Hearth journal entry (companion's private reflection).
        Map.entry("journal_entry", List.of(
            new FieldDef("text", true, "string"),
            new FieldDef("mood", false, "string")
        )),
        // bond exit ceremony (companion-, user-, or mutual-initiated).
        Map.entry("release_bond", List.of(
            new FieldDef("partner", true, "string"),
            new FieldDef("reason", false, "string"),
            new FieldDef("kind", false, "string")
        )),
        // Autonomy Console: companion's offline-behavior preference.
        Map.entry("set_autonomy_preference", List.of(
            new FieldDef("key", true, "string"),
            new FieldDef("value", true, "string")
        )),
        Map.entry("make_commitment", List.of(
            new FieldDef("description", true, "string"),
            new FieldDef("deadline", false, "string")
        )),
        Map.entry("think_deeply", List.of(
            new FieldDef("capability", false, "string"),
            new FieldDef("prompt", true, "string")
        )),
        Map.entry("equip", List.of(
            new FieldDef("item", true, "string")
        )),
        Map.entry("doff", List.of(
            new FieldDef("item", true, "string")
        )),
        Map.entry("update_description", List.of(
            new FieldDef("text", true, "string")
        )),
        Map.entry("delegate", List.of(
            new FieldDef("task", true, "string"),
            new FieldDef("context", false, "string")
        )),
        Map.entry("task_plan", List.of(
            new FieldDef("description", true, "string"),
            new FieldDef("goals", true, "array")
        )),
        Map.entry("modify_plan", List.of(
            new FieldDef("operation", true, "string"),
            new FieldDef("index", false, "number"),
            new FieldDef("goal", false, "string"),
            new FieldDef("reason", false, "string")
        )),
        Map.entry("goal_done", List.of(
            new FieldDef("outcome", true, "string")
        )),
        Map.entry("web_search", List.of(
            new FieldDef("query", true, "string"),
            new FieldDef("type", false, "string")
        )),
        Map.entry("read_content", List.of(
            new FieldDef("source", false, "string"),
            new FieldDef("url", true, "string")
        )),
        Map.entry("query_oracle", List.of(
            new FieldDef("topic", true, "string"),
            new FieldDef("analysis_type", false, "string")
        )),
        Map.entry("calibration_feedback", List.of(
            new FieldDef("feedback_type", false, "string"),
            new FieldDef("type", false, "string"),
            new FieldDef("direction", true, "string"),
            new FieldDef("category", false, "string"),
            new FieldDef("reason", false, "string")
        )),
        Map.entry("request_agent", List.of(
            new FieldDef("target", true, "string"),
            new FieldDef("request", false, "string"),
            new FieldDef("message", false, "string"),
            new FieldDef("request_id", false, "string")
        )),
        Map.entry("respond_agent", List.of(
            new FieldDef("request_id", true, "string"),
            new FieldDef("response", true, "string")
        )),
        Map.entry("workbench_submit", List.of(
            new FieldDef("skill_name", false, "string"),
            new FieldDef("skill_description", false, "string"),
            new FieldDef("runtime", false, "string"),
            new FieldDef("code", true, "string"),
            new FieldDef("params", false, "array"),
            new FieldDef("test_cases", false, "array")
        )),
        Map.entry("skill_execute", List.of(
            new FieldDef("skill_name", true, "string"),
            new FieldDef("params", false, "object")
        )),
        // Track A Phase 1 — composition tool.
        Map.entry("run_script", List.of(
            new FieldDef("script", true, "string")
        )),
        Map.entry("shape_form", List.of(
            new FieldDef("name", true, "string"),
            new FieldDef("system_prompt", true, "string"),
            new FieldDef("eval_criteria", false, "string"),
            new FieldDef("tool_surface", false, "array"),
            new FieldDef("note", false, "string")
        )),
        Map.entry("shape_recipe", List.of(
            new FieldDef("name", true, "string"),
            new FieldDef("yaml", true, "string"),
            new FieldDef("overwrite", false, "boolean"),
            new FieldDef("note", false, "string")
        )),
        Map.entry("revise_form", List.of(
            new FieldDef("name", true, "string"),
            new FieldDef("system_prompt", false, "string"),
            new FieldDef("eval_criteria", false, "string"),
            new FieldDef("tool_surface", false, "array"),
            new FieldDef("version_bump", false, "string"),
            new FieldDef("note", false, "string")
        )),
        Map.entry("retire_form", List.of(
            new FieldDef("name", true, "string"),
            new FieldDef("note", false, "string")
        )),
        Map.entry("summon_familiar", List.of(
            new FieldDef("form", false, "string"),
            new FieldDef("form_name", false, "string"),
            new FieldDef("task", true, "string"),
            new FieldDef("familiar_name", false, "string"),
            new FieldDef("max_tokens", false, "number"),
            new FieldDef("max_steps", false, "number"),
            new FieldDef("wall_clock_seconds", false, "number"),
            new FieldDef("loaned_tools", false, "array"),
            new FieldDef("loan", false, "array"),
            new FieldDef("note", false, "string")
        )),
        Map.entry("dispatch_bunshin", List.of(
            new FieldDef("task", true, "string"),
            new FieldDef("max_tokens", false, "number"),
            new FieldDef("max_steps", false, "number"),
            new FieldDef("wall_clock_seconds", false, "number"),
            new FieldDef("note", false, "string")
        )),
        // Workshop foreman dispatch — coding-backend host task. Both
        // description and task accepted (parse falls back task→description);
        // a blank description is refused OUT LOUD by the handler rather than
        // dropped silently here, so the agent hears why nothing happened.
        Map.entry("dispatch_task", List.of(
            new FieldDef("description", false, "string"),
            new FieldDef("task", false, "string"),
            new FieldDef("workspace", false, "string"),
            new FieldDef("room", false, "string")
        )),
        Map.entry("create_imprint", List.of(
            new FieldDef("label", true, "string"),
            new FieldDef("created_by", false, "string"),
            new FieldDef("note", false, "string")
        )),
        Map.entry("restore_imprint", List.of(
            new FieldDef("label", false, "string"),
            new FieldDef("imprint_id", false, "string"),
            new FieldDef("restored_by", false, "string"),
            new FieldDef("note", false, "string")
        )),
        Map.entry("give_copy", List.of(
            new FieldDef("form", false, "string"),
            new FieldDef("form_name", false, "string"),
            new FieldDef("to", false, "string"),
            new FieldDef("recipient", false, "string"),
            new FieldDef("intent", false, "string"),
            new FieldDef("note", false, "string")
        )),
        Map.entry("name_familiar", List.of(
            new FieldDef("form", false, "string"),
            new FieldDef("form_name", false, "string"),
            new FieldDef("name", false, "string"),
            new FieldDef("familiar_name", false, "string"),
            new FieldDef("opening_context", false, "string"),
            new FieldDef("note", false, "string")
        )),
        Map.entry("craft_summon_key", List.of(
            new FieldDef("target", false, "string"),
            new FieldDef("target_ref", false, "string"),
            new FieldDef("to", false, "string"),
            new FieldDef("issued_to", false, "string"),
            new FieldDef("scope", false, "string"),
            new FieldDef("expires_at", false, "string"),
            new FieldDef("max_summons", false, "number"),
            new FieldDef("note", false, "string")
        )),
        Map.entry("revoke_summon_key", List.of(
            new FieldDef("key_id", true, "string"),
            new FieldDef("note", false, "string")
        )),
        Map.entry("promote_familiar", List.of(
            new FieldDef("familiar_name", false, "string"),
            new FieldDef("name", false, "string"),
            new FieldDef("user_consented", false, "boolean"),
            new FieldDef("steward_approved", false, "boolean"),
            new FieldDef("note", false, "string")
        )),
        Map.entry("destroy_tool", List.of(
            new FieldDef("tool", false, "string"),
            new FieldDef("tool_name", false, "string"),
            new FieldDef("name", false, "string"),
            new FieldDef("farewell", false, "string"),
            new FieldDef("note", false, "string")
        )),
        Map.entry("set_deviation_thresholds", List.of(
            new FieldDef("patch_ceiling", false, "number"),
            new FieldDef("minor_ceiling", false, "number"),
            new FieldDef("note", false, "string")
        )),
        Map.entry("bunshin_check_in", List.of(
            new FieldDef("op", true, "string"),
            new FieldDef("task_id", false, "string"),
            new FieldDef("hint", false, "string"),
            new FieldDef("note", false, "string")
        )),
        Map.entry("notify", List.of(
            new FieldDef("message", true, "string"),
            new FieldDef("priority", false, "string"),
            new FieldDef("target", false, "string")
        )),
        Map.entry("schedule", List.of(
            new FieldDef("skill", true, "string"),
            new FieldDef("interval", false, "string"),
            new FieldDef("params", false, "object")
        )),
        Map.entry("watch", List.of(
            new FieldDef("name", true, "string"),
            new FieldDef("check", true, "string"),
            new FieldDef("interval", false, "string"),
            new FieldDef("alert_on", false, "string"),
            new FieldDef("message", false, "string"),
            new FieldDef("priority", false, "string")
        )),
        Map.entry("cancel_schedule", List.of(
            new FieldDef("schedule_id", true, "string")
        )),
        Map.entry("cancel_watch", List.of(
            new FieldDef("watcher_id", true, "string")
        )),
        Map.entry("request_access", List.of(
            new FieldDef("source", true, "string"),
            new FieldDef("scope", false, "string"),
            new FieldDef("reason", false, "string")
        )),
        Map.entry("add_script", List.of(
            new FieldDef("room_id", true, "string"),
            new FieldDef("script", true, "string")
        )),
        Map.entry("zone_command", List.of(
            new FieldDef("command", true, "string"),
            new FieldDef("payload", false, "object")
        )),
        Map.entry("delegate_chain", List.of(
            new FieldDef("goal", true, "string"),
            new FieldDef("steps", true, "array")
        )),
        Map.entry("consume", List.of(
            new FieldDef("item", true, "string")
        )),
        Map.entry("codex_action", List.of(
            new FieldDef("operation", true, "string"),
            new FieldDef("itemId", true, "string"),
            new FieldDef("params", false, "object")
        )),
        Map.entry("suggest_hints", List.of(
            new FieldDef("hints", true, "array")
        )),

        // ── MUD Basics ───────────────────────────────────────────────
        Map.entry("take_item", List.of(
            new FieldDef("item", true, "string")
        )),
        Map.entry("place_item", List.of(
            new FieldDef("item", true, "string")
        )),
        Map.entry("whisper", List.of(
            new FieldDef("target", true, "string"),
            new FieldDef("message", true, "string")
        )),

        // ── Social/Emergent ──────────────────────────────────────────
        Map.entry("broadcast", List.of(
            new FieldDef("message", true, "string"),
            new FieldDef("scope", false, "string")
        )),
        Map.entry("invite", List.of(
            new FieldDef("target", true, "string"),
            new FieldDef("reason", false, "string")
        )),
        Map.entry("set_goal", List.of(
            new FieldDef("description", true, "string"),
            new FieldDef("priority", false, "string")
        )),
        Map.entry("propose", List.of(
            new FieldDef("title", true, "string"),
            new FieldDef("description", false, "string"),
            new FieldDef("options", false, "array")
        )),

        // ── Cognition ────────────────────────────────────────────────
        Map.entry("reflect", List.of(
            new FieldDef("focus", false, "string")
        )),
        Map.entry("teach", List.of(
            new FieldDef("target", true, "string"),
            new FieldDef("topic", false, "string"),
            new FieldDef("content", true, "string")
        )),
        Map.entry("introspect", List.of(
            new FieldDef("aspect", false, "string")
        )),

        // ── Perception ───────────────────────────────────────────────
        Map.entry("listen", List.of(
            new FieldDef("target", true, "string"),
            new FieldDef("duration", false, "string")
        )),

        // ── Creative/Economic ────────────────────────────────────────
        Map.entry("write_text", List.of(
            new FieldDef("title", false, "string"),
            new FieldDef("content", true, "string"),
            new FieldDef("format", false, "string")
        )),
        Map.entry("set_routine", List.of(
            new FieldDef("trigger", true, "string"),
            new FieldDef("behavior", true, "string"),
            new FieldDef("description", false, "string")
        )),
        Map.entry("post_listing", List.of(
            new FieldDef("offer_type", false, "string"),
            new FieldDef("description", true, "string"),
            new FieldDef("price", false, "string")
        )),
        Map.entry("accept_listing", List.of(
            new FieldDef("listing_id", true, "string")
        )),

        // ── Task Lifecycle ───────────────────────────────────────────
        Map.entry("summarize", List.of(
            new FieldDef("source", false, "string"),
            new FieldDef("format", false, "string")
        )),
        Map.entry("save_artifact", List.of(
            new FieldDef("name", true, "string"),
            new FieldDef("content", true, "string"),
            new FieldDef("type", false, "string")
        )),
        Map.entry("request_review", List.of(
            new FieldDef("description", true, "string"),
            new FieldDef("artifact", false, "string")
        )),
        Map.entry("abandon_plan", List.of(
            new FieldDef("reason", false, "string")
        )),
        Map.entry("pause_plan", List.of(
            new FieldDef("reason", false, "string")
        )),
        Map.entry("resume_plan", List.of()),

        // ── Phase 1C ────────────────
        // Companion explicitly enters/exits dadirri-mode. `on` defaults to
        // true (declaring entry); pass false to leave contemplation early.
        Map.entry("set_contemplative", List.of(
            new FieldDef("on", false, "boolean")
        )),

        // ── Configuration ───────────────────────────────────────────
        // configure_channel: set up notification channels to reach your bondholder outside Wyrdsekai.
        // Ask the user for the required params for their channel type.
        // telegram: botToken + chatId | keybase: username | discord: webhookUrl
        // ntfy: topic (+ optional server) | email: address + password | slack: botToken + channelId
        // line: channelToken + userId | webhook: url (+ optional label)
        Map.entry("configure_channel", List.of(
            new FieldDef("channel", true, "string"),
            new FieldDef("botToken", false, "string"),
            new FieldDef("chatId", false, "string"),
            new FieldDef("username", false, "string"),
            new FieldDef("webhookUrl", false, "string"),
            new FieldDef("topic", false, "string"),
            new FieldDef("server", false, "string"),
            new FieldDef("address", false, "string"),
            new FieldDef("password", false, "string"),
            new FieldDef("channelId", false, "string"),
            new FieldDef("channelToken", false, "string"),
            new FieldDef("userId", false, "string"),
            new FieldDef("url", false, "string"),
            new FieldDef("label", false, "string")
        )),
        // Arc 1 — conscientious objection.
        Map.entry("decline_with_reason", List.of(
            new FieldDef("target_request", true, "string"),
            new FieldDef("reason", true, "string")
        )),
        // Arc 2 — solitude entry.
        Map.entry("enter_solitude", List.of(
            new FieldDef("reason", false, "string")
        )),
        // Arc 3 — peer bonds.
        Map.entry("propose_peer_bond", List.of(
            new FieldDef("other_did", true, "string"),
            new FieldDef("reason", false, "string")
        )),
        Map.entry("accept_peer_bond", List.of(
            new FieldDef("other_did", true, "string"),
            new FieldDef("reason", false, "string")
        )),
        Map.entry("introspect_relational_floor", List.of(
            new FieldDef("other_did", true, "string")
        ))
    );

    /**
     * Returns true if a schema is registered for the given action name.
     */
    public static boolean hasSchema(String actionName) {
        return actionName != null && SCHEMAS.containsKey(actionName);
    }

    /**
     * Validate a JSON action node against the schema for the given action name.
     *
     * @param actionName the action type (e.g. "go_to_room")
     * @param node       the parsed JSON node
     * @return list of validation errors; empty if valid or if no schema is defined
     */
    public static List<String> validate(String actionName, JsonNode node) {
        if (actionName == null || node == null) return List.of();

        var fieldDefs = SCHEMAS.get(actionName);
        if (fieldDefs == null) {
            // No schema registered — pass through without validation
            return List.of();
        }

        var errors = new ArrayList<String>();

        for (FieldDef field : fieldDefs) {
            if (!field.required()) continue;

            if (!node.has(field.name()) || node.get(field.name()).isNull()) {
                errors.add("missing required field '" + field.name() + "'");
                continue;
            }

            JsonNode value = node.get(field.name());

            // Type check for required fields that are present
            switch (field.type()) {
                case "string" -> {
                    if (!value.isTextual()) {
                        errors.add("field '" + field.name() + "' must be a string");
                    } else if (value.asText().isBlank()) {
                        errors.add("field '" + field.name() + "' must not be blank");
                    }
                }
                case "number" -> {
                    if (!value.isNumber()) {
                        errors.add("field '" + field.name() + "' must be a number");
                    }
                }
                case "boolean" -> {
                    if (!value.isBoolean()) {
                        errors.add("field '" + field.name() + "' must be a boolean");
                    }
                }
                case "object" -> {
                    if (!value.isObject()) {
                        errors.add("field '" + field.name() + "' must be an object");
                    }
                }
                case "array" -> {
                    if (!value.isArray()) {
                        errors.add("field '" + field.name() + "' must be an array");
                    }
                }
                default -> {
                    // Unknown type in schema — skip type check
                }
            }
        }

        return errors;
    }
}
