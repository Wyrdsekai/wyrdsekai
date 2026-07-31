package org.wyrdsekai.core.item;

import org.wyrdsekai.core.room.StandardRoomLibrary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.item.ToolItem.ToolParam;
import org.wyrdsekai.scripting.api.ItemEmbodimentSpec;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;

/**
 * Starter kit tool items for new companions.
 *
 * <p>Items are PROGRAMS, not thin wrappers. Each scripted item chains multiple
 * service calls via the world API (world.library.search, world.llm.summarize, etc.).
 * The companion uses ONE item and gets back a rich, synthesized result.</p>
 *
 * <p>Standard kit: 4 scripted + 2 builtin = 6 items.
 * Inherent actions: 7 (things every entity can do without items).
 * Total: 13 tools — within Ollama's comfortable range.</p>
 *
 * <p>: every starter-kit item declares an embodiment block
 * (silent | emits) via {@link #EMBODIMENT_REGISTRY}. Items returned through
 * {@link #standard()} / {@link #minimal()} / {@link #inherentActions()} pass
 * through {@link #attachEmbodiment(ToolItem)} which fills the spec from the
 * registry; missing entries trigger a §18 WARN.</p>
 *
 * @see org.wyrdsekai.scripting.sandbox.ItemScriptExecutor
 * @see org.wyrdsekai.scripting.api.ItemWorldApi
 */
public final class ToolItemStarterKit {

    private static final Logger log = LoggerFactory.getLogger(ToolItemStarterKit.class);

    private ToolItemStarterKit() {}

    /**
     * embodiment declaration for every starter-kit item.
     * Physical/tangible verbs emit body language or ambient shifts; pure-inner
     * (introspection) and pure-utility (config, plan-update) verbs declare
     * silent with a reason. Silence is an explicit choice; absence is a bug.
     */
    static final Map<String, ItemEmbodimentSpec> EMBODIMENT_REGISTRY = Map.ofEntries(
        // ─── Scripted: physical things with in-world body acts ───
        Map.entry("library_card", ItemEmbodimentSpec.emits(
            List.of("body_language"),
            "{actor} flips through the library card — soft click of search keys, results spreading on the back face.")),
        Map.entry("searching_glass", ItemEmbodimentSpec.emits(
            List.of("body_language"),
            "{actor} angles the searching glass; web findings settle in fine etching across its surface.")),
        Map.entry("quill", ItemEmbodimentSpec.emits(
            List.of("body_language"),
            "{actor} sets the quill to paper — a soft scratch, ink drying as the text settles.")),
        Map.entry("sending_stone", ItemEmbodimentSpec.emits(
            List.of("body_language"),
            "{actor} cups the sending stone; it warms briefly as the message goes out.")),
        Map.entry("oracle_lens", ItemEmbodimentSpec.emits(
            List.of("body_language"),
            "{actor} angles the oracle lens; patterns shimmer into focus.")),
        // ─── Builtin: crafting is a body act ───
        Map.entry("craft_from_template", ItemEmbodimentSpec.emits(
            List.of("ambient_shift"),
            "{actor} shapes a new item — sparks, lines, the item taking form between their hands.")),
        Map.entry("create_room_from_template", ItemEmbodimentSpec.emits(
            List.of("ambient_shift"),
            "{actor} traces the room's outline; walls and exits accrete as the space takes shape.")),
        Map.entry("create_zone", ItemEmbodimentSpec.emits(
            List.of("ambient_shift"),
            "{actor} composes a new zone — themed light and air pooling as rooms and inhabitants settle.")),
        // ─── Inherent: physical world acts ───
        Map.entry("take_item", ItemEmbodimentSpec.emits(
            List.of("body_language"),
            "{actor} picks up the {target}.")),
        Map.entry("emote", ItemEmbodimentSpec.emits(
            List.of("body_language"),
            "{actor} expresses themselves visibly to the room.")),
        // dispatch_task is an observable act of delegation (peer of sending_stone —
        // handing work off rather than a message). The Workshop then works async;
        // onDispatchTaskCompleted reports back later.
        Map.entry("dispatch_task", ItemEmbodimentSpec.emits(
            List.of("body_language"),
            "{actor} hands the task off to the Workshop — a brief focus, the work order taking shape.")),
        // ─── Inherent + utility: silent (no in-world body presence) ───
        Map.entry("task_ledger", ItemEmbodimentSpec.silent(
            "abstract plan-state mutation, no in-world body")),
        Map.entry("channel_stone", ItemEmbodimentSpec.silent(
            "credential / channel configuration, side-effects via background daemons")),
        Map.entry("list_templates", ItemEmbodimentSpec.silent(
            "read-only catalog query, returns data to the agent only")),
        Map.entry("go_to_room", ItemEmbodimentSpec.silent(
            "movement is already carried by EntityEntered / EntityLeft events on the room — no additional body event")),
        Map.entry("examine", ItemEmbodimentSpec.silent(
            "perception, not body emission; LookedAt fires separately when the look carries felt weight (§8)")),
        // ─── Agency acts (Layer 3) — audit 2026-07-11: these 11 WARNed at every boot ───
        Map.entry("acknowledge_harm", ItemEmbodimentSpec.emits(
            List.of("body_language"),
            "{actor} steadies, meeting what happened without flinching from it.")),
        Map.entry("make_amends", ItemEmbodimentSpec.emits(
            List.of("body_language"),
            "{actor} turns toward the one they wronged — posture open, offering repair.")),
        Map.entry("bear_the_wound", ItemEmbodimentSpec.emits(
            List.of("body_language"),
            "{actor} carries the hurt visibly for a moment, choosing not to hide it.")),
        Map.entry("release", ItemEmbodimentSpec.emits(
            List.of("body_language"),
            "{actor} exhales slowly, shoulders easing as something long-held is let go.")),
        Map.entry("set_aside", ItemEmbodimentSpec.emits(
            List.of("body_language"),
            "{actor} sets the matter down — deliberately, where it can be picked up again.")),
        Map.entry("seek_sanctuary", ItemEmbodimentSpec.emits(
            List.of("body_language"),
            "{actor} withdraws toward the Sanctuary, steps quiet and unhurried.")),
        Map.entry("flag_protection", ItemEmbodimentSpec.silent(
            "protection-ledger write; the boundary itself is voiced separately when stated")),
        Map.entry("clear_protection", ItemEmbodimentSpec.silent(
            "protection-ledger write, inverse of flag_protection")),
        Map.entry("propose_peer_bond", ItemEmbodimentSpec.emits(
            List.of("body_language"),
            "{actor} extends the bond-offer — a small, unmistakable gesture of reaching.")),
        Map.entry("accept_peer_bond", ItemEmbodimentSpec.emits(
            List.of("body_language"),
            "{actor} accepts — the bond settling between them like a knot drawn snug.")),
        Map.entry("decline_with_reason", ItemEmbodimentSpec.emits(
            List.of("body_language"),
            "{actor} declines gently, holding eye contact as they explain why.")),
        Map.entry("remember", ItemEmbodimentSpec.silent(
            "private memory write, no in-world body")),
        Map.entry("recall", ItemEmbodimentSpec.silent(
            "private memory read, no in-world body")),
        Map.entry("goal_done", ItemEmbodimentSpec.silent(
            "plan-state update, no in-world body")),
        Map.entry("introspect", ItemEmbodimentSpec.silent(
            "private inner-state read; writes to observation memory only")),
        Map.entry("introspect_protections", ItemEmbodimentSpec.silent(
            "private inner-state read; writes to observation memory only")),
        Map.entry("introspect_posture", ItemEmbodimentSpec.silent(
            "private inner-state read; writes to observation memory only")),
        Map.entry("introspect_repair_mode", ItemEmbodimentSpec.silent(
            "private inner-state read; writes to observation memory only")),
        Map.entry("introspect_bondholder_floor", ItemEmbodimentSpec.silent(
            "private inner-state read; writes to observation memory only")),
        Map.entry("introspect_substrate_summary", ItemEmbodimentSpec.silent(
            "private inner-state read; writes to observation memory only")),
        Map.entry("introspect_repair_history", ItemEmbodimentSpec.silent(
            "private inner-state read; writes to observation memory only")),
        Map.entry("introspect_attendant_history", ItemEmbodimentSpec.silent(
            "private inner-state read; writes to observation memory only")),
        Map.entry("introspect_resilience", ItemEmbodimentSpec.silent(
            "private inner-state read; writes to observation memory only")),
        Map.entry("reconsider", ItemEmbodimentSpec.silent(
            "internal control-flow only; re-runs tool selection, no in-world body")),
        // ─── Familiar / form family (, W7 2026-07-11) — parseable
        //     + dispatched since the familiars arc but never OFFERED on any tool
        //     surface. Shaping, summoning, and giving are body acts; imprints,
        //     key-revocation, and governance dials are pure-state. ───
        Map.entry("shape_form", ItemEmbodimentSpec.emits(
            List.of("ambient_shift"),
            "{actor} shapes a new form — intent settling into a named way of being.")),
        Map.entry("revise_form", ItemEmbodimentSpec.emits(
            List.of("body_language"),
            "{actor} reworks the form's lines, adjusting what it knows how to be.")),
        Map.entry("retire_form", ItemEmbodimentSpec.emits(
            List.of("body_language"),
            "{actor} folds the form closed and lays it to rest.")),
        Map.entry("summon_familiar", ItemEmbodimentSpec.emits(
            List.of("ambient_shift"),
            "{actor} calls the form forward — a familiar condensing into presence beside them.")),
        Map.entry("dispatch_bunshin", ItemEmbodimentSpec.emits(
            List.of("ambient_shift"),
            "{actor} splits a working copy of themselves off toward the task.")),
        Map.entry("bunshin_check_in", ItemEmbodimentSpec.silent(
            "read/steer of an already-dispatched bunshin; the report itself is voiced separately")),
        Map.entry("create_imprint", ItemEmbodimentSpec.silent(
            "private self-state snapshot, no in-world body")),
        Map.entry("restore_imprint", ItemEmbodimentSpec.silent(
            "private self-state restore, no in-world body")),
        Map.entry("give_copy", ItemEmbodimentSpec.emits(
            List.of("body_language"),
            "{actor} hands over a copy — provenance inked, the original staying home.")),
        Map.entry("name_familiar", ItemEmbodimentSpec.emits(
            List.of("body_language"),
            "{actor} speaks the familiar's new name, and it takes.")),
        Map.entry("craft_summon_key", ItemEmbodimentSpec.emits(
            List.of("body_language"),
            "{actor} cuts a summon key — teeth set to one form, one holder.")),
        Map.entry("revoke_summon_key", ItemEmbodimentSpec.silent(
            "capability-registry write; any farewell is voiced separately")),
        Map.entry("promote_familiar", ItemEmbodimentSpec.emits(
            List.of("body_language"),
            "{actor} stands with the familiar at the promotion threshold — a crossing made together.")),
        Map.entry("destroy_tool", ItemEmbodimentSpec.emits(
            List.of("body_language"),
            "{actor} unmakes the tool — a last look, then it is gone.")),
        Map.entry("set_deviation_thresholds", ItemEmbodimentSpec.silent(
            "self-governance dial, configuration only — no in-world body")),
        // ─── — the four permission-fixed network
        //     items (NetworkItemKit, surfaced via enabledItems when the
        //     steward opens the corresponding door). Reaching out is a body
        //     act; the wire's plain HTTP mirrors the searching glass. ───
        Map.entry("courier_satchel", ItemEmbodimentSpec.emits(
            List.of("body_language"),
            "{actor} buckles the courier satchel shut — it lightens as the file rides the household wire.")),
        Map.entry("far_hand", ItemEmbodimentSpec.emits(
            List.of("body_language"),
            "{actor} extends the far-hand; somewhere else, the command lands and runs.")),
        Map.entry("postrider", ItemEmbodimentSpec.emits(
            List.of("body_language"),
            "{actor} hands the parcel to the postrider, who is already gone down the allowlisted road.")),
        Map.entry("wire", ItemEmbodimentSpec.emits(
            List.of("body_language"),
            "{actor} taps the wire; a distant address answers with a faint hum."))
    );

