package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LexiconServiceTest {

    private LexiconService service;

    @BeforeEach void setUp() {
        service = new LexiconService();
    }

    @Test void registerTerm_creates_entry() {
        var entry = service.registerTerm("wyrd-wave", "a synchronized pulse of agent activity",
            "agent-1", "nexus");
        assertThat(entry.term()).isEqualTo("wyrd-wave");
        assertThat(entry.usageCount()).isEqualTo(1);
        assertThat(service.termCount()).isEqualTo(1);
    }

    @Test void registerTerm_duplicate_increments_usage() {
        service.registerTerm("wyrd-wave", "definition 1", "agent-1", "nexus");
        var entry = service.registerTerm("wyrd-wave", "definition 2", "agent-2", "bridge");
        assertThat(entry.usageCount()).isEqualTo(2);
        assertThat(service.termCount()).isEqualTo(1); // Still one term
    }

    @Test void updateUsage_increases_adoption() {
        service.registerTerm("spark", "a moment of insight", "agent-1", "nexus");
        service.updateUsage("spark", "agent-2");
        service.updateUsage("spark", "agent-3");
        var entry = service.lookup("spark").orElseThrow();
        assertThat(entry.adoptedBy()).hasSize(3);
        assertThat(entry.coherenceScore()).isGreaterThan(0.5);
    }

    @Test void search_finds_matching_terms() {
        service.registerTerm("bright-thread", "a strong CRDT strand", "agent-1", "loom");
        service.registerTerm("dark-thread", "a corrupted strand", "agent-2", "loom");
        service.registerTerm("nexus-pulse", "heartbeat of the hub", "agent-3", "nexus");
        var results = service.search("thread");
        assertThat(results).hasSize(2);
    }

    @Test void topTerms_ordered_by_usage() {
        service.registerTerm("rare-term", "used once", "agent-1", "nexus");
        service.registerTerm("popular-term", "used often", "agent-1", "nexus");
        service.updateUsage("popular-term", "agent-2");
        service.updateUsage("popular-term", "agent-3");
        var top = service.topTerms(2);
        assertThat(top.get(0).term()).isEqualTo("popular-term");
    }

    @Test void widelyAdopted_filters_by_adopter_count() {
        service.registerTerm("niche", "used by one", "agent-1", "nexus");
        service.registerTerm("common", "used by many", "agent-1", "nexus");
        service.updateUsage("common", "agent-2");
        service.updateUsage("common", "agent-3");
        var shared = service.widelyAdopted(3);
        assertThat(shared).hasSize(1);
        assertThat(shared.get(0).term()).isEqualTo("common");
    }

    @Test void lookup_normalizes_case() {
        service.registerTerm("UPPER", "uppercase term", "agent-1", "nexus");
        assertThat(service.lookup("upper")).isPresent();
        assertThat(service.lookup("UPPER")).isPresent();
    }

    @Test void calibrationStatus_default_uncalibrated() {
        assertThat(service.calibrationStatus())
            .isEqualTo(LexiconService.CalibrationStatus.UNCALIBRATED);
    }

    @Test void describe_shows_summary() {
        service.registerTerm("test-word", "a test", "agent-1", "nexus");
        var desc = service.describe();
        assertThat(desc).contains("Lexicon");
        assertThat(desc).contains("test-word");
    }
}
