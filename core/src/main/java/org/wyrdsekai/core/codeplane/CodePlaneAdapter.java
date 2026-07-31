package org.wyrdsekai.core.codeplane;

import java.util.*;

/**
 * CodePlane integration adapter (§75).
 * Maps CodePlane Board concepts to Wyrdsekai Room protocol.
 * CodePlane builds Wyrdsekai, Wyrdsekai runs CodePlane.
 *
 * Spatial mapping:
 *   CodePlane Board  → Wyrdsekai Room
 *   Board Column     → Room property (workflow stage)
 *   Board Card       → Room object (takeable)
 *   Board Comment    → Room event (speech)
 *   Board Member     → Room entity (agent/player)
 */
public class CodePlaneAdapter {

    /** A CodePlane Board mapped to room metadata. */
    public record BoardMapping(
        String boardId,
        String roomId,
        String boardName,
        Map<String, String> columnToProperty,  // column → room property key
        Map<String, String> memberToEntity      // member → entity ID
    ) {}

    /** A CodePlane Card mapped to a room object. */
    public record CardMapping(
        String cardId,
        String objectId,
        String title,
        String columnId,
        String assigneeEntity,
        Map<String, String> metadata
    ) {}

    /** A cross-project event bridging CodePlane and Wyrdsekai. */
    public record BridgeEvent(
        String source,     // "codeplane" or "wyrdsekai"
        String eventType,  // "card_moved", "speech", "entity_entered", etc.
        String sourceId,
        String targetId,
        Map<String, String> payload
    ) {}

    /** Spatial rooms corresponding to CodePlane workspaces (§75). */
    public enum SpatialRoom {
        THE_FORGE("the-forge", "Where code is forged — active development workspace"),
        THE_CRUCIBLE("the-crucible", "Where code is tested — CI/CD and quality assurance"),
        THE_ASSAY_OFFICE("the-assay-office", "Where code is evaluated — code review and analysis"),
        THE_LEDGER("the-ledger", "Where contributions are tracked — project metrics and history"),
        THE_ARCHIVE("the-archive", "Where knowledge is preserved — documentation and artifacts");

        private final String roomId;
        private final String description;

        SpatialRoom(String roomId, String description) {
            this.roomId = roomId;
            this.description = description;
        }

        public String roomId() { return roomId; }
        public String description() { return description; }
    }

    private final Map<String, BoardMapping> boardMappings = new LinkedHashMap<>();
    private final Map<String, CardMapping> cardMappings = new LinkedHashMap<>();
    private final List<BridgeEvent> eventLog = new ArrayList<>();

    /**
     * Register a board-to-room mapping.
     */
    public BoardMapping mapBoard(String boardId, String roomId, String boardName,
                                  Map<String, String> columnMap,
                                  Map<String, String> memberMap) {
        var mapping = new BoardMapping(boardId, roomId, boardName, columnMap, memberMap);
        boardMappings.put(boardId, mapping);
        return mapping;
    }

    /**
     * Register a card-to-object mapping.
     */
    public CardMapping mapCard(String cardId, String objectId, String title,
                                String columnId, String assigneeEntity,
                                Map<String, String> metadata) {
        var mapping = new CardMapping(cardId, objectId, title, columnId,
            assigneeEntity, metadata);
        cardMappings.put(cardId, mapping);
        return mapping;
    }

    /**
     * Translate a CodePlane event to a Wyrdsekai room event.
     */
    public Optional<BridgeEvent> translateToRoom(String eventType, String sourceId,
                                                   Map<String, String> payload) {
        var event = new BridgeEvent("codeplane", eventType, sourceId,
            findRoomForSource(sourceId), payload);
        eventLog.add(event);
        return Optional.of(event);
    }

    /**
     * Translate a Wyrdsekai room event to a CodePlane event.
     */
    public Optional<BridgeEvent> translateToCodePlane(String eventType, String roomId,
                                                        Map<String, String> payload) {
        var event = new BridgeEvent("wyrdsekai", eventType, roomId,
            findBoardForRoom(roomId), payload);
        eventLog.add(event);
        return Optional.of(event);
    }

    /** Get the board mapping for a board ID. */
    public Optional<BoardMapping> getBoardMapping(String boardId) {
        return Optional.ofNullable(boardMappings.get(boardId));
    }

    /** Get the card mapping for a card ID. */
    public Optional<CardMapping> getCardMapping(String cardId) {
        return Optional.ofNullable(cardMappings.get(cardId));
    }

    /** List all spatial rooms from §75. */
    public List<SpatialRoom> spatialRooms() {
        return List.of(SpatialRoom.values());
    }

    /** Get recent bridge events. */
    public List<BridgeEvent> recentEvents(int limit) {
        int start = Math.max(0, eventLog.size() - limit);
        return List.copyOf(eventLog.subList(start, eventLog.size()));
    }

    /** Human-readable summary. */
    public String describe() {
        var sb = new StringBuilder("=== CodePlane Integration ===\n\n");
        sb.append("Board mappings: ").append(boardMappings.size()).append("\n");
        sb.append("Card mappings: ").append(cardMappings.size()).append("\n");
        sb.append("Bridge events: ").append(eventLog.size()).append("\n\n");
        sb.append("Spatial Rooms:\n");
        for (var room : SpatialRoom.values()) {
            sb.append("  ").append(room.roomId()).append(" — ").append(room.description()).append("\n");
        }
        return sb.toString().stripTrailing();
    }

    private String findRoomForSource(String sourceId) {
        for (var mapping : boardMappings.values()) {
            if (mapping.boardId().equals(sourceId)) return mapping.roomId();
        }
        return sourceId;
    }

    private String findBoardForRoom(String roomId) {
        for (var mapping : boardMappings.values()) {
            if (mapping.roomId().equals(roomId)) return mapping.boardId();
        }
        return roomId;
    }
}
