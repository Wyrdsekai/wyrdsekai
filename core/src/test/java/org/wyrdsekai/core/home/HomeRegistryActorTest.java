package org.wyrdsekai.core.home;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.typed.ActorRef;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.common.home.AuditEntry;
import org.wyrdsekai.common.home.Capability;
import org.wyrdsekai.common.home.Grant;
import org.wyrdsekai.common.home.ResourceTypeRegistry;
import org.wyrdsekai.common.home.ResourceUri;
import org.wyrdsekai.common.home.RevocationMode;
import org.wyrdsekai.core.persistence.SchemaInitializer;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HomeRegistryActor + CheckAccess behaviour, grounded against a real SQLite
 * database initialized with the full schema. These tests fail-loud on any
 * regression to the grant model's correctness.
 */
class HomeRegistryActorTest {

    private static final ActorTestKit testKit = ActorTestKit.create(
        ConfigFactory.parseString("pekko.actor.provider = \"local\""));

    @AfterAll
    static void tearDownAll() { testKit.shutdownTestKit(); }

    @TempDir Path tempDir;
    private HomeStore store;
    private ActorRef<HomeRegistryActor.Command> actor;

    @BeforeEach
    void setUp() {
        var jdbcUrl = SchemaInitializer.initialize(tempDir.resolve("home-test.db"));
        store = new HomeStore(jdbcUrl);
        actor = testKit.spawn(HomeRegistryActor.create(store));
    }

    // --- CheckAccess core path ----------------------------------------

    @Test void allow_when_active_grant_matches() {
        var resource = ResourceUri.of("alice", ResourceTypeRegistry.COLLECTION, "library-notes");
        var grant = Grant.issue("alice", "wyrd", resource, Capability.read,
            Map.of(), Instant.now(), Instant.now().plusSeconds(3600), "testing");
        var issueProbe = testKit.<HomeRegistryActor.IssueResult>createTestProbe();
        actor.tell(new HomeRegistryActor.IssueGrant(grant, issueProbe.ref()));
        issueProbe.expectMessageClass(HomeRegistryActor.Issued.class);

        var accessProbe = testKit.<HomeRegistryActor.AccessDecision>createTestProbe();
        actor.tell(new HomeRegistryActor.CheckAccess(
            "wyrd", resource, Capability.read, Map.of(), accessProbe.ref()));
        var decision = accessProbe.receiveMessage();
        assertThat(decision).isInstanceOf(HomeRegistryActor.Allow.class);
        var allow = (HomeRegistryActor.Allow) decision;
        assertThat(allow.byGrant().id()).isEqualTo(grant.id());
    }

    @Test void deny_when_no_grant() {
        var resource = ResourceUri.of("alice", ResourceTypeRegistry.JOURNAL, "entry-1");
        var probe = testKit.<HomeRegistryActor.AccessDecision>createTestProbe();
        actor.tell(new HomeRegistryActor.CheckAccess(
            "mallory", resource, Capability.read, Map.of(), probe.ref()));
        assertThat(probe.receiveMessage()).isInstanceOf(HomeRegistryActor.Deny.class);
    }

    @Test void deny_after_revocation() {
        var resource = ResourceUri.of("alice", ResourceTypeRegistry.COLLECTION, "notes");
        var grant = Grant.issue("alice", "wyrd", resource, Capability.read,
            Map.of(), Instant.now(), Instant.now().plusSeconds(3600), null);
        var issueProbe = testKit.<HomeRegistryActor.IssueResult>createTestProbe();
        actor.tell(new HomeRegistryActor.IssueGrant(grant, issueProbe.ref()));
        issueProbe.expectMessageClass(HomeRegistryActor.Issued.class);

        var revokeProbe = testKit.<HomeRegistryActor.RevokeResult>createTestProbe();
        actor.tell(new HomeRegistryActor.RevokeGrant(grant.id(), "alice", revokeProbe.ref()));
        revokeProbe.expectMessageClass(HomeRegistryActor.Revoked.class);

        var accessProbe = testKit.<HomeRegistryActor.AccessDecision>createTestProbe();
        actor.tell(new HomeRegistryActor.CheckAccess(
            "wyrd", resource, Capability.read, Map.of(), accessProbe.ref()));
        assertThat(accessProbe.receiveMessage()).isInstanceOf(HomeRegistryActor.Deny.class);
    }