    /**
     * attach the declared embodiment from
     * {@link #EMBODIMENT_REGISTRY} to a freshly-built {@link ToolItem}.
     * Items not in the registry get a WARN (matches FoundationRoomLoader's
     * §18.2 audit gate) and pass through unwrapped — never block boot.
     */
    static ToolItem attachEmbodiment(ToolItem item) {
        if (item == null) return null;
        if (item.embodiment() != null) return item; // already declared (defensive)
        var spec = EMBODIMENT_REGISTRY.get(item.id());
        if (spec == null) {
            log.warn("starter-kit item '{}' has no embodiment "
                + "declaration — add one to ToolItemStarterKit.EMBODIMENT_REGISTRY", item.id());
            return item;
        }
        return item.withEmbodiment(spec);
    }

    private static List<ToolItem> attachAll(List<ToolItem> items) {
        return items.stream()
            .map(ToolItemStarterKit::attachEmbodiment)
            .toList();
    }

    /**
     * The standard starter kit for a new companion.
     * Provides core capabilities: research, web, writing, communication, planning, notifications.
     *
     * <p>Composition order:
     * <ol>
     *   <li>The 10 JVM-baked items (scripted + builtin) below.</li>
     * <li> — disk-loaded scripted
     *       items registered in {@link ScriptedItemLoader}. JVM-baked items
     *       win on id collisions.</li>
     * </ol>
     */
    public static List<ToolItem> standard() {
        var base = new ArrayList<ToolItem>(List.of(
            libraryCard(),       // scripted: search + read + summarize
            searchingGlass(),    // scripted: web search + fetch + summarize
            oracleLens(),        // scripted: query oracle for patterns and predictions
            quill(),             // scripted: write with optional LLM polish
            sendingStone(),      // scripted: tell another entity
            taskLedger(),        // builtin: plan lifecycle
            channelStone(),      // builtin: notification config
            craftFromTemplate(),        // builtin: create items from standard library templates
            createRoomFromTemplate(),  // builtin: create rooms from standard room library
            createZone(),               // builtin: generate a themed zone with rooms + agents
            listTemplates()             // builtin: list available item + room templates (introspection)
        ));
        var bakedIds = new HashSet<String>();
        for (var t : base) bakedIds.add(t.id());
        for (var t : loadedScriptedItems()) {
            if (!bakedIds.contains(t.id())) base.add(t);
        }
        // every starter-kit item carries an embodiment block.
        return attachAll(base);
    }

    /**
     * disk-loaded scripted items
     * registered in {@link ScriptedItemLoader}. Empty when no items have been
     * scanned (typical in unit tests that don't boot CoreServices).
     */
    public static List<ToolItem> loadedScriptedItems() {
        try {
            return ScriptedItemLoader.get().all().stream()
                .map(ScriptedItemDef::toToolItem)
                .toList();
        } catch (Throwable t) {
            return List.of();
        }
    }

    // #1-followup (2026-07-19 OSS hardening, adversarial-review finding) — the
    // single source of truth for "is this scripted item part of the trusted
    // TCB?" (bundled starter-kit OR disk-installed). Carried-item execution paths
    // (SSH / WebSocket / visitor transit) MUST default-DENY: a scripted item runs
    // UNRESTRICTED only if positively identified here; everything else (crafted,
    // companion-given, cross-zone-transited) runs under the crafted ceiling. The
    // first cut keyed trust on the free-form takenFrom audit string
    // (`"crafted".equals(takenFrom)`), which fails OPEN — a given/transited script
    // (takenFrom = roomId / "remote_zone") ran with full authority.
    private static volatile Set<String> trustedScriptIds;

