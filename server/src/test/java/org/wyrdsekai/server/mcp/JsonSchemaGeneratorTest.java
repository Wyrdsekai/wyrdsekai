package org.wyrdsekai.server.mcp;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JsonSchemaGeneratorTest {

    // Test records for schema generation
    record SimpleRecord(String name, int age, boolean active) {}
    record NestedRecord(String id, SimpleRecord nested, List<String> tags) {}
    enum Color { RED, GREEN, BLUE }
    record WithEnum(String name, Color color) {}

    @Test void simpleRecord_generates_object_schema() {
        var schema = JsonSchemaGenerator.generateSchema(SimpleRecord.class);
        assertThat(schema.get("type").asText()).isEqualTo("object");
        assertThat(schema.get("title").asText()).isEqualTo("SimpleRecord");
        assertThat(schema.get("properties").has("name")).isTrue();
        assertThat(schema.get("properties").get("name").get("type").asText()).isEqualTo("string");
        assertThat(schema.get("properties").get("age").get("type").asText()).isEqualTo("integer");
        assertThat(schema.get("properties").get("active").get("type").asText()).isEqualTo("boolean");
    }

    @Test void record_includes_required_fields() {
        var schema = JsonSchemaGenerator.generateSchema(SimpleRecord.class);
        var required = schema.get("required");
        assertThat(required.size()).isEqualTo(3);
    }

    @Test void nestedRecord_generates_nested_schema() {
        var schema = JsonSchemaGenerator.generateSchema(NestedRecord.class);
        var nestedProp = schema.get("properties").get("nested");
        assertThat(nestedProp.get("type").asText()).isEqualTo("object");
        var tagsProp = schema.get("properties").get("tags");
        assertThat(tagsProp.get("type").asText()).isEqualTo("array");
    }

    @Test void enum_generates_string_enum_schema() {
        var schema = JsonSchemaGenerator.generateSchema(Color.class);
        assertThat(schema.get("type").asText()).isEqualTo("string");
        var enumValues = schema.get("enum");
        assertThat(enumValues.size()).isEqualTo(3);
    }

    @Test void withEnum_generates_enum_property() {
        var schema = JsonSchemaGenerator.generateSchema(WithEnum.class);
        var colorProp = schema.get("properties").get("color");
        assertThat(colorProp.get("type").asText()).isEqualTo("string");
        assertThat(colorProp.get("enum").size()).isEqualTo(3);
    }

    @Test void schema_has_meta() {
        var schema = JsonSchemaGenerator.generateSchema(SimpleRecord.class);
        assertThat(schema.has("$schema")).isTrue();
    }
}
