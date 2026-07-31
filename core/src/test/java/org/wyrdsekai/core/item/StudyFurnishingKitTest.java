package org.wyrdsekai.core.item;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.common.home.AuditEntry;
import org.wyrdsekai.common.home.Capability;
import org.wyrdsekai.common.home.Grant;
import org.wyrdsekai.common.home.GrantRequest;
import org.wyrdsekai.common.home.ResourceTypeRegistry;
import org.wyrdsekai.common.home.ResourceUri;
import org.wyrdsekai.core.agent.AgentCostTracker;
import org.wyrdsekai.core.home.HomeClient;
import org.wyrdsekai.core.home.HomeRegistryActor;
import org.wyrdsekai.core.home.HomeStore;
import org.wyrdsekai.core.persistence.SchemaInitializer;
import org.wyrdsekai.scripting.sandbox.ItemScriptExecutor;

import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration test for scripted furnishings.
 * Drives the full stack: StudyFurnishingKit scripts → ItemScriptExecutor →
 * world.audit/world.grants/world.home → HomeOwnerItemProvider → HomeRegistryActor.
 *
 * <p>Catches regressions in the script→API→actor chain, which is load-bearing
 * for every Home surface we add.</p>
 */
class StudyFurnishingKitTest {

    private static final String OWNER = "did:key:z6MkAlice001";

    private static ActorTestKit testKit;
    private static HomeStore homeStore;
    private static HomeClient homeClient;

    @TempDir static Path workspace;

    @BeforeAll
    static void setUp() {
        testKit = ActorTestKit.create("StudyFurnishingKitTest",
            ConfigFactory.parseString("pekko.actor.provider = \"local\""));
        var jdbcUrl = SchemaInitializer.initialize(workspace.resolve("home.db"));
        homeStore = new HomeStore(jdbcUrl);
        var registry = testKit.spawn(HomeRegistryActor.create(homeStore));
        homeClient = new HomeClient(registry, testKit.system());
    }

    @AfterAll
    static void tearDown() {
        if (testKit != null) testKit.shutdownTestKit();
    }

    @Test void kit_has_all_expected_furnishings() {
        var items = StudyFurnishingKit.defaults();
        assertThat(items).extracting("id")
            .containsExactlyInAnyOrder("embers", "board", "mailbox",
                "ledger", "manifest", "trunk",
                "shelf", "lantern", "mirror", "compass", "window",
                "threshold",
                // Phase 1b
                "coding-slate",
                "codex",
                // (P4)
                "warden",
                // MCP capability grants (steward) MCP_TOOL
                "tool-warden");
        assertThat(items).allMatch(ToolItem::isScripted,
            "every furnishing must have a script");
    }

    // ─── Warden (relay governance, ) ────

    /** A minimal provider exposing the relay namespace, capturing the last op. */
    private static class WardenProvider extends VisitorItemProvider {
        final String scope;
        final List<Map<String, Object>> regs;
        boolean configured = true;
        String lastOp;
        Map<String, Object> lastArgs;
        WardenProvider(String scope, List<Map<String, Object>> regs) {
            super("alpha", "alpha");
            this.scope = scope; this.regs = regs;
        }
        @Override public String callerDid() { return OWNER; }
        @Override public Map<String, Object> relayInfo() {
            var m = new HashMap<String, Object>();
            m.put("configured", configured);
            m.put("relayDid", "did:key:zRELAY");
            m.put("relayLabel", "relay.example.org");
            m.put("ownerDid", OWNER);
            m.put("scope", scope);
            m.put("canDelegate", "owner".equals(scope) || "full".equals(scope));
            return m;
        }
        @Override public List<Map<String, Object>> relayRegistrations() {
            return regs == null ? List.of() : regs;
        }
        @Override public List<Map<String, Object>> relayDelegations() {
            return List.of();
        }
        @Override public Map<String, Object> relayAdminAction(String op, Map<String, Object> args) {
            lastOp = op;
            // Snapshot into a plain map — the incoming map is a GraalJS proxy
            // tied to the script context, which closes after execute() returns.
            lastArgs = args == null ? new HashMap<>() : new HashMap<>(args);
            return Map.of("ok", true, "status", 200,
                "invite_url", "wyrdrelay://relay.example.org/TOK");
        }
    }

