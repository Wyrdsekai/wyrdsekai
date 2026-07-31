package org.wyrdsekai.server.session;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.RecipientRef;
import org.apache.pekko.actor.typed.javadsl.AbstractBehavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.Receive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.event.VisibilityLevel;
import org.wyrdsekai.common.event.WorldEvent;
import org.wyrdsekai.common.model.Exit;
import org.wyrdsekai.common.model.Hint;
import org.wyrdsekai.common.model.RoomObject;
import org.wyrdsekai.common.model.RoomSnapshot;
import org.wyrdsekai.common.protocol.S2CMessage;
import org.wyrdsekai.common.util.Json;
import org.wyrdsekai.core.governance.ModerationService;
import org.wyrdsekai.core.household.ParentalControlService;
import org.wyrdsekai.core.protection.HostilityScorer;
import org.wyrdsekai.core.room.RoomCommand;
import org.wyrdsekai.scripting.i18n.ScriptMessageCatalog;
import org.wyrdsekai.core.room.RoomNotification;
import org.wyrdsekai.core.room.RoomResponse;

import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Per-connection session actor.
 * Tracks sequence numbers for reconnection replay.
 * Routes C2S messages to room actors, converts room notifications to S2C messages.
 */
public class ClientSessionActor extends AbstractBehavior<ClientSessionActor.SessionMessage> {

    private static final Logger log = LoggerFactory.getLogger(ClientSessionActor.class);
    private static final int MAX_MESSAGE_LOG = 1000;

    public sealed interface SessionMessage {}

    /** Wraps a room notification for this session. */
    public record RoomEvent(RoomNotification notification) implements SessionMessage {}

    /** Wraps a room response for this session. Optionally includes player inventory. */
    public record RoomResponseMsg(RoomResponse response, String requestId,
                                  List<RoomObject> inventory) implements SessionMessage {
        public RoomResponseMsg(RoomResponse response, String requestId) {
            this(response, requestId, null);
        }
    }

    /** Join a room — subscribe to its notifications, unsubscribe from previous.
     *  {@code entityId} (nullable) lets the room route directed messages
     *  (whisper, etc.) to this session via {@code entitySubscribers}. */
    public record JoinRoom(RecipientRef<RoomCommand> roomRef, String entityId) implements SessionMessage {
        /** Back-compat overload — no entity tracking (directed delivery won't reach this session). */
        public JoinRoom(RecipientRef<RoomCommand> roomRef) { this(roomRef, null); }
    }

    /**
     * Subscribe + Enter + Look in one step, all sent from this actor to guarantee
     * Pekko single-sender ordering (Subscribe before EnterRoom before LookRoom).
     * Fixes the race where onEnter narrate events fire before the session is subscribed.
     */
    public record JoinRoomAndEnter(RecipientRef<RoomCommand> roomRef,
                                    String entityId, String entityName, String entityType,
                                    String fromDirection, String locale,
                                    String requestId,
                                    List<RoomObject> inventory) implements SessionMessage {}

    /** Internal: LookRoom response received via messageAdapter. */
    private record LookResult(RoomResponse response) implements SessionMessage {}

    /** Session disconnected. */
    public record Disconnected() implements SessionMessage {}

    /** Replay request on reconnect. */
    public record ReplayFrom(long lastSeenSeq) implements SessionMessage {}

    /** Send a raw S2C message (for inventory, notifications, etc). */
    public record SendMessage(S2CMessage message) implements SessionMessage {}

    /** Update the session's locale for i18n. */
    public record SetLocale(String locale) implements SessionMessage {}

    /** Report an entity for moderation. */
    public record Report(String targetEntity, String reason, String roomId) implements SessionMessage {}

    /**
     * Voice transcription routed as text input.
     *
     * <p>{@code speakerName} is the transcriber's display name, resolved by the
     * sender (which owns the session-name map). Without it this handler used the
     * raw sessionId as the speaker's name and the room rendered a UUID where a
     * person's name belongs.</p>
     */
    public record VoiceTranscription(String sessionId, String transcribedText,
                                     String roomId, String speakerName)
            implements SessionMessage {}

    private final String sessionId;
    private final Consumer<String> sendToClient;
    private final ActorRef<RoomNotification> roomNotificationAdapter;
    private final AtomicLong seqCounter = new AtomicLong(0);
    private final ArrayDeque<S2CMessage> messageLog = new ArrayDeque<>();
    private final ActorRef<RoomResponse> lookResponseAdapter;
    private RecipientRef<RoomCommand> currentRoom;
    private String locale = "en";
    private String pendingLookRequestId;
    private List<RoomObject> pendingLookInventory;

