package org.wyrdsekai.core.i18n;

import org.junit.jupiter.api.*;

import org.wyrdsekai.core.soul.*;
import org.wyrdsekai.core.agent.*;
import org.wyrdsekai.core.agent.TranslationPrompts.TranslationType;
import org.wyrdsekai.common.event.WorldEvent;
import org.wyrdsekai.common.model.*;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for i18n wiring across memory, consolidation,
 * prompt assembly, translation prompts, and locale detection.
 *
 * Tests verify that the originLocale field on MemoryNode flows correctly
 * through the full lifecycle: creation -> decay -> consolidation -> prompt assembly.
 */
class I18nIntegrationTest {

    // ── Shared helpers ──

    private static final Instant NOW = Instant.parse("2026-03-08T12:00:00Z");

    private static AgentProfile testProfile(String systemPrompt, int contextWindow) {
        return new AgentProfile(
            "TestAgent", "agent-1", "agent", "A test agent",
            systemPrompt, contextWindow, 200, 0.7
        );
    }

    private static RoomSnapshot testRoom() {
        return new RoomSnapshot(
            "room-1", "The Nexus", "A shimmering crossroads.", "foundation",
            List.of(new Exit("north", "room-2", "A corridor leads north")),
            List.of(new Entity("player-1", "Kai", "player", "A weary traveler")),
            List.of(),
            List.of()
        );
    }

    private static WorldEvent.Said said(String entityId, String entityName, String text) {
        return new WorldEvent.Said("room-1", NOW, entityId, entityName, text);
    }

    // ── 1. MemoryNodeLocaleTests ──

    @Nested
    class MemoryNodeLocaleTests {

        @Test
        void neutral_default_locale_is_en() {
            var node = MemoryNode.neutral("m1", "Hello world", List.of("greeting"));
            assertEquals("en", node.originLocale(),
                "Neutral memory should default to 'en' locale");
        }

        @Test
        void neutral_with_explicit_locale() {
            var node = MemoryNode.neutral("m1", "Bonjour le monde", List.of("greeting"), "fr");
            assertEquals("fr", node.originLocale());
            assertEquals("Bonjour le monde", node.content());
        }

        @Test
        void formative_default_locale_is_en() {
            var node = MemoryNode.formative("m1", "A defining moment",
                List.of("identity"), "joy", 0.9f);
            assertEquals("en", node.originLocale(),
                "Formative memory should default to 'en' locale");
        }

        @Test
        void formative_with_explicit_locale() {
            var node = MemoryNode.formative("m1", "人生を変えた瞬間",
                List.of("identity"), "joy", 0.9f, "ja");
            assertEquals("ja", node.originLocale());
            assertTrue(node.formative());
        }

        @Test
        void decayed_preserves_locale() {
            var node = MemoryNode.neutral("m1", "Un recuerdo", List.of("memory"), "es");
            var decayed = node.decayed(0.1f);
            assertEquals("es", decayed.originLocale(),
                "Decay must preserve originLocale");
            assertTrue(decayed.importance() < node.importance(),
                "Importance should decrease after decay");
        }

        @Test
        void accessed_preserves_locale() {
            var node = MemoryNode.neutral("m1", "思い出", List.of("memory"), "ja");
            var accessed = node.accessed();
            assertEquals("ja", accessed.originLocale(),
                "Access must preserve originLocale");
            assertEquals(1, accessed.accessCount());
        }

        @Test
        void japanese_memory_node() {
            var node = MemoryNode.neutral("m1", "桜の花が散る", List.of("sakura", "nature"), "ja");
            assertEquals("ja", node.originLocale());
            assertEquals("桜の花が散る", node.content());
            assertTrue(node.keywords().contains("sakura"));
        }

        @Test
        void code_switched_memory_node() {
            var node = MemoryNode.neutral("m1",
                "Let's go to the お祭り together", List.of("festival", "plan"), "en");
            assertEquals("en", node.originLocale(),
                "Code-switched content uses the dominant language as locale");
            assertTrue(node.content().contains("お祭り"));
        }
    }

    // ── 2. ConsolidationLocaleTests ──

    @Nested
    class ConsolidationLocaleTests {

        private CompactedMemory memoryWith(MemoryNode... nodes) {
            return new CompactedMemory(List.of(nodes), List.of(), Map.of());
        }

