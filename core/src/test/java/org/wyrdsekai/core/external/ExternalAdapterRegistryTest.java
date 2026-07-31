package org.wyrdsekai.core.external;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ExternalAdapterRegistryTest {

    private ExternalAdapterRegistry registry;

    @BeforeEach
    void setup() {
        registry = ExternalAdapterRegistry.get();
        registry.clearForTests();
    }

    static class FakeAdapter implements ExternalAdapter {
        private final String ns;
        FakeAdapter(String ns) { this.ns = ns; }
        @Override public String namespace() { return ns; }
        @Override public Set<String> capabilities() {
            return Set.of("ping", "echo");
        }
        @Override public String credentialSlot() { return ns + ".token"; }
        @Override public AdapterResponse invoke(AdapterRequest req) {
            if ("ping".equals(req.method())) return AdapterResponse.ok(Map.of("ok", true));
            if ("echo".equals(req.method())) return AdapterResponse.ok(req.args());
            return AdapterResponse.fail("unknown_method", req.method(), false);
        }
    }

    @Test
    void register_and_invoke() {
        registry.register(new FakeAdapter("fakeservice"));
        var resp = registry.invoke(new AdapterRequest(
            "fakeservice", "ping", Map.of(),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertTrue(resp.success());
        assertNotNull(resp.data());
    }

    @Test
    void unknown_namespace_returns_adapter_unavailable() {
        var resp = registry.invoke(new AdapterRequest(
            "ghost", "ping", Map.of(),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
        assertEquals("adapter_unavailable", resp.error().code());
    }

    @Test
    void unknown_method_returns_unknown_method() {
        registry.register(new FakeAdapter("svc"));
        var resp = registry.invoke(new AdapterRequest(
            "svc", "destroy_world", Map.of(),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
        assertEquals("unknown_method", resp.error().code());
    }

    @Test
    void adapter_throws_returns_adapter_threw() {
        registry.register(new ExternalAdapter() {
            @Override public String namespace() { return "explosive"; }
            @Override public Set<String> capabilities() { return Set.of("boom"); }
            @Override public String credentialSlot() { return "explosive.fuse"; }
            @Override public AdapterResponse invoke(AdapterRequest req) {
                throw new RuntimeException("oh no");
            }
        });
        var resp = registry.invoke(new AdapterRequest(
            "explosive", "boom", Map.of(),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertFalse(resp.success());
        assertEquals("adapter_threw", resp.error().code());
        assertTrue(resp.error().retryable());
    }

    @Test
    void replace_registration_logs_warn() {
        registry.register(new FakeAdapter("dup"));
        registry.register(new FakeAdapter("dup"));
        assertEquals(1, registry.namespaces().size());
        assertTrue(registry.namespaces().contains("dup"));
    }

    @Test
    void echo_returns_args_payload() {
        registry.register(new FakeAdapter("echo_svc"));
        var resp = registry.invoke(new AdapterRequest(
            "echo_svc", "echo", Map.of("hello", "world"),
            ItemCapabilitySet.UNRESTRICTED, null));
        assertTrue(resp.success());
        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) resp.data();
        assertEquals("world", data.get("hello"));
    }
}
