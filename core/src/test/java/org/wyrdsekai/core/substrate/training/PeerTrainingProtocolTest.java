package org.wyrdsekai.core.substrate.training;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.substrate.DeepSleepTrainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wire-protocol tests for {@link PeerTrainingProtocol} + end-to-end
 * round-trip via a {@link FakeTransport} that mimics NATS request-reply
 * + topic broadcast in-memory. These don't need a running NATS or a
 * peer node — they prove the executor and the peer service agree on
 * the protocol shape and assemble adapter chunks correctly.
 */
class PeerTrainingProtocolTest {

    // ── Record round-trip ───────────────────────────────────────────────

    @Test
    void request_jackson_roundtrip() {
        var orig = new PeerTrainingProtocol.Request(
            "req-1", "home-server", "did:wyrd:wyrd", "Wyrd",
            "/models/foo", List.of(Map.of("system", "s", "user", "u", "assistant", "a")),
            500);
        var bytes = PeerTrainingProtocol.encode(orig);
        var decoded = PeerTrainingProtocol.decode(bytes, PeerTrainingProtocol.Request.class);
        assertThat(decoded.requestId()).isEqualTo("req-1");
        assertThat(decoded.submitterNodeId()).isEqualTo("home-server");
        assertThat(decoded.corpus()).hasSize(1);
        assertThat(decoded.maxIters()).isEqualTo(500);
    }

    @Test
    void response_ok_roundtrip() {
        var orig = new PeerTrainingProtocol.Response(
            "req-1", "ok", "training complete", 24, 21_000_000L, "abcdef");
        var bytes = PeerTrainingProtocol.encode(orig);
        var decoded = PeerTrainingProtocol.decode(bytes, PeerTrainingProtocol.Response.class);
        assertThat(decoded.ok()).isTrue();
        assertThat(decoded.adapterChunkCount()).isEqualTo(24);
        assertThat(decoded.adapterTotalBytes()).isEqualTo(21_000_000L);
        assertThat(decoded.adapterSha256()).isEqualTo("abcdef");
    }

    @Test
    void response_fail_omits_adapter_fields() {
        var orig = new PeerTrainingProtocol.Response(
            "req-1", "fail", "OOM", null, null, null);
        var bytes = PeerTrainingProtocol.encode(orig);
        // JSON should not contain the null fields (NON_NULL inclusion).
        var json = new String(bytes);
        assertThat(json).doesNotContain("adapterChunkCount");
        assertThat(json).doesNotContain("adapterTotalBytes");
        assertThat(json).doesNotContain("adapterSha256");
        var decoded = PeerTrainingProtocol.decode(bytes, PeerTrainingProtocol.Response.class);
        assertThat(decoded.ok()).isFalse();
        assertThat(decoded.detail()).isEqualTo("OOM");
    }

    @Test
    void chunk_roundtrip_preserves_bytes() {
        var data = new byte[1024];
        for (int i = 0; i < data.length; i++) data[i] = (byte) (i & 0xff);
        var orig = new PeerTrainingProtocol.AdapterChunk("req-1", 7, data);
        var bytes = PeerTrainingProtocol.encode(orig);
        var decoded = PeerTrainingProtocol.decode(bytes, PeerTrainingProtocol.AdapterChunk.class);
        assertThat(decoded.seq()).isEqualTo(7);
        assertThat(decoded.data()).isEqualTo(data);
    }

    // ── Subject namespacing ────────────────────────────────────────────

    @Test
    void subject_namespace_includes_peer_node_id() {
        assertThat(PeerTrainingProtocol.requestSubject("gpu-host"))
            .isEqualTo("wyrdsekai.training.peer.gpu-host.request");
        assertThat(PeerTrainingProtocol.adapterChunkSubject("gpu-host", "req-1", 3))
            .isEqualTo("wyrdsekai.training.peer.gpu-host.adapter.req-1.chunk.3");
        assertThat(PeerTrainingProtocol.adapterChunkWildcard("gpu-host", "req-1"))
            .isEqualTo("wyrdsekai.training.peer.gpu-host.adapter.req-1.chunk.*");
    }

    // ── End-to-end via FakeTransport ───────────────────────────────────

