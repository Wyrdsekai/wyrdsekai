package org.wyrdsekai.core.room;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.pekko.actor.typed.ActorRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.model.Exit;
import org.wyrdsekai.common.model.Hint;
import org.wyrdsekai.common.model.Posture;
import org.wyrdsekai.common.model.RoomObject;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/**
 * Dispatches serialized room commands to a local RoomActor.
 * Used by the primary-side command listener to handle commands forwarded from replicas.
 *
 * The challenge: RoomCommand records contain ActorRef<RoomResponse> replyTo fields
 * which can't be serialized over the wire. This dispatcher extracts command fields
 * from JSON, constructs new commands with a local replyTo, and serializes the response.
 */
public final class RoomCommandDispatcher {

    private static final Logger log = LoggerFactory.getLogger(RoomCommandDispatcher.class);
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private RoomCommandDispatcher() {}

    /**
     * Deserialize a JSON room command, apply it to {@code roomRef}, and return
     * the serialized {@link RoomResponse}. This is the shared primary-side
     * executor: ZoneGuardian wires it for cross-node room proxying, and (as of
     * the definitive re-audit, #33-3) {@code MutationRouter}'s forwarded-mutation
     * handler reuses it so replica→primary mutations actually apply instead of
     * returning "No mutation handler configured".
     */
    public static CompletionStage<String> dispatch(ActorRef<RoomCommand> roomRef, String commandJson) {
        try {
            var node = MAPPER.readTree(commandJson);
            var type = node.path("type").asText("");

            return switch (type) {
                case "look" -> askRoom(roomRef, replyTo ->
                    new RoomCommand.LookRoom(
                        node.path("entityId").asText(),
                        node.path("locale").asText("en"),
                        replyTo));
                case "enter" -> askRoom(roomRef, replyTo ->
                    new RoomCommand.EnterRoom(
                        node.path("entityId").asText(),
                        node.path("entityName").asText(),
                        node.path("entityType").asText(),
                        node.path("description").asText(""),
                        node.path("fromDirection").asText(),
                        node.path("locale").asText("en"),
                        replyTo));
                case "leave" -> askRoom(roomRef, replyTo ->
                    new RoomCommand.LeaveRoom(
                        node.path("entityId").asText(),
                        node.path("entityName").asText(),
                        node.path("direction").asText(),
                        replyTo));
                case "say" -> askRoom(roomRef, replyTo ->
                    new RoomCommand.SayInRoom(
                        node.path("entityId").asText(),
                        node.path("entityName").asText(),
                        node.path("text").asText(),
                        node.path("locale").asText("en"),
                        null, // attachments not proxied
                        replyTo));
                case "emote" -> askRoom(roomRef, replyTo ->
                    new RoomCommand.EmoteInRoom(
                        node.path("entityId").asText(),
                        node.path("entityName").asText(),
                        node.path("text").asText(),
                        node.path("locale").asText("en"),
                        replyTo));
                case "whisper" -> askRoom(roomRef, replyTo ->
                    new RoomCommand.WhisperInRoom(
                        node.path("entityId").asText(),
                        node.path("entityName").asText(),
                        node.path("targetEntityId").asText(),
                        node.path("text").asText(),
                        node.path("locale").asText("en"),
                        replyTo));
                case "take" -> askRoom(roomRef, replyTo ->
                    new RoomCommand.TakeObject(
                        node.path("entityId").asText(),
                        node.path("objectName").asText(),
                        node.path("locale").asText("en"),
                        replyTo));
                case "drop" -> askRoom(roomRef, replyTo ->
                    new RoomCommand.DropObject(
                        node.path("entityId").asText(),
                        node.path("objectId").asText(),
                        node.path("objectName").asText(),
                        node.path("description").asText(),
                        node.path("takeable").asBoolean(true),
                        node.path("locale").asText("en"),
                        replyTo));
                case "use" -> askRoom(roomRef, replyTo ->
                    new RoomCommand.UseObject(
                        node.path("entityId").asText(),
                        node.path("objectName").asText(),
                        node.path("target").asText(null),
                        node.path("locale").asText("en"),
                        replyTo));
                case "hint_select" -> askRoom(roomRef, replyTo ->
                    new RoomCommand.SelectHint(
                        node.path("entityId").asText(),
                        node.path("index").asInt(),
                        node.path("locale").asText("en"),
                        replyTo));
                case "create" -> askRoom(roomRef, replyTo ->
                    new RoomCommand.CreateRoom(
                        node.path("name").asText(),
                        node.path("description").asText(),
                        node.path("zone").asText(),
                        parseExits(node.path("exits")),
                        parseObjects(node.path("objects")),
                        replyTo));
                case "add_exit" -> askRoom(roomRef, replyTo ->
                    new RoomCommand.AddExit(
                        node.path("direction").asText(),
                        node.path("targetRoom").asText(),
                        node.path("label").asText(),
                        replyTo));
                case "update_hints" -> askRoom(roomRef, replyTo ->
                    new RoomCommand.UpdateHints(
                        parseHints(node.path("hints")),
                        replyTo));
                case "quarantine" -> askRoom(roomRef, replyTo ->
                    new RoomCommand.Quarantine(
                        node.path("entityId").asText(),
                        node.path("reason").asText(),
                        replyTo));
                case "unquarantine" -> askRoom(roomRef, replyTo ->
                    new RoomCommand.Unquarantine(
                        node.path("entityId").asText(),
                        replyTo));
                case "update_entity_desc" -> askRoom(roomRef, replyTo ->
                    new RoomCommand.UpdateEntityDescription(
                        node.path("entityId").asText(),
                        node.path("description").asText(),
                        replyTo));
                // Audit follow-up 2026-07-11: RoomProxy forwards these four, but this
                // primary-side switch never learned them — remote sit/stand/rename/
                // item-effects were rejected as unknown_command despite proxy parity.
                case "set_posture" -> {
                    // Posture is a structured record, not an enum — deserialize whole.
                    var posture = MAPPER.treeToValue(node.path("posture"), Posture.class);
                    yield askRoom(roomRef, replyTo -> new RoomCommand.SetPosture(
                        node.path("entityId").asText(), posture, replyTo));
                }
                case "clear_posture" -> askRoom(roomRef, replyTo ->
                    new RoomCommand.ClearPosture(
                        node.path("entityId").asText(),
                        replyTo));
                case "update_entity_name" -> askRoom(roomRef, replyTo ->
                    new RoomCommand.UpdateEntityName(
                        node.path("entityId").asText(),
                        node.path("newName").asText(),
                        replyTo));
                case "item_bridge" -> {
                    // Fire-and-forget (no replyTo in the record) — deserialize whole.
                    var cmd = MAPPER.treeToValue(node, RoomCommand.ItemBridgeAction.class);
                    roomRef.tell(cmd);
                    yield CompletableFuture.completedFuture("{\"type\":\"ok\"}");
                }
                default -> CompletableFuture.completedFuture(
                    "{\"type\":\"rejected\",\"code\":\"unknown_command\",\"reason\":\"Unknown command type: " + type + "\"}");
            };
        } catch (Exception e) {
            log.warn("Failed to dispatch room command: {}", e.getMessage());
            return CompletableFuture.completedFuture(
                "{\"type\":\"rejected\",\"code\":\"parse_error\",\"reason\":\"" + e.getMessage() + "\"}");
        }
    }

