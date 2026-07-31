package org.wyrdsekai.core.room;

import org.wyrdsekai.common.event.WorldEvent;
import org.wyrdsekai.common.model.*;

import java.util.*;

/**
 * Current state of a room, derived from applying events.
 * This is the "left fold" of the event stream.
 */
public record RoomState(
    String roomId,
    String name,
    String description,
    String zone,
    List<String> aliases,
    Map<String, Exit> exits,
    Map<String, Entity> entities,
    Map<String, RoomObject> objects,
    List<Hint> hints,
    Map<String, String> properties
) {

    /** Backward-compatible constructor — no aliases. */
    public RoomState(String roomId, String name, String description, String zone,
                     Map<String, Exit> exits, Map<String, Entity> entities,
                     Map<String, RoomObject> objects, List<Hint> hints,
                     Map<String, String> properties) {
        this(roomId, name, description, zone, List.of(), exits, entities, objects, hints, properties);
    }

    public static RoomState empty(String roomId) {
        return new RoomState(roomId, "", "", "", List.of(), Map.of(), Map.of(), Map.of(), List.of(), Map.of());
    }

    /** Apply a world event to produce a new state. */
    public RoomState apply(WorldEvent event) {
        return switch (event) {
            case WorldEvent.RoomCreated e -> new RoomState(
                roomId, e.name(), e.description(), e.zone(),
                e.aliases() != null ? e.aliases() : List.of(),
                exits, entities, objects, hints, properties);

            case WorldEvent.EntityEntered e -> {
                var newEntities = new HashMap<>(entities);
                newEntities.put(e.entityId(),
                    new Entity(e.entityId(), e.entityName(), e.entityType(),
                        e.description() != null ? e.description() : ""));
                yield new RoomState(roomId, name, description, zone, aliases,
                    exits, Map.copyOf(newEntities), objects, hints, properties);
            }

            case WorldEvent.EntityLeft e -> {
                var newEntities = new HashMap<>(entities);
                newEntities.remove(e.entityId());
                yield new RoomState(roomId, name, description, zone, aliases,
                    exits, Map.copyOf(newEntities), objects, hints, properties);
            }

            case WorldEvent.ObjectTaken e -> {
                var newObjects = new HashMap<>(objects);
                newObjects.remove(e.objectId());
                yield new RoomState(roomId, name, description, zone, aliases,
                    exits, entities, Map.copyOf(newObjects), hints, properties);
            }

            case WorldEvent.ObjectDropped e -> {
                var newObjects = new HashMap<>(objects);
                newObjects.put(e.objectId(),
                    new RoomObject(e.objectId(), e.objectName(), e.description(), e.takeable()));
                yield new RoomState(roomId, name, description, zone, aliases,
                    exits, entities, Map.copyOf(newObjects), hints, properties);
            }

            case WorldEvent.ExitOpened e -> {
                var newExits = new HashMap<>(exits);
                newExits.put(e.direction(),
                    new Exit(e.direction(), e.targetRoom(), e.label()));
                yield new RoomState(roomId, name, description, zone, aliases,
                    Map.copyOf(newExits), entities, objects, hints, properties);
            }

            case WorldEvent.ExitClosed e -> {
                var newExits = new HashMap<>(exits);
                newExits.remove(e.direction());
                yield new RoomState(roomId, name, description, zone, aliases,
                    Map.copyOf(newExits), entities, objects, hints, properties);
            }

            case WorldEvent.DescriptionChanged e -> new RoomState(
                roomId, name, e.newDescription(), zone, aliases,
                exits, entities, objects, hints, properties);

            case WorldEvent.HintsUpdated e -> new RoomState(
                roomId, name, description, zone, aliases,
                exits, entities, objects, List.copyOf(e.hints()), properties);

            case WorldEvent.PropertyChanged e -> {
                var newProps = new HashMap<>(properties);
                if (e.newValue() == null) {
                    newProps.remove(e.key());
                } else {
                    newProps.put(e.key(), e.newValue());
                }
                yield new RoomState(roomId, name, description, zone, aliases,
                    exits, entities, objects, hints, Map.copyOf(newProps));
            }

            case WorldEvent.ObjectAdded e -> {
                var newObjects = new HashMap<>(objects);
                // preserve state map from the event so
                // furnishings seeded with {sittable: true} (or any other flag)
                // get their state through CreateRoom → ObjectAdded → RoomState.
                var stateMap = e.state() == null ? Map.<String,String>of() : e.state();
                var objAliases = e.aliases() == null ? List.<String>of() : e.aliases();
                newObjects.put(e.objectId(),
                    new RoomObject(e.objectId(), e.objectName(), e.description(),
                        e.takeable(), true, false, objAliases, stateMap));
                yield new RoomState(roomId, name, description, zone, aliases,
                    exits, entities, Map.copyOf(newObjects), hints, properties);
            }

            // Events that don't change state (Said, ObjectUsed, ScriptTriggered, etc.)
            case WorldEvent.Said ignored -> this;
            case WorldEvent.ObjectUsed ignored -> this;
            case WorldEvent.ScriptTriggered ignored -> this;
            case WorldEvent.Whispered ignored -> this;
            case WorldEvent.Told ignored -> this;
            case WorldEvent.VitalitySuggested ignored -> this;
            case WorldEvent.Emoted ignored -> this;
            case WorldEvent.EntityTraveling ignored -> this;
            case WorldEvent.EntityReturned ignored -> this;
            // posture lives on the Entity record. Apply updates
            // the entity's posture so journal replay restores it after actor restart.
            // No-op if the entity is no longer in the room (left during the scene).
            case WorldEvent.PostureChanged e -> {
                var entity = entities.get(e.entityId());
                if (entity == null) yield this;
                var newEntities = new HashMap<>(entities);
                newEntities.put(e.entityId(), entity.withPosture(e.current()));
                yield new RoomState(roomId, name, description, zone, aliases,
                    exits, Map.copyOf(newEntities), objects, hints, properties);
            }
            // LookedAt is a perception event; the look does not
            // change room aggregate state. Scripts and scene-detectors consume it.
            case WorldEvent.LookedAt ignored -> this;
            // AmbientChanged narrates a room-property shift.
            // The underlying property is already mutated via PropertyChanged on the
            // emitting script side (when scripts care); the ambient event carries the
            // narration descriptor. No additional aggregate mutation here.
            case WorldEvent.AmbientChanged ignored -> this;
        };
    }

    /** Convert current state to a snapshot for the wire protocol. */
    public RoomSnapshot toSnapshot() {
        return new RoomSnapshot(
            roomId, name, description, zone,
            aliases != null ? aliases : List.of(),
            List.copyOf(exits.values()),
            List.copyOf(entities.values()),
            List.copyOf(objects.values()),
            hints
        );
    }
}
