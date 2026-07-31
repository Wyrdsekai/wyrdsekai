package org.wyrdsekai.core.memory;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the LLM-output JSON parser. The actual inference call is mocked
 * out by these tests — we exercise {@link EntityExtractor#parse(String)} which
 * handles code fences, surrounding prose, and malformed JSON gracefully.
 */
class EntityExtractorTest {

    @Test
    void parse_plain_json() {
        var json = "{\"entities\":[{\"type\":\"pet\",\"role\":\"name\",\"value\":\"Mochi\"}],"
                + "\"relations\":[{\"subject\":\"Mochi\",\"predicate\":\"is_a\",\"object\":\"cat\"}]}";
        var result = EntityExtractor.parse(json);

        assertThat(result.entities()).hasSize(1);
        assertThat(result.entities().getFirst().type()).isEqualTo("pet");
        assertThat(result.entities().getFirst().role()).isEqualTo("name");
        assertThat(result.entities().getFirst().value()).isEqualTo("Mochi");

        assertThat(result.relations()).hasSize(1);
        assertThat(result.relations().getFirst().predicate()).isEqualTo("is_a");
    }

    @Test
    void parse_code_fenced_json() {
        var wrapped = "```json\n"
                + "{\"entities\":[{\"type\":\"allergy\",\"role\":\"food\",\"value\":\"cashews\"}],"
                + "\"relations\":[]}\n"
                + "```";
        var result = EntityExtractor.parse(wrapped);
        assertThat(result.entities()).hasSize(1);
        assertThat(result.entities().getFirst().value()).isEqualTo("cashews");
    }

    @Test
    void parse_with_trailing_prose() {
        var noisy = "Here is the extraction:\n"
                + "{\"entities\":[{\"type\":\"location\",\"role\":\"hometown\",\"value\":\"Portland\"}],"
                + "\"relations\":[]}\n"
                + "Hope that helps!";
        var result = EntityExtractor.parse(noisy);
        assertThat(result.entities()).hasSize(1);
        assertThat(result.entities().getFirst().value()).isEqualTo("Portland");
    }

    @Test
    void parse_multiple_entities() {
        var json = "{\"entities\":["
                + "{\"type\":\"pet\",\"role\":\"name\",\"value\":\"Mochi\"},"
                + "{\"type\":\"pet\",\"role\":\"type\",\"value\":\"cat\"}"
                + "],\"relations\":[]}";
        var result = EntityExtractor.parse(json);
        assertThat(result.entities()).hasSize(2);
        assertThat(result.entities()).extracting(EntityExtractor.EntityRecord::value)
                .containsExactly("Mochi", "cat");
    }

    @Test
    void parse_normalizes_type_to_lowercase() {
        var json = "{\"entities\":[{\"type\":\"PET\",\"role\":\"Name\",\"value\":\"Mochi\"}],"
                + "\"relations\":[]}";
        var result = EntityExtractor.parse(json);
        assertThat(result.entities().getFirst().type()).isEqualTo("pet");
        assertThat(result.entities().getFirst().role()).isEqualTo("name");
        // value is case-preserved for names
        assertThat(result.entities().getFirst().value()).isEqualTo("Mochi");
    }

    @Test
    void parse_empty_arrays() {
        var json = "{\"entities\":[],\"relations\":[]}";
        var result = EntityExtractor.parse(json);
        assertThat(result.isEmpty()).isTrue();
    }

    @Test
    void parse_malformed_returns_empty() {
        var result = EntityExtractor.parse("not json at all");
        assertThat(result.isEmpty()).isTrue();
    }

    @Test
    void parse_null_returns_empty() {
        assertThat(EntityExtractor.parse(null).isEmpty()).isTrue();
    }

    @Test
    void parse_missing_value_skipped() {
        var json = "{\"entities\":[{\"type\":\"pet\",\"role\":\"name\"}],\"relations\":[]}";
        var result = EntityExtractor.parse(json);
        assertThat(result.entities()).isEmpty();
    }

    @Test
    void parse_nullable_role() {
        var json = "{\"entities\":[{\"type\":\"family\",\"role\":null,\"value\":\"sister\"}],"
                + "\"relations\":[]}";
        var result = EntityExtractor.parse(json);
        assertThat(result.entities()).hasSize(1);
        assertThat(result.entities().getFirst().role()).isNull();
    }

    @Test
    void parse_skips_invalid_relations() {
        var json = "{\"entities\":[],\"relations\":["
                + "{\"subject\":\"\",\"predicate\":\"is_a\",\"object\":\"cat\"},"
                + "{\"subject\":\"Mochi\",\"predicate\":\"is_a\",\"object\":\"cat\"}"
                + "]}";
        var result = EntityExtractor.parse(json);
        assertThat(result.relations()).hasSize(1);
        assertThat(result.relations().getFirst().subject()).isEqualTo("Mochi");
    }
}