        @Test
        void consolidation_preserves_origin_locales() {
            var en = MemoryNode.neutral("m1", "Hello friend", List.of("greeting"), "en");
            var ja = MemoryNode.neutral("m2", "こんにちは友達", List.of("greeting"), "ja");
            var current = memoryWith(en, ja);

            var result = MemoryConsolidator.consolidate(current, List.of(), 0.05f);

            var locales = result.nodes().stream()
                .collect(Collectors.toMap(MemoryNode::id, MemoryNode::originLocale));
            assertEquals("en", locales.get("m1"));
            assertEquals("ja", locales.get("m2"));
        }

        @Test
        void locale_aware_consolidation_detects_missing_locale() {
            // A legacy node without explicit locale should have the default "en"
            var node = MemoryNode.neutral("m1", "Old memory", List.of("legacy"));
            var current = memoryWith(node);
            var result = MemoryConsolidator.consolidate(current, List.of(), 0.05f);
            assertFalse(result.nodes().isEmpty());
            assertEquals("en", result.nodes().getFirst().originLocale());
        }

        @Test
        void locale_aware_consolidation_keeps_existing_locale() {
            var node = MemoryNode.neutral("m1", "Hola mundo", List.of("greeting"), "es");
            var current = memoryWith(node);
            var result = MemoryConsolidator.consolidate(current, List.of(), 0.01f);
            assertEquals("es", result.nodes().getFirst().originLocale());
        }

        @Test
        void multilingual_memories_survive_consolidation() {
            var en = MemoryNode.neutral("m1", "The market was busy", List.of("market"), "en");
            var ja = MemoryNode.neutral("m2", "市場は賑わっていた", List.of("market"), "ja");
            var es = MemoryNode.neutral("m3", "El mercado estaba lleno", List.of("market"), "es");
            var current = memoryWith(en, ja, es);

            // Low decay so nothing gets pruned
            var result = MemoryConsolidator.consolidate(current, List.of(), 0.01f);

            assertEquals(3, result.nodes().size(), "All multilingual memories should survive");
            var localeSet = result.nodes().stream()
                .map(MemoryNode::originLocale)
                .collect(Collectors.toSet());
            assertTrue(localeSet.contains("en"));
            assertTrue(localeSet.contains("ja"));
            assertTrue(localeSet.contains("es"));
        }

        @Test
        void formative_japanese_memory_preserved() {
            var formative = MemoryNode.formative("m1", "初めて桜を見た日",
                List.of("sakura", "first"), "joy", 0.95f, "ja");
            var filler = MemoryNode.neutral("m2", "Nothing happened", List.of("filler"), "en");
            var current = memoryWith(formative, filler);

            // Aggressive decay
            var result = MemoryConsolidator.consolidate(current, List.of(), 0.9f);

            var formativeNodes = result.nodes().stream()
                .filter(MemoryNode::formative).toList();
            assertEquals(1, formativeNodes.size(), "Formative memory must survive aggressive consolidation");
            assertEquals("ja", formativeNodes.getFirst().originLocale());
            assertEquals("初めて桜を見た日", formativeNodes.getFirst().content());
        }

        @Test
        void mixed_language_topic_weights() {
            var en = MemoryNode.neutral("m1", "Cooking dinner", List.of("cooking", "food"), "en");
            var ja = MemoryNode.neutral("m2", "料理を作る", List.of("cooking", "food"), "ja");
            var current = memoryWith(en, ja);

            var result = MemoryConsolidator.consolidate(current, List.of(), 0.01f);

            // Both use the same keywords, so topic weights should reflect combined weight
            assertTrue(result.topicWeights().containsKey("cooking"),
                "Shared keyword across languages should appear in topic weights");
            assertTrue(result.topicWeights().get("cooking") > 0);
        }

        @Test
        void default_consolidation_still_works() {
            // Backward compat: 2-arg consolidate without explicit decay rate
            var node = MemoryNode.neutral("m1", "A simple memory", List.of("simple"));
            var current = memoryWith(node);
            var result = MemoryConsolidator.consolidate(current, List.of());
            assertNotNull(result);
            assertFalse(result.nodes().isEmpty());
        }

        @Test
        void consolidation_with_null_policy_works() {
            // Backward compat: consolidation works without any locale policy reference
            var node = MemoryNode.neutral("m1", "Basic memory", List.of("basic"));
            var newNode = MemoryNode.neutral("m2", "New memory", List.of("new"));
            var current = memoryWith(node);
            var result = MemoryConsolidator.consolidate(current, List.of(newNode), 0.05f);
            assertEquals(2, result.nodes().size());
        }
    }

    // ── 3. PromptAssemblerLocaleTests ──

    @Nested
    class PromptAssemblerLocaleTests {

