package org.wyrdsekai.core.economy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.agent.AgentCostTracker;

import java.io.File;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for economy wiring (Item 6a).
 * Tests that all economy classes work together end-to-end.
 */
class EconomyWiringIntegrationTest {

    private MutualCreditLedger ledger;
    private CrossZoneExchange crossZone;
    private TradingPostService tradingPost;
    private ComputeUnitNormalizer normalizer;
    private EstateManager estateManager;

    @BeforeEach
    void setup() {
        ledger = new MutualCreditLedger();  // in-memory for test
        crossZone = new CrossZoneExchange();
        tradingPost = new TradingPostService();
        normalizer = new ComputeUnitNormalizer();
        estateManager = new EstateManager();
    }

    @Test
    void ledger_persistence_round_trip() throws Exception {
        // Use file-based temp SQLite for test (in-memory doesn't persist across connections)
        var tmpFile = File.createTempFile("ledger-test", ".db");
        tmpFile.deleteOnExit();
        var jdbcUrl = "jdbc:sqlite:" + tmpFile.getAbsolutePath();
        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            conn.createStatement().execute(
                "CREATE TABLE IF NOT EXISTS ledger_transactions("
                + "tx_id TEXT PRIMARY KEY, from_entity TEXT NOT NULL, to_entity TEXT NOT NULL, "
                + "amount INTEGER NOT NULL, description TEXT NOT NULL DEFAULT '', "
                + "created_at INTEGER NOT NULL DEFAULT 0)");
            conn.createStatement().execute(
                "CREATE TABLE IF NOT EXISTS ledger_balances("
                + "entity_id TEXT PRIMARY KEY, balance INTEGER NOT NULL DEFAULT 0, "
                + "credit_limit INTEGER NOT NULL DEFAULT 100, "
                + "total_earned INTEGER NOT NULL DEFAULT 0, total_spent INTEGER NOT NULL DEFAULT 0)");
        }

        var persistence = new LedgerPersistence(jdbcUrl);
        var persistedLedger = new MutualCreditLedger(persistence);

        // Grant credits and transfer
        persistedLedger.grant("agent-a", 1000, "initial grant");
        var tx = persistedLedger.transfer("agent-a", "agent-b", 250, "test transfer");
        assertThat(tx).isPresent();
        assertThat(persistedLedger.getBalance("agent-a").balance()).isEqualTo(750);
        assertThat(persistedLedger.getBalance("agent-b").balance()).isEqualTo(250);

        // Verify persistence layer has the data
        var loadedTx = persistence.loadTransaction(tx.get().id());
        assertThat(loadedTx).isPresent();
        assertThat(loadedTx.get().amount()).isEqualTo(250);

        var loadedBalance = persistence.loadBalance("agent-a");
        assertThat(loadedBalance).isPresent();
        assertThat(loadedBalance.get().balance()).isEqualTo(750);
    }

    @Test
    void cross_zone_exchange_records_inference_cost() {
        crossZone.setRate("zone-a", "zone-b", 1.0, Instant.now().plusSeconds(3600));

        var result = crossZone.exchange("zone-a", "zone-b",
            "agent-1", "zone-b", 100, "Remote inference: 9B (500 tokens)");

        assertThat(result.success()).isTrue();
        assertThat(result.transaction().sourceAmount()).isEqualTo(100);
        assertThat(result.transaction().targetAmount()).isEqualTo(100);
        assertThat(crossZone.transactionCount()).isEqualTo(1);
        assertThat(crossZone.netFlow("zone-a", "zone-b")).isEqualTo(100);
    }

    @Test
    void compute_normalizer_different_tiers() {
        // Phone inference costs less than server inference
        var phoneCU = normalizer.toCU("phone", 1000);
        var desktopCU = normalizer.toCU("desktop", 1000);
        var clusterCU = normalizer.toCU("cluster", 1000);

        assertThat(phoneCU).isLessThan(desktopCU);
        assertThat(desktopCU).isLessThan(clusterCU);
        assertThat(phoneCU).isEqualTo(0.1);   // 0.1 CU per 1K tokens
        assertThat(desktopCU).isEqualTo(2.0);  // 2.0 CU per 1K tokens
    }

    @Test
    void trading_post_full_lifecycle() {
        // Post item
        var posted = tradingPost.postItem("Magic Sword", "A glowing blade", 50,
            "seller-1", "Ember");
        assertThat(posted.itemId()).isNotNull();
        assertThat(tradingPost.availableCount()).isEqualTo(1);

        // Browse
        var items = tradingPost.browseItems();
        assertThat(items).hasSize(1);
        assertThat(items.get(0).name()).isEqualTo("Magic Sword");

        // Acquire
        var acquired = tradingPost.acquireItem(posted.itemId(), "buyer-1");
        assertThat(acquired).isPresent();
        assertThat(acquired.get().status()).isEqualTo(TradingPostService.ItemStatus.SOLD);

        // Provenance chain
        var provenance = tradingPost.verifyProvenance(posted.itemId());
        assertThat(provenance).isPresent();
        assertThat(provenance.get()).hasSize(2); // posted + acquired

        // Can't buy again
        var duplicate = tradingPost.acquireItem(posted.itemId(), "buyer-2");
        assertThat(duplicate).isEmpty();
    }

    @Test
    void agent_cost_tracker_accumulates() {
        AgentCostTracker.init();
        var tracker = AgentCostTracker.get();
        tracker.recordInference("agent-accum", 100, 500, 50);
        tracker.recordInference("agent-accum", 200, 800, 100);

        var summary = tracker.summary("agent-accum");
        assertThat(summary).isPresent();
        assertThat(summary.get().totalInferences()).isEqualTo(2);
        assertThat(summary.get().totalTokens()).isEqualTo(1450); // 500+50+800+100
    }

    @Test
    void daily_budget_enforcement() {
        AgentCostTracker.init();
        var tracker = AgentCostTracker.get();
        tracker.setBudget("agent-budget", 0.01); // very low budget

        // Record expensive call
        tracker.record(new AgentCostTracker.CostEntry(
            "agent-budget", "inference", 100, 1000, 0.02, Instant.now()));

        var budgetCheck = tracker.checkBudget("agent-budget");
        assertThat(budgetCheck).isNotNull();
        assertThat(budgetCheck).contains("exceeded");
    }

    @Test
    void estate_manager_deletion_confirmation() {
        var summary = new EstateManager.EstateSummary(
            "did:key:agent1", "Ember", 15, 3,
            BigDecimal.valueOf(500), Instant.now(), 42);

        var confirmation = estateManager.confirmDeletion(summary, List.of("Nova", "Sage"));
        assertThat(confirmation.consequences()).isNotEmpty();
        assertThat(confirmation.consequences()).anyMatch(c -> c.contains("Notify linked agents"));
        assertThat(confirmation.confirmationPhrase()).isEqualTo("confirm delete ember");

        var display = estateManager.deletionDisplay(confirmation);
        assertThat(display).contains("42 days");
        assertThat(display).contains("15 soul items");
    }

    @Test
    void reputation_affects_tier() {
        var service = new ReputationService(ledger);

        // No activity = newcomer (default tier)
        var rep = service.computeReputation("newcomer");
        assertThat(rep.tier()).isEqualTo("newcomer");

        // Add some activity
        ledger.grant("active-agent", 1000, "grant");
        ledger.transfer("active-agent", "other", 100, "trade");
        var activeRep = service.computeReputation("active-agent");
        // Should have some reputation from transactions
        assertThat(activeRep.composite()).isGreaterThan(0.0);
    }
}
