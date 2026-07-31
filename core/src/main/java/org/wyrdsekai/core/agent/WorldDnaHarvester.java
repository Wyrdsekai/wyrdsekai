package org.wyrdsekai.core.agent;

import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;
import org.apache.pekko.actor.typed.javadsl.TimerScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.util.Json;
import org.wyrdsekai.core.persistence.RoomMetadataService;
import org.wyrdsekai.core.persistence.WorldDnaService;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Periodically harvests creation patterns and records them in World DNA.
 * M0 scope: records room_design patterns when new rooms are discovered.
 * Scoring and outcome correlation deferred to M1.
 */
public class WorldDnaHarvester extends AbstractBehavior<WorldDnaHarvester.Command> {

    private static final Logger log = LoggerFactory.getLogger(WorldDnaHarvester.class);
    private static final String HARVEST_TIMER = "harvest";

    public sealed interface Command {}
    private record HarvestTick() implements Command {}

    /** Record an interaction pattern for DNA harvesting. */
    public record RecordInteraction(String patternType, String patternData,
                                     String roomId, String agentId, String zoneId) implements Command {}

    /** Record a positive/negative outcome for a pattern. */
    public record RecordOutcome(String patternId, boolean positive) implements Command {}

    private final WorldDnaService dnaService;
    private final RoomMetadataService metadataService;
    private final Set<String> knownRoomIds = new HashSet<>();

    private WorldDnaHarvester(ActorContext<Command> context,
                               TimerScheduler<Command> timers,
                               WorldDnaService dnaService,
                               RoomMetadataService metadataService,
                               Duration harvestInterval) {
        super(context);
        this.dnaService = dnaService;
        this.metadataService = metadataService;

        // Initialize known rooms (don't re-harvest existing rooms)
        for (var room : metadataService.listRooms()) {
            knownRoomIds.add(room.roomId());
        }

        timers.startTimerWithFixedDelay(HARVEST_TIMER,
            new HarvestTick(), harvestInterval);

        log.info("WorldDnaHarvester started — {} existing rooms tracked, interval={}",
            knownRoomIds.size(), harvestInterval);
    }

    public static Behavior<Command> create(WorldDnaService dnaService,
                                            RoomMetadataService metadataService,
                                            Duration harvestInterval) {
        return Behaviors.setup(ctx -> Behaviors.withTimers(timers ->
            new WorldDnaHarvester(ctx, timers, dnaService, metadataService, harvestInterval)));
    }

    public static Behavior<Command> create(WorldDnaService dnaService,
                                            RoomMetadataService metadataService) {
        return create(dnaService, metadataService, Duration.ofMinutes(5));
    }

    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
            .onMessage(HarvestTick.class, this::onHarvestTick)
            .onMessage(RecordInteraction.class, this::onRecordInteraction)
            .onMessage(RecordOutcome.class, this::onRecordOutcome)
            .build();
    }

    private Behavior<Command> onHarvestTick(HarvestTick tick) {
        try {
            var allRooms = metadataService.listRooms();
            int newPatterns = 0;

            for (var room : allRooms) {
                if (knownRoomIds.contains(room.roomId())) continue;

                // New room discovered — record room_design pattern
                knownRoomIds.add(room.roomId());

                Map<String, Object> patternData = new HashMap<>();
                patternData.put("name", room.name());
                patternData.put("zone", room.zone());
                patternData.put("createdBy", room.createdBy());

                try {
                    var json = Json.mapper().writeValueAsString(patternData);
                    dnaService.record("room_design", json,
                        room.roomId(), room.createdBy(), room.zone());
                    newPatterns++;
                } catch (Exception e) {
                    log.warn("Failed to record pattern for room {}: {}",
                        room.roomId(), e.getMessage());
                }
            }

            if (newPatterns > 0) {
                log.info("Harvested {} new room_design pattern(s), total tracked: {}",
                    newPatterns, knownRoomIds.size());
            }
        } catch (Exception e) {
            log.warn("Harvest tick failed: {}", e.getMessage());
        }

        return this;
    }

    private Behavior<Command> onRecordInteraction(RecordInteraction cmd) {
        try {
            dnaService.record(cmd.patternType(), cmd.patternData(),
                cmd.roomId(), cmd.agentId(), cmd.zoneId());
            log.debug("Recorded {} pattern from room {} by agent {}",
                cmd.patternType(), cmd.roomId(), cmd.agentId());
        } catch (Exception e) {
            log.warn("Failed to record interaction pattern: {}", e.getMessage());
        }
        return this;
    }

    private Behavior<Command> onRecordOutcome(RecordOutcome cmd) {
        try {
            dnaService.recordOutcome(cmd.patternId(), cmd.positive());
            log.debug("Recorded {} outcome for pattern {}",
                cmd.positive() ? "positive" : "negative", cmd.patternId());
        } catch (Exception e) {
            log.warn("Failed to record outcome for pattern {}: {}",
                cmd.patternId(), e.getMessage());
        }
        return this;
    }
}
