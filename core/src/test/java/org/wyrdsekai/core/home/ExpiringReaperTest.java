package org.wyrdsekai.core.home;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.javadsl.AskPattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.common.home.AuditEntry;
import org.wyrdsekai.common.home.Capability;
import org.wyrdsekai.common.home.Grant;
import org.wyrdsekai.common.home.ResourceTypeRegistry;
import org.wyrdsekai.common.home.ResourceUri;
import org.wyrdsekai.core.persistence.SchemaInitializer;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reaper fires {@code grant-expired} audit entries for grants past their
 * {@code expires_at} and only does so once per grant.
 */
class ExpiringReaperTest {

    private ActorTestKit testKit;
    private HomeClient homeClient;
    private ActorRef<HomeRegistryActor.Command> registry;
    @TempDir Path workspace;

    @BeforeEach void setUp() {
        testKit = ActorTestKit.create("ExpiringReaperTest",
            ConfigFactory.parseString("pekko.actor.provider = \"local\""));
        var jdbc = SchemaInitializer.initialize(workspace.resolve("reaper.db"));
        var store = new HomeStore(jdbc);
        registry = testKit.spawn(HomeRegistryActor.create(store));
        homeClient = new HomeClient(registry, testKit.system());
    }

    @AfterEach void tearDown() {
        if (testKit != null) testKit.shutdownTestKit();
    }

    private HomeRegistryActor.ExpiryReport reap() throws Exception {
        return AskPattern.<HomeRegistryActor.Command, HomeRegistryActor.ExpiryReport>ask(
            registry,
            replyTo -> new HomeRegistryActor.ReapExpiredGrants(replyTo),
            Duration.ofSeconds(5), testKit.system().scheduler())
            .toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private List<AuditEntry> auditFor(String owner) throws Exception {
        return AskPattern.<HomeRegistryActor.Command, HomeRegistryActor.AuditList>ask(
            registry,
            replyTo -> new HomeRegistryActor.QueryAudit(owner, null, 100, replyTo),
            Duration.ofSeconds(5), testKit.system().scheduler())
            .toCompletableFuture().get(10, TimeUnit.SECONDS).entries();
    }

    @Test void reaper_audits_expired_grant_once() throws Exception {
        var resource = ResourceUri.of("alice", ResourceTypeRegistry.COLLECTION, "notes");
        var expiredAt = Instant.now().minusSeconds(60);
        // Issue with expiry already in the past.
        homeClient.issue(Grant.issue(
            "alice", "bob", resource, Capability.read, Map.of(),
            Instant.now().minusSeconds(120), expiredAt, "old grant"));

        var report = reap();
        assertThat(report.expiredCount()).isEqualTo(1);

        var expired = auditFor("alice").stream()
            .filter(e -> AuditEntry.Verb.GRANT_EXPIRED.equals(e.verb()))
            .toList();
        assertThat(expired).hasSize(1);

        // Second sweep: no new audits.
        assertThat(reap().expiredCount()).isEqualTo(0);
        var expiredAgain = auditFor("alice").stream()
            .filter(e -> AuditEntry.Verb.GRANT_EXPIRED.equals(e.verb()))
            .toList();
        assertThat(expiredAgain).hasSize(1);
    }

    @Test void reaper_skips_unexpired_and_revoked() throws Exception {
        var resource = ResourceUri.of("alice", ResourceTypeRegistry.COLLECTION, "notes");
        // Active, open-ended.
        homeClient.issue(Grant.issue(
            "alice", "bob", resource, Capability.read, Map.of(),
            Instant.now(), null, "live"));
        // Future expiry.
        homeClient.issue(Grant.issue(
            "alice", "carol", resource, Capability.read, Map.of(),
            Instant.now(), Instant.now().plusSeconds(3600), "future"));
        // Already expired but revoked — should still be skipped.
        var revoked = homeClient.issue(Grant.issue(
            "alice", "dave", resource, Capability.read, Map.of(),
            Instant.now().minusSeconds(120), Instant.now().minusSeconds(60), "revoked-past"));
        homeClient.revoke(revoked.id(), "alice");

        assertThat(reap().expiredCount()).isEqualTo(0);
    }
}
