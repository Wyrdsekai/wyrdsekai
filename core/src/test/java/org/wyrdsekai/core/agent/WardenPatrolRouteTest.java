package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WardenPatrolRouteTest {

    @Test void default_route_covers_foundation() {
        var route = WardenPatrolRoute.defaultRoute();
        assertThat(route.size()).isEqualTo(8);
        assertThat(route.stops().stream().map(WardenPatrolRoute.PatrolStop::roomId))
            .contains("nexus", "ward-room", "vault", "docks", "bridge",
                "counting-house", "library", "boiler-room");
    }

    @Test void advance_cycles_through_stops() {
        var route = new WardenPatrolRoute(List.of(
            new WardenPatrolRoute.PatrolStop("a", "Room A", WardenPatrolRoute.PatrolAction.OBSERVE, 10),
            new WardenPatrolRoute.PatrolStop("b", "Room B", WardenPatrolRoute.PatrolAction.SCAN, 20),
            new WardenPatrolRoute.PatrolStop("c", "Room C", WardenPatrolRoute.PatrolAction.INVESTIGATE, 30)
        ));

        assertThat(route.advance().roomId()).isEqualTo("a");
        assertThat(route.advance().roomId()).isEqualTo("b");
        assertThat(route.advance().roomId()).isEqualTo("c");
        assertThat(route.advance().roomId()).isEqualTo("a"); // wraps around
    }

    @Test void current_without_advance() {
        var route = new WardenPatrolRoute(List.of(
            new WardenPatrolRoute.PatrolStop("a", "Room A", WardenPatrolRoute.PatrolAction.OBSERVE, 10)
        ));

        assertThat(route.current().roomId()).isEqualTo("a");
        assertThat(route.current().roomId()).isEqualTo("a"); // still same
    }

    @Test void reset_returns_to_start() {
        var route = new WardenPatrolRoute(List.of(
            new WardenPatrolRoute.PatrolStop("a", "A", WardenPatrolRoute.PatrolAction.OBSERVE, 10),
            new WardenPatrolRoute.PatrolStop("b", "B", WardenPatrolRoute.PatrolAction.SCAN, 20)
        ));

        route.advance();
        route.advance();
        assertThat(route.currentIndex()).isEqualTo(0);
        route.advance();
        assertThat(route.currentIndex()).isEqualTo(1);
        route.reset();
        assertThat(route.currentIndex()).isEqualTo(0);
    }

    @Test void empty_route() {
        var route = new WardenPatrolRoute(List.of());
        assertThat(route.size()).isEqualTo(0);
        assertThat(route.current()).isNull();
        assertThat(route.advance()).isNull();
    }

    @Test void describe_marks_current() {
        var route = new WardenPatrolRoute(List.of(
            new WardenPatrolRoute.PatrolStop("a", "Room A", WardenPatrolRoute.PatrolAction.OBSERVE, 10),
            new WardenPatrolRoute.PatrolStop("b", "Room B", WardenPatrolRoute.PatrolAction.SCAN, 20)
        ));

        var desc = route.describe();
        assertThat(desc).contains("→ Room A"); // current position marked
        assertThat(desc).contains("Room B");
        assertThat(desc).contains("2 stops");
    }

    @Test void investigation_result() {
        var result = new WardenPatrolRoute.InvestigationResult(
            "nexus", true, "Injection pattern detected",
            WardenPatrolRoute.PatrolAction.QUARANTINE);

        assertThat(result.threatDetected()).isTrue();
        assertThat(result.summary()).contains("Injection");
        assertThat(result.recommendedAction()).isEqualTo(WardenPatrolRoute.PatrolAction.QUARANTINE);
    }
}
