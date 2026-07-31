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
 * Integration tests for the Unified Personal Context pipeline.
 * Tests the full flow: data sources -> aggregator -> profile -> filtered context.
 */
class UnifiedContextIntegrationTest {

    private PersonalContextAggregator aggregator;
    private ContextAccessManager accessMgr;
    private static final String PLAYER = "did:key:operator";
    private static final String AGENT_MA = "agent-ma";
    private static final String AGENT_NEW = "agent-newcomer";
    private static final String STEWARD = "did:key:steward1";

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
    void full_pipeline_location_calendar_desktop_to_unified_context() {
        // Grant Ma all permissions
        accessMgr.grant(AGENT_MA, "location", "", STEWARD);
        accessMgr.grant(AGENT_MA, "calendar", "", STEWARD);
        accessMgr.grant(AGENT_MA, "active_window", "", STEWARD);

        var now = Instant.now();
        aggregator.updateLocation(PLAYER, LocationContext.LocationState.WORK, "Office");
        aggregator.updateCalendar(PLAYER, List.of(
            new CalendarContext.CalendarEvent("Code Review",
                now.plusSeconds(1800), now.plusSeconds(3600), "Room B", false)
        ));
        aggregator.updateDesktop(PLAYER, "PR-1234", "coding");

        String ctx = aggregator.buildContextForAgent(PLAYER, AGENT_MA, accessMgr);

        assertThat(ctx).isNotNull();
        assertThat(ctx).contains("## Personal Context");
        assertThat(ctx).contains("Office");
        assertThat(ctx).contains("Code Review");
        assertThat(ctx).contains("PR-1234");
    }

    @Test
    void permission_filtering_agent_without_calendar_access() {
        // Ma has everything, newcomer has nothing
        accessMgr.grant(AGENT_MA, "location", "", STEWARD);
        accessMgr.grant(AGENT_MA, "calendar", "", STEWARD);
        accessMgr.grant(AGENT_MA, "active_window", "", STEWARD);

        var now = Instant.now();
        aggregator.updateLocation(PLAYER, LocationContext.LocationState.WORK, "Office");
        aggregator.updateCalendar(PLAYER, List.of(
            new CalendarContext.CalendarEvent("Private Meeting",
                now.plusSeconds(600), now.plusSeconds(1800), null, false)
        ));

        String maCtx = aggregator.buildContextForAgent(PLAYER, AGENT_MA, accessMgr);
        String newCtx = aggregator.buildContextForAgent(PLAYER, AGENT_NEW, accessMgr);

        assertThat(maCtx).contains("Private Meeting");
        assertThat(maCtx).contains("Office");

        // Newcomer should NOT see calendar or location
        assertThat(newCtx).doesNotContain("Private Meeting");
        assertThat(newCtx).doesNotContain("Office");
    }

    @Test
    void topic_extraction_feeds_into_context() {
        var messages = List.of(
            "We need to finish the database migration this week",
            "The database migration scripts are almost ready",
            "Let me check the migration status"
        );
        var topics = TopicExtractor.extractTopics(messages, 3);
        aggregator.updateTopics(PLAYER, topics);

        // Topics are Tier 1 -- visible to all agents without permission
        String ctx = aggregator.buildContextForAgent(PLAYER, AGENT_NEW, accessMgr);

        assertThat(ctx).isNotNull();
        assertThat(ctx).contains("Recent topics");
        assertThat(ctx).containsIgnoringCase("migration");
    }

    @Test
    void meeting_plus_coding_connected_context() {
        accessMgr.grant(AGENT_MA, "calendar", "", STEWARD);
        accessMgr.grant(AGENT_MA, "active_window", "", STEWARD);

        var now = Instant.now();
        aggregator.updateCalendar(PLAYER, List.of(
            new CalendarContext.CalendarEvent("Code Review",
                now.plusSeconds(900), now.plusSeconds(2700), null, false)
        ));
        aggregator.updateDesktop(PLAYER, "git diff output", "coding");

        String ctx = aggregator.buildContextForAgent(PLAYER, AGENT_MA, accessMgr);

        assertThat(ctx).isNotNull();
        // Connected insight: preparing for meeting + editing
        assertThat(ctx).contains("Preparing for Code Review");
    }

