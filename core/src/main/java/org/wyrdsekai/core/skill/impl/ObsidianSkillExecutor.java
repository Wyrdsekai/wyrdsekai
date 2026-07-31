package org.wyrdsekai.core.skill.impl;

import org.wyrdsekai.common.i18n.I18n;
import org.wyrdsekai.core.skill.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Obsidian vault skills via local filesystem.
 * Reads and writes markdown files from a configured vault directory.
 * Path traversal prevention: resolved paths must start with vault root.
 */
public class ObsidianSkillExecutor implements SkillExecutor {

    private final Map<String, SkillDefinition> skills = new ConcurrentHashMap<>();
    private final Path vaultPath;

    public ObsidianSkillExecutor(String vaultPath) {
        this.vaultPath = Path.of(vaultPath).toAbsolutePath().normalize();

        define(new SkillDefinition("scriptorium.obsidian.read",
            "Obsidian Read", "Read a note from the Obsidian vault",
            "scriptorium", SkillTier.NATIVE, "wyrdsekai", "Apache-2.0",
            List.of(SkillParam.required("path", "string", "Relative path to note")),
            SkillAuth.NONE, SkillLocality.LOCAL, true));

        define(new SkillDefinition("scriptorium.obsidian.write",
            "Obsidian Write", "Write or update a note in the vault",
            "scriptorium", SkillTier.NATIVE, "wyrdsekai", "Apache-2.0",
            List.of(
                SkillParam.required("path", "string", "Relative path to note"),
                SkillParam.required("content", "string", "Note content (Markdown)")),
            SkillAuth.NONE, SkillLocality.LOCAL, true));

        define(new SkillDefinition("scriptorium.obsidian.search",
            "Obsidian Search", "Search notes in the Obsidian vault",
            "scriptorium", SkillTier.NATIVE, "wyrdsekai", "Apache-2.0",
            List.of(SkillParam.required("query", "string", "Search query")),
            SkillAuth.NONE, SkillLocality.LOCAL, true));

        define(new SkillDefinition("scriptorium.obsidian.list",
            "Obsidian List", "List notes in the Obsidian vault",
            "scriptorium", SkillTier.NATIVE, "wyrdsekai", "Apache-2.0",
            List.of(
                SkillParam.optional("folder", "string", "Subfolder to list (default: root)"),
                SkillParam.optional("limit", "number", "Max results (default 20)")),
            SkillAuth.NONE, SkillLocality.LOCAL, true));
    }

    private void define(SkillDefinition skill) {
        skills.put(skill.id(), skill);
    }

    @Override
    public SkillResult execute(String skillId, Map<String, Object> params, SkillContext context) {
        if (!Files.isDirectory(vaultPath)) {
            return SkillResult.error(I18n.get("skill.not_configured", "vault_path"),
                0, SkillTier.NATIVE, skillId);
        }

        long start = System.currentTimeMillis();

        try {
            return switch (skillId) {
                case "scriptorium.obsidian.read" -> executeRead(params, start, skillId);
                case "scriptorium.obsidian.write" -> executeWrite(params, start, skillId, context);
                case "scriptorium.obsidian.search" -> executeSearch(params, start, skillId);
                case "scriptorium.obsidian.list" -> executeList(params, start, skillId);
                default -> SkillResult.unavailable(skillId);
            };
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            return SkillResult.error(I18n.get("skill.error.execution", e.getMessage()),
                elapsed, SkillTier.NATIVE, skillId);
        }
    }

    private SkillResult executeRead(Map<String, Object> params, long start,
                                     String skillId) throws IOException {
        String relPath = requireParam(params, "path");
        if (relPath == null) {
            return SkillResult.error(I18n.get("skill.param_required", "path"),
                0, SkillTier.NATIVE, skillId);
        }

        Path resolved = resolveSafe(relPath);
        if (resolved == null) {
            return SkillResult.error(I18n.get("skill.fs.traversal_blocked"),
                0, SkillTier.NATIVE, skillId);
        }

        if (!Files.exists(resolved)) {
            long elapsed = System.currentTimeMillis() - start;
            return SkillResult.error(I18n.get("skill.obsidian.not_found", relPath),
                elapsed, SkillTier.NATIVE, skillId);
        }

        String content = Files.readString(resolved);
        long elapsed = System.currentTimeMillis() - start;
        return SkillResult.ok(content.length() > 2048 ? content.substring(0, 2048) + "..." : content,
            Map.of("path", relPath, "content", content, "size", content.length()),
            elapsed, SkillTier.NATIVE, skillId);
    }

