package org.wyrdsekai.core.item;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.household.ParentalControlService;
import org.wyrdsekai.core.persistence.AuthService;
import org.wyrdsekai.core.persistence.SqlDialect;
import org.wyrdsekai.core.test.TestDb;
import org.wyrdsekai.scripting.sandbox.ItemScriptExecutor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code world.parental} surfaces for the parental-controls scroll —
 * mirrors {@link HouseholdControlPanelApiTest}: (a) safe-empty provider
 * defaults, (b) {@link HomeOwnerItemProvider} wired against the real
 * service with the ACTING player's id routed as caller (steward-only
 * writes verified both ways, member reads scoped to self), and (c) an
 * end-to-end GraalJS script through {@link ItemScriptExecutor}.
 */
@Tag("integration")
class ParentalControlPanelApiTest {

    private AuthService auth;
    private ParentalControlService parental;
    private String stewardId;
    private String memberId;

    @BeforeEach
    void setUp() {
        var jdbcUrl = TestDb.createInMemory();
        auth = new AuthService(jdbcUrl);
        // First registered user auto-becomes steward; second is a member.
        stewardId = auth.register("operator", "password123", "Masumi").orElseThrow().userId();
        memberId = auth.register("kaz", "password123", "Kaz").orElseThrow().userId();
        parental = new ParentalControlService(jdbcUrl, new SqlDialect.SQLite(), auth);
        parental.initSchema();
    }

    private HomeOwnerItemProvider providerFor(String playerId) {
        return new HomeOwnerItemProvider("zone", "zone", playerId, null, null)
            .withAuth(auth)
            .withParental(parental);
    }

    // ─── (a) defaults are safe empties — never throw into the script ────

    @Test
    void visitor_defaults_are_safe_empties() {
        var p = new VisitorItemProvider("zone", "zone");
        assertTrue(p.parentalList().isEmpty());
        assertEquals(false, p.parentalGet("kaz").get("ok"));
        assertEquals(false, p.parentalSet("kaz", "minutes", 60).get("ok"));
        assertEquals(false, p.parentalClear("kaz").get("ok"));
        assertNotNull(p.parentalSet("kaz", "minutes", 60).get("error"));
    }

    @Test
    void unwired_home_owner_provider_degrades_gracefully() {
        var p = new HomeOwnerItemProvider("zone", "zone", stewardId, null, null);
        assertTrue(p.parentalList().isEmpty());
        assertEquals(false, p.parentalGet("kaz").get("ok"));
        assertEquals(false, p.parentalSet("kaz", "minutes", 60).get("ok"));
        assertEquals(false, p.parentalClear("kaz").get("ok"));
    }

    // ─── (b) wired provider — steward writes, member denials, shapes ────

    @Test
    void steward_sets_each_field_and_list_shape_is_complete() {
        var steward = providerFor(stewardId);

        assertEquals(true, steward.parentalSet("kaz", "minutes", 120).get("ok"));
        assertEquals(true, steward.parentalSet("kaz", "inference", "50").get("ok"));
        assertEquals(true, steward.parentalSet("kaz", "filter", "strict").get("ok"));
        assertEquals(true, steward.parentalSet("kaz", "block-room", "gpu-chamber").get("ok"));
        assertEquals(true, steward.parentalSet("kaz", "block-room", "study-*").get("ok"));

        // Service-level truth.
        var controls = parental.controlsFor(memberId).orElseThrow();
        assertEquals(120, (int) controls.dailyMinutes());
        assertEquals(50, (int) controls.dailyInference());
        assertEquals("strict", controls.contentFilter());
        assertEquals(List.of("gpu-chamber", "study-*"), controls.blockedRooms());

        // List shape includes usage counters.
        parental.recordMinutes(memberId, 7);
        parental.recordInference(memberId);
        var listed = steward.parentalList();
        assertEquals(1, listed.size());
        var row = listed.get(0);
        assertEquals("kaz", row.get("username"));
        assertEquals("Kaz", row.get("displayName"));
        assertEquals(120, row.get("dailyMinutes"));
        assertEquals(50, row.get("dailyInference"));
        assertEquals("strict", row.get("contentFilter"));
        assertEquals(List.of("gpu-chamber", "study-*"), row.get("blockedRooms"));
        assertEquals(7, row.get("minutesUsedToday"));
        assertEquals(1, row.get("inferencesUsedToday"));
    }

