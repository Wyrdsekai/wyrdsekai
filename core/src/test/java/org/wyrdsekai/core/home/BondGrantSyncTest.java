package org.wyrdsekai.core.home;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.common.home.Capability;
import org.wyrdsekai.common.home.Grant;
import org.wyrdsekai.common.home.ResourceTypeRegistry;
import org.wyrdsekai.common.home.ResourceUri;
import org.wyrdsekai.core.persistence.SchemaInitializer;
import org.wyrdsekai.core.soul.Bond;
import org.wyrdsekai.core.soul.BondRitual;
import org.wyrdsekai.core.soul.BondStore;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BOND: bonds materialize as reciprocal
 * {@code home://X/bond/Y} read-grants.
 */
class BondGrantSyncTest {

    private ActorTestKit testKit;
    private HomeClient homeClient;
    private BondRitual ritual;
    @TempDir Path workspace;

    @BeforeEach void setUp() {
        testKit = ActorTestKit.create("BondGrantSyncTest",
            ConfigFactory.parseString("pekko.actor.provider = \"local\""));
        var jdbc = SchemaInitializer.initialize(workspace.resolve("bond.db"));
        var homeStore = new HomeStore(jdbc);
        var registry = testKit.spawn(HomeRegistryActor.create(homeStore));
        homeClient = new HomeClient(registry, testKit.system());

        var bondStore = new BondStore(jdbc);
        ritual = new BondRitual(bondStore);
        ritual.setListener(new BondGrantSync(homeClient));
    }

    @AfterEach void tearDown() {
        if (testKit != null) testKit.shutdownTestKit();
    }

    @Test void acquaintance_materializes_reciprocal_grants() {
        ritual.formAcquaintance("alice", "bob");

        var aliceSeesBob = ResourceUri.of("alice", ResourceTypeRegistry.BOND, "bob");
        var bobSeesAlice = ResourceUri.of("bob", ResourceTypeRegistry.BOND, "alice");

        var aliceIssued = activeIssued("alice");
        assertThat(aliceIssued).hasSize(1);
        assertThat(aliceIssued.get(0).subject()).isEqualTo("bob");
        assertThat(aliceIssued.get(0).resource().toString()).isEqualTo(aliceSeesBob.toString());
        assertThat(aliceIssued.get(0).capability()).isEqualTo(Capability.read);

        var bobIssued = activeIssued("bob");
        assertThat(bobIssued).hasSize(1);
        assertThat(bobIssued.get(0).subject()).isEqualTo("alice");
        assertThat(bobIssued.get(0).resource().toString()).isEqualTo(bobSeesAlice.toString());
    }

    @Test void severance_revokes_both_grants() {
        var bond = ritual.formAcquaintance("alice", "bob");
        assertThat(activeIssued("alice")).hasSize(1);
        assertThat(activeIssued("bob")).hasSize(1);

        ritual.sever(bond.bondId());

        assertThat(activeIssued("alice")).isEmpty();
        assertThat(activeIssued("bob")).isEmpty();
    }

    @Test void elevation_updates_scope_depth() {
        var bond = ritual.formAcquaintance("alice", "bob");
        // Manually elevate via proposal + accept flow
        var proposal = ritual.proposeRitual(bond.bondId(), "alice", "first ritual");
        ritual.acceptRitual(proposal.proposalId());

        var aliceIssued = activeIssued("alice");
        assertThat(aliceIssued).hasSize(1);
        assertThat(aliceIssued.get(0).scope().get("depth")).isEqualTo(Bond.BondDepth.FAMILIAR.name());
    }

    @Test void interaction_updates_scope_count() {
        var bond = ritual.formAcquaintance("alice", "bob");
        ritual.recordInteraction(bond.bondId());
        ritual.recordInteraction(bond.bondId());

        var aliceIssued = activeIssued("alice");
        assertThat(aliceIssued).hasSize(1);
        var count = ((Number) aliceIssued.get(0).scope().get("interactionCount")).intValue();
        assertThat(count).isEqualTo(2);
    }

    private List<Grant> activeIssued(String who) {
        var now = Instant.now();
        return homeClient.listIssuedBy(who).stream()
            .filter(g -> g.isActive(now))
            .toList();
    }
}
