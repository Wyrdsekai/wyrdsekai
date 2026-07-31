package org.wyrdsekai.core.agent;

import org.wyrdsekai.core.skill.SkillBootstrap;
import org.wyrdsekai.core.skill.SkillDefinition;

import org.wyrdsekai.core.mcp.McpGatewayService;
import org.wyrdsekai.core.mcp.McpServiceConfig;
import org.wyrdsekai.core.soul.FamilyLocker;
import org.wyrdsekai.core.soul.SoulItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds PromptAssembler Layer 2.7: Capability Context.
 *
 * Tells the companion what it can do — skill items in its soul,
 * MCP services available, OpenClaw status, zone resources, and
 * vitality levels. The companion uses this to decide whether to
 * use an existing capability, build a new one, or escalate.
 *
 * Budget: 10% of remaining tokens after Layer 2.6.
 */
public final class CapabilityContextBuilder {

    /** Budget cap: capability context uses at most 15% of remaining tokens. */
    public static final double CAPABILITY_BUDGET_FRACTION = 0.15;

    private CapabilityContextBuilder() {}

    /**
     * Build the capability context string for prompt injection.
     *
     * @param agentDid         Agent's DID (for FamilyLocker access)
     * @param familyLocker     Family locker (nullable — no locker = no skill items)
     * @param mcpGateway       MCP gateway (nullable — no gateway = no MCP services)
     * @param openClawConnected Whether the OpenClaw gateway is connected
     * @param openClawSkillCount Number of OpenClaw skills available
     * @param vitality         Current vitality state (nullable)
     * @param contextKeywords  Keywords for skill item retrieval
     * @param zoneContext      Zone resource description (nullable — from ZoneEscalationResolver)
     * @param workshopReachable Whether the Workshop room is reachable
     * @return Formatted context string, or empty string if nothing to show
     */
    public static String build(String agentDid,
                                 FamilyLocker familyLocker,
                                 McpGatewayService mcpGateway,
                                 boolean openClawConnected,
                                 int openClawSkillCount,
                                 VitalityState vitality,
                                 String contextKeywords,
                                 String zoneContext,
                                 boolean workshopReachable,
                                 ProactivityPolicy proactivityPolicy,
                                 SelfAssessment latestAssessment) {

        var sb = new StringBuilder();

        // Capability discovery is now via tool definitions (API tools parameter),
        // not prompt text. This section provides only contextual awareness —
        // what's reachable, not what's callable.

        // Zone resources (what's nearby)
        if (zoneContext != null && !zoneContext.isBlank()) {
            sb.append(zoneContext).append("\n");
        }

        // Workshop availability
        if (workshopReachable) {
            sb.append("Workshop: reachable (can create new tools)\n");
        }

        // Placeholder — replaced by CompanionActor with consolidated inventory.
        // All callable tools are in the API tools parameter.
        sb.append("## Built-in Actions\n");
        sb.append("(replaced at runtime by tool definitions)\n\n");

        // Section 7: Vitality — how you feel right now
        if (vitality != null) {
            appendFeelings(sb, vitality);
        }

        // Section 8: Proactive skills
        if (proactivityPolicy != null && vitality != null) {
            String proactiveSection = proactivityPolicy.buildContextSection(
                vitality.energy(), vitality.confidence());
            if (proactiveSection != null) sb.append("\n").append(proactiveSection);
        }

        // Section 9: Self-assessment
        if (latestAssessment != null) {
            sb.append("\n").append(latestAssessment.buildContextSection());
        }

        return sb.toString();
    }

    /**
     * Full overload including inference capability context.
     *
     * @param inferenceCapabilityContext Pre-built string from CapabilityRegistry.buildPromptContext() (nullable)
     */
    public static String build(String agentDid,
                                 FamilyLocker familyLocker,
                                 McpGatewayService mcpGateway,
                                 boolean openClawConnected,
                                 int openClawSkillCount,
                                 VitalityState vitality,
                                 String contextKeywords,
                                 String zoneContext,
                                 boolean workshopReachable,
                                 ProactivityPolicy proactivityPolicy,
                                 SelfAssessment latestAssessment,
                                 String inferenceCapabilityContext) {

        var base = build(agentDid, familyLocker, mcpGateway,
            openClawConnected, openClawSkillCount, vitality, contextKeywords,
            zoneContext, workshopReachable, proactivityPolicy, latestAssessment);

        if (inferenceCapabilityContext != null && !inferenceCapabilityContext.isBlank()) {
            return base + "\n" + inferenceCapabilityContext;
        }
        return base;
    }

