package org.wyrdsekai.core.skill.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.skill.SkillContext;
import org.wyrdsekai.core.skill.SkillPermission;
import org.wyrdsekai.core.skill.SkillRegistry;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LIVE-PROTOCOL test for the OpenClaw gateway executor (1.3): a real
 * WebSocket server on loopback speaks the gateway protocol
 * ({@code catalogue} → skills list; {@code invoke} → {@code result}/
 * {@code error} keyed by requestId), and the executor is driven through an
 * actual JDK WebSocket connection — connect kick, catalogue load, dynamic
 * {@code supports()}, round-trip invoke, error mapping, and routing through
 * a {@link SkillRegistry} the way SkillBootstrap wires it in prod.
 */
@Tag("integration")
final class OpenClawGatewayLiveProtocolTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static Javalin server;
    private static int port;

    private OpenClawGatewayExecutor executor;

    @BeforeAll
    static void startFakeGateway() {
        server = Javalin.create(cfg -> {
            // Default Jetty inbound cap is 64KB — the fragmentation test
            // round-trips ~300KB each way.
            cfg.jetty.modifyWebSocketServletFactory(f -> {
                f.setMaxTextMessageSize(2_000_000);
            });
            cfg.routes.ws("/", ws -> {
            ws.onMessage(ctx -> {
                var node = JSON.readTree(ctx.message());
                switch (node.path("type").asText("")) {
                    case "catalogue" -> ctx.send(JSON.writeValueAsString(Map.of(
                        "type", "catalogue",
                        "skills", List.of(
                            Map.of("id", "clawhub.echo", "name", "Echo",
                                "description", "Echoes text back", "room", "workshop",
                                "params", List.of(Map.of(
                                    "name", "text", "type", "string",
                                    "description", "text to echo", "required", true))),
                            Map.of("id", "clawhub.fail", "name", "AlwaysFails",
                                "description", "Gateway-side failure", "room", "workshop",
                                "params", List.of())))));
                    case "invoke" -> {
                        var requestId = node.path("requestId").asText();
                        var skillId = node.path("skillId").asText();
                        if ("clawhub.echo".equals(skillId)) {
                            ctx.send(JSON.writeValueAsString(Map.of(
                                "type", "result", "requestId", requestId,
                                "skillId", skillId, "success", true,
                                "output", "echo:" + node.path("params").path("text").asText(),
                                "latencyMs", 7,
                                "meta", Map.of("gateway", "fake"))));
                        } else {
                            ctx.send(JSON.writeValueAsString(Map.of(
                                "type", "error", "requestId", requestId,
                                "skillId", skillId,
                                "message", "skill exploded on the gateway side")));
                        }
                    }
                    default -> { /* ignore */ }
                }
            });
            });
        }).start(0);
        port = server.port();
    }

    @AfterAll
    static void stopFakeGateway() {
        if (server != null) server.stop();
    }

    @AfterEach
    void closeExecutor() {
        if (executor != null) executor.close();
    }

    private OpenClawGatewayExecutor connected() throws Exception {
        var ex = new OpenClawGatewayExecutor("ws://127.0.0.1:" + port + "/");
        ex.connectAsync().get(10, TimeUnit.SECONDS);
        return ex;
    }

    @Test
    void connect_kick_loads_the_catalogue_and_supports_goes_live() throws Exception {
        executor = connected();
        assertThat(executor.availableSkills())
            .extracting(d -> d.id())
            .contains("clawhub.echo", "clawhub.fail");
        assertThat(executor.supports("clawhub.echo")).isTrue();
        assertThat(executor.supports("clawhub.unknown")).isFalse();
    }

    @Test
    void invoke_round_trips_over_the_socket() throws Exception {
        executor = connected();
        var result = executor.execute("clawhub.echo",
            Map.of("text", "konbanwa"),
            SkillContext.forAgent("did:test:agent", "workshop", Map.of(), Long.MAX_VALUE));
        assertThat(result.success()).as(result.output()).isTrue();
        assertThat(result.output()).isEqualTo("echo:konbanwa");
    }

    @Test
    void gateway_side_error_maps_to_skill_error_not_hang() throws Exception {
        executor = connected();
        var result = executor.execute("clawhub.fail", Map.of(),
            SkillContext.forAgent("did:test:agent", "workshop", Map.of(), Long.MAX_VALUE));
        assertThat(result.success()).isFalse();
        assertThat(result.output()).contains("exploded");
    }

    @Test
    void registry_routes_catalogued_skills_like_prod_wiring() throws Exception {
        executor = connected();
        // Same shape as SkillBootstrap: registerExecutor + permission + execute.
        var registry = new SkillRegistry(null, null);
        registry.registerExecutor(executor);
        registry.setPermissions("did:test:agent", SkillPermission.allowAll());

        var result = registry.execute("clawhub.echo", Map.of("text", "via-registry"),
            SkillContext.forAgent("did:test:agent", "workshop", Map.of(), Long.MAX_VALUE));
        assertThat(result.success()).as(result.output()).isTrue();
        assertThat(result.output()).isEqualTo("echo:via-registry");

        // The dynamically catalogued defs surface to agents too.
        assertThat(registry.skillsForAgent("did:test:agent"))
            .anyMatch(d -> d.id().equals("clawhub.echo"));
    }

    @Test
    void gateway_down_yields_honest_unavailable_not_crash() {
        var down = new OpenClawGatewayExecutor("ws://127.0.0.1:1/");
        try {
            var result = down.execute("clawhub.echo", Map.of("text", "x"),
                SkillContext.forAgent("did:test:agent", "workshop", Map.of(), Long.MAX_VALUE));
            // Never catalogued → unavailable; and no exception escapes.
            assertThat(result.success()).isFalse();
        } finally {
            down.close();
        }
    }

    /**
     * Fragmented frames: the JDK client may deliver partial text — the
     * executor accumulates until {@code last=true}. A large payload forces
     * multi-frame delivery through Jetty's write path.
     */
    @Test
    void large_message_survives_fragmentation() throws Exception {
        executor = connected();
        var big = "x".repeat(300_000);
        var result = executor.execute("clawhub.echo", Map.of("text", big),
            SkillContext.forAgent("did:test:agent", "workshop", Map.of(),
                Long.MAX_VALUE));
        assertThat(result.success()).as(
            () -> "large round-trip failed: " + abbreviate(result.output())).isTrue();
        assertThat(result.output()).hasSize("echo:".length() + big.length());
    }

    private static String abbreviate(String s) {
        if (s == null) return "null";
        return s.length() <= 200 ? s : s.substring(0, 200) + "…(" + s.length() + ")";
    }

}
