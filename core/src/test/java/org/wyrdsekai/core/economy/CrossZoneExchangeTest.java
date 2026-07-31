package org.wyrdsekai.core.economy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class CrossZoneExchangeTest {

    private CrossZoneExchange exchange;

    @BeforeEach
    void setUp() {
        exchange = new CrossZoneExchange();
    }

    @Test void set_and_get_rate() {
        exchange.setRate("zone-a", "zone-b", 1.5, null);
        var rate = exchange.getRate("zone-a", "zone-b");
        assertThat(rate).isPresent();
        assertThat(rate.get().rate()).isEqualTo(1.5);
    }

    @Test void rate_not_found() {
        assertThat(exchange.getRate("zone-a", "zone-b")).isEmpty();
    }

    @Test void expired_rate_not_returned() {
        exchange.setRate("zone-a", "zone-b", 1.0, Instant.now().minusSeconds(3600));
        assertThat(exchange.getRate("zone-a", "zone-b")).isEmpty();
    }

    @Test void convert_credits() {
        exchange.setRate("zone-a", "zone-b", 2.0, null);
        var rate = exchange.getRate("zone-a", "zone-b").orElseThrow();
        assertThat(rate.convert(100)).isEqualTo(200);
    }

    @Test void reverse_convert() {
        exchange.setRate("zone-a", "zone-b", 2.0, null);
        var rate = exchange.getRate("zone-a", "zone-b").orElseThrow();
        assertThat(rate.reverseConvert(200)).isEqualTo(100);
    }

    @Test void execute_exchange() {
        exchange.setRate("zone-a", "zone-b", 1.5, null);
        var result = exchange.exchange("zone-a", "zone-b", "alice", "bob", 100, "test trade");
        assertThat(result.success()).isTrue();
        assertThat(result.transaction().sourceAmount()).isEqualTo(100);
        assertThat(result.transaction().targetAmount()).isEqualTo(150);
        assertThat(result.transaction().appliedRate()).isEqualTo(1.5);
    }

    @Test void exchange_fails_no_rate() {
        var result = exchange.exchange("zone-a", "zone-b", "alice", "bob", 100, null);
        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("No exchange rate");
    }

    @Test void exchange_fails_negative_amount() {
        exchange.setRate("zone-a", "zone-b", 1.0, null);
        var result = exchange.exchange("zone-a", "zone-b", "alice", "bob", -10, null);
        assertThat(result.success()).isFalse();
    }

    @Test void transactions_for_entity() {
        exchange.setRate("zone-a", "zone-b", 1.0, null);
        exchange.exchange("zone-a", "zone-b", "alice", "bob", 100, null);
        exchange.exchange("zone-a", "zone-b", "carol", "alice", 50, null);

        assertThat(exchange.transactionsFor("alice")).hasSize(2);
        assertThat(exchange.transactionsFor("bob")).hasSize(1);
    }

    @Test void transactions_between_zones() {
        exchange.setRate("zone-a", "zone-b", 1.0, null);
        exchange.setRate("zone-b", "zone-c", 1.0, null);
        exchange.exchange("zone-a", "zone-b", "alice", "bob", 100, null);
        exchange.exchange("zone-b", "zone-c", "bob", "carol", 50, null);

        assertThat(exchange.transactionsBetweenZones("zone-a", "zone-b")).hasSize(1);
        assertThat(exchange.transactionsBetweenZones("zone-b", "zone-c")).hasSize(1);
    }

    @Test void net_flow() {
        exchange.setRate("zone-a", "zone-b", 1.0, null);
        exchange.exchange("zone-a", "zone-b", "alice", "bob", 100, null);
        exchange.exchange("zone-a", "zone-b", "carol", "dave", 50, null);

        assertThat(exchange.netFlow("zone-a", "zone-b")).isEqualTo(150);
    }

    @Test void active_rates() {
        exchange.setRate("zone-a", "zone-b", 1.0, null);
        exchange.setRate("zone-b", "zone-c", 2.0, null);
        exchange.setRate("zone-c", "zone-d", 0.5, Instant.now().minusSeconds(1)); // expired

        assertThat(exchange.activeRates()).hasSize(2);
    }

    @Test void transaction_count() {
        assertThat(exchange.transactionCount()).isEqualTo(0);
        exchange.setRate("zone-a", "zone-b", 1.0, null);
        exchange.exchange("zone-a", "zone-b", "alice", "bob", 100, null);
        assertThat(exchange.transactionCount()).isEqualTo(1);
    }
}
