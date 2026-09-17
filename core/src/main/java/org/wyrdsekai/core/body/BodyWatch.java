package org.wyrdsekai.core.body;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The scheduled door of the map. Every few seconds it takes the record's pulse (can the
 * database be opened and asked one thing), reads the host, and ages every entry. The brains
 * heartbeat through the inference router's own health loop; this covers the organs that have
 * no loop of their own.
 *
 * <p>Runs on one daemon thread with nothing to wait for and never touches inference.</p>
 */
public final class BodyWatch {

    private static final Logger log = LoggerFactory.getLogger(BodyWatch.class);

    public static final String RECORD = "store:record";
    public static final String HOST = "env:host";

    private final BodyMap map;
    private final String jdbcUrl;
    private final Path dataDir;
    private final Duration every;
    private final AtomicReference<HostSense.Reading> lastHost = new AtomicReference<>();
    private ScheduledExecutorService exec;
    private volatile ReflexArena arena;
    private final java.util.List<Source> sources = new java.util.concurrent.CopyOnWriteArrayList<>();
    private long pulses;

    /** One part's word on one pulse: who it is, whether it answers, what it says. */
    public record Beat(LimbDescriptor descriptor, boolean alive, String detail) {}

    /**
     * Something that speaks for parts the watch cannot see itself: the mesh's peer nodes, the
     * relay door, a coding backend, the librarian's door. Asked every {@code everyPulses}
     * pulses. A part it stops naming ages out by the clock like any other; a part it names
     * for the first time is attached. A source that throws is skipped for that pulse.
     */
    public record Source(java.util.function.Supplier<java.util.List<Beat>> beats, int everyPulses) {}

    public BodyWatch source(java.util.function.Supplier<java.util.List<Beat>> beats) {
        return source(beats, 1);
    }

    public BodyWatch source(java.util.function.Supplier<java.util.List<Beat>> beats, int everyPulses) {
        sources.add(new Source(beats, Math.max(1, everyPulses)));
        return this;
    }

    /** One fixed part with its own liveness probe. */
    public BodyWatch probe(LimbDescriptor d, java.util.function.BooleanSupplier alive,
                           java.util.function.Supplier<String> detail, int everyPulses) {
        return source(() -> java.util.List.of(new Beat(d, alive.getAsBoolean(),
            detail == null ? null : detail.get())), everyPulses);
    }
    private static volatile BodyWatch current;

    /** The reflex arena runs on this thread, after the map is aged, with nothing to wait for. */
    public BodyWatch withArena(ReflexArena a) {
        this.arena = a;
        return this;
    }

    public ReflexArena arena() { return arena; }

    /** The node's running watch, or null (tests, nodes without a record). */
    public static BodyWatch current() { return current; }

    public BodyWatch(BodyMap map, String jdbcUrl, Path dataDir, Duration every) {
        this.map = map;
        this.jdbcUrl = jdbcUrl;
        this.dataDir = dataDir;
        this.every = every;
    }

    /** Attach the organs this watch speaks for and start the pulse. */
    public BodyWatch start() {
        map.attach(new LimbDescriptor(RECORD, BodyKind.STORE, "record", "household",
            jdbcUrl == null ? null : jdbcUrl.replaceAll("\\?.*$", ""), every, FeltWeight.LOUD,
            "I cannot keep anything new; what happens now may not be remembered", "never"));
        map.attach(new LimbDescriptor(HOST, BodyKind.ENVIRONMENT, "host", "household",
            dataDir == null ? null : dataDir.toString(), every, FeltWeight.PRESENT,
            null, "never"));
        exec = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "body-watch");
            t.setDaemon(true);
            return t;
        });
        exec.scheduleWithFixedDelay(this::pulse, 0, every.toMillis(), TimeUnit.MILLISECONDS);
        current = this;
        log.info("Body watch started (every {}s)", every.toSeconds());
        return this;
    }

    public void stop() {
        if (exec != null) exec.shutdownNow();
        if (current == this) current = null;
    }

    /** The most recent host reading, for the felt line and the gauges. */
    public HostSense.Reading host() {
        return lastHost.get();
    }

    void pulse() {
        try {
            map.heartbeat(RECORD, recordAnswers(), recordDetail());
            var host = HostSense.read(dataDir);
            lastHost.set(host);
            map.heartbeat(HOST, true, host.gauges());
            pulses++;
            for (var src : sources) {
                if (pulses % src.everyPulses() != 0) continue;
                try {
                    var beats = src.beats().get();
                    if (beats == null) continue;
                    for (var b : beats) {
                        if (b == null || b.descriptor() == null) continue;
                        var id = b.descriptor().id();
                        if (map.part(id).isEmpty()) map.attach(b.descriptor());
                        map.heartbeat(id, b.alive(), b.detail());
                    }
                } catch (RuntimeException e) {
                    log.debug("Body watch source failed: {}", e.toString());
                }
            }
            var changed = map.tick(Instant.now());
            if (!changed.isEmpty()) {
                log.info("Body watch: {} part(s) went quiet", changed.size());
            }
            var a = arena;
            if (a != null) a.evaluate(host, map, Instant.now());
        } catch (RuntimeException e) {
            log.debug("Body watch pulse: {}", e.toString());
        }
    }

    private boolean recordAnswers() {
        if (jdbcUrl == null) return false;
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery("SELECT 1")) {
            return rs.next();
        } catch (Exception e) {
            log.warn("Body watch: the record did not answer: {}", e.getMessage());
            return false;
        }
    }

    private String recordDetail() {
        if (dataDir == null) return null;
        try {
            var db = dataDir.resolve("world.db");
            var wal = dataDir.resolve("world.db-wal");
            if (!Files.exists(db)) return null;
            long bytes = Files.size(db) + (Files.exists(wal) ? Files.size(wal) : 0);
            return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1e6);
        } catch (Exception e) {
            return null;
        }
    }
}
