package org.wyrdsekai.core.skill.impl;

import org.wyrdsekai.common.i18n.I18n;
import org.wyrdsekai.core.skill.*;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Local file search skill executor.
 * Uses Java NIO Files.walk with pattern matching for filename search.
 * Provides search, recent files, and index operations.
 * Configurable search paths restrict where searches can occur.
 */
public class FileSearchSkillExecutor implements SkillExecutor {

    private static final int MAX_RESULTS = 50;
    private static final int MAX_WALK_DEPTH = 10;

    private final Map<String, SkillDefinition> skills = new ConcurrentHashMap<>();
    private final List<Path> searchPaths;

    /**
     * @param searchPaths Allowed root directories for file search
     */
    public FileSearchSkillExecutor(List<Path> searchPaths) {
        this.searchPaths = searchPaths != null ? List.copyOf(searchPaths) : List.of();

        define(new SkillDefinition("vault.files.search", "File Search",
            "Search local files by name pattern", "vault", SkillTier.NATIVE,
            "wyrdsekai", "Apache-2.0",
            List.of(SkillParam.required("query", "string", "Filename pattern or substring"),
                     SkillParam.optional("glob", "string", "Glob pattern (e.g., *.pdf)"),
                     SkillParam.optional("limit", "number", "Max results (default: 50)")),
            SkillAuth.NONE, SkillLocality.LOCAL, true));

        define(new SkillDefinition("vault.files.recent", "Recent Files",
            "List recently modified files", "vault", SkillTier.NATIVE,
            "wyrdsekai", "Apache-2.0",
            List.of(SkillParam.optional("limit", "number", "Max files (default: 20)"),
                     SkillParam.optional("glob", "string", "Filter by glob pattern")),
            SkillAuth.NONE, SkillLocality.LOCAL, true));

        define(new SkillDefinition("vault.files.index", "File Index",
            "Index configured search paths", "vault", SkillTier.NATIVE,
            "wyrdsekai", "Apache-2.0", List.of(),
            SkillAuth.NONE, SkillLocality.LOCAL, false));
    }

    private void define(SkillDefinition skill) { skills.put(skill.id(), skill); }

    @Override
    public SkillResult execute(String skillId, Map<String, Object> params, SkillContext context) {
        if (searchPaths.isEmpty())
            return SkillResult.error(I18n.get("skill.not_configured", "File search paths"),
                0, SkillTier.NATIVE, skillId);

        long start = System.currentTimeMillis();
        return switch (skillId) {
            case "vault.files.search" -> executeSearch(params, start, skillId);
            case "vault.files.recent" -> executeRecent(params, start, skillId);
            case "vault.files.index" -> executeIndex(start, skillId);
            default -> SkillResult.unavailable(skillId);
        };
    }

    private SkillResult executeSearch(Map<String, Object> params, long start, String skillId) {
        String query = requireParam(params, "query");
        if (query == null) return SkillResult.error(
            I18n.get("skill.param_required", "query"), 0, SkillTier.NATIVE, skillId);

        String globPattern = param(params, "glob", null);
        int limit = Math.min(intParam(params, "limit", MAX_RESULTS), MAX_RESULTS);
        String queryLower = query.toLowerCase();

        List<String> matches = new ArrayList<>();
        for (Path root : searchPaths) {
            if (!Files.isDirectory(root)) continue;
            PathMatcher matcher = globPattern != null
                ? root.getFileSystem().getPathMatcher("glob:" + globPattern)
                : null;
            try (Stream<Path> walk = Files.walk(root, MAX_WALK_DEPTH)) {
                walk.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        boolean nameMatch = name.contains(queryLower);
                        boolean globMatch = matcher == null || matcher.matches(p.getFileName());
                        return nameMatch && globMatch;
                    })
                    .limit(limit - matches.size())
                    .forEach(p -> matches.add(p.toString()));
            } catch (IOException e) {
                // Skip roots that fail to walk
            }
            if (matches.size() >= limit) break;
        }

        long elapsed = System.currentTimeMillis() - start;
        if (matches.isEmpty()) {
            return SkillResult.ok(I18n.get("skill.filesearch.no_results", query),
                Map.of("query", query, "results", List.of()),
                elapsed, SkillTier.NATIVE, skillId);
        }

        return SkillResult.ok(I18n.get("skill.filesearch.results", matches.size()),
            Map.of("query", query, "results", matches, "count", matches.size()),
            elapsed, SkillTier.NATIVE, skillId);
    }

    private SkillResult executeRecent(Map<String, Object> params, long start, String skillId) {
        int limit = Math.min(intParam(params, "limit", 20), MAX_RESULTS);
        String globPattern = param(params, "glob", null);

        // Collect files with modification times
        var filesWithTime = new TreeMap<Long, String>(Comparator.reverseOrder());
        for (Path root : searchPaths) {
            if (!Files.isDirectory(root)) continue;
            PathMatcher matcher = globPattern != null
                ? root.getFileSystem().getPathMatcher("glob:" + globPattern) : null;
            try (Stream<Path> walk = Files.walk(root, MAX_WALK_DEPTH)) {
                walk.filter(Files::isRegularFile)
                    .filter(p -> matcher == null || matcher.matches(p.getFileName()))
                    .forEach(p -> {
                        try {
                            long modified = Files.getLastModifiedTime(p).toMillis();
                            filesWithTime.put(modified, p.toString());
                        } catch (IOException e) { /* skip */ }
                    });
            } catch (IOException e) {
                // Skip
            }
        }

        var recent = filesWithTime.values().stream().limit(limit).toList();
        long elapsed = System.currentTimeMillis() - start;

        if (recent.isEmpty()) {
            return SkillResult.ok(I18n.get("skill.filesearch.no_results", "recent"),
                Map.of("results", List.of()),
                elapsed, SkillTier.NATIVE, skillId);
        }

        return SkillResult.ok(I18n.get("skill.filesearch.results", recent.size()),
            Map.of("results", recent, "count", recent.size()),
            elapsed, SkillTier.NATIVE, skillId);
    }

    private SkillResult executeIndex(long start, String skillId) {
        int totalFiles = 0;
        for (Path root : searchPaths) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> walk = Files.walk(root, MAX_WALK_DEPTH)) {
                totalFiles += (int) walk.filter(Files::isRegularFile).count();
            } catch (IOException e) { /* skip */ }
        }
        long elapsed = System.currentTimeMillis() - start;

        return SkillResult.ok("Indexed " + totalFiles + " files across "
                + searchPaths.size() + " search paths",
            Map.of("totalFiles", totalFiles, "searchPaths", searchPaths.size()),
            elapsed, SkillTier.NATIVE, skillId);
    }

    private String param(Map<String, Object> p, String k, String d) {
        Object v = p != null ? p.get(k) : null; return v != null ? String.valueOf(v) : d;
    }
    private String requireParam(Map<String, Object> p, String k) {
        Object v = p != null ? p.get(k) : null; return v != null ? String.valueOf(v) : null;
    }
    private int intParam(Map<String, Object> p, String k, int d) {
        Object v = p != null ? p.get(k) : null;
        if (v instanceof Number n) return n.intValue();
        if (v != null) { try { return Integer.parseInt(String.valueOf(v)); } catch (NumberFormatException e) { /* */ } }
        return d;
    }

    @Override public List<SkillDefinition> availableSkills() { return List.copyOf(skills.values()); }
    @Override public boolean supports(String skillId) { return skills.containsKey(skillId); }
    @Override public SkillTier tier() { return SkillTier.NATIVE; }
}