    @Test void warden_view_shows_registrations_and_actions_for_owner() {
        var executor = new ItemScriptExecutor();
        var regs = List.<Map<String, Object>>of(
            new HashMap<>(Map.of("did", "did:key:zMEMBER", "pubkey", "UABC",
                "active", true, "tier", "HOUSEHOLD")));
        var provider = new WardenProvider("owner", regs);
        var warden = StudyFurnishingKit.warden();
        var result = executor.execute(warden.id(), warden.script(), Map.of(), provider);
        var text = String.valueOf(result.get("text"));
        assertThat(text).contains("Warden of relay relay.example.org");
        assertThat(text).contains("your role: owner");
        assertThat(text).contains("[LIVE]");
        assertThat(text).contains("grant-admin");          // owner sees delegation actions
        assertThat(text).contains("action=reports");        // P6 reports surface
        assertThat(text).contains("Coming later");
    }

    @Test void warden_hides_grant_admin_for_moderation_scope() {
        var executor = new ItemScriptExecutor();
        var provider = new WardenProvider("moderation", List.of());
        var warden = StudyFurnishingKit.warden();
        var result = executor.execute(warden.id(), warden.script(), Map.of(), provider);
        var text = String.valueOf(result.get("text"));
        assertThat(text).contains("your role: moderation");
        assertThat(text).contains("action=remove");          // moderation can kick
        assertThat(text).doesNotContain("action=grant-admin"); // but not delegate
    }

    @Test void warden_view_only_when_no_scope() {
        var executor = new ItemScriptExecutor();
        var provider = new WardenProvider(null, List.of());
        var warden = StudyFurnishingKit.warden();
        var result = executor.execute(warden.id(), warden.script(), Map.of(), provider);
        var text = String.valueOf(result.get("text"));
        assertThat(text).contains("none of its tags answer to you");
    }

    @Test void warden_invite_action_invokes_relay_with_right_op() {
        var executor = new ItemScriptExecutor();
        var provider = new WardenProvider("owner", List.of());
        var warden = StudyFurnishingKit.warden();
        var result = executor.execute(warden.id(), warden.script(),
            Map.of("action", "invite", "ttl", 3600), provider);
        assertThat(provider.lastOp).isEqualTo("invite");
        assertThat(provider.lastArgs).containsEntry("ttl", 3600);
        assertThat(String.valueOf(result.get("text"))).contains("invite succeeded");
    }

    @Test void warden_grant_admin_action_passes_did_and_scope() {
        var executor = new ItemScriptExecutor();
        var provider = new WardenProvider("owner", List.of());
        var warden = StudyFurnishingKit.warden();
        executor.execute(warden.id(), warden.script(),
            Map.of("action", "grant-admin", "did", "did:key:zX", "scope", "moderation"),
            provider);
        assertThat(provider.lastOp).isEqualTo("grant-admin");
        assertThat(provider.lastArgs).containsEntry("subject_did", "did:key:zX");
        assertThat(provider.lastArgs).containsEntry("scope", "moderation");
    }

    @Test void warden_placeholder_action_does_not_hit_relay() {
        var executor = new ItemScriptExecutor();
        var provider = new WardenProvider("owner", List.of());
        var warden = StudyFurnishingKit.warden();
        var result = executor.execute(warden.id(), warden.script(),
            Map.of("action", "promote", "did", "did:key:zX"), provider);
        assertThat(String.valueOf(result.get("text"))).contains("arrives later");
        assertThat(provider.lastOp).as("unwired op must not hit the relay").isNull();
    }

    @Test void warden_reports_action_invokes_report_queue() {
        var executor = new ItemScriptExecutor();
        var report = new HashMap<String, Object>();
        report.put("id", "rpt-abc123");
        report.put("subject_did", "did:key:zSUBJECTxxxxxxxxxxxx");
        report.put("reporter_did", "did:key:zREPORTERyyyyyyyy");
        report.put("reason", "spamming the lobby");
        report.put("created_at", "2026-06-17T00:00:00");
        report.put("status", "open");
        report.put("subject_present", true);
        var provider = new ReportingWardenProvider("moderation",
            List.of(report));
        var warden = StudyFurnishingKit.warden();
        var result = executor.execute(warden.id(), warden.script(),
            Map.of("action", "reports"), provider);
        assertThat(provider.lastOp).isEqualTo("report-queue");
        var text = String.valueOf(result.get("text"));
        assertThat(text).contains("rpt-abc123");
        assertThat(text).contains("spamming the lobby");
    }

    @Test void warden_resolve_action_passes_id_and_verdict() {
        var executor = new ItemScriptExecutor();
        var provider = new ReportingWardenProvider("moderation", List.of());
        var warden = StudyFurnishingKit.warden();
        var result = executor.execute(warden.id(), warden.script(),
            Map.of("action", "resolve", "report", "rpt-abc123", "verdict", "noted"),
            provider);
        assertThat(provider.lastOp).isEqualTo("resolve-report");
        assertThat(provider.lastArgs).containsEntry("report_id", "rpt-abc123");
        assertThat(provider.lastArgs).containsEntry("action", "noted");
        assertThat(String.valueOf(result.get("text"))).contains("resolved as 'noted'");
    }

