package org.wyrdsekai.core.household;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.persistence.AuthService;
import org.wyrdsekai.core.persistence.SqlDialect;
import org.wyrdsekai.core.test.TestDb;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ParentalControlService — the substrate behind the parental-controls
 * scroll: steward-gated CRUD, glob room blocks, per-day counters with
 * unlimited-null semantics, and the 60s accrual tick.
 */
@Tag("integration")
class ParentalControlServiceTest {

    private ParentalControlService service;
    private AuthService auth;
    private String stewardId;
    private String memberId;

    @BeforeEach
    void setUp() {
        var jdbcUrl = TestDb.createInMemory();
        auth = new AuthService(jdbcUrl);
        // First registered user auto-becomes steward; second is a member.
        stewardId = auth.register("operator", "password123", "Operator").orElseThrow().userId();
        memberId = auth.register("kaz", "password123", "Kaz").orElseThrow().userId();
        service = new ParentalControlService(jdbcUrl, new SqlDialect.SQLite(), auth);
        service.initSchema();
    }

    // ─── CRUD + steward gate ────────────────────────────────────────────

    @Test
    void steward_set_get_clear_round_trip() {
        assertThat(service.controlsFor(memberId)).isEmpty();

        var ok = service.setControls(stewardId, memberId,
            120, List.of("gpu-chamber", "study-*"), 50, "strict");
        assertThat(ok).isTrue();

        var controls = service.controlsFor(memberId).orElseThrow();
        assertThat(controls.dailyMinutes()).isEqualTo(120);
        assertThat(controls.dailyInference()).isEqualTo(50);
        assertThat(controls.contentFilter()).isEqualTo("strict");
        assertThat(controls.blockedRooms()).containsExactly("gpu-chamber", "study-*");
        assertThat(controls.setBy()).isEqualTo(stewardId);

        assertThat(service.listControls()).hasSize(1);

        assertThat(service.clearControls(stewardId, memberId)).isTrue();
        assertThat(service.controlsFor(memberId)).isEmpty();
        assertThat(service.listControls()).isEmpty();
    }

    @Test
    void set_replaces_existing_controls() {
        service.setControls(stewardId, memberId, 60, List.of("vault"), null, "off");
        service.setControls(stewardId, memberId, 30, List.of(), 5, "strict");

        var controls = service.controlsFor(memberId).orElseThrow();
        assertThat(controls.dailyMinutes()).isEqualTo(30);
        assertThat(controls.dailyInference()).isEqualTo(5);
        assertThat(controls.contentFilter()).isEqualTo("strict");
        assertThat(controls.blockedRooms()).isEmpty();
    }

    @Test
    void non_steward_writes_are_refused() {
        assertThat(service.setControls(memberId, stewardId, 1, List.of(), 1, "strict")).isFalse();
        assertThat(service.controlsFor(stewardId)).isEmpty();

        service.setControls(stewardId, memberId, 10, List.of(), null, "off");
        assertThat(service.clearControls(memberId, memberId)).isFalse();
        assertThat(service.controlsFor(memberId)).isPresent();

        assertThat(service.setControls(null, memberId, 1, List.of(), null, "off")).isFalse();
        assertThat(service.setControls("no-such-user", memberId, 1, List.of(), null, "off")).isFalse();
    }

    // ─── Room glob matching ─────────────────────────────────────────────

    @Test
    void room_blocks_match_exact_and_wildcard_globs() {
        service.setControls(stewardId, memberId,
            null, List.of("gpu-chamber", "study-*", "lab-?"), null, "off");

        // Exact
        assertThat(service.canEnterRoom(memberId, "gpu-chamber")).isFalse();
        assertThat(service.canEnterRoom(memberId, "gpu-chamber-2")).isTrue();
        // * wildcard
        assertThat(service.canEnterRoom(memberId, "study-operator")).isFalse();
        assertThat(service.canEnterRoom(memberId, "study-")).isFalse();
        assertThat(service.canEnterRoom(memberId, "studio")).isTrue();
        // ? wildcard — exactly one character
        assertThat(service.canEnterRoom(memberId, "lab-7")).isFalse();
        assertThat(service.canEnterRoom(memberId, "lab-77")).isTrue();
        // Unblocked room
        assertThat(service.canEnterRoom(memberId, "nexus")).isTrue();
    }

    @Test
    void uncontrolled_users_and_missing_service_data_are_allowed() {
        assertThat(service.canEnterRoom(memberId, "anywhere")).isTrue();
        assertThat(service.canEnterRoom("ghost-user", "anywhere")).isTrue();
        assertThat(service.canEnterRoom(null, "anywhere")).isTrue();
        assertThat(service.canEnterRoom(memberId, null)).isTrue();
    }

