package org.wyrdsekai.core.skill;

/**
 * Exports Wyrdsekai skills as skills.sh SKILL.md format.
 *
 * Converts workbench/native skills into the Vercel cross-agent standard
 * (YAML frontmatter + Markdown body).
 */
public final class SkillsMdExporter {

    private SkillsMdExporter() {}

    /**
     * Export a skill item (from SoulItem text) as SKILL.md.
     *
     * @param name       Skill name
     * @param definition Decoded skill definition
     * @return SKILL.md formatted string
     */
    public static String export(String name, SkillItemCodec.SkillDefinition definition) {
        if (definition == null) return null;

        var sb = new StringBuilder();
        sb.append("---\n");
        sb.append("name: ").append(name != null ? name : "unnamed").append("\n");

        if (definition.description() != null && !definition.description().isBlank()) {
            sb.append("description: ").append(definition.description()).append("\n");
        }

        if (definition.params() != null && !definition.params().isEmpty()) {
            sb.append("params:\n");
            for (var param : definition.params()) {
                sb.append("  - name: ").append(param.name()).append("\n");
                sb.append("    type: ").append(param.type() != null ? param.type() : "string").append("\n");
                if (param.description() != null && !param.description().isBlank()) {
                    sb.append("    description: ").append(param.description()).append("\n");
                }
                sb.append("    required: ").append(param.required()).append("\n");
            }
        }

        sb.append("---\n\n");

        // Body: either the code (for executable skills) or instructions
        if (definition.code() != null && !definition.code().isBlank()) {
            if ("prompt".equals(definition.runtime())) {
                // Prompt skills: instructions as-is
                sb.append(definition.code());
            } else {
                // Code skills: wrap in code block
                sb.append("# Implementation\n\n");
                sb.append("Runtime: ").append(definition.runtime()).append("\n\n");
                sb.append("```").append(runtimeToLang(definition.runtime())).append("\n");
                sb.append(definition.code()).append("\n");
                sb.append("```\n");
            }
        }

        return sb.toString();
    }

    /**
     * Export a SkillsMdFormat back to SKILL.md (roundtrip).
     */
    public static String export(SkillsMdFormat format) {
        if (format == null) return null;

        var sb = new StringBuilder();
        sb.append("---\n");
        sb.append("name: ").append(format.name()).append("\n");

        if (format.description() != null && !format.description().isBlank()) {
            sb.append("description: ").append(format.description()).append("\n");
        }

        if (format.params() != null && !format.params().isEmpty()) {
            sb.append("params:\n");
            for (var param : format.params()) {
                sb.append("  - name: ").append(param.name()).append("\n");
                sb.append("    type: ").append(param.type()).append("\n");
                if (param.description() != null && !param.description().isBlank()) {
                    sb.append("    description: ").append(param.description()).append("\n");
                }
                sb.append("    required: ").append(param.required()).append("\n");
            }
        }

        // Metadata
        if (format.metadata() != null) {
            for (var entry : format.metadata().entrySet()) {
                sb.append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
            }
        }

        sb.append("---\n\n");

        if (format.hasInstructions()) {
            sb.append(format.instructions());
        }

        return sb.toString();
    }

    private static String runtimeToLang(String runtime) {
        if (runtime == null) return "";
        return switch (runtime) {
            case "graaljs", "javascript" -> "javascript";
            case "python" -> "python";
            case "shell", "bash" -> "bash";
            default -> runtime;
        };
    }
}
