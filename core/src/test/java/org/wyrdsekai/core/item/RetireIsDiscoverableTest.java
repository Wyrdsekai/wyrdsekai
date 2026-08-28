package org.wyrdsekai.core.item;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A command nobody can find is a command that does not exist.
 *
 * <p>I wrote that sentence into the parser's comment when adding {@code retire} — and
 * then did not put it in {@code help}. The steward's question, 2026-08-20:
 * <i>"how would anyone know what to do? shouldn't it be in the help?"</i>
 *
 * <p>Same shape as everything else this week: the thing was built, and the join to where
 * a person looks for it was left out.
 */
class RetireIsDiscoverableTest {

    private static String catalog() throws Exception {
        var p = Path.of("scripts/i18n/en.json");
        if (!Files.exists(p)) p = Path.of("../scripts/i18n/en.json");
        return Files.readString(p);
    }

    @Test
    void the_shell_help_lists_it_next_to_the_verb_it_completes() throws Exception {
        var json = new ObjectMapper().readTree(catalog());
        var line = json.path("telnet.help_retire").asText("");
        assertThat(line).as("telnet/ssh help must mention retire").contains("retire <object>");
        assertThat(line).as("and say what it does").containsIgnoringCase("destroy");
    }

    @Test
    void the_ui_help_lists_it_and_its_aliases() throws Exception {
        var json = new ObjectMapper().readTree(catalog());
        var help = json.path("ui.help_commands").asText("");
        assertThat(help).contains("retire <object>");
        assertThat(help)
            .as("the aliases must be discoverable too, or people guess and fail")
            .contains("destroy")
            .contains("discard");
    }

    @Test
    void help_still_lists_the_verb_retire_complements() throws Exception {
        // Guard against a careless edit dropping drop while adding retire.
        var json = new ObjectMapper().readTree(catalog());
        assertThat(json.path("ui.help_commands").asText("")).contains("drop <object>");
        assertThat(json.path("telnet.help_drop").asText("")).contains("drop <object>");
    }

    @Test
    void the_catalog_is_still_valid_json() throws Exception {
        // Hand-editing a 900-line i18n file is exactly how a node fails to boot.
        assertThat(new ObjectMapper().readTree(catalog()).isObject()).isTrue();
    }
}