    // Parental content filter (2026-07-03): the entity this session belongs
    // to, captured at room-join. When that member's content_filter is
    // 'strict', hostile prose spoken by OTHER entities is blotted before it
    // reaches this session's wire. One shared scorer — it is stateless.
    private String sessionEntityId;
    private static final HostilityScorer CONTENT_FILTER_SCORER = new HostilityScorer();
    private static final String FILTERED_LINE =
        "(a rough remark passes; the household filter blots it out)";

    private ClientSessionActor(ActorContext<SessionMessage> context, String sessionId,
                                Consumer<String> sendToClient) {
        super(context);
        this.sessionId = sessionId;
        this.sendToClient = sendToClient;
        this.roomNotificationAdapter = context.messageAdapter(
            RoomNotification.class, RoomEvent::new);
        this.lookResponseAdapter = context.messageAdapter(
            RoomResponse.class, LookResult::new);
        log.info("Session created: {}", sessionId);
    }

    public static Behavior<SessionMessage> create(String sessionId,
                                                   Consumer<String> sendToClient) {
        return Behaviors.setup(ctx -> new ClientSessionActor(ctx, sessionId, sendToClient));
    }

    @Override
    public Receive<SessionMessage> createReceive() {
        return newReceiveBuilder()
            .onMessage(RoomEvent.class, this::onRoomEvent)
            .onMessage(RoomResponseMsg.class, this::onRoomResponse)
            .onMessage(JoinRoom.class, this::onJoinRoom)
            .onMessage(JoinRoomAndEnter.class, this::onJoinRoomAndEnter)
            .onMessage(LookResult.class, this::onLookResult)
            .onMessage(ReplayFrom.class, this::onReplayFrom)
            .onMessage(Disconnected.class, this::onDisconnected)
            .onMessage(SendMessage.class, this::onSendMessage)
            .onMessage(SetLocale.class, this::onSetLocale)
            .onMessage(Report.class, this::onReport)
            .onMessage(VoiceTranscription.class, this::onVoiceTranscription)
            .build();
    }

    private Behavior<SessionMessage> onJoinRoom(JoinRoom msg) {
        // Unsubscribe from previous room
        if (currentRoom != null) {
            currentRoom.tell(new RoomCommand.Unsubscribe(roomNotificationAdapter));
        }
        // Subscribe to new room. Pass entityId so the room's entitySubscribers
        // map gets populated and directed events (whispers, etc.) reach us.
        currentRoom = msg.roomRef();
        if (msg.entityId() != null) sessionEntityId = msg.entityId();
        currentRoom.tell(new RoomCommand.Subscribe(
            roomNotificationAdapter, VisibilityLevel.PUBLIC,
            msg.entityId()));
        log.info("Session {} joined room (entity={})", sessionId, msg.entityId());
        return this;
    }

    /**
     * Subscribe + Enter + Look — all three sent from this actor to the same room.
     * Pekko guarantees single-sender message ordering, so Subscribe is always
     * processed before EnterRoom, ensuring onEnter narrate events reach the client.
     */
    private Behavior<SessionMessage> onJoinRoomAndEnter(JoinRoomAndEnter msg) {
        if (currentRoom != null) {
            currentRoom.tell(new RoomCommand.Unsubscribe(roomNotificationAdapter));
        }
        currentRoom = msg.roomRef();
        if (msg.entityId() != null) sessionEntityId = msg.entityId();
        pendingLookRequestId = msg.requestId() != null ? msg.requestId() : "__go__";
        pendingLookInventory = msg.inventory();

        // All three from this actor → Pekko single-sender ordering guarantee.
        // Pass entityId so directed events (whispers) route back to us.
        currentRoom.tell(new RoomCommand.Subscribe(
            roomNotificationAdapter, VisibilityLevel.PUBLIC,
            msg.entityId()));
        currentRoom.tell(new RoomCommand.EnterRoom(
            msg.entityId(), msg.entityName(), msg.entityType(),
            msg.fromDirection(), msg.locale(),
            getContext().getSystem().<RoomResponse>ignoreRef()));
        currentRoom.tell(new RoomCommand.LookRoom(
            msg.entityId(), msg.locale(), lookResponseAdapter));

        log.debug("Session {} JoinRoomAndEnter sent", sessionId);
        return this;
    }

