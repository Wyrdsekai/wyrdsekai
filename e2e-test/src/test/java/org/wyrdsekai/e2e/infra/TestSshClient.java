package org.wyrdsekai.e2e.infra;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.client.channel.ChannelShell;
import org.awaitility.Awaitility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * Test client for the Wyrdsekai SSH adapter (port 7022).
 * Uses Apache SSHD client (same library as the server).
 */
public class TestSshClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TestSshClient.class);

    private final SshClient client;
    private final ClientSession session;
    private final ChannelShell channel;
    private final OutputStream out;
    private final CopyOnWriteArrayList<String> receivedLines = new CopyOnWriteArrayList<>();
    private final Thread readerThread;
    private volatile boolean running = true;

    private TestSshClient(SshClient client, ClientSession session,
                          ChannelShell channel, OutputStream out, InputStream in) {
        this.client = client;
        this.session = session;
        this.channel = channel;
        this.out = out;

        this.readerThread = Thread.ofVirtual().name("ssh-reader").start(() -> {
            try (var reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while (running && (line = reader.readLine()) != null) {
                    receivedLines.add(line);
                    log.debug("[SSH] < {}", line);
                }
            } catch (IOException e) {
                if (running) log.debug("[SSH] Reader closed: {}", e.getMessage());
            }
        });
    }

    /** Connect with password authentication. */
    public static TestSshClient connectWithPassword(String host, int port,
                                                      String username, String password) throws Exception {
        var client = SshClient.setUpDefaultClient();
        client.setServerKeyVerifier((s, addr, key) -> true); // accept all host keys in tests
        client.start();

        var session = client.connect(username, host, port)
            .verify(Duration.ofSeconds(10))
            .getSession();
        session.addPasswordIdentity(password);
        session.auth().verify(Duration.ofSeconds(10));

        var channel = session.createShellChannel();
        channel.open().verify(Duration.ofSeconds(10));

        return new TestSshClient(client, session, channel,
            channel.getInvertedIn(), channel.getInvertedOut());
    }

    /** Connect with public key authentication. */
    public static TestSshClient connectWithKey(String host, int port,
                                                 String username, KeyPair keyPair) throws Exception {
        var client = SshClient.setUpDefaultClient();
        client.setServerKeyVerifier((s, addr, key) -> true);
        client.start();

        var session = client.connect(username, host, port)
            .verify(Duration.ofSeconds(10))
            .getSession();
        session.addPublicKeyIdentity(keyPair);
        session.auth().verify(Duration.ofSeconds(10));

        var channel = session.createShellChannel();
        channel.open().verify(Duration.ofSeconds(10));

        return new TestSshClient(client, session, channel,
            channel.getInvertedIn(), channel.getInvertedOut());
    }

    /** Send a line. */
    public void sendLine(String text) throws IOException {
        log.debug("[SSH] > {}", text);
        out.write((text + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** Wait for text containing substring. */
    public String waitForText(String substring, Duration timeout) {
        var result = new String[1];
        int startIdx = receivedLines.size();
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

    /** Wait for line matching predicate. */
    public String waitForLine(Predicate<String> predicate, Duration timeout) {
        var result = new String[1];
        int startIdx = receivedLines.size();
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

    /** Get all received lines. */
    public List<String> allLines() {
        return new ArrayList<>(receivedLines);
    }

    /** Current line count. */
    public int lineCount() {
        return receivedLines.size();
    }

    /** Mark current position for later comparison. */
    public int mark() {
        return receivedLines.size();
    }

    /** Lines since mark. */
    public List<String> linesSince(int fromIndex) {
        return new ArrayList<>(receivedLines.subList(
            Math.min(fromIndex, receivedLines.size()), receivedLines.size()));
    }

    @Override
    public void close() throws Exception {
        running = false;
        readerThread.interrupt();
        if (channel != null && channel.isOpen()) channel.close();
        if (session != null && session.isOpen()) session.close();
        if (client != null) client.stop();
    }
}
