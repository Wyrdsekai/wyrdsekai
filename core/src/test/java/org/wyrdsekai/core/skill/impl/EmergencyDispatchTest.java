package org.wyrdsekai.core.skill.impl;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.skill.SkillContext;
import org.wyrdsekai.core.skill.SkillPermission;
import org.wyrdsekai.core.skill.SkillRegistry;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 3.2 — the emergency-call dispatch seam. handleEmergencyCall's substrate
 * path executes {@code herald.call.emergency} through the registry with a
 * direct jurisdiction number ({@code to}) and Safe-chain credentials; this
 * proves that path end-to-end against a local Twilio-shaped stub: the
 * permission safety floor admits it, the POST carries the right To/Auth,
 * and every unconfigured state comes back as an honest, speakable error.
 */
final class EmergencyDispatchTest {

    private HttpServer server;
    private final AtomicReference<String> capturedBody = new AtomicReference<>();
    private final AtomicReference<String> capturedAuth = new AtomicReference<>();

    @BeforeEach
    void startStub() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            var buf = new ByteArrayOutputStream();
            exchange.getRequestBody().transferTo(buf);
            capturedBody.set(buf.toString(StandardCharsets.UTF_8));
            capturedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            var reply = "{\"sid\":\"CA-test\",\"status\":\"queued\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(201, reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopStub() {
        if (server != null) server.stop(0);
    }

    private EmergencyCallSkillExecutor stubbedExecutor(List<EmergencyCallSkillExecutor.EmergencyContact> contacts) {
        var exec = new EmergencyCallSkillExecutor(contacts);
        exec.baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/Accounts";
        return exec;
    }

    private static SkillContext ctxWithCreds() {
        return SkillContext.forAgent("did:test:companion", "hearth",
            Map.of("twilio_credentials", "AC-test:token-test",
                   "twilio_from", "+15550100"),
            Long.MAX_VALUE);
    }

    @Test
    void substrate_direct_number_dials_through_the_registry_safety_floor() {
        var registry = new SkillRegistry(null, null);
        registry.registerExecutor(stubbedExecutor(List.of()));
        // companionDefault denies herald.* EXCEPT herald.call.emergency — the
        // safety floor. The substrate's jurisdiction dial must pass it.
        registry.setPermissions("did:test:companion", SkillPermission.companionDefault());

        var result = registry.execute("herald.call.emergency",
            Map.of("to", "911", "message", "Automated emergency call. Reason: smoke."),
            ctxWithCreds());

        assertTrue(result.success(), result.output());
        var body = capturedBody.get();
        assertNotNull(body, "the call must actually reach the telephony API");
        assertTrue(body.contains("To=911"), body);
        assertTrue(body.contains("From=%2B15550100"), body);
        assertTrue(body.contains("smoke"), "the reason must ride in the spoken TwiML");
        assertTrue(capturedAuth.get().startsWith("Basic "),
            "credentials must go as Basic auth, never in the URL");
    }

    @Test
    void missing_credentials_is_an_honest_speakable_error() {
        var exec = stubbedExecutor(List.of());
        var result = exec.execute("herald.call.emergency",
            Map.of("to", "911", "message", "help"),
            SkillContext.forAgent("did:test:companion", "hearth", Map.of(), Long.MAX_VALUE));
        assertFalse(result.success());
        assertNull(capturedBody.get(), "no credentials → no network attempt");
    }

    @Test
    void contact_flow_without_contacts_names_the_gap() {
        var exec = stubbedExecutor(List.of());
        var result = exec.execute("herald.call.emergency", Map.of("message", "help"),
            ctxWithCreds());
        assertFalse(result.success());
        assertNull(capturedBody.get());
    }

    @Test
    void contact_flow_dials_the_configured_contact() {
        var exec = stubbedExecutor(List.of(
            new EmergencyCallSkillExecutor.EmergencyContact("Operator", "+15550199", "steward")));
        var result = exec.execute("herald.call.emergency", Map.of("message", "help"),
            ctxWithCreds());
        assertTrue(result.success(), result.output());
        assertTrue(capturedBody.get().contains("To=%2B15550199"), capturedBody.get());
    }
}