    @Test
    void limits_accept_off_for_unlimited_and_reject_junk() {
        var steward = providerFor(stewardId);
        assertEquals(true, steward.parentalSet("kaz", "minutes", 60).get("ok"));
        assertEquals(true, steward.parentalSet("kaz", "minutes", "off").get("ok"));
        assertNull(parental.controlsFor(memberId).orElseThrow().dailyMinutes());

        assertEquals(false, steward.parentalSet("kaz", "minutes", "lots").get("ok"));
        assertEquals(false, steward.parentalSet("kaz", "minutes", -5).get("ok"));
        assertEquals(false, steward.parentalSet("kaz", "filter", "medium").get("ok"));
        assertEquals(false, steward.parentalSet("kaz", "volume", 11).get("ok"));
        assertEquals(false, steward.parentalSet("ghost", "minutes", 60).get("ok"));
    }

    @Test
    void unblock_room_removes_and_reports_missing() {
        var steward = providerFor(stewardId);
        steward.parentalSet("kaz", "block-room", "vault");
        assertEquals(true, steward.parentalSet("kaz", "unblock-room", "vault").get("ok"));
        assertTrue(parental.controlsFor(memberId).orElseThrow().blockedRooms().isEmpty());

        var missing = steward.parentalSet("kaz", "unblock-room", "vault");
        assertEquals(false, missing.get("ok"));
        assertEquals("no such room block: vault", missing.get("error"));
    }

    @Test
    void member_writes_are_denied() {
        var member = providerFor(memberId);
        var denied = member.parentalSet("operator", "minutes", 5);
        assertEquals(false, denied.get("ok"));
        assertEquals("steward only", denied.get("error"));
        assertTrue(parental.controlsFor(stewardId).isEmpty());

        providerFor(stewardId).parentalSet("kaz", "minutes", 60);
        var deniedClear = member.parentalClear("kaz");
        assertEquals(false, deniedClear.get("ok"));
        assertEquals("steward only", deniedClear.get("error"));
        assertTrue(parental.controlsFor(memberId).isPresent());
    }

    @Test
    void member_reads_are_scoped_to_self() {
        var steward = providerFor(stewardId);
        steward.parentalSet("kaz", "minutes", 60);
        steward.parentalSet("operator", "minutes", 90);

        // Steward sees both; the member only their own row.
        assertEquals(2, steward.parentalList().size());
        var memberView = providerFor(memberId).parentalList();
        assertEquals(1, memberView.size());
        assertEquals("kaz", memberView.get(0).get("username"));

        // get(): self ok, other refused.
        assertEquals(true, providerFor(memberId).parentalGet("kaz").get("ok"));
        var other = providerFor(memberId).parentalGet("operator");
        assertEquals(false, other.get("ok"));
        assertEquals("steward only", other.get("error"));
    }

    @Test
    void get_reports_uncontrolled_member_honestly() {
        var res = providerFor(stewardId).parentalGet("kaz");
        assertEquals(true, res.get("ok"));
        assertEquals(false, res.get("controls"));
    }

    @Test
    void clear_round_trip() {
        var steward = providerFor(stewardId);
        steward.parentalSet("kaz", "minutes", 60);
        assertEquals(true, steward.parentalClear("kaz").get("ok"));
        assertTrue(parental.controlsFor(memberId).isEmpty());

        var again = steward.parentalClear("kaz");
        assertEquals(false, again.get("ok"));
        assertEquals("no controls set for kaz", again.get("error"));
    }

    // ─── (c) end-to-end: GraalJS script → world.parental through executor ──

    private ItemScriptExecutor executor;

    @AfterEach
    void tearDownExecutor() throws Exception {
        if (executor != null) executor.close();
    }

    @Test
    void script_set_and_list_end_to_end_with_wired_service() {
        executor = new ItemScriptExecutor();
        var res = executor.execute("parental_panel", """
            function invoke(p) {
              var set = world.parental.set(p.target, "minutes", p.value);
              if (!set.ok) return set;
              var rows = world.parental.list();
              return {ok: true, count: rows.length, minutes: rows[0].dailyMinutes};
            }
            """,
            Map.of("target", "kaz", "value", 45),
            providerFor(stewardId));
        assertEquals(true, res.get("ok"));
        assertEquals(1, ((Number) res.get("count")).intValue());
        assertEquals(45, ((Number) res.get("minutes")).intValue());
        assertEquals(45, (int) parental.controlsFor(memberId).orElseThrow().dailyMinutes());
    }

    @Test
    void script_write_denied_for_member_end_to_end() {
        executor = new ItemScriptExecutor();
        var res = executor.execute("parental_panel", """
            function invoke(p) {
              return world.parental.set(p.target, "minutes", p.value);
            }
            """,
            Map.of("target", "operator", "value", 5),
            providerFor(memberId));
        assertEquals(false, res.get("ok"));
        assertEquals("steward only", res.get("error"));
    }
}
