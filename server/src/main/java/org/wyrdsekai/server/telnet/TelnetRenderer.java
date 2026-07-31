package org.wyrdsekai.server.telnet;

import org.wyrdsekai.common.model.*;
import org.wyrdsekai.common.protocol.PriorityLevel;
import org.wyrdsekai.common.protocol.S2CMessage;
import org.wyrdsekai.scripting.i18n.ScriptMessageCatalog;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Renders S2C messages as plain text for Telnet clients.
 * Optionally sends GMCP structured data alongside prose.
 */
public class TelnetRenderer {

    private final OutputStream out;
    private final boolean gmcpEnabled;
    private volatile String locale = "en";

    public TelnetRenderer(OutputStream out, boolean gmcpEnabled) {
        this.out = out;
        this.gmcpEnabled = gmcpEnabled;
    }

    /** Update the locale used for resolving hint labels. */
    public void setLocale(String locale) {
        this.locale = locale != null ? locale : "en";
    }

    public void render(S2CMessage msg) throws IOException {
        switch (msg) {
            case S2CMessage.RoomState rs -> renderRoomState(rs);
            case S2CMessage.Prose prose -> renderProse(prose);
            case S2CMessage.AgentAction action -> renderAgentAction(action);
            case S2CMessage.StateChange change -> renderStateChange(change);
            case S2CMessage.ReplayDone done -> renderReplayDone(done);
            case S2CMessage.Error error -> renderError(error);
            case S2CMessage.Notification notif -> renderNotification(notif);
            case S2CMessage.Transit transit -> renderTransit(transit);
            case S2CMessage.TokenStream ts -> renderTokenStream(ts);
            case S2CMessage.TopologyChanged tc -> {} // no telnet rendering
            case S2CMessage.MapData md -> {} // no telnet rendering
            case S2CMessage.ZoneResponse zr -> renderZoneResponse(zr);
            case S2CMessage.VoiceAudio va -> {} // no telnet rendering for voice audio
        }
    }

