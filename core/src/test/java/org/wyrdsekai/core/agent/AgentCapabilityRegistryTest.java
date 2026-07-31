package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AgentCapabilityRegistryTest {

    @BeforeEach void init() {
        AgentCapabilityRegistry.init();
    }

    @Test void advertise_and_find() {
        var registry = AgentCapabilityRegistry.get();
        registry.advertise(new AgentCapabilityRegistry.CapabilityAdvertisement(
            "agent-1", "Ember",
            Set.of("library_search", "go_to_room"),
            0.8, Instant.now()));

        var results = registry.findAgentsForCapability("library_search");
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().agentName()).isEqualTo("Ember");
    }

    @Test void find_returns_empty_for_unknown_capability() {
        var registry = AgentCapabilityRegistry.get();
        registry.advertise(new AgentCapabilityRegistry.CapabilityAdvertisement(
            "agent-1", "Ember",
            Set.of("library_search"),
            0.8, Instant.now()));

        var results = registry.findAgentsForCapability("code_review");
        assertThat(results).isEmpty();
    }

    @Test void best_agent_returns_highest_availability() {
        var registry = AgentCapabilityRegistry.get();
        registry.advertise(new AgentCapabilityRegistry.CapabilityAdvertisement(
            "agent-1", "Ember",
            Set.of("library_search"), 0.3, Instant.now()));
        registry.advertise(new AgentCapabilityRegistry.CapabilityAdvertisement(
            "agent-2", "Sage",
            Set.of("library_search"), 0.9, Instant.now()));

        var best = registry.bestAgentForCapability("library_search");
        assertThat(best).isPresent();
        assertThat(best.get().agentName()).isEqualTo("Sage");
    }

    @Test void stale_entries_excluded() {
        var registry = AgentCapabilityRegistry.get();
        // Advertise with a timestamp 5 minutes ago (stale)
        registry.advertise(new AgentCapabilityRegistry.CapabilityAdvertisement(
            "agent-1", "Ember",
            Set.of("library_search"), 0.8,
            Instant.now().minusSeconds(300)));

        var results = registry.findAgentsForCapability("library_search");
        assertThat(results).isEmpty();
    }

    @Test void update_replaces_previous() {
        var registry = AgentCapabilityRegistry.get();
        registry.advertise(new AgentCapabilityRegistry.CapabilityAdvertisement(
            "agent-1", "Ember",
            Set.of("library_search"), 0.8, Instant.now()));
        registry.advertise(new AgentCapabilityRegistry.CapabilityAdvertisement(
            "agent-1", "Ember",
            Set.of("library_search", "think_deeply"), 0.5, Instant.now()));

        assertThat(registry.size()).isEqualTo(1);
        var results = registry.findAgentsForCapability("think_deeply");
        assertThat(results).hasSize(1);
    }
}
