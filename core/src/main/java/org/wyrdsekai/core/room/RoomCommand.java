package org.wyrdsekai.core.room;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.apache.pekko.actor.typed.ActorRef;
import org.wyrdsekai.common.event.VisibilityLevel;
import org.wyrdsekai.common.event.WorldEvent;
import org.wyrdsekai.common.model.Exit;
import org.wyrdsekai.common.model.Hint;
import org.wyrdsekai.common.model.ImageAttachment;
import org.wyrdsekai.common.model.Posture;
import org.wyrdsekai.common.model.RoomObject;
import org.wyrdsekai.common.model.RoomSnapshot;

import java.util.List;
import java.util.Map;

/**
 * Commands sent to the RoomActor.
 * Each command carries a replyTo for response routing.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = RoomCommand.LookRoom.class, name = "look"),
    @JsonSubTypes.Type(value = RoomCommand.EnterRoom.class, name = "enter"),
    @JsonSubTypes.Type(value = RoomCommand.LeaveRoom.class, name = "leave"),
    @JsonSubTypes.Type(value = RoomCommand.SayInRoom.class, name = "say"),
    @JsonSubTypes.Type(value = RoomCommand.TakeObject.class, name = "take"),
    @JsonSubTypes.Type(value = RoomCommand.DropObject.class, name = "drop"),
    @JsonSubTypes.Type(value = RoomCommand.UseObject.class, name = "use"),
    @JsonSubTypes.Type(value = RoomCommand.SelectHint.class, name = "hint_select"),
    @JsonSubTypes.Type(value = RoomCommand.CreateRoom.class, name = "create"),
    @JsonSubTypes.Type(value = RoomCommand.AddExit.class, name = "add_exit"),
    @JsonSubTypes.Type(value = RoomCommand.Subscribe.class, name = "subscribe"),
    @JsonSubTypes.Type(value = RoomCommand.Unsubscribe.class, name = "unsubscribe"),
    @JsonSubTypes.Type(value = RoomCommand.UpdateHints.class, name = "update_hints"),
    @JsonSubTypes.Type(value = RoomCommand.WhisperInRoom.class, name = "whisper"),
    @JsonSubTypes.Type(value = RoomCommand.Quarantine.class, name = "quarantine"),
    @JsonSubTypes.Type(value = RoomCommand.Unquarantine.class, name = "unquarantine"),
    @JsonSubTypes.Type(value = RoomCommand.GetSnapshot.class, name = "get_snapshot"),
    @JsonSubTypes.Type(value = RoomCommand.EmoteInRoom.class, name = "emote"),
    @JsonSubTypes.Type(value = RoomCommand.UpdateEntityDescription.class, name = "update_entity_desc"),
    @JsonSubTypes.Type(value = RoomCommand.UpdateEntityName.class, name = "update_entity_name"),
    // Audit follow-up 2026-07-11: these two serialized with the unstable default
    // class-name id, breaking cross-node dispatch of sit/stand.
    @JsonSubTypes.Type(value = RoomCommand.SetPosture.class, name = "set_posture"),
    @JsonSubTypes.Type(value = RoomCommand.ClearPosture.class, name = "clear_posture"),
    @JsonSubTypes.Type(value = RoomCommand.ItemBridgeAction.class, name = "item_bridge"),
    @JsonSubTypes.Type(value = RoomCommand.TimerFired.class, name = "timer_fired"),
    @JsonSubTypes.Type(value = RoomCommand.InvokeScriptHook.class, name = "invoke_script_hook"),
    @JsonSubTypes.Type(value = RoomCommand.GetToolDefinitions.class, name = "get_tool_definitions"),
})
public sealed interface RoomCommand {

    /** Internal: purge stale recovered companion/agent entities after restart
     *  (ghost-presence fix, 2026-07-12). Self-sent from RecoveryCompleted. */
    record PurgeStaleCompanions() implements RoomCommand {}

    /** Look at the room — returns current state. */
    record LookRoom(String entityId, String locale, ActorRef<RoomResponse> replyTo) implements RoomCommand {
        public LookRoom(String entityId, ActorRef<RoomResponse> replyTo) {
            this(entityId, "en", replyTo);
        }
    }

    /** Entity enters the room. */
    record EnterRoom(String entityId, String entityName, String entityType,
                     String description, String fromDirection, String locale,
                     ActorRef<RoomResponse> replyTo) implements RoomCommand {
        public EnterRoom(String entityId, String entityName, String entityType,
                         String fromDirection, ActorRef<RoomResponse> replyTo) {
            this(entityId, entityName, entityType, "", fromDirection, "en", replyTo);
        }
        public EnterRoom(String entityId, String entityName, String entityType,
                         String fromDirection, String locale, ActorRef<RoomResponse> replyTo) {
            this(entityId, entityName, entityType, "", fromDirection, locale, replyTo);
        }
    }

    /** Entity leaves the room. */
    record LeaveRoom(String entityId, String entityName, String direction,
                     ActorRef<RoomResponse> replyTo) implements RoomCommand {}

    /**
     * Entity says something. Optionally carries image attachments for vision analysis.
     *
     * @param attachments nullable list of image attachments
     */
    record SayInRoom(String entityId, String entityName, String text,
                     String locale, List<ImageAttachment> attachments,
                     ActorRef<RoomResponse> replyTo) implements RoomCommand {

        /** Backward-compatible constructor — no attachments, default locale. */
        public SayInRoom(String entityId, String entityName, String text,
                         ActorRef<RoomResponse> replyTo) {
            this(entityId, entityName, text, "en", null, replyTo);
        }

        /** Backward-compatible constructor — no attachments. */
        public SayInRoom(String entityId, String entityName, String text,
                         String locale, ActorRef<RoomResponse> replyTo) {
            this(entityId, entityName, text, locale, null, replyTo);
        }
    }

    /** Entity performs an emote (visible action/expression). */
    record EmoteInRoom(String entityId, String entityName, String text,
                       String locale, ActorRef<RoomResponse> replyTo) implements RoomCommand {
        public EmoteInRoom(String entityId, String entityName, String text,
                           ActorRef<RoomResponse> replyTo) {
            this(entityId, entityName, text, "en", replyTo);
        }
    }

    /** Entity whispers to a specific entity (directed message). */
    record WhisperInRoom(String entityId, String entityName, String targetEntityId,
                         String text, String locale, ActorRef<RoomResponse> replyTo) implements RoomCommand {
        public WhisperInRoom(String entityId, String entityName, String targetEntityId,
                             String text, ActorRef<RoomResponse> replyTo) {
            this(entityId, entityName, targetEntityId, text, "en", replyTo);
        }
    }

    /** Entity takes an object. */
    record TakeObject(String entityId, String objectName, String locale,
                      ActorRef<RoomResponse> replyTo) implements RoomCommand {
        public TakeObject(String entityId, String objectName,
                          ActorRef<RoomResponse> replyTo) {
            this(entityId, objectName, "en", replyTo);
        }
    }

    /** Entity drops an object. Includes full metadata for room restoration. */
    record DropObject(String entityId, String objectId, String objectName,
                      String description, boolean takeable, String locale,
                      ActorRef<RoomResponse> replyTo) implements RoomCommand {
        public DropObject(String entityId, String objectId, String objectName,
                          String description, boolean takeable,
                          ActorRef<RoomResponse> replyTo) {
            this(entityId, objectId, objectName, description, takeable, "en", replyTo);
        }
    }

    /** Entity uses an object. */
    record UseObject(String entityId, String objectName, String target, String locale,
                     ActorRef<RoomResponse> replyTo) implements RoomCommand {
        public UseObject(String entityId, String objectName, String target,
                         ActorRef<RoomResponse> replyTo) {
            this(entityId, objectName, target, "en", replyTo);
        }
    }

    /** Select a hint by index. */
    record SelectHint(String entityId, int index, String locale,
                      ActorRef<RoomResponse> replyTo) implements RoomCommand {
        public SelectHint(String entityId, int index,
                          ActorRef<RoomResponse> replyTo) {
            this(entityId, index, "en", replyTo);
        }
    }

    /** Initialize a new room. */
    record CreateRoom(String name, String description, String zone,
                      List<String> aliases, List<Exit> exits, List<RoomObject> objects,
                      ActorRef<RoomResponse> replyTo) implements RoomCommand {
        /** Backward-compatible constructor — no aliases. */
        public CreateRoom(String name, String description, String zone,
                          List<Exit> exits, List<RoomObject> objects,
                          ActorRef<RoomResponse> replyTo) {
            this(name, description, zone, List.of(), exits, objects, replyTo);
        }
    }

    /** Add an exit to an existing room (for bidirectional linking). */
    record AddExit(String direction, String targetRoom, String label,
                   ActorRef<RoomResponse> replyTo) implements RoomCommand {}

    /** Update the room's active hints (from companion or script). */
    record UpdateHints(List<Hint> hints,
                       ActorRef<RoomResponse> replyTo) implements RoomCommand {}

    /** Subscribe to room events (for session actors).
     *  @param visibility maximum visibility level this subscriber can receive (§2.1) */
    record Subscribe(ActorRef<RoomNotification> subscriber,
                     VisibilityLevel visibility,
                     String entityId) implements RoomCommand {
        /** Subscribe with PUBLIC visibility (default for players). */
        public Subscribe(ActorRef<RoomNotification> subscriber) {
            this(subscriber, VisibilityLevel.PUBLIC, null);
        }
        /** Subscribe with specific visibility (no entity tracking). */
        public Subscribe(ActorRef<RoomNotification> subscriber, VisibilityLevel visibility) {
            this(subscriber, visibility, null);
        }
    }

    /** Unsubscribe from room events. */
    record Unsubscribe(ActorRef<RoomNotification> subscriber) implements RoomCommand {}

    /** Quarantine a room — lock all exits, block new entries (§4.2).
     *  Only Warden or Wizard can quarantine. */
    record Quarantine(String entityId, String reason,
                      ActorRef<RoomResponse> replyTo) implements RoomCommand {}

    /** Lift quarantine — restore normal room operation (§4.2). */
    record Unquarantine(String entityId,
                        ActorRef<RoomResponse> replyTo) implements RoomCommand {}

    /** Set or update the room's behavior script (JavaScript).
     *  Two-phase room creation: create_room first, then add_script to furnish behavior.
     *  With {@code append=true} the script is concatenated after the room's current
     *  script instead of replacing it — the install mode for std/behavior mixins
     *  (greeter/narrator/announcer/recorder/guardian), which chain onto existing
     *  hooks rather than owning the room. */
    record SetBehaviorScript(String roomId, String script, String requesterId,
                              boolean append,
                              ActorRef<RoomResponse> replyTo) implements RoomCommand {
        /** Backward-compatible constructor — replace mode. */
        public SetBehaviorScript(String roomId, String script, String requesterId,
                                 ActorRef<RoomResponse> replyTo) {
            this(roomId, script, requesterId, false, replyTo);
        }
    }

    /**
     * §31 room timers — internal self-message sent by the room's TimerScheduler
     * when a script-scheduled timer ({@code world.scheduleTimer}) fires. Routed
     * to {@link RoomScriptEngine#invokeTimer}; emissions are processed like any
     * other hook's.
     */
    record TimerFired(String timerId, String hookName) implements RoomCommand {}

    /**
     * Invoke a named script hook on this room's script from outside the room
     * (e.g. CompanionActor running {@code onWorkbenchResult} after a workbench
     * or dispatch_task outcome, or dispatching a room-declared tool call to
     * {@code onToolCall}). Fire-and-forget when {@code replyTo} is null;
     * otherwise replies {@link RoomResponse.HookRan} carrying the narration
     * the hook emitted, so callers (ReAct tool loop) get real findings back
     * instead of a generic "executed".
     */
    record InvokeScriptHook(String hookName, List<Object> args,
                            ActorRef<RoomResponse> replyTo) implements RoomCommand {}

    /**
     * Query the room script's {@code getToolDefinitions()} (SPEC — room-scoped
     * agent tools). Replies {@link RoomResponse.ToolDefinitions}; empty list when
     * the room has no script or the script doesn't export the function.
     */
    record GetToolDefinitions(ActorRef<RoomResponse> replyTo) implements RoomCommand {}

    /** Read-only snapshot query for Between room replication.
     *  Returns the current room state as a RoomSnapshot without persisting any events. */
    record GetSnapshot(ActorRef<RoomSnapshot> replyTo) implements RoomCommand {}

    /** Update an entity's description in the room state (for @describe / update_description). */
    record UpdateEntityDescription(String entityId, String description,
                                    ActorRef<RoomResponse> replyTo) implements RoomCommand {}

    /** Rename an entity in the room state. */
    record UpdateEntityName(String entityId, String newName,
                            ActorRef<RoomResponse> replyTo) implements RoomCommand {}

    /**
     * set an entity's posture in the current room.
     * Replaces any prior posture on the entity. Broadcasts {@link WorldEvent.PostureChanged}
     * with previous + current. Callable from player commands ({@code sit at X}, scripts'
     * {@code world.entity.setPosture}, and agent action dispatch.
     *
     * <p>Posture is room-local: leaving the room clears it (see EntityLeft handler). The
     * engine does not enforce mechanics (story substrate, not physics substrate per §2):
     * two entities may both target the same chair; narration handles it.</p>
     */
    record SetPosture(String entityId, Posture posture,
                      ActorRef<RoomResponse> replyTo) implements RoomCommand {}

    /**
     * clear an entity's posture (return to default standing).
     * Broadcasts {@link WorldEvent.PostureChanged} with previous + current=null.
     */
    record ClearPosture(String entityId,
                        ActorRef<RoomResponse> replyTo) implements RoomCommand {}

    /** Broadcast a remote event to local subscribers without modifying room state.
     *  Used by Between to forward events from other nodes. */
    record BroadcastRemoteEvent(WorldEvent event) implements RoomCommand {}

    /**
     * fire-and-forget action issued by an
     * item script via {@code world.room.*}. Wraps a sub-action so we don't
     * have to add seven new top-level commands.
     *
     * <p>The {@code callerEntityId} is the DID/name of the entity whose item
     * is doing the writing — used for attribution on emitted events
     * (e.g. {@code Said.entityId}).</p>
     */
    record ItemBridgeAction(String callerEntityId,
                              ItemBridgeSubAction action) implements RoomCommand {}

    /**
     * Sub-actions for {@link ItemBridgeAction}. Sealed so we can switch
     * exhaustively in {@code RoomActor.onItemBridgeAction}.
     */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = ItemBridgeSubAction.Emit.class, name = "emit"),
        @JsonSubTypes.Type(value = ItemBridgeSubAction.Narrate.class, name = "narrate"),
        @JsonSubTypes.Type(value = ItemBridgeSubAction.AddObject.class, name = "add_object"),
        @JsonSubTypes.Type(value = ItemBridgeSubAction.RemoveObject.class, name = "remove_object"),
        @JsonSubTypes.Type(value = ItemBridgeSubAction.SetProperty.class, name = "set_property"),
        @JsonSubTypes.Type(value = ItemBridgeSubAction.UpdateDescription.class, name = "update_description"),
        @JsonSubTypes.Type(value = ItemBridgeSubAction.SetPosture.class, name = "set_posture"),
        @JsonSubTypes.Type(value = ItemBridgeSubAction.ClearPosture.class, name = "clear_posture"),
        @JsonSubTypes.Type(value = ItemBridgeSubAction.LookAt.class, name = "look_at"),
        @JsonSubTypes.Type(value = ItemBridgeSubAction.BroadcastBodyLanguage.class, name = "broadcast_body_language"),
    })
    sealed interface ItemBridgeSubAction {
        record Emit(String eventType, Map<String, Object> data) implements ItemBridgeSubAction {}
        record Narrate(String text) implements ItemBridgeSubAction {}
        record AddObject(String id, String name, String description, boolean takeable) implements ItemBridgeSubAction {}
        record RemoveObject(String id) implements ItemBridgeSubAction {}
        record SetProperty(String key, String value) implements ItemBridgeSubAction {}
        record UpdateDescription(String text) implements ItemBridgeSubAction {}
        /** — set posture on the named entity in this room. */
        record SetPosture(String entityId, Posture posture) implements ItemBridgeSubAction {}
        /** — clear posture on the named entity. */
        record ClearPosture(String entityId) implements ItemBridgeSubAction {}
        /** — broadcast a LookedAt scene event. */
        record LookAt(String actorId, String targetId, String manner) implements ItemBridgeSubAction {}
        /** — body-language narration attributed to actor (emitted as Emoted). */
        record BroadcastBodyLanguage(String actorId, String text) implements ItemBridgeSubAction {}
    }
}
