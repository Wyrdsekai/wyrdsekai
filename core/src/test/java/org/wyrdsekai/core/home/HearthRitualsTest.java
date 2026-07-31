package org.wyrdsekai.core.home;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.common.home.Capability;
import org.wyrdsekai.common.home.ResourceTypeRegistry;
import org.wyrdsekai.core.persistence.SchemaInitializer;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * agent birth: founding grants issued from steward to a new
 * agent + reciprocal audit-log read back to steward.
 */
class HearthRitualsTest {

    private static final String STEWARD = "did:key:z6MkMasumi";
    private static final String AGENT = "did:key:z6MkWyrd";

    private ActorTestKit testKit;
    private HomeClient homeClient;
    @TempDir Path workspace;

    @BeforeEach void setUp() {
        testKit = ActorTestKit.create("HearthRitualsTest",
            ConfigFactory.parseString("pekko.actor.provider = \"local\""));
        var jdbc = SchemaInitializer.initialize(workspace.resolve("hearth.db"));
        var store = new HomeStore(jdbc);
        var registry = testKit.spawn(HomeRegistryActor.create(store));
        homeClient = new HomeClient(registry, testKit.system());
    }

    @AfterEach void tearDown() {
        if (testKit != null) testKit.shutdownTestKit();
    }

    @Test void issueFoundingGrants_creates_budget_memory_homeroom() {
        HearthRituals.issueFoundingGrants(
            homeClient, STEWARD, AGENT, 50_000L, Duration.ofDays(30));

        var active = homeClient.listIssuedBy(STEWARD).stream()
            .filter(g -> g.isActive(Instant.now()))
            .filter(g -> AGENT.equals(g.subject()))
            .toList();

        assertThat(active).hasSize(3);
        // 1. inference-budget
        assertThat(active).anySatisfy(g -> {
            assertThat(g.resource().type()).isEqualTo(ResourceTypeRegistry.INFERENCE_BUDGET);
            assertThat(g.capability()).isEqualTo(Capability.use);
            assertThat(((Number) g.scope().get("dailyTokenCap")).longValue()).isEqualTo(50_000L);
        });
        // 2. memory-index read
        assertThat(active).anySatisfy(g -> {
            assertThat(g.resource().type()).isEqualTo(ResourceTypeRegistry.MEMORY_INDEX);
            assertThat(g.resource().id()).isEqualTo("all");
            assertThat(g.capability()).isEqualTo(Capability.read);
        });
        // 3. home-room use
        assertThat(active).anySatisfy(g -> {
            assertThat(g.resource().type()).isEqualTo(ResourceTypeRegistry.HOME_ROOM);
            assertThat(g.capability()).isEqualTo(Capability.use);
        });
    }

    @Test void zero_budget_omits_inference_grant() {
        HearthRituals.issueFoundingGrants(homeClient, STEWARD, AGENT, 0, null);
        var active = homeClient.listIssuedBy(STEWARD).stream()
            .filter(g -> g.isActive(Instant.now()))
            .toList();
        assertThat(active)
            .noneMatch(g -> g.resource().type().equals(ResourceTypeRegistry.INFERENCE_BUDGET));
        assertThat(active).hasSize(2);
    }

    @Test void shareAuditWithSteward_gives_read_on_agent_audit_log() {
        HearthRituals.shareAuditWithSteward(homeClient, AGENT, STEWARD, null);
        var held = homeClient.listHeldBy(STEWARD);
        assertThat(held).anySatisfy(g -> {
            assertThat(g.issuer()).isEqualTo(AGENT);
            assertThat(g.resource().type()).isEqualTo(ResourceTypeRegistry.AUDIT_LOG);
            assertThat(g.capability()).isEqualTo(Capability.read);
        });
    }

    @Test void seedHearth_bundles_both_directions() {
        HearthRituals.seedHearth(
            homeClient, STEWARD, AGENT, 10_000L, Duration.ofDays(90));
        // Steward issued 3 grants.
        assertThat(homeClient.listIssuedBy(STEWARD).stream()
            .filter(g -> g.isActive(Instant.now())).count()).isEqualTo(3);
        // Agent issued 1 grant.
        assertThat(homeClient.listIssuedBy(AGENT).stream()
            .filter(g -> g.isActive(Instant.now())).count()).isEqualTo(1);
    }

    @Test void idempotent_reissue_replaces_prior() {
        HearthRituals.issueFoundingGrants(homeClient, STEWARD, AGENT, 10_000L, null);
        HearthRituals.issueFoundingGrants(homeClient, STEWARD, AGENT, 50_000L, null);
        var budget = homeClient.listIssuedBy(STEWARD).stream()
            .filter(g -> g.isActive(Instant.now()))
            .filter(g -> ResourceTypeRegistry.INFERENCE_BUDGET.equals(g.resource().type()))
            .toList();
        assertThat(budget).hasSize(1);
        assertThat(((Number) budget.get(0).scope().get("dailyTokenCap")).longValue())
            .isEqualTo(50_000L);
    }

    @Test void self_reciprocal_is_skipped() {
        // agent == steward (shouldn't happen, but be defensive)
        HearthRituals.shareAuditWithSteward(homeClient, STEWARD, STEWARD, null);
        assertThat(homeClient.listIssuedBy(STEWARD)).isEmpty();
    }

    @Test void null_args_are_safe() {
        HearthRituals.issueFoundingGrants(null, STEWARD, AGENT, 1000, null);
        HearthRituals.issueFoundingGrants(homeClient, null, AGENT, 1000, null);
        HearthRituals.issueFoundingGrants(homeClient, STEWARD, null, 1000, null);
        // All quiet no-ops — no exceptions.
    }
}
