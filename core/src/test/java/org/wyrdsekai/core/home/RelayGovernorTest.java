package org.wyrdsekai.core.home;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.common.home.Capability;
import org.wyrdsekai.common.home.RelayAdminOp;
import org.wyrdsekai.common.home.RelayAdminScope;
import org.wyrdsekai.core.persistence.SchemaInitializer;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * (P4) — the in-world governance binding
 * ({@link RelayGovernor}): per-action authorize-gating + that an authorized
 * action reaches the gateway with the right op, while a denied action never
 * touches the network.
 */
class RelayGovernorTest {

    private static final String OWNER = "did:key:owner";
    private static final String RELAY = "did:key:relayA";
    private static final String RELAY_LABEL = "relay.example.org";

    private ActorTestKit testKit;
    private HomeClient homeClient;
    private RelayGovernance gov;
    @TempDir Path workspace;

    @BeforeEach void setUp() {
        testKit = ActorTestKit.create("RelayGovernorTest",
            ConfigFactory.parseString("pekko.actor.provider = \"local\""));
        var jdbc = SchemaInitializer.initialize(workspace.resolve("relay-governor.db"));
        var store = new HomeStore(jdbc);
        var registry = testKit.spawn(HomeRegistryActor.create(store));
        homeClient = new HomeClient(registry, testKit.system());
        gov = new RelayGovernance(homeClient);
    }

    @AfterEach void tearDown() {
        if (testKit != null) testKit.shutdownTestKit();
    }

    /** A gateway that records the last op it was asked to call. */
    private static final class CapturingGateway implements RelayAdminGateway {
        final AtomicReference<String> lastOp = new AtomicReference<>();
        final AtomicReference<Map<String, Object>> lastArgs = new AtomicReference<>();
        int calls = 0;

        @Override public String actingDid() { return "did:key:signer"; }
        @Override public String relayDid() { return RELAY; }
        @Override public String relayLabel() { return RELAY_LABEL; }
        @Override public Map<String, Object> call(String op, Map<String, Object> args) {
            calls++;
            lastOp.set(op);
            lastArgs.set(args);
            // Mimic a successful relay response.
            if ("list".equals(op)) {
                return Map.of("ok", true, "status", 200,
                    "registrations", List.of(Map.of("did", "did:key:zMEMBER", "active", true)));
            }
            return Map.of("ok", true, "status", 200);
        }
    }

    private RelayGovernor governor(CapturingGateway gw) {
        return new RelayGovernor(gov, gw, OWNER, RELAY, RELAY_LABEL);
    }

    // --- Scope visibility (what the furnishing shows) ---------------------

    @Test void owner_sees_owner_scope_and_can_do_everything() {
        var g = governor(new CapturingGateway());
        assertThat(g.scopeOf(OWNER)).isEqualTo("owner");
        for (var op : RelayAdminOp.values()) {
            assertThat(g.canDo(OWNER, op)).as("owner can " + op).isTrue();
        }
        assertThat(g.canDelegate(OWNER)).isTrue();
    }

    @Test void zone_steward_is_owner_equivalent_even_when_not_ownerDid() {
        // #13 (2026-07-19 OSS hardening) — on a home relay the ownerDid is the
        // node's NKey identity, but the steward administers via their ACCOUNT
        // DID. Without the steward-check they were denied ALL relay admin on the
        // relay they own (scopeOf → null). The predicate makes them owner-
        // equivalent for zone-side authorization.
        var steward = "did:wyrd:steward-account";
        var gw = new CapturingGateway();
        var g = new RelayGovernor(gov, gw, OWNER, RELAY, RELAY_LABEL,
            did -> steward.equals(did));

        assertThat(g.scopeOf(steward)).isEqualTo("owner");
        for (var op : RelayAdminOp.values()) {
            assertThat(g.canDo(steward, op)).as("steward can " + op).isTrue();
        }
        assertThat(g.canDelegate(steward)).isTrue();

        // A non-steward, non-owner still gets nothing (bar open-to-any ops).
        var nobody = "did:key:nobody";
        assertThat(g.scopeOf(nobody)).isNull();
        for (var op : RelayAdminOp.values()) {
            if (!op.isOpenToAnySigner()) {
                assertThat(g.canDo(nobody, op)).as("nobody cannot " + op).isFalse();
            }
        }
    }

