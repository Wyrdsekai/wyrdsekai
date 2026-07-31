package org.wyrdsekai.core.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Parses OpenClaw SKILL.md files into SkillDefinition records.
 *
 * <p>Two formats are recognized:</p>
 * <ul>
 *   <li><b>Modern (ClawHub / agentskills.io, 2026)</b> — YAML frontmatter
 *       ({@code name}, {@code description}, {@code version}, optional
 *       {@code metadata.openclaw.requires.bins/env}) followed by freeform
 *       markdown instructions. Imports as ONE PROMPT-tier skill whose
 *       instructions are the body — the same shape Hermes Agent
 *       (agentskills.io) skills use. ClawHub publishes under MIT-0.</li>
 *   <li><b>Legacy (structured)</b> — {@code ## tool} headings with
 *       {@code **Description**:} lines and parameter tables. Imports as
 *       CLI-tier skills, one per heading, origin "openclaw/&lt;name&gt;",
 *       license MIT.</li>
 * </ul>
 */
public class SkillMdImporter {

    private static final Pattern TOOL_NAME_PATTERN = Pattern.compile("^##\\s+(.+)$");
    private static final Pattern DESCRIPTION_PATTERN = Pattern.compile("^\\*\\*Description\\*\\*\\s*:?\\s*(.+)$");
    private static final Pattern PARAM_PATTERN = Pattern.compile(
        "^\\|\\s*`?(\\w+)`?\\s*\\|\\s*`?(\\w+)`?\\s*\\|\\s*(true|false|yes|no)\\s*\\|\\s*(.+?)\\s*\\|$"
    );
    private static final Pattern FRONTMATTER = Pattern.compile(
        "\\A---\\s*\\n(.*?)\\n---\\s*\\n?(.*)", Pattern.DOTALL);
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    /**
     * A modern frontmatter SKILL.md, parsed. {@code definition} is for the
     * {@link SkillRegistry}; {@code instructions} is the markdown body to
     * register with {@link PromptSkillExecutor}; {@code requiredBins} /
     * {@code requiredEnv} are availability preconditions from
     * {@code metadata.openclaw.requires}.
     */
    public record ModernSkill(SkillDefinition definition, String instructions,
                               String version, List<String> requiredBins,
                               List<String> requiredEnv) {}

    /**
     * Parse a modern frontmatter SKILL.md. Returns empty when the content
     * has no frontmatter or no {@code name} — callers fall back to the
     * legacy structured parse.
     */
    @SuppressWarnings("unchecked")
    public Optional<ModernSkill> importModern(String content, String room) {
        if (content == null) return Optional.empty();
        var matcher = FRONTMATTER.matcher(content);
        if (!matcher.matches()) return Optional.empty();

        Map<String, Object> front;
        try {
            front = YAML.readValue(matcher.group(1), Map.class);
        } catch (IOException e) {
            return Optional.empty();
        }
        if (front == null) return Optional.empty();

        var name = str(front.get("name"));
        if (name == null || name.isBlank()) return Optional.empty();
        var description = str(front.get("description"));
        var version = str(front.get("version"));
        var body = matcher.group(2).strip();

        // metadata.openclaw block (documented aliases: clawdbot, clawdis)
        var requiredBins = new ArrayList<String>();
        var requiredEnv = new ArrayList<String>();
        String primaryEnv = null;
        if (front.get("metadata") instanceof Map<?, ?> metadata) {
            Object oc = metadata.get("openclaw");
            if (oc == null) oc = metadata.get("clawdbot");
            if (oc == null) oc = metadata.get("clawdis");
            if (oc instanceof Map<?, ?> openclaw) {
                primaryEnv = str(openclaw.get("primaryEnv"));
                if (openclaw.get("requires") instanceof Map<?, ?> requires) {
                    addStrings(requiredBins, requires.get("bins"));
                    addStrings(requiredBins, requires.get("anyBins"));
                    addStrings(requiredEnv, requires.get("env"));
                }
            }
            // metadata.wyrdsekai.room — our §12.4 room placement, carried in
            // the file so a bundled/installed skill lands in the right room
            // without a side-channel mapping table.
            if (metadata.get("wyrdsekai") instanceof Map<?, ?> wyrd) {
                var roomOverride = str(wyrd.get("room"));
                if (roomOverride != null && !roomOverride.isBlank()) {
                    room = roomOverride.trim();
                }
            }
        }

        var slug = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "-");
        var auth = primaryEnv != null && !primaryEnv.isBlank()
            ? SkillAuth.apiKey(primaryEnv)
            : (requiredEnv.isEmpty() ? SkillAuth.NONE : SkillAuth.apiKey(requiredEnv.getFirst()));
        var definition = new SkillDefinition(
            room + "." + slug,
            name,
            description != null && !description.isBlank() ? description : name,
            room,
            SkillTier.PROMPT,
            "openclaw/" + slug,
            "MIT-0",
            List.of(),
            auth,
            SkillLocality.ANY,
            false
        );
        return Optional.of(new ModernSkill(definition, body, version,
            List.copyOf(requiredBins), List.copyOf(requiredEnv)));
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static void addStrings(List<String> target, Object value) {
        if (value instanceof List<?> list) {
            for (var item : list) {
                if (item != null) target.add(String.valueOf(item));
            }
        } else if (value instanceof String s && !s.isBlank()) {
            target.add(s);
        }
    }

    /**
     * Parse an OpenClaw SKILL.md file into skill definitions.
     *
     * @param skillMdPath Path to the SKILL.md file
     * @param cliName     The CLI binary name (e.g., "openhue", "himalaya")
     * @param room        Default room for these skills
     */
    public List<SkillDefinition> importFromMarkdown(Path skillMdPath, String cliName, String room)
            throws IOException {
        String content = Files.readString(skillMdPath);

        // Modern frontmatter format first — contemporary ClawHub /
        // agentskills.io skills have no structured tool tables and would
        // otherwise import as zero tools.
        var modern = importModern(content, room);
        if (modern.isPresent()) {
            return List.of(modern.get().definition());
        }

        List<String> lines = content.lines().toList();
        List<SkillDefinition> skills = new ArrayList<>();

        String currentTool = null;
        String currentDescription = null;
        List<SkillParam> currentParams = new ArrayList<>();

        for (String line : lines) {
            Matcher toolMatch = TOOL_NAME_PATTERN.matcher(line);
            if (toolMatch.matches()) {
                // Save previous tool if exists
                if (currentTool != null) {
                    skills.add(buildDefinition(cliName, room, currentTool, currentDescription, currentParams));
                }
                currentTool = toolMatch.group(1).trim();
                currentDescription = null;
                currentParams = new ArrayList<>();
                continue;
            }

            Matcher descMatch = DESCRIPTION_PATTERN.matcher(line);
            if (descMatch.matches()) {
                currentDescription = descMatch.group(1).trim();
                continue;
            }

            Matcher paramMatch = PARAM_PATTERN.matcher(line);
            if (paramMatch.matches()) {
                String name = paramMatch.group(1);
                String type = paramMatch.group(2);
                boolean required = "true".equalsIgnoreCase(paramMatch.group(3))
                    || "yes".equalsIgnoreCase(paramMatch.group(3));
                String desc = paramMatch.group(4).trim();
                currentParams.add(new SkillParam(name, type, desc, required, List.of()));
            }
        }

        // Don't forget the last tool
        if (currentTool != null) {
            skills.add(buildDefinition(cliName, room, currentTool, currentDescription, currentParams));
        }

        return skills;
    }

    /**
     * Scan a directory of OpenClaw skills, looking for SKILL.md files.
     * Each subdirectory is expected to be one skill with a SKILL.md file.
     */
    public List<SkillDefinition> scanOpenClawSkills(Path openclawSkillsDir, String defaultRoom)
            throws IOException {
        List<SkillDefinition> all = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(openclawSkillsDir)) {
            dirs.filter(Files::isDirectory).forEach(dir -> {
                Path skillMd = dir.resolve("SKILL.md");
                if (!Files.exists(skillMd)) skillMd = dir.resolve("skill.md");
                if (Files.exists(skillMd)) {
                    try {
                        String cliName = dir.getFileName().toString();
                        all.addAll(importFromMarkdown(skillMd, cliName, defaultRoom));
                    } catch (IOException e) {
                        // Log and continue — one bad SKILL.md shouldn't break discovery
                    }
                }
            });
        }
        return all;
    }

    private SkillDefinition buildDefinition(String cliName, String room,
                                             String toolName, String description,
                                             List<SkillParam> params) {
        String id = room + "." + cliName + "." + toolName.toLowerCase().replace(" ", "-");
        return new SkillDefinition(
            id,
            toolName,
            description != null ? description : toolName,
            room,
            SkillTier.CLI,
            "openclaw/" + cliName,
            "MIT",
            params,
            SkillAuth.NONE,
            SkillLocality.ANY,
            false
        );
    }
}