        @Test
        void layer_2_6_locale_context_injected_for_non_english() {
            var profile = testProfile("You are a helpful companion.", 8000);
            var room = testRoom();
            var trigger = said("player-1", "Kai", "こんにちは");
            String localeCtx = TranslationPrompts.localeContext("Japanese", "ja", 42);

            var messages = PromptAssembler.assemble(
                profile, room, List.of(), trigger,
                null, List.of(), null, localeCtx);

            boolean hasLocaleLayer = messages.stream()
                .anyMatch(m -> m.content().contains("Japanese") && m.content().contains("ja"));
            assertTrue(hasLocaleLayer, "Layer 2.6 should be present for non-English locale");
        }

        @Test
        void layer_2_6_not_injected_for_english() {
            var profile = testProfile("You are a helpful companion.", 8000);
            var room = testRoom();
            var trigger = said("player-1", "Kai", "Hello there");

            // Pass null locale context for English users
            var messages = PromptAssembler.assemble(
                profile, room, List.of(), trigger,
                null, List.of(), null, null);

            boolean hasLocaleLayer = messages.stream()
                .anyMatch(m -> m.content().contains("User language preference"));
            assertFalse(hasLocaleLayer, "No locale layer should be injected for English");
        }

        @Test
        void layer_2_6_trimmed_when_over_budget() {
            // Very small context window — locale context should be trimmed
            var profile = testProfile("You are a helpful companion.", 100);
            var room = testRoom();
            var trigger = said("player-1", "Kai", "Hello");
            String localeCtx = TranslationPrompts.localeContext("Japanese", "ja", 42);

            var messages = PromptAssembler.assemble(
                profile, room, List.of(), trigger,
                null, List.of(), null, localeCtx);

            // With a 100-token window, the locale context likely gets trimmed
            // The test verifies no crash occurs and system prompt is always present
            assertFalse(messages.isEmpty(), "Should always have at least the system prompt");
            assertEquals("system", messages.getFirst().role());
        }

        @Test
        void locale_context_format_matches_translation_prompts() {
            String ctx = TranslationPrompts.localeContext("Japanese", "ja", 15);
            assertTrue(ctx.contains("Japanese"));
            assertTrue(ctx.contains("ja"));
            assertTrue(ctx.contains("15"));

            // Verify the format can be parsed/found in assembled messages
            var profile = testProfile("System prompt.", 8000);
            var trigger = said("player-1", "Kai", "Test");

            var messages = PromptAssembler.assemble(
                profile, null, List.of(), trigger,
                null, List.of(), null, ctx);

            var localeMsg = messages.stream()
                .filter(m -> m.content().contains("User language preference"))
                .findFirst();
            assertTrue(localeMsg.isPresent());
            assertEquals(ctx, localeMsg.get().content());
        }

        @Test
        void null_locale_context_no_injection() {
            var profile = testProfile("System prompt.", 8000);
            var trigger = said("player-1", "Kai", "Hello");

            var messages = PromptAssembler.assemble(
                profile, null, List.of(), trigger,
                null, List.of(), null, null);

            boolean hasLocaleLayer = messages.stream()
                .anyMatch(m -> m.content().contains("User language preference"));
            assertFalse(hasLocaleLayer);
        }

        @Test
        void blank_locale_context_no_injection() {
            var profile = testProfile("System prompt.", 8000);
            var trigger = said("player-1", "Kai", "Hello");

            var messages = PromptAssembler.assemble(
                profile, null, List.of(), trigger,
                null, List.of(), null, "   ");

            boolean hasLocaleLayer = messages.stream()
                .anyMatch(m -> m.content().contains("User language preference"));
            assertFalse(hasLocaleLayer, "Blank locale context should not be injected");
        }
    }

    // ── 4. TranslationPromptsTests ──

    @Nested
    class TranslationPromptsTests {

        @Test
        void locale_context_contains_language_name() {
            String ctx = TranslationPrompts.localeContext("Japanese", "ja", 10);
            assertTrue(ctx.contains("Japanese"));
        }

        @Test
        void locale_context_contains_language_code() {
            String ctx = TranslationPrompts.localeContext("Spanish", "es", 5);
            assertTrue(ctx.contains("es"));
        }

        @Test
        void locale_context_contains_term_count() {
            String ctx = TranslationPrompts.localeContext("Korean", "ko", 77);
            assertTrue(ctx.contains("77"));
            assertTrue(ctx.contains("shared terms available"));
        }

        @Test
        void system_prompt_prose_contains_languages() {
            String prompt = TranslationPrompts.systemPrompt(
                TranslationType.PROSE, "English", "Japanese");
            assertTrue(prompt.contains("English"));
            assertTrue(prompt.contains("Japanese"));
            assertTrue(prompt.contains("literary translator"),
                "Prose prompt should describe literary translation");
        }