    @Test void deny_after_expiry() {
        var resource = ResourceUri.of("alice", ResourceTypeRegistry.COLLECTION, "notes");
        var expired = new Grant(
            UUID.randomUUID().toString(), "alice", "wyrd", resource, Capability.read,
            Map.of(), RevocationMode.standard,
            Instant.now().minusSeconds(7200),
            Instant.now().minusSeconds(60),  // already expired
            null, null, null, null);
        store.saveGrant(expired);

        var probe = testKit.<HomeRegistryActor.AccessDecision>createTestProbe();
        actor.tell(new HomeRegistryActor.CheckAccess(
            "wyrd", resource, Capability.read, Map.of(), probe.ref()));
        assertThat(probe.receiveMessage()).isInstanceOf(HomeRegistryActor.Deny.class);
    }

    // --- Grant shape validation ---------------------------------------

    @Test void reject_grant_with_invalid_capability_for_resource_type() {
        // journals don't support 'use' per ResourceTypeRegistry matrix
        var resource = ResourceUri.of("alice", ResourceTypeRegistry.JOURNAL, "entry-1");
        var bogus = Grant.issue("alice", "wyrd", resource, Capability.use,
            Map.of(), Instant.now(), null, null);
        var probe = testKit.<HomeRegistryActor.IssueResult>createTestProbe();
        actor.tell(new HomeRegistryActor.IssueGrant(bogus, probe.ref()));
        var result = probe.receiveMessage();
        assertThat(result).isInstanceOf(HomeRegistryActor.IssueError.class);
        assertThat(((HomeRegistryActor.IssueError) result).reason()).contains("not valid");
    }

    @Test void reject_grant_issued_by_non_owner_without_delegate() {
        var resource = ResourceUri.of("alice", ResourceTypeRegistry.COLLECTION, "notes");
        // mallory tries to grant on alice's resource — no delegate held
        var bogus = Grant.issue("mallory", "wyrd", resource, Capability.read,
            Map.of(), Instant.now(), null, null);
        var probe = testKit.<HomeRegistryActor.IssueResult>createTestProbe();
        actor.tell(new HomeRegistryActor.IssueGrant(bogus, probe.ref()));
        var result = probe.receiveMessage();
        assertThat(result).isInstanceOf(HomeRegistryActor.IssueError.class);
        assertThat(((HomeRegistryActor.IssueError) result).reason())
            .contains("not the resource owner");
    }

    // --- Public grants ------------------------------------------------

    @Test void public_grant_allows_any_subject() {
        var resource = ResourceUri.of("alice", ResourceTypeRegistry.JOURNAL, "public-post");
        var grant = Grant.issue("alice", Grant.PUBLIC_SUBJECT, resource, Capability.read,
            Map.of(), Instant.now(), null, "public opt-in");
        var issueProbe = testKit.<HomeRegistryActor.IssueResult>createTestProbe();
        actor.tell(new HomeRegistryActor.IssueGrant(grant, issueProbe.ref()));
        issueProbe.expectMessageClass(HomeRegistryActor.Issued.class);

        var accessProbe = testKit.<HomeRegistryActor.AccessDecision>createTestProbe();
        actor.tell(new HomeRegistryActor.CheckAccess(
            "random-stranger", resource, Capability.read, Map.of(), accessProbe.ref()));
        assertThat(accessProbe.receiveMessage()).isInstanceOf(HomeRegistryActor.Allow.class);
    }

    // --- Scope satisfaction -------------------------------------------