    @Test void moderation_grantee_sees_remove_and_list_but_not_grant_admin() {
        var bob = "did:key:bob";
        gov.grantAdmin(OWNER, bob, OWNER, RELAY, RelayAdminScope.MODERATION,
            Capability.use, null, "moderation");
        var g = governor(new CapturingGateway());

        assertThat(g.scopeOf(bob)).isEqualTo("moderation");
        assertThat(g.canDo(bob, RelayAdminOp.LIST)).isTrue();
        assertThat(g.canDo(bob, RelayAdminOp.REMOVE)).isTrue();
        assertThat(g.canDo(bob, RelayAdminOp.INVITE)).isTrue();   // lesser scope
        // Grant/admin + policy are FULL-only — hidden from the furnishing.
        assertThat(g.canDo(bob, RelayAdminOp.GRANT_ADMIN)).isFalse();
        assertThat(g.canDo(bob, RelayAdminOp.SET_MODE)).isFalse();
        assertThat(g.canDelegate(bob)).isFalse();
    }

    @Test void no_scope_caller_is_view_only_nothing() {
        var nobody = "did:key:nobody";
        var g = governor(new CapturingGateway());
        assertThat(g.scopeOf(nobody)).isNull();
        for (var op : RelayAdminOp.values()) {
            // Open-to-any-signer ops (e.g. REPORT:
            // filing a report needs no grant, the signature is the whole bar)
            // are intentionally allowed for a no-scope caller. Only grant-gated
            // ops must be denied.
            if (op.isOpenToAnySigner()) {
                assertThat(g.canDo(nobody, op)).as("nobody may " + op).isTrue();
            } else {
                assertThat(g.canDo(nobody, op)).as("nobody cannot " + op).isFalse();
            }
        }
    }

    // --- authorizeAndCall: gateway reached iff authorized -----------------

    @Test void authorized_action_reaches_gateway_with_right_op() {
        var gw = new CapturingGateway();
        var g = governor(gw);
        var res = g.authorizeAndCall(OWNER, RelayAdminOp.GRANT_ADMIN,
            Map.of("subject_did", "did:key:zX", "scope", "moderation"));
        assertThat(res.get("ok")).isEqualTo(true);
        assertThat(gw.calls).isEqualTo(1);
        assertThat(gw.lastOp.get()).isEqualTo("grant-admin");
        assertThat(gw.lastArgs.get()).containsEntry("subject_did", "did:key:zX");
    }

    @Test void denied_action_never_touches_the_gateway() {
        var carol = "did:key:carol";
        gov.grantAdmin(OWNER, carol, OWNER, RELAY, RelayAdminScope.MODERATION,
            Capability.use, null, "moderation");
        var gw = new CapturingGateway();
        var g = governor(gw);

        var res = g.authorizeAndCall(carol, RelayAdminOp.GRANT_ADMIN,
            Map.of("subject_did", "did:key:zX", "scope", "full"));
        assertThat(res.get("ok")).isEqualTo(false);
        assertThat(res.get("status")).isEqualTo(403);
        assertThat(gw.calls).as("no network call on a zone-side denial").isZero();
    }

    @Test void list_registrations_authorized_for_moderation() {
        var dave = "did:key:dave";
        gov.grantAdmin(OWNER, dave, OWNER, RELAY, RelayAdminScope.MODERATION,
            Capability.use, null, "moderation");
        var gw = new CapturingGateway();
        var g = governor(gw);

        var regs = g.listRegistrations(dave);
        assertThat(regs).hasSize(1);
        assertThat(gw.lastOp.get()).isEqualTo("list");
    }

    @Test void list_registrations_empty_when_caller_lacks_scope() {
        var gw = new CapturingGateway();
        var g = governor(gw);
        var regs = g.listRegistrations("did:key:stranger");
        assertThat(regs).isEmpty();
        assertThat(gw.calls).as("unauthorized list never hits the relay").isZero();
    }

    @Test void invite_only_grantee_can_invite_only() {
        var ivan = "did:key:ivan";
        gov.grantAdmin(OWNER, ivan, OWNER, RELAY, RelayAdminScope.INVITE_ONLY,
            Capability.use, null, "invite");
        var gw = new CapturingGateway();
        var g = governor(gw);

        assertThat(g.scopeOf(ivan)).isEqualTo("invite-only");
        assertThat(g.authorizeAndCall(ivan, RelayAdminOp.INVITE, Map.of()).get("ok"))
            .isEqualTo(true);
        // remove (moderation) is denied.
        assertThat(g.authorizeAndCall(ivan, RelayAdminOp.REMOVE,
            Map.of("pubkey", "UABC")).get("status")).isEqualTo(403);
    }
}