        @Test
        void system_prompt_detect_is_language_agnostic() {
            String prompt = TranslationPrompts.systemPrompt(
                TranslationType.DETECT, "English", "Japanese");
            // DETECT ignores source/target — it just identifies language
            assertTrue(prompt.contains("Identify the language"));
            assertFalse(prompt.contains("English"),
                "DETECT prompt should not reference specific languages");
            assertFalse(prompt.contains("Japanese"),
                "DETECT prompt should not reference specific languages");
        }
    }

    // ── 5. DreamOrderTests ──

    @Nested
    class DreamOrderTests {

        private final MemoryLocalePolicy policy = new MemoryLocalePolicy();

        @Test
        void dream_order_recency_weighted() {
            var recency = Map.of("f1", 0.2, "f2", 0.8, "f3", 0.5);
            var locales = Map.of("f1", "en", "f2", "ja", "f3", "es");
            var order = policy.dreamOrder(recency, locales);

            assertEquals("f2", order.get(0), "Most recent fragment first");
            assertEquals("f3", order.get(1));
            assertEquals("f1", order.get(2), "Least recent fragment last");
        }

        @Test
        void dream_order_multilingual_fragments_included() {
            var recency = Map.of("f-en", 0.3, "f-ja", 0.9, "f-zh", 0.6, "f-ko", 0.1);
            var locales = Map.of("f-en", "en", "f-ja", "ja", "f-zh", "zh", "f-ko", "ko");
            var order = policy.dreamOrder(recency, locales);

            assertEquals(4, order.size(), "All language fragments should be included");
            assertEquals("f-ja", order.get(0), "Japanese fragment is most recent");
            assertEquals("f-ko", order.get(3), "Korean fragment is least recent");
        }

        @Test
        void dream_order_empty_map() {
            var order = policy.dreamOrder(Map.of(), Map.of());
            assertTrue(order.isEmpty(), "Empty input should yield empty dream order");
        }

        @Test
        void dream_order_single_fragment() {
            var recency = Map.of("only", 0.5);
            var locales = Map.of("only", "ja");
            var order = policy.dreamOrder(recency, locales);
            assertEquals(1, order.size());
            assertEquals("only", order.getFirst());
        }
    }

    // ── 6. LocaleDetectionIntegrationTests ──

    @Nested
    class LocaleDetectionIntegrationTests {

        private final MemoryLocalePolicy policy = new MemoryLocalePolicy();

        @Test
        void detect_and_tag_japanese_memory() {
            String content = "今日は素晴らしい一日だった";
            String detected = policy.detectLanguage(content);
            assertEquals("ja", detected);

            var node = MemoryNode.neutral("m1", content, List.of("day", "mood"), detected);
            assertEquals("ja", node.originLocale());
        }

        @Test
        void detect_and_tag_spanish_memory() {
            String content = "¿Cómo estás hoy?";
            String detected = policy.detectLanguage(content);
            assertEquals("es", detected);

            var node = MemoryNode.neutral("m1", content, List.of("greeting"), detected);
            assertEquals("es", node.originLocale());
        }

        @Test
        void detect_and_tag_english_memory() {
            String content = "The sunset was beautiful today";
            String detected = policy.detectLanguage(content);
            assertEquals("en", detected);

            var node = MemoryNode.neutral("m1", content, List.of("sunset"), detected);
            assertEquals("en", node.originLocale());
        }

        @Test
        void detect_code_switched_content() {
            // Code-switched text with both English and Japanese
            String content = "Let's go to the お祭り tonight";
            String detected = policy.detectLanguage(content);
            // Heuristic detects CJK characters and returns "ja"
            assertEquals("ja", detected,
                "Code-switched content with Japanese should detect as 'ja'");
        }

        @Test
        void detect_empty_content() {
            assertEquals("unknown", policy.detectLanguage(""));
            assertEquals("unknown", policy.detectLanguage(null));
        }
    }

    // ── 7. EndToEndLocaleFlowTests ──

    @Nested
    class EndToEndLocaleFlowTests {

        @Test
        void full_cycle_japanese_memory_preserved() {
            // Create a memory in Japanese
            var jaNode = MemoryNode.neutral("m-ja", "桜の木の下で友達と話した",
                List.of("sakura", "friend", "conversation"), "ja");
            assertTrue(jaNode.formative() == false);
            assertEquals("ja", jaNode.originLocale());

            // Put it in compacted memory and consolidate
            var memory = new CompactedMemory(List.of(jaNode), List.of(), Map.of());
            var consolidated = MemoryConsolidator.consolidate(memory, List.of(), 0.05f);

            // Verify originLocale survives consolidation
            assertEquals(1, consolidated.nodes().size());
            assertEquals("ja", consolidated.nodes().getFirst().originLocale());
            assertEquals("桜の木の下で友達と話した", consolidated.nodes().getFirst().content(),
                "Japanese content must not be translated during consolidation");
        }