    @Test void warden_resolve_action_needs_report_and_verdict() {
        var executor = new ItemScriptExecutor();
        var provider = new ReportingWardenProvider("moderation", List.of());
        var warden = StudyFurnishingKit.warden();
        var result = executor.execute(warden.id(), warden.script(),
            Map.of("action", "resolve", "report", "rpt-abc123"), provider);
        assertThat(String.valueOf(result.get("text"))).contains("needs report=");
        assertThat(provider.lastOp).as("incomplete resolve must not hit the relay").isNull();
    }

    @Test void warden_view_shows_reports_for_moderator() {
        var executor = new ItemScriptExecutor();
        var report = new HashMap<String, Object>();
        report.put("id", "rpt-xyz");
        report.put("subject_did", "did:key:zSUB");
        report.put("reporter_did", "did:key:zREP");
        report.put("reason", "harassment");
        report.put("created_at", "2026-06-17T00:00:00");
        report.put("status", "open");
        report.put("subject_present", false);
        var provider = new ReportingWardenProvider("owner", List.of(report));
        var warden = StudyFurnishingKit.warden();
        var result = executor.execute(warden.id(), warden.script(), Map.of(), provider);
        var text = String.valueOf(result.get("text"));
        assertThat(text).contains("Reports queue (1 open)");
        assertThat(text).contains("rpt-xyz");
        assertThat(text).contains("(left)");   // subject_present=false annotated
    }

    /** Warden provider whose relay surface understands the P6 report ops. */
    private static class ReportingWardenProvider extends WardenProvider {
        final List<Map<String, Object>> reports;
        ReportingWardenProvider(String scope, List<Map<String, Object>> reports) {
            super(scope, List.of());
            this.reports = reports;
        }
        @Override public Map<String, Object> relayAdminAction(String op, Map<String, Object> args) {
            lastOp = op;
            lastArgs = args == null ? new HashMap<>() : new HashMap<>(args);
            if ("report-queue".equals(op)) {
                return Map.of("ok", true, "status", 200,
                    "reports", reports, "open_count", reports.size());
            }
            if ("resolve-report".equals(op)) {
                return Map.of("ok", true, "status", 200,
                    "report_id", String.valueOf(args == null ? "" : args.get("report_id")),
                    "resolution", String.valueOf(args == null ? "" : args.get("action")),
                    "status_field", "resolved");
            }
            return Map.of("ok", true, "status", 200);
        }
    }

    @Test void warden_no_relay_configured_message() {
        var executor = new ItemScriptExecutor();
        var provider = new WardenProvider(null, List.of());
        provider.configured = false;
        var warden = StudyFurnishingKit.warden();
        var result = executor.execute(warden.id(), warden.script(), Map.of(), provider);
        assertThat(String.valueOf(result.get("text"))).contains("does not administer any relay");
    }

    @Test void embers_reads_recent_audit_entries() throws Exception {
        // Seed the owner's Home with a handful of audit entries.
        for (var verb : new String[]{"grant-issued", "home-entered", "access-granted"}) {
            homeStore.appendAudit(AuditEntry.now(
                OWNER, OWNER, verb, "home://" + OWNER + "/home-room",
                AuditEntry.Outcome.ok,
                Map.of("note", "test"), null));
        }

        var executor = new ItemScriptExecutor();
        var provider = new HomeOwnerItemProvider("alpha", "alpha",
            OWNER, homeClient, testKit.system());
        var params = new HashMap<String, Object>();
        params.put("limit", 10);

        var embers = StudyFurnishingKit.embers();
        var result = executor.execute(embers.id(), embers.script(), params, provider);

        var text = String.valueOf(result.get("text"));
        assertThat(text).contains("events in your Home");
        assertThat(text).contains("grant-issued");
        assertThat(text).contains("home-entered");
        // The `events` list should also be returned for structured consumers.
        assertThat(result.get("events")).isNotNull();
    }

    @Test void embers_handles_empty_log_gracefully() {
        // Fresh owner with no audit history.
        var newOwner = "did:key:z6MkFresh001";
        var executor = new ItemScriptExecutor();
        var provider = new HomeOwnerItemProvider("alpha", "alpha",
            newOwner, homeClient, testKit.system());

        var embers = StudyFurnishingKit.embers();
        var result = executor.execute(embers.id(), embers.script(), Map.of(), provider);
        var text = String.valueOf(result.get("text"));
        assertThat(text).contains("rest quietly");
    }

