package org.wyrdsekai.core.external.t;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase T fs-watch + sandbox tests. */
class FilesystemWatchListenerTest {

    private InboundSubscriptionRegistry registry;
    private InboundDispatchService dispatch;
    private AtomicInteger deliveredCount;
    private List<InboundEvent> delivered;

    @BeforeEach
    void setUp() {
        InboundSubscriptionRegistry.resetForTesting();
        InboundDispatchService.resetForTesting();
        registry = InboundSubscriptionRegistry.get(null);
        deliveredCount = new AtomicInteger();
        delivered = new ArrayList<>();
        var stub = new HookCallbackInvoker(null, id -> "function onEvent(){return {ok:true};}",
            (a, b) -> null,
            id -> ItemCapabilitySet.UNRESTRICTED) {
            @Override
            public Map<String, Object> invoke(String itemId, String agentId,
                                                String hookName, InboundEvent event) {
                synchronized (delivered) { delivered.add(event); }
                deliveredCount.incrementAndGet();
                return Map.of("ok", true);
            }
        };
        dispatch = InboundDispatchService.init(registry, stub);
    }

    @AfterEach
    void tearDown() {
        InboundSubscriptionRegistry.resetForTesting();
        InboundDispatchService.resetForTesting();
    }

    @Test
    void file_watch_path_traversal_rejected(@TempDir Path tmp) {
        var listener = new FilesystemWatchListener(registry, dispatch, id -> tmp);
        var res = listener.subscribe("x", "did:wyrd:a", "../escape", "onEvent", null);
        assertThat(res.get("ok")).isEqualTo(false);
        assertThat(String.valueOf(res.get("error"))).contains("path traversal");
    }

    @Test
    void file_watch_absolute_path_rejected(@TempDir Path tmp) {
        var listener = new FilesystemWatchListener(registry, dispatch, id -> tmp);
        var res = listener.subscribe("x", "did:wyrd:a", "/etc", "onEvent", null);
        assertThat(res.get("ok")).isEqualTo(false);
    }

    @Test
    void file_watch_resolves_within_item_home(@TempDir Path tmp) throws Exception {
        var listener = new FilesystemWatchListener(registry, dispatch, id -> tmp);
        var res = listener.subscribe("x", "did:wyrd:a", "drop", "onEvent", null);
        assertThat(res.get("ok")).isEqualTo(true);
        assertThat(String.valueOf(res.get("watching"))).contains(tmp.toString());
        var subId = String.valueOf(res.get("subscriptionId"));

        var dropDir = tmp.resolve("drop");
        Files.writeString(dropDir.resolve("hello.txt"), "world");

        // Poll up to 5s for a delivery — file watchers on Linux can take 1-2s.
        var deadline = System.currentTimeMillis() + 5_000;
        while (deliveredCount.get() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(deliveredCount.get()).isGreaterThan(0);
        listener.disarm(subId);
    }

    @Test
    void resolve_sandboxed_with_blank_relpath_returns_root(@TempDir Path tmp) {
        var resolved = FilesystemWatchListener.resolveSandboxed(tmp, "");
        assertThat(resolved).isEqualTo(tmp);
    }

    @Test
    void resolve_sandboxed_normalises_dotdot_inside(@TempDir Path tmp) {
        // a/b/../c → a/c — stays inside, allowed.
        var resolved = FilesystemWatchListener.resolveSandboxed(tmp, "a/b/../c");
        assertThat(resolved).isNotNull();
        assertThat(resolved.startsWith(tmp.normalize())).isTrue();
    }
}
