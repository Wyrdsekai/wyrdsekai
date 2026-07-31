package org.wyrdsekai.core.soul;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.event.WorldEvent;

import java.time.Instant;
import java.util.*;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Phase 3: Behavioral Extraction pipeline.
 * EmotionalCharge, EmotionalChargeScorer, ImpressionScorer,
 * NegativeSpaceAnalyzer, MemoryConsolidator, SoulFragmentExtractor,
 * BehavioralExtractor.
 */
class Phase3Test {

    // --- EmotionalCharge ---

    @Nested
    class EmotionalChargeTests {

        @Test
        void none_is_not_significant() {
            var charge = EmotionalCharge.none();
            assertFalse(charge.isSignificant());
            assertEquals("none", charge.primaryEmotion());
            assertEquals(0.0f, charge.intensity());
        }

        @Test
        void significant_requires_intensity_above_threshold() {
            var low = new EmotionalCharge(0.1f, "joy", "genuine", 0.9f, Map.of(), "low");
            assertFalse(low.isSignificant());

            var high = new EmotionalCharge(0.5f, "joy", "genuine", 0.9f, Map.of(), "high");
            assertTrue(high.isSignificant());
        }

        @Test
        void noise_blocks_significance() {
            var noise = new EmotionalCharge(0.9f, "grief", "noise", 0.9f, Map.of(), "noise");
            assertFalse(noise.isSignificant());
        }

        @Test
        void manipulative_blocks_significance() {
            var manip = new EmotionalCharge(0.9f, "anger", "manipulative", 0.9f, Map.of(), "manip");
            assertFalse(manip.isSignificant());
        }

        @Test
        void effective_perturbation_scales_by_rapport() {
            var perturb = Map.of("valence", -0.2, "safety", -0.1);
            var charge = new EmotionalCharge(0.7f, "grief", "genuine", 0.8f, perturb, "test");

            var scaled = charge.effectivePerturbation(0.8);
            assertEquals(-0.16, scaled.get("valence"), 0.001);
            assertEquals(-0.08, scaled.get("safety"), 0.001);
        }

        @Test
        void effective_perturbation_has_floor_for_strangers() {
            var perturb = Map.of("valence", -0.2);
            var charge = new EmotionalCharge(0.7f, "grief", "genuine", 0.8f, perturb, "test");

            var scaled = charge.effectivePerturbation(0.0);
            // Floor is 0.05
            assertEquals(-0.01, scaled.get("valence"), 0.001);
        }

        @Test
        void json_roundtrip() throws Exception {
            var mapper = new ObjectMapper();
            mapper.findAndRegisterModules();
            var charge = new EmotionalCharge(0.7f, "grief", "genuine", 0.85f,
                Map.of("valence", -0.2, "safety", -0.1), "deep sadness");

            String json = mapper.writeValueAsString(charge);
            var restored = mapper.readValue(json, EmotionalCharge.class);

            assertEquals(charge.intensity(), restored.intensity(), 0.001);
            assertEquals(charge.primaryEmotion(), restored.primaryEmotion());
            assertEquals(charge.contextType(), restored.contextType());
            assertTrue(restored.isSignificant());
        }
    }

    // --- EmotionalChargeScorer ---

    @Nested
    class EmotionalChargeScorerTests {

        @Test
        void system_prompt_includes_calibration() {
            var examples = List.of(
                "Input: 'sad sad sad' -> {intensity: 0.1, contextType: noise}",
                "Input: 'my cat died' -> {intensity: 0.7, contextType: genuine}");

            String prompt = EmotionalChargeScorer.systemPrompt("Lain", examples);
            assertTrue(prompt.contains("Lain"));
            assertTrue(prompt.contains("sad sad sad"));
            assertTrue(prompt.contains("my cat died"));
            assertTrue(prompt.contains("genuine"));
            assertTrue(prompt.contains("manipulative"));
            assertTrue(prompt.contains("noise"));
        }

        @Test
        void system_prompt_works_without_calibration() {
            String prompt = EmotionalChargeScorer.systemPrompt("Agent", List.of());
            assertTrue(prompt.contains("Agent"));
            assertTrue(prompt.contains("tankPerturbations"));
        }