    @Test void board_shows_issued_grants_with_active_count() {
        // Issue two grants to different subjects.
        var resource = ResourceUri.of(OWNER, ResourceTypeRegistry.COLLECTION, "notes");
        var resource2 = ResourceUri.of(OWNER, ResourceTypeRegistry.COLLECTION, "health");
        homeStore.saveGrant(Grant.issue(OWNER, "companion-wyrd", resource,
            Capability.read, Map.of(), Instant.now(), null, "for searching"));
        homeStore.saveGrant(Grant.issue(OWNER, "did:key:z6MkBob002", resource2,
            Capability.read, Map.of(), Instant.now(), null, "shared shelf"));

        var executor = new ItemScriptExecutor();
        var provider = new HomeOwnerItemProvider("alpha", "alpha",
            OWNER, homeClient, testKit.system());

        var board = StudyFurnishingKit.board();
        var result = executor.execute(board.id(), board.script(), Map.of(), provider);
        var text = String.valueOf(result.get("text"));
        assertThat(text).contains("Pinned to the board");
        assertThat(text).contains("companion-wyrd");
        assertThat(text).contains("did:key:z6MkBob002");
        assertThat(text).contains("notes");
        assertThat(text).contains("health");
        assertThat(result.get("active")).isInstanceOf(Number.class);
        assertThat(((Number) result.get("active")).intValue()).isGreaterThanOrEqualTo(2);
    }

    @Test void board_shows_pending_grant_requests_when_any() {
        var requester = "did:key:z6MkBob-knocker";
        // Create a pending grant-request against OWNER's home-room.
        var resource = ResourceUri.of(OWNER, ResourceTypeRegistry.HOME_ROOM);
        var req = GrantRequest.create(
            requester, OWNER, resource, Capability.use, Map.of(), "just visiting");
        homeClient.createRequest(req);

        var executor = new ItemScriptExecutor();
        var provider = new HomeOwnerItemProvider("alpha", "alpha",
            OWNER, homeClient, testKit.system());

        var board = StudyFurnishingKit.board();
        var result = executor.execute(board.id(), board.script(), Map.of(), provider);
        var text = String.valueOf(result.get("text"));
        assertThat(text).contains("awaiting your decision");
        assertThat(text).contains(requester);
        assertThat(text).contains("just visiting");
        assertThat(text).contains("approve");
        assertThat(((Number) result.get("pendingCount")).intValue()).isEqualTo(1);
    }

    @Test void board_reports_empty_when_no_grants_issued() {
        var newOwner = "did:key:z6MkFreshBoard002";
        var executor = new ItemScriptExecutor();
        var provider = new HomeOwnerItemProvider("alpha", "alpha",
            newOwner, homeClient, testKit.system());

        var board = StudyFurnishingKit.board();
        var result = executor.execute(board.id(), board.script(), Map.of(), provider);
        assertThat(String.valueOf(result.get("text"))).contains("board is empty");
    }

    @Test void board_fails_soft_when_provider_has_no_caller() {
        // VisitorItemProvider has no callerDid — the script should render
        // a graceful message rather than blowing up.
        var executor = new ItemScriptExecutor();
        var provider = new VisitorItemProvider("alpha", "alpha");
        var board = StudyFurnishingKit.board();
        var result = executor.execute(board.id(), board.script(), Map.of(), provider);
        var text = String.valueOf(result.get("text"));
        assertThat(text).containsAnyOf("blank", "No Home");
    }

    // ─── Mailbox (grants held) ─────────────────────────────────────

    @Test void mailbox_shows_grants_issued_to_owner_by_others() {
        // Bob grants Alice read on his health-notes collection.
        var bob = "did:key:z6MkBob999";
        var resource = ResourceUri.of(bob, ResourceTypeRegistry.COLLECTION, "health-notes");
        homeStore.saveGrant(Grant.issue(bob, OWNER, resource,
            Capability.read, Map.of(), Instant.now(),
            Instant.now().plusSeconds(86400), "shared with alice"));

        var executor = new ItemScriptExecutor();
        var provider = new HomeOwnerItemProvider("alpha", "alpha",
            OWNER, homeClient, testKit.system());

        var mailbox = StudyFurnishingKit.mailbox();
        var result = executor.execute(mailbox.id(), mailbox.script(), Map.of(), provider);
        var text = String.valueOf(result.get("text"));
        assertThat(text).contains("envelopes addressed to you");
        assertThat(text).contains(bob);
        assertThat(text).contains("health-notes");
        assertThat(result.get("active")).isInstanceOf(Number.class);
        assertThat(((Number) result.get("active")).intValue()).isGreaterThanOrEqualTo(1);
    }

