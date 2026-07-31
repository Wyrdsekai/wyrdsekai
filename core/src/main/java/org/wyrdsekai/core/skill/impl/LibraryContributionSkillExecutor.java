package org.wyrdsekai.core.skill.impl;

import org.wyrdsekai.common.i18n.I18n;
import org.wyrdsekai.core.skill.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Agent contributions to the Library. Agents can submit knowledge
 * and check contribution status. In-memory store; persistence
 * handled by room state snapshots.
 */
public class LibraryContributionSkillExecutor implements SkillExecutor {

    public record Contribution(String id, String title, String submitter, String status) {}

    private final Map<String, SkillDefinition> skills = new LinkedHashMap<>();
    private final Map<String, Contribution> contributions = new ConcurrentHashMap<>();
    private final AtomicLong idCounter = new AtomicLong(1);

    public LibraryContributionSkillExecutor() {
        skills.put("library.contribute.submit", new SkillDefinition(
            "library.contribute.submit",
            "Submit Contribution", "Submit knowledge to the library",
            "library", SkillTier.NATIVE, "wyrdsekai", "Apache-2.0",
            List.of(
                SkillParam.required("title", "string", "Title of the contribution"),
                SkillParam.required("content", "string", "Content to contribute")),
            SkillAuth.NONE, SkillLocality.LOCAL, false));

        skills.put("library.contribute.status", new SkillDefinition(
            "library.contribute.status",
            "Contribution Status", "Check the status of a submitted contribution",
            "library", SkillTier.NATIVE, "wyrdsekai", "Apache-2.0",
            List.of(SkillParam.required("id", "string", "Contribution ID")),
            SkillAuth.NONE, SkillLocality.LOCAL, false));
    }

    @Override
    public SkillResult execute(String skillId, Map<String, Object> params, SkillContext context) {
        long start = System.currentTimeMillis();

        return switch (skillId) {
            case "library.contribute.submit" -> doSubmit(params, context, start);
            case "library.contribute.status" -> doStatus(params, start);
            default -> SkillResult.unavailable(skillId);
        };
    }

    private SkillResult doSubmit(Map<String, Object> params, SkillContext context, long start) {
        String title = str(params, "title");
        String content = str(params, "content");
        if (title == null || content == null) {
            return SkillResult.error(
                I18n.get("skill.param_required", "title, content"),
                0, SkillTier.NATIVE, "library.contribute.submit");
        }

        String id = "contrib-" + idCounter.getAndIncrement();
        var contrib = new Contribution(id, title, context.agentDid(), "pending");
        contributions.put(id, contrib);

        long elapsed = System.currentTimeMillis() - start;
        return SkillResult.ok(
            I18n.get("skill.library.contributed", title, id),
            Map.of("id", id, "status", "pending"),
            elapsed, SkillTier.NATIVE, "library.contribute.submit");
    }

    private SkillResult doStatus(Map<String, Object> params, long start) {
        String id = str(params, "id");
        if (id == null) {
            return SkillResult.error(
                I18n.get("skill.param_required", "id"),
                0, SkillTier.NATIVE, "library.contribute.status");
        }

        var contrib = contributions.get(id);
        if (contrib == null) {
            long elapsed = System.currentTimeMillis() - start;
            return SkillResult.error(
                I18n.get("skill.library.rejected", id),
                elapsed, SkillTier.NATIVE, "library.contribute.status");
        }

        long elapsed = System.currentTimeMillis() - start;
        return SkillResult.ok(
            I18n.get("skill.library.accepted", contrib.title(), contrib.status()),
            Map.of("id", contrib.id(), "title", contrib.title(),
                "submitter", contrib.submitter(), "status", contrib.status()),
            elapsed, SkillTier.NATIVE, "library.contribute.status");
    }

    public void accept(String id) {
        var c = contributions.get(id);
        if (c != null) {
            contributions.put(id, new Contribution(c.id(), c.title(), c.submitter(), "accepted"));
        }
    }

    public void reject(String id) {
        var c = contributions.get(id);
        if (c != null) {
            contributions.put(id, new Contribution(c.id(), c.title(), c.submitter(), "rejected"));
        }
    }

    public Map<String, Contribution> allContributions() {
        return Collections.unmodifiableMap(contributions);
    }

    private static String str(Map<String, Object> params, String key) {
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
