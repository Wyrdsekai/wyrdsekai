package org.wyrdsekai.core.privacy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class DataMinimizerTest {

    private DataMinimizer minimizer;

    @BeforeEach
    void setUp() {
        minimizer = new DataMinimizer();
        minimizer.setPolicy(DataMinimizer.CHAT_HISTORY);
        minimizer.setPolicy(DataMinimizer.SESSION_DATA);
    }

    @Test void set_and_get_policy() {
        var policy = minimizer.getPolicy("chat_history");
        assertThat(policy).isPresent();
        assertThat(policy.get().maxRetention()).isEqualTo(Duration.ofDays(90));
    }

    @Test void track_item() {
        var item = minimizer.track("item-1", "entity-1", "chat_history");
        assertThat(item.itemId()).isEqualTo("item-1");
        assertThat(item.entityId()).isEqualTo("entity-1");
        assertThat(item.isExpired()).isFalse();
    }

    @Test void items_for_entity() {
        minimizer.track("item-1", "entity-1", "chat_history");
        minimizer.track("item-2", "entity-1", "session_data");
        minimizer.track("item-3", "entity-2", "chat_history");

        assertThat(minimizer.itemsForEntity("entity-1")).hasSize(2);
        assertThat(minimizer.itemsForEntity("entity-2")).hasSize(1);
    }

    @Test void tracked_count() {
        minimizer.track("item-1", "entity-1", "chat_history");
        minimizer.track("item-2", "entity-2", "session_data");
        assertThat(minimizer.trackedCount()).isEqualTo(2);
    }

    @Test void policy_count() {
        assertThat(minimizer.policyCount()).isEqualTo(2);
    }

    @Test void sweep_no_expired() {
        minimizer.track("item-1", "entity-1", "chat_history");
        var result = minimizer.sweep();
        assertThat(result.itemsChecked()).isEqualTo(1);
        assertThat(result.itemsExpired()).isEqualTo(0);
        assertThat(result.itemsDeleted()).isEqualTo(0);
    }

    @Test void expired_items_detected() {
        // Use a very short retention policy
        minimizer.setPolicy(new DataMinimizer.RetentionPolicy(
            "instant", Duration.ofMillis(1), true, "test"));
        minimizer.track("item-1", "entity-1", "instant");

        // Wait for expiry
        try { Thread.sleep(5); } catch (InterruptedException ignored) {}

        assertThat(minimizer.expiredItems()).hasSize(1);
    }

    @Test void default_policies() {
        assertThat(DataMinimizer.CHAT_HISTORY.category()).isEqualTo("chat_history");
        assertThat(DataMinimizer.SESSION_DATA.maxRetention()).isEqualTo(Duration.ofDays(7));
        assertThat(DataMinimizer.ANALYTICS.autoDelete()).isTrue();
        assertThat(DataMinimizer.ACCOUNT_DATA.autoDelete()).isFalse();
    }

    @Test void unknown_category_uses_default_retention() {
        var item = minimizer.track("item-1", "entity-1", "unknown_category");
        // Should use 365-day default
        assertThat(item.isExpired()).isFalse();
    }
}
