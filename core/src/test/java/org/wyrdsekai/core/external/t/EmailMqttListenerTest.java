package org.wyrdsekai.core.external.t;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase T tests for EmailPollListener + MqttListener (network-free stubs). */
class EmailMqttListenerTest {

    private InboundSubscriptionRegistry registry;
    private InboundDispatchService dispatch;
    private List<InboundEvent> delivered;
    private ScheduledExecutorService scheduler;

    @BeforeEach
    void setUp() {
        InboundSubscriptionRegistry.resetForTesting();
        InboundDispatchService.resetForTesting();
        registry = InboundSubscriptionRegistry.get(null);
        delivered = new ArrayList<>();
        var stub = new HookCallbackInvoker(null, id -> "function onEvent(){return {ok:true};}",
            (a, b) -> null,
            id -> ItemCapabilitySet.UNRESTRICTED) {
            @Override
            public Map<String, Object> invoke(String itemId, String agentId,
                                                String hookName, InboundEvent event) {
                delivered.add(event);
                return Map.of("ok", true);
            }
        };
        dispatch = InboundDispatchService.init(registry, stub);
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "test-email-poller");
            t.setDaemon(true);
            return t;
        });
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
        InboundSubscriptionRegistry.resetForTesting();
        InboundDispatchService.resetForTesting();
    }

    @Test
    void email_poll_dispatches_each_message() {
        var stubInbox = new ArrayList<EmailPollListener.EmailMessage>();
        stubInbox.add(new EmailPollListener.EmailMessage(
            "1", "alice@example.com", "me@me", "Hello",
            "Hi there", Instant.now(), Map.of()));
        stubInbox.add(new EmailPollListener.EmailMessage(
            "2", "bob@example.com", "me@me", "Pizza?",
            "Tonight?", Instant.now(), Map.of()));

        var listener = new EmailPollListener(registry, dispatch, scheduler,
            itemId -> (filter, lastSeen) -> {
                // First poll returns everything; second poll returns nothing.
                if (lastSeen == null) {
                    var copy = new ArrayList<>(stubInbox);
                    return copy;
                }
                return List.of();
            });
        var res = listener.subscribe("email_companion", "did:wyrd:a",
            Map.of("from", "alice"), "onEmail", Map.of("pollSeconds", 60));
        var subId = String.valueOf(res.get("subscriptionId"));
        assertThat(listener.poll(subId)).isEqualTo(2);
        assertThat(delivered).hasSize(2);
        assertThat(delivered.get(0).kind()).isEqualTo("email");
        assertThat(listener.poll(subId)).isEqualTo(0);
    }

    @Test
    void mqtt_subscribe_then_deliver_test_message() {
        var receivedTopic = new AtomicReference<String>();
        BiConsumer<String, byte[]>[] sink = new BiConsumer[1];
        var fakeClient = new MqttListener.MqttClient() {
            @Override public void subscribe(String topic, BiConsumer<String, byte[]> onMessage) {
                receivedTopic.set(topic);
                sink[0] = onMessage;
            }
            @Override public void unsubscribe(String topic) {}
            @Override public void close() {}
        };
        var listener = new MqttListener(registry, dispatch, broker -> fakeClient);
        var res = listener.subscribe("home_telemetry", "did:wyrd:a",
            "tcp://broker:1883", "home/temp", "onMqtt", null);
        assertThat(res.get("ok")).isEqualTo(true);
        assertThat(receivedTopic.get()).isEqualTo("home/temp");
        var subId = String.valueOf(res.get("subscriptionId"));

        listener.deliverTestMessage(subId, "home/temp", "{\"c\":21.4}".getBytes());
        assertThat(delivered).hasSize(1);
        var event = delivered.get(0);
        assertThat(event.kind()).isEqualTo("mqtt");
        @SuppressWarnings("unchecked")
        var body = (Map<String, Object>) event.payload().get("body");
        assertThat(body).containsEntry("c", 21.4);
    }

    @Test
    void mqtt_rejects_blank_broker_or_topic() {
        var listener = new MqttListener(registry, dispatch, b -> null);
        assertThat(listener.subscribe("x", "did:a", "", "topic", "h", null).get("ok"))
            .isEqualTo(false);
        assertThat(listener.subscribe("x", "did:a", "tcp://b", "", "h", null).get("ok"))
            .isEqualTo(false);
    }
}
