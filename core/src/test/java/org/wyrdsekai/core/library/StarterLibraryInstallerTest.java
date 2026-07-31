package org.wyrdsekai.core.library;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** — locale-aware starter-pack selection. */
class StarterLibraryInstallerTest {

    @Test
    void english_household_gets_tier0_plus_english_tier1_with_wikipedia_last() {
        var packs = StarterLibraryInstaller.selectStarterPacks(Set.of("en"));
        var names = packs.stream().map(KnowledgePackRegistry.PackInfo::name).toList();
        assertThat(names).contains("jmdict", "gutenberg-classics", "simple-wikipedia");
        assertThat(names.indexOf("simple-wikipedia"))
            .as("the 500MB pull goes last so dictionaries land first")
            .isEqualTo(names.size() - 1);
    }

    @Test
    void english_household_also_gets_tier2_essential_packs() {
        var names = StarterLibraryInstaller.selectStarterPacks(Set.of("en")).stream()
            .map(KnowledgePackRegistry.PackInfo::name).toList();
        // Tier-2 packs the registry flags essential ship by default too — not just Tier ≤ 1.
        assertThat(names).contains(
            "medquad", "stackexchange-cooking", "stackexchange-diy", "stackexchange-gardening");
        // ...but rec-only / non-essential Tier-2 packs stay opt-in.
        assertThat(names).doesNotContain("stackexchange-money", "wikibooks-en", "python-docs");
    }

    @Test
    void pull_weight_parses_free_text_sizes() {
        assertThat(StarterLibraryInstaller.pullWeightMb(packWithSize("~520 MB download, ~200 MB indexed")))
            .isEqualTo(520);
        assertThat(StarterLibraryInstaller.pullWeightMb(packWithSize("~2 GB"))).isEqualTo(2048);
        assertThat(StarterLibraryInstaller.pullWeightMb(packWithSize(null))).isEqualTo(Integer.MAX_VALUE);
    }

    private static KnowledgePackRegistry.PackInfo packWithSize(String size) {
        return new KnowledgePackRegistry.PackInfo(
            "x", "X", "d", List.of(), null, null, null, List.of(), size, null);
    }

    @Test
    void japanese_household_still_gets_the_dictionary_and_english_floor() {
        var langs = StarterLibraryInstaller.parseLangs("ja");
        assertThat(langs).containsExactlyInAnyOrder("ja", "en"); // en is always the floor
        var names = StarterLibraryInstaller.selectStarterPacks(langs).stream()
            .map(KnowledgePackRegistry.PackInfo::name).toList();
        assertThat(names).contains("jmdict", "simple-wikipedia");
    }

    @Test
    void truthy_flag_values() {
        assertThat(StarterLibraryInstaller.isTruthy("true")).isTrue();
        assertThat(StarterLibraryInstaller.isTruthy("pending")).isTrue();
        assertThat(StarterLibraryInstaller.isTruthy("false")).isFalse();
        assertThat(StarterLibraryInstaller.isTruthy(null)).isFalse();
    }
}
