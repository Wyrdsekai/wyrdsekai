package org.wyrdsekai.core.coding.acp;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live ACP v1 handshake against a REAL agent binary — operator's note
 * 2026-08-15: "since goose also is acp v1 compliant we should make sure
 * to test with that too."
 *
 * <p>Deliberately initialize-only: version negotiation and capability
 * exchange involve no model call, so this runs CPU-only (no session, no
 * prompt — a full live turn needs an inference backend and waits for the
 * GPU embargo to lift). Gated on {@code WYRDSEKAI_ACP_LIVE_AGENT}
 * pointing at the agent executable, e.g.:</p>
 *
 * <pre>
 *   WYRDSEKAI_ACP_LIVE_AGENT=~/.wyrdsekai-bake/coding-cli-bundle/goose/goose
 * </pre>
 *
 * <p>Known wire wrinkle this test exists to catch: goose's in-repo
 * python test client sends {@code "protocolVersion": "v1"} (string)
 * while the spec schema and goose's own Rust fixture use integer
 * {@code 1}. We speak spec-integer; if a live agent echoes anything
 * else, this fails HERE with evidence, not in production.</p>
 */
@Tag("live")
class AcpGooseLiveHandshakeTest {

    @Test
    @EnabledIfEnvironmentVariable(named = "WYRDSEKAI_ACP_LIVE_AGENT", matches = ".+")
    void initialize_negotiates_protocol_version_1_with_real_agent() throws Exception {
        var agent = System.getenv("WYRDSEKAI_ACP_LIVE_AGENT");
        var pb = new ProcessBuilder(agent, "acp");
        pb.redirectErrorStream(false);
        var process = pb.start();
        Thread.ofVirtual().start(() -> {
            try (var err = process.getErrorStream()) { err.readAllBytes(); }
            catch (Exception ignored) { }
        });
        try (var conn = new AcpConnection(
                process.getInputStream(), process.getOutputStream());
             var client = new AcpClient(conn, null)) {
            var result = client.initialize("wyrdsekai-live-handshake", "0");
            assertThat(client.negotiatedVersion()).isEqualTo(1);
            assertThat(result.has("agentCapabilities"))
                .as("initialize result should carry agentCapabilities; got: " + result)
                .isTrue();
        } finally {
            process.destroy();
        }
    }
}
