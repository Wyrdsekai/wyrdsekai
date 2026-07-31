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
import org.wyrdsekai.common.home.GrantRequest;
import org.wyrdsekai.common.home.ResourceTypeRegistry;
import org.wyrdsekai.common.home.ResourceUri;
import org.wyrdsekai.core.persistence.SchemaInitializer;
import org.wyrdsekai.core.persistence.WardService;
import org.wyrdsekai.core.room.StudyProvisioner;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * end-to-end: Alice's Study is warded. Bob is denied entry.
 * Bob knocks via HomeProxy → a pending request lands on Alice's Board.
 * Alice approves → a Grant is minted + a silent ward row is written.
 * The next ward check for Bob on Alice's Study passes.
 */
class KnockApproveEnterE2ETest {

    private static final String ALICE = "alice-001";
    private static final String BOB   = "bob-002";

    private ActorTestKit testKit;
    private HomeClient homeClient;
    private WardService wardService;
    @TempDir Path workspace;

    @BeforeEach void setUp() {
        testKit = ActorTestKit.create("KnockApproveEnterE2ETest",
            ConfigFactory.parseString("pekko.actor.provider = \"local\""));
        var jdbc = SchemaInitializer.initialize(workspace.resolve("e2e.db"));
        var store = new HomeStore(jdbc);
        var registry = testKit.spawn(HomeRegistryActor.create(store));
        homeClient = new HomeClient(registry, testKit.system());
        wardService = new WardService(jdbc);
        wardService.setGrantSync(new WardGrantSync(homeClient));
    }

    @AfterEach void tearDown() {
        if (testKit != null) testKit.shutdownTestKit();
    }

    @Test void knock_approve_enter_full_flow() throws Exception {
        // 1. Alice's Study is warded — only Alice can enter.
        var studyRoom = StudyProvisioner.studyRoomId(ALICE);
        wardService.grant(studyRoom, ALICE, "enter", ALICE);
        wardService.grant(studyRoom, ALICE, "admin", ALICE);

        // 2. Bob is denied entry initially.
        assertThat(wardService.isAllowed(studyRoom, BOB, "enter"))
            .as("bob cannot enter warded study before knocking")
            .isFalse();

        // 3. Bob knocks via HomeProxy.
        var proxy = new HomeProxy.Local(homeClient, "alpha");
        var knockResult = proxy.knock(BOB, ALICE, "may I visit?");
        assertThat(knockResult.ok()).isTrue();

        // 4. Alice's Board sees the pending request.
        var pending = homeClient.pendingForOwner(ALICE);
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).id()).isEqualTo(knockResult.requestId());
        assertThat(pending.get(0).requester()).isEqualTo(BOB);
        assertThat(pending.get(0).resource().type())
            .isEqualTo(ResourceTypeRegistry.HOME_ROOM);

        // 5. Alice approves. The Grant is minted AND a ward row is silently written.
        var approved = homeClient.approveRequest(knockResult.requestId(), ALICE, null, "come in");
        assertThat(approved.status()).isEqualTo(
            GrantRequest.Status.approved);
        assertThat(approved.issuedGrantId()).isNotNull();

        // Close-the-loop: mimic the WyrdWebSocket handler's silent ward write.
        wardService.grantSilent(studyRoom, BOB, "enter", ALICE);

        // 6. Bob can now enter.
        assertThat(wardService.isAllowed(studyRoom, BOB, "enter"))
            .as("bob can enter after approve")
            .isTrue();

        // 7. The grant is visible via the normal Home APIs.
        var resource = ResourceUri.of(ALICE, ResourceTypeRegistry.HOME_ROOM);
        var heldByBob = homeClient.listHeldBy(BOB);
        assertThat(heldByBob).anySatisfy(g -> {
            assertThat(g.resource().toString()).isEqualTo(resource.toString());
            assertThat(g.capability()).isEqualTo(Capability.use);
            assertThat(g.isActive(Instant.now())).isTrue();
        });

        // 8. Audit trail on Alice's Home contains the full story.
        var entries = fetchAudit(ALICE);
        var verbs = entries.stream().map(AuditEntry::verb).toList();
        assertThat(verbs).contains(
            AuditEntry.Verb.GRANT_REQUESTED,
            AuditEntry.Verb.GRANT_ISSUED,
            AuditEntry.Verb.GRANT_APPROVED);

        // 9. Pending list is now empty.
        assertThat(homeClient.pendingForOwner(ALICE)).isEmpty();
    }

    @Test void deny_leaves_ward_unchanged() throws Exception {
        var studyRoom = StudyProvisioner.studyRoomId(ALICE);
        wardService.grant(studyRoom, ALICE, "enter", ALICE);

        var proxy = new HomeProxy.Local(homeClient, "alpha");
        var knockResult = proxy.knock(BOB, ALICE, "stranger");
        var denied = homeClient.denyRequest(knockResult.requestId(), ALICE, "not today");
        assertThat(denied.status()).isEqualTo(
            GrantRequest.Status.denied);

        // Bob still cannot enter.
        assertThat(wardService.isAllowed(studyRoom, BOB, "enter")).isFalse();
        // No grant was issued.
        assertThat(homeClient.listHeldBy(BOB)).isEmpty();
    }

    @Test void wrong_approver_cannot_let_bob_in() throws Exception {
        var studyRoom = StudyProvisioner.studyRoomId(ALICE);
        wardService.grant(studyRoom, ALICE, "enter", ALICE);
        var proxy = new HomeProxy.Local(homeClient, "alpha");
        var req = proxy.knock(BOB, ALICE, "please?");

        // Carol tries to approve Alice's request — rejected.
        try {
            homeClient.approveRequest(req.requestId(), "carol-003", null, null);
            assertThat(false).as("carol approval should have thrown").isTrue();
        } catch (Exception e) {
            assertThat(e.getMessage()).contains("not the resource owner");
        }

        assertThat(homeClient.pendingForOwner(ALICE)).hasSize(1);
        assertThat(wardService.isAllowed(studyRoom, BOB, "enter")).isFalse();
    }

    private List<AuditEntry> fetchAudit(String owner) throws Exception {
        return AskPattern
            .<HomeRegistryActor.Command, HomeRegistryActor.AuditList>ask(
                homeClient.registry(),
                replyTo -> new HomeRegistryActor.QueryAudit(owner, null, 100, replyTo),
                Duration.ofSeconds(5), testKit.system().scheduler())
            .toCompletableFuture().get(10, TimeUnit.SECONDS).entries();
    }
}
