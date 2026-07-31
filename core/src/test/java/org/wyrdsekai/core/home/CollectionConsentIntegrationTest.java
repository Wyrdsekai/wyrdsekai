package org.wyrdsekai.core.home;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.common.home.AuditEntry;
import org.wyrdsekai.common.home.Capability;
import org.wyrdsekai.common.home.Grant;
import org.wyrdsekai.common.home.ResourceTypeRegistry;
import org.wyrdsekai.common.home.ResourceUri;
import org.wyrdsekai.core.library.StudyService;
import org.wyrdsekai.core.persistence.SchemaInitializer;
import org.wyrdsekai.core.search.WyrdLuceneStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration test for the M1a cut-over: collection consent now
 * goes through {@link HomeRegistryActor} / {@link HomeClient} instead of an
 * in-memory map. Verifies that:
 *
 * <ol>
 *   <li>Grant issuance via {@code StudyService.grantAccess} lands as a real
 *       row in the grants table with the right resource URI, capability, and
 *       subject.</li>
 *   <li>{@code hasAccess} resolves by {@code CheckAccess} on the registry.</li>
 *   <li>{@code revokeAccess} marks the grant revoked and {@code hasAccess}
 *       flips to false.</li>
 *   <li>Each operation leaves a trail in the audit log keyed to the owner's
 *       Home.</li>
 *   <li>A companion without a grant gets denied.</li>
 *   <li>The "journal" shortcut (owner's own companion reads shared journal
 *       without needing a grant) still works — no grant table row needed.</li>
 * </ol>
 */
class CollectionConsentIntegrationTest {

    private static final String ALICE = "did:key:z6MkAlice001";
    private static final String WYRD = "companion-wyrd";

    private static ActorTestKit testKit;
    private static Path workspace;
    private static WyrdLuceneStore lucene;
    private static HomeStore homeStore;
    private static HomeClient homeClient;
    private static StudyService study;

    @BeforeAll
    static void setUp() throws Exception {
        workspace = Files.createTempDirectory("home-consent-it-");
        lucene = new WyrdLuceneStore(workspace.resolve("search"), 384);
        lucene.ensureAllCollections();

        testKit = ActorTestKit.create("CollectionConsentIT",
            ConfigFactory.parseString("pekko.actor.provider = \"local\""));

        var jdbcUrl = SchemaInitializer.initialize(workspace.resolve("home.db"));
        homeStore = new HomeStore(jdbcUrl);
        var registry = testKit.spawn(HomeRegistryActor.create(homeStore));
        homeClient = new HomeClient(registry, testKit.system());
        study = new StudyService(lucene, homeClient);
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (testKit != null) testKit.shutdownTestKit();
        if (lucene != null) lucene.close();
    }

    @Test void grant_writes_real_row_and_access_check_resolves() {
        // No access yet → deny
        assertThat(study.hasAccess(ALICE, WYRD, "library-notes")).isFalse();

        // Grant
        study.grantAccess(ALICE, WYRD, "library-notes");

        // Actor says yes
        assertThat(study.hasAccess(ALICE, WYRD, "library-notes")).isTrue();

        // Grant is persisted with the expected shape
        var granted = homeClient.listIssuedBy(ALICE);
        assertThat(granted).anySatisfy(g -> {
            assertThat(g.issuer()).isEqualTo(ALICE);
            assertThat(g.subject()).isEqualTo(WYRD);
            assertThat(g.resource().type()).isEqualTo(ResourceTypeRegistry.COLLECTION);
            assertThat(g.resource().id()).isEqualTo("library-notes");
            assertThat(g.capability()).isEqualTo(Capability.read);
            assertThat(g.isActive(Instant.now())).isTrue();
        });
    }

    @Test void revoke_flips_access_and_is_audited() {
        study.grantAccess(ALICE, WYRD, "private-journal");
        assertThat(study.hasAccess(ALICE, WYRD, "private-journal")).isTrue();

        study.revokeAccess(ALICE, WYRD, "private-journal");
        assertThat(study.hasAccess(ALICE, WYRD, "private-journal")).isFalse();

        // Audit log contains grant-issued + grant-revoked + access checks for this resource
        var auditForAlice = homeStore.queryAudit(ALICE, null, 200);
        var verbs = auditForAlice.stream().map(AuditEntry::verb).toList();
        assertThat(verbs).contains(
            AuditEntry.Verb.GRANT_ISSUED,
            AuditEntry.Verb.GRANT_REVOKED);
        // At least one access check (post-revoke deny).
        assertThat(verbs).contains(AuditEntry.Verb.ACCESS_DENIED);
    }

    @Test void journal_shortcut_does_not_require_a_grant() {
        // Shared journal is always readable by the owner's companion — the
        // StudyService short-circuits before consulting HomeRegistry.
        assertThat(study.hasAccess(ALICE, WYRD, "journal")).isTrue();
        // And there is no grant row for it — confirm we didn't accidentally
        // start issuing implicit grants for the shortcut.
        var rows = homeClient.listIssuedBy(ALICE);
        var journalRow = rows.stream()
            .filter(g -> "journal".equals(g.resource().id()))
            .findFirst();
        assertThat(journalRow).as("journal shortcut must not be backed by a stored grant")
            .isEmpty();
    }

    @Test void idempotent_re_grant_does_not_accumulate_rows() {
        // Re-granting the same access should replace, not accumulate.
        var beforeCount = homeClient.listIssuedBy(ALICE).stream()
            .filter(g -> "recipes".equals(g.resource().id())).count();
        assertThat(beforeCount).isZero();

        study.grantAccess(ALICE, WYRD, "recipes");
        study.grantAccess(ALICE, WYRD, "recipes");
        study.grantAccess(ALICE, WYRD, "recipes");

        var active = homeClient.listIssuedBy(ALICE).stream()
            .filter(g -> "recipes".equals(g.resource().id()))
            .filter(g -> g.isActive(Instant.now()))
            .count();
        assertThat(active).as("re-grant must produce exactly one active row").isEqualTo(1);
    }

    @Test void direct_actor_check_matches_service_decision() {
        study.grantAccess(ALICE, WYRD, "travel-log");
        // Using the actor's CheckAccess directly on the same resource should
        // agree with the service's hasAccess.
        var resource = ResourceUri.of(ALICE, ResourceTypeRegistry.COLLECTION, "travel-log");
        assertThat(homeClient.check(WYRD, resource, Capability.read, Map.of())).isTrue();
        // A different subject is denied.
        assertThat(homeClient.check("mallory", resource, Capability.read, Map.of())).isFalse();
    }

    @Test void list_grants_returns_only_active_collection_reads() {
        // Some grants for alice
        study.grantAccess(ALICE, WYRD, "notes");
        study.grantAccess(ALICE, WYRD, "health");
        study.revokeAccess(ALICE, WYRD, "notes");

        var grants = study.listGrants(ALICE);
        // "health" should still be there; "notes" revoked
        assertThat(grants.keySet()).anyMatch(k -> k.contains(":health"));
        assertThat(grants.keySet()).noneMatch(k -> k.contains(":notes"));
    }
}