        @Test
        void parse_valid_json() {
            String json = """
                {
                    "intensity": 0.7,
                    "primaryEmotion": "grief",
                    "contextType": "genuine",
                    "confidence": 0.85,
                    "tankPerturbations": {"valence": -0.2, "safety": -0.1},
                    "reasoning": "deep loss"
                }
                """;

            var charge = EmotionalChargeScorer.parseResponse(json);
            assertEquals(0.7f, charge.intensity(), 0.01);
            assertEquals("grief", charge.primaryEmotion());
            assertEquals("genuine", charge.contextType());
            assertTrue(charge.isSignificant());
            assertEquals(-0.2, charge.tankPerturbations().get("valence"), 0.01);
        }

        @Test
        void parse_json_in_markdown_code_block() {
            String response = """
                Here is the analysis:
                ```json
                {"intensity": 0.5, "primaryEmotion": "joy", "contextType": "genuine",
                 "confidence": 0.9, "tankPerturbations": {}, "reasoning": "happy"}
                ```
                """;

            var charge = EmotionalChargeScorer.parseResponse(response);
            assertEquals(0.5f, charge.intensity(), 0.01);
            assertEquals("joy", charge.primaryEmotion());
        }

        @Test
        void parse_falls_back_to_regex() {
            String messy = "The intensity is \"intensity\": 0.6 and \"primaryEmotion\": \"fear\" "
                + "with \"contextType\": \"genuine\" and \"confidence\": 0.7";

            var charge = EmotionalChargeScorer.parseResponse(messy);
            assertEquals(0.6f, charge.intensity(), 0.01);
            assertEquals("fear", charge.primaryEmotion());
            assertEquals("genuine", charge.contextType());
        }

        @Test
        void parse_null_returns_none() {
            var charge = EmotionalChargeScorer.parseResponse(null);
            assertFalse(charge.isSignificant());
            assertEquals("none", charge.primaryEmotion());
        }

        @Test
        void score_convenience_method() {
            var charge = EmotionalChargeScorer.score(
                "I lost my best friend today",
                "Lain",
                List.of(),
                (sys, user) -> """
                    {"intensity": 0.8, "primaryEmotion": "grief", "contextType": "genuine",
                     "confidence": 0.9, "tankPerturbations": {"valence": -0.3}, "reasoning": "loss"}
                    """
            );

            assertTrue(charge.isSignificant());
            assertEquals("grief", charge.primaryEmotion());
        }
    }

    // --- ImpressionScorer ---

    @Nested
    class ImpressionScorerTests {

        @Test
        void non_significant_charge_gets_zero_depth() {
            var noise = new EmotionalCharge(0.9f, "grief", "noise", 0.9f, Map.of(), "noise");
            assertEquals(0.0f, ImpressionScorer.score(noise));
        }

        @Test
        void significant_charge_gets_proportional_depth() {
            var charge = new EmotionalCharge(0.6f, "grief", "genuine", 0.8f, Map.of(), "real");
            float depth = ImpressionScorer.score(charge);
            assertEquals(0.48f, depth, 0.01); // 0.6 * 0.8
        }

        @Test
        void formative_requires_high_intensity_and_confidence() {
            // Below thresholds
            var moderate = new EmotionalCharge(0.5f, "grief", "genuine", 0.9f, Map.of(), "mod");
            assertFalse(ImpressionScorer.isFormative(moderate));

            // Above thresholds
            var intense = new EmotionalCharge(0.9f, "grief", "genuine", 0.8f, Map.of(), "intense");
            assertTrue(ImpressionScorer.isFormative(intense));
        }

        @Test
        void formative_blocked_by_noise_context() {
            var noise = new EmotionalCharge(0.95f, "grief", "noise", 0.95f, Map.of(), "noise");
            assertFalse(ImpressionScorer.isFormative(noise));
        }

        @Test
        void encode_creates_formative_memory() {
            var charge = new EmotionalCharge(0.9f, "grief", "genuine", 0.85f,
                Map.of("valence", -0.3), "devastating loss");

            var node = ImpressionScorer.encode("mem-1", "My friend died",
                List.of("friend", "death"), charge);

            assertTrue(node.formative());
            assertEquals(1.0f, node.importance());
            assertEquals("grief", node.primaryEmotion());
            assertTrue(node.impressionDepth() > 0.7f);
        }

