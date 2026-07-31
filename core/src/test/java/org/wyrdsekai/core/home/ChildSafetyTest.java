package org.wyrdsekai.core.home;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
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
 * child safety:
 *  - confidential journal entries excluded from a delegated grant
 *  - dual-audit: cross-home access lands on both parties' audit logs
 */
class ChildSafetyTest {

    private ActorTestKit testKit;
    private HomeClient homeClient;
    @TempDir Path workspace;

    @BeforeEach void setUp() {
        testKit = ActorTestKit.create("ChildSafetyTest",
            ConfigFactory.parseString("pekko.actor.provider = \"local\""));
        var jdbc = SchemaInitializer.initialize(workspace.resolve("safety.db"));
        var store = new HomeStore(jdbc);
        var registry = testKit.spawn(HomeRegistryActor.create(store));
        homeClient = new HomeClient(registry, testKit.system());
    }

    @AfterEach void tearDown() {
        if (testKit != null) testKit.shutdownTestKit();
    }

    @Test void excludes_denies_access_to_confidential_tag() {
        // Alice is the child; her mother holds read on a journal entry
        // that is also used for confidential entries. The grant excludes
        // the "confidential" tag.
        var resource = ResourceUri.of("alice", ResourceTypeRegistry.JOURNAL, "entry-0");
        homeClient.issueOrReplace(
            "alice", "mom", resource, Capability.read,
            Map.of("excludes", List.of("confidential")),
            null, "parental access");

        // Access with a non-confidential tag → allowed.
        assertThat(homeClient.check("mom", resource, Capability.read,
            Map.of("tag", "everyday"))).isTrue();

        // Access with the confidential tag → denied.
        assertThat(homeClient.check("mom", resource, Capability.read,
            Map.of("tag", "confidential"))).isFalse();
    }

    @Test void excludes_denies_by_path() {
        var resource = ResourceUri.of("alice", ResourceTypeRegistry.COLLECTION, "diary");
        homeClient.issueOrReplace(
            "alice", "mom", resource, Capability.read,
            Map.of("excludes", List.of("secret-2026-04-17")),
            null, "diary access");

        assertThat(homeClient.check("mom", resource, Capability.read,
            Map.of("path", "public-2026-04-17"))).isTrue();
        assertThat(homeClient.check("mom", resource, Capability.read,
            Map.of("path", "secret-2026-04-17"))).isFalse();
    }

    @Test void dual_audit_lands_on_both_homes() throws Exception {
        var resource = ResourceUri.of("alice", ResourceTypeRegistry.JOURNAL, "e1");
        homeClient.issueOrReplace("alice", "mom", resource, Capability.read,
            Map.of(), null, "read");

        // Mom reads → access granted.
        assertThat(homeClient.check("mom", resource, Capability.read, Map.of())).isTrue();

        // Audit entry lands on alice's Home.
        var aliceEntries = fetchAudit("alice");
        assertThat(aliceEntries.stream()
            .filter(e -> e.verb().equals(AuditEntry.Verb.ACCESS_GRANTED))
            .map(AuditEntry::resource)
            .toList())
            .contains(resource.toString());

        // And on mom's Home too — the dual-audit entry carries onHome=alice.
        var momEntries = fetchAudit("mom");
        assertThat(momEntries.stream()
            .filter(e -> e.verb().equals(AuditEntry.Verb.ACCESS_GRANTED))
            .toList())
            .anySatisfy(e -> {
                assertThat(e.resource()).isEqualTo(resource.toString());
                assertThat(e.detail().get("onHome")).isEqualTo("alice");
            });
    }

    @Test void self_access_does_not_dual_audit() throws Exception {
        // Alice accesses her own journal — owner == subject, no dual-audit row.
        var resource = ResourceUri.of("alice", ResourceTypeRegistry.JOURNAL, "e1");
        homeClient.issueOrReplace("alice", "alice", resource, Capability.read,
            Map.of(), null, "self");
        assertThat(homeClient.check("alice", resource, Capability.read, Map.of())).isTrue();

        // Alice's audit has the entry. No duplicate because homeOwner == subject.
        var entries = fetchAudit("alice").stream()
            .filter(e -> e.verb().equals(AuditEntry.Verb.ACCESS_GRANTED))
            .filter(e -> e.resource().equals(resource.toString()))
            .toList();
        assertThat(entries).hasSize(1);
    }

    @Test void public_subject_does_not_dual_audit() throws Exception {
        // Grants with subject="public" should not try to audit on the public DID.
        var resource = ResourceUri.of("alice", ResourceTypeRegistry.JOURNAL, "e1");
        homeClient.issueOrReplace("alice", Grant.PUBLIC_SUBJECT, resource, Capability.read,
            Map.of(), null, "open");

        assertThat(homeClient.check("bob", resource, Capability.read, Map.of())).isTrue();

        // Check audit on "public" — should be empty (no Home owner).
        var pub = fetchAudit(Grant.PUBLIC_SUBJECT);
        assertThat(pub).isEmpty();
    }

    private List<AuditEntry> fetchAudit(String homeOwner) throws Exception {
        return AskPattern
            .<HomeRegistryActor.Command, HomeRegistryActor.AuditList>ask(
                homeClient.registry(),
                replyTo -> new HomeRegistryActor.QueryAudit(homeOwner, null, 100, replyTo),
                Duration.ofSeconds(5), testKit.system().scheduler())
            .toCompletableFuture().get(10, TimeUnit.SECONDS).entries();
    }
}
