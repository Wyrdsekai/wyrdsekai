package org.wyrdsekai.server.session;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.governance.ModerationService;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W5 wiring: {@code report <name>} must actually FILE into the installed
 * {@link ModerationService} — not just ack. Before 2026-07-11 the ack fired
 * and nothing reached the steward's moderation queue.
 */
class ClientSessionReportTest {

    private static final ActorTestKit testKit = ActorTestKit.create(
        ConfigFactory.parseString("pekko.actor.provider = \"local\""));

    @AfterAll
    static void shutdown() {
        testKit.shutdownTestKit();
    }

    @AfterEach
    void reset() {
        ModerationService.resetForTests();
    }

    @Test
    void report_files_into_installed_moderation_service_and_acks() {
        var moderation = new ModerationService();
        ModerationService.install(moderation);

        List<String> sentToClient = new CopyOnWriteArrayList<>();
        var session = testKit.spawn(
            ClientSessionActor.create("session-w5", sentToClient::add));
        var probe = testKit.createTestProbe();

        session.tell(new ClientSessionActor.Report(
            "evil-agent-42", "kept shouting slurs", "room-nexus"));

        probe.awaitAssert(Duration.ofSeconds(5), () -> {
            assertThat(moderation.openReports()).hasSize(1);
            return null;
        });
        var filed = moderation.openReports().get(0);
        assertThat(filed.targetEntity()).isEqualTo("evil-agent-42");
        assertThat(filed.reason()).isEqualTo("kept shouting slurs");
        assertThat(filed.roomId()).isEqualTo("room-nexus");
        // No room joined in this test → reporter falls back to the session id.
        assertThat(filed.reporterEntity()).isEqualTo("session-w5");
        assertThat(filed.status()).isEqualTo(ModerationService.ReportStatus.OPEN);

        // The ack to the reporting user is preserved.
        probe.awaitAssert(Duration.ofSeconds(5), () -> {
            assertThat(sentToClient).hasSize(1);
            return null;
        });
    }

    @Test
    void report_without_installed_service_still_acks() {
        // No install() — bare boots / tests. The ack must still fire.
        List<String> sentToClient = new CopyOnWriteArrayList<>();
        var session = testKit.spawn(
            ClientSessionActor.create("session-bare", sentToClient::add));
        var probe = testKit.createTestProbe();

        session.tell(new ClientSessionActor.Report("someone", "reason", "room-1"));

        probe.awaitAssert(Duration.ofSeconds(5), () -> {
            assertThat(sentToClient).hasSize(1);
            return null;
        });
    }
}