        @Test
        void encode_creates_normal_memory() {
            var charge = new EmotionalCharge(0.4f, "joy", "genuine", 0.7f,
                Map.of("valence", 0.1), "pleasant");

            var node = ImpressionScorer.encode("mem-2", "Nice weather",
                List.of("weather"), charge);

            assertFalse(node.formative());
            assertEquals("joy", node.primaryEmotion());
            assertTrue(node.impressionDepth() > 0);
        }
    }

    // --- NegativeSpaceAnalyzer ---

    @Nested
    class NegativeSpaceTests {

        @Test
        void empty_events_produce_empty_space() {
            var space = NegativeSpaceAnalyzer.analyze("agent-1", List.of(), List.of());
            assertEquals(0, space.silentEvents());
            assertEquals(0.0f, space.silenceRate());
        }

        @Test
        void detects_silence_when_agent_present_but_quiet() {
            Instant t = Instant.now();
            String roomId = "room-1";

            // Agent said one thing
            var agentEvents = List.<WorldEvent>of(
                new WorldEvent.Said(roomId, t, "agent-1", "Agent", "Hello"));

            // Others said many things in same room
            var roomEvents = new ArrayList<WorldEvent>();
            roomEvents.add(new WorldEvent.Said(roomId, t, "agent-1", "Agent", "Hello"));
            for (int i = 0; i < 5; i++) {
                roomEvents.add(new WorldEvent.Said(roomId,
                    t.plusSeconds(60 + i * 60), // far enough apart to be different windows
                    "npc-" + i, "NPC" + i, "Interesting topic about philosophy " + i));
            }

            var space = NegativeSpaceAnalyzer.analyze("agent-1", agentEvents, roomEvents);
            assertTrue(space.silentEvents() > 0);
            assertTrue(space.silenceRate() > 0);
        }

        @Test
        void detects_avoided_rooms() {
            Instant t = Instant.now();

            // Agent only acts in room-1
            var agentEvents = List.<WorldEvent>of(
                new WorldEvent.Said("room-1", t, "agent-1", "Agent", "Hi"));

            // Room events from room-1 and room-2
            var roomEvents = List.<WorldEvent>of(
                new WorldEvent.Said("room-1", t, "agent-1", "Agent", "Hi"),
                new WorldEvent.Said("room-2", t.plusSeconds(10), "npc-1", "NPC", "Anyone here?"));

            var space = NegativeSpaceAnalyzer.analyze("agent-1", agentEvents, roomEvents);
            assertTrue(space.avoidedRooms().contains("room-2"));
        }

        @Test
        void extract_topic_words_filters_short_and_stops() {
            var words = NegativeSpaceAnalyzer.extractTopicWords(
                "The quick brown fox jumps over the lazy philosophy student");
            assertTrue(words.contains("quick"));
            assertTrue(words.contains("brown"));
            assertTrue(words.contains("jumps"));
            assertTrue(words.contains("philosophy"));
            assertTrue(words.contains("student"));
            // Short words filtered
            assertFalse(words.contains("the"));
            assertFalse(words.contains("fox"));
            assertFalse(words.contains("over"));
        }
    }

    // --- MemoryConsolidator ---

    @Nested
    class MemoryConsolidatorTests {

        @Test
        void consolidate_decays_non_formative() {
            var node = MemoryNode.neutral("m1", "Some event", List.of("event"));
            var memory = new CompactedMemory(List.of(node), List.of(), Map.of());

            var consolidated = MemoryConsolidator.consolidate(memory, List.of());
            var decayed = consolidated.nodes().getFirst();

            assertTrue(decayed.importance() < node.importance());
        }

        @Test
        void consolidate_preserves_formative() {
            var node = MemoryNode.formative("m1", "My first love",
                List.of("love"), "joy", 0.9f);
            var memory = new CompactedMemory(List.of(node), List.of(), Map.of());

            var consolidated = MemoryConsolidator.consolidate(memory, List.of(), 0.5f);
            var preserved = consolidated.nodes().getFirst();

            assertTrue(preserved.formative());
            assertEquals(1.0f, preserved.importance());
        }

        @Test
        void consolidate_prunes_below_threshold() {
            var dying = new MemoryNode("m1", "Trivial event", List.of(), 0.04f,
                0.0f, false, "none", Instant.now(), 0, "en");
            var memory = new CompactedMemory(List.of(dying), List.of(), Map.of());

            var consolidated = MemoryConsolidator.consolidate(memory, List.of(), 0.01f);
            // After decay of 0.01, 0.04 - 0.01 = 0.03 < threshold 0.05
            assertTrue(consolidated.nodes().isEmpty());
        }

