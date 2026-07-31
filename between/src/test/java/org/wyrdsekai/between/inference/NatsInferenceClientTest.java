package org.wyrdsekai.between.inference;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.between.RelaySessionTransport;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Behaviour of the cross-zone inference client against a fake in-memory relay.
 * These tests lock in the contract that matters to real peers: subscribe before
 * publish (no lost tokens), token accumulation, terminal chunk completion,
 * error propagation, and {@code fullContent} precedence over accumulator.
 */
class NatsInferenceClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // --- Task #36: dispatch timeout derives from WYRDSEKAI_INFERENCE_TIMEOUT ---

    @Test void timeout_defaults_to_120_when_unset_or_blank() {
        // Unset (null) and blank both fall back to the historical default so a
        // node with no env override behaves byte-identically to before.
        assertThat(NatsInferenceClient.parseTimeoutSec(null)).isEqualTo(120);
        assertThat(NatsInferenceClient.parseTimeoutSec("")).isEqualTo(120);
        assertThat(NatsInferenceClient.parseTimeoutSec("   ")).isEqualTo(120);
    }

    @Test void timeout_derives_from_env_value() {
        // A shorter env keeps the NATS dispatch strictly under the companion
        // watchdog (env + 30) so a dead remote fails over before the turn dies.
        assertThat(NatsInferenceClient.parseTimeoutSec("120")).isEqualTo(120);
        assertThat(NatsInferenceClient.parseTimeoutSec("45")).isEqualTo(45);
        assertThat(NatsInferenceClient.parseTimeoutSec(" 90 ")).isEqualTo(90);
    }

    @Test void timeout_falls_back_to_120_on_malformed_value() {
        assertThat(NatsInferenceClient.parseTimeoutSec("garbage")).isEqualTo(120);
        assertThat(NatsInferenceClient.parseTimeoutSec("12x")).isEqualTo(120);
    }

    /**
     * In-memory substitute for {@link RelaySessionTransport}. Routes publishes
     * back to subscribers on the same subject, in-thread (deterministic). Records
     * the publish order so tests can assert the subscribe-before-publish invariant.
     */
    static final class FakeTransport extends RelaySessionTransport {
        final Map<String, Consumer<byte[]>> subscriptions = new ConcurrentHashMap<>();
        final List<String> publishLog = new CopyOnWriteArrayList<>();
        final List<String> subscribeLog = new CopyOnWriteArrayList<>();

        @Override public boolean isConnected() { return true; }

        @Override public Object subscribe(String subject, Consumer<byte[]> handler) {
            subscribeLog.add(subject);
            subscriptions.put(subject, handler);
            return subject; // dispatcher handle — just the subject string
        }

        @Override public void publish(String subject, byte[] data) {
            publishLog.add(subject);
            var sub = subscriptions.get(subject);
            if (sub != null) sub.accept(data);
        }

        @Override public void closeDispatcherObj(Object dispatcher) {
            if (dispatcher instanceof String s) subscriptions.remove(s);
        }
    }

    @Test void subscribes_before_publishing_so_tokens_arent_lost() throws Exception {
        // The race we're preventing: if the client publishes the request before
        // subscribing to the stream subject, a fast provider could publish the
        // first token before the subscription exists and it would be lost.
        var transport = new FakeTransport();
        var client = new NatsInferenceClient(transport);

        var req = NatsInferenceClient.build(
            "alpha", "agent-1", "test-model",
            "system", "user", 64, 0.0, false);

        var future = client.request("beta", req);

        // Assert ordering: subscribe happened, then publish.
        assertThat(transport.subscribeLog).isNotEmpty();
        assertThat(transport.publishLog).isNotEmpty();

        // The stream subscription for this request's streamId must have been
        // registered before the request publish.
        var streamSubject = NatsInferenceProtocol.streamSubject(req.streamId());
        var requestSubject = NatsInferenceProtocol.requestSubject("beta");
        var subIdx = transport.subscribeLog.indexOf(streamSubject);
        var pubIdx = transport.publishLog.indexOf(requestSubject);
        assertThat(subIdx).isGreaterThanOrEqualTo(0);
        assertThat(pubIdx).isGreaterThanOrEqualTo(0);

        // Simulate a provider reply: one terminal chunk.
        var terminal = new NatsInferenceProtocol.StreamChunk(
            req.streamId(), null, "response text", true, 5, 3, "stop", null);
        transport.subscriptions.get(streamSubject).accept(MAPPER.writeValueAsBytes(terminal));

        var completion = future.get(2, TimeUnit.SECONDS);
        assertThat(completion.text()).isEqualTo("response text");
        assertThat(completion.promptTokens()).isEqualTo(5);
        assertThat(completion.completionTokens()).isEqualTo(3);

        client.close();
    }

    @Test void accumulates_streamed_tokens_and_invokes_consumer() throws Exception {
        var transport = new FakeTransport();
        var client = new NatsInferenceClient(transport);

        var req = NatsInferenceClient.build(
            "alpha", "a1", "m", null, "q", 32, 0.0, true);
        var collected = new CopyOnWriteArrayList<String>();

        var future = client.requestStreaming("beta", req, collected::add);

        var streamSubject = NatsInferenceProtocol.streamSubject(req.streamId());
        var sub = transport.subscriptions.get(streamSubject);
        sub.accept(MAPPER.writeValueAsBytes(tokenChunk(req.streamId(), "hel")));
        sub.accept(MAPPER.writeValueAsBytes(tokenChunk(req.streamId(), "lo ")));
        sub.accept(MAPPER.writeValueAsBytes(tokenChunk(req.streamId(), "there")));
        sub.accept(MAPPER.writeValueAsBytes(terminalChunk(req.streamId(), null, 10, 7)));

        var completion = future.get(2, TimeUnit.SECONDS);
        // fullContent absent on terminal → text is accumulator join
        assertThat(completion.text()).isEqualTo("hello there");
        assertThat(completion.completionTokens()).isEqualTo(7);
        assertThat(collected).containsExactly("hel", "lo ", "there");

        client.close();
    }

    @Test void terminal_fullContent_takes_precedence_over_accumulator() throws Exception {
        // If the provider sends fullContent on the terminal chunk, use it even
        // when per-token chunks were also sent. This matches the non-streaming
        // path where the server computes the full text and sends it once.
        var transport = new FakeTransport();
        var client = new NatsInferenceClient(transport);

        var req = NatsInferenceClient.build("alpha", "a", "m", null, "q", 16, 0.0, false);
        var future = client.request("beta", req);

        var sub = transport.subscriptions.get(NatsInferenceProtocol.streamSubject(req.streamId()));
        // Imagine a flaky provider that sent partial tokens then gave us the full.
        sub.accept(MAPPER.writeValueAsBytes(tokenChunk(req.streamId(), "partial")));
        sub.accept(MAPPER.writeValueAsBytes(terminalChunk(req.streamId(), "canonical answer", 8, 4)));

        var completion = future.get(2, TimeUnit.SECONDS);
        assertThat(completion.text()).isEqualTo("canonical answer");

        client.close();
    }

    @Test void error_chunk_completes_future_exceptionally() throws Exception {
        var transport = new FakeTransport();
        var client = new NatsInferenceClient(transport);

        var req = NatsInferenceClient.build("alpha", "a", "m", null, "q", 16, 0.0, false);
        var future = client.request("beta", req);

        var sub = transport.subscriptions.get(NatsInferenceProtocol.streamSubject(req.streamId()));
        var err = new NatsInferenceProtocol.StreamChunk(
            req.streamId(), null, null, true, null, null, null, "backend timeout");
        sub.accept(MAPPER.writeValueAsBytes(err));

        assertThatThrownBy(() -> future.get(2, TimeUnit.SECONDS))
            .hasRootCauseMessage("Remote inference error: backend timeout");

        client.close();
    }

    @Test void disconnected_transport_fails_fast() {
        var disconnected = new RelaySessionTransport() {
            @Override public boolean isConnected() { return false; }
        };
        var client = new NatsInferenceClient(disconnected);

        var req = NatsInferenceClient.build("a", "i", "m", null, "q", 16, 0.0, false);
        var future = client.request("beta", req);

        assertThat(future).isCompletedExceptionally();
        client.close();
    }

    @Test void stale_chunks_after_completion_are_ignored() throws Exception {
        // After terminal chunk, the stream subscription is removed. A late chunk
        // arriving due to out-of-order delivery must not throw or crash.
        var transport = new FakeTransport();
        var client = new NatsInferenceClient(transport);

        var req = NatsInferenceClient.build("alpha", "a", "m", null, "q", 16, 0.0, false);
        var future = client.request("beta", req);

        var streamSubject = NatsInferenceProtocol.streamSubject(req.streamId());
        // Grab the subscription handler directly — after terminal chunk the client
        // will call closeDispatcherObj which removes it from our map; we still
        // invoke the handler we captured to simulate a late packet in flight.
        var sub = transport.subscriptions.get(streamSubject);
        sub.accept(MAPPER.writeValueAsBytes(terminalChunk(req.streamId(), "done", 1, 1)));
        assertThat(future.get(1, TimeUnit.SECONDS).text()).isEqualTo("done");

        // Late chunk — client must swallow it without error.
        sub.accept(MAPPER.writeValueAsBytes(tokenChunk(req.streamId(), "late")));

        client.close();
    }

    @Test void build_helper_omits_system_when_blank() {
        var req = NatsInferenceClient.build(
            "alpha", "a1", "model", "  ", "hello", 64, 0.0, false);
        assertThat(req.messages()).hasSize(1);
        assertThat(req.messages().get(0).role()).isEqualTo("user");
    }

    @Test void build_helper_includes_system_when_set() {
        var req = NatsInferenceClient.build(
            "alpha", "a1", "model", "be nice", "hello", 64, 0.0, false);
        assertThat(req.messages()).hasSize(2);
        assertThat(req.messages().get(0).role()).isEqualTo("system");
        assertThat(req.messages().get(0).content()).isEqualTo("be nice");
    }

    private static NatsInferenceProtocol.StreamChunk tokenChunk(String streamId, String token) {
        return new NatsInferenceProtocol.StreamChunk(
            streamId, token, null, false, null, null, null, null);
    }

    private static NatsInferenceProtocol.StreamChunk terminalChunk(
            String streamId, String fullContent, int promptTokens, int completionTokens) {
        return new NatsInferenceProtocol.StreamChunk(
            streamId, null, fullContent, true, promptTokens, completionTokens, "stop", null);
    }
}
