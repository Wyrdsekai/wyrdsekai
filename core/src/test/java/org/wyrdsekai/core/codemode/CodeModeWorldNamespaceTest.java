package org.wyrdsekai.core.codemode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.scripting.api.ItemWorldApiProvider;
import org.wyrdsekai.scripting.codemode.CodeModeExecutor;
import org.wyrdsekai.scripting.sandbox.ItemScriptExecutor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * / Phase 2a — {@code world.peek} and
 * {@code world.listInventory} wiring.
 *
 * <p>Asserts:
 * <ul>
 *   <li>{@code world.peek("foyer")} returns the populated room snapshot
 *       when the peek provider yields one.</li>
 *   <li>{@code world.peek("nonexistent")} returns null when the provider
 *       can't resolve.</li>
 *   <li>{@code world.peek("zone.room")} (cross-zone) returns null without
 *       calling the provider — Phase 2b stub-marker.</li>
 *   <li>{@code world.listInventory()} returns one entry per equipped item.</li>
 *   <li>End-to-end: a JS script using {@code world.peek} + {@code listInventory}
 *       runs cleanly and {@code console.log}s the expected output.</li>
 * </ul>
 */
class CodeModeWorldNamespaceTest {

    private ItemScriptExecutor exec;
    private TestProvider provider;

    @BeforeEach
    void setUp() {
        exec = new ItemScriptExecutor();
        provider = new TestProvider();
    }

    @AfterEach
    void tearDown() {
        exec.close();
    }

    @Test
    void world_peek_returns_populated_snapshot() {
        var foyerSnap = new LinkedHashMap<String, Object>();
        foyerSnap.put("name", "Foyer");
        foyerSnap.put("description", "A bright entryway.");
        foyerSnap.put("exits", List.of("north", "east"));
        foyerSnap.put("entities", List.of(
            Map.of("alias", "wyrd", "kind", "agent")));
        foyerSnap.put("items", List.of(
            Map.of("alias", "lantern", "kind", "item")));

        CodeModeNamespace.WorldPeekProvider peek = alias ->
            "foyer".equals(alias) ? foyerSnap : null;

        var ns = CodeModeNamespace.forActor(
            List.of(), exec, provider, peek, null, null);

        @SuppressWarnings("unchecked")
        var result = (Map<String, Object>) ns.get("world").get("peek")
            .apply(new Object[]{"foyer"});
        assertThat(result).isNotNull();
        assertThat(result.get("name")).isEqualTo("Foyer");
        assertThat(result.get("description")).isEqualTo("A bright entryway.");
        @SuppressWarnings("unchecked")
        var exits = (List<String>) result.get("exits");
        assertThat(exits).containsExactly("north", "east");
    }

    @Test
    void world_peek_returns_null_for_unknown_alias() {
        CodeModeNamespace.WorldPeekProvider peek = alias -> null;
        var ns = CodeModeNamespace.forActor(
            List.of(), exec, provider, peek, null, null);

        var result = ns.get("world").get("peek").apply(new Object[]{"nonexistent"});
        assertThat(result).isNull();
    }

    @Test
    void world_peek_returns_null_when_provider_throws() {
        CodeModeNamespace.WorldPeekProvider peek = alias -> {
            throw new RuntimeException("simulated failure");
        };
        var ns = CodeModeNamespace.forActor(
            List.of(), exec, provider, peek, null, null);

        // Provider failure surfaces as null — the script handles it.
        var result = ns.get("world").get("peek").apply(new Object[]{"foyer"});
        assertThat(result).isNull();
    }

    @Test
    void world_peek_returns_null_for_blank_alias() {
        CodeModeNamespace.WorldPeekProvider peek = alias ->
            Map.of("name", alias);
        var ns = CodeModeNamespace.forActor(
            List.of(), exec, provider, peek, null, null);

        assertThat(ns.get("world").get("peek").apply(new Object[]{""})).isNull();
        assertThat(ns.get("world").get("peek").apply(new Object[]{})).isNull();
    }

    @Test
    void world_list_inventory_returns_equipped_items() {
        provider.inventory.add(Map.of("id", "lib-1", "name", "library_card",
            "description", "A library card"));
        provider.inventory.add(Map.of("id", "ora-1", "name", "oracle_lens",
            "description", "An oracle lens"));

        var ns = CodeModeNamespace.forActor(List.of(), exec, provider);

        @SuppressWarnings("unchecked")
        var result = (List<Map<String, Object>>) ns.get("world")
            .get("listInventory").apply(new Object[]{});
        assertThat(result).hasSize(2);
        assertThat(result.get(0).get("alias")).isEqualTo("library_card");
        assertThat(result.get(0).get("equipped")).isEqualTo(true);
        assertThat(result.get(1).get("alias")).isEqualTo("oracle_lens");
    }

    @Test
    void world_list_inventory_empty_when_none_equipped() {
        var ns = CodeModeNamespace.forActor(List.of(), exec, provider);
        @SuppressWarnings("unchecked")
        var result = (List<Map<String, Object>>) ns.get("world")
            .get("listInventory").apply(new Object[]{});
        assertThat(result).isEmpty();
    }

    @Test
    void e2e_script_uses_world_peek_and_inventory() {
        var foyerSnap = new LinkedHashMap<String, Object>();
        foyerSnap.put("name", "Foyer");
        foyerSnap.put("exits", List.of("north"));
        provider.inventory.add(Map.of("id", "card-1", "name", "library_card",
            "description", ""));

        CodeModeNamespace.WorldPeekProvider peek = alias ->
            "foyer".equals(alias) ? foyerSnap : null;

        var ns = CodeModeNamespace.forActor(
            List.of(), exec, provider, peek, null, null);

        var result = CodeModeExecutor.run("""
            const room = world.peek('foyer');
            const inv = world.listInventory();
            console.log('room=' + room.name);
            console.log('items=' + inv.length);
            console.log('first=' + inv[0].alias);
            """, ns);

        assertThat(result.success()).isTrue();
        assertThat(result.log()).contains("room=Foyer", "items=1", "first=library_card");
    }

    /** Test provider with mutable inventory list. */
    static class TestProvider implements ItemWorldApiProvider {
        final List<Map<String, Object>> inventory = new ArrayList<>();

        @Override public List<Map<String, Object>> searchKnowledge(String q, int l) { return List.of(); }
        @Override public Map<String, Object> readKnowledgeChunk(String id) { return Map.of(); }
        @Override public List<Map<String, Object>> webSearch(String q, String t, int l) { return List.of(); }
        @Override public String webFetch(String url, int max) { return ""; }
        @Override public List<Map<String, Object>> queryOracle(String t, String a) { return List.of(); }
        @Override public String llmSummarize(String text, String inst) { return ""; }
        @Override public String llmAnalyze(String text, String prompt) { return ""; }
        @Override public void agentSpeak(String text) {}
        @Override public void agentRemember(String text) {}
        @Override public void agentTell(String tgt, String msg) {}
        @Override public List<Map<String, Object>> inventoryList() { return inventory; }
        @Override public Map<String, Object> inventoryUse(String id, Map<String, Object> p, int d) { return Map.of(); }
    }
}
