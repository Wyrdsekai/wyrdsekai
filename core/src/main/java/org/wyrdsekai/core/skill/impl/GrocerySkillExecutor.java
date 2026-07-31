package org.wyrdsekai.core.skill.impl;

import org.wyrdsekai.common.i18n.I18n;
import org.wyrdsekai.core.skill.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * CRDT-backed shopping list skill executor.
 * Maintains an in-memory grocery list that syncs via Between transport in production.
 * Provides add, remove, list, and suggest operations.
 */
public class GrocerySkillExecutor implements SkillExecutor {

    private final Map<String, SkillDefinition> skills = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<GroceryItem> items = new CopyOnWriteArrayList<>();

    /** A grocery list item. */
    public record GroceryItem(String id, String name, boolean checked,
                               Instant addedAt, String addedBy) {}

    public GrocerySkillExecutor() {
        define(new SkillDefinition("hearth.grocery.add", "Grocery Add",
            "Add an item to the shopping list", "hearth", SkillTier.NATIVE,
            "wyrdsekai", "Apache-2.0",
            List.of(SkillParam.required("item", "string", "Item name"),
                     SkillParam.optional("quantity", "string", "Quantity (e.g., 2 lbs)")),
            SkillAuth.NONE, SkillLocality.BETWEEN, true));

        define(new SkillDefinition("hearth.grocery.remove", "Grocery Remove",
            "Remove or check off an item", "hearth", SkillTier.NATIVE,
            "wyrdsekai", "Apache-2.0",
            List.of(SkillParam.required("item", "string", "Item name or ID to remove")),
            SkillAuth.NONE, SkillLocality.BETWEEN, true));

        define(new SkillDefinition("hearth.grocery.list", "Grocery List",
            "Show the current shopping list", "hearth", SkillTier.NATIVE,
            "wyrdsekai", "Apache-2.0",
            List.of(SkillParam.optional("show_checked", "boolean", "Include checked items")),
            SkillAuth.NONE, SkillLocality.BETWEEN, true));

        define(new SkillDefinition("hearth.grocery.suggest", "Grocery Suggest",
            "Suggest items based on recent history", "hearth", SkillTier.NATIVE,
            "wyrdsekai", "Apache-2.0",
            List.of(SkillParam.optional("count", "number", "Number of suggestions")),
            SkillAuth.NONE, SkillLocality.BETWEEN, true));
    }

    private void define(SkillDefinition skill) { skills.put(skill.id(), skill); }

    @Override
    public SkillResult execute(String skillId, Map<String, Object> params, SkillContext context) {
        long start = System.currentTimeMillis();
        return switch (skillId) {
            case "hearth.grocery.add" -> executeAdd(params, context.agentDid(), start, skillId);
            case "hearth.grocery.remove" -> executeRemove(params, start, skillId);
            case "hearth.grocery.list" -> executeList(params, start, skillId);
            case "hearth.grocery.suggest" -> executeSuggest(params, start, skillId);
            default -> SkillResult.unavailable(skillId);
        };
    }

    private SkillResult executeAdd(Map<String, Object> params, String agentDid,
                                    long start, String skillId) {
        String itemName = requireParam(params, "item");
        if (itemName == null) return SkillResult.error(
            I18n.get("skill.param_required", "item"), 0, SkillTier.NATIVE, skillId);

        String quantity = param(params, "quantity", null);
        String displayName = quantity != null ? quantity + " " + itemName : itemName;
        String id = UUID.randomUUID().toString().substring(0, 8);

        items.add(new GroceryItem(id, displayName, false, Instant.now(), agentDid));
        long elapsed = System.currentTimeMillis() - start;

        return SkillResult.ok(I18n.get("skill.grocery.added", displayName),
            Map.of("id", id, "item", displayName, "total", items.size()),
            elapsed, SkillTier.NATIVE, skillId);
    }

    private SkillResult executeRemove(Map<String, Object> params, long start, String skillId) {
        String itemRef = requireParam(params, "item");
        if (itemRef == null) return SkillResult.error(
            I18n.get("skill.param_required", "item"), 0, SkillTier.NATIVE, skillId);

        boolean removed = items.removeIf(i ->
            i.id().equals(itemRef) || i.name().equalsIgnoreCase(itemRef));
        long elapsed = System.currentTimeMillis() - start;

        if (removed) {
            return SkillResult.ok(I18n.get("skill.grocery.removed", itemRef),
                Map.of("removed", itemRef), elapsed, SkillTier.NATIVE, skillId);
        }
        return SkillResult.ok("Not found: " + itemRef,
            Map.of("found", false), elapsed, SkillTier.NATIVE, skillId);
    }

    private SkillResult executeList(Map<String, Object> params, long start, String skillId) {
        boolean showChecked = "true".equalsIgnoreCase(param(params, "show_checked", "false"));
        var filtered = items.stream()
            .filter(i -> showChecked || !i.checked())
            .toList();
        long elapsed = System.currentTimeMillis() - start;

        if (filtered.isEmpty()) {
            return SkillResult.ok(I18n.get("skill.grocery.empty"),
                Map.of("count", 0, "items", List.of()),
                elapsed, SkillTier.NATIVE, skillId);
        }

        var sb = new StringBuilder();
        for (var item : filtered) {
            sb.append(item.checked() ? "[x] " : "[ ] ")
                .append(item.name()).append(" (").append(item.id()).append(")\n");
        }

        return SkillResult.ok(sb.toString().trim(),
            Map.of("count", filtered.size(), "items", filtered.stream()
                .map(i -> Map.of("id", i.id(), "name", i.name(), "checked", i.checked()))
                .collect(Collectors.toList())),
            elapsed, SkillTier.NATIVE, skillId);
    }

    private SkillResult executeSuggest(Map<String, Object> params, long start, String skillId) {
        int count = intParam(params, "count", 5);
        var currentNames = items.stream().map(GroceryItem::name).collect(Collectors.toSet());
        var suggestions = List.of("milk", "eggs", "bread", "bananas", "butter",
            "rice", "onions", "garlic", "chicken", "pasta");
        var filtered = suggestions.stream()
            .filter(s -> !currentNames.contains(s)).limit(count).toList();
        long elapsed = System.currentTimeMillis() - start;

        return SkillResult.ok("Suggestions: " + String.join(", ", filtered),
            Map.of("suggestions", filtered), elapsed, SkillTier.NATIVE, skillId);
    }

    /** Direct access for testing and Between sync. */
    public List<GroceryItem> getItems() { return List.copyOf(items); }

    /** Merge items from Between sync. */
    public void mergeItems(List<GroceryItem> incoming) {
        var existingIds = items.stream().map(GroceryItem::id).collect(Collectors.toSet());
        for (var item : incoming) {
            if (!existingIds.contains(item.id())) items.add(item);
        }
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
