package org.wyrdsekai.core.context;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.agent.CalendarContext;
import org.wyrdsekai.core.agent.ContextAccessManager;
import org.wyrdsekai.core.agent.LocationContext;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link PersonalContextAggregator} -- singleton context service.
 */
class PersonalContextAggregatorTest {

    private PersonalContextAggregator aggregator;
    private static final String PLAYER_A = "did:key:playerA";
    private static final String PLAYER_B = "did:key:playerB";
    private static final String AGENT = "agent-ma";
    private static final String STEWARD = "did:key:steward1";

    private ContextAccessManager accessMgr;

    @BeforeEach
    void setUp() {
        PersonalContextAggregator.init();
        aggregator = PersonalContextAggregator.get();
        accessMgr = new ContextAccessManager();
    }

    @AfterEach
    void tearDown() {
        PersonalContextAggregator.reset();
    }

    @Test
    void get_or_create_profile() {
        var profile = aggregator.getProfile(PLAYER_A);
        assertThat(profile).isNotNull();
        assertThat(profile.playerDid()).isEqualTo(PLAYER_A);

        // Same DID returns same instance
        var same = aggregator.getProfile(PLAYER_A);
        assertThat(same).isSameAs(profile);

        assertThat(aggregator.profileCount()).isEqualTo(1);
    }

    @Test
    void update_propagates_to_profile() {
        aggregator.updateLocation(PLAYER_A, LocationContext.LocationState.WORK, "Office");
        aggregator.updateDesktop(PLAYER_A, "IntelliJ", "coding");
        aggregator.updateTopics(PLAYER_A, List.of("API migration"));

        var profile = aggregator.getProfile(PLAYER_A);
        assertThat(profile.locationState()).isEqualTo(LocationContext.LocationState.WORK);
        assertThat(profile.locationName()).isEqualTo("Office");
        assertThat(profile.currentApp()).isEqualTo("IntelliJ");
        assertThat(profile.appCategory()).isEqualTo("coding");
        assertThat(profile.getRecentTopics()).containsExactly("API migration");
    }

    @Test
    void context_for_agent_respects_permissions() {
        accessMgr.grant(AGENT, "location", "", STEWARD);
        // Do NOT grant calendar

        aggregator.updateLocation(PLAYER_A, LocationContext.LocationState.HOME, "Home");
        var now = Instant.now();
        aggregator.updateCalendar(PLAYER_A, List.of(
            new CalendarContext.CalendarEvent("Secret", now.plusSeconds(600),
                now.plusSeconds(1800), null, false)
        ));

        String ctx = aggregator.buildContextForAgent(PLAYER_A, AGENT, accessMgr);

        assertThat(ctx).isNotNull();
        assertThat(ctx).contains("Home");
        assertThat(ctx).doesNotContain("Secret");
    }

    @Test
    void multiple_players_independent() {
        aggregator.updateLocation(PLAYER_A, LocationContext.LocationState.WORK, "Office");
        aggregator.updateLocation(PLAYER_B, LocationContext.LocationState.HOME, "Home");

        assertThat(aggregator.profileCount()).isEqualTo(2);
        assertThat(aggregator.getProfile(PLAYER_A).locationName()).isEqualTo("Office");
        assertThat(aggregator.getProfile(PLAYER_B).locationName()).isEqualTo("Home");
    }

    @Test
    void mark_active_updates_timestamp() throws InterruptedException {
        aggregator.getProfile(PLAYER_A); // Create profile
        Thread.sleep(50);
        aggregator.markActive(PLAYER_A);

        var profile = aggregator.getProfile(PLAYER_A);
        assertThat(profile.idleDuration().toMillis()).isLessThan(100);
    }

    @Test
    void build_context_for_nonexistent_player_returns_null() {
        String ctx = aggregator.buildContextForAgent("did:key:nobody", AGENT, accessMgr);
        assertThat(ctx).isNull();
    }
}
