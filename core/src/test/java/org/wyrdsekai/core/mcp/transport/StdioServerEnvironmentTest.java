package org.wyrdsekai.core.mcp.transport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An MCP server started over stdio inherited the daemon's whole environment (2026-09-28
 * audit). It now gets a clean one plus the names its service entry passes it.
 */
class StdioServerEnvironmentTest {

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void a_stdio_server_sees_only_its_own_environment() throws Exception {
        var pb = StdioTransportHandler.processBuilder(List.of("/usr/bin/env"), Map.of("BRAVE_API_KEY", "its-own"));
        var p = pb.start();
        var out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(10, TimeUnit.SECONDS)).isTrue();
        assertThat(out).contains("BRAVE_API_KEY=its-own");
        var leaked = out.lines().filter(l -> l.contains("="))
            .map(l -> l.substring(0, l.indexOf('=')))
            .filter(n -> !n.equals("BRAVE_API_KEY") && !StdioTransportHandler.ENV.allows(n))
            .toList();
        assertThat(leaked).as("the server saw variables it was not given").isEmpty();
    }
}
