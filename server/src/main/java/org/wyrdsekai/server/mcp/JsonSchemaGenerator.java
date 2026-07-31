package org.wyrdsekai.server.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.lang.reflect.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * JSON Schema generator for Java sealed interfaces and records (§29).
 * Generates JSON Schema compatible with MCP tool definitions.
 * Supports records, sealed interfaces, enums, and common Java types.
 */
public class JsonSchemaGenerator {

    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * Generate a JSON Schema for a Java class.
     * Supports records, sealed interfaces, enums, and basic types.
     */
    public static JsonNode generateSchema(Class<?> clazz) {
        var schema = mapper.createObjectNode();
        schema.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        populateSchema(clazz, schema);
        return schema;
    }

    /**
     * Generate schemas for all permitted subclasses of a sealed interface.
     * Returns a discriminated union (oneOf) schema.
     */
    public static JsonNode generateSealedSchema(Class<?> sealedInterface) {
        if (!sealedInterface.isSealed()) {
            return generateSchema(sealedInterface);
        }

        var schema = mapper.createObjectNode();
        schema.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        schema.put("title", sealedInterface.getSimpleName());

        var oneOf = schema.putArray("oneOf");
        for (var permitted : sealedInterface.getPermittedSubclasses()) {
            var subSchema = mapper.createObjectNode();
            populateSchema(permitted, subSchema);
            subSchema.put("title", permitted.getSimpleName());
            oneOf.add(subSchema);
        }

        return schema;
    }

    private static void populateSchema(Class<?> clazz, ObjectNode schema) {
        if (clazz.isRecord()) {
            populateRecordSchema(clazz, schema);
        } else if (clazz.isEnum()) {
            populateEnumSchema(clazz, schema);
        } else if (clazz.isSealed()) {
            populateSealedSchema(clazz, schema);
        } else {
            schema.put("type", javaTypeToJsonType(clazz));
        }
    }

    private static void populateRecordSchema(Class<?> recordClass, ObjectNode schema) {
        schema.put("type", "object");
        schema.put("title", recordClass.getSimpleName());

        var properties = schema.putObject("properties");
        var required = schema.putArray("required");

        for (var component : recordClass.getRecordComponents()) {
            var propSchema = mapper.createObjectNode();
            populateTypeSchema(component.getGenericType(), propSchema);
            properties.set(component.getName(), propSchema);
            required.add(component.getName());
        }
    }

    private static void populateEnumSchema(Class<?> enumClass, ObjectNode schema) {
        schema.put("type", "string");
        schema.put("title", enumClass.getSimpleName());
        var enumValues = schema.putArray("enum");
        for (var constant : enumClass.getEnumConstants()) {
            enumValues.add(constant.toString());
        }
    }

    private static void populateSealedSchema(Class<?> sealedClass, ObjectNode schema) {
        schema.put("title", sealedClass.getSimpleName());
        var oneOf = schema.putArray("oneOf");
        for (var permitted : sealedClass.getPermittedSubclasses()) {
            var subSchema = mapper.createObjectNode();
            populateSchema(permitted, subSchema);
            oneOf.add(subSchema);
        }
    }

    private static void populateTypeSchema(Type type, ObjectNode schema) {
        if (type instanceof Class<?> clazz) {
            if (clazz.isRecord()) {
                populateRecordSchema(clazz, schema);
            } else if (clazz.isEnum()) {
                populateEnumSchema(clazz, schema);
            } else {
                schema.put("type", javaTypeToJsonType(clazz));
            }
        } else if (type instanceof ParameterizedType pt) {
            var rawType = (Class<?>) pt.getRawType();
            if (List.class.isAssignableFrom(rawType) || Set.class.isAssignableFrom(rawType)) {
                schema.put("type", "array");
                var itemSchema = schema.putObject("items");
                var typeArg = pt.getActualTypeArguments()[0];
                populateTypeSchema(typeArg, itemSchema);
            } else if (Map.class.isAssignableFrom(rawType)) {
                schema.put("type", "object");
                var valueSchema = schema.putObject("additionalProperties");
                var typeArg = pt.getActualTypeArguments()[1];
                populateTypeSchema(typeArg, valueSchema);
            } else if (Optional.class.isAssignableFrom(rawType)) {
                var typeArg = pt.getActualTypeArguments()[0];
                populateTypeSchema(typeArg, schema);
            } else {
                schema.put("type", "object");
            }
        } else {
            schema.put("type", "string");
        }
    }

    private static String javaTypeToJsonType(Class<?> clazz) {
        if (clazz == String.class || clazz == char.class || clazz == Character.class) {
            return "string";
        } else if (clazz == int.class || clazz == Integer.class
                || clazz == long.class || clazz == Long.class
                || clazz == short.class || clazz == Short.class
                || clazz == byte.class || clazz == Byte.class) {
            return "integer";
        } else if (clazz == double.class || clazz == Double.class
                || clazz == float.class || clazz == Float.class) {
            return "number";
        } else if (clazz == boolean.class || clazz == Boolean.class) {
            return "boolean";
        } else if (clazz == byte[].class) {
            return "string"; // base64-encoded
        } else if (Instant.class.isAssignableFrom(clazz)) {
            return "string"; // ISO-8601
        } else {
            return "object";
        }
    }
}
