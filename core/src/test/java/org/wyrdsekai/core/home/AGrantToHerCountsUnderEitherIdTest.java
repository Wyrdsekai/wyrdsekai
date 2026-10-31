package org.wyrdsekai.core.home;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.common.home.Capability;
import org.wyrdsekai.common.home.ResourceTypeRegistry;
import org.wyrdsekai.common.home.ResourceUri;
import org.wyrdsekai.core.agent.CompanionRegistry;
import org.wyrdsekai.core.persistence.SchemaInitializer;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A companion goes by two ids: her DID and her entity id. A person's grant may name either (the
 * sharing page grants by the DID; her own request files under the entity id), and a check may
 * come with either. A grant to her counts under both, and to no one else.
 */
class AGrantToHerCountsUnderEitherIdTest {

    private static final String OWNER = "did:key:z6MkExamplePerson";
    private static final String WREN_DID = "did:key:z6MkExampleWren";
    private static final String WREN = "companion-wren";
    private static final String ROOK_DID = "did:key:z6MkExampleRook";
    private static final String ROOK = "companion-rook";

    private ActorTestKit testKit;
    private HomeClient home;
    private CompanionRegistry companions;
    private final ResourceUri books = ResourceUri.of(OWNER, ResourceTypeRegistry.COLLECTION, "books");
    @TempDir Path workspace;

    @BeforeEach void setUp() {
        testKit = ActorTestKit.create("AGrantToHerCountsUnderEitherIdTest",
            ConfigFactory.parseString("pekko.actor.provider = \"local\""));
        var jdbc = SchemaInitializer.initialize(workspace.resolve("home.db"));
        home = new HomeClient(testKit.spawn(HomeRegistryActor.create(new HomeStore(jdbc))), testKit.system());
        companions = new CompanionRegistry(jdbc);
        companions.register(WREN_DID, WREN, "wren", "zone");
        companions.register(ROOK_DID, ROOK, "rook", "zone");
    }

    @AfterEach void tearDown() {
        if (testKit != null) testKit.shutdownTestKit();
    }

    private boolean reads(String asker) {
        return home.check(asker, books, Capability.read, Map.of());
    }

    @Test void a_grant_to_her_did_counts_when_she_asks_with_her_entity_id() {
        home.issueOrReplace(OWNER, WREN_DID, books, Capability.read, Map.of(), null, "test");

        assertThat(reads(WREN_DID)).isTrue();
        assertThat(reads(WREN)).isTrue();
    }

    @Test void a_grant_to_her_entity_id_counts_when_she_asks_with_her_did() {
        home.issueOrReplace(OWNER, WREN, books, Capability.read, Map.of(), null, "test");

        assertThat(reads(WREN)).isTrue();
        assertThat(reads(WREN_DID)).isTrue();
    }

    @Test void her_grant_is_not_her_housemates() {
        home.issueOrReplace(OWNER, WREN_DID, books, Capability.read, Map.of(), null, "test");

        assertThat(reads(ROOK)).isFalse();
        assertThat(reads(ROOK_DID)).isFalse();
        assertThat(reads("did:key:z6MkSomeoneElse")).isFalse();
    }

    @Test void a_revoked_grant_counts_under_neither() {
        home.issueOrReplace(OWNER, WREN_DID, books, Capability.read, Map.of(), null, "test");
        home.revokeByKey(OWNER, WREN_DID, books, Capability.read);

        assertThat(reads(WREN_DID)).isFalse();
        assertThat(reads(WREN)).isFalse();
    }

    @Test void an_archived_record_joins_nothing() {
        home.issueOrReplace(OWNER, WREN_DID, books, Capability.read, Map.of(), null, "test");
        companions.archive(WREN_DID);

        assertThat(reads(WREN)).isFalse();
        assertThat(reads(WREN_DID)).as("the grant still names this DID").isTrue();
    }

    @Test void the_grants_she_holds_are_listed_under_either_id() {
        home.issueOrReplace(OWNER, WREN_DID, books, Capability.read, Map.of(), null, "test");

        assertThat(home.listHeldBy(WREN)).hasSize(1);
        assertThat(home.listHeldBy(WREN_DID)).hasSize(1);
        assertThat(home.listHeldBy(ROOK)).isEmpty();
    }
}
