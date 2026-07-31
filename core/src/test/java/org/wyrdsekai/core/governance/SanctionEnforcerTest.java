package org.wyrdsekai.core.governance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SanctionEnforcerTest {

    private ModerationService modService;
    private SanctionEnforcer enforcer;

    @BeforeEach void setUp() {
        modService = new ModerationService();
        enforcer = new SanctionEnforcer(modService);
    }

    @Test void applySanction_records_action() {
        var action = enforcer.applySanction("user1",
            ModerationService.SanctionLevel.WARNING, "First offense", null);
        assertThat(action.action()).isEqualTo("applied");
        assertThat(action.level()).isEqualTo(ModerationService.SanctionLevel.WARNING);
        assertThat(enforcer.currentLevel("user1")).isEqualTo(ModerationService.SanctionLevel.WARNING);
    }

    @Test void escalate_increases_level() {
        enforcer.applySanction("user1", ModerationService.SanctionLevel.WARNING,
            "First", null);
        var action = enforcer.escalate("user1", "Repeated offense");
        assertThat(action.level()).isEqualTo(ModerationService.SanctionLevel.PROBATION);
        assertThat(action.action()).isEqualTo("escalated");
    }

    @Test void liftSanction_resets_to_none() {
        enforcer.applySanction("user1", ModerationService.SanctionLevel.SUSPENSION,
            "Test", null);
        var action = enforcer.liftSanction("user1", "Appeal granted");
        assertThat(action.level()).isEqualTo(ModerationService.SanctionLevel.NONE);
        assertThat(enforcer.currentLevel("user1")).isEqualTo(ModerationService.SanctionLevel.NONE);
    }

    @Test void canEnterRoom_banned_entity_blocked() {
        enforcer.applySanction("user1", ModerationService.SanctionLevel.BAN,
            "Banned", null);
        assertThat(enforcer.canEnterRoom("user1", "any-room")).isFalse();
    }

    @Test void canEnterRoom_suspended_entity_restricted() {
        enforcer.applySanction("user1", ModerationService.SanctionLevel.SUSPENSION,
            "Suspended", null);
        enforcer.setRestrictedRooms("user1", List.of("nexus"));
        assertThat(enforcer.canEnterRoom("user1", "nexus")).isTrue();
        assertThat(enforcer.canEnterRoom("user1", "bridge")).isFalse();
    }

    @Test void canSpeak_probation_silenced() {
        enforcer.applySanction("user1", ModerationService.SanctionLevel.PROBATION,
            "Silenced", null);
        assertThat(enforcer.canSpeak("user1")).isFalse();
    }

    @Test void canSpeak_warning_allowed() {
        enforcer.applySanction("user1", ModerationService.SanctionLevel.WARNING,
            "Warning", null);
        assertThat(enforcer.canSpeak("user1")).isTrue();
    }

    @Test void historyFor_tracks_all_actions() {
        enforcer.applySanction("user1", ModerationService.SanctionLevel.WARNING, "First", null);
        enforcer.escalate("user1", "Second");
        enforcer.liftSanction("user1", "Resolved");
        assertThat(enforcer.historyFor("user1")).hasSize(3);
    }

    @Test void describe_shows_history() {
        enforcer.applySanction("user1", ModerationService.SanctionLevel.WARNING, "Test", null);
        assertThat(enforcer.describe()).contains("Enforcement History");
    }
}
