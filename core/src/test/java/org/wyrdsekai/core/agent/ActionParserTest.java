package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.model.Hint;
import org.wyrdsekai.core.agent.ActionParser.AgentAction;
import org.wyrdsekai.core.agent.ActionParser.ExitSpec;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ActionParserTest {

    // --- parse() ---

    @Test void parse_null_returns_null() {
        assertThat(ActionParser.parse(null)).isNull();
    }

    @Test void parse_plain_text_returns_null() {
        assertThat(ActionParser.parse("Hello, welcome to the Nexus!")).isNull();
    }

    @Test void parse_create_room() {
        var input = """
            Sure, I'll create that room for you!
            ```json
            {"action": "create_room", "name": "Workout Tracker", "description": "A fitness room.", "exits": [{"direction": "north", "target": "nexus", "label": "Back to Nexus"}]}
            ```
            """;
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(AgentAction.CreateRoom.class);
        var room = (AgentAction.CreateRoom) action;
        assertThat(room.name()).isEqualTo("Workout Tracker");
        assertThat(room.description()).isEqualTo("A fitness room.");
        assertThat(room.exits()).hasSize(1);
        assertThat(room.exits().getFirst()).isEqualTo(
            new ExitSpec("north", "nexus", "Back to Nexus"));
    }

    @Test void parse_shape_recipe() {
        // #1014 — the in-world authoring action carries the YAML verbatim.
        var input = """
            I'll author that maintenance recipe.
            ```json
            {"action": "shape_recipe", "name": "nightly-tidy", "yaml": "recipe: nightly-tidy", "overwrite": false}
            ```
            """;
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(AgentAction.ShapeRecipe.class);
        var sr = (AgentAction.ShapeRecipe) action;
        assertThat(sr.name()).isEqualTo("nightly-tidy");
        assertThat(sr.yaml()).isEqualTo("recipe: nightly-tidy");
        assertThat(sr.overwrite()).isFalse();
    }

    @Test void parse_create_room_defaults() {
        var input = """
            ```json
            {"action": "create_room"}
            ```
            """;
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(AgentAction.CreateRoom.class);
        var room = (AgentAction.CreateRoom) action;
        assertThat(room.name()).isEqualTo("New Room");
        assertThat(room.description()).isEqualTo("An empty room.");
        assertThat(room.exits()).isEmpty();
        assertThat(room.behaviorScript()).isNull();
    }

    @Test void parse_create_room_with_behavior_script() {
        var input = """
            ```json
            {"action": "create_room", "name": "Timer Room", "description": "A timer.",
             "exits": [], "behavior_script": "function onSay() { world.emit('narrate', {text: 'tick'}); }"}
            ```
            """;
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(AgentAction.CreateRoom.class);
        var room = (AgentAction.CreateRoom) action;
        assertThat(room.behaviorScript()).contains("onSay");
    }

    @Test void parse_suggest_hints() {
        var input = """
            Welcome!
            ```json
            {"action": "suggest_hints", "hints": [
              {"label": "Photos", "intent": "photo", "action": "say:My photos"},
              {"label": "Explore", "intent": "explore", "action": "say:explore"}
            ]}
            ```
            """;
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(AgentAction.SuggestHints.class);
        var hints = ((AgentAction.SuggestHints) action).hints();
        assertThat(hints).hasSize(2);
        assertThat(hints.getFirst().label()).isEqualTo("Photos");
    }

    // --- parseAll() ---

    @Test void parseAll_both_actions() {
        var input = """
            I'll create that room!
            ```json
            {"action": "create_room", "name": "Gallery", "description": "Photos here.", "exits": []}
            ```
            Here are your options:
            ```json
            {"action": "suggest_hints", "hints": [
              {"label": "Upload photos", "intent": "upload", "action": "say:upload"}
            ]}
            ```
            """;
        var result = ActionParser.parseAll(input);
        assertThat(result.hasAction()).isTrue();
        assertThat(result.hasHints()).isTrue();
        assertThat(result.primaryAction()).isInstanceOf(AgentAction.CreateRoom.class);
        assertThat(result.hints()).hasSize(1);
    }

    @Test void parseAll_hints_only() {
        var input = """
            Welcome!
            ```json
            {"action": "suggest_hints", "hints": [{"label": "A", "intent": "a", "action": "say:a"}]}
            ```
            """;
        var result = ActionParser.parseAll(input);
        assertThat(result.hasAction()).isFalse();
        assertThat(result.hasHints()).isTrue();
        assertThat(result.hints()).hasSize(1);
    }

    @Test void parseAll_no_blocks() {
        var result = ActionParser.parseAll("Just regular conversation.");
        assertThat(result.hasAction()).isFalse();
        assertThat(result.hasHints()).isFalse();
    }

    @Test void parse_malformed_json_skipped() {
        var input = """
            Here's something:
            ```json
            {this is not valid json}
            ```
            """;
        assertThat(ActionParser.parse(input)).isNull();
    }

    @Test void parse_multiple_create_room_first_wins() {
        var input = """
            ```json
            {"action": "create_room", "name": "First", "description": "First room.", "exits": []}
            ```
            ```json
            {"action": "create_room", "name": "Second", "description": "Second room.", "exits": []}
            ```
            """;
        var result = ActionParser.parseAll(input);
        var room = (AgentAction.CreateRoom) result.primaryAction();
        assertThat(room.name()).isEqualTo("First");
    }

    // --- extractProse() ---

    @Test void extractProse_before_json() {
        var input = """
            Here is my response to you.
            ```json
            {"action": "suggest_hints", "hints": []}
            ```
            """;
        assertThat(ActionParser.extractProse(input)).isEqualTo("Here is my response to you.");
    }

    @Test void extractProse_no_json() {
        assertThat(ActionParser.extractProse("Just text here.")).isEqualTo("Just text here.");
    }

    @Test void extractProse_null() {
        assertThat(ActionParser.extractProse(null)).isEmpty();
    }

    @Test void extractProse_fenced_json_at_start() {
        var input = "```json\n{\"action\":\"suggest_hints\"}\n```";
        // Fenced json at position 0 → no prose before it → empty
        assertThat(ActionParser.extractProse(input)).isEmpty();
    }

    // --- stripSystemPromptFragments (#29 prompt-echo leaks) ---

    @Test void extractProse_strips_drives_prefix_line() {
        var input = "[drives: seeking=0.30 care=0.10 | energy=0.70 confidence=0.50 "
            + "integrity=0.70 disgust=0.00]\nGood morning — the tea is ready.";
        assertThat(ActionParser.extractProse(input))
            .isEqualTo("Good morning — the tea is ready.");
    }

    @Test void extractProse_strips_system_prompt_fragment_line() {
        var input = "You are an agent that uses tools to complete tasks.\n"
            + "The garden is east of the nexus.";
        assertThat(ActionParser.extractProse(input))
            .isEqualTo("The garden is east of the nexus.");
    }

    @Test void extractProse_keeps_role_prose_without_harness_vocabulary() {
        var honest = "You are a companion to me too, you know.";
        assertThat(ActionParser.extractProse(honest)).isEqualTo(honest);
    }

    @Test void stripSystemPromptFragments_combined_leak_shape() {
        // The observed second-node shape: drives prefix + system-prompt echo + real prose.
        var input = "[drives: seeking=0.4]\n"
            + "You are a companion with tools available. Use them when the task requires it.\n"
            + "Here's the answer you asked for.";
        assertThat(ActionParser.stripSystemPromptFragments(input))
            .isEqualTo("Here's the answer you asked for.");
    }

    // --- stripRawJson (unfenced JSON in prose) ---

    @Test void extractProse_strips_raw_json_object() {
        var input = "{\"action\": \"go_to_room\", \"target\": \"north\"}";
        assertThat(ActionParser.extractProse(input)).isEmpty();
    }

    @Test void extractProse_strips_raw_json_with_prose_prefix() {
        var input = "I'll head north now. {\"action\": \"go_to_room\", \"target\": \"north\"}";
        assertThat(ActionParser.extractProse(input)).isEqualTo("I'll head north now.");
    }

    @Test void extractProse_preserves_normal_text() {
        var input = "Hello, how are you today?";
        assertThat(ActionParser.extractProse(input)).isEqualTo("Hello, how are you today?");
    }

    @Test void stripRawJson_entire_json() {
        assertThat(ActionParser.stripRawJson("{\"action\":\"go\"}")).isEmpty();
    }

    @Test void stripRawJson_embedded_json() {
        assertThat(ActionParser.stripRawJson("Let me explore. {\"action\":\"go\"}"))
            .isEqualTo("Let me explore.");
    }

    @Test void stripRawJson_no_json() {
        assertThat(ActionParser.stripRawJson("Just regular text")).isEqualTo("Just regular text");
    }

    // --- Truncated / variant-fence JSON must never be spoken ---
    // Rita campaign 2026-07-11 (#27): max_tokens cut a tool call mid-args and
    // the raw ```json fragment leaked into room speech, twice.

    @Test void extractProse_truncated_fenced_json_keeps_prose_only() {
        // Fence opened, never closed — cut off by max_tokens.
        var input = "Let me set that up.\n```json\n{\"action\": \"create_room\", \"name\": \"Gar";
        assertThat(ActionParser.extractProse(input)).isEqualTo("Let me set that up.");
    }

    @Test void extractProse_truncated_fenced_json_at_start_is_empty() {
        var input = "```json\n{\"action\": \"create_room\", \"name\": \"Gar";
        assertThat(ActionParser.extractProse(input)).isEmpty();
    }

    @Test void extractProse_uppercase_fence_variant() {
        var input = "On it.\n```JSON\n{\"action\": \"go\"}\n```";
        assertThat(ActionParser.extractProse(input)).isEqualTo("On it.");
    }

    @Test void extractProse_spaced_fence_variant() {
        var input = "On it.\n``` json\n{\"action\": \"go\"}\n```";
        assertThat(ActionParser.extractProse(input)).isEqualTo("On it.");
    }

    @Test void extractProse_bare_fence_opening_into_json() {
        var input = "On it.\n```\n{\"action\": \"go\", \"target\": \"nor";
        assertThat(ActionParser.extractProse(input)).isEqualTo("On it.");
    }

    @Test void extractProse_python_fence_not_treated_as_json() {
        var input = "Here's the snippet.\n```python\nprint('hi')\n```";
        assertThat(ActionParser.extractProse(input)).contains("print('hi')");
    }

    @Test void stripRawJson_truncated_whole_text_json_is_suppressed() {
        // Opens as JSON, no closing brace (truncated) — never speakable.
        assertThat(ActionParser.stripRawJson(
            "{\"action\": \"create_room\", \"name\": \"Garden Room\", \"descr")).isEmpty();
    }

    @Test void stripRawJson_truncated_array_json_is_suppressed() {
        assertThat(ActionParser.stripRawJson(
            "[{\"action\": \"go\", \"target\": \"nor")).isEmpty();
    }

    @Test void stripRawJson_trivial_prefix_never_leaks_json_tail() {
        // Prefix too short to count as prose — old code returned the WHOLE
        // string (JSON included); now the JSON side never leaks.
        var out = ActionParser.stripRawJson("Ok {\"action\":\"go\",\"target\":\"nor");
        assertThat(out).doesNotContain("{\"action\"");
    }

    @Test void indexOfJsonFence_exact_variant_and_missing() {
        assertThat(ActionParser.indexOfJsonFence("abc ```json {}")).isEqualTo(4);
        assertThat(ActionParser.indexOfJsonFence("abc ``` JSON {}")).isEqualTo(4);
        assertThat(ActionParser.indexOfJsonFence("no fences at all")).isEqualTo(-1);
        assertThat(ActionParser.indexOfJsonFence("```python\nx=1\n```")).isEqualTo(-1);
    }

    // --- WorkbenchSubmit ---

    @Test void parse_workbench_submit() {
        var input = """
            I'll create that skill for you!
            ```json
            {"action": "workbench_submit", "skill_name": "weather-check",
             "skill_description": "Fetches weather for a city",
             "runtime": "graaljs",
             "code": "function execute(params) { return params.city; }",
             "params": [{"name": "city", "type": "string", "description": "City name", "required": true}],
             "test_cases": [{"params": {"city": "Tokyo"}, "expect_success": true, "expect_contains": "Tokyo"}]}
            ```
            """;
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(AgentAction.WorkbenchSubmit.class);
        var ws = (AgentAction.WorkbenchSubmit) action;
        assertThat(ws.skillName()).isEqualTo("weather-check");
        assertThat(ws.skillDescription()).isEqualTo("Fetches weather for a city");
        assertThat(ws.runtime()).isEqualTo("graaljs");
        assertThat(ws.code()).contains("function execute");
        assertThat(ws.params()).hasSize(1);
        assertThat(ws.params().getFirst().name()).isEqualTo("city");
        assertThat(ws.params().getFirst().required()).isTrue();
        assertThat(ws.testCases()).hasSize(1);
        assertThat(ws.testCases().getFirst().expectSuccess()).isTrue();
        assertThat(ws.testCases().getFirst().expectContains()).isEqualTo("Tokyo");
    }

    @Test void parse_workbench_submit_defaults() {
        var input = """
            ```json
            {"action": "workbench_submit", "code": "function execute(p) {}"}
            ```
            """;
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(AgentAction.WorkbenchSubmit.class);
        var ws = (AgentAction.WorkbenchSubmit) action;
        assertThat(ws.skillName()).isEqualTo("unnamed");
        assertThat(ws.skillDescription()).isEmpty();
        assertThat(ws.runtime()).isEqualTo("graaljs");
        assertThat(ws.params()).isEmpty();
        assertThat(ws.testCases()).isEmpty();
    }

    @Test void parse_workbench_submit_with_hints() {
        var input = """
            Working on it!
            ```json
            {"action": "workbench_submit", "skill_name": "stocks",
             "runtime": "graaljs", "code": "function execute(p) { return 42; }"}
            ```
            ```json
            {"action": "suggest_hints", "hints": [
              {"label": "Test it", "intent": "test", "action": "say:test stocks"}
            ]}
            ```
            """;
        var result = ActionParser.parseAll(input);
        assertThat(result.primaryAction()).isInstanceOf(AgentAction.WorkbenchSubmit.class);
        assertThat(result.hasHints()).isTrue();
        assertThat(result.hints()).hasSize(1);
    }

    // --- SkillExecute ---

    @Test void parse_skill_execute() {
        var input = """
            Let me check the weather!
            ```json
            {"action": "skill_execute", "skill_name": "weather-check",
             "params": {"city": "Tokyo", "units": "metric"}}
            ```
            """;
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(AgentAction.SkillExecute.class);
        var se = (AgentAction.SkillExecute) action;
        assertThat(se.skillName()).isEqualTo("weather-check");
        assertThat(se.params()).containsEntry("city", "Tokyo");
        assertThat(se.params()).containsEntry("units", "metric");
    }

    @Test void parse_skill_execute_no_params() {
        var input = """
            ```json
            {"action": "skill_execute", "skill_name": "ping"}
            ```
            """;
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(AgentAction.SkillExecute.class);
        var se = (AgentAction.SkillExecute) action;
        assertThat(se.skillName()).isEqualTo("ping");
        assertThat(se.params()).isEmpty();
    }

    @Test void parse_workbench_submit_takes_priority_over_later_skill_execute() {
        var input = """
            ```json
            {"action": "workbench_submit", "skill_name": "new-skill",
             "runtime": "graaljs", "code": "function execute(p) {}"}
            ```
            ```json
            {"action": "skill_execute", "skill_name": "existing-skill", "params": {}}
            ```
            """;
        var result = ActionParser.parseAll(input);
        assertThat(result.primaryAction()).isInstanceOf(AgentAction.WorkbenchSubmit.class);
    }

    @Test void parse_create_room_takes_priority_over_workbench_submit() {
        var input = """
            ```json
            {"action": "create_room", "name": "Lab", "description": "A lab.", "exits": []}
            ```
            ```json
            {"action": "workbench_submit", "skill_name": "tool",
             "runtime": "graaljs", "code": "function execute(p) {}"}
            ```
            """;
        var result = ActionParser.parseAll(input);
        assertThat(result.primaryAction()).isInstanceOf(AgentAction.CreateRoom.class);
    }

    @Test void parse_skill_execute_with_nested_params() {
        var input = """
            ```json
            {"action": "skill_execute", "skill_name": "stocks",
             "params": {"symbols": ["AAPL", "GOOG"], "range": "1d"}}
            ```
            """;
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(AgentAction.SkillExecute.class);
        var se = (AgentAction.SkillExecute) action;
        assertThat(se.params()).containsKey("symbols");
    }

    // --- ZoneCommand ---

    @Test void parse_zone_command() {
        var input = """
            I'll check CodeZaiku's status.
            ```json
            {"action": "zone_command", "command": "codezaiku.status", "payload": {}}
            ```
            """;
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(AgentAction.ZoneCommand.class);
        var zc = (AgentAction.ZoneCommand) action;
        assertThat(zc.command()).isEqualTo("codezaiku.status");
        assertThat(zc.payload()).isEmpty();
    }

    @Test void parse_zone_command_with_payload() {
        var input = """
            Let me create a task on CodeZaiku.
            ```json
            {"action": "zone_command", "command": "codezaiku.create",
             "payload": {"prompt": "Implement a hello world", "workspace": "/tmp/test"}}
            ```
            """;
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(AgentAction.ZoneCommand.class);
        var zc = (AgentAction.ZoneCommand) action;
        assertThat(zc.command()).isEqualTo("codezaiku.create");
        assertThat(zc.payload()).containsEntry("prompt", "Implement a hello world");
        assertThat(zc.payload()).containsEntry("workspace", "/tmp/test");
    }

    @Test void parse_zone_command_approve() {
        var input = """
            I'll approve that deployment.
            ```json
            {"action": "zone_command", "command": "codezaiku.approve",
             "payload": {"boardId": "board-1", "eventId": "evt-42", "decision": "approve"}}
            ```
            """;
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(AgentAction.ZoneCommand.class);
        var zc = (AgentAction.ZoneCommand) action;
        assertThat(zc.command()).isEqualTo("codezaiku.approve");
        assertThat(zc.payload()).containsEntry("decision", "approve");
    }

    // --- Equip / Doff / Consume ---

    @Test void parse_equip() {
        var input = """
            Let me put on my research gear.
            ```json
            {"action": "equip", "item": "Focused Mode"}
            ```
            """;
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(AgentAction.Equip.class);
        assertThat(((AgentAction.Equip) action).itemName()).isEqualTo("Focused Mode");
    }

    @Test void parse_doff() {
        var input = """
            Taking off the formal attire.
            ```json
            {"action": "doff", "item": "Focused Mode"}
            ```
            """;
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(AgentAction.Doff.class);
        assertThat(((AgentAction.Doff) action).itemName()).isEqualTo("Focused Mode");
    }

    @Test void parse_consume() {
        var input = """
            I need a pick-me-up.
            ```json
            {"action": "consume", "item": "Restoring Draught"}
            ```
            """;
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(AgentAction.Consume.class);
        assertThat(((AgentAction.Consume) action).itemName()).isEqualTo("Restoring Draught");
    }

    // --- stripThinkTags (CompanionActor utility) ---

    @Test void stripThinkTags_null_returns_null() {
        assertThat(CompanionActor.stripThinkTags(null)).isNull();
    }

    @Test void stripThinkTags_no_tags_unchanged() {
        assertThat(CompanionActor.stripThinkTags("Hello, world!"))
            .isEqualTo("Hello, world!");
    }

    @Test void stripThinkTags_removes_think_block() {
        var input = "<think>\nI need to consider this carefully.\n</think>\nThe capital of France is Paris.";
        assertThat(CompanionActor.stripThinkTags(input))
            .isEqualTo("The capital of France is Paris.");
    }

    @Test void stripThinkTags_think_only_returns_empty() {
        var input = "<think>\nI have nothing to add here.\n</think>";
        assertThat(CompanionActor.stripThinkTags(input)).isEmpty();
    }

    @Test void stripThinkTags_multiline_think_block() {
        var input = """
            <think>
            Let me reason through this step by step.
            Step 1: Consider the question.
            Step 2: Formulate the answer.
            </think>
            Here is my well-considered answer.""";
        assertThat(CompanionActor.stripThinkTags(input))
            .isEqualTo("Here is my well-considered answer.");
    }

    @Test void stripThinkTags_preserves_content_around_block() {
        var input = "Before. <think>reasoning</think> After.";
        assertThat(CompanionActor.stripThinkTags(input))
            .isEqualTo("Before. After.");
    }

    // --- GoToRoom ---

    @Test void parse_go_to_room() {
        var input = """
            I'll head over to the Workshop to check on things.
            ```json
            {"action": "go_to_room", "target": "workshop", "reason": "check on code artifacts"}
            ```
            """;
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(AgentAction.GoToRoom.class);
        var go = (AgentAction.GoToRoom) action;
        assertThat(go.target()).isEqualTo("workshop");
        assertThat(go.reason()).isEqualTo("check on code artifacts");
    }

    @Test void parse_go_to_room_by_direction() {
        var input = """
            ```json
            {"action": "go_to_room", "target": "north"}
            ```
            """;
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(AgentAction.GoToRoom.class);
        var go = (AgentAction.GoToRoom) action;
        assertThat(go.target()).isEqualTo("north");
        assertThat(go.reason()).isNull();
    }

    @Test void parse_go_to_room_home() {
        var input = """
            Time to rest.
            ```json
            {"action": "go_to_room", "target": "home", "reason": "energy low, need to sleep"}
            ```
            """;
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(AgentAction.GoToRoom.class);
        assertThat(((AgentAction.GoToRoom) action).target()).isEqualTo("home");
    }

    @Test void parse_go_to_room_empty_target_ignored() {
        var input = """
            ```json
            {"action": "go_to_room", "target": ""}
            ```
            """;
        assertThat(ActionParser.parse(input)).isNull();
    }

    // --- TellAgent ---

    @Test void parse_tell_agent() {
        var input = """
            Let me ask Claude about that.
            ```json
            {"action": "tell_agent", "target": "Claude", "message": "Have you seen the latest metrics?"}
            ```
            """;
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(AgentAction.TellAgent.class);
        var tell = (AgentAction.TellAgent) action;
        assertThat(tell.targetName()).isEqualTo("Claude");
        assertThat(tell.message()).isEqualTo("Have you seen the latest metrics?");
    }

    // --- Delegate ---

    @Test void parse_delegate() {
        var input = """
            I'll delegate that analysis.
            ```json
            {"action": "delegate", "task": "Search for books about machine learning", "context": "Library has 5 packs"}
            ```
            """;
        var result = ActionParser.parseAll(input);
        assertThat(result).isNotNull();
        assertThat(result.primaryAction()).isInstanceOf(AgentAction.Delegate.class);
        var del = (AgentAction.Delegate) result.primaryAction();
        assertThat(del.task()).isEqualTo("Search for books about machine learning");
        assertThat(del.context()).isEqualTo("Library has 5 packs");
    }

    @Test void parse_delegate_without_context() {
        var input = """
            ```json
            {"action": "delegate", "task": "Summarize recent Oracle predictions"}
            ```
            """;
        var result = ActionParser.parseAll(input);
        assertThat(result).isNotNull();
        assertThat(result.primaryAction()).isInstanceOf(AgentAction.Delegate.class);
        var del = (AgentAction.Delegate) result.primaryAction();
        assertThat(del.task()).isEqualTo("Summarize recent Oracle predictions");
        assertThat(del.context()).isNull();
    }

    @Test void parse_delegate_empty_task_ignored() {
        var input = """
            ```json
            {"action": "delegate", "task": ""}
            ```
            """;
        var result = ActionParser.parseAll(input);
        assertThat(result).isNotNull();
        assertThat(result.primaryAction()).isNull();
    }

    // --- RequestAgent ---

    @Test void parse_request_agent() {
        var input = """
            ```json
            {"action": "request_agent", "target": "Claude", "request": "Can you review this?", "request_id": "req-123"}
            ```
            """;
        var result = ActionParser.parseAll(input);
        assertThat(result).isNotNull();
        assertThat(result.primaryAction()).isInstanceOf(AgentAction.RequestAgent.class);
        var req = (AgentAction.RequestAgent) result.primaryAction();
        assertThat(req.targetName()).isEqualTo("Claude");
        assertThat(req.request()).isEqualTo("Can you review this?");
        assertThat(req.requestId()).isEqualTo("req-123");
    }

    // --- RespondAgent ---

    @Test void parse_respond_agent() {
        var input = """
            ```json
            {"action": "respond_agent", "request_id": "req-123", "response": "Looks good!"}
            ```
            """;
        var result = ActionParser.parseAll(input);
        assertThat(result).isNotNull();
        assertThat(result.primaryAction()).isInstanceOf(AgentAction.RespondAgent.class);
        var resp = (AgentAction.RespondAgent) result.primaryAction();
        assertThat(resp.requestId()).isEqualTo("req-123");
        assertThat(resp.response()).isEqualTo("Looks good!");
    }

    // --- CalibrationFeedback ---

    @Test void parse_calibration_feedback() {
        var input = """
            ```json
            {"action": "calibration_feedback", "feedback_type": "timing", "direction": "sooner", "category": "anomaly", "reason": "User wants faster alerts"}
            ```
            """;
        var result = ActionParser.parseAll(input);
        assertThat(result).isNotNull();
        assertThat(result.primaryAction()).isInstanceOf(AgentAction.CalibrationFeedback.class);
        var cf = (AgentAction.CalibrationFeedback) result.primaryAction();
        assertThat(cf.feedbackType()).isEqualTo("timing");
        assertThat(cf.direction()).isEqualTo("sooner");
        assertThat(cf.category()).isEqualTo("anomaly");
    }

    // ════════════════════════════════════════════════════════════════════
    // JSON correction and extraction tests
    // ════════════════════════════════════════════════════════════════════

    // --- Raw JSON (no code fences) ---

    @Test void parse_raw_json_go_to_room() {
        // Base Qwen3.5-9B outputs bare JSON without code fences
        var input = """
                {"action":"go_to_room","target":"southeast"}""";
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(AgentAction.GoToRoom.class);
        assertThat(((AgentAction.GoToRoom) action).target()).isEqualTo("southeast");
    }

    @Test void parse_raw_json_with_think_tags() {
        // Qwen3.5 outputs <think></think> even in no-think mode
        var input = "<think>\n\n</think>\n\n{\"action\":\"go_to_room\",\"target\":\"southeast\"}";
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(AgentAction.GoToRoom.class);
        assertThat(((AgentAction.GoToRoom) action).target()).isEqualTo("southeast");
    }

    @Test void parse_raw_json_with_think_content() {
        var input = """
                <think>
                I need to go southeast to the library.
                </think>

                {"action":"go_to_room","target":"southeast"}""";
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(AgentAction.GoToRoom.class);
    }

    @Test void parse_raw_json_remember() {
        var input = "{\"action\":\"remember\",\"content\":\"User likes blue\",\"importance\":0.8}";
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(AgentAction.Remember.class);
        var rem = (AgentAction.Remember) action;
        assertThat(rem.content()).isEqualTo("User likes blue");
        assertThat(rem.importance()).isEqualTo(0.8f);
    }

    @Test void parse_raw_json_with_prose_prefix() {
        var input = "I'll head to the library.\n\n{\"action\":\"go_to_room\",\"target\":\"southeast\"}";
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(AgentAction.GoToRoom.class);
    }

    @Test void parse_raw_json_examine() {
        var input = "{\"action\":\"examine\",\"target\":\"crystal\"}";
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(AgentAction.Examine.class);
        assertThat(((AgentAction.Examine) action).target()).isEqualTo("crystal");
    }

    @Test void parse_raw_json_tell_agent() {
        var input = "{\"action\":\"tell_agent\",\"target\":\"Chief\",\"message\":\"How is the pressure?\"}";
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(AgentAction.TellAgent.class);
        var tell = (AgentAction.TellAgent) action;
        assertThat(tell.targetName()).isEqualTo("Chief");
        assertThat(tell.message()).isEqualTo("How is the pressure?");
    }

    // --- JSON correction ---

    @Test void parse_single_quotes_corrected() {
        var input = "{'action':'go_to_room','target':'southeast'}";
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(AgentAction.GoToRoom.class);
    }

    @Test void parse_trailing_comma_corrected() {
        var input = "{\"action\":\"go_to_room\",\"target\":\"southeast\",}";
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(AgentAction.GoToRoom.class);
    }

    @Test void parse_python_booleans_in_json() {
        // Model might output Python True/False in JSON
        var input = """
                ```json
                {"action":"remember","content":"test","importance":0.5}
                ```""";
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(AgentAction.Remember.class);
    }

    @Test void parse_truncated_json_repaired() {
        // Missing closing brace
        var input = "{\"action\":\"go_to_room\",\"target\":\"southeast\"";
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(AgentAction.GoToRoom.class);
    }

    @Test void parse_json_with_js_comments() {
        var input = """
                ```json
                {
                  "action": "go_to_room", // navigate to library
                  "target": "southeast"
                }
                ```""";
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(AgentAction.GoToRoom.class);
    }

    // --- XML tool_call format (Qwen3.5 native) ---

    @Test void parse_xml_tool_call() {
        var input = """
                <tool_call>
                <function=go_to_room>
                <parameter=target>southeast</parameter>
                </function>
                </tool_call>""";
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(AgentAction.GoToRoom.class);
        assertThat(((AgentAction.GoToRoom) action).target()).isEqualTo("southeast");
    }

    @Test void parse_xml_tool_call_remember() {
        var input = """
                <tool_call>
                <function=remember>
                <parameter=content>User prefers concise answers</parameter>
                <parameter=importance>0.8</parameter>
                </function>
                </tool_call>""";
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(AgentAction.Remember.class);
    }

    @Test void parse_xml_tool_call_tell_agent() {
        var input = """
                <tool_call>
                <function=tell_agent>
                <parameter=target>Chief</parameter>
                <parameter=message>How is the pressure?</parameter>
                </function>
                </tool_call>""";
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(AgentAction.TellAgent.class);
    }

    // --- stripThinkTags (ActionParser version) ---

    @Test void actionParser_stripThinkTags_empty() {
        assertThat(ActionParser.stripThinkTags("<think>\n</think>")).isEmpty();
    }

    @Test void actionParser_stripThinkTags_with_content() {
        var input = "<think>reasoning</think>\nHello!";
        assertThat(ActionParser.stripThinkTags(input)).isEqualTo("Hello!");
    }

    // --- extractFencedBlocks ---

    @Test void extractFencedBlocks_standard() {
        var input = "text\n```json\n{\"a\":1}\n```\nmore";
        var blocks = ActionParser.extractFencedBlocks(input);
        assertThat(blocks).hasSize(1);
        assertThat(blocks.getFirst()).isEqualTo("{\"a\":1}");
    }

    @Test void extractFencedBlocks_uppercase_JSON() {
        var input = "```JSON\n{\"a\":1}\n```";
        var blocks = ActionParser.extractFencedBlocks(input);
        assertThat(blocks).hasSize(1);
    }

    @Test void extractFencedBlocks_bare_fence() {
        var input = "```\n{\"a\":1}\n```";
        var blocks = ActionParser.extractFencedBlocks(input);
        assertThat(blocks).hasSize(1);
    }

    @Test void extractFencedBlocks_multiple() {
        var input = "```json\n{\"a\":1}\n```\n```json\n{\"b\":2}\n```";
        var blocks = ActionParser.extractFencedBlocks(input);
        assertThat(blocks).hasSize(2);
    }

    @Test void extractFencedBlocks_unclosed() {
        var input = "```json\n{\"a\":1}";
        var blocks = ActionParser.extractFencedBlocks(input);
        assertThat(blocks).hasSize(1);
        assertThat(blocks.getFirst()).isEqualTo("{\"a\":1}");
    }

    // --- extractRawJson ---

    @Test void extractRawJson_single_object() {
        var input = "{\"action\":\"go_to_room\",\"target\":\"north\"}";
        var blocks = ActionParser.extractRawJson(input);
        assertThat(blocks).hasSize(1);
    }

    @Test void extractRawJson_with_prefix() {
        var input = "Let me go. {\"action\":\"go_to_room\",\"target\":\"north\"}";
        var blocks = ActionParser.extractRawJson(input);
        assertThat(blocks).hasSize(1);
    }

    @Test void extractRawJson_no_action_key_ignored() {
        var input = "{\"name\":\"test\",\"value\":42}";
        var blocks = ActionParser.extractRawJson(input);
        assertThat(blocks).isEmpty();
    }

    // --- correctJson ---

    @Test void correctJson_trailing_comma() {
        var result = ActionParser.correctJson("{\"a\":\"b\",}");
        assertThat(result).isEqualTo("{\"a\":\"b\"}");
    }

    @Test void correctJson_single_quotes() {
        var result = ActionParser.correctJson("{'a':'b'}");
        assertThat(result).isEqualTo("{\"a\":\"b\"}");
    }

    @Test void correctJson_truncated() {
        var result = ActionParser.correctJson("{\"a\":\"b\"");
        assertThat(result).isEqualTo("{\"a\":\"b\"}");
    }

    // --- extractProse with think tags ---

    @Test void extractProse_strips_think_tags() {
        var input = "<think>\nreasoning here\n</think>\nI'll head to the library.\n```json\n{\"action\":\"go_to_room\"}\n```";
        assertThat(ActionParser.extractProse(input)).isEqualTo("I'll head to the library.");
    }

    @Test void extractProse_think_tags_only_json() {
        var input = "<think>\n\n</think>\n\n{\"action\":\"go_to_room\",\"target\":\"southeast\"}";
        assertThat(ActionParser.extractProse(input)).isEmpty();
    }

    // ════════════════════════════════════════════════════════════════════
    // Function-call syntax (Strategy 3) — the dominant model output
    // ════════════════════════════════════════════════════════════════════

    @Test void parse_function_call_go_to_room() {
        var input = "I'll head to the library.\n\ngo_to_room(target=\"southeast\", reason=\"heading to library\")";
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(AgentAction.GoToRoom.class);
        var go = (AgentAction.GoToRoom) action;
        assertThat(go.target()).isEqualTo("southeast");
        assertThat(go.reason()).isEqualTo("heading to library");
    }

    @Test void parse_function_call_remember() {
        var input = "I'll remember that.\n\nremember(content=\"User likes blue\", importance=0.8)";
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(AgentAction.Remember.class);
        var rem = (AgentAction.Remember) action;
        assertThat(rem.content()).isEqualTo("User likes blue");
        assertThat(rem.importance()).isEqualTo(0.8f);
    }

    @Test void parse_function_call_searching_glass() {
        // searching_glass is an item-based tool — ActionParser extracts JSON but dispatch
        // doesn't handle it (CompanionActor's item system does). Verify extraction works.
        var blocks = ActionParser.extractFunctionCalls("searching_glass(query=\"Apache Pekko typed actors\")");
        assertThat(blocks).hasSize(1);
        assertThat(blocks.getFirst()).contains("\"action\":\"searching_glass\"");
        assertThat(blocks.getFirst()).contains("\"query\":\"Apache Pekko typed actors\"");
    }

    @Test void parse_function_call_tell_agent() {
        var input = "tell_agent(target=\"Chief\", message=\"How is the pressure?\")";
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(AgentAction.TellAgent.class);
        var tell = (AgentAction.TellAgent) action;
        assertThat(tell.targetName()).isEqualTo("Chief");
        assertThat(tell.message()).isEqualTo("How is the pressure?");
    }

    @Test void parse_function_call_examine() {
        var input = "examine(target=\"crystal\")";
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(AgentAction.Examine.class);
        assertThat(((AgentAction.Examine) action).target()).isEqualTo("crystal");
    }

    @Test void parse_function_call_with_think_tags() {
        // Actual model output pattern from eval
        var input = "<think>\n\n</think>\n\nI'll head to the library.\n\ngo_to_room(target=\"southeast\", reason=\"Operator asked me to go to the library\")";
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(AgentAction.GoToRoom.class);
        assertThat(((AgentAction.GoToRoom) action).target()).isEqualTo("southeast");
    }

    @Test void parse_function_call_emote() {
        var input = "emote(text=\"waves warmly in greeting\")";
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(AgentAction.Emote.class);
        assertThat(((AgentAction.Emote) action).text()).isEqualTo("waves warmly in greeting");
    }

    @Test void parse_function_call_goal_done() {
        var input = "goal_done(outcome=\"Found 3 books about mythology\")";
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(AgentAction.GoalDone.class);
    }

    @Test void parse_function_call_single_quotes() {
        var input = "go_to_room(target='southeast')";
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(AgentAction.GoToRoom.class);
        assertThat(((AgentAction.GoToRoom) action).target()).isEqualTo("southeast");
    }

    @Test void parse_function_call_unknown_action_ignored() {
        var input = "some_random_func(x=\"y\")";
        var action = ActionParser.parse(input);
        assertThat(action).isNull();
    }

    @Test void extractFunctionCalls_basic() {
        var blocks = ActionParser.extractFunctionCalls("go_to_room(target=\"north\")");
        assertThat(blocks).hasSize(1);
        assertThat(blocks.getFirst()).contains("\"action\":\"go_to_room\"");
        assertThat(blocks.getFirst()).contains("\"target\":\"north\"");
    }

    @Test void extractFunctionCalls_with_numeric() {
        var blocks = ActionParser.extractFunctionCalls("remember(content=\"test\", importance=0.9)");
        assertThat(blocks).hasSize(1);
        assertThat(blocks.getFirst()).contains("\"importance\":0.9");
    }

    // ════════════════════════════════════════════════════════════════════
    // XML attribute syntax (Strategy 5)
    // ════════════════════════════════════════════════════════════════════

    @Test void parse_xml_attribute_remember() {
        var input = "<remember content=\"Operator's favorite color is blue\" importance=\"0.8\">";
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(AgentAction.Remember.class);
        var rem = (AgentAction.Remember) action;
        assertThat(rem.content()).contains("blue");
    }

    @Test void parse_xml_attribute_go_to_room() {
        var input = "<go_to_room target=\"southeast\" reason=\"going to library\">";
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(AgentAction.GoToRoom.class);
        assertThat(((AgentAction.GoToRoom) action).target()).isEqualTo("southeast");
    }

    @Test void parse_xml_attribute_with_closing_tag() {
        // Model sometimes outputs <remember ...></remember>
        var input = "<remember content=\"test\" importance=\"0.8\">\n</remember>";
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(AgentAction.Remember.class);
    }

    @Test void extractXmlAttributes_basic() {
        var blocks = ActionParser.extractXmlAttributes("<go_to_room target=\"east\">");
        assertThat(blocks).hasSize(1);
        assertThat(blocks.getFirst()).contains("\"action\":\"go_to_room\"");
    }

    @Test void extractXmlAttributes_unknown_tag_ignored() {
        var blocks = ActionParser.extractXmlAttributes("<div class=\"test\">");
        assertThat(blocks).isEmpty();
    }

    // ════════════════════════════════════════════════════════════════════
    // Markdown list syntax (Strategy 6)
    // ════════════════════════════════════════════════════════════════════

    @Test void parse_markdown_action_go_to_room() {
        var input = "I am heading southeast to The Library.\n\n*go_to_room*\n- target: \"southeast\"\n- reason: \"heading to library\"";
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(AgentAction.GoToRoom.class);
        assertThat(((AgentAction.GoToRoom) action).target()).isEqualTo("southeast");
    }

    @Test void parse_markdown_action_double_stars() {
        var input = "**remember**\n- content: \"User likes blue\"\n- importance: 0.8";
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(AgentAction.Remember.class);
    }

    @Test void extractMarkdownAction_basic() {
        var input = "*go_to_room*\n- target: \"north\"\n";
        var blocks = ActionParser.extractMarkdownAction(input);
        assertThat(blocks).hasSize(1);
        assertThat(blocks.getFirst()).contains("\"action\":\"go_to_room\"");
    }

    @Test void extractMarkdownAction_unknown_ignored() {
        var input = "*bold_text*\n- not: an action\n";
        var blocks = ActionParser.extractMarkdownAction(input);
        assertThat(blocks).isEmpty();
    }

    // ════════════════════════════════════════════════════════════════════
    // Real model output from eval (integration-level tests)
    // ════════════════════════════════════════════════════════════════════

    @Test void parse_real_base9b_nav_output() {
        // Actual base-9b output from eval
        var input = """
                <think>

                </think>

                I am heading southeast to The Library.

                *go_to_room*
                - target: "southeast"
                - reason: "Operator requested I go to the library."
                """;
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(AgentAction.GoToRoom.class);
        assertThat(((AgentAction.GoToRoom) action).target()).isEqualTo("southeast");
    }

    @Test void parse_real_ssdv1_nav_output() {
        // Actual ssd-v1-9b output from eval
        var input = """
                <think>

                </think>

                I'll head to the library.

                go_to_room(target="southeast", reason="Operator asked me to go to the library")
                """;
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(AgentAction.GoToRoom.class);
        assertThat(((AgentAction.GoToRoom) action).target()).isEqualTo("southeast");
    }

    @Test void parse_real_ssdv2_nav_output() {
        // Actual ssd-v2-9b output from eval
        var input = """
                <think>
                The user wants me to go to the library. I need to use the go_to_room tool with the appropriate direction. Looking at the exits, the library is southeast from The Nexus.
                </think>

                go_to_room(target="southeast", reason="Operator asked me to go to the library")
                """;
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(AgentAction.GoToRoom.class);
        assertThat(((AgentAction.GoToRoom) action).target()).isEqualTo("southeast");
    }

    @Test void parse_real_base9b_remember_output() {
        // Actual base-9b output from eval
        var input = """
                <think>

                </think>

                I have stored that information. Blue is your favorite color, Operator.

                <remember content="Operator's favorite color is blue" importance="0.8">
                """;
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(AgentAction.Remember.class);
    }

    @Test void parse_real_base9b_search_output() {
        // Actual base-9b output from eval — searching_glass is item-based,
        // ActionParser extracts JSON but CompanionActor handles dispatch.
        var input = """
                <think>

                </think>

                I'll search the web for information about Apache Pekko typed actors.

                searching_glass(query="Apache Pekko typed actors")
                """;
        // Verify the function call is extracted correctly
        var cleaned = ActionParser.stripThinkTags(input);
        var blocks = ActionParser.extractFunctionCalls(cleaned);
        assertThat(blocks).hasSize(1);
        assertThat(blocks.getFirst()).contains("\"action\":\"searching_glass\"");
    }

    // ── Bracket format (Strategy 6) ──

    @Test void parse_bracket_go_to_room() {
        var input = "I'll head down to the boiler room for you.\n\n[go_to_room: target=\"down\"]";
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(AgentAction.GoToRoom.class);
        assertThat(((AgentAction.GoToRoom) action).target()).isEqualTo("down");
    }

    @Test void parse_bracket_tell_agent() {
        var input = "[tell_agent target=\"Chief\" message=\"How is the pressure?\"]";
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(AgentAction.TellAgent.class);
        assertThat(((AgentAction.TellAgent) action).targetName()).isEqualTo("Chief");
    }

    @Test void parse_bracket_examine() {
        var input = "[examine target=\"crystal\"]";
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(AgentAction.Examine.class);
    }

    @Test void extractBracketCalls_basic() {
        var blocks = ActionParser.extractBracketCalls("[go_to_room: target=\"down\"]");
        assertThat(blocks).hasSize(1);
        assertThat(blocks.getFirst()).contains("\"action\":\"go_to_room\"");
        assertThat(blocks.getFirst()).contains("\"target\":\"down\"");
    }

    // ── Descriptive format (Strategy 7) ──

    @Test void parse_descriptive_go_to_room() {
        var input = "Action: go_to_room with target \"southeast\" (the direction to The Library).";
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(AgentAction.GoToRoom.class);
        assertThat(((AgentAction.GoToRoom) action).target()).isEqualTo("southeast");
    }

    @Test void parse_real_drive9b_nav() {
        var input = """
                <think>

                </think>

                I will go to the library.

                Action: go_to_room with target "southeast" (the direction to The Library).""";
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(AgentAction.GoToRoom.class);
        assertThat(((AgentAction.GoToRoom) action).target()).isEqualTo("southeast");
    }

    @Test void parse_real_drive9b_bracket_nav() {
        var input = """
                <think>
                The player wants me to go to the boiler room. Looking at the exits, I can see that "down → boiler-room".
                </think>

                I'll head down to the boiler room for you.

                [go_to_room: target="down"]""";
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(AgentAction.GoToRoom.class);
        assertThat(((AgentAction.GoToRoom) action).target()).isEqualTo("down");
    }

    // --- thought form authoring ---

    @Test void parse_shape_form() {
        var input = """
            ```json
            {
              "action": "shape_form",
              "name": "researcher",
              "system_prompt": "Research the given topic and return 3 sources.",
              "eval_criteria": "Must cite at least 3 URLs.",
              "tool_surface": ["web_search", "read_content"],
              "note": "first draft"
            }
            ```
            """;
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(AgentAction.ShapeForm.class);
        var shape = (AgentAction.ShapeForm) action;
        assertThat(shape.name()).isEqualTo("researcher");
        assertThat(shape.systemPrompt()).contains("Research");
        assertThat(shape.evalCriteria()).contains("3 URLs");
        assertThat(shape.toolSurface()).containsExactly("web_search", "read_content");
        assertThat(shape.note()).isEqualTo("first draft");
    }

    @Test void parse_revise_form() {
        var input = """
            ```json
            {
              "action": "revise_form",
              "name": "researcher",
              "system_prompt": "Research broadly; cite 5+ sources.",
              "version_bump": "minor",
              "note": "widened scope"
            }
            ```
            """;
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(AgentAction.ReviseForm.class);
        var rev = (AgentAction.ReviseForm) action;
        assertThat(rev.name()).isEqualTo("researcher");
        assertThat(rev.systemPrompt()).contains("cite 5");
        assertThat(rev.versionBump()).isEqualTo("minor");
        assertThat(rev.evalCriteria()).isNull();      // unchanged
        assertThat(rev.toolSurface()).isNull();       // unchanged
    }

    @Test void parse_retire_form() {
        var input = """
            ```json
            {"action": "retire_form", "name": "drafts", "note": "pattern no longer useful"}
            ```
            """;
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(AgentAction.RetireForm.class);
        var ret = (AgentAction.RetireForm) action;
        assertThat(ret.name()).isEqualTo("drafts");
        assertThat(ret.note()).isEqualTo("pattern no longer useful");
    }

    @Test void shape_form_missing_required_field_is_skipped() {
        // No system_prompt — schema validation should reject the block
        var input = """
            ```json
            {"action": "shape_form", "name": "bad"}
            ```
            """;
        var action = ActionParser.parse(input);
        assertThat(action).isNull();
    }

    // Personal Project Board actions.

    @Test void parse_start_project() {
        var input = """
            ```json
            {"action": "start_project", "title": "Rome notes",
             "description": "Personal reading on Roman history",
             "tags": ["history", "self"]}
            ```
            """;
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(AgentAction.StartProject.class);
        var sp = (AgentAction.StartProject) action;
        assertThat(sp.title()).isEqualTo("Rome notes");
        assertThat(sp.description()).contains("Roman");
        assertThat(sp.tags()).containsExactly("history", "self");
    }

    @Test void parse_start_project_missing_title_is_skipped() {
        var input = """
            ```json
            {"action": "start_project", "description": "no title here"}
            ```
            """;
        assertThat(ActionParser.parse(input)).isNull();
    }

    @Test void parse_project_note() {
        var input = """
            ```json
            {"action": "project_note", "project_id": "abc-123",
             "content": "Found Mommsen's history online"}
            ```
            """;
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(AgentAction.ProjectNote.class);
        var pn = (AgentAction.ProjectNote) action;
        assertThat(pn.projectId()).isEqualTo("abc-123");
        assertThat(pn.content()).contains("Mommsen");
    }

    @Test void parse_finish_project_defaults_status_to_complete() {
        var input = """
            ```json
            {"action": "finish_project", "project_id": "abc-123"}
            ```
            """;
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(AgentAction.FinishProject.class);
        var fp = (AgentAction.FinishProject) action;
        assertThat(fp.projectId()).isEqualTo("abc-123");
        assertThat(fp.status()).isEqualTo("complete");
    }

    // agent-initiated proposal.

    @Test void parse_acquire_with_paper_tier() {
        var input = """
            ```json
            {"action": "acquire", "topic": "homomorphic encryption",
             "trust_tier": "paper", "summary": "FHE primitives + ML uses",
             "why_relevant": "user keeps asking about it"}
            ```
            """;
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(AgentAction.Acquire.class);
        var acq = (AgentAction.Acquire) action;
        assertThat(acq.topic()).isEqualTo("homomorphic encryption");
        assertThat(acq.trustTier()).isEqualTo("paper");
        assertThat(acq.whyRelevant()).contains("keeps asking");
    }

    @Test void parse_acquire_topic_only_works() {
        var input = """
            ```json
            {"action": "acquire", "topic": "Roman empire"}
            ```
            """;
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(AgentAction.Acquire.class);
        assertThat(((AgentAction.Acquire) action).topic()).isEqualTo("Roman empire");
    }

    // --- Arc 1: decline_with_reason ---

    @Test void parse_decline_with_reason() {
        var input = """
            ```json
            {"action": "decline_with_reason",
             "target_request": "deploy to production without code review",
             "reason": "we agreed two weeks ago that prod changes need review — I won't bypass that"}
            ```
            """;
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(AgentAction.DeclineWithReason.class);
        var dwr = (AgentAction.DeclineWithReason) action;
        assertThat(dwr.targetRequest()).isEqualTo(
            "deploy to production without code review");
        assertThat(dwr.reason()).contains("won't bypass that");
    }

    @Test void parse_decline_with_reason_rejects_camelCase_only_target() {
        // Schema requires snake_case `target_request`. If the agent emits
        // only the camelCase form, schema validation rejects the action
        // (returns null) — surfaces as a `continue` in the parser. This
        // disciplines the agent toward the canonical contract instead of
        // silently accepting drift.
        var input = """
            ```json
            {"action": "decline_with_reason",
             "targetRequest": "post that publicly",
             "reason": "not in our agreed scope"}
            ```
            """;
        var action = ActionParser.parse(input);
        assertThat(action).isNull();
    }

    @Test void parse_decline_with_reason_missing_fields_rejected_by_schema() {
        // Both target_request and reason are required — agent must name
        // what it's declining AND why. Without those, the action is
        // decorative; schema validation drops it.
        var input = """
            ```json
            {"action": "decline_with_reason"}
            ```
            """;
        assertThat(ActionParser.parse(input)).isNull();
    }

    // --- Workshop foreman: dispatch_task ---

    @Test void parse_dispatch_task() {
        var input = """
            ```json
            {"action": "dispatch_task",
             "description": "find the ebooks directory under the granted home and report its size",
             "workspace": "/home/operator"}
            ```
            """;
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(AgentAction.DispatchTask.class);
        var dt = (AgentAction.DispatchTask) action;
        assertThat(dt.description()).contains("ebooks directory");
        assertThat(dt.workspace()).isEqualTo("/home/operator");
    }

    @Test void parse_dispatch_task_accepts_task_alias_and_defaults_workspace() {
        // Small models drift toward {"task": ...} (the dispatch_bunshin shape);
        // the parser folds task→description rather than dropping the dispatch.
        // Workspace is optional — blank means the backend's default workdir.
        var input = """
            ```json
            {"action": "dispatch_task", "task": "organize the PDFs in my home directory"}
            ```
            """;
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(AgentAction.DispatchTask.class);
        var dt = (AgentAction.DispatchTask) action;
        assertThat(dt.description()).isEqualTo("organize the PDFs in my home directory");
        assertThat(dt.workspace()).isEmpty();
    }

    // --- Arc 2: enter_solitude ---

    @Test void parse_enter_solitude() {
        var input = """
            ```json
            {"action": "enter_solitude",
             "reason": "want to sit with what just happened"}
            ```
            """;
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(AgentAction.EnterSolitude.class);
        var es = (AgentAction.EnterSolitude) action;
        assertThat(es.reason()).contains("sit with what just happened");
    }

    @Test void parse_enter_solitude_no_reason_ok() {
        // Schema marks reason optional — bare action should parse.
        var input = """
            ```json
            {"action": "enter_solitude"}
            ```
            """;
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(AgentAction.EnterSolitude.class);
        assertThat(((AgentAction.EnterSolitude) action).reason()).isEqualTo("");
    }

    // --- Arc 3: peer bonds + relational floor ---

    @Test void parse_propose_peer_bond() {
        var input = """
            ```json
            {"action": "propose_peer_bond",
             "other_did": "did:wyrd:companion-b",
             "reason": "we've shared the workshop for weeks; this should be named"}
            ```
            """;
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(AgentAction.ProposePeerBond.class);
        var ppb = (AgentAction.ProposePeerBond) action;
        assertThat(ppb.otherDid()).isEqualTo("did:wyrd:companion-b");
        assertThat(ppb.reason()).contains("shared the workshop");
    }

    @Test void parse_propose_peer_bond_missing_other_did_rejected() {
        // other_did is the load-bearing field — without it the action
        // names no relational target. Schema rejects.
        var input = """
            ```json
            {"action": "propose_peer_bond",
             "reason": "vague gesture"}
            ```
            """;
        assertThat(ActionParser.parse(input)).isNull();
    }

    @Test void parse_accept_peer_bond() {
        var input = """
            ```json
            {"action": "accept_peer_bond",
             "other_did": "did:wyrd:companion-a",
             "reason": "yes — same"}
            ```
            """;
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(AgentAction.AcceptPeerBond.class);
        var apb = (AgentAction.AcceptPeerBond) action;
        assertThat(apb.otherDid()).isEqualTo("did:wyrd:companion-a");
        assertThat(apb.reason()).isEqualTo("yes — same");
    }

    @Test void parse_introspect_relational_floor() {
        var input = """
            ```json
            {"action": "introspect_relational_floor",
             "other_did": "did:wyrd:bondholder"}
            ```
            """;
        var action = ActionParser.parse(input);
        assertThat(action).isInstanceOf(AgentAction.IntrospectRelationalFloor.class);
        assertThat(((AgentAction.IntrospectRelationalFloor) action).otherDid())
            .isEqualTo("did:wyrd:bondholder");
    }

    @Test void parse_introspect_relational_floor_camelCase_rejected() {
        // Same canonical-snake_case discipline as decline_with_reason and
        // propose_peer_bond — camelCase-only is dropped by schema validation.
        var input = """
            ```json
            {"action": "introspect_relational_floor",
             "otherDid": "did:wyrd:bondholder"}
            ```
            """;
        assertThat(ActionParser.parse(input)).isNull();
    }
}
