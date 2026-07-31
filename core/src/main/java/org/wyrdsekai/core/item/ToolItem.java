package org.wyrdsekai.core.item;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.wyrdsekai.common.util.Json;
import org.wyrdsekai.core.inference.InferenceClient.ToolDefinition;
import org.wyrdsekai.core.inference.InferenceClient.ToolFunction;
import org.wyrdsekai.scripting.api.ItemEmbodimentSpec;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An executable tool item — a function pointer in the MUD.
 *
 * <p>Items are the capability layer. An agent discovers what it can do by
 * examining its inventory (equipped items) and room objects. Each tool item
 * declares its interface (params, return schema) and contains an executable
 * script (GraalJS) or references a built-in handler.</p>
 *
 * <p>Tool items are equippable, shareable, craftable, and composable.
 * An agent can use, examine, give, and create tool items. MCP endpoints
 * can be wrapped as tool items with {@code mcpServer}/{@code mcpTool} fields.</p>
 *
 * <p>The agent's tool set = inherent actions + equipped tool items + room objects.
 * Typically 5-15 tools, not 66.</p>
 *
 * @param id            Unique item ID
 * @param name          Human-readable name (e.g., "Library Membership Card")
 * @param description   What it does (shown to LLM as tool description)
 * @param category      "tool", "material", "container", "document"
 * @param params        Input parameters for the tool
 * @param returnDesc    Description of what the tool returns (nullable)
 * @param script        GraalJS source code (nullable — built-in handlers have no script)
 * @param builtinHandler Name of built-in Java handler (nullable — e.g., "library_search")
 * @param mcpServer     MCP server name (nullable — for MCP-wrapped items)
 * @param mcpTool       MCP tool name (nullable)
 * @param creatorDid    Who created this item
 * @param created       When created
 * @param composable    Whether other items can invoke this one
 * @param templateBase  Standard library base script path (e.g., "std/book") — nullable
 * @param thematic      Thematic profile for composition evaluation — nullable
 * @param config        Template configuration key-value pairs — nullable
 * @param embodiment — declared embodiment block (silent vs emits).
 *                      Nullable for back-compat with serialized JSON; the §18.2 audit
 *                      WARNs on any starter-kit item where this is null.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolItem(
    @JsonProperty("id") String id,
    @JsonProperty("name") String name,
    @JsonProperty("description") String description,
    @JsonProperty("category") String category,
    @JsonProperty("params") List<ToolParam> params,
    @JsonProperty("returnDesc") String returnDesc,
    @JsonProperty("script") String script,
    @JsonProperty("builtinHandler") String builtinHandler,
    @JsonProperty("mcpServer") String mcpServer,
    @JsonProperty("mcpTool") String mcpTool,
    @JsonProperty("creatorDid") String creatorDid,
    @JsonProperty("created") Instant created,
    @JsonProperty("composable") boolean composable,
    @JsonProperty("templateBase") String templateBase,
    @JsonProperty("thematic") ThematicProfile thematic,
    @JsonProperty("config") Map<String, String> config,
    @JsonProperty("embodiment") ItemEmbodimentSpec embodiment
) {
    @JsonCreator
    public ToolItem {}

    /** Back-compat 16-arg constructor — defaults embodiment to null. */
    public ToolItem(String id, String name, String description, String category,
                    List<ToolParam> params, String returnDesc,
                    String script, String builtinHandler,
                    String mcpServer, String mcpTool,
                    String creatorDid, Instant created, boolean composable,
                    String templateBase, ThematicProfile thematic,
                    Map<String, String> config) {
        this(id, name, description, category, params, returnDesc,
            script, builtinHandler, mcpServer, mcpTool,
            creatorDid, created, composable,
            templateBase, thematic, config, null);
    }

    /**
     * Return a copy with the given embodiment block attached.
     * One-line use at construction sites:
     * {@snippet :
     *   return ToolItem.builtin("foo", "Foo", "...", "fooHandler", List.of())
     *       .withEmbodiment(ItemEmbodimentSpec.silent("pure compute"));
     * }
     */
    public ToolItem withEmbodiment(ItemEmbodimentSpec spec) {
        return new ToolItem(id, name, description, category, params, returnDesc,
            script, builtinHandler, mcpServer, mcpTool,
            creatorDid, created, composable,
            templateBase, thematic, config, spec);
    }

    /** Parameter definition for a tool item. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ToolParam(
        @JsonProperty("name") String name,
        @JsonProperty("type") String type,          // "string", "number", "boolean"
        @JsonProperty("description") String description,
        @JsonProperty("required") boolean required,
        @JsonProperty("enumValues") List<String> enumValues  // nullable
    ) {
        @JsonCreator
        public ToolParam {}
    }

    /**
     * Convert this item to a tool definition for the LLM tools parameter.
     */
    public ToolDefinition toToolDefinition() {
        var mapper = Json.mapper();
        var paramsSchema = mapper.createObjectNode();
        paramsSchema.put("type", "object");
        var properties = paramsSchema.putObject("properties");
        var required = paramsSchema.putArray("required");

        if (params != null) {
            for (var param : params) {
                var prop = properties.putObject(param.name());
                prop.put("type", param.type() != null ? param.type() : "string");
                if (param.description() != null) {
                    prop.put("description", param.description());
                }
                if (param.enumValues() != null && !param.enumValues().isEmpty()) {
                    var enumArr = prop.putArray("enum");
                    param.enumValues().forEach(enumArr::add);
                }
                if (param.required()) {
                    required.add(param.name());
                }
            }
        }

        return ToolDefinition.function(
            id != null ? id : name.toLowerCase().replace(' ', '_'),
            description,
            paramsSchema);
    }

    /** Whether this item has an executable script. */
    public boolean isScripted() {
        return script != null && !script.isBlank();
    }

    /** Whether this item wraps a built-in Java handler. */
    public boolean isBuiltin() {
        return builtinHandler != null && !builtinHandler.isBlank();
    }

    /** Whether this item wraps an MCP tool. */
    public boolean isMcp() {
        return mcpServer != null && mcpTool != null;
    }

    // ─── Builder helpers ────────────────────────────────────────

    /**
     * Create a tool item that wraps a built-in action handler.
     * Used for starter kit items that delegate to existing CompanionActor handlers.
     */
    public static ToolItem builtin(String id, String name, String description,
                                     String builtinHandler, List<ToolParam> params) {
        return new ToolItem(id, name, description, "tool", params, null,
            null, builtinHandler, null, null, "wyrdsekai", Instant.now(), true,
            null, null, null);
    }

    /**
     * Create a scripted tool item with GraalJS source.
     */
    public static ToolItem scripted(String id, String name, String description,
                                      String script, List<ToolParam> params,
                                      String creatorDid) {
        return new ToolItem(id, name, description, "tool", params, null,
            script, null, null, null, creatorDid, Instant.now(), true,
            null, null, null);
    }

    /**
     * Create a scripted tool item from a standard library template.
     */
    public static ToolItem fromTemplate(String id, String name, String description,
                                          String category, String script,
                                          List<ToolParam> params, String creatorDid,
                                          String templateBase, ThematicProfile thematic,
                                          Map<String, String> config) {
        return new ToolItem(id, name, description, category, params, null,
            script, null, null, null, creatorDid, Instant.now(), true,
            templateBase, thematic, config);
    }

    /**
     * Create a tool item that wraps an MCP endpoint.
     */
    public static ToolItem mcp(String id, String name, String description,
                                 String mcpServer, String mcpTool, List<ToolParam> params) {
        return new ToolItem(id, name, description, "tool", params, null,
            null, null, mcpServer, mcpTool, "wyrdsekai", Instant.now(), true,
            null, null, null);
    }

    /** Whether this item was created from a standard library template. */
    public boolean isTemplate() {
        return templateBase != null && !templateBase.isBlank();
    }
}
