package org.wyrdsekai.core.room;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.wyrdsekai.common.model.RoomObject;
import org.wyrdsekai.common.model.RoomSnapshot;

import java.util.List;
import java.util.Map;

/**
 * Responses from the RoomActor to command senders.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = RoomResponse.Ok.class, name = "ok"),
    @JsonSubTypes.Type(value = RoomResponse.Rejected.class, name = "rejected"),
    @JsonSubTypes.Type(value = RoomResponse.HintAction.class, name = "hint_action"),
    @JsonSubTypes.Type(value = RoomResponse.ObjectTakenOk.class, name = "object_taken_ok"),
    @JsonSubTypes.Type(value = RoomResponse.Narrated.class, name = "narrated"),
    @JsonSubTypes.Type(value = RoomResponse.HookRan.class, name = "hook_ran"),
    @JsonSubTypes.Type(value = RoomResponse.ToolDefinitions.class, name = "tool_definitions"),
})
public sealed interface RoomResponse {

    /** Successful operation with current room snapshot. */
    record Ok(RoomSnapshot snapshot) implements RoomResponse {}

    /** Operation failed. */
    record Rejected(String code, String reason) implements RoomResponse {}

    /** Hint dispatch that requires external handling (e.g., navigation). */
    record HintAction(String actionType, String parameter, String targetRoomId) implements RoomResponse {}

    /** Object was successfully taken — includes the taken object's metadata. */
    record ObjectTakenOk(RoomSnapshot snapshot, RoomObject takenObject) implements RoomResponse {}

    /**
     * Narration-only acknowledgement — the action emitted a {@code Said} (or
     * similar) world event for the caller and needs no room redraw. Distinct
     * from {@link Ok} because client sessions translate {@code Ok(snapshot)}
     * into a full {@code RoomState} push, which would clobber the narration
     * line. Used by {@code examine} and other "look at X" verbs that don't
     * mutate room state.
     */
    record Narrated() implements RoomResponse {}

    /**
     * Reply to {@link RoomCommand.InvokeScriptHook} — the hook ran, and
     * {@code narration} carries the concatenated {@code narrate} emissions it
     * produced (empty when the hook narrated nothing or wasn't defined).
     */
    record HookRan(String narration) implements RoomResponse {}

    /**
     * Reply to {@link RoomCommand.GetToolDefinitions} — the room script's
     * declared agent-callable tools ({@code getToolDefinitions()}), or empty.
     * Each map: {name, description, params:{...}}.
     */
    record ToolDefinitions(List<Map<String, Object>> tools)
        implements RoomResponse {}
}
