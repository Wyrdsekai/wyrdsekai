package org.wyrdsekai.common.model;

import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * MUD-style alias resolution with ordinal disambiguation.
 *
 * Supports:
 * <ul>
 *   <li>"sword" → first item whose aliases contain "sword"</li>
 *   <li>"2.sword" → second item whose aliases contain "sword"</li>
 *   <li>"iron sword" → exact alias match on "iron sword"</li>
 *   <li>Falls back to name-contains matching if no alias hit</li>
 * </ul>
 *
 * Resolution order:
 * 1. Parse ordinal prefix (N.query)
 * 2. Try exact alias match (case-insensitive)
 * 3. Try name match (case-insensitive exact, then contains)
 * 4. Return Nth match when ordinal specified
 */
public final class AliasResolver {

    private static final Pattern ORDINAL_PATTERN = Pattern.compile("^(\\d+)\\.(.+)$");

    private AliasResolver() {}

    /**
     * Parsed query with optional ordinal.
     * @param ordinal 1-based ordinal (0 = no ordinal specified, take first match)
     * @param query   The alias/name query after stripping ordinal prefix
     */
    public record ParsedQuery(int ordinal, String query) {}

    /** Parse "N.alias" syntax. Returns ordinal=0 if no prefix. */
    public static ParsedQuery parseQuery(String input) {
        if (input == null || input.isBlank()) return new ParsedQuery(0, "");
        var trimmed = input.trim();
        var matcher = ORDINAL_PATTERN.matcher(trimmed);
        if (matcher.matches()) {
            try {
                int ordinal = Integer.parseInt(matcher.group(1));
                if (ordinal > 0) {
                    return new ParsedQuery(ordinal, matcher.group(2).trim());
                }
            } catch (NumberFormatException ignored) {}
        }
        return new ParsedQuery(0, trimmed);
    }

    /**
     * Resolve a query against a collection of items.
     *
     * @param items       Collection to search
     * @param query       Raw query string, possibly with "N." ordinal prefix
     * @param aliasGetter Function to get aliases from an item
     * @param nameGetter  Function to get display name from an item
     * @param <T>         Item type
     * @return Resolved item, or empty if no match
     */
    public static <T> Optional<T> resolve(Collection<T> items, String query,
                                           Function<T, List<String>> aliasGetter,
                                           Function<T, String> nameGetter) {
        var parsed = parseQuery(query);
        if (parsed.query().isEmpty()) return Optional.empty();

        var lowerQuery = parsed.query().toLowerCase();
        var matches = new ArrayList<T>();

        // 1. Exact alias match (case-insensitive)
        for (var item : items) {
            var aliases = aliasGetter.apply(item);
            if (aliases != null) {
                for (var alias : aliases) {
                    if (alias.equalsIgnoreCase(parsed.query())) {
                        matches.add(item);
                        break;
                    }
                }
            }
        }

        // 2. If no alias match, try exact name match
        if (matches.isEmpty()) {
            for (var item : items) {
                if (nameGetter.apply(item).equalsIgnoreCase(parsed.query())) {
                    matches.add(item);
                }
            }
        }

        // 3. If still no match, try partial: alias contains query or query contains alias
        if (matches.isEmpty()) {
            for (var item : items) {
                var aliases = aliasGetter.apply(item);
                if (aliases != null) {
                    for (var alias : aliases) {
                        if (alias.toLowerCase().contains(lowerQuery)
                                || lowerQuery.contains(alias.toLowerCase())) {
                            matches.add(item);
                            break;
                        }
                    }
                }
            }
        }

        // 4. If still no match, try partial name: name contains query or query contains name
        if (matches.isEmpty()) {
            for (var item : items) {
                var name = nameGetter.apply(item).toLowerCase();
                if (name.contains(lowerQuery) || lowerQuery.contains(name)) {
                    matches.add(item);
                }
            }
        }

        if (matches.isEmpty()) return Optional.empty();

        // Apply ordinal
        if (parsed.ordinal() > 0) {
            if (parsed.ordinal() <= matches.size()) {
                return Optional.of(matches.get(parsed.ordinal() - 1));
            }
            return Optional.empty(); // "3.sword" but only 2 swords
        }

        return Optional.of(matches.getFirst());
    }

    /**
     * Resolve a query against RoomObjects (convenience method).
     * Matches against aliases first, then object name.
     */
    public static Optional<RoomObject> resolveObject(Collection<RoomObject> objects, String query) {
        return resolve(objects, query,
            RoomObject::aliases,
            RoomObject::name);
    }

    /**
     * Resolve a query against Entities (convenience method).
     * Matches against aliases first, then entity name.
     */
    public static Optional<Entity> resolveEntity(Collection<Entity> entities, String query) {
        return resolve(entities, query,
            Entity::aliases,
            Entity::name);
    }

    /**
     * Count how many items match a query (for "You see two swords here" style messages).
     */
    public static <T> int countMatches(Collection<T> items, String query,
                                        Function<T, List<String>> aliasGetter,
                                        Function<T, String> nameGetter) {
        var lowerQuery = query.toLowerCase().trim();
        int count = 0;
        for (var item : items) {
            var aliases = aliasGetter.apply(item);
            boolean matched = false;
            if (aliases != null) {
                for (var alias : aliases) {
                    if (alias.equalsIgnoreCase(query)) {
                        matched = true;
                        break;
                    }
                }
            }
            if (!matched && nameGetter.apply(item).equalsIgnoreCase(query)) {
                matched = true;
            }
            if (matched) count++;
        }
        return count;
    }
}