    private void renderRoomState(S2CMessage.RoomState msg) throws IOException {
        var room = msg.room();

        TelnetCodec.sendLine(out, "");
        TelnetCodec.sendLine(out, room.name());
        // Split multi-line descriptions so each embedded newline gets proper
        // CR+LF termination — a bare \n (e.g. from the themed atmosphere line or
        // a phase-ambient overlay) stair-steps in SSH/telnet raw mode otherwise.
        for (var part : room.description().split("\n", -1)) {
            TelnetCodec.sendLine(out, part);
        }
        TelnetCodec.sendLine(out, "");

        // Dedupe entities by name — prevents "Masumi is here" twice when
        // the same player has two concurrent sessions mid-takeover.
        // append "(<posture descriptor>)" when the entity
        // has a posture set, so observers see body state on look. Keep one
        // formatted label per unique name; first occurrence wins for posture.
        if (!room.entities().isEmpty()) {
            var seen = new LinkedHashSet<String>();
            var parts = new ArrayList<String>();
            for (var entity : room.entities()) {
                if (!seen.add(entity.name())) continue;
                if (entity.posture() != null && entity.posture().descriptor() != null
                        && !entity.posture().descriptor().isBlank()) {
                    parts.add(entity.name() + " (" + entity.posture().descriptor() + ")");
                } else {
                    parts.add(entity.name());
                }
            }
            TelnetCodec.sendLine(out, "Present: " + String.join(", ", parts));
        }

        // Compact object list (was one line per object — flooded Studies with
        // 20+ furniture items). `actions` menu still surfaces per-object
        // interactions; here we just tell the player what's visible.
        if (!room.objects().isEmpty()) {
            var visible = room.objects().stream()
                .filter(RoomObject::visible)
                .map(RoomObject::name)
                .toList();
            if (!visible.isEmpty()) {
                TelnetCodec.sendLine(out, "Here: " + String.join(", ", visible));
            }
        }

        // Inventory
        if (msg.inventory() != null && !msg.inventory().isEmpty()) {
            var names = msg.inventory().stream()
                .map(RoomObject::name).toList();
            TelnetCodec.sendLine(out, "Carrying: " + String.join(", ", names));
        }

        if (!room.exits().isEmpty()) {
            var exits = room.exits();
            if (exits.size() <= 4) {
                // Few exits: keep the full "direction → destination" mapping.
                // Uses the exit label when it names a room; falls back to
                // targetRoom id.
                var parts = new ArrayList<String>();
                for (var e : exits) {
                    var dest = extractDestination(e);
                    parts.add(dest == null || dest.isBlank()
                        ? e.direction()
                        : e.direction() + " → " + dest);
                }
                TelnetCodec.sendLine(out, "Exits: " + String.join(", ", parts));
            } else {
                // Hub rooms (the Nexus has 11 exits): the full mapping is a
                // wall of text on every look. Directions only here; `exits`
                // prints the destinations, `map` draws the layout.
                var dirs = new ArrayList<String>();
                for (var e : exits) dirs.add(e.direction());
                TelnetCodec.sendLine(out, "Exits: " + String.join(", ", dirs)
                    + "  (`exits` lists destinations, `map` shows the layout)");
            }
        }

        // Gentle onboarding footer — new users don't yet know `actions`/`help`
        // exist. One line, not a wall of enumerated options.
        TelnetCodec.sendLine(out, "");
        TelnetCodec.sendLine(out,
            "(type `actions` for things to do here, `help` for commands)");

        // GMCP Room.Info
        if (gmcpEnabled) {
            var roomInfo = new LinkedHashMap<String, Object>();
            roomInfo.put("name", room.name());
            roomInfo.put("id", room.roomId());
            roomInfo.put("zone", room.zone());
            var exits = new LinkedHashMap<String, String>();
            for (var exit : room.exits()) {
                exits.put(exit.direction(), exit.targetRoom());
            }
            roomInfo.put("exits", exits);
            TelnetCodec.sendGmcp(out, "Room.Info", roomInfo);

            // GMCP Char.Items.Inv
            if (msg.inventory() != null) {
                var items = new ArrayList<Map<String, Object>>();
                for (var obj : msg.inventory()) {
                    var item = new LinkedHashMap<String, Object>();
                    item.put("id", obj.id());
                    item.put("name", obj.name());
                    item.put("desc", obj.description());
                    items.add(item);
                }
                TelnetCodec.sendGmcp(out, "Char.Items.Inv", items);
            }
        }
    }

    private void renderProse(S2CMessage.Prose msg) throws IOException {
        var priority = PriorityLevel.fromWire(msg.priority());

        // Ambient messages suppressed in telnet (no verbose toggle yet)
        if (priority == PriorityLevel.AMBIENT) return;

        String line;
        if ("emote".equals(msg.style())) {
            line = "* " + msg.text();
        } else if ("whisper".equals(msg.style())) {
            line = msg.text();  // already formatted as "X whispers to you: ..."
        } else if ("tell".equals(msg.style())) {
            line = msg.text();  // already formatted as "X tells you: ..."
        } else {
            line = switch (msg.speaker()) {
                case "narrator" -> msg.text();
                case "system" -> "[System] " + msg.text();
                default -> msg.speaker() + ": " + msg.text();
            };
        }

        if (priority == PriorityLevel.CRITICAL) {
            for (var part : ("** " + line + " **").split("\n", -1)) {
                TelnetCodec.sendLine(out, part);
            }
        } else {
            // Split multi-line prose so each embedded newline gets proper
            // CR+LF termination — bare \n in a line causes stair-step
            // rendering in SSH/telnet clients.
            for (var part : line.split("\n", -1)) {
                TelnetCodec.sendLine(out, part);
            }
        }

        if (msg.hints() != null && !msg.hints().isEmpty()) {
            renderHints(msg.hints());
        }
    }

    private void renderAgentAction(S2CMessage.AgentAction msg) throws IOException {
        TelnetCodec.sendLine(out, "* " + msg.agentName() + " " + msg.description());
    }

    private void renderStateChange(S2CMessage.StateChange msg) throws IOException {
        TelnetCodec.sendLine(out, "~ " + msg.description());
    }

    private void renderReplayDone(S2CMessage.ReplayDone msg) throws IOException {
        TelnetCodec.sendLine(out, "[Reconnected — replayed " + msg.count() + " messages]");
    }

