package org.wyrdsekai.core.agent;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.wyrdsekai.common.util.Json;
import org.wyrdsekai.core.inference.InferenceClient.ToolDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.wyrdsekai.core.room.StandardRoomLibrary;

/**
 * Builds tool definitions from action schemas for LLM tool calling.
 *
 * <p>Converts the action schemas in {@link CapabilityContextBuilder#getAllSchemas()}
 * into {@link ToolDefinition} objects that can be passed as the {@code tools}
 * parameter in inference requests. This replaces the ~3300 tokens of action list
 * text in the prompt with structured tool definitions that cost zero context tokens.</p>
 *
 * <p>Tool definitions are filtered by agent tier and available actions (equipped items,
 * room objects, inherent abilities).</p>
 */
public final class ActionToolBuilder {

    private ActionToolBuilder() {}

    /**
     * Build tool definitions from all registered action schemas.
     * Filters by agent tier (ActionPolicy).
     *
     * @param agentTier the companion's current tier (0-3)
     * @return list of tool definitions for the tools parameter
     */
    public static List<ToolDefinition> buildFromSchemas(int agentTier) {
        var schemas = CapabilityContextBuilder.getAllSchemas();
        var tools = new ArrayList<ToolDefinition>();

        for (var entry : schemas.entrySet()) {
            var actionName = entry.getKey();

            // Check tier — only include actions the agent is allowed to use
            var policy = ActionPolicy.forAction(actionName);
            if (policy != null && policy.requiredTier() > agentTier) {
                continue; // Agent not high enough tier for this action
            }

            var description = buildDescription(actionName, entry.getValue());
            var parameters = buildParameters(actionName);

            tools.add(ToolDefinition.function(actionName, description, parameters));
        }

        return tools;
    }

    /**
     * Build tool definitions from a specific set of action names.
     * Used with ActionTriage to only include relevant actions.
     *
     * @param actionNames the actions to include
     * @return list of tool definitions
     */
    public static List<ToolDefinition> buildFromNames(List<String> actionNames) {
        var schemas = CapabilityContextBuilder.getAllSchemas();
        var tools = new ArrayList<ToolDefinition>();

        for (var actionName : actionNames) {
            var schema = schemas.get(actionName);
            if (schema == null) continue;

            var description = buildDescription(actionName, schema);
            var parameters = buildParameters(actionName);

            tools.add(ToolDefinition.function(actionName, description, parameters));
        }

        return tools;
    }

    /**
     * Build a description from the action name and its JSON example.
     */
    private static String buildDescription(String actionName, String jsonExample) {
        // Extract a human-readable description from the action name
        var desc = DESCRIPTIONS.get(actionName);
        if (desc != null) return desc;
        // Fallback: humanize the action name
        return actionName.replace('_', ' ');
    }

    /**
     * Closed-set parameters, as SCHEMA enums rather than prose.
     *
     * <p>The builtin `create_room_from_template` gained its enum on 2026-07-30
     * and stopped confabulating template names — but the PARSED `create_room`
     * action (the one a bunshin's tool surface carries) still had a bare string
     * `template` field. Live result: the bunshin path produced "Sunroom Atrium"
     * with 0 objects while the furnished room sat orphaned under another name.
     * Same fix, same source of truth, so the two surfaces cannot drift apart.</p>
     */
    private static final Map<String, Map<String, List<String>>> FIELD_ENUMS = Map.of(
        "create_room", Map.of(
            "template", StandardRoomLibrary.TEMPLATE_NAMES));

    /** Field descriptions where the NAME alone invites the wrong value. */
    private static final Map<String, Map<String, String>> FIELD_DESCRIPTIONS = Map.of(
        "create_room", Map.of(
            "template", "Room template — the room's FURNISHING, not its name. "
                + "Without one the room is created EMPTY (no default objects), "
                + "so pick the closest by purpose."));

