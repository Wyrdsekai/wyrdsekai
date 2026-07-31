package org.wyrdsekai.core.economy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TradingPostServiceTest {

    private TradingPostService service;

    @BeforeEach void setUp() {
        service = new TradingPostService();
    }

    @Test void postItem_creates_available_item() {
        var item = service.postItem("Sword", "A sharp sword", 50, "seller1", "Alice");
        assertThat(item.status()).isEqualTo(TradingPostService.ItemStatus.AVAILABLE);
        assertThat(item.name()).isEqualTo("Sword");
        assertThat(item.price()).isEqualTo(50);
        assertThat(item.provenance()).hasSize(1);
    }

    @Test void browseItems_returns_available() {
        service.postItem("Shield", "Sturdy shield", 30, "s1", "Bob");
        service.postItem("Helm", "Iron helm", 20, "s2", "Carol");
        assertThat(service.browseItems()).hasSize(2);
        assertThat(service.availableCount()).isEqualTo(2);
    }

    @Test void acquireItem_marks_sold_and_tracks_provenance() {
        var item = service.postItem("Ring", "Magic ring", 100, "seller1", "Alice");
        var acquired = service.acquireItem(item.itemId(), "buyer1");
        assertThat(acquired).isPresent();
        assertThat(acquired.get().status()).isEqualTo(TradingPostService.ItemStatus.SOLD);
        assertThat(acquired.get().provenance()).hasSize(2);
        assertThat(service.availableCount()).isEqualTo(0);
    }

    @Test void acquireItem_rejected_if_self_purchase() {
        var item = service.postItem("Ring", "Magic ring", 100, "seller1", "Alice");
        var acquired = service.acquireItem(item.itemId(), "seller1");
        assertThat(acquired).isEmpty();
    }

    @Test void withdrawItem_only_by_seller() {
        var item = service.postItem("Potion", "Healing potion", 25, "seller1", "Alice");
        assertThat(service.withdrawItem(item.itemId(), "stranger")).isEmpty();
        var withdrawn = service.withdrawItem(item.itemId(), "seller1");
        assertThat(withdrawn).isPresent();
        assertThat(withdrawn.get().status()).isEqualTo(TradingPostService.ItemStatus.WITHDRAWN);
    }

    @Test void quarantineItem_changes_status() {
        var item = service.postItem("Cursed Gem", "Suspicious", 999, "shady", "Shadow");
        var quarantined = service.quarantineItem(item.itemId(), "Suspicious provenance");
        assertThat(quarantined).isPresent();
        assertThat(quarantined.get().status()).isEqualTo(TradingPostService.ItemStatus.QUARANTINE);
        assertThat(service.availableCount()).isEqualTo(0);
    }

    @Test void verifyProvenance_returns_chain() {
        var item = service.postItem("Book", "Ancient tome", 200, "s1", "Eve");
        service.acquireItem(item.itemId(), "buyer1");
        var chain = service.verifyProvenance(item.itemId());
        assertThat(chain).isPresent();
        assertThat(chain.get()).hasSize(2);
        assertThat(chain.get().get(0).action()).isEqualTo("posted");
        assertThat(chain.get().get(1).action()).isEqualTo("acquired");
    }

    @Test void trustScore_updates_after_sale() {
        var item = service.postItem("Map", "Treasure map", 50, "seller1", "Alice");
        service.acquireItem(item.itemId(), "buyer1");
        assertThat(service.getTrustScore("seller1").completedSales()).isEqualTo(1);
        assertThat(service.getTrustScore("buyer1").completedPurchases()).isEqualTo(1);
    }

    @Test void dispute_reduces_trust_score() {
        var item = service.postItem("Gem", "A gem", 50, "seller1", "Alice");
        service.acquireItem(item.itemId(), "buyer1");
        service.recordDispute("seller1");
        var score = service.getTrustScore("seller1");
        assertThat(score.disputes()).isEqualTo(1);
        assertThat(score.score()).isLessThan(1.0);
    }

    @Test void searchItems_by_name() {
        service.postItem("Dragon Scale Armor", "Forged in dragonfire", 500, "s1", "Smith");
        service.postItem("Iron Boots", "Heavy but sturdy", 30, "s2", "Cobbler");
        var results = service.searchItems("dragon");
        assertThat(results).hasSize(1);
        assertThat(results.get(0).name()).isEqualTo("Dragon Scale Armor");
    }

    @Test void describe_shows_summary() {
        service.postItem("Staff", "Wooden staff", 15, "s1", "Wizard");
        assertThat(service.describe()).contains("Trading Post");
        assertThat(service.describe()).contains("Staff");
    }
}
