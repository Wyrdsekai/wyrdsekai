package org.wyrdsekai.core.home;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.common.home.Capability;
import org.wyrdsekai.common.home.GrantRequest;
import org.wyrdsekai.common.home.GrantTemplate;
import org.wyrdsekai.common.home.ResourceTypeRegistry;
import org.wyrdsekai.common.home.ResourceUri;
import org.wyrdsekai.common.home.TrustTier;
import org.wyrdsekai.core.persistence.SchemaInitializer;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * trust tiers translate to grant templates.
 * When a grant-request's scope carries {@code trustTier}, the approve path
 * uses {@link GrantTemplate} so the minted Grant inherits the tier's
 * default expiry + scope additions.
 */
class TrustTierTemplateTest {

    private ActorTestKit testKit;
    private HomeClient homeClient;
    @TempDir Path workspace;

    @BeforeEach void setUp() {
        testKit = ActorTestKit.create("TrustTierTemplateTest",
            ConfigFactory.parseString("pekko.actor.provider = \"local\""));
        var jdbc = SchemaInitializer.initialize(workspace.resolve("tier.db"));
        var store = new HomeStore(jdbc);
        var registry = testKit.spawn(HomeRegistryActor.create(store));
        homeClient = new HomeClient(registry, testKit.system());
    }

    @AfterEach void tearDown() {
        if (testKit != null) testKit.shutdownTestKit();
    }

    @Test void tourist_tier_yields_24h_expiry() {
        var r = ResourceUri.of("alice", ResourceTypeRegistry.HOME_ROOM);
        var scope = new HashMap<String, Object>();
        scope.put("trustTier", "tourist");
        var req = homeClient.createRequest(GrantRequest.create(
            "did:zone:beta", "alice", r, Capability.use, scope, "visiting"));
        var before = Instant.now();
        var approved = homeClient.approveRequest(req.id(), "alice", null, null);

        var grant = homeClient.listHeldBy("did:zone:beta").stream()
            .filter(g -> g.id().equals(approved.issuedGrantId()))
            .findFirst().orElseThrow();
        assertThat(grant.scope()).containsEntry("trustTier", "tourist");
        assertThat(grant.expiresAt()).isNotNull();
        // Expiry ≈ now + 24h, within 5 seconds of test start.
        var expected = before.plus(TrustTier.TOURIST.defaultTtl());
        assertThat(Duration.between(grant.expiresAt(), expected).abs())
            .isLessThan(Duration.ofSeconds(5));
    }

    @Test void resident_tier_yields_7d_expiry() {
        var r = ResourceUri.of("alice", ResourceTypeRegistry.COLLECTION, "notes");
        var scope = new HashMap<String, Object>();
        scope.put("trustTier", "resident");
        var req = homeClient.createRequest(GrantRequest.create(
            "bob", "alice", r, Capability.read, scope, null));
        var before = Instant.now();
        var approved = homeClient.approveRequest(req.id(), "alice", null, null);

        var grant = homeClient.listHeldBy("bob").stream()
            .filter(g -> g.id().equals(approved.issuedGrantId()))
            .findFirst().orElseThrow();
        assertThat(grant.expiresAt()).isNotNull();
        var expected = before.plus(TrustTier.RESIDENT.defaultTtl());
        assertThat(Duration.between(grant.expiresAt(), expected).abs())
            .isLessThan(Duration.ofSeconds(5));
    }

    @Test void citizen_tier_is_open_ended() {
        var r = ResourceUri.of("alice", ResourceTypeRegistry.COLLECTION, "notes");
        var req = homeClient.createRequest(GrantRequest.create(
            "bob", "alice", r, Capability.read,
            Map.of("trustTier", "citizen"), null));
        var approved = homeClient.approveRequest(req.id(), "alice", null, null);
        var grant = homeClient.listHeldBy("bob").stream()
            .filter(g -> g.id().equals(approved.issuedGrantId()))
            .findFirst().orElseThrow();
        assertThat(grant.expiresAt()).isNull();
    }

    @Test void explicit_expiry_overrides_tier_default() {
        var r = ResourceUri.of("alice", ResourceTypeRegistry.HOME_ROOM);
        var req = homeClient.createRequest(GrantRequest.create(
            "did:zone:beta", "alice", r, Capability.use,
            Map.of("trustTier", "tourist"), null));
        var explicit = Instant.now().plus(Duration.ofMinutes(30));
        var approved = homeClient.approveRequest(req.id(), "alice", explicit, null);

        var grant = homeClient.listHeldBy("did:zone:beta").stream()
            .filter(g -> g.id().equals(approved.issuedGrantId()))
            .findFirst().orElseThrow();
        assertThat(Duration.between(grant.expiresAt(), explicit).abs())
            .isLessThan(Duration.ofSeconds(1));
    }

    @Test void unknown_tier_falls_back_to_tourist() {
        var r = ResourceUri.of("alice", ResourceTypeRegistry.HOME_ROOM);
        var req = homeClient.createRequest(GrantRequest.create(
            "did:zone:beta", "alice", r, Capability.use,
            Map.of("trustTier", "warlord"), null));
        var approved = homeClient.approveRequest(req.id(), "alice", null, null);
        var grant = homeClient.listHeldBy("did:zone:beta").stream()
            .filter(g -> g.id().equals(approved.issuedGrantId()))
            .findFirst().orElseThrow();
        assertThat(grant.scope()).containsEntry("trustTier", "tourist");
        assertThat(grant.expiresAt()).isNotNull();
    }

    @Test void no_tier_preserves_legacy_path() {
        var r = ResourceUri.of("alice", ResourceTypeRegistry.HOME_ROOM);
        var req = homeClient.createRequest(GrantRequest.create(
            "bob", "alice", r, Capability.use, Map.of(), null));
        var approved = homeClient.approveRequest(req.id(), "alice", null, null);
        var grant = homeClient.listHeldBy("bob").stream()
            .filter(g -> g.id().equals(approved.issuedGrantId()))
            .findFirst().orElseThrow();
        // No tier → open-ended, no trustTier scope stamp.
        assertThat(grant.expiresAt()).isNull();
        assertThat(grant.scope()).doesNotContainKey("trustTier");
    }

    @Test void tier_template_unit_round_trip() {
        var r = ResourceUri.of("alice", ResourceTypeRegistry.COLLECTION, "notes");
        var grant = GrantTemplate.forTier(
            TrustTier.RESIDENT, "alice", "bob", r, Capability.read,
            Map.of("collection", "notes"), null, "welcome");
        assertThat(grant.scope()).containsEntry("trustTier", "resident");
        assertThat(grant.scope()).containsEntry("collection", "notes");
        assertThat(grant.expiresAt()).isNotNull();
    }
}
