package org.wyrdsekai.server;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OnboardingServiceTest {

    private OnboardingService service;

    @BeforeEach void setUp() {
        service = new OnboardingService();
    }

    @Test void welcomeMessage_contains_player_name() {
        assertThat(service.welcomeMessage("Alice")).contains("Alice");
    }

    @Test void nextStage_welcome_for_new_player() {
        assertThat(service.nextStage(0, 0))
            .isEqualTo(OnboardingService.OnboardingStage.WELCOME);
    }

    @Test void nextStage_explore_after_a_few_interactions() {
        assertThat(service.nextStage(2, 5))
            .isEqualTo(OnboardingService.OnboardingStage.EXPLORE);
    }

    @Test void nextStage_interact_after_some_rooms() {
        assertThat(service.nextStage(5, 10))
            .isEqualTo(OnboardingService.OnboardingStage.INTERACT);
    }

    @Test void nextStage_complete_after_many_rooms() {
        assertThat(service.nextStage(8, 20))
            .isEqualTo(OnboardingService.OnboardingStage.COMPLETE);
    }

    @Test void hints_vary_by_stage() {
        var welcomeHints = service.hintsForStage(OnboardingService.OnboardingStage.WELCOME);
        var exploreHints = service.hintsForStage(OnboardingService.OnboardingStage.EXPLORE);
        assertThat(welcomeHints).isNotEqualTo(exploreHints);
    }

    @Test void all_stages_have_hints() {
        for (var stage : OnboardingService.OnboardingStage.values()) {
            assertThat(service.hintsForStage(stage)).isNotEmpty();
        }
    }
}