    private Behavior<SessionMessage> onLookResult(LookResult msg) {
        if (pendingLookRequestId == null) return this;
        var requestId = pendingLookRequestId;
        var inventory = pendingLookInventory;
        pendingLookRequestId = null;
        pendingLookInventory = null;

        switch (msg.response()) {
            case RoomResponse.Ok ok ->
                send(new S2CMessage.RoomState(nextSeq(), translateSnapshot(ok.snapshot()), inventory));
            case RoomResponse.ObjectTakenOk taken ->
                send(new S2CMessage.RoomState(nextSeq(), translateSnapshot(taken.snapshot()), inventory));
            case RoomResponse.Rejected rejected ->
                send(new S2CMessage.Error(nextSeq(), rejected.code(), rejected.reason(), requestId));
            default -> {}
        }
        return this;
    }

    private Behavior<SessionMessage> onDisconnected(Disconnected msg) {
        if (currentRoom != null) {
            currentRoom.tell(new RoomCommand.Unsubscribe(roomNotificationAdapter));
        }
        return Behaviors.stopped();
    }

    private Behavior<SessionMessage> onRoomEvent(RoomEvent msg) {
        var worldEvent = msg.notification().event();
        var s2c = worldEventToS2C(worldEvent);
        if (s2c != null) {
            send(s2c);
        }
        return this;
    }

    private Behavior<SessionMessage> onRoomResponse(RoomResponseMsg msg) {
        switch (msg.response()) {
            case RoomResponse.Ok ok -> {
                var s2c = new S2CMessage.RoomState(nextSeq(), translateSnapshot(ok.snapshot()), msg.inventory());
                send(s2c);
            }
            case RoomResponse.ObjectTakenOk taken -> {
                var s2c = new S2CMessage.RoomState(nextSeq(), translateSnapshot(taken.snapshot()), msg.inventory());
                send(s2c);
            }
            case RoomResponse.Rejected rejected -> {
                var s2c = new S2CMessage.Error(
                    nextSeq(), rejected.code(), rejected.reason(), msg.requestId());
                send(s2c);
            }
            case RoomResponse.HintAction ha ->
                log.warn("Unhandled HintAction in session {}: {} {} {}",
                    sessionId, ha.actionType(), ha.parameter(), ha.targetRoomId());
            case RoomResponse.Narrated narrated -> {
                // Intentional no-op. The narration line for examine/look-at
                // verbs is already delivered to this session via the Said
                // WorldEvent it produced (notifySubscribers → Prose). Pushing
                // a RoomState here would clobber the line with a redraw.
            }
            case RoomResponse.HookRan _ -> {
                // Script-hook narration reaches the session via the Said
                // WorldEvent it emitted — same no-op rationale as Narrated.
            }
            case RoomResponse.ToolDefinitions _ -> {} // Agent-side query reply — not a session concern
        }
        return this;
    }

    private Behavior<SessionMessage> onSendMessage(SendMessage msg) {
        send(msg.message());
        return this;
    }

    private Behavior<SessionMessage> onSetLocale(SetLocale msg) {
        this.locale = msg.locale() != null ? msg.locale() : "en";
        log.info("Session {} locale set to {}", sessionId, locale);
        return this;
    }

    private Behavior<SessionMessage> onReport(Report msg) {
        log.warn("MODERATION REPORT from session {}: target={}, reason={}, room={}",
            sessionId, msg.targetEntity(), msg.reason(), msg.roomId());
        // W5 (2026-07-11): actually FILE the report — before this, the ack
        // fired but nothing landed in the moderation queue the steward
        // reviews. Reporter = the session's entity when known (captured at
        // room-join), else the session id so the trail never goes blank.
        var moderation = ModerationService.get();
        if (moderation != null) {
            var reporter = sessionEntityId != null ? sessionEntityId : sessionId;
            var filed = moderation.fileReport(
                reporter, msg.targetEntity(), msg.reason(), msg.roomId());
            log.info("Report {} filed: reporter={}, target={}, room={}",
                filed.id(), reporter, msg.targetEntity(), msg.roomId());
        } else {
            log.warn("No ModerationService installed — report from session {} "
                + "acknowledged but NOT persisted", sessionId);
        }
        // Emit a system notification to the reporting user acknowledging the report
        var catalog = ScriptMessageCatalog.forLang(locale);
        var ack = new S2CMessage.Prose(nextSeq(), "system",
            catalog.get("ui.report_filed", msg.targetEntity()),
            List.of(), null, "critical", locale);
        send(ack);
        return this;
    }

    private Behavior<SessionMessage> onVoiceTranscription(VoiceTranscription msg) {
        log.info("Voice transcription in session {}: \"{}\"", sessionId, msg.transcribedText());
        if (currentRoom != null && msg.transcribedText() != null && !msg.transcribedText().isBlank()) {
            // Route transcribed text to the current room as a SayInRoom command
            var speakerId = sessionEntityId != null ? sessionEntityId : sessionId;
            var speakerName = msg.speakerName() != null && !msg.speakerName().isBlank()
                ? msg.speakerName() : speakerId;
            currentRoom.tell(new RoomCommand.SayInRoom(
                speakerId, speakerName, msg.transcribedText(), locale,
                getContext().getSystem().ignoreRef()));
        } else if (currentRoom == null) {
            log.warn("Voice transcription received but session {} is not in a room", sessionId);
        }
        return this;
    }