    @Test void scope_must_match_when_grant_narrows() {
        var resource = ResourceUri.of("alice", ResourceTypeRegistry.INFERENCE_BUDGET, null);
        // Granted 5000 tokens/day to beta
        var grant = Grant.issue("alice", "beta", resource, Capability.use,
            Map.of("dailyTokenCap", 5000),
            Instant.now(), Instant.now().plusSeconds(86400), null);
        var issueProbe = testKit.<HomeRegistryActor.IssueResult>createTestProbe();
        actor.tell(new HomeRegistryActor.IssueGrant(grant, issueProbe.ref()));
        issueProbe.expectMessageClass(HomeRegistryActor.Issued.class);

        // Request matching scope → allow
        var p1 = testKit.<HomeRegistryActor.AccessDecision>createTestProbe();
        actor.tell(new HomeRegistryActor.CheckAccess(
            "beta", resource, Capability.use, Map.of("dailyTokenCap", 5000), p1.ref()));
        assertThat(p1.receiveMessage()).isInstanceOf(HomeRegistryActor.Allow.class);

        // Request with different scope value → deny
        var p2 = testKit.<HomeRegistryActor.AccessDecision>createTestProbe();
        actor.tell(new HomeRegistryActor.CheckAccess(
            "beta", resource, Capability.use, Map.of("dailyTokenCap", 10000), p2.ref()));
        assertThat(p2.receiveMessage()).isInstanceOf(HomeRegistryActor.Deny.class);
    }

    // --- Delegation cascade -------------------------------------------

    @Test void revoking_parent_grant_cascades_to_delegated_children() {
        var resource = ResourceUri.of("alice", ResourceTypeRegistry.COLLECTION, "shared");
        // alice grants mallory delegate capability
        var parent = Grant.issue("alice", "mallory", resource, Capability.delegate,
            Map.of(), Instant.now(), null, "delegation parent");
        var issueProbe = testKit.<HomeRegistryActor.IssueResult>createTestProbe();
        actor.tell(new HomeRegistryActor.IssueGrant(parent, issueProbe.ref()));
        issueProbe.expectMessageClass(HomeRegistryActor.Issued.class);

        // mallory re-delegates to bob (directly via store; M3 will enforce chain validity)
        var child = new Grant(UUID.randomUUID().toString(),
            "mallory", "bob", resource, Capability.read,
            Map.of(), RevocationMode.standard,
            Instant.now(), null, null, null, null, parent.id());
        store.saveGrant(child);

        // Revoke parent
        var revokeProbe = testKit.<HomeRegistryActor.RevokeResult>createTestProbe();
        actor.tell(new HomeRegistryActor.RevokeGrant(parent.id(), "alice", revokeProbe.ref()));
        var revoked = (HomeRegistryActor.Revoked) revokeProbe.receiveMessage();
        assertThat(revoked.cascadeCount()).isEqualTo(1);

        // Child is now revoked too
        var fetchProbe = testKit.<HomeRegistryActor.GrantDetail>createTestProbe();
        actor.tell(new HomeRegistryActor.FetchGrant(child.id(), fetchProbe.ref()));
        var detail = fetchProbe.receiveMessage();
        assertThat(detail.grant()).isNotNull();
        assertThat(detail.grant().isRevoked()).isTrue();
    }

    // --- Audit trail --------------------------------------------------

    @Test void issue_revoke_and_access_are_audited() {
        var resource = ResourceUri.of("alice", ResourceTypeRegistry.COLLECTION, "notes");
        var grant = Grant.issue("alice", "wyrd", resource, Capability.read,
            Map.of(), Instant.now(), Instant.now().plusSeconds(3600), null);
        var issueProbe = testKit.<HomeRegistryActor.IssueResult>createTestProbe();
        actor.tell(new HomeRegistryActor.IssueGrant(grant, issueProbe.ref()));
        issueProbe.expectMessageClass(HomeRegistryActor.Issued.class);

        var accessProbe = testKit.<HomeRegistryActor.AccessDecision>createTestProbe();
        actor.tell(new HomeRegistryActor.CheckAccess(
            "wyrd", resource, Capability.read, Map.of(), accessProbe.ref()));
        accessProbe.expectMessageClass(HomeRegistryActor.Allow.class);

        var revokeProbe = testKit.<HomeRegistryActor.RevokeResult>createTestProbe();
        actor.tell(new HomeRegistryActor.RevokeGrant(grant.id(), "alice", revokeProbe.ref()));
        revokeProbe.expectMessageClass(HomeRegistryActor.Revoked.class);

        var auditProbe = testKit.<HomeRegistryActor.AuditList>createTestProbe();
        actor.tell(new HomeRegistryActor.QueryAudit("alice", null, 100, auditProbe.ref()));
        var audit = auditProbe.receiveMessage().entries();
        var verbs = audit.stream().map(e -> e.verb()).toList();
        assertThat(verbs).contains(
            AuditEntry.Verb.GRANT_ISSUED,
            AuditEntry.Verb.ACCESS_GRANTED,
            AuditEntry.Verb.GRANT_REVOKED);
    }