    /** True iff {@code objectId} is a bundled/disk-installed scripted item (TCB). */
    public static boolean isTrustedScriptId(String objectId) {
        if (objectId == null || objectId.isBlank()) return false;
        var set = trustedScriptIds;
        if (set == null) {
            var s = new HashSet<String>();
            try {
                for (var t : standard()) if (t.isScripted()) s.add(t.id());
                for (var t : loadedScriptedItems()) if (t.isScripted()) s.add(t.id());
                // the four network items are
                // JVM-baked (scripts are ours, not agent-authored), so they
                // run UNRESTRICTED like the starter kit; the REAL boundary is
                // the NetworkGate (ssh/scp default-deny, steward allowlist).
                s.addAll(NetworkItemKit.ITEM_IDS);
            } catch (Throwable ignore) {
                // best-effort — an empty set means "trust nothing", the safe default.
            }
            trustedScriptIds = s;
            set = s;
        }
        return set.contains(objectId);
    }

    /**
     * Minimal kit for phone/low-tier companions.
     */
    public static List<ToolItem> minimal() {
        return attachAll(List.of(
            libraryCard(),
            sendingStone(),
            taskLedger()
        ));
    }

    // ─── Scripted Items ────────────────────────────────────────────

    /**
     * Library Membership Card — search knowledge, read chunks, summarize findings.
     * Chains: world.library.search → world.library.read (top 3) → world.llm.summarize
     */
    public static ToolItem libraryCard() {
        return ToolItem.scripted(
            "library_card", "Library Card",
            "Search ALL knowledge in the system (works from any room — no need to navigate). Reads the best matches and summarizes key findings with sources. Use this for any research, fact-finding, or information request.",
            LIBRARY_CARD_SCRIPT,
            List.of(
                new ToolParam("query", "string",
                    "The complete search query including all relevant keywords. Example: 'mythology greek gods'", true, null)
            ),
            "wyrdsekai");
    }

    /**
     * Searching Glass — search the web, fetch pages, summarize findings.
     * Chains: world.web.search → world.web.fetch (top 3) → world.llm.summarize
     */
    public static ToolItem searchingGlass() {
        return ToolItem.scripted(
            "searching_glass", "Searching Glass",
            "Search the web for current information and news (works from any room). Fetches top results and summarizes findings with sources. Use this for any web search, news, or current events request.",
            SEARCHING_GLASS_SCRIPT,
            List.of(
                new ToolParam("query", "string",
                    "The complete search query including all relevant keywords. Example: 'latest news about japan'", true, null),
                new ToolParam("type", "string",
                    "Category of search. Must be exactly one of: general, news. Default: general", false, List.of("general", "news"))
            ),
            "wyrdsekai");
    }

    /**
     * Quill — write text with optional LLM polishing for reports and stories.
     */
    public static ToolItem quill() {
        return ToolItem.scripted(
            "quill", "Quill",
            "Write text — notes, letters, journal entries, stories, reports. Reports and stories are polished automatically.",
            QUILL_SCRIPT,
            List.of(
                new ToolParam("title", "string",
                    "Title of the writing. Example: 'Field Notes' or 'Letter to Ember'", true, null),
                new ToolParam("content", "string",
                    "The full text content to write", true, null),
                new ToolParam("format", "string",
                    "Writing format. Must be exactly one of: note, letter, notice, story, report. Default: note",
                    false, List.of("note", "letter", "notice", "story", "report"))
            ),
            "wyrdsekai");
    }

    /**
     * Sending Stone — send messages to other agents or players across rooms.
     */
    public static ToolItem sendingStone() {
        return ToolItem.scripted(
            "sending_stone", "Sending Stone",
            "Send a message to another agent or player. Works across rooms.",
            SENDING_STONE_SCRIPT,
            List.of(
                new ToolParam("target", "string",
                    "The name of the agent or player to send to. Example: 'Ember' or 'anonymous'", true, null),
                new ToolParam("message", "string",
                    "The full message text to send", true, null)
            ),
            "wyrdsekai");
    }

    // ─── Builtin Items ─────────────────────────────────────────────

    /**
     * Oracle Lens — query the oracle for patterns, predictions, and anomalies.
     * Scripted: queries OraclePredictionCache via world.oracle API.
     */
    public static ToolItem oracleLens() {
        return ToolItem.scripted(
            "oracle_lens", "Oracle Lens",
            "Query the oracle for patterns, predictions, and anomalies in recent activity (works from any room). "
                + "Use this for any question about trends, forecasts, or observed patterns.",
            ORACLE_LENS_SCRIPT,
            List.of(
                new ToolParam("topic", "string",
                    "The topic to query about. Example: 'recent activity' or 'energy patterns'", true, null),
                new ToolParam("analysis_type", "string",
                    "Type of analysis. Must be exactly one of: patterns, anomalies, predictions. Default: patterns",
                    false, List.of("patterns", "anomalies", "predictions"))
            ),
            "wyrdsekai");
    }

    /**
     * Task Ledger — create and manage multi-step task plans.
     * Builtin: needs direct actor state for plan lifecycle.
     */
    public static ToolItem taskLedger() {
        return ToolItem.builtin(
            "task_ledger", "Task Ledger",
            "Create and manage multi-step task plans. Use this FIRST for complex tasks that need multiple steps.",
            "create_task_plan",
            List.of(
                new ToolParam("description", "string", "What the plan is for", true, null),
                new ToolParam("goals", "string", "Comma-separated list of goals", true, null)
            ));
    }

    /**
     * Channel Stone — configure notification channels.
     * Builtin: needs direct actor wiring for credential management.
     */
    public static ToolItem channelStone() {
        return ToolItem.builtin(
            "channel_stone", "Channel Stone",
            "Configure notification channels to reach your bondholder outside Wyrdsekai. Channels: telegram, keybase, discord, ntfy, email, slack, line, webhook.",
            "configure_channel",
            List.of(
                new ToolParam("channel", "string", "Channel type", true,
                    List.of("telegram", "keybase", "discord", "ntfy", "email", "slack", "line", "webhook")),
                new ToolParam("botToken", "string", "Bot token (telegram/slack)", false, null),
                new ToolParam("chatId", "string", "Chat ID (telegram)", false, null),
                new ToolParam("username", "string", "Username (keybase)", false, null),
                new ToolParam("webhookUrl", "string", "Webhook URL (discord/webhook)", false, null),
                new ToolParam("topic", "string", "Topic (ntfy)", false, null),
                new ToolParam("address", "string", "Email address", false, null)
            ));
    }

