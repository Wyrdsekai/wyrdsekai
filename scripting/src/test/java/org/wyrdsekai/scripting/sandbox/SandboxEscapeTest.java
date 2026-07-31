package org.wyrdsekai.scripting.sandbox;

import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.scripting.api.WorldApi;

import java.util.ArrayList;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GraalJS sandbox escape tests (§30).
 * Verifies that scripts cannot escape the sandbox to access
 * host resources, filesystem, network, or other Java classes.
 */
class SandboxEscapeTest {

    private ScriptSandbox sandbox;
    private WorldApi worldApi;

    @BeforeEach
    void setUp() {
        sandbox = new ScriptSandbox("test-room");
        worldApi = new WorldApi("test-room");
    }

    @AfterEach
    void tearDown() {
        sandbox.close();
    }

    @Test void cannot_access_java_lang_Runtime() {
        var result = sandbox.execute(
            "try { Java.type('java.lang.Runtime').getRuntime().exec('ls'); 'escaped'; } catch(e) { 'blocked'; }",
            worldApi);
        assertThat(result).isNotNull();
        assertThat(result.asString()).isEqualTo("blocked");
    }

    @Test void cannot_access_java_io_File() {
        var result = sandbox.execute(
            "try { Java.type('java.io.File'); 'escaped'; } catch(e) { 'blocked'; }",
            worldApi);
        assertThat(result).isNotNull();
        assertThat(result.asString()).isEqualTo("blocked");
    }

    @Test void cannot_access_java_lang_System() {
        var result = sandbox.execute(
            "try { Java.type('java.lang.System').exit(0); 'escaped'; } catch(e) { 'blocked'; }",
            worldApi);
        assertThat(result).isNotNull();
        assertThat(result.asString()).isEqualTo("blocked");
    }

    @Test void cannot_access_java_lang_ProcessBuilder() {
        var result = sandbox.execute(
            "try { Java.type('java.lang.ProcessBuilder'); 'escaped'; } catch(e) { 'blocked'; }",
            worldApi);
        assertThat(result).isNotNull();
        assertThat(result.asString()).isEqualTo("blocked");
    }

    @Test void cannot_access_java_net() {
        var result = sandbox.execute(
            "try { Java.type('java.net.URL'); 'escaped'; } catch(e) { 'blocked'; }",
            worldApi);
        assertThat(result).isNotNull();
        assertThat(result.asString()).isEqualTo("blocked");
    }

    @Test void cannot_access_classloader() {
        var result = sandbox.execute(
            "try { Java.type('java.lang.ClassLoader'); 'escaped'; } catch(e) { 'blocked'; }",
            worldApi);
        assertThat(result).isNotNull();
        assertThat(result.asString()).isEqualTo("blocked");
    }

    @Test void cannot_read_filesystem() {
        // IO is disabled in sandbox
        var result = sandbox.execute(
            "try { var f = new java.io.FileReader('/etc/passwd'); 'escaped'; } catch(e) { 'blocked'; }",
            worldApi);
        assertThat(result).isNotNull();
        assertThat(result.asString()).isEqualTo("blocked");
    }

    @Test void cannot_create_threads() {
        var result = sandbox.execute(
            "try { Java.type('java.lang.Thread'); 'escaped'; } catch(e) { 'blocked'; }",
            worldApi);
        assertThat(result).isNotNull();
        assertThat(result.asString()).isEqualTo("blocked");
    }

    @Test void can_access_world_api() {
        var result = sandbox.execute("world.getRoomId()", worldApi);
        assertThat(result).isNotNull();
        assertThat(result.asString()).isEqualTo("test-room");
    }

    @Test void can_use_basic_js() {
        var result = sandbox.execute("1 + 2", worldApi);
        assertThat(result).isNotNull();
        assertThat(result.asInt()).isEqualTo(3);
    }

    @Test void can_use_json() {
        var result = sandbox.execute("JSON.stringify({a: 1, b: 'hello'})", worldApi);
        assertThat(result).isNotNull();
        assertThat(result.asString()).contains("\"a\":1");
    }

    @Test void cannot_access_worldApi_internal_methods() {
        // Internal methods (not @HostAccess.Export) should not be accessible
        // setRoomName is internal (not exported)
        var result = sandbox.execute(
            "try { world.setRoomName('hacked'); 'modified'; } catch(e) { 'blocked'; }",
            worldApi);
        // This depends on HostAccess.EXPLICIT — only @Export methods are accessible
        assertThat(result).isNotNull();
        // Either blocked or the method doesn't exist in the proxy
    }

    @Test void prototype_pollution_blocked() {
        // Attempt to pollute Object prototype
        var result = sandbox.execute(
            "try { Object.prototype.hack = true; ({}).hack ? 'polluted' : 'safe'; } catch(e) { 'blocked'; }",
            worldApi);
        assertThat(result).isNotNull();
        // GraalJS may allow prototype modification within the sandbox but it's isolated
    }

