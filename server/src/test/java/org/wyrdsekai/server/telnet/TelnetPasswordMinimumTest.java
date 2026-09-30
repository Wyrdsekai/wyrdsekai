package org.wyrdsekai.server.telnet;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.persistence.AuthService;
import org.wyrdsekai.core.persistence.InviteService;
import org.wyrdsekai.core.persistence.SchemaInitializer;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Telnet 'create' and 'redeem' took any password, where HTTP required four characters
 * (2026-09-28 audit). The same minimum now holds on telnet.
 */
@Tag("integration")
class TelnetPasswordMinimumTest {

    private AuthService auth;
    private InviteService invites;
    private ServerSocket server;

    @BeforeEach
    void setUp(@TempDir Path tmp) throws Exception {
        var jdbc = SchemaInitializer.initialize(tmp.resolve("world.db"));
        auth = new AuthService(jdbc);
        invites = new InviteService(jdbc);
        server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
    }

    @AfterEach
    void tearDown() throws Exception {
        server.close();
    }

    /** Sends the lines to a real TelnetSession and returns everything it wrote back. */
    private String session(String... lines) throws Exception {
        try (var client = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort())) {
            var accepted = server.accept();
            var thread = Thread.ofVirtual().start(new TelnetSession(accepted, null, auth, invites, null, null));
            var out = client.getOutputStream();
            for (var line : lines) out.write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            var seen = readUntilClosed(client.getInputStream());
            thread.join(10_000);
            return seen;
        }
    }

    private static String readUntilClosed(InputStream in) throws Exception {
        var buf = new ByteArrayOutputStream();
        var chunk = new byte[4096];
        int n;
        while ((n = in.read(chunk)) != -1) buf.write(chunk, 0, n);
        return buf.toString(StandardCharsets.UTF_8);
    }

    @Test
    void create_refuses_a_short_password() throws Exception {
        var seen = session("create sam abc", "quit");
        assertThat(seen).contains("at least 4 characters");
        assertThat(auth.countUsers()).isZero();
    }

    @Test
    void redeem_refuses_a_short_password() throws Exception {
        auth.register("sam", "password1", "Sam").orElseThrow();
        var steward = auth.findUserByUsername("sam").orElseThrow();
        var invite = invites.createInvite("kai", "member", steward.id());
        var seen = session("redeem " + invite.code() + " kai abc", "quit");
        assertThat(seen).contains("at least 4 characters");
        assertThat(auth.countUsers()).isEqualTo(1);
    }
}
