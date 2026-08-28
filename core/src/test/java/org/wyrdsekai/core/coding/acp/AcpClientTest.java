package org.wyrdsekai.core.coding.acp;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.coding.ConsentBroker;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fixture tests for the ACP v1 protocol driver — a scripted
 * {@link FakeAcpAgent} over piped streams, no subprocess.
 *
 * <p>Pins the letter's two cheap-now recommendations as behavior:
 * the protocol version is NEGOTIATED (a mismatching echo fails
 * initialization loudly), and declined capabilities are refused by
 * name rather than silently honoured.</p>
 */
class AcpClientTest {

    private record Rig(AcpClient client, FakeAcpAgent agent) {}

    private static Rig rig(int echoVersion, boolean withPermission,
                           boolean withDeclinedFs) throws IOException {
        var clientToAgent = new PipedOutputStream();
        var agentSees = new PipedInputStream(clientToAgent, 1 << 16);
        var agentToClient = new PipedOutputStream();
        var clientSees = new PipedInputStream(agentToClient, 1 << 16);
        var agent = new FakeAcpAgent(agentSees, agentToClient,
            echoVersion, withPermission, withDeclinedFs);
        Thread.ofVirtual().name("fake-acp-agent").start(agent);
        var conn = new AcpConnection(clientSees, clientToAgent);
        return new Rig(new AcpClient(conn, null), agent);
    }

    /** Rig variant with an explicit permission policy (consent tests). */
    private static Rig rig(int echoVersion, boolean withPermission,
                           boolean withDeclinedFs,
                           AcpClient.PermissionPolicy policy) throws IOException {
        var clientToAgent = new PipedOutputStream();
        var agentSees = new PipedInputStream(clientToAgent, 1 << 16);
        var agentToClient = new PipedOutputStream();
        var clientSees = new PipedInputStream(agentToClient, 1 << 16);
        var agent = new FakeAcpAgent(agentSees, agentToClient,
            echoVersion, withPermission, withDeclinedFs);
        Thread.ofVirtual().name("fake-acp-agent").start(agent);
        var conn = new AcpConnection(clientSees, clientToAgent);
        return new Rig(new AcpClient(conn, policy), agent);
    }

    @Test
    void full_turn_negotiates_collects_updates_and_answers_permission() throws Exception {
        var rig = rig(AcpMethods.PROTOCOL_VERSION, true, false);
        rig.agent()
            .withUpdate(FakeAcpAgent.toolCallUpdate("call_1", "/ws/a.js", "/ws/b.md"))
            .withUpdate(FakeAcpAgent.messageChunk("done."));

        try (var client = rig.client()) {
            client.initialize("wyrdsekai-test", "0");
            assertThat(client.negotiatedVersion()).isEqualTo(1);

            var sessionId = client.newSession("/tmp/ws");
            assertThat(sessionId).isEqualTo("sess_fake_1");

            var response = client.prompt(sessionId, "do the thing",
                Duration.ofSeconds(10));
            assertThat(response.path("stopReason").asText()).isEqualTo("end_turn");

            assertThat(client.updates()).hasSize(2);
            // permission was answered by policy: first allow-kind option
            assertThat(rig.agent().permissionAnswerSeen).isEqualTo("ok-once");
        }
    }

    /**
     * The house rule at the wire: THE HUMAN COMMITS. CodeZaiku's git gate
     * (2026-08-15) asks before any git-state write; the default policy
     * declines it — reject_once, never *_always — while ordinary tool
     * calls (the test above) still flow.
     */
    @Test
    void git_write_permission_is_declined_by_house_policy() throws Exception {
        var rig = rig(AcpMethods.PROTOCOL_VERSION, true, false);
        rig.agent().permissionTitle = "shell: git commit -m 'agent work'";

        try (var client = rig.client()) {
            client.initialize("wyrdsekai-test", "0");
            var sessionId = client.newSession("/tmp/ws");
            var response = client.prompt(sessionId, "commit it", Duration.ofSeconds(10));
            assertThat(response.path("stopReason").asText()).isEqualTo("end_turn");
            assertThat(rig.agent().permissionAnswerSeen)
                .as("a git-state write must be DECLINED by default — the human commits")
                .isEqualTo("no");
        }
    }

    @Test
    void steward_allow_grants_the_git_write_once() throws Exception {
        var broker = new ConsentBroker();
        var policy = AcpClient.stewardConsent(
            broker, Duration.ofSeconds(10), "acp-test", "task-1");
        var rig = rig(AcpMethods.PROTOCOL_VERSION, true, false, policy);
        rig.agent().permissionTitle = "shell: git commit -m 'agent work'";

        // The steward's side: answer the pending ask as soon as it appears.
        Thread.ofVirtual().start(() -> {
            try {
                for (int i = 0; i < 200; i++) {
                    var pending = broker.pending();
                    if (!pending.isEmpty()) {
                        broker.answer(pending.get(0).id(), true);
                        return;
                    }
                    Thread.sleep(25);
                }
            } catch (InterruptedException ignored) { }
        });

        try (var client = rig.client()) {
            client.initialize("wyrdsekai-test", "0");
            var sessionId = client.newSession("/tmp/ws");
            client.prompt(sessionId, "commit it", Duration.ofSeconds(15));
            assertThat(rig.agent().permissionAnswerSeen)
                .as("a live steward ALLOW must pick the allow-ONCE option")
                .isEqualTo("ok-once");
        }
    }

    @Test
    void steward_silence_still_means_no() throws Exception {
        // Nobody answers; a short wait stands in for the full window. The
        // outcome must be byte-identical to HOUSE_POLICY's static refusal.
        var broker = new ConsentBroker();
        var policy = AcpClient.stewardConsent(
            broker, Duration.ofMillis(300), "acp-test", "task-2");
        var rig = rig(AcpMethods.PROTOCOL_VERSION, true, false, policy);
        rig.agent().permissionTitle = "shell: git push origin main";

        try (var client = rig.client()) {
            client.initialize("wyrdsekai-test", "0");
            var sessionId = client.newSession("/tmp/ws");
            client.prompt(sessionId, "push it", Duration.ofSeconds(15));
            assertThat(rig.agent().permissionAnswerSeen)
                .as("silence is not consent — unanswered ask resolves to reject_once")
                .isEqualTo("no");
        }
        assertThat(broker.pending())
            .as("the timed-out ask must not linger as pending")
            .isEmpty();
    }

    @Test
    void version_mismatch_fails_initialization_loudly() throws Exception {
        var rig = rig(99, false, false);
        try (var client = rig.client()) {
            assertThatThrownBy(() -> client.initialize("wyrdsekai-test", "0"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("version mismatch");
            // and no un-negotiated method call is possible afterwards
            assertThatThrownBy(() -> client.newSession("/tmp"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("not initialized");
        }
    }

    @Test
    void declined_fs_capability_is_refused_by_name() throws Exception {
        var rig = rig(AcpMethods.PROTOCOL_VERSION, false, true);
        try (var client = rig.client()) {
            client.initialize("wyrdsekai-test", "0");
            var sessionId = client.newSession("/tmp/ws");
            // the fake agent calls fs/read_text_file mid-turn; the turn
            // still completes, and the agent got an error, not file bytes
            var response = client.prompt(sessionId, "go", Duration.ofSeconds(10));
            assertThat(response.path("stopReason").asText()).isEqualTo("end_turn");
            assertThat(rig.agent().fsCallErrorSeen)
                .contains("capability not offered");
        }
    }
}
