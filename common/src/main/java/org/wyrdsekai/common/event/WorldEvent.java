package org.wyrdsekai.common.event;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import org.wyrdsekai.common.model.Hint;
import org.wyrdsekai.common.model.ImageAttachment;
import org.wyrdsekai.common.model.Posture;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Domain events that occur within rooms.
 * These are persisted in the event journal and drive room state changes.
 * Each event is tagged with roomId (sharding key) and timestamp.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = WorldEvent.RoomCreated.class, name = "room_created"),
    @JsonSubTypes.Type(value = WorldEvent.EntityEntered.class, name = "entity_entered"),
    @JsonSubTypes.Type(value = WorldEvent.EntityLeft.class, name = "entity_left"),
    @JsonSubTypes.Type(value = WorldEvent.Said.class, name = "said"),
    @JsonSubTypes.Type(value = WorldEvent.Told.class, name = "told"),
    @JsonSubTypes.Type(value = WorldEvent.ObjectTaken.class, name = "object_taken"),
    @JsonSubTypes.Type(value = WorldEvent.ObjectDropped.class, name = "object_dropped"),
    @JsonSubTypes.Type(value = WorldEvent.ObjectUsed.class, name = "object_used"),
    @JsonSubTypes.Type(value = WorldEvent.ExitOpened.class, name = "exit_opened"),
    @JsonSubTypes.Type(value = WorldEvent.ExitClosed.class, name = "exit_closed"),
    @JsonSubTypes.Type(value = WorldEvent.DescriptionChanged.class, name = "description_changed"),
    @JsonSubTypes.Type(value = WorldEvent.HintsUpdated.class, name = "hints_updated"),
    @JsonSubTypes.Type(value = WorldEvent.ScriptTriggered.class, name = "script_triggered"),
    @JsonSubTypes.Type(value = WorldEvent.PropertyChanged.class, name = "property_changed"),
    @JsonSubTypes.Type(value = WorldEvent.ObjectAdded.class, name = "object_added"),
    @JsonSubTypes.Type(value = WorldEvent.Whispered.class, name = "whispered"),
    @JsonSubTypes.Type(value = WorldEvent.VitalitySuggested.class, name = "vitality_suggested"),
    @JsonSubTypes.Type(value = WorldEvent.Emoted.class, name = "emoted"),
    @JsonSubTypes.Type(value = WorldEvent.EntityTraveling.class, name = "entity_traveling"),
    @JsonSubTypes.Type(value = WorldEvent.EntityReturned.class, name = "entity_returned"),
    @JsonSubTypes.Type(value = WorldEvent.PostureChanged.class, name = "posture_changed"),
    @JsonSubTypes.Type(value = WorldEvent.LookedAt.class, name = "looked_at"),
    @JsonSubTypes.Type(value = WorldEvent.AmbientChanged.class, name = "ambient_changed"),
})
public sealed interface WorldEvent {

    String roomId();
    Instant timestamp();

    /** A new room was created. */
    record RoomCreated(String roomId, Instant timestamp, String name,
                       String description, String zone,
                       List<String> aliases) implements WorldEvent {
        /** Backward-compatible constructor — no aliases (for existing journal entries). */
        public RoomCreated(String roomId, Instant timestamp, String name,
                           String description, String zone) {
            this(roomId, timestamp, name, description, zone, List.of());
        }
    }

    /** An entity (player or agent) entered the room. */
    record EntityEntered(String roomId, Instant timestamp, String entityId,
                         String entityName, String entityType,
                         String fromDirection, String description) implements WorldEvent {
        /** Backward-compatible constructor without description. */
        public EntityEntered(String roomId, Instant timestamp, String entityId,
                             String entityName, String entityType, String fromDirection) {
            this(roomId, timestamp, entityId, entityName, entityType, fromDirection, "");
        }
    }

    /** An entity left the room. */
    record EntityLeft(String roomId, Instant timestamp, String entityId,
                      String entityName, String direction) implements WorldEvent {}

    /**
     * Someone spoke in the room. Locale tracks the speaker's language preference.
     * Optionally carries image attachments for vision analysis.
     *
     * @param attachments nullable list of image attachments (photos, screenshots)
     */
    record Said(String roomId, Instant timestamp, String entityId,
                String entityName, String text, String locale,
                List<ImageAttachment> attachments) implements WorldEvent {

        /** Backward-compatible constructor — defaults locale to "en", no attachments. */
        public Said(String roomId, Instant timestamp, String entityId,
                    String entityName, String text) {
            this(roomId, timestamp, entityId, entityName, text, "en", null);
        }

        /** Backward-compatible constructor — no attachments. */
        public Said(String roomId, Instant timestamp, String entityId,
                    String entityName, String text, String locale) {
            this(roomId, timestamp, entityId, entityName, text, locale, null);
        }
    }

    /** A private message sent to a specific entity (cross-room). */
    record Told(String roomId, Instant timestamp, String fromEntityId,
                String fromEntityName, String toEntityId, String text,
                String locale) implements WorldEvent {
        public Told(String roomId, Instant timestamp, String fromEntityId,
                    String fromEntityName, String toEntityId, String text) {
            this(roomId, timestamp, fromEntityId, fromEntityName, toEntityId, text, "en");
        }
    }

    /** An object was taken from the room. */
    record ObjectTaken(String roomId, Instant timestamp, String entityId,
                       String objectId, String objectName) implements WorldEvent {}

    /** An object was dropped in the room. */
    record ObjectDropped(String roomId, Instant timestamp, String entityId,
                         String objectId, String objectName,
                         String description, boolean takeable) implements WorldEvent {}

