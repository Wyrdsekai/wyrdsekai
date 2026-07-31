package org.wyrdsekai.server.session;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.model.RoomSnapshot;
import org.wyrdsekai.common.protocol.S2CMessage;
import org.wyrdsekai.common.util.Json;

import java.util.List;
import java.util.Optional;

/**
 * Decodes cross-zone session events (JSON from {@code VirtualSessionHandler})
 * into the locally-typed {@link S2CMessage} hierarchy. Every transport (WS,
 * SSH, Telnet) renders {@code S2CMessage}s already, so funneling remote events
 * through this single point keeps all three transports visually in sync and
 * collapses ~140 lines that were previously duplicated in WyrdWebSocket.
 * <p>
 */
public final class RemoteEventDecoder {

    private static final Logger log = LoggerFactory.getLogger(RemoteEventDecoder.class);

    private RemoteEventDecoder() {}

    /**
     * @param eventJson raw event JSON as delivered by {@code RemoteZoneSession}
     * @return a decoded {@link S2CMessage} suitable for forwarding to
     *         {@link ClientSessionActor.SendMessage}, or empty if the JSON is
     *         unparseable. Unknown event types are rendered as an ambient
     *         prose tag so the client at least sees *something* rather than
     *         silently dropping remote signals.
     */
    public static Optional<S2CMessage> decode(String eventJson) {
        if (eventJson == null || eventJson.isEmpty()) return Optional.empty();
        try {
            var eventNode = Json.mapper().readTree(eventJson);
            return Optional.of(decodeNode(eventNode));
        } catch (Exception e) {
            log.warn("RemoteEventDecoder: failed to parse event ({} bytes): {}",
                eventJson.length(), e.getMessage());
            return Optional.empty();
        }
    }

    private static S2CMessage decodeNode(JsonNode eventNode) throws Exception {
        var type = eventNode.path("type").asText("");
        var data = eventNode.path("data");

        return switch (type) {
            case "room_state" -> {
                var roomNode = data.path("room");
                var snapshot = Json.mapper().treeToValue(roomNode, RoomSnapshot.class);
                yield new S2CMessage.RoomState(0, snapshot, List.of());
            }
            case "prose" -> new S2CMessage.Prose(0,
                data.path("speaker").asText("system"),
                data.path("text").asText(""),
                List.of(), null,
                data.path("priority").asText("normal"));
            case "room_event" -> decodeRoomEvent(data);
            case "error" -> new S2CMessage.Error(0, "remote_error",
                data.path("message").asText("Unknown error"), null);
            case "notification" -> new S2CMessage.Notification(0,
                data.path("priority").asText("normal"),
                data.path("fromAgent").asText("system"),
                data.path("message").asText(""));
            default -> new S2CMessage.Prose(0, "system",
                "[remote: " + type + "]", List.of(), null, "ambient");
        };
    }

    private static S2CMessage decodeRoomEvent(JsonNode data) {
        var eventType = data.path("eventType").asText("");
        var eventData = data.path("data");
        return switch (eventType) {
            case "Said" -> new S2CMessage.Prose(0,
                eventData.path("entityName").asText("someone"),
                eventData.path("text").asText(""),
                List.of(), null, "normal");
            case "EntityEntered" -> new S2CMessage.Prose(0,
                "narrator",
                arrivalText(eventData.path("entityName").asText("someone"),
                    eventData.path("fromDirection").asText("")),
                List.of(), null, "ambient");
            case "EntityLeft" -> new S2CMessage.Prose(0,
                "narrator",
                departureText(eventData.path("entityName").asText("someone"),
                    eventData.path("direction").asText("")),
                List.of(), null, "ambient");
            case "Emoted" -> new S2CMessage.Prose(0,
                eventData.path("entityName").asText("someone"),
                eventData.path("text").asText(""),
                List.of(), null, "normal", (String) null, "emote");
            default -> new S2CMessage.Prose(0, "system",
                "[" + eventType + "]", List.of(), null, "ambient");
        };
    }

    /** Cross-zone arrival prose. Mirrors ClientSessionActor's placeholder logic
     *  so a remote login/spawn reads "X arrives." not "X arrives from somewhere." */
    private static String arrivalText(String name, String dir) {
        return isPlaceholderDirection(dir)
            ? name + " arrives."
            : name + " enters from " + dir + ".";
    }

    /** Cross-zone departure prose. Placeholder exits read "X leaves." and real
     *  exits read "X heads <dir>." (avoids the "X leaves in." awkwardness). */
    private static String departureText(String name, String dir) {
        return isPlaceholderDirection(dir)
            ? name + " leaves."
            : name + " heads " + dir + ".";
    }

    private static boolean isPlaceholderDirection(String dir) {
        if (dir == null) return true;
        var d = dir.trim().toLowerCase();
        return d.isEmpty() || d.equals("nowhere") || d.equals("somewhere") || d.equals("unknown");
    }
}
