package org.wyrdsekai.cli;

import org.jline.keymap.KeyMap;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.Reference;
import org.jline.reader.UserInterruptException;
import org.jline.reader.EndOfFileException;
import org.jline.terminal.TerminalBuilder;
import org.wyrdsekai.common.protocol.C2SMessage;
import org.wyrdsekai.common.protocol.S2CMessage;

import java.io.IOException;
import java.io.PrintStream;
import java.util.List;
import java.util.function.Consumer;

/**
 * Wyrdsekai CLI entry point.
 * Connects to server via WebSocket, renders room state, handles user input.
 *
 * Usage: wyrd [--host HOST] [--port PORT] [--accessible]
 */
public class Wyrd {

    private static final String DEFAULT_HOST = "localhost";
    private static final int DEFAULT_PORT = 7070;

    public static void main(String[] args) throws IOException {
        String host = DEFAULT_HOST;
        int port = DEFAULT_PORT;
        boolean accessible = false;
        boolean guest = false;
        // reach a NAT'd, relay-only zone by tunneling a full
        // session over the relay's NATS bus instead of a direct ws://host/ws.
        String relayUrl = null;   // nats:// or tls:// to the relay
        String zoneId = null;     // the zone to tunnel into
        String relayUser = null;  // relay transport account (e.g. relay_phone)
        String relayPass = null;
        String relayCaFp = null;  // pinned household-CA SHA-256 (from the invite's ca_fp)
        // Headless credentials — skip the interactive door gate (scripts, CI,
        // automation). Works for both the direct and the relay-tunnel path.
        String acctUser = null;
        String acctPass = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host" -> { if (i + 1 < args.length) host = args[++i]; }
                case "--port" -> { if (i + 1 < args.length) port = Integer.parseInt(args[++i]); }
                case "--accessible" -> accessible = true;
                // Skip the login gate and connect anonymously (scripts, kiosk,
                // explicit guest). Default is to authenticate at the door, like
                // ssh/telnet.
                case "--guest" -> guest = true;
                case "--relay" -> { if (i + 1 < args.length) relayUrl = args[++i]; }
                case "--zone" -> { if (i + 1 < args.length) zoneId = args[++i]; }
                case "--relay-user" -> { if (i + 1 < args.length) relayUser = args[++i]; }
                case "--relay-pass" -> { if (i + 1 < args.length) relayPass = args[++i]; }
                case "--relay-ca-fp" -> { if (i + 1 < args.length) relayCaFp = args[++i]; }
                case "--user" -> { if (i + 1 < args.length) acctUser = args[++i]; }
                case "--password" -> { if (i + 1 < args.length) acctPass = args[++i]; }
            }
        }
        final String finalAcctUser = acctUser;
        final String finalAcctPass = acctPass;
        final boolean headlessLogin = acctUser != null && acctPass != null;

        final var finalHost = host;
        final var finalPort = port;
        final String finalRelayCaFp = relayCaFp;
        final boolean viaRelay = relayUrl != null && zoneId != null;

        var terminal = TerminalBuilder.builder()
            .system(true)
            .build();

        var renderer = new Renderer(System.out, accessible);
        var completer = new WyrdCompleter(renderer);
        var reader = LineReaderBuilder.builder()
            .terminal(terminal)
            .completer(completer)
            .build();

        // Control-key shortcuts (parity with SSH). Ctrl-D = quit this channel
        // (JLine raises EndOfFileException, handled in the input loop). Ctrl-C =
        // cancel the current line (UserInterruptException). Ctrl-L = JLine's
        // built-in clear-screen. Ctrl-Q = logout (leave EVERY channel): inject
        // the `logout` command and submit it. Both terminals here run raw mode,
        // so Ctrl-Q isn't eaten by XON/XOFF flow control.
        reader.getWidgets().put("wyrd-logout", () -> {
            reader.getBuffer().clear();
            reader.getBuffer().write("logout");
            reader.callWidget(LineReader.ACCEPT_LINE);
            return true;
        });
        var mainKeyMap = reader.getKeyMaps().get(LineReader.MAIN);
        if (mainKeyMap != null) {
            mainKeyMap.bind(new Reference("wyrd-logout"), KeyMap.ctrl('Q'));
        }

        var inputHandler = new InputHandler(null, System.out);
        inputHandler.setRenderer(renderer);
        var authClient = new AuthClient(finalHost, finalPort);
        inputHandler.setAuthClient(authClient);

        // The render handler is transport-agnostic — both the direct WebSocket
        // Connection and the RelayTunnelConnection deliver the same S2C frames.
        Consumer<S2CMessage> onMessage = msg -> {
            renderer.render(msg);
            if (msg instanceof S2CMessage.RoomState rs) {
                inputHandler.setCurrentRoomId(rs.room().roomId());
            }
        };

        WyrdSession connection;
        if (viaRelay) {
            // tunnel a full /ws session through the relay
            // to a zone with no public door. Auth is mcp.login over the relay bus
            // (same request/reply the phone uses); the minted token authenticates
            // the zone-side loopback /ws. No direct HTTP to the NAT'd zone.
            var tunnel = new RelayTunnelConnection(relayUrl, relayUser, relayPass, finalRelayCaFp, zoneId,
                onMessage,
                status -> System.out.println("Relay tunnel: " + status));
            connection = tunnel;
            inputHandler.setConnection(connection);

            if (headlessLogin) {
                // Non-interactive: mint the session token over the relay up front.
                if (tunnel.loginOverRelay(finalAcctUser, finalAcctPass)) {
                    inputHandler.setCurrentUsername(finalAcctUser);
                    System.out.println("Signed in as " + finalAcctUser + " over the relay.");
                } else {
                    System.out.println("Headless login failed for " + finalAcctUser
                        + " — continuing as guest over the relay.");
                }
            } else if (!guest) {
                boolean ok = relayLoginGate(reader, tunnel, inputHandler, System.out);
                if (!ok) {
                    System.out.println("Continuing as guest over the relay.");
                }
            }
            System.out.println("Tunneling into zone '" + zoneId + "' via relay " + relayUrl + "...");
        } else {
            var direct = new Connection(finalHost, finalPort, onMessage,
                state -> {
                    switch (state) {
                        case CONNECTING -> System.out.println("Connecting to " + finalHost + ":" + finalPort + "...");
                        case CONNECTED -> System.out.println("Connected.");
                        case RECONNECTING -> System.out.println("Reconnecting...");
                        case DISCONNECTED -> System.out.println("Disconnected.");
                    }
                });
            connection = direct;
            inputHandler.setConnection(connection);

            // Authenticate at the door — parity with ssh/telnet (one entry model for
            // every surface). The token (or guest) is decided BEFORE the first WS
            // connect, so the server's residency landing (: resident
            // → their Study, guest → the Nexus) routes you home on arrival — instead
            // of dropping you in anonymously and making you /login then find your way.
            // Non-TTY stdin (pipes/CI) and --guest fall through to anonymous.
            if (headlessLogin) {
                var login = authClient.login(finalAcctUser, finalAcctPass);
                if (login.success()) {
                    inputHandler.setCurrentUsername(login.username());
                    direct.setToken(login.token());
                    System.out.println("Signed in as " + login.username() + ".");
                } else {
                    System.out.println("Headless login failed for " + finalAcctUser
                        + " — continuing as guest.");
                }
            } else if (!guest) {
                String token = loginGate(reader, authClient, inputHandler, System.out);
                direct.setToken(token);
            }
        }

        // Connect and wait
        connection.connect();
        if (!connection.awaitConnected(5000)) {
            System.err.println(viaRelay
                ? "Failed to open relay tunnel to zone '" + zoneId + "' via " + relayUrl
                : "Failed to connect to server at " + finalHost + ":" + finalPort);
            terminal.close();
            return;
        }

        // Give the server a moment to send initial room state
        try { Thread.sleep(200); } catch (InterruptedException ignored) {}

        System.out.println("Type /help for commands, /quit to exit.");
        System.out.println();

        try {
            while (true) {
                String line;
                try {
                    line = reader.readLine(renderer.getPromptPrefix() + "> ");
                } catch (UserInterruptException e) {
                    // Ctrl-C cancels the current input line; stay connected.
                    continue;
                } catch (EndOfFileException e) {
                    // Ctrl-D = quit this channel (the account stays present on
                    // any other surfaces; use logout / Ctrl-Q to leave them too).
                    // Same server-authoritative detach as `/quit`: let the server
                    // report how many other channels remain before we exit.
                    connection.prepareClose();
                    connection.send(new C2SMessage.Command(
                        connection.newId(), "quit", List.of()));
                    connection.awaitClosed(1500);
                    break;
                }
                if (!inputHandler.handle(line)) {
                    break;
                }
            }
        } finally {
            connection.disconnect();
            terminal.close();
        }
    }

    /**
     * Door-step login — the same identity gate ssh/telnet present, run before
     * the first WebSocket connect. Returns an auth token (→ land in your Study),
     * or null to continue as a guest (→ the Nexus).
     *
     * <p>Joining is <strong>invite-only</strong>, matching the SSH/telnet model:
     * the gate tries a plain password login first (returning members), then —
     * on failure — treats the entered secret as an <em>invitation code</em> and
     * redeems it (first-run stewards + invited members; the invite IS the
     * password, exactly as the {@code wyrd start} banner says). It never
     * silently creates an inviteless account — a household is entered by
     * invitation, not self-signup.
     *
     * <p>If stdin isn't interactive (piped, CI), readLine throws EOF and we fall
     * through to guest — automated callers never hang on the prompt.
     */
    private static String loginGate(LineReader reader, AuthClient authClient,
                                    InputHandler inputHandler, PrintStream out) {
        out.println();
        out.println("Sign in to wyrdsekai. Enter your name, or leave it blank for guest.");
        out.println("First time? Use the invitation code from `wyrd start` as your password.");
        while (true) {
            String username;
            try {
                username = reader.readLine("Name: ").trim();
            } catch (UserInterruptException | EndOfFileException e) {
                return null; // guest
            }
            if (username.isEmpty()) {
                out.println("Continuing as guest.");
                return null;
            }

            String secret;
            try {
                secret = reader.readLine("Password or invite code: ", '*');
            } catch (UserInterruptException | EndOfFileException e) {
                return null;
            }

            // 1) Returning member — plain password login.
            var login = authClient.login(username, secret);
            if (login.success()) {
                inputHandler.setCurrentUsername(login.username());
                out.println("Welcome back, " + login.username() + ".");
                return login.token();
            }

            // 2) First steward / invited member — redeem the secret as an
            //    invitation code (ssh-parity: the invite is the password). The
            //    redeemed account's password becomes the code; the role comes
            //    from the invite. No inviteless account is ever created here.
            var redeemed = authClient.redeem(username, secret, secret);
            if (redeemed.success()) {
                inputHandler.setCurrentUsername(redeemed.username());
                out.println("Welcome, " + redeemed.username() + " — invitation accepted.");
                return redeemed.token();
            }

            // 3) Neither a known account nor a valid invitation.
            out.println("No account or valid invitation for '" + username + "'.");
            out.println("Ask your household steward for an invite (they run `wyrd invite`),");
            out.println("or use the code shown by `wyrd start` if this is a first run.");
            String choice;
            try {
                choice = reader.readLine("[r]etry / [g]uest: ").trim().toLowerCase();
            } catch (UserInterruptException | EndOfFileException e) {
                return null;
            }
            if (choice.startsWith("g")) {
                out.println("Continuing as guest.");
                return null;
            }
            // anything else → retry the name/secret loop
        }
    }

    /**
     * Door-step login for the relay-tunnel path — same identity gate, but the
     * credentials never touch a direct HTTP endpoint (the zone is NAT'd and has
     * no public door). Instead it runs {@code wyrd.zone.{zone}.mcp.login} over
     * the relay bus (the same request/reply the phone uses); the minted session
     * token is held on the tunnel and authenticates the zone-side loopback /ws.
     *
     * <p>Returns true if a session token was obtained; false → continue as guest
     * (anonymous tunnel; the zone lands you in the Nexus or refuses if it's
     * invite-only). Non-interactive stdin (pipes/CI) returns false.
     */
    private static boolean relayLoginGate(LineReader reader, RelayTunnelConnection tunnel,
                                          InputHandler inputHandler, PrintStream out) {
        out.println();
        out.println("Sign in to this zone over the relay. Enter your name, or leave it blank for guest.");
        out.println("First time? Use the invitation code from `wyrd start` as your password.");
        while (true) {
            String username;
            try {
                username = reader.readLine("Name: ").trim();
            } catch (UserInterruptException | EndOfFileException e) {
                return false; // guest
            }
            if (username.isEmpty()) {
                out.println("Continuing as guest.");
                return false;
            }

            String secret;
            try {
                secret = reader.readLine("Password or invite code: ", '*');
            } catch (UserInterruptException | EndOfFileException e) {
                return false;
            }

            // mcp.login over the relay — the zone validates and mints a session
            // token, exactly as for the phone. The invite-as-password path is
            // handled zone-side (the server treats an unknown account + valid
            // invite code as a redeem), so a single request covers both.
            if (tunnel.loginOverRelay(username, secret)) {
                inputHandler.setCurrentUsername(username);
                out.println("Welcome, " + username + " — signed in over the relay.");
                return true;
            }

            out.println("No account or valid invitation for '" + username + "' on this zone.");
            String choice;
            try {
                choice = reader.readLine("[r]etry / [g]uest: ").trim().toLowerCase();
            } catch (UserInterruptException | EndOfFileException e) {
                return false;
            }
            if (choice.startsWith("g")) {
                out.println("Continuing as guest.");
                return false;
            }
            // anything else → retry
        }
    }
}