    private Behavior<SessionMessage> onReplayFrom(ReplayFrom msg) {
        long fromSeq = msg.lastSeenSeq();

        // Warn if the client's last-seen seq is older than our oldest retained message
        var oldest = messageLog.peekFirst();
        if (oldest != null && fromSeq < oldest.seq()) {
            log.warn("Session {} replay requested from seq {} but oldest retained is seq {} — {} messages lost",
                sessionId, fromSeq, oldest.seq(), oldest.seq() - fromSeq);
        }

        int count = 0;
        for (var logged : messageLog) {
            if (logged.seq() > fromSeq) {
                sendJson(logged);
                count++;
            }
        }
        var done = new S2CMessage.ReplayDone(
            nextSeq(), fromSeq, seqCounter.get(), count);
        send(done);
        return this;
    }

    /**
     * True when a movement direction carries no real bearing — login/spawn
     * ("nowhere"), an unknown origin ("somewhere"), or a blank/null. Such
     * events render as plain "X arrives." / "X leaves." rather than the
     * nonsensical "X enters from nowhere." / "X leaves somewhere."
     */
    private static boolean isPlaceholderDirection(String dir) {
        if (dir == null) return true;
        var d = dir.trim().toLowerCase();
        return d.isEmpty() || d.equals("nowhere") || d.equals("somewhere") || d.equals("unknown");
    }

    private S2CMessage worldEventToS2C(WorldEvent event) {
        long seq = nextSeq();
        return switch (event) {
            case WorldEvent.Said e ->
                new S2CMessage.Prose(seq, e.entityName(), filterProse(e), List.of(), null,
                    priorityFor(e), locale);
            case WorldEvent.Whispered e ->
                new S2CMessage.Prose(seq, e.entityName(), e.text(), List.of(), null,
                    "critical", locale, "whisper");
            case WorldEvent.EntityEntered e -> {
                var catalog = ScriptMessageCatalog.forLang(locale);
                var entityName = "anonymous".equals(e.entityName())
                    ? catalog.get("ui.anonymous") : e.entityName();
                String text;
                if ("teleport".equalsIgnoreCase(e.fromDirection())) {
                    // teleport_to → "X appears." in target room
                    text = catalog.get("ui.appears", entityName);
                } else if (isPlaceholderDirection(e.fromDirection())) {
                    // Login / spawn / unknown origin — don't say "enters from nowhere".
                    text = catalog.get("ui.enters", entityName);
                } else {
                    var dirKey = "ui." + e.fromDirection();
                    var dir = catalog.hasKey(dirKey) ? catalog.get(dirKey) : e.fromDirection();
                    text = catalog.get("ui.enters_from", entityName, dir);
                }
                // "normal" — MUD arrivals are essential, not ambient noise
                yield new S2CMessage.Prose(seq, "narrator", text,
                    List.of(), null, "normal", locale);
            }
            case WorldEvent.EntityLeft e -> {
                var catalog = ScriptMessageCatalog.forLang(locale);
                var entityName = "anonymous".equals(e.entityName())
                    ? catalog.get("ui.anonymous") : e.entityName();
                String text;
                if ("teleport".equalsIgnoreCase(e.direction())) {
                    // teleport_to → "X vanishes." in source room
                    text = catalog.get("ui.vanishes", entityName);
                } else if (isPlaceholderDirection(e.direction())) {
                    // Logout / despawn / unknown exit — don't say "leaves nowhere".
                    text = catalog.get("ui.departs", entityName);
                } else {
                    var dirKey = "ui." + e.direction();
                    var dir = catalog.hasKey(dirKey) ? catalog.get(dirKey) : e.direction();
                    text = catalog.get("ui.leaves", entityName, dir);
                }
                // "normal" — MUD departures are essential, not ambient noise
                yield new S2CMessage.Prose(seq, "narrator", text,
                    List.of(), null, "normal", locale);
            }
            case WorldEvent.ObjectTaken e -> {
                var catalog = ScriptMessageCatalog.forLang(locale);
                var text = catalog.get("ui.took", e.objectName());
                yield new S2CMessage.AgentAction(seq, e.entityId(), "take", text);
            }
            case WorldEvent.ObjectDropped e -> {
                var catalog = ScriptMessageCatalog.forLang(locale);
                var text = catalog.get("ui.dropped", e.objectName());
                yield new S2CMessage.AgentAction(seq, e.entityId(), "drop", text);
            }
            case WorldEvent.ObjectUsed e ->
                new S2CMessage.AgentAction(seq, e.entityId(), "use", e.result());
            case WorldEvent.DescriptionChanged e ->
                new S2CMessage.StateChange(seq, e.reason(), null);
            case WorldEvent.HintsUpdated e ->
                new S2CMessage.Prose(seq, "narrator", null, e.hints(), null, "ambient", locale);
            case WorldEvent.PropertyChanged e ->
                new S2CMessage.StateChange(seq, "property:" + e.key(), null);
            case WorldEvent.Told e ->
                new S2CMessage.Prose(seq, e.fromEntityName(),
                    e.fromEntityName() + " tells you: " + e.text(),
                    List.of(), null, "critical", locale, "tell");
            case WorldEvent.Emoted e ->
                new S2CMessage.Prose(seq, e.entityName(), e.entityName() + " " + e.text(),
                    List.of(), null, "normal", locale, "emote");
            default -> null;
        };
    }

