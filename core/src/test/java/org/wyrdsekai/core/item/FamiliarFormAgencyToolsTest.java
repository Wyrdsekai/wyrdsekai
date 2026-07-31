package org.wyrdsekai.core.item;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.util.Json;
import org.wyrdsekai.core.agent.ActionParser;
import org.wyrdsekai.core.agent.ActionPolicy;
import org.wyrdsekai.core.agent.ActionSchemas;
import org.wyrdsekai.core.inference.InferenceClient;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * W7 (, audit 2026-07-11) — the familiar/form action
 * family is offered on the agency tool surface, and every offered tool
 * round-trips through the REAL parser: build the {@code ToolDefinition},
 * synthesize a call {@code {"action": <id>, ...params}} from its own schema,
 * and require {@link ActionParser#parseAll} to yield a primary action whose
 * canonical name equals the tool id. This is exactly the mismatch class the
 * audit found in task_ledger (schema teaches params the parser won't accept).
 */
class FamiliarFormAgencyToolsTest {

    /** The W7 family — tool id MUST equal the parser action string. */
    private static final Set<String> FAMILY_IDS = Set.of(
        "shape_form", "revise_form", "retire_form",
        "summon_familiar", "dispatch_bunshin", "bunshin_check_in",
        "create_imprint", "restore_imprint",
        "give_copy", "name_familiar",
        "craft_summon_key", "revoke_summon_key",
        "promote_familiar", "destroy_tool",
        "set_deviation_thresholds");

    @Test
    void familiarFormFamilyIsOfferedOnAgencySurface() {
        var offered = ToolItemStarterKit.agencyActions().stream()
            .map(ToolItem::id)
            .collect(Collectors.toSet());
        var missing = FAMILY_IDS.stream()
            .filter(id -> !offered.contains(id))
            .toList();
        assertTrue(missing.isEmpty(),
            "W7: familiar/form acts missing from agencyActions(): " + missing);
    }

    @Test
    void familiarFormActionsReturnsExactlyTheFamily() {
        var ids = ToolItemStarterKit.familiarFormActions().stream()
            .map(ToolItem::id)
            .collect(Collectors.toSet());
        assertEquals(FAMILY_IDS, ids,
            "familiarFormActions() must carry exactly the W7 family");
    }

    @Test
    void everyFamiliarFormActionDeclaresEmbodiment() {
        // non-null after attachEmbodiment means the
        // registry has an entry and no boot WARN fires for these ids.
        var missing = ToolItemStarterKit.familiarFormActions().stream()
            .filter(i -> i.embodiment() == null)
            .map(ToolItem::id)
            .toList();
        assertTrue(missing.isEmpty(),
            "familiar/form acts missing embodiment: " + missing);
        for (var id : FAMILY_IDS) {
            var spec = ToolItemStarterKit.EMBODIMENT_REGISTRY.get(id);
            assertNotNull(spec, "EMBODIMENT_REGISTRY missing id: " + id);
            assertTrue(spec.isValid(),
                "EMBODIMENT_REGISTRY entry structurally invalid for: " + id);
        }
    }

    @Test
    void everyFamiliarFormActionHasARealSchema() {
        // Audit Class G — schema-built tools must ship real properties/required.
        for (var item : ToolItemStarterKit.familiarFormActions()) {
            assertTrue(ActionSchemas.hasSchema(item.id()),
                "ActionSchemas.SCHEMAS missing entry for: " + item.id());
            var def = item.toToolDefinition();
            assertEquals(item.id(), def.function().name(),
                "tool name must equal the parseable action string");
            assertNotNull(def.function().description());
            assertFalse(def.function().description().isBlank(),
                "description must teach usage for: " + item.id());
            var schema = Json.mapper().valueToTree(def.function().parameters());
            assertTrue(schema.path("properties").size() > 0,
                "tool ships an empty properties object: " + item.id());
        }
    }

    @Test
    void fullSchemaCallRoundTripsThroughActionParser() {
        // Every param the schema advertises, filled — the parser must accept
        // the call and produce the right action type. Proves every advertised
        // param NAME is one the parse branch reads (or at worst tolerates).
        for (var item : ToolItemStarterKit.familiarFormActions()) {
            var call = sampleCall(item.toToolDefinition(), false);
            assertRoundTrips(item.id(), call);
        }
    }

    @Test
    void requiredOnlyCallRoundTripsThroughActionParser() {
        // Minimal call (required params only) must survive ActionSchemas
        // validation inside the parser — proves the tool's required set
        // covers everything SCHEMAS insists on.
        for (var item : ToolItemStarterKit.familiarFormActions()) {
            var call = sampleCall(item.toToolDefinition(), true);
            assertRoundTrips(item.id(), call);
        }
    }

    @Test
    void toolIdsStayUniqueAcrossAllSurfaces() {
        var all = new ArrayList<ToolItem>();
        all.addAll(ToolItemStarterKit.inherentActions());
        all.addAll(ToolItemStarterKit.standard());
        all.addAll(ToolItemStarterKit.agencyActions());
        var seen = new HashSet<String>();
        for (var item : all) {
            assertTrue(seen.add(item.id()), "Duplicate tool id: " + item.id());
        }
    }

    // ── helpers ─────────────────────────────────────────────────────

    private static void assertRoundTrips(String id, ObjectNode call) {
        var llmOutput = "```json\n" + call.toPrettyString() + "\n```";
        var result = ActionParser.parseAll(llmOutput);
        assertNotNull(result.primaryAction(),
            "W7 round-trip: parser yielded no action for '" + id
                + "' from schema-built call:\n" + llmOutput);
        assertEquals(id, ActionPolicy.actionTypeOf(result.primaryAction()),
            "W7 round-trip: '" + id + "' parsed to a different action type");
    }

    /**
     * Synthesize a call from the tool's OWN advertised schema — the same
     * thing a model following the schema would emit. Enums use their first
     * value; free params get type-appropriate samples.
     */
    private static ObjectNode sampleCall(
            InferenceClient.ToolDefinition def, boolean requiredOnly) {
        var mapper = Json.mapper();
        var call = mapper.createObjectNode();
        call.put("action", def.function().name());
        JsonNode params = mapper.valueToTree(def.function().parameters());
        var required = new HashSet<String>();
        if (params.has("required")) {
            params.get("required").forEach(n -> required.add(n.asText()));
        }
        var props = params.path("properties");
        props.fields().forEachRemaining(field -> {
            var name = field.getKey();
            if (requiredOnly && !required.contains(name)) return;
            var spec = field.getValue();
            if (spec.has("enum") && spec.get("enum").size() > 0) {
                call.put(name, spec.get("enum").get(0).asText());
                return;
            }
            switch (spec.path("type").asText("string")) {
                case "number" -> call.put(name, 1);
                case "boolean" -> call.put(name, true);
                case "array" -> call.putArray(name).add("sample");
                default -> call.put(name, "sample " + name.replace('_', ' '));
            }
        });
        return call;
    }
}
