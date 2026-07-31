package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.protocol.S2CMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link LocalCommandRouter}. Per
 * covers registration, dispatch
 * malformed input, unknown namespaces, handler exceptions, and
 * re-registration.
 */
class LocalCommandRouterTest {

    private LocalCommandRouter router;

    @BeforeEach
    void setup() {
        LocalCommandRouter.resetForTest();
        router = LocalCommandRouter.get();
    }

    @AfterEach
    void teardown() {
        LocalCommandRouter.resetForTest();
    }

    /** Recording handler — captures the verb + args + payload it receives. */
    private static final class Recorder implements NamespaceHandler {
        String entityId;
        String verb;
        List<String> args;
        Map<String, String> payload;
        int callCount = 0;
        boolean ackOnly = false;

        @Override
        public void dispatch(String entityId, String verb, List<String> args,
                             Map<String, String> payload,
                             Consumer<S2CMessage> respond) {
            this.callCount++;
            this.entityId = entityId;
            this.verb = verb;
            this.args = args;
            this.payload = payload;
            respond.accept(new S2CMessage.Prose(0, "system",
                "ack:" + verb, List.of(), null, "normal", null));
            if (!ackOnly) {
                respond.accept(new S2CMessage.Prose(0, "system",
                    "done:" + verb, List.of(), null, "normal", null));
            }
        }
    }

    @Test
    void singleton_returnsStableInstance() {
        var a = LocalCommandRouter.get();
        var b = LocalCommandRouter.get();
        assertSame(a, b, "singleton must return the same instance");
    }

    @Test
    void register_routesToHandler() {
        var rec = new Recorder();
        router.register("openhands", rec);

        var responses = new ArrayList<S2CMessage>();
        var ok = router.execute("did:wyrd:test", "openhands.create",
            List.of(), Map.of("intent", "explore"), responses::add);

        assertTrue(ok, "execute should return true on a routed command");
        assertEquals("did:wyrd:test", rec.entityId);
        assertEquals("create", rec.verb);
        assertEquals(Map.of("intent", "explore"), rec.payload);
        assertEquals(2, responses.size(), "ack + done responses expected");
    }

    @Test
    void register_rejectsBlankNamespace() {
        var ex = assertThrows(IllegalArgumentException.class,
            () -> router.register("", (eid, verb, args, payload, resp) -> {}));
        assertTrue(ex.getMessage().contains("non-blank"));
    }

    @Test
    void register_rejectsNamespaceWithDot() {
        var ex = assertThrows(IllegalArgumentException.class,
            () -> router.register("openhands.evil",
                (eid, verb, args, payload, resp) -> {}));
        assertTrue(ex.getMessage().contains("dot"));
    }

    @Test
    void register_replacesPriorHandler() {
        var first = new Recorder();
        var second = new Recorder();
        router.register("openhands", first);
        router.register("openhands", second);

        router.execute("did:test", "openhands.run",
            List.of(), Map.of(), msg -> {});
        assertEquals(0, first.callCount);
        assertEquals(1, second.callCount);
    }

    @Test
    void unregister_removesHandler() {
        var rec = new Recorder();
        router.register("openhands", rec);
        router.unregister("openhands");

        var captured = new AtomicReference<S2CMessage>();
        var ok = router.execute("did:test", "openhands.create",
            List.of(), Map.of(), captured::set);

        assertFalse(ok);
        assertInstanceOf(S2CMessage.Error.class, captured.get());
        assertEquals("unknown_namespace", ((S2CMessage.Error) captured.get()).code());
        assertEquals(0, rec.callCount);
    }

    @Test
    void execute_rejectsBlankCommand() {
        var captured = new AtomicReference<S2CMessage>();
        var ok = router.execute("did:test", "", List.of(), Map.of(), captured::set);
        assertFalse(ok);
        var err = (S2CMessage.Error) captured.get();
        assertEquals("malformed_command", err.code());
    }

    @Test
    void execute_rejectsCommandWithoutDot() {
        var captured = new AtomicReference<S2CMessage>();
        var ok = router.execute("did:test", "openhandscreate",
            List.of(), Map.of(), captured::set);
        assertFalse(ok);
        assertEquals("malformed_command",
            ((S2CMessage.Error) captured.get()).code());
    }

    @Test
    void execute_rejectsLeadingDot() {
        var captured = new AtomicReference<S2CMessage>();
        var ok = router.execute("did:test", ".create",
            List.of(), Map.of(), captured::set);
        assertFalse(ok);
        assertEquals("malformed_command",
            ((S2CMessage.Error) captured.get()).code());
    }

    @Test
    void execute_rejectsTrailingDot() {
        var captured = new AtomicReference<S2CMessage>();
        var ok = router.execute("did:test", "openhands.",
            List.of(), Map.of(), captured::set);
        assertFalse(ok);
        assertEquals("malformed_command",
            ((S2CMessage.Error) captured.get()).code());
    }

    @Test
    void execute_unknownNamespaceReportsAvailableSet() {
        router.register("openhands", new Recorder());
        router.register("opencode", new Recorder());

        var captured = new AtomicReference<S2CMessage>();
        var ok = router.execute("did:test", "iot.lights",
            List.of(), Map.of(), captured::set);

        assertFalse(ok);
        var err = (S2CMessage.Error) captured.get();
        assertEquals("unknown_namespace", err.code());
        assertTrue(err.message().contains("openhands")
            && err.message().contains("opencode"),
            "unknown_namespace error should list available namespaces; got: " + err.message());
    }

    @Test
    void execute_handlerExceptionIsCaughtAndReported() {
        router.register("flaky", (eid, verb, args, payload, respond) -> {
            throw new RuntimeException("kaboom");
        });

        var captured = new AtomicReference<S2CMessage>();
        var ok = router.execute("did:test", "flaky.create",
            List.of(), Map.of(), captured::set);

        assertFalse(ok);
        var err = (S2CMessage.Error) captured.get();
        assertEquals("handler_failure", err.code());
        assertTrue(err.message().contains("kaboom"));
    }

    @Test
    void execute_normalisesNullArgsAndPayload() {
        var rec = new Recorder();
        router.register("openhands", rec);

        router.execute("did:test", "openhands.run",
            null, null, msg -> {});

        assertEquals(List.of(), rec.args);
        assertEquals(Map.of(), rec.payload);
    }

    @Test
    void availableNamespaces_reflectsRegistrations() {
        assertTrue(router.availableNamespaces().isEmpty());
        router.register("openhands", new Recorder());
        router.register("opencode", new Recorder());
        assertEquals(Set.of("openhands", "opencode"),
            router.availableNamespaces());
        router.unregister("opencode");
        assertEquals(Set.of("openhands"), router.availableNamespaces());
    }

    @Test
    void hasHandler_matchesRegistration() {
        assertFalse(router.hasHandler("openhands"));
        router.register("openhands", new Recorder());
        assertTrue(router.hasHandler("openhands"));
        router.unregister("openhands");
        assertFalse(router.hasHandler("openhands"));
    }

    @Test
    void executeWithPermissions_deniesWhenNotAllowed() {
        var rec = new Recorder();
        router.register("openhands", rec);

        var perms = new AgentPermissions(List.of()); // no permissions = deny all
        var captured = new AtomicReference<S2CMessage>();
        var ok = router.executeWithPermissions("did:test", "openhands.create",
            List.of(), Map.of(), captured::set, perms);

        assertFalse(ok);
        assertEquals(0, rec.callCount, "denied command should not reach handler");
        var err = (S2CMessage.Error) captured.get();
        assertEquals("permission_denied", err.code());
    }
}