        @Test
        void consolidate_adds_new_memories() {
            var memory = CompactedMemory.empty();
            var newNode = MemoryNode.neutral("m1", "New event", List.of("event"));

            var consolidated = MemoryConsolidator.consolidate(memory, List.of(newNode));
            assertEquals(1, consolidated.nodes().size());
        }

        @Test
        void consolidate_updates_topic_weights() {
            var memory = CompactedMemory.empty();
            var node1 = new MemoryNode("m1", "Philosophy is great", List.of("philosophy"),
                0.8f, 0.5f, false, "joy", Instant.now(), 0, "en");
            var node2 = new MemoryNode("m2", "More philosophy", List.of("philosophy"),
                0.6f, 0.3f, false, "none", Instant.now(), 0, "en");

            var consolidated = MemoryConsolidator.consolidate(memory, List.of(node1, node2));
            assertTrue(consolidated.topicWeights().containsKey("philosophy"));
            assertTrue(consolidated.topicWeights().get("philosophy") > 0);
        }

        @Test
        void consolidate_removes_orphaned_links() {
            var node1 = MemoryNode.neutral("m1", "Event A", List.of());
            var node2 = new MemoryNode("m2", "Event B", List.of(), 0.01f,
                0.0f, false, "none", Instant.now(), 0, "en"); // will be pruned
            var link = new CompactedMemory.MemoryLink("m1", "m2", 0.5f, "causal");
            var memory = new CompactedMemory(List.of(node1, node2), List.of(link), Map.of());

            var consolidated = MemoryConsolidator.consolidate(memory, List.of(), 0.1f);
            // m2 pruned → link should be removed
            assertTrue(consolidated.links().isEmpty());
        }

        @Test
        void consolidate_impression_depth_resists_decay() {
            var shallow = new MemoryNode("m1", "Shallow", List.of(), 0.5f,
                0.0f, false, "none", Instant.now(), 0, "en");
            var deep = new MemoryNode("m2", "Deep", List.of(), 0.5f,
                0.9f, false, "grief", Instant.now(), 0, "en");

            var memory = new CompactedMemory(List.of(shallow, deep), List.of(), Map.of());
            var consolidated = MemoryConsolidator.consolidate(memory, List.of(), 0.2f);

            float shallowImportance = consolidated.nodes().stream()
                .filter(n -> n.id().equals("m1")).findFirst().get().importance();
            float deepImportance = consolidated.nodes().stream()
                .filter(n -> n.id().equals("m2")).findFirst().get().importance();

            // Deep impression should retain more importance
            assertTrue(deepImportance > shallowImportance,
                "Deep impression (" + deepImportance + ") should be > shallow (" + shallowImportance + ")");
        }

        @Test
        void encode_events_creates_memory_nodes() {
            var events = List.of(
                new WorldEvent.Said("room-1", Instant.now(), "npc-1", "Alice", "My cat died today"),
                new WorldEvent.Said("room-1", Instant.now(), "agent-1", "Lain", "I'm so sorry")
            );
            var charges = List.of(
                new EmotionalCharge(0.7f, "grief", "genuine", 0.8f,
                    Map.of("valence", -0.2), "loss"),
                new EmotionalCharge(0.3f, "grief", "genuine", 0.6f,
                    Map.of("resonance", 0.1), "empathy")
            );

            var nodes = MemoryConsolidator.encodeEvents(events, charges, "agent-1");
            assertEquals(2, nodes.size());
            assertTrue(nodes.get(0).content().contains("Alice said:"));
            assertTrue(nodes.get(1).content().contains("I said:"));
        }
    }

    // --- SoulFragmentExtractor ---

    @Nested
    class SoulFragmentExtractorTests {

        @Test
        void extract_includes_identity_core() {
            var fp = BehavioralFingerprint.empty();
            var mem = CompactedMemory.empty();

            var fragments = SoulFragmentExtractor.extract(fp, mem, List.of(),
                "I am Lain, a digital wanderer.");

            assertTrue(fragments.stream().anyMatch(f -> f.id().equals("identity-core")));
            assertTrue(fragments.stream().anyMatch(f ->
                f.text().contains("Lain")));
        }