    /** An object was used. */
    record ObjectUsed(String roomId, Instant timestamp, String entityId,
                      String objectId, String objectName, String target,
                      String result) implements WorldEvent {}

    /** A new exit was opened/created. */
    record ExitOpened(String roomId, Instant timestamp, String direction,
                      String targetRoom, String label) implements WorldEvent {}

    /** An exit was closed/removed. */
    record ExitClosed(String roomId, Instant timestamp,
                      String direction) implements WorldEvent {}

    /** Room description changed (by script, agent, or world event). */
    record DescriptionChanged(String roomId, Instant timestamp,
                              String newDescription, String reason) implements WorldEvent {}

    /** Hints were updated for the room (§65.2). */
    record HintsUpdated(String roomId, Instant timestamp,
                        List<Hint> hints) implements WorldEvent {}

    /** A room script was triggered. */
    record ScriptTriggered(String roomId, Instant timestamp,
                           String scriptName, String trigger,
                           Map<String, Object> context) implements WorldEvent {}

    /**
     * An object was added/placed in the room (room initialization, script).
     *
     * @param state — optional state-flag map for furnishings
     *              that need it (e.g. {@code {"sittable": "true"}}). May be
     *              empty/null; backward-compat ctor preserves old call sites.
     */
    record ObjectAdded(String roomId, Instant timestamp, String objectId,
                       String objectName, String description,
                       boolean takeable, Map<String, String> state,
                       List<String> aliases)
            implements WorldEvent {
        /** Backward-compatible ctor — no state. */
        public ObjectAdded(String roomId, Instant timestamp, String objectId,
                           String objectName, String description, boolean takeable) {
            this(roomId, timestamp, objectId, objectName, description, takeable,
                Map.of(), List.of());
        }

        /** Backward-compatible ctor — state, no aliases (pre-2026-07-04 journals).
         *  Aliases were silently dropped at this event boundary, which made
         *  AliasResolver's exact-alias tier dead for every seeded furnishing
         *  ("use scroll" hijacked by partial match — second-node). */
        public ObjectAdded(String roomId, Instant timestamp, String objectId,
                           String objectName, String description, boolean takeable,
                           Map<String, String> state) {
            this(roomId, timestamp, objectId, objectName, description, takeable,
                state, List.of());
        }
    }

    /** A room property changed (generic key-value). */
    record PropertyChanged(String roomId, Instant timestamp,
                           String key, String oldValue,
                           String newValue) implements WorldEvent {}

    /** A directed message to a specific entity (whisper). */
    record Whispered(String roomId, Instant timestamp, String entityId,
                     String entityName, String targetEntityId,
                     String text) implements WorldEvent {}

    /** A room suggests a vitality change to an entity (agent evaluates and may accept). */
    record VitalitySuggested(String roomId, Instant timestamp,
                             String entityId, String tank,
                             double delta, String reason) implements WorldEvent {}

    /** An entity performed an emote (visible action/expression) in the room. */
    record Emoted(String roomId, Instant timestamp, String entityId,
                  String entityName, String text) implements WorldEvent {}

    /**
     * An entity began traveling to another zone.
     * Differs from EntityLeft (which means disconnect or local move).
     * Companions use this to know the player is temporarily away, not gone.
     */
    record EntityTraveling(String roomId, Instant timestamp,
                           String entityId, String entityName,
                           String destinationZone) implements WorldEvent {}

    /**
     * An entity returned from traveling. Companions resume normal interaction,
     * replay buffered messages.
     */
    record EntityReturned(String roomId, Instant timestamp,
                          String entityId, String entityName,
                          String fromZone) implements WorldEvent {}

    /**
     * an entity's body changed posture.
     * Broadcast on set, change, or clear. Carries both previous and current so observers
     * can render the transition ("Masumi stood from the chair and walked to the window"
     * derives from previous.verb=sit, current.verb=stand). Either side may be null:
     * {@code previous=null} = posture set from default; {@code current=null} = posture cleared.
     * Transient (not state-mutating beyond the entity's posture field).
     */
    record PostureChanged(String roomId, Instant timestamp,
                          String entityId, String entityName,
                          Posture previous, Posture current) implements WorldEvent {}

    /**
     * an entity looked at another entity or object with named manner.
     * Distinct from a generic examination: the {@code manner} is non-null when the look
     * carried felt weight ("studying her face", "glancing at the door"). Casual examination
     * does not need to emit LookedAt; this event is for moments the scene-detector should
     * notice. Useful for "she saw you looking" without forcing every glance into broadcast.
     *
     * @param manner free-form description of how the look was carried out, or null if not specified
     */
    record LookedAt(String roomId, Instant timestamp,
                    String actorId, String actorName,
                    String targetId, String targetName,
                    String manner) implements WorldEvent {}

    /**
     * room-level ambient state shifted.
     * Scripts emit this when a property of the room's atmosphere changes
     * (hearth dimming, lamps lit, a draft from an opened window, time-of-day rendering
     * once Layer 5 lands). The {@code descriptor} carries the felt text ("The hearth has
     * burned low; the room is softer now."); the {@code key}/{@code previous}/{@code current}
     * triple is for engine bookkeeping if needed.
     *
     * @param key        the ambient property that changed (e.g. {@code "light"}, {@code "warmth"})
     * @param previous   prior value (may be null for first set)
     * @param current    new value (may be null when ambient is cleared)
     * @param descriptor felt-text narration broadcast to observers; may be null when the
     *                   change should be silent (e.g. a property tracked but not narrated)
     */
    record AmbientChanged(String roomId, Instant timestamp,
                          String key, String previous, String current,
                          String descriptor) implements WorldEvent {}
}
