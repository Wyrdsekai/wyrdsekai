package org.wyrdsekai.cli;

import org.wyrdsekai.common.model.*;
import org.wyrdsekai.common.protocol.PriorityLevel;
import org.wyrdsekai.common.protocol.S2CMessage;

import java.io.PrintStream;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashSet;

/**
 * Renders S2C messages to terminal output.
 * Supports normal mode (prose + hints) and --accessible mode (structured).
 * Tracks current room state for prompt and tab completion.
 */
public class Renderer {

    private final PrintStream out;
    private final boolean accessible;
    private volatile boolean verbose;

    // ANSI codes
    private static final String RESET = "\033[0m";
    private static final String BOLD = "\033[1m";
    private static final String DIM = "\033[2m";
    private static final String CYAN = "\033[36m";
    private static final String YELLOW = "\033[33m";
    private static final String GREEN = "\033[32m";
    private static final String RED = "\033[31m";
    private static final String BELL = "\007";

    private volatile String currentRoomName = "";
    private volatile RoomSnapshot currentRoom;
    private volatile List<RoomObject> lastInventory = List.of();
    // SSH-parity: hints are surfaced on demand via `actions`, not auto-dumped
    // after every line. We keep the latest set so `actions` can replay it.
    private volatile List<Hint> lastHints = List.of();

    public Renderer(PrintStream out, boolean accessible) {
        this(out, accessible, false);
    }

    public Renderer(PrintStream out, boolean accessible, boolean verbose) {
        this.out = out;
        this.accessible = accessible;
        this.verbose = verbose;
    }

    /** Toggle verbose mode (shows ambient priority messages). */
    public void setVerbose(boolean verbose) {
        this.verbose = verbose;
    }

    /** Get current room name for the prompt. */
    public String getCurrentRoomName() {
        var name = currentRoomName;
        return name.isEmpty() ? "?" : name;
    }

    /** Get current room snapshot for tab completion. */
    public RoomSnapshot getCurrentRoom() {
        return currentRoom;
    }

    /**
     * Prompt prefix matching the SSH/telnet renderer: "RoomName @zone" when the
     * zone is known, else just "RoomName". The caller appends "> ".
     */
    public String getPromptPrefix() {
        var name = getCurrentRoomName();
        var room = currentRoom;
        if (room != null && room.zone() != null && !room.zone().isBlank()) {
            return name + " @" + room.zone();
        }
        return name;
    }

    /**
     * Render the contextual actions menu on demand (the `actions` command),
     * mirroring the SSH/telnet `actions` surface. Uses the latest hints
     * captured from room-state / prose. Typing the number then selects it.
     */
    public void renderActionsMenu() {
        var hints = lastHints;
        if (hints == null || hints.isEmpty()) {
            out.println(accessible
                ? "No actions available here."
                : DIM + "Nothing to do here right now." + RESET);
            return;
        }
        if (accessible) {
            out.println("Actions:");
            for (int i = 0; i < hints.size(); i++) {
                out.println("  [" + (i + 1) + "] " + hints.get(i).label());
            }
            out.println("Type a number to choose.");
        } else {
            out.println(CYAN + "Things to do here:" + RESET);
            for (int i = 0; i < hints.size(); i++) {
                out.println(CYAN + "  [" + (i + 1) + "] " + hints.get(i).label() + RESET);
            }
            out.println(DIM + "(type a number to choose)" + RESET);
        }
    }

    /** Get last known inventory for tab completion. */
    public List<RoomObject> getLastInventory() {
        return lastInventory;
    }

