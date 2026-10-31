package org.wyrdsekai.server;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The server closes a connection that has been quiet for five minutes. The browser page sent
 * nothing while a person read a quiet room, so every five minutes they were taken out of the room
 * and brought back. The page now pings on a timer, and the server takes the ping as a sign of use
 * without treating it as input.
 */
class AQuietBrowserTabStaysConnectedTest {

    private static String read(String rel) throws Exception {
        var fromModule = Path.of("..", rel);
        return Files.readString(Files.exists(fromModule) ? fromModule : Path.of(rel));
    }

    @Test
    void the_page_pings_well_inside_the_servers_quiet_limit() throws Exception {
        var page = read("server/src/main/resources/web/index.html");
        var every = Pattern.compile("const KEEPALIVE_MS = (\\d+);").matcher(page);
        assertThat(every.find()).as("the page names its keep-alive period").isTrue();
        assertThat(Long.parseLong(every.group(1))).isLessThanOrEqualTo(60_000);
        assertThat(page).contains("keepalive = setInterval(() => {\n      if (ws && ws.readyState === WebSocket.OPEN) ws.send('{\"type\":\"ping\"}');");
        assertThat(page).as("the timer stops with the connection").contains("ws.onclose = ev => {\n    setConn(T.disconnected, false);\n    clearInterval(keepalive);");
    }

    @Test
    void the_server_counts_a_ping_as_use_and_reads_nothing_into_it() throws Exception {
        var ws = read("server/src/main/java/org/wyrdsekai/server/ws/WyrdWebSocket.java");
        int touch = ws.indexOf("if (connectionRegistry != null) connectionRegistry.touch(sessionId);");
        int ping = ws.indexOf("if (raw != null && raw.contains(\"\\\"type\\\":\\\"ping\\\"\")) {");
        assertThat(touch).isPositive();
        assertThat(ping).as("a ping is dropped before it is parsed as input").isGreaterThan(touch);
        var main = read("server/src/main/java/org/wyrdsekai/server/Main.java");
        assertThat(main).contains("cfg.jetty.modifyWebSocketServletFactory(ws -> ws.setIdleTimeout(Duration.ofMinutes(5)));");
    }
}