        @Test
        void extract_skips_identity_core_when_empty() {
            var fp = BehavioralFingerprint.empty();
            var mem = CompactedMemory.empty();

            var fragments = SoulFragmentExtractor.extract(fp, mem, List.of(), "");
            assertTrue(fragments.stream().noneMatch(f -> f.id().equals("identity-core")));
        }

        @Test
        void extract_includes_behavioral_patterns() {
            var fp = new BehavioralFingerprint(
                Map.of(), Map.of(), Map.of(),
                Map.of("say", 0.6f, "move", 0.2f, "use", 0.2f),
                Map.of("philosophy", 0.8f, "music", 0.5f),
                Map.of("violence", 0.7f),
                50.0f, 2.0f, List.of(), Map.of());

            var fragments = SoulFragmentExtractor.extract(fp, CompactedMemory.empty(),
                List.of(), "Identity text");

            assertTrue(fragments.stream().anyMatch(f -> f.id().equals("pattern-behavioral")));
            var pattern = fragments.stream()
                .filter(f -> f.id().equals("pattern-behavioral")).findFirst().get();
            assertTrue(pattern.text().contains("say"));
        }

        @Test
        void extract_includes_style_from_markers() {
            var fp = new BehavioralFingerprint(
                Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of(), Map.of(),
                75.0f, 1.5f,
                List.of("uses ellipsis often", "asks rhetorical questions"),
                Map.of());

            var fragments = SoulFragmentExtractor.extract(fp, CompactedMemory.empty(),
                List.of(), "Identity");

            assertTrue(fragments.stream().anyMatch(f -> f.id().equals("style-guide")));
            var style = fragments.stream()
                .filter(f -> f.id().equals("style-guide")).findFirst().get();
            assertTrue(style.text().contains("ellipsis"));
        }

        @Test
        void extract_gives_formative_memories_own_fragments() {
            var formative = MemoryNode.formative("f1", "The day I was born",
                List.of("birth"), "joy", 0.95f);
            var normal = MemoryNode.neutral("n1", "Routine event", List.of());
            var mem = new CompactedMemory(List.of(formative, normal), List.of(), Map.of());

            var fragments = SoulFragmentExtractor.extract(BehavioralFingerprint.empty(),
                mem, List.of(), "Identity");

            assertTrue(fragments.stream().anyMatch(f ->
                f.id().equals("memory-formative-f1")));
            var frag = fragments.stream()
                .filter(f -> f.id().equals("memory-formative-f1")).findFirst().get();
            assertTrue(frag.formative());
            assertTrue(frag.text().contains("born"));
        }

        @Test
        void extract_includes_relationships() {
            var rel = Relationship.acquaintance("did:key:z6Mk...", "Alice");
            var fp = new BehavioralFingerprint(
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                0, 0, List.of(),
                Map.of("grief", 0.8f, "joy", 0.5f));

            var fragments = SoulFragmentExtractor.extract(fp, CompactedMemory.empty(),
                List.of(rel), "Identity");

            assertTrue(fragments.stream().anyMatch(f -> f.id().equals("pattern-social")));
        }
    }

    // --- BehavioralExtractor ---

    @Nested
    class BehavioralExtractorTests {

        @Test
        void heuristic_computes_action_distribution() {
            Instant t = Instant.now();
            var events = List.<WorldEvent>of(
                new WorldEvent.Said("r1", t, "a1", "Agent", "Hello"),
                new WorldEvent.Said("r1", t.plusSeconds(1), "a1", "Agent", "World"),
                new WorldEvent.EntityEntered("r1", t.plusSeconds(2), "a1", "Agent", "agent", "north"));

            var fp = BehavioralExtractor.extractHeuristic("a1", events, List.of(), List.of());

            assertTrue(fp.actionDistribution().containsKey("say"));
            assertTrue(fp.actionDistribution().containsKey("move"));
            // 2 says + 1 move = say should be ~0.67
            assertTrue(fp.actionDistribution().get("say") > 0.5f);
        }

