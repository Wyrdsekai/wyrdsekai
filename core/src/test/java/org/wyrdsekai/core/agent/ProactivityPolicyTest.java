package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProactivityPolicyTest {

    // --- Factory methods ---

    @Test void serverDefault_sets_lower_thresholds() {
        var policy = ProactivityPolicy.serverDefault(List.of("hearth.*"));
        assertThat(policy.minEnergy()).isEqualTo(0.4);
        assertThat(policy.minConfidence()).isEqualTo(0.5);
        assertThat(policy.maxPerWindow()).isEqualTo(3);
        assertThat(policy.windowSize()).isEqualTo(Duration.ofMinutes(10));
        assertThat(policy.proactivePatterns()).containsExactly("hearth.*");
    }

    @Test void phoneDefault_sets_higher_thresholds() {
        var policy = ProactivityPolicy.phoneDefault(List.of("hearth.*"));
        assertThat(policy.minEnergy()).isEqualTo(0.6);
        assertThat(policy.minConfidence()).isEqualTo(0.5);
        assertThat(policy.maxPerWindow()).isEqualTo(2);
    }

    @Test void disabled_blocks_everything() {
        var policy = ProactivityPolicy.disabled();
        assertThat(policy.proactivePatterns()).isEmpty();
        assertThat(policy.maxPerWindow()).isEqualTo(0);
        assertThat(policy.isActive(1.0, 1.0)).isFalse(); // empty patterns = inactive
    }

    @Test void null_patterns_defaults_to_empty() {
        var policy = new ProactivityPolicy(null, 0.4, 0.5, 3, Duration.ofMinutes(10));
        assertThat(policy.proactivePatterns()).isEmpty();
    }

    @Test void null_windowSize_defaults_to_10min() {
        var policy = new ProactivityPolicy(List.of("*"), 0.4, 0.5, 3, null);
        assertThat(policy.windowSize()).isEqualTo(Duration.ofMinutes(10));
    }

    // --- isActive ---

    @Nested class IsActive {
        @Test void active_when_above_thresholds() {
            var policy = ProactivityPolicy.serverDefault(List.of("hearth.*"));
            assertThat(policy.isActive(0.5, 0.6)).isTrue();
        }

        @Test void inactive_when_energy_below_threshold() {
            var policy = ProactivityPolicy.serverDefault(List.of("hearth.*"));
            assertThat(policy.isActive(0.3, 0.6)).isFalse();
        }

        @Test void inactive_when_confidence_below_threshold() {
            var policy = ProactivityPolicy.serverDefault(List.of("hearth.*"));
            assertThat(policy.isActive(0.5, 0.4)).isFalse();
        }

        @Test void inactive_when_no_patterns() {
            var policy = ProactivityPolicy.serverDefault(List.of());
            assertThat(policy.isActive(1.0, 1.0)).isFalse();
        }

        @Test void active_at_exact_thresholds() {
            var policy = ProactivityPolicy.serverDefault(List.of("hearth.*"));
            assertThat(policy.isActive(0.4, 0.5)).isTrue();
        }
    }

    // --- matchesPattern ---

    @Nested class MatchesPattern {
        @Test void exact_match() {
            var policy = ProactivityPolicy.serverDefault(List.of("hearth.lights.toggle"));
            assertThat(policy.matchesPattern("hearth.lights.toggle")).isTrue();
        }

        @Test void glob_suffix_match() {
            var policy = ProactivityPolicy.serverDefault(List.of("hearth.*"));
            assertThat(policy.matchesPattern("hearth.lights.toggle")).isTrue();
            assertThat(policy.matchesPattern("hearth.ha.set-light")).isTrue();
        }

        @Test void glob_no_match() {
            var policy = ProactivityPolicy.serverDefault(List.of("hearth.*"));
            assertThat(policy.matchesPattern("scrying.search")).isFalse();
        }

        @Test void null_skillId_returns_false() {
            var policy = ProactivityPolicy.serverDefault(List.of("hearth.*"));
            assertThat(policy.matchesPattern(null)).isFalse();
        }

        @Test void multiple_patterns() {
            var policy = ProactivityPolicy.serverDefault(
                List.of("hearth.*", "scrying.*", "herald.notify"));
            assertThat(policy.matchesPattern("hearth.lights.off")).isTrue();
            assertThat(policy.matchesPattern("scrying.search")).isTrue();
            assertThat(policy.matchesPattern("herald.notify")).isTrue();
            assertThat(policy.matchesPattern("herald.email.send")).isFalse();
        }
    }

    // --- Window tracking ---

    @Nested class WindowTracking {
        @Test void initial_remaining_equals_max() {
            var policy = ProactivityPolicy.serverDefault(List.of("hearth.*"));
            assertThat(policy.remainingInWindow()).isEqualTo(3);
        }

        @Test void recordProactiveAction_decrements_remaining() {
            var policy = ProactivityPolicy.serverDefault(List.of("hearth.*"));
            assertThat(policy.recordProactiveAction()).isTrue();
            assertThat(policy.remainingInWindow()).isEqualTo(2);
        }

        @Test void returns_false_when_budget_exhausted() {
            var policy = ProactivityPolicy.serverDefault(List.of("hearth.*"));
            assertThat(policy.recordProactiveAction()).isTrue();
            assertThat(policy.recordProactiveAction()).isTrue();
            assertThat(policy.recordProactiveAction()).isTrue();
            assertThat(policy.recordProactiveAction()).isFalse(); // 4th exceeds max=3
        }

        @Test void remaining_never_negative() {
            var policy = ProactivityPolicy.serverDefault(List.of("hearth.*"));
            for (int i = 0; i < 10; i++) policy.recordProactiveAction();
            assertThat(policy.remainingInWindow()).isEqualTo(0);
        }
    }

    // --- buildContextSection ---

    @Nested class BuildContextSection {
        @Test void returns_null_when_inactive() {
            var policy = ProactivityPolicy.serverDefault(List.of("hearth.*"));
            assertThat(policy.buildContextSection(0.2, 0.2)).isNull();
        }

        @Test void returns_null_when_budget_exhausted() {
            var policy = ProactivityPolicy.serverDefault(List.of("hearth.*"));
            for (int i = 0; i < 3; i++) policy.recordProactiveAction();
            assertThat(policy.buildContextSection(0.5, 0.6)).isNull();
        }

        @Test void includes_patterns_and_budget() {
            var policy = ProactivityPolicy.serverDefault(List.of("hearth.*", "scrying.*"));
            var section = policy.buildContextSection(0.5, 0.6);
            assertThat(section).isNotNull();
            assertThat(section).contains("Proactive Skills");
            assertThat(section).contains("hearth.*");
            assertThat(section).contains("scrying.*");
            assertThat(section).contains("3 of 3");
        }

        @Test void budget_reflects_used_actions() {
            var policy = ProactivityPolicy.serverDefault(List.of("hearth.*"));
            policy.recordProactiveAction();
            var section = policy.buildContextSection(0.5, 0.6);
            assertThat(section).contains("2 of 3");
        }
    }

    // --- globMatch static ---

    @Test void globMatch_exact() {
        assertThat(ProactivityPolicy.globMatch("foo", "foo")).isTrue();
        assertThat(ProactivityPolicy.globMatch("foo", "bar")).isFalse();
    }

    @Test void globMatch_wildcard_suffix() {
        assertThat(ProactivityPolicy.globMatch("hearth.*", "hearth.lights")).isTrue();
        assertThat(ProactivityPolicy.globMatch("hearth.*", "hearth.")).isTrue();
        assertThat(ProactivityPolicy.globMatch("hearth.*", "scrying.search")).isFalse();
    }
}