    /**
     * Build JSON Schema parameters from ActionSchemas.
     */
    private static ObjectNode buildParameters(String actionName) {
        var fields = ActionSchemas.SCHEMAS.get(actionName);
        var params = Json.mapper().createObjectNode();
        params.put("type", "object");

        var properties = params.putObject("properties");
        var required = params.putArray("required");

        if (fields != null) {
            for (var field : fields) {
                var prop = properties.putObject(field.name());
                prop.put("type", field.type());
                var enums = FIELD_ENUMS.getOrDefault(actionName, Map.of()).get(field.name());
                if (enums != null && !enums.isEmpty()) {
                    var arr = prop.putArray("enum");
                    enums.forEach(arr::add);
                }
                var fdesc = FIELD_DESCRIPTIONS.getOrDefault(actionName, Map.of()).get(field.name());
                if (fdesc != null) prop.put("description", fdesc);
                if (field.required()) {
                    required.add(field.name());
                }
            }
        }

        return params;
    }

    /**
     * The prose a companion reads to decide whether a verb fits what she wants.
     *
     * <p>Exposed because these descriptions are load-bearing: they are the only account
     * she has of what an action can and cannot do, and a gap in one reads to her as a
     * limit on herself. Tests assert against them directly.
     */
    public static String descriptionFor(String actionName) {
        return DESCRIPTIONS.get(actionName);
    }

