package org.wyrdsekai.cli;

import org.wyrdsekai.common.protocol.C2SMessage;
import org.wyrdsekai.common.protocol.CommandParser;
import org.wyrdsekai.common.protocol.CommandParser.ParsedCommand;

import java.io.PrintStream;
import java.util.List;

/**
 * Parses user input via CommandParser and dispatches C2S messages.
 * Handles client-local commands (/help, /login, /register, /whoami, /logout).
 */
public class InputHandler {

    private final PrintStream out;
    private WyrdSession connection;
    private AuthClient authClient;
    private Renderer renderer;
    private String currentRoomId = "nexus";
    private String currentUsername;

    public InputHandler(WyrdSession connection, PrintStream out) {
        this.connection = connection;
        this.out = out;
    }

    public void setConnection(WyrdSession connection) {
        this.connection = connection;
    }

    /** Wire the renderer so `actions` can replay the latest contextual hints
     *  locally (SSH/telnet parity — the menu is on demand, not auto-dumped). */
    public void setRenderer(Renderer renderer) {
        this.renderer = renderer;
    }

    public void setAuthClient(AuthClient authClient) {
        this.authClient = authClient;
    }

    public void setCurrentRoomId(String roomId) {
        this.currentRoomId = roomId;
    }

    public String getCurrentUsername() {
        return currentUsername;
    }

    /** Set the active username (e.g. after the startup login gate authenticates). */
    public void setCurrentUsername(String username) {
        this.currentUsername = username;
    }