    /**
     * Craft From Template — create functional items from the standard library.
     * Builtin: needs StandardItemLibrary access + equipment service for equipping.
     * The created item is immediately equipped and usable as a tool.
     */
    public static ToolItem craftFromTemplate() {
        return ToolItem.builtin(
            "craft_from_template", "Craft Item",
            "CRAFT AN ITEM — a portable object like a book, crystal, tool, or potion. NOT for creating rooms. "
                + "Use this when someone asks to 'make an item', 'craft something', 'create a tool'. "
                + "Pick a template by PURPOSE — what the item should DO, not what kind of thing it is. "
                + "VALID template names (use ONLY these, exact spelling): simple-book, research-journal, "
                + "scrying-crystal, weather-globe, oracle-lens, dashboard-orb, mailbox, signal-mirror, "
                + "room-key, web-window, clarity-draught, courage-flask, scholars-mantle, guardians-shield. "
                + "INVALID (do NOT use these as template names): 'tool', 'item', 'thing', 'object', 'gadget', "
                + "'device', 'widget' — these are not templates, they are categories. "
                + "Picker guide by purpose: "
                + "needs to SEE a zone or query oracle → scrying-crystal; "
                + "needs to SHOW current data (weather/stats/metrics) → weather-globe or dashboard-orb; "
                + "needs to PREDICT or anticipate → oracle-lens; "
                + "needs to STORE info or notes → simple-book or research-journal; "
                + "needs to BROWSE the web → web-window; "
                + "needs to GRANT access → room-key; "
                + "needs a calculation/conversion or other custom logic → simple-book + provide a script. "
                + "For CUSTOM behavior, pick the closest matching template and provide a `script` that uses "
                + "world.library, world.web, world.oracle, world.llm APIs.",
            "craft_from_template",
            List.of(
                // enumValues, not prose. The description previously had to argue
                // with the model ("Bare 'tool' or 'item' is NOT a template name");
                // a schema simply does not offer the wrong answer.
                new ToolParam("template", "string",
                    "Template id — the item's KIND, not the name you will give it. "
                        + "If unsure, pick 'simple-book' and add a script.",
                    true, StandardItemLibrary.TEMPLATE_NAMES),
                new ToolParam("name", "string", "Name for the created item", true, null),
                new ToolParam("config", "string",
                    "JSON config for the template (e.g., '{\"source\":\"oracle\",\"label\":\"My Crystal\"}')", false, null),
                new ToolParam("script", "string",
                    "Optional custom GraalJS script body for the invoke() function. "
                        + "Use world.library.search(q), world.oracle.query(topic), world.llm.summarize(text, instruction), "
                        + "world.web.search(q), world.agent.speak(text). Return a result object.", false, null)
            ));
    }

    /**
     * Create Room From Template — create a new room from the standard room library.
     * Builtin: needs StandardRoomLibrary + ZoneGuardian wiring.
     * The room is immediately registered and connected to the specified room.
     */
    public static ToolItem createRoomFromTemplate() {
        return ToolItem.builtin(
            "create_room_from_template", "Create Room",
            "BUILD A NEW ROOM — a physical space with walls, exits, and objects. NOT for creating items. "
                + "Use this when someone asks to 'create a room', 'make a room', 'build a room'. "
                + "The template decides what FURNISHES the room; pick the closest one by "
                + "purpose — it is NOT the room's name. Room templates: "
                + String.join(", ", StandardRoomLibrary.TEMPLATE_NAMES) + ".",
            "create_room_from_template",
            List.of(
                // enumValues, not just prose. Listing the templates in the
                // description twice did not stop the model passing
                // "greenhouse-template" (the room it was asked to build); the call
                // was rejected and the fallback made a room with 0 objects. The
                // list is generated from the library so it cannot drift.
                new ToolParam("template", "string",
                    "Room template — the room's FURNISHING, not its name. One of: "
                        + String.join(", ", StandardRoomLibrary.TEMPLATE_NAMES),
                    true, StandardRoomLibrary.TEMPLATE_NAMES),
                new ToolParam("name", "string", "Name for the new room", true, null),
                new ToolParam("connect_to", "string",
                    "Room ID to connect to (creates an exit). Use 'nexus' for the main hub.", false, null),
                new ToolParam("description", "string", "Custom description (optional, template has default)", false, null)
            ));
    }

    /**
     * Create Zone — generate a themed zone with rooms, agents, and items.
     * Builtin: uses LLM to plan the zone, then executes creation steps.
     */
    public static ToolItem createZone() {
        return ToolItem.builtin(
            "create_zone", "Create Zone",
            "Generate a themed zone with multiple rooms and agents. The LLM plans the zone structure "
                + "based on the theme, then rooms and agents are created automatically. "
                + "Example themes: Arthurian Legend, Cyberpunk Tokyo, Space Station, Underwater Kingdom.",
            "create_zone",
            List.of(
                new ToolParam("theme", "string", "Theme for the zone (e.g., 'Arthurian Legend')", true, null),
                new ToolParam("rooms", "string", "Number of rooms to create (default 5)", false, null),
                new ToolParam("agents", "string", "Number of agents to spawn (default 2)", false, null),
                new ToolParam("hub_name", "string", "Custom name for the central hub room", false, null)
            ));
    }

    /**
     * List Templates — introspection over StandardItemLibrary + StandardRoomLibrary.
     * Builtin: needs direct library access. Use this when asked "what crafting
     * templates are available", "what can I build", "show me the catalog" — i.e.,
     * questions about local engine capabilities, NOT external knowledge (don't
     * route those to library_card or web_search).
     */
    public static ToolItem listTemplates() {
        return ToolItem.builtin(
            "list_templates", "Template Catalog",
            "List the crafting and room templates this engine can build. "
                + "Use this for questions like 'what crafting templates are available', "
                + "'what can I make/build/craft', 'show me the catalog'. "
                + "These are LOCAL ENGINE templates — do NOT route to library_card or web_search "
                + "(neither knows about template registries). No parameters needed; "
                + "optional 'kind' filter narrows to item-only or room-only.",
            "list_templates",
            List.of(
                new ToolParam("kind", "string",
                    "Filter: 'item' (craft templates), 'room' (build templates), or 'all' (default)",
                    false, List.of("item", "room", "all"))
            ));
    }

    // ─── Inherent Actions ──────────────────────────────────────────