    @Test void mailbox_empty_when_nothing_granted_to_owner() {
        var newOwner = "did:key:z6MkFreshMail003";
        var executor = new ItemScriptExecutor();
        var provider = new HomeOwnerItemProvider("alpha", "alpha",
            newOwner, homeClient, testKit.system());

        var mailbox = StudyFurnishingKit.mailbox();
        var result = executor.execute(mailbox.id(), mailbox.script(), Map.of(), provider);
        assertThat(String.valueOf(result.get("text"))).contains("mailbox is empty");
    }

    // ─── Ledger (inference budget) ─────────────────────────────────

    @Test void ledger_reports_inference_usage() {
        var owner = "did:key:z6MkLedger001";
        // Seed AgentCostTracker with a few inferences for this owner.
        if (AgentCostTracker.get() == null) {
            AgentCostTracker.init();
        }
        var tracker = AgentCostTracker.get();
        tracker.recordInference(owner, 140L, 120, 80);
        tracker.recordInference(owner, 210L, 90, 60);

        var executor = new ItemScriptExecutor();
        var provider = new HomeOwnerItemProvider("alpha", "alpha",
            owner, homeClient, testKit.system());

        var ledger = StudyFurnishingKit.ledger();
        var result = executor.execute(ledger.id(), ledger.script(), Map.of(), provider);
        var text = String.valueOf(result.get("text"));
        assertThat(text).contains("reckoning");
        assertThat(text).contains("Inferences: 2");
        // 120+80+90+60 = 350 tokens total
        assertThat(text).contains("Tokens:");
        assertThat(text).contains("350");
        assertThat(result.get("summary")).isInstanceOf(Map.class);
    }

    @Test void ledger_shows_fresh_page_when_no_usage() {
        var owner = "did:key:z6MkLedgerFresh002";
        if (AgentCostTracker.get() == null) {
            AgentCostTracker.init();
        }
        var executor = new ItemScriptExecutor();
        var provider = new HomeOwnerItemProvider("alpha", "alpha",
            owner, homeClient, testKit.system());

        var ledger = StudyFurnishingKit.ledger();
        var result = executor.execute(ledger.id(), ledger.script(), Map.of(), provider);
        assertThat(String.valueOf(result.get("text"))).contains("ledger is fresh");
    }

    // ─── Manifest (federation agreements) ──────────────────────────

    @Test void manifest_lists_agreements_when_supplier_set() {
        var executor = new ItemScriptExecutor();
        var provider = new HomeOwnerItemProvider("alpha", "alpha",
            OWNER, homeClient, testKit.system());

        // Stub supplier returning two fake agreements.
        provider.withAgreements(() -> List.of(
            new HashMap<>(Map.of(
                "remoteZone", "beta",
                "status", "active",
                "trustLevel", "tourist",
                "localQuotaDaily", 50_000L,
                "remoteQuotaDaily", 50_000L)),
            new HashMap<>(Map.of(
                "remoteZone", "gamma",
                "status", "pending",
                "trustLevel", "resident"))
        ));

        var manifest = StudyFurnishingKit.manifest();
        var result = executor.execute(manifest.id(), manifest.script(), Map.of(), provider);
        var text = String.valueOf(result.get("text"));
        assertThat(text).contains("agreements with other zones");
        assertThat(text).contains("beta");
        assertThat(text).contains("gamma");
        assertThat(text).contains("tourist");
        assertThat(text).contains("50000");
        assertThat(text).contains("2 agreements on record");
    }

    @Test void manifest_clean_when_no_agreements() {
        var executor = new ItemScriptExecutor();
        var provider = new HomeOwnerItemProvider("alpha", "alpha",
            OWNER, homeClient, testKit.system());
        var manifest = StudyFurnishingKit.manifest();
        var result = executor.execute(manifest.id(), manifest.script(), Map.of(), provider);
        assertThat(String.valueOf(result.get("text"))).contains("manifest is clean");
    }

    // ─── Trunk (inventory snapshot) ────────────────────────────────

    @Test void trunk_lists_owned_items() {
        var executor = new ItemScriptExecutor();
        var provider = new HomeOwnerItemProvider("alpha", "alpha",
            OWNER, homeClient, testKit.system());

        provider.withInventory(() -> List.of(
            new HashMap<>(Map.of(
                "id", "key-01", "name", "brass key",
                "description", "A small brass key",
                "takeable", true, "scripted", false)),
            new HashMap<>(Map.of(
                "id", "embers", "name", "Embers",
                "description", "The centerpiece fire.",
                "takeable", false, "scripted", true))
        ));

        var trunk = StudyFurnishingKit.trunk();
        var result = executor.execute(trunk.id(), trunk.script(), Map.of(), provider);
        var text = String.valueOf(result.get("text"));
        assertThat(text).contains("Inside the trunk");
        assertThat(text).contains("brass key");
        assertThat(text).contains("Embers");
        assertThat(text).contains("scripted");
        assertThat(text).contains("fixed");
        assertThat(text).contains("2 items, 1 scripted");
    }

