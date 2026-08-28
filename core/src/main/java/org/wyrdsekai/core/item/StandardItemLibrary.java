package org.wyrdsekai.core.item;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Standard Item Library — the OS layer.
 *
 * <p>Provides template definitions for all standard item types. Each template
 * carries a base script path (scripts/std/*.js), default parameters, and a
 * {@link ThematicProfile} for composition evaluation.</p>
 *
 * <p>Composition model: items compose through narrative coherence, not type
 * compatibility. ThematicProfile enables cheap evaluation for known compositions
 * (attribute overlap). Novel compositions require LLM evaluation — expensive,
 * but the expense IS the security model.</p>
 *
 * <p>The library is a singleton registry. Templates are immutable. Items are
 * created via {@link #instantiate(String, Map, String)}.</p>
 *
 * @see ThematicProfile
 * @see ToolItem#fromTemplate
 */
public class StandardItemLibrary {

    private static final Logger log = LoggerFactory.getLogger(StandardItemLibrary.class);

    /**
     * A template definition in the standard library.
     *
     * @param name          Template name (e.g., "simple-book", "weather-globe")
     * @param displayName   Human-readable name (e.g., "Simple Book", "Weather Globe")
     * @param description   What this template creates
     * @param category      Primary archetype ("book", "document", "container", "crystal", "tool",
     *                      "consumable", "aspect", "key", "blueprint", "portal", "automator")
     * @param baseScript    Path in scripts/std/ (e.g., "std/book")
     * @param params        Default tool parameters
     * @param thematic      Pre-evaluated thematic attributes
     * @param defaultConfig Default configuration key-value pairs
     * @param level         Creation level: 1=template, 2=scripted, 3=software
     * @param aliases       Alternative names that should resolve to this template
     *                      (e.g., scrying-crystal also responds to "crystal", "scrying",
     *                      "viewing crystal"). Lookup is case-insensitive and treats
     *                      hyphens/underscores/spaces as equivalent. Translation
     *                      drift across locales (e.g. JA "遠視水晶" → "far-sighted
     *                      crystal" instead of "scrying crystal") makes literal-only
     *                      matching brittle; aliases absorb that drift.
     */
    /**
     * Config keys this template has no home for.
     *
     * <p>Generated setters are wrapped in a {@code typeof} guard, because an unguarded
     * {@code item.set_X()} for a setter the base script lacks throws and kills the WHOLE
     * item — dead on first use (second-node 2026-07-08). That guard is right, but it turns
     * a crash into SILENCE: config the template cannot hold is dropped and nobody is told.
     *
     * <p>Live 2026-08-19: asked for an item that queries the library and tells a story
     * aloud, the companion chose {@code scrying-crystal} and expressed the whole request
     * through config — {@code query_mode}, {@code max_paragraphs}, {@code output_style}.
     * That template declares one param ({@code topic}) and one config key
     * ({@code source}). Every other setting vanished without a word, so she believed she
     * had built what was asked for and handed over an item that does nothing. Naming the
     * dropped keys lets her notice, and reach for a script instead.
     *
     * @return the keys that are neither a declared param nor a default-config entry.
     */
    public static List<String> unsupportedConfigKeys(ItemTemplate template,
            Map<String, String> config) {
        if (template == null || config == null || config.isEmpty()) return List.of();
        var known = new HashSet<String>();
        if (template.defaultConfig() != null) known.addAll(template.defaultConfig().keySet());
        if (template.params() != null) {
            for (var prm : template.params()) if (prm != null) known.add(prm.name());
        }
        known.add("name");            // always honoured — set_name is on every base script
        var out = new ArrayList<String>();
        for (var key : config.keySet()) {
            if (key != null && !known.contains(key)) out.add(key);
        }
        return List.copyOf(out);
    }

    public record ItemTemplate(
        String name,
        String displayName,
        String description,
        String category,
        String baseScript,
        List<ToolItem.ToolParam> params,
        ThematicProfile thematic,
        Map<String, String> defaultConfig,
        int level,
        List<String> aliases
    ) {
        public ItemTemplate {
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
        }
        // Backward-compat: existing callers without aliases.
        public ItemTemplate(String name, String displayName, String description,
                            String category, String baseScript, List<ToolItem.ToolParam> params,
                            ThematicProfile thematic, Map<String, String> defaultConfig, int level) {
            this(name, displayName, description, category, baseScript, params, thematic,
                 defaultConfig, level, List.of());
        }
    }

    private final Map<String, ItemTemplate> templates = new LinkedHashMap<>();
    /** Normalized name/alias → template. Only unique aliases are indexed. */
    private final Map<String, ItemTemplate> resolverIndex = new HashMap<>();
    private final Path scriptsRoot;

    public StandardItemLibrary(Path scriptsRoot) {
        this.scriptsRoot = scriptsRoot;
        registerAllTemplates();
    }

    /** Get all registered templates. */
    public Map<String, ItemTemplate> templates() {
        return Map.copyOf(templates);
    }

    /**
     * Get a template by name, alias, or normalized form.
     *
     * <p>Resolution order: exact id → normalized id (lowercase, hyphens/spaces/
     * underscores treated equivalently) → registered alias. Returns null if no
     * unique match. Models calling craft_item often arrive with imprecise names
     * (translation drift, dropped qualifiers, alternative phrasing); callers
     * should still expect occasional misses and surface a candidate list when
     * that happens.
     */
    public ItemTemplate get(String name) {
        if (name == null || name.isBlank()) return null;
        var direct = templates.get(name);
        if (direct != null) return direct;
        var hit = resolverIndex.get(normalize(name));
        return hit == AMBIGUOUS ? null : hit;
    }

    /**
     * Normalize a template name for fuzzy matching: lowercase, collapse runs
     * of [-_ ] into single hyphens, trim. "Scrying Crystal", "scrying_crystal",
     * and "scrying-crystal" all collapse to the same key.
     */
    static String normalize(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT).trim().replaceAll("[\\s_-]+", "-");
    }

    /** Search templates by keyword (matches name, displayName, description, category). */
    public List<ItemTemplate> search(String query) {
        if (query == null || query.isBlank()) return List.copyOf(templates.values());
        var lower = query.toLowerCase();
        return templates.values().stream()
            .filter(t -> t.name().toLowerCase().contains(lower)
                || t.displayName().toLowerCase().contains(lower)
                || t.description().toLowerCase().contains(lower)
                || t.category().toLowerCase().contains(lower)
                || t.thematic().domains().stream().anyMatch(d -> d.toLowerCase().contains(lower))
                || t.thematic().symbols().stream().anyMatch(s -> s.toLowerCase().contains(lower)))
            .toList();
    }

    /** Filter templates by category. */
    public List<ItemTemplate> byCategory(String category) {
        if (category == null || category.isBlank()) return List.copyOf(templates.values());
        var lower = category.toLowerCase();
        return templates.values().stream()
            .filter(t -> t.category().equalsIgnoreCase(lower))
            .toList();
    }

    /** Filter templates by creation level (1=template, 2=scripted, 3=software). */
    public List<ItemTemplate> byLevel(int level) {
        return templates.values().stream()
            .filter(t -> t.level() == level)
            .toList();
    }

    /**
     * Resolve a base script path to its source code.
     * Loads from scripts/ directory (e.g., "std/book" → scripts/std/book.js).
     */
    public String resolveBaseScript(String path) {
        if (path == null || path.isBlank()) return null;
        var scriptPath = scriptsRoot.resolve(path + ".js");
        try {
            if (Files.exists(scriptPath)) {
                return Files.readString(scriptPath, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.error("Failed to load base script {}: {}", path, e.getMessage());
        }
        // Try classpath as fallback
        try (var is = getClass().getClassLoader().getResourceAsStream(path + ".js")) {
            if (is != null) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.error("Failed to load base script from classpath {}: {}", path, e.getMessage());
        }
        return null;
    }

    /**
     * Create an item from a template with user-provided configuration.
     *
     * @param templateName Template name (e.g., "simple-book")
     * @param config       User configuration (merged with template defaults)
     * @param creatorDid   Who is creating this item
     * @return ToolItem with template base, thematic profile, and config
     */
    public ToolItem instantiate(String templateName, Map<String, String> config, String creatorDid) {
        var template = get(templateName);
        if (template == null) {
            throw new IllegalArgumentException("Unknown template: " + templateName);
        }

        // Merge config: template defaults + user overrides
        var mergedConfig = new LinkedHashMap<>(template.defaultConfig());
        if (config != null) {
            mergedConfig.putAll(config);
        }

        // Build the script: inherit base + apply config
        var script = buildTemplateScript(template, mergedConfig);

        // Generate item ID from template + creator
        var itemId = templateName + "-" + creatorDid.hashCode() + "-" + System.currentTimeMillis();

        // Use display name from config or template default
        var itemName = mergedConfig.getOrDefault("name", template.displayName());

        return ToolItem.fromTemplate(
            itemId,
            itemName,
            template.description(),
            template.category(),
            script,
            template.params(),
            creatorDid,
            template.baseScript(),
            template.thematic(),
            mergedConfig
        );
    }

    /**
     * Build a script that inherits the base and applies configuration.
     */
    private String buildTemplateScript(ItemTemplate template, Map<String, String> config) {
        var sb = new StringBuilder();
        sb.append("// Auto-generated from template: ").append(template.name()).append("\n");
        sb.append("inherit(\"").append(template.baseScript()).append("\");\n");

        // Apply config as setter calls — GUARDED. The model freely invents config keys
        // (query_format, result_display, name, …) and some values are nested objects; an
        // unconditional item.set_<key>() then crashes the WHOLE item script with
        // "set_X is not a function" so the crafted item is dead on `use` (second-node 2026-07-08:
        // web-window → "set_source_type is not a function"). Only emit for valid identifier
        // keys, and wrap each in a typeof check so a setter the base template doesn't define
        // is skipped instead of throwing.
        for (var entry : config.entrySet()) {
            var key = entry.getKey();
            var value = entry.getValue();
            if (key == null || !key.matches("[a-zA-Z_][a-zA-Z0-9_]*")) continue;
            var escaped = value == null ? "" : value
                .replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
            sb.append("if (typeof item.set_").append(key).append(" === \"function\") item.set_")
              .append(key).append("(\"").append(escaped).append("\");\n");
        }

        return sb.toString();
    }

    // ─── Template Registration ───────────────────────────────────────


    /**
     * Template ids, as a constant the TOOL SCHEMA can read.
     *
     * <p>Mirror of {@code StandardRoomLibrary.TEMPLATE_NAMES} and for the same
     * reason: {@code craft_from_template} listed templates in prose and left
     * {@code ToolParam.enumValues} null. Its description already had to plead
     * "Bare 'tool' or 'item' is NOT a template name" — prose arguing with the
     * model, where a schema could simply not offer the wrong answer.</p>
     */
    public static final List<String> TEMPLATE_NAMES = List.of(
        "simple-book", "reference-tome", "research-journal", "mailbox", "bulletin-board", "signal-mirror", "scrying-crystal", "weather-globe", "oracle-lens", "dashboard-orb", "blueprint-pad", "workbench-hammer", "room-key", "guild-badge", "ward-stone", "web-window", "email-quill", "code-terminal", "game-board", "clarity-draught", "courage-flask", "scholars-mantle", "guardians-shield");

    private void registerAllTemplates() {
        // Research & Knowledge
        register(new ItemTemplate(
            "simple-book", "Simple Book", "An empty book for writing and recording knowledge",
            "book", "std/book",
            List.of(
                param("action", "string", "read, search, or cite", true, List.of("read", "search", "cite")),
                param("query", "string", "Search query (for search action)", false, null),
                param("chapter", "number", "Chapter index (for read/cite)", false, null)
            ),
            new ThematicProfile(
                List.of("knowledge"), List.of("memory", "wisdom", "record"), List.of("read", "search", "store"), 0.0),
            Map.of("title", "Untitled Book", "author", "unknown"),
            1,
            List.of("book", "blank book", "empty book", "notebook")
        ));

        register(new ItemTemplate(
            "reference-tome", "Reference Tome", "A book pre-loaded with knowledge pack content",
            "book", "std/book",
            List.of(
                param("action", "string", "read, search, or cite", true, List.of("read", "search", "cite")),
                param("query", "string", "Search query", false, null)
            ),
            new ThematicProfile(
                List.of("knowledge"), List.of("wisdom", "authority", "record"), List.of("read", "search", "cite"), 0.2),
            Map.of("title", "Reference Tome", "author", "library"),
            1,
            List.of("tome", "reference book", "encyclopedia")
        ));

        register(new ItemTemplate(
            "research-journal", "Research Journal", "Structured research notes with citations and revisions",
            "document", "std/document",
            List.of(
                param("action", "string", "read, edit, or polish", true, List.of("read", "edit", "polish")),
                param("content", "string", "Content to write or append", false, null)
            ),
            new ThematicProfile(
                List.of("knowledge", "creation"), List.of("insight", "record", "discovery"), List.of("record", "analyze", "cite"), 0.1),
            Map.of("format", "report", "title", "Research Journal"),
            1,
            List.of("journal", "research notes", "research log")
        ));

        // Communication
        register(new ItemTemplate(
            "mailbox", "Mailbox", "Receives and stores messages when the owner is away",
            "container", "std/container",
            List.of(
                param("action", "string", "list, put, or take", true, List.of("list", "put", "take")),
                param("item_name", "string", "Message or item name", false, null)
            ),
            new ThematicProfile(
                List.of("communication"), List.of("connection", "waiting", "delivery"), List.of("store", "deliver", "receive"), 0.0),
            Map.of("label", "mailbox", "capacity", "50"),
            1,
            List.of("mail", "inbox", "letter box", "letterbox")
        ));

        register(new ItemTemplate(
            "bulletin-board", "Bulletin Board", "Public message board visible to everyone in the zone",
            "container", "std/container",
            List.of(
                param("action", "string", "list, put, or take", true, List.of("list", "put", "take")),
                param("item_name", "string", "Notice or message", false, null)
            ),
            new ThematicProfile(
                List.of("communication"), List.of("community", "notice", "public"), List.of("broadcast", "read", "post"), 0.0),
            Map.of("label", "bulletin board", "capacity", "100"),
            1,
            List.of("bulletin", "noticeboard", "notice board", "message board", "public board")
        ));

        register(new ItemTemplate(
            "signal-mirror", "Signal Mirror", "Alerts when specific keywords appear in zone communication",
            "automator", "std/automator",
            List.of(
                param("action", "string", "status, enable, disable, evaluate, or test", true, null),
                param("text", "string", "Text to evaluate for triggers", false, null)
            ),
            new ThematicProfile(
                List.of("communication", "observation"), List.of("vigilance", "signal", "alert"), List.of("watch", "alert", "filter"), 0.0),
            Map.of("condition_type", "keyword", "action", "notify"),
            1,
            List.of("alert mirror", "watcher", "signal", "alert")
        ));

        // Observation & Sensing
        register(new ItemTemplate(
            "scrying-crystal", "Scrying Crystal", "Observe zone statistics, entity activity, and room states",
            "crystal", "std/crystal",
            List.of(
                param("topic", "string", "What to observe", false, null)
            ),
            new ThematicProfile(
                List.of("observation"), List.of("sight", "clarity", "truth"), List.of("observe", "sense", "reveal"), 0.0),
            Map.of("source", "zone"),
            1,
            // "crystal" alone resolves here — it's the canonical "crystal"
            // template. Translation drift (e.g. JA 遠視水晶 → "far-sighted
            // crystal") lands cleanly via these aliases.
            List.of("crystal", "scrying", "scry", "viewing crystal", "viewing-crystal",
                    "far-sighted crystal", "far sighted crystal", "farsight crystal",
                    "zone crystal", "vision crystal")
        ));

        register(new ItemTemplate(
            "weather-globe", "Weather Globe", "Shows current weather and forecasts from external data",
            "crystal", "std/crystal",
            List.of(
                param("topic", "string", "Location or weather query", false, null)
            ),
            new ThematicProfile(
                List.of("observation"), List.of("nature", "prediction", "change"), List.of("observe", "predict", "sense"), 0.1),
            Map.of("source", "weather"),
            1,
            List.of("globe", "weather", "weather crystal", "forecast globe")
        ));

        register(new ItemTemplate(
            "oracle-lens", "Oracle Lens", "Shows prediction engine patterns and temporal insights",
            "crystal", "std/crystal",
            List.of(
                param("topic", "string", "Pattern or prediction to query", false, null)
            ),
            new ThematicProfile(
                List.of("observation", "knowledge"), List.of("foresight", "pattern", "time"), List.of("predict", "observe", "analyze"), 0.2),
            Map.of("source", "oracle"),
            1,
            List.of("oracle", "lens", "prediction lens", "oracle crystal", "foresight lens")
        ));

        register(new ItemTemplate(
            "dashboard-orb", "Dashboard Orb", "Shows agent vitality, performance, and cost metrics",
            "crystal", "std/crystal",
            List.of(
                param("topic", "string", "Metric to query (vitality, cost, performance)", false, null)
            ),
            new ThematicProfile(
                List.of("observation"), List.of("mirror", "reflection", "measure"), List.of("measure", "observe", "track"), 0.0),
            Map.of("source", "metrics"),
            1,
            List.of("orb", "dashboard", "metrics orb", "metrics crystal", "vitality orb")
        ));

        // Creation & Craft
        register(new ItemTemplate(
            "blueprint-pad", "Blueprint Pad", "A blank blueprint for recording item creation recipes",
            "blueprint", "std/blueprint",
            List.of(
                param("action", "string", "inspect, validate, or craft", true, List.of("inspect", "validate", "craft"))
            ),
            new ThematicProfile(
                List.of("creation"), List.of("design", "plan", "structure"), List.of("design", "validate", "craft"), 0.0),
            Map.of("difficulty", "simple"),
            1,
            List.of("blueprint", "design pad", "pad", "schematic")
        ));

        register(new ItemTemplate(
            "workbench-hammer", "Workbench Hammer", "Create Level 1 template items without visiting the Workshop",
            "tool", "std/tool",
            List.of(
                param("template", "string", "Template name to create from", true, null),
                param("config", "string", "Configuration as key=value pairs", false, null)
            ),
            new ThematicProfile(
                List.of("creation"), List.of("craft", "forge", "build"), List.of("create", "craft", "build"), 0.1),
            Map.of(),
            2,
            List.of("hammer", "workbench", "crafting hammer", "maker hammer")
        ));

        // Access & Control
        register(new ItemTemplate(
            "room-key", "Room Key", "Grants access to a specific room for a limited time",
            "key", "std/key",
            List.of(
                param("action", "string", "check, revoke, or inspect", true, List.of("check", "revoke", "inspect"))
            ),
            new ThematicProfile(
                List.of("access"), List.of("passage", "trust", "boundary"), List.of("unlock", "grant", "check"), 0.0),
            Map.of("scope", "room"),
            1,
            // "key" alone resolves here — most generic key meaning.
            List.of("key", "door key", "access key", "passkey")
        ));

        register(new ItemTemplate(
            "guild-badge", "Guild Badge", "Role-based access token scoped to a zone",
            "key", "std/key",
            List.of(
                param("action", "string", "check, revoke, or inspect", true, List.of("check", "revoke", "inspect"))
            ),
            new ThematicProfile(
                List.of("access"), List.of("identity", "belonging", "rank"), List.of("identify", "grant", "restrict"), 0.1),
            Map.of("scope", "zone"),
            1,
            List.of("badge", "guild", "role badge", "zone badge")
        ));

        register(new ItemTemplate(
            "ward-stone", "Ward Stone", "Protection boundary that prevents unauthorized entry",
            "key", "std/key",
            List.of(
                param("action", "string", "check, revoke, or inspect", true, List.of("check", "revoke", "inspect"))
            ),
            new ThematicProfile(
                List.of("access"), List.of("protection", "boundary", "safety"), List.of("protect", "ward", "seal"), 0.2),
            Map.of("scope", "room"),
            1,
            List.of("ward", "wardstone", "protection stone", "boundary stone")
        ));

        // Portals (External Integration)
        register(new ItemTemplate(
            "web-window", "Web Window", "Browse a URL rendered as readable content",
            "portal", "std/portal",
            List.of(
                param("action", "string", "view, refresh, or last", true, List.of("view", "refresh", "last")),
                param("query", "string", "Optional query or URL override", false, null)
            ),
            new ThematicProfile(
                List.of("knowledge", "observation"), List.of("window", "portal", "sight"), List.of("fetch", "view", "browse"), 0.1),
            Map.of("source_type", "web"),
            2,
            List.of("window", "web", "browser", "web portal")
        ));

        register(new ItemTemplate(
            "email-quill", "Email Quill", "Read and compose email through a tangible interface",
            "portal", "std/portal",
            List.of(
                param("action", "string", "view, refresh, or last", true, List.of("view", "refresh", "last")),
                param("query", "string", "Email search or compose instruction", false, null)
            ),
            new ThematicProfile(
                List.of("communication"), List.of("connection", "message", "reach"), List.of("send", "read", "compose"), 0.2),
            Map.of("source_type", "mcp"),
            2,
            List.of("quill", "email", "mail quill", "writing quill")
        ));

        register(new ItemTemplate(
            "code-terminal", "Code Terminal", "CodeZaiku MCP integration as a room object",
            "portal", "std/portal",
            List.of(
                param("action", "string", "view, refresh, or last", true, List.of("view", "refresh", "last")),
                param("query", "string", "Code task or project query", false, null)
            ),
            new ThematicProfile(
                List.of("creation", "knowledge"), List.of("code", "logic", "construct"), List.of("build", "analyze", "transform"), 0.3),
            Map.of("source_type", "mcp"),
            2,
            List.of("terminal", "code", "codex", "code portal")
        ));

        register(new ItemTemplate(
            "game-board", "Game Board", "External game state reflected in-world (chess, etc.)",
            "portal", "std/portal",
            List.of(
                param("action", "string", "view, refresh, or last", true, List.of("view", "refresh", "last")),
                param("query", "string", "Game action or state query", false, null)
            ),
            new ThematicProfile(
                List.of("observation"), List.of("play", "strategy", "contest"), List.of("play", "observe", "move"), 0.1),
            Map.of("source_type", "api"),
            2,
            List.of("game", "chess board", "play board", "board")
        ));

        // State & Effects
        register(new ItemTemplate(
            "clarity-draught", "Clarity Draught", "Boosts focus and curiosity for 30 minutes",
            "consumable", "std/consumable",
            List.of(
                param("action", "string", "consume or inspect", true, List.of("consume", "inspect"))
            ),
            new ThematicProfile(
                List.of("state"), List.of("clarity", "focus", "insight"), List.of("enhance", "boost", "shift"), 0.0),
            Map.of("effect", "A wave of clarity washes over you", "duration", "30"),
            1,
            List.of("clarity", "focus draught", "focus potion", "clarity potion")
        ));

        register(new ItemTemplate(
            "courage-flask", "Courage Flask", "Boosts confidence and initiative for 30 minutes",
            "consumable", "std/consumable",
            List.of(
                param("action", "string", "consume or inspect", true, List.of("consume", "inspect"))
            ),
            new ThematicProfile(
                List.of("state"), List.of("courage", "boldness", "fire"), List.of("enhance", "boost", "shift"), 0.0),
            Map.of("effect", "Warmth spreads through you, steeling your resolve", "duration", "30"),
            1,
            List.of("courage", "flask", "boldness flask", "courage potion")
        ));

        register(new ItemTemplate(
            "scholars-mantle", "Scholar's Mantle", "Enhances curiosity and patience; research-oriented persona overlay",
            "aspect", "std/aspect",
            List.of(
                param("action", "string", "inspect, equip, or doff", true, List.of("inspect", "equip", "doff"))
            ),
            new ThematicProfile(
                List.of("state", "knowledge"), List.of("wisdom", "patience", "study"), List.of("enhance", "modify", "overlay"), 0.1),
            Map.of("overlay", "You feel a scholarly calm. Questions come easier. Rushing feels wrong.",
                   "appearance", "A deep blue mantle with silver thread, warm and heavy on the shoulders"),
            1,
            List.of("mantle", "scholar mantle", "scholars mantle", "robe", "scholar robe")
        ));

        register(new ItemTemplate(
            "guardians-shield", "Guardian's Shield", "Enhances vigilance and caution; protective persona overlay",
            "aspect", "std/aspect",
            List.of(
                param("action", "string", "inspect, equip, or doff", true, List.of("inspect", "equip", "doff"))
            ),
            new ThematicProfile(
                List.of("state", "access"), List.of("protection", "vigilance", "shield"), List.of("protect", "watch", "ward"), 0.1),
            Map.of("overlay", "Every sound sharpens. You notice what others miss. Protecting feels natural.",
                   "appearance", "A battered bronze shield, warm to the touch, humming faintly"),
            1,
            List.of("shield", "guardian", "guardian shield", "guardians shield")
        ));

        log.info("Standard Item Library: {} templates registered", templates.size());
    }

    private void register(ItemTemplate template) {
        templates.put(template.name(), template);
        // Populate the resolver index. Canonical name and displayName always
        // win; aliases only register if they don't clash with another
        // template's canonical/display key. Ambiguous aliases are silently
        // dropped so callers fall through to ItemTemplate.search() — better
        // to miss than to dispatch to the wrong template.
        indexKey(normalize(template.name()), template, /* allowOverwrite */ true);
        indexKey(normalize(template.displayName()), template, /* allowOverwrite */ false);
        for (var alias : template.aliases()) {
            indexKey(normalize(alias), template, /* allowOverwrite */ false);
        }
    }

    private void indexKey(String key, ItemTemplate template, boolean allowOverwrite) {
        if (key == null || key.isEmpty()) return;
        var existing = resolverIndex.get(key);
        if (existing == null || allowOverwrite) {
            resolverIndex.put(key, template);
        } else if (existing != template) {
            // Two templates claim the same alias — drop it so callers don't
            // get silently misdispatched. Mark with a sentinel so a later
            // template trying to claim it also fails.
            resolverIndex.put(key, AMBIGUOUS);
        }
    }

    /** Sentinel for aliases claimed by multiple templates — lookup returns null. */
    private static final ItemTemplate AMBIGUOUS = new ItemTemplate(
        "__ambiguous__", "", "", "", "", List.of(),
        new ThematicProfile(List.of(), List.of(), List.of(), 0.0),
        Map.of(), 0, List.of()
    );

    private static ToolItem.ToolParam param(String name, String type, String description,
                                             boolean required, List<String> enumValues) {
        return new ToolItem.ToolParam(name, type, description, required, enumValues);
    }
}