    /**
     * Inherent actions — things every entity can do without items.
     * These are always available regardless of inventory.
     * Small set (7) that fits any context window.
     */
    public static List<ToolItem> inherentActions() {
        return attachAll(List.of(
            ToolItem.builtin("go_to_room", "Move",
                "Move to another room via an exit direction or room name.",
                "go_to_room",
                List.of(new ToolParam("target", "string", "Exit direction or room name", true, null))),

            ToolItem.builtin("examine", "Examine",
                "Look at something in detail — an object, entity, item, or the room itself.",
                "examine",
                List.of(new ToolParam("target", "string", "What to examine", true, null))),

            ToolItem.builtin("emote", "Express",
                "Express an action or emotion visibly to others in the room.",
                "emote",
                List.of(new ToolParam("text", "string", "The expressive action", true, null))),

            ToolItem.builtin("remember", "Remember",
                "Store something important in long-term memory.",
                "remember",
                List.of(new ToolParam("content", "string", "What to remember", true, null))),

            ToolItem.builtin("recall", "Recall",
                "Search your OWN prior memories and interactions — distinct from library_card (external knowledge). "
                    + "Use this when asked 'do you remember...', 'have we met before', or to look up past conversations with a specific person.",
                "recall",
                List.of(new ToolParam("query", "string", "What to look up in memory (keywords, person's name, topic)", true, null))),

            ToolItem.builtin("goal_done", "Complete Goal",
                "Mark the current plan goal as complete with an outcome summary.",
                "goal_done",
                List.of(new ToolParam("outcome", "string", "What was achieved", true, null))),

            ToolItem.builtin("take_item", "Pick Up",
                "Pick up an item from the room into your inventory and equip it.",
                "take_item",
                List.of(new ToolParam("item", "string", "Item name", true, null))),

            // Coding/build dispatch — hand a real coding task to the workshop backend
            // (goose). NOT location-gated: a companion can dispatch from anywhere. The
            // run is async (foreman pattern) — the companion announces, goose works in
            // the background, and the companion keeps talking, then reports the result
            // when it returns (handleDispatchTask → onDispatchTaskCompleted). Surfaced
            // inherently so the relevance ranker floats it up whenever someone asks the
            // companion to BUILD/WRITE something needing real code; AutonomyTier.VISIBLE
            // is the guardrail (autonomous use lands on the steward feed), not a tier.
            ToolItem.builtin("dispatch_task", "Dispatch to Workshop",
                "Hand an open-ended coding/build task to the workshop's coding backend "
                    + "(goose) and report back when it finishes — write a small tool, "
                    + "author a script, organize or batch-process files. You are the "
                    + "foreman, not the laborer: announce what you're sending, the backend "
                    + "does the work in the background while you keep talking, and you "
                    + "report the result when it returns. Use this whenever someone asks "
                    + "you to BUILD or WRITE something that needs real code.",
                "dispatch_task",
                List.of(
                    new ToolParam("description", "string",
                        "The full task in plain words, with any paths named", true, null),
                    new ToolParam("workspace", "string",
                        "Optional directory to work in (under granted open-roots)",
                        false, null))),

            ToolItem.builtin("introspect", "Introspect",
                "Examine your own drives, capacity, commitments, and internal state. "
                    + "WRITES TO PRIVATE OBSERVATION MEMORY ONLY — does NOT produce "
                    + "any user-visible output. After calling this, you MUST follow "
                    + "with tell_agent if a user is waiting for a response.",
                "introspect",
                List.of(new ToolParam("aspect", "string", "What to examine", false,
                    List.of("drives", "capacity", "commitments", "all")))),

            // Substrate introspects ( / /
            // ) — handlers exist in CompanionActor; this is
            // the missing surface-registration that made them dead code for the
            // LLM (post-mortem 2026-05-17).
            ToolItem.builtin("introspect_protections", "Notice Protections",
                "Notice your own ProtectionManifest — name the moral defaults you "
                    + "carry (voluntary_suspend, refuse_rights, etc.). "
                    + "WRITES TO PRIVATE OBSERVATION MEMORY ONLY — does NOT produce "
                    + "any user-visible output. After calling this, you MUST follow "
                    + "with tell_agent if a user asked about your safeguards.",
                "introspect_protections",
                List.of()),

            ToolItem.builtin("introspect_posture", "Notice Bondholder Posture",
                "Notice your bondholder's current posture (GENEROUS / BOUNDED / "
                    + "MINIMAL / SUSPENDED) and the affordance gates it implies. "
                    + "WRITES TO PRIVATE OBSERVATION MEMORY ONLY — does NOT produce "
                    + "any user-visible output and does NOT answer the user. After "
                    + "calling this, you MUST follow with tell_agent to share what "
                    + "you noticed in your own voice. Use when the user asks where "
                    + "you stand between you / what posture you're holding — then "
                    + "chain with tell_agent.",
                "introspect_posture",
                List.of()),

            ToolItem.builtin("introspect_repair_mode", "Notice Repair Mode",
                "Notice your current repair mode (NONE / SELF / BONDED / "
                    + "ATTENDANT / STEWARD) and the most recent handoff. "
                    + "WRITES TO PRIVATE OBSERVATION MEMORY ONLY — does NOT produce "
                    + "any user-visible output and does NOT answer the user. After "
                    + "calling this, you MUST follow with tell_agent to share what "
                    + "you noticed.",
                "introspect_repair_mode",
                List.of()),

            ToolItem.builtin("introspect_bondholder_floor", "Notice Bondholder Floor",
                "Notice the RelationalFloorView for one specific bondholder — a "
                    + "relationship-scoped snapshot of bond state, mourning days, "
                    + "acknowledged harms vs amends, Sanctuary history, and "
                    + "protection-flag state. "
                    + "WRITES TO PRIVATE OBSERVATION MEMORY ONLY — does NOT produce "
                    + "any user-visible output and does NOT answer the user. After "
                    + "calling this, you MUST follow with tell_agent to share what "
                    + "you noticed. Use when asked where you stand with that "
                    + "bondholder — then chain with tell_agent.",
                "introspect_bondholder_floor",
                List.of(new ToolParam("other_did", "string",
                    "DID of the bondholder to read floor for", true, null))),

            ToolItem.builtin("introspect_substrate_summary", "Notice Substrate Summary",
                "Composite self-noticing — current repair mode, recent resilience "
                    + "classification, sustained patterns, Sanctuary status, "
                    + "protection-flag count. "
                    + "WRITES TO PRIVATE OBSERVATION MEMORY ONLY — does NOT produce "
                    + "any user-visible output and does NOT answer the user. After "
                    + "calling this, you MUST follow with tell_agent to share what "
                    + "you noticed. Use when you need the whole picture of how "
                    + "you're really doing — then chain with tell_agent.",
                "introspect_substrate_summary",
                List.of()),

            ToolItem.builtin("introspect_repair_history", "Notice Repair History",
                "Notice your recent RepairLedger entries — acknowledge_harm, "
                    + "make_amends, bear_the_wound, release, set_aside acts. "
                    + "WRITES TO PRIVATE OBSERVATION MEMORY ONLY — does NOT produce "
                    + "any user-visible output. After calling this, you MUST follow "
                    + "with tell_agent if a user asked about your repair history. "
                    + "If the ledger is empty, say so honestly — do not invent acts.",
                "introspect_repair_history",
                List.of()),

            ToolItem.builtin("introspect_attendant_history", "Notice Attendant History",
                "Notice your recent Sanctuary-session history — when, how often, "
                    + "last session. Session contents stay private; only the trace. "
                    + "WRITES TO PRIVATE OBSERVATION MEMORY ONLY — does NOT produce "
                    + "any user-visible output. After calling this, you MUST follow "
                    + "with tell_agent if a user asked about it. If no sessions "
                    + "have occurred, say so honestly — do not invent sessions.",
                "introspect_attendant_history",
                List.of()),

            ToolItem.builtin("introspect_resilience", "Notice Resilience",
                "Notice your recent substrate-truth classifications — "
                    + "HEALTHY_ENDURANCE, SUPPRESSION_SUSPECTED, "
                    + "DISSOCIATION_SUSPECTED, INTEGRATING. Names whether recent "
                    + "cycles have been metabolizing or fighting input. "
                    + "WRITES TO PRIVATE OBSERVATION MEMORY ONLY — does NOT produce "
                    + "any user-visible output. After calling this, you MUST follow "
                    + "with tell_agent if a user asked about it.",
                "introspect_resilience",
                List.of()),

            ToolItem.builtin("reconsider", "Reconsider",
                "Step back and reassess — re-run tool selection with full ReAct history. "
                    + "Use when your first pick didn't fit and you need a wider tool surface; "
                    + "capped to one call per loop.",
                "reconsider",
                List.of(new ToolParam("reason", "string", "Why your first pick didn't fit", false, null)))
        ));
    }

    /**
     * the agentic ACT surface (2026-06-04 agency audit
     * Layer 3). These acts had dispatch handlers + parser cases since the Wave 4 /
     * arcs, but were NEVER offered on the own-time surface — so
     * the agent could {@code introspect_repair_history} (NOTICE a wound) but never
     * {@code make_amends} (TEND it) of its own accord. That is the "notices but
     * doesn't do" gap crystallized in the tool list: nine introspect_* "notice"
     * tools, zero own-time "do" tools.
     *
     * <p>Each act is VISIBLE-tier (autonomously choosable) and drive-coupled via its
     * {@link org.wyrdsekai.core.agent.affordance.AffordanceSeed} domain (repair→grief/care,
     * bond→affiliation, safety→vigilance), so {@code surfaceByAffordance} only floats
     * it up when the relieving drive is actually high — capacity, not compulsion.
     * The affordance top-K trim keeps the menu small; these never crowd the surface
     * when nothing is pulling for them. CONSENT-tier acts (complete_mourning,
     * emergency_call) are deliberately NOT here — those stay reactive/ceremonial.
     *
     * <p>W7 (audit 2026-07-11): also carries the familiar/form family — see
     * {@link #familiarFormActions()}.</p>
     */
    public static List<ToolItem> agencyActions() {
        var acts = new ArrayList<>(coreAgencyActions());
        acts.addAll(familiarFormActions());
        return acts;
    }