    private void renderError(S2CMessage.Error msg) throws IOException {
        TelnetCodec.sendLine(out, "Error [" + msg.code() + "]: " + msg.message());
    }

    private void renderZoneResponse(S2CMessage.ZoneResponse msg) throws IOException {
        TelnetCodec.sendLine(out, "[" + msg.namespace() + "] " + msg.text());
    }

    private void renderNotification(S2CMessage.Notification msg) throws IOException {
        TelnetCodec.sendLine(out, "[" + msg.title() + "] " + msg.message());
    }

    private void renderTransit(S2CMessage.Transit msg) throws IOException {
        TelnetCodec.sendLine(out, "");
        TelnetCodec.sendLine(out, "[Transit] " + msg.message());
        if (msg.targetUrl() != null) {
            TelnetCodec.sendLine(out, "Target: " + msg.targetUrl());
        }
    }

    private void renderTokenStream(S2CMessage.TokenStream msg) throws IOException {
        if (msg.done()) {
            TelnetCodec.sendLine(out, msg.token());
        } else {
            TelnetCodec.sendRaw(out, msg.token());
        }
    }

    /**
     * Extract a human-readable destination from an Exit. Exit labels in
     * foundation-rooms.json typically read "An archway opens east to The Docks"
     * or "Step out to The Nexus". We pull the trailing "to <Name>" clause; if
     * that pattern doesn't match, fall back to the raw targetRoom id (which
     * at least tells the user where they'd end up).
     */
    /** Public so the SSH `exits` command renders the same mapping. */
    public static String extractDestination(Exit e) {
        var label = e.label();
        if (label != null && !label.isBlank()) {
            var idx = label.lastIndexOf(" to ");
            if (idx > 0 && idx + 4 < label.length()) {
                return label.substring(idx + 4).trim();
            }
        }
        return e.targetRoom();
    }

    /**
     * Render a numbered menu of available actions on demand (triggered by
     * the `actions` command). Surfaces per-object hints, then
     * room-specific special verbs (e.g., Docks has federation commands
     * that live in JS {@code onSay} handlers, not in the hint list), then
     * the always-available MUD commands as a discovery floor.
     */
    public void renderActionsMenu(List<Hint> hints, String roomName, String roomId) throws IOException {
        TelnetCodec.sendLine(out, "");
        TelnetCodec.sendLine(out, "Actions here:");
        if (hints != null && !hints.isEmpty()) {
            var catalog = ScriptMessageCatalog.forLang(locale);
            for (int i = 0; i < hints.size(); i++) {
                var label = resolveHintLabel(hints.get(i), catalog);
                TelnetCodec.sendLine(out, "  [" + (i + 1) + "] " + label);
            }
        } else {
            TelnetCodec.sendLine(out, "  (nothing specific — just conversation and exits)");
        }

        // Room-specific specials — verbs handled by room scripts, not hints.
        renderRoomSpecificActions(roomName, roomId);

        TelnetCodec.sendLine(out, "");
        TelnetCodec.sendLine(out, "Always available:");
        TelnetCodec.sendLine(out, "  look / l       describe this room");
        TelnetCodec.sendLine(out, "  exits          list the ways out");
        TelnetCodec.sendLine(out, "  go <dir>       move (north, east, … or a room name)");
        TelnetCodec.sendLine(out, "  inventory / i  what you're carrying");
        TelnetCodec.sendLine(out, "  examine <x>    look at something more closely");
        TelnetCodec.sendLine(out, "  say <text>     speak to everyone in the room");
        TelnetCodec.sendLine(out, "  tell <who> <x> message someone directly");
        TelnetCodec.sendLine(out, "  who            who's here + zone roster");
        TelnetCodec.sendLine(out, "  home           return to your Study");
        TelnetCodec.sendLine(out, "  help           full command reference");
        TelnetCodec.sendLine(out, "  quit           leave the world");
    }

    /** Back-compat overload — old callers without roomName/roomId. */
    public void renderActionsMenu(List<Hint> hints) throws IOException {
        renderActionsMenu(hints, null, null);
    }

