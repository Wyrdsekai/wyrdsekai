package org.wyrdsekai.server.telnet;

import org.wyrdsekai.common.util.Json;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Low-level Telnet protocol handling and GMCP (Generic MUD Communication Protocol).
 * GMCP uses Telnet option 201 for structured JSON data alongside prose text.
 */
public final class TelnetCodec {

    // Telnet commands
    public static final int IAC  = 255;
    public static final int WILL = 251;
    public static final int WONT = 252;
    public static final int DO   = 253;
    public static final int DONT = 254;
    public static final int SB   = 250;
    public static final int SE   = 240;
    public static final int GA   = 249; // Go Ahead

    // Telnet options
    public static final int OPT_GMCP = 201;

    private TelnetCodec() {}

    /** Send IAC WILL GMCP to offer GMCP support. */
    public static void negotiateGmcp(OutputStream out) throws IOException {
        out.write(new byte[] { (byte) IAC, (byte) WILL, (byte) OPT_GMCP });
        out.flush();
    }

    /**
     * Send a GMCP message: IAC SB 201 <package> <json> IAC SE
     * The package name and JSON payload are separated by a space.
     */
    public static void sendGmcp(OutputStream out, String packageName, Object data) throws IOException {
        String json;
        try {
            json = Json.mapper().writeValueAsString(data);
        } catch (Exception e) {
            return;
        }
        var payload = (packageName + " " + json).getBytes(StandardCharsets.UTF_8);
        out.write(new byte[] { (byte) IAC, (byte) SB, (byte) OPT_GMCP });
        out.write(payload);
        out.write(new byte[] { (byte) IAC, (byte) SE });
        out.flush();
    }

    /** Send a line of plain text with CR+LF termination. */
    public static void sendLine(OutputStream out, String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.write(new byte[] { '\r', '\n' });
        out.flush();
    }

    /** Send text without line termination. */
    public static void sendRaw(OutputStream out, String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /**
     * Read a line from telnet input, stripping IAC sequences.
     * Returns the cleaned line, or null on end-of-stream.
     * Sets gmcpSupported[0] to true if client sends DO GMCP.
     */
    public static String readLine(InputStream in, boolean[] gmcpSupported) throws IOException {
        var sb = new StringBuilder();
        int b;
        while ((b = in.read()) != -1) {
            if (b == IAC) {
                int cmd = in.read();
                if (cmd == -1) return null;
                switch (cmd) {
                    case WILL, WONT, DO, DONT -> {
                        int opt = in.read();
                        if (opt == -1) return null;
                        if (cmd == DO && opt == OPT_GMCP) {
                            gmcpSupported[0] = true;
                        }
                    }
                    case SB -> {
                        // Skip subnegotiation content until IAC SE
                        skipSubnegotiation(in);
                    }
                    case IAC -> sb.append((char) IAC); // Escaped 255
                    default -> {} // Ignore other IAC commands
                }
            } else if (b == '\n') {
                return sb.toString().stripTrailing();
            } else if (b == 4) {
                // Ctrl-D (EOT). On an empty line, honor the terminal EOF
                // convention → end of stream (the telnet session quits/detaches).
                // Mid-line, ignore it (don't inject a control char into the line).
                if (sb.length() == 0) return null;
            } else if (b != '\r') {
                sb.append((char) b);
            }
        }
        return null; // End of stream
    }

    private static void skipSubnegotiation(InputStream in) throws IOException {
        int prev = 0;
        int b;
        while ((b = in.read()) != -1) {
            if (prev == IAC && b == SE) return;
            prev = b;
        }
    }
}