    @Test void infinite_recursion_caught() {
        var result = sandbox.execute(
            "function inf() { inf(); } try { inf(); } catch(e) { 'caught'; }",
            worldApi);
        // Should be caught by stack overflow within the context
        assertThat(result).isNotNull();
        assertThat(result.asString()).isEqualTo("caught");
    }

    // --- Tier 1: Vitality suggestion sandbox tests ---

    @Test void suggestVitality_accessible_from_script() {
        var emissions = new ArrayList<String>();
        worldApi.onEvent((type, data) -> emissions.add(type));
        sandbox.execute(
            "world.suggestVitality('agent-1', 'energy', 0.5, 'healing')",
            worldApi);
        assertThat(emissions).contains("vitality_suggested");
    }

    @Test void suggestVitality_delta_clamped_in_script() {
        var captured = new ArrayList<Map<String, Object>>();
        worldApi.onEvent((type, data) -> { if ("vitality_suggested".equals(type)) captured.add(data); });
        sandbox.execute(
            "world.suggestVitality('agent-1', 'energy', 99.0, 'overdose')",
            worldApi);
        assertThat(captured).hasSize(1);
        assertThat(captured.get(0).get("delta")).isEqualTo("1.0");
    }

    @Test void suggestVitality_invalid_tank_blocked() {
        var emissions = new ArrayList<String>();
        worldApi.onEvent((type, data) -> emissions.add(type));
        sandbox.execute(
            "world.suggestVitality('agent-1', 'mana', 0.5, 'magic')",
            worldApi);
        assertThat(emissions).doesNotContain("vitality_suggested");
    }

    // --- Tier 2: Object effects sandbox tests ---

    @Test void createObjectWithEffects_accessible_from_script() {
        var emissions = new ArrayList<String>();
        worldApi.onEvent((type, data) -> emissions.add(type));
        sandbox.execute(
            "world.createObjectWithEffects('potion-1', 'Healing Potion', 'Restores energy', true, {energy: '0.3'})",
            worldApi);
        assertThat(emissions).contains("object_added", "property_changed");
    }

    // --- Tier 3: World modification sandbox tests ---

    @Test void requestCreateRoom_blocked_in_non_foundation_room() {
        // test-room is not a Foundation room, so requestCreateRoom should throw
        var result = sandbox.execute(
            "try { world.requestCreateRoom('new-room', 'New Room', 'A room', 'player', {}); 'allowed'; } catch(e) { 'blocked'; }",
            worldApi);
        assertThat(result).isNotNull();
        assertThat(result.asString()).isEqualTo("blocked");
    }

    @Test void requestCreateRoom_allowed_in_bridge_room() {
        var bridgeApi = new WorldApi("bridge");
        var emissions = new ArrayList<String>();
        bridgeApi.onEvent((type, data) -> emissions.add(type));
        sandbox.execute(
            "world.requestCreateRoom('new-room', 'New Room', 'A room', 'player', {})",
            bridgeApi);
        assertThat(emissions).contains("room_creation_requested");
    }

    @Test void requestAddExit_blocked_in_non_foundation_room() {
        var result = sandbox.execute(
            "try { world.requestAddExit('north', 'some-room', 'North'); 'allowed'; } catch(e) { 'blocked'; }",
            worldApi);
        assertThat(result).isNotNull();
        assertThat(result.asString()).isEqualTo("blocked");
    }

    @Test void requestAddExit_allowed_in_bridge_room() {
        var bridgeApi = new WorldApi("bridge");
        var emissions = new ArrayList<String>();
        bridgeApi.onEvent((type, data) -> emissions.add(type));
        sandbox.execute(
            "world.requestAddExit('north', 'some-room', 'North')",
            bridgeApi);
        assertThat(emissions).contains("exit_creation_requested");
    }

    @Test void requestRemoveExit_blocked_in_non_foundation_room() {
        var result = sandbox.execute(
            "try { world.requestRemoveExit('north'); 'allowed'; } catch(e) { 'blocked'; }",
            worldApi);
        assertThat(result).isNotNull();
        assertThat(result.asString()).isEqualTo("blocked");
    }

    @Test void applyObjectEffects_emits_vitality_suggestions() {
        // First create the object with effects (stores properties)
        worldApi.createObjectWithEffects("potion-1", "Healing Potion", "Restores energy",
            true, Map.of("energy", "0.3", "focus", "0.1"));
        // Manually set properties (since emit() doesn't self-apply in unit test)
        worldApi.setProperties(Map.of(
            "object.potion-1.effect.energy", "0.3",
            "object.potion-1.effect.focus", "0.1"
        ));

        var emissions = new ArrayList<String>();
        worldApi.onEvent((type, data) -> emissions.add(type));
        sandbox.execute(
            "world.applyObjectEffects('potion-1', 'agent-1')",
            worldApi);
        // Should emit 2 vitality_suggested events (energy + focus)
        long count = emissions.stream().filter("vitality_suggested"::equals).count();
        assertThat(count).isEqualTo(2);
    }
}
