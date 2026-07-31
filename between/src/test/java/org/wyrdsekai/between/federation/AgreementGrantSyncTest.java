package org.wyrdsekai.between.federation;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.assertj.core.api.ListAssert;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.home.Capability;
import org.wyrdsekai.common.home.Grant;
import org.wyrdsekai.common.home.ResourceTypeRegistry;
import org.wyrdsekai.common.home.ResourceUri;
import org.wyrdsekai.common.model.QuotaPolicy;
import org.wyrdsekai.core.home.HomeClient;
import org.wyrdsekai.core.home.HomeRegistryActor;
import org.wyrdsekai.core.home.HomeStore;
import org.wyrdsekai.core.persistence.SchemaInitializer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M2 migration: bilateral agreements materialize as
 * {@code home://did:zone:{local}/agreement/{remote}} grants, and revocations
 * take them back out.
 */
@Tag("integration")
class AgreementGrantSyncTest {

    private ActorTestKit testKit;
    private HomeStore homeStore;
    private HomeClient homeClient;
    private FederationService fed;
    private Path workspace;

    @BeforeEach void setUp() throws Exception {
        workspace = Files.createTempDirectory("agree-sync-test");
        testKit = ActorTestKit.create("AgreementGrantSyncTest",
            ConfigFactory.parseString("pekko.actor.provider = \"local\""));
        var homeJdbc = SchemaInitializer.initialize(workspace.resolve("home.db"));
        homeStore = new HomeStore(homeJdbc);
        var registry = testKit.spawn(HomeRegistryActor.create(homeStore));
        homeClient = new HomeClient(registry, testKit.system());

        // Separate DB for bilateral_agreements — shared-cache in-memory.
        var fedJdbc = "jdbc:sqlite:file:" + UUID.randomUUID() + "?mode=memory&cache=shared";
        initFedSchema(fedJdbc);
        fed = new FederationService(fedJdbc);
        fed.setGrantSync(new AgreementGrantSync(homeClient));
    }

    @SuppressWarnings("resource")
    private static void initFedSchema(String jdbcUrl) throws SQLException {
        var conn = DriverManager.getConnection(jdbcUrl);
        try (var s = conn.createStatement()) {
            s.execute("""
                CREATE TABLE IF NOT EXISTS bilateral_agreements(
                  local_zone_id   TEXT NOT NULL,
                  remote_zone_id  TEXT NOT NULL,
                  remote_public_key TEXT NOT NULL DEFAULT '',
                  status          TEXT NOT NULL DEFAULT 'pending',
                  trust_level     TEXT NOT NULL DEFAULT 'tourist',
                  agreed_at       INTEGER NOT NULL DEFAULT 0,
                  expires_at      INTEGER,
                  PRIMARY KEY (local_zone_id, remote_zone_id)
                )""");
        }
    }

    @AfterEach void tearDown() {
        if (testKit != null) testKit.shutdownTestKit();
    }

    @Test void active_agreement_materializes_as_use_grant() {
        fed.saveAgreement(new BilateralAgreement(
            "alpha", "beta", "pub-beta", "active", "tourist",
            Instant.now(), null, QuotaPolicy.forTrustLevel("tourist"),
            QuotaPolicy.forTrustLevel("tourist")));

        var issuer = AgreementGrantSync.zoneDid("alpha");
        var subject = AgreementGrantSync.zoneDid("beta");
        var resource = ResourceUri.of(issuer, ResourceTypeRegistry.AGREEMENT, "beta");

        var issued = activeGrants(issuer);
        assertThat(issued).hasSize(1);
        var g = issued.get(0);
        assertThat(g.subject()).isEqualTo(subject);
        assertThat(g.resource().toString()).isEqualTo(resource.toString());
        assertThat(g.capability()).isEqualTo(Capability.use);
        assertThat(g.scope()).containsKeys(
            "trustLevel", "localInferenceTokensPerDay", "remoteInferenceTokensPerDay");

        // A CheckAccess with matching scope passes. Access control on this
        // resource is informational until a quota-aware ResourceHandler lands.
        var requested = Map.<String, Object>of(
            "trustLevel", "tourist",
            "localInferenceTokensPerDay", g.scope().get("localInferenceTokensPerDay"),
            "remoteInferenceTokensPerDay", g.scope().get("remoteInferenceTokensPerDay"),
            "localStorageBytesTotal", g.scope().get("localStorageBytesTotal"),
            "localBandwidthBytesPerDay", g.scope().get("localBandwidthBytesPerDay"),
            "localAllowTransit", g.scope().get("localAllowTransit"),
            "localAllowTell", g.scope().get("localAllowTell"),
            "localAllowInventory", g.scope().get("localAllowInventory"));
        assertThat(homeClient.check(subject, resource, Capability.use, requested)).isTrue();
    }

    @Test void pending_agreement_does_not_materialize() {
        fed.saveAgreement(new BilateralAgreement(
            "alpha", "beta", "pub-beta", "pending", "tourist",
            Instant.now(), null));
        assertActiveGrants(AgreementGrantSync.zoneDid("alpha")).isEmpty();
    }

    @Test void revoked_agreement_removes_grant() {
        fed.saveAgreement(new BilateralAgreement(
            "alpha", "beta", "pub-beta", "active", "tourist",
            Instant.now(), null));
        var issuer = AgreementGrantSync.zoneDid("alpha");
        assertActiveGrants(issuer).hasSize(1);

        fed.updateAgreementStatus("alpha", "beta", "revoked");

        assertActiveGrants(issuer)
            .as("revoking agreement revokes the use-grant")
            .isEmpty();
    }

    @Test void status_flip_back_to_active_reissues() {
        fed.saveAgreement(new BilateralAgreement(
            "alpha", "beta", "pub-beta", "active", "tourist",
            Instant.now(), null));
        fed.updateAgreementStatus("alpha", "beta", "revoked");
        var issuer = AgreementGrantSync.zoneDid("alpha");
        assertActiveGrants(issuer).isEmpty();

        fed.updateAgreementStatus("alpha", "beta", "active");
        assertActiveGrants(issuer).hasSize(1);
    }

    @Test void second_save_idempotently_replaces() {
        fed.saveAgreement(new BilateralAgreement(
            "alpha", "beta", "pub-beta", "active", "tourist",
            Instant.now(), null));
        fed.saveAgreement(new BilateralAgreement(
            "alpha", "beta", "pub-beta", "active", "resident",
            Instant.now(), null));

        var issuer = AgreementGrantSync.zoneDid("alpha");
        var active = activeGrants(issuer);
        assertThat(active).hasSize(1);
        assertThat(active.get(0).scope().get("trustLevel")).isEqualTo("resident");
    }

    // Helper — grantsByIssuer returns all rows (including revoked); caller-side
    // filter to active is the query pattern REST handlers and UI use.
    private List<Grant> activeGrants(String issuer) {
        var now = Instant.now();
        return homeClient.listIssuedBy(issuer).stream()
            .filter(g -> g.isActive(now))
            .toList();
    }

    private ListAssert<Grant>
            assertActiveGrants(String issuer) {
        return assertThat(activeGrants(issuer));
    }
}
