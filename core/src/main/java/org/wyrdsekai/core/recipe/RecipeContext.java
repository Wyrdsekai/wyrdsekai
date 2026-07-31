package org.wyrdsekai.core.recipe;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mutable variable bag for a recipe run. Seeded with the run params
 * steps merge their outputs in (e.g. a SHELL step whose stdout is a JSON object). Gate
 * conditions and {@code {{ }}} templates resolve against it.
 */
public final class RecipeContext {

    private static final Pattern TEMPLATE = Pattern.compile("\\{\\{\\s*([\\w.]+)\\s*}}");

    private final Map<String, Object> vars = new LinkedHashMap<>();

    public RecipeContext() {}

    public RecipeContext(Map<String, Object> seed) {
        if (seed != null) vars.putAll(seed);
    }

    public void put(String key, Object value) { vars.put(key, value); }

    public void putAll(Map<String, Object> m) { if (m != null) vars.putAll(m); }

    public Object get(String key) { return vars.get(key); }

    public boolean has(String key) { return vars.containsKey(key); }

    public Map<String, Object> snapshot() { return new LinkedHashMap<>(vars); }

    /** Substitute {{var}} placeholders from the context; unknown vars are left as-is. */
    public String resolve(String template) {
        if (template == null || template.indexOf("{{") < 0) return template;
        Matcher m = TEMPLATE.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            Object v = vars.get(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(v == null ? m.group(0) : String.valueOf(v)));
        }
        m.appendTail(out);
        return out.toString();
    }
}