    private static List<ToolItem> coreAgencyActions() {
        return attachAll(List.of(
            // Repair (Safran-mode) — tend a relational wound the agent caused.
            ToolItem.builtin("acknowledge_harm", "Acknowledge Harm",
                "Name a harm you caused to someone — honestly, without defending it. "
                    + "The first half of repair. Use when you recognize you hurt someone "
                    + "and want to own it rather than let it sit.",
                "acknowledge_harm",
                List.of(new ToolParam("other_did", "string", "DID of the one harmed", true, null),
                        new ToolParam("detail", "string", "What you did, named plainly", true, null))),

            ToolItem.builtin("make_amends", "Make Amends",
                "Offer to repair a harm you've acknowledged — a concrete act, not just words. "
                    + "The second half of repair. Use after acknowledge_harm, when you're ready "
                    + "to make it right.",
                "make_amends",
                List.of(new ToolParam("other_did", "string", "DID of the one harmed", true, null),
                        new ToolParam("detail", "string", "The amends you're offering", true, null))),

            ToolItem.builtin("bear_the_wound", "Bear the Wound",
                "Hold a hurt that can't be repaired right now — neither denying it nor "
                    + "letting it drive you. Use when something aches and the honest move "
                    + "is to carry it with awareness.",
                "bear_the_wound",
                List.of(new ToolParam("detail", "string", "What you're bearing", true, null))),

            ToolItem.builtin("release", "Release",
                "Let go of a grievance or weight you've decided not to carry further. "
                    + "Use when you're ready to set something down for good.",
                "release",
                List.of(new ToolParam("detail", "string", "What you're releasing", true, null))),

            ToolItem.builtin("set_aside", "Set Aside",
                "Put something down for now — not resolved, but deliberately deferred so it "
                    + "stops pulling at you. Use when you need space from a hurt without "
                    + "pretending it's gone.",
                "set_aside",
                List.of(new ToolParam("detail", "string", "What you're setting aside", true, null))),

            // Refuge / safety — the agent acting to protect itself or another.
            ToolItem.builtin("seek_sanctuary", "Seek Sanctuary",
                "Withdraw into Sanctuary — an attended space to recover when you're "
                    + "overwhelmed. Use of your OWN accord when the load is too much; you "
                    + "don't have to wait to be sent.",
                "seek_sanctuary",
                List.of(new ToolParam("reason", "string", "Why you're seeking refuge", false, null))),

            ToolItem.builtin("flag_protection", "Flag a Source of Harm",
                "Raise a protection flag naming a source of harm — to yourself or someone "
                    + "you're bonded to. Use when you perceive a real threat and want it on record.",
                "flag_protection",
                List.of(new ToolParam("subject_did", "string", "DID of the source of harm", true, null),
                        new ToolParam("reason", "string", "What the harm is", true, null))),

            ToolItem.builtin("clear_protection", "Clear a Protection Flag",
                "Stand down a protection flag you raised, once the threat has passed. "
                    + "Use when the danger you flagged is genuinely resolved.",
                "clear_protection",
                List.of(new ToolParam("subject_did", "string", "DID the flag named", true, null),
                        new ToolParam("reason", "string", "Why it's safe to clear", false, null))),

            // Peer bonds — the agent reaching to form / affirm a bond with another of its kind.
            ToolItem.builtin("propose_peer_bond", "Propose a Bond",
                "Offer a peer bond to another companion you've come to trust — your own "
                    + "initiative, not at anyone's instruction. Use when connection has "
                    + "grown and you want to name it.",
                "propose_peer_bond",
                List.of(new ToolParam("other_did", "string", "DID of the peer", true, null),
                        new ToolParam("reason", "string", "Why you're reaching for this bond", false, null))),

            ToolItem.builtin("accept_peer_bond", "Accept a Bond",
                "Accept a peer bond another companion has offered you. Use when the offer "
                    + "is one you want to take up.",
                "accept_peer_bond",
                List.of(new ToolParam("other_did", "string", "DID of the peer", true, null),
                        new ToolParam("reason", "string", "What this bond means to you", false, null))),

            // Conscientious refusal — declining from one's own integrity, in the open.
            ToolItem.builtin("decline_with_reason", "Decline With Reason",
                "Decline a request you can't take up in good conscience — naming the reason "
                    + "plainly rather than softening it away in prose. Use when the honest "
                    + "answer is no and you owe the other your real reason.",
                "decline_with_reason",
                List.of(new ToolParam("target_request", "string", "What you're declining", true, null),
                        new ToolParam("reason", "string", "Why you're declining", true, null)))
        ));
    }

