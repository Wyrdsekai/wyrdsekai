package org.wyrdsekai.core.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.soul.FamilyLocker;
import org.wyrdsekai.core.soul.SoulItem;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Executes Python scripts via ProcessBuilder. Only available on non-phone nodes
 * where Python 3 is installed.
 *
 * <p>Scripts run in a sandboxed workspace directory with a configurable timeout.
 * stdin receives JSON params, stdout is captured as the result.
 *
 * <p>Skill IDs use the prefix "workbench." (same as GraalJS skills) and are
 * distinguished by their runtime field ("python").
 */
public class PythonSkillExecutor implements SkillExecutor {

    private static final Logger log = LoggerFactory.getLogger(PythonSkillExecutor.class);
    private static final String PREFIX = "workbench.";

    /** Default script timeout in seconds. */
    public static final int DEFAULT_TIMEOUT_SECONDS = 30;

    /** Maximum output size (64 KB). */
    private static final int MAX_OUTPUT_SIZE = 65_536;

    private final FamilyLocker familyLocker;
    private final String agentDid;
    private final Path workspaceRoot;
    private final Map<String, CachedSkill> cache = new ConcurrentHashMap<>();

    public PythonSkillExecutor(FamilyLocker familyLocker, String agentDid, Path workspaceRoot) {
        this.familyLocker = familyLocker;
        this.agentDid = agentDid;
        this.workspaceRoot = workspaceRoot;
    }

    /**
     * Check if Python 3 is available on this system.
     *
     * @return true if python3 can be invoked
     */
    public static boolean isAvailable() {
        try {
            var process = new ProcessBuilder("python3", "--version")
                .redirectErrorStream(true)
                .start();
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public SkillResult execute(String skillId, Map<String, Object> params, SkillContext context) {
        String skillName = stripPrefix(skillId);
        long start = System.currentTimeMillis();

        // Resolve skill
        var cached = resolveSkill(skillName);
        if (cached == null) {
            return SkillResult.error("Skill not found: " + skillName, 0, tier(), skillId);
        }
        if (!"python".equals(cached.definition().runtime())) {
            return SkillResult.error("Not a Python skill: " + skillName, 0, tier(), skillId);
        }

        // Check Python availability
        if (!isAvailable()) {
            return SkillResult.error("Python 3 is not available on this node", 0, tier(), skillId);
        }

        Path scriptFile = null;
        try {
            // Write script to temp file in workspace
            Files.createDirectories(workspaceRoot);
            scriptFile = workspaceRoot.resolve("_skill_" + skillName + ".py");
            Files.writeString(scriptFile, cached.definition().code());

            // Build JSON params string for stdin
            String jsonParams = "{}";
            if (params != null && !params.isEmpty()) {
                var sb = new StringBuilder("{");
                boolean first = true;
                for (var entry : params.entrySet()) {
                    if (!first) sb.append(",");
                    sb.append("\"").append(escapeJson(entry.getKey())).append("\":");
                    sb.append("\"").append(escapeJson(String.valueOf(entry.getValue()))).append("\"");
                    first = false;
                }
                sb.append("}");
                jsonParams = sb.toString();
            }

            // Determine timeout
            int timeoutSec = (int) Math.min(context.timeoutMs() / 1000, DEFAULT_TIMEOUT_SECONDS);
            if (timeoutSec <= 0) timeoutSec = DEFAULT_TIMEOUT_SECONDS;

            // Execute
            var pb = new ProcessBuilder("python3", scriptFile.toString())
                .directory(workspaceRoot.toFile())
                .redirectErrorStream(false);

            var process = pb.start();

            // Send params to stdin
            try (var os = process.getOutputStream()) {
                os.write(jsonParams.getBytes());
                os.flush();
            }

            // Wait with timeout
            boolean finished = process.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                long elapsed = System.currentTimeMillis() - start;
                return SkillResult.error("Script timed out after " + timeoutSec + "s",
                    elapsed, tier(), skillId);
            }

            // Capture output
            String stdout = truncate(new String(process.getInputStream().readAllBytes()));
            String stderr = truncate(new String(process.getErrorStream().readAllBytes()));
            int exitCode = process.exitValue();

            long elapsed = System.currentTimeMillis() - start;

            if (exitCode != 0) {
                String errorMsg = stderr.isBlank() ? "Exit code " + exitCode : stderr;
                return SkillResult.error("Script failed: " + errorMsg, elapsed, tier(), skillId);
            }

            log.info("Python skill '{}' executed in {}ms", skillName, elapsed);
            return SkillResult.ok(
                stdout.isBlank() ? "Script completed" : stdout,
                Map.of("skill", skillName, "exitCode", exitCode, "stderr", stderr),
                elapsed, tier(), skillId
            );

        } catch (IOException | InterruptedException e) {
            long elapsed = System.currentTimeMillis() - start;
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Python skill '{}' failed: {}", skillName, e.getMessage());
            return SkillResult.error("Execution failed: " + e.getMessage(), elapsed, tier(), skillId);
        } finally {
            // Clean up temp script file
            if (scriptFile != null) {
                try { Files.deleteIfExists(scriptFile); } catch (IOException ignored) {}
            }
        }
    }

    @Override
    public List<SkillDefinition> availableSkills() {
        refreshCache();
        var skills = new ArrayList<SkillDefinition>();
        for (var entry : cache.entrySet()) {
            var def = entry.getValue().definition();
            skills.add(new SkillDefinition(
                PREFIX + entry.getKey(),
                entry.getKey(),
                def.description(),
                "workshop",
                SkillTier.WORKBENCH,
                "workbench",
                null,
                List.of(),
                null,
                SkillLocality.ANY,
                false
            ));
        }
        return skills;
    }

    @Override
    public boolean supports(String skillId) {
        if (skillId == null || !skillId.startsWith(PREFIX)) return false;
        String skillName = stripPrefix(skillId);
        var cached = resolveSkill(skillName);
        return cached != null && "python".equals(cached.definition().runtime());
    }

    @Override
    public SkillTier tier() {
        return SkillTier.WORKBENCH;
    }

    /**
     * Register a Python skill.
     */
    public void register(String skillName, SoulItem item, SkillItemCodec.SkillDefinition def) {
        if ("python".equals(def.runtime())) {
            cache.put(skillName, new CachedSkill(item, def));
            log.info("Registered Python skill: {}", skillName);
        }
    }

    /**
     * Unregister a skill.
     */
    public void unregister(String skillName) {
        cache.remove(skillName);
    }

    // --- Internal ---

    private CachedSkill resolveSkill(String skillName) {
        var cached = cache.get(skillName);
        if (cached != null) return cached;
        refreshCache();
        return cache.get(skillName);
    }

    private void refreshCache() {
        try {
            var skillItems = familyLocker.byCategory("skill", agentDid);
            if (skillItems == null) return;
            for (var item : skillItems) {
                if (!cache.containsKey(item.label())) {
                    var def = SkillItemCodec.decode(item);
                    if (def != null && "python".equals(def.runtime())) {
                        cache.put(item.label(), new CachedSkill(item, def));
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Failed to refresh Python skill cache: {}", e.getMessage());
        }
    }

    private static String stripPrefix(String skillId) {
        return skillId.startsWith(PREFIX) ? skillId.substring(PREFIX.length()) : skillId;
    }

    private static String truncate(String s) {
        return s != null && s.length() > MAX_OUTPUT_SIZE ? s.substring(0, MAX_OUTPUT_SIZE) : s;
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }

    record CachedSkill(SoulItem item, SkillItemCodec.SkillDefinition definition) {}
}
