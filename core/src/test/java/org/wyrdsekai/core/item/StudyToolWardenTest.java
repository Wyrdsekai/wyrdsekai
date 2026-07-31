package org.wyrdsekai.core.item;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.home.HomeClient;
import org.wyrdsekai.core.home.HomeRegistryActor;
import org.wyrdsekai.core.home.HomeStore;
import org.wyrdsekai.core.mcp.McpGrantAdmin;
import org.wyrdsekai.core.mcp.McpServiceConfig;
import org.wyrdsekai.core.mcp.McpServiceRegistry;
import org.wyrdsekai.core.persistence.SchemaInitializer;
import org.wyrdsekai.core.room.RoomAuthority;
import org.wyrdsekai.scripting.api.ItemWorldApiProvider;
import org.wyrdsekai.scripting.sandbox.ItemScriptExecutor;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the Study "Tool Warden" furnishing end-to-end through GraalJS:
 * item script → {@code world.mcp.grant/services/revoke} → ItemWorldApiProvider →
 * McpGrantAdmin → HomeRegistryActor. Proves the steward's in-world UX for
 * authorizing MCP capabilities actually issues/revokes real grants.
 */
class StudyToolWardenTest {

    private static final String STEWARD = "did:key:steward";
    private static final String SERVICE = "searxng";

    private ActorTestKit testKit;
    private McpGrantAdmin admin;
    private ItemWorldApiProvider stewardProvider;
    private final ItemScriptExecutor executor = new ItemScriptExecutor();

    @TempDir Path workspace;

    @BeforeEach
    void setUp() {
        testKit = ActorTestKit.create("StudyToolWardenTest",
            ConfigFactory.parseString("pekko.actor.provider = \"local\""));
        var jdbc = SchemaInitializer.initialize(workspace.resolve("home.db"));
        var registryActor = testKit.spawn(HomeRegistryActor.create(new HomeStore(jdbc)));
        var homeClient = new HomeClient(registryActor, testKit.system());

        RoomAuthority.grantAdmin(STEWARD);

        var registry = new McpServiceRegistry();
        registry.register(new McpServiceConfig(
            SERVICE, "SearXNG", "http", "http://127.0.0.1:1/mcp", "local", null, null, true));

        admin = new McpGrantAdmin(homeClient, STEWARD, registry);
        McpGrantAdmin.install(admin);

        // Provider mirroring ItemWorldApiProviderImpl's delegation: the acting
        // caller is the steward, so grants are authorized. Extends the concrete
        // VisitorItemProvider so the many unrelated world.* methods are stubbed.
        stewardProvider = new VisitorItemProvider("alpha", "alpha") {
            @Override public String callerDid() { return STEWARD; }
            @Override public List<Map<String, Object>> mcpGrantServices() { return admin.services(STEWARD); }
            @Override public List<Map<String, Object>> mcpGrantList() { return admin.grants(STEWARD); }
            @Override public Map<String, Object> mcpGrantIssue(String s, String svc) {
                return admin.grant(STEWARD, s, svc);
            }
            @Override public Map<String, Object> mcpGrantRevoke(String s, String svc) {
                return admin.revoke(STEWARD, s, svc);
            }
        };
    }

    @AfterEach
    void tearDown() {
        if (testKit != null) testKit.shutdownTestKit();
    }

    private String run(Map<String, Object> params) {
        var item = StudyFurnishingKit.toolWarden();
        var result = executor.execute(item.id(), item.script(), params, stewardProvider);
        return String.valueOf(result.get("text"));
    }

    @Test
    void view_lists_configured_services() {
        var text = run(Map.of());
        assertThat(text).contains("The Tool Warden");
        assertThat(text).contains(SERVICE);
        assertThat(text).contains("keys held by: — none —");
    }

    @Test
    void grant_via_item_then_view_shows_grantee() {
        var granted = run(Map.of("op", "grant", "agent", "ma", "service", SERVICE));
        assertThat(granted).contains("Key handed over: ma may now use " + SERVICE);

        var view = run(Map.of());
        assertThat(view).contains("keys held by: ma");
    }

    @Test
    void revoke_via_item_removes_grant() {
        run(Map.of("op", "grant", "agent", "everyone", "service", SERVICE));
        var revoked = run(Map.of("op", "revoke", "agent", "everyone", "service", SERVICE));
        assertThat(revoked).contains("Key taken back: public");
        assertThat(run(Map.of())).contains("keys held by: — none —");
    }
}
