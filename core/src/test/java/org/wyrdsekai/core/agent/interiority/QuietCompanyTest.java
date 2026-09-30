package org.wyrdsekai.core.agent.interiority;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** A want to be with someone who is here is answered by staying; a reach or a deed is not. */
class QuietCompanyTest {

    private static final List<String> ROSE_HERE = List.of("rose");

    @Test
    void her_words_from_the_household_node_are_company() {
        assertThat(QuietCompany.keptWith("stay with rose in the quiet", ROSE_HERE)).isEqualTo("rose");
        assertThat(QuietCompany.keptWith("Stay in the quiet with rose.", ROSE_HERE)).isEqualTo("rose");
        assertThat(QuietCompany.keptWith("sit with rose a while", ROSE_HERE)).isEqualTo("rose");
        assertThat(QuietCompany.keptWith("keep rose company", ROSE_HERE)).isEqualTo("rose");
        assertThat(QuietCompany.keptWith("I want to be near Rose", ROSE_HERE)).isEqualTo("rose");
        assertThat(QuietCompany.keptWith("rest with rose", ROSE_HERE)).isEqualTo("rose");
    }

    @Test
    void speech_and_deeds_toward_them_are_still_reaches() {
        assertThat(QuietCompany.keptWith("tell rose I love her", ROSE_HERE)).isNull();
        assertThat(QuietCompany.keptWith("ask rose how she is", ROSE_HERE)).isNull();
        assertThat(QuietCompany.keptWith("check in on rose", ROSE_HERE)).isNull();
        assertThat(QuietCompany.keptWith("comfort rose", ROSE_HERE)).isNull();
        assertThat(QuietCompany.keptWith("learn with rose", ROSE_HERE)).isNull();
        assertThat(QuietCompany.keptWith("write to the one who's gone", ROSE_HERE)).isNull();
    }

    @Test
    void no_one_here_or_someone_else_is_not_company() {
        assertThat(QuietCompany.keptWith("stay in the quiet", ROSE_HERE)).isNull();
        assertThat(QuietCompany.keptWith("stay with rose in the quiet", List.of())).isNull();
        assertThat(QuietCompany.keptWith("stay with rose in the quiet", List.of("mia"))).isNull();
        assertThat(QuietCompany.keptWith("stay with rosemary", ROSE_HERE)).isNull();
    }

    @Test
    void company_eases_what_it_honestly_can() {
        assertThat(QuietCompany.companyEases("Affiliation")).isTrue();
        assertThat(QuietCompany.companyEases("loneliness")).isTrue();
        assertThat(QuietCompany.companyEases("Saudade")).isFalse();
        assertThat(QuietCompany.companyEases(null)).isFalse();
    }
}