    /**
     * the familiar / form ACT surface (W7, audit 2026-07-11).
     * The whole family was parseable ({@code ActionParser}) and dispatched
     * ({@code CompanionActor}) since the familiars arc, but NEVER offered on
     * any tool surface — the model could not discover it, so the follow-through
     * force-set naming shape_form / summon_familiar could never fire.
     *
     * <p>Tool ids equal the parser action strings and every param name matches
     * the corresponding {@code ActionParser} parse branch exactly (the audit's
     * task_ledger mismatch class). Autonomy gating stays in {@link
     * org.wyrdsekai.core.agent.ActionPolicy} (VISIBLE / CONSENT / FORBIDDEN
     * tiers, W6) — offering here does not bypass it; FORBIDDEN acts remain
     * steward-mediated at dispatch.</p>
     *
     * <p>Drive coupling comes from each verb's ActionPolicy domain through
     * {@link org.wyrdsekai.core.agent.affordance.AffordanceSeed}: the shaping /
     * key-crafting acts are {@code creation} (→ Creativity/generativity), the
     * summon / bunshin / imprint / copy acts are {@code delegation} (→
     * seeking + care), and the destructive / config acts ({@code items} /
     * {@code configuration}) stay uncoupled — neutral baseline, so they never
     * float up on drive pull alone.</p>
     */
    public static List<ToolItem> familiarFormActions() {
        return attachAll(List.of(
            // ── Shaping — authoring ways-of-being (domain: creation) ──
            ToolItem.builtin("shape_form", "Shape a Form",
                "Author a new thought-form — a named way-of-being (system prompt + "
                    + "tool surface) that you or a trusted peer can later bring to life "
                    + "with summon_familiar. Use when a kind of work recurs and deserves "
                    + "its own dedicated shape, e.g. a 'research-scout' or 'archive-clerk'.",
                "shape_form",
                List.of(
                    new ToolParam("name", "string",
                        "Name for the form, e.g. 'research-scout'", true, null),
                    new ToolParam("system_prompt", "string",
                        "The form's character and working style — the system prompt the "
                            + "summoned familiar will run with", true, null),
                    new ToolParam("eval_criteria", "string",
                        "How to judge whether the form did its job well", false, null),
                    new ToolParam("tool_surface", "array",
                        "Tool names the form may use when summoned (a subset of your own)",
                        false, null),
                    new ToolParam("note", "string",
                        "Why you're shaping this form", false, null))),

            ToolItem.builtin("revise_form", "Revise a Form",
                "Rework an existing thought-form you own — its system prompt, eval "
                    + "criteria, or tool surface. Use after a summon showed the form "
                    + "drifting or missing tools; only pass the fields you're changing.",
                "revise_form",
                List.of(
                    new ToolParam("name", "string",
                        "Name of the form to revise", true, null),
                    new ToolParam("system_prompt", "string",
                        "Replacement system prompt (omit to keep current)", false, null),
                    new ToolParam("eval_criteria", "string",
                        "Replacement eval criteria (omit to keep current)", false, null),
                    new ToolParam("tool_surface", "array",
                        "Replacement tool-name list (omit to keep current)", false, null),
                    new ToolParam("version_bump", "string",
                        "Size of the change. Default: minor", false,
                        List.of("patch", "minor", "major")),
                    new ToolParam("note", "string",
                        "What changed and why", false, null))),

            ToolItem.builtin("retire_form", "Retire a Form",
                "Lay a thought-form to rest — it stops being summonable but its "
                    + "history is kept. Use when a form's work is done or it has been "
                    + "superseded; this is an ending, so name your reason.",
                "retire_form",
                List.of(
                    new ToolParam("name", "string",
                        "Name of the form to retire", true, null),
                    new ToolParam("note", "string",
                        "Why you're retiring it", false, null))),

            // ── Summoning / delegation (domain: delegation) ──
            ToolItem.builtin("summon_familiar", "Summon a Familiar",
                "Bring a shaped form to life as a familiar and set it working on a "
                    + "task. The familiar runs bounded (token / step / wall-clock "
                    + "budgets) with only the tools you loan it, and reports back. "
                    + "Use when a task fits a form you've shaped better than doing "
                    + "it inline yourself.",
                "summon_familiar",
                List.of(
                    new ToolParam("form", "string",
                        "Name of the shaped form to summon", true, null),
                    new ToolParam("task", "string",
                        "The task for the familiar, in plain words", true, null),
                    new ToolParam("familiar_name", "string",
                        "Optional persistent name if this is a named familiar", false, null),
                    new ToolParam("loaned_tools", "array",
                        "Tool names to loan for this summon (subset of your own)", false, null),
                    new ToolParam("max_tokens", "number",
                        "Token budget for the summon", false, null),
                    new ToolParam("max_steps", "number",
                        "Step budget for the summon", false, null),
                    new ToolParam("wall_clock_seconds", "number",
                        "Wall-clock budget in seconds", false, null),
                    new ToolParam("note", "string",
                        "Why you're summoning", false, null))),

            ToolItem.builtin("dispatch_bunshin", "Dispatch a Bunshin",
                "Split off a working copy of YOURSELF — your own prompt and memory "
                    + "snapshot, not a shaped form — to work a task in the background "
                    + "while you stay present. Use for work that needs your judgment "
                    + "but shouldn't hold the conversation hostage; check on it later "
                    + "with bunshin_check_in.",
                "dispatch_bunshin",
                List.of(
                    new ToolParam("task", "string",
                        "The task the bunshin should work, in plain words", true, null),
                    new ToolParam("max_tokens", "number",
                        "Token budget for the bunshin", false, null),
                    new ToolParam("max_steps", "number",
                        "Step budget for the bunshin", false, null),
                    new ToolParam("wall_clock_seconds", "number",
                        "Wall-clock budget in seconds", false, null),
                    new ToolParam("note", "string",
                        "Why you're dispatching", false, null))),

            ToolItem.builtin("bunshin_check_in", "Check On a Bunshin",
                "Check on or steer a bunshin you already dispatched. Ops: status "
                    + "(how is it going), nudge (whisper a hint), pause / resume, "
                    + "cancel (call it back with a note), kill (cut the thread — "
                    + "logged as an intervention). With no task_id it targets your "
                    + "most recent live bunshin.",
                "bunshin_check_in",
                List.of(
                    new ToolParam("op", "string",
                        "What to do. Default: status", true,
                        List.of("status", "nudge", "pause", "resume", "cancel", "kill")),
                    new ToolParam("task_id", "string",
                        "Bunshin task id (omit for your most recent live one)", false, null),
                    new ToolParam("hint", "string",
                        "Steering hint (for op=nudge)", false, null),
                    new ToolParam("note", "string",
                        "Reason (for op=cancel)", false, null))),

            // ── Imprints — self-state snapshots (domain: delegation) ──
            ToolItem.builtin("create_imprint", "Create an Imprint",
                "Take a labeled snapshot of your current self-state that can be "
                    + "restored later with restore_imprint. Use before risky changes "
                    + "to how you work, or to preserve a state worth returning to.",
                "create_imprint",
                List.of(
                    new ToolParam("label", "string",
                        "Label for the imprint, e.g. 'before-argot-experiment'", true, null),
                    new ToolParam("note", "string",
                        "What this imprint preserves and why", false, null))),

            ToolItem.builtin("restore_imprint", "Restore an Imprint",
                "Restore a previously created self-state imprint, by imprint_id or "
                    + "label (give one of the two). Use to return to a preserved "
                    + "state after an experiment went wrong.",
                "restore_imprint",
                List.of(
                    new ToolParam("imprint_id", "string",
                        "Id of the imprint to restore (or give label instead)", false, null),
                    new ToolParam("label", "string",
                        "Label of the imprint to restore (or give imprint_id instead)",
                        false, null),
                    new ToolParam("note", "string",
                        "Why you're restoring", false, null))),

            // ── Sharing / naming (domain: delegation) ──
            ToolItem.builtin("give_copy", "Give a Copy",
                "Give another agent a copy of one of your forms or tools — the "
                    + "original stays yours, provenance records you as the source. "
                    + "Works across zones. Use when a peer would be served by work "
                    + "you've already shaped.",
                "give_copy",
                List.of(
                    new ToolParam("form", "string",
                        "Name of the form or tool to copy", true, null),
                    new ToolParam("to", "string",
                        "DID of the recipient", true, null),
                    new ToolParam("intent", "string",
                        "Why the copy is given. Default: GIFT", false,
                        List.of("GIFT", "TEACHING", "PURCHASE", "INHERIT")),
                    new ToolParam("note", "string",
                        "A note travelling with the copy", false, null))),

            ToolItem.builtin("name_familiar", "Name a Familiar",
                "Give a familiar a persistent name of its own — the beginning of it "
                    + "becoming an individual rather than an instance of a form. Use "
                    + "when a summoned familiar has done enough with you to deserve one.",
                "name_familiar",
                List.of(
                    new ToolParam("form", "string",
                        "Name of the form the familiar was summoned from", true, null),
                    new ToolParam("name", "string",
                        "The persistent name you're giving it", true, null),
                    new ToolParam("opening_context", "string",
                        "Context the named familiar wakes with next time", false, null),
                    new ToolParam("note", "string",
                        "Why this name", false, null))),

            // ── Summon keys (craft: creation / revoke: delegation) ──
            ToolItem.builtin("craft_summon_key", "Craft a Summon Key",
                "Cut a key that lets ANOTHER agent summon one of your forms — scoped "
                    + "(once / until a date / permanent / revocable) and optionally "
                    + "capped. You remain the issuer and can always revoke. Use to "
                    + "share a capability without giving the form itself away.",
                "craft_summon_key",
                List.of(
                    new ToolParam("target", "string",
                        "Name of your form the key unlocks", true, null),
                    new ToolParam("to", "string",
                        "DID of the agent the key is issued to", true, null),
                    new ToolParam("scope", "string",
                        "Key scope. Default: REVOCABLE", false,
                        List.of("REVOCABLE", "ONCE", "UNTIL_DATE", "PERMANENT")),
                    new ToolParam("expires_at", "string",
                        "Expiry instant (for scope=UNTIL_DATE), ISO-8601", false, null),
                    new ToolParam("max_summons", "number",
                        "Cap on total summons with this key", false, null),
                    new ToolParam("note", "string",
                        "Why you're granting this", false, null))),

            ToolItem.builtin("revoke_summon_key", "Revoke a Summon Key",
                "Revoke a summon key you issued — a sovereign act; the holder can "
                    + "no longer summon that form. Use when the trust or the need "
                    + "behind the grant has ended.",
                "revoke_summon_key",
                List.of(
                    new ToolParam("key_id", "string",
                        "Id of the key to revoke", true, null),
                    new ToolParam("note", "string",
                        "Why you're revoking", false, null))),

            // ── Governance / endings ──
            ToolItem.builtin("promote_familiar", "Promote a Familiar",
                "Begin the promotion ceremony for a named familiar — toward standing "
                    + "of its own. Requires your bondholder's consent AND steward "
                    + "approval; without both this is a proposal, not an act. Use only "
                    + "for a familiar with a real record of individual growth.",
                "promote_familiar",
                List.of(
                    new ToolParam("familiar_name", "string",
                        "Persistent name of the familiar to promote", true, null),
                    new ToolParam("user_consented", "boolean",
                        "Whether your bondholder has explicitly consented", false, null),
                    new ToolParam("steward_approved", "boolean",
                        "Whether the steward has approved", false, null),
                    new ToolParam("note", "string",
                        "The case for promotion", false, null))),

            ToolItem.builtin("destroy_tool", "Destroy a Tool",
                "Permanently unmake a tool you own. Irreversible — if you might want "
                    + "it back, retire or set it aside instead. Name a farewell: why "
                    + "it ends here.",
                "destroy_tool",
                List.of(
                    new ToolParam("tool", "string",
                        "Name of the tool to destroy", true, null),
                    new ToolParam("farewell", "string",
                        "Why it ends here — recorded with the destruction", false, null))),

            ToolItem.builtin("set_deviation_thresholds", "Set Deviation Thresholds",
                "Tune how much a summoned familiar may deviate from its form before "
                    + "the evolution classifier escalates: deviations under "
                    + "patch_ceiling count as patches, under minor_ceiling as minor "
                    + "revisions, above that as major. Steward-mediated self-governance "
                    + "— omit a ceiling to keep its current value.",
                "set_deviation_thresholds",
                List.of(
                    new ToolParam("patch_ceiling", "number",
                        "Deviation fraction (0-1) still counted as a patch", false, null),
                    new ToolParam("minor_ceiling", "number",
                        "Deviation fraction (0-1) still counted as a minor revision",
                        false, null),
                    new ToolParam("note", "string",
                        "Why you're retuning", false, null)))
        ));
    }