    // --- Enumeration --------------------------------------------------

    @Test void enumerate_issued_and_held_separates_by_role() {
        var r1 = ResourceUri.of("alice", ResourceTypeRegistry.COLLECTION, "a1");
        var r2 = ResourceUri.of("alice", ResourceTypeRegistry.COLLECTION, "a2");
        var g1 = Grant.issue("alice", "wyrd", r1, Capability.read, Map.of(),
            Instant.now(), null, null);
        var g2 = Grant.issue("alice", "bob", r2, Capability.read, Map.of(),
            Instant.now(), null, null);
        var p = testKit.<HomeRegistryActor.IssueResult>createTestProbe();
        actor.tell(new HomeRegistryActor.IssueGrant(g1, p.ref()));
        p.expectMessageClass(HomeRegistryActor.Issued.class);
        actor.tell(new HomeRegistryActor.IssueGrant(g2, p.ref()));
        p.expectMessageClass(HomeRegistryActor.Issued.class);

        var issuedProbe = testKit.<HomeRegistryActor.GrantList>createTestProbe();
        actor.tell(new HomeRegistryActor.EnumerateIssued("alice", issuedProbe.ref()));
        assertThat(issuedProbe.receiveMessage().grants()).hasSize(2);

        var heldProbe = testKit.<HomeRegistryActor.GrantList>createTestProbe();
        actor.tell(new HomeRegistryActor.EnumerateHeld("wyrd", heldProbe.ref()));
        assertThat(heldProbe.receiveMessage().grants()).hasSize(1);
    }

    @Test void home_entered_audit_writes_to_owners_log() {
        // when an owner arrives at their Home (via login
        // landing or the `home` command), their Embers (audit log) records it.
        // This test exercises the direct AppendAudit path that HomeClient uses.
        var ownerDid = "alice";
        var entry = AuditEntry.now(
            ownerDid, ownerDid,
            AuditEntry.Verb.HOME_ENTERED,
            "home://" + ownerDid + "/home-room",
            AuditEntry.Outcome.ok,
            Map.of("via", "home-command"),
            null);
        actor.tell(new HomeRegistryActor.AppendAudit(entry));

        // AppendAudit is fire-and-forget. Poll the audit log briefly.
        var deadline = System.currentTimeMillis() + 1500;
        List<AuditEntry> audit = List.of();
        while (System.currentTimeMillis() < deadline) {
            var probe = testKit.<HomeRegistryActor.AuditList>createTestProbe();
            actor.tell(new HomeRegistryActor.QueryAudit(ownerDid, null, 50, probe.ref()));
            audit = probe.receiveMessage().entries();
            if (audit.stream().anyMatch(e ->
                AuditEntry.Verb.HOME_ENTERED.equals(e.verb()))) break;
            try { Thread.sleep(25); } catch (InterruptedException ignored) {}
        }
        assertThat(audit).anyMatch(e ->
            AuditEntry.Verb.HOME_ENTERED.equals(e.verb())
            && "home-command".equals(e.detail().get("via")));
    }

    @Test void summary_includes_issued_held_counts_and_recent_audit() {
        var r = ResourceUri.of("alice", ResourceTypeRegistry.COLLECTION, "notes");
        var g = Grant.issue("alice", "wyrd", r, Capability.read, Map.of(),
            Instant.now(), null, null);
        var issueProbe = testKit.<HomeRegistryActor.IssueResult>createTestProbe();
        actor.tell(new HomeRegistryActor.IssueGrant(g, issueProbe.ref()));
        issueProbe.expectMessageClass(HomeRegistryActor.Issued.class);

        var probe = testKit.<HomeRegistryActor.HomeSummary>createTestProbe();
        actor.tell(new HomeRegistryActor.GetSummary("alice", probe.ref()));
        var summary = probe.receiveMessage();
        assertThat(summary.ownerDid()).isEqualTo("alice");
        assertThat(summary.grantsIssued()).isEqualTo(1);
        assertThat(summary.grantsIssuedActive()).isEqualTo(1);
        assertThat(summary.recentAudit()).isNotEmpty();
    }
}