    private static CompletionStage<String> askRoom(
            ActorRef<RoomCommand> roomRef,
            Function<ActorRef<RoomResponse>, RoomCommand> factory) {
        return Rooms.<RoomResponse>ask(roomRef, factory, TIMEOUT)
            .thenApply(response -> {
                try {
                    return MAPPER.writeValueAsString(response);
                } catch (Exception e) {
                    return "{\"type\":\"rejected\",\"code\":\"serialize_error\",\"reason\":\"" + e.getMessage() + "\"}";
                }
            })
            .exceptionally(err -> "{\"type\":\"rejected\",\"code\":\"timeout\",\"reason\":\"" + err.getMessage() + "\"}");
    }

    private static List<Exit> parseExits(JsonNode node) {
        if (node == null || node.isMissingNode() || !node.isArray()) return List.of();
        try { return MAPPER.readerForListOf(Exit.class).readValue(node); }
        catch (Exception e) { return List.of(); }
    }

    private static List<RoomObject> parseObjects(JsonNode node) {
        if (node == null || node.isMissingNode() || !node.isArray()) return List.of();
        try { return MAPPER.readerForListOf(RoomObject.class).readValue(node); }
        catch (Exception e) { return List.of(); }
    }

    private static List<Hint> parseHints(JsonNode node) {
        if (node == null || node.isMissingNode() || !node.isArray()) return List.of();
        try { return MAPPER.readerForListOf(Hint.class).readValue(node); }
        catch (Exception e) { return List.of(); }
    }
}