    @Test
    void executor_skips_when_no_transport_available(@TempDir Path tmp) {
        // Default-arity Context leaves transport=null; executor should
        // SKIP cleanly rather than NPE.
        var strat = new TrainingStrategy.PeerDelegated("gpu-host", "alpha");
        var ctx = new TrainingExecutor.Context(
            new DeepSleepTrainer.NoOpInferenceController(), tmp);
        var executor = TrainingExecutor.Factory.forStrategy(strat, ctx);
        var result = executor.execute("did:wyrd:x", "X", "/m", tmp,
            List.of(Map.of("system", "s", "user", "u", "assistant", "a")));
        assertThat(result.outcome())
            .isEqualTo(DeepSleepTrainer.Outcome.SKIPPED_NO_BACKEND);
    }

    @Test
    void executor_skips_when_peer_does_not_respond(@TempDir Path tmp) {
        var transport = new FakeTransport();
        // No peer subscriber installed — request times out.
        var strat = new TrainingStrategy.PeerDelegated("gpu-host", "alpha");
        var ctx = new TrainingExecutor.Context(
            new DeepSleepTrainer.NoOpInferenceController(), tmp,
            transport, "home-server");
        var executor = TrainingExecutor.Factory.forStrategy(strat, ctx);

        // Override the FakeTransport's request-reply timeout-from-ms to 100
        // by configuring it that way (FakeTransport returns empty if no
        // handler subscribes within ~100ms).
        transport.setNoSubscriberDelay(Duration.ofMillis(100));

        var result = executor.execute("did:wyrd:x", "X", "/m", tmp,
            List.of(Map.of("system", "s", "user", "u", "assistant", "a")));
        assertThat(result.outcome())
            .isEqualTo(DeepSleepTrainer.Outcome.SKIPPED_NO_BACKEND);
        assertThat(result.detail()).contains("no response");
    }

    @Test
    void executor_completes_full_roundtrip_via_fake_peer(@TempDir Path tmp) throws Exception {
        var transport = new FakeTransport();

        // Stand up a fake peer that "trains" by returning a deterministic
        // adapter blob in 3 chunks.
        var fakeAdapter = new byte[3 * 256];
        for (int i = 0; i < fakeAdapter.length; i++) fakeAdapter[i] = (byte) (i & 0xff);
        installFakePeer(transport, "gpu-host", fakeAdapter, /*chunkSize*/ 256);

        var strat = new TrainingStrategy.PeerDelegated("gpu-host", "alpha");
        var ctx = new TrainingExecutor.Context(
            new DeepSleepTrainer.NoOpInferenceController(), tmp,
            transport, "home-server");
        var executor = TrainingExecutor.Factory.forStrategy(strat, ctx);

        var result = executor.execute("did:wyrd:wyrd", "Wyrd", "/models/foo", tmp,
            List.of(Map.of("system", "s", "user", "u", "assistant", "a")));

        assertThat(result.outcome()).isEqualTo(DeepSleepTrainer.Outcome.COMPLETED);
        assertThat(result.adapterPath()).isNotNull();
        assertThat(Files.exists(result.adapterPath())).isTrue();
        assertThat(Files.readAllBytes(result.adapterPath())).isEqualTo(fakeAdapter);
    }