        @Test
        void full_cycle_mixed_language_memories() {
            var en = MemoryNode.neutral("m-en", "We explored the old ruins",
                List.of("exploration", "ruins"), "en");
            var ja = MemoryNode.formative("m-ja", "初めて友と出会った場所",
                List.of("meeting", "friend"), "joy", 0.9f, "ja");
            var es = MemoryNode.neutral("m-es", "El castillo era impresionante",
                List.of("castle", "exploration"), "es");

            var memory = new CompactedMemory(List.of(en, ja, es), List.of(), Map.of());

            // Two consolidation cycles
            var cycle1 = MemoryConsolidator.consolidate(memory, List.of(), 0.1f);
            var cycle2 = MemoryConsolidator.consolidate(cycle1, List.of(), 0.1f);

            // All three should survive (formative never pruned, others have enough importance)
            var localeMap = cycle2.nodes().stream()
                .collect(Collectors.toMap(MemoryNode::id, MemoryNode::originLocale));

            // Formative Japanese memory MUST survive
            assertEquals("ja", localeMap.get("m-ja"),
                "Formative Japanese memory must survive multiple consolidation cycles");

            // Non-formative may or may not survive depending on decay,
            // but if they survive, their locale must be preserved
            if (localeMap.containsKey("m-en")) {
                assertEquals("en", localeMap.get("m-en"));
            }
            if (localeMap.containsKey("m-es")) {
                assertEquals("es", localeMap.get("m-es"));
            }
        }

        @Test
        void prompt_assembler_full_stack_with_locale() {
            var profile = testProfile(
                "You are Hana, a companion spirit of the garden.", 8000);
            var room = testRoom();
            var history = List.of(
                said("player-1", "Kai", "こんにちは、花さん"),
                said("agent-1", "Hana", "こんにちは、カイさん。お元気ですか？")
            );
            var trigger = said("player-1", "Kai", "今日の庭はどうですか？");
            String localeCtx = TranslationPrompts.localeContext("Japanese", "ja", 25);

            var messages = PromptAssembler.assemble(
                profile, room, history, trigger,
                null, List.of(), null, localeCtx);

            // Layer 0 (language-floor arc 2026-07-31): the locale block LEADS —
            // the pin only works in leading position. Identity follows at L1.
            assertEquals("system", messages.getFirst().role());
            assertTrue(messages.getFirst().content().contains("Japanese"),
                "locale pin must be the first tokens of the request");
            assertEquals("system", messages.get(1).role());
            assertTrue(messages.get(1).content().contains("Hana"));

            // Layer 2.6: locale context present
            boolean hasLocale = messages.stream()
                .anyMatch(m -> m.content().contains("Japanese") && m.content().contains("ja"));
            assertTrue(hasLocale, "Locale context layer must be present");

            // Conversation history present
            boolean hasHistory = messages.stream()
                .anyMatch(m -> m.content().contains("こんにちは"));
            assertTrue(hasHistory, "Conversation history must include Japanese text");

            // Trigger event present
            boolean hasTrigger = messages.stream()
                .anyMatch(m -> m.content().contains("今日の庭"));
            assertTrue(hasTrigger, "Trigger event must be in the prompt");
        }

        @Test
        void translation_prompts_all_types() {
            for (TranslationType type : TranslationType.values()) {
                String prompt = TranslationPrompts.systemPrompt(type, "English", "Japanese");
                assertNotNull(prompt, "System prompt for " + type + " must not be null");
                assertFalse(prompt.isBlank(), "System prompt for " + type + " must not be blank");

                double temp = TranslationPrompts.temperature(type);
                assertTrue(temp >= 0.0 && temp <= 1.0,
                    "Temperature for " + type + " must be in [0, 1]");

                int maxTok = TranslationPrompts.maxTokens(type);
                assertTrue(maxTok > 0,
                    "Max tokens for " + type + " must be positive");
            }

            // DETECT specifically should NOT contain source/target language names
            String detectPrompt = TranslationPrompts.systemPrompt(
                TranslationType.DETECT, "English", "Japanese");
            assertFalse(detectPrompt.contains("English"));
            assertFalse(detectPrompt.contains("Japanese"));
        }
    }
}