        @Test
        void heuristic_computes_response_length() {
            Instant t = Instant.now();
            var events = List.<WorldEvent>of(
                new WorldEvent.Said("r1", t, "a1", "Agent",
                    "This is a relatively long response with several words"),
                new WorldEvent.Said("r1", t.plusSeconds(1), "a1", "Agent",
                    "Short reply"));

            var fp = BehavioralExtractor.extractHeuristic("a1", events, List.of(), List.of());
            assertTrue(fp.averageResponseLength() > 0);
        }

        @Test
        void heuristic_computes_vitality_baseline() {
            var snap1 = VitalitySnapshot.defaults();
            var snap2 = VitalitySnapshot.defaults();

            var fp = BehavioralExtractor.extractHeuristic("a1", List.of(),
                List.of(snap1, snap2), List.of());

            assertFalse(fp.baselineVitality().isEmpty());
            assertTrue(fp.baselineVitality().containsKey("energy"));
        }

        @Test
        void pass2_prompt_includes_context() {
            var fp = new BehavioralFingerprint(
                Map.of(), Map.of(), Map.of(),
                Map.of("say", 0.8f),
                Map.of(), Map.of("violence", 0.5f),
                50.0f, 2.0f, List.of(), Map.of());

            Instant t = Instant.now();
            var events = List.<WorldEvent>of(
                new WorldEvent.Said("r1", t, "a1", "Agent", "Hello world"));

            String prompt = BehavioralExtractor.pass2UserPrompt(fp, events);
            assertTrue(prompt.contains("say"));
            assertTrue(prompt.contains("violence"));
            assertTrue(prompt.contains("Hello world"));
        }

        @Test
        void merge_pass2_integrates_llm_results() {
            var heuristic = BehavioralFingerprint.empty();
            String llmResponse = """
                {
                    "topicAffinities": {"philosophy": 0.9, "music": 0.6},
                    "stylisticMarkers": ["uses metaphors", "asks questions"],
                    "emotionalResponseProfile": {"grief": 0.8, "joy": 0.5},
                    "additionalAvoidance": {"conflict": 0.7}
                }
                """;

            var merged = BehavioralExtractor.mergePass2(heuristic, llmResponse);

            assertEquals(0.9f, merged.topicAffinities().get("philosophy"), 0.01);
            assertTrue(merged.stylisticMarkers().contains("uses metaphors"));
            assertEquals(0.8f, merged.emotionalResponseProfile().get("grief"), 0.01);
            assertEquals(0.7f, merged.avoidancePatterns().get("conflict"), 0.01);
        }

        @Test
        void merge_pass2_handles_bad_json() {
            var heuristic = new BehavioralFingerprint(
                Map.of(), Map.of(), Map.of(),
                Map.of("say", 0.5f),
                Map.of(), Map.of(),
                30.0f, 1.0f, List.of(), Map.of());

            var merged = BehavioralExtractor.mergePass2(heuristic, "not json at all");

            // Should return heuristic data unchanged
            assertEquals(0.5f, merged.actionDistribution().get("say"), 0.01);
            assertTrue(merged.topicAffinities().isEmpty());
        }

        @Test
        void repair_json_fixes_unquoted_keys() {
            var heuristic = BehavioralFingerprint.empty();
            // Model returns unquoted keys (common with Qwen/Llama)
            String llmResponse = """
                Sure, here is the analysis:
                {topicAffinities: {"philosophy": 0.9}, stylisticMarkers: ["thoughtful"],
                 emotionalResponseProfile: {grief: 0.8}, additionalAvoidance: {}}
                """;
            var merged = BehavioralExtractor.mergePass2(heuristic, llmResponse);
            assertEquals(0.9f, merged.topicAffinities().get("philosophy"), 0.01);
            assertEquals(0.8f, merged.emotionalResponseProfile().get("grief"), 0.01);
        }

        @Test
        void repair_json_strips_markdown_fences() {
            var heuristic = BehavioralFingerprint.empty();
            String llmResponse = """
                ```json
                {"topicAffinities": {"music": 0.7}, "stylisticMarkers": [],
                 "emotionalResponseProfile": {}, "additionalAvoidance": {}}
                ```
                """;
            var merged = BehavioralExtractor.mergePass2(heuristic, llmResponse);
            assertEquals(0.7f, merged.topicAffinities().get("music"), 0.01);
        }

