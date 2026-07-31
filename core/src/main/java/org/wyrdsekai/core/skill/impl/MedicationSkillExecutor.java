package org.wyrdsekai.core.skill.impl;

import org.wyrdsekai.common.i18n.I18n;
import org.wyrdsekai.core.skill.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Medication tracking skills for the Hearth room.
 * In-memory store of medication entries with reminders and acknowledgment.
 * Designed for the aging companion use case ( S99).
 */
public class MedicationSkillExecutor implements SkillExecutor {

    public record MedEntry(String name, String schedule, Instant lastTaken) {}

    private final Map<String, SkillDefinition> skills = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, MedEntry> medications = new ConcurrentHashMap<>();

    public MedicationSkillExecutor() {
        define(new SkillDefinition("hearth.medication.list",
            "Medication List", "List all tracked medications and their schedules",
            "hearth", SkillTier.NATIVE, "wyrdsekai", "Apache-2.0",
            List.of(),
            SkillAuth.NONE, SkillLocality.LOCAL, true));

        define(new SkillDefinition("hearth.medication.acknowledge",
            "Acknowledge Medication", "Mark a medication as taken",
            "hearth", SkillTier.NATIVE, "wyrdsekai", "Apache-2.0",
            List.of(SkillParam.required("name", "string", "Medication name")),
            SkillAuth.NONE, SkillLocality.LOCAL, true));

        define(new SkillDefinition("hearth.medication.missed",
            "Missed Medications", "List medications not taken on schedule",
            "hearth", SkillTier.NATIVE, "wyrdsekai", "Apache-2.0",
            List.of(),
            SkillAuth.NONE, SkillLocality.LOCAL, true));
    }

    private void define(SkillDefinition skill) {
        skills.put(skill.id(), skill);
    }

    public void addMedication(String name, String schedule) {
        medications.put(name.toLowerCase(), new MedEntry(name, schedule, null));
    }

    @Override
    public SkillResult execute(String skillId, Map<String, Object> params, SkillContext context) {
        long start = System.currentTimeMillis();

        return switch (skillId) {
            case "hearth.medication.list" -> executeList(start, skillId);
            case "hearth.medication.acknowledge" -> executeAcknowledge(params, start, skillId);
            case "hearth.medication.missed" -> executeMissed(start, skillId);
            default -> SkillResult.unavailable(skillId);
        };
    }

    private SkillResult executeList(long start, String skillId) {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (MedEntry entry : medications.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", entry.name());
            m.put("schedule", entry.schedule());
            m.put("lastTaken", entry.lastTaken() != null ? entry.lastTaken().toString() : "never");
            entries.add(m);
        }

        long elapsed = System.currentTimeMillis() - start;
        String output = I18n.get("skill.medication.reminder", entries.size());
        return SkillResult.ok(output, Map.of("medications", entries, "count", entries.size()),
            elapsed, SkillTier.NATIVE, skillId);
    }

    private SkillResult executeAcknowledge(Map<String, Object> params, long start, String skillId) {
        String name = requireParam(params, "name");
        if (name == null) {
            return SkillResult.error(I18n.get("skill.param_required", "name"),
                0, SkillTier.NATIVE, skillId);
        }

        String key = name.toLowerCase();
        MedEntry existing = medications.get(key);
        if (existing == null) {
            long elapsed = System.currentTimeMillis() - start;
            return SkillResult.error("Medication not found: " + name,
                elapsed, SkillTier.NATIVE, skillId);
        }

        Instant now = Instant.now();
        medications.put(key, new MedEntry(existing.name(), existing.schedule(), now));

        long elapsed = System.currentTimeMillis() - start;
        String output = I18n.get("skill.medication.acknowledged", existing.name());
        return SkillResult.ok(output, Map.of("name", existing.name(), "takenAt", now.toString()),
            elapsed, SkillTier.NATIVE, skillId);
    }

    private SkillResult executeMissed(long start, String skillId) {
        List<Map<String, String>> missed = new ArrayList<>();
        for (MedEntry entry : medications.values()) {
            if (entry.lastTaken() == null) {
                missed.add(Map.of("name", entry.name(), "schedule", entry.schedule()));
            }
        }

        long elapsed = System.currentTimeMillis() - start;
        String output = I18n.get("skill.medication.missed", missed.size());
        return SkillResult.ok(output, Map.of("missed", missed, "count", missed.size()),
            elapsed, SkillTier.NATIVE, skillId);
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