    private void renderRoomSpecificActions(String roomName, String roomId) throws IOException {
        // Docks-specific: federation + travel commands. Handled by
        // scripts/rooms/docks.js onSay — not in the hint list, so we
        // document them explicitly here or newcomers can't discover them.
        boolean isDocks = (roomId != null && roomId.toLowerCase().contains("dock"))
            || (roomName != null && roomName.toLowerCase().contains("dock"));
        if (isDocks) {
            TelnetCodec.sendLine(out, "");
            TelnetCodec.sendLine(out, "In The Docks:");
            TelnetCodec.sendLine(out, "  say travel <contact>:<label>  cross to a federated zone");
            TelnetCodec.sendLine(out, "  say manifest                  this zone's federation status");
            TelnetCodec.sendLine(out, "  say arrivals                  who's currently visiting");
            TelnetCodec.sendLine(out, "  say propose <zone>            steward: propose federation");
            TelnetCodec.sendLine(out, "  say accept <zone>             steward: accept a proposal");
            TelnetCodec.sendLine(out, "  say help                      docks command reference");
        }
    }

    private void renderHints(List<Hint> hints) throws IOException {
        if (hints == null || hints.isEmpty()) return;
        TelnetCodec.sendLine(out, "");
        var catalog = ScriptMessageCatalog.forLang(locale);
        for (int i = 0; i < hints.size(); i++) {
            var displayLabel = resolveHintLabel(hints.get(i), catalog);
            TelnetCodec.sendLine(out, "  [" + (i + 1) + "] " + displayLabel);
        }
    }

    /**
     * Resolve a hint's display label.
     * If the label is an unresolved i18n key (the catalog has a different value for it),
     * resolve it. For parameterized keys like "ui.go", extract the argument from the
     * hint's action field (e.g., "go:north" -> direction).
     */
    private String resolveHintLabel(Hint hint, ScriptMessageCatalog catalog) {
        // First try explicit labelKey if present
        var key = hint.labelKey();
        if (key != null && !key.isEmpty() && catalog.hasKey(key)) {
            var param = extractActionParam(hint.action());
            if (param != null) {
                var resolvedParam = resolveDirectionParam(param, catalog);
                return catalog.get(key, resolvedParam);
            }
            return catalog.get(key);
        }

        // Check if label itself is an unresolved i18n key
        var label = hint.label();
        if (label != null && catalog.hasKey(label)) {
            var resolved = catalog.get(label);
            if (!resolved.equals(label)) {
                // It's a key — resolve it, substituting action param if needed
                var param = extractActionParam(hint.action());
                if (param != null && resolved.contains("{0}")) {
                    var resolvedParam = resolveDirectionParam(param, catalog);
                    return catalog.get(label, resolvedParam);
                }
                return resolved;
            }
        }

        return label;
    }

    /** Extract the parameter after ':' in an action string (e.g., "go:north" -> "north"). */
    private static String extractActionParam(String action) {
        if (action == null) return null;
        int colonIdx = action.indexOf(':');
        return colonIdx >= 0 ? action.substring(colonIdx + 1) : null;
    }

    /** Resolve a direction parameter through the catalog (e.g., "north" -> catalog "ui.north"). */
    private static String resolveDirectionParam(String param, ScriptMessageCatalog catalog) {
        var dirKey = "ui." + param;
        return catalog.hasKey(dirKey) ? catalog.get(dirKey) : param;
    }

    /** Send a raw line to the client. Used by link-takeover + helper paths
     *  that want to render outside of the typed S2CMessage flow. */
    public void sendLine(String text) throws IOException {
        TelnetCodec.sendLine(out, text);
    }

    /** Send the input prompt (zone-unqualified). */
    public void sendPrompt(String roomName) throws IOException {
        TelnetCodec.sendRaw(out, roomName + "> ");
    }

    /**
     * Send the input prompt with zone label. Format: {@code "<room> @<zone>> "}.
     * Lets users see at a glance which zone they're in — especially important
     * when proxied into a foreign zone where the room name may be identical
     * to a room at home (e.g. both zones have "The Docks").
     */
    public void sendPrompt(String roomName, String zoneLabel) throws IOException {
        if (zoneLabel == null || zoneLabel.isBlank()) {
            TelnetCodec.sendRaw(out, roomName + "> ");
        } else {
            TelnetCodec.sendRaw(out, roomName + " @" + zoneLabel + "> ");
        }
    }
}