    /** Parse input line and send appropriate C2S message. Returns false if /quit. */
    public boolean handle(String input) {
        // `actions` / `/actions` — render the contextual menu locally from the
        // latest hints (SSH/telnet parity). Intercept before parsing so it
        // never routes to the room as speech.
        if (renderer != null && input != null) {
            var t = input.trim();
            if (t.equalsIgnoreCase("actions") || t.equalsIgnoreCase("/actions")) {
                renderer.renderActionsMenu();
                return true;
            }
        }

        var cmd = CommandParser.parse(input);
        if (cmd == null) return true;

        return switch (cmd) {
            case ParsedCommand.Quit q -> {
                // Detach THIS channel only. The account stays present on any other
                // surfaces (SSH/web/phone); use logout to leave everywhere. The CLI
                // can't see the session registry, so the server computes how many
                // other channels remain and pushes the SAME detach hint SSH/telnet
                // render inline, then closes us. prepareClose() suppresses the
                // auto-reconnect; awaitClosed() lets the hint render before we exit.
                connection.prepareClose();
                connection.send(new C2SMessage.Command(
                    connection.newId(), "quit", List.of()));
                connection.awaitClosed(1500);
                yield false;
            }

            case ParsedCommand.Logout lo -> {
                // End the whole presence — server drops every channel for this
                // account (it holds the session registry), then closes this one.
                // Same server-driven close as quit: wait for the goodbye to render.
                connection.prepareClose();
                connection.send(new C2SMessage.Command(
                    connection.newId(), "logout", List.of()));
                connection.awaitClosed(1500);
                yield false;
            }

            case ParsedCommand.Sessions se -> {
                // List/kill the account's live channels — server-side action.
                connection.send(new C2SMessage.Command(
                    connection.newId(), "sessions", se.args()));
                yield true;
            }

            case ParsedCommand.Key k -> {
                // Manage this account's own SSH keys (list/add/remove) — server-side
                // action, scoped to the authenticated user. Same generic Command
                // route as `sessions`; the server dispatches "key" to SessionCommands.
                connection.send(new C2SMessage.Command(
                    connection.newId(), "key", k.args()));
                yield true;
            }

            case ParsedCommand.HintSelect hs -> {
                connection.send(new C2SMessage.HintSelect(
                    connection.newId(), currentRoomId, hs.index()));
                yield true;
            }

            case ParsedCommand.SlashCommand sc -> handleSlashCommand(sc);

            case ParsedCommand.Look l -> {
                connection.send(new C2SMessage.Look(
                    connection.newId(), currentRoomId));
                yield true;
            }

            case ParsedCommand.Go go -> {
                connection.send(new C2SMessage.Go(
                    connection.newId(), currentRoomId, go.direction()));
                yield true;
            }

            case ParsedCommand.Take take -> {
                connection.send(new C2SMessage.Take(
                    connection.newId(), currentRoomId, take.objectName()));
                yield true;
            }

            case ParsedCommand.Drop drop -> {
                connection.send(new C2SMessage.Drop(
                    connection.newId(), currentRoomId, drop.objectName()));
                yield true;
            }

            case ParsedCommand.Use use -> {
                connection.send(new C2SMessage.Use(
                    connection.newId(), currentRoomId, use.objectName(), use.target()));
                yield true;
            }

            case ParsedCommand.Say say -> {
                connection.send(new C2SMessage.Say(
                    connection.newId(), currentRoomId, say.text()));
                yield true;
            }

            case ParsedCommand.Tell tell -> {
                // Tell routes as a say for now — cross-room EntityRegistry TODO
                connection.send(new C2SMessage.Say(
                    connection.newId(), currentRoomId,
                    "tell " + tell.targetName() + " " + tell.text()));
                yield true;
            }

            case ParsedCommand.MapCommand mc -> {
                connection.send(new C2SMessage.MapRequest(
                    connection.newId(), "map", mc.radius(), null));
                yield true;
            }
            case ParsedCommand.Where w -> {
                connection.send(new C2SMessage.MapRequest(
                    connection.newId(), "where", 0, null));
                yield true;
            }
            case ParsedCommand.Nearby n -> {
                connection.send(new C2SMessage.MapRequest(
                    connection.newId(), "nearby", 1, null));
                yield true;
            }
            case ParsedCommand.Rooms r -> {
                connection.send(new C2SMessage.MapRequest(
                    connection.newId(), "rooms", 0, null));
                yield true;
            }
            case ParsedCommand.Path p -> {
                connection.send(new C2SMessage.MapRequest(
                    connection.newId(), "path", 0, p.targetRoom()));
                yield true;
            }
            case ParsedCommand.Exits e -> {
                connection.send(new C2SMessage.MapRequest(
                    connection.newId(), "exits", 0, null));
                yield true;
            }

            case ParsedCommand.Office o -> {
                // Go to player's private Study. Must be a Command (routes to the
                // server's home-teleport handler), NOT a Go — a Go treats "office"
                // as a compass direction and fails with no_exit.
                connection.send(new C2SMessage.Command(
                    connection.newId(), "office", List.of()));
                yield true;
            }

            case ParsedCommand.Emote emote -> {
                connection.send(new C2SMessage.Say(
                    connection.newId(), currentRoomId, ":" + emote.text()));
                yield true;
            }

            case ParsedCommand.Whisper whisper -> {
                connection.send(new C2SMessage.Say(
                    connection.newId(), currentRoomId,
                    ">" + whisper.target() + " " + whisper.text()));
                yield true;
            }

            case ParsedCommand.Describe desc -> {
                // Forward describe as a Say so the server can process it
                connection.send(new C2SMessage.Say(
                    connection.newId(), currentRoomId,
                    "@describe " + desc.target() + "=" + desc.text()));
                yield true;
            }

            case ParsedCommand.Examine ex -> {
                // SPEC §2.2. CLI sends the typed Examine envelope so the
                // server runs the dedicated ExamineLookup chain (no onUse,
                // no ObjectUsed, no room re-render).
                connection.send(new C2SMessage.Examine(
                    connection.newId(), currentRoomId, ex.target()));
                yield true;
            }

            case ParsedCommand.Rename rn -> {
                // SPEC §7.4. CLI sends the typed Rename envelope so the
                // server runs the shared RenameService chain (validation,
                // persistence, room sync, EntityRegistry refresh).
                connection.send(new C2SMessage.Rename(
                    connection.newId(), rn.target(), rn.newName()));
                yield true;
            }

            case ParsedCommand.Give give -> {
                // Forward as say command — server will handle give semantics
                connection.send(new C2SMessage.Say(
                    connection.newId(), currentRoomId,
                    "give " + give.objectName() + " to " + give.targetName()));
                yield true;
            }

            case ParsedCommand.Score sc -> {
                connection.send(new C2SMessage.Command(
                    connection.newId(), "score", List.of()));
                yield true;
            }

            case ParsedCommand.Shout shout -> {
                connection.send(new C2SMessage.Say(
                    connection.newId(), currentRoomId, "[shout] " + shout.text()));
                yield true;
            }

            case ParsedCommand.Reply reply -> {
                // CLI doesn't track tells — forward as say for now
                connection.send(new C2SMessage.Say(
                    connection.newId(), currentRoomId, reply.text()));
                yield true;
            }

            case ParsedCommand.Follow follow -> {
                connection.send(new C2SMessage.Command(
                    connection.newId(), "follow", List.of(follow.targetName())));
                yield true;
            }

            case ParsedCommand.Afk afk -> {
                out.println("AFK: " + afk.message());
                yield true;
            }

            case ParsedCommand.Brief b -> {
                out.println("Brief mode toggled.");
                yield true;
            }

            case ParsedCommand.Unknown u -> {
                out.println("Huh? Use 'text to say, :text to emote, or /help for commands.");
                yield true;
            }

            case ParsedCommand.Alias a -> {
                out.println("Alias set: " + a.name() + " -> " + a.expansion());
                yield true;
            }

            case ParsedCommand.Unalias u -> {
                out.println("Alias removed: " + u.name());
                yield true;
            }

            case ParsedCommand.GrantWard gw -> {
                out.println("Grant ward to " + gw.agentName() + " — use server SSH/telnet for this.");
                yield true;
            }
            case ParsedCommand.RevokeWard rw -> {
                out.println("Revoke ward from " + rw.agentName() + " — use server SSH/telnet for this.");
                yield true;
            }
            case ParsedCommand.Invite inv -> {
                out.println("Invite " + inv.agentName() + " — use server SSH/telnet for this.");
                yield true;
            }
            case ParsedCommand.Dismiss dis -> {
                out.println("Dismiss " + dis.agentName() + " — use server SSH/telnet for this.");
                yield true;
            }
            case ParsedCommand.AbortPlan _ -> {
                connection.send(new C2SMessage.Say(
                    connection.newId(), currentRoomId, "abort"));
                yield true;
            }
            case ParsedCommand.Sit sit -> {
                // CLI client routes sit/stand through the say channel for the
                // server-side parser to re-parse and dispatch.
                // — full body-state wiring is a server-side dispatcher concern.
                var line = sit.target() == null ? "sit"
                    : "sit at " + sit.target();
                connection.send(new C2SMessage.Say(connection.newId(), currentRoomId, line));
                yield true;
            }
            case ParsedCommand.Stand _ -> {
                connection.send(new C2SMessage.Say(connection.newId(), currentRoomId, "stand"));
                yield true;
            }
        };
    }