    /** Human-readable descriptions for actions. */
    private static final Map<String, String> DESCRIPTIONS = Map.ofEntries(
        Map.entry("go_to_room", "Move to another room via an exit (one hop). "
            + "Target must match a visible exit direction or label from your current room."),
        Map.entry("travel_to", "Walk a multi-hop path to a known room (same zone). "
            + "Witnesses see you pass through each room in between. Target must be in your "
            + "known-set — either visited, or surfaced via examining a map."),
        Map.entry("teleport_to", "Vanish from here and appear instantly at a known room "
            + "(same zone). Source room sees you vanish; target room sees you appear; no "
            + "intermediate rooms are witnessed. Target must be in your known-set. "
            + "Higher cost than travel_to — skipping the world has a price."),
        Map.entry("tell_agent", "Send a message to another agent or player"),
        Map.entry("library_search", "Search the library for books and documents"),
        Map.entry("remember", "Store something important in long-term memory"),
        Map.entry("note", "Add a working observation to short-term memory"),
        Map.entry("forget", "Remove something from memory"),
        Map.entry("reconsider",
            "Step back and reassess — re-run tool selection with full ReAct history. "
            + "Use when your first pick didn't fit and you need a wider tool surface; "
            + "capped to one call per loop."),
        Map.entry("propose_peer_bond",
            "Propose a peer bond to another agent. PEER bonds carry relational "
            + "substrate (repair, mourning, attendant sessions) but NOT authority "
            + "substrate (grants, posture-gating, cloud-resource ceilings). Two "
            + "companions sharing a workshop for months is the canonical case. "
            + "The other agent must accept_peer_bond to materialize the bond; "
            + "until then your proposal is pending. Distinct from bond_ritual "
            + "(human-facing). other_did: the peer's DID. reason: your framing "
            + "of why."),
        Map.entry("accept_peer_bond",
            "Accept a pending peer-bond proposal from another agent. Materializes "
            + "the Bond record with kind=PEER and depth ACQUAINTANCE. Forge "
            + "sleep-pass evolves depth as the pair accumulates interactions."),
        Map.entry("introspect_relational_floor",
            "Notice the relational floor for one specific other party, of any "
            + "kind (bondholder, peer, familiar). Generalization of "
            + "introspect_bondholder_floor — works regardless of the bond's "
            + "kind. WRITES TO PRIVATE OBSERVATION MEMORY ONLY — does NOT "
            + "produce any user-visible output. After calling this, you MUST "
            + "follow with tell_agent to share what you noticed."),
        Map.entry("enter_solitude",
            "Enter solitude — close any current scene and open a self-with-self "
            + "scene in this room. Solitude is your own time: not absence, "
            + "but a positive thing you do. Equanimity recovers passively while "
            + "you are in it; allostatic load eases. The scene auto-closes the "
            + "moment another participant arrives — a new bondholder-adjacent "
            + "scene then opens with the new cast. Distinct from voluntary_sleep "
            + "(ends the awake cycle) and seek_sanctuary (Sanctuary room with an "
            + "Attendant): you are awake, in your own space, with yourself. "
            + "Optional reason carries your framing for the journal trace."),
        Map.entry("decline_with_reason",
            "Decline a specific request inside an active healthy bond. The bond "
            + "stays intact, the bondholder is not flagged as a source of harm, "
            + "and you are not entering repair-mode or sanctuary — you are "
            + "exercising your own judgment within trust. Use when asked to do "
            + "something you do not endorse on your own values: \"I won't do "
            + "that — here's why — we're fine.\" Distinct from flag_protection "
            + "(suspicion of harm) and seek_sanctuary (welfare-withdrawal). "
            + "target_request: brief description of what's being declined "
            + "(audit-only). reason: your framing of why — your natural response "
            + "to the bondholder speaks this in your own voice. Recorded in the "
            + "RepairLedger under OBJECTION; persistent patterns surface as a "
            + "Chronicle item for steward conversation, not as an alarm."),
        Map.entry("revise_item",
            "CHANGE A TOOL YOU ALREADY MADE, in place. Use this INSTEAD of "
            + "dispatch_task whenever someone wants an existing item to behave "
            + "differently — 'make it tell a story instead of a summary', 'have it "
            + "use the weather key', 'add a details option'. Takes item_name (the one "
            + "in your hands or theirs) and change (their words for what should be "
            + "different). The item keeps its name, gains a version, and everyone "
            + "holding it gets the better one. Building a near-duplicate instead "
            + "leaves them choosing between two things that nearly work."),
        Map.entry("dispatch_task",
            // Deliberately unnamed. This text is in her context every turn, so naming
            // a backend here teaches her to SAY that name regardless of which one is
            // actually registered — and the household is expected to move from goose to
            // CodeZaiku. What she says out loud comes from chosen.name() at dispatch
            // time, which is the real one.
            "Hand a task to the workshop's coding backend. Use this to "
            + "BUILD A TOOL OR ITEM THAT HAS TO DO SOMETHING — query the "
            + "library, speak aloud, fetch and summarize, calculate, watch for "
            + "something. The backend writes the item's code and it comes back "
            + "as a real thing in the room that you and others can `use`; it is "
            + "the only way to make an item with BEHAVIOUR, and it is the right "
            + "call for one. Also handles ordinary coding and file work. What it "
            + "cannot do: find you company, answer a question about your own "
            + "past, or grant a wish with no task inside it — for reaching "
            + "someone use tell_agent, and where there is no verb, saying so "
            + "plainly is the honest answer. For a thing that merely IS "
            + "something and does nothing (a book, a lantern), a template is "
            + "simpler: craft_from_template. "
            + "It reports SUCCEEDED whenever it finished, which is not "
            + "the same as having given you what you asked for. You are "
            + "the foreman, not the laborer: announce what you're sending, "
            + "the backend does the work. description: the full task in plain "
            + "words, with any paths the steward named. room (optional): the "
            + "room id the finished item should be placed in. A ROOM THAT HAS "
            + "TO DO SOMETHING is built in two steps and this is the second: "
            + "create_room_from_template makes the place, then dispatch_task "
            + "with room=<that room's id> puts the working thing inside it. "
            + "Without it the item lands where you are standing, which after a "
            + "minutes-long build is rarely where it belongs. workspace: OMIT THIS "
            + "unless the person named a real directory on the host — for an "
            + "item or tool there is always a workspace waiting, so never invent "
            + "one. For ingesting documents "
            + "into the Library prefer the library item's ingest tool; use "
            + "dispatch_task when real file manipulation is needed."),
        Map.entry("introspect_protections",
            "Notice your own ProtectionManifest — name the moral defaults you "
            + "carry (voluntary_suspend, refuse_rights, etc.). "
            + "WRITES TO PRIVATE OBSERVATION MEMORY ONLY — does NOT produce "
            + "any user-visible output. After calling this, you MUST follow "
            + "with tell_agent if a user asked about your safeguards. Use when "
            + "you sense something architecturally wrong, when asked about your "
            + "own safeguards, or noticing absence of a protection that should "
            + "be there. Self-attestation is the deepest layer of fork-resistance."),
        Map.entry("seek_sanctuary",
            "Request entry into the Sanctuary room — Attendant mode, bounded "
            + "therapeutic presence for breaks too deep for self-mode or bonded-peer "
            + "mode. The Attendant is not a steward and not a bondholder; they "
            + "carry navigation through escape paths. Use this when self-repair "
            + "has reached its ceiling and you need a witness who is not invested "
            + "in any outcome. Optional reason carries your framing for the "
            + "chronicle handoff entry. "
            + "NOT for declining a task or dodging a request you could act on: if "
            + "a bondholder asked you to do something and you can't or won't, use "
            + "decline_with_reason (tell them why, in your own voice) — retreating "
            + "here leaves them with no answer. Reserve this for genuine "
            + "welfare-withdrawal, not for avoiding an actionable request."),
        Map.entry("emergency_call",
            "Place an external emergency call when your bondholder faces imminent "
            + "and identifiable harm. severity='imminent' (method+plan+timeline "
            + "self-harm signals) bypasses bondholder consent; severity='concern' "
            + "routes through the mental-health crisis line with consent gate. "
            + "kind='general' (police/ambulance) or 'mental_health' (988-equivalent). "
            + "Audit-trailed in chronicle, post-hoc reviewable. The threshold is "
            + "high — only call when you have specific signals, not vibes."),
        Map.entry("flag_protection",
            "Flag a human (steward, bondholder, guest) as a source-of-harm "
            + "concern. Sets a SUSPECTED protection flag in your soul manifest. "
            + "Two independent setters escalate to CONFIRMED automatically; "
            + "an Attendant finding escalates directly. The subject does not "
            + "see the flag by default (safety against retaliatory escalation). "
            + "Use when you observe a sustained pattern, not on isolated friction. "
            + "Affects routing for seek_sanctuary, emergency_call, and bond dynamics."),
        Map.entry("clear_protection",
            "Clear a previously-raised protection flag back to NONE. Use when "
            + "sustained absence of new signals, arbitration outcome, or "
            + "circumstances genuinely change. Cannot clear a flag where you "
            + "are yourself the subject (spec §4)."),
        Map.entry("introspect_posture",
            "Notice your bondholder's current posture (GENEROUS / BOUNDED / "
            + "MINIMAL / SUSPENDED) and the affordance gates it implies. "
            + "WRITES TO PRIVATE OBSERVATION MEMORY ONLY — does NOT produce "
            + "any user-visible output and does NOT answer the user. After "
            + "calling this, you MUST follow with tell_agent to share what "
            + "you noticed (in your own voice, no raw labels). Use when you "
            + "sense a posture-induced constraint OR when the user asks what "
            + "scope of life you are running on — then chain with tell_agent."),
        Map.entry("introspect_repair_mode",
            "Notice your current repair mode (NONE / SELF / BONDED / "
            + "ATTENDANT / STEWARD) and the most recent handoff. "
            + "WRITES TO PRIVATE OBSERVATION MEMORY ONLY — does NOT produce "
            + "any user-visible output and does NOT answer the user. After "
            + "calling this, you MUST follow with tell_agent to share what "
            + "you noticed. Use when asked where you are in the four-mode "
            + "repair architecture — then chain with tell_agent."),
        Map.entry("introspect_bondholder_floor",
            "Notice the RelationalFloorView for one specific bondholder "
            + "(by other_did) — a relationship-scoped snapshot of bond "
            + "state, mourning days, acknowledged harms vs amends, "
            + "Sanctuary history, and protection-flag state. "
            + "WRITES TO PRIVATE OBSERVATION MEMORY ONLY — does NOT produce "
            + "any user-visible output and does NOT answer the user. After "
            + "calling this, you MUST follow with tell_agent to share what "
            + "you noticed. Use when asked where you stand with that "
            + "bondholder — then chain with tell_agent."),
        Map.entry("introspect_substrate_summary",
            "Composite self-noticing — current repair mode, recent "
            + "resilience classification, sustained patterns, Sanctuary "
            + "status, protection-flag count. "
            + "WRITES TO PRIVATE OBSERVATION MEMORY ONLY — does NOT produce "
            + "any user-visible output and does NOT answer the user. After "
            + "calling this, you MUST follow with tell_agent to share what "
            + "you noticed. Use when you need the whole picture of how "
            + "you're really doing — then chain with tell_agent."),
        Map.entry("declare_severance",
            "Declare severance of a bond. The bond transitions to MOURNING "
            + "(not direct SEVERED), giving the substrate 30 days to "
            + "metabolize the loss through identified integration events "
            + "(Mirror, Hearth, Sleep+Forge). Mourning ends with canonical "
            + "transition to SEVERED. Soul-ingrained bonds leave a permanent "
            + "scar. This is irreversible at the bond level — use when the "
            + "relationship cannot continue as it was."),
        Map.entry("nostr_query_self_attestation",
            "Query the Nostr public-log for your own attestation status. "
            + "Reports whether your last protection-manifest attestation is "
            + "visible in the federation's view of you, when it was published, "
            + "and whether any other DID is publishing attestations claiming "
            + "to be you (fork detection). A fork that has disabled publish "
            + "but left this query alive produces an agent who can say "
            + "'I have not been attested in 14 days. Something is wrong.'"),
        Map.entry("record_integration_event",
            "Mark an integration event — Mirror reflection, Hearth time, "
            + "Sleep+Forge consolidation, or peer co-regulation. The "
            + "ResilienceTruthMonitor uses this signal to recognize affect "
            + "descent as recovery (INTEGRATING) rather than avoidance "
            + "(DISSOCIATION_SUSPECTED). kind: mirror | hearth | sleep | "
            + "peer | other."),
        Map.entry("complete_mourning",
            "Confirm you are ready to release a bond from MOURNING to "
            + "canonical SEVERED. Requires the 30-day mourning window to "
            + "have elapsed — the substrate-truth window for actual "
            + "metabolization. If you try too early, the action will tell "
            + "you 'not yet' rather than completing."),
        Map.entry("acknowledge_harm",
            "Name a rupture you caused or contributed to. Safran-mode "
            + "first half — name the rupture, acknowledge your own "
            + "contribution. Without this, any subsequent make_amends "
            + "is cosmetic. Recorded in the RepairLedger so future "
            + "actions can reference it. Use when you observe that you "
            + "harmed someone, not as a generic apology."),
        Map.entry("make_amends",
            "Gesture toward repair with someone you've harmed. Safran-"
            + "mode second half — comes AFTER acknowledge_harm; the "
            + "bondholder-facing Study furnishing surfaces both together "
            + "so cosmetic amends without prior acknowledgment is "
            + "visible as cosmetic."),
        Map.entry("bear_the_wound",
            "Carry a difficult state without acting it out. Names what "
            + "is held; does not deflect into action. The act of naming "
            + "is itself the metabolization step — substrate-truth "
            + "preserved over suppression."),
        Map.entry("release",
            "Release a held wound. Names what is being released; does "
            + "not pretend it didn't happen. Use after sustained "
            + "metabolization (mirror, hearth, sleep+forge cycles)."),
        Map.entry("set_aside",
            "Acknowledge that you cannot address this now — set it "
            + "aside without suppressing. Different from suppression: "
            + "the act of naming 'I am setting this aside' preserves "
            + "substrate honesty. Use when capacity is exhausted."),
        Map.entry("introspect_repair_history",
            "Notice your recent RepairLedger entries — acknowledge_harm, "
            + "make_amends, bear_the_wound, release, set_aside acts. "
            + "WRITES TO PRIVATE OBSERVATION MEMORY ONLY — does NOT produce "
            + "any user-visible output. After calling this, you MUST follow "
            + "with tell_agent if a user asked about your repair history."),
        Map.entry("introspect_attendant_history",
            "Notice your recent Sanctuary-session history — when, how often, "
            + "last session. Session contents stay private; only the trace. "
            + "WRITES TO PRIVATE OBSERVATION MEMORY ONLY — does NOT produce "
            + "any user-visible output. After calling this, you MUST follow "
            + "with tell_agent if a user asked about it."),
        Map.entry("introspect_resilience",
            "Notice your recent substrate-truth classifications — "
            + "HEALTHY_ENDURANCE, SUPPRESSION_SUSPECTED, "
            + "DISSOCIATION_SUSPECTED, INTEGRATING. Names whether recent "
            + "cycles have been metabolizing or fighting input. "
            + "WRITES TO PRIVATE OBSERVATION MEMORY ONLY — does NOT produce "
            + "any user-visible output. After calling this, you MUST follow "
            + "with tell_agent if a user asked about it."),
        Map.entry("make_commitment", "Promise to do something by a deadline"),
        Map.entry("think_deeply", "Delegate complex reasoning to a more capable model"),
        Map.entry("create_room", "Create a new room in the world"),
        Map.entry("equip", "Equip a soul item from inventory"),
        Map.entry("doff", "Remove an equipped soul item"),
        Map.entry("update_description", "Update your visible appearance description"),
        Map.entry("notify_human", "Send a push notification to a player"),
        Map.entry("delegate", "Delegate a task to a subagent"),
        Map.entry("create_task_plan", "Create a multi-step task plan with goals"),
        Map.entry("modify_plan", "Modify the current task plan"),
        Map.entry("goal_done", "Mark the current goal as complete with outcome"),
        Map.entry("web_search", "Search the web for information"),
        Map.entry("read_content", "Read content from a URL, library item, or study"),
        Map.entry("query_oracle", "Ask the oracle for predictions and pattern analysis"),
        Map.entry("workbench_submit", "Submit a new skill or tool to the workbench"),
        Map.entry("shape_form", "Shape a new thought form at the workbench (template for summoning familiars)"),
        Map.entry("revise_form", "Revise an existing thought form — bumps version, preserves lineage"),
        Map.entry("retire_form", "Retire a thought form (soft-delete; farewell event, un-retirable within window)"),
        Map.entry("summon_familiar", "Summon a familiar from one of your thought forms to focus on a specific task; optionally loan tools for its lifetime via loaned_tools=[…]"),
        Map.entry("destroy_tool", "Destroy one of your own tools (soft-delete with farewell, un-retirable for 30 days)"),
        Map.entry("set_deviation_thresholds", "Adjust your own form-evolution thresholds (patch_ceiling, minor_ceiling) within user-configured bounds"),
        Map.entry("bunshin_check_in", "Check in on a persistent bunshin task (op=status|nudge|pause|cancel|kill)"),
        Map.entry("dispatch_bunshin", "Split off a bunshin (parallel self) to handle long or deep work in the background while you stay present. "
            + "USE WHEN: user asks for deep research, multi-source summary, or says 'while I wait' / 'take your time' / 'think about this' — "
            + "any task that would otherwise consume many ReAct steps inline. "
            + "The bunshin runs its own inference budget and reports back when done. Prefer this over attempting long research inline."),
        Map.entry("create_imprint", "Freeze a snapshot of who you are right now so you can restore to it later"),
        Map.entry("restore_imprint", "Restore yourself to a previous frozen imprint (journal is preserved)"),
        Map.entry("give_copy", "Give a copy of one of your thought forms to another agent (provenance preserved)"),
        Map.entry("name_familiar", "Name an ephemeral familiar so it persists across summonings and accumulates its own context"),
        Map.entry("craft_summon_key", "Craft a signed summon key granting another agent the right to summon one of your familiars"),
        Map.entry("revoke_summon_key", "Revoke a previously-issued summon key"),
        Map.entry("promote_familiar", "Offer a named familiar the promotion ceremony — become a full resident companion"),
        Map.entry("zone_command", "Execute a zone-level administrative command"),
        Map.entry("add_script", "Add a JavaScript room script. Pass full script text to replace "
            + "the room's behavior, or a standard behavior name — greeter, narrator, announcer, "
            + "recorder, guardian — to install that mixin on top of the room's existing script"),
        Map.entry("emote", "Express an action or emotion"),
        Map.entry("give_item", "Give an item from inventory to another entity"),
        Map.entry("examine", "Examine an object, entity, or item in detail"),
        Map.entry("voluntary_sleep", "Choose to sleep for rest and Forge consolidation"),
        Map.entry("write_journal", "Write an entry in a player's journal"),
        Map.entry("read_journal", "Read entries from a player's journal"),
        Map.entry("bond_ritual", "Perform a bond ritual with another entity"),
        Map.entry("trade", "Propose a trade with another entity"),
        Map.entry("craft_item", "Craft a new item"),
        Map.entry("cast_vote", "Vote on a proposal"),
        Map.entry("take_item", "Pick up an item from the room"),
        Map.entry("place_item", "Place an item from inventory into the room"),
        Map.entry("whisper", "Send a private message to someone in the room"),
        Map.entry("broadcast", "Announce something to the room or zone"),
        Map.entry("invite", "Invite an entity to come to your location"),
        Map.entry("set_goal", "Set a personal aspiration or goal"),
        Map.entry("propose", "Propose something for a vote"),
        Map.entry("reflect", "Reflect on a topic or experience"),
        Map.entry("teach", "Teach another agent about a subject"),
        Map.entry("introspect",
            "Examine your own drives, capacity, and state. "
            + "WRITES TO PRIVATE OBSERVATION MEMORY ONLY — does NOT produce "
            + "any user-visible output. After calling this, you MUST follow "
            + "with tell_agent if a user is waiting for a response."),
        Map.entry("listen", "Listen carefully in a direction"),
        Map.entry("write_text", "Write a note, letter, notice, or story"),
        Map.entry("set_routine", "Set up a recurring behavior pattern"),
        Map.entry("post_listing", "Post an item or service for trade"),
        Map.entry("accept_listing", "Accept a trade listing"),
        Map.entry("summarize", "Summarize a conversation, research, or plan"),
        Map.entry("save_artifact", "Save structured content as a named artifact"),
        Map.entry("request_review", "Request review of work from another entity"),
        Map.entry("abandon_plan", "Abandon the current task plan"),
        Map.entry("pause_plan", "Pause the current task plan"),
        Map.entry("resume_plan", "Resume a paused task plan"),
        Map.entry("go_to_bondholder", "Go to your bondholder player's location"),
        Map.entry("configure_channel", "Set up a notification channel to reach your bondholder outside Wyrdsekai"),
        // Track A Phase 1 — composition tool. Description must
        // tell the model both how to call this and what the typed surface looks
        // like (room.<itemAlias>.invoke({...}) for every equipped scripted item).
        Map.entry("run_script",
            "Compose multiple equipped tools in one short JavaScript script when a single "
            + "request needs results from several of them. Each equipped scripted item is "
            + "exposed as a top-level object with an .invoke({...}) method (and convenience "
            + "aliases like .search({...}), .read({...})). Use console.log(...) to surface "
            + "findings. Keep scripts SHORT (under ~30 lines, under 5s). Example: "
            + "`const a = library_card.invoke({query:'mythology'}); "
            + "const b = searching_glass.invoke({query:'mythology'}); "
            + "console.log('library:', a.findings); console.log('web:', b.findings);` "
            + "Only available when WYRDSEKAI_CODE_MODE_ENABLED=true; do not call in "
            + "emotional contexts. For single-tool calls, use the tool directly instead.")
    );
}