    // ─── Unlimited-null semantics ───────────────────────────────────────

    @Test
    void null_limits_mean_unlimited() {
        // No controls at all → unlimited.
        assertThat(service.minutesRemaining(memberId)).isNull();
        assertThat(service.inferencesRemaining(memberId)).isNull();

        // Controls with null numeric limits → still unlimited.
        service.setControls(stewardId, memberId, null, List.of("vault"), null, "strict");
        assertThat(service.minutesRemaining(memberId)).isNull();
        assertThat(service.inferencesRemaining(memberId)).isNull();
    }

    // ─── Counters: decrement + never negative ───────────────────────────

    @Test
    void minutes_and_inference_counters_decrement_remaining() {
        service.setControls(stewardId, memberId, 10, List.of(), 2, "off");

        assertThat(service.minutesRemaining(memberId)).isEqualTo(10);
        service.recordMinutes(memberId, 3);
        assertThat(service.minutesRemaining(memberId)).isEqualTo(7);
        service.recordMinutes(memberId, 20);
        assertThat(service.minutesRemaining(memberId)).isZero(); // clamped, never negative

        assertThat(service.inferencesRemaining(memberId)).isEqualTo(2);
        service.recordInference(memberId);
        assertThat(service.inferencesRemaining(memberId)).isEqualTo(1);
        service.recordInference(memberId);
        assertThat(service.inferencesRemaining(memberId)).isZero();
        service.recordInference(memberId);
        assertThat(service.inferencesRemaining(memberId)).isZero();

        var usage = service.usageToday(memberId);
        assertThat(usage.minutesUsed()).isEqualTo(23);
        assertThat(usage.inferencesUsed()).isEqualTo(3);
    }

    @Test
    void daily_counters_roll_over_by_day_string() {
        service.setControls(stewardId, memberId, 10, List.of(), 5, "off");

        // Yesterday's spend does not touch today's remaining.
        service.recordMinutes(memberId, 10, "2026-07-02");
        service.recordInference(memberId, "2026-07-02");
        assertThat(service.minutesRemaining(memberId)).isEqualTo(10);
        assertThat(service.inferencesRemaining(memberId)).isEqualTo(5);

        // The old day's counters are still there, per-day keyed.
        var yesterday = service.usageFor(memberId, "2026-07-02");
        assertThat(yesterday.minutesUsed()).isEqualTo(10);
        assertThat(yesterday.inferencesUsed()).isEqualTo(1);
        assertThat(service.usageToday(memberId).minutesUsed()).isZero();
    }

    // ─── Content filter ─────────────────────────────────────────────────

    @Test
    void content_filter_defaults_off_and_normalizes() {
        assertThat(service.contentFilter(memberId)).isEqualTo(ParentalControlService.FILTER_OFF);

        service.setControls(stewardId, memberId, null, List.of(), null, "STRICT");
        assertThat(service.contentFilter(memberId)).isEqualTo(ParentalControlService.FILTER_STRICT);

        service.setControls(stewardId, memberId, null, List.of(), null, "whatever");
        assertThat(service.contentFilter(memberId)).isEqualTo(ParentalControlService.FILTER_OFF);
    }

    // ─── Accrual tick ───────────────────────────────────────────────────

    @Test
    void tick_charges_live_controlled_members_and_flags_over_limit() {
        service.setControls(stewardId, memberId, 2, List.of(), null, "off");
        var overLimit = new ArrayList<String>();

        // Tick 1: one minute spent, still under limit.
        service.tick(() -> Set.of(memberId, stewardId, "anon-abc123"), overLimit::add);
        assertThat(service.minutesRemaining(memberId)).isEqualTo(1);
        assertThat(overLimit).isEmpty();

        // Tick 2: limit reached → member flagged.
        service.tick(() -> Set.of(memberId), overLimit::add);
        assertThat(service.minutesRemaining(memberId)).isZero();
        assertThat(overLimit).containsExactly(memberId);

        // Uncontrolled users never accrue.
        assertThat(service.usageToday(stewardId).minutesUsed()).isZero();
        assertThat(service.usageToday("anon-abc123").minutesUsed()).isZero();
    }

    // ─── Glob helper ────────────────────────────────────────────────────

    @Test
    void glob_matcher_treats_regex_metacharacters_literally() {
        assertThat(ParentalControlService.globMatches("room.one", "room.one")).isTrue();
        assertThat(ParentalControlService.globMatches("room.one", "roomXone")).isFalse();
        assertThat(ParentalControlService.globMatches("a+b", "a+b")).isTrue();
        assertThat(ParentalControlService.globMatches("*", "anything-at-all")).isTrue();
    }
}
