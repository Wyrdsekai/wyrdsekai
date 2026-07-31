package org.wyrdsekai.core.economy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.room.ZoneAesthetic;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.assertj.core.data.Offset.offset;

/**
 * Tests for zone-to-zone transit economy integration (Item 6c).
 */
class ZoneTransitEconomyTest {

    private CrossZoneExchange exchange;
    private MutualCreditLedger homeLedger;
    private ComputeUnitNormalizer normalizer;

    @BeforeEach
    void setup() {
        exchange = new CrossZoneExchange();
        homeLedger = new MutualCreditLedger();
        normalizer = new ComputeUnitNormalizer();
    }

    @Test
    void cross_zone_inference_debits_visitor() {
        // Setup exchange rate between zones
        exchange.setRate("home-zone", "host-zone", 1.0, Instant.now().plusSeconds(3600));

        // Simulate: agent from home-zone gets inference on host-zone
        var result = exchange.exchange("home-zone", "host-zone",
            "agent-visitor", "host-zone",
            50, "Remote inference: 9B (1000 tokens)");

        assertThat(result.success()).isTrue();
        assertThat(result.transaction().sourceEntityId()).isEqualTo("agent-visitor");
        assertThat(result.transaction().sourceAmount()).isEqualTo(50);
    }

    @Test
    void different_exchange_rates_affect_cost() {
        // Expensive zone charges 2x
        exchange.setRate("cheap-zone", "expensive-zone", 2.0,
            Instant.now().plusSeconds(3600));

        var result = exchange.exchange("cheap-zone", "expensive-zone",
            "agent-1", "expensive-zone", 100, "inference");

        assertThat(result.success()).isTrue();
        // 100 source credits = 200 target credits at 2x rate
        assertThat(result.transaction().targetAmount()).isEqualTo(200);
    }

    @Test
    void expired_rate_blocks_exchange() {
        // Set expired rate
        exchange.setRate("zone-a", "zone-b", 1.0,
            Instant.now().minusSeconds(10)); // already expired

        var result = exchange.exchange("zone-a", "zone-b",
            "agent-1", "zone-b", 100, "inference");

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("No exchange rate");
    }

    @Test
    void compute_normalizer_normalizes_across_tiers() {
        // Same 1000 tokens, different hardware
        var phoneCost = normalizer.toCU("phone", 1000);     // 0.1 CU
        var edgeCost = normalizer.toCU("edge", 1000);       // 0.5 CU
        var desktopCost = normalizer.toCU("desktop", 1000); // 2.0 CU
        var externalCost = normalizer.toCU("external", 1000); // 10.0 CU

        assertThat(phoneCost).isLessThan(edgeCost);
        assertThat(edgeCost).isLessThan(desktopCost);
        assertThat(desktopCost).isLessThan(externalCost);
    }

    @Test
    void shapley_share_distributes_cost_proportionally() {
        var shares = normalizer.shapleyShare(100.0,
            Map.of("node-a", 0.7, "node-b", 0.3));

        assertThat(shares.get("node-a")).isCloseTo(70.0, offset(0.01));
        assertThat(shares.get("node-b")).isCloseTo(30.0, offset(0.01));
    }

    @Test
    void zone_cost_modifiers_affect_action_cost() {
        // Arcane zone: craft_item = 0.7x, web_search = 1.5x
        var arcane = ZoneAesthetic.arcane();

        double baseCraftCost = 0.20;
        double baseSearchCost = 0.05;

        double craftInArcane = baseCraftCost * arcane.costModifier("craft_item");
        double searchInArcane = baseSearchCost * arcane.costModifier("web_search");

        // Craft is cheaper in arcane (0.14 vs 0.20)
        assertThat(craftInArcane).isLessThan(baseCraftCost);
        // Search is more expensive in arcane (0.075 vs 0.05)
        assertThat(searchInArcane).isGreaterThan(baseSearchCost);
    }

    @Test
    void net_flow_tracks_bilateral_balance() {
        exchange.setRate("zone-a", "zone-b", 1.0, Instant.now().plusSeconds(3600));
        exchange.setRate("zone-b", "zone-a", 1.0, Instant.now().plusSeconds(3600));

        // Zone A agents use zone B inference
        exchange.exchange("zone-a", "zone-b", "agent-1", "zone-b", 200, "inference");
        exchange.exchange("zone-a", "zone-b", "agent-2", "zone-b", 150, "inference");

        // Zone B agent uses zone A inference
        exchange.exchange("zone-b", "zone-a", "agent-3", "zone-a", 100, "inference");

        // Net flow A→B = 350, B→A = 100
        assertThat(exchange.netFlow("zone-a", "zone-b")).isEqualTo(350);
        assertThat(exchange.netFlow("zone-b", "zone-a")).isEqualTo(100);
    }
}
