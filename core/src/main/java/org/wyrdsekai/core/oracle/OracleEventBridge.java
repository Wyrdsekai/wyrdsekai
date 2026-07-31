package org.wyrdsekai.core.oracle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.event.WorldEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * Bridges Wyrdsekai events to oracle-core for prediction analysis.
 *
 * Subscribes to AgentEventStream, converts WorldEvents to OracleEvents,
 * batches them, and sends to oracle-core /v1/ingest periodically.
 *
 * Non-blocking, non-fatal. If oracle-core is down, events are dropped
 * (the Oracle is an enhancement, not a requirement).
 */
public final class OracleEventBridge {

    private static final Logger log = LoggerFactory.getLogger(OracleEventBridge.class);
    private static final int BATCH_SIZE = 50;
    private static final Duration FLUSH_INTERVAL = Duration.ofSeconds(60);

    private final OracleBridge bridge;
    private final String userId;
    private final List<OracleEvent> batch = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService scheduler;
    private volatile boolean running;

    public OracleEventBridge(OracleBridge bridge, String userId) {
        this.bridge = bridge;
        this.userId = userId;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "oracle-event-bridge");
            t.setDaemon(true);
            return t;
        });
    }

    /** Start periodic flushing. */
    public void start() {
        running = true;
        scheduler.scheduleAtFixedRate(
            this::flush,
            FLUSH_INTERVAL.toSeconds(),
            FLUSH_INTERVAL.toSeconds(),
            TimeUnit.SECONDS
        );
        log.info("OracleEventBridge started for user '{}', flush every {}s", userId, FLUSH_INTERVAL.toSeconds());
    }

    /** Stop flushing and drain remaining events. */
    public void stop() {
        running = false;
        flush();
        scheduler.shutdown();
    }

    /**
     * Accept a WorldEvent for oracle-core ingestion.
     * Called from AgentEventStream subscriber.
     */
    public void onWorldEvent(WorldEvent event) {
        if (!running) return;

        var oracleEvent = convertEvent(event);
        if (oracleEvent != null) {
            batch.add(oracleEvent);
            if (batch.size() >= BATCH_SIZE) {
                flush();
            }
        }
    }

    /** Flush the current batch to oracle-core. */
    public void flush() {
        if (batch.isEmpty()) return;

        var toSend = new ArrayList<>(batch);
        batch.clear();

        bridge.ingest(userId, toSend).thenAccept(count -> {
            if (count > 0) {
                log.debug("Flushed {} events to oracle-core", count);
            }
        });
    }

    /** Convert a WorldEvent to an OracleEvent. Returns null if not relevant. */
    private OracleEvent convertEvent(WorldEvent event) {
        return switch (event) {
            case WorldEvent.Said said -> new OracleEvent(
                said.timestamp(), "room_event", "said",
                said.text(), said.entityId(), said.roomId()
            );
            case WorldEvent.EntityEntered entered -> new OracleEvent(
                entered.timestamp(), "room_event", "entity_entered",
                entered.entityName() + " entered from " + entered.fromDirection(),
                entered.entityId(), entered.roomId()
            );
            case WorldEvent.EntityLeft left -> new OracleEvent(
                left.timestamp(), "room_event", "entity_left",
                left.entityName() + " left " + left.direction(),
                left.entityId(), left.roomId()
            );
            case WorldEvent.ObjectUsed used -> new OracleEvent(
                used.timestamp(), "room_event", "object_used",
                "used " + used.objectName(),
                used.entityId(), used.roomId()
            );
            case WorldEvent.PropertyChanged prop -> new OracleEvent(
                prop.timestamp(), "room_event", "property_changed",
                prop.key() + "=" + prop.newValue(),
                "", prop.roomId()
            );
            default -> null; // Skip room creation, object add/drop, etc.
        };
    }
}
