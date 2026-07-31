package org.wyrdsekai.core.mcp;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.common.home.Capability;
import org.wyrdsekai.common.home.ResourceTypeRegistry;
import org.wyrdsekai.common.home.ResourceUri;
import org.wyrdsekai.core.home.HomeClient;
import org.wyrdsekai.core.home.HomeRegistryActor;
import org.wyrdsekai.core.home.HomeStore;
import org.wyrdsekai.core.persistence.SchemaInitializer;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MCP_TOOL: owner-issued grants gate MCP tool invocations.
 */
class McpGrantCheckTest {

    private ActorTestKit testKit;
    private HomeClient homeClient;
    @TempDir Path workspace;

    @BeforeEach void setUp() {
        testKit = ActorTestKit.create("McpGrantCheckTest",
            ConfigFactory.parseString("pekko.actor.provider = \"local\""));
        var jdbc = SchemaInitializer.initialize(workspace.resolve("mcp.db"));
        var store = new HomeStore(jdbc);
        var registry = testKit.spawn(HomeRegistryActor.create(store));
        homeClient = new HomeClient(registry, testKit.system());
    }

    @AfterEach void tearDown() {
        if (testKit != null) testKit.shutdownTestKit();
    }

    @Test void permissive_mode_allows_when_no_grant() {
        var check = McpGrantCheck.homeClientBacked(homeClient, false);
        assertThat(check.canUse("alice", "github", "issue_create")).isTrue();
    }

    @Test void strict_mode_denies_when_no_grant() {
        var check = McpGrantCheck.homeClientBacked(homeClient, true);
        assertThat(check.canUse("alice", "github", "issue_create")).isFalse();
    }

    @Test void strict_mode_allows_when_grant_present() {
        var check = McpGrantCheck.homeClientBacked(homeClient, true);
        var resource = ResourceUri.of("alice", ResourceTypeRegistry.MCP_TOOL, "github/issue_create");
        homeClient.issueOrReplace("alice", "alice", resource, Capability.use,
            Map.of(), null, "test-grant");

        assertThat(check.canUse("alice", "github", "issue_create")).isTrue();
    }

    @Test void strict_mode_denies_different_tool() {
        var check = McpGrantCheck.homeClientBacked(homeClient, true);
        var resource = ResourceUri.of("alice", ResourceTypeRegistry.MCP_TOOL, "github/issue_create");
        homeClient.issueOrReplace("alice", "alice", resource, Capability.use,
            Map.of(), null, "test-grant");

        // Grant is for issue_create, not repo_delete.
        assertThat(check.canUse("alice", "github", "repo_delete")).isFalse();
    }

    @Test void null_caller_follows_mode_default() {
        var permissive = McpGrantCheck.homeClientBacked(homeClient, false);
        var strict = McpGrantCheck.homeClientBacked(homeClient, true);
        assertThat(permissive.canUse(null, "github", "any")).isTrue();
        assertThat(strict.canUse(null, "github", "any")).isFalse();
    }
}