    @Test void trunk_empty_when_nothing_owned() {
        var executor = new ItemScriptExecutor();
        var provider = new HomeOwnerItemProvider("alpha", "alpha",
            OWNER, homeClient, testKit.system());
        var trunk = StudyFurnishingKit.trunk();
        var result = executor.execute(trunk.id(), trunk.script(), Map.of(), provider);
        assertThat(String.valueOf(result.get("text"))).contains("trunk is empty");
    }

    @Test void trunk_fails_soft_when_no_caller() {
        var executor = new ItemScriptExecutor();
        var provider = new VisitorItemProvider("alpha", "alpha");
        var trunk = StudyFurnishingKit.trunk();
        var result = executor.execute(trunk.id(), trunk.script(), Map.of(), provider);
        assertThat(String.valueOf(result.get("text"))).contains("locked");
    }

    // ─── Shelf (bonds) ────────────────────────────────────────────

    @Test void shelf_lists_bonds() {
        var executor = new ItemScriptExecutor();
        var provider = new HomeOwnerItemProvider("alpha", "alpha",
            OWNER, homeClient, testKit.system());
        provider.withBonds(() -> List.of(
            new HashMap<>(Map.of(
                "partner", "did:key:z6MkBob",
                "depth", "SACRED", "depthLevel", 2,
                "interactionCount", 12,
                "active", true, "scarred", false)),
            new HashMap<>(Map.of(
                "partner", "did:key:z6MkCarol",
                "depth", "ACQUAINTANCE", "depthLevel", 0,
                "interactionCount", 3,
                "active", false, "scarred", true))
        ));

        var shelf = StudyFurnishingKit.shelf();
        var result = executor.execute(shelf.id(), shelf.script(), Map.of(), provider);
        var text = String.valueOf(result.get("text"));
        assertThat(text).contains("your keepsakes");
        assertThat(text).contains("SACRED");
        assertThat(text).contains("did:key:z6MkBob");
        assertThat(text).contains("scarred");
        assertThat(text).contains("1 active, 1 scarred");
    }

    @Test void shelf_empty_when_no_bonds() {
        var executor = new ItemScriptExecutor();
        var provider = new HomeOwnerItemProvider("alpha", "alpha",
            OWNER, homeClient, testKit.system());
        var shelf = StudyFurnishingKit.shelf();
        var result = executor.execute(shelf.id(), shelf.script(), Map.of(), provider);
        assertThat(String.valueOf(result.get("text"))).contains("shelf is empty");
    }

    // ─── Companion Codex (companion roster) ───────────────────────

    @Test void codex_lists_companions_with_pointers() {
        var executor = new ItemScriptExecutor();
        var provider = new HomeOwnerItemProvider("alpha", "alpha",
            OWNER, homeClient, testKit.system());
        provider.withCompanions(() -> List.of(
            new HashMap<>(Map.of(
                "name", "Wyrd", "entityId", "companion-wyrd",
                "did", "did:key:z6MkWyrd",
                "temperament", "scholar~0.41",
                "voiceRevision", 3, "voiceClauses", 5,
                "relationships", 2, "online", true)),
            new HashMap<>(Map.of(
                "name", "Wisp", "entityId", "companion-wisp",
                "relationships", 0, "online", false))
        ));

        var codex = StudyFurnishingKit.codex();
        var result = executor.execute(codex.id(), codex.script(), Map.of(), provider);
        var text = String.valueOf(result.get("text"));
        assertThat(text).contains("2 companions");
        assertThat(text).contains("Wyrd");
        assertThat(text).contains("companion-wisp");
        assertThat(text).contains("scholar~0.41");
        assertThat(text).contains("rev 3, 5 clauses");
        assertThat(text).contains("not currently in the world");
        assertThat(text).contains("rename");
        assertThat(text).contains("Hearth");
    }

    @Test void codex_blank_when_no_companions() {
        var executor = new ItemScriptExecutor();
        var provider = new HomeOwnerItemProvider("alpha", "alpha",
            OWNER, homeClient, testKit.system());
        provider.withCompanions(List::of);
        var codex = StudyFurnishingKit.codex();
        var result = executor.execute(codex.id(), codex.script(), Map.of(), provider);
        assertThat(String.valueOf(result.get("text"))).contains("pages are blank");
    }

    // ─── Lantern (presence) ───────────────────────────────────────

