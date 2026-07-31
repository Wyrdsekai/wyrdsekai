package org.wyrdsekai.core.home;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.common.home.Capability;
import org.wyrdsekai.common.home.GrantRequest;
import org.wyrdsekai.common.home.ResourceTypeRegistry;
import org.wyrdsekai.common.home.ResourceUri;
import org.wyrdsekai.core.persistence.SchemaInitializer;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ACTION: owner-issued grants to enumerate which actions a
 * companion may take on the owner's behalf.
 */
class ActionGrantCheckTest {

    private ActorTestKit testKit;
    private HomeClient homeClient;
    @TempDir Path workspace;

    @BeforeEach void setUp() {
        testKit = ActorTestKit.create("ActionGrantCheckTest",
            ConfigFactory.parseString("pekko.actor.provider = \"local\""));
        var jdbc = SchemaInitializer.initialize(workspace.resolve("action.db"));
        var store = new HomeStore(jdbc);
        var registry = testKit.spawn(HomeRegistryActor.create(store));
        homeClient = new HomeClient(registry, testKit.system());
    }

    @AfterEach void tearDown() {
        if (testKit != null) testKit.shutdownTestKit();
    }

    @Test void permissive_allows_without_grant() {
        var check = ActionGrantCheck.homeClientBacked(homeClient, false);
        assertThat(check.canPerform("companion", "owner", "web_search")).isTrue();
    }

    @Test void strict_denies_without_grant() {
        var check = ActionGrantCheck.homeClientBacked(homeClient, true);
        assertThat(check.canPerform("companion", "owner", "web_search")).isFalse();
    }

    @Test void strict_allows_when_owner_grants_action() {
        var check = ActionGrantCheck.homeClientBacked(homeClient, true);
        var resource = ResourceUri.of("owner", ResourceTypeRegistry.ACTION, "web_search");
        homeClient.issueOrReplace("owner", "companion", resource,
            Capability.use, Map.of(), null, "allowed");

        assertThat(check.canPerform("companion", "owner", "web_search")).isTrue();
        // Different action still denied.
        assertThat(check.canPerform("companion", "owner", "delete_all")).isFalse();
    }

    @Test void allowAll_always_allows() {
        var check = ActionGrantCheck.allowAll();
        assertThat(check.canPerform(null, null, null)).isTrue();
        assertThat(check.canPerform("any", "any", "any")).isTrue();
    }

    /**
     * The full in-world consent loop wired 2026-07-21 (AutonomyGate →
     * request_access → Board approve): the companion's denial template names
     * home://{owner}/action/{verb}; request_access files a GrantRequest for
     * it; the owner's approve mints the Grant; the same ActionGrantCheck the
     * gate consults then says yes.
     */
    @Test void request_access_then_owner_approve_unlocks_the_verb() {
        var check = ActionGrantCheck.homeClientBacked(homeClient, true);
        assertThat(check.canPerform("companion-ember", "owner", "teleport_to")).isFalse();

        // Companion files the request exactly as handleRequestAccess does
        // from the denial's inWorldResolution template.
        var resource = ResourceUri.parse("home://owner/action/teleport_to");
        var req = GrantRequest.create("companion-ember", resource.owner(), resource,
            Capability.use, Map.of(), "I'd like to come to you when you call.");
        var stored = homeClient.createRequest(req);
        assertThat(stored.status()).isEqualTo(GrantRequest.Status.pending);

        // Still denied while pending.
        assertThat(check.canPerform("companion-ember", "owner", "teleport_to")).isFalse();

        // Owner approves at the Board — mints the Grant.
        var approved = homeClient.approveRequest(stored.id(), "owner", null, "ok");
        assertThat(approved.status()).isEqualTo(GrantRequest.Status.approved);

        assertThat(check.canPerform("companion-ember", "owner", "teleport_to")).isTrue();
        // Scoped to the verb — a different action stays denied.
        assertThat(check.canPerform("companion-ember", "owner", "release_bond")).isFalse();
    }
}