    private SkillResult executeWrite(Map<String, Object> params, long start,
                                      String skillId, SkillContext context) throws IOException {
        if (!context.isHumanSession()) {
            long elapsed = System.currentTimeMillis() - start;
            return SkillResult.error(I18n.get("skill.obsidian.write_denied"),
                elapsed, SkillTier.NATIVE, skillId);
        }

        String relPath = requireParam(params, "path");
        String content = requireParam(params, "content");
        if (relPath == null || content == null) {
            return SkillResult.error(I18n.get("skill.param_required", "path, content"),
                0, SkillTier.NATIVE, skillId);
        }

        Path resolved = resolveSafe(relPath);
        if (resolved == null) {
            return SkillResult.error(I18n.get("skill.fs.traversal_blocked"),
                0, SkillTier.NATIVE, skillId);
        }

        Files.createDirectories(resolved.getParent());
        Files.writeString(resolved, content);

        long elapsed = System.currentTimeMillis() - start;
        return SkillResult.ok(I18n.get("skill.obsidian.written", relPath),
            Map.of("path", relPath, "size", content.length()),
            elapsed, SkillTier.NATIVE, skillId);
    }

    private SkillResult executeSearch(Map<String, Object> params, long start,
                                       String skillId) throws IOException {
        String query = requireParam(params, "query");
        if (query == null) {
            return SkillResult.error(I18n.get("skill.param_required", "query"),
                0, SkillTier.NATIVE, skillId);
        }

        String lowerQuery = query.toLowerCase();
        List<Map<String, String>> results = new ArrayList<>();

        try (Stream<Path> walk = Files.walk(vaultPath, 10)) {
            walk.filter(p -> p.toString().endsWith(".md") && Files.isRegularFile(p))
                .forEach(p -> {
                    try {
                        String content = Files.readString(p);
                        if (content.toLowerCase().contains(lowerQuery)) {
                            results.add(Map.of(
                                "path", vaultPath.relativize(p).toString(),
                                "name", p.getFileName().toString()));
                        }
                    } catch (IOException ignored) {}
                });
        }

        long elapsed = System.currentTimeMillis() - start;
        return SkillResult.ok(results.size() + " notes found",
            Map.of("results", results, "query", query),
            elapsed, SkillTier.NATIVE, skillId);
    }

    private SkillResult executeList(Map<String, Object> params, long start,
                                     String skillId) throws IOException {
        String folder = param(params, "folder", "");
        int limit = intParam(params, "limit", 20);

        Path listRoot = folder.isEmpty() ? vaultPath : resolveSafe(folder);
        if (listRoot == null) {
            return SkillResult.error(I18n.get("skill.fs.traversal_blocked"),
                0, SkillTier.NATIVE, skillId);
        }

        if (!Files.isDirectory(listRoot)) {
            long elapsed = System.currentTimeMillis() - start;
            return SkillResult.error(I18n.get("skill.obsidian.not_found", folder),
                elapsed, SkillTier.NATIVE, skillId);
        }

        List<Map<String, Object>> entries = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(listRoot, 1)) {
            walk.filter(p -> !p.equals(listRoot))
                .limit(limit)
                .forEach(p -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("name", p.getFileName().toString());
                    entry.put("path", vaultPath.relativize(p).toString());
                    entry.put("directory", Files.isDirectory(p));
                    entries.add(entry);
                });
        }

        long elapsed = System.currentTimeMillis() - start;
        return SkillResult.ok(entries.size() + " entries",
            Map.of("entries", entries),
            elapsed, SkillTier.NATIVE, skillId);
    }

    private Path resolveSafe(String relPath) {
        Path resolved = vaultPath.resolve(relPath).toAbsolutePath().normalize();
        if (!resolved.startsWith(vaultPath)) return null;
        return resolved;
    }

    private String param(Map<String, Object> params, String key, String defaultValue) {
        Object v = params != null ? params.get(key) : null;
        return v != null ? String.valueOf(v) : defaultValue;
    }

    private String requireParam(Map<String, Object> params, String key) {
        Object v = params != null ? params.get(key) : null;
        return v != null ? String.valueOf(v) : null;
    }

    private int intParam(Map<String, Object> params, String key, int defaultValue) {
        Object v = params != null ? params.get(key) : null;
        if (v instanceof Number n) return n.intValue();
        if (v != null) {
            try { return Integer.parseInt(String.valueOf(v)); }
            catch (NumberFormatException e) { /* fall through */ }
        }
        return defaultValue;
    }

    @Override
    public List<SkillDefinition> availableSkills() { return List.copyOf(skills.values()); }

    @Override
    public boolean supports(String skillId) { return skills.containsKey(skillId); }

    @Override
    public SkillTier tier() { return SkillTier.NATIVE; }
}