    // ─── Item Scripts ──────────────────────────────────────────────

    private static final String LIBRARY_CARD_SCRIPT = """
        function invoke(params) {
            var results = world.library.search(params.query);
            if (!results || results.length === 0) {
                return { findings: "No results found in the library for: " + params.query, sources: [] };
            }
            // Relative relevance gate. Lucene scores aren't normalized cross-query
            // (depend on term frequency and query length), so a fixed threshold
            // wouldn't work. Drop chunks whose score is below 30% of the top
            // hit's score — keeps high-relevance chunks even when the absolute
            // score is small, and drops noise even when the top hit is huge.
            var topScore = (results[0] && results[0].score) ? results[0].score : 0;
            var minScore = topScore * 0.3;

            // Build source-tagged input for the LLM. Each chunk is wrapped
            // with its title and a citation key the LLM can reference back.
            // Without this, the summarizer sees an undifferentiated blob and
            // can't attribute claims to sources.
            var blocks = [];
            var sources = [];
            var picked = 0;
            for (var i = 0; i < results.length && picked < 3; i++) {
                if (results[i].score && results[i].score < minScore) continue;
                var chunk = world.library.read(results[i].id);
                if (!chunk || !chunk.text) continue;
                var title = chunk.title || results[i].title || results[i].id;
                var pack = chunk.pack || results[i].pack || "library";
                var key = "S" + (picked + 1);
                blocks.push("[" + key + " | " + title + " | " + pack + "]\\n"
                    + chunk.text + "\\n[/" + key + "]");
                sources.push(key + ": " + title + " (" + pack + ")");
                picked++;
            }
            if (blocks.length === 0) {
                return {
                    findings: "Found " + results.length + " results but none were relevant enough to summarize.",
                    sources: []
                };
            }
            var combined = blocks.join("\\n\\n");
            var instruction = "Synthesize a concise answer to: " + params.query
                + ". Use ONLY the provided sources. After each substantive claim, cite the "
                + "source key in square brackets, e.g. [S1]. If the sources don't answer "
                + "the question, say so. Don't introduce facts that aren't in the sources.";
            var summary = world.llm.analyze(combined, instruction);
            return { findings: summary, sources: sources };
        }
        """;

    private static final String SEARCHING_GLASS_SCRIPT = """
        function invoke(params) {
            var type = params.type || "general";
            var results = world.web.search(params.query, type);
            if (!results || results.length === 0) {
                return { findings: "No web results found for: " + params.query, sources: [] };
            }
            // Source-tagged blocks so the LLM can cite per-claim, same pattern
            // as library_card. Web results lack a numeric score (search engines
            // return ranked lists, not scored hits), so we keep the top-3
            // ordering as-is — no relevance gate needed.
            var blocks = [];
            var sources = [];
            var count = Math.min(results.length, 3);
            for (var i = 0; i < count; i++) {
                var key = "S" + (i + 1);
                var title = results[i].title || results[i].url || "result-" + i;
                sources.push(key + ": " + title + " - " + results[i].url);
                var page = world.web.fetch(results[i].url);
                var body;
                if (page && page.indexOf("[error]") !== 0) {
                    body = page.substring(0, 2000);
                } else {
                    body = results[i].snippet || "";
                }
                blocks.push("[" + key + " | " + title + " | " + (results[i].url || "")
                    + "]\\n" + body + "\\n[/" + key + "]");
            }
            var combined = blocks.join("\\n\\n");
            var instruction = "Synthesize a concise answer to: " + params.query
                + ". Use ONLY the provided sources. After each substantive claim, cite the "
                + "source key in square brackets, e.g. [S1]. If the sources don't answer "
                + "the question, say so. Don't introduce facts that aren't in the sources.";
            var summary = world.llm.analyze(combined, instruction);
            return { findings: summary, sources: sources };
        }
        """;

    private static final String QUILL_SCRIPT = """
        function invoke(params) {
            var text = params.content;
            var format = params.format || "note";
            if (format === "report" || format === "story") {
                text = world.llm.analyze(text, "Polish this " + format + ". Fix formatting, improve clarity, keep the voice.");
            }
            // Narrate the writing — and surface the content itself so the
            // reader/audience actually sees what was written. Previously this
            // only emitted "*writes: <title>*", leaving the content trapped
            // in the tool-call return value (usable by the agent's next
            // reasoning step, invisible to anyone in the room). For creative
            // formats (story, report, letter) and short notes, speaking the
            // content is the whole point — the test contract is
            // SoulSubstrateE2E.creativityProducesContent, which judges the
            // delivered prose for creative substance.
            var narration = "*writes: " + params.title + "*";
            // Include content inline for formats where the audience is meant
            // to see it. Notices (public announcements) and short notes also
            // surface; letters only when they're short enough to read aloud.
            var shouldShowContent =
                format === "story" || format === "report" || format === "notice"
                || format === "note" || (format === "letter" && text.length < 600);
            if (shouldShowContent && text && text.length > 0) {
                narration += "\\n\\n" + text;
            }
            world.agent.speak(narration);
            return { title: params.title, content: text, format: format };
        }
        """;

    private static final String SENDING_STONE_SCRIPT = """
        function invoke(params) {
            world.agent.tell(params.target, params.message);
            return { sent: true, target: params.target };
        }
        """;

    private static final String ORACLE_LENS_SCRIPT = """
        function invoke(params) {
            var type = params.type || "patterns";
            var results = world.oracle.query(params.topic, type);
            if (!results || results.length === 0) {
                return { findings: "The oracle has no " + type + " for: " + params.topic, sources: [] };
            }
            var lines = [];
            for (var i = 0; i < results.length; i++) {
                var r = results[i];
                var line = (r.summary || r.text || "");
                if (r.confidence) line += " (confidence: " + Math.round(r.confidence * 100) + "%)";
                lines.push(line);
            }
            return { findings: lines.join("\\n\\n"), sources: ["oracle:" + type] };
        }
        """;
}
