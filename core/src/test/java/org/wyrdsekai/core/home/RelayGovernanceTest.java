package org.wyrdsekai.core.home;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.common.home.Capability;
import org.wyrdsekai.common.home.Grant;
import org.wyrdsekai.common.home.RelayAdminOp;
import org.wyrdsekai.common.home.RelayAdminScope;
import org.wyrdsekai.common.home.ResourceTypeRegistry;
import org.wyrdsekai.core.persistence.SchemaInitializer;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * a relay is a Home-owned resource governed by Grants
 * and relay-admin delegation is a Grant — not a bespoke admin-role system. These
 * tests exercise the {@link RelayGovernance} authorization predicate (P3's hook)
 * over real grants persisted via {@link HomeRegistryActor}.
 */
class RelayGovernanceTest {

    private static final String OWNER = "did:key:owner";
    private static final String RELAY_A = "did:key:relayA";
    private static final String RELAY_B = "did:key:relayB";

    private ActorTestKit testKit;
    private HomeClient homeClient;
    private RelayGovernance gov;
    @TempDir Path workspace;

    @BeforeEach void setUp() {
        testKit = ActorTestKit.create("RelayGovernanceTest",
            ConfigFactory.parseString("pekko.actor.provider = \"local\""));
        var jdbc = SchemaInitializer.initialize(workspace.resolve("relay-gov.db"));
        var store = new HomeStore(jdbc);
        var registry = testKit.spawn(HomeRegistryActor.create(store));
        homeClient = new HomeClient(registry, testKit.system());
        gov = new RelayGovernance(homeClient);
    }

    @AfterEach void tearDown() {
        if (testKit != null) testKit.shutdownTestKit();
    }

    // --- Resource type registration --------------------------------------

    @Test void relay_resource_types_are_registered() {
        assertThat(ResourceTypeRegistry.get(ResourceTypeRegistry.RELAY)).isNotNull();
        assertThat(ResourceTypeRegistry.get(ResourceTypeRegistry.RELAY_ADMIN)).isNotNull();
        // relay-admin carries use + delegate, and is id-bearing (keyed on relay DID).
        var admin = ResourceTypeRegistry.get(ResourceTypeRegistry.RELAY_ADMIN);
        assertThat(admin.supports(Capability.use)).isTrue();
        assertThat(admin.supports(Capability.delegate)).isTrue();
        assertThat(admin.supports(Capability.write)).isFalse();
        assertThat(admin.hasId()).isTrue();
    }

    @Test void issue_rejects_invalid_capability_on_relay_admin() {
        var resource = RelayGovernance.relayAdminResource(OWNER, RELAY_A);
        var bad = Grant.issue(OWNER, "did:key:x", resource, Capability.write,
            RelayGovernance.scopePayload(RelayAdminScope.MODERATION, RELAY_A),
            Instant.now(), null, "bad");
        assertThatThrownBy(() -> homeClient.issue(bad))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // --- Owner sovereignty -----------------------------------------------

    @Test void owner_allowed_at_all_scopes() {
        for (var op : RelayAdminOp.values()) {
            assertThat(gov.authorize(OWNER, OWNER, RELAY_A, op))
                .as("owner should be allowed: " + op)
                .isTrue();
        }
        assertThat(gov.canDelegate(OWNER, OWNER, RELAY_A)).isTrue();
    }

    // --- Scope gating ----------------------------------------------------

    @Test void moderation_grantee_allowed_moderation_denied_policy() {
        var bob = "did:key:bob";
        gov.grantAdmin(OWNER, bob, OWNER, RELAY_A, RelayAdminScope.MODERATION,
            Capability.use, null, "moderation delegate");

        // Moderation ops allowed.
        assertThat(gov.authorize(bob, OWNER, RELAY_A, RelayAdminOp.LIST)).isTrue();
        assertThat(gov.authorize(bob, OWNER, RELAY_A, RelayAdminOp.REMOVE)).isTrue();
        assertThat(gov.authorize(bob, OWNER, RELAY_A, RelayAdminOp.PROMOTE)).isTrue();
        assertThat(gov.authorize(bob, OWNER, RELAY_A, RelayAdminOp.REPORT_QUEUE)).isTrue();
        assertThat(gov.authorize(bob, OWNER, RELAY_A, RelayAdminOp.RESOLVE_REPORT)).isTrue();
        // Invite is a lesser scope — moderation covers it.
        assertThat(gov.authorize(bob, OWNER, RELAY_A, RelayAdminOp.INVITE)).isTrue();
        // Policy / mode / grant-admin require FULL — denied.
        assertThat(gov.authorize(bob, OWNER, RELAY_A, RelayAdminOp.SET_MODE)).isFalse();
        assertThat(gov.authorize(bob, OWNER, RELAY_A, RelayAdminOp.SET_POLICY)).isFalse();
        assertThat(gov.authorize(bob, OWNER, RELAY_A, RelayAdminOp.GRANT_ADMIN)).isFalse();
        // use-cap grantee cannot re-delegate.
        assertThat(gov.canDelegate(bob, OWNER, RELAY_A)).isFalse();
    }

    @Test void invite_only_grantee_allowed_invite_only() {
        var carol = "did:key:carol";
        gov.grantAdmin(OWNER, carol, OWNER, RELAY_A, RelayAdminScope.INVITE_ONLY,
            Capability.use, null, "invite delegate");

        assertThat(gov.authorize(carol, OWNER, RELAY_A, RelayAdminOp.INVITE)).isTrue();
        // Nothing else.
        assertThat(gov.authorize(carol, OWNER, RELAY_A, RelayAdminOp.LIST)).isFalse();
        assertThat(gov.authorize(carol, OWNER, RELAY_A, RelayAdminOp.REMOVE)).isFalse();
        assertThat(gov.authorize(carol, OWNER, RELAY_A, RelayAdminOp.SET_MODE)).isFalse();
    }

    @Test void full_grantee_allowed_everything() {
        var dave = "did:key:dave";
        gov.grantAdmin(OWNER, dave, OWNER, RELAY_A, RelayAdminScope.FULL,
            Capability.use, null, "full delegate");
        for (var op : RelayAdminOp.values()) {
            assertThat(gov.authorize(dave, OWNER, RELAY_A, op))
                .as("full grantee should be allowed: " + op)
                .isTrue();
        }
    }

    @Test void stranger_denied_with_no_grant() {
        assertThat(gov.authorize("did:key:nobody", OWNER, RELAY_A, RelayAdminOp.LIST)).isFalse();
        assertThat(gov.authorize("did:key:nobody", OWNER, RELAY_A, RelayAdminOp.INVITE)).isFalse();
    }

    // --- §8 reports: filing is open to any signer, viewing/resolving is not ---

    @Test void filing_a_report_is_open_to_any_signer() {
        // A DID with NO relay-admin grant may file a report (the zone-side
        // parallel of the relay's _OPEN_TO_ANY_SIGNER exemption, §8) ...
        var anyone = "did:key:anyone";
        assertThat(gov.authorize(anyone, OWNER, RELAY_A, RelayAdminOp.REPORT)).isTrue();
        assertThat(RelayAdminOp.REPORT.isOpenToAnySigner()).isTrue();
        // ... but cannot VIEW or RESOLVE the queue (those need moderation).
        assertThat(gov.authorize(anyone, OWNER, RELAY_A, RelayAdminOp.REPORT_QUEUE)).isFalse();
        assertThat(gov.authorize(anyone, OWNER, RELAY_A, RelayAdminOp.RESOLVE_REPORT)).isFalse();
        assertThat(RelayAdminOp.REPORT_QUEUE.isOpenToAnySigner()).isFalse();
        assertThat(RelayAdminOp.RESOLVE_REPORT.isOpenToAnySigner()).isFalse();
    }

    // --- Expiry / revocation ---------------------------------------------

    @Test void expired_grant_denied() {
        var eve = "did:key:eve";
        var resource = RelayGovernance.relayAdminResource(OWNER, RELAY_A);
        // Already-expired grant.
        var grant = Grant.issue(OWNER, eve, resource, Capability.use,
            RelayGovernance.scopePayload(RelayAdminScope.MODERATION, RELAY_A),
            Instant.now().minusSeconds(3600), Instant.now().minusSeconds(60), "expired");
        homeClient.issue(grant);
        assertThat(gov.authorize(eve, OWNER, RELAY_A, RelayAdminOp.LIST)).isFalse();
    }

    @Test void revoked_grant_denied() {
        var frank = "did:key:frank";
        var grant = gov.grantAdmin(OWNER, frank, OWNER, RELAY_A, RelayAdminScope.MODERATION,
            Capability.use, null, "to be revoked");
        assertThat(gov.authorize(frank, OWNER, RELAY_A, RelayAdminOp.LIST)).isTrue();

        assertThat(homeClient.revoke(grant.id(), OWNER)).isTrue();
        assertThat(gov.authorize(frank, OWNER, RELAY_A, RelayAdminOp.LIST)).isFalse();
    }

    // --- §4.5 cascade delegation -----------------------------------------

    @Test void delegate_cap_holder_can_reissue_moderation_third_party_cannot_redelegate() {
        var delegateZone = "did:key:moderatorzone";
        var thirdParty = "did:key:helper";
        var resource = RelayGovernance.relayAdminResource(OWNER, RELAY_A);

        // Owner grants the delegate zone moderation WITH delegate capability.
        var parent = gov.grantAdmin(OWNER, delegateZone, OWNER, RELAY_A,
            RelayAdminScope.MODERATION, Capability.delegate, null, "delegated moderation");
        assertThat(gov.canDelegate(delegateZone, OWNER, RELAY_A)).isTrue();

        // Delegate zone re-issues a moderation USE grant to a third party,
        // referencing the parent (§4.5 cascade). The HomeRegistryActor validates
        // the child is a subset up the chain to the owner.
        var child = new Grant(
            UUID.randomUUID().toString(),
            delegateZone,          // issuer = the delegate
            thirdParty,            // subject = third DID
            resource,
            Capability.use,
            RelayGovernance.scopePayload(RelayAdminScope.MODERATION, RELAY_A),
            null,                  // standard revocation mode
            Instant.now(),
            null, null, "sub-delegated", null,
            parent.id());          // delegatedFrom -> parent
        var issued = homeClient.issue(child);
        assertThat(issued.delegatedFrom()).isEqualTo(parent.id());

        // Third party is now allowed moderation ops.
        assertThat(gov.authorize(thirdParty, OWNER, RELAY_A, RelayAdminOp.LIST)).isTrue();
        assertThat(gov.authorize(thirdParty, OWNER, RELAY_A, RelayAdminOp.REMOVE)).isTrue();
        // But cannot perform full ops, and cannot re-delegate (use cap only).
        assertThat(gov.authorize(thirdParty, OWNER, RELAY_A, RelayAdminOp.SET_MODE)).isFalse();
        assertThat(gov.canDelegate(thirdParty, OWNER, RELAY_A)).isFalse();
    }

    @Test void revoking_parent_cascades_to_child() {
        var delegateZone = "did:key:moderatorzone2";
        var thirdParty = "did:key:helper2";
        var resource = RelayGovernance.relayAdminResource(OWNER, RELAY_A);
        var parent = gov.grantAdmin(OWNER, delegateZone, OWNER, RELAY_A,
            RelayAdminScope.MODERATION, Capability.delegate, null, "parent");
        var child = new Grant(
            UUID.randomUUID().toString(),
            delegateZone, thirdParty, resource, Capability.use,
            RelayGovernance.scopePayload(RelayAdminScope.MODERATION, RELAY_A),
            null, Instant.now(), null, null, "child", null, parent.id());
        homeClient.issue(child);
        assertThat(gov.authorize(thirdParty, OWNER, RELAY_A, RelayAdminOp.LIST)).isTrue();

        // Revoke the parent — the child cascades.
        assertThat(homeClient.revoke(parent.id(), OWNER)).isTrue();
        assertThat(gov.authorize(delegateZone, OWNER, RELAY_A, RelayAdminOp.LIST)).isFalse();
        assertThat(gov.authorize(thirdParty, OWNER, RELAY_A, RelayAdminOp.LIST)).isFalse();
    }

    @Test void delegate_cannot_broaden_scope_beyond_parent() {
        var delegateZone = "did:key:modzone3";
        var thirdParty = "did:key:helper3";
        var resource = RelayGovernance.relayAdminResource(OWNER, RELAY_A);
        // Parent is moderation-scoped.
        var parent = gov.grantAdmin(OWNER, delegateZone, OWNER, RELAY_A,
            RelayAdminScope.MODERATION, Capability.delegate, null, "moderation parent");
        // Attempt to re-issue a FULL grant — must be rejected by §4.5 subset
        // (child scope key 'relay-scope' would differ from parent's).
        var overbroad = new Grant(
            UUID.randomUUID().toString(),
            delegateZone, thirdParty, resource, Capability.use,
            RelayGovernance.scopePayload(RelayAdminScope.FULL, RELAY_A),
            null, Instant.now(), null, null, "overbroad", null, parent.id());
        assertThatThrownBy(() -> homeClient.issue(overbroad))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // --- Relay-id narrowing ----------------------------------------------

    @Test void grant_for_relay_A_does_not_authorize_relay_B() {
        var zane = "did:key:zane";
        gov.grantAdmin(OWNER, zane, OWNER, RELAY_A, RelayAdminScope.FULL,
            Capability.use, null, "relay A only");
        assertThat(gov.authorize(zane, OWNER, RELAY_A, RelayAdminOp.LIST)).isTrue();
        // Relay B is a different resource (different URI id) -> no grant.
        assertThat(gov.authorize(zane, OWNER, RELAY_B, RelayAdminOp.LIST)).isFalse();
    }

    // --- Op/scope mapping vocabulary -------------------------------------

    @Test void op_scope_mapping_matches_spec() {
        assertThat(RelayAdminOp.INVITE.requiredScope()).isEqualTo(RelayAdminScope.INVITE_ONLY);
        assertThat(RelayAdminOp.LIST.requiredScope()).isEqualTo(RelayAdminScope.MODERATION);
        assertThat(RelayAdminOp.REMOVE.requiredScope()).isEqualTo(RelayAdminScope.MODERATION);
        assertThat(RelayAdminOp.PROMOTE.requiredScope()).isEqualTo(RelayAdminScope.MODERATION);
        assertThat(RelayAdminOp.DEMOTE.requiredScope()).isEqualTo(RelayAdminScope.MODERATION);
        assertThat(RelayAdminOp.REPORT_QUEUE.requiredScope()).isEqualTo(RelayAdminScope.MODERATION);
        assertThat(RelayAdminOp.RESOLVE_REPORT.requiredScope()).isEqualTo(RelayAdminScope.MODERATION);
        // `report`'s enum scope is MODERATION for a stable vocabulary, but filing
        // is open to any signer (§8) — the scope value is not the gate.
        assertThat(RelayAdminOp.REPORT.requiredScope()).isEqualTo(RelayAdminScope.MODERATION);
        assertThat(RelayAdminOp.REPORT.isOpenToAnySigner()).isTrue();
        assertThat(RelayAdminOp.parse("resolve-report")).isEqualTo(RelayAdminOp.RESOLVE_REPORT);
        assertThat(RelayAdminOp.SET_MODE.requiredScope()).isEqualTo(RelayAdminScope.FULL);
        assertThat(RelayAdminOp.SET_POLICY.requiredScope()).isEqualTo(RelayAdminScope.FULL);
        assertThat(RelayAdminOp.GRANT_ADMIN.requiredScope()).isEqualTo(RelayAdminScope.FULL);

        assertThat(RelayAdminOp.parse("set-mode")).isEqualTo(RelayAdminOp.SET_MODE);
        assertThat(RelayAdminOp.parse("REPORT_QUEUE")).isEqualTo(RelayAdminOp.REPORT_QUEUE);
        assertThat(RelayAdminScope.parse("moderation")).isEqualTo(RelayAdminScope.MODERATION);
        assertThat(RelayAdminScope.FULL.covers(RelayAdminScope.INVITE_ONLY)).isTrue();
        assertThat(RelayAdminScope.INVITE_ONLY.covers(RelayAdminScope.MODERATION)).isFalse();
    }
}
