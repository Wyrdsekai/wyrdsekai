package org.wyrdsekai.core.skill.impl;

import org.wyrdsekai.common.i18n.I18n;
import org.wyrdsekai.core.skill.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Filesystem mount skills for the Vault room.
 * Reads, writes, and lists files from configured mount points.
 * Path traversal prevention: resolved paths must start with mount root.
 * Read-only enforced per mount.
 */
public class FilesystemSkillExecutor implements SkillExecutor {

    private final Map<String, SkillDefinition> skills = new ConcurrentHashMap<>();
    private final Map<String, Path> mounts;
    private final Set<String> writableMounts;

    public FilesystemSkillExecutor(Map<String, Path> mounts) {
        this(mounts, Set.of());
    }

    public FilesystemSkillExecutor(Map<String, Path> mounts, Set<String> writableMounts) {
        this.mounts = new ConcurrentHashMap<>();
        for (var entry : mounts.entrySet()) {
            this.mounts.put(entry.getKey(), entry.getValue().toAbsolutePath().normalize());
        }
        this.writableMounts = ConcurrentHashMap.newKeySet();
        this.writableMounts.addAll(writableMounts);

        define(new SkillDefinition("vault.fs.read",
            "Read File", "Read a file from a mounted path",
            "vault", SkillTier.NATIVE, "wyrdsekai", "Apache-2.0",
            List.of(SkillParam.required("mount", "string", "Mount name"),
                     SkillParam.required("path", "string", "Relative path within mount")),
            SkillAuth.NONE, SkillLocality.LOCAL, true));

        define(new SkillDefinition("vault.fs.write",
            "Write File", "Write content to a file in a mounted path",
            "vault", SkillTier.NATIVE, "wyrdsekai", "Apache-2.0",
            List.of(SkillParam.required("mount", "string", "Mount name"),
                     SkillParam.required("path", "string", "Relative path within mount"),
                     SkillParam.required("content", "string", "File content")),
            SkillAuth.NONE, SkillLocality.LOCAL, true));

        define(new SkillDefinition("vault.fs.list",
            "List Directory", "List files in a mounted directory",
            "vault", SkillTier.NATIVE, "wyrdsekai", "Apache-2.0",
            List.of(SkillParam.required("mount", "string", "Mount name"),
                     SkillParam.optional("path", "string", "Relative subdirectory (default: root)")),
            SkillAuth.NONE, SkillLocality.LOCAL, true));
    }

    private void define(SkillDefinition skill) {
        skills.put(skill.id(), skill);
    }