    @Test
    void all_sources_combined_produces_coherent_narrative() {
        accessMgr.grant(AGENT_MA, "location", "", STEWARD);
        accessMgr.grant(AGENT_MA, "calendar", "", STEWARD);
        accessMgr.grant(AGENT_MA, "active_window", "", STEWARD);
        accessMgr.grant(AGENT_MA, "email_subjects", "", STEWARD);
        accessMgr.grant(AGENT_MA, "files", "", STEWARD);

        var now = Instant.now();
        aggregator.updateLocation(PLAYER, LocationContext.LocationState.WORK, "Main Office");
        aggregator.updateCalendar(PLAYER, List.of(
            new CalendarContext.CalendarEvent("Sprint Planning",
                now.plusSeconds(3600), now.plusSeconds(7200), "Conf Room A", false)
        ));
        aggregator.updateDesktop(PLAYER, "Jira Board", "browsing");
        aggregator.updateEmailSubjects(PLAYER, List.of("RE: Sprint velocity", "Standup notes"));
        aggregator.updateRecentFiles(PLAYER, List.of("sprint-backlog.md", "velocity-chart.png"));
        aggregator.updateTopics(PLAYER, List.of("Sprint planning", "Velocity tracking"));
        aggregator.markActive(PLAYER);

        String ctx = aggregator.buildContextForAgent(PLAYER, AGENT_MA, accessMgr);

        assertThat(ctx).isNotNull();
        assertThat(ctx).contains("## Personal Context");
        assertThat(ctx).contains("Main Office");
        assertThat(ctx).contains("Sprint Planning");
        assertThat(ctx).contains("Jira Board");
        assertThat(ctx).contains("Sprint velocity");
        assertThat(ctx).contains("sprint-backlog.md");
        assertThat(ctx).contains("Sprint planning");
        assertThat(ctx).contains("Last active");
    }

    @Test
    void revoke_permission_removes_source_from_context() {
        accessMgr.grant(AGENT_MA, "location", "", STEWARD);
        accessMgr.grant(AGENT_MA, "calendar", "", STEWARD);

        var now = Instant.now();
        aggregator.updateLocation(PLAYER, LocationContext.LocationState.HOME, "Home");
        aggregator.updateCalendar(PLAYER, List.of(
            new CalendarContext.CalendarEvent("Personal Appointment",
                now.plusSeconds(1800), now.plusSeconds(3600), null, false)
        ));

        // Before revoke: both visible
        String before = aggregator.buildContextForAgent(PLAYER, AGENT_MA, accessMgr);
        assertThat(before).contains("Home");
        assertThat(before).contains("Personal Appointment");

        // Revoke calendar
        accessMgr.revoke(AGENT_MA, "calendar");

        // After revoke: calendar gone
        String after = aggregator.buildContextForAgent(PLAYER, AGENT_MA, accessMgr);
        assertThat(after).contains("Home");
        assertThat(after).doesNotContain("Personal Appointment");
    }

    @Test
    void stale_location_data_marked() {
        accessMgr.grant(AGENT_MA, "location", "", STEWARD);

        // Fresh location -- not stale
        aggregator.updateLocation(PLAYER, LocationContext.LocationState.WORK, "Office");
        String freshCtx = aggregator.buildContextForAgent(PLAYER, AGENT_MA, accessMgr);
        assertThat(freshCtx).contains("Office");
        assertThat(freshCtx).doesNotContain("[stale]");

        // We can't easily simulate 30-minute-old data without mocking time,
        // but we can verify the isLocationStale() method works
        var profile = aggregator.getProfile(PLAYER);
        assertThat(profile.isLocationStale()).isFalse();
    }

    @Test
    void multiple_agents_see_different_views_of_same_player() {
        // Ma has full access
        accessMgr.grant(AGENT_MA, "location", "", STEWARD);
        accessMgr.grant(AGENT_MA, "calendar", "", STEWARD);
        accessMgr.grant(AGENT_MA, "active_window", "", STEWARD);
        accessMgr.grant(AGENT_MA, "email_subjects", "", STEWARD);

        // Newcomer has only location
        accessMgr.grant(AGENT_NEW, "location", "", STEWARD);

        var now = Instant.now();
        aggregator.updateLocation(PLAYER, LocationContext.LocationState.WORK, "Office");
        aggregator.updateCalendar(PLAYER, List.of(
            new CalendarContext.CalendarEvent("Secret Strategy Session",
                now.plusSeconds(600), now.plusSeconds(3600), null, false)
        ));
        aggregator.updateDesktop(PLAYER, "Confidential.docx", "writing");
        aggregator.updateEmailSubjects(PLAYER, List.of("RE: Compensation Review"));
        aggregator.updateTopics(PLAYER, List.of("Team restructuring"));

        String maView = aggregator.buildContextForAgent(PLAYER, AGENT_MA, accessMgr);
        String newView = aggregator.buildContextForAgent(PLAYER, AGENT_NEW, accessMgr);

        // Ma sees everything
        assertThat(maView).contains("Office");
        assertThat(maView).contains("Secret Strategy Session");
        assertThat(maView).contains("Confidential.docx");
        assertThat(maView).contains("Compensation Review");
        assertThat(maView).contains("Team restructuring"); // topics are Tier 1

        // Newcomer sees only location + topics (Tier 1)
        assertThat(newView).contains("Office");
        assertThat(newView).contains("Team restructuring"); // topics are Tier 1
        assertThat(newView).doesNotContain("Secret Strategy Session");
        assertThat(newView).doesNotContain("Confidential.docx");
        assertThat(newView).doesNotContain("Compensation Review");
    }
}
