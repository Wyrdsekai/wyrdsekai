package org.wyrdsekai.common.protocol;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import org.wyrdsekai.common.model.ImageAttachment;

import java.util.List;
import java.util.Map;

/**
 * Client → Server WebSocket messages.
 * Each message has an id (for correlation) and type-specific payload.
 * roomId identifies the room context for sharding/routing.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = C2SMessage.Say.class, name = "say"),
    @JsonSubTypes.Type(value = C2SMessage.Go.class, name = "go"),
    @JsonSubTypes.Type(value = C2SMessage.Take.class, name = "take"),
    @JsonSubTypes.Type(value = C2SMessage.Drop.class, name = "drop"),
    @JsonSubTypes.Type(value = C2SMessage.Use.class, name = "use"),
    @JsonSubTypes.Type(value = C2SMessage.Examine.class, name = "examine"),
    @JsonSubTypes.Type(value = C2SMessage.Rename.class, name = "rename"),
    @JsonSubTypes.Type(value = C2SMessage.Look.class, name = "look"),
    @JsonSubTypes.Type(value = C2SMessage.HintSelect.class, name = "hint_select"),
    @JsonSubTypes.Type(value = C2SMessage.Reconnect.class, name = "reconnect"),
    @JsonSubTypes.Type(value = C2SMessage.Command.class, name = "command"),
    @JsonSubTypes.Type(value = C2SMessage.SetPreference.class, name = "set_preference"),
    @JsonSubTypes.Type(value = C2SMessage.MapRequest.class, name = "map_request"),
    @JsonSubTypes.Type(value = C2SMessage.VoiceAudio.class, name = "voice_audio"),
    @JsonSubTypes.Type(value = C2SMessage.Emote.class, name = "emote"),
})
public sealed interface C2SMessage {

    String id();

    /**
     * Text input — natural language or MUD command.
     * Optionally carries image attachments for vision analysis.
     *
     * @param attachments nullable list of image attachments (photos, screenshots)
     * @param voice       nullable — true if this input originated from voice (STT).
     *                    Null or false means text input. Backward compatible.
     */
    record Say(String id, String roomId, String text,
               List<ImageAttachment> attachments,
               Boolean voice) implements C2SMessage {

        /** Backward-compatible constructor — no attachments, no voice. */
        public Say(String id, String roomId, String text) {
            this(id, roomId, text, null, null);
        }

        /** Backward-compatible constructor — attachments but no voice. */
        public Say(String id, String roomId, String text,
                   List<ImageAttachment> attachments) {
            this(id, roomId, text, attachments, null);
        }

        /** Check if this input originated from voice. */
        public boolean isVoice() {
            return voice != null && voice;
        }
    }

    /** Navigation to an adjacent room. */
    record Go(String id, String roomId, String direction) implements C2SMessage {}

    /** Pick up an object. */
    record Take(String id, String roomId, String objectName) implements C2SMessage {}

    /** Drop an object. */
    record Drop(String id, String roomId, String objectName) implements C2SMessage {}

    /** Use an object, optionally on a target. */
    record Use(String id, String roomId, String objectName, String target) implements C2SMessage {}

    /**
     * Passive observation of an object, entity, or self.
     * <p>Distinguished from {@link Use}: examine does NOT invoke {@code onUse}
     * scripts, does NOT broadcast {@code ObjectUsed}, and does NOT trigger a
     * room re-render. The server returns the target's description text as a
     * {@link S2CMessage.Prose} message; lookup order is self → inventory →
     * room-object → room-entity.</p>
     */
    record Examine(String id, String roomId, String target) implements C2SMessage {}

    /**
     * Rename the caller's display name.
     * <p>v1 scope: self-rename only — {@code target} must be {@code "me"} or
     * the caller's current display name. Persists via
     * {@code AuthService.updateDisplayName}, broadcasts a name change to the
     * current room, and refreshes {@code EntityRegistry}. Steward and
     * bondholder rename paths are v2 (bond/grant authority + companion
     * SoulManifest writes).</p>
     */
    record Rename(String id, String target, String newName) implements C2SMessage {}

    /** Observe the current room. */
    record Look(String id, String roomId) implements C2SMessage {}

    /** Select a hint by index (one-keystroke from hint list). */
    record HintSelect(String id, String roomId, int index) implements C2SMessage {}

    /** Reconnect with last-seen sequence number for replay. */
    record Reconnect(String id, String roomId, long lastSeenSeq) implements C2SMessage {}

    /**
     * System command — extensible dispatch for zone-type actions (§83.7).
     * Namespaced commands (e.g. "codeplane.approve", "homekit.toggle") route to zone handlers.
     * Unprefixed commands ("who", "inventory") are core Wyrdsekai commands.
     *
     * @param payload Structured key-value data for zone-type actions. Empty for core commands.
     */
    record Command(String id, String command, List<String> args,
                   Map<String, String> payload) implements C2SMessage {

        /** Backward-compatible constructor — no payload. */
        public Command(String id, String command, List<String> args) {
            this(id, command, args, Map.of());
        }
    }

    /** Set a client preference (e.g. locale). */
    record SetPreference(String id, String key, String value) implements C2SMessage {}

    /**
     * Request topology data for map rendering (§N6).
     * Alternative to text commands — lets mobile clients request map data directly.
     *
     * @param command "map", "nearby", "rooms", "path", "where", "exits"
     * @param radius  BFS radius for map command (1-5, default 2)
     * @param target  Target room name for path command (null otherwise)
     */
    record MapRequest(String id, String command, int radius,
                      String target) implements C2SMessage {}

    /**
     * Voice audio input -- player sends audio for server-side STT transcription.
     * Audio is base64-encoded in JSON for backward compatibility with text WebSocket frames.
     * The server transcribes the audio and processes it as a Say event with voice=true.
     *
     * @param id          Correlation ID
     * @param audioBase64 Base64-encoded audio data
     * @param format      Audio format: "wav", "pcm16", "opus", "mp3"
     */
    record VoiceAudio(String id, String audioBase64, String format) implements C2SMessage {}

    /** Client emotes in a room. */
    record Emote(String id, String roomId, String text) implements C2SMessage {}
}
