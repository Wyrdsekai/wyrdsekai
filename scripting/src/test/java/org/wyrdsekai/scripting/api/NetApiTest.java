package org.wyrdsekai.scripting.api;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * the {@code world.net.*} facade routes to the
 * provider and enforces item capability gating.
 */
final class NetApiTest {

    /** Implements the interface's abstract methods trivially; net stays default. */
    private static class BaseProvider implements ItemWorldApiProvider {
        @Override public List<Map<String, Object>> searchKnowledge(String q, int l) { return List.of(); }
        @Override public Map<String, Object> readKnowledgeChunk(String id) { return Map.of(); }
        @Override public List<Map<String, Object>> webSearch(String q, String t, int l) { return List.of(); }
        @Override public String webFetch(String url, int maxChars) { return "?"; }
        @Override public List<Map<String, Object>> queryOracle(String t, String a) { return List.of(); }
        @Override public String llmSummarize(String t, String i) { return ""; }
        @Override public String llmAnalyze(String t, String p) { return ""; }
        @Override public void agentSpeak(String t) { }
        @Override public void agentRemember(String c) { }
        @Override public void agentTell(String target, String message) { }
        @Override public List<Map<String, Object>> inventoryList() { return List.of(); }
        @Override public Map<String, Object> inventoryUse(String id, Map<String, Object> p, int d) { return Map.of(); }
    }

    /** Records the net calls the facade forwards. */
    private static final class RecordingProvider extends BaseProvider {
        final AtomicReference<String> lastCall = new AtomicReference<>();

        @Override public Map<String, Object> netSshRun(String host, String command, Map<String, Object> opts) {
            lastCall.set("ssh:" + host + ":" + command);
            return Map.of("ok", true, "host", host, "exit", 0, "stdout", "done");
        }
        @Override public Map<String, Object> netScpTo(String host, String lp, String rp, Map<String, Object> o) {
            lastCall.set("scpTo:" + host);
            return Map.of("ok", true, "host", host, "direction", "to");
        }
        @Override public Map<String, Object> netHouseholdCopy(String nodeId, String lp, String rp) {
            lastCall.set("household:" + nodeId);
            return Map.of("ok", true, "node", nodeId);
        }
    }

    @Test
    void unrestricted_item_routes_ssh_to_provider() {
        var p = new RecordingProvider();
        var api = new ItemWorldApi(p);   // UNRESTRICTED (JVM-baked)
        var r = api.net.ssh("second-node", "uptime");
        assertEquals(Boolean.TRUE, r.get("ok"));
        assertEquals("ssh:second-node:uptime", p.lastCall.get());
    }

    @Test
    void household_copy_routes_to_provider() {
        var p = new RecordingProvider();
        var api = new ItemWorldApi(p);
        var r = api.net.household_copy("second-node-node", "/a", "/b");
        assertEquals(Boolean.TRUE, r.get("ok"));
        assertEquals("household:second-node-node", p.lastCall.get());
    }

    @Test
    void scp_to_routes_to_provider() {
        var p = new RecordingProvider();
        var api = new ItemWorldApi(p);
        var r = api.net.scp_to("second-node", "/local", "/remote");
        assertEquals(Boolean.TRUE, r.get("ok"));
        assertEquals("scpTo:second-node", p.lastCall.get());
    }

    @Test
    void capability_gate_denies_undeclared_net_ssh() {
        var p = new RecordingProvider();
        // Item declares only net.household — net.ssh must be denied at the facade.
        var caps = ItemCapabilitySet.of(List.of("net.household"));
        var api = new ItemWorldApi(p, caps);
        assertThrows(CapabilityDeniedError.class, () -> api.net.ssh("second-node", "uptime"));
        assertNull(p.lastCall.get(), "denied call must not reach the provider");
        // ...but the declared cap works.
        assertDoesNotThrow(() -> api.net.household_copy("second-node", "/a", "/b"));
    }

    @Test
    void unwired_provider_denies_safely() {
        // A provider that doesn't override the net methods → default "not wired".
        var api = new ItemWorldApi(new BaseProvider());
        var r = api.net.ssh("second-node", "uptime");
        assertEquals(Boolean.FALSE, r.get("ok"));
        assertEquals("deny:unwired", r.get("reason"));
    }
}