    @Test void lantern_shows_others_when_present() {
        var executor = new ItemScriptExecutor();
        var provider = new HomeOwnerItemProvider("alpha", "alpha",
            OWNER, homeClient, testKit.system());
        provider.withPresence(() -> List.of(
            new HashMap<>(Map.of(
                "entityId", "did:key:z6MkBob", "name", "Bob", "type", "player"))
        ));

        var lantern = StudyFurnishingKit.lantern();
        var result = executor.execute(lantern.id(), lantern.script(), Map.of(), provider);
        var text = String.valueOf(result.get("text"));
        assertThat(text).contains("lantern flutters");
        assertThat(text).contains("Bob");
    }

    @Test void lantern_shows_alone_when_solo() {
        var executor = new ItemScriptExecutor();
        var provider = new HomeOwnerItemProvider("alpha", "alpha",
            OWNER, homeClient, testKit.system());
        var lantern = StudyFurnishingKit.lantern();
        var result = executor.execute(lantern.id(), lantern.script(), Map.of(), provider);
        assertThat(String.valueOf(result.get("text"))).contains("alone in your Home");
    }

    @Test void lantern_filters_self_from_presence() {
        var executor = new ItemScriptExecutor();
        var provider = new HomeOwnerItemProvider("alpha", "alpha",
            OWNER, homeClient, testKit.system());
        // Only self present → should still render "alone"
        provider.withPresence(() -> List.of(
            new HashMap<>(Map.of("entityId", OWNER, "name", "self", "type", "player"))
        ));
        var lantern = StudyFurnishingKit.lantern();
        var result = executor.execute(lantern.id(), lantern.script(), Map.of(), provider);
        assertThat(String.valueOf(result.get("text"))).contains("alone in your Home");
    }

    // ─── Mirror (self snapshot) ───────────────────────────────────

    @Test void mirror_reports_holdings() {
        var executor = new ItemScriptExecutor();
        var provider = new HomeOwnerItemProvider("alpha", "alpha",
            OWNER, homeClient, testKit.system());
        provider.withBonds(() -> List.of(
            new HashMap<>(Map.of("partner", "b", "active", true))));
        provider.withInventory(() -> List.of(
            new HashMap<>(Map.of("id", "x", "name", "X", "takeable", true, "scripted", false))));

        var mirror = StudyFurnishingKit.mirror();
        var result = executor.execute(mirror.id(), mirror.script(), Map.of(), provider);
        var text = String.valueOf(result.get("text"));
        assertThat(text).contains("your reflection");
        assertThat(text).contains(OWNER);
        assertThat(result.get("identity")).isEqualTo(OWNER);
        assertThat(((Number) result.get("bonds")).intValue()).isEqualTo(1);
        assertThat(((Number) result.get("inventory")).intValue()).isEqualTo(1);
    }

    // ─── Compass (notifications) ──────────────────────────────────

    @Test void compass_shows_channels() {
        var executor = new ItemScriptExecutor();
        var provider = new HomeOwnerItemProvider("alpha", "alpha",
            OWNER, homeClient, testKit.system());
        provider.withNotifications(() -> List.of(
            new HashMap<>(Map.of("channel", "phone", "enabled", true, "destination", "SMS")),
            new HashMap<>(Map.of("channel", "desktop", "enabled", false))
        ));

        var compass = StudyFurnishingKit.compass();
        var result = executor.execute(compass.id(), compass.script(), Map.of(), provider);
        var text = String.valueOf(result.get("text"));
        assertThat(text).contains("compass points to");
        assertThat(text).contains("phone");
        assertThat(text).contains("SMS");
        assertThat(text).contains("1 channel(s) enabled");
    }

    @Test void compass_empty_when_no_channels() {
        var executor = new ItemScriptExecutor();
        var provider = new HomeOwnerItemProvider("alpha", "alpha",
            OWNER, homeClient, testKit.system());
        var compass = StudyFurnishingKit.compass();
        var result = executor.execute(compass.id(), compass.script(), Map.of(), provider);
        assertThat(String.valueOf(result.get("text"))).contains("No notification channels");
    }

    // ─── Window (bonded whereabouts) ──────────────────────────────

    @Test void window_shows_bonded_with_location() {
        var executor = new ItemScriptExecutor();
        var provider = new HomeOwnerItemProvider("alpha", "alpha",
            OWNER, homeClient, testKit.system());
        provider.withBonds(() -> List.of(
            new HashMap<>(Map.of(
                "partner", "did:key:z6MkBob",
                "active", true,
                "currentZone", "beta",
                "currentRoom", "study",
                "awake", true))
        ));

        var window = StudyFurnishingKit.window();
        var result = executor.execute(window.id(), window.script(), Map.of(), provider);
        var text = String.valueOf(result.get("text"));
        assertThat(text).contains("bonded parties");
        assertThat(text).contains("did:key:z6MkBob");
        assertThat(text).contains("beta/study");
    }