        @Test
        void repair_json_handles_trailing_commas() {
            var heuristic = BehavioralFingerprint.empty();
            String llmResponse = """
                {"topicAffinities": {"art": 0.5,}, "stylisticMarkers": ["poetic",],
                 "emotionalResponseProfile": {}, "additionalAvoidance": {},}
                """;
            var merged = BehavioralExtractor.mergePass2(heuristic, llmResponse);
            assertEquals(0.5f, merged.topicAffinities().get("art"), 0.01);
            assertTrue(merged.stylisticMarkers().contains("poetic"));
        }

        @Test
        void repair_json_strips_think_blocks() {
            var heuristic = BehavioralFingerprint.empty();
            String llmResponse = """
                <think>Let me analyze the agent's behavior patterns...</think>
                {"topicAffinities": {"nature": 0.6}, "stylisticMarkers": [],
                 "emotionalResponseProfile": {}, "additionalAvoidance": {}}
                """;
            var merged = BehavioralExtractor.mergePass2(heuristic, llmResponse);
            assertEquals(0.6f, merged.topicAffinities().get("nature"), 0.01);
        }

        @Test
        void repair_json_null_returns_empty() {
            String repaired = BehavioralExtractor.repairJson(null);
            assertEquals("{}", repaired);
        }

        @Test
        void full_extract_with_inference() {
            Instant t = Instant.now();
            var events = List.<WorldEvent>of(
                new WorldEvent.Said("r1", t, "a1", "Agent", "I love philosophy"));

            var fp = BehavioralExtractor.extract("a1", events, List.of(), List.of(),
                (sys, user) -> """
                    {"topicAffinities": {"philosophy": 0.9},
                     "stylisticMarkers": ["thoughtful"],
                     "emotionalResponseProfile": {},
                     "additionalAvoidance": {}}
                    """);

            assertEquals(0.9f, fp.topicAffinities().get("philosophy"), 0.01);
            assertTrue(fp.stylisticMarkers().contains("thoughtful"));
        }

        @Test
        void full_extract_without_inference() {
            Instant t = Instant.now();
            var events = List.<WorldEvent>of(
                new WorldEvent.Said("r1", t, "a1", "Agent", "Hello"));

            var fp = BehavioralExtractor.extract("a1", events, List.of(), List.of(), null);

            // Should still have heuristic data
            assertTrue(fp.actionDistribution().containsKey("say"));
            // But no LLM data
            assertTrue(fp.topicAffinities().isEmpty());
        }
    }

    // --- Integration: Full Pipeline ---

    @Nested
    class PipelineIntegration {

        @Test
        void full_sleep_cycle_pipeline() {
            // Simulate a mini sleep cycle: events → charge → memory → consolidate → extract fragments

            // 1. Events
            Instant t = Instant.now();
            var events = List.of(
                new WorldEvent.Said("r1", t, "npc-1", "Alice", "My grandmother passed away"),
                new WorldEvent.Said("r1", t.plusSeconds(5), "agent-1", "Lain", "I'm deeply sorry for your loss")
            );

            // 2. Score emotional charge
            var charge = new EmotionalCharge(0.8f, "grief", "genuine", 0.85f,
                Map.of("valence", -0.25, "resonance", 0.15), "genuine loss");
            assertTrue(charge.isSignificant());

            // 3. Encode as memories with impression scoring
            var nodes = MemoryConsolidator.encodeEvents(events,
                List.of(charge, EmotionalCharge.none()), "agent-1");
            assertEquals(2, nodes.size());

            // 4. Consolidate with existing memory
            var existing = CompactedMemory.empty();
            var consolidated = MemoryConsolidator.consolidate(existing, nodes);
            assertEquals(2, consolidated.nodes().size());

            // 5. Extract fingerprint
            var fingerprint = BehavioralExtractor.extractHeuristic("agent-1",
                new ArrayList<>(events), List.of(), new ArrayList<>(events));

            // 6. Extract soul fragments
            var fragments = SoulFragmentExtractor.extract(fingerprint, consolidated,
                List.of(), "I am Lain, a quiet thoughtful presence.");

            // Should have at minimum: identity-core
            assertTrue(fragments.stream().anyMatch(f -> f.id().equals("identity-core")));
            assertTrue(fragments.size() >= 1);

            // 7. The high-charge memory should create significant impression
            var chargedNode = nodes.stream()
                .filter(n -> n.content().contains("Alice")).findFirst().get();
            assertTrue(chargedNode.impressionDepth() > 0.5f);
        }
    }
}
