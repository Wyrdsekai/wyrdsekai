package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.protocol.S2CMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the CommandRouter interface contract.
 * Uses a simple in-memory implementation to verify routing behavior.
 */
class CommandRouterTest {

    /** Minimal CommandRouter that records calls and routes by namespace. */
    static class TestRouter implements CommandRouter {
        record Call(String entityId, String namespace, String action,
                    List<String> args, Map<String, String> payload) {}

        final ConcurrentHashMap<String, Consumer<Call>> handlers = new ConcurrentHashMap<>();
        final AtomicReference<Call> lastCall = new AtomicReference<>();

        void registerHandler(String namespace, Consumer<Call> handler) {
            handlers.put(namespace, handler);
        }

        @Override
        public boolean execute(String entityId, String command, List<String> args,
                               Map<String, String> payload, Consumer<S2CMessage> respond) {
            var dot = command.indexOf('.');
            if (dot <= 0 || dot >= command.length() - 1) return false;
            var namespace = command.substring(0, dot);
            var action = command.substring(dot + 1);
            var handler = handlers.get(namespace);
            if (handler == null) return false;

            var call = new Call(entityId, namespace, action, args, payload);
            lastCall.set(call);
            handler.accept(call);

            respond.accept(new S2CMessage.Prose(0, namespace,
                "Handled: " + action, List.of(), null, "normal", null));
            return true;
        }

        @Override
        public Set<String> availableNamespaces() {
            return Set.copyOf(handlers.keySet());
        }
    }

    @Test void routes_to_correct_namespace() {
        var router = new TestRouter();
        var called = new AtomicReference<TestRouter.Call>();
        router.registerHandler("codezaiku", called::set);

        var responded = new AtomicReference<S2CMessage>();
        var routed = router.execute("agent-1", "codezaiku.status",
            List.of(), Map.of(), responded::set);

        assertThat(routed).isTrue();
        assertThat(called.get().namespace()).isEqualTo("codezaiku");
        assertThat(called.get().action()).isEqualTo("status");
        assertThat(called.get().entityId()).isEqualTo("agent-1");
        assertThat(responded.get()).isInstanceOf(S2CMessage.Prose.class);
    }

    @Test void returns_false_for_unknown_namespace() {
        var router = new TestRouter();
        var routed = router.execute("agent-1", "unknown.cmd",
            List.of(), Map.of(), msg -> {});
        assertThat(routed).isFalse();
    }

    @Test void returns_false_for_no_dot_in_command() {
        var router = new TestRouter();
        router.registerHandler("codezaiku", call -> {});
        var routed = router.execute("agent-1", "nodot",
            List.of(), Map.of(), msg -> {});
        assertThat(routed).isFalse();
    }

    @Test void passes_payload_through() {
        var router = new TestRouter();
        router.registerHandler("codezaiku", call -> {});

        router.execute("agent-1", "codezaiku.create",
            List.of(), Map.of("prompt", "hello", "workspace", "/tmp"),
            msg -> {});

        assertThat(router.lastCall.get().payload())
            .containsEntry("prompt", "hello")
            .containsEntry("workspace", "/tmp");
    }

    @Test void available_namespaces_reflects_registered() {
        var router = new TestRouter();
        assertThat(router.availableNamespaces()).isEmpty();

        router.registerHandler("codezaiku", call -> {});
        router.registerHandler("iot", call -> {});
        assertThat(router.availableNamespaces()).containsExactlyInAnyOrder("codezaiku", "iot");
    }

    @Test void multiple_namespaces_route_independently() {
        var router = new TestRouter();
        var cpCalls = new ArrayList<TestRouter.Call>();
        var iotCalls = new ArrayList<TestRouter.Call>();
        router.registerHandler("codezaiku", cpCalls::add);
        router.registerHandler("iot", iotCalls::add);

        router.execute("agent-1", "codezaiku.status", List.of(), Map.of(), msg -> {});
        router.execute("agent-1", "iot.lights", List.of(), Map.of("room", "living"), msg -> {});
        router.execute("agent-1", "codezaiku.create", List.of(), Map.of(), msg -> {});

        assertThat(cpCalls).hasSize(2);
        assertThat(iotCalls).hasSize(1);
        assertThat(cpCalls.get(0).action()).isEqualTo("status");
        assertThat(cpCalls.get(1).action()).isEqualTo("create");
        assertThat(iotCalls.get(0).action()).isEqualTo("lights");
    }

    // --- executeWithPermissions tests ---

    @Test void executeWithPermissions_allowed_routes_normally() {
        var router = new TestRouter();
        router.registerHandler("codezaiku", call -> {});
        var perms = AgentPermissions.unrestricted();

        var responded = new AtomicReference<S2CMessage>();
        var routed = router.executeWithPermissions("agent-1", "codezaiku.status",
            List.of(), Map.of(), responded::set, perms);

        assertThat(routed).isTrue();
        assertThat(responded.get()).isInstanceOf(S2CMessage.Prose.class);
    }

    @Test void executeWithPermissions_denied_returns_error() {
        var router = new TestRouter();
        router.registerHandler("codezaiku", call -> {});
        var perms = AgentPermissions.newAgent(); // read-only

        var responded = new AtomicReference<S2CMessage>();
        var routed = router.executeWithPermissions("agent-1", "codezaiku.create",
            List.of(), Map.of(), responded::set, perms);

        assertThat(routed).isFalse();
        assertThat(responded.get()).isInstanceOf(S2CMessage.Error.class);
        var error = (S2CMessage.Error) responded.get();
        assertThat(error.code()).isEqualTo("permission_denied");
    }

    @Test void executeWithPermissions_null_permissions_skips_check() {
        var router = new TestRouter();
        router.registerHandler("codezaiku", call -> {});

        var responded = new AtomicReference<S2CMessage>();
        var routed = router.executeWithPermissions("agent-1", "codezaiku.create",
            List.of(), Map.of(), responded::set, null);

        // null permissions = no check = routes normally
        assertThat(routed).isTrue();
    }
}