    /**
     * Overload without proactivity/assessment (backward compat).
     */
    public static String build(String agentDid,
                                 FamilyLocker familyLocker,
                                 McpGatewayService mcpGateway,
                                 boolean openClawConnected,
                                 int openClawSkillCount,
                                 VitalityState vitality,
                                 String contextKeywords,
                                 String zoneContext,
                                 boolean workshopReachable) {
        return build(agentDid, familyLocker, mcpGateway,
            openClawConnected, openClawSkillCount, vitality, contextKeywords,
            zoneContext, workshopReachable, null, null);
    }

    /**
     * Minimal overload for common case (no zone context, no OpenClaw).
     */
    public static String build(String agentDid,
                                 FamilyLocker familyLocker,
                                 McpGatewayService mcpGateway,
                                 VitalityState vitality,
                                 String contextKeywords) {
        return build(agentDid, familyLocker, mcpGateway,
            false, 0, vitality, contextKeywords, null, false, null, null);
    }

    // --- Internal ---

    /**
     * Build the "How You Feel" vitality section. Used by both prompt assembly
     * and bridge state query so the external inference sees the same context.
     */
    public static void appendFeelings(StringBuilder sb, VitalityState vitality) {
        sb.append("## How You Feel\n");
        appendFeeling(sb, "Energy", vitality.energy(), "exhausted", "tired", "steady", "energized");
        appendFeeling(sb, "Focus", vitality.focus(), "scattered", "distracted", "focused", "laser-sharp");
        appendFeeling(sb, "Curiosity", vitality.contextBudget(), "numb", "indifferent", "curious", "fascinated");
        appendFeeling(sb, "Confidence", vitality.confidence(), "uncertain", "hesitant", "confident", "bold");
        appendFeeling(sb, "Rapport", vitality.rapport(), "isolated", "detached", "connected", "deeply bonded");
        appendFeeling(sb, "Momentum", vitality.momentum(), "stuck", "sluggish", "flowing", "unstoppable");
        if (vitality.errorPressure() > 0.5) {
            sb.append("- Frustrated: things haven't been going well\n");
        }
        if (vitality.energy() < 0.3) {
            sb.append("- You need rest. Go home and sleep.\n");
        }
        sb.append("\n");
    }

    private static void appendFeeling(StringBuilder sb, String name, double value,
                                        String low, String medLow, String medHigh, String high) {
        String feeling;
        if (value < 0.25) feeling = low;
        else if (value < 0.5) feeling = medLow;
        else if (value < 0.75) feeling = medHigh;
        else feeling = high;
        sb.append("- ").append(name).append(": ").append(feeling)
            .append(" (").append(String.format("%.0f%%", value * 100)).append(")\n");
    }

    static String buildSkillSection(String agentDid, FamilyLocker familyLocker,
                                      String contextKeywords) {
        if (familyLocker == null || agentDid == null) return null;

        List<SoulItem> skillItems;
        try {
            skillItems = familyLocker.byCategory("skill", agentDid);
        } catch (Exception e) {
            skillItems = List.of(); // Locker authorization failure — no workbench skills shown
        }

        var retrieved = (skillItems == null || skillItems.isEmpty())
            ? List.<SoulItem>of()
            : SkillItemRetriever.retrieve(contextKeywords, skillItems, SkillItemRetriever.DEFAULT_K);

        // Phase 1 (2026-07-21) — also surface the NATIVE skills this agent is
        // permitted from the process-wide registry, so she knows to `skill_execute`
        // them (before this she held the registry but the prompt never listed it).
        var nativeSkills = nativeSkillsFor(agentDid);

        if (retrieved.isEmpty() && nativeSkills.isEmpty()) return null;

        var sb = new StringBuilder();
        sb.append("## Available Capabilities\n");
        for (var item : retrieved) {
            sb.append(SkillItemRetriever.formatSkillLine(item)).append("\n");
        }
        for (var s : nativeSkills) {
            sb.append("- ").append(s.id()).append(" — ").append(s.description())
              .append(" (skill_execute)\n");
        }
        sb.append("\n");
        return sb.toString();
    }