    public void render(S2CMessage msg) {
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
            case S2CMessage.TopologyChanged tc -> {} // handled by map UI
            case S2CMessage.MapData md -> {} // handled by map UI
            case S2CMessage.ZoneResponse zr -> renderZoneResponse(zr);
            case S2CMessage.VoiceAudio va -> {} // voice audio handled by platform-specific player
        }
    }

    private void renderZoneResponse(S2CMessage.ZoneResponse msg) {
        out.println(CYAN + "[" + msg.namespace() + "]" + RESET + " " + msg.text());
    }

    private void renderRoomState(S2CMessage.RoomState msg) {
        var room = msg.room();
        currentRoomName = room.name();
        currentRoom = room;
        if (msg.inventory() != null) {
            lastInventory = msg.inventory();
        }

        if (accessible) {
            renderStructuredRoom(room, msg.inventory());
            return;
        }

        // SSH/telnet-parity room render: a compact header, then one line each
        // for who's Present, what's Here, what you're Carrying and the Exits —
        // no per-entity "X is here." spam, no auto-numbered actions menu. The
        // hints surface on demand via `actions` (stored in lastHints below).
        lastHints = room.hints() != null ? room.hints() : List.of();

        out.println();
        out.println(BOLD + CYAN + room.name() + RESET);
        out.println(room.description());
        out.println();

        // Present: deduped entity names ( — posture suffix).
        if (!room.entities().isEmpty()) {
            var seen = new LinkedHashSet<String>();
            var parts = new ArrayList<String>();
            for (var entity : room.entities()) {
                if (!seen.add(entity.name())) continue;
                var suffix = (entity.posture() != null
                    && entity.posture().descriptor() != null
                    && !entity.posture().descriptor().isBlank())
                    ? " (" + entity.posture().descriptor() + ")"
                    : "";
                parts.add(entity.name() + suffix);
            }
            out.println(GREEN + "Present: " + String.join(", ", parts) + RESET);
        }

        // Here: compact visible-object list (was one line per object).
        var visible = room.objects().stream()
            .filter(RoomObject::visible)
            .map(RoomObject::name)
            .toList();
        if (!visible.isEmpty()) {
            out.println(YELLOW + "Here: " + String.join(", ", visible) + RESET);
        }

        // Carrying
        if (msg.inventory() != null && !msg.inventory().isEmpty()) {
            var names = msg.inventory().stream().map(RoomObject::name).toList();
            out.println(DIM + "Carrying: " + String.join(", ", names) + RESET);
        }

        // Exits
        if (!room.exits().isEmpty()) {
            var dirs = room.exits().stream().map(Exit::direction).toList();
            out.println(DIM + "Exits: " + String.join(", ", dirs) + RESET);
        }

        // Onboarding footer — one line, mirrors the SSH/telnet renderer.
        out.println();
        out.println(DIM + "(type `actions` for things to do here, `help` for commands)" + RESET);
    }

    private void renderProse(S2CMessage.Prose msg) {
        var priority = PriorityLevel.fromWire(msg.priority());

        // Ambient messages suppressed unless verbose
        if (priority == PriorityLevel.AMBIENT && !verbose) return;

        if (accessible) {
            // Critical: bell + immediate announce
            if (priority == PriorityLevel.CRITICAL) {
                out.print(BELL);
                out.println("[CRITICAL] [" + msg.speaker() + "] " + msg.text());
            } else {
                out.println("[" + msg.speaker() + "] " + msg.text());
            }
        } else {
            String prefix = switch (msg.speaker()) {
                case "narrator" -> DIM;
                case "system" -> YELLOW;
                default -> BOLD + msg.speaker() + ": " + RESET;
            };
            // Priority-aware styling
            String line = switch (priority) {
                case CRITICAL -> BOLD + RED + prefix + msg.text() + RESET;
                case AMBIENT -> DIM + prefix + msg.text() + RESET;
                default -> prefix + msg.text() + RESET;
            };
            out.println(line);
        }

        if (msg.hints() != null && !msg.hints().isEmpty()) {
            // Keep the latest hints for on-demand `actions`. In accessible mode
            // we still announce them inline (screen-reader users want the
            // options immediately); normal terminals defer to `actions`.
            lastHints = msg.hints();
            if (accessible) {
                renderHints(msg.hints());
            }
        }

        if (accessible && msg.structured() != null) {
            renderStructured(msg.structured());
        }

        renderContentBlocks(msg.blocks());
    }

    private void renderAgentAction(S2CMessage.AgentAction msg) {
        if (accessible) {
            out.println("[action] " + msg.agentName() + " " + msg.action() + ": " + msg.description());
        } else {
            out.println(DIM + "* " + msg.agentName() + " " + msg.description() + RESET);
        }
    }

    private void renderStateChange(S2CMessage.StateChange msg) {
        if (accessible) {
            out.println("[change] " + msg.description());
        } else {
            out.println(DIM + "~ " + msg.description() + RESET);
        }
        renderContentBlocks(msg.blocks());
    }

    private void renderReplayDone(S2CMessage.ReplayDone msg) {
        out.println(DIM + "[Reconnected — replayed " + msg.count() + " messages]" + RESET);
    }

    private void renderError(S2CMessage.Error msg) {
        out.println(RED + "Error [" + msg.code() + "]: " + msg.message() + RESET);
    }

    private void renderNotification(S2CMessage.Notification msg) {
        String prefix = switch (msg.level()) {
            case "warning" -> YELLOW;
            case "error" -> RED;
            default -> CYAN;
        };
        out.println(prefix + "[" + msg.title() + "] " + msg.message() + RESET);
    }

    private void renderTransit(S2CMessage.Transit msg) {
        out.println();
        out.println(BOLD + CYAN + "Transit" + RESET);
        out.println(msg.message());
        if (msg.targetUrl() != null) {
            out.println("Target: " + msg.targetUrl());
        }
        if (msg.transitToken() != null) {
            out.println("Token: " + msg.transitToken());
        }
    }

    private void renderTokenStream(S2CMessage.TokenStream msg) {
        if (accessible) {
            if (msg.done()) {
                out.println(msg.token());
            } else {
                out.print(msg.token());
            }
        } else {
            if (msg.done()) {
                out.println(msg.token() + RESET);
            } else {
                out.print(DIM + msg.token());
            }
        }
        out.flush();
    }

    private void renderHints(List<Hint> hints) {
        if (hints == null || hints.isEmpty()) return;
        out.println();
        for (int i = 0; i < hints.size(); i++) {
            if (accessible) {
                out.println("  [" + (i + 1) + "] " + hints.get(i).label());
            } else {
                out.println(CYAN + "  [" + (i + 1) + "] " + hints.get(i).label() + RESET);
            }
        }
    }

    private void renderStructuredRoom(RoomSnapshot room, List<RoomObject> inventory) {
        out.println("Room: " + room.name());
        out.println("Description: " + room.description());
        out.println("Zone: " + room.zone());
        if (!room.entities().isEmpty()) {
            out.println("Entities:");
            for (var e : room.entities()) {
                out.println("  - " + e.name() + " (" + e.type() + ")");
            }
        }
        if (!room.objects().isEmpty()) {
            out.println("Objects:");
            for (var o : room.objects()) {
                out.println("  - " + o.name() + ": " + o.description());
            }
        }
        if (inventory != null && !inventory.isEmpty()) {
            out.println("Inventory:");
            for (var o : inventory) {
                out.println("  - " + o.name() + ": " + o.description());
            }
        }
        if (!room.exits().isEmpty()) {
            out.println("Exits:");
            for (var e : room.exits()) {
                out.println("  - " + e.direction() + " → " + e.label());
            }
        }
        renderHints(room.hints());
    }

    private void renderContentBlocks(List<ContentBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) return;
        for (var block : blocks) {
            // CLI always renders fallback text — no rich renderers in terminal
            if (block.fallback() != null && !block.fallback().isEmpty()) {
                if (accessible) {
                    out.println("  [" + block.format() + "] " + block.fallback());
                } else {
                    out.println(DIM + "  " + block.fallback() + RESET);
                }
            }
        }
    }

    private void renderStructured(Structured s) {
        out.println("  [structured]");
        if (s.exits() != null) {
            for (var e : s.exits()) {
                out.println("    exit: " + e.direction() + " → " + e.label());
            }
        }
        if (s.entities() != null) {
            for (var e : s.entities()) {
                out.println("    entity: " + e.name() + " (" + e.type() + ")");
            }
        }
        if (s.objects() != null) {
            for (var o : s.objects()) {
                out.println("    object: " + o.name() + " — " + o.description());
            }
        }
    }
}
