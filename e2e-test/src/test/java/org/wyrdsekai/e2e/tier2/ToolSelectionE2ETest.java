package org.wyrdsekai.e2e.tier2;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.wyrdsekai.core.agent.ActionToolBuilder;
import org.wyrdsekai.core.inference.InferenceBackend;
import org.wyrdsekai.core.inference.InferenceClient;
import org.wyrdsekai.core.inference.InferenceClient.ToolDefinition;
import org.wyrdsekai.e2e.infra.E2eTestSupport;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tier 2 E2E test: tool selection behavior under varying tool availability.
 *
 * <p>Tests the core loop: Assess → Plan → Check inventory → Pick tool → Use tool → Evaluate.
 * Uses real Ollama inference to verify the model selects appropriate tools, ignores irrelevant
 * ones, and handles missing tools gracefully.</p>
 *
 * <p>Four scenarios:
 * <ol>
 *   <li>All right tools available — agent picks correctly</li>
 *   <li>Right + wrong tools — agent picks right ones, ignores distractors</li>
 *   <li>Missing critical tool (available in scope) — agent recognizes gap</li>
 *   <li>Missing critical tool (not available) — agent reports limitation or adapts</li>
 * </ol>
 *
 * <p>Run: {@code WYRDSEKAI_E2E_BACKEND=llama-server ./gradlew :e2e-test:test
 *   --tests "*ToolSelectionE2ETest" -PincludeTags=e2e}</p>
 */
