package org.wyrdsekai.core.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.soul.FamilyLocker;
import org.wyrdsekai.core.soul.SoulItem;
import org.wyrdsekai.scripting.sandbox.ItemScriptExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Executes workbench-created skills from the FamilyLocker.
 * GraalJS skills run in a sandboxed context.
 * Shell skills are not yet supported (future: ProcessBuilder with timeout).
 *
 * Skill IDs use the prefix "workbench." — e.g., "workbench.weather-check".
 */
public class WorkbenchSkillExecutor implements SkillExecutor {

    private static final Logger log = LoggerFactory.getLogger(WorkbenchSkillExecutor.class);
    private static final String PREFIX = "workbench.";

    private final FamilyLocker familyLocker;
    private final String agentDid;

    /** Cache of decoded skill definitions keyed by skill name. */
    private final Map<String, CachedSkill> cache = new ConcurrentHashMap<>();

    public WorkbenchSkillExecutor(FamilyLocker familyLocker, String agentDid) {
        this.familyLocker = familyLocker;
        this.agentDid = agentDid;
    }

    @Override
    public SkillResult execute(String skillId, Map<String, Object> params, SkillContext context) {
        String skillName = stripPrefix(skillId);
        long start = System.currentTimeMillis();

        // Resolve skill from locker
        var cached = resolveSkill(skillName);
        if (cached == null) {
            return SkillResult.error("Skill not found: " + skillName, 0, tier(), skillId);
        }

        var def = cached.definition();
        if (!"graaljs".equals(def.runtime())) {
            return SkillResult.error("Runtime '" + def.runtime() +
                "' not yet supported for execution", 0, tier(), skillId);
        }

        // Execute the skill code via GraalJS sandbox (ItemScriptExecutor)
        try {
            var executor = new ItemScriptExecutor();
            var result = executor.execute(
                "skill-" + skillName, def.code(),
                params != null ? params : Map.of(),
                null); // no ItemWorldApiProvider for standalone skills
            executor.close();

            long elapsed = System.currentTimeMillis() - start;
            log.info("Workbench skill '{}' executed in {}ms: {}", skillName, elapsed,
                result.containsKey("error") ? "ERROR" : "OK");

            if (result.containsKey("error")) {
                return SkillResult.error("Skill error: " + result.get("error"),
                    elapsed, tier(), skillId);
            }

            return SkillResult.ok(
                result.containsKey("output") ? String.valueOf(result.get("output"))
                    : "Skill '" + skillName + "' completed",
                result, elapsed, tier(), skillId);

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.warn("Workbench skill '{}' failed: {}", skillName, e.getMessage());
            return SkillResult.error("Execution failed: " + e.getMessage(),
                elapsed, tier(), skillId);
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
        return resolveSkill(skillName) != null;
    }

    @Override
    public SkillTier tier() {
        return SkillTier.WORKBENCH;
    }

    /**
     * Register a newly created skill (called after WorkbenchValidator passes).
     */
    public void register(String skillName, SoulItem item, SkillItemCodec.SkillDefinition def) {
        cache.put(skillName, new CachedSkill(item, def));
        log.info("Registered workbench skill: {}", skillName);
    }

    /**
     * Unregister a skill (called on tombstone).
     */
    public void unregister(String skillName) {
        cache.remove(skillName);
    }

    // --- Internal ---

    private CachedSkill resolveSkill(String skillName) {
        var cached = cache.get(skillName);
        if (cached != null) return cached;

        // Try loading from locker
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
                    if (def != null) {
                        cache.put(item.label(), new CachedSkill(item, def));
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Failed to refresh workbench skill cache: {}", e.getMessage());
        }
    }

    private static String stripPrefix(String skillId) {
        return skillId.startsWith(PREFIX) ? skillId.substring(PREFIX.length()) : skillId;
    }

    record CachedSkill(SoulItem item, SkillItemCodec.SkillDefinition definition) {}
}