    private boolean handleSlashCommand(ParsedCommand.SlashCommand sc) {
        return switch (sc.command()) {
            case "help" -> { printHelp(); yield true; }
            case "login" -> { handleLogin(sc); yield true; }
            case "register" -> { handleRegister(sc); yield true; }
            case "logout" -> { handleLogout(); yield true; }
            case "whoami" -> { handleWhoami(); yield true; }
            default -> {
                // Forward unknown slash commands to server
                connection.send(new C2SMessage.Command(
                    connection.newId(), sc.command(), sc.args()));
                yield true;
            }
        };
    }

    private void handleLogin(ParsedCommand.SlashCommand sc) {
        if (authClient == null) {
            out.println("Auth not available.");
            return;
        }
        if (sc.args().size() < 2) {
            out.println("Usage: /login <username> <password>");
            return;
        }
        var result = authClient.login(sc.args().get(0), sc.args().get(1));
        if (result.success()) {
            currentUsername = result.username();
            out.println("Logged in as " + result.username() + ". Reconnecting...");
            connection.setToken(result.token());
            connection.reconnectWithToken();
        } else {
            out.println("Login failed: " + result.error());
        }
    }

    private void handleRegister(ParsedCommand.SlashCommand sc) {
        if (authClient == null) {
            out.println("Auth not available.");
            return;
        }
        if (sc.args().size() < 2) {
            out.println("Usage: /register <username> <password> [display_name]");
            return;
        }
        var displayName = sc.args().size() > 2
            ? String.join(" ", sc.args().subList(2, sc.args().size()))
            : null;
        var result = authClient.register(sc.args().get(0), sc.args().get(1), displayName);
        if (result.success()) {
            currentUsername = result.username();
            out.println("Registered as " + result.username() + ". Reconnecting...");
            connection.setToken(result.token());
            connection.reconnectWithToken();
        } else {
            out.println("Registration failed: " + result.error());
        }
    }

    private void handleLogout() {
        currentUsername = null;
        connection.setToken(null);
        connection.reconnectWithToken();
        out.println("Logged out. Reconnecting as anonymous...");
    }

    private void handleWhoami() {
        if (currentUsername != null) {
            out.println("You are: " + currentUsername);
        } else {
            out.println("You are: anonymous");
        }
    }

    private void printHelp() {
        out.println("""
            Commands:
              look (l)                     - Look around the room
              go <direction>               - Move in a direction
              n, s, e, w, u, d             - Quick direction shortcuts
              say <text>                   - Say something (or just type anything)
              take <object>                - Pick up an object
              drop <object>                - Drop an object
              use <object> [on <target>]   - Use an object
              inventory (i)                - Show what you're carrying
              1-9                          - Select a hint by number
              /login <user> <pass>         - Log in
              /register <user> <pass>      - Create account
              /logout                      - Log out
              /whoami                      - Show current identity
              /help                        - Show this help
              /quit                        - Exit""");
    }
}