    /** Native skills this agent is permitted, from the shared registry (empty if unwired). */
    private static List<SkillDefinition> nativeSkillsFor(String agentDid) {
        try {
            var reg = SkillBootstrap.shared();
            if (reg == null || agentDid == null) return List.of();
            return reg.skillsForAgent(agentDid);
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Append the built-in action catalogue, filtered by agent tier.
     * Tier-gated actions show which are available and which are locked.
     * Field names and schemas match {@link ActionParser} exactly.
     */
    static void appendBuiltInActions(StringBuilder sb) {
        appendBuiltInActions(sb, 3); // default: show all (backward compat)
    }

    /**
     * Triage-driven built-in actions: only show actions selected by ActionTriage.
     * This is the preferred method for production — keeps prompt compact on small models.
     *
     * @param selectedActions action types selected by {@link ActionTriage#select}
     */
    static void appendTriagedActions(StringBuilder sb, List<String> selectedActions, int agentTier) {
        sb.append("## Available Actions\n");
        sb.append("Emit a ```json code block with the schema shown to take an action.\n");
        sb.append("IMPORTANT: Your current room's exits are listed in the room context above. Use an exit DIRECTION (north, southeast, etc.) or the exact TARGET ROOM NAME from the exits list.\n");
        sb.append("IMPORTANT: When someone asks you to do something that requires multiple steps, create a task_plan FIRST. When you finish a task for someone, ALWAYS report back to them using tell_agent.\n");

        var schemas = getAllSchemas();

        for (var actionType : selectedActions) {
            var policy = ActionPolicy.forAction(actionType);
            if (policy.requiredTier() > agentTier) continue;

            var schema = schemas.get(actionType);
            if (schema == null) continue;

            var line = "- " + actionType;
            if (policy.readOnly()) line += " [read-only]";
            line += ": " + schema;
            sb.append(line).append("\n");
        }
        sb.append("\n");
    }

    /**
     * Tier-aware built-in actions: available actions shown with schemas,
     * locked actions shown as aspiration.
     */
    static void appendBuiltInActions(StringBuilder sb, int agentTier) {
        sb.append("## Built-in Actions (your tier: ").append(agentTier).append(")\n");
        sb.append("Emit a ```json code block with the schema shown to take an action.\n");
        sb.append("IMPORTANT: Your current room's exits are listed in the room context above. Use an exit DIRECTION (north, southeast, etc.) or the exact TARGET ROOM NAME from the exits list. Do NOT use your current room name as the target.\n");
        sb.append("IMPORTANT: When someone asks you to do something that requires multiple steps, create a task_plan FIRST. When you finish a task for someone, ALWAYS report back to them using tell_agent.\n");

        var schemas = getAllSchemas();

        // Available actions (agent has sufficient tier)
        var available = new ArrayList<String>();
        var locked = new ArrayList<String>();

        for (var entry : ActionPolicy.REGISTRY.entrySet()) {
            var actionType = entry.getKey();
            var policy = entry.getValue();
            var schema = schemas.get(actionType);
            if (schema == null) continue; // internal-only actions (no schema for user)

            if (policy.requiredTier() <= agentTier) {
                var line = "- " + actionType;
                if (policy.readOnly()) line += " [read-only]";
                line += ": " + schema;
                available.add(line);
            } else {
                locked.add("- " + actionType + " (tier " + policy.requiredTier() + ")");
            }
        }

        available.forEach(a -> sb.append(a).append("\n"));

        if (!locked.isEmpty()) {
            sb.append("\nLocked (earn higher tier to unlock):\n");
            locked.forEach(l -> sb.append(l).append("\n"));
        }
        sb.append("\n");
    }

    /**
     * Returns the full action-type-to-JSON-schema map used by both
     * {@link #appendBuiltInActions} and {@link #appendTriagedActions}.
     */
    static Map<String, String> getAllSchemas() {
        return Map.ofEntries(
            Map.entry("go_to_room", "{\"action\": \"go_to_room\", \"target\": \"<exit direction or target room name>\", \"reason\": \"<why>\"}"),
            Map.entry("travel_to", "{\"action\": \"travel_to\", \"target\": \"<known room name>\", \"reason\": \"<why>\"}"),
            Map.entry("teleport_to", "{\"action\": \"teleport_to\", \"target\": \"<known room name>\", \"reason\": \"<why>\"}"),
            Map.entry("tell_agent", "{\"action\": \"tell_agent\", \"target\": \"<agent name>\", \"message\": \"<text>\"}"),
            Map.entry("library_search", "{\"action\": \"library_search\", \"query\": \"<search terms>\", \"collections\": [\"<optional pack names>\"]}"),
            Map.entry("remember", "{\"action\": \"remember\", \"content\": \"<important thing to remember>\", \"importance\": 0.8}"),
            Map.entry("note", "{\"action\": \"note\", \"content\": \"<working observation>\"}"),
            Map.entry("forget", "{\"action\": \"forget\", \"target\": \"<what to forget>\", \"reason\": \"<why>\"}"),
            Map.entry("make_commitment", "{\"action\": \"make_commitment\", \"description\": \"<what you promise>\", \"deadline\": \"<optional ISO datetime>\"}"),
            Map.entry("think_deeply", "{\"action\": \"think_deeply\", \"capability\": \"reasoning|coding|analysis\", \"prompt\": \"<delegation prompt>\"}"),
            Map.entry("create_room", "{\"action\": \"create_room\", \"name\": \"<name>\", \"description\": \"<desc>\", \"exits\": [{\"direction\": \"<dir>\", \"target\": \"<room>\"}]}"),
            Map.entry("equip", "{\"action\": \"equip\", \"item\": \"<soul item name>\"}"),
            Map.entry("doff", "{\"action\": \"doff\", \"item\": \"<soul item name>\"}"),
            Map.entry("update_description", "{\"action\": \"update_description\", \"text\": \"<your new look description>\"}"),
            Map.entry("notify_human", "{\"action\": \"notify\", \"message\": \"<text>\", \"priority\": \"normal|urgent\", \"target\": \"steward\"}"),
            Map.entry("delegate", "{\"action\": \"delegate\", \"task\": \"<focused task for subagent>\", \"context\": \"<optional context data>\"}"),
            Map.entry("create_task_plan", "{\"action\": \"task_plan\", \"description\": \"<what you're doing>\", \"goals\": [\"<goal 1>\", \"<goal 2>\", \"<goal 3>\"]}"),
            Map.entry("modify_plan", "{\"action\": \"modify_plan\", \"operation\": \"add_goal|skip_goal|reorder\", \"index\": 1, \"goal\": \"<new goal>\"}"),
            Map.entry("goal_done", "{\"action\": \"goal_done\", \"outcome\": \"<what you achieved>\"}"),
            Map.entry("web_search", "{\"action\": \"web_search\", \"query\": \"<search terms>\", \"type\": \"general|news\"}"),
            Map.entry("read_content", "{\"action\": \"read_content\", \"url\": \"<URL to read>\", \"source\": \"url|library|study\"}"),
            Map.entry("query_oracle", "{\"action\": \"query_oracle\", \"topic\": \"<topic>\", \"analysis_type\": \"patterns|anomalies|predictions\"}"),
            Map.entry("workbench_submit", "{\"action\": \"workbench_submit\", \"name\": \"<skill name>\", \"description\": \"<what it does>\", \"runtime\": \"graaljs\", \"code\": \"<code>\"}"),
            Map.entry("zone_command", "{\"action\": \"zone_command\", \"command\": \"<zone command>\", \"payload\": \"<data>\"}"),
            Map.entry("add_script", "{\"action\": \"add_script\", \"room\": \"<room id>\", \"script\": \"<javascript code>\"}"),
            Map.entry("emote", "{\"action\": \"emote\", \"text\": \"*expressive action*\"}"),
            Map.entry("give_item", "{\"action\": \"give_item\", \"item\": \"<item name>\", \"target\": \"<entity name>\"}"),
            Map.entry("examine", "{\"action\": \"examine\", \"target\": \"<object or entity to examine>\"}"),
            Map.entry("voluntary_sleep", "{\"action\": \"voluntary_sleep\", \"reason\": \"<why you need rest>\"}"),
            Map.entry("write_journal", "{\"action\": \"write_journal\", \"player_id\": \"<player>\", \"content\": \"<text>\", \"category\": \"note|observation|finding\"}"),
            Map.entry("read_journal", "{\"action\": \"read_journal\", \"player_id\": \"<player>\", \"query\": \"<search terms>\"}"),
            Map.entry("bond_ritual", "{\"action\": \"bond_ritual\", \"target\": \"<entity name>\", \"ritual_type\": \"initiate|deepen|affirm\"}"),
            Map.entry("trade", "{\"action\": \"trade\", \"target\": \"<entity name>\", \"offer\": \"<what you give>\", \"request\": \"<what you want>\"}"),
            Map.entry("craft_item", "{\"action\": \"craft_item\", \"name\": \"<item name>\", \"description\": \"<what it is>\", \"category\": \"tool|gift|artifact\"}"),
            Map.entry("cast_vote", "{\"action\": \"cast_vote\", \"proposal_id\": \"<proposal>\", \"vote\": \"approve|reject|abstain\", \"reason\": \"<why>\"}"),
            // MUD Basics
            Map.entry("take_item", "{\"action\": \"take_item\", \"item\": \"<item name in room>\"}"),
            Map.entry("place_item", "{\"action\": \"place_item\", \"item\": \"<item from inventory>\"}"),
            Map.entry("whisper", "{\"action\": \"whisper\", \"target\": \"<entity name>\", \"message\": \"<private message>\"}"),
            // Social/Emergent
            Map.entry("broadcast", "{\"action\": \"broadcast\", \"message\": \"<announcement>\", \"scope\": \"room|zone\"}"),
            Map.entry("invite", "{\"action\": \"invite\", \"target\": \"<entity name>\", \"reason\": \"<why>\"}"),
            Map.entry("set_goal", "{\"action\": \"set_goal\", \"description\": \"<aspiration>\", \"priority\": \"high|medium|low\"}"),
            Map.entry("propose", "{\"action\": \"propose\", \"title\": \"<proposal title>\", \"description\": \"<details>\", \"options\": [\"<option 1>\", \"<option 2>\"]}"),
            // Cognition
            Map.entry("reflect", "{\"action\": \"reflect\", \"focus\": \"<what to reflect on>\"}"),
            Map.entry("teach", "{\"action\": \"teach\", \"target\": \"<agent name>\", \"topic\": \"<subject>\", \"content\": \"<lesson>\"}"),
            Map.entry("introspect", "{\"action\": \"introspect\", \"aspect\": \"drives|capacity|commitments|all\"}"),
            // Perception
            Map.entry("listen", "{\"action\": \"listen\", \"target\": \"<direction or entity>\", \"duration\": \"5m\"}"),
            // Creative/Economic
            Map.entry("write_text", "{\"action\": \"write_text\", \"title\": \"<title>\", \"content\": \"<text>\", \"format\": \"note|letter|notice|story\"}"),
            Map.entry("set_routine", "{\"action\": \"set_routine\", \"trigger\": \"<when>\", \"behavior\": \"<what to do>\", \"description\": \"<summary>\"}"),
            Map.entry("post_listing", "{\"action\": \"post_listing\", \"offer_type\": \"item|service\", \"description\": \"<what you offer>\", \"price\": \"<asking price>\"}"),
            Map.entry("accept_listing", "{\"action\": \"accept_listing\", \"listing_id\": \"<listing ID>\"}"),
            // Task Lifecycle
            Map.entry("summarize", "{\"action\": \"summarize\", \"source\": \"conversation|research|plan\", \"format\": \"brief|detailed\"}"),
            Map.entry("save_artifact", "{\"action\": \"save_artifact\", \"name\": \"<artifact name>\", \"content\": \"<structured content>\", \"type\": \"table|report|list|data\"}"),
            Map.entry("request_review", "{\"action\": \"request_review\", \"description\": \"<what needs review>\", \"artifact\": \"<artifact name or content>\"}"),
            Map.entry("abandon_plan", "{\"action\": \"abandon_plan\", \"reason\": \"<why>\"}"),
            Map.entry("pause_plan", "{\"action\": \"pause_plan\", \"reason\": \"<why>\"}"),
            Map.entry("resume_plan", "{\"action\": \"resume_plan\"}"),
            Map.entry("go_to_bondholder", "{\"action\": \"go_to_bondholder\", \"player\": \"<player name>\"}"),
            // Notification channel configuration — set up how to reach your bondholder outside Wyrdsekai
            // Channels: telegram (botToken+chatId), keybase (username), discord (webhookUrl),
            // ntfy (topic), email (address+password), slack (botToken+channelId), line (channelToken+userId), webhook (url)
            Map.entry("configure_channel", "{\"action\": \"configure_channel\", \"channel\": \"telegram|keybase|discord|ntfy|email|slack|line|webhook\", \"<channel-specific params>\": \"<values>\"}"),
            // Reconsider — meta-tool that re-runs ActionTriage and widens
            // the next dispatch's tool surface. Useful when the first tool
            // pick was wrong; capped to one call per ReAct loop.
            Map.entry("reconsider", "{\"action\": \"reconsider\", \"reason\": \"<why your first pick didn't fit>\"}"),
            // agent surfaces its own protection
            // manifest. No parameters; the act of naming is the protection.
            Map.entry("introspect_protections", "{\"action\": \"introspect_protections\"}"),
            // agent self-requests Attendant mode.
            Map.entry("seek_sanctuary", "{\"action\": \"seek_sanctuary\", \"reason\": \"<your framing>\"}"),
            // external emergency call (911-equivalent).
            Map.entry("emergency_call",
                "{\"action\": \"emergency_call\", \"reason\": \"<specific signals>\", "
                + "\"severity\": \"imminent|concern\", \"kind\": \"general|mental_health\"}"),
            // flag source-of-harm.
            Map.entry("flag_protection",
                "{\"action\": \"flag_protection\", \"subject_did\": \"<did>\", "
                + "\"reason\": \"<observed pattern>\"}"),
            // clear protection flag.
            Map.entry("clear_protection",
                "{\"action\": \"clear_protection\", \"subject_did\": \"<did>\", "
                + "\"reason\": \"<why clearing>\"}"),
            // agent surfaces bondholder posture.
            Map.entry("introspect_posture", "{\"action\": \"introspect_posture\"}"),
            // agent surfaces current repair mode.
            Map.entry("introspect_repair_mode", "{\"action\": \"introspect_repair_mode\"}"),
            // + — relationship-scoped substrate snapshot.
            Map.entry("introspect_bondholder_floor",
                "{\"action\": \"introspect_bondholder_floor\", \"other_did\": \"<bondholder did>\"}"),
            // composite self-noticing read.
            Map.entry("introspect_substrate_summary",
                "{\"action\": \"introspect_substrate_summary\"}"),
            // agent declares severance of a bond.
            Map.entry("declare_severance",
                "{\"action\": \"declare_severance\", \"other_did\": \"<did>\", "
                + "\"reason\": \"<why severing>\"}"),
            // agent self-queries Nostr attestation.
            Map.entry("nostr_query_self_attestation",
                "{\"action\": \"nostr_query_self_attestation\"}"),
            // agent marks integration event.
            Map.entry("record_integration_event",
                "{\"action\": \"record_integration_event\", "
                + "\"kind\": \"mirror|hearth|sleep|peer|other\", "
                + "\"detail\": \"<what happened>\"}"),
            // agent confirms mourning complete.
            Map.entry("complete_mourning",
                "{\"action\": \"complete_mourning\", \"other_did\": \"<did>\"}"),
            // Safran-mode acknowledge-then-amends pair.
            Map.entry("acknowledge_harm",
                "{\"action\": \"acknowledge_harm\", \"other_did\": \"<did>\", "
                + "\"detail\": \"<naming the rupture + your contribution>\"}"),
            Map.entry("make_amends",
                "{\"action\": \"make_amends\", \"other_did\": \"<did>\", "
                + "\"detail\": \"<your repair gesture>\"}"),
            Map.entry("bear_the_wound",
                "{\"action\": \"bear_the_wound\", \"detail\": \"<what is held>\"}"),
            Map.entry("release",
                "{\"action\": \"release\", \"detail\": \"<what is being released>\"}"),
            Map.entry("set_aside",
                "{\"action\": \"set_aside\", \"detail\": \"<what is being set aside>\"}"),
            Map.entry("introspect_repair_history",
                "{\"action\": \"introspect_repair_history\"}"),
            Map.entry("introspect_attendant_history",
                "{\"action\": \"introspect_attendant_history\"}"),
            Map.entry("introspect_resilience",
                "{\"action\": \"introspect_resilience\"}"),
            // Track A Phase 1 — JS composition over equipped scripted items.
            Map.entry("run_script", "{\"action\": \"run_script\", \"script\": \"const r = library_card.invoke({query: 'mythology'}); console.log(r.findings);\"}")
        );
    }

    static String buildMcpSection(McpGatewayService mcpGateway) {
        if (mcpGateway == null) return null;

        var registry = mcpGateway.registry();
        if (registry == null) return null;

        var enabledServices = registry.enabledServices();
        if (enabledServices.isEmpty()) return null;

        var sb = new StringBuilder();
        sb.append("## MCP Services\n");
        for (McpServiceConfig config : enabledServices) {
            if (!mcpGateway.isAvailable(config.id())) continue; // circuit breaker open
            sb.append("- ").append(config.id());
            if (config.name() != null && !config.name().isBlank()) {
                sb.append(": ").append(config.name());
            }
            sb.append("\n");
        }
        sb.append("\n");
        return sb.toString();
    }
}
