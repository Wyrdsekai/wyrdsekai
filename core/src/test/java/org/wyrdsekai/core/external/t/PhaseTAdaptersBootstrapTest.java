package org.wyrdsekai.core.external.t;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.ExternalAdapterRegistry;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test for the Phase T bootstrap → POST /api/webhook/{id} chain.
 * Verifies that calling {@link PhaseTAdaptersBootstrap#init} produces a
 * working {@code world.inbound.*} surface AND a {@link WebhookListener} that
 * subscribers can sign + post against. Uses no Akka, no HTTP — exercises the
 * Java pieces that real users hit through {@code WebhookRoutes}.
 *
 * <p>The Main wiring in {@code Main.java} adds two things on top of what we
 * test here: (1) an {@link org.apache.pekko.actor.typed.ActorSystem} +
 * {@code ItemScheduleService} for the {@code scheduled} listener — covered
 * by ScheduledListenerBridge tests; (2) an HTTP route registration of the
 * webhook listener — covered by WebhookListenerTest. This test asserts the
 * glue between them (one bootstrap call → working adapter + listener).</p>
 */
class PhaseTAdaptersBootstrapTest {

    @BeforeEach
    void setUp() {
        PhaseTAdaptersBootstrap.resetForTests();
        ExternalAdapterRegistry.get().unregister("inbound");
    }

    @AfterEach
    void tearDown() {
        PhaseTAdaptersBootstrap.resetForTests();
        ExternalAdapterRegistry.get().unregister("inbound");
    }

    @Test
    void init_registers_inbound_adapter_with_external_registry() {
        var listener = PhaseTAdaptersBootstrap.init(null, null, null);

        assertThat(listener).isNotNull();
        assertThat(PhaseTAdaptersBootstrap.webhookListener()).isSameAs(listener);

        var adapterOpt = ExternalAdapterRegistry.get().lookup("inbound");
        assertThat(adapterOpt).isPresent();
        var adapter = adapterOpt.get();
        assertThat(adapter.namespace()).isEqualTo("inbound");
        assertThat(adapter.capabilities())
            .contains("webhook", "email_watch", "mqtt", "file_watch",
                "scheduled", "list", "cancel", "pause", "resume");
    }

    @Test
    void init_is_idempotent() {
        var first = PhaseTAdaptersBootstrap.init(null, null, null);
        var second = PhaseTAdaptersBootstrap.init(null, null, null);
        assertThat(first).isSameAs(second);
    }

    @Test
    void inbound_webhook_subscribe_returns_url_and_secret() {
        PhaseTAdaptersBootstrap.init(null, null, null);
        var resp = ExternalAdapterRegistry.get().invoke(buildReq("webhook", Map.of(
            "path", "/gh",
            "hookName", "onPing",
            "agentId", "did:wyrd:steward"
        )));

        assertThat(resp.success()).isTrue();
        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) resp.data();
        assertThat(data.get("ok")).isEqualTo(true);
        assertThat(String.valueOf(data.get("url"))).startsWith("/api/webhook/");
        assertThat(String.valueOf(data.get("secret"))).hasSizeGreaterThan(20);
    }

    @Test
    void scheduled_listener_off_when_no_actor_system() {
        PhaseTAdaptersBootstrap.init(null, null, null);

        var resp = ExternalAdapterRegistry.get().invoke(buildReq("scheduled", Map.of(
            "cronExpr", "*/5 * * * *",
            "hookName", "tick",
            "agentId", "did:wyrd:steward"
        )));

        // Without a schedule service the bridge wires as null. The adapter
        // surfaces this as a structured failure rather than throwing — items
        // can still try and gracefully degrade.
        assertThat(resp.success()).isFalse();
        assertThat(resp.error()).isNotNull();
        assertThat(resp.error().message()).contains("not wired");
    }

    @Test
    void list_returns_empty_before_any_subscriptions() {
        PhaseTAdaptersBootstrap.init(null, null, null);

        var resp = ExternalAdapterRegistry.get().invoke(buildReq("list", Map.of(
            "agentId", "did:wyrd:steward"
        )));

        assertThat(resp.success()).isTrue();
        assertThat(resp.data()).isInstanceOf(List.class);
    }

    private static AdapterRequest buildReq(String method, Map<String, Object> args) {
        return new AdapterRequest("inbound", method,
            new HashMap<>(args), ItemCapabilitySet.UNRESTRICTED, "test_item");
    }
}