    @Test
    void executor_fails_on_sha256_mismatch(@TempDir Path tmp) throws Exception {
        // Peer claims a SHA-256 that doesn't match what it actually shipped.
        var transport = new FakeTransport();
        var blob = new byte[256];
        installFakePeerWithFakeSha(transport, "gpu-host", blob, "deadbeef");

        var ctx = new TrainingExecutor.Context(
            new DeepSleepTrainer.NoOpInferenceController(), tmp,
            transport, "home-server");
        var executor = TrainingExecutor.Factory.forStrategy(
            new TrainingStrategy.PeerDelegated("gpu-host", "alpha"), ctx);

        var result = executor.execute("did:wyrd:x", "X", "/m", tmp,
            List.of(Map.of("system", "s", "user", "u", "assistant", "a")));

        assertThat(result.outcome()).isEqualTo(DeepSleepTrainer.Outcome.FAILED);
        assertThat(result.detail()).contains("SHA-256 mismatch");
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    /** Install a fake peer that responds ok and ships {@code adapter} in chunks. */
    private void installFakePeer(FakeTransport transport, String peerNodeId,
                                  byte[] adapter, int chunkSize) {
        installPeerHandler(transport, peerNodeId, (request, reply) -> {
            var actualSha = PeerDelegatedExecutor.sha256Hex(adapter);
            var chunkCount = (int) Math.ceil((double) adapter.length / chunkSize);
            var resp = new PeerTrainingProtocol.Response(
                request.requestId(), "ok", "ok",
                chunkCount, (long) adapter.length, actualSha);
            transport.publish(reply, PeerTrainingProtocol.encode(resp));
            for (int seq = 0; seq < chunkCount; seq++) {
                var off = seq * chunkSize;
                var len = Math.min(chunkSize, adapter.length - off);
                var slice = new byte[len];
                System.arraycopy(adapter, off, slice, 0, len);
                var chunk = new PeerTrainingProtocol.AdapterChunk(
                    request.requestId(), seq, slice);
                transport.publish(
                    PeerTrainingProtocol.adapterChunkSubject(
                        peerNodeId, request.requestId(), seq),
                    PeerTrainingProtocol.encode(chunk));
            }
        });
    }

    /** Variant: peer claims a fake SHA so we can prove the executor verifies. */
    private void installFakePeerWithFakeSha(FakeTransport transport, String peerNodeId,
                                             byte[] adapter, String fakeSha) {
        installPeerHandler(transport, peerNodeId, (request, reply) -> {
            var resp = new PeerTrainingProtocol.Response(
                request.requestId(), "ok", "ok",
                1, (long) adapter.length, fakeSha);
            transport.publish(reply, PeerTrainingProtocol.encode(resp));
            var chunk = new PeerTrainingProtocol.AdapterChunk(
                request.requestId(), 0, adapter);
            transport.publish(
                PeerTrainingProtocol.adapterChunkSubject(
                    peerNodeId, request.requestId(), 0),
                PeerTrainingProtocol.encode(chunk));
        });
    }

    private void installPeerHandler(
            FakeTransport transport, String peerNodeId,
            BiConsumer<PeerTrainingProtocol.Request, String> handler) {
        transport.subscribe(
            PeerTrainingProtocol.requestSubject(peerNodeId),
            (subj, replyTo, payload) -> {
                var req = PeerTrainingProtocol.decode(
                    payload, PeerTrainingProtocol.Request.class);
                handler.accept(req, replyTo);
            });
    }

    /**
     * In-memory NATS-shaped transport. Supports request-reply (the
     * subscribed handler receives a reply-to subject and publishes back
     * on it) and broadcast publish/subscribe.
     */
    static final class FakeTransport implements PeerTrainingTransport {
        private final Map<String, List<MessageHandler>> subs = new ConcurrentHashMap<>();
        private volatile Duration noSubscriberDelay = Duration.ofMillis(50);

        void setNoSubscriberDelay(Duration d) { this.noSubscriberDelay = d; }

        @Override
        public Optional<byte[]> requestReply(String subject, byte[] payload, Duration timeout) {
            var replyInbox = "_INBOX." + UUID.randomUUID();
            var latch = new CountDownLatch(1);
            var holder = new byte[1][];
            // Subscribe to the reply inbox first.
            var replySub = subscribe(replyInbox, (s, replyTo, body) -> {
                holder[0] = body;
                latch.countDown();
            });
            // Deliver the request to all subscribers of `subject`.
            var matched = false;
            for (var key : new HashSet<>(subs.keySet())) {
                if (matchesSubject(key, subject)) {
                    for (var h : subs.get(key)) {
                        h.onMessage(subject, replyInbox, payload);
                        matched = true;
                    }
                }
            }
            if (!matched) {
                // No subscriber — wait briefly to mimic a NATS timeout, then fail.
                try { Thread.sleep(noSubscriberDelay.toMillis()); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                replySub.close();
                return Optional.empty();
            }
            try {
                var ok = latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
                replySub.close();
                return ok ? Optional.of(holder[0]) : Optional.empty();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                replySub.close();
                return Optional.empty();
            }
        }

        @Override
        public void publish(String subject, byte[] payload) {
            for (var key : new HashSet<>(subs.keySet())) {
                if (matchesSubject(key, subject)) {
                    for (var h : subs.get(key)) {
                        h.onMessage(subject, null, payload);
                    }
                }
            }
        }

        @Override
        public Subscription subscribe(String subject, MessageHandler handler) {
            subs.computeIfAbsent(subject, k -> new CopyOnWriteArrayList<>()).add(handler);
            return () -> {
                var list = subs.get(subject);
                if (list != null) list.remove(handler);
            };
        }

        /** Minimal NATS subject matcher: only "*" tail wildcard handled. */
        private static boolean matchesSubject(String pattern, String subject) {
            if (pattern.equals(subject)) return true;
            if (pattern.endsWith(".*")) {
                var prefix = pattern.substring(0, pattern.length() - 1);
                return subject.startsWith(prefix);
            }
            return false;
        }
    }
}
