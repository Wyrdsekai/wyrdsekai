package org.wyrdsekai.between.federation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.soul.SoulTransitProtocol;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FederationTierTest {

    private FederationService service;

    @BeforeEach
    void setUp() {
        // Use a dummy JDBC URL — visit tracking is in-memory, no DB needed
        service = new FederationService("jdbc:sqlite:file:tier-test?mode=memory&cache=shared");
    }

    @Test void initial_visit_count_is_zero() {
        assertThat(service.getVisitCount("agent-1", "zone-a")).isEqualTo(0);
    }

    @Test void record_visit_increments_count() {
        service.recordVisit("agent-1", "zone-a");
        assertThat(service.getVisitCount("agent-1", "zone-a")).isEqualTo(1);

        service.recordVisit("agent-1", "zone-a");
        assertThat(service.getVisitCount("agent-1", "zone-a")).isEqualTo(2);
    }

    @Test void visits_are_per_agent_per_zone() {
        service.recordVisit("agent-1", "zone-a");
        service.recordVisit("agent-1", "zone-b");
        service.recordVisit("agent-2", "zone-a");

        assertThat(service.getVisitCount("agent-1", "zone-a")).isEqualTo(1);
        assertThat(service.getVisitCount("agent-1", "zone-b")).isEqualTo(1);
        assertThat(service.getVisitCount("agent-2", "zone-a")).isEqualTo(1);
    }

    @Test void tier_escalation_boundaries_tourist() {
        // 0-2 visits -> tourist
        assertThat(computeTier(0)).isEqualTo("tourist");
        assertThat(computeTier(1)).isEqualTo("tourist");
        assertThat(computeTier(2)).isEqualTo("tourist");
    }

    @Test void tier_escalation_boundaries_resident() {
        // 3-9 visits -> resident
        assertThat(computeTier(3)).isEqualTo("resident");
        assertThat(computeTier(5)).isEqualTo("resident");
        assertThat(computeTier(9)).isEqualTo("resident");
    }

    @Test void tier_escalation_boundaries_citizen() {
        // 10+ visits -> citizen
        assertThat(computeTier(10)).isEqualTo("citizen");
        assertThat(computeTier(15)).isEqualTo("citizen");
        assertThat(computeTier(100)).isEqualTo("citizen");
    }

    // ── Soul-aware transit capability advertisement (definitive re-audit #33-5) ──
    // The default was none() and setLocalSoulCapabilities had no prod caller, so
    // soul-aware transit could never engage. Main now advertises real caps via
    // full(models); these tests pin the before/after of that advertisement.

    @Test void default_soul_capabilities_are_none() {
        var caps = service.getLocalSoulCapabilities();
        assertThat(caps.soulAware()).isFalse();
        assertThat(caps.forgeAvailable()).isFalse();
        assertThat(caps.buddingSupported()).isFalse();
        assertThat(caps.availableModels()).isEmpty();
    }

    @Test void full_soul_capabilities_advertise_soul_aware_transit() {
        service.setLocalSoulCapabilities(
            SoulTransitProtocol.ZoneSoulCapabilities.full(List.of("qwen3.5-9b")));
        var caps = service.getLocalSoulCapabilities();
        assertThat(caps.soulAware()).isTrue();
        assertThat(caps.forgeAvailable()).isTrue();
        assertThat(caps.buddingSupported()).isTrue();
        assertThat(caps.availableModels()).containsExactly("qwen3.5-9b");
    }

    /**
     * Mirrors the tier computation logic from FederationActor.handleInboundTransitRequest.
     * This avoids needing to spin up a full actor system for unit testing the logic.
     */
    private String computeTier(int visits) {
        if (visits >= 10) return BilateralAgreement.TRUST_CITIZEN;
        if (visits >= 3) return BilateralAgreement.TRUST_RESIDENT;
        return BilateralAgreement.TRUST_TOURIST;
    }
}
