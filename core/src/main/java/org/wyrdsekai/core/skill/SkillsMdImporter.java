package org.wyrdsekai.core.skill;

import org.wyrdsekai.core.library.OutputSanitizer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Imports skills.sh SKILL.md files into the Wyrdsekai skill system.
 *
 * Parses YAML frontmatter → {@link SkillsMdFormat} → {@link SkillItemCodec.SkillDefinition}
 * with tier PROMPT. Imported prompts pass through OutputSanitizer.
 */
public final class SkillsMdImporter {

    private SkillsMdImporter() {}

    private static final Pattern FRONTMATTER = Pattern.compile(
        "\\A---\\s*\\n(.*?)\\n---\\s*\\n?(.*)", Pattern.DOTALL);

    /**
     * Parse a SKILL.md string into a SkillsMdFormat.
     *
     * @param content Full SKILL.md text
     * @return Parsed format, or null if not valid SKILL.md
     */
    public static SkillsMdFormat parse(String content) {
        if (content == null || content.isBlank()) return null;

        var matcher = FRONTMATTER.matcher(content);
        if (!matcher.matches()) return null;

        String yamlSection = matcher.group(1);
        String body = matcher.group(2).strip();

        // Simple YAML parser (no external YAML lib — just key: value pairs and lists)
        var yaml = parseSimpleYaml(yamlSection);

        String name = yaml.getOrDefault("name", "").toString();
        String description = yaml.getOrDefault("description", "").toString();

        var params = new ArrayList<SkillsMdFormat.SkillsMdParam>();
        if (yaml.containsKey("params") && yaml.get("params") instanceof List<?> paramList) {
            for (var item : paramList) {
                if (item instanceof Map<?, ?> paramMap) {
                    params.add(new SkillsMdFormat.SkillsMdParam(
                        str(paramMap, "name"),
                        str(paramMap, "type", "string"),
                        str(paramMap, "description"),
                        "true".equals(str(paramMap, "required"))
                    ));
                }
            }
        }

        var metadata = new LinkedHashMap<String, String>();
        for (var entry : yaml.entrySet()) {
            if (!"name".equals(entry.getKey()) && !"description".equals(entry.getKey())
                    && !"params".equals(entry.getKey())) {
                metadata.put(entry.getKey(), String.valueOf(entry.getValue()));
            }
        }

        if (name.isBlank()) return null;

        return new SkillsMdFormat(name, description, params, body, metadata);
    }

    /**
     * Convert a parsed SkillsMdFormat to a SkillItemCodec.SkillDefinition
     * suitable for storage as a SoulItem.
     */
    public static SkillItemCodec.SkillDefinition toSkillDefinition(SkillsMdFormat format) {
        if (format == null) return null;

        var params = format.params().stream()
            .map(p -> new SkillItemCodec.Param(p.name(), p.type(), p.description(), p.required()))
            .toList();

        return SkillItemCodec.create(
            "prompt",  // runtime = prompt (instruction-based)
            format.hasInstructions() ? format.instructions() : "",
            params,
            format.description(),
            null, null);
    }

    /**
     * Import a SKILL.md and optionally sanitize the instructions.
     *
     * @param content   SKILL.md text
     * @param sanitizer Output sanitizer (nullable)
     * @return Import result, or null on parse failure
     */
    public static ImportResult importSkill(String content, OutputSanitizer sanitizer) {
        var format = parse(content);
        if (format == null) return null;

        String instructions = format.instructions();
        boolean sanitized = false;

        if (sanitizer != null && instructions != null && !instructions.isBlank()) {
            var result = sanitizer.sanitize("skills.sh-import", instructions);
            if (!result.clean()) {
                instructions = result.sanitizedResponse();
                sanitized = true;
            }
        }

        var finalFormat = sanitized
            ? new SkillsMdFormat(format.name(), format.description(),
                format.params(), instructions, format.metadata())
            : format;

        var def = toSkillDefinition(finalFormat);
        return new ImportResult(finalFormat, def, sanitized);
    }

    /** Result of an import operation. */
    public record ImportResult(
        SkillsMdFormat format,
        SkillItemCodec.SkillDefinition definition,
        boolean wasSanitized
    ) {}

    // --- Simple YAML parser ---

    /**
     * Parse simple YAML (key: value, list items with - prefix).
     * Not a full YAML parser — handles the skills.sh frontmatter format.
     */
    static Map<String, Object> parseSimpleYaml(String yaml) {
        var result = new LinkedHashMap<String, Object>();
        if (yaml == null) return result;

        String currentKey = null;
        List<Object> currentList = null;
        Map<String, String> currentMap = null;

        for (String line : yaml.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;

            // Indented list item with key-value pairs
            if (line.startsWith("    - ") || line.startsWith("  - ")) {
                String itemText = trimmed.substring(2).strip();
                if (currentList == null && currentKey != null) {
                    currentList = new ArrayList<>();
                    result.put(currentKey, currentList);
                }
                if (currentList != null) {
                    if (itemText.contains(": ")) {
                        currentMap = new LinkedHashMap<>();
                        var kv = itemText.split(":\\s+", 2);
                        currentMap.put(kv[0].strip(), kv.length > 1 ? kv[1].strip() : "");
                        currentList.add(currentMap);
                    } else {
                        currentMap = null;
                        currentList.add(itemText);
                    }
                }
                continue;
            }

            // Continuation of map item
            if ((line.startsWith("      ") || line.startsWith("    "))
                    && currentMap != null && trimmed.contains(": ")) {
                var kv = trimmed.split(":\\s+", 2);
                currentMap.put(kv[0].strip(), kv.length > 1 ? kv[1].strip() : "");
                continue;
            }

            // Top-level key: value
            if (trimmed.contains(":")) {
                currentList = null;
                currentMap = null;
                var kv = trimmed.split(":\\s*", 2);
                currentKey = kv[0].strip();
                String value = kv.length > 1 ? kv[1].strip() : "";
                if (!value.isEmpty()) {
                    result.put(currentKey, value);
                    currentKey = kv[0].strip(); // keep for possible list continuation
                } else {
                    // Value will come as indented list items
                    result.put(currentKey, "");
                }
            }
        }

        return result;
    }

    private static String str(Map<?, ?> map, String key) {
        return str(map, key, "");
    }

    private static String str(Map<?, ?> map, String key, String defaultVal) {
        var val = map.get(key);
        return val != null ? val.toString() : defaultVal;
    }
}