    @Override
    public SkillResult execute(String skillId, Map<String, Object> params, SkillContext context) {
        long start = System.currentTimeMillis();

        try {
            return switch (skillId) {
                case "vault.fs.read" -> executeRead(params, start, skillId);
                case "vault.fs.write" -> executeWrite(params, start, skillId);
                case "vault.fs.list" -> executeList(params, start, skillId);
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
        String mountName = requireParam(params, "mount");
        String relPath = requireParam(params, "path");
        if (mountName == null || relPath == null) {
            return SkillResult.error(I18n.get("skill.param_required", "mount, path"),
                0, SkillTier.NATIVE, skillId);
        }

        Path mountRoot = mounts.get(mountName);
        if (mountRoot == null) {
            return SkillResult.error(I18n.get("skill.fs.not_mounted", mountName),
                0, SkillTier.NATIVE, skillId);
        }

        Path resolved = resolveSafe(mountRoot, relPath);
        if (resolved == null) {
            return SkillResult.error(I18n.get("skill.fs.traversal_blocked"),
                0, SkillTier.NATIVE, skillId);
        }

        if (!Files.isRegularFile(resolved)) {
            long elapsed = System.currentTimeMillis() - start;
            return SkillResult.error(I18n.get("skill.fs.not_mounted", relPath),
                elapsed, SkillTier.NATIVE, skillId);
        }

        String content = Files.readString(resolved);
        if (content.length() > 65536) content = content.substring(0, 65536) + "\n...(truncated)";

        long elapsed = System.currentTimeMillis() - start;
        return SkillResult.ok(content,
            Map.of("path", relPath, "mount", mountName, "size", content.length()),
            elapsed, SkillTier.NATIVE, skillId);
    }

    private SkillResult executeWrite(Map<String, Object> params, long start,
                                      String skillId) throws IOException {
        String mountName = requireParam(params, "mount");
        String relPath = requireParam(params, "path");
        String content = requireParam(params, "content");
        if (mountName == null || relPath == null || content == null) {
            return SkillResult.error(I18n.get("skill.param_required", "mount, path, content"),
                0, SkillTier.NATIVE, skillId);
        }

        Path mountRoot = mounts.get(mountName);
        if (mountRoot == null) {
            return SkillResult.error(I18n.get("skill.fs.not_mounted", mountName),
                0, SkillTier.NATIVE, skillId);
        }

        if (!writableMounts.contains(mountName)) {
            long elapsed = System.currentTimeMillis() - start;
            return SkillResult.error(I18n.get("skill.fs.read_only", mountName),
                elapsed, SkillTier.NATIVE, skillId);
        }

        Path resolved = resolveSafe(mountRoot, relPath);
        if (resolved == null) {
            return SkillResult.error(I18n.get("skill.fs.traversal_blocked"),
                0, SkillTier.NATIVE, skillId);
        }

        Files.createDirectories(resolved.getParent());
        Files.writeString(resolved, content);

        long elapsed = System.currentTimeMillis() - start;
        return SkillResult.ok("Written to " + relPath,
            Map.of("path", relPath, "mount", mountName, "size", content.length()),
            elapsed, SkillTier.NATIVE, skillId);
    }

    private SkillResult executeList(Map<String, Object> params, long start,
                                     String skillId) throws IOException {
        String mountName = requireParam(params, "mount");
        if (mountName == null) {
            return SkillResult.error(I18n.get("skill.param_required", "mount"),
                0, SkillTier.NATIVE, skillId);
        }

        Path mountRoot = mounts.get(mountName);
        if (mountRoot == null) {
            return SkillResult.error(I18n.get("skill.fs.not_mounted", mountName),
                0, SkillTier.NATIVE, skillId);
        }

        String relPath = param(params, "path", "");
        Path listDir = relPath.isEmpty() ? mountRoot : resolveSafe(mountRoot, relPath);
        if (listDir == null) {
            return SkillResult.error(I18n.get("skill.fs.traversal_blocked"),
                0, SkillTier.NATIVE, skillId);
        }

        if (!Files.isDirectory(listDir)) {
            long elapsed = System.currentTimeMillis() - start;
            return SkillResult.error(I18n.get("skill.fs.not_mounted", relPath),
                elapsed, SkillTier.NATIVE, skillId);
        }

        List<Map<String, Object>> entries = new ArrayList<>();
        try (Stream<Path> stream = Files.list(listDir)) {
            stream.forEach(p -> {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("name", p.getFileName().toString());
                entry.put("directory", Files.isDirectory(p));
                try {
                    entry.put("size", Files.isRegularFile(p) ? Files.size(p) : 0);
                } catch (IOException e) {
                    entry.put("size", 0);
                }
                entries.add(entry);
            });
        }

        long elapsed = System.currentTimeMillis() - start;
        return SkillResult.ok(entries.size() + " entries",
            Map.of("entries", entries, "mount", mountName),
            elapsed, SkillTier.NATIVE, skillId);
    }

    private Path resolveSafe(Path mountRoot, String relPath) {
        Path resolved = mountRoot.resolve(relPath).toAbsolutePath().normalize();
        if (!resolved.startsWith(mountRoot)) return null;
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

    @Override
    public List<SkillDefinition> availableSkills() { return List.copyOf(skills.values()); }

    @Override
    public boolean supports(String skillId) { return skills.containsKey(skillId); }

    @Override
    public SkillTier tier() { return SkillTier.NATIVE; }
}