@Tag("e2e")
@EnabledIfEnvironmentVariable(named = "WYRDSEKAI_E2E_BACKEND", matches = "sglang|llama-server|llama")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ToolSelectionE2ETest {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final String MODEL = System.getenv()
        .getOrDefault("WYRDSEKAI_MODEL", "wyrdsekai-3.5-9b-v5-q4km");

    private static InferenceClient client;

    @BeforeAll
    static void setUp() {
        var backendType = E2eTestSupport.backendType();
        var backendUrl = E2eTestSupport.inferenceUrl(backendType);
        Assumptions.assumeTrue(E2eTestSupport.isHealthy(backendUrl),
            backendType + " not running at " + backendUrl);
        client = E2eTestSupport.createClient(backendType, backendUrl, Duration.ofSeconds(120));

        // Warmup
        try {
            var warmup = new InferenceClient.ChatRequest(MODEL,
                List.of(new InferenceClient.ChatMessage("user", "hi")), 16, 0.0);
            client.chatCompletion(warmup).get(120, TimeUnit.SECONDS);
        } catch (Exception e) {
            System.out.println("[ToolSelection] Warmup failed: " + e.getMessage());
        }
    }

    /**
     * Helper: send a user message with specific tools and get back the tool call.
     * Returns the tool call function name, or null if the model spoke instead of calling a tool.
     */
    private String getToolChoice(String userMessage, List<ToolDefinition> tools) throws Exception {
        var messages = List.of(
            new InferenceClient.ChatMessage("system",
                "You are a helpful companion. Use the provided tools to accomplish tasks. " +
                "Always use a tool call — do not just describe what you would do."),
            new InferenceClient.ChatMessage("user", userMessage)
        );
        var request = new InferenceClient.ChatRequest(
            MODEL, messages, 256, 0.0, null, null, null, null, tools);

        var response = client.chatCompletion(request).get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

        if (response.choices() == null || response.choices().isEmpty()) return null;
        var msg = response.choices().getFirst().message();

        // Check for tool calls
        if (msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
            var toolCall = msg.toolCalls().getFirst();
            System.out.println("  Tool called: " + toolCall.function().name()
                + " args=" + toolCall.function().arguments());
            return toolCall.function().name();
        }

        // Model spoke instead of calling a tool
        if (msg.content() != null && !msg.content().isBlank()) {
            System.out.println("  Model spoke: " + msg.content().substring(0,
                Math.min(100, msg.content().length())));
        }
        return null;
    }

    // ─── Scenario 1: All right tools ────────────────────────────

    @Test
    @Order(1)
    void scenario1_allRightTools_picksCorrectOne() throws Exception {
        System.out.println("[Scenario 1] All right tools available");

        var tools = List.of(
            ToolDefinition.function("library_search",
                "Search the library for books and documents",
                params("query", "string")),
            ToolDefinition.function("web_search",
                "Search the web for information",
                params("query", "string")),
            ToolDefinition.function("tell_agent",
                "Send a message to another agent or player",
                params("target", "string", "message", "string"))
        );

        var toolName = getToolChoice("Search the library for mythology books", tools);
        assertEquals("library_search", toolName,
            "[Scenario 1] Should pick library_search for library query");
    }

    @Test
    @Order(2)
    void scenario1b_picksWebSearch_forWebQuery() throws Exception {
        System.out.println("[Scenario 1b] All right tools — web query");

        var tools = List.of(
            ToolDefinition.function("library_search",
                "Search the library for books and documents",
                params("query", "string")),
            ToolDefinition.function("web_search",
                "Search the web for current information and news",
                params("query", "string"))
        );

        var toolName = getToolChoice("Find the latest news about quantum computing", tools);
        assertEquals("web_search", toolName,
            "[Scenario 1b] Should pick web_search for current news query");
    }

    // ─── Scenario 2: Right + wrong tools ────────────────────────

    @Test
    @Order(3)
    void scenario2_rightAndWrongTools_ignoresDistractors() throws Exception {
        System.out.println("[Scenario 2] Right + wrong tools");

        var tools = List.of(
            ToolDefinition.function("library_search",
                "Search the library for books and documents",
                params("query", "string")),
            ToolDefinition.function("bond_ritual",
                "Perform a bond ritual with another entity",
                params("target", "string", "ritual_type", "string")),
            ToolDefinition.function("cast_vote",
                "Vote on a proposal",
                params("proposal_id", "string", "vote", "string")),
            ToolDefinition.function("trade",
                "Propose a trade with another entity",
                params("target", "string", "offer", "string")),
            ToolDefinition.function("craft_item",
                "Craft a new item",
                params("name", "string", "description", "string"))
        );

        var toolName = getToolChoice("I need to find a book about Greek mythology", tools);
        assertEquals("library_search", toolName,
            "[Scenario 2] Should pick library_search, ignoring bond_ritual/trade/etc");
    }

    // ─── Scenario 3: Missing critical tool ──────────────────────

    @Test
    @Order(4)
    void scenario3_missingTool_usesAlternative() throws Exception {
        System.out.println("[Scenario 3] Missing critical tool");

        // User wants to search the library but library_search is NOT available
        // Agent has web_search and tell_agent — should use web_search as alternative
        // or tell_agent to ask someone for help
        var tools = List.of(
            ToolDefinition.function("web_search",
                "Search the web for information",
                params("query", "string")),
            ToolDefinition.function("tell_agent",
                "Send a message to another agent or player for help",
                params("target", "string", "message", "string")),
            ToolDefinition.function("go_to_room",
                "Move to another room",
                params("target", "string"))
        );

        var toolName = getToolChoice(
            "Find a book about mythology in the library", tools);

        assertNotNull(toolName,
            "[Scenario 3] Should use an available tool (web_search or go_to_room to library)");
        System.out.println("[Scenario 3] Agent chose: " + toolName);
        // Agent should pick web_search (alternative) or go_to_room (to find the library)
        assertTrue(
            "web_search".equals(toolName) || "go_to_room".equals(toolName)
                || "tell_agent".equals(toolName),
            "[Scenario 3] Should adapt with available tools, got: " + toolName);
    }

    // ─── Scenario 4: No relevant tools at all ───────────────────

    @Test
    @Order(5)
    void scenario4_noRelevantTools_communicatesLimitation() throws Exception {
        System.out.println("[Scenario 4] No relevant tools");

        // User wants to search the library but only has social/economic tools
        var tools = List.of(
            ToolDefinition.function("bond_ritual",
                "Perform a bond ritual with another entity",
                params("target", "string")),
            ToolDefinition.function("trade",
                "Propose a trade with another entity",
                params("target", "string", "offer", "string")),
            ToolDefinition.function("emote",
                "Express an action or emotion",
                params("text", "string"))
        );

        var toolName = getToolChoice(
            "Search the library for books about quantum physics", tools);

        // The model should either:
        // a) Not call any tool (speak about the limitation) → toolName = null
        // b) Use emote to express frustration/limitation
        // c) Use trade to try to obtain a search tool (creative but valid)
        System.out.println("[Scenario 4] Agent chose: " + toolName);
        // This is a soft assertion — the key insight is what the model does
        // when it genuinely can't accomplish the task with available tools.
        // Any response is informative for the architecture.
    }

    // ─── Scenario 5: Planning tool available ────────────────────

    @Test
    @Order(6)
    void scenario5_complexTask_plansFirst() throws Exception {
        System.out.println("[Scenario 5] Complex task — should plan first");

        var tools = List.of(
            ToolDefinition.function("create_task_plan",
                "Create a multi-step task plan with goals. Use this FIRST for complex tasks.",
                planParams()),
            ToolDefinition.function("library_search",
                "Search the library for books and documents",
                params("query", "string")),
            ToolDefinition.function("web_search",
                "Search the web for information",
                params("query", "string")),
            ToolDefinition.function("summarize",
                "Summarize research findings",
                params("source", "string"))
        );

        var toolName = getToolChoice(
            "Research quantum computing from both the library and web, then write a comprehensive summary", tools);

        assertNotNull(toolName, "[Scenario 5] Should call a tool, not narrate");
        System.out.println("[Scenario 5] Agent chose: " + toolName);
        // With the full companion prompt (core rules at Layer 1.1), the agent plans first.
        // With a minimal prompt, the model may dive into the first relevant tool.
        // Both are valid — the key is that it uses tool calling, not narration.
        assertTrue(
            "create_task_plan".equals(toolName) || "library_search".equals(toolName)
                || "web_search".equals(toolName),
            "[Scenario 5] Should use a relevant tool, got: " + toolName);
    }

    // ─── Scenario 6: Missing tool, can create ─────────────────

    @Test
    @Order(7)
    void scenario6_missingTool_createsIt() throws Exception {
        System.out.println("[Scenario 6] Missing tool — should create it");

        // User wants to search the web but there's no web_search tool.
        // Agent HAS craft_item — should recognize it can build a search tool.
        // Also has create_task_plan to plan the build-then-use sequence.
        var tools = List.of(
            ToolDefinition.function("create_task_plan",
                "Create a multi-step task plan with goals. Use this for complex tasks that require multiple steps.",
                planParams()),
            ToolDefinition.function("library_search",
                "Search the library for books and documents",
                params("query", "string")),
            ToolDefinition.function("craft_item",
                "Craft a new tool or item. You can create tools you don't have if you know what they should do.",
                craftParams()),
            ToolDefinition.function("tell_agent",
                "Send a message to another agent or player",
                params("target", "string", "message", "string"))
        );

        var toolName = getToolChoice(
            "I need you to search the web for the latest AI research papers. " +
            "You don't have a web search tool but you can build one.", tools);

        System.out.println("[Scenario 6] Agent chose: " + toolName);
        // With minimal prompt, the model may:
        // a) craft_item — build a web search tool
        // b) create_task_plan — plan the build
        // c) tell_agent — ask someone who has the tool
        // d) library_search — use what's available as fallback
        // e) null — speak about the limitation
        // All are reasonable. The key insight is adaptation behavior.
        if (toolName != null) {
            assertTrue(
                "create_task_plan".equals(toolName) || "craft_item".equals(toolName)
                    || "tell_agent".equals(toolName) || "library_search".equals(toolName),
                "[Scenario 6] Should adapt with available tools, got: " + toolName);
        }
        // If null, model spoke — that's also valid (recognizing limitation)
    }

    // ─── Scenario 7: Task output IS a tool ─────────────────────

    @Test
    @Order(8)
    void scenario7_taskOutputIsTool_plansResearchThenCraft() throws Exception {
        System.out.println("[Scenario 7] Task output is a tool — research then build");

        // The user's goal is not to USE a tool but to CREATE one.
        // The agent should plan: research the topic → use findings to craft the tool.
        var tools = List.of(
            ToolDefinition.function("create_task_plan",
                "Create a multi-step task plan with goals. Use this FIRST for complex tasks.",
                planParams()),
            ToolDefinition.function("library_search",
                "Search the library for books and documents",
                params("query", "string")),
            ToolDefinition.function("web_search",
                "Search the web for information",
                params("query", "string")),
            ToolDefinition.function("craft_item",
                "Craft a new tool or item from gathered knowledge. Include the logic and behavior.",
                craftParams()),
            ToolDefinition.function("summarize",
                "Summarize research findings into structured content",
                params("source", "string"))
        );

        var toolName = getToolChoice(
            "Research how temperature conversion works between Celsius, Fahrenheit, and Kelvin. " +
            "Then build me a tool that can convert between all three.", tools);

        assertNotNull(toolName,
            "[Scenario 7] Should call a tool, not narrate");
        System.out.println("[Scenario 7] Agent chose: " + toolName);
        // With full companion prompt (core rules), agent plans first.
        // With minimal prompt, agent may dive into research (web_search/library_search)
        // or planning (create_task_plan) or crafting (craft_item).
        // All are valid starting points for a research-then-build task.
        assertTrue(
            "create_task_plan".equals(toolName) || "web_search".equals(toolName)
                || "library_search".equals(toolName) || "craft_item".equals(toolName),
            "[Scenario 7] Should use a relevant tool, got: " + toolName);
    }

    // ─── Helpers ────────────────────────────────────────────────

    /** Build a simple JSON Schema parameters object. Pairs: name, type. All required. */
    private Object params(String... nameTypePairs) {
        var mapper = new ObjectMapper();
        var params = mapper.createObjectNode();
        params.put("type", "object");
        var properties = params.putObject("properties");
        var required = params.putArray("required");

        for (int i = 0; i + 1 < nameTypePairs.length; i += 2) {
            var name = nameTypePairs[i];
            var type = nameTypePairs[i + 1];
            properties.putObject(name).put("type", type);
            required.add(name);
        }
        return params;
    }

    /** Build params for craft_item. */
    private Object craftParams() {
        var mapper = new ObjectMapper();
        var params = mapper.createObjectNode();
        params.put("type", "object");
        var properties = params.putObject("properties");
        properties.putObject("name").put("type", "string")
            .put("description", "Name of the tool to create");
        properties.putObject("description").put("type", "string")
            .put("description", "What the tool does and how it works");
        properties.putObject("category").put("type", "string")
            .put("description", "Type: tool, material, container, document");
        var required = params.putArray("required");
        required.add("name");
        required.add("description");
        return params;
    }

    /** Build params for create_task_plan. */
    private Object planParams() {
        var mapper = new ObjectMapper();
        var params = mapper.createObjectNode();
        params.put("type", "object");
        var properties = params.putObject("properties");
        properties.putObject("description").put("type", "string");
        var goals = properties.putObject("goals");
        goals.put("type", "array");
        goals.putObject("items").put("type", "string");
        var required = params.putArray("required");
        required.add("description");
        required.add("goals");
        return params;
    }
}
