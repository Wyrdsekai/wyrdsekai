package org.wyrdsekai.e2e.infra;

import org.awaitility.Awaitility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * Test client for the Wyrdsekai telnet adapter (raw TCP on port 7071).
 * Analogous to TestWebSocketClient but for the MUD telnet interface.
 *
 * Handles IAC/GMCP negotiation, line-based I/O, and async output polling.
 */
public class TestTelnetClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TestTelnetClient.class);

    private final Socket socket;
    private final BufferedReader reader;
    private final OutputStream out;
    private final CopyOnWriteArrayList<String> receivedLines = new CopyOnWriteArrayList<>();
    private final Thread readerThread;
    private volatile boolean running = true;
    /**
     * Lines received before the test's last action (connecting, or {@link #sendLine}). The next
     * wait looks from here, so a reply that lands between the action and the wait is not
     * missed: the server answers in about a millisecond, and a test thread that is descheduled
     * for longer than that after its send used to wait for a line it had already received
     * (2026-09-28: the banner before the first waitForText, score, reply, look).
     */
    private volatile int actionMark = 0;
    private volatile boolean actionPending = true;

    private TestTelnetClient(String host, int port) throws IOException {
        this.socket = new Socket(host, port);
        this.socket.setSoTimeout(0); // no timeout — reader thread handles it
        this.out = socket.getOutputStream();
        this.reader = new BufferedReader(
            new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

        // Background thread reads all output and stores lines
        this.readerThread = Thread.ofVirtual().name("telnet-reader").start(() -> {
            try {
                StringBuilder partial = new StringBuilder();
                int ch;
                while (running && (ch = reader.read()) != -1) {
                    if (ch == '\n') {
                        var line = partial.toString().replace("\r", "");
                        receivedLines.add(line);
                        log.debug("[Telnet] < {}", line);
                        partial.setLength(0);
                    } else if (ch == 0xFF) {
                        // IAC — skip telnet negotiation bytes
                        int cmd = reader.read();
                        if (cmd == 0xFA) {
                            // Subnegotiation — skip until IAC SE
                            while (true) {
                                int b = reader.read();
                                if (b == 0xFF) {
                                    int se = reader.read();
                                    if (se == 0xF0) break; // SE
                                }
                                if (b == -1) break;
                            }
                        } else if (cmd >= 0xFB && cmd <= 0xFE) {
                            reader.read(); // option byte
                        }
                    } else {
                        partial.append((char) ch);
                    }
                }
            } catch (IOException e) {
                if (running) log.debug("[Telnet] Reader closed: {}", e.getMessage());
            }
        });
    }

    /** Connect to a telnet server. */
    public static TestTelnetClient connect(String host, int port) throws IOException {
        return new TestTelnetClient(host, port);
    }

    /** Send a line (appends \r\n). */
    public void sendLine(String text) throws IOException {
        actionMark = receivedLines.size();
        actionPending = true;
        log.debug("[Telnet] > {}", text);
        out.write((text + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** Login as guest. */
    public void loginAsGuest() throws IOException {
        sendLine("guest");
    }

    /** Login with credentials. */
    public void login(String username, String password) throws IOException {
        sendLine("connect " + username + " " + password);
    }

    /** Create a new account. */
    public void createAccount(String username, String password) throws IOException {
        sendLine("create " + username + " " + password);
    }

    /**
     * Where a wait starts: the first wait after an action looks back to that action; any
     * later wait looks only at lines that arrive after it is called.
     */
    private int waitStart() {
        if (actionPending) {
            actionPending = false;
            return Math.min(actionMark, receivedLines.size());
        }
        return receivedLines.size();
    }

    /** Wait for a line containing the given substring. */
    public String waitForText(String substring, Duration timeout) {
        var result = new String[1];
        int startIdx = waitStart();
        Awaitility.await().atMost(timeout).pollInterval(Duration.ofMillis(100)).until(() -> {
            for (int i = startIdx; i < receivedLines.size(); i++) {
                if (receivedLines.get(i).contains(substring)) {
                    result[0] = receivedLines.get(i);
                    return true;
                }
            }
            return false;
        });
        return result[0];
    }

    /** Wait for any line matching the predicate. */
    public String waitForLine(Predicate<String> predicate, Duration timeout) {
        var result = new String[1];
        int startIdx = waitStart();
        Awaitility.await().atMost(timeout).pollInterval(Duration.ofMillis(100)).until(() -> {
            for (int i = startIdx; i < receivedLines.size(); i++) {
                if (predicate.test(receivedLines.get(i))) {
                    result[0] = receivedLines.get(i);
                    return true;
                }
            }
            return false;
        });
        return result[0];
    }

    /** Check that no line contains the substring within the timeout. */
    public boolean noneContains(String substring, Duration timeout) {
        actionPending = false;
        int startSize = receivedLines.size();
        try {
            Thread.sleep(timeout.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        for (int i = startSize; i < receivedLines.size(); i++) {
            if (receivedLines.get(i).contains(substring)) return false;
        }
        return true;
    }

    /** Count lines containing the substring received within a timeout window. */
    public int countContaining(String substring, Duration window) {
        actionPending = false;
        int startSize = receivedLines.size();
        try {
            Thread.sleep(window.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        int count = 0;
        for (int i = startSize; i < receivedLines.size(); i++) {
            if (receivedLines.get(i).contains(substring)) count++;
        }
        return count;
    }

    /** Get all received lines. */
    public List<String> allLines() {
        return new ArrayList<>(receivedLines);
    }

    /** Get lines received since a given index. */
    public List<String> linesSince(int fromIndex) {
        return new ArrayList<>(receivedLines.subList(
            Math.min(fromIndex, receivedLines.size()), receivedLines.size()));
    }

    /** Current number of received lines. */
    public int lineCount() {
        return receivedLines.size();
    }

    /** Clear all received lines and return current count (useful as a marker). */
    public int mark() {
        return receivedLines.size();
    }

    @Override
    public void close() throws IOException {
        running = false;
        readerThread.interrupt();
        socket.close();
    }
}
