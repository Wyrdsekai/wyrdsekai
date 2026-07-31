package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link LocationContext} -- phone GPS location awareness for agents.
 */
class LocationContextTest {

    private LocationContext ctx;

    @BeforeEach
    void setUp() {
        LocationContext.init();
        ctx = LocationContext.get();
    }

    @AfterEach
    void tearDown() {
        LocationContext.reset();
    }

    @Test
    void update_and_retrieve_location() {
        ctx.update(37.7749, -122.4194, "home");

        assertThat(ctx.latitude()).isEqualTo(37.7749);
        assertThat(ctx.longitude()).isEqualTo(-122.4194);
        assertThat(ctx.locationName()).isEqualTo("home");
        assertThat(ctx.lastUpdated()).isNotNull();
    }

    @Test
    void infer_home_from_keyword() {
        assertThat(ctx.inferState("home")).isEqualTo(LocationContext.LocationState.HOME);
        assertThat(ctx.inferState("My House")).isEqualTo(LocationContext.LocationState.HOME);
        assertThat(ctx.inferState("apartment")).isEqualTo(LocationContext.LocationState.HOME);
    }

    @Test
    void infer_work_from_keyword() {
        assertThat(ctx.inferState("office")).isEqualTo(LocationContext.LocationState.WORK);
        assertThat(ctx.inferState("The Workplace")).isEqualTo(LocationContext.LocationState.WORK);
        assertThat(ctx.inferState("coworking space")).isEqualTo(LocationContext.LocationState.WORK);
        assertThat(ctx.inferState("studio")).isEqualTo(LocationContext.LocationState.WORK);
    }

    @Test
    void infer_commuting_from_keyword() {
        assertThat(ctx.inferState("commuting")).isEqualTo(LocationContext.LocationState.COMMUTING);
        assertThat(ctx.inferState("On the train")).isEqualTo(LocationContext.LocationState.COMMUTING);
        assertThat(ctx.inferState("bus stop")).isEqualTo(LocationContext.LocationState.COMMUTING);
        assertThat(ctx.inferState("driving")).isEqualTo(LocationContext.LocationState.COMMUTING);
    }

    @Test
    void unknown_when_no_update() {
        assertThat(ctx.currentState()).isEqualTo(LocationContext.LocationState.UNKNOWN);
        assertThat(ctx.locationName()).isNull();
        assertThat(ctx.lastUpdated()).isNull();
    }

    @Test
    void buildContext_returns_null_when_unknown() {
        assertThat(ctx.buildContext()).isNull();
    }

    @Test
    void buildContext_includes_location_name_and_state() {
        ctx.update(35.6762, 139.6503, "office");

        String context = ctx.buildContext();
        assertThat(context).isNotNull();
        assertThat(context).contains("## Human Location");
        assertThat(context).contains("office");
        assertThat(context).contains("WORK");
    }

    @Test
    void timeSinceUpdate_tracks_correctly() {
        // Before any update, timeSinceUpdate should be very large
        assertThat(ctx.timeSinceUpdate()).isGreaterThan(Duration.ofDays(100));

        ctx.update(0.0, 0.0, "home");

        // Right after update, timeSinceUpdate should be very small
        assertThat(ctx.timeSinceUpdate()).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void location_state_change_detection() {
        // Initially UNKNOWN -> UNKNOWN: no change
        assertThat(ctx.hasStateChanged()).isFalse();

        // First update: UNKNOWN -> HOME
        ctx.update(37.0, -122.0, "home");
        assertThat(ctx.hasStateChanged()).isTrue();
        assertThat(ctx.previousState()).isEqualTo(LocationContext.LocationState.UNKNOWN);
        assertThat(ctx.currentState()).isEqualTo(LocationContext.LocationState.HOME);

        // Second update: HOME -> WORK
        ctx.update(37.1, -122.1, "office");
        assertThat(ctx.hasStateChanged()).isTrue();
        assertThat(ctx.previousState()).isEqualTo(LocationContext.LocationState.HOME);
        assertThat(ctx.currentState()).isEqualTo(LocationContext.LocationState.WORK);

        // Third update: WORK -> WORK (same state)
        ctx.update(37.1, -122.1, "coworking space");
        assertThat(ctx.hasStateChanged()).isFalse();
    }

    @Test
    void update_from_location_event() {
        var event = new AgentEvent.LocationUpdate(
            35.6762, 139.6503, "gym",
            LocationContext.LocationState.AWAY, Instant.now());

        ctx.update(event);

        assertThat(ctx.currentState()).isEqualTo(LocationContext.LocationState.AWAY);
        assertThat(ctx.locationName()).isEqualTo("gym");
        assertThat(ctx.latitude()).isEqualTo(35.6762);
    }

    @Test
    void infer_away_for_unknown_named_location() {
        assertThat(ctx.inferState("Trader Joe's")).isEqualTo(LocationContext.LocationState.AWAY);
        assertThat(ctx.inferState("gym")).isEqualTo(LocationContext.LocationState.AWAY);
        assertThat(ctx.inferState("restaurant")).isEqualTo(LocationContext.LocationState.AWAY);
    }

    @Test
    void infer_unknown_for_null_or_blank() {
        assertThat(ctx.inferState(null)).isEqualTo(LocationContext.LocationState.UNKNOWN);
        assertThat(ctx.inferState("")).isEqualTo(LocationContext.LocationState.UNKNOWN);
        assertThat(ctx.inferState("   ")).isEqualTo(LocationContext.LocationState.UNKNOWN);
    }
}