    /** Translate room snapshot fields using the session's locale catalog. */
    private RoomSnapshot translateSnapshot(RoomSnapshot snapshot) {
        if ("en".equals(locale)) return snapshot;
        var catalog = ScriptMessageCatalog.forLang(locale);
        var roomId = snapshot.roomId();

        var name = snapshot.name();
        var nameKey = roomId + ".name";
        if (catalog.hasKey(nameKey)) {
            name = catalog.get(nameKey);
        }

        var description = snapshot.description();
        var descKey = roomId + ".description";
        if (catalog.hasKey(descKey)) {
            description = catalog.get(descKey);
        }

        // Translate object names/descriptions if keys exist
        var objects = snapshot.objects().stream().map(obj -> {
            var objNameKey = roomId + ".obj." + obj.id() + ".name";
            var objDescKey = roomId + ".obj." + obj.id() + ".desc";
            return new RoomObject(
                obj.id(),
                catalog.hasKey(objNameKey) ? catalog.get(objNameKey) : obj.name(),
                catalog.hasKey(objDescKey) ? catalog.get(objDescKey) : obj.description(),
                obj.takeable());
        }).toList();

        // Translate exit labels if keys exist
        var exits = snapshot.exits().stream().map(exit -> {
            var exitKey = roomId + ".exit." + exit.direction();
            return new Exit(
                exit.direction(),
                exit.targetRoom(),
                catalog.hasKey(exitKey) ? catalog.get(exitKey) : exit.label());
        }).toList();

        return new RoomSnapshot(roomId, name, description, snapshot.zone(),
            snapshot.aliases(), exits, snapshot.entities(), objects, snapshot.hints());
    }

    /**
     * Parental content filter: when THIS session's member has
     * {@code content_filter = 'strict'}, prose spoken by another entity that
     * the hostility scorer flags is replaced with a blot line. The member's
     * own speech, unfiltered members, and service-not-wired all pass the
     * original text through untouched.
     */
    private String filterProse(WorldEvent.Said e) {
        var text = e.text();
        var parental = ParentalControlService.get();
        if (parental == null || sessionEntityId == null || text == null
                || sessionEntityId.equals(e.entityId())) {
            return text;
        }
        try {
            if (ParentalControlService.FILTER_STRICT.equals(parental.contentFilter(sessionEntityId))
                    && CONTENT_FILTER_SCORER.score(text).isHostile()) {
                return FILTERED_LINE;
            }
        } catch (RuntimeException ex) {
            log.debug("parental content filter check failed for {}: {}",
                sessionEntityId, ex.getMessage());
        }
        return text;
    }

    /** Determine priority for Said events. Narrator = normal, system = critical, else normal. */
    private static String priorityFor(WorldEvent.Said e) {
        return switch (e.entityName()) {
            case "system" -> "critical";
            case "narrator" -> "normal";
            default -> "normal";
        };
    }

    private long nextSeq() {
        return seqCounter.incrementAndGet();
    }

    private void send(S2CMessage msg) {
        if (messageLog.size() >= MAX_MESSAGE_LOG) {
            messageLog.pollFirst();
        }
        messageLog.addLast(msg);
        sendJson(msg);
    }

    private void sendJson(S2CMessage msg) {
        try {
            var json = Json.mapper().writeValueAsString(msg);
            sendToClient.accept(json);
        } catch (Exception e) {
            log.error("Failed to serialize S2C message for session {}", sessionId, e);
        }
    }
}