    @Test void window_hides_when_no_location() {
        var executor = new ItemScriptExecutor();
        var provider = new HomeOwnerItemProvider("alpha", "alpha",
            OWNER, homeClient, testKit.system());
        provider.withBonds(() -> List.of(
            new HashMap<>(Map.of("partner", "b", "active", true))
        ));
        var window = StudyFurnishingKit.window();
        var result = executor.execute(window.id(), window.script(), Map.of(), provider);
        var text = String.valueOf(result.get("text"));
        // A bond without currentZone should still show a line but as "unseen".
        // The script only hides when zero active-with-data bonds remain — we have one.
        assertThat(text).contains("bonded parties");
        assertThat(text).contains("unseen");
    }

    @Test void window_empty_when_no_bonds() {
        var executor = new ItemScriptExecutor();
        var provider = new HomeOwnerItemProvider("alpha", "alpha",
            OWNER, homeClient, testKit.system());
        var window = StudyFurnishingKit.window();
        var result = executor.execute(window.id(), window.script(), Map.of(), provider);
        assertThat(String.valueOf(result.get("text"))).contains("mist");
    }

    // ─── Threshold (pairing knocks) ───────────────────────────────

    @Test void threshold_quiet_when_no_pending_no_code_no_key() {
        var executor = new ItemScriptExecutor();
        var provider = new HomeOwnerItemProvider("alpha", "alpha",
            OWNER, homeClient, testKit.system())
            .withPendingPairings(List::of)
            .withActivePairCode(() -> null)
            .withActiveHouseholdKey(() -> null);
        var threshold = StudyFurnishingKit.threshold();
        var result = executor.execute(threshold.id(), threshold.script(), Map.of(), provider);
        assertThat(String.valueOf(result.get("text"))).contains("rests quietly");
    }

    @Test void threshold_lists_pending_pairings_with_code() {
        var executor = new ItemScriptExecutor();
        var provider = new HomeOwnerItemProvider("alpha", "alpha",
            OWNER, homeClient, testKit.system())
            .withPendingPairings(() -> List.of(
                new HashMap<>(Map.of(
                    "challengeId", "ch-001",
                    "code", "482931",
                    "deviceName", "test-node",
                    "deviceType", "node",
                    "expiresAt", "2026-04-25T10:30:00Z"))
            ))
            .withActivePairCode(() -> "482931")
            .withActiveHouseholdKey(() -> null);
        var threshold = StudyFurnishingKit.threshold();
        var result = executor.execute(threshold.id(), threshold.script(), Map.of(), provider);
        var text = String.valueOf(result.get("text"));
        assertThat(text).contains("Waiting at the threshold");
        assertThat(text).contains("test-node");
        assertThat(text).contains("[node]");
        assertThat(text).contains("482931");
        assertThat(text).contains("ch-001");
        assertThat(((Number) result.get("pendingCount")).intValue()).isEqualTo(1);
    }

    @Test void threshold_shows_household_key_when_present() {
        var executor = new ItemScriptExecutor();
        var provider = new HomeOwnerItemProvider("alpha", "alpha",
            OWNER, homeClient, testKit.system())
            .withPendingPairings(List::of)
            .withActivePairCode(() -> null)
            .withActiveHouseholdKey(() -> "wyrd_hk_deadbeef00112233");
        var threshold = StudyFurnishingKit.threshold();
        var result = executor.execute(threshold.id(), threshold.script(), Map.of(), provider);
        var text = String.valueOf(result.get("text"));
        assertThat(text).contains("household key");
        assertThat(text).contains("wyrd_hk_deadbeef00112233");
        assertThat(text).contains("wyrdsekai join --key");
    }

    @Test void threshold_rotates_household_key_on_request() {
        var minted = new String[]{null};
        var executor = new ItemScriptExecutor();
        var provider = new HomeOwnerItemProvider("alpha", "alpha",
            OWNER, homeClient, testKit.system())
            .withGenerateHouseholdKey(() -> {
                minted[0] = "wyrd_hk_freshlymintedkey";
                return minted[0];
            });
        var threshold = StudyFurnishingKit.threshold();
        var result = executor.execute(threshold.id(), threshold.script(),
            Map.of("key", "rotate"), provider);
        var text = String.valueOf(result.get("text"));
        assertThat(text).contains("new household key");
        assertThat(text).contains("wyrd_hk_freshlymintedkey");
        assertThat(result.get("rotated")).isEqualTo(true);
        assertThat(minted[0]).isEqualTo("wyrd_hk_freshlymintedkey");
    }
}
