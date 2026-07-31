package org.wyrdsekai.core.interop;

import java.util.Map;

/**
 * Translates between A2A protocol messages and Wyrdsekai room commands (§97.1, §97.5).
 * <p>
 * A2A → Wyrdsekai:
 *   tasks/send → RoomCommand (say, use, go)
 *   Message text → room narrative
 * <p>
 * Wyrdsekai → A2A:
 *   RoomCommand → tasks/send
 *   S2CMessage → A2A Message response
 */
public class A2AMessageTranslator {

    /** A translated room command. */
    public record TranslatedCommand(
        String verb,
        String target,
        Map<String, String> params
    ) {}

    /** A translated A2A response. */
    public record TranslatedResponse(
        String method,
        String contentJson,
        Map<String, Object> metadata
    ) {}

    /**
     * Translate an inbound A2A task message to a Wyrdsekai room command.
     *
     * @param method      A2A method (tasks/send, etc.)
     * @param contentJson the message text/content
     * @return translated room command
     */
    public TranslatedCommand toRoomCommand(String method, String contentJson) {
        if (contentJson == null || contentJson.isBlank()) {
            return new TranslatedCommand("say", "", Map.of());
        }

        var text = contentJson.trim();

        // Try to parse as a room command
        if (text.startsWith("go:") || text.startsWith("go ")) {
            return new TranslatedCommand("go", text.substring(3).trim(), Map.of());
        }
        if (text.startsWith("use:") || text.startsWith("use ")) {
            return new TranslatedCommand("use", text.substring(4).trim(), Map.of());
        }
        if (text.startsWith("say:") || text.startsWith("say ")) {
            return new TranslatedCommand("say", text.substring(4).trim(), Map.of());
        }

        // Default: treat as speech
        return new TranslatedCommand("say", text, Map.of());
    }

    /**
     * Translate a Wyrdsekai room narrative to an A2A response.
     *
     * @param narrative   the room narrative text
     * @param roomId      the room where the interaction happened
     * @return translated A2A response
     */
    public TranslatedResponse toA2AResponse(String narrative, String roomId) {
        return new TranslatedResponse(
            A2AGateway.METHOD_TASKS_SEND,
            narrative != null ? narrative : "",
            Map.of("room", roomId != null ? roomId : "unknown",
                "platform", "wyrdsekai")
        );
    }

    /**
     * Translate a Wyrdsekai room command to an outbound A2A task.
     *
     * @param verb   command verb (say, use, go)
     * @param target command target
     * @return A2A content JSON
     */
    public String toOutboundContent(String verb, String target) {
        if (verb == null) return target != null ? target : "";
        return verb + ":" + (target != null ? target : "");
    }
}
