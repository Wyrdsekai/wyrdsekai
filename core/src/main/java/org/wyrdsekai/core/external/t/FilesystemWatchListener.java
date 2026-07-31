package org.wyrdsekai.core.external.t;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * (Phase T) — sandboxed filesystem watcher.
 *
 * <p>{@code world.inbound.file_watch(relPath, hookName, opts?)} resolves the
 * relative path against the item's home directory ({@code ~/.wyrdsekai/items/{itemId}/})
 * and delivers an {@link InboundEvent} on create / modify / delete events.
 * Path-traversal attempts (via {@code ..} or absolute paths) are rejected at
 * subscribe time so a single bad subscription can't watch outside its sandbox.</p>
 *
 * <p>One watcher thread per (item, root) pair — held in {@link #watchers}.
 * The thread is a virtual daemon so a misbehaving item can't keep the JVM
 * alive past shutdown.</p>
 */
public final class FilesystemWatchListener {

    private static final Logger log = LoggerFactory.getLogger(FilesystemWatchListener.class);

    private final InboundSubscriptionRegistry registry;
    private final InboundDispatchService dispatch;
    /** Item home root resolver — production: {@code ~/.wyrdsekai/items/{itemId}/}. */
    private final Function<String, Path> itemHomeResolver;

    /** Subscription id → running watcher thread + watch service. */
    private final ConcurrentHashMap<String, Watcher> watchers = new ConcurrentHashMap<>();

    public FilesystemWatchListener(InboundSubscriptionRegistry registry,
                                     InboundDispatchService dispatch,
                                     Function<String, Path> itemHomeResolver) {
        this.registry = registry;
        this.dispatch = dispatch;
        this.itemHomeResolver = itemHomeResolver;
    }

    public Map<String, Object> subscribe(String itemId, String agentId, String relPath,
                                           String hookName, Map<String, Object> opts) {
        var itemHome = itemHomeResolver.apply(itemId);
        if (itemHome == null) {
            return Map.of("ok", false, "error", "item home unavailable");
        }
        var resolved = resolveSandboxed(itemHome, relPath);
        if (resolved == null) {
            return Map.of("ok", false, "error", "path traversal rejected");
        }
        try {
            Files.createDirectories(resolved);
        } catch (IOException e) {
            return Map.of("ok", false, "error", "mkdir failed: " + e.getMessage());
        }
        var combined = new LinkedHashMap<String, Object>();
        if (opts != null) combined.putAll(opts);
        combined.put("relPath", relPath == null ? "" : relPath);
        var subId = registry.add(itemId, agentId, "file_watch", hookName,
            resolved.toString(), combined, null,
            opts == null ? null : (opts.get("capPerHour") instanceof Number n ? n.intValue() : null));
        try {
            arm(subId, resolved, allowedKinds(opts));
        } catch (IOException e) {
            registry.cancel(agentId, subId);
            return Map.of("ok", false, "error", "watch start failed: " + e.getMessage());
        }
        return Map.of("ok", true, "subscriptionId", subId, "watching", resolved.toString());
    }

    /** Cancel one watcher (called via registry.cancel as well). */
    public void disarm(String subscriptionId) {
        var w = watchers.remove(subscriptionId);
        if (w != null) w.stop();
    }

    /**
     * Resolve {@code relPath} against {@code itemHome}; reject if the resolved
     * absolute path escapes {@code itemHome} (path-traversal hardening per
     * Phase T test plan).
     */
    static Path resolveSandboxed(Path itemHome, String relPath) {
        if (relPath == null || relPath.isBlank()) return itemHome;
        if (relPath.startsWith("/") || relPath.startsWith("\\")) return null;
        var resolved = itemHome.resolve(relPath).normalize();
        if (!resolved.startsWith(itemHome.normalize())) return null;
        return resolved;
    }

    private List<WatchEvent.Kind<?>> allowedKinds(Map<String, Object> opts) {
        var out = new ArrayList<WatchEvent.Kind<?>>();
        @SuppressWarnings("unchecked")
        var raw = opts == null ? null : (List<String>) opts.get("events");
        if (raw == null || raw.isEmpty()) {
            out.add(StandardWatchEventKinds.ENTRY_CREATE);
            out.add(StandardWatchEventKinds.ENTRY_MODIFY);
            out.add(StandardWatchEventKinds.ENTRY_DELETE);
            return out;
        }
        for (var k : raw) {
            switch (k.toLowerCase()) {
                case "create" -> out.add(StandardWatchEventKinds.ENTRY_CREATE);
                case "modify" -> out.add(StandardWatchEventKinds.ENTRY_MODIFY);
                case "delete" -> out.add(StandardWatchEventKinds.ENTRY_DELETE);
                default -> log.debug("file_watch: unknown event kind {}", k);
            }
        }
        return out;
    }

    private void arm(String subId, Path dir, List<WatchEvent.Kind<?>> kinds) throws IOException {
        var ws = FileSystems.getDefault().newWatchService();
        dir.register(ws, kinds.toArray(WatchEvent.Kind<?>[]::new));
        var thread = Thread.ofVirtual().name("inbound-fs-watch-" + subId)
            .unstarted(() -> watchLoop(subId, ws, dir));
        var w = new Watcher(ws, thread);
        watchers.put(subId, w);
        thread.start();
    }

    private void watchLoop(String subId, WatchService ws, Path dir) {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                WatchKey key;
                try {
                    key = ws.take();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (ClosedWatchServiceException e) {
                    break;
                }
                for (var event : key.pollEvents()) {
                    var ctx = event.context();
                    var relName = ctx == null ? "" : ctx.toString();
                    var kind = event.kind().name();
                    var payload = Map.<String, Object>of(
                        "kind", kind,
                        "name", relName,
                        "dir", dir.toString());
                    dispatch.dispatch(subId, InboundEvent.of("file_watch", dir.toString(), payload));
                }
                key.reset();
            }
        } finally {
            try { ws.close(); } catch (IOException _) {}
        }
    }

    private static final class Watcher {
        final WatchService ws;
        final Thread thread;

        Watcher(WatchService ws, Thread thread) { this.ws = ws; this.thread = thread; }

        void stop() {
            try { ws.close(); } catch (IOException _) {}
            thread.interrupt();
        }
    }
}
