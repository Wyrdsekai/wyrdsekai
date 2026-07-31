package org.wyrdsekai.core.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.test.TestDb;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class WorldDnaServiceTest {

    private WorldDnaService service;

    @BeforeEach void setUp() {
        var jdbcUrl = TestDb.createInMemory();
        service = new WorldDnaService(jdbcUrl);
    }

    @Test void record_and_query() {
        service.record("room_design", "{\"name\":\"Gallery\"}", "gallery", "wyrd", "foundation");
        var patterns = service.queryTopPatterns("room_design", "foundation", 10);
        assertThat(patterns).hasSize(1);
        assertThat(patterns.getFirst().patternType()).isEqualTo("room_design");
        assertThat(patterns.getFirst().patternData()).contains("Gallery");
    }

    @Test void queryTopPatterns_ordered_by_score() {
        var id1 = service.record("room_design", "low", "r1", "a1", "z1");
        var id2 = service.record("room_design", "high", "r2", "a1", "z1");
        service.updateScore(id2, 0.9);
        service.updateScore(id1, 0.1);

        var patterns = service.queryTopPatterns("room_design", "z1", 10);
        assertThat(patterns).hasSize(2);
        assertThat(patterns.get(0).patternData()).isEqualTo("high");
        assertThat(patterns.get(1).patternData()).isEqualTo("low");
    }

    @Test void queryTopPatterns_filtered_by_zone() {
        service.record("room_design", "z1-pattern", "r1", "a1", "zone1");
        service.record("room_design", "z2-pattern", "r2", "a1", "zone2");

        var z1 = service.queryTopPatterns("room_design", "zone1", 10);
        assertThat(z1).hasSize(1);
        assertThat(z1.getFirst().patternData()).isEqualTo("z1-pattern");
    }

    @Test void queryTopPatterns_respects_limit() {
        for (int i = 0; i < 10; i++) {
            service.record("room_design", "pattern-" + i, "r" + i, "a1", "z1");
        }
        var patterns = service.queryTopPatterns("room_design", "z1", 3);
        assertThat(patterns).hasSize(3);
    }

    @Test void updateScore() {
        var id = service.record("room_design", "test", "r1", "a1", "z1");
        service.updateScore(id, 0.75);
        var patterns = service.queryTopPatterns("room_design", "z1", 10);
        assertThat(patterns.getFirst().outcomeScore()).isEqualTo(0.75);
    }

    @Test void incrementUsage() {
        var id = service.record("room_design", "test", "r1", "a1", "z1");
        service.incrementUsage(id);
        service.incrementUsage(id);
        var patterns = service.queryTopPatterns("room_design", "z1", 10);
        assertThat(patterns.getFirst().usageCount()).isEqualTo(2);
        assertThat(patterns.getFirst().lastUsedAt()).isNotNull();
    }

    @Test void countByType() {
        service.record("room_design", "a", "r1", "a1", "z1");
        service.record("room_design", "b", "r2", "a1", "z1");
        service.record("agent_strategy", "c", "r3", "a1", "z1");
        assertThat(service.countByType("room_design")).isEqualTo(2);
        assertThat(service.countByType("agent_strategy")).isEqualTo(1);
    }

    @Test void countAll() {
        service.record("room_design", "a", "r1", "a1", "z1");
        service.record("agent_strategy", "b", "r2", "a1", "z1");
        assertThat(service.countAll()).isEqualTo(2);
    }

    @Test void record_different_types_coexist() {
        service.record("room_design", "room", "r1", "a1", "z1");
        service.record("agent_strategy", "strategy", "r1", "a1", "z1");
        assertThat(service.countAll()).isEqualTo(2);
        assertThat(service.queryTopPatterns("room_design", "z1", 10)).hasSize(1);
        assertThat(service.queryTopPatterns("agent_strategy", "z1", 10)).hasSize(1);
    }

    // --- Outcome tracking (§27) ---

    @Test void recordOutcome_positive_increases_score() {
        var id = service.record("room_design", "test", "r1", "a1", "z1");
        // Initial score is 0.0 (default). Positive outcome pushes toward 1.0.
        service.recordOutcome(id, true);
        var patterns = service.queryTopPatterns("room_design", "z1", 10);
        assertThat(patterns.getFirst().outcomeScore()).isGreaterThan(0.0);
        assertThat(patterns.getFirst().usageCount()).isEqualTo(1);
    }

    @Test void recordOutcome_negative_keeps_score_low() {
        var id = service.record("room_design", "test", "r1", "a1", "z1");
        service.recordOutcome(id, false);
        var patterns = service.queryTopPatterns("room_design", "z1", 10);
        assertThat(patterns.getFirst().outcomeScore()).isEqualTo(0.0);
    }

    @Test void recordOutcome_multiple_converges() {
        var id = service.record("room_design", "test", "r1", "a1", "z1");
        // 5 positive outcomes should push score significantly above 0.5
        for (int i = 0; i < 5; i++) {
            service.recordOutcome(id, true);
        }
        var patterns = service.queryTopPatterns("room_design", "z1", 10);
        assertThat(patterns.getFirst().outcomeScore()).isGreaterThan(0.5);
    }

    @Test void queryByUsage_ordered_by_count() {
        var id1 = service.record("room_design", "popular", "r1", "a1", "z1");
        var id2 = service.record("room_design", "unpopular", "r2", "a1", "z1");
        service.incrementUsage(id1);
        service.incrementUsage(id1);
        service.incrementUsage(id1);
        service.incrementUsage(id2);

        var patterns = service.queryByUsage("room_design", 10);
        assertThat(patterns).hasSize(2);
        assertThat(patterns.get(0).patternData()).isEqualTo("popular");
    }

    @Test void queryRecentlyUsed_returns_recent_only() {
        var id1 = service.record("room_design", "recent", "r1", "a1", "z1");
        service.record("room_design", "never-used", "r2", "a1", "z1");
        service.incrementUsage(id1);

        var patterns = service.queryRecentlyUsed(3600, 10); // within last hour
        assertThat(patterns).hasSize(1);
        assertThat(patterns.getFirst().patternData()).isEqualTo("recent");
    }
}
